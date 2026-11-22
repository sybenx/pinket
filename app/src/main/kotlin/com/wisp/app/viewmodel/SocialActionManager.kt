package com.wisp.app.viewmodel

import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.CustomEmoji
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip13
import com.wisp.app.nostr.Nip25
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.Nip18
import com.wisp.app.nostr.Nip09
import com.wisp.app.nostr.Nip57
import com.wisp.app.nostr.Nip88
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.DmRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.MuteRepository
import com.wisp.app.repo.NotificationRepository
import com.wisp.app.repo.PrivateReactionPublisher
import com.wisp.app.repo.RelayListRepository
import com.wisp.app.repo.WalletProvider
import com.wisp.app.repo.PinRepository
import com.wisp.app.repo.CustomEmojiRepository
import com.wisp.app.repo.DeletedEventsRepository
import com.wisp.app.repo.InterfacePreferences
import com.wisp.app.repo.PowPreferences
import com.wisp.app.repo.ZapSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Handles user-initiated social actions: follow/block, reactions, reposts, zaps, pins, mutes.
 * Extracted from FeedViewModel to reduce its size.
 */
class SocialActionManager(
    private val relayPool: RelayPool,
    private val outboxRouter: OutboxRouter,
    private val eventRepo: EventRepository,
    private val contactRepo: ContactRepository,
    private val muteRepo: MuteRepository,
    private val notifRepo: NotificationRepository,
    private val dmRepo: DmRepository,
    private val pinRepo: PinRepository,
    private val deletedEventsRepo: DeletedEventsRepository,
    private val getWalletProvider: () -> WalletProvider,
    private val customEmojiRepo: CustomEmojiRepository,
    private val zapSender: ZapSender,
    private val powPrefs: PowPreferences,
    private val interfacePrefs: InterfacePreferences,
    private val relayListRepo: RelayListRepository,
    private val scope: CoroutineScope,
    private val getSigner: () -> NostrSigner?,
    private val getUserPubkey: () -> String?,
    /** The only allowed signer of follows (3), mutes (10000) and pins (10001): pass the wanted state. */
    private val listWriter: ListWriter? = null
) {
    private companion object {
        const val KIND_FOLLOWS = 3
    }

    private val _zapInProgress = MutableStateFlow<Set<String>>(emptySet())
    val zapInProgress: StateFlow<Set<String>> = _zapInProgress

    private val _zapSuccess = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val zapSuccess: SharedFlow<String> = _zapSuccess

    private val _zapError = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val zapError: SharedFlow<String> = _zapError

    private val _reactionSent = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val reactionSent: SharedFlow<Unit> = _reactionSent

    // Non-null while the "No follow list found" dialog is visible (holds the first person to follow). Shown only
    // after ListWriter's check of your write relays found no follow list (Refused NEEDS_CONFIRM).
    private val _pendingFirstFollow = MutableStateFlow<String?>(null)
    val pendingFirstFollow: StateFlow<String?> = _pendingFirstFollow

    // Kept for the dialog: always true while it shows (the relay check is done before it opens).
    private val _firstFollowCheckDone = MutableStateFlow(false)
    val firstFollowCheckDone: StateFlow<Boolean> = _firstFollowCheckDone

    /** Everyone the dialog's "Follow anyway" would follow (one person, or a follow all). */
    @Volatile private var firstFollowTargets: List<String> = emptyList()

    // ---------------------------------------------------------------- follows (kind 3)

    /**
     * Follow ([wanted] = true) or unfollow. [wanted] is the state the user asked for, from what the button showed at
     * tap time. Only the one `p` entry changes; everything else in the follow list stays. When there is no follow list
     * anywhere, the "No follow list found" dialog asks first ([confirmFirstFollow]).
     */
    fun setFollowing(pubkey: String, wanted: Boolean, onResult: (ListResult) -> Unit = {}) {
        val w = listWriter ?: return
        w.submit(KIND_FOLLOWS, listOf("p", pubkey), wanted) { r ->
            if (needsConfirm(r)) askFirstFollow(listOf(pubkey))
            onResult(r)
        }
    }

    /** A follow button tap: the wanted state is the opposite of what the button shows now (pending changes included). */
    fun toggleFollow(pubkey: String) = setFollowing(pubkey, wanted = !contactRepo.isFollowing(pubkey))

    /** Follow several people in one write; nobody already followed is touched. */
    fun followAll(pubkeys: Collection<String>, onResult: (ListResult) -> Unit = {}) {
        val w = listWriter ?: return
        val targets = pubkeys.distinct()
        if (targets.isEmpty()) return
        w.submitMany(KIND_FOLLOWS, targets.map { listOf("p", it) }, true) { r ->
            if (needsConfirm(r)) askFirstFollow(targets)
            onResult(r)
        }
    }

    /** Open the "No follow list found" dialog for [pubkeys]. */
    fun askFirstFollow(pubkeys: List<String>) {
        if (pubkeys.isEmpty()) return
        firstFollowTargets = pubkeys
        _firstFollowCheckDone.value = true
        _pendingFirstFollow.value = pubkeys.first()
    }

    /** "Follow anyway": start a follow list with the people the dialog was for (still checked as a first list). */
    fun confirmFirstFollow() {
        val targets = firstFollowTargets
        dismissFirstFollow()
        if (targets.isEmpty()) return
        val w = listWriter ?: return
        w.submitMany(KIND_FOLLOWS, targets.map { listOf("p", it) }, true, confirmFirstList = true)
    }

    fun dismissFirstFollow() {
        _pendingFirstFollow.value = null
        _firstFollowCheckDone.value = false
        firstFollowTargets = emptyList()
    }

    private fun needsConfirm(r: ListResult) =
        r is ListResult.Refused && r.reason == ListResult.Reason.NEEDS_CONFIRM

    // ---------------------------------------------------------------- mutes (kind 10000)

    /** Mute a person: hidden here at once, whatever the list write does; the list gets a private entry. */
    fun blockUser(pubkey: String) = setUserMuted(pubkey, true)

    fun unblockUser(pubkey: String) = setUserMuted(pubkey, false)

    /**
     * Mute ([wanted] = true) or unmute a person. The local effect is immediate and does not wait for the write. A new
     * mute goes into the private part (a public tag when that can't be read or would be too large); unmute removes
     * every copy from both parts.
     */
    fun setUserMuted(pubkey: String, wanted: Boolean, onResult: (ListResult) -> Unit = {}) {
        if (wanted) {
            muteRepo.blockLocally(pubkey)
            eventRepo.purgeUser(pubkey)
            notifRepo.purgeUser(pubkey)
            dmRepo.purgeUser(pubkey)
        } else {
            muteRepo.unblockLocally(pubkey)
        }
        listWriter?.submit(Nip51.KIND_MUTE_LIST, listOf("p", pubkey), wanted, place = ListPlace.PRIVATE_OR_PUBLIC, onResult = onResult)
    }

    /** Mute or unmute a word. Unmuting matches any spelling of it, wherever it is stored. */
    fun setMutedWord(word: String, wanted: Boolean, onResult: (ListResult) -> Unit = {}) {
        val w = word.trim()
        if (w.isEmpty()) return
        if (wanted) muteRepo.muteWordLocally(w) else muteRepo.unmuteWordLocally(w)
        listWriter?.submit(Nip51.KIND_MUTE_LIST, listOf("word", w), wanted, place = ListPlace.PRIVATE_OR_PUBLIC, onResult = onResult)
    }

    /** Muted threads are local only and never written to the mute list. */
    fun muteThread(rootEventId: String) {
        muteRepo.muteThread(rootEventId)
        notifRepo.purgeThread(rootEventId)
        eventRepo.purgeThread(rootEventId)
    }

    fun sendRepost(event: NostrEvent) {
        val s = getSigner() ?: return
        scope.launch {
            try {
                val hint = outboxRouter.getRelayHint(event.pubkey)
                val tags = Nip18.buildRepostTags(event, hint).toMutableList()
                if (interfacePrefs.isClientTagEnabled()) {
                    tags.add(listOf("client", "Pinket"))
                }
                val repostEvent = s.signEvent(kind = 6, content = event.toJson(), tags = tags)
                val msg = ClientMessage.event(repostEvent)
                outboxRouter.publishToInbox(msg, event.pubkey)
                eventRepo.markUserRepost(event.id)
                eventRepo.addEvent(repostEvent)
            } catch (_: Exception) {}
        }
    }

    fun sendReaction(event: NostrEvent, content: String = "+") {
        toggleReaction(event, content)
    }

    fun toggleReaction(event: NostrEvent, emoji: String) {
        val s = getSigner() ?: return
        val myPubkey = s.pubkeyHex

        // NIP-17 private replies are reacted to with a gift-wrapped kind-7 rumor so
        // the target rumor id never lands on a public relay. v1 is add-only: if the
        // user has already reacted with this emoji we no-op rather than publishing a
        // gift-wrapped NIP-09 deletion (gift-wrapped deletion is a future enhancement).
        if (eventRepo.isPrivate(event.id)) {
            val existingEmoji = eventRepo.getUserReactionEmoji(event.id, myPubkey)
            if (existingEmoji != null) return
            val shortcodeMatch = Nip30.shortcodeRegex.matchEntire(emoji)
            val emojiUrl = if (shortcodeMatch != null) {
                val shortcode = shortcodeMatch.groupValues[1]
                customEmojiRepo.resolvedEmojis.value[shortcode]
            } else null
            scope.launch {
                try {
                    PrivateReactionPublisher.send(
                        signer = s,
                        relayPool = relayPool,
                        dmRepo = dmRepo,
                        relayListRepo = relayListRepo,
                        eventRepo = eventRepo,
                        targetEvent = event,
                        emoji = emoji,
                        emojiUrl = emojiUrl
                    )
                    _reactionSent.tryEmit(Unit)
                    customEmojiRepo.recordEmojiUsage(emoji)
                } catch (_: Exception) {}
            }
            return
        }

        val existingEventId = eventRepo.getUserReactionEventId(event.id, myPubkey, emoji)

        scope.launch {
            try {
                if (existingEventId != null) {
                    val tags = Nip09.buildDeletionTags(existingEventId, 7)
                    val deletionEvent = s.signEvent(kind = 5, content = "", tags = tags)
                    relayPool.sendToWriteRelays(ClientMessage.event(deletionEvent))
                    eventRepo.removeReaction(event.id, myPubkey, emoji)
                } else {
                    val shortcodeMatch = Nip30.shortcodeRegex.matchEntire(emoji)
                    var tags: List<List<String>> = if (shortcodeMatch != null) {
                        val shortcode = shortcodeMatch.groupValues[1]
                        val url = customEmojiRepo.resolvedEmojis.value[shortcode]
                        if (url != null) {
                            Nip25.buildReactionTagsWithEmoji(
                                event, CustomEmoji(shortcode, url)
                            )
                        } else {
                            Nip25.buildReactionTags(event)
                        }
                    } else {
                        Nip25.buildReactionTags(event)
                    }

                    if (interfacePrefs.isClientTagEnabled()) {
                        tags = tags + listOf(listOf("client", "Pinket"))
                    }

                    val createdAt: Long
                    if (powPrefs.isReactionPowEnabled()) {
                        val pinned = System.currentTimeMillis() / 1000
                        val result = withContext(Dispatchers.Default) {
                            Nip13.mine(
                                pubkeyHex = myPubkey,
                                kind = 7,
                                content = emoji,
                                tags = tags,
                                targetDifficulty = powPrefs.getReactionDifficulty(),
                                createdAt = pinned
                            )
                        }
                        tags = result.tags
                        createdAt = result.createdAt
                    } else {
                        createdAt = System.currentTimeMillis() / 1000
                    }

                    val reactionEvent = s.signEvent(kind = 7, content = emoji, tags = tags, createdAt = createdAt)
                    val msg = ClientMessage.event(reactionEvent)
                    outboxRouter.publishToInbox(msg, event.pubkey)
                    eventRepo.addEvent(reactionEvent)
                    _reactionSent.tryEmit(Unit)
                    customEmojiRepo.recordEmojiUsage(emoji)
                }
            } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- pins (kind 10001)

    /** Pin ([wanted] = true) or unpin a note. A new pin is an `e` tag with a relay hint when one is known. */
    fun setPinned(eventId: String, wanted: Boolean, onResult: (ListResult) -> Unit = {}) {
        listWriter?.submit(Nip51.KIND_PIN_LIST, eTagFor(eventId), wanted, onResult = onResult)
    }

    /** A pin button tap: the wanted state is the opposite of what the button shows now (pending changes included). */
    fun togglePin(eventId: String) = setPinned(eventId, wanted = !pinRepo.isPinned(eventId))

    /** `["e", id, hint]`, the hint left out when none is known. */
    fun eTagFor(eventId: String): List<String> {
        val hint = eventRepo.getRelayHintsForEvents(setOf(eventId))[eventId]
        return if (hint.isNullOrBlank()) listOf("e", eventId) else listOf("e", eventId, hint)
    }

    fun deleteEvent(eventId: String, kind: Int) {
        val s = getSigner() ?: return
        scope.launch {
            try {
                val tags = Nip09.buildDeletionTags(eventId, kind)
                val deletionEvent = s.signEvent(kind = 5, content = "", tags = tags)
                relayPool.sendToWriteRelays(ClientMessage.event(deletionEvent))
                deletedEventsRepo.markDeleted(eventId)
                eventRepo.removeEvent(eventId)
            } catch (_: Exception) {}
        }
    }

    /**
     * Opens a subscription for zap receipts (kind 9735) targeting [eventId].
     * Kept open for 30s to catch the receipt whenever the LNURL provider publishes it.
     * Returns the subscription ID so the caller can close it early on failure.
     */
    fun subscribeZapReceipt(eventId: String): String {
        val subId = "zap-rcpt-${eventId.take(12)}"
        val filter = Filter(kinds = listOf(9735), eTags = listOf(eventId))
        relayPool.sendToReadRelays(ClientMessage.req(subId, filter))
        scope.launch {
            kotlinx.coroutines.delay(30_000)
            relayPool.closeOnAllRelays(subId)
        }
        return subId
    }

    /**
     * @param eventATag For parameterized replaceable events (e.g. kind 30311 live activities),
     *   pass the "a" tag value ("30311:pubkey:dTag"). Per NIP-57 the zap request must use an
     *   "a" tag instead of "e" so the receipt is associated with the addressable event.
     */
    fun sendZap(event: NostrEvent, amountMsats: Long, message: String = "", isAnonymous: Boolean = false, isPrivate: Boolean = false, extraRelayHints: List<String> = emptyList(), recipientOverride: String? = null, eventATag: String? = null) {
        // NIP-17 private reply targets must use DIP-03. The synthetic event's id is the
        // rumor id and its created_at is the rumor timestamp — both feed the existing
        // ephemeral derivation cleanly. Force the flag even if the caller forgot it so a
        // public-zap fallback can never leak the rumor id on public relays.
        @Suppress("NAME_SHADOWING")
        val isPrivate = isPrivate || eventRepo.isPrivate(event.id)
        val recipientPubkey = recipientOverride ?: event.pubkey
        val profileData = eventRepo.getProfileData(recipientPubkey)
        val lud16 = profileData?.lud16
        if (lud16.isNullOrBlank()) {
            _zapError.tryEmit("This user has no lightning address")
            return
        }
        // Reconnect wallet if credentials exist but not connected
        val wallet = getWalletProvider()
        if (wallet.hasConnection() && !wallet.isConnected.value) {
            wallet.connect()
        }
        scope.launch {
            _zapInProgress.value = _zapInProgress.value + event.id
            // Open receipt subscription BEFORE paying so we catch the 9735
            // even if the LNURL provider publishes it before NWC confirms
            val receiptSubId = if (eventATag != null) {
                // Addressable event — receipt will have an "a" tag, not "e"
                val subId = "zap-rcpt-${event.id.take(12)}"
                val filter = Filter(kinds = listOf(9735), aTags = listOf(eventATag))
                relayPool.sendToReadRelays(ClientMessage.req(subId, filter))
                scope.launch {
                    kotlinx.coroutines.delay(30_000)
                    relayPool.closeOnAllRelays(subId)
                }
                subId
            } else {
                subscribeZapReceipt(event.id)
            }
            // For private zaps, also subscribe on our DM relays — that's where
            // the LNURL will publish the receipt (per ZapSender's `relays` tag).
            // Subscriptions over DM relays go through NIP-42 AUTH automatically
            // via the relay's existing auth handshake.
            if (isPrivate && relayPool.hasDmRelays()) {
                val dmFilter = if (eventATag != null) {
                    Filter(kinds = listOf(9735), aTags = listOf(eventATag))
                } else {
                    Filter(kinds = listOf(9735), eTags = listOf(event.id))
                }
                relayPool.sendToDmRelays(ClientMessage.req("zap-rcpt-dm-${event.id.take(12)}", dmFilter))
            }
            // Also subscribe for receipt on extra relay hints (e.g. live stream chat relays)
            if (extraRelayHints.isNotEmpty()) {
                val hintFilter = if (eventATag != null) {
                    Filter(kinds = listOf(9735), aTags = listOf(eventATag))
                } else {
                    Filter(kinds = listOf(9735), eTags = listOf(event.id))
                }
                val hintSubId = "zap-rcpt-hint-${event.id.take(12)}"
                for (url in extraRelayHints) {
                    relayPool.sendToRelayOrEphemeral(url, ClientMessage.req(hintSubId, hintFilter))
                }
                scope.launch {
                    kotlinx.coroutines.delay(30_000)
                    relayPool.closeOnAllRelays(hintSubId)
                }
            }
            // For addressable events (e.g. live streams), use "a" tag instead of "e" tag
            // per NIP-57 so the receipt is properly associated with the event.
            val zapExtraTags = if (eventATag != null) listOf(listOf("a", eventATag)) else emptyList()
            val result = zapSender.sendZap(
                recipientLud16 = lud16,
                recipientPubkey = recipientPubkey,
                eventId = if (eventATag != null) null else event.id,
                amountMsats = amountMsats,
                message = message,
                isAnonymous = isAnonymous,
                isPrivate = isPrivate,
                extraTags = zapExtraTags,
                extraRelayHints = extraRelayHints,
                eventCreatedAt = event.created_at
            )
            _zapInProgress.value = _zapInProgress.value - event.id
            result.fold(
                onSuccess = {
                    val myPubkey = if (isAnonymous) "" else (getUserPubkey() ?: "")
                    eventRepo.addOptimisticZap(event.id, myPubkey, amountMsats / 1000, message, isPrivate)
                    _zapSuccess.tryEmit(event.id)
                },
                onFailure = { e ->
                    if (isPaymentInFlight(e)) {
                        // Payment was sent but we lost the connection before confirmation.
                        // Treat as success to avoid prompting the user to pay again.
                        val myPubkey = if (isAnonymous) "" else (getUserPubkey() ?: "")
                        eventRepo.addOptimisticZap(event.id, myPubkey, amountMsats / 1000, message, isPrivate)
                        _zapSuccess.tryEmit(event.id)
                    } else {
                        _zapError.tryEmit(e.message ?: "Zap failed")
                        // Close receipt subscription on failure
                        relayPool.closeOnAllRelays(receiptSubId)
                        if (isPrivate) relayPool.closeOnAllRelays("zap-rcpt-dm-${event.id.take(12)}")
                    }
                }
            )
        }
    }

    fun sendZapToPubkey(
        pubkey: String,
        amountMsats: Long,
        message: String = "",
        isAnonymous: Boolean = false,
        rumorId: String? = null
    ) {
        val lud16 = eventRepo.getProfileData(pubkey)?.lud16
        if (lud16.isNullOrBlank()) {
            _zapError.tryEmit("This user has no lightning address")
            return
        }
        val wallet = getWalletProvider()
        if (wallet.hasConnection() && !wallet.isConnected.value) wallet.connect()
        scope.launch {
            _zapInProgress.value = _zapInProgress.value + pubkey
            val result = zapSender.sendZap(
                recipientLud16 = lud16,
                recipientPubkey = pubkey,
                eventId = rumorId,
                amountMsats = amountMsats,
                message = message,
                isAnonymous = isAnonymous,
                isPrivate = false
            )
            _zapInProgress.value = _zapInProgress.value - pubkey
            result.fold(
                onSuccess = { _zapSuccess.tryEmit(pubkey) },
                onFailure = { e ->
                    if (isPaymentInFlight(e)) {
                        _zapSuccess.tryEmit(pubkey)
                    } else {
                        _zapError.tryEmit(e.message ?: "Zap failed")
                    }
                }
            )
        }
    }

    /**
     * Returns true if the exception indicates the payment request was sent
     * but we lost the wallet response (e.g. connection drop, cancellation).
     * In this case the payment is likely in-flight and we should NOT show
     * an error to avoid prompting the user to pay again.
     */
    private fun isPaymentInFlight(e: Throwable): Boolean {
        // CancellationException means the coroutine/connection was cancelled
        // after the pay request was already dispatched to the wallet.
        if (e is kotlinx.coroutines.CancellationException) return true
        // Wallet-returned errors (INSUFFICIENT_BALANCE, etc.) are plain Exceptions
        // with the error code in the message — those are real failures.
        return false
    }

    fun publishPollVote(pollEventId: String, optionIds: List<String>) {
        val s = getSigner() ?: return
        scope.launch {
            try {
                val tags = Nip88.buildResponseTags(pollEventId, optionIds).toMutableList()
                if (interfacePrefs.isClientTagEnabled()) {
                    tags.add(listOf("client", "Pinket"))
                }
                val event = s.signEvent(kind = Nip88.KIND_POLL_RESPONSE, content = "", tags = tags)
                val msg = ClientMessage.event(event)
                // Send to our write relays
                relayPool.sendToWriteRelays(msg)
                // Also send to the poll's specified relays per NIP-88
                val pollEvent = eventRepo.getEvent(pollEventId)
                if (pollEvent != null) {
                    for (url in Nip88.cappedPollRelays(pollEvent)) {
                        relayPool.sendToRelayOrEphemeral(url, msg)
                    }
                }
                // Optimistically add to eventRepo so UI updates immediately
                eventRepo.addEvent(event)
            } catch (_: Exception) {}
        }
    }

    fun sendZapPollVote(
        pollEvent: NostrEvent,
        optionIndex: Int,
        amountMsats: Long,
        message: String = "",
        isAnonymous: Boolean = false
    ) {
        val profileData = eventRepo.getProfileData(pollEvent.pubkey)
        val lud16 = profileData?.lud16
        if (lud16.isNullOrBlank()) {
            _zapError.tryEmit("This user has no lightning address")
            return
        }
        val wallet = getWalletProvider()
        if (wallet.hasConnection() && !wallet.isConnected.value) {
            wallet.connect()
        }
        scope.launch {
            _zapInProgress.value = _zapInProgress.value + pollEvent.id
            val receiptSubId = subscribeZapReceipt(pollEvent.id)
            val pollOptionTag = listOf("poll_option", optionIndex.toString())
            val result = zapSender.sendZap(
                recipientLud16 = lud16,
                recipientPubkey = pollEvent.pubkey,
                eventId = pollEvent.id,
                amountMsats = amountMsats,
                message = message,
                isAnonymous = isAnonymous,
                extraTags = listOf(pollOptionTag)
            )
            _zapInProgress.value = _zapInProgress.value - pollEvent.id
            result.fold(
                onSuccess = {
                    _zapSuccess.tryEmit(pollEvent.id)
                },
                onFailure = { e ->
                    if (isPaymentInFlight(e)) {
                        _zapSuccess.tryEmit(pollEvent.id)
                    } else {
                        _zapError.tryEmit(e.message ?: "Zap poll vote failed")
                        relayPool.closeOnAllRelays(receiptSubId)
                    }
                }
            )
        }
    }
}
