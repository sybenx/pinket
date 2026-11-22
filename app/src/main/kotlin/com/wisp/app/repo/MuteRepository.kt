package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.viewmodel.ListWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Who and what you mute. Read only as far as your mute list (kind 10000) goes: the projection loads from your newest
 * version that the own-list store took, public tags and the private part together, so mutes other apps stored either
 * way count here too. Writes go through ListWriter; its pending wanted states are laid over the projection
 * ([setPending]).
 *
 * Two things are local only and never written to the list:
 * - mutes made on this device that the list does not have yet ([blockLocally], [muteWordLocally]): they hide at once,
 *   whatever happens to the write, and are dropped once a version of the list has them or you unmute;
 * - muted threads.
 */
class MuteRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    /** Muted people as shown: the list, local mutes and pending changes. */
    private val _blockedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    val blockedPubkeys: StateFlow<Set<String>> = _blockedPubkeys

    /** Muted words as shown, in their stored spelling. */
    private val _mutedWords = MutableStateFlow<Set<String>>(emptySet())
    val mutedWords: StateFlow<Set<String>> = _mutedWords

    private val _mutedThreads = MutableStateFlow<Set<String>>(emptySet())
    val mutedThreads: StateFlow<Set<String>> = _mutedThreads

    /** From your newest mute list: public tags plus the private part. */
    private var listPubkeys = LinkedHashSet<String>()
    private var listWords = LinkedHashSet<String>()
    /** The private part as last read: kept when a newer version's private part cannot be read. */
    private var privPubkeys = LinkedHashSet<String>()
    private var privWords = LinkedHashSet<String>()
    /** Mutes made here that the list does not have (yet). */
    private var localPubkeys = LinkedHashSet<String>()
    private var localWords = LinkedHashSet<String>()
    private var threadSet = HashSet<String>()
    private var lastUpdated: Long = 0

    /** Pending wanted states from ListWriter (lowercase pubkey / lowercase word to wanted). */
    private var pendingPubkeys: Map<String, Boolean> = emptyMap()
    private var pendingWords: Map<String, Boolean> = emptyMap()

    @Volatile private var shownPubkeysLower: Set<String> = emptySet()
    @Volatile private var shownWordsLower: Set<String> = emptySet()

    init {
        loadFromPrefs()
    }

    /**
     * Load a version the own-list store took as your newest, without a signer: the public tags, and the private part
     * as last read.
     */
    @Synchronized
    fun loadFromEvent(event: NostrEvent) {
        if (event.kind != Nip51.KIND_MUTE_LIST) return
        apply(event, null)
    }

    /** Load a version the own-list store took as your newest, public tags and the private part. */
    suspend fun loadFromEvent(event: NostrEvent, signer: NostrSigner) {
        if (event.kind != Nip51.KIND_MUTE_LIST) return
        val priv = readPrivate(event, signer)
        synchronized(this) { apply(event, priv) }
    }

    /** For when no own-list store is loaded: load only a version newer than the one shown. */
    suspend fun loadFromEventIfNewer(event: NostrEvent, signer: NostrSigner?) {
        if (event.kind != Nip51.KIND_MUTE_LIST) return
        if (event.created_at <= synchronized(this) { lastUpdated }) return
        val priv = signer?.let { readPrivate(event, it) }
        synchronized(this) {
            if (event.created_at <= lastUpdated) return
            apply(event, priv)
        }
    }

    /** [priv]: the decrypted private part, or null when it could not be read (the last read one is kept). */
    private fun apply(event: NostrEvent, priv: List<List<String>>?) {
        if (priv != null || event.content.isBlank()) {
            val p = priv.orEmpty()
            privPubkeys = p.filter { it.size >= 2 && it[0] == "p" }.mapTo(LinkedHashSet()) { it[1] }
            privWords = p.filter { it.size >= 2 && it[0] == "word" }.mapTo(LinkedHashSet()) { it[1] }
        }
        val pub = event.tags
        listPubkeys = LinkedHashSet<String>().apply {
            pub.filter { it.size >= 2 && it[0] == "p" }.forEach { add(it[1]) }
            addAll(privPubkeys)
        }
        listWords = LinkedHashSet<String>().apply {
            pub.filter { it.size >= 2 && it[0] == "word" }.forEach { add(it[1]) }
            addAll(privWords)
        }
        // A local mute the list now has is no longer needed.
        val inList = listPubkeys.mapTo(HashSet()) { it.lowercase() }
        localPubkeys.removeAll { it.lowercase() in inList }
        val wordsInList = listWords.mapTo(HashSet()) { it.lowercase() }
        localWords.removeAll { it.lowercase() in wordsInList }
        // Never lowered: kept as the legacy watermark (mute_updated) the own-list store is seeded from.
        lastUpdated = maxOf(lastUpdated, event.created_at)
        publish()
        saveToPrefs()
    }

    private suspend fun readPrivate(event: NostrEvent, signer: NostrSigner): List<List<String>>? {
        if (event.content.isBlank()) return emptyList()
        return try {
            val plain = if (event.content.contains("?iv=")) signer.nip04Decrypt(event.content, signer.pubkeyHex)
            else signer.nip44Decrypt(event.content, signer.pubkeyHex)
            ListWriter.parsePrivate(plain)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** ListWriter's pending wanted states for mutes: people (pubkey to wanted) and words (word to wanted). */
    @Synchronized
    fun setPending(pubkeys: Map<String, Boolean>, words: Map<String, Boolean>) {
        val p = pubkeys.mapKeys { it.key.lowercase() }
        val w = words.mapKeys { it.key.lowercase() }
        if (p == pendingPubkeys && w == pendingWords) return
        pendingPubkeys = p
        pendingWords = w
        publish()
    }

    /** Hide this person on this device at once (the list write is separate). */
    @Synchronized
    fun blockLocally(pubkey: String) {
        if (listPubkeys.none { it.equals(pubkey, ignoreCase = true) } &&
            localPubkeys.none { it.equals(pubkey, ignoreCase = true) }) localPubkeys.add(pubkey)
        publish()
        saveToPrefs()
    }

    /** Drop this device's own mute of the person (the list write is separate). */
    @Synchronized
    fun unblockLocally(pubkey: String) {
        localPubkeys.removeAll { it.equals(pubkey, ignoreCase = true) }
        publish()
        saveToPrefs()
    }

    @Synchronized
    fun muteWordLocally(word: String) {
        if (listWords.none { it.equals(word, ignoreCase = true) } &&
            localWords.none { it.equals(word, ignoreCase = true) }) localWords.add(word)
        publish()
        saveToPrefs()
    }

    /** Any spelling of the word. */
    @Synchronized
    fun unmuteWordLocally(word: String) {
        localWords.removeAll { it.equals(word, ignoreCase = true) }
        publish()
        saveToPrefs()
    }

    fun isBlocked(pubkey: String): Boolean = pubkey.lowercase() in shownPubkeysLower

    fun containsMutedWord(content: String): Boolean {
        val words = shownWordsLower
        if (words.isEmpty()) return false
        val lower = content.lowercase()
        return words.any { lower.contains(it) }
    }

    @Synchronized
    fun muteThread(rootEventId: String) {
        threadSet.add(rootEventId)
        _mutedThreads.value = threadSet.toSet()
        saveToPrefs()
    }

    @Synchronized
    fun unmuteThread(rootEventId: String) {
        threadSet.remove(rootEventId)
        _mutedThreads.value = threadSet.toSet()
        saveToPrefs()
    }

    fun isThreadMuted(rootEventId: String): Boolean = _mutedThreads.value.contains(rootEventId)

    fun getBlockedPubkeys(): Set<String> = _blockedPubkeys.value

    fun getMutedWords(): Set<String> = _mutedWords.value

    @Synchronized
    fun clear(clearPersisted: Boolean = true) {
        listPubkeys = LinkedHashSet()
        listWords = LinkedHashSet()
        privPubkeys = LinkedHashSet()
        privWords = LinkedHashSet()
        localPubkeys = LinkedHashSet()
        localWords = LinkedHashSet()
        threadSet = HashSet()
        lastUpdated = 0
        _mutedThreads.value = emptySet()
        // Pending stays: it is ListWriter's overlay, which follows the active account on its own.
        publish()
        if (clearPersisted) prefs.edit().clear().apply()
    }

    @Synchronized
    fun reload(pubkeyHex: String?) {
        clear(clearPersisted = false)
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    /** List, local mutes and pending changes: what is shown and filtered on. */
    private fun publish() {
        _blockedPubkeys.value = overlay(listPubkeys + localPubkeys, pendingPubkeys)
        _mutedWords.value = overlay(listWords + localWords, pendingWords)
        shownPubkeysLower = _blockedPubkeys.value.mapTo(HashSet()) { it.lowercase() }
        shownWordsLower = _mutedWords.value.mapTo(HashSet()) { it.lowercase() }
    }

    private fun overlay(base: Set<String>, pending: Map<String, Boolean>): Set<String> {
        val shown = LinkedHashSet<String>()
        val present = HashSet<String>()
        for (v in base) {
            val l = v.lowercase()
            if (pending[l] == false || !present.add(l)) continue
            shown.add(v)
        }
        for ((v, want) in pending) if (want && present.add(v)) shown.add(v)
        return shown
    }

    private fun saveToPrefs() {
        prefs.edit()
            .putStringSet("blocked_pubkeys", listPubkeys.toSet())
            .putStringSet("muted_words", listWords.toSet())
            .putStringSet("private_pubkeys", privPubkeys.toSet())
            .putStringSet("private_words", privWords.toSet())
            .putStringSet("local_pubkeys", localPubkeys.toSet())
            .putStringSet("local_words", localWords.toSet())
            .putStringSet("muted_threads", threadSet.toSet())
            .putLong("mute_updated", lastUpdated)
            .apply()
    }

    private fun loadFromPrefs() {
        lastUpdated = prefs.getLong("mute_updated", 0)
        prefs.getStringSet("blocked_pubkeys", null)?.let { listPubkeys = LinkedHashSet(it) }
        prefs.getStringSet("muted_words", null)?.let { listWords = LinkedHashSet(it) }
        // Before this version the private part was not kept apart: the whole list stands in for it.
        privPubkeys = LinkedHashSet(prefs.getStringSet("private_pubkeys", null) ?: listPubkeys)
        privWords = LinkedHashSet(prefs.getStringSet("private_words", null) ?: listWords)
        prefs.getStringSet("local_pubkeys", null)?.let { localPubkeys = LinkedHashSet(it) }
        prefs.getStringSet("local_words", null)?.let { localWords = LinkedHashSet(it) }
        prefs.getStringSet("muted_threads", null)?.let { threadSet = HashSet(it) }
        _mutedThreads.value = threadSet.toSet()
        publish()
    }

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_mutes_$pubkeyHex" else "wisp_mutes"
    }
}
