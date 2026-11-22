package com.wisp.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.PayOptions

@Composable
internal fun rememberProfile(
    eventRepo: EventRepository?,
    pubkey: String?,
    relayHints: List<String> = emptyList()
): ProfileData? {
    if (eventRepo == null || pubkey == null) return null
    val version by remember(eventRepo, pubkey) {
        eventRepo.profileVersionFor(pubkey)
    }.collectAsState(initial = 0)
    LaunchedEffect(eventRepo, pubkey, relayHints) {
        eventRepo.requestProfileIfMissing(pubkey, relayHints)
    }
    return remember(eventRepo, pubkey, version) { eventRepo.getProfileData(pubkey) }
}

/**
 * What [pubkey] can be paid with (Bitcoin via lightning address, Monero via their
 * kind 10133 list). Recomposes when either the profile or the pay list changes, and
 * queues a pay-list lookup if one has not finished recently. Use this for the money
 * button: it reflects only what the author accepts.
 */
@Composable
internal fun rememberPayOptions(eventRepo: EventRepository?, pubkey: String?): PayOptions {
    if (eventRepo == null || pubkey == null) return PayOptions.NONE
    val version by remember(eventRepo, pubkey) {
        eventRepo.profileVersionFor(pubkey)
    }.collectAsState(initial = 0)
    LaunchedEffect(eventRepo, pubkey) {
        eventRepo.requestPayTargetsIfMissing(pubkey)
    }
    return remember(eventRepo, pubkey, version) { eventRepo.getPayOptions(pubkey) }
}

@Composable
internal fun rememberObservedEvent(eventRepo: EventRepository?, eventId: String?): NostrEvent? {
    if (eventRepo == null || eventId == null) return null
    return key(eventRepo, eventId) {
        // Seeded from memory so a cached event is there on the first frame, not one frame later.
        val initial = remember(eventRepo, eventId) { eventRepo.peekEvent(eventId) }
        val event by remember(eventRepo, eventId) {
            eventRepo.observeEvent(eventId)
        }.collectAsState(initial = initial)
        event
    }
}

@Composable
internal fun rememberAddressableEvent(
    eventRepo: EventRepository,
    kind: Int,
    author: String,
    dTag: String
): NostrEvent? {
    return key(eventRepo, kind, author, dTag) {
        val initial = remember(eventRepo, kind, author, dTag) { eventRepo.peekAddressableEvent(kind, author, dTag) }
        val event by remember(eventRepo, kind, author, dTag) {
            eventRepo.observeAddressableEvent(kind, author, dTag)
        }.collectAsState(initial = initial)
        event
    }
}

/**
 * Whether [present] was true the first time this call site composed for [key]. A card that started
 * without its data keeps its placeholder size when the data arrives later in the same composition.
 */
@Composable
internal fun rememberPresentAtStart(key: Any?, present: Boolean): Boolean = remember(key) { present }
