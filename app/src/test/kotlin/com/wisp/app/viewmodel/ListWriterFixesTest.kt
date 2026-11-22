package com.wisp.app.viewmodel

import android.app.Application
import com.wisp.app.R
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.repo.MemoryOwnListStorage
import com.wisp.app.repo.OwnListStore
import com.wisp.app.repo.PendingChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The review fixes of the list writes, against fake relays with a real LocalSigner on a throwaway key generated here:
 * media servers the screen cannot show, your relay list loaded when the writer fetches it, a kept change that expects
 * its list, sets public or private by their newest version, relay list repair with dead write relays, a set being
 * created shown at once, the whole-version write when things fail, read-only sets and unreadable private parts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ListWriterFixesTest {
    private val w1 = "wss://w1.example"
    private val w2 = "wss://w2.example"
    private val w3 = "wss://w3.example"
    private val idx = "wss://idx.example"
    private val idx2 = "wss://idx2.example"
    /** Content that is no private part this key can read. */
    private val GARBAGE = "bm90IGEgdmFsaWQgbmlwNDQgcGF5bG9hZA=="

    private class FakeRelays : ListSource, ListSink {
        val held = mutableMapOf<String, MutableList<NostrEvent>>()
        var online = mutableSetOf<String>()
        var accepting = mutableSetOf<String>()
        var onFetch: (ListFetchRequest) -> Unit = {}
        val published = mutableListOf<NostrEvent>()

        fun put(url: String, e: NostrEvent) { held.getOrPut(ListWriter.norm(url)) { mutableListOf() }.add(e) }

        fun newestAt(url: String, kind: Int, d: String? = null): NostrEvent? = held[ListWriter.norm(url)].orEmpty()
            .filter { it.kind == kind && (d == null || it.tags.any { t -> t.size >= 2 && t[0] == "d" && t[1] == d }) }
            .maxWithOrNull(compareBy<NostrEvent> { it.created_at }.thenByDescending { it.id })

        override suspend fun fetch(request: ListFetchRequest): ListFetchResult {
            onFetch(request)
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
            val sent = (writeRelays + otherRelays).map { ListWriter.norm(it) }.filter { it in online }.toSet()
            val acc = sent.filter { it in accepting }.toSet()
            for (u in acc) put(u, event)
            return ListPublishResult(sent, acc)
        }
    }

    private class Clock(var now: Long, var active: String?)

    /** The account's signer with switches for the failures under test. */
    private class TestSigner(val real: LocalSigner) : NostrSigner {
        var throwOnSign = false
        var otherKey: LocalSigner? = null
        var badReadBack = false
        var onSign: () -> Unit = {}
        override val pubkeyHex: String get() = real.pubkeyHex
        override suspend fun signEvent(kind: Int, content: String, tags: List<List<String>>, createdAt: Long): NostrEvent {
            onSign()
            if (throwOnSign) error("signer went away")
            return (otherKey ?: real).signEvent(kind, content, tags, createdAt)
        }
        override suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String) = real.nip44Encrypt(plaintext, peerPubkeyHex)
        override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String) =
            if (badReadBack) "[[\"title\",\"something else\"]]" else real.nip44Decrypt(ciphertext, peerPubkeyHex)
        override suspend fun nip04Encrypt(plaintext: String, peerPubkeyHex: String) = real.nip04Encrypt(plaintext, peerPubkeyHex)
        override suspend fun nip04Decrypt(ciphertext: String, peerPubkeyHex: String) = real.nip04Decrypt(ciphertext, peerPubkeyHex)
    }

    private class Harness(
        val relays: FakeRelays,
        val signer: LocalSigner,
        val testSigner: TestSigner,
        val store: OwnListStore,
        val storage: MemoryOwnListStorage,
        val writer: ListWriter,
        val clock: Clock,
        val messages: MutableList<Int>,
        val projected: MutableList<NostrEvent>
    ) {
        val me: String get() = signer.pubkeyHex
        var now: Long
            get() = clock.now
            set(v) { clock.now = v }
    }

    /** [relayList]: the `r` tags of your relay list already known here (null: none known). */
    private fun harness(
        relayList: List<List<String>>? = listOf(listOf("r", w1), listOf("r", w2)),
        indexers: List<String> = listOf(idx),
        start: Long = 1_000_000L
    ): Harness = runBlocking {
        val kp = Keys.generate()
        val signer = LocalSigner(kp.privkey, kp.pubkey)
        val testSigner = TestSigner(signer)
        val relays = FakeRelays().apply {
            online = (listOf(w1, w2, w3) + indexers).map { ListWriter.norm(it) }.toMutableSet()
            accepting = online.toMutableSet()
        }
        val clock = Clock(start, signer.pubkeyHex)
        val storage = MemoryOwnListStorage()
        val store = OwnListStore(signer.pubkeyHex, storage, clock = { clock.now }, entryId = { ListWriter.entryId(it) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val projected = Collections.synchronizedList(mutableListOf<NostrEvent>())
        val writer = ListWriter(
            source = relays,
            sink = relays,
            account = { ListAccount(testSigner, store) },
            activePubkey = { clock.active },
            poolWriteRelays = { listOf(w1, w2) },
            scope = scope,
            project = { projected += it },
            clock = { clock.now },
            indexerRelays = indexers,
            work = EmptyCoroutineContext
        )
        val messages = Collections.synchronizedList(mutableListOf<Int>())
        scope.launch { writer.messages.collect { messages += it } }
        if (relayList != null) assertTrue(store.seen(signer.signEvent(10002, "", relayList, start - 5000)))
        Harness(relays, signer, testSigner, store, storage, writer, clock, messages, projected)
    }

    private fun Harness.sign(kind: Int, tags: List<List<String>>, content: String = "", at: Long = now - 100): NostrEvent =
        runBlocking { signer.signEvent(kind, content, tags, at) }

    private fun Harness.onRelays(e: NostrEvent, vararg urls: String = arrayOf(w1, w2, idx)) { for (u in urls) relays.put(u, e) }

    private fun Harness.enc(tags: List<List<String>>) = runBlocking { signer.nip44Encrypt(ListWriter.privateJson(tags), me) }
    private fun Harness.dec(content: String): List<List<String>> = runBlocking { ListWriter.parsePrivate(signer.nip44Decrypt(content, me))!! }

    private fun hex(n: Int) = n.toString(16).padStart(64, '0')
    private fun sent(r: ListResult): NostrEvent = (r as? ListResult.Sent)?.event ?: error("not sent: $r")
    private fun reason(r: ListResult): ListResult.Reason = (r as? ListResult.Refused)?.reason ?: error("not refused: $r")

    // ------------------------------------------------------------------ media servers the screen cannot show

    @Test
    fun mediaServerSaveNeverTouchesEntriesTheScreenCannotShow() = runBlocking {
        val h = harness()
        val tags = listOf(
            listOf("server", "blossom.example.com"), listOf("server", "https://x.example:443"),
            listOf("x-unknown", "keep"), listOf("server", "https://a.example")
        )
        val old = h.sign(10063, tags)
        h.onRelays(old)
        assertTrue(h.store.seen(old))
        val start = RelayListEdits.startTags(h.store, 10063)
        val shown = RelayListEdits.serversOf(start)
        assertEquals(listOf("https://x.example/", "https://a.example/"), shown)
        // Nothing changed on the screen: nothing to write (the entry it cannot show stays).
        assertTrue(RelayListEdits.serverList(start, shown).isEmpty())
        // One server taken out on screen: it goes out in the spelling the list holds, the hidden one stays.
        val edits = RelayListEdits.serverList(start, listOf("https://a.example/", "https://d.example"))
        assertEquals(listOf(ListEdit.remove("server", "https://x.example:443"), ListEdit.add("server", "https://d.example")), edits)
        val e = sent(h.writer.edit(10063, edits, expected = true))
        assertEquals(
            listOf(listOf("server", "blossom.example.com"), listOf("x-unknown", "keep"), listOf("server", "https://a.example"), listOf("server", "https://d.example")),
            e.tags
        )
    }

    // ------------------------------------------------------------------ your relay list, fetched by the writer

    @Test
    fun aRelayListTheWriterFetchesIsLoadedIntoTheApp() = runBlocking {
        val h = harness(relayList = null)
        val rl = h.sign(10002, listOf(listOf("r", w1), listOf("r", w2), listOf("r", "wss://mine.example")), at = h.now - 3000)
        h.onRelays(rl)
        // A bookmark tapped before the start-up fetch delivered the relay list.
        sent(h.writer.set(10003, listOf("e", hex(1)), wanted = true))
        assertEquals(rl.id, h.store.latest(10002)?.id)
        assertTrue("the relay list reached the app's settings", h.projected.any { it.id == rl.id })
    }

    @Test
    fun theRelayEditorIsInStepOnlyWithTheVersionItsRowsCameFrom() {
        val h = harness(relayList = null)
        assertTrue(RelayListEdits.inStep(h.store, 10002))
        val rl = h.sign(10002, listOf(listOf("r", w1)), at = h.now - 300)
        assertTrue(h.store.seen(rl))
        assertFalse(RelayListEdits.inStep(h.store, 10002))
        h.store.markProjected("10002", rl.id)
        assertTrue(RelayListEdits.inStep(h.store, 10002))
        val newer = h.sign(10002, listOf(listOf("r", w2)), at = h.now - 200)
        assertTrue(h.store.seen(newer))
        assertFalse(RelayListEdits.inStep(h.store, 10002))
        h.store.clear()
        assertTrue(RelayListEdits.inStep(h.store, 10002))
        assertNull(h.store.projected("10002"))
    }

    // ------------------------------------------------------------------ favourites and groups expected

    @Test
    fun favouritesOrGroupsTheAppShowsAreNeverStartedFromOneEntry() = runBlocking {
        val h = harness()
        // The app shows favourites (from before the store knew any): the star tap expects the list.
        val r = h.writer.set(10012, listOf("relay", "wss://f.example"), wanted = true, expected = true)
        assertEquals(ListResult.Reason.NOT_FOUND, reason(r))
        val g = h.writer.edit(10009, listOf(ListEdit.add("group", "x", "wss://g.example"), ListEdit.add("r", "wss://g.example")), expected = true)
        assertEquals(ListResult.Reason.NOT_FOUND, reason(g))
        assertTrue(h.relays.published.isEmpty())
        // Other joined groups: expected. The group itself only: not.
        assertTrue(GroupListViewModel.groupListExpected(listOf("wss://a.example" to "a", "wss://g.example/" to "x"), "wss://g.example", "x"))
        assertFalse(GroupListViewModel.groupListExpected(listOf("wss://G.example/" to "x"), "wss://g.example", "x"))
        assertFalse(GroupListViewModel.groupListExpected(emptyList(), "wss://g.example", "x"))
    }

    // ------------------------------------------------------------------ a kept change keeps "expected"

    @Test
    fun aKeptSetChangeThatExpectsItsSetIsRefusedOnReplayWhenNoneIsFound() = runBlocking {
        val h = harness()
        h.relays.online.clear()
        val r = h.writer.edit(30000, listOf(ListEdit.add("p", hex(1))), d = "close", place = ListPlace.AS_SET, expected = true, explicit = true)
        assertEquals(ListResult.Pending, r)
        assertTrue(h.store.pending("30000:close").single().expected)
        // Back online; the set is on none of your relays: never started from this one entry.
        h.relays.online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(w2), ListWriter.norm(idx))
        h.writer.resumeNow()
        assertTrue(h.relays.published.isEmpty())
        assertTrue(h.store.pending("30000:close").isEmpty())
        assertTrue(R.string.list_not_found in h.messages)
    }

    // ------------------------------------------------------------------ public or private, from the newest version

    @Test
    fun aSetEntryGoesWhereTheNewestVersionKeepsItsEntriesWhateverWasRecorded() = runBlocking {
        val h = harness()
        // A private bookmark set recorded "public" by the one-time move (its cache could not be read then).
        val priv = listOf(listOf("title", "Reads"), listOf("e", hex(1)))
        val set = h.sign(30003, listOf(listOf("d", "reads")), h.enc(priv))
        h.onRelays(set)
        h.store.markMadeHere("30003:reads", false)
        val a = sent(h.writer.edit(30003, listOf(ListEdit.add("e", hex(2))), d = "reads", place = ListPlace.AS_SET, expected = true, explicit = true))
        assertEquals(set.tags, a.tags)
        assertEquals(priv + listOf(listOf("e", hex(2))), h.dec(a.content))
        // A public set: public.
        val pub = h.sign(30000, listOf(listOf("d", "friends"), listOf("title", "Friends"), listOf("p", hex(1))))
        h.onRelays(pub)
        val b = sent(h.writer.edit(30000, listOf(ListEdit.add("p", hex(2))), d = "friends", place = ListPlace.AS_SET, expected = true, explicit = true))
        assertEquals(pub.tags + listOf(listOf("p", hex(2))), b.tags)
        assertEquals("", b.content)
        // A private set emptied to nothing: still private.
        val empty = h.sign(30000, listOf(listOf("d", "close")), h.enc(emptyList()))
        h.onRelays(empty)
        val c = sent(h.writer.edit(30000, listOf(ListEdit.add("p", hex(3))), d = "close", place = ListPlace.AS_SET, expected = true, explicit = true))
        assertEquals(empty.tags, c.tags)
        assertEquals(listOf(listOf("p", hex(3))), h.dec(c.content))
        // A private part that cannot be read and no public entries: refused, never a public tag.
        val opaque = h.sign(30003, listOf(listOf("d", "secret")), GARBAGE)
        h.onRelays(opaque)
        val d = h.writer.edit(30003, listOf(ListEdit.add("e", hex(4))), d = "secret", place = ListPlace.AS_SET, expected = true, explicit = true)
        assertEquals(ListResult.Reason.NOT_FOUND, reason(d))
        assertEquals(3, h.relays.published.size)
    }

    @Test
    fun theOneTimeMoveRecordsOnlyFromAStoredVersionThatLooksLikeThisApps() {
        val h = harness()
        assertNull(ListCrudManager.migratedMadeHere(30003, "reads", null))
        val privateSet = h.sign(30003, listOf(listOf("d", "reads")), "some-content")
        assertEquals(true, ListCrudManager.migratedMadeHere(30003, "reads", privateSet))
        val publicSet = h.sign(30003, listOf(listOf("d", "reads"), listOf("title", "Reads"), listOf("e", hex(1))))
        assertEquals(false, ListCrudManager.migratedMadeHere(30003, "reads", publicSet))
        val foreign = h.sign(30003, listOf(listOf("d", "reads"), listOf("e", hex(1)), listOf("client", "other")))
        assertNull(ListCrudManager.migratedMadeHere(30003, "reads", foreign))
        val notSlug = h.sign(30003, listOf(listOf("d", "Reads Later"), listOf("e", hex(1))))
        assertNull(ListCrudManager.migratedMadeHere(30003, "Reads Later", notSlug))
        // The newest version's shape wins over the made-here record.
        assertEquals(ListPlace.PRIVATE, ListCrudManager.placeOf(true, false, privateSet, false, setOf("e", "a", "t")))
        assertEquals(ListPlace.PUBLIC, ListCrudManager.placeOf(true, true, publicSet, true, setOf("e", "a", "t")))
    }

    // ------------------------------------------------------------------ relay list repair, write relays mostly dead

    @Test
    fun theRelayListCanBeRepairedWhenMostWriteRelaysAreGone() = runBlocking {
        val h = harness(relayList = listOf(listOf("r", w1), listOf("r", w2), listOf("r", w3)), indexers = listOf(idx, idx2))
        val rl = h.store.latest(10002)!!
        h.onRelays(rl, w1, idx, idx2)
        h.relays.online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(idx), ListWriter.norm(idx2))
        h.relays.accepting = h.relays.online.toMutableSet()
        // Other lists still need half of your write relays.
        assertEquals(ListResult.Pending, h.writer.set(10003, listOf("e", hex(1)), wanted = true))
        // Your relay list may be fixed from what the profile indexes and the one live write relay hold.
        val e = sent(h.writer.edit(10002, listOf(ListEdit.remove("r", w2), ListEdit.remove("r", w3)), expected = true))
        assertEquals(listOf(listOf("r", w1)), e.tags)
    }

    @Test
    fun allWriteRelaysGoneTheNewRelayListIsConfirmedByItsOwnRelays() = runBlocking {
        val fresh = "wss://fresh.example"
        val h = harness(relayList = listOf(listOf("r", w1), listOf("r", w2)), indexers = listOf(idx, idx2))
        val rl = h.store.latest(10002)!!
        h.onRelays(rl, idx, idx2)
        h.relays.online = mutableSetOf(ListWriter.norm(idx), ListWriter.norm(idx2), ListWriter.norm(fresh))
        h.relays.accepting = h.relays.online.toMutableSet()
        val e = sent(h.writer.edit(10002, listOf(ListEdit.remove("r", w1), ListEdit.remove("r", w2), ListEdit.add("r", fresh)), expected = true))
        assertEquals(listOf(listOf("r", fresh)), e.tags)
        assertEquals(e.id, h.relays.newestAt(fresh, 10002)?.id)
        assertNull(h.store.unconfirmed("10002"))
    }

    @Test
    fun indexersThatDoNotHoldTheRelayListDoNotStandInForWriteRelays() = runBlocking {
        val h = harness(relayList = listOf(listOf("r", w1), listOf("r", w2)), indexers = listOf(idx, idx2))
        // No write relay can be asked, and the two indexers answer with nothing: the device's copy may be stale.
        h.relays.online = mutableSetOf(ListWriter.norm(idx), ListWriter.norm(idx2))
        h.relays.accepting = h.relays.online.toMutableSet()
        val before = h.relays.published.size
        assertEquals(ListResult.Pending, h.writer.edit(10002, listOf(ListEdit.remove("r", w2)), expected = true))
        assertEquals(before, h.relays.published.size)
    }

    // ------------------------------------------------------------------ one listed write relay

    @Test
    fun withOneListedWriteRelayASetCanBeCreated() = runBlocking {
        val h = harness(relayList = listOf(listOf("r", w1)))
        val e = sent(h.writer.createSet(30000, "family", "Family"))
        assertEquals(listOf(listOf("d", "family"), listOf("title", "Family")), e.tags)
        val p = sent(h.writer.setProfileText(mapOf("name" to "x")))
        assertEquals("""{"name":"x"}""", p.content)
    }

    // ------------------------------------------------------------------ a set being created shows at once

    @Test
    fun aSetBeingCreatedIsShownUntilTheWriteIsDone() = runBlocking {
        val h = harness()
        val during = mutableListOf<List<PendingChange>>()
        h.relays.onFetch = { during += h.writer.creating.value }
        sent(h.writer.createSet(30003, "later", "Read later", private = true))
        val marker = during.first().single()
        assertTrue(marker.creating)
        assertEquals(30003, marker.kind)
        assertEquals("later", marker.d)
        assertEquals("Read later", marker.title)
        assertTrue(h.writer.creating.value.isEmpty())
        // Refused (the name is taken): gone again too.
        during.clear()
        assertEquals(ListResult.Reason.NAME_TAKEN, reason(h.writer.createSet(30003, "later", "Read later")))
        assertEquals(1, during.first().size)
        assertTrue(h.writer.creating.value.isEmpty())
        // Never stored with the changes kept for later.
        assertTrue(h.store.allPending().isEmpty())
    }

    // ------------------------------------------------------------------ whole-version writes that do not go well

    @Test
    fun aCreatedSetNoRelayConfirmedIsKeptAndSentAgainNotSignedTwice() = runBlocking {
        val h = harness()
        h.relays.accepting.clear()
        assertEquals(ListResult.Pending, h.writer.createSet(30000, "family", "Family"))
        assertEquals(listOf(R.string.list_will_save), h.messages.toList())
        val unconfirmed = h.store.unconfirmed("30000:family")
        assertNotNull(unconfirmed)
        assertEquals(1, h.relays.published.size)
        h.relays.accepting = h.relays.online.toMutableSet()
        h.writer.resumeNow()
        assertNull(h.store.unconfirmed("30000:family"))
        assertEquals(unconfirmed!!.id, h.relays.newestAt(w1, 30000, "family")?.id)
        assertEquals(setOf(unconfirmed.id), h.relays.published.map { it.id }.toSet())
    }

    @Test
    fun anAccountChangeDuringTheFetchOrTheSignPublishesNothingAndSaysNothing() = runBlocking {
        val h = harness()
        h.relays.onFetch = { h.clock.active = "f".repeat(64) }
        assertEquals(ListResult.Reason.NOT_SENT, reason(h.writer.setProfileText(mapOf("name" to "x"))))
        h.relays.onFetch = {}
        h.clock.active = h.me
        h.testSigner.onSign = { h.clock.active = null }
        assertEquals(ListResult.Reason.NOT_SENT, reason(h.writer.createSet(30000, "family", "Family")))
        assertTrue(h.relays.published.isEmpty())
        assertTrue(h.messages.isEmpty())
        assertTrue(h.store.latest(30000, "family") == null)
    }

    @Test
    fun aFailingOrForeignSignerPublishesNothing() = runBlocking {
        val h = harness()
        h.testSigner.throwOnSign = true
        assertEquals(ListResult.Reason.NOT_SENT, reason(h.writer.setProfileText(mapOf("name" to "x"))))
        h.testSigner.throwOnSign = false
        val other = Keys.generate()
        h.testSigner.otherKey = LocalSigner(other.privkey, other.pubkey)
        assertEquals(ListResult.Reason.NOT_SENT, reason(h.writer.setAppData("nwc-wallet-backup") { "C" }))
        assertTrue(h.relays.published.isEmpty())
        assertEquals(listOf(R.string.list_not_sent, R.string.list_not_sent), h.messages.toList())
    }

    @Test
    fun aPrivateSetWhoseContentDoesNotReadBackIsNotWritten() = runBlocking {
        val h = harness()
        h.testSigner.badReadBack = true
        assertEquals(ListResult.Reason.BLOCKED, reason(h.writer.createSet(30000, "close", "Close", private = true)))
        assertTrue(h.relays.published.isEmpty())
        assertFalse(h.store.madeHere("30000:close"))
    }

    @Test
    fun wholeWritesThatExpectAVersionOrMeetOneDatedAheadAreRefused() = runBlocking {
        val h = harness()
        assertEquals(ListResult.Reason.NOT_FOUND, reason(h.writer.reorder(10063, listOf(listOf("server", "https://a.example")), expected = true)))
        assertEquals(ListResult.Reason.NOT_FOUND, reason(h.writer.setAppData("nwc-wallet-backup", expected = true) { "C" }))
        h.onRelays(h.sign(0, emptyList(), """{"name":"a"}""", at = h.now + 3600))
        assertEquals(ListResult.Reason.NOT_FOUND, reason(h.writer.setProfileText(mapOf("name" to "b"))))
        assertTrue(h.relays.published.isEmpty())
    }

    // ------------------------------------------------------------------ read-only sets

    @Test
    fun anotherAppsSetRefusesEveryChangeTheUserDidNotMakeOnIt() = runBlocking {
        val h = harness()
        val foreign = h.sign(30000, listOf(listOf("d", "Their Folks"), listOf("title", "Folks"), listOf("p", hex(1))))
        h.onRelays(foreign)
        assertEquals(ListResult.Reason.READ_ONLY, reason(h.writer.edit(30000, listOf(ListEdit.remove("p", hex(1))), d = "Their Folks", expected = true)))
        assertEquals(ListResult.Reason.READ_ONLY, reason(h.writer.edit(30000, listOf(ListEdit.set("title", "Mine")), d = "Their Folks", expected = true)))
        assertTrue(h.relays.published.isEmpty())
        // Kept offline: one change picked by the app, one made by the user on that set. Only the user's goes in.
        h.relays.online.clear()
        assertEquals(ListResult.Pending, h.writer.edit(30000, listOf(ListEdit.add("p", hex(2))), d = "Their Folks", expected = true))
        assertEquals(ListResult.Pending, h.writer.edit(30000, listOf(ListEdit.add("p", hex(3))), d = "Their Folks", expected = true, explicit = true))
        // The user's flag survives the store (a fresh store on the same storage, as after a restart).
        val again = OwnListStore(h.me, h.storage, entryId = { ListWriter.entryId(it) })
        assertEquals(listOf(false, true), again.pending("30000:Their Folks").map { it.explicit })
        h.relays.online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(w2), ListWriter.norm(idx))
        h.writer.resumeNow()
        val e = h.relays.newestAt(w1, 30000, "Their Folks")!!
        assertEquals(foreign.tags + listOf(listOf("p", hex(3))), e.tags)
        assertTrue(h.store.pending("30000:Their Folks").isEmpty())
        assertTrue(R.string.list_read_only in h.messages)
    }

    @Test
    fun creatingAHiddenNameOfAnotherAppsSetIsRefused() = runBlocking {
        val h = harness()
        val foreign = h.sign(30000, listOf(listOf("d", "friends"), listOf("title", "Friends"), listOf("client", "other")))
        h.onRelays(foreign)
        h.writer.hideSet(30000, "friends")
        assertEquals(ListResult.Reason.NAME_TAKEN, reason(h.writer.createSet(30000, "friends", "Friends")))
        assertTrue(h.store.isHidden(30000, "friends"))
        assertTrue(h.relays.published.isEmpty())
    }

    // ------------------------------------------------------------------ an unreadable private part

    @Test
    fun aSetWhosePrivatePartCannotBeReadGetsNoTitleOrPrivateEntryBesideIt() = runBlocking {
        val h = harness()
        val set = h.sign(30000, listOf(listOf("d", "close"), listOf("p", hex(1))), GARBAGE)
        h.onRelays(set)
        for (place in listOf(ListPlace.PUBLIC, ListPlace.PRIVATE)) {
            val r = h.writer.edit(30000, listOf(ListEdit.set("title", "Closest")), d = "close", place = place, expected = true, explicit = true)
            assertEquals(ListResult.Reason.NOT_FOUND, reason(r))
        }
        assertEquals(ListResult.Reason.NOT_FOUND, reason(h.writer.set(30000, listOf("p", hex(2)), wanted = true, d = "close", place = ListPlace.PRIVATE, expected = true, explicit = true)))
        assertEquals(ListResult.Reason.NOT_FOUND, reason(h.writer.set(30000, listOf("p", hex(9)), wanted = false, d = "close", expected = true, explicit = true)))
        assertTrue(h.relays.published.isEmpty())
        // A public entry and a public title still change, the private part byte for byte.
        val a = sent(h.writer.set(30000, listOf("p", hex(2)), wanted = true, d = "close", expected = true, explicit = true))
        assertEquals(GARBAGE, a.content)
        assertEquals(set.tags + listOf(listOf("p", hex(2))), a.tags)
        val titled = h.sign(30000, listOf(listOf("d", "named"), listOf("title", "Old")), GARBAGE)
        h.onRelays(titled)
        val t = sent(h.writer.edit(30000, listOf(ListEdit.set("title", "New")), d = "named", expected = true, explicit = true))
        assertEquals(listOf(listOf("d", "named"), listOf("title", "New")), t.tags)
        assertEquals(GARBAGE, t.content)
    }
}
