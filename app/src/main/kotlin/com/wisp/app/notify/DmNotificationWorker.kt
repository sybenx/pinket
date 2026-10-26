package com.wisp.app.notify

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wisp.app.db.DmMessageEntity
import com.wisp.app.db.DmMessageEntity_
import com.wisp.app.db.EventEntity
import com.wisp.app.db.EventEntity_
import com.wisp.app.db.WispObjectBox
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip17
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import com.wisp.app.nostr.RelayMessage
import com.wisp.app.nostr.toHex
import com.wisp.app.nostr.toNpub
import com.wisp.app.relay.HttpClientFactory
import com.wisp.app.repo.KeyRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Background check for DMs that arrived while the app wasn't open. Reads kind 1059 gift wraps
 * for the active account from its DM inbox relays, skips any the app already has or the user
 * deleted, and posts one notification per sender. Needs the account's own key, both to unwrap
 * and to answer NIP-42 AUTH, which most DM relays require before serving gift wraps.
 */
class DmNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DmNotify"
        /** NIP-59 backdates gift wraps by up to two days, so look back that far past the last check. */
        private const val WRAP_BACKDATE_SECS = 2 * 24 * 3600L
        private const val MAX_RELAYS = 4
        private const val RELAY_TIMEOUT_MS = 15_000L
        private const val MAX_REMEMBERED = 1000
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!DmNotifications.isEnabled(ctx) || DmNotifications.appVisible) return Result.success()
        val keyRepo = KeyRepository(ctx)
        val keypair = keyRepo.getKeypair() ?: return Result.success() // read-only: can't decrypt
        val me = keypair.pubkey.toHex()

        val state = ctx.getSharedPreferences("wisp_dm_notify_$me", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis() / 1000
        val lastCheck = state.getLong("last_check", 0L)
        if (lastCheck == 0L) {
            // First run: start from now instead of announcing old messages
            state.edit().putLong("last_check", now).apply()
            return Result.success()
        }
        val notified = state.getStringSet("notified", emptySet())!!.toMutableSet()
        val deleted = ctx.getSharedPreferences("wisp_dm_$me", Context.MODE_PRIVATE)
            .getStringSet("deleted_gwraps", emptySet())!!

        val relays = keyRepo.getDmRelays().ifEmpty { keyRepo.getRelays().filter { it.read }.map { it.url } }
            .distinct().take(MAX_RELAYS)
        if (relays.isEmpty()) return Result.success()

        val filter = Filter(kinds = listOf(1059), pTags = listOf(me), since = lastCheck - WRAP_BACKDATE_SECS)
        val wraps = coroutineScope {
            relays.map { url -> async { fetch(url, filter, keypair.privkey, keypair.pubkey) } }.awaitAll()
        }.flatten().distinctBy { it.id }

        val stored = storedGiftWrapIds(me, wraps.map { it.id })
        val fresh = wraps.filter { it.id !in notified && it.id !in deleted && it.id !in stored }

        // sender -> message lines, oldest first
        val bySender = linkedMapOf<String, MutableList<Pair<Long, String>>>()
        for (wrap in fresh) {
            notified += wrap.id
            val rumor = try { Nip17.unwrapGiftWrap(keypair.privkey, wrap) } catch (_: Exception) { null } ?: continue
            if (rumor.pubkey == me) continue // our own sent copy
            val text = when (rumor.kind) {
                14 -> rumor.content.trim()
                15 -> applicationContext.getString(com.wisp.app.R.string.dm_notif_file)
                else -> "" // reactions and other rumor kinds don't notify
            }
            if (text.isEmpty()) continue
            bySender.getOrPut(rumor.pubkey) { mutableListOf() } += rumor.createdAt to text
        }
        for ((sender, msgs) in bySender) {
            DmNotifications.show(ctx, sender, displayName(sender), msgs.sortedBy { it.first }.map { it.second })
        }

        state.edit()
            .putLong("last_check", now)
            .putStringSet("notified", HashSet(notified.toList().takeLast(MAX_REMEMBERED)))
            .apply()
        Log.d(TAG, "checked ${relays.size} relays: ${wraps.size} wraps, ${fresh.size} new, ${bySender.size} senders notified")
        return Result.success()
    }

    /** Gift wraps the app has already decrypted and stored. */
    private fun storedGiftWrapIds(owner: String, ids: List<String>): Set<String> {
        if (ids.isEmpty() || !WispObjectBox.isInitialized) return emptySet()
        return try {
            val keys = ids.map { "$owner|$it" }.toTypedArray()
            WispObjectBox.store.boxFor(DmMessageEntity::class.java)
                .query(DmMessageEntity_.ownerPlusGiftWrap.oneOf(keys, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE))
                .build().use { q -> q.find().map { it.giftWrapId }.toSet() }
        } catch (e: Exception) {
            Log.w(TAG, "stored lookup failed: ${e.message}")
            emptySet()
        }
    }

    /** Name from the profile the app cached, else a short npub. */
    private fun displayName(pubkey: String): String {
        val fallback = pubkey.toNpub().let { "${it.take(12)}…${it.takeLast(4)}" }
        if (!WispObjectBox.isInitialized) return fallback
        return try {
            WispObjectBox.store.boxFor(EventEntity::class.java)
                .query(EventEntity_.pubkey.equal(pubkey).and(EventEntity_.kind.equal(0)))
                .orderDesc(EventEntity_.createdAt)
                .build().use { it.findFirst() }
                ?.let { e ->
                    NostrEvent(
                        id = e.eventId, pubkey = e.pubkey, created_at = e.createdAt, kind = e.kind,
                        tags = Json.decodeFromString(e.tags), content = e.content, sig = e.sig
                    )
                }
                ?.let { ProfileData.fromEvent(it)?.displayString }
                ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    /** One short-lived connection: REQ until EOSE, answering AUTH with the account key. */
    private suspend fun fetch(url: String, filter: Filter, privkey: ByteArray, pubkey: ByteArray): List<NostrEvent> {
        val subId = "dmnotify"
        val req = ClientMessage.req(subId, filter)
        val inbox = Channel<RelayMessage?>(Channel.UNLIMITED)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(req) }
            override fun onMessage(webSocket: WebSocket, text: String) { RelayMessage.parse(text)?.let { inbox.trySend(it) } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { inbox.trySend(null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { inbox.trySend(null) }
        }
        val ws = HttpClientFactory.getGeneralClient().newWebSocket(Request.Builder().url(url).build(), listener)
        val events = mutableListOf<NostrEvent>()
        try {
            withTimeoutOrNull(RELAY_TIMEOUT_MS) {
                var authId: String? = null
                var authAccepted = false
                var waitingForAuth = false
                while (true) {
                    when (val msg = inbox.receive() ?: break) {
                        is RelayMessage.Auth -> {
                            val auth = NostrEvent.create(
                                privkey, pubkey, 22242, "",
                                listOf(listOf("relay", url), listOf("challenge", msg.challenge))
                            )
                            authId = auth.id
                            ws.send(ClientMessage.auth(auth))
                        }
                        // The REQ went out before AUTH finished, so its CLOSED and the AUTH OK
                        // can arrive in either order; re-send the REQ once both are in
                        is RelayMessage.Ok -> if (msg.eventId == authId) {
                            if (!msg.accepted) break
                            authAccepted = true
                            if (waitingForAuth) { waitingForAuth = false; ws.send(req) }
                        }
                        is RelayMessage.Closed -> if (msg.subscriptionId == subId) {
                            if (!msg.message.startsWith("auth-required")) break
                            if (authAccepted) ws.send(req) else waitingForAuth = true
                        }
                        is RelayMessage.EventMsg -> if (msg.subscriptionId == subId && msg.event.kind == 1059) events += msg.event
                        is RelayMessage.Eose -> if (msg.subscriptionId == subId) break
                        else -> {}
                    }
                }
            }
        } finally {
            ws.close(1000, null)
        }
        return events
    }
}
