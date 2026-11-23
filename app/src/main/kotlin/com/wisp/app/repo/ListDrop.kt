package com.wisp.app.repo

import com.wisp.app.nostr.ListWrite
import com.wisp.app.nostr.NostrEvent

/**
 * The wipe guard's rules, pure: which lists it watches, what an entry is, and when a new version of a list lost
 * enough entries to keep the version before it ([OwnListStore] keeps it, WipeGuard offers to put the entries back).
 */
object ListDrop {
    /** Follows, mutes, pins, bookmarks, people sets and bookmark sets. */
    val GUARDED_KINDS: Set<Int> = setOf(3, 10000, 10001, 10003, 30000, 30003)

    /** A version that lost at least this many entries in one step counts as a drop. */
    const val MIN_MISSING = 3

    /** Tags that describe a list rather than being one of its entries. */
    val NOT_ENTRIES: Set<String> = setOf(
        "d", "title", "description", "image", "alt", "client", "summary", "name", "published_at", "expiration"
    )

    fun guarded(kind: Int): Boolean = kind in GUARDED_KINDS

    /** Kind 3's content is a relay map, never a private part. */
    fun hasPrivatePart(kind: Int): Boolean = kind != 3

    /** An entry's identity, the same one ListWriter uses (ListWrite.slot), or null for a tag that is not an entry. */
    fun id(tag: List<String>): String? {
        if (tag.isEmpty() || tag[0] in NOT_ENTRIES) return null
        return ListWrite.slot(tag)?.let { "${it.first}\u0000${it.second}" }
    }

    /** The entries of [tags] by identity, first copy of each, in order. */
    fun entries(tags: List<List<String>>): LinkedHashMap<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for (t in tags) {
            val id = id(t) ?: continue
            if (id !in out) out[id] = t
        }
        return out
    }

    /**
     * Whether [next] (from a relay) may have lost enough entries of [old] to be a drop, judged without decrypting:
     * at least [MIN_MISSING] public entries gone, or a private part that was there and is now different (it may have
     * lost entries, or have been emptied; decided once both can be read). Entries that went public to private are
     * sorted out then too.
     */
    fun mayHaveDropped(old: NostrEvent, next: NostrEvent): Boolean {
        if (!guarded(old.kind) || old.kind != next.kind) return false
        val gone = entries(old.tags).keys - entries(next.tags).keys
        if (gone.size >= MIN_MISSING) return true
        return hasPrivatePart(old.kind) && old.content.isNotBlank() && next.content != old.content
    }

    /** What is missing: entries of the kept copy in neither part of the current version, in the kept copy's order. */
    data class Missing(val public: List<List<String>>, val private: List<List<String>>) {
        val size: Int get() = public.size + private.size
    }

    /**
     * Entries of the kept copy ([keptPub] tags, [keptPriv] decrypted private tags) that the current version
     * ([curPub], [curPriv]) has in neither part, less [excluded] ids (entries taken out here on purpose). An entry
     * kept in both parts goes back public.
     */
    fun missing(
        keptPub: List<List<String>>,
        keptPriv: List<List<String>>,
        curPub: List<List<String>>,
        curPriv: List<List<String>>,
        excluded: Set<String> = emptySet()
    ): Missing {
        val now = entries(curPub).keys + entries(curPriv).keys
        val pub = entries(keptPub).filterKeys { it !in now && it !in excluded }
        val priv = entries(keptPriv).filterKeys { it !in now && it !in excluded && it !in pub }
        return Missing(pub.values.toList(), priv.values.toList())
    }

    /**
     * A drop, once both versions can be read: [MIN_MISSING] or more entries missing, or a private part that had
     * entries and now has none, with something missing (entries that all went public are not a drop).
     */
    fun isDrop(missing: Missing, keptPriv: List<List<String>>, curPriv: List<List<String>>): Boolean =
        missing.size >= MIN_MISSING ||
            (missing.size > 0 && entries(keptPriv).isNotEmpty() && entries(curPriv).isEmpty())
}
