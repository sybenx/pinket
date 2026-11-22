package com.wisp.app.repo

import com.wisp.app.nostr.NostrEvent
import com.wisp.app.repo.ReplyGate.OwnVote
import com.wisp.app.repo.SpamVote.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpamVotesTest {

    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private val fp = PostFingerprint(setOf("scam.example"), "hash1")

    private fun snap(vararg v: SpamVote) = SpamVotesSnapshot(v.toList())

    @Test fun `no votes means no say`() {
        assertTrue(SpamVotesSnapshot.EMPTY.isEmpty)
        assertFalse(SpamVotesSnapshot.EMPTY.hasFingerprintVotes)
        assertNull(SpamVotesSnapshot.EMPTY.voteFor(alice, fp))
    }

    @Test fun `author vote applies to every post by them`() {
        val s = snap(SpamVote(Type.AUTHOR, alice, true), SpamVote(Type.AUTHOR, bob, false))
        assertEquals(OwnVote.SPAM, s.voteFor(alice, null))
        assertEquals(OwnVote.SPAM, s.voteFor(alice, PostFingerprint.EMPTY))
        assertEquals(OwnVote.NOT_SPAM, s.voteFor(bob, fp))
        assertNull(s.voteFor(carol, fp))
        assertEquals(OwnVote.SPAM, s.authorVote(alice))
        assertNull(s.authorVote(carol))
        assertFalse(s.hasFingerprintVotes)
    }

    @Test fun `a spam vote on one post catches the same post from someone else`() {
        val s = SpamVotesSnapshot(SpamVotesSnapshot.spamRows(alice, fp, "Alice", "Claim your prize", 1L))
        assertEquals(OwnVote.SPAM, s.voteFor(alice, null))
        // Same text shape, other author, other link.
        assertEquals(OwnVote.SPAM, s.voteFor(bob, PostFingerprint(setOf("else.example"), "hash1")))
        // Same link, other text.
        assertEquals(OwnVote.SPAM, s.voteFor(bob, PostFingerprint(setOf("scam.example"), "hash2")))
        // Nothing in common.
        assertNull(s.voteFor(bob, PostFingerprint(setOf("else.example"), "hash2")))
        // Fingerprint not made yet: no say until it is.
        assertNull(s.voteFor(bob, null))
        assertTrue(s.hasFingerprintVotes)
    }

    @Test fun `author vote beats fingerprint votes`() {
        val s = snap(
            SpamVote(Type.AUTHOR, alice, false),
            SpamVote(Type.TEXT, "hash1", true),
            SpamVote(Type.DOMAIN, "scam.example", true)
        )
        assertEquals(OwnVote.NOT_SPAM, s.voteFor(alice, fp))
        assertEquals(OwnVote.SPAM, s.voteFor(bob, fp))
    }

    @Test fun `among fingerprint votes not spam wins`() {
        val s = snap(SpamVote(Type.TEXT, "hash1", false), SpamVote(Type.DOMAIN, "scam.example", true))
        assertEquals(OwnVote.NOT_SPAM, s.voteFor(bob, fp))
        assertEquals(OwnVote.SPAM, s.voteFor(bob, PostFingerprint(setOf("scam.example"), null)))
    }

    @Test fun `spam rows skip what the post does not have`() {
        val rows = SpamVotesSnapshot.spamRows(alice, PostFingerprint.EMPTY, null, null, 5L)
        assertEquals(listOf(SpamVote(Type.AUTHOR, alice, true, null, null, 5L)), rows)
        val full = SpamVotesSnapshot.spamRows(alice, PostFingerprint(setOf("x.example", "y.example"), "h"), "A", "text", 5L)
        assertEquals(4, full.size)
        assertEquals(setOf(Type.AUTHOR, Type.TEXT, Type.DOMAIN), full.map { it.type }.toSet())
    }

    @Test fun `newest vote is listed first`() {
        val s = snap(SpamVote(Type.AUTHOR, alice, true, createdAt = 1), SpamVote(Type.AUTHOR, bob, true, createdAt = 9))
        assertEquals(listOf(bob, alice), s.all.map { it.key })
    }

    // ---- reports from follows ----

    private fun report(by: String, vararg tags: List<String>, kind: Int = 1984, id: String = "id-$by-${tags.size}") =
        NostrEvent(id = id, pubkey = by, created_at = 1L, kind = kind, tags = tags.toList(), content = "", sig = "")

    @Test fun `only spam reports count`() {
        assertEquals(setOf(bob), NetworkReportSignals.spamReportedPubkeys(report(alice, listOf("p", bob, "spam"))))
        assertEquals(
            setOf(bob),
            NetworkReportSignals.spamReportedPubkeys(report(alice, listOf("e", "e".repeat(64), "SPAM"), listOf("p", bob)))
        )
        assertTrue(NetworkReportSignals.spamReportedPubkeys(report(alice, listOf("p", bob, "nudity"))).isEmpty())
        assertTrue(NetworkReportSignals.spamReportedPubkeys(report(alice, listOf("p", bob))).isEmpty())
        assertTrue(NetworkReportSignals.spamReportedPubkeys(report(alice, listOf("p", bob, "spam"), kind = 1)).isEmpty())
        assertTrue(NetworkReportSignals.spamReportedPubkeys(report(alice, listOf("p", "nothex", "spam"))).isEmpty())
        // Reporting yourself does not count.
        assertTrue(NetworkReportSignals.spamReportedPubkeys(report(alice, listOf("p", alice, "spam"))).isEmpty())
    }

    @Test fun `report rows are one per reporter and only from follows`() {
        val rows = NetworkReportSignals.reportedByRows(
            listOf(
                report(alice, listOf("p", carol, "spam"), id = "1"),
                report(alice, listOf("p", carol, "spam"), id = "2"),
                report(bob, listOf("p", carol, "spam"), id = "3"),
                report("d".repeat(64), listOf("p", carol, "spam"), id = "4")
            ),
            allowedAuthors = setOf(alice, bob)
        )
        assertEquals(setOf(carol to alice, carol to bob), rows.toSet())
        assertEquals(2, rows.size)
    }

    @Test fun `own report tags`() {
        assertEquals(
            listOf(listOf("e", "x", "spam"), listOf("p", bob)),
            NetworkReportSignals.buildSpamReportTags("x", bob)
        )
        assertEquals(listOf(listOf("p", bob, "spam")), NetworkReportSignals.buildSpamReportTags(null, bob))
        // What we publish is what we count.
        for (id in listOf("e".repeat(64), null)) {
            val ev = NostrEvent("i", alice, 1L, 1984, NetworkReportSignals.buildSpamReportTags(id, bob), "", "")
            assertEquals(setOf(bob), NetworkReportSignals.spamReportedPubkeys(ev))
        }
    }
}
