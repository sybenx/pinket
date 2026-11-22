package com.wisp.app.repo

import android.app.Application
import com.wisp.app.nostr.CustomEmoji
import com.wisp.app.nostr.ListWrite
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.viewmodel.ListCrudManager
import com.wisp.app.viewmodel.ListPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the set repositories show: what was loaded, with ListWriter's pending changes laid over it (so buttons flip at
 * once) and without the sets hidden here. Plus ListCrudManager's pure helpers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SetOverlayTest {
    private val me = "a".repeat(64)
    private fun hex(n: Int) = n.toString(16).padStart(64, '0')

    private fun ev(kind: Int, tags: List<List<String>>, at: Long = 100, content: String = "", id: String = "f".repeat(64)) =
        NostrEvent(id, me, at, kind, tags, content, "")

    private fun pc(kind: Int, d: String?, entry: List<String>, wanted: Boolean, replace: Boolean = false, title: String? = null) =
        PendingChange(kind = kind, d = d, entry = entry, wanted = wanted, isPrivate = false, at = 1, title = title, replace = replace)

    // ---------------------------------------------------------------- SetOverlay

    @Test
    fun valuesCompareLikeTheWriterAndKeepOrder() {
        val shown = SetOverlay.values(listOf(hex(1), hex(2).uppercase()), "p", listOf(
            pc(30000, "x", listOf("p", hex(2)), wanted = false),
            pc(30000, "x", listOf("p", hex(3)), wanted = true),
            pc(30000, "x", listOf("p", hex(1).uppercase()), wanted = true)
        ))
        assertEquals(listOf(hex(1), hex(3)), shown.toList())
    }

    @Test
    fun emojisRemoveAddAndReplaceByShortcode() {
        val base = listOf(CustomEmoji("a", "u1"), CustomEmoji("b", "u2"))
        val shown = SetOverlay.emojis(base, listOf(
            pc(10030, null, listOf("emoji", "a"), wanted = false),
            pc(10030, null, listOf("emoji", "b", "u3"), wanted = true, replace = true),
            pc(10030, null, listOf("emoji", "c", "u4"), wanted = true)
        ))
        assertEquals(listOf(CustomEmoji("b", "u3"), CustomEmoji("c", "u4")), shown)
    }

    // ---------------------------------------------------------------- repositories

    @Test
    fun peopleSetShowsPendingMembersAndHidesHiddenSets() {
        val r = ListRepository(RuntimeEnvironment.getApplication(), "p1".repeat(32))
        r.setOwner(me)
        r.updateFromEvent(ev(30000, listOf(listOf("d", "friends"), listOf("title", "Friends"), listOf("p", hex(1)))))
        r.updateFromEvent(ev(30000, listOf(listOf("d", "work"), listOf("p", hex(9))), id = "e".repeat(64)))
        r.setOverlay(listOf(pc(30000, "friends", listOf("p", hex(2)), wanted = true), pc(30000, "friends", listOf("p", hex(1)), wanted = false)), emptySet())
        assertEquals(setOf(hex(2)), r.getList(me, "friends")!!.members)
        assertEquals(setOf(hex(1)), r.getLoadedList(me, "friends")!!.members)
        r.setOverlay(emptyList(), setOf("30000:work"))
        assertEquals(listOf("friends"), r.ownLists.value.map { it.dTag })
        assertEquals(setOf(hex(1)), r.ownLists.value.single().members)
    }

    @Test
    fun bookmarkSetShowsPendingNotesInTheListedIds() {
        val r = BookmarkSetRepository(RuntimeEnvironment.getApplication(), "b1".repeat(32))
        r.setOwner(me)
        r.updateFromEvent(ev(30003, listOf(listOf("d", "reads"), listOf("e", hex(1)))))
        r.setOverlay(listOf(pc(30003, "reads", listOf("e", hex(2), "wss://hint"), wanted = true)), emptySet())
        assertEquals(setOf(hex(1), hex(2)), r.allListedEventIds.value)
        r.setOverlay(emptyList(), setOf("30003:reads"))
        assertTrue(r.ownSets.value.isEmpty())
        assertTrue(r.allListedEventIds.value.isEmpty())
    }

    @Test
    fun aFirstHashtagFollowShowsAtOnceAndARenameToo() {
        val r = InterestRepository(RuntimeEnvironment.getApplication(), "i1".repeat(32))
        r.setOverlay(listOf(pc(30015, "interests", listOf("t", "nostr"), wanted = true, title = "Interests")), emptySet())
        assertEquals("Interests", r.getSet("interests")!!.name)
        assertTrue(r.isFollowing("Nostr"))
        r.updateFromEvent(ev(30015, listOf(listOf("d", "interests"), listOf("title", "Interests"), listOf("t", "nostr"))))
        r.setOverlay(listOf(pc(30015, "interests", listOf("title", "Topics"), wanted = true, replace = true)), emptySet())
        assertEquals(listOf("Topics"), r.sets.value.map { it.name })
        r.setOverlay(emptyList(), setOf("30015:interests"))
        assertTrue(r.sets.value.isEmpty())
        assertTrue(!r.isFollowing("nostr"))
        // A pending remove alone never makes a set appear.
        r.setOverlay(listOf(pc(30015, "other", listOf("t", "x"), wanted = false)), emptySet())
        assertNull(r.getSet("other"))
    }

    @Test
    fun emojiListAndSetsShowPendingChanges() {
        val r = CustomEmojiRepository(RuntimeEnvironment.getApplication(), me)
        r.updateFromEvent(ev(10030, listOf(listOf("emoji", "a", "u1"), listOf("a", "30030:$me:cats"))))
        r.updateFromEvent(ev(30030, listOf(listOf("d", "cats"), listOf("title", "Cats"), listOf("emoji", "c", "u5")), id = "e".repeat(64)))
        assertEquals(mapOf("a" to "u1", "c" to "u5"), r.resolvedEmojis.value)
        r.setOverlay(listOf(
            pc(10030, null, listOf("emoji", "a"), wanted = false),
            pc(30030, "cats", listOf("emoji", "d", "u6"), wanted = true)
        ), emptySet())
        assertEquals(emptyList<CustomEmoji>(), r.userEmojiList.value!!.emojis)
        assertEquals(listOf("30030:$me:cats"), r.userEmojiList.value!!.setReferences)
        assertEquals(mapOf("c" to "u5", "d" to "u6"), r.resolvedEmojis.value)
        r.setOverlay(emptyList(), setOf("30030:cats"))
        assertTrue(r.ownSets.value.isEmpty())
    }

    // ---------------------------------------------------------------- ListCrudManager helpers

    @Test
    fun placeOfFollowsTheRecordThenTheNewestVersion() {
        val p = setOf("p")
        assertEquals(ListPlace.PRIVATE, ListCrudManager.placeOf(true, true, null, false, p))
        assertEquals(ListPlace.PUBLIC, ListCrudManager.placeOf(true, false, null, true, p))
        // Public entries: public, whatever the content holds.
        assertEquals(ListPlace.PUBLIC, ListCrudManager.placeOf(false, false, ev(30000, listOf(listOf("d", "x"), listOf("p", hex(1))), content = "enc"), true, p))
        // A private part and no public entries: private.
        assertEquals(ListPlace.PRIVATE, ListCrudManager.placeOf(false, false, ev(30000, listOf(listOf("d", "x")), content = "enc"), false, p))
        // Empty with a public title: public (an empty set shows as private, but its title says otherwise).
        assertEquals(ListPlace.PUBLIC, ListCrudManager.placeOf(false, false, ev(30000, listOf(listOf("d", "x"), listOf("title", "X"))), true, p))
        // Empty, no title, shown as private (a private set emptied by the old code): private.
        assertEquals(ListPlace.PRIVATE, ListCrudManager.placeOf(false, false, ev(30000, listOf(listOf("d", "x"))), true, p))
        assertEquals(ListPlace.PUBLIC, ListCrudManager.placeOf(false, false, null, null, p))
    }

    @Test
    fun oldDeletionMarksHideOnlyWhatTheUserNoLongerSaw() {
        val other = "b".repeat(64)
        val hide = ListCrudManager.deletedSetsToHide(
            setOf("30015:$me:old", "30030:$me:cats", "30000:$me:gone", "30000:$me:back", "30003:$me:reads",
                "30015:$other:theirs", "31234:$me:draft", "bad"),
            me, shownPeople = setOf("back"), shownBookmarks = emptySet()
        ).toSet()
        assertEquals(setOf(30015 to "old", 30000 to "gone", 30003 to "reads"), hide)
    }

    @Test
    fun slugIsTheDThisAppGives() {
        assertEquals("read-later", ListCrudManager.slug(" Read Later "))
        assertEquals("a_b-c", ListCrudManager.slug("a_b-c"))
        assertTrue(ListWrite.key(listOf("t", "Bitcoin"))!!.second == "Bitcoin")
    }
}
