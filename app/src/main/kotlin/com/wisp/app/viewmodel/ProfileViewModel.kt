package com.wisp.app.viewmodel

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.NipA3
import com.wisp.app.nostr.ProfileContent
import com.wisp.app.R
import com.wisp.app.util.Monero
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.nostr.toHex
import com.wisp.app.repo.KeyRepository
import com.wisp.app.ui.util.GifToMp4Converter
import com.wisp.app.ui.util.MediaCompressor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ProfileViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)
    private val blossomRepo = BlossomRepository(app, keyRepo.getPubkeyHex())

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name

    private val _about = MutableStateFlow("")
    val about: StateFlow<String> = _about

    private val _picture = MutableStateFlow("")
    val picture: StateFlow<String> = _picture

    private val _nip05 = MutableStateFlow("")
    val nip05: StateFlow<String> = _nip05

    private val _banner = MutableStateFlow("")
    val banner: StateFlow<String> = _banner

    private val _lud16 = MutableStateFlow("")
    val lud16: StateFlow<String> = _lud16

    private val _publishing = MutableStateFlow(false)
    val publishing: StateFlow<Boolean> = _publishing

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun updateName(value: String) { _name.value = value }
    fun updateAbout(value: String) { _about.value = value }
    fun updatePicture(value: String) { _picture.value = value }
    fun updateNip05(value: String) { _nip05.value = value }
    fun updateBanner(value: String) { _banner.value = value }
    fun updateLud16(value: String) { _lud16.value = value }

    private val _uploading = MutableStateFlow<String?>(null)
    val uploading: StateFlow<String?> = _uploading

    private var refreshJob: Job? = null

    fun uploadImage(contentResolver: ContentResolver, uri: Uri, target: ImageTarget, signer: NostrSigner? = null) {
        viewModelScope.launch {
            try {
                _uploading.value = when (target) {
                    ImageTarget.PICTURE -> "Uploading avatar..."
                    ImageTarget.BANNER -> "Uploading banner..."
                }
                val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("Cannot read file")
                val srcMime = contentResolver.getType(uri) ?: "application/octet-stream"
                val (bytes, mime, ext) = when {
                    srcMime == "image/gif" -> GifToMp4Converter.convert(raw, getApplication())
                    srcMime.startsWith("image/") -> MediaCompressor.compressForProfile(raw, srcMime).asTriple()
                    else -> Triple(raw, srcMime, MimeTypeMap.getSingleton().getExtensionFromMimeType(srcMime) ?: "bin")
                }
                val url = blossomRepo.uploadMedia(bytes, mime, ext, signer)
                when (target) {
                    ImageTarget.PICTURE -> _picture.value = url
                    ImageTarget.BANNER -> _banner.value = url
                }
                _uploading.value = null
            } catch (e: Exception) {
                _error.value = "Upload failed: ${e.message}"
                _uploading.value = null
            }
        }
    }

    enum class ImageTarget { PICTURE, BANNER }

    // ── Monero address (NIP-A3 kind 10133) ──────────────────────────────────

    private val _moneroAddress = MutableStateFlow("")
    /** Editor field for the owner's Monero address. Empty means none. */
    val moneroAddress: StateFlow<String> = _moneroAddress

    fun updateMoneroAddress(value: String) { _moneroAddress.value = value }

    /** True when the field is empty or holds a valid address. Drive the field's error state with this. */
    fun isMoneroAddressValid(value: String = _moneroAddress.value): Boolean {
        val normalized = Monero.normalizeAddress(value) ?: return true
        return Monero.isValidAddress(normalized)
    }

    private var eventRepoRef: EventRepository? = null
    /** Newest kind 0 seen by the editor's own fetch; base for the merge on save. */
    private var latestProfileEvent: NostrEvent? = null
    /** What the Monero field was filled with, so a late fetch does not overwrite typing. */
    private var loadedMoneroAddress: String = ""
    /** Becomes true once a relay finished answering the editor's fetch (EOSE). */
    private val selfFetchDone = MutableStateFlow(false)
    private var selfFetchStarted = false
    private var eoseJob: Job? = null

    private fun applyProfile(profile: com.wisp.app.nostr.ProfileData) {
        _name.value = profile.displayName?.ifBlank { null } ?: profile.name ?: ""
        _about.value = profile.about ?: ""
        _picture.value = profile.picture ?: ""
        _nip05.value = profile.nip05 ?: ""
        _banner.value = profile.banner ?: ""
        _lud16.value = profile.lud16 ?: ""
    }

    private fun applyPayTargets(eventRepo: EventRepository, pubkeyHex: String) {
        val entry = eventRepo.getPayTargets(pubkeyHex)?.let { NipA3.firstMoneroEntry(it.tags) } ?: ""
        // Only replace the field while the person has not edited it.
        if (_moneroAddress.value == loadedMoneroAddress) _moneroAddress.value = entry
        loadedMoneroAddress = entry
    }

    fun loadCurrentProfile(eventRepo: EventRepository, relayPool: RelayPool? = null) {
        val pubkeyHex = keyRepo.getPubkeyHex() ?: return
        eventRepoRef = eventRepo
        latestProfileEvent = null
        loadedMoneroAddress = ""
        _moneroAddress.value = ""

        // Load from cache immediately
        val profile = eventRepo.getProfileData(pubkeyHex)
        if (profile != null) applyProfile(profile)
        applyPayTargets(eventRepo, pubkeyHex)

        // Request fresh profile and payment targets from relays
        if (relayPool == null) return
        startSelfFetch(eventRepo, relayPool, pubkeyHex)
    }

    private fun startSelfFetch(eventRepo: EventRepository, relayPool: RelayPool, pubkeyHex: String) {
        val subId = "editprofile"
        relayPool.closeOnAllRelays(subId)
        selfFetchDone.value = false
        selfFetchStarted = true

        // Subscribe to EOSE and events before sending so a fast relay is not missed.
        eoseJob?.cancel()
        eoseJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            relayPool.eoseSignals.first { it == subId }
            selfFetchDone.value = true
        }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            relayPool.relayEventsFor { it == subId }.collect { (event, _, subscriptionId) ->
                if (subscriptionId != subId || event.pubkey != pubkeyHex) return@collect
                when (event.kind) {
                    0 -> {
                        if (event.created_at > (latestProfileEvent?.created_at ?: -1)) latestProfileEvent = event
                        eventRepo.addEvent(event)
                        val updated = eventRepo.getProfileData(pubkeyHex) ?: return@collect
                        applyProfile(updated)
                    }
                    NipA3.KIND_PAYMENT_TARGETS -> {
                        eventRepo.cacheEvent(event)
                        applyPayTargets(eventRepo, pubkeyHex)
                    }
                }
            }
        }

        val filters = listOf(
            Filter(kinds = listOf(0), authors = listOf(pubkeyHex), limit = 1),
            Filter(kinds = listOf(NipA3.KIND_PAYMENT_TARGETS), authors = listOf(pubkeyHex), limit = 1)
        )
        relayPool.sendToAll(ClientMessage.req(subId, filters))
    }

    /**
     * Waits (bounded) for the editor's fetch so saving starts from the newest
     * kind 0 and kind 10133. Returns true if at least one relay finished answering.
     */
    private suspend fun awaitSelfFetch(relayPool: RelayPool, pubkeyHex: String): Boolean {
        val repo = eventRepoRef
        if (!selfFetchStarted && repo != null) startSelfFetch(repo, relayPool, pubkeyHex)
        if (selfFetchDone.value) return true
        val done = withTimeoutOrNull(SELF_FETCH_TIMEOUT_MS) { selfFetchDone.first { it } } != null
        // Give slower relays a moment to deliver a newer copy after the first EOSE.
        if (done) delay(SELF_FETCH_SETTLE_MS)
        return done
    }

    /**
     * Publishes the profile. Returns false without publishing when the Monero
     * field holds an invalid address or nobody is logged in. Otherwise the work
     * continues in the background: it waits briefly for the newest profile and
     * payment list, publishes kind 0 starting from the existing content (unknown
     * fields survive), then publishes kind 10133 only if the Monero entry changed.
     */
    fun publishProfile(relayPool: RelayPool, signer: NostrSigner? = null): Boolean {
        val s = signer ?: keyRepo.getKeypair()?.let { LocalSigner(it.privkey, it.pubkey) }
        if (s == null) {
            _error.value = "Not logged in"
            return false
        }
        val pubkeyHex = s.pubkeyHex
        val desiredMonero = Monero.normalizeAddress(_moneroAddress.value)
        if (desiredMonero != null && !Monero.isValidAddress(desiredMonero)) {
            _error.value = getApplication<Application>().getString(R.string.profile_monero_address_invalid)
            return false
        }
        val edits = ProfileContent.editorEdits(
            name = _name.value, about = _about.value, picture = _picture.value,
            nip05 = _nip05.value, banner = _banner.value, lud16 = _lud16.value
        )

        _publishing.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val fetched = awaitSelfFetch(relayPool, pubkeyHex)
                val repo = eventRepoRef

                // Kind 0: newest raw content we can find, then overwrite edited fields only.
                val base = listOfNotNull(latestProfileEvent, repo?.newestCachedEvent(0, pubkeyHex))
                    .maxByOrNull { it.created_at }
                val content = ProfileContent.merge(base?.content, edits)
                val profileEvent = s.signEvent(kind = 0, content = content)
                relayPool.sendToWriteRelays(ClientMessage.event(profileEvent))

                // Kind 10133: change only the Monero entry of the newest list.
                val existing = repo?.getPayTargets(pubkeyHex)
                val cachedEvent = repo?.newestCachedEvent(NipA3.KIND_PAYMENT_TARGETS, pubkeyHex)
                val existingTags: List<List<String>>?
                val existingContent: String
                if (cachedEvent != null && cachedEvent.created_at > (existing?.createdAt ?: -1)) {
                    existingTags = cachedEvent.tags; existingContent = cachedEvent.content
                } else {
                    existingTags = existing?.tags; existingContent = existing?.content ?: ""
                }
                val newTags = NipA3.planMoneroUpdate(existingTags, desiredMonero)
                if (newTags != null) {
                    if (existingTags == null && !fetched) {
                        // Could not confirm there is no existing list; publishing now
                        // could wipe other payment entries.
                        _error.value = getApplication<Application>().getString(R.string.profile_monero_not_saved)
                    } else {
                        val payEvent = s.signEvent(
                            kind = NipA3.KIND_PAYMENT_TARGETS,
                            content = existingContent,
                            tags = newTags
                        )
                        relayPool.sendToWriteRelays(ClientMessage.event(payEvent))
                        repo?.cacheEvent(payEvent)
                        loadedMoneroAddress = desiredMonero ?: ""
                    }
                }
            } catch (e: Exception) {
                _error.value = "Failed to publish: ${e.message}"
            } finally {
                _publishing.value = false
            }
        }
        return true
    }

    companion object {
        private const val SELF_FETCH_TIMEOUT_MS = 6_000L
        private const val SELF_FETCH_SETTLE_MS = 800L
    }
}
