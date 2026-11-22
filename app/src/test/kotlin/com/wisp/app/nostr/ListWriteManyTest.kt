package com.wisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pinket's additions to Marble's ListWrite: group and word keys, planMany/guardMany, guardReorder, bothGuard, dated ahead. */
class ListWriteManyTest {
    private val me = "ME"
    private val now = 1000L

    private fun ev(id: String, createdAt: Long, tags: List<List<String>>, content: String = "", kind: Int = 3, pubkey: String = me) =
        NostrEvent(id, pubkey, createdAt, kind, tags, content, "")

    private fun ver(kind: Int, createdAt: Long, tags: List<List<String>>, content: String = "") =
        ListWrite.Version(kind, createdAt, content, tags)

    private fun write(p: ListWrite.Plan): ListWrite.Plan.Write = p as? ListWrite.Plan.Write ?: error("not a write: $p")

    private fun add(vararg t: String) = ListWrite.Change(add = t.toList())
    private fun remove(vararg t: String) = ListWrite.Change(remove = t.toList())
    private fun set(vararg t: String) = ListWrite.Change(set = t.toList())

    // --- group key: id plus relay ---

    private val groups = listOf(
        listOf("group", "_", "wss://a.example"),
        listOf("group", "_", "wss://B.example/"),
        listOf("r", "wss://a.example")
    )

    @Test fun groupKeyIsIdPlusNormalisedRelay() {
        assertEquals("group" to "_|wss://b.example", ListWrite.key(listOf("group", "_", " wss://B.Example/ ")))
        assertTrue(ListWrite.key(listOf("group", "_", "wss://a.example")) != ListWrite.key(listOf("group", "_", "wss://b.example")))
        assertEquals("group" to "x|", ListWrite.key(listOf("group", "x")))
    }

    @Test fun leaveSameGroupIdOnOneRelayKeepsTheOther() {
        val found = listOf(ev("g1", 900, groups, kind = 10009))
        val p = write(ListWrite.plan(found, me, 10009, null, remove("group", "_", "wss://b.example"), true, now))
        assertEquals(listOf(groups[0], groups[2]), p.tags)
        assertNull(ListWrite.guard(ListWrite.version(found[0]), ListWrite.Version(p.kind, p.createdAt, p.content, p.tags), now))

        val m = write(ListWrite.planMany(found, me, 10009, null, listOf(remove("group", "_", "wss://b.example")), true, now))
        assertEquals(p.tags, m.tags)
    }

    @Test fun joinSameGroupIdOnAnotherRelayIsWritten() {
        val found = listOf(ev("g1", 900, groups, kind = 10009))
        val p = write(ListWrite.plan(found, me, 10009, null, add("group", "_", "wss://c.example"), true, now))
        assertEquals(groups + listOf(listOf("group", "_", "wss://c.example")), p.tags)
        // The same group written differently is already in.
        assertEquals(ListWrite.Plan.Nothing, ListWrite.plan(found, me, 10009, null, add("group", "_", "wss://A.example/"), true, now))
    }

    // --- word compares lowercase ---

    @Test fun wordCaseIsOneEntry() {
        assertEquals(ListWrite.key(listOf("word", "Foo")), ListWrite.key(listOf("word", "fOO")))
        val found = listOf(ev("w1", 900, listOf(listOf("p", "P1"), listOf("word", "Foo"), listOf("t", "x")), content = "ENC", kind = 10000))
        assertEquals(ListWrite.Plan.Nothing, ListWrite.plan(found, me, 10000, null, add("word", "FOO"), true, now))
        val p = write(ListWrite.plan(found, me, 10000, null, remove("word", "foo"), true, now))
        assertEquals(listOf(listOf("p", "P1"), listOf("t", "x")), p.tags)
        assertEquals("ENC", p.content)
        assertNull(ListWrite.guard(ListWrite.version(found[0]), ListWrite.Version(p.kind, p.createdAt, p.content, p.tags)))
    }

    // --- planMany / guardMany ---

    private val follows = listOf(listOf("p", "P1", "wss://x.example", "jo"), listOf("t", "nostr"), listOf("unknown", "keep"), listOf("p", "P2"))

    @Test fun planManyAddsInOrderAndGuardManyAcceptsExactlyThose() {
        val old = ev("f1", 900, follows, content = "{\"relays\":1}")
        val changes = listOf(add("p", "P3"), add("p", "P1"), add("p", "P4"))
        val p = write(ListWrite.planMany(listOf(old), me, 3, null, changes, true, now))
        assertEquals(follows + listOf(listOf("p", "P3"), listOf("p", "P4")), p.tags)
        assertEquals(old.content, p.content)
        assertEquals(1000L, p.createdAt)
        val touched = ListWrite.touched(changes)
        assertEquals(setOf("p" to "p3", "p" to "p1", "p" to "p4"), touched)
        val next = ListWrite.Version(p.kind, p.createdAt, p.content, p.tags)
        assertNull(ListWrite.guardMany(ListWrite.version(old), next, touched, 0, now))
    }

    @Test fun guardManyRefusesAnythingNotTouched() {
        val old = ver(3, 900, follows)
        val touched = setOf("p" to "p3")
        // An untouched entry added.
        assertEquals("changes more than asked", ListWrite.guardMany(old, ver(3, 1000, follows + listOf(listOf("p", "P3"), listOf("p", "P9"))), touched, 0))
        // An untouched entry taken out.
        assertEquals("changes more than asked", ListWrite.guardMany(old, ver(3, 1000, follows.drop(1) + listOf(listOf("p", "P3"))), touched, 0))
        // A touched entry taken out, but no removal was asked for.
        assertEquals("takes out more than asked", ListWrite.guardMany(ver(3, 900, follows + listOf(listOf("p", "P3"))), ver(3, 1000, follows), touched, 0))
        // An untouched tag changed in place (a petname dropped).
        assertEquals("other tags changed", ListWrite.guardMany(old, ver(3, 1000, listOf(listOf("p", "P1")) + follows.drop(1) + listOf(listOf("p", "P3"))), touched, 0))
        // The order of the others changed.
        assertEquals("other tags changed", ListWrite.guardMany(old, ver(3, 1000, follows.reversed() + listOf(listOf("p", "P3"))), touched, 0))
        // Content, time, list.
        assertEquals("the private part changed", ListWrite.guardMany(old, ver(3, 1000, follows + listOf(listOf("p", "P3")), "x"), touched, 0))
        assertEquals("not newer", ListWrite.guardMany(old, ver(3, 900, follows + listOf(listOf("p", "P3"))), touched, 0))
        assertEquals("a different list", ListWrite.guardMany(old, ver(10000, 1000, follows + listOf(listOf("p", "P3"))), touched, 0))
        assertEquals("changes nothing", ListWrite.guardMany(old, ver(3, 1000, follows), touched, 0))
    }

    @Test fun guardManyKeepsTouchedEntriesThatStayedAsTheyWere() {
        val old = ver(3, 900, follows)
        // followAll: P1 is already followed, P3 is new; both are touched.
        val touched = ListWrite.touched(listOf(add("p", "P1"), add("p", "P3")))
        // P1's relay hint and petname lost.
        val stripped = listOf(listOf("p", "P1"), listOf("t", "nostr"), listOf("unknown", "keep"), listOf("p", "P2"), listOf("p", "P3"))
        assertEquals("other tags changed", ListWrite.guardMany(old, ver(3, 1000, stripped), touched, 0))
        // P3 inserted at the start instead of appended.
        assertEquals("other tags changed", ListWrite.guardMany(old, ver(3, 1000, listOf(listOf("p", "P3")) + follows), touched, 0))
        // P3 appended twice.
        assertEquals("other tags changed", ListWrite.guardMany(old, ver(3, 1000, follows + listOf(listOf("p", "P3"), listOf("p", "P3"))), touched, 0))
        // P1 duplicated.
        assertEquals("other tags changed", ListWrite.guardMany(old, ver(3, 1000, follows + listOf(listOf("p", "P1"), listOf("p", "P3"))), touched, 0))
        // Appended once: fine.
        assertNull(ListWrite.guardMany(old, ver(3, 1000, follows + listOf(listOf("p", "P3"))), touched, 0))
        // A set of a slot the list does not have yet is appended at the tail.
        val titled = ListWrite.touched(listOf(set("title", "New")))
        assertNull(ListWrite.guardMany(old, ver(3, 1000, follows + listOf(listOf("title", "New"))), titled, 0, set = titled))
    }

    @Test fun guardManyCountsRemovals() {
        val old = ver(3, 900, follows)
        val touched = setOf("p" to "p1", "p" to "p2")
        val next = ver(3, 1000, listOf(listOf("t", "nostr"), listOf("unknown", "keep")))
        assertEquals("takes out more than asked", ListWrite.guardMany(old, next, touched, 1))
        assertNull(ListWrite.guardMany(old, next, touched, 2))
    }

    @Test fun planManyRemovesAndAddsTogether() {
        val old = ev("f1", 900, follows)
        val changes = listOf(remove("p", "p2"), add("p", "P5"), remove("p", "P9"))
        val p = write(ListWrite.planMany(listOf(old), me, 3, null, changes, true, now))
        assertEquals(follows.dropLast(1) + listOf(listOf("p", "P5")), p.tags)
        assertNull(ListWrite.guardMany(ListWrite.version(old), ListWrite.Version(p.kind, p.createdAt, p.content, p.tags), ListWrite.touched(changes), 2))
    }

    @Test fun planManySetReplacesInPlace() {
        // A relay marker changed in place.
        val relays = listOf(listOf("r", "wss://a.example", "read"), listOf("r", "wss://b.example"), listOf("x", "y"))
        val old = ev("r1", 900, relays, kind = 10002)
        val changes = listOf(set("r", "wss://A.example/"))
        val p = write(ListWrite.planMany(listOf(old), me, 10002, null, changes, true, now))
        assertEquals(listOf(listOf("r", "wss://A.example/"), relays[1], relays[2]), p.tags)
        assertNull(ListWrite.guardMany(ListWrite.version(old), ListWrite.Version(p.kind, p.createdAt, p.content, p.tags), ListWrite.touched(changes), 0, set = ListWrite.touched(changes)))
        // Without saying it is a set, a changed tag in place is refused.
        assertEquals("other tags changed", ListWrite.guardMany(ListWrite.version(old), ListWrite.Version(p.kind, p.createdAt, p.content, p.tags), ListWrite.touched(changes), 0))
        // The same marker again is nothing.
        assertEquals(ListWrite.Plan.Nothing, ListWrite.planMany(listOf(old), me, 10002, null, listOf(set("r", "wss://a.example", "read")), true, now))

        // A set's title renamed in place; d stays.
        val named = ev("s1", 900, listOf(listOf("d", "news"), listOf("title", "News"), listOf("p", "P1")), kind = 30000)
        val t = listOf(set("title", "Reading"))
        val r = write(ListWrite.planMany(listOf(named), me, 30000, "news", t, true, now))
        assertEquals(listOf(listOf("d", "news"), listOf("title", "Reading"), listOf("p", "P1")), r.tags)
        assertNull(ListWrite.guardMany(ListWrite.version(named), ListWrite.Version(r.kind, r.createdAt, r.content, r.tags), ListWrite.touched(t), 0, set = ListWrite.touched(t)))
        // plan with a set-only change goes the same way.
        assertEquals(r, ListWrite.plan(listOf(named), me, 30000, "news", t[0], true, now))
    }

    @Test fun planManyNothingAndRefusals() {
        val old = ev("f1", 900, follows)
        // Every change already done, or undone by a later one.
        assertEquals(ListWrite.Plan.Nothing, ListWrite.planMany(listOf(old), me, 3, null, listOf(add("p", "P1"), remove("p", "P9")), true, now))
        assertEquals(ListWrite.Plan.Nothing, ListWrite.planMany(listOf(old), me, 3, null, listOf(add("p", "P7"), remove("p", "P7")), true, now))
        assertEquals(ListWrite.Plan.Refuse("no change"), ListWrite.planMany(listOf(old), me, 3, null, emptyList(), true, now))
        assertEquals(ListWrite.Plan.Refuse("no change"), ListWrite.planMany(listOf(old), me, 3, null, listOf(ListWrite.Change(add = listOf("p", "A"), remove = listOf("p", "B"))), true, now))
        assertEquals(ListWrite.Plan.Refuse("no change"), ListWrite.planMany(listOf(old), me, 3, null, listOf(add("p")), true, now))
        assertEquals(ListWrite.Plan.Refuse("a different list"), ListWrite.planMany(listOf(old), me, 3, null, listOf(add("d", "x")), true, now))
        assertEquals(ListWrite.Plan.Refuse("a different list"), ListWrite.plan(listOf(old), me, 3, null, add("d", "x"), true, now))
        // A set needs its d.
        assertEquals(ListWrite.Plan.Refuse("no d"), ListWrite.planMany(emptyList(), me, 30000, null, listOf(add("p", "P1")), false, now))
        assertEquals(ListWrite.Plan.Refuse("no d"), ListWrite.plan(emptyList(), me, 30000, null, add("p", "P1"), false, now))
        // Expected but missing.
        assertEquals(ListWrite.Plan.Refuse("no list found"), ListWrite.planMany(listOf(ev("o", 900, follows, pubkey = "OTHER")), me, 3, null, listOf(add("p", "P1")), true, now))
    }

    @Test fun planManyFirstList() {
        val p = write(ListWrite.planMany(emptyList(), me, 30003, "reading", listOf(add("e", "E1"), add("e", "E2")), false, now, title = "Reading"))
        assertEquals(listOf(listOf("d", "reading"), listOf("title", "Reading"), listOf("e", "E1"), listOf("e", "E2")), p.tags)
        assertEquals("", p.content)
        assertEquals(now, p.createdAt)
        assertNull(p.old)
        assertEquals(ListWrite.Plan.Nothing, ListWrite.planMany(emptyList(), me, 10003, null, listOf(remove("e", "E1")), false, now))
    }

    // --- dated ahead ---

    @Test fun futureDatedOldIsRefused() {
        val ahead = listOf(ev("z1", now + ListWrite.AHEAD + 1, follows))
        assertEquals(ListWrite.Plan.Refuse("dated ahead"), ListWrite.plan(ahead, me, 3, null, add("p", "P9"), true, now))
        assertEquals(ListWrite.Plan.Refuse("dated ahead"), ListWrite.planMany(ahead, me, 3, null, listOf(add("p", "P9")), true, now))

        // At the limit it is still the start, and the write is old + 1.
        val edge = listOf(ev("z2", now + ListWrite.AHEAD, follows))
        assertEquals(now + ListWrite.AHEAD + 1, write(ListWrite.plan(edge, me, 3, null, add("p", "P9"), true, now)).createdAt)

        // The guards refuse it when given now, and not without.
        val old = ver(3, now + ListWrite.AHEAD + 1, follows)
        val next = ver(3, now + ListWrite.AHEAD + 2, follows + listOf(listOf("p", "P9")))
        assertEquals("dated ahead", ListWrite.guard(old, next, now))
        assertNull(ListWrite.guard(old, next))
        assertEquals("dated ahead", ListWrite.guardMany(old, next, setOf("p" to "p9"), 0, now))
        assertEquals("dated ahead", ListWrite.versionGuard(old, next, now))
    }

    @Test fun clockBehindWithinLimitWritesOldPlusOne() {
        val p = write(ListWrite.planMany(listOf(ev("c1", now + 300, follows)), me, 3, null, listOf(add("p", "P9")), true, now))
        assertEquals(now + 301, p.createdAt)
    }

    // --- guardReorder ---

    @Test fun guardReorder() {
        val servers = listOf(listOf("server", "https://a.example"), listOf("server", "https://b.example"), listOf("x", "y"))
        val old = ver(10063, 900, servers)
        assertNull(ListWrite.guardReorder(old, ver(10063, 1000, servers.reversed())))
        assertEquals("changes nothing", ListWrite.guardReorder(old, ver(10063, 1000, servers)))
        assertEquals("other tags changed", ListWrite.guardReorder(old, ver(10063, 1000, servers.drop(1))))
        assertEquals("other tags changed", ListWrite.guardReorder(old, ver(10063, 1000, servers + listOf(servers[0]))))
        assertEquals("the private part changed", ListWrite.guardReorder(old, ver(10063, 1000, servers.reversed(), "x")))
        assertEquals("not newer", ListWrite.guardReorder(old, ver(10063, 900, servers.reversed())))
    }

    // --- bothGuard ---

    @Test fun bothGuard() {
        val pub = listOf(listOf("p", "P1"), listOf("t", "x"), listOf("p", "P2"))
        val priv = listOf(listOf("p", "P2"), listOf("word", "w"), listOf("e", "E1"))
        val k = ListWrite.key(listOf("p", "P2"))!!
        val pubOut = listOf(listOf("p", "P1"), listOf("t", "x"))
        val privOut = listOf(listOf("word", "w"), listOf("e", "E1"))
        // Out of both parts, or of either one.
        assertNull(ListWrite.bothGuard(pub, pubOut, priv, privOut, k))
        assertNull(ListWrite.bothGuard(pub, pubOut, priv, priv, k))
        assertNull(ListWrite.bothGuard(pub, pub, priv, privOut, k))
        // Out of neither.
        assertEquals("changes nothing", ListWrite.bothGuard(pub, pub, priv, priv, k))
        // Something else went too, or the part did not have it, or something was added.
        assertEquals("other tags changed", ListWrite.bothGuard(pub, listOf(listOf("t", "x")), priv, privOut, k))
        assertEquals("other tags changed", ListWrite.bothGuard(pub, pubOut, priv, listOf(listOf("e", "E1")), k))
        assertEquals("other tags changed", ListWrite.bothGuard(pubOut, pubOut.dropLast(1), priv, privOut, k))
        assertEquals("other tags changed", ListWrite.bothGuard(pub, pub + listOf(listOf("p", "P3")), priv, privOut, k))
        // Order of the rest kept.
        assertEquals("other tags changed", ListWrite.bothGuard(pub, pubOut.reversed(), priv, privOut, k))
        // Every copy goes, in any spelling of the key.
        val twice = listOf(listOf("p", "P2"), listOf("p", "p2", "wss://hint"), listOf("t", "x"))
        assertNull(ListWrite.bothGuard(twice, listOf(listOf("t", "x")), emptyList(), emptyList(), k))
        assertNotNull(ListWrite.bothGuard(twice, listOf(listOf("p", "p2", "wss://hint"), listOf("t", "x")), emptyList(), emptyList(), k))
    }

    @Test fun versionGuardForPrivateEdits() {
        val old = ver(10000, 900, emptyList(), "A")
        assertNull(ListWrite.versionGuard(old, ver(10000, 1000, emptyList(), "B"), now, sameContent = false))
        assertEquals("the private part changed", ListWrite.versionGuard(old, ver(10000, 1000, emptyList(), "B"), now))
    }
}
