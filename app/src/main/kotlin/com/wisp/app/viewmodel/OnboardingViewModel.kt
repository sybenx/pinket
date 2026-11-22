package com.wisp.app.viewmodel

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.Nip02
import com.wisp.app.nostr.Nip65
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.ProfileData
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.OnboardingPhase
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.RelayProber
import com.wisp.app.nostr.Nip78
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.WalletModeRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class SuggestionSection(
    val profiles: List<ProfileData> = emptyList(),
    val isLoading: Boolean = true
)

enum class SectionType { ACTIVE_NOW, CREATORS, NEWS }

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)

    /**
     * Set by Navigation (FeedViewModel.listWriter): the only allowed signer of the follow list. During onboarding the
     * app wiring may still point at no account, so pass `account = openListAccount(app, signer)` per call.
     */
    var listWriter: ListWriter? = null
    private val blossomRepo = BlossomRepository(app)

    // Relay discovery state (background)
    private val _phase = MutableStateFlow(OnboardingPhase.DISCOVERING)
    val phase: StateFlow<OnboardingPhase> = _phase

    private val _discoveredRelays = MutableStateFlow<List<RelayConfig>?>(null)
    val discoveredRelays: StateFlow<List<RelayConfig>?> = _discoveredRelays

    private val _probingUrl = MutableStateFlow<String?>(null)
    val probingUrl: StateFlow<String?> = _probingUrl

    // Profile form state (foreground)
    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name

    private val _about = MutableStateFlow("")
    val about: StateFlow<String> = _about

    private val _picture = MutableStateFlow("")
    val picture: StateFlow<String> = _picture

    private val _uploading = MutableStateFlow<String?>(null)
    val uploading: StateFlow<String?> = _uploading

    private val _publishing = MutableStateFlow(false)
    val publishing: StateFlow<Boolean> = _publishing

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Suggestion sections
    private val _activeNow = MutableStateFlow(SuggestionSection())
    val activeNow: StateFlow<SuggestionSection> = _activeNow

    private val _creators = MutableStateFlow(SuggestionSection())
    val creators: StateFlow<SuggestionSection> = _creators

    private val _news = MutableStateFlow(SuggestionSection())
    val news: StateFlow<SuggestionSection> = _news

    private val _selectedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    val selectedPubkeys: StateFlow<Set<String>> = _selectedPubkeys

    private var suggestionsJob: Job? = null

    companion object {
        private const val TAG = "OnboardingSuggestions"
        private const val WISP_RELAY_URL = "wss://relay.wisp.talk"
        val CREATOR_PUBKEYS = listOf(
            "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d", // fiatjaf
            "e2ccf7cf20403f3f2a4a55b328f0de3be38558a7d5f33632fdaaefc726c1c8eb"  // utxo
        )
        private val ACTIVE_RELAYS = listOf("wss://premium.primal.net", "wss://nostr.wine", "wss://relay.wisp.talk", "wss://pyramid.fiatjaf.com")
        private const val NEWS_RELAY = "wss://news.utxo.one"
    }

    fun updateName(value: String) { _name.value = value }
    fun updateAbout(value: String) { _about.value = value }

    fun uploadImage(contentResolver: ContentResolver, uri: Uri, signer: NostrSigner? = null) {
        viewModelScope.launch {
            try {
                _uploading.value = "Uploading..."
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("Cannot read file")
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
                val url = blossomRepo.uploadMedia(bytes, mimeType, ext, signer)
                _picture.value = url
                _uploading.value = null
            } catch (e: Exception) {
                _error.value = "Upload failed: ${e.message}"
                _uploading.value = null
            }
        }
    }

    fun startDiscovery(walletModeRepo: WalletModeRepository? = null) {
        // Reset state from any previous onboarding run (ViewModel survives logout)
        suggestionsJob?.cancel()
        suggestionsJob = null
        _activeNow.value = SuggestionSection()
        _creators.value = SuggestionSection()
        _news.value = SuggestionSection()
        _selectedPubkeys.value = emptySet()
        _publishing.value = false
        _error.value = null

        // Use real keypair if available, otherwise generate a throwaway for probing
        val keypair = keyRepo.getKeypair() ?: Keys.generate()
        val pubHex = keyRepo.getPubkeyHex() ?: keypair.pubkey.toHex()
        keyRepo.reloadPrefs(pubHex)
        blossomRepo.reload(pubHex)

        // Reload the wallet mode repo so it reads this pubkey's prefs
        walletModeRepo?.reload(pubHex)

        viewModelScope.launch {
            val relays = RelayProber.discoverAndSelect(
                keypair = keypair,
                onPhase = { phase -> _phase.value = phase },
                onProbing = { url -> _probingUrl.value = url }
            )
            _probingUrl.value = null
            _discoveredRelays.value = relays
        }
    }

    /**
     * Finish profile step: save relays, init relay pool, publish kind 0.
     * Returns true if successful.
     */
    suspend fun finishProfile(
        relayPool: RelayPool,
        signer: NostrSigner? = null
    ): Boolean {
        val s = signer ?: keyRepo.getKeypair()?.let { LocalSigner(it.privkey, it.pubkey) } ?: return false
        val discovered = _discoveredRelays.value ?: RelayConfig.DEFAULTS
        // Always include Wisp's own relay in the new account's NIP-65 list,
        // regardless of what relay probing discovered. Read+write so the new
        // user both publishes to and reads from it from day one.
        val relays = if (discovered.any { it.url.equals(WISP_RELAY_URL, ignoreCase = true) }) {
            discovered
        } else {
            discovered + RelayConfig(WISP_RELAY_URL, read = true, write = true)
        }

        return try {
            _publishing.value = true

            keyRepo.saveRelays(relays)
            relayPool.updateRelays(relays)

            _phase.value = OnboardingPhase.BROADCASTING

            writeFirstRelayList(relayPool, s, relays)

            writeFirstProfile(s)

            _publishing.value = false
            true
        } catch (e: Exception) {
            _error.value = "Failed: ${e.message}"
            _publishing.value = false
            false
        }
    }

    /** The profile fields filled in here (blank ones left out). */
    private fun firstProfileFields(): Map<String, String> = buildMap {
        if (_name.value.isNotBlank()) put("name", _name.value)
        if (_about.value.isNotBlank()) put("about", _about.value)
        if (_picture.value.isNotBlank()) put("picture", _picture.value)
    }

    /**
     * Your profile from onboarding, through the list writer and in the background (the step never waits for it):
     * written only when you have no profile anywhere. An existing key that lands here keeps the profile it has.
     */
    private fun writeFirstProfile(signer: NostrSigner) {
        val w = listWriter ?: return
        val fields = firstProfileFields()
        if (fields.isEmpty()) return
        val account = openListAccount(getApplication(), signer)
        viewModelScope.launch {
            // The relays were only just set up: when none could be reached yet, try again a little later (a short
            // message only when the last try does not go through).
            for (attempt in 0 until 3) {
                if (attempt > 0) kotlinx.coroutines.delay(5_000)
                val result = try {
                    w.setProfileText(fields, onlyIfMissing = true, account = account, quiet = attempt < 2)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Onboarding", "profile failed", e)
                    return@launch
                }
                Log.d("Onboarding", "profile: $result")
                if (!(result is ListResult.Refused && result.reason == ListResult.Reason.NOT_SENT)) break
            }
        }
    }

    /**
     * Your relay list from onboarding, through the list writer and in the background (the step never waits for it):
     * written only as a first list. A key made on this device writes it at once; an existing key that lands here
     * gets it only when your write relays show you have none. When you already have one, nothing is written and the
     * app uses yours instead of the ones found here.
     */
    private fun writeFirstRelayList(relayPool: RelayPool, signer: NostrSigner, relays: List<RelayConfig>) {
        val w = listWriter ?: return
        val account = openListAccount(getApplication(), signer)
        viewModelScope.launch {
            var result: ListResult = ListResult.Pending
            // The relays were only just set up: when none could be reached yet, try again a little later.
            for (attempt in 0 until 3) {
                if (attempt > 0) kotlinx.coroutines.delay(5_000)
                result = try {
                    w.createIfMissing(
                        10002,
                        Nip65.buildRelayTags(relays),
                        extraRelays = relays.map { it.url },
                        account = account
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Onboarding", "relay list failed", e)
                    return@launch
                }
                Log.d("Onboarding", "relay list: $result")
                // Pending with a signed version kept: it is re-sent with the next list write, nothing more to do.
                if (result != ListResult.Pending || account.store.latest(10002) != null) break
            }
            if (result != ListResult.Unchanged) return@launch
            val mine = account.store.latest(10002) ?: return@launch
            val auth = relays.associate { ListWriter.norm(it.url) to it.auth }
            val yours = Nip65.parseRelayList(mine).map { r -> auth[ListWriter.norm(r.url)]?.let { r.copy(auth = it) } ?: r }
            if (yours.isEmpty() || keyRepo.getPubkeyHex()?.lowercase() != signer.pubkeyHex.lowercase()) return@launch
            keyRepo.saveRelays(yours)
            relayPool.updateRelays(yours)
        }
    }

    /**
     * Load follow suggestions from relays in three parallel sections.
     */
    fun loadSuggestions(relayPool: RelayPool) {
        if (suggestionsJob != null) return
        _selectedPubkeys.value = emptySet()

        suggestionsJob = viewModelScope.launch {
            // Wait for at least one relay to connect
            relayPool.awaitAnyConnected(minCount = 1, timeoutMs = 5_000)

            // Launch all three sections in parallel
            launch { loadActiveNow(relayPool) }
            launch { loadCreators(relayPool) }
            launch { loadNews(relayPool) }
        }
    }

    /**
     * Collect events for a subscription until EOSE or timeout, whichever comes first.
     * Returns immediately when the expected number of EOSE signals arrive.
     */
    private suspend fun collectUntilEose(
        relayPool: RelayPool,
        subId: String,
        expectedEose: Int,
        timeoutMs: Long,
        onEvent: (NostrEvent) -> Unit
    ) {
        val done = CompletableDeferred<Unit>()
        val eoseCount = java.util.concurrent.atomic.AtomicInteger(0)

        val collectJob = viewModelScope.launch {
            relayPool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId == subId) onEvent(relayEvent.event)
            }
        }
        val eoseJob = viewModelScope.launch {
            relayPool.eoseSignals.collect { id ->
                if (id == subId && eoseCount.incrementAndGet() >= expectedEose) {
                    done.complete(Unit)
                }
            }
        }

        withTimeoutOrNull(timeoutMs) { done.await() }
        collectJob.cancel()
        eoseJob.cancel()
        relayPool.closeOnAllRelays(subId)
    }

    private suspend fun loadActiveNow(relayPool: RelayPool) {
        try {
            val subId = "onb-active"
            val since = System.currentTimeMillis() / 1000 - 20 * 60
            val filter = Filter(kinds = listOf(1), since = since, limit = 200)
            val reqMsg = ClientMessage.req(subId, filter)
            for (url in ACTIVE_RELAYS) relayPool.sendToRelayOrEphemeral(url, reqMsg)

            val authors = mutableSetOf<String>()
            collectUntilEose(relayPool, subId, ACTIVE_RELAYS.size, 8_000) { event ->
                authors.add(event.pubkey)
            }

            if (authors.isEmpty()) {
                _activeNow.value = SuggestionSection(isLoading = false)
                return
            }

            val selected = authors.shuffled().take(20)
            val profiles = fetchProfiles(relayPool, selected, "onb-active-p", ACTIVE_RELAYS)
            _activeNow.value = SuggestionSection(profiles = profiles, isLoading = false)
        } catch (e: Exception) {
            Log.e(TAG, "loadActiveNow failed: ${e.message}")
            _activeNow.value = SuggestionSection(isLoading = false)
        }
    }

    private suspend fun loadCreators(relayPool: RelayPool) {
        try {
            val profiles = fetchProfiles(relayPool, CREATOR_PUBKEYS, "onb-creators", ACTIVE_RELAYS)
            _creators.value = SuggestionSection(profiles = profiles, isLoading = false)
        } catch (e: Exception) {
            Log.e(TAG, "loadCreators failed: ${e.message}")
            _creators.value = SuggestionSection(isLoading = false)
        }
    }

    private suspend fun loadNews(relayPool: RelayPool) {
        try {
            val subId = "onb-news"
            val filter = Filter(kinds = listOf(1), limit = 100)
            val reqMsg = ClientMessage.req(subId, filter)
            relayPool.sendToRelayOrEphemeral(NEWS_RELAY, reqMsg)

            val authors = mutableSetOf<String>()
            collectUntilEose(relayPool, subId, 1, 8_000) { event ->
                authors.add(event.pubkey)
            }

            if (authors.isEmpty()) {
                _news.value = SuggestionSection(isLoading = false)
                return
            }

            val profiles = fetchProfiles(relayPool, authors.toList(), "onb-news-p", listOf(NEWS_RELAY))
            _news.value = SuggestionSection(profiles = profiles, isLoading = false)
        } catch (e: Exception) {
            Log.e(TAG, "loadNews failed: ${e.message}")
            _news.value = SuggestionSection(isLoading = false)
        }
    }

    /**
     * Fetch kind 0 profiles for a list of pubkeys. Returns as soon as EOSE arrives or timeout.
     */
    private suspend fun fetchProfiles(
        relayPool: RelayPool,
        pubkeys: List<String>,
        subId: String,
        relayUrls: List<String>?
    ): List<ProfileData> {
        if (pubkeys.isEmpty()) return emptyList()

        val filter = Filter(kinds = listOf(0), authors = pubkeys)
        val reqMsg = ClientMessage.req(subId, filter)
        val expectedEose: Int

        if (relayUrls != null) {
            for (url in relayUrls) relayPool.sendToRelayOrEphemeral(url, reqMsg)
            expectedEose = relayUrls.size
        } else {
            relayPool.sendToReadRelays(reqMsg)
            expectedEose = 1
        }

        val profiles = mutableMapOf<String, ProfileData>()
        collectUntilEose(relayPool, subId, expectedEose, 8_000) { event ->
            if (event.kind == 0) {
                val profile = ProfileData.fromEvent(event)
                if (profile != null) {
                    val existing = profiles[profile.pubkey]
                    if (existing == null || profile.updatedAt > existing.updatedAt) {
                        profiles[profile.pubkey] = profile
                    }
                }
            }
        }

        return profiles.values.toList()
    }

    fun toggleFollowAll(section: SectionType) {
        val profiles = when (section) {
            SectionType.ACTIVE_NOW -> _activeNow.value.profiles
            SectionType.CREATORS -> _creators.value.profiles
            SectionType.NEWS -> _news.value.profiles
        }
        val pubkeys = profiles.map { it.pubkey }.toSet()
        val current = _selectedPubkeys.value
        val allSelected = pubkeys.all { it in current }
        _selectedPubkeys.value = if (allSelected) {
            current - pubkeys
        } else {
            current + pubkeys
        }
    }

    fun togglePubkey(pubkey: String) {
        val current = _selectedPubkeys.value
        _selectedPubkeys.value = if (pubkey in current) {
            current - pubkey
        } else {
            current + pubkey
        }
    }

    /**
     * Finish onboarding: mark it complete at once, then add the chosen people (and you) to your follow list through
     * ListWriter, in the background. Never blocks or fails the step: a write that can't go out now is kept and sent
     * later. The follows show at once through ListWriter's pending overlay on [contactRepo].
     *
     * For a key made on this device there is no list anywhere yet, so this starts the first one. For an existing key
     * that lands here, the chosen people are added to the list it already has; nothing else in it changes.
     */
    suspend fun finishOnboarding(
        relayPool: RelayPool,
        contactRepo: ContactRepository,
        selectedPubkeys: Set<String>,
        signer: NostrSigner? = null
    ) {
        keyRepo.markOnboardingComplete()
        val s = signer ?: keyRepo.getKeypair()?.let { LocalSigner(it.privkey, it.pubkey) } ?: return
        val w = listWriter ?: return
        val entries = (selectedPubkeys + s.pubkeyHex).map { listOf("p", it) }
        val account = openListAccount(getApplication(), s)
        viewModelScope.launch {
            // The user chose these follows in onboarding: that is the confirmation a first follow list needs.
            val result = w.setMany(3, entries, wanted = true, confirmFirstList = true, account = account)
            Log.d("Onboarding", "follow list: $result")
        }
    }
}
