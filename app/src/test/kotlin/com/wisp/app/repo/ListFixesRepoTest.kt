package com.wisp.app.repo

import android.app.Application
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.viewmodel.ListWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Review fixes on the storage side: a set hidden for a deletion request comes back in a later session when a newer
 * version arrives, the flags a kept change carries survive a restart (a creating marker is never stored), sets being
 * created are shown at once, and the favourites the app shows count as an existing list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ListFixesRepoTest {
    private val me = "ab".repeat(32)
    private val now = 2_000_000L
    private val app get() = RuntimeEnvironment.getApplication()

    private fun set(kind: Int, d: String, at: Long, id: String = "c".repeat(64), tags: List<List<String>> = emptyList()) =
        NostrEvent(id, me, at, kind, listOf(listOf("d", d)) + tags, "", "")

    @Test
    fun aDeletionHideIsLiftedByANewerVersionInALaterSession() {
        val storage = MemoryOwnListStorage()
        val s1 = OwnListStore(me, storage, clock = { now })
        assertTrue(s1.hideForDeletion(30000, "friends", at = 500))
        assertTrue(s1.isHidden(30000, "friends"))
        assertEquals(500L, s1.deletionHideAt("30000:friends"))
        // An older request changes nothing.
        assertFalse(s1.hideForDeletion(30000, "friends", at = 400))
        // A later session: a fresh store on the same storage (nothing in memory).
        val s2 = OwnListStore(me, storage, clock = { now })
        assertFalse(s2.showAgainIfNewer(set(30000, "friends", at = 450)))
        assertTrue(s2.isHidden(30000, "friends"))
        assertTrue(s2.showAgainIfNewer(set(30000, "friends", at = 600)))
        assertFalse(s2.isHidden(30000, "friends"))
        assertNull(s2.deletionHideAt("30000:friends"))
    }

    @Test
    fun aHideTheUserMadeIsNeverLiftedOrTakenOverByADeletion() {
        val s = OwnListStore(me, MemoryOwnListStorage(), clock = { now })
        s.hideSet(30003, "reads")
        assertFalse(s.hideForDeletion(30003, "reads", at = 500))
        assertNull(s.deletionHideAt("30003:reads"))
        assertFalse(s.showAgainIfNewer(set(30003, "reads", at = 900)))
        assertTrue(s.isHidden(30003, "reads"))
        // The user hides a set hidden for a deletion: it is the user's hide now.
        assertTrue(s.hideForDeletion(30015, "tech", at = 500))
        s.hideSet(30015, "tech")
        assertFalse(s.showAgainIfNewer(set(30015, "tech", at = 900)))
        assertTrue(s.isHidden(30015, "tech"))
    }

    @Test
    fun aDeletionOlderThanTheNewestVersionHidesNothing() {
        val s = OwnListStore(me, MemoryOwnListStorage(), clock = { now })
        assertTrue(s.seen(set(30000, "friends", at = 800)))
        assertFalse(s.hideForDeletion(30000, "friends", at = 700))
        assertFalse(s.isHidden(30000, "friends"))
        assertTrue(s.hideForDeletion(30000, "friends", at = 900))
        // Another key's version never lifts it.
        assertFalse(s.showAgainIfNewer(NostrEvent("d".repeat(64), "ef".repeat(32), 1000, 30000, listOf(listOf("d", "friends")), "", "")))
        assertTrue(s.isHidden(30000, "friends"))
    }

    @Test
    fun aKeptChangeKeepsItsFlagsAcrossARestartAndCreatingMarkersAreNeverStored() {
        val storage = MemoryOwnListStorage()
        val s = OwnListStore(me, storage, clock = { now }, entryId = { ListWriter.entryId(it) })
        s.addPending(PendingChange(30000, "close", listOf("p", "1".repeat(64)), wanted = true, explicit = true, expected = true, asSet = true))
        s.addPending(PendingChange(30000, "close", listOf("title", "Close"), wanted = true, replace = true, creating = true))
        assertFalse(storage.get("pending:30000:close")!!.contains("creating"))
        val again = OwnListStore(me, storage, clock = { now }, entryId = { ListWriter.entryId(it) })
        val p = again.pending("30000:close")
        assertEquals(2, p.size)
        assertTrue(p[0].explicit && p[0].expected && p[0].asSet)
        assertFalse(p[1].creating)
    }

    @Test
    fun aSetBeingCreatedIsShownAsAnEmptySetUntilItLoads() {
        val marker = PendingChange(30000, "family", listOf("title", "Family"), wanted = true, replace = true, title = "Family", creating = true)
        val people = ListRepository(app, me)
        people.setOwner(me)
        people.setOverlay(listOf(marker), emptySet())
        assertEquals(listOf("family" to "Family"), people.ownLists.value.map { it.dTag to it.name })
        people.updateFromEvent(set(30000, "family", at = 100, tags = listOf(listOf("title", "Family"), listOf("p", "1".repeat(64)))))
        assertEquals(1, people.ownLists.value.size)
        assertEquals(1, people.ownLists.value.single().members.size)
        people.setOverlay(emptyList(), emptySet())
        assertEquals(1, people.ownLists.value.size)

        val topics = InterestRepository(app, me)
        topics.setOverlay(listOf(marker.copy(kind = 30015, d = "tech", title = "Tech")), emptySet())
        assertEquals(listOf("tech" to "Tech"), topics.sets.value.map { it.dTag to it.name })
        topics.setOverlay(emptyList(), emptySet())
        assertTrue(topics.sets.value.isEmpty())
        // Not a change: the markers never reach the pending entries a set shows.
        assertTrue(SetOverlay.byD(listOf(marker), 30000).isEmpty())
    }

    @Test
    fun favouritesTheAppShowsCountAsAnExistingList() {
        val pk = "fa".repeat(32)
        val r = RelaySetRepository(app, pk)
        assertFalse(r.hasSavedFavorites())
        r.updateFavoriteRelaysFromEvent(NostrEvent("e".repeat(64), pk, 100, 10012, listOf(listOf("relay", "wss://f.example")), "", ""))
        assertTrue(r.hasSavedFavorites())
        // Loaded from this device's saved copy after a restart, before any version arrives.
        assertTrue(RelaySetRepository(app, pk).hasSavedFavorites())
    }

    @Test fun aSetBroughtBackIsNotHiddenAgainByItsOldDeletionRequest() {
        val s = OwnListStore(me, MemoryOwnListStorage(), clock = { now })
        assertTrue(s.hideForDeletion(30000, "friends", at = 500))
        s.unhideSet(30000, "friends")
        assertFalse("the same old request arrives again at the next start", s.hideForDeletion(30000, "friends", at = 500))
        assertFalse(s.isHidden(30000, "friends"))
        assertTrue("a request made after it was brought back still hides it", s.hideForDeletion(30000, "friends", at = now + 10))
    }
}
