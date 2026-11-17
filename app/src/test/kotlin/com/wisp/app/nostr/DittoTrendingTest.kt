package com.wisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DittoTrendingTest {

    private val now = 1_800_000_000L
    private val day = 86_400L

    private fun event(
        pubkey: String = "a".repeat(64),
        kind: Int = 1,
        createdAt: Long = now,
        tags: List<List<String>> = emptyList(),
        id: String = "0".repeat(64)
    ) = NostrEvent(id = id, pubkey = pubkey, created_at = createdAt, kind = kind, tags = tags, content = "", sig = "")

    private fun label(value: String, vararg items: List<String>, pubkey: String = DittoTrending.DITTO_PUB_TRENDS_PUBKEY, createdAt: Long = now) =
        event(
            pubkey = pubkey,
            kind = 1985,
            createdAt = createdAt,
            tags = listOf(listOf("L", "pub.ditto.trends"), listOf("l", value, "pub.ditto.trends")) + items
        )

    // -- Notes filters --

    @Test
    fun `hot uses sort hot with no since`() {
        val json = DittoTrending.notesFilter(TrendingWindow.HOT, now, limit = 50).toJsonObject().toString()
        assertEquals("""{"kinds":[1],"limit":50,"search":"sort:hot"}""", json)
    }

    @Test
    fun `top windows use sort top with since per window`() {
        val expected = mapOf(
            TrendingWindow.WEEK to now - 7 * day,
            TrendingWindow.MONTH to now - 30 * day,
            TrendingWindow.YEAR to now - 365 * day
        )
        for ((window, since) in expected) {
            val filter = DittoTrending.notesFilter(window, now)
            assertEquals("sort:top", filter.search)
            assertEquals(since, filter.since)
            assertEquals(listOf(1), filter.kinds)
            assertEquals(100, filter.limit)
        }
    }

    @Test
    fun `top all time has no since`() {
        val filter = DittoTrending.notesFilter(TrendingWindow.ALL, now)
        assertEquals("sort:top", filter.search)
        assertNull(filter.since)
    }

    @Test
    fun `saved window falls back to hot when missing or removed`() {
        assertEquals(TrendingWindow.HOT, TrendingWindow.fromSaved(null))
        assertEquals(TrendingWindow.HOT, TrendingWindow.fromSaved("TODAY"))
        assertEquals(TrendingWindow.HOT, TrendingWindow.fromSaved("garbage"))
        assertEquals(TrendingWindow.MONTH, TrendingWindow.fromSaved("MONTH"))
    }

    // -- Label filters --

    @Test
    fun `label filter pins the signer on relay ditto pub`() {
        val json = DittoTrending.labelFilter("wss://relay.ditto.pub/", "#t").toJsonObject().toString()
        assertEquals(
            """{"kinds":[1985],"authors":["${DittoTrending.DITTO_PUB_TRENDS_PUBKEY}"],"#L":["pub.ditto.trends"],"#l":["#t"],"limit":1}""",
            json
        )
    }

    @Test
    fun `label filter on another relay has no author`() {
        val filter = DittoTrending.labelFilter("wss://syb.lol", "#e")
        assertNull(filter.authors)
        assertEquals(listOf("#e"), filter.lTags)
        assertEquals(listOf("pub.ditto.trends"), filter.bigLTags)
    }

    // -- Parsing labels --

    @Test
    fun `parses hashtags in order without duplicates`() {
        val ev = label(
            "#t",
            listOf("t", "bitcoin", "", "78", "268"),
            listOf("t", "Nostr", "", "49", "119"),
            listOf("t", "#wordle"),
            listOf("t", "bitcoin"),
            listOf("t", ""),
            listOf("e", "f".repeat(64))
        )
        assertEquals(listOf("bitcoin", "nostr", "wordle"), DittoTrending.parseHashtags(ev))
    }

    @Test
    fun `parses note ids in order and skips malformed ones`() {
        val a = "0000484d69dee8cae6e3d68732ff6823d94d382634b4b4b8cfc928f71f217b5d"
        val b = "04890c496d1751e554a814ffd4567923ea7fd690b31f93721be2256ea800611e"
        val ev = label(
            "#e",
            listOf("e", a, "wss://relay.dreamith.to/", "80", "160"),
            listOf("e", b, "wss://relay.dreamith.to/", "65", "119"),
            listOf("e", "short"),
            listOf("e", a),
            listOf("t", "bitcoin")
        )
        assertEquals(listOf(a, b), DittoTrending.parseNoteIds(ev))
    }

    @Test
    fun `newest label wins and other signers are ignored on relay ditto pub`() {
        val old = label("#t", listOf("t", "old"), createdAt = now - 600)
        val fresh = label("#t", listOf("t", "fresh"), createdAt = now)
        val spoof = label("#t", listOf("t", "spam"), pubkey = "b".repeat(64), createdAt = now + 60)
        val wrongValue = label("#e", createdAt = now + 120)
        val picked = DittoTrending.newestLabel(listOf(old, spoof, fresh, wrongValue), "#t", "wss://relay.ditto.pub")
        assertEquals(fresh, picked)
    }

    @Test
    fun `on another relay the newest label from any signer wins`() {
        val a = label("#t", listOf("t", "a"), pubkey = "7013db5b".padEnd(64, '0'), createdAt = now)
        val b = label("#t", listOf("t", "b"), pubkey = "c".repeat(64), createdAt = now - 10)
        assertEquals(a, DittoTrending.newestLabel(listOf(b, a), "#t", "wss://syb.lol"))
        assertNull(DittoTrending.newestLabel(emptyList(), "#t", "wss://syb.lol"))
    }

    // -- Trending users --

    @Test
    fun `distinct authors keep rank order`() {
        val notes = listOf("p1", "p2", "p1", "p3", "p2", "p4").map { event(pubkey = it) }
        assertEquals(listOf("p1", "p2", "p3", "p4"), DittoTrending.distinctAuthors(notes))
        assertEquals(emptyList<String>(), DittoTrending.distinctAuthors(emptyList()))
    }
}
