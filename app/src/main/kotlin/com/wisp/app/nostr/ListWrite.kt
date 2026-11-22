package com.wisp.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Changing entries of one of your lists (the house rule: marble-content-fixtures/list-writes.md; a port of Marble's
 * ListWrite). A list is replaced whole, so a write from the wrong start wipes it everywhere: start from your newest
 * version anywhere, change only the entries asked for, keep the rest (unknown tags and content too), and check it all
 * again ([guard], [guardMany], [guardReorder], [privateGuard], [bothGuard]) before it is signed.
 *
 * Pure: no Android, no relays, no signer. The caller fetches, decrypts, signs and publishes.
 */
object ListWrite {
    /** A version dated more than this many seconds after now is never the start of a write (Marble's Policy AHEAD). */
    const val AHEAD = 600L

    /**
     * Tags that a list holds at most once (a set's name, picture and description), keyed by name alone in [slot] so a
     * [Change.set] replaces them in place.
     */
    val SINGLE: Set<String> = setOf("title", "image", "description")

    /**
     * One change: add an entry, remove every copy of an entry, or [set] (replace the tag with the same [slot] in place,
     * or append it when there is none: a relay marker, a set's title). Exactly one of the three for [planMany].
     */
    data class Change(val add: List<String>? = null, val remove: List<String>? = null, val set: List<String>? = null)

    sealed class Plan {
        data class Write(val kind: Int, val tags: List<List<String>>, val content: String, val createdAt: Long, val old: NostrEvent?) : Plan()
        data object Nothing : Plan()
        data class Refuse(val why: String) : Plan()
    }

    /**
     * An entry's identity: its name and value. A relay's address in one spelling (`relay`, `r`); `p` and `e` trimmed and
     * lowercase; a muted `word` lowercase; a `group` (NIP-29) is its id on its relay, `"<id>|<relay>"`, because ids
     * repeat across relays; a media `server` (10063) its address in one spelling, like a relay.
     */
    fun key(t: List<String>): Pair<String, String>? {
        if (t.size < 2) return null
        val v = when (t[0]) {
            "relay", "r", "server" -> relay(t[1])
            "p", "e" -> t[1].trim().lowercase()
            "word" -> t[1].lowercase()
            "group" -> t[1] + "|" + relay(t.getOrNull(2) ?: "")
            else -> t[1]
        }
        return t[0] to v
    }

    /** [key], except a [SINGLE] tag is keyed by its name alone. Used by [planMany], [touched] and [guardMany]. */
    fun slot(t: List<String>): Pair<String, String>? =
        if (t.size >= 2 && t[0] in SINGLE) t[0] to "" else key(t)

    private fun relay(u: String) = u.trim().lowercase().trimEnd('/')

    private fun dTag(e: NostrEvent): String? = e.tags.firstOrNull { it.isNotEmpty() && it[0] == "d" }?.getOrNull(1)

    private fun addressable(kind: Int) = kind in 30000..39999

    /** Your newest version of the list: yours, that kind (a set: that d); a tie in time goes to the lowest id (NIP-01). */
    fun newest(found: List<NostrEvent>, me: String, kind: Int, d: String? = null): NostrEvent? = found
        .filter { it.pubkey == me && it.kind == kind && (d == null || dTag(it) == d) }
        .minWithOrNull(compareByDescending<NostrEvent> { it.created_at }.thenBy { it.id })

    /**
     * What one change does to your list. [expected]: this device has seen your list of this kind, or the account
     * shows one: then no list found is a refusal, never a list started from empty. [maxAhead]: refuse when your newest
     * version is dated more than this many seconds after [now] (null: never; only the shared vectors pass null).
     */
    fun plan(found: List<NostrEvent>, me: String, kind: Int, d: String?, change: Change, expected: Boolean, now: Long, title: String? = null, maxAhead: Long? = AHEAD): Plan {
        if (change.set != null && change.add == null && change.remove == null) {
            return planMany(found, me, kind, d, listOf(change), expected, now, title, maxAhead)
        }
        if (addressable(kind) && d == null) return Plan.Refuse("no d")
        val old = newest(found, me, kind, d)
        val entry = change.add ?: change.remove ?: return Plan.Refuse("no change")
        val k = key(entry) ?: return Plan.Refuse("no change")
        if (entry[0] == "d") return Plan.Refuse("a different list")
        if (old == null) {
            if (expected) return Plan.Refuse("no list found")
            if (change.add == null) return Plan.Nothing
            // A first list: just the entry (a new set: its d, then its title if it has one, then the entry).
            return Plan.Write(kind, listOfNotNull(d?.let { listOf("d", it) }, title?.takeIf { d != null && it.isNotBlank() }?.let { listOf("title", it) }, change.add), "", now, null)
        }
        if (maxAhead != null && old.created_at > now + maxAhead) return Plan.Refuse("dated ahead")
        val has = old.tags.any { key(it) == k }
        val tags = when {
            change.add != null -> if (has) return Plan.Nothing else old.tags + listOf(change.add)
            else -> if (!has) return Plan.Nothing else old.tags.filterNot { key(it) == k }
        }
        return Plan.Write(kind, tags, old.content, maxOf(now, old.created_at + 1), old)
    }

    /**
     * Several changes to one list, applied in order onto your newest version (or onto a first list: its d and title).
     * Entries are matched by [slot]. Nothing when the result equals the start. Check the result with [guardMany] and
     * [touched] of the same changes.
     */
    fun planMany(found: List<NostrEvent>, me: String, kind: Int, d: String?, changes: List<Change>, expected: Boolean, now: Long, title: String? = null, maxAhead: Long? = AHEAD): Plan {
        if (addressable(kind) && d == null) return Plan.Refuse("no d")
        if (changes.isEmpty()) return Plan.Refuse("no change")
        for (c in changes) {
            val e = entryOf(c) ?: return Plan.Refuse("no change")
            if (slot(e) == null) return Plan.Refuse("no change")
            if (e[0] == "d") return Plan.Refuse("a different list")
        }
        val old = newest(found, me, kind, d)
        if (old == null) {
            if (expected) return Plan.Refuse("no list found")
            val base = listOfNotNull(d?.let { listOf("d", it) }, title?.takeIf { d != null && it.isNotBlank() }?.let { listOf("title", it) })
            val tags = applyAll(base, changes)
            return if (tags == base) Plan.Nothing else Plan.Write(kind, tags, "", now, null)
        }
        if (maxAhead != null && old.created_at > now + maxAhead) return Plan.Refuse("dated ahead")
        val tags = applyAll(old.tags, changes)
        if (tags == old.tags) return Plan.Nothing
        return Plan.Write(kind, tags, old.content, maxOf(now, old.created_at + 1), old)
    }

    /** The entries [changes] act on ([slot]s), for [guardMany]. A change that is not exactly one entry adds nothing. */
    fun touched(changes: List<Change>): Set<Pair<String, String>> =
        changes.mapNotNull { c -> entryOf(c)?.let(::slot) }.toSet()

    private fun entryOf(c: Change): List<String>? = listOfNotNull(c.add, c.remove, c.set).singleOrNull()

    private fun applyAll(start: List<List<String>>, changes: List<Change>): List<List<String>> =
        changes.fold(start) { tags, c -> applyOne(tags, c) }

    /** One change applied to [tags] the way [planMany] does it (entries matched by [slot]). */
    fun apply(tags: List<List<String>>, change: Change): List<List<String>> = applyOne(tags, change)

    private fun applyOne(tags: List<List<String>>, c: Change): List<List<String>> {
        val add = c.add
        val remove = c.remove
        val set = c.set
        return when {
            add != null -> {
                val s = slot(add)
                if (tags.any { slot(it) == s }) tags else tags + listOf(add)
            }
            remove != null -> {
                val s = slot(remove)
                tags.filterNot { slot(it) == s }
            }
            set != null -> {
                val s = slot(set)
                val out = ArrayList<List<String>>(tags.size + 1)
                var done = false
                for (t in tags) {
                    if (slot(t) == s) {
                        if (!done) { out.add(set); done = true }
                    } else {
                        out.add(t)
                    }
                }
                if (!done) out.add(set)
                out
            }
            else -> tags
        }
    }

    /** One version of a list, for the last check: what it is, when, its private part, its tags. */
    data class Version(val kind: Int, val createdAt: Long, val content: String, val tags: List<List<String>>)

    fun version(e: NostrEvent): Version = Version(e.kind, e.created_at, e.content, e.tags)

    private fun dOf(tags: List<List<String>>): String? = tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)

    /**
     * The part of every check that is not about entries: same kind and d, the content unchanged (unless
     * [sameContent] is false, for a private-part edit checked with [privateGuard] or [bothGuard]), newer, and, when
     * [now] is given, the old version not dated more than [AHEAD] seconds ahead.
     */
    fun versionGuard(old: Version, next: Version, now: Long? = null, sameContent: Boolean = true): String? {
        if (old.kind != next.kind || dOf(old.tags) != dOf(next.tags)) return "a different list"
        if (sameContent && old.content != next.content) return "the private part changed"
        if (next.createdAt <= old.createdAt) return "not newer"
        if (now != null && old.createdAt > now + AHEAD) return "dated ahead"
        return null
    }

    /**
     * The last check before signing, whatever made the list: null if [next] changes exactly one entry of [old] and
     * nothing else; else why not.
     */
    fun guard(old: Version, next: Version, now: Long? = null): String? =
        versionGuard(old, next, now) ?: tagsGuard(old.tags, next.tags)

    /** The tag part of [guard] (also run on a decrypted private part, its public tags unchanged). */
    fun tagsGuard(old: List<List<String>>, next: List<List<String>>): String? {
        val before = old.mapNotNull(::key).toSet(); val after = next.mapNotNull(::key).toSet()
        val removed = before - after; val added = after - before
        if (removed.size > 1) return "takes out more than one"
        if (removed.size + added.size != 1) return "changes more than the one entry"
        if (old.filterNot { key(it) in removed } != next.filterNot { key(it) in added }) return "other tags changed"
        return null
    }

    /** A list's private part checked the same way (decrypted tags), with its public tags unchanged. */
    fun privateGuard(oldPublic: List<List<String>>, nextPublic: List<List<String>>, oldPrivate: List<List<String>>, nextPrivate: List<List<String>>): String? =
        if (oldPublic != nextPublic) "other tags changed" else tagsGuard(oldPrivate, nextPrivate)

    /**
     * The last check for several changes: like [guard], but every entry added or taken out must be in [touched]
     * (from [touched]), at most [maxRemoved] entries come out, every tag that was not added, taken out or in [set]
     * stays the same, in order, and the added tags (one each) are the tail. A [set] slot (from [Change.set]) may
     * differ, but stays where its first copy was, once.
     */
    fun guardMany(old: Version, next: Version, touched: Set<Pair<String, String>>, maxRemoved: Int, now: Long? = null, set: Set<Pair<String, String>> = emptySet()): String? =
        versionGuard(old, next, now) ?: tagsGuardMany(old.tags, next.tags, touched, maxRemoved, set)

    /** The tag part of [guardMany] (also for a decrypted private part). */
    fun tagsGuardMany(old: List<List<String>>, next: List<List<String>>, touched: Set<Pair<String, String>>, maxRemoved: Int, set: Set<Pair<String, String>> = emptySet()): String? {
        val before = old.mapNotNull(::slot).toSet(); val after = next.mapNotNull(::slot).toSet()
        val removed = before - after; val added = after - before
        if ((removed + added).any { it !in touched }) return "changes more than asked"
        if (removed.size > maxRemoved) return "takes out more than asked"
        if (old == next) return "changes nothing"
        // New entries: one tag each, and together they are exactly the tail.
        if (next.count { slot(it) in added } != added.size || next.takeLast(added.size).any { slot(it) !in added }) return "other tags changed"
        // Everything else the same, in place. A set slot may differ, but stays where its first copy was, once.
        fun shape(tags: List<List<String>>, collapse: Boolean): List<List<String>> {
            val seen = HashSet<Pair<String, String>>()
            return tags.mapNotNull { t ->
                val s = slot(t)
                when {
                    s != null && (s in removed || s in added) -> null
                    s != null && s in set -> if (seen.add(s) || !collapse) listOf("\u0000set", s.first, s.second) else null
                    else -> t
                }
            }
        }
        if (shape(old, true) != shape(next, false)) return "other tags changed"
        return null
    }

    /** The last check for a reorder (10063): the same tags, each as many times, in a new order; content unchanged. */
    fun guardReorder(old: Version, next: Version, now: Long? = null): String? {
        versionGuard(old, next, now)?.let { return it }
        if (old.tags == next.tags) return "changes nothing"
        if (old.tags.groupingBy { it }.eachCount() != next.tags.groupingBy { it }.eachCount()) return "other tags changed"
        return null
    }

    /**
     * Taking one entry out of wherever it lives (an unmute): the public tags and the decrypted private part each stay
     * as they were or lose exactly every copy of [entry] (a [key]), and at least one of them loses it. The content
     * changes when the private part does, so check the rest with [versionGuard] and sameContent = false.
     */
    fun bothGuard(oldPublic: List<List<String>>, nextPublic: List<List<String>>, oldPrivate: List<List<String>>, nextPrivate: List<List<String>>, entry: Pair<String, String>): String? {
        fun part(o: List<List<String>>, n: List<List<String>>): Boolean? = when {
            o == n -> false
            o.any { key(it) == entry } && n == o.filterNot { key(it) == entry } -> true
            else -> null
        }
        val pub = part(oldPublic, nextPublic) ?: return "other tags changed"
        val priv = part(oldPrivate, nextPrivate) ?: return "other tags changed"
        if (!pub && !priv) return "changes nothing"
        return null
    }

    // ---------------------------------------------------------------- reorder (10063)

    /**
     * [tags] with the entries named in [order] (by [key]) put in that order, each into the places those entries
     * already held; every other tag stays where it is. A named entry that is not in [tags] is skipped. Null when a
     * named entry is there more than once or is the `d` tag (which copy goes where cannot be told).
     */
    fun reorder(tags: List<List<String>>, order: List<List<String>>): List<List<String>>? {
        val wanted = order.mapNotNull(::key).distinct()
        if (wanted.any { it.first == "d" }) return null
        val wantedSet = wanted.toSet()
        val positions = tags.indices.filter { key(tags[it]) in wantedSet }
        val present = positions.map { key(tags[it])!! }
        if (present.size != present.toSet().size) return null
        val byKey = positions.associate { key(tags[it])!! to tags[it] }
        val sequence = wanted.filter { it in byKey }
        val out = tags.toMutableList()
        positions.forEachIndexed { i, pos -> out[pos] = byKey.getValue(sequence[i]) }
        return out
    }

    /** A new order for your newest version of a list ([reorder]); check the result with [guardReorder]. */
    fun planReorder(found: List<NostrEvent>, me: String, kind: Int, d: String?, order: List<List<String>>, expected: Boolean, now: Long, maxAhead: Long? = AHEAD): Plan {
        if (addressable(kind) && d == null) return Plan.Refuse("no d")
        val old = newest(found, me, kind, d) ?: return if (expected) Plan.Refuse("no list found") else Plan.Nothing
        if (maxAhead != null && old.created_at > now + maxAhead) return Plan.Refuse("dated ahead")
        val tags = reorder(old.tags, order) ?: return Plan.Refuse("an entry is there twice")
        if (tags == old.tags) return Plan.Nothing
        return Plan.Write(kind, tags, old.content, maxOf(now, old.created_at + 1), old)
    }

    // ---------------------------------------------------------------- profile (kind 0)

    private val profileJson = Json { ignoreUnknownKeys = true }

    /** A profile's fields: its content as a JSON object (blank content is an empty one), or null when it is not one. */
    fun profileFields(content: String): JsonObject? {
        if (content.isBlank()) return JsonObject(emptyMap())
        return try {
            profileJson.parseToJsonElement(content) as? JsonObject
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Your profile with only [fields] changed: a value sets that field (in its place, or at the end when new), null
     * takes the field out. Every other field, unknown ones too, and every tag stay as they were. [expected]: you have
     * a profile (this device saw one): then none found is a refusal. Check the result with [guardProfile].
     */
    fun planProfile(found: List<NostrEvent>, me: String, fields: Map<String, JsonElement?>, expected: Boolean, now: Long, maxAhead: Long? = AHEAD): Plan {
        if (fields.isEmpty()) return Plan.Refuse("no change")
        val old = newest(found, me, 0)
        if (old == null) {
            if (expected) return Plan.Refuse("no list found")
            val first = LinkedHashMap<String, JsonElement>()
            for ((k, v) in fields) if (v != null) first[k] = v
            if (first.isEmpty()) return Plan.Nothing
            return Plan.Write(0, emptyList(), JsonObject(first).toString(), now, null)
        }
        if (maxAhead != null && old.created_at > now + maxAhead) return Plan.Refuse("dated ahead")
        val start = profileFields(old.content) ?: return Plan.Refuse("profile unreadable")
        val next = LinkedHashMap<String, JsonElement>(start)
        for ((k, v) in fields) if (v == null) next.remove(k) else next[k] = v
        if (next == start) return Plan.Nothing
        return Plan.Write(0, old.tags, JsonObject(next).toString(), maxOf(now, old.created_at + 1), old)
    }

    /**
     * The last check for a profile: newer, tags unchanged, every field in [fields] as asked (null: gone), every
     * other field the same value and in the same order. [old] null: a first profile, no tags, only [fields].
     */
    fun guardProfile(old: Version?, next: Version, fields: Map<String, JsonElement?>, now: Long? = null): String? {
        if (next.kind != 0 || (old != null && old.kind != 0)) return "a different list"
        if (old != null) {
            versionGuard(old, next, now, sameContent = false)?.let { return it }
            if (old.tags != next.tags) return "other tags changed"
        } else if (next.tags.isNotEmpty()) return "other tags changed"
        val o = if (old != null) profileFields(old.content) ?: return "profile unreadable" else JsonObject(emptyMap())
        val n = profileFields(next.content) ?: return "the profile changed"
        if (o == n) return "changes nothing"
        for (k in o.keys + n.keys + fields.keys) {
            if (fields.containsKey(k)) {
                val want = fields[k]
                if (want == null) { if (n.containsKey(k)) return "changes more than asked" }
                else if (n[k] != want) return "changes more than asked"
            } else if (o[k] != n[k]) return "changes more than asked"
        }
        val keptOld = o.keys.filter { it in n && !fields.containsKey(it) }
        val keptNew = n.keys.filter { it in o && !fields.containsKey(it) }
        if (keptOld != keptNew) return "other fields moved"
        return null
    }

    // ---------------------------------------------------------------- content only (30078 app data)

    /**
     * A new content for your newest version of [kind] (a set: [d]), every tag kept. [content] null: nothing to write
     * (the caller found the newest already holds it). With no version found and nothing [expected], a first version:
     * its d, then [firstTags]. Check the result with [guardContent].
     */
    fun planContent(old: NostrEvent?, kind: Int, d: String?, content: String?, firstTags: List<List<String>>, expected: Boolean, now: Long, maxAhead: Long? = AHEAD): Plan {
        if (addressable(kind) && d == null) return Plan.Refuse("no d")
        if (content == null) return Plan.Nothing
        if (old == null) {
            if (expected) return Plan.Refuse("no list found")
            val tags = listOfNotNull(d?.let { listOf("d", it) }) + firstTags.filterNot { it.firstOrNull() == "d" }
            return Plan.Write(kind, tags, content, now, null)
        }
        if (old.kind != kind || (d != null && dTag(old) != d)) return Plan.Refuse("a different list")
        if (maxAhead != null && old.created_at > now + maxAhead) return Plan.Refuse("dated ahead")
        if (old.content == content) return Plan.Nothing
        return Plan.Write(kind, old.tags, content, maxOf(now, old.created_at + 1), old)
    }

    /** The last check for a content-only write: same kind and d, newer, every tag the same, the content new. */
    fun guardContent(old: Version, next: Version, now: Long? = null): String? {
        versionGuard(old, next, now, sameContent = false)?.let { return it }
        if (old.tags != next.tags) return "other tags changed"
        if (old.content == next.content) return "changes nothing"
        return null
    }
}
