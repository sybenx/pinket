package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Your pinned notes (kind 10001), read only. The projection loads from your newest version that the own-list store
 * took (a fetched or signed event); nothing here changes it locally. Writes go through ListWriter, and its pending
 * wanted states are laid over the projection ([setPending]) so a pin button flips at once.
 */
class PinRepository(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)

    /** Pinned note ids: the projection with ListWriter's pending changes laid over it. */
    private val _pinnedIds = MutableStateFlow<Set<String>>(emptySet())
    val pinnedIds: StateFlow<Set<String>> = _pinnedIds

    private var idSet = LinkedHashSet<String>()
    private var lastUpdated: Long = 0

    /** Pending wanted states from ListWriter, by note id (lowercase). */
    private var pending: Map<String, Boolean> = emptyMap()

    init {
        loadFromPrefs()
    }

    /** Load a version the own-list store took as your newest (it decides what is newest), so no time check here. */
    @Synchronized
    fun loadFromEvent(event: NostrEvent) {
        if (event.kind != Nip51.KIND_PIN_LIST) return
        apply(event)
    }

    /** For when no own-list store is loaded: load only a version newer than the one shown. */
    @Synchronized
    fun loadFromEventIfNewer(event: NostrEvent) {
        if (event.kind != Nip51.KIND_PIN_LIST) return
        if (event.created_at <= lastUpdated) return
        apply(event)
    }

    private fun apply(event: NostrEvent) {
        idSet = LinkedHashSet(Nip51.parsePinList(event))
        // Never lowered: kept as the legacy watermark (pin_updated) the own-list store is seeded from.
        lastUpdated = maxOf(lastUpdated, event.created_at)
        publish()
        saveToPrefs()
    }

    /** ListWriter's pending wanted states for pins (note id to wanted). */
    @Synchronized
    fun setPending(wanted: Map<String, Boolean>) {
        val next = wanted.mapKeys { it.key.lowercase() }
        if (next == pending) return
        pending = next
        publish()
    }

    /** Pinned as shown (pending changes included). */
    fun isPinned(eventId: String): Boolean = eventId in _pinnedIds.value || eventId.lowercase() in _pinnedIds.value

    /** Pinned ids as shown (pending changes included). */
    fun getPinnedIds(): Set<String> = _pinnedIds.value

    @Synchronized
    fun clear() {
        idSet = LinkedHashSet()
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

    private fun publish() {
        if (pending.isEmpty()) {
            _pinnedIds.value = idSet.toSet()
            return
        }
        val shown = LinkedHashSet<String>()
        for (id in idSet) if (pending[id.lowercase()] != false) shown.add(id)
        val present = shown.mapTo(HashSet()) { it.lowercase() }
        for ((id, want) in pending) if (want && present.add(id)) shown.add(id)
        _pinnedIds.value = shown
    }

    private fun saveToPrefs() {
        prefs.edit()
            .putStringSet("pinned_ids", idSet.toSet())
            .putLong("pin_updated", lastUpdated)
            .apply()
    }

    private fun loadFromPrefs() {
        lastUpdated = prefs.getLong("pin_updated", 0)
        val ids = prefs.getStringSet("pinned_ids", null)
        if (ids != null) idSet = LinkedHashSet(ids)
        publish()
    }

    companion object {
        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "wisp_pins_$pubkeyHex" else "wisp_pins"
    }
}
