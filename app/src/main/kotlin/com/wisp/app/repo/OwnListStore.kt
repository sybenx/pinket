package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Key/value storage behind [OwnListStore]. Production uses [PrefsOwnListStorage]
 * (one SharedPreferences file per account); JVM tests use [MemoryOwnListStorage].
 */
interface OwnListStorage {
    fun get(name: String): String?
    /** `null` removes the entry. */
    fun put(name: String, value: String?)
    fun names(): Set<String>
}

class PrefsOwnListStorage(private val prefs: SharedPreferences) : OwnListStorage {
    override fun get(name: String): String? = prefs.getString(name, null)
    override fun put(name: String, value: String?) {
        val edit = prefs.edit()
        if (value == null) edit.remove(name) else edit.putString(name, value)
        edit.apply()
    }
    override fun names(): Set<String> = prefs.all.keys.toSet()
}

class MemoryOwnListStorage : OwnListStorage {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(name: String): String? = map[name]
    override fun put(name: String, value: String?) {
        if (value == null) map.remove(name) else map[name] = value
    }
    override fun names(): Set<String> = map.keys.toSet()
}

/**
 * A list change the user asked for that could not be written yet (offline, no relay answered).
 * Replayed onto the newest version later. [wanted] = true means the entry should be in the list.
 * [isPrivate]: a new entry goes in the private part; [privateOnly]: and never falls back to a
 * public tag (refused instead). [firstListOk]: the user confirmed starting a first list (kind 3).
 * [title]: a new set's title, used only when the change starts a first list.
 * [replace]: put [entry] in place of the tag with the same slot (ListWrite.slot: a relay's marker, a set's title),
 * or add it when there is none ([wanted] is then always true).
 * [explicit]: the user picked this very set on screen; only then may a set made by another app be changed.
 * [expected]: the app showed a list of this kind when the change was made, so a replay that finds none is refused
 * (never a list started from nothing), the same as the tap itself.
 * [asSet]: a new entry goes where the set keeps its entries, decided on the newest version when it is written
 * ([isPrivate] and [privateOnly] are then ignored).
 * [creating]: not a change; a set being created right now, shown at once (never stored).
 */
@Serializable
data class PendingChange(
    val kind: Int,
    val d: String? = null,
    val entry: List<String>,
    val wanted: Boolean,
    val isPrivate: Boolean = false,
    val at: Long = 0,
    val privateOnly: Boolean = false,
    val firstListOk: Boolean = false,
    val title: String? = null,
    val replace: Boolean = false,
    val explicit: Boolean = false,
    val expected: Boolean = false,
    val asSet: Boolean = false,
    @kotlinx.serialization.Transient val creating: Boolean = false
) {
    val key: String get() = OwnListStore.key(kind, d)
}

/**
 * The fuller version of a list, kept when a version from a relay replaced it and may have lost entries.
 * [event]: its JSON. [at]: when that was noticed (seconds). [open]: checked (both private parts read) and it is a
 * drop, so it is offered; false: still to be checked. [excluded]: entries (ListWriter.entryId) taken out on this
 * device since, never offered back.
 */
@Serializable
data class KeptCopy(
    val event: String,
    val at: Long,
    val open: Boolean = false,
    val excluded: List<String> = emptyList()
)

/**
 * Per-account record of the newest own version of each list (kind, or kind:d for sets),
 * as raw event JSON, plus what this device knows about each list:
 * - everSeen: this device has seen a version (survives [clear]); a seeded legacy watermark > 0 counts.
 * - watermark: created_at of what the old repos had already loaded (legacy `*_updated` prefs),
 *   seeded once per key; events older than it are never accepted.
 * - madeHere: a set this app created (and whether it was created private).
 * - unconfirmed: a signed event that no relay has accepted yet.
 * - pending: changes that could not be written yet.
 * - kept (wipe guard): the version before one from a relay that may have lost entries ([KeptCopy], ListDrop).
 * - hidden: sets the user took away here (kind:d). Nothing is published for that (never a deletion request), the set
 *   is only not shown on this device. Survives [clear].
 *
 * Any replaceable (0, 3, 10000-19999) or addressable (30000-39999) kind is accepted here, but the app offers only
 * [STORED_KINDS] (the lists ListWriter writes, plus your relay list), so the one prefs file stays small.
 * Never reaches into other repositories: legacy watermarks are passed in.
 */
class OwnListStore(
    pubkeyHex: String,
    private val storage: OwnListStorage,
    legacyWatermarks: Map<String, Long> = emptyMap(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val entryId: (List<String>) -> String? = { OwnListStore.defaultEntryId(it) }
) {
    val pubkeyHex: String = pubkeyHex.lowercase()
    private val lock: Any = locks.getOrPut(this.pubkeyHex) { Any() }

    init {
        seedWatermarks(legacyWatermarks)
    }

    // ---- versions ----

    /**
     * Offer an own event (from a relay or the router). Returns true when it became the newest
     * version for its key. Rejected: other pubkeys, kinds that are not lists, events dated more than
     * [MAX_FUTURE_SECONDS] ahead, events older than the seeded watermark, and anything not newer
     * than the stored version (newer = later created_at, or same second with the lower id).
     * The same event as the unconfirmed one confirms it (a relay had it).
     */
    fun seen(event: NostrEvent): Boolean = synchronized(lock) {
        val key = keyOrNull(event) ?: return false
        if (event.pubkey.lowercase() != pubkeyHex) return false
        val unconfirmedId = unconfirmedEvent(key)?.id
        if (unconfirmedId != null && unconfirmedId == event.id) storage.put(UNCONF + key, null)
        val old = parse(storage.get(EV + key))
        val ok = accept(key, event)
        if (ok && old != null) keepIfDropped(key, old, event)
        ok
    }

    /**
     * Record an event this device just signed: stored as newest (same checks as [seen]) and
     * marked unconfirmed until [markConfirmed] or a relay sends it back through [seen].
     */
    fun recordSigned(event: NostrEvent): Boolean = synchronized(lock) {
        val key = keyOrNull(event) ?: return false
        if (event.pubkey.lowercase() != pubkeyHex) return false
        // Recorded now: an echo from a relay from here on is not newer than what is stored.
        signedHereIds.remove("$pubkeyHex|${event.id}")
        // A relay can echo the version back before this runs (a live subscription for your own events): the store
        // then already holds it, from a relay, and that is as good as recorded.
        if (parse(storage.get(EV + key))?.id == event.id) return true
        val ok = accept(key, event)
        if (ok) storage.put(UNCONF + key, event.toJson())
        ok
    }

    fun latest(kind: Int, d: String? = null): NostrEvent? = latest(key(kind, d))

    fun latest(key: String): NostrEvent? = synchronized(lock) { parse(storage.get(EV + key)) }

    fun everSeen(key: String): Boolean = synchronized(lock) {
        storage.get(EVER + key) != null || watermark(key) > 0
    }

    fun everSeen(kind: Int, d: String? = null): Boolean = everSeen(key(kind, d))

    /** created_at floor from the legacy prefs (0 when none). */
    fun watermark(key: String): Long = storage.get(WM + key)?.toLongOrNull() ?: 0L

    /**
     * Lower the floor for [key] to [to] (never raises it). Only for when your write relays proved that nothing newer
     * than [to] exists anywhere: the version the floor came from was signed here and never reached a relay.
     */
    fun lowerWatermark(key: String, to: Long) {
        synchronized(lock) {
            if (to < watermark(key)) storage.put(WM + key, to.coerceAtLeast(0L).toString())
        }
    }

    // ---- wipe guard: the fuller copy kept when a version from a relay lost entries ----

    /**
     * [event] was just signed on this device and is about to be sent: when a relay sends it back before
     * [recordSigned] runs, it is this device's own change, never a drop. Remembered for this app run only.
     */
    fun signedHere(event: NostrEvent) {
        signedHereIds.add("$pubkeyHex|${event.id}")
    }

    /** The kept copy for [key], or null. */
    fun kept(key: String): KeptCopy? = synchronized(lock) { readKept(key) }

    /** Keys that have a kept copy. */
    fun keptKeys(): Set<String> = synchronized(lock) {
        storage.names().filter { it.startsWith(KEPT) }.map { it.removePrefix(KEPT) }.toSet()
    }

    /** The kept copy for [key] was checked and is a drop: offer it (it stays until [forgetKept]). */
    fun openKept(key: String) {
        synchronized(lock) {
            val k = readKept(key) ?: return
            if (!k.open) writeKept(key, k.copy(open = true))
        }
    }

    /** Forget the kept copy for [key] (put back, left, or nothing is missing any more). */
    fun forgetKept(key: String) {
        synchronized(lock) { storage.put(KEPT + key, null) }
    }

    /**
     * Entries the user takes out here on purpose ([ids], ListWriter.entryId): never offered back from the kept copy
     * for [key]. Nothing happens when there is no kept copy.
     */
    fun excludeFromKept(key: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            val k = readKept(key) ?: return
            val next = (k.excluded + ids).distinct()
            if (next.size != k.excluded.size) writeKept(key, k.copy(excluded = next))
        }
    }

    /**
     * A version from a relay replaced [old] for [key]: keep [old] when it may have lost entries (ListDrop). The first
     * kept copy stays (the earliest, fullest one); a version this device signed is never a drop.
     */
    private fun keepIfDropped(key: String, old: NostrEvent, next: NostrEvent) {
        if (!ListDrop.guarded(next.kind)) return
        if ("$pubkeyHex|${next.id}" in signedHereIds) return
        if (storage.get(KEPT + key) != null) return
        if (!ListDrop.mayHaveDropped(old, next)) return
        writeKept(key, KeptCopy(event = old.toJson(), at = clock()))
    }

    private fun readKept(key: String): KeptCopy? {
        val raw = storage.get(KEPT + key) ?: return null
        return try {
            json.decodeFromString(KeptCopy.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeKept(key: String, k: KeptCopy) {
        storage.put(KEPT + key, json.encodeToString(KeptCopy.serializer(), k))
    }

    // ---- unconfirmed ----

    /** The signed event for [key] that no relay has accepted yet, or null. */
    fun unconfirmed(key: String): NostrEvent? = synchronized(lock) { unconfirmedEvent(key) }

    /** Keys that have an unconfirmed signed event (for re-sending on reconnect). */
    fun unconfirmedKeys(): Set<String> = synchronized(lock) {
        storage.names().filter { it.startsWith(UNCONF) }.map { it.removePrefix(UNCONF) }.toSet()
    }

    /** A relay accepted [eventId]; clears the flag only if it is still the unconfirmed one. */
    fun markConfirmed(key: String, eventId: String) {
        synchronized(lock) {
            if (unconfirmedEvent(key)?.id == eventId) storage.put(UNCONF + key, null)
        }
    }

    // ---- made here / new account ----

    fun markMadeHere(key: String, isPrivate: Boolean = false) {
        synchronized(lock) { storage.put(MADE + key, if (isPrivate) "private" else "public") }
    }

    fun madeHere(key: String): Boolean = storage.get(MADE + key) != null

    fun madeHerePrivate(key: String): Boolean = storage.get(MADE + key) == "private"

    /**
     * True the first time it is called for [name] on this account (and records it), false after: for one-time
     * migrations. Survives [clear], like madeHere.
     */
    fun firstTime(name: String): Boolean = synchronized(lock) {
        if (storage.get(ONCE + name) != null) return@synchronized false
        storage.put(ONCE + name, "1")
        true
    }

    /**
     * Call ONLY where this app generates a brand new key (sign-up), never on import or login. Stores when, so that
     * only sign-up itself ([isOnboardingWindow]) may skip the fetch for a first list.
     */
    fun markNewAccount() {
        storage.put(NEW_ACCOUNT, clock().toString())
    }

    /** The key was generated on this device (kept until logout). */
    fun isNewAccount(): Boolean = storage.get(NEW_ACCOUNT) != null

    /**
     * Still inside sign-up ([NEW_ACCOUNT_WINDOW] seconds after [markNewAccount]): only then may a first list skip the
     * fetch. An old mark without a time ("1") is outside it.
     */
    fun isOnboardingWindow(): Boolean {
        val at = storage.get(NEW_ACCOUNT)?.toLongOrNull() ?: return false
        return clock() - at in 0..NEW_ACCOUNT_WINDOW
    }

    // ---- hidden sets ----

    /** Hide the set [kind]:[d] on this device (the user "deleted" it). Nothing is published. */
    fun hideSet(kind: Int, d: String) {
        synchronized(lock) { storage.put(HIDDEN + key(kind, d), "1") }
    }

    /**
     * Hide the set [kind]:[d] for a deletion request dated [at] (another app's, or this app's from before sets were
     * hidden instead), unless the newest version known is newer than it or the user hid the set. The request's date
     * is kept with the hide, so a newer version shows the set again ([showAgainIfNewer]) in this session or a later
     * one. True when the hide was written.
     */
    fun hideForDeletion(kind: Int, d: String, at: Long): Boolean = synchronized(lock) {
        val key = key(kind, d)
        val cur = storage.get(HIDDEN + key)
        if (cur != null && !cur.startsWith(DELETION)) return@synchronized false
        val shownAt = storage.get(SHOWN + key)?.toLongOrNull()
        if (shownAt != null && at <= shownAt) return@synchronized false
        val latest = parse(storage.get(EV + key))
        if (latest != null && latest.created_at > at) return@synchronized false
        val prev = cur?.removePrefix(DELETION)?.toLongOrNull()
        if (prev != null && prev >= at) return@synchronized false
        storage.put(HIDDEN + key, DELETION + at)
        true
    }

    /** The date of the deletion request a set is hidden for, or null (not hidden, or hidden by the user). */
    fun deletionHideAt(key: String): Long? =
        storage.get(HIDDEN + key)?.takeIf { it.startsWith(DELETION) }?.removePrefix(DELETION)?.toLongOrNull()

    /**
     * [event] is a version of a set hidden for a deletion request and newer than that request: show the set again.
     * A set the user hid stays hidden. True when it was shown again.
     */
    fun showAgainIfNewer(event: NostrEvent): Boolean = synchronized(lock) {
        if (event.pubkey.lowercase() != pubkeyHex) return@synchronized false
        val key = keyOrNull(event)?.takeIf { isAddressable(event.kind) } ?: return@synchronized false
        val at = deletionHideAt(key) ?: return@synchronized false
        if (event.created_at <= at) return@synchronized false
        storage.put(HIDDEN + key, null)
        true
    }

    /** Show the set again (it was created again here, or the user asked). */
    fun unhideSet(kind: Int, d: String) {
        synchronized(lock) {
            storage.put(HIDDEN + key(kind, d), null)
            // Remember when it was brought back: a deletion request from before then must not hide it again.
            storage.put(SHOWN + key(kind, d), clock().toString())
        }
    }

    fun isHidden(kind: Int, d: String): Boolean = isHidden(key(kind, d))

    fun isHidden(key: String): Boolean = storage.get(HIDDEN + key) != null

    /** Every hidden set, as "kind:d" keys. */
    fun hiddenSets(): Set<String> = synchronized(lock) {
        storage.names().filter { it.startsWith(HIDDEN) }.map { it.removePrefix(HIDDEN) }.toSet()
    }

    // ---- pending ----

    /** Adds a pending change; replaces an earlier pending change for the same list and entry. */
    fun addPending(change: PendingChange) {
        synchronized(lock) {
            val id = entryId(change.entry)
            val list = readPending(change.key).filterNot { id != null && entryId(it.entry) == id }
            writePending(change.key, list + change)
        }
    }

    fun pending(key: String): List<PendingChange> = synchronized(lock) { readPending(key) }

    fun pending(kind: Int, d: String? = null): List<PendingChange> = pending(key(kind, d))

    fun allPending(): List<PendingChange> = synchronized(lock) {
        storage.names().filter { it.startsWith(PENDING) }
            .flatMap { readPending(it.removePrefix(PENDING)) }
    }

    /** Removes exactly this change (a newer change for the same entry is kept). */
    fun removePending(change: PendingChange): Boolean = synchronized(lock) {
        val list = readPending(change.key)
        val next = list.filterNot { it == change }
        if (next.size == list.size) return@synchronized false
        writePending(change.key, next)
        true
    }

    fun clearPending(key: String) {
        synchronized(lock) { storage.put(PENDING + key, null) }
    }

    // ---- locking / reset ----

    // ---- what the app's settings were loaded from ----

    /** The app's own settings for [key] (relays, media servers) were last loaded from the version [eventId]. */
    fun markProjected(key: String, eventId: String) {
        synchronized(lock) { storage.put(PROJECTED + key, eventId) }
    }

    /** The version the app's settings for [key] were last loaded from, or null. */
    fun projected(key: String): String? = storage.get(PROJECTED + key)

    /** One Mutex per account and key, shared by every store instance of this account. */
    fun mutex(key: String): Mutex = mutexes.getOrPut("$pubkeyHex|$key") { Mutex() }

    fun mutex(kind: Int, d: String? = null): Mutex = mutex(key(kind, d))

    /**
     * Forgets stored versions (and what was loaded from them), kept copies, unconfirmed events, pending changes and the
     * new-account mark. Keeps everSeen, watermarks, madeHere and hidden sets.
     */
    fun clear() {
        synchronized(lock) {
            for (name in storage.names()) {
                if (name.startsWith(EV) || name.startsWith(UNCONF) || name.startsWith(KEPT) || name.startsWith(PENDING) || name.startsWith(PROJECTED) || name == NEW_ACCOUNT) {
                    storage.put(name, null)
                }
            }
        }
    }

    // ---- internals ----

    private fun accept(key: String, event: NostrEvent): Boolean {
        if (event.created_at > clock() + MAX_FUTURE_SECONDS) return false
        if (event.created_at < watermark(key)) return false
        val old = parse(storage.get(EV + key))
        if (old != null && !newer(event, old)) return false
        storage.put(EV + key, event.toJson())
        storage.put(EVER + key, "1")
        // A signed version no relay confirmed is replaced by a newer one: never re-send it again.
        val u = unconfirmedEvent(key)
        if (u != null && u.id != event.id && newer(event, u)) storage.put(UNCONF + key, null)
        return true
    }

    private fun seedWatermarks(legacy: Map<String, Long>) {
        synchronized(lock) {
            val ceiling = clock()
            for ((key, value) in legacy) {
                if (storage.get(WM_SET + key) != null) continue
                val wm = value.coerceIn(0L, ceiling)
                if (wm > 0) storage.put(WM + key, wm.toString())
                storage.put(WM_SET + key, "1")
            }
        }
    }

    private fun unconfirmedEvent(key: String): NostrEvent? = parse(storage.get(UNCONF + key))

    private fun readPending(key: String): List<PendingChange> {
        val raw = storage.get(PENDING + key) ?: return emptyList()
        return try {
            json.decodeFromString(pendingSerializer, raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writePending(key: String, list: List<PendingChange>) {
        storage.put(PENDING + key, if (list.isEmpty()) null else json.encodeToString(pendingSerializer, list))
    }

    private fun parse(raw: String?): NostrEvent? {
        if (raw == null) return null
        return try { NostrEvent.fromJson(raw) } catch (_: Exception) { null }
    }

    companion object {
        const val MAX_FUTURE_SECONDS = 600L
        /** Seconds after sign-up in which a first list may be written without asking relays. */
        const val NEW_ACCOUNT_WINDOW = 15 * 60L

        /**
         * The kinds the app keeps here, all written through ListWriter: profile (0), follows (3), mutes, pins, your
         * relay list, bookmarks, search and blocked relays, groups, favourite relays, emoji list, DM relays, media
         * servers, the sets (people, relay, bookmark, interest, emoji) and app data (only the d tags in
         * [STORED_APP_DATA]).
         */
        val STORED_KINDS: Set<Int> = setOf(
            0, 3, 10000, 10001, 10002, 10003, 10006, 10007, 10009, 10012, 10030, 10050, 10063,
            30000, 30002, 30003, 30015, 30030, 30078
        )

        /** App data (30078) this app writes: only these d tags are kept (other apps keep theirs under the same kind). */
        val STORED_APP_DATA: Set<String> = setOf(com.wisp.app.nostr.Nip78.NWC_BACKUP_D_TAG)

        /** Whether the app keeps this event's kind (and, for app data, its d) here. Not about whose event it is. */
        fun keeps(event: NostrEvent): Boolean {
            if (event.kind !in STORED_KINDS) return false
            if (event.kind == com.wisp.app.nostr.Nip78.KIND) {
                val d = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
                return d in STORED_APP_DATA
            }
            return true
        }

        private const val EV = "ev:"
        private const val EVER = "ever:"
        private const val WM = "wm:"
        private const val WM_SET = "wmset:"
        private const val MADE = "made:"
        private const val UNCONF = "unconf:"
        private const val PENDING = "pending:"
        private const val NEW_ACCOUNT = "new_account"
        private const val HIDDEN = "hidden:"
        private const val SHOWN = "shown:"
        /** A hidden value for a set hidden for a deletion request: this prefix, then the request's created_at. */
        private const val DELETION = "del:"
        private const val ONCE = "once:"
        private const val PROJECTED = "proj:"
        /** The wipe guard's kept copy of a list ([KeptCopy]). */
        private const val KEPT = "guard:"
        /** "pubkey|event id" of versions signed on this device in this app run ([signedHere]). */
        private val signedHereIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val pendingSerializer = ListSerializer(PendingChange.serializer())
        private val locks = ConcurrentHashMap<String, Any>()
        private val mutexes = ConcurrentHashMap<String, Mutex>()

        fun prefsName(pubkeyHex: String): String = "wisp_ownlists_${pubkeyHex.lowercase()}"

        /** Production constructor: SharedPreferences `wisp_ownlists_<pubkey>`. */
        fun open(
            context: Context,
            pubkeyHex: String,
            legacyWatermarks: Map<String, Long> = emptyMap(),
            entryId: (List<String>) -> String? = { defaultEntryId(it) }
        ): OwnListStore = OwnListStore(
            pubkeyHex,
            PrefsOwnListStorage(context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)),
            legacyWatermarks,
            entryId = entryId
        )

        fun isReplaceable(kind: Int): Boolean = kind == 0 || kind == 3 || kind in 10000..19999

        fun isAddressable(kind: Int): Boolean = kind in 30000..39999

        /** "3", "10000", or "30000:<d>" (a missing d is ""). */
        fun key(kind: Int, d: String? = null): String =
            if (isAddressable(kind)) "$kind:${d ?: ""}" else kind.toString()

        /** Key for an event, or null when the kind is not a list kind. */
        fun keyOrNull(event: NostrEvent): String? = when {
            isReplaceable(event.kind) -> event.kind.toString()
            isAddressable(event.kind) -> key(event.kind, event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: "")
            else -> null
        }

        /** NIP-01: later created_at wins; same second, the lower id wins. */
        fun newer(a: NostrEvent, than: NostrEvent): Boolean =
            a.created_at > than.created_at || (a.created_at == than.created_at && a.id < than.id)

        /** Fallback entry identity for pending de-duplication: tag name + value (word lowercased, relay normalised). */
        fun defaultEntryId(tag: List<String>): String? {
            if (tag.size < 2) return null
            val value = when (tag[0]) {
                "word" -> tag[1].lowercase()
                "relay", "r" -> tag[1].trim().lowercase().trimEnd('/')
                "group" -> tag[1] + "|" + (tag.getOrNull(2)?.trim()?.lowercase()?.trimEnd('/') ?: "")
                else -> tag[1]
            }
            return tag[0] + "\u0000" + value
        }
    }
}
