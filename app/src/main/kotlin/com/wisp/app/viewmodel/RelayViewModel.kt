package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.R
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.RelaySetType
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.OwnListStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RelayViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)
    var relayPool: RelayPool? = null

    /** The active account's own-list store (set by Navigation): what the editor's Broadcast starts from. */
    var ownListStore: () -> OwnListStore? = { null }

    /** The app's list writer (set by Navigation): the only one that signs your relay lists. */
    var listWriter: () -> ListWriter? = { null }

    /** Load your newest stored version of a list into the app's settings again (set by Navigation). */
    var reproject: suspend (kind: Int) -> Unit = {}

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    /** String ids for a short toast after Broadcast (saved, or nothing to save). Refusals are shown by the writer. */
    val messages: SharedFlow<Int> = _messages

    private val _selectedTab = MutableStateFlow(RelaySetType.GENERAL)
    val selectedTab: StateFlow<RelaySetType> = _selectedTab

    val relays: StateFlow<List<RelayConfig>> = keyRepo.relaysFlow
    val dmRelays: StateFlow<List<String>> = keyRepo.dmRelaysFlow
    val searchRelays: StateFlow<List<String>> = keyRepo.searchRelaysFlow
    val blockedRelays: StateFlow<List<String>> = keyRepo.blockedRelaysFlow

    /** Re-point prefs at the current user's file so flows pick up their relay data. */
    fun reload() {
        keyRepo.reloadPrefs(keyRepo.getPubkeyHex())
    }

    private val trendingPrefs = com.wisp.app.repo.TrendingPreferences(app)
    private val _trendingRelay = MutableStateFlow(trendingPrefs.getRelay())
    /** Ditto relay used for trending notes, people and onboarding topics. */
    val trendingRelay: StateFlow<String> = _trendingRelay

    /** Saves the trending relay; blank restores the default. False if the URL is not valid. */
    fun saveTrendingRelay(url: String): Boolean {
        if (!trendingPrefs.setRelay(url)) return false
        _trendingRelay.value = trendingPrefs.getRelay()
        return true
    }

    private val _newRelayUrl = MutableStateFlow("")
    val newRelayUrl: StateFlow<String> = _newRelayUrl

    fun selectTab(tab: RelaySetType) {
        _selectedTab.value = tab
        _newRelayUrl.value = ""
    }

    fun updateNewRelayUrl(value: String) {
        _newRelayUrl.value = value
    }

    fun addRelay(): Boolean {
        val url = _newRelayUrl.value.trim().trimEnd('/')
        if (url.isBlank()) return false
        if (!RelayConfig.isValidUrl(url)) return false

        when (_selectedTab.value) {
            RelaySetType.GENERAL -> {
                if (relays.value.any { it.url == url }) return false
                keyRepo.saveRelays(relays.value + RelayConfig(url))
            }
            RelaySetType.DM -> {
                // With no DM relay list anywhere the app listens on the default DM relays: adding one keeps them,
                // so your inbox does not move away from them without you seeing it.
                val shown = dmRelays.value.ifEmpty {
                    val me = keyRepo.getPubkeyHex()
                    if (me != null && keyRepo.isDmDefaultsInUse(me)) RelayConfig.DEFAULT_DM_RELAYS else emptyList()
                }
                if (url in shown) return false
                keyRepo.saveDmRelays(shown + url)
            }
            RelaySetType.SEARCH -> {
                if (url in searchRelays.value) return false
                keyRepo.saveSearchRelays(searchRelays.value + url)
            }
            RelaySetType.BLOCKED -> {
                if (url in blockedRelays.value) return false
                val updated = blockedRelays.value + url
                keyRepo.saveBlockedRelays(updated)
                relayPool?.updateBlockedUrls(updated)
            }
        }
        _newRelayUrl.value = ""
        return true
    }

    fun removeRelay(url: String) {
        when (_selectedTab.value) {
            RelaySetType.GENERAL -> {
                keyRepo.saveRelays(relays.value.filter { it.url != url })
            }
            RelaySetType.DM -> {
                keyRepo.saveDmRelays(dmRelays.value.filter { it != url })
            }
            RelaySetType.SEARCH -> {
                keyRepo.saveSearchRelays(searchRelays.value.filter { it != url })
            }
            RelaySetType.BLOCKED -> {
                val updated = blockedRelays.value.filter { it != url }
                keyRepo.saveBlockedRelays(updated)
                relayPool?.updateBlockedUrls(updated)
            }
        }
    }

    fun toggleRead(url: String) {
        val updated = relays.value.map {
            if (it.url == url) it.copy(read = !it.read) else it
        }
        keyRepo.saveRelays(updated)
    }

    fun toggleWrite(url: String) {
        val updated = relays.value.map {
            if (it.url == url) it.copy(write = !it.write) else it
        }
        keyRepo.saveRelays(updated)
    }

    fun toggleAuth(url: String) {
        val updated = relays.value.map {
            if (it.url == url) it.copy(auth = !it.auth) else it
        }
        keyRepo.saveRelays(updated)
    }

    /**
     * Broadcast the list of the selected tab: the rows the user added, removed or changed since your newest version
     * (with changes still waiting laid over it) are written as one change to the newest version found on your write
     * relays. Nothing else in the list changes, never a whole-list replace. Returns false when it cannot start (not
     * signed in); the result comes as a toast from the writer (refused, will save later) or [messages] (saved).
     */
    fun publishRelayList(): Boolean {
        val w = listWriter() ?: return false
        val store = ownListStore() ?: return false
        val tab = _selectedTab.value
        val kind = tab.eventKind
        if (!RelayListEdits.inStep(store, kind)) {
            // The rows were not loaded from your newest version (it arrived without reaching this screen): a diff
            // against it would take out relays the user never touched. Load it, let the user look, save again.
            viewModelScope.launch {
                reproject(kind)
                reload()
                _messages.tryEmit(R.string.list_reloaded)
            }
            return true
        }
        val edits = editsFor(tab, store)
        if (edits.isEmpty()) {
            _messages.tryEmit(R.string.list_no_changes)
            return true
        }
        w.submitEdit(kind, edits, expected = store.everSeen(kind)) { result ->
            if (result is ListResult.Sent) _messages.tryEmit(R.string.error_relay_broadcast)
        }
        return true
    }

    /** The per-row changes of [tab] between what your newest version holds and what the editor shows. */
    private fun editsFor(tab: RelaySetType, store: OwnListStore): List<ListEdit> {
        val start = RelayListEdits.startTags(store, tab.eventKind)
        return when (tab) {
            RelaySetType.GENERAL -> RelayListEdits.relayList(RelayListEdits.relaysOf(start), relays.value)
            RelaySetType.DM -> RelayListEdits.addressList("relay", RelayListEdits.addressesOf(tab.eventKind, start), dmRelays.value, start)
            RelaySetType.SEARCH -> RelayListEdits.addressList("relay", RelayListEdits.addressesOf(tab.eventKind, start), searchRelays.value, start)
            RelaySetType.BLOCKED -> RelayListEdits.addressList("relay", RelayListEdits.addressesOf(tab.eventKind, start), blockedRelays.value, start)
        }
    }
}
