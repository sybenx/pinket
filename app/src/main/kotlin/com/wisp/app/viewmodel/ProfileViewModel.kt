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
    /** What the Monero field was filled with, to tell whether the person edited it. */
    private var loadedMoneroAddress: String = ""
    /**
     * Becomes true once the editor's fetch is conclusive: every write relay that was
     * connected when it started has finished answering (EOSE), and at least one relay
     * answered. Only then can a missing list or profile be trusted as missing.
     */
    private val selfFetchComplete = MutableStateFlow(false)
    private var selfFetchStarted = false
    private var selfFetchSubIds: List<String> = emptyList()
    private var selfFetchGeneration = 0L
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
        // Same address the pay button shows (first valid entry), so editor and button agree.
        val entry = eventRepo.getPayTargets(pubkeyHex)?.moneroAddress ?: ""
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
        selfFetchSubIds.forEach(relayPool::closeOnAllRelays)
        selfFetchComplete.value = false
        selfFetchStarted = true

        // One sub id for every relay (wide net for the newest copy), plus one per
        // connected write relay so we know when each of those has answered. The
        // account's own lists are published to its write relays.
        val writeSubs = relayPool.getWriteRelayUrls()
            .filter(relayPool::isRelayConnected)
            .mapIndexed { index, url -> url to "$SELF_SUB_PREFIX-w$index" }
            .filter { (url, subId) -> relayPool.subscriptionTracker.hasCapacity(url, subId) }
        val allSubIds = listOf(SELF_SUB_PREFIX) + writeSubs.map { it.second }
        selfFetchSubIds = allSubIds
        val generation = relayPool.reconnectGeneration
        selfFetchGeneration = generation

        // Subscribe to EOSE and events before sending so a fast relay is not missed.
        eoseJob?.cancel()
        eoseJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val waiting = writeSubs.map { it.second }.toMutableSet()
            var anyEose = false
            relayPool.eoseSignals.first { subId ->
                if (subId in allSubIds && relayPool.reconnectGeneration == generation) {
                    anyEose = true
                    waiting.remove(subId)
                }
                anyEose && waiting.isEmpty()
            }
            selfFetchComplete.value = true
        }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            relayPool.relayEventsFor { it in allSubIds }.collect { (event, _, subscriptionId) ->
                if (subscriptionId !in allSubIds || event.pubkey != pubkeyHex) return@collect
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
        relayPool.sendToAll(ClientMessage.req(SELF_SUB_PREFIX, filters))
        for ((url, subId) in writeSubs) relayPool.sendToRelay(url, ClientMessage.req(subId, filters))
    }

    /**
     * Waits (bounded) for the editor's fetch so saving starts from the newest
     * kind 0 and kind 10133. Returns true only if the fetch is conclusive (see
     * [selfFetchComplete]).
     */
    private suspend fun awaitSelfFetch(relayPool: RelayPool, pubkeyHex: String): Boolean {
        val repo = eventRepoRef
        if (selfFetchComplete.value) return true
        // Not started yet, or the connection reset since (EOSEs after that are ignored): ask again.
        val stale = selfFetchStarted && relayPool.reconnectGeneration != selfFetchGeneration
        if ((!selfFetchStarted || stale) && repo != null) startSelfFetch(repo, relayPool, pubkeyHex)
        val done = withTimeoutOrNull(SELF_FETCH_TIMEOUT_MS) { selfFetchComplete.first { it } } != null
        // Give other relays a moment to deliver a newer copy.
        if (done) delay(SELF_FETCH_SETTLE_MS)
        return done
    }

    /**
     * Publishes the profile. Returns false without publishing when the Monero
     * field was edited to an invalid address or nobody is logged in. Otherwise the
     * work continues in the background: it waits briefly for the newest profile and
     * payment list, publishes kind 0 starting from the existing content (unknown
     * fields survive), then publishes kind 10133 only if the person edited the
     * Monero field. When the existing profile or list could not be loaded, that
     * part is not published and [error] says so.
     */
    fun publishProfile(relayPool: RelayPool, signer: NostrSigner? = null): Boolean {
        val s = signer ?: keyRepo.getKeypair()?.let { LocalSigner(it.privkey, it.pubkey) }
        if (s == null) {
            _error.value = "Not logged in"
            return false
        }
        val pubkeyHex = s.pubkeyHex
        val desiredMonero = Monero.normalizeAddress(_moneroAddress.value)
        // Decide now: a list arriving during the wait below rewrites loadedMoneroAddress.
        val moneroEdited = desiredMonero != Monero.normalizeAddress(loadedMoneroAddress)
        if (moneroEdited && desiredMonero != null && !Monero.isValidAddress(desiredMonero)) {
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
                if (base == null && (repo?.getProfileData(pubkeyHex) != null || !fetched)) {
                    // A profile exists (or may exist) but its full content did not load.
                    // Publishing from nothing would drop fields this editor does not show.
                    _error.value = getApplication<Application>().getString(R.string.profile_not_saved)
                    return@launch
                }
                val content = ProfileContent.merge(base?.content, edits)
                val profileEvent = s.signEvent(kind = 0, content = content)
                relayPool.sendToWriteRelays(ClientMessage.event(profileEvent))

                // Kind 10133: only when the field was edited, and only the Monero entry.
                if (moneroEdited) publishMonero(relayPool, s, pubkeyHex, desiredMonero, fetched)
            } catch (e: Exception) {
                _error.value = "Failed to publish: ${e.message}"
            } finally {
                _publishing.value = false
            }
        }
        return true
    }

    private suspend fun publishMonero(
        relayPool: RelayPool,
        signer: NostrSigner,
        pubkeyHex: String,
        desiredMonero: String?,
        fetched: Boolean
    ) {
        if (!fetched) {
            // Could not confirm we have the newest list; publishing now could wipe
            // other payment entries or bring back an old one.
            _error.value = getApplication<Application>().getString(R.string.profile_monero_not_saved)
            return
        }
        val repo = eventRepoRef
        val existing = repo?.getPayTargets(pubkeyHex)
        val cachedEvent = repo?.newestCachedEvent(NipA3.KIND_PAYMENT_TARGETS, pubkeyHex)
        val existingTags: List<List<String>>?
        val existingContent: String
        if (cachedEvent != null && cachedEvent.created_at > (existing?.createdAt ?: -1)) {
            existingTags = cachedEvent.tags; existingContent = cachedEvent.content
        } else {
            existingTags = existing?.tags; existingContent = existing?.content ?: ""
        }
        val newTags = NipA3.planMoneroUpdate(existingTags, desiredMonero) ?: return
        val payEvent = signer.signEvent(
            kind = NipA3.KIND_PAYMENT_TARGETS,
            content = existingContent,
            tags = newTags
        )
        relayPool.sendToWriteRelays(ClientMessage.event(payEvent))
        repo?.cacheEvent(payEvent)
        loadedMoneroAddress = desiredMonero ?: ""
    }

    companion object {
        private const val SELF_FETCH_TIMEOUT_MS = 6_000L
        private const val SELF_FETCH_SETTLE_MS = 800L
        private const val SELF_SUB_PREFIX = "editprofile"
    }
}
