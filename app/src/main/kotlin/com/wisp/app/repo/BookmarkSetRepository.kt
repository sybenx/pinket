package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.BookmarkSet
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Bookmark sets (kind 30003). Your own load from the version the own-list store took as newest (and from ListWriter
 * after it signs); writes go through ListWriter only. What is shown ([ownSets], [allListedEventIds], [getSet]) has
 * ListWriter's pending changes laid over it and leaves out the sets hidden here.
 */
class BookmarkSetRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val bookmarkSets = mutableMapOf<String, BookmarkSet>()

    private val _ownSets = MutableStateFlow<List<BookmarkSet>>(emptyList())
    val ownSets: StateFlow<List<BookmarkSet>> = _ownSets

    private val _allListedEventIds = MutableStateFlow<Set<String>>(emptySet())
    val allListedEventIds: StateFlow<Set<String>> = _allListedEventIds

    private var ownerPubkey: String? = null

    /** ListWriter's pending changes to your bookmark sets, by d. */
    private var pending: Map<String, List<PendingChange>> = emptyMap()
    /** Sets hidden on this device ("30003:d"). */
    private var hidden: Set<String> = emptySet()
    /** Sets being created (d to title), shown as empty sets until they load. */
    private var creating: Map<String, String> = emptyMap()

    init {
        loadFromPrefs()
    }

    @Synchronized
    fun setOwner(pubkey: String) {
        ownerPubkey = pubkey
        refreshOwnSets()
    }

    /** ListWriter's pending changes (any kind; only bookmark sets are used) and the sets hidden here ("kind:d"). */
    @Synchronized
    fun setOverlay(pendingChanges: List<PendingChange>, hiddenKeys: Set<String>) {
        val p = SetOverlay.byD(pendingChanges, Nip51.KIND_BOOKMARK_SET)
        val h = hiddenKeys.filterTo(HashSet()) { it.startsWith("${Nip51.KIND_BOOKMARK_SET}:") }
        val c = SetOverlay.creating(pendingChanges, Nip51.KIND_BOOKMARK_SET)
        if (p == pending && h == hidden && c == creating) return
        pending = p
        hidden = h
        creating = c
        refreshOwnSets()
    }

    private fun shown(set: BookmarkSet): BookmarkSet {
        if (set.pubkey != ownerPubkey) return set
        val p = pending[set.dTag] ?: return set
        return set.copy(
            name = SetOverlay.title(set.name, p),
            eventIds = SetOverlay.values(set.eventIds, "e", p),
            coordinates = SetOverlay.values(set.coordinates, "a", p),
            hashtags = SetOverlay.values(set.hashtags, "t", p)
        )
    }

    private fun isHidden(set: BookmarkSet): Boolean =
        set.pubkey == ownerPubkey && OwnListStore.key(Nip51.KIND_BOOKMARK_SET, set.dTag) in hidden

    @Synchronized
    fun updateFromEvent(event: NostrEvent, decryptedContent: String? = null) {
        val set = Nip51.parseBookmarkSet(event, decryptedContent) ?: return
        val key = "${event.pubkey}:${set.dTag}"
        val existing = bookmarkSets[key]
        if (existing != null && existing.createdAt >= set.createdAt) return
        bookmarkSets[key] = set
        refreshOwnSets()
    }

    /** The set as shown (pending changes included), hidden or not. */
    @Synchronized
    fun getSet(pubkey: String, dTag: String): BookmarkSet? {
        return bookmarkSets["$pubkey:$dTag"]?.let { shown(it) }
    }

    /** The set as last loaded, without pending changes (for deciding where a new entry goes). */
    @Synchronized
    fun getLoadedSet(pubkey: String, dTag: String): BookmarkSet? = bookmarkSets["$pubkey:$dTag"]

    @Synchronized
    fun getAllSetsForUser(pubkey: String): List<BookmarkSet> {
        return bookmarkSets.values.filter { it.pubkey == pubkey }
    }

    @Synchronized
    fun clear() {
        bookmarkSets.clear()
        _ownSets.value = emptyList()
        _allListedEventIds.value = emptySet()
        ownerPubkey = null
    }

    @Synchronized
    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun refreshOwnSets() {
        val owner = ownerPubkey ?: return
        val own = bookmarkSets.values.filter { it.pubkey == owner }.sortedBy { it.name }
        val making = creating.filterKeys { bookmarkSets["$owner:$it"] == null }.map { (d, t) -> BookmarkSet(owner, d, t, emptySet()) }
        val shownSets = (own.filterNot { isHidden(it) }.map { shown(it) } + making).sortedBy { it.name }
        _ownSets.value = shownSets
        _allListedEventIds.value = shownSets.flatMapTo(mutableSetOf()) { it.eventIds }
        // What was loaded, never the pending changes.
        saveToPrefs(own)
    }

    private fun saveToPrefs(sets: List<BookmarkSet>) {
        val serializable = sets.map {
            SerializableBookmarkSet(
                it.pubkey, it.dTag, it.name,
                it.eventIds.toList(), it.coordinates.toList(), it.hashtags.toList(),
                it.createdAt, it.isPrivate
            )
        }
        prefs.edit().putString("bookmark_sets", json.encodeToString(serializable)).apply()
    }

    private fun loadFromPrefs() {
        val str = prefs.getString("bookmark_sets", null) ?: return
        try {
            val serializable = json.decodeFromString<List<SerializableBookmarkSet>>(str)
            for (s in serializable) {
                val bs = BookmarkSet(
                    s.pubkey, s.dTag, s.name,
                    s.eventIds.toSet(), s.coordinates.toSet(), s.hashtags.toSet(),
                    s.createdAt, s.isPrivate
                )
                bookmarkSets["${s.pubkey}:${s.dTag}"] = bs
            }
        } catch (_: Exception) {}
    }

    @Serializable
    private data class SerializableBookmarkSet(
        val pubkey: String,
        val dTag: String,
        val name: String,
        val eventIds: List<String>,
        val coordinates: List<String>,
        val hashtags: List<String>,
        val createdAt: Long,
        val isPrivate: Boolean = false
    )

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_bookmark_sets_$pubkeyHex" else "wisp_bookmark_sets"
    }
}
