package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.RelaySet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RelaySetRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val relaySets = mutableMapOf<String, RelaySet>()

    private val _ownRelaySets = MutableStateFlow<List<RelaySet>>(emptyList())
    val ownRelaySets: StateFlow<List<RelaySet>> = _ownRelaySets

    private val _favoriteRelays = MutableStateFlow<List<String>>(emptyList())
    val favoriteRelays: StateFlow<List<String>> = _favoriteRelays

    private var ownerPubkey: String? = null
    private var favoriteRelaysTimestamp: Long = 0

    /** Favourites as your newest version holds them (saved); [favoriteRelays] adds the pending changes. */
    private var baseFavorites: List<String> = emptyList()
    private var pendingFavorites: List<PendingChange> = emptyList()
    private var pendingSets: List<PendingChange> = emptyList()
    private var hiddenSets: Set<String> = emptySet()
    /** Relay sets being created (d to title), shown as empty sets until they load. */
    private var creatingSets: Map<String, String> = emptyMap()

    init {
        loadFromPrefs()
    }

    @Synchronized
    fun setOwner(pubkey: String) {
        ownerPubkey = pubkey
        refreshOwnSets()
    }

    @Synchronized
    fun updateRelaySetFromEvent(event: NostrEvent) {
        val set = Nip51.parseRelaySetNamed(event) ?: return
        val key = "${event.pubkey}:${set.dTag}"
        val existing = relaySets[key]
        if (existing != null && existing.createdAt >= set.createdAt) return
        relaySets[key] = set
        refreshOwnSets()
    }

    @Synchronized
    fun updateFavoriteRelaysFromEvent(event: NostrEvent) {
        if (event.created_at <= favoriteRelaysTimestamp) return
        favoriteRelaysTimestamp = event.created_at
        val urls = Nip51.parseRelaySet(event)
        baseFavorites = urls
        _favoriteRelays.value = applyPending(urls, pendingFavorites)
        saveFavoritesToPrefs(urls)
    }

    /** The app shows favourite relays loaded from a version of yours (saved here, pending changes not counted). */
    @Synchronized
    fun hasSavedFavorites(): Boolean = baseFavorites.isNotEmpty()

    fun getSet(pubkey: String, dTag: String): RelaySet? {
        return relaySets["$pubkey:$dTag"]
    }

    /** Favourite (shown, pending changes included). */
    fun isFavorite(url: String): Boolean = _favoriteRelays.value.any { norm(it) == norm(url) }

    /**
     * Lay list changes still waiting to be written (favourites 10012, relay sets 30002) and the relay sets hidden
     * here ("30002:d") over what your newest versions hold, so a tap shows at once. What is saved comes only from
     * signed versions ([updateFavoriteRelaysFromEvent], [updateRelaySetFromEvent]).
     */
    @Synchronized
    fun setOverlay(pending: List<PendingChange>, hidden: Set<String>) {
        pendingFavorites = pending.filter { it.kind == Nip51.KIND_FAVORITE_RELAYS && it.d == null }
        pendingSets = pending.filter { it.kind == Nip51.KIND_RELAY_SET && it.d != null && !it.creating }
        hiddenSets = hidden.filter { it.startsWith("${Nip51.KIND_RELAY_SET}:") }.toSet()
        creatingSets = SetOverlay.creating(pending, Nip51.KIND_RELAY_SET)
        _favoriteRelays.value = applyPending(baseFavorites, pendingFavorites)
        refreshOwnSets()
    }

    @Synchronized
    fun clear() {
        relaySets.clear()
        _ownRelaySets.value = emptyList()
        _favoriteRelays.value = emptyList()
        baseFavorites = emptyList()
        pendingFavorites = emptyList()
        pendingSets = emptyList()
        hiddenSets = emptySet()
        ownerPubkey = null
        favoriteRelaysTimestamp = 0
    }

    @Synchronized
    fun reload(pubkeyHex: String?) {
        clear()
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun refreshOwnSets() {
        val owner = ownerPubkey ?: return
        val own = relaySets.values.filter { it.pubkey == owner }.sortedBy { it.name }
        saveSetsToPrefs(own)
        val making = creatingSets.filterKeys { relaySets["$owner:$it"] == null }.map { (d, t) -> RelaySet(owner, d, t, emptySet(), 0) }
        _ownRelaySets.value = own
            .filter { "${Nip51.KIND_RELAY_SET}:${it.dTag}" !in hiddenSets }
            .map { set ->
                val mine = pendingSets.filter { it.d == set.dTag }
                if (mine.isEmpty()) set else set.copy(relays = applyPending(set.relays.toList(), mine).toSet())
            } + making
    }

    /** [urls] with the pending `relay` adds and removes laid over (compared in one spelling). */
    private fun applyPending(urls: List<String>, pending: List<PendingChange>): List<String> {
        var out = urls
        for (p in pending) {
            if (p.entry.size < 2 || (p.entry[0] != "relay" && p.entry[0] != "r")) continue
            val url = p.entry[1].trim().trimEnd('/')
            out = if (p.wanted) {
                if (out.any { norm(it) == norm(url) }) out else out + url
            } else out.filterNot { norm(it) == norm(url) }
        }
        return out
    }

    private fun saveSetsToPrefs(sets: List<RelaySet>) {
        val serializable = sets.map {
            SerializableRelaySet(it.pubkey, it.dTag, it.name, it.relays.toList(), it.createdAt)
        }
        prefs.edit().putString("relay_sets", json.encodeToString(serializable)).apply()
    }

    private fun saveFavoritesToPrefs(urls: List<String>) {
        prefs.edit().putString("favorite_relays", json.encodeToString(urls)).apply()
    }

    private fun loadFromPrefs() {
        val setsStr = prefs.getString("relay_sets", null)
        if (setsStr != null) {
            try {
                val serializable = json.decodeFromString<List<SerializableRelaySet>>(setsStr)
                for (s in serializable) {
                    val rs = RelaySet(s.pubkey, s.dTag, s.name, s.relays.toSet(), s.createdAt)
                    relaySets["${s.pubkey}:${s.dTag}"] = rs
                }
            } catch (_: Exception) {}
        }
        val favsStr = prefs.getString("favorite_relays", null)
        if (favsStr != null) {
            try {
                baseFavorites = json.decodeFromString<List<String>>(favsStr)
                _favoriteRelays.value = applyPending(baseFavorites, pendingFavorites)
            } catch (_: Exception) {}
        }
    }

    @Serializable
    private data class SerializableRelaySet(
        val pubkey: String,
        val dTag: String,
        val name: String,
        val relays: List<String>,
        val createdAt: Long
    )

    companion object {
        /** The d tag this app gives a relay set named [name]. */
        fun slug(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

        private fun norm(url: String): String = url.trim().lowercase().trimEnd('/')

        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_relay_sets_$pubkeyHex" else "wisp_relay_sets"
    }
}
