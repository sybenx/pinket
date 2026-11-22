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
 * Your bookmarks (kind 10003), read only. Public tags and the private part count alike. The projection loads from your newest version that the own-list store took
 * (a fetched or signed event); nothing here changes it locally. Writes go through ListWriter, and its pending wanted
 * states are laid over the projection ([setPending]) so a button flips at once.
 */
class BookmarkRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    /** Bookmarked note ids: the projection with ListWriter's pending changes laid over it. */
    private val _bookmarkedIds = MutableStateFlow<Set<String>>(emptySet())
    val bookmarkedIds: StateFlow<Set<String>> = _bookmarkedIds

    private var idSet = HashSet<String>()
    private var coordinateSet = HashSet<String>()
    private var hashtagSet = HashSet<String>()
    /** The private part as last read (`e`, `a`, `t`): kept when a newer version's private part cannot be read. */
    private var privIds = HashSet<String>()
    private var privCoords = HashSet<String>()
    private var privTags = HashSet<String>()
    private var lastUpdated: Long = 0

    /** Pending wanted states from ListWriter, by note id as written in the change. */
    private var pending: Map<String, Boolean> = emptyMap()

    init {
        loadFromPrefs()
    }

    /**
     * Load a version the own-list store took as your newest (it decides what is newest, including same-second ties),
     * so there is no time check here. Without a signer: the public `e`, `a` and `t` tags, and the private part as
     * last read.
     */
    @Synchronized
    fun loadFromEvent(event: NostrEvent) {
        if (event.kind != Nip51.KIND_BOOKMARK_LIST) return
        apply(event, null)
    }

    /** Load a version the own-list store took as your newest, public tags and the private part. */
    suspend fun loadFromEvent(event: NostrEvent, signer: NostrSigner) {
        if (event.kind != Nip51.KIND_BOOKMARK_LIST) return
        val priv = readPrivate(event, signer)
        synchronized(this) { apply(event, priv) }
    }

    /** For when no own-list store is loaded: load only a version newer than the one shown. */
    @Synchronized
    fun loadFromEventIfNewer(event: NostrEvent) {
        if (event.kind != Nip51.KIND_BOOKMARK_LIST) return
        if (event.created_at <= lastUpdated) return
        apply(event, null)
    }

    /** [priv]: the decrypted private part, or null when it could not be read (the last read one is kept). */
    private fun apply(event: NostrEvent, priv: List<List<String>>?) {
        if (priv != null || event.content.isBlank()) {
            val p = priv.orEmpty().filter { it.size >= 2 }
            privIds = p.filter { it[0] == "e" }.mapTo(HashSet()) { it[1] }
            privCoords = p.filter { it[0] == "a" }.mapTo(HashSet()) { it[1] }
            privTags = p.filter { it[0] == "t" }.mapTo(HashSet()) { it[1] }
        }
        val bookmarkList = Nip51.parseBookmarkList(event)
        idSet = HashSet(bookmarkList.eventIds).apply { addAll(privIds) }
        coordinateSet = HashSet(bookmarkList.coordinates).apply { addAll(privCoords) }
        hashtagSet = HashSet(bookmarkList.hashtags).apply { addAll(privTags) }
        // Never lowered: kept as the legacy watermark (bookmark_updated) the own-list store is seeded from.
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

    /**
     * ListWriter's pending wanted states for bookmarks (note id to wanted). A pending add shows as bookmarked and a
     * pending remove as not, until the write is confirmed (the projection then has it) or refused (it flips back).
     */
    @Synchronized
    fun setPending(wanted: Map<String, Boolean>) {
        if (wanted == pending) return
        pending = wanted
        publish()
    }

    /** Bookmarked as shown (pending changes included). */
    fun isBookmarked(eventId: String): Boolean = eventId in _bookmarkedIds.value

    /** Bookmarked note ids as shown (pending changes included). */
    fun getBookmarkedIds(): Set<String> = _bookmarkedIds.value
    fun getCoordinates(): Set<String> = synchronized(this) { coordinateSet.toSet() }
    fun getHashtags(): Set<String> = synchronized(this) { hashtagSet.toSet() }

    @Synchronized
    fun clear() {
        idSet = HashSet()
        coordinateSet = HashSet()
        hashtagSet = HashSet()
        privIds = HashSet()
        privCoords = HashSet()
        privTags = HashSet()
        lastUpdated = 0
        // Pending stays: it is ListWriter's overlay, which follows the active account on its own.
        publish()
    }

    @Synchronized
    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    /** Projection plus pending: ids compare in lowercase, as ListWriter keys `e` entries. */
    private fun publish() {
        if (pending.isEmpty()) {
            _bookmarkedIds.value = idSet.toSet()
            return
        }
        val byLower = pending.entries.associate { it.key.lowercase() to it.value }
        val shown = LinkedHashSet<String>()
        for (id in idSet) if (byLower[id.lowercase()] != false) shown.add(id)
        val present = shown.mapTo(HashSet()) { it.lowercase() }
        for ((id, want) in pending) if (want && id.lowercase() !in present) {
            shown.add(id)
            present.add(id.lowercase())
        }
        _bookmarkedIds.value = shown
    }

    private fun saveToPrefs() {
        prefs.edit()
            .putStringSet("bookmark_ids", idSet.toSet())
            .putStringSet("bookmark_coords", coordinateSet.toSet())
            .putStringSet("bookmark_hashtags", hashtagSet.toSet())
            .putStringSet("private_ids", privIds.toSet())
            .putStringSet("private_coords", privCoords.toSet())
            .putStringSet("private_hashtags", privTags.toSet())
            .putLong("bookmark_updated", lastUpdated)
            .apply()
    }

    private fun loadFromPrefs() {
        lastUpdated = prefs.getLong("bookmark_updated", 0)
        val ids = prefs.getStringSet("bookmark_ids", null)
        if (ids != null) idSet = HashSet(ids)
        val coords = prefs.getStringSet("bookmark_coords", null)
        if (coords != null) coordinateSet = HashSet(coords)
        val tags = prefs.getStringSet("bookmark_hashtags", null)
        if (tags != null) hashtagSet = HashSet(tags)
        prefs.getStringSet("private_ids", null)?.let { privIds = HashSet(it) }
        prefs.getStringSet("private_coords", null)?.let { privCoords = HashSet(it) }
        prefs.getStringSet("private_hashtags", null)?.let { privTags = HashSet(it) }
        publish()
    }

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_bookmarks_$pubkeyHex" else "wisp_bookmarks"
    }
}
