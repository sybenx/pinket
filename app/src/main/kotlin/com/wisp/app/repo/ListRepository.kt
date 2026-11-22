package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.FollowSet
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * People sets (kind 30000): yours and others' that were loaded. Your own sets load from the version the own-list store
 * took as newest (and from ListWriter after it signs); writes go through ListWriter only. What is shown ([ownLists],
 * [selectedList], [getList]) has ListWriter's pending changes laid over it and leaves out the sets hidden here.
 */
class ListRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val followSets = mutableMapOf<String, FollowSet>()

    private val _ownLists = MutableStateFlow<List<FollowSet>>(emptyList())
    val ownLists: StateFlow<List<FollowSet>> = _ownLists

    private val _selectedList = MutableStateFlow<FollowSet?>(null)
    val selectedList: StateFlow<FollowSet?> = _selectedList

    private var ownerPubkey: String? = null

    /** ListWriter's pending changes to your people sets, by d. */
    private var pending: Map<String, List<PendingChange>> = emptyMap()
    /** Sets hidden on this device ("30000:d"). */
    private var hidden: Set<String> = emptySet()
    /** Sets being created (d to title), shown as empty sets until they load. */
    private var creating: Map<String, String> = emptyMap()

    init {
        loadFromPrefs()
    }

    @Synchronized
    fun setOwner(pubkey: String) {
        ownerPubkey = pubkey
        refreshOwnLists()
    }

    /** ListWriter's pending changes (any kind; only people sets are used) and the sets hidden here ("kind:d"). */
    @Synchronized
    fun setOverlay(pendingChanges: List<PendingChange>, hiddenKeys: Set<String>) {
        val p = SetOverlay.byD(pendingChanges, Nip51.KIND_FOLLOW_SET)
        val h = hiddenKeys.filterTo(HashSet()) { it.startsWith("${Nip51.KIND_FOLLOW_SET}:") }
        val c = SetOverlay.creating(pendingChanges, Nip51.KIND_FOLLOW_SET)
        if (p == pending && h == hidden && c == creating) return
        pending = p
        hidden = h
        creating = c
        refreshOwnLists()
    }

    /** A set as shown: your own with pending changes laid over it, anyone else's as loaded. */
    private fun shown(fs: FollowSet): FollowSet {
        if (fs.pubkey != ownerPubkey) return fs
        val p = pending[fs.dTag] ?: return fs
        return fs.copy(name = SetOverlay.title(fs.name, p), members = SetOverlay.values(fs.members, "p", p))
    }

    private fun isHidden(fs: FollowSet): Boolean =
        fs.pubkey == ownerPubkey && OwnListStore.key(Nip51.KIND_FOLLOW_SET, fs.dTag) in hidden

    @Synchronized
    fun updateFromEvent(event: NostrEvent, decryptedContent: String? = null) {
        val followSet = Nip51.parseFollowSet(event, decryptedContent) ?: return
        val key = "${event.pubkey}:${followSet.dTag}"
        val existing = followSets[key]
        if (existing != null && existing.createdAt >= followSet.createdAt) return
        followSets[key] = followSet
        refreshOwnLists()
        // Update selected list if it matches
        val sel = _selectedList.value
        if (sel != null && sel.pubkey == followSet.pubkey && sel.dTag == followSet.dTag) {
            _selectedList.value = shown(followSet)
        }
    }

    fun selectList(followSet: FollowSet?) {
        _selectedList.value = followSet
    }

    /** Members as shown (pending changes included). */
    @Synchronized
    fun getListMembers(pubkey: String, dTag: String): Set<String> {
        return followSets["$pubkey:$dTag"]?.let { shown(it).members } ?: emptySet()
    }

    /** The set as shown (pending changes included), hidden or not. */
    @Synchronized
    fun getList(pubkey: String, dTag: String): FollowSet? {
        return followSets["$pubkey:$dTag"]?.let { shown(it) }
    }

    /** The set as last loaded, without pending changes (for deciding where a new entry goes). */
    @Synchronized
    fun getLoadedList(pubkey: String, dTag: String): FollowSet? = followSets["$pubkey:$dTag"]

    /** Stop using [dTag] as the feed when it is the selected list (it was hidden). */
    fun deselect(pubkey: String, dTag: String) {
        val sel = _selectedList.value
        if (sel != null && sel.pubkey == pubkey && sel.dTag == dTag) _selectedList.value = null
    }

    @Synchronized
    fun getAllListsForUser(pubkey: String): List<FollowSet> {
        return followSets.values.filter { it.pubkey == pubkey }
    }

    @Synchronized
    fun clear() {
        followSets.clear()
        _ownLists.value = emptyList()
        _selectedList.value = null
        ownerPubkey = null
    }

    @Synchronized
    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun refreshOwnLists() {
        val owner = ownerPubkey ?: return
        val own = followSets.values.filter { it.pubkey == owner }.sortedBy { it.name }
        val making = creating.filterKeys { followSets["$owner:$it"] == null }.map { (d, t) -> FollowSet(owner, d, t, emptySet(), 0) }
        _ownLists.value = (own.filterNot { isHidden(it) }.map { shown(it) } + making).sortedBy { it.name }
        val sel = _selectedList.value
        if (sel != null && sel.pubkey == owner) followSets["${sel.pubkey}:${sel.dTag}"]?.let { _selectedList.value = shown(it) }
        // What was loaded, never the pending changes.
        saveToPrefs(own)
    }

    private fun saveToPrefs(lists: List<FollowSet>) {
        val serializable = lists.map {
            SerializableFollowSet(it.pubkey, it.dTag, it.name, it.members.toList(), it.createdAt, it.isPrivate)
        }
        prefs.edit().putString("follow_sets", json.encodeToString(serializable)).apply()
    }

    private fun loadFromPrefs() {
        val str = prefs.getString("follow_sets", null) ?: return
        try {
            val serializable = json.decodeFromString<List<SerializableFollowSet>>(str)
            for (s in serializable) {
                val fs = FollowSet(s.pubkey, s.dTag, s.name, s.members.toSet(), s.createdAt, s.isPrivate)
                followSets["${s.pubkey}:${s.dTag}"] = fs
            }
        } catch (_: Exception) {}
    }

    @Serializable
    private data class SerializableFollowSet(
        val pubkey: String,
        val dTag: String,
        val name: String,
        val members: List<String>,
        val createdAt: Long,
        val isPrivate: Boolean = false
    )

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_lists_$pubkeyHex" else "wisp_lists"
    }
}
