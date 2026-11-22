package com.wisp.app.repo

import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.toHex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnListStoreTest {
    private val me = Keys.generate().pubkey.toHex()
    private val other = Keys.generate().pubkey.toHex()
    private var now = 1_700_000_000L

    private fun store(
        storage: OwnListStorage = MemoryOwnListStorage(),
        legacy: Map<String, Long> = emptyMap(),
        pubkey: String = me
    ) = OwnListStore(pubkey, storage, legacy, clock = { now })

    private fun ev(
        kind: Int,
        createdAt: Long,
        id: String,
        tags: List<List<String>> = emptyList(),
        pubkey: String = me,
        content: String = ""
    ) = NostrEvent(id, pubkey, createdAt, kind, tags, content, "")

    private fun id(c: Char) = c.toString().repeat(64)

    @Test fun newerByCreatedAtWins() {
        val s = store()
        assertTrue(s.seen(ev(10000, now - 100, id('b'))))
        assertTrue(s.seen(ev(10000, now - 50, id('c'))))
        assertFalse(s.seen(ev(10000, now - 80, id('a'))))
        assertEquals(id('c'), s.latest(10000)?.id)
    }

    @Test fun sameSecondLowerIdWins() {
        val s = store()
        assertTrue(s.seen(ev(3, now, id('b'))))
        assertFalse(s.seen(ev(3, now, id('c'))))
        assertTrue(s.seen(ev(3, now, id('a'))))
        assertEquals(id('a'), s.latest(3)?.id)
        assertFalse("same event again is not newer", s.seen(ev(3, now, id('a'))))
    }

    @Test fun keysAreSeparatedByKindAndD() {
        val s = store()
        assertTrue(s.seen(ev(30000, now, id('a'), listOf(listOf("d", "friends")))))
        assertTrue(s.seen(ev(30000, now, id('b'), listOf(listOf("d", "work")))))
        assertTrue(s.seen(ev(10003, now, id('c'))))
        assertEquals(id('a'), s.latest(30000, "friends")?.id)
        assertEquals(id('b'), s.latest(30000, "work")?.id)
        assertEquals(id('c'), s.latest(10003)?.id)
        assertEquals("30000:friends", OwnListStore.key(30000, "friends"))
        assertEquals("3", OwnListStore.key(3, "ignored"))
    }

    @Test fun nonListKindsAreIgnored() {
        assertFalse(store().seen(ev(1, now, id('a'))))
    }

    @Test fun rawEventRoundTrips() {
        val s = store()
        val e = ev(10003, now, id('a'), listOf(listOf("e", id('f')), listOf("x", "unknown", "extra")), content = "abc?iv=def")
        assertTrue(s.seen(e))
        assertEquals(e, s.latest(10003))
    }

    @Test fun futureDatedRejected() {
        val s = store()
        assertFalse(s.seen(ev(10000, now + 601, id('a'))))
        assertTrue(s.seen(ev(10000, now + 600, id('b'))))
    }

    @Test fun wrongPubkeyRejected() {
        val s = store()
        assertFalse(s.seen(ev(3, now, id('a'), pubkey = other)))
        assertFalse(s.recordSigned(ev(3, now, id('a'), pubkey = other)))
        assertNull(s.latest(3))
        assertFalse(s.everSeen("3"))
    }

    @Test fun watermarkRejectsOlderAndCountsAsSeen() {
        val s = store(legacy = mapOf("10000" to now - 100))
        assertEquals(now - 100, s.watermark("10000"))
        assertTrue(s.everSeen("10000"))
        assertFalse(s.everSeen("10003"))
        assertFalse(s.seen(ev(10000, now - 101, id('a'))))
        assertTrue(s.seen(ev(10000, now - 100, id('b'))))
    }

    @Test fun watermarkSeededOnlyOncePerKey() {
        val storage = MemoryOwnListStorage()
        store(storage, legacy = mapOf("10000" to now - 100, "3" to 0L))
        val again = store(storage, legacy = mapOf("10000" to now - 10, "3" to now - 5, "10003" to now - 7))
        assertEquals(now - 100, again.watermark("10000"))
        assertEquals(0L, again.watermark("3"))
        assertEquals(now - 7, again.watermark("10003"))
    }

    @Test fun futureLegacyWatermarkIsCappedAtNow() {
        val s = store(legacy = mapOf("10003" to now + 100_000))
        assertEquals(now, s.watermark("10003"))
    }

    @Test fun everSeenSurvivesClear() {
        val s = store()
        s.seen(ev(10003, now, id('a')))
        s.markMadeHere("30000:friends", isPrivate = true)
        s.addPending(PendingChange(10003, null, listOf("e", id('e')), wanted = true))
        s.clear()
        assertNull(s.latest(10003))
        assertTrue(s.everSeen(10003))
        assertTrue(s.madeHere("30000:friends"))
        assertTrue(s.madeHerePrivate("30000:friends"))
        assertTrue(s.allPending().isEmpty())
    }

    @Test fun pendingPersistsAndReplacesSameEntry() {
        val storage = MemoryOwnListStorage()
        val s = store(storage)
        val follow = PendingChange(3, null, listOf("p", id('1')), wanted = true, at = 1)
        val mute = PendingChange(10000, null, listOf("word", "Spam"), wanted = true, isPrivate = true, at = 2)
        s.addPending(follow)
        s.addPending(mute)
        val reopened = store(storage)
        assertEquals(listOf(follow), reopened.pending(3))
        assertEquals(listOf(mute), reopened.pending("10000"))
        assertEquals(setOf(follow, mute), reopened.allPending().toSet())

        val unmute = PendingChange(10000, null, listOf("word", "spam"), wanted = false, isPrivate = true, at = 3)
        reopened.addPending(unmute)
        assertEquals(listOf(unmute), reopened.pending(10000))
        assertFalse("an older change does not remove the newer one", reopened.removePending(mute))
        assertTrue(reopened.removePending(unmute))
        assertTrue(reopened.pending(10000).isEmpty())
    }

    @Test fun pendingDoesNotCrossAccounts() {
        val a = store(MemoryOwnListStorage())
        val b = store(MemoryOwnListStorage(), pubkey = other)
        a.addPending(PendingChange(3, null, listOf("p", id('1')), wanted = true))
        assertTrue(b.allPending().isEmpty())
    }

    @Test fun importedKeyIsNotNew() {
        val storage = MemoryOwnListStorage()
        val imported = store(storage)
        assertFalse(imported.isNewAccount())
        val generated = store(MemoryOwnListStorage(), pubkey = other)
        generated.markNewAccount()
        assertTrue(generated.isNewAccount())
        assertFalse(store(storage).isNewAccount())
    }

    @Test fun aVersionARelayEchoedBeforeItWasRecordedCountsAsRecorded() {
        val s = store()
        val signed = ev(0, now, id('a'))
        assertTrue("the relay echo gets there first", s.seen(signed))
        assertTrue(s.recordSigned(signed))
        assertNull("a relay has it: nothing to re-send", s.unconfirmed("0"))
    }

    @Test fun signedEventIsUnconfirmedUntilARelayHasIt() {
        val s = store()
        val signed = ev(10003, now, id('a'))
        assertTrue(s.recordSigned(signed))
        assertEquals(signed, s.unconfirmed("10003"))
        assertEquals(setOf("10003"), s.unconfirmedKeys())
        s.markConfirmed("10003", id('b'))
        assertEquals(signed, s.unconfirmed("10003"))
        assertFalse(s.seen(signed))
        assertNull("relay echo confirms it", s.unconfirmed("10003"))

        val next = ev(10003, now + 1, id('c'))
        assertTrue(s.recordSigned(next))
        s.markConfirmed("10003", next.id)
        assertNull(s.unconfirmed("10003"))
    }

    @Test fun mutexIsPerKeyAndShared() {
        val storage = MemoryOwnListStorage()
        val a = store(storage)
        val b = store(storage)
        assertSame(a.mutex("3"), b.mutex(3))
        assertNotEquals(a.mutex("3"), a.mutex("10000"))
        assertNotEquals(a.mutex("3"), store(pubkey = other).mutex("3"))
    }

    @Test fun localSignerNip04RoundTrip() = runBlocking {
        val k = Keys.generate()
        val signer = LocalSigner(k.privkey, k.pubkey)
        val text = """[["p","${id('1')}"],["word","x"]]"""
        val enc = signer.nip04Encrypt(text, signer.pubkeyHex)
        assertTrue(enc.contains("?iv="))
        assertEquals(text, signer.nip04Decrypt(enc, signer.pubkeyHex))
    }

    @Test fun aNewerVersionDropsTheUnconfirmedOne() {
        val s = store()
        val e1 = ev(10003, now - 10, id('b'))
        assertTrue(s.recordSigned(e1))
        // An older copy arriving changes nothing.
        assertFalse(s.seen(ev(10003, now - 20, id('a'))))
        assertEquals(e1, s.unconfirmed("10003"))
        // Another app's newer version replaces it: never re-sent again.
        assertTrue(s.seen(ev(10003, now - 5, id('c'))))
        assertNull(s.unconfirmed("10003"))
        assertTrue(s.unconfirmedKeys().isEmpty())
        // A version signed here is still unconfirmed until a relay takes it.
        val e3 = ev(10003, now, id('d'))
        assertTrue(s.recordSigned(e3))
        assertEquals(e3, s.unconfirmed("10003"))
    }

    @Test fun onlySignUpItselfIsTheOnboardingWindow() {
        val storage = MemoryOwnListStorage()
        val s = store(storage)
        assertFalse(s.isOnboardingWindow())
        s.markNewAccount()
        assertTrue(s.isNewAccount())
        assertTrue(s.isOnboardingWindow())
        now += OwnListStore.NEW_ACCOUNT_WINDOW
        assertTrue(s.isOnboardingWindow())
        now += 1
        assertFalse(s.isOnboardingWindow())
        // Still a key made here.
        assertTrue(s.isNewAccount())
        // The old mark without a time is outside the window.
        storage.put("new_account", "1")
        assertTrue(s.isNewAccount())
        assertFalse(s.isOnboardingWindow())
    }

    @Test fun watermarkOnlyComesDown() {
        val s = store(legacy = mapOf("3" to now - 100))
        assertEquals(now - 100, s.watermark("3"))
        s.lowerWatermark("3", now - 50)
        assertEquals(now - 100, s.watermark("3"))
        s.lowerWatermark("3", now - 500)
        assertEquals(now - 500, s.watermark("3"))
        assertTrue(s.seen(ev(3, now - 400, id('a'))))
    }

    @Test fun storedKindsAreTheListsAndTheRelayLists() {
        // Phase 1 kinds, plus the phase 2 kinds that move to ListWriter.
        assertTrue(OwnListStore.STORED_KINDS.containsAll(setOf(3, 10000, 10001, 10003, 10002, 10050)))
        assertEquals(
            setOf(0, 3, 10000, 10001, 10002, 10003, 10006, 10007, 10009, 10012, 10030, 10050, 10063, 30000, 30002, 30003, 30015, 30030, 30078),
            OwnListStore.STORED_KINDS
        )
    }
}
