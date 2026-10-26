package com.wisp.app.notify

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wisp.app.db.DmMessageEntity
import com.wisp.app.db.DmMessageEntity_
import com.wisp.app.db.WispObjectBox
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip17
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.RelayMessage
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.HttpClientFactory
import com.wisp.app.repo.KeyRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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
        private const val MAX_RELAYS = 8
        private const val RELAY_TIMEOUT_MS = 15_000L
        private const val MAX_REMEMBERED = 1000
        private const val AUTH_GRACE_MS = 2_000L
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!DmNotifications.isEnabled(ctx)) return skip("turned off")
        if (DmNotifications.appVisible) return skip("app on screen")
        val keyRepo = KeyRepository(ctx)
        val keypair = keyRepo.getKeypair() ?: return skip("no private key (read-only account)")
        val me = keypair.pubkey.toHex()

        val state = ctx.getSharedPreferences("wisp_dm_notify_$me", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis() / 1000
        val lastCheck = state.getLong("last_check", 0L)
        if (lastCheck == 0L) {
            // First run: start from now instead of announcing old messages
            state.edit().putLong("last_check", now).apply()
            return skip("first run, starting from now")
        }
        val notified = state.getStringSet("notified", emptySet())!!.toMutableSet()
        val deleted = ctx.getSharedPreferences("wisp_dm_$me", Context.MODE_PRIVATE)
            .getStringSet("deleted_gwraps", emptySet())!!

        val relays = keyRepo.getDmRelays().ifEmpty { keyRepo.getRelays().filter { it.read }.map { it.url } }
            .distinct().take(MAX_RELAYS)
        if (relays.isEmpty()) return skip("no DM or read relays")
        Log.i(TAG, "checking ${relays.joinToString()} since ${lastCheck - WRAP_BACKDATE_SECS}")

        val filter = Filter(kinds = listOf(1059), pTags = listOf(me), since = lastCheck - WRAP_BACKDATE_SECS)
        val wraps = coroutineScope {
            relays.map { url -> async { fetch(url, filter, keypair.privkey, keypair.pubkey) } }.awaitAll()
        }.flatten().distinctBy { it.id }

        val stored = storedGiftWrapIds(me, wraps.map { it.id })
        val fresh = wraps.filter { it.id !in notified && it.id !in deleted && it.id !in stored }
        val skipped = "already notified ${wraps.count { it.id in notified }}, " +
            "deleted ${wraps.count { it.id in deleted }}, already in app ${wraps.count { it.id in stored }}"
        var unreadable = 0
        var ownCopies = 0
        var notMessages = 0

        // sender -> message lines, oldest first
        val bySender = linkedMapOf<String, MutableList<Pair<Long, String>>>()
        for (wrap in fresh) {
            notified += wrap.id
            val rumor = try { Nip17.unwrapGiftWrap(keypair.privkey, wrap) } catch (_: Exception) { null }
            if (rumor == null) { unreadable++; continue }
            if (rumor.pubkey == me) { ownCopies++; continue } // our own sent copy
            val text = when (rumor.kind) {
                14 -> rumor.content.trim()
                15 -> applicationContext.getString(com.wisp.app.R.string.dm_notif_file)
                else -> "" // reactions and other rumor kinds don't notify
            }
            if (text.isEmpty()) { notMessages++; continue }
            bySender.getOrPut(rumor.pubkey) { mutableListOf() } += rumor.createdAt to text
        }
        for ((sender, msgs) in bySender) {
            DmNotifications.show(ctx, sender, DmNotifications.displayName(sender), msgs.sortedBy { it.first }.map { it.second })
        }

        state.edit()
            .putLong("last_check", now)
            .putStringSet("notified", HashSet(notified.toList().takeLast(MAX_REMEMBERED)))
            .apply()
        Log.i(TAG, "checked ${relays.size} relays: ${wraps.size} wraps ($skipped), ${fresh.size} new " +
            "(unreadable $unreadable, own copies $ownCopies, not messages $notMessages), ${bySender.size} senders notified")
        return Result.success()
    }

    private fun skip(reason: String): Result {
        Log.i(TAG, "skipped: $reason")
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

    /**
     * One short-lived connection, answering NIP-42 AUTH with the account key. DM relays rarely
     * serve gift wraps before AUTH: some reply CLOSED "auth-required", others just send an empty
     * EOSE and a challenge. So the REQ is sent again once AUTH is accepted, and an EOSE that comes
     * before any challenge waits briefly in case one follows.
     */
    private suspend fun fetch(url: String, filter: Filter, privkey: ByteArray, pubkey: ByteArray): List<NostrEvent> {
        val subId = "dmnotify"
        val req = ClientMessage.req(subId, filter)
        val inbox = Channel<RelayMessage?>(Channel.UNLIMITED)
        var failure: String? = null
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(req) }
            override fun onMessage(webSocket: WebSocket, text: String) { RelayMessage.parse(text)?.let { inbox.trySend(it) } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = t.message ?: t.javaClass.simpleName
                inbox.trySend(null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { inbox.trySend(null) }
        }
        val ws = HttpClientFactory.getGeneralClient().newWebSocket(Request.Builder().url(url).build(), listener)
        val events = mutableListOf<NostrEvent>()
        var outcome = "timed out"
        var authId: String? = null
        var authAccepted = false
        var requestedAfterAuth = false
        try {
            withTimeoutOrNull(RELAY_TIMEOUT_MS) {
                // Set after an EOSE with no AUTH challenge yet: how long to wait for a late one
                var graceUntil = Long.MAX_VALUE
                while (true) {
                    val left = graceUntil - System.currentTimeMillis()
                    if (left <= 0) { outcome = "done"; break }
                    val msg = (if (graceUntil == Long.MAX_VALUE) inbox.receive() else withTimeoutOrNull(left) { inbox.receive() })
                    if (msg == null) {
                        outcome = if (graceUntil != Long.MAX_VALUE && failure == null) "done" else "connection ended${failure?.let { ": $it" } ?: ""}"
                        break
                    }
                    when (msg) {
                        is RelayMessage.Auth -> {
                            graceUntil = Long.MAX_VALUE
                            val auth = NostrEvent.create(
                                privkey, pubkey, 22242, "",
                                listOf(listOf("relay", url), listOf("challenge", msg.challenge))
                            )
                            authId = auth.id
                            ws.send(ClientMessage.auth(auth))
                        }
                        is RelayMessage.Ok -> if (msg.eventId == authId) {
                            if (!msg.accepted) { outcome = "AUTH rejected: ${msg.message}"; break }
                            authAccepted = true
                            if (!requestedAfterAuth) { requestedAfterAuth = true; ws.send(req) }
                        }
                        is RelayMessage.Closed -> if (msg.subscriptionId == subId) {
                            if (!msg.message.startsWith("auth-required")) { outcome = "closed: ${msg.message}"; break }
                            // Wait for the AUTH OK; it re-sends the REQ
                        }
                        is RelayMessage.EventMsg -> if (msg.subscriptionId == subId && msg.event.kind == 1059) events += msg.event
                        is RelayMessage.Eose -> if (msg.subscriptionId == subId) {
                            when {
                                requestedAfterAuth -> { outcome = "done"; break }
                                authId != null -> {} // AUTH in flight; the answer to the re-sent REQ counts
                                else -> graceUntil = System.currentTimeMillis() + AUTH_GRACE_MS
                            }
                        }
                        else -> {}
                    }
                }
            }
        } finally {
            ws.close(1000, null)
        }
        Log.i(TAG, "$url: ${events.size} wraps, auth=${if (authAccepted) "ok" else if (authId != null) "pending" else "none"}, $outcome")
        return events
    }
}
