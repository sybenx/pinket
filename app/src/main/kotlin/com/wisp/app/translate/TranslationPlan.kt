package com.wisp.app.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What to do with a post on screen, once its language is known (or not). */
sealed interface TranslationPlan {
    /** In a language you read, too short to tell, or one that can't be translated: show nothing. */
    data object Nothing : TranslationPlan
    /** Sure, and it can translate without downloading anything: translate now. */
    data class Auto(val from: String) : TranslationPlan
    /** Sure, but a tap is needed (a model to download, or auto-translate is off): "Translate from Spanish". */
    data class Hint(val from: String) : TranslationPlan
    /** Unsure: "Translate from…" with the likeliest few. */
    data class Pick(val likeliest: List<String>) : TranslationPlan

    companion object {
        /**
         * [guess] for a post that isn't yours. [reads]: languages you read (normalized). [ready]: it can translate
         * from that language without a download. [supported]: it can translate from that language at all.
         */
        fun decide(
            guess: PostLanguage.Guess,
            reads: Set<String>,
            autoTranslate: Boolean,
            supported: (String) -> Boolean,
            ready: (String) -> Boolean
        ): TranslationPlan {
            val from = guess.lang
            if (from != null) {
                if (from in reads || !supported(from)) return Nothing
                return if (autoTranslate && ready(from)) Auto(from) else Hint(from)
            }
            val likely = guess.likeliest.filter(supported)
            // Unsure and maybe in a language you read: say nothing.
            if (likely.isEmpty() || likely.any { it in reads }) return Nothing
            return Pick(likely)
        }

        /** The guess to use: the post's tag, else the author's remembered language, else the detector's (if any). */
        suspend fun guess(
            tagged: String?,
            remembered: String?,
            text: String,
            detect: suspend (cleaned: String) -> List<Pair<String, Float>>
        ): PostLanguage.Guess {
            (tagged ?: remembered)?.let { return PostLanguage.Guess.known(it) }
            val cleaned = PostLanguage.clean(text)
            if (!PostLanguage.guessable(cleaned)) return PostLanguage.Guess.NONE
            return PostLanguage.decide(detect(cleaned))
        }
    }
}

/** The language you picked for someone's posts when the guess was unsure. Saved as a small JSON object. */
class AuthorLanguages(saved: String? = null) {
    private val map = LinkedHashMap<String, String>()

    init {
        if (!saved.isNullOrBlank()) try {
            Json.parseToJsonElement(saved).jsonObject.forEach { (k, v) ->
                v.jsonPrimitive.contentOrNull?.let { map[k] = PostLanguage.normalize(it) }
            }
        } catch (_: Exception) {}
    }

    @Synchronized fun get(pubkey: String): String? = map[pubkey]

    @Synchronized fun set(pubkey: String, lang: String) {
        map.remove(pubkey)
        map[pubkey] = PostLanguage.normalize(lang)
        while (map.size > MAX) map.remove(map.keys.first())
    }

    @Synchronized fun toJson(): String = JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString()

    companion object { const val MAX = 2000 }
}

/** The last [max] translations (post id → text and source language), oldest dropped first. Saved as JSON. */
class TranslationCache(private val max: Int = 500) {
    data class Entry(val text: String, val from: String)

    private val map = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized fun get(id: String): Entry? = map[id]

    @Synchronized fun put(id: String, entry: Entry) {
        map[id] = entry
        while (map.size > max) map.remove(map.keys.first())
    }

    @Synchronized fun size() = map.size

    @Synchronized fun toJson(): String = JsonObject(map.mapValues { (_, e) ->
        JsonObject(mapOf("text" to JsonPrimitive(e.text), "from" to JsonPrimitive(e.from)))
    }).toString()

    @Synchronized fun load(json: String) {
        try {
            Json.parseToJsonElement(json).jsonObject.forEach { (k, v) ->
                val o = v.jsonObject
                val text = o["text"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val from = o["from"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                map[k] = Entry(text, from)
            }
            while (map.size > max) map.remove(map.keys.first())
        } catch (_: Exception) {}
    }
}

/** Your own LibreTranslate's address, as typed: https only; no scheme gets https; plain http is refused. */
object ServerAddress {
    sealed interface Result {
        data class Ok(val url: String) : Result
        data object Empty : Result
        data object PlainHttp : Result
    }

    fun parse(input: String): Result {
        val t = input.trim().trimEnd('/')
        return when {
            t.isEmpty() -> Result.Empty
            t.startsWith("https://", ignoreCase = true) -> Result.Ok(t)
            t.startsWith("http://", ignoreCase = true) -> Result.PlainHttp
            "://" in t -> Result.PlainHttp
            else -> Result.Ok("https://$t")
        }
    }
}
