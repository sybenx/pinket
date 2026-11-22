package com.wisp.app.viewmodel

import android.app.Application
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.relay.RelayConfig
import com.wisp.app.repo.MemoryOwnListStorage
import com.wisp.app.repo.OwnListStore
import com.wisp.app.repo.PendingChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The editors that save many rows at once (relay editor, media servers) and the group list: what they hand the
 * writer is the per-entry difference between where they started and what the user ended with, applied to the newest
 * version found (never a whole-list replace). Fake relays, a real LocalSigner on a key generated here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RelayListEditsTest {
    private val w1 = "wss://w1.example"
    private val w2 = "wss://w2.example"
    private val idx = "wss://idx.example"

    // ------------------------------------------------------------------ pure diffs

    @Test
    fun relayEditorDiffIsOneChangePerRowTouched() {
        val start = listOf(RelayConfig("wss://a.example"), RelayConfig("wss://b.example", read = true, write = false), RelayConfig("wss://c.example"))
        val end = listOf(
            RelayConfig("wss://a.example", auth = false),          // only the local sign-in switch: not in the list
            RelayConfig("wss://B.example/", read = false, write = true), // marker change, other spelling
            RelayConfig("wss://d.example", read = true, write = false)   // new, read only
        )
        assertEquals(
            listOf(
                ListEdit.remove("r", "wss://c.example"),
                ListEdit.set("r", "wss://B.example/", "write"),
                ListEdit.set("r", "wss://d.example", "read")
            ),
            RelayListEdits.relayList(start, end)
        )
        assertTrue(RelayListEdits.relayList(start, start).isEmpty())
    }

    @Test
    fun addressDiffRemovesUnderTheTagNameTheListUses() {
        val startTags = listOf(listOf("relay", "wss://a.example"), listOf("r", "wss://b.example/"), listOf("x", "keep"))
        val start = RelayListEdits.addressesOf(10050, startTags)
        assertEquals(listOf("wss://a.example", "wss://b.example"), start)
        val edits = RelayListEdits.addressList("relay", start, listOf("wss://A.example/", "wss://c.example"), startTags)
        assertEquals(listOf(ListEdit.remove("r", "wss://b.example"), ListEdit.add("relay", "wss://c.example")), edits)
        assertTrue(RelayListEdits.addressList("server", listOf("https://m.example/"), listOf("https://m.example")).isEmpty())
    }

    @Test
    fun hiddenRowsAreNeverTouched() {
        // An address the app's filter does not show (a port) is in neither side, so it is never removed.
        val tags = listOf(listOf("r", "wss://a.example"), listOf("r", "wss://host.example:4848"))
        val shown = RelayListEdits.relaysOf(tags)
        assertEquals(listOf("wss://a.example"), shown.map { it.url })
        assertTrue(RelayListEdits.relayList(shown, shown).isEmpty())
    }

    @Test
    fun pendingChangesAreLaidOverInOrder() {
        val tags = listOf(listOf("group", "_", "wss://a.example"), listOf("r", "wss://a.example"), listOf("group", "x", "wss://b.example"))
        val pending = listOf(
            PendingChange(10009, entry = listOf("group", "_", "wss://a.example/"), wanted = false),
            PendingChange(10009, entry = listOf("group", "y", "wss://c.example"), wanted = true),
            PendingChange(10009, entry = listOf("r", "wss://a.example", "read"), wanted = true, replace = true)
        )
        assertEquals(
            listOf(listOf("r", "wss://a.example", "read"), listOf("group", "x", "wss://b.example"), listOf("group", "y", "wss://c.example")),
            RelayListEdits.withPending(tags, pending)
        )
        // The projection: a group you left (still waiting to be written) is not shown again.
        val store = OwnListStore("ab".repeat(32), MemoryOwnListStorage(), entryId = { ListWriter.entryId(it) })
        store.addPending(pending[0])
        val e = NostrEvent("id", "ab".repeat(32), 1, Nip51.KIND_SIMPLE_GROUPS, tags, "", "")
        assertEquals(listOf("x"), Nip51.parseSimpleGroups(RelayListEdits.overlaid(e, store)).map { it.groupId })
    }

    // ------------------------------------------------------------------ through the writer

    private class FakeRelays : ListSource, ListSink {
        val held = mutableMapOf<String, MutableList<NostrEvent>>()
        var online = mutableSetOf<String>()

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
            val sent = (writeRelays + otherRelays).map { ListWriter.norm(it) }.filter { it in online }.toSet()
            for (u in sent) put(u, event)
            return ListPublishResult(sent, sent)
        }
    }

    private class Harness(val relays: FakeRelays, val signer: LocalSigner, val store: OwnListStore, val writer: ListWriter, var now: Long) {
        fun sign(kind: Int, tags: List<List<String>>, at: Long): NostrEvent = runBlocking { signer.signEvent(kind, "", tags, at) }
        fun everywhere(e: NostrEvent) { relays.put(w1, e); relays.put(w2, e); relays.put(idx, e) }
        private val w1 = "wss://w1.example"
        private val w2 = "wss://w2.example"
        private val idx = "wss://idx.example"
    }

    private fun harness(): Harness {
        val kp = Keys.generate()
        val signer = LocalSigner(kp.privkey, kp.pubkey)
        val relays = FakeRelays().apply { online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(w2), ListWriter.norm(idx)) }
        var h: Harness? = null
        val store = OwnListStore(signer.pubkeyHex, MemoryOwnListStorage(), clock = { h?.now ?: 1_000_000L }, entryId = { ListWriter.entryId(it) })
        val writer = ListWriter(
            source = relays,
            sink = relays,
            account = { ListAccount(signer, store) },
            activePubkey = { signer.pubkeyHex },
            poolWriteRelays = { listOf(w1, w2) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            clock = { h?.now ?: 1_000_000L },
            indexerRelays = listOf(idx),
            work = EmptyCoroutineContext
        )
        val made = Harness(relays, signer, store, writer, 1_000_000L)
        h = made
        return made
    }

    private fun sent(r: ListResult): NostrEvent = (r as? ListResult.Sent)?.event ?: error("not sent: $r")

    @Test
    fun relayEditorBroadcastKeepsWhatAnotherDeviceAddedMeanwhile() = runBlocking {
        val h = harness()
        // What this device knows (and the editor started from).
        val known = h.sign(10002, listOf(listOf("r", w1), listOf("r", w2), listOf("r", "wss://old.example", "read"), listOf("x-unknown", "keep")), h.now - 500)
        assertTrue(h.store.seen(known))
        // Meanwhile another app added a relay (a port the editor never shows) and one more.
        val newer = h.sign(10002, known.tags + listOf(listOf("r", "wss://host.example:4848"), listOf("r", "wss://other.example")), h.now - 100)
        h.everywhere(newer)

        val start = RelayListEdits.relaysOf(RelayListEdits.startTags(h.store, 10002))
        val end = start.filter { it.url != "wss://old.example" }
            .map { if (it.url == w2) it.copy(read = false) else it } + RelayConfig("wss://new.example")
        val edits = RelayListEdits.relayList(start, end)
        val r = sent(h.writer.edit(10002, edits, expected = h.store.everSeen(10002)))
        assertEquals(
            listOf(listOf("r", w1), listOf("r", w2, "write"), listOf("x-unknown", "keep"),
                listOf("r", "wss://host.example:4848"), listOf("r", "wss://other.example"), listOf("r", "wss://new.example")),
            r.tags
        )
        assertEquals(h.now, r.created_at)
        // Nothing changed in the editor: nothing to write.
        val again = RelayListEdits.relaysOf(RelayListEdits.startTags(h.store, 10002))
        assertTrue(RelayListEdits.relayList(again, again).isEmpty())
    }

    @Test
    fun dmRelayRemovalKeptOfflineStaysOutOfWhatTheEditorStartsFrom() = runBlocking {
        val h = harness()
        val rl = h.sign(10002, listOf(listOf("r", w1), listOf("r", w2)), h.now - 900)
        assertTrue(h.store.seen(rl))
        val dm = h.sign(10050, listOf(listOf("relay", "wss://dm1.example"), listOf("relay", "wss://dm2.example")), h.now - 500)
        assertTrue(h.store.seen(dm))
        h.everywhere(dm)
        h.relays.online.clear()
        val start = RelayListEdits.addressesOf(10050, RelayListEdits.startTags(h.store, 10050))
        val edits = RelayListEdits.addressList("relay", start, listOf("wss://dm1.example"), RelayListEdits.startTags(h.store, 10050))
        assertEquals(ListResult.Pending, h.writer.edit(10050, edits, expected = true))
        // The kept change is part of where the editor starts: saving again writes nothing new.
        assertEquals(listOf("wss://dm1.example"), RelayListEdits.addressesOf(10050, RelayListEdits.startTags(h.store, 10050)))
        h.relays.online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(w2), ListWriter.norm(idx))
        h.writer.resumeNow()
        assertEquals(listOf(listOf("relay", "wss://dm1.example")), h.store.latest(10050)!!.tags)
    }

    @Test
    fun mediaServersAddRemoveThenOrderKeepUnknownTags() = runBlocking {
        val h = harness()
        val rl = h.sign(10002, listOf(listOf("r", w1), listOf("r", w2)), h.now - 900)
        assertTrue(h.store.seen(rl))
        val old = h.sign(10063, listOf(listOf("server", "https://a.example/"), listOf("x", "keep"), listOf("server", "https://b.example"), listOf("server", "https://c.example")), h.now - 500)
        assertTrue(h.store.seen(old)); h.everywhere(old)
        val startTags = RelayListEdits.startTags(h.store, 10063)
        val start = startTags.filter { it[0] == "server" }.map { it[1] }
        val shown = listOf("https://c.example", "https://d.example", "https://a.example")
        val edits = RelayListEdits.addressList("server", start, shown, startTags)
        assertEquals(listOf(ListEdit.remove("server", "https://b.example"), ListEdit.add("server", "https://d.example")), edits)
        sent(h.writer.edit(10063, edits, expected = true))
        h.now += 1
        val ordered = sent(h.writer.reorder(10063, shown.map { listOf("server", it) }, expected = true))
        assertEquals(
            listOf(listOf("server", "https://c.example"), listOf("x", "keep"), listOf("server", "https://d.example"), listOf("server", "https://a.example/")),
            ordered.tags
        )
    }

    @Test
    fun joiningAGroupAddsItsRelayOnlyWhenMissingAndLeavingKeepsTheRest() = runBlocking {
        val h = harness()
        val rl = h.sign(10002, listOf(listOf("r", w1), listOf("r", w2)), h.now - 900)
        assertTrue(h.store.seen(rl))
        val g = h.sign(10009, listOf(listOf("group", "_", "wss://a.example", "Lobby"), listOf("r", "wss://a.example"), listOf("x", "keep")), h.now - 500)
        h.everywhere(g)
        // Join a second group on the same relay: the group goes in, no second `r`.
        val joined = sent(h.writer.edit(10009, listOf(ListEdit.add("group", "z", "wss://a.example", "Zed"), ListEdit.add("r", "wss://a.example"))))
        assertEquals(g.tags + listOf(listOf("group", "z", "wss://a.example", "Zed")), joined.tags)
        h.now += 1
        // Leave the first: only its group tag goes, the relay stays.
        val left = sent(h.writer.edit(10009, listOf(ListEdit.remove("group", "_", "wss://a.example/"))))
        assertEquals(listOf(listOf("r", "wss://a.example"), listOf("x", "keep"), listOf("group", "z", "wss://a.example", "Zed")), left.tags)
    }

    @Test
    fun favouriteRelayAndRelaySetChangeOneEntry() = runBlocking {
        val h = harness()
        val rl = h.sign(10002, listOf(listOf("r", w1), listOf("r", w2)), h.now - 900)
        assertTrue(h.store.seen(rl))
        val fav = h.sign(10012, listOf(listOf("relay", "wss://f.example"), listOf("x", "keep")), h.now - 500)
        h.everywhere(fav)
        val f = sent(h.writer.set(10012, listOf("relay", "wss://g.example"), wanted = true))
        assertEquals(fav.tags + listOf(listOf("relay", "wss://g.example")), f.tags)
        val set = h.sign(30002, listOf(listOf("d", "fast"), listOf("title", "Fast"), listOf("relay", "wss://f.example")), h.now - 400)
        h.everywhere(set)
        val s = sent(h.writer.edit(30002, listOf(ListEdit.remove("relay", "wss://F.example/")), d = "fast", expected = true, explicit = true))
        assertEquals(listOf(listOf("d", "fast"), listOf("title", "Fast")), s.tags)
    }
}
