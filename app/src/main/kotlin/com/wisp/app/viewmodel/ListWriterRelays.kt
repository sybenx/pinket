package com.wisp.app.viewmodel

import android.content.Context
import android.util.Log
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.OwnListStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The created_at of what the old repositories had loaded per list, read from their prefs. Seeds the own-list store's
 * watermarks the first time it is opened for an account (see OwnListStore), so an older relay copy never replaces
 * what this device already showed.
 */
fun ownListLegacyWatermarks(context: Context, pubkeyHex: String): Map<String, Long> {
    fun read(prefs: String, key: String): Long = try {
        context.getSharedPreferences("${prefs}_$pubkeyHex", Context.MODE_PRIVATE).getLong(key, 0L)
    } catch (e: Exception) {
        0L
    }
    return mapOf(
        "3" to read("wisp_contacts", "follows_updated"),
        "10000" to read("wisp_mutes", "mute_updated"),
        "10003" to read("wisp_bookmarks", "bookmark_updated"),
        "10001" to read("wisp_pins", "pin_updated")
    )
}

/** The own-list store of an account, opened the one way production uses (legacy watermarks, ListWrite entry keys). */
fun openOwnListStore(context: Context, pubkeyHex: String): OwnListStore =
    OwnListStore.open(
        context, pubkeyHex,
        legacyWatermarks = ownListLegacyWatermarks(context, pubkeyHex),
        entryId = { tag -> ListWriter.entryId(tag) }
    )

/** A [ListAccount] for a signer that is not (yet) the app's active one, e.g. during onboarding. */
fun openListAccount(context: Context, signer: NostrSigner): ListAccount =
    ListAccount(signer, openOwnListStore(context, signer.pubkeyHex))

/**
 * [ListSource] over [RelayPool]: one REQ `listw-<kind>-<n>` to each relay (dedup bypass and subscription-cap
 * priority are registered for the prefix), answers counted per relay. Returns when every relay asked has sent EOSE,
 * or 2 s after the first EOSE from a WRITE relay once a majority of the write relays answered, or after 6 s.
 */
class RelayListSource(
    private val relayPool: RelayPool,
    private val graceMs: Long = 2_000,
    private val capMs: Long = 6_000
) : ListSource {
    init {
        relayPool.registerDedupBypass(PREFIX)
    }

    override suspend fun fetch(request: ListFetchRequest): ListFetchResult = coroutineScope {
        val subId = "$PREFIX${request.kind}-${counter.incrementAndGet()}"
        val filter = Filter(
            kinds = listOf(request.kind),
            authors = listOf(request.author),
            dTags = request.d?.let { listOf(it) },
            limit = 5
        )
        val msg = ClientMessage.req(subId, filter)
        val writeSet = request.writeRelays.map { ListWriter.norm(it) }.toSet()
        val events = ConcurrentLinkedQueue<NostrEvent>()
        val answered = ConcurrentHashMap.newKeySet<String>()
        val eosed = ConcurrentHashMap.newKeySet<String>()
        val firstWriteEose = AtomicLong(0)

        // Collect before sending: the flows do not replay.
        val evJob = launch(start = CoroutineStart.UNDISPATCHED) {
            relayPool.relayEvents.collect {
                if (it.subscriptionId == subId) {
                    events.add(it.event)
                    answered.add(ListWriter.norm(it.relayUrl))
                }
            }
        }
        val eoseJob = launch(start = CoroutineStart.UNDISPATCHED) {
            relayPool.relayEoses.collect {
                if (it.subscriptionId == subId) {
                    val u = ListWriter.norm(it.relayUrl)
                    answered.add(u)
                    eosed.add(u)
                    if (u in writeSet) firstWriteEose.compareAndSet(0, System.currentTimeMillis())
                }
            }
        }

        val asked = mutableListOf<String>()
        try {
            for (url in (request.writeRelays + request.otherRelays).distinctBy { ListWriter.norm(it) }) {
                // A known relay that is down would only queue the REQ for later: it is not asked now.
                if (relayPool.isKnownRelay(url) && !relayPool.isRelayConnected(url)) continue
                val sent = try {
                    relayPool.sendToRelayOrEphemeral(url, msg, skipBadCheck = true)
                } catch (e: Exception) {
                    false
                }
                if (sent) asked += url
            }
            val askedNorm = asked.map { ListWriter.norm(it) }.toSet()
            val writeAsked = askedNorm.filter { it in writeSet }.toSet()
            val start = System.currentTimeMillis()
            if (askedNorm.isNotEmpty()) {
                while (true) {
                    val now = System.currentTimeMillis()
                    if (askedNorm.all { it in eosed }) break
                    val t1 = firstWriteEose.get()
                    val writeAnswered = writeAsked.count { it in answered }
                    if (t1 > 0 && now - t1 >= graceMs && writeAnswered * 2 > writeAsked.size) break
                    if (now - start >= capMs) break
                    delay(50)
                }
                // EVENT and EOSE travel on different flows: let events already sent before an EOSE arrive.
                delay(150)
            }
        } finally {
            evJob.cancel()
            eoseJob.cancel()
            for (url in asked) {
                try { relayPool.closeOnRelay(url, subId) } catch (_: Exception) {}
            }
            relayPool.forgetSubscriptionStats(subId)
        }
        // A REQ only reached a relay that is connected (or answered); queued ones were never asked.
        val reallyAsked = asked.filter { ListWriter.norm(it) in answered || relayPool.isRelayConnected(it) }
            .map { ListWriter.norm(it) }.toSet()
        ListFetchResult(reallyAsked, answered.toSet(), events.toList())
    }

    companion object {
        const val PREFIX = "listw-"
        private val counter = AtomicInteger(0)
    }
}

/**
 * [ListSink] over [RelayPool]: the EVENT goes out through the non-queueing path to every relay, and the call returns
 * as soon as one write relay answers OK true, or about 3 s after the last send finished (at most [capMs]).
 */
class RelayListSink(
    private val relayPool: RelayPool,
    private val okWaitMs: Long = 3_000,
    private val capMs: Long = 9_000
) : ListSink {
    private val sendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun publish(event: NostrEvent, writeRelays: List<String>, otherRelays: List<String>): ListPublishResult = coroutineScope {
        val writeSet = writeRelays.map { ListWriter.norm(it) }.toSet()
        val accepted = ConcurrentHashMap.newKeySet<String>()
        val sentTo = ConcurrentHashMap.newKeySet<String>()
        val okJob = launch(start = CoroutineStart.UNDISPATCHED) {
            relayPool.publishResults.collect {
                if (it.eventId == event.id && it.accepted) accepted.add(ListWriter.norm(it.relayUrl))
            }
        }
        val targets = (writeRelays + otherRelays).distinctBy { ListWriter.norm(it) }
        val pending = AtomicInteger(targets.size)
        val lastDone = AtomicLong(0)
        for (url in targets) {
            sendScope.launch {
                try {
                    if (relayPool.sendPublicationToRelay(url, event)) sentTo.add(ListWriter.norm(url))
                } catch (e: Exception) {
                    Log.w("ListWriter", "publish to $url failed: ${e.message}")
                } finally {
                    lastDone.set(System.currentTimeMillis())
                    pending.decrementAndGet()
                }
            }
        }
        try {
            val start = System.currentTimeMillis()
            while (true) {
                val now = System.currentTimeMillis()
                if (accepted.any { it in writeSet }) break
                if (pending.get() == 0 && (sentTo.isEmpty() || now - lastDone.get() >= okWaitMs)) break
                if (now - start >= capMs) break
                delay(50)
            }
        } finally {
            okJob.cancel()
        }
        ListPublishResult(sentTo.toSet(), accepted.toSet())
    }
}
