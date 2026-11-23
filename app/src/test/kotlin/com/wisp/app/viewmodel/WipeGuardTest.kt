package com.wisp.app.viewmodel

import android.app.Application
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.repo.ListDrop
import com.wisp.app.repo.MemoryOwnListStorage
import com.wisp.app.repo.OwnListStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The wipe guard: a version from a relay that lost entries keeps the fuller one, the notice counts what is missing
 * now, Put back adds only through ListWriter, Leave it publishes nothing, and Pinket's own changes never raise one.
 * Fake relays, a real LocalSigner on a throwaway key; Robolectric only for android.util.Base64 inside NIP-44.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WipeGuardTest {
    private val w1 = "wss://w1.example"
    private val w2 = "wss://w2.example"

    private class FakeRelays : ListSource, ListSink {
        val held = mutableMapOf<String, MutableList<NostrEvent>>()
        val online = mutableSetOf<String>()
        val published = mutableListOf<NostrEvent>()
        /** Runs for every published event before the relays answer (a live subscription echoing it back). */
        var onPublish: (NostrEvent) -> Unit = {}

        fun put(url: String, e: NostrEvent) { held.getOrPut(ListWriter.norm(url)) { mutableListOf() }.add(e) }

        override suspend fun fetch(request: ListFetchRequest): ListFetchResult {
            val asked = (request.writeRelays + request.otherRelays).map { ListWriter.norm(it) }.filter { it in online }.toSet()
            val events = asked.flatMap { u ->
                held[u].orEmpty().filter { e ->
                    e.pubkey == request.author && e.kind == request.kind &&
                        (request.d == null || e.tags.any { it.size >= 2 && it[0] == "d" && it[1] == request.d })
                }
            }
            return ListFetchResult(asked, asked, events)
        }

        override suspend fun publish(event: NostrEvent, writeRelays: List<String>, otherRelays: List<String>): ListPublishResult {
            published += event
            onPublish(event)
            val sent = (writeRelays + otherRelays).map { ListWriter.norm(it) }.filter { it in online }.toSet()
            for (u in sent) put(u, event)
            return ListPublishResult(sent, sent)
        }
    }

    /** A signer whose decrypting can be switched off (no signer able to read the private part yet). */
    private class Gate(private val inner: NostrSigner) : NostrSigner by inner {
        var canRead = true
        override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String {
            if (!canRead) error("not now")
            return inner.nip44Decrypt(ciphertext, peerPubkeyHex)
        }
        override suspend fun nip04Decrypt(ciphertext: String, peerPubkeyHex: String): String {
            if (!canRead) error("not now")
            return inner.nip04Decrypt(ciphertext, peerPubkeyHex)
        }
    }

    private class Clock(var now: Long)

    private class H(
        val relays: FakeRelays,
        val signer: LocalSigner,
        val gate: Gate,
        val store: OwnListStore,
        val writer: ListWriter,
        val guard: WipeGuard,
        val clock: Clock
    ) {
        val me: String get() = signer.pubkeyHex
        val now: Long get() = clock.now
    }

    private fun harness(): H = runBlocking {
        val kp = Keys.generate()
        val signer = LocalSigner(kp.privkey, kp.pubkey)
        val gate = Gate(signer)
        val relays = FakeRelays().apply { online += listOf(ListWriter.norm(w1), ListWriter.norm(w2)) }
        val clock = Clock(1_000_000L)
        val store = OwnListStore(signer.pubkeyHex, MemoryOwnListStorage(), clock = { clock.now }, entryId = { ListWriter.entryId(it) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val acct = { ListAccount(gate, store) }
        val writer = ListWriter(
            source = relays, sink = relays, account = acct, activePubkey = { signer.pubkeyHex },
            poolWriteRelays = { listOf(w1, w2) }, scope = scope, clock = { clock.now }, indexerRelays = emptyList(),
            work = EmptyCoroutineContext
        )
        val guard = WipeGuard(acct, writer, scope, EmptyCoroutineContext)
        val rl = signer.signEvent(10002, "", listOf(listOf("r", w1), listOf("r", w2)), clock.now - 5000)
        assertTrue(store.seen(rl))
        H(relays, signer, gate, store, writer, guard, clock)
    }

    private fun hex(n: Int) = n.toString(16).padStart(64, '0')
    private fun e(n: Int) = listOf("e", hex(n))
    private fun p(n: Int) = listOf("p", hex(n))

    private var step = 0L

    /** A version signed by "another app" with this key, newer each time. */
    private fun H.version(kind: Int, tags: List<List<String>>, priv: List<List<String>>? = null): NostrEvent = runBlocking {
        val content = priv?.let { signer.nip44Encrypt(ListWriter.privateJson(it), me) } ?: ""
        signer.signEvent(kind, content, tags, now - 1000 + (++step))
    }

    /** A version arriving from a relay: on the relays, and offered to the store the way EventRouter does. */
    private fun H.fromRelay(e: NostrEvent): Boolean {
        relays.put(w1, e); relays.put(w2, e)
        return store.seen(e)
    }

    private fun H.dec(content: String): List<List<String>> = runBlocking {
        ListWriter.parsePrivate(signer.nip44Decrypt(content, me))!!
    }

    // ------------------------------------------------------------------ detection

    @Test
    fun aPublicDropOfThreeRaisesANotice() = runBlocking {
        val h = harness()
        h.fromRelay(h.version(10003, (1..5).map { e(it) }))
        h.fromRelay(h.version(10003, listOf(e(1), e(2))))
        h.guard.refresh()
        val n = h.guard.notices.value.single()
        assertEquals(10003, n.kind)
        assertNull(n.d)
        assertEquals(3, n.missing)
    }

    @Test
    fun aDropOfTwoRaisesNothing() = runBlocking {
        val h = harness()
        h.fromRelay(h.version(10003, (1..5).map { e(it) }))
        h.fromRelay(h.version(10003, listOf(e(1), e(2), e(3))))
        assertTrue(h.store.keptKeys().isEmpty())
        h.guard.refresh()
        assertTrue(h.guard.notices.value.isEmpty())
    }

    @Test
    fun titlesAndOtherNonEntryTagsAreNotEntries() = runBlocking {
        val h = harness()
        h.fromRelay(h.version(30003, listOf(listOf("d", "reading"), listOf("title", "Reading"), listOf("description", "x"),
            listOf("image", "https://i.example/a.png"), e(1))))
        h.fromRelay(h.version(30003, listOf(listOf("d", "reading"), e(1))))
        h.guard.refresh()
        assertTrue(h.guard.notices.value.isEmpty())
    }

    @Test
    fun aPrivatePartEmptiedRaisesANotice() = runBlocking {
        val h = harness()
        h.fromRelay(h.version(10000, listOf(p(1)), priv = listOf(p(2), listOf("word", "spoilers"))))
        h.fromRelay(h.version(10000, listOf(p(1))))
        h.guard.refresh()
        val n = h.guard.notices.value.single()
        assertEquals(10000, n.kind)
        assertEquals(2, n.missing)
    }

    @Test
    fun privateEntriesAreCountedOnceThePrivatePartsCanBeRead() = runBlocking {
        val h = harness()
        h.gate.canRead = false
        h.fromRelay(h.version(10003, listOf(e(1)), priv = (10..15).map { e(it) }))
        h.fromRelay(h.version(10003, listOf(e(1)), priv = listOf(e(10), e(11))))
        h.guard.refresh()
        // Cannot be told yet: no notice, and the fuller copy is kept meanwhile.
        assertTrue(h.guard.notices.value.isEmpty())
        assertNotNull(h.store.kept("10003"))
        h.gate.canRead = true
        h.guard.refresh()
        assertEquals(4, h.guard.notices.value.single().missing)
    }

    @Test
    fun anEntryMovedFromPublicToPrivateIsNotMissing() = runBlocking {
        val h = harness()
        h.fromRelay(h.version(10003, (1..4).map { e(it) }))
        h.fromRelay(h.version(10003, emptyList(), priv = (1..4).map { e(it) }))
        h.guard.refresh()
        assertTrue(h.guard.notices.value.isEmpty())
        // Checked: not a drop, so nothing is kept.
        assertTrue(h.store.keptKeys().isEmpty())
    }

    @Test
    fun aPrivateChangeThatLostNothingKeepsNothing() = runBlocking {
        val h = harness()
        h.fromRelay(h.version(10000, emptyList(), priv = listOf(p(1), p(2))))
        h.fromRelay(h.version(10000, emptyList(), priv = listOf(p(1), p(2), p(3))))
        assertNotNull(h.store.kept("10000"))
        h.guard.refresh()
        assertTrue(h.guard.notices.value.isEmpty())
        assertTrue(h.store.keptKeys().isEmpty())
    }

    // ------------------------------------------------------------------ own changes

    @Test
    fun removingEntriesInPinketRaisesNothing() = runBlocking {
        val h = harness()
        h.fromRelay(h.version(10003, (1..6).map { e(it) }))
        val r = h.writer.setMany(10003, (1..5).map { e(it) }, wanted = false)
        assertTrue(r is ListResult.Sent)
        val sent = (r as ListResult.Sent).event
        assertEquals(listOf(e(6)), sent.tags)
        // The relay echo of the version signed here.
        assertFalse(h.store.seen(sent))
        h.guard.refresh()
        assertTrue(h.store.keptKeys().isEmpty())
        assertTrue(h.guard.notices.value.isEmpty())
    }

    @Test
    fun aRelayEchoArrivingBeforeTheSignedVersionIsRecordedRaisesNothing() = runBlocking {
        val h = harness()
        h.fromRelay(h.version(10000, (1..6).map { p(it) }))
        var echoed = false
        h.relays.onPublish = { ev -> if (ev.kind == 10000) echoed = h.store.seen(ev) }
        val r = h.writer.setMany(10000, (1..5).map { p(it) }, wanted = false)
        assertTrue(r is ListResult.Sent)
        assertTrue("the echo replaced the old version before recordSigned", echoed)
        h.guard.refresh()
        assertTrue(h.store.keptKeys().isEmpty())
        assertTrue(h.guard.notices.value.isEmpty())
    }

    @Test
    fun entriesRemovedInPinketWhileANoticeIsOpenAreNotOfferedBack() = runBlocking {
        val h = harness()
        h.fromRelay(h.version(10003, (1..6).map { e(it) }))
        h.fromRelay(h.version(10003, listOf(e(1), e(6))))
        h.guard.refresh()
        assertEquals(4, h.guard.notices.value.single().missing)
        assertTrue(h.writer.set(10003, e(1), wanted = false) is ListResult.Sent)
        h.guard.refresh()
        assertEquals(4, h.guard.notices.value.single().missing)
    }

    // ------------------------------------------------------------------ the kept copy over time

    @Test
    fun theFirstKeptCopyStaysAndTheCountFollowsTheNewestVersion() = runBlocking {
        val h = harness()
        val full = h.version(10003, (1..10).map { e(it) })
        h.fromRelay(full)
        h.fromRelay(h.version(10003, (1..6).map { e(it) }))
        h.guard.refresh()
        assertEquals(4, h.guard.notices.value.single().missing)
        // A second, later drop: the earliest, fullest copy is kept.
        h.fromRelay(h.version(10003, listOf(e(1), e(2))))
        assertEquals(full.id, NostrEvent.fromJson(h.store.kept("10003")!!.event).id)
        h.guard.refresh()
        assertEquals(8, h.guard.notices.value.single().missing)
        // Entries come back by hand (here, from another app): the count shrinks.
        h.fromRelay(h.version(10003, (1..7).map { e(it) }))
        h.guard.refresh()
        assertEquals(3, h.guard.notices.value.single().missing)
        // Nothing missing any more: the notice closes and the kept copy is forgotten.
        h.fromRelay(h.version(10003, (1..10).map { e(it) } + listOf(e(11))))
        h.guard.refresh()
        assertTrue(h.guard.notices.value.isEmpty())
        assertTrue(h.store.keptKeys().isEmpty())
    }

    @Test
    fun theKeptCopySurvivesARestartAndIsClearedOnLogout() = runBlocking {
        val storage = MemoryOwnListStorage()
        val kp = Keys.generate()
        val signer = LocalSigner(kp.privkey, kp.pubkey)
        val now = 1_000_000L
        val s1 = OwnListStore(signer.pubkeyHex, storage, clock = { now })
        assertTrue(s1.seen(signer.signEvent(3, "", (1..5).map { p(it) }, now - 100)))
        assertTrue(s1.seen(signer.signEvent(3, "", listOf(p(1)), now - 50)))
        val s2 = OwnListStore(signer.pubkeyHex, storage, clock = { now })
        assertNotNull(s2.kept("3"))
        s2.clear()
        assertNull(s2.kept("3"))
    }

    @Test
    fun onlyTheGuardedListsAreWatched() = runBlocking {
        val h = harness()
        h.fromRelay(h.version(10006, (1..5).map { listOf("relay", "wss://r$it.example") }))
        h.fromRelay(h.version(10006, emptyList()))
        assertTrue(h.store.keptKeys().isEmpty())
        assertEquals(setOf(3, 10000, 10001, 10003, 30000, 30003), ListDrop.GUARDED_KINDS)
    }

    // ------------------------------------------------------------------ put back / leave it

    @Test
    fun putBackAddsTheMissingEntriesToTheNewestVersionInTheirOwnParts() = runBlocking {
        val h = harness()
        h.fromRelay(h.version(10003, listOf(e(1), e(2), e(3), e(4), listOf("t", "nostr")), priv = listOf(e(5), e(6))))
        val current = h.version(10003, listOf(listOf("t", "nostr"), e(1), e(7)))
        h.fromRelay(current)
        h.guard.refresh()
        val n = h.guard.notices.value.single()
        assertEquals(5, n.missing)
        val before = h.relays.published.size
        val r = h.guard.putBack(n)
        assertTrue(r is ListResult.Sent)
        val ev = (r as ListResult.Sent).event
        // Written by ListWriter (the one place that signs lists), once.
        assertEquals(before + 1, h.relays.published.size)
        assertEquals(ev.id, h.relays.published.last().id)
        // Everything in the current version stays, in order; the missing public entries are added after it.
        assertEquals(current.tags + listOf(e(2), e(3), e(4)), ev.tags)
        assertEquals(listOf(e(5), e(6)), h.dec(ev.content))
        assertTrue(ev.created_at > current.created_at)
        assertTrue(h.guard.notices.value.isEmpty())
        assertTrue(h.store.keptKeys().isEmpty())
    }

    @Test
    fun putBackIntoASetKeepsItsTitleAndNameIsShown() = runBlocking {
        val h = harness()
        val head = listOf(listOf("d", "reading"), listOf("title", "Reading"))
        h.fromRelay(h.version(30003, head + (1..9).map { e(it) }))
        val current = h.version(30003, head + listOf(e(1)))
        h.fromRelay(current)
        h.guard.refresh()
        val n = h.guard.notices.value.single()
        assertEquals("reading", n.d)
        assertEquals("Reading", n.title)
        assertEquals(8, n.missing)
        val r = h.guard.putBack(n)
        assertTrue(r is ListResult.Sent)
        assertEquals(current.tags + (2..9).map { e(it) }, (r as ListResult.Sent).event.tags)
        assertTrue(h.guard.notices.value.isEmpty())
    }

    @Test
    fun putBackOfflineKeepsTheNotice() = runBlocking {
        val h = harness()
        h.fromRelay(h.version(10001, (1..4).map { e(it) }))
        h.fromRelay(h.version(10001, listOf(e(1))))
        h.guard.refresh()
        val n = h.guard.notices.value.single()
        h.relays.online.clear()
        val r = h.guard.putBack(n)
        assertFalse(r is ListResult.Sent)
        assertEquals(3, h.guard.notices.value.single().missing)
        assertNotNull(h.store.kept("10001"))
    }

    @Test
    fun leaveItForgetsTheKeptCopyAndPublishesNothing() = runBlocking {
        val h = harness()
        h.fromRelay(h.version(3, (1..5).map { p(it) }))
        h.fromRelay(h.version(3, listOf(p(1))))
        h.guard.refresh()
        val n = h.guard.notices.value.single()
        val before = h.relays.published.size
        h.guard.leaveIt(n)
        assertEquals(before, h.relays.published.size)
        assertTrue(h.guard.notices.value.isEmpty())
        assertTrue(h.store.keptKeys().isEmpty())
    }

    @Test
    fun severalNoticesComeOldestFirst() = runBlocking {
        val h = harness()
        h.fromRelay(h.version(10000, (1..4).map { p(it) }))
        h.fromRelay(h.version(10003, (1..4).map { e(it) }))
        h.fromRelay(h.version(10003, emptyList()))
        h.clock.now += 60
        h.fromRelay(h.version(10000, emptyList()))
        h.guard.refresh()
        assertEquals(listOf(10003, 10000), h.guard.notices.value.map { it.kind })
    }
}
