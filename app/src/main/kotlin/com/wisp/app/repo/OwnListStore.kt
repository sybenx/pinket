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
    val title: String? = null
) {
    val key: String get() = OwnListStore.key(kind, d)
}

/**
 * Per-account record of the newest own version of each list (kind, or kind:d for sets),
 * as raw event JSON, plus what this device knows about each list:
 * - everSeen: this device has seen a version (survives [clear]); a seeded legacy watermark > 0 counts.
 * - watermark: created_at of what the old repos had already loaded (legacy `*_updated` prefs),
 *   seeded once per key; events older than it are never accepted.
 * - madeHere: a set this app created (and whether it was created private).
 * - unconfirmed: a signed event that no relay has accepted yet.
 * - pending: changes that could not be written yet.
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
        accept(key, event)
    }

    /**
     * Record an event this device just signed: stored as newest (same checks as [seen]) and
     * marked unconfirmed until [markConfirmed] or a relay sends it back through [seen].
     */
    fun recordSigned(event: NostrEvent): Boolean = synchronized(lock) {
        val key = keyOrNull(event) ?: return false
        if (event.pubkey.lowercase() != pubkeyHex) return false
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

    /** One Mutex per account and key, shared by every store instance of this account. */
    fun mutex(key: String): Mutex = mutexes.getOrPut("$pubkeyHex|$key") { Mutex() }

    fun mutex(kind: Int, d: String? = null): Mutex = mutex(key(kind, d))

    /**
     * Forgets stored versions, unconfirmed events, pending changes and the new-account mark.
     * Keeps everSeen, watermarks and madeHere.
     */
    fun clear() {
        synchronized(lock) {
            for (name in storage.names()) {
                if (name.startsWith(EV) || name.startsWith(UNCONF) || name.startsWith(PENDING) || name == NEW_ACCOUNT) {
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
         * The kinds the app keeps here: follows, mutes, pins, bookmarks, your relay list (ListWriter reads it) and the
         * DM relay list. Add the phase 2 kinds as they move to ListWriter.
         */
        val STORED_KINDS: Set<Int> = setOf(3, 10000, 10001, 10003, 10002, 10050)

        private const val EV = "ev:"
        private const val EVER = "ever:"
        private const val WM = "wm:"
        private const val WM_SET = "wmset:"
        private const val MADE = "made:"
        private const val UNCONF = "unconf:"
        private const val PENDING = "pending:"
        private const val NEW_ACCOUNT = "new_account"

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
