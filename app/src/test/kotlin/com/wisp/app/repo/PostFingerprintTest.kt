package com.wisp.app.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostFingerprintTest {

    private val k = "a".repeat(58)

    @Test fun `same template with other numbers, links and mentions gives the same hash`() {
        val a = PostFingerprint.of("Congrats nostr:npub1$k you won 500 sats! Claim at https://win-big.example/c/123 before 10:30")
        val b = PostFingerprint.of("congrats   nostr:npub1${"c".repeat(58)} you won 21000 sats!\nClaim at https://other-scam.example/x?y=9 before 9:05")
        assertNotNull(a.textHash)
        assertEquals(a.textHash, b.textHash)
        assertEquals(setOf("win-big.example"), a.domains)
        assertEquals(setOf("other-scam.example"), b.domains)
    }

    @Test fun `different wording gives a different hash`() {
        val a = PostFingerprint.of("You won a prize, claim it here today")
        val b = PostFingerprint.of("You won a prize, claim it there today")
        assertNotEquals(a.textHash, b.textHash)
    }

    @Test fun `normalizing folds links, mentions, numbers, case and whitespace`() {
        assertEquals(
            PostFingerprint.normalizeText("Hello @bob and #[0], see http://a.example/x  now 12,500.75 times"),
            PostFingerprint.normalizeText("hello @alice_2 and nostr:nprofile1$k, see https://b.example \n now 3 times")
        )
        // A link and a mention are not the same thing.
        assertNotEquals(
            PostFingerprint.normalizeText("look at https://a.example"),
            PostFingerprint.normalizeText("look at nostr:note1$k")
        )
        // An email address is not a mention of a person.
        assertTrue(PostFingerprint.normalizeText("mail me at joe@site.example").contains("joe"))
    }

    @Test fun `short posts have no text hash`() {
        assertNull(PostFingerprint.of("gm").textHash)
        assertNull(PostFingerprint.of("nice one!").textHash)
        assertNull(PostFingerprint.of("https://spam.example/abc").textHash)
        assertNull(PostFingerprint.of("123456789 123456789 123456789 12345").textHash)
        assertNull(PostFingerprint.of("Supercalifragilisticexpialidocious").textHash)
        assertNotNull(PostFingerprint.of("this is long enough to count").textHash)
    }

    @Test fun `a short post with an uncommon link still has its domain`() {
        val fp = PostFingerprint.of("wow https://Casino-Bonus.Example:8443/win?id=4")
        assertNull(fp.textHash)
        assertEquals(setOf("casino-bonus.example"), fp.domains)
    }

    @Test fun `common hosts and their subdomains are not fingerprint domains`() {
        val fp = PostFingerprint.of(
            "pic https://image.nostr.build/abc.jpg vid https://www.youtube.com/watch?v=1 " +
                "and https://m.youtube.com/x also https://notyoutube.com/x www.shady-shop.example/buy"
        )
        assertEquals(setOf("notyoutube.com", "shady-shop.example"), fp.domains)
    }

    @Test fun `host parsing`() {
        assertEquals("a.example", PostFingerprint.hostOf("https://www.A.example/path"))
        assertEquals("a.example", PostFingerprint.hostOf("https://user:pw@a.example:444/?q=1"))
        assertEquals("a.example", PostFingerprint.hostOf("http://a.example#frag"))
        assertNull(PostFingerprint.hostOf("https://localhost/x"))
        assertNull(PostFingerprint.hostOf("https:///nothing"))
    }

    @Test fun `empty content`() {
        assertEquals(PostFingerprint.EMPTY, PostFingerprint.of(""))
        assertEquals(PostFingerprint.EMPTY, PostFingerprint.of("   \n "))
        assertTrue(PostFingerprint.of("gm").isEmpty)
    }
}
