package com.wisp.app.repo

import android.app.Application
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.runBlocking
import com.wisp.app.viewmodel.ListWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** BookmarkRepository as a read model: loads from the version the own-list store took, pending laid over it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BookmarkRepositoryTest {
    private val me = "a".repeat(64)
    private val idA = "1".repeat(64)
    private val idB = "2".repeat(64)
    private val idC = "3".repeat(64)

    private fun repo(pk: String = me) = BookmarkRepository(RuntimeEnvironment.getApplication(), pk)

    private fun ev(createdAt: Long, vararg ids: String, id: String = "f".repeat(64)) = NostrEvent(
        id, me, createdAt, 10003,
        ids.map { listOf("e", it) } + listOf(listOf("r", "https://example.com"), listOf("t", "nostr")),
        "opaque-private-part", ""
    )

    @Test
    fun loadsOnlyFromEvents() {
        val r = repo()
        r.loadFromEvent(ev(100, idA, idB))
        assertEquals(setOf(idA, idB), r.bookmarkedIds.value)
        assertEquals(setOf("nostr"), r.getHashtags())
    }

    @Test
    fun storeDecidesNewestSoNoTimeCheck() {
        val r = repo(pk = "b".repeat(64))
        r.loadFromEvent(ev(200, idA, id = "9".repeat(64)))
        // Same second, lower id: the store took it as newer (NIP-01), so the repository must load it.
        r.loadFromEvent(ev(200, idB, id = "0".repeat(64)))
        assertEquals(setOf(idB), r.bookmarkedIds.value)
    }

    @Test
    fun fallbackLoadOnlyNewer() {
        val r = repo(pk = "c".repeat(64))
        r.loadFromEventIfNewer(ev(300, idA))
        r.loadFromEventIfNewer(ev(299, idB))
        r.loadFromEventIfNewer(ev(300, idC))
        assertEquals(setOf(idA), r.bookmarkedIds.value)
    }

    @Test
    fun pendingOverlayFlipsAtOnceAndProjectionStays() {
        val r = repo(pk = "d".repeat(64))
        r.loadFromEvent(ev(100, idA, idB))
        r.setPending(mapOf(idC to true, idA to false))
        assertEquals(setOf(idB, idC), r.bookmarkedIds.value)
        assertTrue(r.isBookmarked(idC))
        assertFalse(r.isBookmarked(idA))
        // Refused or confirmed: the overlay goes away and the projection shows again.
        r.setPending(emptyMap())
        assertEquals(setOf(idA, idB), r.bookmarkedIds.value)
        // A reload from prefs gets the projection only, never the pending state.
        r.setPending(mapOf(idC to true))
        val again = repo(pk = "d".repeat(64))
        assertEquals(setOf(idA, idB), again.bookmarkedIds.value)
    }

    @Test
    fun overlayIdsMatchAnyCase() {
        val r = repo(pk = "e".repeat(64))
        val upper = "AB".repeat(32)
        r.loadFromEvent(ev(100, upper))
        // ListWriter keys `e` entries in lowercase.
        r.setPending(mapOf(upper.lowercase() to false))
        assertTrue(r.bookmarkedIds.value.isEmpty())
        r.setPending(mapOf(upper.lowercase() to true))
        assertEquals(setOf(upper), r.bookmarkedIds.value)
    }

    @Test
    fun overlayKeyPrefixMatchesListWriter() {
        val prefix = ListWriter.overlayKey(10003, null, listOf("e", ""))!!
        val key = ListWriter.overlayKey(10003, null, listOf("e", idA, "wss://hint.example"))!!
        assertTrue(key.startsWith(prefix))
        assertEquals(idA, key.substring(prefix.length))
        assertFalse(ListWriter.overlayKey(10003, null, listOf("a", "30023:$me:x"))!!.startsWith(prefix))
        assertFalse(ListWriter.overlayKey(10001, null, listOf("e", idA))!!.startsWith(prefix))
    }

    @Test
    fun privatelyBookmarkedNotesShowAsBookmarked() = runBlocking {
        val k = Keys.generate()
        val signer = LocalSigner(k.privkey, k.pubkey)
        val r = repo(pk = signer.pubkeyHex)
        val priv = ListWriter.privateJson(listOf(listOf("e", idB), listOf("a", "30023:$me:x"), listOf("t", "secret")))
        val content = signer.nip44Encrypt(priv, signer.pubkeyHex)
        val e = NostrEvent("f".repeat(64), signer.pubkeyHex, 100, 10003, listOf(listOf("e", idA)), content, "")
        r.loadFromEvent(e, signer)
        assertEquals(setOf(idA, idB), r.bookmarkedIds.value)
        assertTrue(r.isBookmarked(idB))
        assertTrue("30023:$me:x" in r.getCoordinates())
        assertTrue("secret" in r.getHashtags())
        // A later version whose private part cannot be read keeps the last read one.
        val unreadable = NostrEvent("e".repeat(64), signer.pubkeyHex, 200, 10003, listOf(listOf("e", idC)), "not-readable", "")
        r.loadFromEvent(unreadable, signer)
        assertEquals(setOf(idC, idB), r.bookmarkedIds.value)
        // And so does a restart.
        assertEquals(setOf(idC, idB), repo(pk = signer.pubkeyHex).bookmarkedIds.value)
        // An empty private part empties it.
        r.loadFromEvent(NostrEvent("d".repeat(64), signer.pubkeyHex, 300, 10003, listOf(listOf("e", idC)), "", ""), signer)
        assertEquals(setOf(idC), r.bookmarkedIds.value)
    }
}
