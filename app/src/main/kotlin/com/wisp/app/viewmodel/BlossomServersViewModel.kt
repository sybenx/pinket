package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.Blossom
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.OwnListStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BlossomServersViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)
    val blossomRepo = BlossomRepository(app, keyRepo.getPubkeyHex())

    val servers: StateFlow<List<String>> = blossomRepo.servers

    private val _newServerUrl = MutableStateFlow("")
    val newServerUrl: StateFlow<String> = _newServerUrl

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** The active account's own-list store and the app's list writer (set by Navigation). */
    var ownListStore: () -> OwnListStore? = { null }
    var listWriter: () -> ListWriter? = { null }
    /** Load your newest stored version of a list into the app's settings again (set by Navigation). */
    var reproject: suspend (kind: Int) -> Unit = {}

    private val _published = MutableStateFlow(false)
    val published: StateFlow<Boolean> = _published

    /** Re-point prefs at the current user's file so flows pick up their server data. */
    fun reload() {
        blossomRepo.reload(keyRepo.getPubkeyHex())
    }

    fun refreshServers() {
        blossomRepo.refreshFromPrefs()
    }

    fun updateNewServerUrl(url: String) {
        _newServerUrl.value = url
    }

    fun addServer() {
        val raw = _newServerUrl.value.trim().trimEnd('/')
        if (raw.isBlank()) return
        if (raw.startsWith("http://")) {
            _error.value = "Only HTTPS servers are supported"
            return
        }
        val url = if (raw.startsWith("https://")) raw else "https://$raw"
        val current = servers.value.toMutableList()
        if (current.contains(url)) {
            _error.value = "Server already added"
            return
        }
        current.add(url)
        blossomRepo.saveBlossomServers(current)
        _newServerUrl.value = ""
        _error.value = null
    }

    fun removeServer(url: String) {
        val current = servers.value.toMutableList()
        current.remove(url)
        blossomRepo.saveBlossomServers(current)
    }

    fun moveServer(from: Int, to: Int) {
        val current = servers.value.toMutableList()
        if (from !in current.indices || to !in current.indices) return
        val item = current.removeAt(from)
        current.add(to, item)
        blossomRepo.saveBlossomServers(current)
    }

    /**
     * Save the media server list: the servers added or removed since your newest version (with changes still
     * waiting laid over it) are written as one change to the newest version found on your write relays, then the
     * order shown is applied to it. Never a whole-list replace; unknown tags stay where they are. Refusals and "will
     * save later" are shown by the list writer.
     */
    fun publishServerList(signer: NostrSigner? = null) {
        if (signer == null && keyRepo.getKeypair() == null) return
        val w = listWriter() ?: return
        val store = ownListStore() ?: return
        _published.value = false
        if (!RelayListEdits.inStep(store, Blossom.KIND_SERVER_LIST)) {
            // The rows shown were not loaded from your newest version: load it, let the user look, save again.
            viewModelScope.launch {
                reproject(Blossom.KIND_SERVER_LIST)
                refreshServers()
                _error.value = getApplication<android.app.Application>().getString(com.wisp.app.R.string.list_reloaded)
            }
            return
        }
        val shown = servers.value
        // Both sides read the way this screen reads them: entries it cannot show are never touched.
        val edits = RelayListEdits.serverList(RelayListEdits.startTags(store, Blossom.KIND_SERVER_LIST), shown)
        viewModelScope.launch {
            val expected = store.everSeen(Blossom.KIND_SERVER_LIST)
            val r = if (edits.isEmpty()) ListResult.Unchanged else w.edit(Blossom.KIND_SERVER_LIST, edits, expected = expected)
            val saved = when (r) {
                is ListResult.Sent, ListResult.Unchanged -> {
                    val order = w.reorder(Blossom.KIND_SERVER_LIST, shown.map { listOf("server", it) }, expected = expected)
                    r is ListResult.Sent || order is ListResult.Sent || order == ListResult.Unchanged
                }
                else -> false
            }
            _published.value = saved
            if (saved) _error.value = null
        }
    }
}
