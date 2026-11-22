package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.InterestSet
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Your interest sets (kind 30015). They load from the version the own-list store took as newest (and from ListWriter
 * after it signs); writes go through ListWriter only. What is shown ([sets] and every lookup here) has ListWriter's
 * pending changes laid over it (a set that is only pending, like a first hashtag follow, shows at once) and leaves out
 * the sets hidden here. A deleted set is a hidden one now: nothing here drops sets on its own.
 */
class InterestRepository(
    private val context: Context,
    private var pubkeyHex: String? = null
) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** What was loaded, by d. */
    private val interestSets = mutableMapOf<String, InterestSet>()

    /** ListWriter's pending changes to your interest sets, by d. */
    private var pending: Map<String, List<PendingChange>> = emptyMap()
    /** Sets hidden on this device ("30015:d"). */
    private var hidden: Set<String> = emptySet()
    /** Sets being created (d to title), shown as empty sets until they load. */
    private var creating: Map<String, String> = emptyMap()

    private val _sets = MutableStateFlow<List<InterestSet>>(emptyList())
    /** Your interest sets as shown. */
    val sets: StateFlow<List<InterestSet>> = _sets

    init {
        loadFromPrefs()
    }

    @Synchronized
    fun updateFromEvent(event: NostrEvent) {
        val set = Nip51.parseInterestSet(event) ?: return
        val existing = interestSets[set.dTag]
        if (existing != null && existing.createdAt >= set.createdAt) return
        interestSets[set.dTag] = set
        refreshSets()
    }

    /** ListWriter's pending changes (any kind; only interest sets are used) and the sets hidden here ("kind:d"). */
    @Synchronized
    fun setOverlay(pendingChanges: List<PendingChange>, hiddenKeys: Set<String>) {
        val p = SetOverlay.byD(pendingChanges, Nip51.KIND_INTEREST_SET)
        val h = hiddenKeys.filterTo(HashSet()) { it.startsWith("${Nip51.KIND_INTEREST_SET}:") }
        val c = SetOverlay.creating(pendingChanges, Nip51.KIND_INTEREST_SET)
        if (p == pending && h == hidden && c == creating) return
        pending = p
        hidden = h
        creating = c
        refreshSets()
    }

    /** The set as shown (pending changes included), hidden or not; a set that is only pending too. */
    @Synchronized
    fun getSet(dTag: String): InterestSet? = shownSet(dTag)

    /** The set as last loaded, without pending changes. */
    @Synchronized
    fun getLoadedSet(dTag: String): InterestSet? = interestSets[dTag]

    fun getAllHashtags(): Set<String> =
        _sets.value.flatMapTo(mutableSetOf()) { it.hashtags }

    fun isFollowing(hashtag: String): Boolean {
        val lower = hashtag.lowercase()
        return _sets.value.any { lower in it.hashtags }
    }

    fun findSetsContaining(hashtag: String): List<InterestSet> {
        val lower = hashtag.lowercase()
        return _sets.value.filter { lower in it.hashtags }
    }

    @Synchronized
    fun clear() {
        interestSets.clear()
        _sets.value = emptyList()
    }

    @Synchronized
    fun reload(pubkeyHex: String?) {
        clear()
        this.pubkeyHex = pubkeyHex
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun shownSet(dTag: String): InterestSet? {
        val loaded = interestSets[dTag] ?: creating[dTag]?.let { InterestSet(dTag, it, emptySet(), 0) }
        val p = pending[dTag] ?: return loaded
        val base = loaded ?: run {
            // Only pending: shown once something is being added (a first follow into a new set).
            if (p.none { it.wanted && it.entry.firstOrNull() == "t" }) return null
            InterestSet(dTag, p.firstNotNullOfOrNull { it.title?.takeIf { t -> t.isNotBlank() } } ?: dTag, emptySet(), 0)
        }
        return base.copy(
            name = SetOverlay.title(base.name, p),
            hashtags = SetOverlay.values(base.hashtags, "t", p).mapTo(LinkedHashSet()) { it.lowercase() }
        )
    }

    private fun refreshSets() {
        val ds = (interestSets.keys + pending.keys + creating.keys).distinct()
        _sets.value = ds
            .filter { OwnListStore.key(Nip51.KIND_INTEREST_SET, it) !in hidden }
            .mapNotNull { shownSet(it) }
            .sortedBy { it.name }
        // What was loaded, never the pending changes.
        saveToPrefs(interestSets.values.sortedBy { it.name })
    }

    private fun saveToPrefs(sets: List<InterestSet>) {
        val serializable = sets.map {
            SerializableInterestSet(it.dTag, it.name, it.hashtags.toList(), it.createdAt)
        }
        prefs.edit().putString("interest_sets", json.encodeToString(serializable)).apply()
    }

    private fun loadFromPrefs() {
        val str = prefs.getString("interest_sets", null) ?: return
        try {
            val serializable = json.decodeFromString<List<SerializableInterestSet>>(str)
            for (s in serializable) {
                interestSets[s.dTag] = InterestSet(s.dTag, s.name, s.hashtags.toSet(), s.createdAt)
            }
            _sets.value = interestSets.keys
                .filter { OwnListStore.key(Nip51.KIND_INTEREST_SET, it) !in hidden }
                .mapNotNull { shownSet(it) }
                .sortedBy { it.name }
        } catch (_: Exception) {}
    }

    @Serializable
    private data class SerializableInterestSet(
        val dTag: String,
        val name: String,
        val hashtags: List<String>,
        val createdAt: Long
    )

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_interests_$pubkeyHex" else "wisp_interests"
    }
}
