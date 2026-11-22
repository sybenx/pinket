package com.wisp.app.viewmodel

import android.util.Log
import com.wisp.app.R
import com.wisp.app.nostr.ListWrite
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.RelayConfig
import com.wisp.app.repo.OwnListStore
import com.wisp.app.repo.PendingChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** The account a list job works for: its signer and its own-list store. Captured once per job. */
data class ListAccount(val signer: NostrSigner, val store: OwnListStore) {
    val pubkeyHex: String get() = signer.pubkeyHex.lowercase()
}

/** Ask relays for your newest versions of one list. Relay URLs in the result are [ListWriter.norm]alised. */
data class ListFetchRequest(
    val kind: Int,
    val d: String?,
    val author: String,
    /** Your write relays: their answers decide whether the fetch counts. */
    val writeRelays: List<String>,
    /** Indexers and other relays: asked too, but their answers alone never suffice. */
    val otherRelays: List<String>
)

/**
 * [asked]: relays the REQ really reached. [answered]: relays that sent EOSE or an event.
 * [events]: everything received (the writer filters to your own events of that list).
 */
data class ListFetchResult(val asked: Set<String>, val answered: Set<String>, val events: List<NostrEvent>)

fun interface ListSource {
    suspend fun fetch(request: ListFetchRequest): ListFetchResult
}

/** [sentTo]: relays the EVENT was written to (not queued). [acceptedBy]: relays that answered OK true. */
data class ListPublishResult(val sentTo: Set<String>, val acceptedBy: Set<String>)

fun interface ListSink {
    suspend fun publish(event: NostrEvent, writeRelays: List<String>, otherRelays: List<String>): ListPublishResult
}

/** Where a new entry goes. Removing always takes the entry out of both parts. */
enum class ListPlace {
    /** A public tag. */
    PUBLIC,
    /** The private part; a public tag when the private part can't be read or would be too large (mutes). */
    PRIVATE_OR_PUBLIC,
    /** The private part only; refused when that can't be done (private sets). */
    PRIVATE
}

sealed class ListResult {
    /** Signed and accepted by at least one of your write relays. */
    data class Sent(val event: NostrEvent) : ListResult()
    /** Nothing to do: the list already is the way you asked (or a newer tap replaced this one). */
    data object Unchanged : ListResult()
    /** Kept and written later (offline, relays did not answer, or no relay confirmed yet). */
    data object Pending : ListResult()
    data class Refused(val reason: Reason, val detail: String = "") : ListResult()

    enum class Reason {
        /** Your current list could not be found or read (string list_not_found). */
        NOT_FOUND,
        /** The write would change more than asked (string list_change_blocked). */
        BLOCKED,
        /** No signer, or something failed before anything was sent (string list_not_sent). */
        NOT_SENT,
        /** No follow list exists yet: ask the user, then call again with confirmFirstList = true. No toast. */
        NEEDS_CONFIRM
    }
}

/**
 * The only place that signs list kinds. Every change follows the house rule (ListWrite): fetch your newest version,
 * change only the entries asked for, keep everything else (unknown tags, hints, content), guard, then sign.
 *
 * Callers pass the state the user WANTS (entry present or not), captured at tap time, never a toggle. Changes are
 * kept per account as pending in [OwnListStore] until a relay confirms the write, and shown meanwhile through
 * [overlay], so buttons flip at once. Replayed by [resume] (on reconnect, after start-up self-data) and by the next
 * change to the same list. Toasts go to [messages] (string resource ids).
 */
class ListWriter(
    private val source: ListSource,
    private val sink: ListSink,
    /** The active account, or null (read-only, signed out, switching). */
    private val account: () -> ListAccount?,
    /** The pubkey of the active account right now (re-checked after the fetch and before publishing). */
    private val activePubkey: () -> String?,
    /** The pool's write relays: used only when the store has no own kind 10002 (a new account, or none anywhere). */
    private val poolWriteRelays: () -> List<String>,
    /** Scope for [submit] and [resume]: the account scope, stopped on account switch. */
    private val scope: CoroutineScope,
    /** Loads a version into the app's projection (repositories). Called for newer fetched and signed versions. */
    private val project: suspend (NostrEvent) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val indexerRelays: List<String> = DEFAULT_INDEXERS,
    /**
     * Where the work runs (parsing, guards, encryption, signing, the fetch and publish collectors): never the main
     * thread. Callbacks of [submit]/[submitMany] still run in [scope]'s context. Tests pass EmptyCoroutineContext.
     */
    private val work: CoroutineContext = Dispatchers.Default
) {
    private val _overlay = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    /** Pending wanted states, keyed by [overlayKey]. Overlay these on the projection. */
    val overlay: StateFlow<Map<String, Boolean>> = _overlay

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    /** String resource ids to show as a short toast. */
    val messages: SharedFlow<Int> = _messages

    private val _needsConfirm = MutableSharedFlow<List<List<String>>>(extraBufferCapacity = 8)
    /**
     * Follow-list entries whose kept change found that you have no follow list yet, when no caller was waiting for
     * the answer (a change replayed by [resume]). Ask the user, then call again with confirmFirstList = true.
     */
    val needsConfirm: SharedFlow<List<List<String>>> = _needsConfirm.asSharedFlow()

    private val waiting: MutableSet<PendingChange> = ConcurrentHashMap.newKeySet()
    private val outcomes = ConcurrentHashMap<PendingChange, ListResult>()
    @Volatile private var willSaveShown = false
    private val resuming = AtomicBoolean(false)

    // ---------------------------------------------------------------- public API

    /**
     * Make [entry] present ([wanted] = true) or absent in your list [kind] (a set: [d]).
     * Public entry: `set(10003, listOf("e", id, hint), wanted = true)`.
     * Private entry: `set(10000, listOf("p", pk), wanted = true, place = ListPlace.PRIVATE_OR_PUBLIC)`.
     * [confirmFirstList]: kind 3 only starts a first follow list after the user confirmed it.
     * [expected]: the account shows a list of this kind (beyond what the store knows).
     * [account]: for a key that is not (yet) the active one in the app wiring, e.g. onboarding.
     */
    suspend fun set(
        kind: Int,
        entry: List<String>,
        wanted: Boolean,
        d: String? = null,
        place: ListPlace = ListPlace.PUBLIC,
        confirmFirstList: Boolean = false,
        expected: Boolean = false,
        title: String? = null,
        account: ListAccount? = null
    ): ListResult = setMany(kind, listOf(entry), wanted, d, place, confirmFirstList, expected, title, account)

    /** Several entries, one wanted state (follow all, onboarding follows). Written as one event when possible. */
    suspend fun setMany(
        kind: Int,
        entries: List<List<String>>,
        wanted: Boolean,
        d: String? = null,
        place: ListPlace = ListPlace.PUBLIC,
        confirmFirstList: Boolean = false,
        expected: Boolean = false,
        title: String? = null,
        account: ListAccount? = null
    ): ListResult = withContext(work) { setManyNow(kind, entries, wanted, d, place, confirmFirstList, expected, title, account) }

    private suspend fun setManyNow(
        kind: Int,
        entries: List<List<String>>,
        wanted: Boolean,
        d: String?,
        place: ListPlace,
        confirmFirstList: Boolean,
        expected: Boolean,
        title: String?,
        account: ListAccount?
    ): ListResult {
        val changes = mutableListOf<PendingChange>()
        try {
            val acct = account ?: this.account() ?: run {
                _messages.tryEmit(R.string.error_not_logged_in)
                return ListResult.Refused(ListResult.Reason.NOT_SENT, "no account")
            }
            if (OwnListStore.keyOrNull(fakeEvent(kind, d)) == null) return refuse(ListResult.Reason.BLOCKED, "not a list kind")
            if (OwnListStore.isAddressable(kind) && d == null) return refuse(ListResult.Reason.BLOCKED, "no d")
            val store = acct.store
            if (store.pubkeyHex != acct.pubkeyHex) return refuse(ListResult.Reason.NOT_SENT, "store of another account")
            val key = OwnListStore.key(kind, d)
            if (entries.any { it.firstOrNull() == "d" }) return refuse(ListResult.Reason.BLOCKED, "a d tag is not an entry")
            val existing = store.pending(key)
            for (e in entries) {
                val id = entryId(e) ?: continue
                // A repeat tap for the same wanted state while that change is still pending: nothing new.
                if (existing.any { entryId(it.entry) == id && it.wanted == wanted }) continue
                if (changes.any { entryId(it.entry) == id }) continue
                val pc = PendingChange(
                    kind = kind, d = d, entry = e, wanted = wanted,
                    isPrivate = place != ListPlace.PUBLIC, at = clock(),
                    privateOnly = place == ListPlace.PRIVATE, firstListOk = confirmFirstList, title = title
                )
                // Waiting before it is stored, so a job already running for this list reports back to us.
                waiting.add(pc)
                changes += pc
                store.addPending(pc)
            }
            refreshOverlay(acct)
            if (changes.isEmpty()) return ListResult.Unchanged
            runKey(acct, kind, d, expected)
            return aggregate(changes.map { c -> outcomes.remove(c) ?: stillPending(acct, c) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "list change failed", e)
            return refuse(ListResult.Reason.NOT_SENT, e.message ?: "error")
        } finally {
            waiting.removeAll(changes.toSet())
            for (c in changes) outcomes.remove(c)
        }
    }

    /** [set] without waiting, in the account scope. */
    fun submit(
        kind: Int,
        entry: List<String>,
        wanted: Boolean,
        d: String? = null,
        place: ListPlace = ListPlace.PUBLIC,
        confirmFirstList: Boolean = false,
        expected: Boolean = false,
        onResult: (ListResult) -> Unit = {}
    ): Job = scope.launch {
        onResult(set(kind, entry, wanted, d, place, confirmFirstList, expected))
    }

    /** [setMany] without waiting, in the account scope. */
    fun submitMany(
        kind: Int,
        entries: List<List<String>>,
        wanted: Boolean,
        d: String? = null,
        place: ListPlace = ListPlace.PUBLIC,
        confirmFirstList: Boolean = false,
        expected: Boolean = false,
        onResult: (ListResult) -> Unit = {}
    ): Job = scope.launch {
        onResult(setMany(kind, entries, wanted, d, place, confirmFirstList, expected))
    }

    /**
     * Write a first list made of [entries] only if you have none anywhere (an automatic default, e.g. the 10050
     * DM relays). Quiet: no toast, nothing kept pending. Sent only when a write relay accepted it; Unchanged when a
     * version exists or was ever seen; Pending when the relays could not tell.
     */
    suspend fun createIfMissing(
        kind: Int,
        entries: List<List<String>>,
        d: String? = null,
        title: String? = null,
        extraRelays: List<String> = emptyList(),
        account: ListAccount? = null
    ): ListResult = withContext(work) { createIfMissingNow(kind, entries, d, title, extraRelays, account) }

    private suspend fun createIfMissingNow(
        kind: Int,
        entries: List<List<String>>,
        d: String?,
        title: String?,
        extraRelays: List<String>,
        account: ListAccount?
    ): ListResult {
        try {
            val acct = account ?: this.account() ?: return ListResult.Refused(ListResult.Reason.NOT_SENT, "no account")
            if (OwnListStore.isAddressable(kind) && d == null) return ListResult.Refused(ListResult.Reason.BLOCKED, "no d")
            if (entries.isEmpty()) return ListResult.Unchanged
            val store = acct.store
            if (store.pubkeyHex != acct.pubkeyHex) return ListResult.Refused(ListResult.Reason.NOT_SENT, "store of another account")
            val key = OwnListStore.key(kind, d)
            return store.mutex(key).withLock {
                val me = acct.pubkeyHex
                val writeRelays = writeRelaysFor(store)
                resendUnconfirmed(acct, key, writeRelays)
                if (store.everSeen(key) || store.latest(key) != null) return@withLock ListResult.Unchanged
                when (val prep = prepare(acct, kind, d, expectedHint = false)) {
                    is Prep.Stop -> prep.result
                    is Prep.Ready -> {
                        if (prep.old != null) return@withLock ListResult.Unchanged
                        if (!prep.firstQuorum) return@withLock ListResult.Pending
                        var tags = listOfNotNull(
                            d?.let { listOf("d", it) },
                            title?.takeIf { d != null && it.isNotBlank() }?.let { listOf("title", it) }
                        )
                        for (e in entries) {
                            val k = ListWrite.key(e) ?: continue
                            if (e[0] == "d" || tags.any { ListWrite.key(it) == k }) continue
                            tags = tags + listOf(e)
                        }
                        val now = clock()
                        val event = try {
                            acct.signer.signEvent(kind = kind, content = "", tags = tags, createdAt = now)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "signing $key failed", e)
                            return@withLock ListResult.Refused(ListResult.Reason.NOT_SENT, "sign failed")
                        }
                        if (event.pubkey.lowercase() != me || activePubkey()?.lowercase() != me) return@withLock ListResult.Pending
                        val pub = sink.publish(event, prep.writeRelays, publishOthers(prep.writeRelays, extraRelays))
                        if (pub.sentTo.isEmpty()) return@withLock ListResult.Pending
                        if (!store.recordSigned(event)) return@withLock ListResult.Pending
                        safeProject(event)
                        if (acceptedByWrite(pub, prep.writeRelays)) {
                            store.markConfirmed(key, event.id)
                            ListResult.Sent(event)
                        } else ListResult.Pending
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "createIfMissing failed", e)
            return ListResult.Refused(ListResult.Reason.NOT_SENT, e.message ?: "error")
        }
    }

    /**
     * Re-send unconfirmed events and replay pending changes for every list of the account (on reconnect, after the
     * start-up self-data fetch, when the account becomes active). Runs one at a time.
     */
    fun resume(account: ListAccount? = null): Job = scope.launch { resumeNow(account) }

    suspend fun resumeNow(account: ListAccount? = null): Unit = withContext(work) { resumeAll(account) }

    private suspend fun resumeAll(account: ListAccount?) {
        if (!resuming.compareAndSet(false, true)) return
        try {
            val acct = account ?: this.account() ?: return
            refreshOverlay(acct)
            val keys = acct.store.allPending().map { it.key }.toSet() + acct.store.unconfirmedKeys()
            for (key in keys) {
                val (kind, d) = parseKey(key) ?: continue
                try {
                    runKey(acct, kind, d, expectedHint = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "resume of $key failed", e)
                }
            }
        } finally {
            resuming.set(false)
        }
    }

    /** The pending wanted state of an entry, or null when nothing is pending for it. */
    fun wanted(kind: Int, d: String?, tag: List<String>): Boolean? = overlayKey(kind, d, tag)?.let { _overlay.value[it] }

    /** Rebuild [overlay] from the active account's pending changes (call after an account switch). */
    fun refreshOverlay(acct: ListAccount? = account()) {
        val active = account()
        val store = when {
            acct != null && (active == null || active.pubkeyHex == acct.pubkeyHex) -> acct.store
            active != null -> active.store
            else -> null
        }
        if (store == null) {
            _overlay.value = emptyMap()
            return
        }
        val all = store.allPending()
        if (all.isEmpty()) willSaveShown = false
        _overlay.value = all.mapNotNull { p -> overlayKey(p.kind, p.d, p.entry)?.let { it to p.wanted } }.toMap()
    }

    // ---------------------------------------------------------------- the job for one list

    private sealed class Prep {
        data class Ready(
            val old: NostrEvent?,
            val writeRelays: List<String>,
            /** A first list may be started (new account here, or your write relays said there is none). */
            val firstQuorum: Boolean,
            /** [old] is a version this device signed that no relay has confirmed yet. */
            val oldUnconfirmed: Boolean
        ) : Prep()
        /** [quiet]: no toast (the account changed under the job). */
        data class Stop(val result: ListResult, val quiet: Boolean = false) : Prep()
    }

    /** Process everything pending for one list, under its lock. Results go to [outcomes] for waiting callers. */
    private suspend fun runKey(acct: ListAccount, kind: Int, d: String?, expectedHint: Boolean) {
        val store = acct.store
        val key = OwnListStore.key(kind, d)
        store.mutex(key).withLock {
            val writeRelays0 = writeRelaysFor(store)
            resendUnconfirmed(acct, key, writeRelays0)
            if (store.pending(key).isEmpty()) return@withLock
            val prep = try {
                prepare(acct, kind, d, expectedHint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "fetch for $key failed", e)
                Prep.Stop(ListResult.Pending)
            }
            // Read after the fetch, so taps made meanwhile go into the same write.
            val pend = store.pending(key)
            if (pend.isEmpty()) return@withLock
            when (prep) {
                is Prep.Stop -> finish(acct, pend.associateWith { prep.result }, quiet = prep.quiet)
                is Prep.Ready -> write(acct, kind, d, pend, prep)
            }
        }
        refreshOverlay(acct)
    }

    /** Fetch and check everything the plan needs: your newest version, from enough of your write relays. */
    private suspend fun prepare(acct: ListAccount, kind: Int, d: String?, expectedHint: Boolean): Prep {
        val store = acct.store
        val me = acct.pubkeyHex
        val key = OwnListStore.key(kind, d)
        val now = clock()

        // A key generated on this device, still during sign-up, nothing ever seen for this list: no fetch, a first
        // list. Later (another app may have written lists with this key by then) the fetch below is always made.
        if (store.isOnboardingWindow() && store.latest(key) == null && !store.everSeen(key)) {
            val relays = poolWriteRelays().distinctBy { norm(it) }.take(MAX_WRITE_RELAYS)
            if (relays.isEmpty()) return Prep.Stop(ListResult.Pending)
            return Prep.Ready(null, relays, firstQuorum = true, oldUnconfirmed = false)
        }

        // An existing account: your own relay list must be known first, so the fetch asks YOUR write relays.
        // Proven that you have none anywhere: your lists can only be on the app's relays (the pool's write relays).
        var noRelayList = false
        if (!store.isNewAccount() && store.latest(RELAY_LIST_KIND) == null && kind != RELAY_LIST_KIND) {
            val relays = (poolWriteRelays() + indexerRelays).distinctBy { norm(it) }
            val r = source.fetch(ListFetchRequest(RELAY_LIST_KIND, null, me, poolWriteRelays(), indexerRelays))
            for (e in r.events) {
                if (e.pubkey.lowercase() == me && e.kind == RELAY_LIST_KIND) store.seen(e)
            }
            if (store.latest(RELAY_LIST_KIND) == null) {
                // No answer, or one relay alone: that proves nothing, try later.
                if (relays.isEmpty() || r.answered.size < 2) return Prep.Stop(ListResult.Pending)
                noRelayList = true
            }
        }

        val listRelays = store.latest(RELAY_LIST_KIND)?.let { writeRelaysOf(it) }.orEmpty()
        val writeRelays = writeRelaysFor(store)
        if (writeRelays.isEmpty()) return Prep.Stop(ListResult.Pending)
        val writeSet = writeRelays.map { norm(it) }.toSet()
        val others = indexerRelays.filter { norm(it) !in writeSet }.distinctBy { norm(it) }

        val r = source.fetch(ListFetchRequest(kind, d, me, writeRelays, others))
        if (r.answered.isEmpty()) return Prep.Stop(ListResult.Pending)
        val writeAnswered = r.answered.map { norm(it) }.filter { it in writeSet }.toSet()
        // Indexer answers alone never suffice: enough of ALL your write relays must answer. One that could not be
        // asked (down, on a cooldown, blocked) counts as not answered.
        if (!enoughWriteAnswers(writeAnswered.size, writeSet.size)) return Prep.Stop(ListResult.Pending)

        // The account may have changed while we waited: never go on with another key.
        if (activePubkey()?.lowercase() != me) return Prep.Stop(ListResult.Pending, quiet = true)

        val mine = r.events.filter { e ->
            e.pubkey.lowercase() == me && e.kind == kind &&
                (d == null || e.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) == d)
        }
        for (e in mine.sortedWith(compareBy<NostrEvent> { it.created_at }.thenByDescending { it.id })) {
            if (store.seen(e)) safeProject(e)
        }
        val found = mine + listOfNotNull(store.latest(key))
        val old = ListWrite.newest(found, me, kind, d)

        // The relays whose answers prove what exists: your listed write relays (all of them count, an unasked one
        // as not answered); with no relay list, the write relays used instead.
        val base = when {
            listRelays.isNotEmpty() -> listRelays.map { norm(it) }.toSet()
            store.isNewAccount() || noRelayList -> writeSet
            else -> emptySet()
        }
        val ok = base.count { it in writeAnswered }

        if (old != null && old.created_at < store.watermark(key)) {
            // This device showed a newer version that never reached a relay (the old code did not check). Only when
            // your write relays prove nothing newer exists, go on from their version; else try again later.
            val proven = base.isNotEmpty() && ok >= minOf(2, base.size) && ok * 2 >= base.size
            if (!proven) return Prep.Stop(ListResult.Pending)
            store.lowerWatermark(key, old.created_at)
            if (store.seen(old)) safeProject(old)
        }
        if (old != null && old.created_at > now + ListWrite.AHEAD) {
            return Prep.Stop(ListResult.Refused(ListResult.Reason.NOT_FOUND, "dated ahead"))
        }
        val expected = expectedHint || store.everSeen(key)
        if (old == null && expected) return Prep.Stop(ListResult.Refused(ListResult.Reason.NOT_FOUND, "no list found"))

        var firstQuorum = false
        if (old == null) {
            // Proof that no list exists: at least 2 of those relays and at least half of them said so. A follow list
            // with fewer listed relays may still be started, because the user confirms it first.
            val need = if (kind in CONFIRM_FIRST_LIST) minOf(2, base.size) else 2
            firstQuorum = base.isNotEmpty() && ok >= need && ok * 2 >= base.size
        }
        val unconf = store.unconfirmed(key)
        return Prep.Ready(old, writeRelays, firstQuorum, oldUnconfirmed = old != null && unconf != null && unconf.id == old.id)
    }

    /** A list being changed: public tags and the decrypted private part (null when unreadable). */
    private class Draft(
        var pub: List<List<String>>,
        var priv: List<List<String>>,
        val opaque: Boolean,
        val nip04: Boolean,
        /** The decrypted private part of the version it started from (empty when none or unreadable). */
        val oldPriv: List<List<String>> = priv,
        var privChanged: Boolean = false
    )

    private sealed class Step {
        object Nothing : Step()
        object Wrote : Step()
        data class Refuse(val reason: ListResult.Reason, val why: String) : Step()
    }

    private suspend fun write(acct: ListAccount, kind: Int, d: String?, pend: List<PendingChange>, prep: Prep.Ready) {
        val store = acct.store
        val me = acct.pubkeyHex
        val key = OwnListStore.key(kind, d)
        val old = prep.old
        val now = clock()
        val draft = startDraft(acct, kind, d, old, pend)
        val steps = LinkedHashMap<PendingChange, Step>()
        val written = mutableListOf<PendingChange>()
        for (c in pend) {
            val s = step(kind, draft, c, old == null, prep.firstQuorum)
            steps[c] = s
            if (s === Step.Wrote) written += c
        }
        val results = LinkedHashMap<PendingChange, ListResult>()
        fun settle(nothing: ListResult) {
            for ((c, s) in steps) results[c] = when (s) {
                Step.Nothing -> nothing
                is Step.Refuse -> ListResult.Refused(s.reason, s.why)
                Step.Wrote -> ListResult.Pending
            }
        }
        if (written.isEmpty()) {
            // Nothing to write. On a version no relay confirmed yet, keep the changes until one does.
            settle(if (prep.oldUnconfirmed) ListResult.Pending else ListResult.Unchanged)
            finish(acct, results)
            return
        }

        val nothingResult = if (prep.oldUnconfirmed) ListResult.Pending else ListResult.Unchanged
        fun refuseWritten(reason: ListResult.Reason, why: String) {
            for (c in written) steps[c] = Step.Refuse(reason, why)
            settle(nothingResult)
            finish(acct, results)
        }

        val content = if (draft.privChanged) {
            try {
                val plain = privateJson(draft.priv)
                if (draft.nip04) acct.signer.nip04Encrypt(plain, me) else acct.signer.nip44Encrypt(plain, me)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "encrypting $key failed", e)
                refuseWritten(ListResult.Reason.NOT_SENT, "sign failed")
                return
            }
        } else old?.content ?: ""
        val createdAt = if (old == null) now else maxOf(now, old.created_at + 1)
        val next = ListWrite.Version(kind, createdAt, content, draft.pub)

        // The last check on the whole result, against the version it started from (independent of the steps).
        val touched = ListWrite.touched(written.map { if (it.wanted) ListWrite.Change(add = it.entry) else ListWrite.Change(remove = it.entry) })
        val maxRemoved = written.count { !it.wanted }
        var why: String? = if (old == null) {
            when {
                d != null && draft.pub.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) != d -> "a different list"
                draft.privChanged && (content.isBlank() ||
                    ListWrite.tagsGuardMany(emptyList(), draft.priv, touched, maxRemoved) != null) -> "the private part changed"
                else -> null
            }
        } else {
            val ov = ListWrite.version(old)
            ListWrite.versionGuard(ov, next, now, sameContent = !draft.privChanged)
                ?: if (!draft.privChanged) {
                    ListWrite.guardMany(ov, next, touched, maxRemoved, now)
                } else {
                    // Public tags: unchanged, or changed only in the entries asked for. The private part: changed,
                    // and only in those entries. The content really is a new one.
                    val p = ListWrite.tagsGuardMany(old.tags, draft.pub, touched, maxRemoved)
                    val q = ListWrite.tagsGuardMany(draft.oldPriv, draft.priv, touched, maxRemoved)
                    when {
                        p != null && p != "changes nothing" -> p
                        q != null -> q
                        content.isBlank() || content == old.content -> "the private part changed"
                        else -> null
                    }
                }
        }
        if (why == null && draft.privChanged) {
            // Read it back: the content must decrypt to exactly the private part that was checked.
            val back = try {
                val plain = if (draft.nip04) acct.signer.nip04Decrypt(content, me) else acct.signer.nip44Decrypt(content, me)
                parsePrivate(plain)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (back != draft.priv) why = "the private part changed"
        }
        if (why != null) {
            refuseWritten(ListResult.Reason.BLOCKED, why)
            return
        }

        val event = try {
            acct.signer.signEvent(kind = kind, content = content, tags = draft.pub, createdAt = createdAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "signing $key failed", e)
            refuseWritten(ListResult.Reason.NOT_SENT, "sign failed")
            return
        }
        if (event.pubkey.lowercase() != me || (old != null && old.pubkey.lowercase() != me)) {
            refuseWritten(ListResult.Reason.NOT_SENT, "another key")
            return
        }
        if (activePubkey()?.lowercase() != me) {
            settle(ListResult.Pending)
            finish(acct, results, quiet = true)
            return
        }
        val pub = sink.publish(event, prep.writeRelays, publishOthers(prep.writeRelays, emptyList()))
        if (pub.sentTo.isEmpty() || !store.recordSigned(event)) {
            // Nothing went out (or a newer version arrived meanwhile): keep every change pending, drop the event.
            settle(ListResult.Pending)
            for (c in written) results[c] = ListResult.Pending
            finish(acct, results)
            return
        }
        safeProject(event)
        if (acceptedByWrite(pub, prep.writeRelays)) {
            store.markConfirmed(key, event.id)
            settle(ListResult.Unchanged)
            for (c in written) results[c] = ListResult.Sent(event)
        } else {
            // Signed and sent, but no write relay confirmed: kept as newest-but-unconfirmed and re-sent next time.
            // The changes stay pending until a relay confirms a version that has them.
            settle(ListResult.Pending)
        }
        finish(acct, results)
    }

    private suspend fun startDraft(acct: ListAccount, kind: Int, d: String?, old: NostrEvent?, pend: List<PendingChange>): Draft {
        if (old == null) {
            val title = pend.firstNotNullOfOrNull { it.title?.takeIf { t -> t.isNotBlank() } }
            val pub = listOfNotNull(d?.let { listOf("d", it) }, title?.takeIf { d != null }?.let { listOf("title", it) })
            return Draft(pub, emptyList(), opaque = false, nip04 = false)
        }
        if (!hasPrivatePart(kind) || old.content.isBlank()) {
            // No private part (kind 3's content is never read or changed), or an empty one.
            return Draft(old.tags, emptyList(), opaque = false, nip04 = false)
        }
        val nip04 = old.content.contains("?iv=")
        val plain = try {
            if (nip04) acct.signer.nip04Decrypt(old.content, acct.pubkeyHex) else acct.signer.nip44Decrypt(old.content, acct.pubkeyHex)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val priv = plain?.let { parsePrivate(it) }
        return if (priv == null) Draft(old.tags, emptyList(), opaque = true, nip04 = nip04)
        else Draft(old.tags, priv, opaque = false, nip04 = nip04, oldPriv = priv)
    }

    /** One change applied to the draft, each checked on its own (ListWrite guards). */
    private fun step(kind: Int, draft: Draft, c: PendingChange, firstList: Boolean, firstQuorum: Boolean): Step {
        val e = c.entry
        val k = ListWrite.key(e) ?: return Step.Refuse(ListResult.Reason.BLOCKED, "not an entry")
        if (e[0] == "d") return Step.Refuse(ListResult.Reason.BLOCKED, "a different list")
        val inPub = draft.pub.any { ListWrite.key(it) == k }
        val inPriv = draft.priv.any { ListWrite.key(it) == k }
        if (c.wanted) {
            if (inPub || inPriv) return Step.Nothing
            if (firstList && draft.pub.none { it.firstOrNull() != "d" && it.firstOrNull() != "title" } && draft.priv.isEmpty()) {
                // Your write relays could not show that you have no such list: never start one from nothing.
                if (!firstQuorum) return Step.Refuse(ListResult.Reason.NOT_FOUND, "cannot tell that no list exists")
                if (kind in CONFIRM_FIRST_LIST && !c.firstListOk) return Step.Refuse(ListResult.Reason.NEEDS_CONFIRM, "no follow list yet")
            }
            if (c.isPrivate && hasPrivatePart(kind)) {
                if (!draft.opaque) {
                    val nextPriv = draft.priv + listOf(e)
                    val fits = draft.nip04 || privateJson(nextPriv).toByteArray(Charsets.UTF_8).size <= NIP44_MAX
                    if (fits) {
                        ListWrite.privateGuard(draft.pub, draft.pub, draft.priv, nextPriv)?.let {
                            return Step.Refuse(ListResult.Reason.BLOCKED, it)
                        }
                        draft.priv = nextPriv
                        draft.privChanged = true
                        return Step.Wrote
                    }
                    if (c.privateOnly) return Step.Refuse(ListResult.Reason.BLOCKED, "private part too large")
                } else if (c.privateOnly) {
                    return Step.Refuse(ListResult.Reason.NOT_FOUND, "private part unreadable")
                }
            }
            val nextPub = draft.pub + listOf(e)
            ListWrite.tagsGuard(draft.pub, nextPub)?.let { return Step.Refuse(ListResult.Reason.BLOCKED, it) }
            draft.pub = nextPub
            return Step.Wrote
        }
        if (!inPub && !inPriv) {
            return if (draft.opaque) Step.Refuse(ListResult.Reason.NOT_FOUND, "private part unreadable") else Step.Nothing
        }
        val nextPub = draft.pub.filterNot { ListWrite.key(it) == k }
        val nextPriv = draft.priv.filterNot { ListWrite.key(it) == k }
        ListWrite.bothGuard(draft.pub, nextPub, draft.priv, nextPriv, k)?.let { return Step.Refuse(ListResult.Reason.BLOCKED, it) }
        if (nextPriv != draft.priv) draft.privChanged = true
        draft.pub = nextPub
        draft.priv = nextPriv
        return Step.Wrote
    }

    /** Record results: done changes leave the pending store; toasts; results for waiting callers. */
    private fun finish(acct: ListAccount, results: Map<PendingChange, ListResult>, quiet: Boolean = false) {
        var willSave = false
        val refusals = mutableSetOf<Int>()
        val confirm = mutableListOf<List<String>>()
        for ((c, r) in results) {
            when (r) {
                is ListResult.Pending -> willSave = true
                is ListResult.Refused -> {
                    acct.store.removePending(c)
                    messageFor(r.reason)?.let { refusals += it }
                    if (r.reason == ListResult.Reason.NEEDS_CONFIRM && c !in waiting) confirm += c.entry
                }
                else -> acct.store.removePending(c)
            }
            if (c in waiting) outcomes[c] = r
        }
        if (quiet) return
        for (m in refusals) _messages.tryEmit(m)
        // Nobody is waiting for these (a replayed change): ask through [needsConfirm].
        if (confirm.isNotEmpty()) _needsConfirm.tryEmit(confirm)
        if (willSave && !willSaveShown) {
            willSaveShown = true
            _messages.tryEmit(R.string.list_will_save)
        }
    }

    private suspend fun resendUnconfirmed(acct: ListAccount, key: String, writeRelays: List<String>) {
        val ev = acct.store.unconfirmed(key) ?: return
        if (ev.pubkey.lowercase() != acct.pubkeyHex || writeRelays.isEmpty()) return
        // Replaced by a newer version since: never re-send the older one.
        if (acct.store.latest(key)?.id != ev.id) return
        if (activePubkey()?.lowercase() != acct.pubkeyHex) return
        try {
            val pub = sink.publish(ev, writeRelays, publishOthers(writeRelays, emptyList()))
            if (acceptedByWrite(pub, writeRelays)) acct.store.markConfirmed(key, ev.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "re-send of $key failed", e)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun writeRelaysFor(store: OwnListStore): List<String> {
        val listed = store.latest(RELAY_LIST_KIND)?.let { writeRelaysOf(it) }.orEmpty()
        val urls = listed.ifEmpty { poolWriteRelays() }
        return urls.distinctBy { norm(it) }.take(MAX_WRITE_RELAYS)
    }

    /**
     * Enough of your write relays answered a fetch: at least half of all of them ([total], unasked ones included).
     * Half rather than a strict majority, so a list with two write relays of which one is gone can still be written;
     * the same bar as the proof that no list exists.
     */
    private fun enoughWriteAnswers(answered: Int, total: Int): Boolean = answered > 0 && answered * 2 >= total

    private fun publishOthers(writeRelays: List<String>, extra: List<String>): List<String> {
        val w = writeRelays.map { norm(it) }.toSet()
        return (indexerRelays + extra).filter { norm(it) !in w }.distinctBy { norm(it) }
    }

    private fun acceptedByWrite(pub: ListPublishResult, writeRelays: List<String>): Boolean {
        val w = writeRelays.map { norm(it) }.toSet()
        return pub.acceptedBy.any { norm(it) in w }
    }

    private suspend fun safeProject(e: NostrEvent) {
        try {
            project(e)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            Log.w(TAG, "projection of kind ${e.kind} failed", ex)
        }
    }

    private fun stillPending(acct: ListAccount, c: PendingChange): ListResult {
        val id = entryId(c.entry)
        val p = acct.store.pending(c.key).firstOrNull { entryId(it.entry) == id }
        return when {
            p == null -> ListResult.Unchanged
            p == c -> ListResult.Pending
            else -> ListResult.Unchanged // replaced by a newer tap on the same entry
        }
    }

    private fun aggregate(results: List<ListResult>): ListResult =
        results.firstOrNull { it is ListResult.Sent }
            ?: results.firstOrNull { it is ListResult.Refused }
            ?: results.firstOrNull { it is ListResult.Pending }
            ?: ListResult.Unchanged

    private fun refuse(reason: ListResult.Reason, why: String): ListResult {
        messageFor(reason)?.let { _messages.tryEmit(it) }
        return ListResult.Refused(reason, why)
    }

    private fun messageFor(reason: ListResult.Reason): Int? = when (reason) {
        ListResult.Reason.NOT_FOUND -> R.string.list_not_found
        ListResult.Reason.BLOCKED -> R.string.list_change_blocked
        ListResult.Reason.NOT_SENT -> R.string.list_not_sent
        ListResult.Reason.NEEDS_CONFIRM -> null
    }

    private fun fakeEvent(kind: Int, d: String?) =
        NostrEvent("", "", 0, kind, listOfNotNull(d?.let { listOf("d", it) }), "", "")

    companion object {
        private const val TAG = "ListWriter"
        const val RELAY_LIST_KIND = 10002
        const val MAX_WRITE_RELAYS = 10
        /** NIP-44 plaintext limit. */
        const val NIP44_MAX = 65535
        /** Kinds whose first list needs the user's confirmation (a follow list started from nothing). */
        val CONFIRM_FIRST_LIST = setOf(3)
        val DEFAULT_INDEXERS: List<String> = RelayConfig.DEFAULT_INDEXER_RELAYS + "wss://purplepag.es"

        /** A relay address in one spelling, for comparing. */
        fun norm(url: String): String = url.trim().lowercase().trimEnd('/')

        /** Entry identity (ListWrite.key) as one string; also the OwnListStore entryId. */
        fun entryId(tag: List<String>): String? = ListWrite.key(tag)?.let { "${it.first}\u0000${it.second}" }

        fun overlayKey(kind: Int, d: String?, tag: List<String>): String? =
            entryId(tag)?.let { "$kind|${d ?: ""}|$it" }

        /** Kinds 0 and 3 have no private part (kind 3's content is a relay map, never touched). */
        fun hasPrivatePart(kind: Int): Boolean = kind != 0 && kind != 3

        /** Write relays from a kind 10002: `r` tags without a marker or marked write. */
        fun writeRelaysOf(e: NostrEvent): List<String> = e.tags
            .filter { it.size >= 2 && it[0] == "r" && (it.size < 3 || it[2].isBlank() || it[2] == "write") }
            .map { it[1].trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { norm(it) }

        fun privateJson(tags: List<List<String>>): String =
            buildJsonArray { for (t in tags) add(buildJsonArray { for (s in t) add(JsonPrimitive(s)) }) }.toString()

        /** A decrypted private part: a JSON array of string arrays, or null when it is anything else. */
        fun parsePrivate(json: String): List<List<String>>? = try {
            val root = Json.parseToJsonElement(json) as? JsonArray
            root?.map { t ->
                val arr = t as? JsonArray ?: return null
                arr.map { v ->
                    val p = v as? JsonPrimitive ?: return null
                    if (!p.isString) return null
                    p.content
                }
            }
        } catch (e: Exception) {
            null
        }

        fun parseKey(key: String): Pair<Int, String?>? {
            val i = key.indexOf(':')
            return if (i < 0) key.toIntOrNull()?.let { it to null }
            else key.substring(0, i).toIntOrNull()?.let { it to key.substring(i + 1) }
        }
    }
}
