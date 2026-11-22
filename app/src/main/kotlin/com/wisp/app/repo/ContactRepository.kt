package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.Nip02
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Your follow list (kind 3), read only. The projection loads from your newest version that the own-list store took
 * (a fetched or signed event); nothing here changes it locally. Writes go through ListWriter, and its pending wanted
 * states are laid over the projection ([setPending]) so a follow button flips at once.
 */
class ContactRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** Follows as shown: the projection with ListWriter's pending changes laid over it. */
    private val _followList = MutableStateFlow<List<Nip02.FollowEntry>>(emptyList())
    val followList: StateFlow<List<Nip02.FollowEntry>> = _followList

    private var projection: List<Nip02.FollowEntry> = emptyList()
    @Volatile private var followSet: Set<String> = emptySet()
    private var lastUpdated: Long = 0

    /** Pending wanted states from ListWriter, by pubkey (lowercase). */
    private var pending: Map<String, Boolean> = emptyMap()

    init {
        loadFromPrefs()
    }

    /**
     * Load a version the own-list store took as your newest (it decides what is newest, including same-second ties),
     * so there is no time check here.
     */
    @Synchronized
    fun loadFromEvent(event: NostrEvent) {
        if (event.kind != 3) return
        apply(event)
    }

    /** For when no own-list store is loaded: load only a version newer than the one shown. */
    @Synchronized
    fun loadFromEventIfNewer(event: NostrEvent) {
        if (event.kind != 3) return
        if (event.created_at <= lastUpdated) return
        apply(event)
    }

    private fun apply(event: NostrEvent) {
        projection = Nip02.parseFollowList(event)
        // Never lowered: kept as the legacy watermark (follows_updated) the own-list store is seeded from.
        lastUpdated = maxOf(lastUpdated, event.created_at)
        publish()
        saveToPrefs(projection)
    }

    /**
     * ListWriter's pending wanted states for follows (pubkey to wanted). A pending follow shows as followed and a
     * pending unfollow as not, until the write is confirmed (the projection then has it) or refused (it flips back).
     */
    @Synchronized
    fun setPending(wanted: Map<String, Boolean>) {
        val next = wanted.mapKeys { it.key.lowercase() }
        if (next == pending) return
        pending = next
        publish()
    }

    /** Followed as shown (pending changes included). */
    fun isFollowing(pubkey: String): Boolean = pubkey.lowercase() in followSet

    /** Follows as shown (pending changes included). */
    fun getFollowList(): List<Nip02.FollowEntry> = _followList.value

    @Synchronized
    fun clear(clearPersisted: Boolean = true) {
        projection = emptyList()
        lastUpdated = 0
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

    private fun publish() {
        val shown = if (pending.isEmpty()) projection else {
            val out = projection.filter { pending[it.pubkey.lowercase()] != false }.toMutableList()
            val present = out.mapTo(HashSet()) { it.pubkey.lowercase() }
            for ((pk, want) in pending) if (want && present.add(pk)) out.add(Nip02.FollowEntry(pk))
            out
        }
        followSet = shown.mapTo(HashSet()) { it.pubkey.lowercase() }
        _followList.value = shown
    }

    private fun saveToPrefs(entries: List<Nip02.FollowEntry>) {
        val serializable = entries.map { SerializableFollow(it.pubkey, it.relayHint, it.petname) }
        prefs.edit()
            .putString("follows", json.encodeToString(serializable))
            .putLong("follows_updated", lastUpdated)
            .apply()
    }

    private fun loadFromPrefs() {
        lastUpdated = prefs.getLong("follows_updated", 0)
        val str = prefs.getString("follows", null)
        if (str != null) {
            try {
                val serializable = json.decodeFromString<List<SerializableFollow>>(str)
                projection = serializable.map { Nip02.FollowEntry(it.pubkey, it.relayHint, it.petname) }
            } catch (_: Exception) {}
        }
        publish()
    }

    @Serializable
    private data class SerializableFollow(
        val pubkey: String,
        val relayHint: String? = null,
        val petname: String? = null
    )

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_contacts_$pubkeyHex" else "wisp_contacts"
    }
}
