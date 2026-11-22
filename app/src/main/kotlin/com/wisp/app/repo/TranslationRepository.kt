package com.wisp.app.repo

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.translate.AuthorLanguages
import com.wisp.app.translate.LibreTranslateClient
import com.wisp.app.translate.OnDeviceTranslator
import com.wisp.app.translate.PostLanguage
import com.wisp.app.translate.ServerAddress
import com.wisp.app.translate.TranslationCache
import com.wisp.app.translate.TranslationPlan
import com.wisp.app.translate.TranslationPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class TranslationStatus {
    IDLE,
    IDENTIFYING_LANGUAGE,
    DOWNLOADING_MODEL,
    TRANSLATING,
    DONE,
    ERROR,
    SAME_LANGUAGE,
    /** Sure of the language, a tap away: "Translate from Spanish". */
    HINT,
    /** Unsure of the language: "Translate from…" with [TranslationState.likeliest]. */
    PICK
}

data class TranslationState(
    val status: TranslationStatus = TranslationStatus.IDLE,
    val translatedText: String = "",
    val sourceLanguage: String = "",
    val targetLanguage: String = "",
    val errorMessage: String = "",
    /** The source language's code (HINT, DONE). */
    val sourceCode: String = "",
    /** PICK: the likeliest languages to offer. */
    val likeliest: List<String> = emptyList(),
    /** PICK from the ⋮ menu: open the choice at once. */
    val openPicker: Boolean = false
)

/** The app's one translation repository, for post cards on any screen. */
val LocalTranslationRepository = staticCompositionLocalOf<TranslationRepository?> { null }

/**
 * Translating posts: language from the post's NIP-32 tag, a language remembered for its author, or a guess from
 * its words (ML Kit, and your own LibreTranslate when the phone isn't sure). Posts on screen that aren't in a
 * language you read are translated at once when that language is on the phone (and auto-translate is on, the
 * default), or get a one-tap hint that downloads it. A model is never downloaded without a tap.
 */
class TranslationRepository(
    context: Context,
    private val myPubkey: () -> String? = { null }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val states = ConcurrentHashMap<String, TranslationState>()
    private val observations = KeyedObservation<String, TranslationState>(this, ::getState)

    val prefs = TranslationPreferences(context)
    private val cache = TranslationCache(500)
    private val cacheFile = File(context.applicationContext.filesDir, "translations.json")
    private var saveJob: Job? = null
    private val authors by lazy { AuthorLanguages(prefs.authorLanguagesJson()) }

    /** Posts already looked at (by auto-translate setting), so scrolling back doesn't redo work. */
    private val looked = ConcurrentHashMap.newKeySet<String>()
    /** A fast scroll doesn't start dozens of detections and translations at once. */
    private val work = Semaphore(3)
    @Volatile private var models: Set<String>? = null
    /** Posts showing a "Translate from X" hint, by id: once X is downloaded, they translate by themselves. */
    private val hinted = ConcurrentHashMap<String, NostrEvent>()
    @Volatile private var autoOn = true

    /** Coarse global counter for screens (bookmarks, thread, search...) that re-snapshot
     *  all visible translations at once. Feed screens use [stateFor] instead. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    init {
        scope.launch(Dispatchers.IO) {
            try {
                if (cacheFile.exists()) cache.load(cacheFile.readText())
            } catch (_: Exception) {}
            observations.publishAll()
            _version.value++
        }
    }

    fun stateFor(eventId: String): Flow<TranslationState> =
        observations.observe(eventId).distinctUntilChanged()

    fun getState(eventId: String): TranslationState =
        states[eventId] ?: cache.get(eventId)?.let { done(it.text, it.from) } ?: TranslationState()

    private fun publish(eventId: String, state: TranslationState) {
        synchronized(this) {
            states[eventId] = state
            observations.publish(eventId)
            _version.value++
        }
    }

    private fun done(text: String, from: String) = TranslationState(
        status = TranslationStatus.DONE,
        translatedText = text,
        sourceLanguage = OnDeviceTranslator.name(from),
        targetLanguage = OnDeviceTranslator.name(TranslationPreferences.target()),
        sourceCode = from
    )

    private fun serverUrl(): String =
        (ServerAddress.parse(prefs.serverUrl()) as? ServerAddress.Result.Ok)?.url ?: ""

    private fun serverOn() = LibreTranslateClient.usable(serverUrl())

    private suspend fun downloadedModels(): Set<String> =
        models ?: try { OnDeviceTranslator.downloaded().also { models = it } } catch (_: Exception) { emptySet() }

    private suspend fun guessFor(event: NostrEvent): PostLanguage.Guess {
        val guess = TranslationPlan.guess(
            tagged = PostLanguage.tagged(event.tags),
            remembered = authors.get(event.pubkey),
            text = event.content,
            detect = { cleaned -> OnDeviceTranslator.candidates(cleaned) }
        )
        if (guess.sure || guess.likeliest.isEmpty() || !serverOn()) return guess
        // The phone isn't sure: a second opinion from your server, if it has a clear one.
        val second = LibreTranslateClient.detect(serverUrl(), prefs.serverKey(), PostLanguage.clean(event.content))
            ?.let { PostLanguage.decide(it) }
        return if (second?.sure == true) second else guess
    }

    private suspend fun translateText(text: String, from: String, download: Boolean): String? {
        val target = TranslationPreferences.target()
        if (from == target) return null
        val url = serverUrl()
        if (LibreTranslateClient.usable(url)) {
            val (protectedText, kept) = PostLanguage.protect(text)
            LibreTranslateClient.translate(url, prefs.serverKey(), protectedText, from, target)
                ?.let { return PostLanguage.restore(it, kept) }
        }
        return OnDeviceTranslator.translate(text, from, target, download).also { if (download) models = null }
    }

    private fun keep(eventId: String, text: String, from: String) {
        cache.put(eventId, TranslationCache.Entry(text, from))
        publish(eventId, done(text, from))
        synchronized(this) {
            if (saveJob?.isActive == true) return
            saveJob = scope.launch(Dispatchers.IO) {
                delay(2000)
                try { cacheFile.writeText(cache.toJson()) } catch (_: Exception) {}
            }
        }
    }

    /**
     * A post on screen. Translates it at once when it's surely not in a language you read and nothing needs
     * downloading (and [autoTranslate] is on); else leaves a hint, or nothing. Call from the card's effect, so
     * a post that scrolls away stops its work.
     */
    suspend fun lookAt(event: NostrEvent, autoTranslate: Boolean) {
        if (event.content.isBlank() || event.pubkey == myPubkey()) return
        autoOn = autoTranslate
        // A hint is looked at again once auto-translate is on: its model may be there by now.
        fun open(s: TranslationStatus) = s == TranslationStatus.IDLE || (autoTranslate && s == TranslationStatus.HINT)
        if (!open(getState(event.id).status)) return
        val key = "${event.id}:$autoTranslate"
        if (!looked.add(key)) return
        try {
            work.withPermit {
                withContext(Dispatchers.Default) {
                    if (!open(getState(event.id).status)) return@withContext
                    val guess = guessFor(event)
                    val target = TranslationPreferences.target()
                    val server = serverOn()
                    val have = if (guess.sure && !server) downloadedModels() else emptySet()
                    val plan = TranslationPlan.decide(
                        guess = guess,
                        reads = prefs.reads() + target,
                        autoTranslate = autoTranslate,
                        supported = OnDeviceTranslator::supported,
                        ready = { server || OnDeviceTranslator.ready(it, target, have) }
                    )
                    when (plan) {
                        TranslationPlan.Nothing -> {}
                        is TranslationPlan.Auto -> {
                            val out = translateText(event.content, plan.from, download = false)
                            if (out != null) keep(event.id, out, plan.from) else { hinted[event.id] = event; publish(event.id, hint(plan.from)) }
                        }
                        is TranslationPlan.Hint -> { hinted[event.id] = event; publish(event.id, hint(plan.from)) }
                        is TranslationPlan.Pick -> publish(event.id, TranslationState(
                            status = TranslationStatus.PICK, likeliest = plan.likeliest
                        ))
                    }
                }
            }
        } catch (e: CancellationException) {
            looked.remove(key)
            throw e
        } catch (_: Exception) {
            // Detection or translation failed quietly: the ⋮ menu still offers Translate.
        }
    }

    private fun hint(from: String) = TranslationState(
        status = TranslationStatus.HINT,
        sourceCode = from,
        sourceLanguage = OnDeviceTranslator.name(from)
    )

    /** The ⋮ menu's Translate: the same detection; unsure, it asks which language instead of guessing. */
    fun translate(event: NostrEvent) {
        val current = getState(event.id)
        when (current.status) {
            TranslationStatus.IDENTIFYING_LANGUAGE, TranslationStatus.DOWNLOADING_MODEL,
            TranslationStatus.TRANSLATING, TranslationStatus.DONE -> return
            TranslationStatus.HINT -> return translateFrom(event, current.sourceCode, remember = false)
            TranslationStatus.PICK -> return publish(event.id, current.copy(openPicker = true))
            else -> {}
        }
        publish(event.id, TranslationState(status = TranslationStatus.IDENTIFYING_LANGUAGE))
        scope.launch {
            val guess = try { guessFor(event) } catch (_: Exception) { PostLanguage.Guess.NONE }
            val from = guess.lang
            when {
                from == null -> publish(event.id, TranslationState(
                    status = TranslationStatus.PICK,
                    likeliest = guess.likeliest.filter(OnDeviceTranslator::supported),
                    openPicker = true
                ))
                from == TranslationPreferences.target() -> publish(event.id, TranslationState(
                    status = TranslationStatus.SAME_LANGUAGE,
                    sourceLanguage = OnDeviceTranslator.name(from),
                    sourceCode = from
                ))
                else -> translateFrom(event, from, remember = false)
            }
        }
    }

    /** The picker was shown; don't open it again by itself. */
    fun pickerShown(eventId: String) {
        val s = states[eventId] ?: return
        if (s.openPicker) publish(eventId, s.copy(openPicker = false))
    }

    /**
     * A tap: translate [event] from [from], downloading that language once if needed. [remember]: use it for
     * this author's future posts (a pick from "Translate from…").
     */
    fun translateFrom(event: NostrEvent, from: String, remember: Boolean) {
        val lang = PostLanguage.normalize(from)
        if (remember) {
            authors.set(event.pubkey, lang)
            prefs.setAuthorLanguagesJson(authors.toJson())
        }
        val name = OnDeviceTranslator.name(lang)
        val previous = getState(event.id)
        publish(event.id, TranslationState(status = TranslationStatus.TRANSLATING, sourceLanguage = name, sourceCode = lang))
        scope.launch {
            try {
                val target = TranslationPreferences.target()
                if (!serverOn() && !OnDeviceTranslator.ready(lang, target, downloadedModels())) {
                    publish(event.id, TranslationState(status = TranslationStatus.DOWNLOADING_MODEL, sourceLanguage = name, sourceCode = lang))
                }
                val out = translateText(event.content, lang, download = true)
                hinted.remove(event.id)
                if (out != null) { keep(event.id, out, lang); translateHinted(lang) }
                else if (lang == TranslationPreferences.target()) publish(event.id, TranslationState(
                    status = TranslationStatus.SAME_LANGUAGE, sourceLanguage = name, sourceCode = lang
                ))
                else fail(event.id, previous)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fail(event.id, previous)
            }
        }
    }

    /** [lang] is on the phone now: posts already hinting it translate by themselves (when auto-translate is on). */
    private fun translateHinted(lang: String) {
        if (!autoOn) return
        val waiting = hinted.entries.filter { states[it.key]?.let { st -> st.status == TranslationStatus.HINT && st.sourceCode == lang } == true }
        for ((id, ev) in waiting) {
            hinted.remove(id)
            scope.launch {
                work.withPermit {
                    try {
                        translateText(ev.content, lang, download = false)?.let { keep(id, it, lang) }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                }
            }
        }
        if (hinted.size > 500) hinted.keys.take(hinted.size - 500).forEach { hinted.remove(it) }
    }

    private fun fail(eventId: String, previous: TranslationState) {
        publish(eventId, TranslationState(status = TranslationStatus.ERROR, errorMessage = "Couldn't translate this right now."))
        // Leave the hint or choice in place to try again.
        if (previous.status == TranslationStatus.HINT || previous.status == TranslationStatus.PICK) {
            scope.launch { delay(2500); if (states[eventId]?.status == TranslationStatus.ERROR) publish(eventId, previous.copy(openPicker = false)) }
        }
    }
}
