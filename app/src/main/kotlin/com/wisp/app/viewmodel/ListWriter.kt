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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
    PRIVATE,
    /**
     * Where the set keeps its entries, read from your newest version when the change is written: a public tag when
     * it has public entries or no private part, else the private part only (refused when that cannot be read).
     */
    AS_SET
}

/**
 * One change to one entry of a list, for [ListWriter.edit]: [Op.ADD] makes the entry present, [Op.REMOVE] takes out
 * every copy of it, [Op.SET] puts [entry] in place of the tag with the same slot (ListWrite.slot: a relay with its
 * read/write marker, a set's title, image or description), or adds it when there is none.
 */
data class ListEdit(val entry: List<String>, val op: Op) {
    enum class Op { ADD, REMOVE, SET }

    companion object {
        fun add(vararg tag: String) = ListEdit(tag.toList(), Op.ADD)
        fun remove(vararg tag: String) = ListEdit(tag.toList(), Op.REMOVE)
        fun set(vararg tag: String) = ListEdit(tag.toList(), Op.SET)
    }
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
        NEEDS_CONFIRM,
        /** A set with that d already exists (string list_name_taken). */
        NAME_TAKEN,
        /** A set made by another app, and the change was not made on that set itself (string list_read_only). */
        READ_ONLY
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

    private val _creating = MutableStateFlow<List<PendingChange>>(emptyList())
    /**
     * Sets being created right now ([createSet] has not returned yet), one marker each ([PendingChange.creating],
     * its title as the entry). Lay them over the sets shown, so a new set appears at once.
     */
    val creating: StateFlow<List<PendingChange>> = _creating

    private val _hiddenSets = MutableStateFlow<Set<String>>(emptySet())
    /** Sets hidden on this device ("kind:d", OwnListStore.key). Filter them out of every set list shown. */
    val hiddenSets: StateFlow<Set<String>> = _hiddenSets

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
     * [explicit]: a set (30000-39999) the user picked on screen; only then may a set made by another app change.
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
        account: ListAccount? = null,
        explicit: Boolean = false
    ): ListResult = setMany(kind, listOf(entry), wanted, d, place, confirmFirstList, expected, title, account, explicit)

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
        account: ListAccount? = null,
        explicit: Boolean = false
    ): ListResult = withContext(work) {
        val op = if (wanted) ListEdit.Op.ADD else ListEdit.Op.REMOVE
        editNow(kind, entries.map { ListEdit(it, op) }, d, place, confirmFirstList, expected, title, account, explicit)
    }

    /**
     * Several changes to one list in one write, in order (the relay editor's adds, removes and marker changes; a
     * group join's `group` plus `r`; a set's rename next to an added entry). Kept pending like [set]. The last change
     * of an entry wins. [place] applies to every ADD and SET; REMOVE always takes the entry out of both parts.
     */
    suspend fun edit(
        kind: Int,
        edits: List<ListEdit>,
        d: String? = null,
        place: ListPlace = ListPlace.PUBLIC,
        expected: Boolean = false,
        title: String? = null,
        explicit: Boolean = false,
        account: ListAccount? = null
    ): ListResult = withContext(work) { editNow(kind, edits, d, place, false, expected, title, account, explicit) }

    private suspend fun editNow(
        kind: Int,
        edits: List<ListEdit>,
        d: String?,
        place: ListPlace,
        confirmFirstList: Boolean,
        expected: Boolean,
        title: String?,
        account: ListAccount?,
        explicit: Boolean
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
            if (edits.any { it.entry.firstOrNull() == "d" }) return refuse(ListResult.Reason.BLOCKED, "a d tag is not an entry")
            val existing = store.pending(key)
            // One change per entry: the first of the same kind of change, else the last one.
            val byEntry = LinkedHashMap<String, ListEdit>()
            for (ed in edits) {
                val id = entryId(ed.entry) ?: continue
                val prev = byEntry[id]
                if (prev != null && prev.op == ed.op && (ed.op != ListEdit.Op.SET || prev.entry == ed.entry)) continue
                byEntry.remove(id)
                byEntry[id] = ed
            }
            for ((id, ed) in byEntry) {
                val e = ed.entry
                val wanted = ed.op != ListEdit.Op.REMOVE
                val replace = ed.op == ListEdit.Op.SET
                // A repeat tap for the same wanted state while that change is still pending: nothing new.
                if (existing.any { entryId(it.entry) == id && it.wanted == wanted && it.replace == replace && (!replace || it.entry == e) }) continue
                val pc = PendingChange(
                    kind = kind, d = d, entry = e, wanted = wanted,
                    isPrivate = place == ListPlace.PRIVATE_OR_PUBLIC || place == ListPlace.PRIVATE, at = clock(),
                    privateOnly = place == ListPlace.PRIVATE, firstListOk = confirmFirstList, title = title,
                    replace = replace, explicit = explicit, expected = expected, asSet = place == ListPlace.AS_SET
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

    /** [edit] without waiting, in the account scope. */
    fun submitEdit(
        kind: Int,
        edits: List<ListEdit>,
        d: String? = null,
        place: ListPlace = ListPlace.PUBLIC,
        expected: Boolean = false,
        title: String? = null,
        explicit: Boolean = false,
        onResult: (ListResult) -> Unit = {}
    ): Job = scope.launch {
        onResult(edit(kind, edits, d, place, expected, title, explicit))
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
                when (val prep = prepare(acct, kind, d, expectedHint = false, automatic = true)) {
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
                        if (acceptedByWrite(pub, prep.writeRelays, event)) {
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
                    // Whether a list is expected comes from the kept changes themselves (runKey).
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
            _hiddenSets.value = emptySet()
            return
        }
        _hiddenSets.value = store.hiddenSets()
        val all = store.allPending()
        if (all.isEmpty()) willSaveShown = false
        _overlay.value = all.mapNotNull { p -> overlayKey(p.kind, p.d, p.entry)?.let { it to p.wanted } }.toMap()
    }

    /** Changes to [kind] (a set: [d]) still kept for later, oldest first (for an editor that shows them). */
    fun pendingChanges(kind: Int, d: String? = null): List<PendingChange> =
        account()?.store?.pending(OwnListStore.key(kind, d)).orEmpty()

    // ---------------------------------------------------------------- sets: hide, made here

    /**
     * Hide the set [kind]:[d] on this device: what "Delete" does for a set. Nothing is published (never a deletion
     * request); the set stays on relays and in other apps. Shown again by [unhideSet] or by [createSet] with that d.
     */
    fun hideSet(kind: Int, d: String, account: ListAccount? = null) {
        val acct = account ?: this.account() ?: return
        acct.store.hideSet(kind, d)
        refreshOverlay(acct)
    }

    fun unhideSet(kind: Int, d: String, account: ListAccount? = null) {
        val acct = account ?: this.account() ?: return
        acct.store.unhideSet(kind, d)
        refreshOverlay(acct)
    }

    fun isHidden(kind: Int, d: String): Boolean = OwnListStore.key(kind, d) in _hiddenSets.value

    /**
     * Whether a change this app picks by itself (not the user on that set's own screen) may touch [event]: made
     * here, or it looks like this app's own set ([looksMadeHere]). Changes with explicit = true skip this check.
     */
    fun isEditableSet(event: NostrEvent): Boolean = looksMadeHere(account()?.store, event)

    // ---------------------------------------------------------------- whole-version writes

    /**
     * Create a new set [kind] with [d] (a people, bookmark, interest, emoji or relay set). Fetches that d first:
     * - none found and your write relays prove none exists: written as `d`, then `title` (public set), or `d` with the
     *   content holding `[["title", title]]` encrypted (private set). Recorded as made here; any hide on d is lifted.
     * - found, and hidden here: the set is shown again (Unchanged), when it is this app's kind of set.
     * - found otherwise: Refused(NAME_TAKEN).
     * While it runs the set is in [creating], so it shows at once. Not kept for later: when your relays cannot be
     * asked it is Refused(NOT_SENT). Add entries afterwards with [set]/[edit] (expected = true), place AS_SET.
     */
    suspend fun createSet(
        kind: Int,
        d: String,
        title: String?,
        private: Boolean = false,
        account: ListAccount? = null
    ): ListResult {
        val name = title?.takeIf { it.isNotBlank() }
        val marker = PendingChange(kind = kind, d = d, entry = listOf("title", name ?: d), wanted = true, replace = true,
            title = name, isPrivate = private, creating = true)
        _creating.update { it + marker }
        try {
            return createSetNow(kind, d, title, private, account)
        } finally {
            _creating.update { list -> list.filterNot { it === marker } }
        }
    }

    private suspend fun createSetNow(
        kind: Int,
        d: String,
        title: String?,
        private: Boolean,
        account: ListAccount?
    ): ListResult = writeWhole(account, kind, d, expected = false, ignoreSeen = true, quiet = false,
        build = { acct, old, firstQuorum ->
            val store = acct.store
            val key = OwnListStore.key(kind, d)
            when {
                old != null -> {
                    if (store.isHidden(key) && looksMadeHere(store, old)) {
                        store.unhideSet(kind, d)
                        Whole.Done(ListResult.Unchanged)
                    } else Whole.Done(ListResult.Refused(ListResult.Reason.NAME_TAKEN, "a set with that d exists"))
                }
                !firstQuorum -> Whole.Done(ListResult.Refused(ListResult.Reason.NOT_FOUND, "cannot tell that no set exists"))
                else -> {
                    val name = title?.takeIf { it.isNotBlank() }
                    val tags = listOfNotNull(listOf("d", d), name?.takeIf { !private }?.let { listOf("title", it) })
                    if (private) {
                        val priv = listOfNotNull(name?.let { listOf("title", it) })
                        val content = acct.signer.nip44Encrypt(privateJson(priv), acct.pubkeyHex)
                        val back = parsePrivate(acct.signer.nip44Decrypt(content, acct.pubkeyHex))
                        Whole.Write(tags, content, clock(), if (back == priv) null else "the private part changed")
                    } else Whole.Write(tags, "", clock(), null)
                }
            }
        },
        onWritten = { acct, _ ->
            acct.store.markMadeHere(OwnListStore.key(kind, d), private)
            acct.store.unhideSet(kind, d)
        })

    /**
     * Put the entries named in [order] (tags, matched by entry) in that order, each into the places those entries
     * already hold in your newest version; every other tag stays put (10063 media servers). Entries not in the list
     * are skipped. Make adds and removes first with [edit]; call this once that returned Sent or Unchanged.
     * Not kept for later: Refused(NOT_SENT) when your relays cannot be asked.
     */
    suspend fun reorder(
        kind: Int,
        order: List<List<String>>,
        d: String? = null,
        expected: Boolean = false,
        account: ListAccount? = null
    ): ListResult = writeWhole(account, kind, d, expected, ignoreSeen = false, quiet = false,
        build = { acct, old, _ ->
            when (val p = ListWrite.planReorder(listOfNotNull(old), acct.pubkeyHex, kind, d, order, expected = false, now = clock())) {
                is ListWrite.Plan.Nothing -> Whole.Done(ListResult.Unchanged)
                is ListWrite.Plan.Refuse -> Whole.Done(ListResult.Refused(ListResult.Reason.BLOCKED, p.why))
                is ListWrite.Plan.Write -> {
                    val next = ListWrite.Version(kind, p.createdAt, p.content, p.tags)
                    Whole.Write(p.tags, p.content, p.createdAt, ListWrite.guardReorder(ListWrite.version(old!!), next, clock()))
                }
            }
        })

    /**
     * Change only [fields] of your profile (kind 0): a value sets that field, null takes it out. Every other field
     * (unknown ones too) and every tag stay. [expected]: the app shows a profile of yours (then none found is
     * refused, never a profile started from nothing). [onlyIfMissing]: write only when you have no profile anywhere
     * (onboarding); Unchanged when one exists. Not kept for later: Refused(NOT_SENT) when your relays cannot be asked.
     * [quiet]: no toasts (a try that will be made again).
     */
    suspend fun setProfile(
        fields: Map<String, JsonElement?>,
        expected: Boolean = false,
        onlyIfMissing: Boolean = false,
        account: ListAccount? = null,
        quiet: Boolean = false
    ): ListResult = writeWhole(account, 0, null, expected, ignoreSeen = false, quiet = quiet,
        build = { acct, old, firstQuorum ->
            if (old != null && onlyIfMissing) return@writeWhole Whole.Done(ListResult.Unchanged)
            if (old == null && !firstQuorum) {
                return@writeWhole Whole.Done(ListResult.Refused(ListResult.Reason.NOT_FOUND, "cannot tell that no profile exists"))
            }
            when (val p = ListWrite.planProfile(listOfNotNull(old), acct.pubkeyHex, fields, expected = false, now = clock())) {
                is ListWrite.Plan.Nothing -> Whole.Done(ListResult.Unchanged)
                is ListWrite.Plan.Refuse -> Whole.Done(ListResult.Refused(ListResult.Reason.NOT_FOUND, p.why))
                is ListWrite.Plan.Write -> {
                    val next = ListWrite.Version(0, p.createdAt, p.content, p.tags)
                    Whole.Write(p.tags, p.content, p.createdAt, ListWrite.guardProfile(old?.let { ListWrite.version(it) }, next, fields, clock()))
                }
            }
        })

    /** [setProfile] with text fields: a blank value takes the field out. */
    suspend fun setProfileText(
        fields: Map<String, String?>,
        expected: Boolean = false,
        onlyIfMissing: Boolean = false,
        account: ListAccount? = null,
        quiet: Boolean = false
    ): ListResult = setProfile(
        fields.mapValues { (_, v) -> v?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } },
        expected, onlyIfMissing, account, quiet
    )

    /**
     * App data ([kind] 30078 by default, keyed by [d]): a new content on your newest version, every tag kept.
     * [content] gets that version (null when there is none) and returns the new content, or null when the newest
     * already holds what you want (nothing is written). A first version is `d` then [firstTags]. [quiet]: no toasts
     * (a background backup). Not kept for later: Refused(NOT_SENT) when your relays cannot be asked.
     */
    suspend fun setAppData(
        d: String,
        firstTags: List<List<String>> = emptyList(),
        expected: Boolean = false,
        quiet: Boolean = false,
        kind: Int = APP_DATA_KIND,
        account: ListAccount? = null,
        content: suspend (old: NostrEvent?) -> String?
    ): ListResult = writeWhole(account, kind, d, expected, ignoreSeen = false, quiet = quiet,
        build = { _, old, firstQuorum ->
            if (old == null && !firstQuorum) {
                return@writeWhole Whole.Done(ListResult.Refused(ListResult.Reason.NOT_FOUND, "cannot tell that none exists"))
            }
            val wantedContent = content(old)
            when (val p = ListWrite.planContent(old, kind, d, wantedContent, firstTags, expected = false, now = clock())) {
                is ListWrite.Plan.Nothing -> Whole.Done(ListResult.Unchanged)
                is ListWrite.Plan.Refuse -> Whole.Done(ListResult.Refused(ListResult.Reason.BLOCKED, p.why))
                is ListWrite.Plan.Write -> {
                    val next = ListWrite.Version(kind, p.createdAt, p.content, p.tags)
                    val why = if (old != null) ListWrite.guardContent(ListWrite.version(old), next, clock())
                    else if (p.tags.firstOrNull() != listOf("d", d)) "a different list" else null
                    Whole.Write(p.tags, p.content, p.createdAt, why)
                }
            }
        })

    private sealed class Whole {
        data class Done(val result: ListResult) : Whole()
        /** Sign this; [why] non-null: the last check failed, nothing is signed. */
        data class Write(val tags: List<List<String>>, val content: String, val createdAt: Long, val why: String?) : Whole()
    }

    /** Lock, fetch your newest version, [build] the next one, guard, sign, publish, record. */
    private suspend fun writeWhole(
        account: ListAccount?,
        kind: Int,
        d: String?,
        expected: Boolean,
        ignoreSeen: Boolean,
        quiet: Boolean,
        build: suspend (acct: ListAccount, old: NostrEvent?, firstQuorum: Boolean) -> Whole,
        onWritten: (ListAccount, NostrEvent) -> Unit = { _, _ -> }
    ): ListResult = withContext(work) {
        fun out(r: ListResult, silent: Boolean = quiet): ListResult {
            if (r is ListResult.Refused) Log.w(TAG, "write of $kind refused: ${r.reason} (${r.detail})")
            if (!silent && r is ListResult.Refused) messageFor(r.reason, kind)?.let { _messages.tryEmit(it) }
            return r
        }
        try {
            val acct = account ?: this@ListWriter.account() ?: run {
                if (!quiet) _messages.tryEmit(R.string.error_not_logged_in)
                return@withContext ListResult.Refused(ListResult.Reason.NOT_SENT, "no account")
            }
            if (OwnListStore.keyOrNull(fakeEvent(kind, d)) == null) return@withContext out(ListResult.Refused(ListResult.Reason.BLOCKED, "not a list kind"))
            if (OwnListStore.isAddressable(kind) && d == null) return@withContext out(ListResult.Refused(ListResult.Reason.BLOCKED, "no d"))
            val store = acct.store
            if (store.pubkeyHex != acct.pubkeyHex) return@withContext out(ListResult.Refused(ListResult.Reason.NOT_SENT, "store of another account"))
            val me = acct.pubkeyHex
            val key = OwnListStore.key(kind, d)
            val result = store.mutex(key).withLock {
                resendUnconfirmed(acct, key, writeRelaysFor(store))
                val prep = try {
                    prepare(acct, kind, d, expected, ignoreSeen)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "fetch for $key failed", e)
                    Prep.Stop(ListResult.Pending)
                }
                val ready = when (prep) {
                    is Prep.Stop -> {
                        val r = prep.result
                        return@withLock if (r is ListResult.Pending) out(ListResult.Refused(ListResult.Reason.NOT_SENT, "relays did not answer"), quiet || prep.quiet)
                        else out(r, quiet || prep.quiet)
                    }
                    is Prep.Ready -> prep
                }
                val old = ready.old
                val step = build(acct, old, ready.firstQuorum)
                val w = when (step) {
                    is Whole.Done -> return@withLock out(step.result)
                    is Whole.Write -> step
                }
                if (w.why != null) return@withLock out(ListResult.Refused(ListResult.Reason.BLOCKED, w.why))
                if (old != null && (w.createdAt <= old.created_at || old.pubkey.lowercase() != me)) {
                    return@withLock out(ListResult.Refused(ListResult.Reason.BLOCKED, "not newer"))
                }
                val event = try {
                    acct.signer.signEvent(kind = kind, content = w.content, tags = w.tags, createdAt = w.createdAt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "signing $key failed", e)
                    return@withLock out(ListResult.Refused(ListResult.Reason.NOT_SENT, "sign failed"))
                }
                if (event.pubkey.lowercase() != me) return@withLock out(ListResult.Refused(ListResult.Reason.NOT_SENT, "another key"))
                if (activePubkey()?.lowercase() != me) return@withLock out(ListResult.Refused(ListResult.Reason.NOT_SENT, "account changed"), true)
                val pub = sink.publish(event, ready.writeRelays, publishOthers(ready.writeRelays, extraPublishRelays(event)))
                if (pub.sentTo.isEmpty() || !store.recordSigned(event)) {
                    return@withLock out(ListResult.Refused(ListResult.Reason.NOT_SENT, "not sent"))
                }
                onWritten(acct, event)
                safeProject(event)
                if (acceptedByWrite(pub, ready.writeRelays, event)) {
                    store.markConfirmed(key, event.id)
                    ListResult.Sent(event)
                } else {
                    // Signed and sent, no write relay confirmed: kept as newest-but-unconfirmed and re-sent by resume.
                    if (!quiet && !willSaveShown) {
                        willSaveShown = true
                        _messages.tryEmit(R.string.list_will_save)
                    }
                    ListResult.Pending
                }
            }
            refreshOverlay(acct)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "whole write of $kind failed", e)
            out(ListResult.Refused(ListResult.Reason.NOT_SENT, e.message ?: "error"))
        }
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
            // A kept change made while the app showed this list expects it, also when replayed later.
            val expected = expectedHint || store.pending(key).any { it.expected }
            val prep = try {
                prepare(acct, kind, d, expected)
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

    /**
     * Fetch and check everything the plan needs: your newest version, from enough of your write relays.
     * [automatic]: a write the app makes by itself (createIfMissing): a first list needs 2 relays saying none.
     */
    private suspend fun prepare(
        acct: ListAccount,
        kind: Int,
        d: String?,
        expectedHint: Boolean,
        ignoreSeen: Boolean = false,
        automatic: Boolean = false
    ): Prep {
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
            for (e in r.events.sortedWith(compareBy<NostrEvent> { it.created_at }.thenByDescending { it.id })) {
                // Loaded into the app's relays too: else the same version arriving later is "not newer" and the
                // relay settings would stay on what they were (the relay editor diffs against this version).
                if (e.pubkey.lowercase() == me && e.kind == RELAY_LIST_KIND && store.seen(e)) safeProject(e)
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
        val othersAnswered = r.answered.map { norm(it) }.filter { it !in writeSet }.toSet()
        // Indexer answers alone never suffice: enough of ALL your write relays must answer. One that could not be
        // asked (down, on a cooldown, blocked) counts as not answered. Your relay list itself is the exception: the
        // profile indexes keep it too, and it is how dead write relays get replaced. So 2 of them answering will do,
        // but only when they actually hold your relay list, in a version at least as new as the one this device
        // knows. Empty answers, or older copies, prove nothing about what your write relays hold now.
        val storedAt = store.latest(key)?.created_at ?: 0L
        val indexersHoldIt = kind == RELAY_LIST_KIND && othersAnswered.size >= 2 && r.events.any { e ->
            e.pubkey.lowercase() == me && e.kind == kind && e.created_at >= storedAt
        }
        val enough = enoughWriteAnswers(writeAnswered.size, writeSet.size) || indexersHoldIt
        if (!enough) return Prep.Stop(ListResult.Pending)

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
            // Your relay list itself, none known yet: the relays it would be on are the app's (they were asked).
            kind == RELAY_LIST_KIND -> writeSet
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
        val expected = expectedHint || (!ignoreSeen && store.everSeen(key))
        if (old == null && expected) return Prep.Stop(ListResult.Refused(ListResult.Reason.NOT_FOUND, "no list found"))

        var firstQuorum = false
        if (old == null) {
            // Proof that no list exists: at least half of those relays said so, and at least 2 of them, or the one you
            // list when you list only one (the user asked for this list). A list the app starts by itself
            // ([automatic]) always needs 2.
            val need = if (automatic) 2 else minOf(2, base.size)
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
        var privChanged: Boolean = false,
        /** The version it started from has a private part (non-blank content), readable or not. */
        val hasContent: Boolean = false
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
        // A set made by another app: only changes the user made on that very set go in.
        val readOnly = old != null && OwnListStore.isAddressable(kind) && !looksMadeHere(store, old)
        val steps = LinkedHashMap<PendingChange, Step>()
        val written = mutableListOf<PendingChange>()
        for (c in pend) {
            val s = if (readOnly && !c.explicit) Step.Refuse(ListResult.Reason.READ_ONLY, "made in another app")
            else step(kind, draft, placed(kind, draft, c), old == null, prep.firstQuorum)
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
        val touched = ListWrite.touched(written.map {
            when {
                it.replace -> ListWrite.Change(set = it.entry)
                it.wanted -> ListWrite.Change(add = it.entry)
                else -> ListWrite.Change(remove = it.entry)
            }
        })
        val setSlots = written.filter { it.replace }.mapNotNull { ListWrite.slot(it.entry) }.toSet()
        val maxRemoved = written.count { !it.wanted }
        var why: String? = if (old == null) {
            when {
                d != null && draft.pub.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) != d -> "a different list"
                draft.privChanged && (content.isBlank() ||
                    ListWrite.tagsGuardMany(emptyList(), draft.priv, touched, maxRemoved, setSlots) != null) -> "the private part changed"
                else -> null
            }
        } else {
            val ov = ListWrite.version(old)
            ListWrite.versionGuard(ov, next, now, sameContent = !draft.privChanged)
                ?: if (!draft.privChanged) {
                    ListWrite.guardMany(ov, next, touched, maxRemoved, now, setSlots)
                } else {
                    // Public tags: unchanged, or changed only in the entries asked for. The private part: changed,
                    // and only in those entries. The content really is a new one.
                    val p = ListWrite.tagsGuardMany(old.tags, draft.pub, touched, maxRemoved, setSlots)
                    val q = ListWrite.tagsGuardMany(draft.oldPriv, draft.priv, touched, maxRemoved, setSlots)
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
        val pub = sink.publish(event, prep.writeRelays, publishOthers(prep.writeRelays, extraPublishRelays(event)))
        if (pub.sentTo.isEmpty() || !store.recordSigned(event)) {
            // Nothing went out (or a newer version arrived meanwhile): keep every change pending, drop the event.
            settle(ListResult.Pending)
            for (c in written) results[c] = ListResult.Pending
            finish(acct, results)
            return
        }
        safeProject(event)
        if (acceptedByWrite(pub, prep.writeRelays, event)) {
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
        return if (priv == null) Draft(old.tags, emptyList(), opaque = true, nip04 = nip04, hasContent = true)
        else Draft(old.tags, priv, opaque = false, nip04 = nip04, oldPriv = priv, hasContent = true)
    }

    /**
     * [c] with its place decided on the version being changed when it asked for [ListPlace.AS_SET]: public when the
     * set has public entries or no private part, else the private part only.
     */
    private fun placed(kind: Int, draft: Draft, c: PendingChange): PendingChange {
        if (!c.asSet) return c
        val names = APP_SET_TAGS[kind].orEmpty() - setOf("d", "title")
        val publicEntries = draft.pub.any { it.size >= 2 && it[0] in names }
        val private = !publicEntries && draft.hasContent
        return c.copy(isPrivate = private, privateOnly = private)
    }

    /** One change applied to the draft, each checked on its own (ListWrite guards). */
    private fun step(kind: Int, draft: Draft, c: PendingChange, firstList: Boolean, firstQuorum: Boolean): Step {
        val e = c.entry
        val k = ListWrite.key(e) ?: return Step.Refuse(ListResult.Reason.BLOCKED, "not an entry")
        if (e[0] == "d") return Step.Refuse(ListResult.Reason.BLOCKED, "a different list")
        if (c.replace) {
            // Put the entry in place of the tag with its slot, where that tag lives; none: added like any entry.
            val s = ListWrite.slot(e) ?: return Step.Refuse(ListResult.Reason.BLOCKED, "not an entry")
            val pubCopies = draft.pub.filter { ListWrite.slot(it) == s }
            val privCopies = draft.priv.filter { ListWrite.slot(it) == s }
            if (pubCopies.isNotEmpty()) {
                if (pubCopies == listOf(e)) return Step.Nothing
                val nextPub = ListWrite.apply(draft.pub, ListWrite.Change(set = e))
                ListWrite.tagsGuardMany(draft.pub, nextPub, setOf(s), 0, setOf(s))?.let { return Step.Refuse(ListResult.Reason.BLOCKED, it) }
                draft.pub = nextPub
                return Step.Wrote
            }
            if (privCopies.isNotEmpty()) {
                if (privCopies == listOf(e)) return Step.Nothing
                val nextPriv = ListWrite.apply(draft.priv, ListWrite.Change(set = e))
                ListWrite.tagsGuardMany(draft.priv, nextPriv, setOf(s), 0, setOf(s))?.let { return Step.Refuse(ListResult.Reason.BLOCKED, it) }
                draft.priv = nextPriv
                draft.privChanged = true
                return Step.Wrote
            }
            // Not there: a slot that could be in an unreadable private part is not added beside it. A set's title,
            // picture or description never is, wherever the change asked to put it (titles are never moved).
            if (draft.opaque && (c.isPrivate || e[0] in ListWrite.SINGLE)) {
                return Step.Refuse(ListResult.Reason.NOT_FOUND, "private part unreadable")
            }
        }
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
            if (acceptedByWrite(pub, writeRelays, ev)) acct.store.markConfirmed(key, ev.id)
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

    /**
     * One of your write relays accepted it. For a new relay list, the write relays it names count too (it is how
     * write relays that are gone get replaced).
     */
    private fun acceptedByWrite(pub: ListPublishResult, writeRelays: List<String>, event: NostrEvent): Boolean {
        val named = if (event.kind == RELAY_LIST_KIND) writeRelaysOf(event) else emptyList()
        val w = (writeRelays + named).map { norm(it) }.toSet()
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

    private fun messageFor(reason: ListResult.Reason, kind: Int? = null): Int? = when (reason) {
        ListResult.Reason.NOT_FOUND -> if (kind == 0) R.string.profile_not_found else R.string.list_not_found
        ListResult.Reason.BLOCKED -> R.string.list_change_blocked
        ListResult.Reason.NOT_SENT -> R.string.list_not_sent
        ListResult.Reason.NEEDS_CONFIRM -> null
        ListResult.Reason.NAME_TAKEN -> R.string.list_name_taken
        ListResult.Reason.READ_ONLY -> R.string.list_read_only
    }

    private fun fakeEvent(kind: Int, d: String?) =
        NostrEvent("", "", 0, kind, listOfNotNull(d?.let { listOf("d", it) }), "", "")

    companion object {
        private const val TAG = "ListWriter"
        const val RELAY_LIST_KIND = 10002
        const val DM_RELAYS_KIND = 10050
        const val APP_DATA_KIND = 30078
        const val MAX_WRITE_RELAYS = 10
        /** NIP-44 plaintext limit. */
        const val NIP44_MAX = 65535
        /** Kinds whose first list needs the user's confirmation (a follow list started from nothing). */
        val CONFIRM_FIRST_LIST = setOf(3)
        val DEFAULT_INDEXERS: List<String> = RelayConfig.DEFAULT_INDEXER_RELAYS + "wss://purplepag.es"

        /** A relay address in one spelling, for comparing. */
        fun norm(url: String): String = url.trim().lowercase().trimEnd('/')

        /**
         * Entry identity (ListWrite.slot: the entry key, a set's title/image/description by name alone) as one string;
         * also the OwnListStore entryId.
         */
        fun entryId(tag: List<String>): String? = ListWrite.slot(tag)?.let { "${it.first}\u0000${it.second}" }

        fun overlayKey(kind: Int, d: String?, tag: List<String>): String? =
            entryId(tag)?.let { "$kind|${d ?: ""}|$it" }

        /**
         * Kinds 0, 3 and 30078 have no private part of tags (kind 3's content is a relay map, kind 0's the profile,
         * 30078's the app's own data): their content is never read or changed by an entry change.
         */
        fun hasPrivatePart(kind: Int): Boolean = kind != 0 && kind != 3 && kind != APP_DATA_KIND

        /** The d tags this app gives its sets: lowercase letters, digits, - and _. */
        private val SLUG = Regex("^[a-z0-9_-]+$")

        /** The tags this app writes in each kind of set (public part). */
        val APP_SET_TAGS: Map<Int, Set<String>> = mapOf(
            30000 to setOf("d", "title", "p"),
            30002 to setOf("d", "title", "relay"),
            30003 to setOf("d", "title", "e", "a", "t"),
            30015 to setOf("d", "title", "t"),
            30030 to setOf("d", "title", "emoji")
        )

        /**
         * A set this app may change on its own: recorded as made here, or (sets from before that record) its d is a
         * slug this app makes and it has only tags this app writes; its title may be missing, private or not its d.
         * Not a set: always true.
         */
        fun looksMadeHere(store: OwnListStore?, event: NostrEvent): Boolean {
            if (!OwnListStore.isAddressable(event.kind)) return true
            val d = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return false
            if (store?.madeHere(OwnListStore.key(event.kind, d)) == true) return true
            val allowed = APP_SET_TAGS[event.kind] ?: return false
            return SLUG.matches(d) && event.tags.all { it.isNotEmpty() && it[0] in allowed }
        }

        /** Where a relay list itself also goes: every relay in a new 10002, every DM relay in a new 10050. */
        fun extraPublishRelays(event: NostrEvent): List<String> = when (event.kind) {
            RELAY_LIST_KIND -> event.tags.filter { it.size >= 2 && it[0] == "r" }.map { it[1].trim() }
            DM_RELAYS_KIND -> event.tags.filter { it.size >= 2 && it[0] == "relay" }.map { it[1].trim() }
            else -> emptyList()
        }.filter { it.startsWith("wss://") || it.startsWith("ws://") }.distinctBy { norm(it) }.take(MAX_WRITE_RELAYS)

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
