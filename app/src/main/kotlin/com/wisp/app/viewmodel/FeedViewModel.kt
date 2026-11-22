package com.wisp.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.HttpClientFactory
import com.wisp.app.relay.Relay
import com.wisp.app.relay.RelayLifecycleManager
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.BroadcastState
import com.wisp.app.relay.NotePublicationRelayTransport
import com.wisp.app.repo.FileNotePublicationStore
import com.wisp.app.repo.NotePublisher
import kotlinx.coroutines.flow.collectLatest
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayHealthTracker
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.RelayScoreBoard
import com.wisp.app.relay.ScoredRelay
import com.wisp.app.relay.SubscriptionManager
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.BookmarkRepository
import com.wisp.app.repo.BookmarkSetRepository
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.DmRepository
import com.wisp.app.repo.GroupRepository
import com.wisp.app.db.EventPersistence
import com.wisp.app.db.WispObjectBox
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.ExtendedNetworkRepository
import com.wisp.app.repo.SocialGraphDb
import com.wisp.app.repo.Nip05Repository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.InterestRepository
import com.wisp.app.repo.ListRepository
import com.wisp.app.repo.MetadataFetcher
import com.wisp.app.repo.DeletedEventsRepository
import com.wisp.app.repo.LiveStreamRepository
import com.wisp.app.repo.MuteRepository
import com.wisp.app.repo.NotificationRepository
import com.wisp.app.repo.PinRepository
import com.wisp.app.repo.ProfileRepository
import com.wisp.app.repo.NwcRepository
import com.wisp.app.repo.WalletModeRepository
import com.wisp.app.repo.WalletProvider
import com.wisp.app.repo.CustomEmojiRepository
import com.wisp.app.repo.InterfacePreferences
import com.wisp.app.repo.PowPreferences
import com.wisp.app.repo.SafetyPreferences
import com.wisp.app.repo.ZapPreferences
import com.wisp.app.repo.RelayHintStore
import com.wisp.app.repo.RelayInfoRepository
import com.wisp.app.repo.TranslationRepository
import com.wisp.app.repo.RelayListRepository
import com.wisp.app.repo.RelaySetRepository
import com.wisp.app.repo.ZapSender
import kotlinx.coroutines.Dispatchers
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.RelaySet
import android.content.Context
import com.wisp.app.nostr.Filter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class FeedType { FOR_YOU, FOLLOWS, EXTENDED_FOLLOWS, RELAY, LIST, TRENDING }

enum class TrendingMode { NOTES, USERS }

sealed interface RelayFeedStatus {
    data object Idle : RelayFeedStatus
    data object Connecting : RelayFeedStatus
    data object Subscribing : RelayFeedStatus
    data object Streaming : RelayFeedStatus
    data object NoEvents : RelayFeedStatus
    data object TimedOut : RelayFeedStatus
    data object RateLimited : RelayFeedStatus
    data class BadRelay(val reason: String) : RelayFeedStatus
    data class Cooldown(val remainingSeconds: Int) : RelayFeedStatus
    data class ConnectionFailed(val message: String) : RelayFeedStatus
    data object Disconnected : RelayFeedStatus
}

sealed class InitLoadingState {
    // Cold-start only (skipped when cached):
    data object SearchingProfile : InitLoadingState()
    data class FoundProfile(val name: String, val picture: String?) : InitLoadingState()
    data class FindingFriends(val found: Int, val total: Int) : InitLoadingState()

    // Common path (both cold and warm):
    data class DiscoveringNetwork(val fetched: Int, val total: Int) : InitLoadingState()
    data class ExpandingRelays(val relayCount: Int) : InitLoadingState()

    // Warm-start path (no progress bar, rotating text):
    data object WarmLoading : InitLoadingState()

    data object Subscribing : InitLoadingState()
    data object Done : InitLoadingState()
}

class FeedViewModel(app: Application) : AndroidViewModel(app) {
    private val accountScope = AccountSessionScope(viewModelScope)
    private val _accountSwitching = MutableStateFlow(false)
    val accountSwitching: StateFlow<Boolean> = _accountSwitching
    private val _accountGeneration = MutableStateFlow(0L)
    val accountGeneration: StateFlow<Long> = _accountGeneration
    // -- Infrastructure --
    val keyRepo = KeyRepository(app)
    private val pubkeyHex: String? = keyRepo.getPubkeyHex()

    /** True when the account has a local private key (i.e. not a remote/NIP-07 signer).
     *  DIP-03 private zaps require local signing for both ephemeral derivation and
     *  ECDH decryption, so this gates the UI toggle. Read live so account switches
     *  with different signing modes report correctly. */
    val hasLocalKeypair: Boolean get() = keyRepo.getKeypair() != null

    var signer: NostrSigner? = null
        private set

    fun setSigner(s: NostrSigner) {
        if (_accountSwitching.value || s.pubkeyHex != getUserPubkey()) return
        signer = s
        zapSender.signer = s
        registerAuthSigner()
        listWriter.refreshOverlay()
        // A mute list loaded while no signer was set has its private part unread: read it now.
        val mutes = ownListStore?.latest(com.wisp.app.nostr.Nip51.KIND_MUTE_LIST)
        if (mutes != null && mutes.pubkey.equals(s.pubkeyHex, ignoreCase = true)) {
            viewModelScope.launch {
                if (getUserPubkey()?.equals(mutes.pubkey, ignoreCase = true) == true) muteRepo.loadFromEvent(mutes, s)
            }
        }
    }

    fun clearSigner() {
        signer = null
        zapSender.signer = null
        relayPool.setAuthSigner(null)
    }

    private fun registerAuthSigner() {
        val s = signer ?: return
        relayPool.setAuthSigner { relayUrl, challenge ->
            s.signEvent(
                kind = 22242,
                content = "",
                tags = listOf(
                    listOf("relay", relayUrl),
                    listOf("challenge", challenge)
                )
            )
        }
    }

    val relayPool = RelayPool(
        prefs = app.getSharedPreferences("relay_auth_prefs_$pubkeyHex", android.content.Context.MODE_PRIVATE)
    )
    val healthTracker = RelayHealthTracker(app, pubkeyHex)
    val profileRepo = ProfileRepository(app)
    val muteRepo = MuteRepository(app, pubkeyHex)
    val nip05Repo = Nip05Repository()
    val relayHintStore = RelayHintStore(app)
    val deletedEventsRepo = DeletedEventsRepository(app, pubkeyHex)
    val eventPersistence: EventPersistence? = if (WispObjectBox.isInitialized) {
        EventPersistence(pubkeyHex)
    } else null
    val dmPersistence: com.wisp.app.db.DmPersistence? = if (WispObjectBox.isInitialized) {
        com.wisp.app.db.DmPersistence()
    } else null
    val eventRepo = EventRepository(profileRepo, muteRepo, relayHintStore).also {
        it.currentUserPubkey = pubkeyHex
        it.deletedEventsRepo = deletedEventsRepo
        it.eventPersistence = eventPersistence
        it.keyRepo = keyRepo
    }
    val contactRepo = ContactRepository(app, pubkeyHex).also {
        eventRepo.contactRepo = it
    }
    val listRepo = ListRepository(app, pubkeyHex)
    val dmRepo = DmRepository(app, pubkeyHex, dmPersistence)
    val groupRepo = GroupRepository(app, pubkeyHex)
    val liveStreamRepo = LiveStreamRepository(eventRepo)
    val notifRepo = NotificationRepository(app, pubkeyHex, muteRepo, eventRepo)
    val relayListRepo = RelayListRepository(app)
    val bookmarkRepo = BookmarkRepository(app, pubkeyHex)
    val bookmarkSetRepo = BookmarkSetRepository(app, pubkeyHex)
    val relaySetRepo = RelaySetRepository(app, pubkeyHex)
    val pinRepo = PinRepository(app, pubkeyHex)
    val blossomRepo = BlossomRepository(app, pubkeyHex)
    val interestRepo = InterestRepository(app, pubkeyHex, deletedEventsRepo)
    val relayInfoRepo = RelayInfoRepository()
    val relayScoreBoard = RelayScoreBoard(app, relayListRepo, contactRepo, pubkeyHex)
    val outboxRouter = OutboxRouter(relayPool, relayListRepo, relayHintStore, relayScoreBoard)

    private fun startPublications() {
        val account = getUserPubkey()
        val previous = eventRepo.notePublisher
        if (previous?.isActive == true && previous.accountPubkey == account) return
        previous?.close()
        val publisher = account?.let {
            NotePublisher(it, FileNotePublicationStore(java.io.File(getApplication<Application>().filesDir, "note_publications/$it")),
                NotePublicationRelayTransport(relayPool, outboxRouter), accountScope, eventRepo::addEvent,
                isDeleted = deletedEventsRepo::isEventDeleted,
                verifiedRelayUrls = { event -> eventRepo.getEventRelays(event.id) })
        }
        eventRepo.notePublisher = publisher
        publisher?.launchWork {
            publisher.latest.collectLatest { publication ->
                relayPool.setBroadcastState(publication?.let {
                    BroadcastState(it.acceptedCount, it.relays.size, !it.inFlight, it.rejectedCount)
                })
                if (publication != null && !publication.inFlight) {
                    kotlinx.coroutines.delay(6000)
                    relayPool.setBroadcastState(null)
                }
            }
        }
    }

    private fun stopPublications() {
        eventRepo.notePublisher?.close()
        eventRepo.notePublisher = null
        relayPool.setBroadcastState(null)
    }
    val subManager = SubscriptionManager(relayPool)
    val lifecycleManager = RelayLifecycleManager(
        context = app,
        relayPool = relayPool,
        scope = viewModelScope,
        onReconnected = { force, refreshFeed ->
            Log.d("RLC", "[FeedVM] onReconnected(force=$force, refreshFeed=$refreshFeed) feedType=${feedSub.feedType.value} selectedRelay=${feedSub.selectedRelay.value} feedSize=${eventRepo.feed.value.size}")
            feedSub.subscribeFeed(refresh = refreshFeed)
            val myPubkey = getUserPubkey()
            if (myPubkey != null) startup.subscribeDmsAndNotifications(myPubkey)
            // Re-send unconfirmed list versions and replay list changes kept while offline.
            listWriter.resume()
            if (force) {
                startup.fetchRelayListsForFollows()
                // After a long pause, NIP-51 lists (bookmarks, hashtag sets, media servers, etc.)
                // may be stale. Re-fetch self-data so they're up to date.
                startup.refreshSelfData()
            }
            onGroupReconnect?.invoke()
        }
    )
    val socialGraphDb = SocialGraphDb(app, pubkeyHex)
    val extendedNetworkRepo = ExtendedNetworkRepository(
        app, contactRepo, muteRepo, relayListRepo, relayPool, subManager, relayScoreBoard, pubkeyHex, socialGraphDb
    )
    val safetyPrefs = SafetyPreferences(app, pubkeyHex)
    val spamAuthorCache = com.wisp.app.repo.SpamAuthorCache()
    @Volatile
    var nspamClassifier: com.wisp.app.ml.NSpamClassifier? = null
        private set

    init {
        eventRepo.safetyPrefs = safetyPrefs
        eventRepo.extendedNetworkRepo = extendedNetworkRepo
        notifRepo.spamAuthorCache = spamAuthorCache
        notifRepo.safetyPrefs = safetyPrefs
        notifRepo.contactRepo = contactRepo
        notifRepo.extendedNetworkRepo = extendedNetworkRepo
        // Re-apply the web of trust filter to notifications already accepted while
        // the graph was missing (fail-open window) or before the user toggled it on.
        viewModelScope.launch(Dispatchers.Default) {
            safetyPrefs.wotFilterEnabled.collect { notifRepo.refilterWot() }
        }
        viewModelScope.launch(Dispatchers.Default) {
            extendedNetworkRepo.cachedNetwork.collect { notifRepo.refilterWot() }
        }
        viewModelScope.launch(Dispatchers.Default) {
            nspamClassifier = try {
                val weights = com.wisp.app.ml.NSpamWeights.loadFromAssets(app)
                com.wisp.app.ml.NSpamClassifier(weights)
            } catch (e: Exception) {
                Log.e("FeedVM", "Failed to load nspam weights", e)
                null
            }
            notifRepo.spamClassifier = nspamClassifier
        }
    }
    val customEmojiRepo = CustomEmojiRepository(app, pubkeyHex)
    val translationRepo = TranslationRepository(app) { getUserPubkey() }
    val zapPrefs = ZapPreferences(app, pubkeyHex)
    val powPrefs = PowPreferences(app)
    private val processingDispatcher = Dispatchers.Default

    val metadataFetcher = MetadataFetcher(
        relayPool, outboxRouter, subManager, profileRepo, eventRepo,
        accountScope, processingDispatcher
    ).also {
        eventRepo.metadataFetcher = it
        it.quoteRelayProvider = {
            relayScoreBoard.getScoredRelays().take(5).map { sr -> sr.url }
        }
    }

    val interfacePrefs = InterfacePreferences(app)
    val nwcRepo = NwcRepository(app, relayPool, pubkeyHex)
    val walletModeRepo = WalletModeRepository(app, pubkeyHex)

    val activeWalletProvider: WalletProvider
        get() = nwcRepo

    val zapSender = ZapSender(keyRepo, { activeWalletProvider }, relayPool, relayListRepo, HttpClientFactory.createRelayClient(), interfacePrefs)
    val powManager = PowManager(powPrefs::getNoteDifficulty, { eventRepo.notePublisher })

    init { startPublications() }

    // -- Manager classes --
    val feedSub: FeedSubscriptionManager = FeedSubscriptionManager(
        relayPool, outboxRouter, subManager, eventRepo, contactRepo, listRepo, notifRepo,
        extendedNetworkRepo, interestRepo, keyRepo, healthTracker, relayScoreBoard, profileRepo,
        metadataFetcher, accountScope, processingDispatcher, pubkeyHex,
        getApplication<Application>().getSharedPreferences("wisp_feed", android.content.Context.MODE_PRIVATE),
        com.wisp.app.repo.TrendingPreferences(getApplication())
    )

    val eventRouter: EventRouter = EventRouter(
        relayPool, eventRepo, contactRepo, muteRepo, notifRepo, listRepo, bookmarkRepo,
        bookmarkSetRepo, pinRepo, blossomRepo, customEmojiRepo, relayListRepo, interestRepo, relaySetRepo,
        relayScoreBoard, relayHintStore, keyRepo, dmRepo, extendedNetworkRepo, groupRepo, liveStreamRepo, metadataFetcher,
        getUserPubkey = { getUserPubkey() },
        getSigner = { signer },
        getFeedSubId = { feedSub.feedSubId },
        getRelayFeedSubId = { feedSub.relayFeedSubId },
        getIsTrendingFeed = { feedSub.feedType.value == FeedType.TRENDING },
        onRelayFeedEventReceived = { feedSub.onRelayFeedEventReceived() },
        getOwnListStore = { ownListStore }
    )

    /**
     * The active account's own-list store: the newest version of each own list this device has seen, pending list
     * changes and unconfirmed signed versions. Re-opened on account switch.
     */
    @Volatile
    var ownListStore: com.wisp.app.repo.OwnListStore? = pubkeyHex?.let { openOwnListStore(app, it) }
        private set

    /** The active account for list writes: its signer and store, never during an account switch. */
    private fun listAccount(): ListAccount? {
        if (_accountSwitching.value) return null
        val s = signer ?: return null
        val store = ownListStore ?: return null
        if (s.pubkeyHex.lowercase() != store.pubkeyHex) return null
        return ListAccount(s, store)
    }

    /** The only place that signs follows, mutes, bookmarks and pins (and the automatic DM relay list). */
    val listWriter: ListWriter = ListWriter(
        source = RelayListSource(relayPool),
        sink = RelayListSink(relayPool),
        account = { listAccount() },
        activePubkey = { getUserPubkey() },
        poolWriteRelays = { relayPool.getWriteRelayUrls() },
        scope = accountScope,
        project = { eventRouter.applyOwnList(it) }
    )

    init {
        // Buttons show pending list changes at once (the projections still change only from a signed version).
        val bookmarkPrefix = ListWriter.overlayKey(com.wisp.app.nostr.Nip51.KIND_BOOKMARK_LIST, null, listOf("e", ""))!!
        val followPrefix = ListWriter.overlayKey(3, null, listOf("p", ""))!!
        val mutePrefix = ListWriter.overlayKey(com.wisp.app.nostr.Nip51.KIND_MUTE_LIST, null, listOf("p", ""))!!
        val mutedWordPrefix = ListWriter.overlayKey(com.wisp.app.nostr.Nip51.KIND_MUTE_LIST, null, listOf("word", ""))!!
        val pinPrefix = ListWriter.overlayKey(com.wisp.app.nostr.Nip51.KIND_PIN_LIST, null, listOf("e", ""))!!
        fun Map<String, Boolean>.under(prefix: String): Map<String, Boolean> =
            filterKeys { it.startsWith(prefix) }.mapKeys { it.key.substring(prefix.length) }
        viewModelScope.launch {
            listWriter.overlay.collect { overlay ->
                bookmarkRepo.setPending(overlay.under(bookmarkPrefix))
                contactRepo.setPending(overlay.under(followPrefix))
                muteRepo.setPending(overlay.under(mutePrefix), overlay.under(mutedWordPrefix))
                pinRepo.setPending(overlay.under(pinPrefix))
            }
        }
    }

    val socialActions: SocialActionManager = SocialActionManager(
        relayPool, outboxRouter, eventRepo, contactRepo, muteRepo, notifRepo, dmRepo,
        pinRepo, deletedEventsRepo, { activeWalletProvider }, customEmojiRepo, zapSender, powPrefs, interfacePrefs,
        relayListRepo, accountScope,
        getSigner = { signer },
        getUserPubkey = { getUserPubkey() },
        listWriter = listWriter
    )

    init {
        // A kept follow, replayed later, found that you have no follow list yet: ask now ("Follow anyway").
        viewModelScope.launch {
            listWriter.needsConfirm.collect { entries ->
                socialActions.askFirstFollow(entries.filter { it.firstOrNull() == "p" }.mapNotNull { it.getOrNull(1) })
            }
        }
    }

    val listCrud: ListCrudManager = ListCrudManager(
        relayPool, subManager, eventRepo, listRepo, interestRepo, bookmarkSetRepo, customEmojiRepo,
        metadataFetcher, outboxRouter, accountScope, processingDispatcher,
        getSigner = { signer },
        getUserPubkey = { getUserPubkey() }
    )

    val startup: StartupCoordinator = StartupCoordinator(
        relayPool, outboxRouter, subManager, eventRepo, eventPersistence, contactRepo, muteRepo, notifRepo,
        listRepo, bookmarkRepo, bookmarkSetRepo, relaySetRepo, pinRepo, blossomRepo, deletedEventsRepo, interestRepo, customEmojiRepo,
        relayListRepo, relayScoreBoard, relayHintStore, healthTracker, keyRepo,
        extendedNetworkRepo, metadataFetcher, profileRepo, relayInfoRepo, nip05Repo,
        nwcRepo, walletModeRepo, dmRepo, liveStreamRepo, zapPrefs, lifecycleManager, eventRouter, feedSub,
        accountScope, processingDispatcher,
        getUserPubkey = { getUserPubkey() },
        registerAuthSigner = { registerAuthSigner() },
        fetchEmojiSets = { listCrud.fetchEmojiSets() },
        getSigner = { signer },
        getListWriter = { listWriter }
    )

    // -- Exposed state --
    val feed: StateFlow<List<NostrEvent>> = combine(
        feedSub.feedType, eventRepo.feed, eventRepo.relayFeed, accountSwitching
    ) { type, main, relay, switching ->
        if (switching) emptyList()
        else if (type == FeedType.RELAY || type == FeedType.TRENDING) relay else main
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Keeps the feed rows on screen in place while the feed changes; outlives the screen. */
    val feedListHolder = com.wisp.app.ui.util.StableListHolder<NostrEvent>(keyOf = { it.id })

    /** Keeps the bookmarks rows on screen in place while their notes come in; outlives the screen. */
    val bookmarksListHolder = com.wisp.app.ui.util.StableListHolder<NostrEvent>(keyOf = { it.id })

    private val bookmarkSetListHolders = HashMap<String, com.wisp.app.ui.util.StableListHolder<NostrEvent>>()

    /** The same, one per bookmark set, so each set comes back to its own row. */
    fun bookmarkSetListHolder(pubkey: String, dTag: String): com.wisp.app.ui.util.StableListHolder<NostrEvent> =
        bookmarkSetListHolders.getOrPut("$pubkey:$dTag") {
            com.wisp.app.ui.util.StableListHolder<NostrEvent>(keyOf = { it.id })
        }

    val liveNowStreams: StateFlow<List<com.wisp.app.repo.LiveStream>> = liveStreamRepo.liveStreams
        .map { streams ->
            streams.values
                .filter { it.chatters.isNotEmpty() }
                .sortedByDescending { it.chatters.size }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val newNoteCount: StateFlow<Int> = eventRepo.newNoteCount

    // -- New notes button visibility --
    private val settingsPrefs = app.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)
    private val _newNotesButtonHidden = MutableStateFlow(
        settingsPrefs.getBoolean("new_notes_button_hidden", false)
    )
    val newNotesButtonHidden: StateFlow<Boolean> = _newNotesButtonHidden

    fun hideNewNotesButton(permanent: Boolean) {
        _newNotesButtonHidden.value = true
        if (permanent) {
            settingsPrefs.edit().putBoolean("new_notes_button_hidden", true).apply()
        }
    }

    fun showNewNotesButton() {
        _newNotesButtonHidden.value = false
        settingsPrefs.edit().putBoolean("new_notes_button_hidden", false).apply()
    }

    val initialLoadDone: StateFlow<Boolean> = feedSub.initialLoadDone

    // The reply signals (ReplyGate) need the follows' graph, which used to be built
    // only from the Social Graph screen. Once per account per app start, after the
    // initial feed load, build it in the background when it is missing or stale.
    private val replySignalDiscoveryTried =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    init {
        viewModelScope.launch {
            initialLoadDone.collect { done ->
                if (!done) return@collect
                val pk = getUserPubkey() ?: return@collect
                if (!safetyPrefs.muteSignalEnabled.value && !safetyPrefs.followSignalEnabled.value) return@collect
                if (!replySignalDiscoveryTried.add(pk)) return@collect
                launch(Dispatchers.Default) {
                    kotlinx.coroutines.delay(2_000)
                    if (getUserPubkey() != pk) return@launch
                    if (!extendedNetworkRepo.needsReplySignalRefresh()) return@launch
                    Log.d("FeedVM", "Building follows graph for reply signals")
                    extendedNetworkRepo.discoverNetwork()
                }
            }
        }
    }
    val initLoadingState: StateFlow<InitLoadingState> = feedSub.initLoadingState
    val feedType: StateFlow<FeedType> = feedSub.feedType
    val selectedRelay: StateFlow<String?> = feedSub.selectedRelay
    val selectedRelaySet: StateFlow<RelaySet?> = feedSub.selectedRelaySet
    val isRefreshing: StateFlow<Boolean> = feedSub.isRefreshing
    val relayFeedStatus: StateFlow<RelayFeedStatus> = feedSub.relayFeedStatus
    val loadingScreenComplete: StateFlow<Boolean> = feedSub.loadingScreenComplete
    val trendingWindow: StateFlow<com.wisp.app.nostr.TrendingWindow> = feedSub.trendingWindow
    val trendingMode: StateFlow<TrendingMode> = feedSub.trendingMode
    val trendingUsers: StateFlow<List<com.wisp.app.nostr.ProfileData>> = feedSub.trendingUsers
    val trendingUsersLoading: StateFlow<Boolean> = feedSub.trendingUsersLoading
    val feedContentFilter: StateFlow<FeedContentFilter> = feedSub.feedContentFilter
    val selectedList: StateFlow<com.wisp.app.nostr.FollowSet?> = listRepo.selectedList
    val zapInProgress: StateFlow<Set<String>> = socialActions.zapInProgress
    val zapSuccess: SharedFlow<String> = socialActions.zapSuccess
    val zapError: SharedFlow<String> = socialActions.zapError
    val reactionSent: SharedFlow<Unit> = socialActions.reactionSent

    suspend fun payInvoice(bolt11: String): Boolean =
        activeWalletProvider.payInvoice(bolt11).isSuccess
    val pendingFirstFollow: StateFlow<String?> = socialActions.pendingFirstFollow
    val firstFollowCheckDone: StateFlow<Boolean> = socialActions.firstFollowCheckDone

    fun getUserPubkey(): String? = keyRepo.getPubkeyHex()
    fun resetNewNoteCount() = eventRepo.resetNewNoteCount()
    fun translateEvent(event: NostrEvent) = translationRepo.translate(event)
    fun queueProfileFetch(pubkey: String) = metadataFetcher.queueProfileFetch(pubkey)
    fun forceProfileFetch(pubkey: String) = metadataFetcher.forceProfileFetch(pubkey)
    fun markLoadingComplete() = feedSub.markLoadingComplete()

    // -- Startup delegates --
    fun initRelays() {
        if (!_accountSwitching.value) startup.initRelays()
    }
    private fun resetForAccountSwitch(clearPersisted: Boolean) {
        stopPublications()
        // Logging out drops this account's kept list versions and pending changes (not what it has ever seen).
        if (clearPersisted) ownListStore?.clear()
        ownListStore = null
        startup.resetForAccountSwitch(clearPersisted)
        groupRepo.clear()
        liveStreamRepo.clear()
    }

    /** Blocks until every queued batch write is durable; failures are logged, not fatal. */
    private suspend fun flushPersistedWrites() {
        for ((name, block) in listOf<Pair<String, suspend () -> Unit>>(
            "events" to { eventPersistence?.flush() },
            "profiles" to { profileRepo.flush() },
            "relay lists" to { relayListRepo.flush() }
        )) {
            try {
                block()
            } catch (e: Exception) {
                Log.w("FeedVM", "Persisted write flush failed for $name during account switch", e)
            }
        }
    }
    fun reloadForNewAccount(resumePublications: Boolean = true) {
        if (!resumePublications || eventRepo.notePublisher?.accountPubkey != getUserPubkey()) stopPublications()
        safetyPrefs.reload(getUserPubkey())
        ownListStore = getUserPubkey()?.let { openOwnListStore(getApplication(), it) }
        startup.reloadForNewAccount()
        groupRepo.reload(getUserPubkey())
        val pk = getUserPubkey()
        relayPool.rekeyAuthPrefs(
            getApplication<Application>().getSharedPreferences("relay_auth_prefs_$pk", Context.MODE_PRIVATE)
        )
        accountScope.start()
        listWriter.refreshOverlay()
        if (resumePublications) startPublications()
    }

    private var pendingSwitchJob: kotlinx.coroutines.Job? = null

    /** UI callbacks stay on Main; only repository reloads move off-thread. */
    fun beginAccountSwitch(
        beforeKeySwap: suspend () -> Unit,
        swapKey: () -> Unit,
        clearPersisted: Boolean = false,
        resumePublications: Boolean = true,
        afterReload: () -> Unit = {}
    ): Boolean {
        if (_accountSwitching.value) return false
        _accountSwitching.value = true
        _accountGeneration.value++
        clearSigner()
        pendingSwitchJob = viewModelScope.launch {
            // Join every old producer before changing the key or any repository owner.
            lifecycleManager.stopAndJoin()
            stopPublications()
            accountScope.stop()
            // Drain batched writes under the OLD owner before any repo is cleared or rekeyed.
            kotlinx.coroutines.withContext(Dispatchers.IO) { flushPersistedWrites() }
            beforeKeySwap()
            kotlinx.coroutines.withContext(Dispatchers.IO) { resetForAccountSwitch(clearPersisted) }
            swapKey()
            kotlinx.coroutines.withContext(Dispatchers.IO) { reloadForNewAccount(resumePublications) }
            _accountSwitching.value = false
            afterReload()
        }
        return true
    }

    suspend fun awaitAccountSwitch() { pendingSwitchJob?.join() }
    /** Called after relay reconnect to re-subscribe notified group channels. */
    var onGroupReconnect: (() -> Unit)? = null
    fun onAppPause() = startup.onAppPause()
    fun onAppResume(pausedMs: Long) = startup.onAppResume(pausedMs)
    fun refreshRelays() = startup.refreshRelays()
    fun refreshDmsAndNotifications() = startup.refreshDmsAndNotifications()
    fun integrateExtendedNetwork() = startup.integrateExtendedNetwork()
    fun refreshNotifRepliesEtag() = startup.refreshNotifRepliesEtag()

    // -- Feed subscription delegates --
    fun setFeedType(type: FeedType) = feedSub.setFeedType(type)
    fun setSelectedRelay(url: String) = feedSub.setSelectedRelay(url)
    fun refreshFeed() = feedSub.refreshFeed()
    fun retryRelayFeed() = feedSub.retryRelayFeed()
    fun loadMore() = feedSub.loadMore()
    fun onVisibleRangeChanged(first: Int, last: Int) = feedSub.onViewportChanged(first, last)
    fun onViewportChanged(visibleEventIds: List<String>) = feedSub.onViewportChanged(visibleEventIds)
    fun trendingRelayUrl(): String = feedSub.trendingRelayUrl
    fun setTrendingWindow(window: com.wisp.app.nostr.TrendingWindow) = feedSub.setTrendingWindow(window)
    fun setTrendingMode(mode: TrendingMode) = feedSub.setTrendingMode(mode)
    fun setFeedContentFilter(filter: FeedContentFilter) = feedSub.setFeedContentFilter(filter)
    fun pauseEngagement() = feedSub.pauseEngagement()
    fun resumeEngagement() = feedSub.resumeEngagement()

    // -- Relay info delegates --
    fun getRelayUrls(): List<String> = relayPool.getRelayUrls()
    fun getScoredRelays(): List<ScoredRelay> = relayScoreBoard.getScoredRelays()

    fun getRelayCoverageCounts(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for ((url, count) in relayScoreBoard.getCoverageCounts()) {
            counts[url] = (counts[url] ?: 0) + count
        }
        for ((url, count) in extendedNetworkRepo.getCoverageCounts()) {
            counts[url] = (counts[url] ?: 0) + count
        }
        return counts
    }

    suspend fun probeRelay(input: String): String? {
        // If the caller already provided a full URL, try only that
        if (input.startsWith("ws://") || input.startsWith("wss://")) {
            return if (tryConnect(input)) input else null
        }
        // Bare domain — try wss:// first, fall back to ws://
        val wssUrl = "wss://$input"
        if (tryConnect(wssUrl)) return wssUrl
        val wsUrl = "ws://$input"
        if (tryConnect(wsUrl)) return wsUrl
        return null
    }

    private suspend fun tryConnect(url: String): Boolean {
        val client = HttpClientFactory.createRelayClient()
        val relay = Relay(RelayConfig(url, read = true, write = false), client)
        relay.autoReconnect = false
        return try {
            relay.connect()
            val connected = relay.awaitConnected(timeoutMs = 4_000L)
            relay.disconnect()
            connected
        } catch (e: Exception) {
            relay.disconnect()
            false
        }
    }

    // -- Social action delegates --
    // List writes (follows, mutes, bookmarks, pins) all go through ListWriter with the state the user wants. The
    // toggle* helpers take that state from what the button shows at tap time (pending changes included).
    fun toggleFollow(pubkey: String) = socialActions.toggleFollow(pubkey)
    fun confirmFirstFollow() = socialActions.confirmFirstFollow()
    fun dismissFirstFollow() = socialActions.dismissFirstFollow()
    /** Open the "No follow list found" dialog (another screen's follow was refused with NEEDS_CONFIRM). */
    fun askFirstFollow(pubkey: String) = socialActions.askFirstFollow(listOf(pubkey))
    fun blockUser(pubkey: String) = socialActions.blockUser(pubkey)
    fun unblockUser(pubkey: String) = socialActions.unblockUser(pubkey)
    fun sendRepost(event: NostrEvent) = socialActions.sendRepost(event)
    fun sendReaction(event: NostrEvent, content: String = "+") = socialActions.toggleReaction(event, content)
    fun toggleReaction(event: NostrEvent, emoji: String) = socialActions.toggleReaction(event, emoji)
    fun sendZap(event: NostrEvent, amountMsats: Long, message: String = "", isAnonymous: Boolean = false, isPrivate: Boolean = false, extraRelayHints: List<String> = emptyList(), recipientOverride: String? = null, eventATag: String? = null) = socialActions.sendZap(event, amountMsats, message, isAnonymous, isPrivate, extraRelayHints, recipientOverride, eventATag)
    fun togglePin(eventId: String) = socialActions.togglePin(eventId)
    fun deleteEvent(eventId: String, kind: Int) = socialActions.deleteEvent(eventId, kind)
    fun followAll(pubkeys: Set<String>) = socialActions.followAll(pubkeys)

    // -- List writes through ListWriter (the state the user wants, captured at tap time; never a toggle) --
    /** Pending wanted states (ListWriter.overlayKey -> wanted). Overlay on the repositories so buttons flip at once. */
    val listOverlay: StateFlow<Map<String, Boolean>> get() = listWriter.overlay
    /** The pending wanted state of one entry, or null when nothing is pending for it. */
    fun listWanted(kind: Int, entry: List<String>, d: String? = null): Boolean? = listWriter.wanted(kind, d, entry)
    /** Follow or unfollow. When there is no follow list anywhere, the "No follow list found" dialog asks first. */
    fun setFollowing(pubkey: String, wanted: Boolean, onResult: (ListResult) -> Unit = {}) =
        socialActions.setFollowing(pubkey, wanted, onResult)
    /** Follow several people in one write (follow all). */
    fun followMany(pubkeys: Collection<String>, onResult: (ListResult) -> Unit = {}) =
        socialActions.followAll(pubkeys, onResult)
    /** Mute or unmute a person: hidden or shown here at once; the mute list write is separate (a new mute goes private). */
    fun setMuted(pubkey: String, wanted: Boolean, onResult: (ListResult) -> Unit = {}) =
        socialActions.setUserMuted(pubkey, wanted, onResult)
    /** Mute or unmute a word. Removal matches any spelling of it (case-insensitive), wherever it is stored. */
    fun setMutedWord(word: String, wanted: Boolean, onResult: (ListResult) -> Unit = {}) =
        socialActions.setMutedWord(word, wanted, onResult)
    /**
     * Bookmark or unbookmark a note (a new one is a public `e` tag with a relay hint when one is known). [wanted] is
     * the state the user asked for, from what the button showed at tap time. Every other entry, tag and the private
     * part stay as they are.
     */
    fun setBookmarked(eventId: String, wanted: Boolean, onResult: (ListResult) -> Unit = {}) =
        listWriter.submit(com.wisp.app.nostr.Nip51.KIND_BOOKMARK_LIST, socialActions.eTagFor(eventId), wanted, onResult = onResult)
    /** Pin or unpin a note on the profile. */
    fun setPinned(eventId: String, wanted: Boolean, onResult: (ListResult) -> Unit = {}) =
        socialActions.setPinned(eventId, wanted, onResult)
    fun muteThread(rootEventId: String) = socialActions.muteThread(rootEventId)
    fun publishPollVote(pollEventId: String, optionIds: List<String>) = socialActions.publishPollVote(pollEventId, optionIds)
    fun sendZapPollVote(pollEvent: NostrEvent, optionIndex: Int, amountMsats: Long, message: String = "", isAnonymous: Boolean = false) =
        socialActions.sendZapPollVote(pollEvent, optionIndex, amountMsats, message, isAnonymous)

    /** Publish a NIP-38 user status (kind 30315). Empty string clears the status. */
    fun publishUserStatus(status: String) {
        val pubkey = getUserPubkey() ?: return
        // Optimistic local update so UI feels instant (before signer check so the
        // UI always responds, even if the signer is momentarily unavailable after
        // process-death recovery).
        eventRepo.setUserStatus(pubkey, status.ifBlank { null })
        val s = signer ?: return
        accountScope.launch {
            val tags = mutableListOf(listOf("d", "general"))
            val event = s.signEvent(kind = 30315, content = status, tags = tags)
            relayPool.sendToWriteRelays(ClientMessage.event(event))
            eventRepo.addEvent(event)
        }
    }

    // -- List CRUD delegates --
    fun createList(name: String, isPrivate: Boolean = false) = listCrud.createList(name, isPrivate)
    fun addToList(dTag: String, pubkey: String) = listCrud.addToList(dTag, pubkey)
    fun removeFromList(dTag: String, pubkey: String) = listCrud.removeFromList(dTag, pubkey)
    fun deleteList(dTag: String) = listCrud.deleteList(dTag)
    fun fetchUserLists(pubkey: String) = listCrud.fetchUserLists(pubkey)
    fun createBookmarkSet(name: String, isPrivate: Boolean = false) = listCrud.createBookmarkSet(name, isPrivate)
    fun addNoteToBookmarkSet(dTag: String, eventId: String) = listCrud.addNoteToBookmarkSet(dTag, eventId)
    fun removeNoteFromBookmarkSet(dTag: String, eventId: String) = listCrud.removeNoteFromBookmarkSet(dTag, eventId)
    fun deleteBookmarkSet(dTag: String) = listCrud.deleteBookmarkSet(dTag)
    fun fetchBookmarkSetEvents(dTag: String) = listCrud.fetchBookmarkSetEvents(dTag)

    fun fetchBookmarkEvents() {
        val ids = bookmarkRepo.getBookmarkedIds().toList()
        if (ids.isEmpty()) return
        val missing = ids.filter { eventRepo.getEvent(it) == null }
        if (missing.isEmpty()) return
        val subId = "fetch-bookmarks"
        val filter = com.wisp.app.nostr.Filter(ids = missing)
        relayPool.sendToTopRelays(com.wisp.app.nostr.ClientMessage.req(subId, filter))
        accountScope.launch {
            subManager.awaitEoseWithTimeout(subId)
            subManager.closeSubscription(subId)
            eventRepo.bumpEventCacheVersion()
            kotlinx.coroutines.withContext(processingDispatcher) {
                metadataFetcher.sweepMissingProfiles()
            }
        }
    }

    fun createEmojiSet(name: String, emojis: List<com.wisp.app.nostr.CustomEmoji>) = listCrud.createEmojiSet(name, emojis)
    fun updateEmojiSet(dTag: String, title: String, emojis: List<com.wisp.app.nostr.CustomEmoji>) = listCrud.updateEmojiSet(dTag, title, emojis)
    fun deleteEmojiSet(dTag: String) = listCrud.deleteEmojiSet(dTag)
    fun publishUserEmojiList(emojis: List<com.wisp.app.nostr.CustomEmoji>, setRefs: List<String>) = listCrud.publishUserEmojiList(emojis, setRefs)
    fun addSetToEmojiList(pubkey: String, dTag: String) = listCrud.addSetToEmojiList(pubkey, dTag)
    fun removeSetFromEmojiList(pubkey: String, dTag: String) = listCrud.removeSetFromEmojiList(pubkey, dTag)

    // -- Interest set CRUD delegates --
    fun followHashtag(tag: String, dTag: String) = listCrud.followHashtag(tag, dTag)
    fun followHashtags(tags: Set<String>, dTag: String, setTitle: String? = null) =
        listCrud.followHashtags(tags, dTag, setTitle)
    fun unfollowHashtag(tag: String, dTag: String) = listCrud.unfollowHashtag(tag, dTag)
    fun createInterestSet(name: String) = listCrud.createInterestSet(name)
    fun renameInterestSet(dTag: String, newName: String) = listCrud.renameInterestSet(dTag, newName)
    fun deleteInterestSet(dTag: String) = listCrud.deleteInterestSet(dTag)

    private val _interestSetsFetched = MutableStateFlow(false)
    val interestSetsFetched: StateFlow<Boolean> = _interestSetsFetched

    private val _hashtagPickerRequested = MutableStateFlow(false)
    val hashtagPickerRequested: StateFlow<Boolean> = _hashtagPickerRequested
    fun requestHashtagPicker() { _hashtagPickerRequested.value = true }
    fun clearHashtagPickerRequest() { _hashtagPickerRequested.value = false }

    /**
     * Fetch interest sets (kind 30015) from relays if none are cached locally.
     * Called when entering hashtag feed to ensure we have up-to-date data.
     */
    suspend fun fetchInterestSetsIfMissing() {
        if (interestRepo.sets.value.isNotEmpty()) {
            _interestSetsFetched.value = true
            return
        }
        val myPubkey = getUserPubkey() ?: run {
            _interestSetsFetched.value = true
            return
        }

        val subId = "interest_fetch_${myPubkey.take(8)}"
        val filter = Filter(
            kinds = listOf(Nip51.KIND_INTEREST_SET),
            authors = listOf(myPubkey),
            limit = 50
        )

        // Query own write (outbox) relays first, with fallback to all connected relays
        outboxRouter.subscribeToUserWriteRelays(subId, myPubkey, filter)
        // Also try indexer relays as safety net
        val reqMsg = ClientMessage.req(subId, filter)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelayOrEphemeral(url, reqMsg, skipBadCheck = true)
        }

        // Wait for events or timeout
        withTimeoutOrNull(4000L) {
            relayPool.relayEvents.first { it.subscriptionId == subId }
        }

        val closeMsg = ClientMessage.close(subId)
        relayPool.closeOnAllRelays(subId)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelay(url, closeMsg)
        }
        _interestSetsFetched.value = true
    }

    fun setSelectedList(followSet: com.wisp.app.nostr.FollowSet) {
        listRepo.selectList(followSet)
    }

    // -- Relay set delegates --
    fun setSelectedRelaySet(relaySet: RelaySet) = feedSub.setSelectedRelaySet(relaySet)

    fun toggleFavoriteRelay(url: String) {
        val isFav = relaySetRepo.isFavorite(url)
        val updated = if (isFav) relaySetRepo.removeFavorite(url) else relaySetRepo.addFavorite(url)
        publishFavoriteRelays(updated)
    }

    fun addRelayToSet(url: String, dTag: String) {
        val updated = relaySetRepo.addRelayToSet(url, dTag) ?: return
        publishRelaySet(updated)
    }

    fun removeRelayFromSet(url: String, dTag: String) {
        val updated = relaySetRepo.removeRelayFromSet(url, dTag) ?: return
        publishRelaySet(updated)
    }

    fun createRelaySet(name: String, relays: Set<String> = emptySet()) {
        val set = relaySetRepo.createRelaySet(name, relays) ?: return
        publishRelaySet(set)
    }

    fun removeRelaySet(dTag: String) {
        relaySetRepo.removeRelaySet(dTag)
    }

    private fun publishFavoriteRelays(urls: List<String>) {
        val s = signer ?: return
        accountScope.launch {
            val tags = Nip51.buildRelaySetTags(urls)
            val event = s.signEvent(
                kind = Nip51.KIND_FAVORITE_RELAYS,
                content = "",
                tags = tags
            )
            relayPool.sendToWriteRelays(ClientMessage.event(event))
        }
    }

    private fun publishRelaySet(relaySet: RelaySet) {
        val s = signer ?: return
        accountScope.launch {
            val tags = Nip51.buildRelaySetNamedTags(relaySet.dTag, relaySet.relays, relaySet.name)
            val event = s.signEvent(
                kind = Nip51.KIND_RELAY_SET,
                content = "",
                tags = tags
            )
            relayPool.sendToWriteRelays(ClientMessage.event(event))
        }
    }

    /**
     * Fetch DM relays (kind 10050) for a pubkey if not already cached.
     * Returns true if the pubkey has DM relays (from cache or fresh fetch).
     */
    suspend fun fetchDmRelaysIfMissing(pubkey: String): Boolean {
        if (relayListRepo.hasDmRelays(pubkey)) return true

        val subId = "zap_dm_relay_${pubkey.take(8)}"
        val filter = Filter(
            kinds = listOf(Nip51.KIND_DM_RELAYS),
            authors = listOf(pubkey),
            limit = 1
        )
        val reqMsg = ClientMessage.req(subId, filter)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelayOrEphemeral(url, reqMsg, skipBadCheck = true)
        }
        relayPool.sendToAll(reqMsg)

        val result = withTimeoutOrNull(4000L) {
            relayPool.relayEvents.first { it.subscriptionId == subId }
        }

        val closeMsg = ClientMessage.close(subId)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelay(url, closeMsg)
        }
        relayPool.sendToAll(closeMsg)

        if (result != null) {
            relayListRepo.updateDmRelaysFromEvent(result.event)
        }

        return relayListRepo.hasDmRelays(pubkey)
    }

    override fun onCleared() {
        stopPublications()
        super.onCleared()
        nwcRepo.disconnect()
        relayPool.disconnectAll()
        notifRepo.shutdown()
        eventRepo.shutdown()
        // viewModelScope is already cancelled here, so drain writers on a detached IO scope.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()).launch(kotlinx.coroutines.Dispatchers.IO) {
            try { eventPersistence?.shutdown() } catch (e: Exception) { Log.w("FeedVM", "Event persistence shutdown failed", e) }
            try { profileRepo.shutdown() } catch (e: Exception) { Log.w("FeedVM", "Profile persistence shutdown failed", e) }
            try { relayListRepo.shutdown() } catch (e: Exception) { Log.w("FeedVM", "Relay list persistence shutdown failed", e) }
        }
    }
}
