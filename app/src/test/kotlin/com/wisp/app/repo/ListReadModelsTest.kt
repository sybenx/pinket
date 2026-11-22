package com.wisp.app.repo

import android.app.Application
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.viewmodel.ListWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Mutes, follows and pins as read models: they load from the version the own-list store took, ListWriter's pending
 * changes are laid over them, and (mutes) local mutes hide at once whatever the write does. A real LocalSigner on a
 * throwaway key generated here encrypts the private parts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ListReadModelsTest {
    private val kp = Keys.generate()
    private val signer = LocalSigner(kp.privkey, kp.pubkey)
    private val me = signer.pubkeyHex
    private val app get() = RuntimeEnvironment.getApplication()

    private val pkA = "1".repeat(64)
    private val pkB = "2".repeat(64)
    private val pkC = "c".repeat(64)
    private val pkD = "d".repeat(64)

    private fun ev(kind: Int, createdAt: Long, tags: List<List<String>>, content: String = "", id: String = "f".repeat(64)) =
        NostrEvent(id, me, createdAt, kind, tags, content, "")

    private fun priv(vararg tags: List<String>): String =
        runBlocking { signer.nip44Encrypt(ListWriter.privateJson(tags.toList()), me) }

    // ------------------------------------------------------------------ mutes

    @Test
    fun muteListMergesPublicAndPrivateAndIgnoresOtherTags() = runBlocking {
        val r = MuteRepository(app, "m1".padEnd(64, '0'))
        val content = priv(listOf("p", pkB), listOf("word", "FooBar"), listOf("t", "spam"), listOf("e", pkD))
        r.loadFromEvent(ev(10000, 100, listOf(listOf("p", pkA), listOf("word", "cats"), listOf("t", "nsfw")), content), signer)
        assertEquals(setOf(pkA, pkB), r.blockedPubkeys.value)
        assertEquals(setOf("cats", "FooBar"), r.mutedWords.value)
        assertTrue(r.isBlocked(pkA) && r.isBlocked(pkB))
        assertTrue("words match any case", r.containsMutedWord("I like foobar a lot"))
        assertTrue(r.containsMutedWord("CATS"))
        assertFalse(r.containsMutedWord("dogs"))
    }

    @Test
    fun unreadablePrivatePartKeepsTheLastReadOne() = runBlocking {
        val r = MuteRepository(app, "m2".padEnd(64, '0'))
        r.loadFromEvent(ev(10000, 100, listOf(listOf("p", pkA)), priv(listOf("p", pkB))), signer)
        // A newer version whose private part can't be read: public tags from it, the private part as last read.
        r.loadFromEvent(ev(10000, 200, listOf(listOf("p", pkC)), "not-a-ciphertext"), signer)
        assertEquals(setOf(pkC, pkB), r.blockedPubkeys.value)
        // Without a signer the same: the private part is not read, the last read one stays.
        r.loadFromEvent(ev(10000, 300, listOf(listOf("p", pkD)), priv(listOf("p", pkA))))
        assertEquals(setOf(pkD, pkB), r.blockedPubkeys.value)
        // With the signer it is read.
        r.loadFromEvent(ev(10000, 300, listOf(listOf("p", pkD)), priv(listOf("p", pkA))), signer)
        assertEquals(setOf(pkD, pkA), r.blockedPubkeys.value)
    }

    @Test
    fun localMuteHidesAtOnceAndLastsUntilTheListHasIt() = runBlocking {
        val r = MuteRepository(app, "m3".padEnd(64, '0'))
        r.loadFromEvent(ev(10000, 100, listOf(listOf("p", pkA))), signer)
        r.blockLocally(pkB)
        r.muteWordLocally("Spoiler")
        assertTrue(r.isBlocked(pkB))
        assertTrue(r.containsMutedWord("no SPOILER please"))
        // A version without them (the write was refused, or not out yet): still hidden here.
        r.loadFromEvent(ev(10000, 200, listOf(listOf("p", pkA), listOf("p", pkC))), signer)
        assertEquals(setOf(pkA, pkC, pkB), r.blockedPubkeys.value)
        // A version with them: the local copies are dropped, the list's stay.
        r.loadFromEvent(ev(10000, 300, listOf(listOf("p", pkA))), signer)
        r.loadFromEvent(ev(10000, 301, emptyList(), priv(listOf("p", pkB), listOf("word", "spoiler"))), signer)
        r.loadFromEvent(ev(10000, 302, emptyList()), signer)
        assertEquals(emptySet<String>(), r.blockedPubkeys.value)
        assertEquals(emptySet<String>(), r.mutedWords.value)
        // Unmuting drops a local mute at once.
        r.blockLocally(pkD)
        r.unblockLocally(pkD.uppercase())
        assertFalse(r.isBlocked(pkD))
    }

    @Test
    fun mutePendingOverlayAndThreadsStayLocal() = runBlocking {
        val r = MuteRepository(app, "m4".padEnd(64, '0'))
        r.muteThread("t".repeat(64))
        r.loadFromEvent(ev(10000, 100, listOf(listOf("p", pkA), listOf("word", "Cats"))), signer)
        r.setPending(mapOf(pkA to false, pkB to true), mapOf("cats" to false, "dogs" to true))
        assertEquals(setOf(pkB), r.blockedPubkeys.value)
        assertEquals(setOf("dogs"), r.mutedWords.value)
        assertFalse(r.containsMutedWord("cats"))
        r.setPending(emptyMap(), emptyMap())
        assertEquals(setOf(pkA), r.blockedPubkeys.value)
        assertTrue("a load never clears muted threads", r.isThreadMuted("t".repeat(64)))
    }

    @Test
    fun muteOverlayKeysMatchListWriter() {
        val prefix = ListWriter.overlayKey(10000, null, listOf("p", ""))!!
        assertEquals(prefix + pkC, ListWriter.overlayKey(10000, null, listOf("p", pkC.uppercase())))
        val wordPrefix = ListWriter.overlayKey(10000, null, listOf("word", ""))!!
        assertEquals(wordPrefix + "cats", ListWriter.overlayKey(10000, null, listOf("word", "CaTs")))
        assertEquals(ListWriter.overlayKey(3, null, listOf("p", ""))!! + pkA, ListWriter.overlayKey(3, null, listOf("p", pkA, "wss://r", "pet")))
        assertEquals(ListWriter.overlayKey(10001, null, listOf("e", ""))!! + pkA, ListWriter.overlayKey(10001, null, listOf("e", pkA, "wss://r")))
    }

    // ------------------------------------------------------------------ follows

    @Test
    fun followsLoadFromEventsWithPendingOnTop() {
        val r = ContactRepository(app, "c1".padEnd(64, '0'))
        r.loadFromEvent(ev(3, 100, listOf(listOf("p", pkA, "wss://hint", "alice"), listOf("t", "nostr"), listOf("p", pkB))))
        assertEquals(listOf(pkA, pkB), r.getFollowList().map { it.pubkey })
        assertEquals("alice", r.getFollowList().first().petname)
        r.setPending(mapOf(pkB to false, pkC.uppercase() to true))
        assertTrue(r.isFollowing(pkC))
        assertFalse(r.isFollowing(pkB))
        assertEquals(listOf(pkA, pkC), r.getFollowList().map { it.pubkey })
        r.setPending(emptyMap())
        assertEquals(listOf(pkA, pkB), r.getFollowList().map { it.pubkey })
    }

    @Test
    fun followsStoreDecidesNewestFallbackOnlyNewer() {
        val r = ContactRepository(app, "c2".padEnd(64, '0'))
        r.loadFromEvent(ev(3, 200, listOf(listOf("p", pkA)), id = "9".repeat(64)))
        r.loadFromEvent(ev(3, 200, listOf(listOf("p", pkB)), id = "0".repeat(64)))
        assertEquals(listOf(pkB), r.getFollowList().map { it.pubkey })
        r.loadFromEventIfNewer(ev(3, 199, listOf(listOf("p", pkC))))
        assertEquals(listOf(pkB), r.getFollowList().map { it.pubkey })
        r.loadFromEventIfNewer(ev(3, 201, listOf(listOf("p", pkD))))
        assertEquals(listOf(pkD), r.getFollowList().map { it.pubkey })
    }

    // ------------------------------------------------------------------ pins

    @Test
    fun pinsLoadFromEventsWithPendingOnTop() {
        val r = PinRepository(app, "p1".padEnd(64, '0'))
        r.loadFromEvent(ev(10001, 100, listOf(listOf("e", pkA), listOf("a", "30023:x:y"), listOf("e", pkB))))
        assertEquals(setOf(pkA, pkB), r.pinnedIds.value)
        r.setPending(mapOf(pkA to false, pkC to true))
        assertEquals(setOf(pkB, pkC), r.pinnedIds.value)
        assertTrue(r.isPinned(pkC))
        assertFalse(r.isPinned(pkA))
        r.setPending(emptyMap())
        assertEquals(setOf(pkA, pkB), r.pinnedIds.value)
        r.loadFromEventIfNewer(ev(10001, 50, listOf(listOf("e", pkD))))
        assertEquals(setOf(pkA, pkB), r.pinnedIds.value)
    }
}
