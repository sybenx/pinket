package com.wisp.app.repo

import com.wisp.app.nostr.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMuteSignalsTest {

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private val follow1 = "1".repeat(64)
    private val follow2 = "2".repeat(64)
    private val stranger = "9".repeat(64)

    private fun muteList(
        author: String, createdAt: Long, tags: List<List<String>>,
        content: String = "", id: String = "e".repeat(63) + createdAt.toString().last(),
        kind: Int = 10000
    ) = NostrEvent(id, author, createdAt, kind, tags, content, "")

    @Test fun `only p tags with 64 hex values count`() {
        val ev = muteList(follow1, 1, listOf(
            listOf("p", a),
            listOf("p", b.uppercase()),
            listOf("p", "abc"),
            listOf("p", "z".repeat(64)),
            listOf("p", a + "0"),
            listOf("p"),
            listOf("t", "nostr"),
            listOf("word", "spam"),
            listOf("e", c)
        ))
        assertEquals(setOf(a, b), NetworkMuteSignals.publicMutedPubkeys(ev))
    }

    @Test fun `encrypted content is ignored`() {
        val ev = muteList(follow1, 1, listOf(listOf("p", a)),
            content = "AhLs0mE+ciphertext?iv=xyz" + c)
        assertEquals(setOf(a), NetworkMuteSignals.publicMutedPubkeys(ev))
        val privateOnly = muteList(follow2, 1, emptyList(), content = "[[\"p\",\"$b\"]]")
        assertTrue(NetworkMuteSignals.publicMutedPubkeys(privateOnly).isEmpty())
    }

    @Test fun `non mute kinds give nothing`() {
        val ev = muteList(follow1, 1, listOf(listOf("p", a)), kind = 3)
        assertTrue(NetworkMuteSignals.publicMutedPubkeys(ev).isEmpty())
    }

    @Test fun `newest event per author wins`() {
        val old = muteList(follow1, 100, listOf(listOf("p", a)), id = "0".repeat(64))
        val new = muteList(follow1, 200, listOf(listOf("p", b)), id = "f".repeat(64))
        for (order in listOf(listOf(old, new), listOf(new, old))) {
            val newest = NetworkMuteSignals.newestPerAuthor(order, setOf(follow1))
            assertEquals(new, newest[follow1])
            assertEquals(listOf(b to follow1), NetworkMuteSignals.mutedByRows(newest))
        }
    }

    @Test fun `same timestamp keeps the lowest id`() {
        val low = muteList(follow1, 100, listOf(listOf("p", a)), id = "0".repeat(64))
        val high = muteList(follow1, 100, listOf(listOf("p", b)), id = "f".repeat(64))
        assertEquals(low, NetworkMuteSignals.newestPerAuthor(listOf(high, low), setOf(follow1))[follow1])
        assertEquals(low, NetworkMuteSignals.newestPerAuthor(listOf(low, high), setOf(follow1))[follow1])
    }

    @Test fun `only the user's follows count as muters`() {
        val ev1 = muteList(follow1, 1, listOf(listOf("p", a)))
        val ev2 = muteList(follow2, 1, listOf(listOf("p", a), listOf("p", follow2)))
        val evX = muteList(stranger, 1, listOf(listOf("p", a)))
        val newest = NetworkMuteSignals.newestPerAuthor(listOf(ev1, ev2, evX), setOf(follow1, follow2))
        val rows = NetworkMuteSignals.mutedByRows(newest).toSet()
        // Self-mutes are dropped, strangers are dropped.
        assertEquals(setOf(a to follow1, a to follow2), rows)
    }

    @Test fun `count table lookup`() {
        val t = PubkeyCountTable.from(mapOf(a to 3, b to 7))
        assertEquals(3, t.get(a))
        assertEquals(7, t.get(b))
        assertEquals(0, t.get(c))
        assertEquals(0, t.get("short"))
        assertEquals(0, PubkeyCountTable.EMPTY.get(a))
        val hi = "f".repeat(64)
        assertEquals(5, PubkeyCountTable.from(mapOf(hi to 5, a to 1)).get(hi))
    }
}
