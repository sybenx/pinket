package com.wisp.app.nostr

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 2 additions to ListWrite: set slots, relay markers, server keys, reorder, profile fields, content-only writes. */
class ListWritePhase2Test {
    private val me = "ME"
    private val now = 1000L

    private fun ev(id: String, createdAt: Long, tags: List<List<String>>, content: String = "", kind: Int = 10002, pubkey: String = me) =
        NostrEvent(id, pubkey, createdAt, kind, tags, content, "")

    private fun write(p: ListWrite.Plan): ListWrite.Plan.Write = p as? ListWrite.Plan.Write ?: error("not a write: $p")

    // --- keys and slots ---

    @Test fun serverIsKeyedByItsAddressInOneSpelling() {
        assertEquals("server" to "https://blossom.example", ListWrite.key(listOf("server", " https://Blossom.Example/ ")))
    }

    @Test fun titleImageAndDescriptionAreSlotsByName() {
        assertEquals("title" to "", ListWrite.slot(listOf("title", "A")))
        assertEquals("image" to "", ListWrite.slot(listOf("image", "https://x/a.png")))
        assertEquals("description" to "", ListWrite.slot(listOf("description", "words")))
        assertEquals("p" to "abc", ListWrite.slot(listOf("p", "ABC")))
    }

    // --- 10002 marker change ---

    private val relayList = listOf(
        listOf("r", "wss://a.example"),
        listOf("r", "wss://b.example", "write"),
        listOf("x-unknown", "keep"),
        listOf("r", "wss://c.example:4848", "read")
    )

    @Test fun markerChangeReplacesThatOneEntryInPlace() {
        val old = ev("o", 900, relayList)
        val c = ListWrite.Change(set = listOf("r", "wss://B.example/", "read"))
        val w = write(ListWrite.planMany(listOf(old), me, 10002, null, listOf(c), expected = true, now = 950))
        assertEquals(relayList[0], w.tags[0])
        assertEquals(listOf("r", "wss://B.example/", "read"), w.tags[1])
        assertEquals(relayList.drop(2), w.tags.drop(2))
        assertEquals(950L, w.createdAt)
        val slot = ListWrite.slot(c.set!!)!!
        assertNull(ListWrite.guardMany(ListWrite.version(old), ListWrite.Version(10002, w.createdAt, w.content, w.tags), ListWrite.touched(listOf(c)), 0, now, setOf(slot)))
    }

    @Test fun markerGuardRefusesAnotherEntryChanging() {
        val old = ListWrite.Version(10002, 900, "", relayList)
        val next = ListWrite.Version(10002, 901, "", listOf(
            listOf("r", "wss://a.example", "read"), // not asked
            listOf("r", "wss://b.example", "read"),
            listOf("x-unknown", "keep"),
            listOf("r", "wss://c.example:4848", "read")
        ))
        val slot = "r" to "wss://b.example"
        assertNotNull(ListWrite.guardMany(old, next, setOf(slot), 0, now, setOf(slot)))
    }

    @Test fun markerSetOnAMissingRelayAppendsIt() {
        val old = ev("o", 900, relayList)
        val c = ListWrite.Change(set = listOf("r", "wss://d.example", "write"))
        val w = write(ListWrite.planMany(listOf(old), me, 10002, null, listOf(c), expected = true, now = 950))
        assertEquals(relayList + listOf(listOf("r", "wss://d.example", "write")), w.tags)
    }

    @Test fun titleRenameStaysInPlace() {
        val tags = listOf(listOf("d", "friends"), listOf("title", "Friends"), listOf("p", "a"), listOf("p", "b"))
        val old = ev("o", 900, tags, kind = 30000)
        val c = ListWrite.Change(set = listOf("title", "Close friends"))
        val w = write(ListWrite.planMany(listOf(old), me, 30000, "friends", listOf(c), expected = true, now = 950))
        assertEquals(listOf(listOf("d", "friends"), listOf("title", "Close friends"), listOf("p", "a"), listOf("p", "b")), w.tags)
        val s = ListWrite.slot(c.set!!)!!
        assertNull(ListWrite.guardMany(ListWrite.version(old), ListWrite.Version(30000, w.createdAt, "", w.tags), setOf(s), 0, now, setOf(s)))
    }

    // --- reorder (10063) ---

    private val servers = listOf(
        listOf("server", "https://one.example"),
        listOf("x-unknown", "stays"),
        listOf("server", "https://two.example/"),
        listOf("server", "https://three.example")
    )

    @Test fun reorderMovesOnlyTheNamedEntriesAmongTheirOwnPlaces() {
        val old = ev("o", 900, servers, kind = 10063)
        val order = listOf(listOf("server", "https://three.example"), listOf("server", "https://ONE.example"), listOf("server", "https://two.example"))
        val w = write(ListWrite.planReorder(listOf(old), me, 10063, null, order, expected = true, now = 800))
        assertEquals(listOf(servers[3], servers[1], servers[0], servers[2]), w.tags)
        assertEquals(901L, w.createdAt)
        assertNull(ListWrite.guardReorder(ListWrite.version(old), ListWrite.Version(10063, w.createdAt, w.content, w.tags), now))
    }

    @Test fun reorderSkipsUnknownEntriesAndKeepsUnnamedOnesInPlace() {
        val tags = ListWrite.reorder(servers, listOf(listOf("server", "https://two.example"), listOf("server", "https://gone.example"), listOf("server", "https://one.example")))
        assertEquals(listOf(servers[2], servers[1], servers[0], servers[3]), tags)
    }

    @Test fun reorderInTheSameOrderIsNothing() {
        val old = ev("o", 900, servers, kind = 10063)
        assertEquals(ListWrite.Plan.Nothing, ListWrite.planReorder(listOf(old), me, 10063, null, servers.filter { it[0] == "server" }, expected = true, now = now))
    }

    @Test fun reorderRefusesAnEntryThatIsThereTwice() {
        val twice = servers + listOf(listOf("server", "https://one.example/"))
        assertNull(ListWrite.reorder(twice, listOf(listOf("server", "https://two.example"), listOf("server", "https://one.example"))))
        val old = ev("o", 900, twice, kind = 10063)
        assertTrue(ListWrite.planReorder(listOf(old), me, 10063, null, listOf(listOf("server", "https://one.example")), true, now) is ListWrite.Plan.Refuse)
    }

    @Test fun reorderGuardRefusesAnAddedOrDroppedTag() {
        val old = ListWrite.Version(10063, 900, "", servers)
        assertNotNull(ListWrite.guardReorder(old, ListWrite.Version(10063, 901, "", servers.reversed() + listOf(listOf("server", "https://new.example")))))
        assertNotNull(ListWrite.guardReorder(old, ListWrite.Version(10063, 901, "", servers.reversed().drop(1))))
        assertNull(ListWrite.guardReorder(old, ListWrite.Version(10063, 901, "", servers.reversed())))
    }

    @Test fun reorderWithNoListIsNothingOrRefusedWhenExpected() {
        assertEquals(ListWrite.Plan.Nothing, ListWrite.planReorder(emptyList(), me, 10063, null, servers, expected = false, now = now))
        assertTrue(ListWrite.planReorder(emptyList(), me, 10063, null, servers, expected = true, now = now) is ListWrite.Plan.Refuse)
    }

    // --- profile (kind 0) ---

    private val profileContent = """{"name":"alice","about":"hi","x_unknown":{"deep":[1,2]},"lud16":"a@b.example","bot":false}"""
    private val profileTags = listOf(listOf("i", "github:alice", "proof"), listOf("client", "other"))

    private fun prof(id: String = "p", at: Long = 900, content: String = profileContent) = ev(id, at, profileTags, content, kind = 0)

    private fun fields(vararg kv: Pair<String, String?>): Map<String, JsonElement?> = kv.associate { (k, v) -> k to v?.let { JsonPrimitive(it) } }

    @Test fun profileChangesOnlyTheEditedFieldKeepingUnknownFieldsTagsAndOrder() {
        val old = prof()
        val f = fields("about" to "hello")
        val w = write(ListWrite.planProfile(listOf(old), me, f, expected = true, now = 800))
        assertEquals(0, w.kind)
        assertEquals(profileTags, w.tags)
        assertEquals(901L, w.createdAt)
        val o = ListWrite.profileFields(old.content)!!
        val n = ListWrite.profileFields(w.content)!!
        assertEquals(o.keys.toList(), n.keys.toList())
        assertEquals(JsonPrimitive("hello"), n["about"])
        for (k in o.keys - "about") assertEquals(o[k], n[k])
        assertNull(ListWrite.guardProfile(ListWrite.version(old), ListWrite.Version(0, w.createdAt, w.content, w.tags), f, 800))
    }

    @Test fun profileNullTakesAFieldOutAndANewFieldGoesLast() {
        val old = prof()
        val f = fields("lud16" to null, "banner" to "https://x.example/b.png")
        val w = write(ListWrite.planProfile(listOf(old), me, f, expected = true, now = now))
        val n = ListWrite.profileFields(w.content)!!
        assertTrue("lud16" !in n)
        assertEquals("banner", n.keys.last())
        assertNull(ListWrite.guardProfile(ListWrite.version(old), ListWrite.Version(0, w.createdAt, w.content, w.tags), f, now))
    }

    @Test fun profileWithTheSameValuesIsNothing() {
        assertEquals(ListWrite.Plan.Nothing, ListWrite.planProfile(listOf(prof()), me, fields("name" to "alice"), expected = true, now = now))
        assertEquals(ListWrite.Plan.Nothing, ListWrite.planProfile(listOf(prof()), me, fields("website" to null), expected = true, now = now))
    }

    @Test fun profileStartsFromTheNewestOfSeveral() {
        val older = prof("a", 800, """{"name":"old"}""")
        val newer = prof("b", 950)
        val w = write(ListWrite.planProfile(listOf(newer, older), me, fields("about" to "x"), expected = true, now = now))
        assertEquals("alice", ListWrite.profileFields(w.content)!!["name"].let { (it as JsonPrimitive).content })
        assertEquals(1000L, w.createdAt)
    }

    @Test fun profileRefusesWhenExpectedButMissingOrUnreadable() {
        assertTrue(ListWrite.planProfile(emptyList(), me, fields("name" to "a"), expected = true, now = now) is ListWrite.Plan.Refuse)
        assertTrue(ListWrite.planProfile(listOf(prof(content = "not json")), me, fields("name" to "a"), expected = true, now = now) is ListWrite.Plan.Refuse)
        assertTrue(ListWrite.planProfile(listOf(prof(content = "[1,2]")), me, fields("name" to "a"), expected = true, now = now) is ListWrite.Plan.Refuse)
        assertTrue(ListWrite.planProfile(listOf(prof(at = now + 601)), me, fields("name" to "a"), expected = true, now = now) is ListWrite.Plan.Refuse)
    }

    @Test fun aFirstProfileIsOnlyTheGivenFields() {
        val f = fields("name" to "bob", "about" to null)
        val w = write(ListWrite.planProfile(emptyList(), me, f, expected = false, now = now))
        assertEquals("""{"name":"bob"}""", w.content)
        assertTrue(w.tags.isEmpty())
        assertNull(ListWrite.guardProfile(null, ListWrite.Version(0, w.createdAt, w.content, w.tags), f, now))
    }

    @Test fun profileGuardRefusesOtherFieldsTagsOrOrderChanging() {
        val old = ListWrite.version(prof())
        val f = fields("about" to "hello")
        // Another field changed.
        assertNotNull(ListWrite.guardProfile(old, ListWrite.Version(0, 901, """{"name":"bob","about":"hello","x_unknown":{"deep":[1,2]},"lud16":"a@b.example","bot":false}""", profileTags), f, now))
        // An unknown field dropped.
        assertNotNull(ListWrite.guardProfile(old, ListWrite.Version(0, 901, """{"name":"alice","about":"hello","lud16":"a@b.example","bot":false}""", profileTags), f, now))
        // Tags changed.
        assertNotNull(ListWrite.guardProfile(old, ListWrite.Version(0, 901, """{"name":"alice","about":"hello","x_unknown":{"deep":[1,2]},"lud16":"a@b.example","bot":false}""", emptyList()), f, now))
        // Kept fields moved.
        assertNotNull(ListWrite.guardProfile(old, ListWrite.Version(0, 901, """{"about":"hello","name":"alice","lud16":"a@b.example","x_unknown":{"deep":[1,2]},"bot":false}""", profileTags), f, now))
        // Not newer.
        assertNotNull(ListWrite.guardProfile(old, ListWrite.Version(0, 900, """{"name":"alice","about":"hello","x_unknown":{"deep":[1,2]},"lud16":"a@b.example","bot":false}""", profileTags), f, now))
        // The edited field not as asked.
        assertNotNull(ListWrite.guardProfile(old, ListWrite.Version(0, 901, """{"name":"alice","about":"other","x_unknown":{"deep":[1,2]},"lud16":"a@b.example","bot":false}""", profileTags), f, now))
        // All fine.
        assertNull(ListWrite.guardProfile(old, ListWrite.Version(0, 901, """{"name":"alice","about":"hello","x_unknown":{"deep":[1,2]},"lud16":"a@b.example","bot":false}""", profileTags), f, now))
    }

    // --- content only (30078) ---

    private val appTags = listOf(listOf("d", "nwc-wallet-backup"), listOf("client", "Other"), listOf("encryption", "nip44"))

    @Test fun contentWriteKeepsEveryTagAndIsNewer() {
        val old = ev("o", 990, appTags, "OLD", kind = 30078)
        val w = write(ListWrite.planContent(old, 30078, "nwc-wallet-backup", "NEW", listOf(listOf("client", "Pinket")), expected = true, now = 980))
        assertEquals(appTags, w.tags)
        assertEquals("NEW", w.content)
        assertEquals(991L, w.createdAt)
        assertNull(ListWrite.guardContent(ListWrite.version(old), ListWrite.Version(30078, w.createdAt, w.content, w.tags), 980))
    }

    @Test fun contentWriteNothingWhenSameOrNullAndFirstVersionHasDThenFirstTags() {
        val old = ev("o", 900, appTags, "SAME", kind = 30078)
        assertEquals(ListWrite.Plan.Nothing, ListWrite.planContent(old, 30078, "nwc-wallet-backup", "SAME", emptyList(), true, now))
        assertEquals(ListWrite.Plan.Nothing, ListWrite.planContent(old, 30078, "nwc-wallet-backup", null, emptyList(), true, now))
        val w = write(ListWrite.planContent(null, 30078, "nwc-wallet-backup", "C", listOf(listOf("d", "x"), listOf("client", "Pinket")), false, now))
        assertEquals(listOf(listOf("d", "nwc-wallet-backup"), listOf("client", "Pinket")), w.tags)
        assertTrue(ListWrite.planContent(null, 30078, "nwc-wallet-backup", "C", emptyList(), true, now) is ListWrite.Plan.Refuse)
        assertTrue(ListWrite.planContent(old, 30078, "other", "C", emptyList(), true, now) is ListWrite.Plan.Refuse)
    }

    @Test fun contentGuardRefusesTagChanges() {
        val old = ListWrite.Version(30078, 900, "A", appTags)
        assertNotNull(ListWrite.guardContent(old, ListWrite.Version(30078, 901, "B", appTags.dropLast(1))))
        assertNotNull(ListWrite.guardContent(old, ListWrite.Version(30078, 901, "A", appTags)))
        assertNotNull(ListWrite.guardContent(old, ListWrite.Version(30078, 901, "B", listOf(listOf("d", "other")) + appTags.drop(1))))
    }
}
