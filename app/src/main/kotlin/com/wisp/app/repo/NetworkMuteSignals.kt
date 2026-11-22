package com.wisp.app.repo

import com.wisp.app.nostr.NostrEvent

/** Pure helpers for the follows' public mute lists (NIP-51 kind 10000). */
object NetworkMuteSignals {
    const val KIND_MUTE_LIST = 10000

    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

    /**
     * Pubkeys in the event's public `p` tags. Only 64-hex values count; the
     * encrypted content (private mutes) is ignored on purpose.
     */
    fun publicMutedPubkeys(event: NostrEvent): Set<String> {
        if (event.kind != KIND_MUTE_LIST) return emptySet()
        val out = HashSet<String>()
        for (tag in event.tags) {
            if (tag.size < 2 || tag[0] != "p") continue
            val pk = tag[1]
            if (HEX64.matches(pk)) out.add(pk.lowercase())
        }
        return out
    }

    /**
     * Replaceable-event rule: newest created_at wins; on a tie the lowest id wins.
     * Returns true when [candidate] should replace [current].
     */
    fun isNewer(candidate: NostrEvent, current: NostrEvent?): Boolean {
        if (current == null) return true
        if (candidate.created_at != current.created_at) return candidate.created_at > current.created_at
        return candidate.id < current.id
    }

    /** Keep the newest kind 10000 event per author, limited to [allowedAuthors]. */
    fun newestPerAuthor(events: Iterable<NostrEvent>, allowedAuthors: Set<String>): Map<String, NostrEvent> {
        val out = HashMap<String, NostrEvent>()
        for (e in events) {
            if (e.kind != KIND_MUTE_LIST || e.pubkey !in allowedAuthors) continue
            if (isNewer(e, out[e.pubkey])) out[e.pubkey] = e
        }
        return out
    }

    /** (muted pubkey, muter) rows for the muted_by table. */
    fun mutedByRows(newestByAuthor: Map<String, NostrEvent>): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        for ((muter, event) in newestByAuthor) {
            for (pk in publicMutedPubkeys(event)) {
                if (pk != muter) rows.add(pk to muter)
            }
        }
        return rows
    }
}

/** Pure helpers for the follows' reports (NIP-56 kind 1984). Only spam reports count. */
object NetworkReportSignals {
    const val KIND_REPORT = 1984
    const val TYPE_SPAM = "spam"

    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

    /**
     * Pubkeys this event reports as spam. The report type is the third
     * element of a `p` or `e` tag; a report counts when any of them says
     * "spam", and it then applies to every `p`-tagged account.
     */
    fun spamReportedPubkeys(event: NostrEvent): Set<String> {
        if (event.kind != KIND_REPORT) return emptySet()
        val isSpam = event.tags.any {
            it.size >= 3 && (it[0] == "p" || it[0] == "e") && it[2].equals(TYPE_SPAM, ignoreCase = true)
        }
        if (!isSpam) return emptySet()
        val out = HashSet<String>()
        for (tag in event.tags) {
            if (tag.size < 2 || tag[0] != "p") continue
            val pk = tag[1]
            if (HEX64.matches(pk)) out.add(pk.lowercase())
        }
        out.remove(event.pubkey)
        return out
    }

    /** (reported pubkey, reporter) rows for the reported_by table, reporters limited to [allowedAuthors]. */
    fun reportedByRows(events: Iterable<NostrEvent>, allowedAuthors: Set<String>): List<Pair<String, String>> {
        val rows = LinkedHashSet<Pair<String, String>>()
        for (e in events) {
            if (e.pubkey !in allowedAuthors) continue
            for (pk in spamReportedPubkeys(e)) rows.add(pk to e.pubkey)
        }
        return rows.toList()
    }

    /** Tags for the user's own spam report on [event]. A private post is reported by author only. */
    fun buildSpamReportTags(eventId: String?, authorPubkey: String): List<List<String>> =
        if (eventId != null) listOf(listOf("e", eventId, TYPE_SPAM), listOf("p", authorPubkey))
        else listOf(listOf("p", authorPubkey, TYPE_SPAM))
}

/**
 * Compact, immutable pubkey -> count lookup. Keys are the first 64 bits of
 * the pubkey (collisions are negligible at graph sizes), stored sorted so a
 * lookup is a binary search with no allocation. Safe to read from any thread,
 * including the main thread and inside locks.
 */
class PubkeyCountTable private constructor(
    private val keys: LongArray,
    private val counts: IntArray
) {
    val size: Int get() = keys.size

    /** Count for [pubkey], or 0 when absent. */
    fun get(pubkey: String): Int {
        val k = keyOf(pubkey) ?: return 0
        val i = keys.binarySearch(k)
        return if (i >= 0) counts[i] else 0
    }

    companion object {
        val EMPTY = PubkeyCountTable(LongArray(0), IntArray(0))

        fun keyOf(pubkey: String): Long? {
            if (pubkey.length < 16) return null
            return try {
                java.lang.Long.parseUnsignedLong(pubkey.substring(0, 16), 16)
            } catch (_: NumberFormatException) {
                null
            }
        }

        fun from(entries: Map<String, Int>): PubkeyCountTable {
            val merged = HashMap<Long, Int>(entries.size * 2)
            for ((pk, c) in entries) {
                val k = keyOf(pk) ?: continue
                merged[k] = maxOf(merged[k] ?: 0, c)
            }
            val sorted = merged.keys.toLongArray().also { it.sort() }
            val counts = IntArray(sorted.size) { merged.getValue(sorted[it]) }
            return PubkeyCountTable(sorted, counts)
        }
    }
}
