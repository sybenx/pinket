package com.wisp.app.viewmodel

import android.util.Log
import com.wisp.app.nostr.Blossom
import com.wisp.app.nostr.DmMessage
import com.wisp.app.nostr.DmReaction
import com.wisp.app.nostr.EncryptedMedia
import com.wisp.app.nostr.DmZap
import com.wisp.app.nostr.Nip09
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.Nip17
import com.wisp.app.nostr.Nip22
import com.wisp.app.nostr.Nip53
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.Nip57
import com.wisp.app.nostr.Nip88
import com.wisp.app.nostr.Nip65
import com.wisp.app.nostr.NipA3
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.RelayScoreBoard
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.BookmarkRepository
import com.wisp.app.repo.BookmarkSetRepository
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.CustomEmojiRepository
import com.wisp.app.repo.DmRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.ExtendedNetworkRepository
import com.wisp.app.repo.InterestRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.ListRepository
import com.wisp.app.repo.MetadataFetcher
import com.wisp.app.repo.MuteRepository
import com.wisp.app.repo.OwnListStore
import com.wisp.app.repo.NotificationRepository
import com.wisp.app.repo.PinRepository
import com.wisp.app.repo.DiagnosticLogger
import com.wisp.app.repo.GroupRepository
import com.wisp.app.repo.LiveStreamRepository
import com.wisp.app.repo.RelayHintStore
import com.wisp.app.repo.RelayListRepository
import com.wisp.app.repo.RelaySetRepository
import com.wisp.app.repo.SigningMode
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes incoming relay events to the appropriate repositories based on subscription ID.
 * Extracted from FeedViewModel to reduce its size.
 */
class EventRouter(
    private val relayPool: RelayPool,
    private val eventRepo: EventRepository,
    private val contactRepo: ContactRepository,
    private val muteRepo: MuteRepository,
    private val notifRepo: NotificationRepository,
    private val listRepo: ListRepository,
    private val bookmarkRepo: BookmarkRepository,
    private val bookmarkSetRepo: BookmarkSetRepository,
    private val pinRepo: PinRepository,
    private val blossomRepo: BlossomRepository,
    private val customEmojiRepo: CustomEmojiRepository,
    private val relayListRepo: RelayListRepository,
    private val interestRepo: InterestRepository,
    private val relaySetRepo: RelaySetRepository,
    private val relayScoreBoard: RelayScoreBoard,
    private val relayHintStore: RelayHintStore,
    private val keyRepo: KeyRepository,
    private val dmRepo: DmRepository,
    private val extendedNetworkRepo: ExtendedNetworkRepository,
    private val groupRepo: GroupRepository?,
    private val liveStreamRepo: LiveStreamRepository?,
    private val metadataFetcher: MetadataFetcher,
    private val getUserPubkey: () -> String?,
    private val getSigner: () -> NostrSigner?,
    private val getFeedSubId: () -> String,
    private val getRelayFeedSubId: () -> String,
    private val getIsTrendingFeed: () -> Boolean,
    private val onRelayFeedEventReceived: () -> Unit,
    /** The active account's own-list store (null before an account is loaded). */
    private val getOwnListStore: () -> OwnListStore? = { null },
    /** A set was hidden or shown again here (a deletion request for it arrived): refresh what is shown. */
    private val onHiddenSetsChanged: () -> Unit = {},
    /** One of your lists the wipe guard watches got a new newest version here (from a relay or signed here). */
    private val onGuardedListApplied: () -> Unit = {}
) {
    /**
     * IDs of the user's own recent events used to build the notif-replies-etag subscription.
     * Updated by StartupCoordinator whenever the subscription is refreshed.
     * Used to verify that an event from notif-replies-etag is a DIRECT reply to one of our
     * posts, not just a nested reply in a thread we started.
     */
    @Volatile var myOwnEventIds: Set<String> = emptySet()

    // Track newest created_at per (pubkey, kind) to prevent stale overwrites
    // when the same self-data event arrives from multiple relays.
    private val selfDataTimestamps = ConcurrentHashMap<String, Long>()

    private fun isNewestSelfData(event: NostrEvent): Boolean {
        val key = "${event.pubkey}:${event.kind}"
        val existing = selfDataTimestamps[key]
        if (existing != null && event.created_at <= existing) return false
        selfDataTimestamps[key] = event.created_at
        return true
    }

    fun clearSelfDataTimestamps() {
        selfDataTimestamps.clear()
    }

    /**
     * Kind 1 notes replying to a 1111 comment are main-feed notes, not comment
     * replies — they must not bump the comment's reply count (thread views hide
     * them). Private rumor replies are exempt: private comment publishing is not
     * implemented yet, so those rumors are still kind 1.
     */
    private fun isStrayKind1OnComment(event: NostrEvent): Boolean =
        event.kind == 1 && !eventRepo.isPrivate(event.id) &&
            Nip22.isStrayKind1OnComment(event) { id -> eventRepo.getEvent(id)?.kind }

    /**
     * Offer an event to the own-list store first, before any repository sees it. Returns null when it is not one of
     * your own list events (or no store is loaded), else whether the store took it as your newest version.
     */
    private fun feedOwnList(event: NostrEvent): Boolean? {
        val store = getOwnListStore() ?: return null
        // Only the list kinds that ListWriter writes or reads (others are not kept: the store is one prefs file).
        if (!OwnListStore.keeps(event)) return null
        if (OwnListStore.keyOrNull(event) == null) return null
        if (event.pubkey.lowercase() != store.pubkeyHex) return null
        return store.seen(event)
    }

    /**
     * Load one of your own list versions into its repository (follows, mutes, bookmarks, pins, people, bookmark,
     * interest and emoji sets, the emoji list). Called for a version
     * the own-list store took as newest: from relays here, and from ListWriter after it signs.
     */
    suspend fun applyOwnList(event: NostrEvent) {
        val myPubkey = getUserPubkey() ?: return
        if (event.pubkey != myPubkey) return
        if (OwnListStore.isAddressable(event.kind)) showAgainIfNewer(event)
        if (com.wisp.app.repo.ListDrop.guarded(event.kind)) onGuardedListApplied()
        when (event.kind) {
            // Your profile, as the list writer found or signed it (incoming profiles reach the cache the usual way).
            0 -> eventRepo.addEvent(event)
            3 -> contactRepo.loadFromEvent(event)
            Nip51.KIND_MUTE_LIST -> {
                val s = getSigner()
                if (s != null) muteRepo.loadFromEvent(event, s) else muteRepo.loadFromEvent(event)
            }
            Nip51.KIND_BOOKMARK_LIST -> {
                val s = getSigner()
                if (s != null) bookmarkRepo.loadFromEvent(event, s) else bookmarkRepo.loadFromEvent(event)
            }
            Nip51.KIND_PIN_LIST -> pinRepo.loadFromEvent(event)
            Nip51.KIND_FOLLOW_SET -> listRepo.updateFromEvent(event, decryptOwn(event))
            Nip51.KIND_BOOKMARK_SET -> bookmarkSetRepo.updateFromEvent(event, decryptOwn(event))
            Nip51.KIND_INTEREST_SET -> interestRepo.updateFromEvent(event)
            Nip30.KIND_EMOJI_SET, Nip30.KIND_USER_EMOJI_LIST -> customEmojiRepo.updateFromEvent(event)
            else -> applyOwnRelayList(event)
        }
    }

    /**
     * Your relay lists, relay sets, groups and media servers, from the version the own-list store took as newest,
     * with your changes still waiting to be written laid over it (so a kept change, such as leaving a group, stays
     * shown and a group you left is not added back).
     */
    private fun applyOwnRelayList(event: NostrEvent) {
        // Only into the active account's settings (a list written for a key that is not active yet, during sign-up,
        // is saved by the screen that wrote it).
        val store = getOwnListStore() ?: return
        if (store.pubkeyHex != event.pubkey.lowercase()) return
        val shown = RelayListEdits.overlaid(event, store)
        // What the editors' rows now come from: they diff against this version (RelayListEdits.inStep).
        if (event.kind in RelayListEdits.EDITOR_KINDS) store.markProjected(OwnListStore.key(event.kind), event.id)
        when (event.kind) {
            10002 -> {
                // The per-relay sign-in switch is kept on this device only: keep it for every relay still listed.
                val auth = keyRepo.getRelays().associate { ListWriter.norm(it.url) to it.auth }
                val relays = Nip65.parseRelayList(shown).map { r -> auth[ListWriter.norm(r.url)]?.let { r.copy(auth = it) } ?: r }
                if (relays.isNotEmpty()) keyRepo.saveRelays(relays)
            }
            Nip51.KIND_DM_RELAYS -> {
                val urls = Nip51.parseRelaySet(shown)
                keyRepo.saveDmRelays(urls)
                relayPool.updateDmRelays(urls)
            }
            Nip51.KIND_SEARCH_RELAYS -> keyRepo.saveSearchRelays(Nip51.parseRelaySet(shown))
            Nip51.KIND_BLOCKED_RELAYS -> {
                val urls = Nip51.parseRelaySet(shown)
                keyRepo.saveBlockedRelays(urls)
                relayPool.updateBlockedUrls(urls)
            }
            // Relay sets and favourites lay the pending changes over themselves (RelaySetRepository.setOverlay).
            Nip51.KIND_FAVORITE_RELAYS -> relaySetRepo.updateFavoriteRelaysFromEvent(event)
            Nip51.KIND_RELAY_SET -> relaySetRepo.updateRelaySetFromEvent(event)
            Nip51.KIND_SIMPLE_GROUPS -> {
                val repo = groupRepo ?: return
                val existing = repo.getJoinedGroupKeys().toSet()
                for (entry in Nip51.parseSimpleGroups(shown)) {
                    if (Pair(entry.relayUrl, entry.groupId) !in existing) {
                        repo.addGroup(entry.relayUrl, entry.groupId, localName = entry.name)
                    }
                }
            }
            Blossom.KIND_SERVER_LIST -> blossomRepo.updateFromEvent(shown)
        }
    }

    /** The private part of your own set, or null (none, or it cannot be read). NIP-04 content stays readable. */
    private suspend fun decryptOwn(event: NostrEvent): String? {
        if (event.content.isBlank()) return null
        val s = getSigner() ?: return null
        return try {
            if (event.content.contains("?iv=")) s.nip04Decrypt(event.content, s.pubkeyHex)
            else s.nip44Decrypt(event.content, s.pubkeyHex)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A deletion request for your set (written by another app, or by this app before sets were hidden instead):
     * hide the set here unless a newer version is already known. Nothing is published.
     */
    private fun hideForDeletion(deletion: NostrEvent) {
        val store = getOwnListStore() ?: return
        var changed = false
        for (coord in Nip09.getDeletedAddresses(deletion)) {
            val parts = coord.split(":", limit = 3)
            if (parts.size != 3 || !parts[1].equals(store.pubkeyHex, ignoreCase = true)) continue
            val kind = parts[0].toIntOrNull() ?: continue
            if (kind !in ListCrudManager.SET_KINDS) continue
            // Kept with the request's date (also across sessions): a newer version shows the set again.
            if (store.hideForDeletion(kind, parts[2], deletion.created_at)) changed = true
        }
        if (changed) onHiddenSetsChanged()
    }

    /** A version of a set hidden for a deletion request that is newer than that request: show the set again. */
    private fun showAgainIfNewer(event: NostrEvent) {
        val store = getOwnListStore() ?: return
        if (store.showAgainIfNewer(event)) onHiddenSetsChanged()
    }

    suspend fun processRelayEvent(event: NostrEvent, relayUrl: String, subscriptionId: String) {
        // Own lists: the store decides what is newest; the repositories load only what it took.
        val ownList = feedOwnList(event)
        if (ownList == true && event.kind in PROJECTED_KINDS) applyOwnList(event)
        if (event.kind == Nip88.KIND_POLL_RESPONSE) {
            Log.d("POLL", "[EventRouter] received kind 1018 id=${event.id.take(12)} sub=$subscriptionId relay=$relayUrl")
        }
        if (event.kind in intArrayOf(20, 21, 22)) {
            Log.d("GALLERY", "[EventRouter] received kind=${event.kind} id=${event.id.take(12)} pubkey=${event.pubkey.take(8)} sub=$subscriptionId relay=$relayUrl")
        }
        if (subscriptionId == "dms") {
            if (event.kind == 1059) processGiftWrap(event, relayUrl)
            return
        }
        if (subscriptionId == "notif") {
            if (muteRepo.isBlocked(event.pubkey)) return
            val myPubkey = getUserPubkey()
            if (myPubkey != null) {
                when (event.kind) {
                    5 -> eventRepo.addEvent(event)
                    6 -> eventRepo.addEvent(event)
                    7 -> eventRepo.addEvent(event)
                    Nip88.KIND_POLL_RESPONSE -> eventRepo.addEvent(event)
                    9735 -> {
                        eventRepo.addEvent(event)
                        eventRepo.addEventRelay(event.id, relayUrl)
                        // Associate with a DM message if the e-tag points to a known rumorId
                        val eTagId = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                        if (eTagId != null) {
                            val (convKey, msgId) = dmRepo.findByRumorId(eTagId)
                                ?.let { it.first to it.second } ?: (null to null)
                            if (convKey != null && msgId != null) {
                                val sats = eventRepo.zapAmountSats(event)
                                if (sats > 0) {
                                    val zapperPubkey = eventRepo.resolveZapSender(event).first ?: event.pubkey
                                    dmRepo.addZap(convKey, msgId, DmZap(zapperPubkey, sats, event.created_at))
                                }
                            }
                        }
                    }
                    1, Nip22.KIND_COMMENT -> {
                        eventRepo.cacheEvent(event)
                        if (!Nip10.isStandaloneQuote(event) && !isStrayKind1OnComment(event)) {
                            val parentId = Nip10.getReplyTarget(event)
                            if (parentId != null && !eventRepo.isWotFiltered(event.pubkey, event.kind)) {
                                eventRepo.addReplyCount(parentId, event.id)
                            }
                        }
                    }
                    else -> eventRepo.cacheEvent(event)
                }
                notifRepo.addEvent(event, myPubkey, source = "notif")
                if (eventRepo.getProfileData(event.pubkey) == null) {
                    metadataFetcher.addToPendingProfiles(event.pubkey)
                }
                if (event.kind == 9735) {
                    val zapperPubkey = eventRepo.resolveZapSender(event).first
                    if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                        metadataFetcher.addToPendingProfiles(zapperPubkey)
                    }
                }
            }
        } else if (subscriptionId == "notif-replies-etag") {
            if (muteRepo.isBlocked(event.pubkey)) return
            val myPubkey = getUserPubkey()
            if (myPubkey != null && (event.kind == 1 || event.kind == Nip22.KIND_COMMENT)) {
                eventRepo.cacheEvent(event)
                val parentId = if (!Nip10.isStandaloneQuote(event) && !isStrayKind1OnComment(event)) {
                    Nip10.getReplyTarget(event)
                } else null
                if (parentId != null && !eventRepo.isWotFiltered(event.pubkey, event.kind)) {
                    eventRepo.addReplyCount(parentId, event.id)
                }
                // Only bypass the p-tag check if this is a DIRECT reply to one of our own
                // events. The #e subscription also returns nested thread replies (where our
                // event is the root ancestor but not the direct parent) — those should not
                // be unconditionally accepted without a p-tag.
                val isDirectReplyToMe = parentId != null && parentId in myOwnEventIds
                notifRepo.addEvent(event, myPubkey, replyToMyEvent = isDirectReplyToMe, source = "notif-replies-etag")
                if (eventRepo.getProfileData(event.pubkey) == null) {
                    metadataFetcher.addToPendingProfiles(event.pubkey)
                }
            }
        } else if (subscriptionId == "notif-quotes-qtag") {
            if (muteRepo.isBlocked(event.pubkey)) return
            val myPubkey = getUserPubkey()
            if (myPubkey != null && event.kind == 1) {
                eventRepo.cacheEvent(event)
                notifRepo.addEvent(event, myPubkey, source = "notif-quotes-qtag")
                if (eventRepo.getProfileData(event.pubkey) == null) {
                    metadataFetcher.addToPendingProfiles(event.pubkey)
                }
            }
        } else if (subscriptionId == "self-notes") {
            eventRepo.cacheEvent(event)
            if (event.kind == 1 || event.kind == Nip22.KIND_COMMENT) {
                val parentId = if (isStrayKind1OnComment(event)) null else Nip10.getReplyTarget(event)
                if (parentId != null) eventRepo.addReplyCount(parentId, event.id)
            }
        } else if (subscriptionId.startsWith("qpoll-")) {
            // Poll vote responses for quoted polls
            if (event.kind == Nip88.KIND_POLL_RESPONSE) {
                eventRepo.addEvent(event)
            }
        } else if (subscriptionId.startsWith("qzpoll-")) {
            // Zap receipts for quoted zap polls
            if (event.kind == 9735) {
                eventRepo.addEvent(event)
            }
        } else if (subscriptionId.startsWith("quote-")) {
            eventRepo.cacheEvent(event)
            if (event.kind == 1 && eventRepo.getProfileData(event.pubkey) == null) {
                metadataFetcher.addToPendingProfiles(event.pubkey)
            }
        } else if (subscriptionId.startsWith("reply-count-")) {
            if (event.kind == 1 || event.kind == Nip22.KIND_COMMENT) {
                eventRepo.cacheEvent(event)
                if (!isStrayKind1OnComment(event)) {
                    val parentId = Nip10.getReplyTarget(event)
                    if (parentId != null && !eventRepo.isWotFiltered(event.pubkey, event.kind)) {
                        eventRepo.addReplyCount(parentId, event.id)
                    }
                }
            }
        } else if (subscriptionId.startsWith("zap-count-") || subscriptionId.startsWith("zap-rcpt-")) {
            if (event.kind == 9735) {
                eventRepo.addEvent(event)
                eventRepo.addEventRelay(event.id, relayUrl)
                val zapperPubkey = eventRepo.resolveZapSender(event).first
                if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                    metadataFetcher.addToPendingProfiles(zapperPubkey)
                }
                // Associate with a DM message if the e-tag points to a known rumorId
                val eTagId = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                if (eTagId != null) {
                    val found = dmRepo.findByRumorId(eTagId)
                    if (found != null) {
                        val sats = eventRepo.zapAmountSats(event)
                        if (sats > 0) {
                            val zapper = zapperPubkey ?: event.pubkey
                            dmRepo.addZap(found.first, found.second, DmZap(zapper, sats, event.created_at))
                        }
                    }
                }
            }
        } else if (subscriptionId == "thread-root" || subscriptionId == "thread-replies" ||
                   subscriptionId.startsWith("thread-reactions")) {
            // ThreadViewModel handles these via its own RelayPool collector — skip entirely
            return
        } else if (subscriptionId.startsWith("engage") || subscriptionId.startsWith("user-engage")) {
            when (event.kind) {
                5 -> eventRepo.addEvent(event)
                6 -> eventRepo.addEvent(event)
                7 -> eventRepo.addEvent(event)
                Nip88.KIND_POLL_RESPONSE -> {
                    Log.d("POLL", "[EventRouter] kind 1018 from sub=$subscriptionId pubkey=${event.pubkey.take(8)} pollId=${Nip88.getPollEventId(event)?.take(12)} options=${Nip88.getResponseOptionIds(event)}")
                    eventRepo.addEvent(event)
                }
                9735 -> {
                    eventRepo.addEvent(event)
                    eventRepo.addEventRelay(event.id, relayUrl)
                    val zapperPubkey = eventRepo.resolveZapSender(event).first
                    if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                        metadataFetcher.addToPendingProfiles(zapperPubkey)
                    }
                }
                1, Nip22.KIND_COMMENT -> {
                    eventRepo.cacheEvent(event)
                    if (!isStrayKind1OnComment(event)) {
                        val parentId = Nip10.getReplyTarget(event)
                        if (parentId != null && !eventRepo.isWotFiltered(event.pubkey, event.kind)) {
                            eventRepo.addReplyCount(parentId, event.id)
                        }
                    }
                }
            }
            // Engagement events win the dedup race against "notif" subscription,
            // so also route notification-eligible events to notifRepo here.
            // For kind 6 reposts, only notify if the reposted event is ours
            // (engagement subs fetch reposts for all viewed posts, not just ours).
            val myPubkey = getUserPubkey()
            val isNotifEligible = myPubkey != null && event.pubkey != myPubkey &&
                event.kind in intArrayOf(1, Nip22.KIND_COMMENT, 6, 7, 9735, Nip88.KIND_POLL_RESPONSE) &&
                !muteRepo.isBlocked(event.pubkey)
            val isRepostOfOther = event.kind == 6 && run {
                val repostedId = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                val repostedEvent = repostedId?.let { eventRepo.getEvent(it) }
                repostedEvent == null || repostedEvent.pubkey != myPubkey
            }
            if (isNotifEligible && !isRepostOfOther) {
                notifRepo.addEvent(event, myPubkey!!, source = subscriptionId)
                if (eventRepo.getProfileData(event.pubkey) == null) {
                    metadataFetcher.addToPendingProfiles(event.pubkey)
                }
            }
        } else if (subscriptionId.startsWith("extnet-k3")) {
            // Extended network discovery: kind 3 follow lists — route to repo, NOT feed
            if (event.kind == 3) {
                Log.d("EventRouter", "Routing kind 3 from sub=$subscriptionId pubkey=${event.pubkey.take(8)}")
                extendedNetworkRepo.processFollowListEvent(event)
            }
        } else if (subscriptionId == "extnet-mute") {
            // Extended network discovery: follows' public mute lists. Collected
            // directly by ExtendedNetworkRepository; never route into feeds.
            Unit
        } else if (subscriptionId.startsWith("extnet-rl-")) {
            // Extended network discovery: relay lists — update relay list cache
            if (event.kind == 10002) relayListRepo.updateFromEvent(event)
        } else if (subscriptionId.startsWith("onb-")) {
            // Onboarding suggestion fetches — only cache kind 0 profiles, don't add to feed
            if (event.kind == 0) eventRepo.cacheEvent(event)
        } else if (subscriptionId.startsWith("fetch-bkset-") || subscriptionId == "fetch-bookmarks") {
            // Bookmark/list event fetches — cache the notes so screens can display them
            eventRepo.cacheEvent(event)
            if (eventRepo.getProfileData(event.pubkey) == null) {
                metadataFetcher.addToPendingProfiles(event.pubkey)
            }
        } else if (subscriptionId == "live-activities") {
            if (event.kind == Nip53.KIND_LIVE_ACTIVITY) {
                liveStreamRepo?.addActivity(event)
                // Also cache in eventRepo so LiveStreamCard can find it via findAddressableEvent
                eventRepo.cacheEvent(event)
                if (eventRepo.getProfileData(event.pubkey) == null) {
                    metadataFetcher.addToPendingProfiles(event.pubkey)
                }
            }
            return
        } else if (subscriptionId == "live-chat-discovery") {
            // Discovery only — track chatter counts for pill ranking, don't consume from dedup
            if (event.kind == Nip53.KIND_LIVE_CHAT_MESSAGE) {
                liveStreamRepo?.trackChatter(event)
            }
            return
        } else if (subscriptionId.startsWith("live-chat-") || subscriptionId.startsWith("live-react-") || subscriptionId.startsWith("live-zap-")) {
            when (event.kind) {
                Nip53.KIND_LIVE_CHAT_MESSAGE -> {
                    eventRepo.cacheEvent(event)
                    liveStreamRepo?.addChatMessage(event)
                }
                7 -> {
                    val eTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                    val aTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "a" }?.get(1)
                    if (eTag != null && aTag != null) {
                        liveStreamRepo?.addReaction(aTag, eTag, event.pubkey, event.content)
                    }
                }
                9735 -> {
                    eventRepo.addEvent(event)
                    eventRepo.addEventRelay(event.id, relayUrl)
                    // Route stream-level zaps to liveStreamRepo for chat announcements
                    var zapATag = event.tags.firstOrNull { it.size >= 2 && it[0] == "a" }?.get(1)
                    if (zapATag == null) {
                        // Some LNURL servers only include e-tag, not a-tag.
                        // If the e-tag points to a cached 30311 event, derive the a-tag.
                        val eTagId = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                        if (eTagId != null) {
                            val targetEvent = eventRepo.getEvent(eTagId)
                            if (targetEvent?.kind == Nip53.KIND_LIVE_ACTIVITY) {
                                val dTag = targetEvent.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
                                if (dTag != null) {
                                    zapATag = Nip53.aTagValue(targetEvent.pubkey, dTag)
                                }
                            }
                        }
                    }
                    if (zapATag != null) {
                        liveStreamRepo?.addStreamZap(event, zapATag)
                    }
                    val zapperPubkey = eventRepo.resolveZapSender(event).first
                    if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                        metadataFetcher.addToPendingProfiles(zapperPubkey)
                    }
                }
            }
            if (eventRepo.getProfileData(event.pubkey) == null) {
                metadataFetcher.addToPendingProfiles(event.pubkey)
            }
            return
        } else {
            if (event.kind == 10002) {
                relayListRepo.updateFromEvent(event)
                val myPubkey = getUserPubkey()
                if (DiagnosticLogger.isEnabled && event.kind in SELF_DATA_KINDS) {
                    val isMine = myPubkey != null && event.pubkey == myPubkey
                    DiagnosticLogger.log("SELF_DATA", "kind=${event.kind} pubkey=${event.pubkey.take(8)} " +
                        "myPubkey=${myPubkey?.take(8)} isMine=$isMine sub=$subscriptionId relay=$relayUrl")
                }
                // Your own relay list loads through applyOwnList once the own-list store took it.
                if (ownList == null && myPubkey != null && event.pubkey == myPubkey && isNewestSelfData(event)) {
                    val relays = Nip65.parseRelayList(event)
                    if (relays.isNotEmpty()) {
                        keyRepo.saveRelays(relays)
                        // Don't call relayPool.updateRelays() here — it would replace
                        // the full pool (pinned+scored+extended ~150 relays) with just
                        // the user's ~5-10 NIP-65 relays, destroying active subscriptions.
                        // The saved list is picked up on next startup by rebuildRelayPool().
                    }
                }
            }
            if (event.kind == Nip51.KIND_DM_RELAYS) {
                relayListRepo.updateDmRelaysFromEvent(event)
                val myPubkey = getUserPubkey()
                if (ownList == null && myPubkey != null && event.pubkey == myPubkey && isNewestSelfData(event)) {
                    val urls = Nip51.parseRelaySet(event)
                    keyRepo.saveDmRelays(urls)
                    relayPool.updateDmRelays(urls)
                }
            }
            if (event.kind == Nip51.KIND_SEARCH_RELAYS && ownList == null) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey && isNewestSelfData(event)) {
                    keyRepo.saveSearchRelays(Nip51.parseRelaySet(event))
                }
            }
            if (event.kind == Nip51.KIND_BLOCKED_RELAYS && ownList == null) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey && isNewestSelfData(event)) {
                    val urls = Nip51.parseRelaySet(event)
                    keyRepo.saveBlockedRelays(urls)
                    relayPool.updateBlockedUrls(urls)
                }
            }
            if (event.kind == Nip51.KIND_MUTE_LIST && ownList == null) {
                val myPubkey = getUserPubkey()
                val s = getSigner()
                if (myPubkey != null && event.pubkey == myPubkey) muteRepo.loadFromEventIfNewer(event, s)
            }
            if (event.kind == Nip51.KIND_BOOKMARK_LIST && ownList == null) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) bookmarkRepo.loadFromEventIfNewer(event)
            }
            if (event.kind == Nip51.KIND_PIN_LIST && ownList == null) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) pinRepo.loadFromEventIfNewer(event)
            }
            if (event.kind == Blossom.KIND_SERVER_LIST && ownList == null) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey && isNewestSelfData(event)) blossomRepo.updateFromEvent(event)
            }
            // Your own sets and emoji list load through applyOwnList once the own-list store took them.
            if (event.kind == Nip51.KIND_FOLLOW_SET && ownList == null) {
                val myPubkey = getUserPubkey()
                val s = getSigner()
                val decrypted = if (myPubkey != null && s != null && event.pubkey == myPubkey && event.content.isNotBlank()) {
                    try { s.nip44Decrypt(event.content, myPubkey) } catch (_: Exception) { null }
                } else null
                listRepo.updateFromEvent(event, decrypted)
            }
            if (event.kind == Nip51.KIND_INTEREST_SET && ownList == null) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) {
                    interestRepo.updateFromEvent(event)
                }
            }
            if (event.kind == 5) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) {
                    // Run through EventRepository so deletion coords/ids are persisted
                    // in DeletedEventsRepository. Safe to call repeatedly.
                    eventRepo.addEvent(event)
                    // Your sets are never dropped for a deletion request: they are hidden here instead (shown
                    // again by a newer version, or by making a set with that name).
                    hideForDeletion(event)
                }
            }
            if (event.kind == Nip51.KIND_BOOKMARK_SET && ownList == null) {
                val myPubkey = getUserPubkey()
                val s = getSigner()
                val decrypted = if (myPubkey != null && s != null && event.pubkey == myPubkey && event.content.isNotBlank()) {
                    try { s.nip44Decrypt(event.content, myPubkey) } catch (_: Exception) { null }
                } else null
                bookmarkSetRepo.updateFromEvent(event, decrypted)
            }
            if (event.kind == Nip51.KIND_RELAY_SET && ownList == null) {
                relaySetRepo.updateRelaySetFromEvent(event)
            }
            if (event.kind == Nip51.KIND_FAVORITE_RELAYS && ownList == null) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) {
                    relaySetRepo.updateFavoriteRelaysFromEvent(event)
                }
            }
            if (event.kind == Nip51.KIND_SIMPLE_GROUPS && ownList == null) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey && isNewestSelfData(event)) {
                    val groups = Nip51.parseSimpleGroups(event)
                    val repo = groupRepo
                    if (repo != null && groups.isNotEmpty()) {
                        val existing = repo.getJoinedGroupKeys().toSet()
                        for (entry in groups) {
                            val key = Pair(entry.relayUrl, entry.groupId)
                            if (key !in existing) {
                                repo.addGroup(entry.relayUrl, entry.groupId, localName = entry.name)
                            }
                        }
                    }
                }
            }
            if (event.kind == Nip30.KIND_USER_EMOJI_LIST && ownList == null) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) customEmojiRepo.updateFromEvent(event)
            }
            if (event.kind == Nip30.KIND_EMOJI_SET && ownList == null) customEmojiRepo.updateFromEvent(event)

            // Only add to feed for feed-related subscriptions;
            // other subs (user profile, bookmarks, threads) just cache
            // Track author provenance and feed hints into scoreboard
            relayHintStore.addAuthorRelay(event.pubkey, relayUrl)
            if (!relayListRepo.hasRelayList(event.pubkey)) {
                relayScoreBoard.addHintRelays(event.pubkey, listOf(relayUrl))
            }
            for (tag in event.tags) {
                if (tag.size >= 3 && tag[0] == "p") {
                    val url = tag[2].trimEnd('/')
                    if (RelayConfig.isValidUrl(url) && !relayListRepo.hasRelayList(tag[1])) {
                        relayScoreBoard.addHintRelays(tag[1], listOf(url))
                    }
                }
            }

            val feedSubId = getFeedSubId()
            val relayFeedSubId = getRelayFeedSubId()
            val isFeedSub = subscriptionId == feedSubId ||
                subscriptionId == "loadmore" ||
                subscriptionId == "feed-backfill"
            val isRelayFeedSub = subscriptionId == relayFeedSubId ||
                subscriptionId == "relay-loadmore"
            if (isRelayFeedSub && subscriptionId.startsWith("trending-users-")) {
                // Kind 0 events collected directly by subscribeTrendingUsers() — skip feed routing
                return
            }
            if (isRelayFeedSub) {
                eventRepo.cacheEvent(event)
                if (getIsTrendingFeed()) {
                    eventRepo.addTrendingFeedEvent(event)
                } else {
                    eventRepo.addRelayFeedEvent(event)
                }
                onRelayFeedEventReceived()
                eventRepo.addEventRelay(event.id, relayUrl)
                if (event.kind == 1 || event.kind == 30023) {
                    metadataFetcher.fetchQuotedEvents(event)
                    if (eventRepo.getProfileData(event.pubkey) == null) {
                        metadataFetcher.addToPendingProfiles(event.pubkey)
                    }
                }
                if (event.kind == 6 && event.content.isNotBlank()) {
                    try {
                        val inner = NostrEvent.fromJson(event.content)
                        if (eventRepo.getProfileData(inner.pubkey) == null) {
                            metadataFetcher.addToPendingProfiles(inner.pubkey)
                        }
                        metadataFetcher.fetchQuotedEvents(inner)
                    } catch (_: Exception) {}
                }
            } else if (isFeedSub) {
                if (event.kind in intArrayOf(20, 21, 22)) {
                    android.util.Log.d("GALLERY", "[EventRouter] feed sub gallery event kind=${event.kind} id=${event.id.take(12)} pubkey=${event.pubkey.take(8)} sub=$subscriptionId relay=$relayUrl")
                }
                eventRepo.addEvent(event)
                eventRepo.addEventRelay(event.id, relayUrl)
                if (event.kind == 1 || event.kind == 30023) {
                    metadataFetcher.fetchQuotedEvents(event)
                    if (eventRepo.getProfileData(event.pubkey) == null) {
                        metadataFetcher.addToPendingProfiles(event.pubkey)
                    }
                }
                if (event.kind == 6 && event.content.isNotBlank()) {
                    try {
                        val inner = NostrEvent.fromJson(event.content)
                        if (eventRepo.getProfileData(inner.pubkey) == null) {
                            metadataFetcher.addToPendingProfiles(inner.pubkey)
                        }
                        metadataFetcher.fetchQuotedEvents(inner)
                    } catch (_: Exception) {}
                }
            } else {
                eventRepo.cacheEvent(event)
                // NIP-38: route user status events to addEvent so they get processed
                if (event.kind == 30315) eventRepo.addEvent(event)
            }
            // Always handle follow list updates (from self-data subscription)
            if (event.kind == 3 && ownList == null) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) contactRepo.loadFromEventIfNewer(event)
            }
        }
    }

    companion object {
        /** Own list kinds whose repositories load through [applyOwnList] once the own-list store takes a version. */
        val PROJECTED_KINDS = setOf(
            3, Nip51.KIND_MUTE_LIST, Nip51.KIND_BOOKMARK_LIST, Nip51.KIND_PIN_LIST,
            Nip51.KIND_FOLLOW_SET, Nip51.KIND_BOOKMARK_SET, Nip51.KIND_INTEREST_SET,
            Nip30.KIND_EMOJI_SET, Nip30.KIND_USER_EMOJI_LIST,
            10002, Nip51.KIND_DM_RELAYS, Nip51.KIND_SEARCH_RELAYS, Nip51.KIND_BLOCKED_RELAYS,
            Nip51.KIND_FAVORITE_RELAYS, Nip51.KIND_RELAY_SET, Nip51.KIND_SIMPLE_GROUPS, Blossom.KIND_SERVER_LIST
        )

        private val SELF_DATA_KINDS = intArrayOf(
            0, 3, 5, 10002, 10050, 10007, 10006,
            Nip51.KIND_MUTE_LIST, Nip51.KIND_SIMPLE_GROUPS, Nip51.KIND_BOOKMARK_LIST,
            Nip51.KIND_PIN_LIST, Blossom.KIND_SERVER_LIST, Nip51.KIND_FOLLOW_SET,
            Nip51.KIND_INTEREST_SET, Nip51.KIND_BOOKMARK_SET, Nip51.KIND_RELAY_SET,
            Nip51.KIND_FAVORITE_RELAYS, Nip30.KIND_USER_EMOJI_LIST, Nip30.KIND_EMOJI_SET,
            NipA3.KIND_PAYMENT_TARGETS
        )
    }

    private fun processGiftWrap(event: NostrEvent, relayUrl: String) {
        dmRepo.updateLatestGiftWrapTimestamp(event.created_at)
        if (dmRepo.isGiftWrapDeleted(event.id)) return
        val keypair = keyRepo.getKeypair() ?: return
        val myPubkey = keypair.pubkey.toHex()

        val rumor = try {
            Nip17.unwrapGiftWrap(keypair.privkey, event)
        } catch (e: Exception) {
            Log.w("EventRouter", "Failed to unwrap gift wrap ${event.id}: ${e.message}")
            null
        } ?: return

        // Private reaction (kind 7 rumor). The "k" tag carries the kind of the message
        // being reacted to: k=1 → reaction on a NIP-17 private reply, route through the
        // note repository so thread/notification rendering picks it up the same way as
        // public reactions; k=14 (or absent for back-compat) → DM reaction, route into
        // the DM conversation entry.
        if (Nip17.isReaction(rumor)) {
            val kTag = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "k" }?.get(1)
            if (kTag == "1") {
                handlePrivateReplyReaction(rumor, myPubkey)
                return
            }
            val targetId = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return
            val participants = Nip17.getConversationParticipants(rumor, myPubkey)
            if (participants.any { muteRepo.isBlocked(it) }) return
            val convKey = DmRepository.conversationKey(participants + myPubkey)
            val emojiContent = rumor.content.trim()
            val emojiUrl = if (emojiContent.startsWith(":") && emojiContent.endsWith(":")) {
                Nip30.parseEmojiTags(rumor.tags)[emojiContent.removeSurrounding(":")]
            } else null
            dmRepo.addReaction(convKey, targetId, DmReaction(rumor.pubkey, emojiContent, rumor.createdAt, emojiUrl))
            return
        }

        // NIP-17 private reply — kind 1 rumor with NIP-10 reply tags.
        // Surface as a normal reply in threads + notifications, flagged as private.
        if (rumor.kind == 1) {
            handlePrivateReply(event, rumor, myPubkey)
            return
        }

        val participants = Nip17.getConversationParticipants(rumor, myPubkey)
        if (participants.any { muteRepo.isBlocked(it) }) return

        val convKey = DmRepository.conversationKey(participants + myPubkey)
        val replyToId = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "e" && it.any { v -> v == "reply" } }?.get(1)
        val rumorId = Nip17.computeRumorId(rumor)
        val emojiMap = Nip30.parseEmojiTags(rumor.tags)
        val fileMetadata = if (Nip17.isFileMessage(rumor)) {
            EncryptedMedia.parseKind15Tags(rumor.tags, rumor.content)
        } else null
        val msg = DmMessage(
            id = "${event.id}:${rumor.createdAt}",
            senderPubkey = rumor.pubkey,
            content = rumor.content,
            createdAt = rumor.createdAt,
            giftWrapId = event.id,
            relayUrls = if (relayUrl.isNotEmpty()) setOf(relayUrl) else emptySet(),
            rumorId = rumorId,
            replyToId = replyToId,
            participants = participants,
            emojiMap = emojiMap,
            encryptedFileMetadata = fileMetadata,
            debugGiftWrapJson = event.toJson(),
            debugRumorJson = Nip17.rumorToJson(rumor)
        )
        dmRepo.addMessage(msg, convKey)
    }

    private fun handlePrivateReplyReaction(rumor: Nip17.Rumor, myPubkey: String) {
        if (muteRepo.isBlocked(rumor.pubkey)) return
        val targetId = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return
        if (eventRepo.getEvent(targetId) == null) {
            // Without the target rumor cached we can't bind ownership in NotificationRepository,
            // which would surface reactions on threads we never received. Drop quietly — the
            // target arrives via the same kind-1059 subscription, so a refetch will heal.
            Log.d("EventRouter", "Skipping private reaction on uncached target ${targetId.take(12)}")
            return
        }
        val rumorId = Nip17.computeRumorId(rumor)
        val synthetic = NostrEvent(
            id = rumorId,
            pubkey = rumor.pubkey,
            created_at = rumor.createdAt,
            kind = 7,
            tags = rumor.tags,
            content = rumor.content,
            sig = ""
        )
        eventRepo.markPrivate(rumorId)
        eventRepo.addEvent(synthetic)
        if (rumor.pubkey == myPubkey) return
        notifRepo.addEvent(synthetic, myPubkey, source = "gift-wrap-private-reaction")
        if (eventRepo.getProfileData(rumor.pubkey) == null) {
            metadataFetcher.addToPendingProfiles(rumor.pubkey)
        }
    }

    private fun handlePrivateReply(wrap: NostrEvent, rumor: Nip17.Rumor, myPubkey: String) {
        if (muteRepo.isBlocked(rumor.pubkey)) return

        val rumorId = Nip17.computeRumorId(rumor)
        val synthetic = NostrEvent(
            id = rumorId,
            pubkey = rumor.pubkey,
            created_at = rumor.createdAt,
            kind = 1,
            tags = rumor.tags,
            content = rumor.content,
            sig = ""
        )
        eventRepo.markPrivate(rumorId)
        eventRepo.cacheEvent(synthetic)
        if (!Nip10.isStandaloneQuote(synthetic)) {
            val parentId = Nip10.getReplyTarget(synthetic)
            if (parentId != null) eventRepo.addReplyCount(parentId, synthetic.id)
        }
        // Self-copy wraps from another device land here too — skip the notification, but
        // the cache write above still surfaces the reply in our thread view.
        if (rumor.pubkey == myPubkey) return
        notifRepo.addEvent(synthetic, myPubkey, replyToMyEvent = true, source = "gift-wrap-private-reply")
        if (eventRepo.getProfileData(rumor.pubkey) == null) {
            metadataFetcher.addToPendingProfiles(rumor.pubkey)
        }
    }
}
