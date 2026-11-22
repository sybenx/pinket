package com.wisp.app.repo

import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 2: hidden sets, the kinds kept, pending changes with a replace mode. */
class OwnListStorePhase2Test {
    private val me = Keys.generate().pubkey.toHex()
    private val other = Keys.generate().pubkey.toHex()
    private var now = 1_700_000_000L

    private fun store(storage: OwnListStorage = MemoryOwnListStorage(), pubkey: String = me) =
        OwnListStore(pubkey, storage, clock = { now })

    private fun ev(kind: Int, tags: List<List<String>> = emptyList()) =
        NostrEvent("a".repeat(64), me, now, kind, tags, "", "")

    @Test fun hiddenSetsArePerAccountKeyedByKindAndDAndPersist() {
        val storage = MemoryOwnListStorage()
        val s = store(storage)
        assertFalse(s.isHidden(30000, "friends"))
        s.hideSet(30000, "friends")
        s.hideSet(30015, "interests")
        assertTrue(s.isHidden(30000, "friends"))
        assertTrue(s.isHidden("30000:friends"))
        assertFalse("same d, other kind", s.isHidden(30003, "friends"))
        assertEquals(setOf("30000:friends", "30015:interests"), s.hiddenSets())
        // Another instance over the same storage (an app restart) still has them.
        assertEquals(setOf("30000:friends", "30015:interests"), store(storage).hiddenSets())
        // Another account's store does not.
        assertTrue(store(MemoryOwnListStorage(), other).hiddenSets().isEmpty())
    }

    @Test fun hiddenSetsSurviveClearAndUnhideShowsAgain() {
        val s = store()
        s.hideSet(30000, "friends")
        s.clear()
        assertTrue(s.isHidden(30000, "friends"))
        s.unhideSet(30000, "friends")
        assertFalse(s.isHidden(30000, "friends"))
        assertTrue(s.hiddenSets().isEmpty())
    }

    @Test fun keepsEveryListKindAndOnlyThisAppsAppData() {
        for (k in listOf(0, 3, 10000, 10001, 10002, 10003, 10006, 10007, 10009, 10012, 10030, 10050, 10063)) {
            assertTrue("kind $k", OwnListStore.keeps(ev(k)))
        }
        for (k in listOf(30000, 30002, 30003, 30015, 30030)) {
            assertTrue("kind $k", OwnListStore.keeps(ev(k, listOf(listOf("d", "x")))))
        }
        assertTrue(OwnListStore.keeps(ev(30078, listOf(listOf("d", "nwc-wallet-backup")))))
        assertFalse(OwnListStore.keeps(ev(30078, listOf(listOf("d", "some-other-app")))))
        assertFalse(OwnListStore.keeps(ev(1)))
        assertFalse(OwnListStore.keeps(ev(10010)))
    }

    @Test fun storesAProfileAndASetByD() {
        val s = store()
        assertTrue(s.seen(ev(0)))
        assertTrue(s.everSeen(0))
        assertTrue(s.seen(NostrEvent("b".repeat(64), me, now, 30003, listOf(listOf("d", "reads")), "", "")))
        assertEquals("b".repeat(64), s.latest(30003, "reads")?.id)
    }

    @Test fun pendingReplaceChangesReplaceTheEarlierChangeForTheSameEntryAndOldJsonStillReads() {
        val storage = MemoryOwnListStorage()
        val s = OwnListStore(me, storage, clock = { now }) { tag ->
            if (tag.size < 2) null else tag[0] + "\u0000" + tag[1].lowercase().trimEnd('/')
        }
        s.addPending(PendingChange(10002, null, listOf("r", "wss://a.example"), wanted = true))
        s.addPending(PendingChange(10002, null, listOf("r", "wss://A.example/", "read"), wanted = true, replace = true))
        val p = s.pending(10002)
        assertEquals(1, p.size)
        assertTrue(p[0].replace)
        assertEquals(listOf("r", "wss://A.example/", "read"), p[0].entry)
        // A change kept by the phase 1 build (no replace/explicit fields) still reads.
        storage.put("pending:10000", """[{"kind":10000,"d":null,"entry":["p","abc"],"wanted":true,"isPrivate":true,"at":5}]""")
        val old = s.pending(10000).single()
        assertFalse(old.replace)
        assertFalse(old.explicit)
        assertTrue(old.isPrivate)
    }
}
