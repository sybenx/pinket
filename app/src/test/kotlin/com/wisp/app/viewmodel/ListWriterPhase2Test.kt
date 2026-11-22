package com.wisp.app.viewmodel

import android.app.Application
import com.wisp.app.R
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.ListWrite
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.repo.MemoryOwnListStorage
import com.wisp.app.repo.OwnListStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2 ListWriter capabilities against fake relays, with a real LocalSigner on a throwaway key generated here:
 * sets by d (add, remove, private, rename, create, read-only, hide), relay list markers, reorder, profile fields and
 * app data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ListWriterPhase2Test {
    private val w1 = "wss://w1.example"
    private val w2 = "wss://w2.example"
    private val idx = "wss://idx.example"

    private class FakeRelays : ListSource, ListSink {
        val held = mutableMapOf<String, MutableList<NostrEvent>>()
        var online = mutableSetOf<String>()
        var accepting = mutableSetOf<String>()
        var leaky = false
        val published = mutableListOf<NostrEvent>()

        fun put(url: String, e: NostrEvent) { held.getOrPut(ListWriter.norm(url)) { mutableListOf() }.add(e) }

        fun newestAt(url: String, kind: Int, d: String? = null): NostrEvent? = held[ListWriter.norm(url)].orEmpty()
            .filter { it.kind == kind && (d == null || it.tags.any { t -> t.size >= 2 && t[0] == "d" && t[1] == d }) }
            .maxWithOrNull(compareBy<NostrEvent> { it.created_at }.thenByDescending { it.id })

        override suspend fun fetch(request: ListFetchRequest): ListFetchResult {
            val asked = (request.writeRelays + request.otherRelays).map { ListWriter.norm(it) }.filter { it in online }.toSet()
            val events = asked.flatMap { u ->
                held[u].orEmpty().filter { e ->
                    leaky || e.pubkey == request.author && e.kind == request.kind &&
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

    private class Harness(
        val relays: FakeRelays,
        val signer: LocalSigner,
        val store: OwnListStore,
        val writer: ListWriter,
        val clock: Clock,
        val messages: MutableList<Int>
    ) {
        val me: String get() = signer.pubkeyHex
        var now: Long
            get() = clock.now
            set(v) { clock.now = v }
    }

    private fun harness(start: Long = 1_000_000L): Harness = runBlocking {
        val kp = Keys.generate()
        val signer = LocalSigner(kp.privkey, kp.pubkey)
        val relays = FakeRelays().apply {
            online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(w2), ListWriter.norm(idx))
            accepting = online.toMutableSet()
        }
        val clock = Clock(start, signer.pubkeyHex)
        val store = OwnListStore(signer.pubkeyHex, MemoryOwnListStorage(), clock = { clock.now }, entryId = { ListWriter.entryId(it) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val writer = ListWriter(
            source = relays,
            sink = relays,
            account = { ListAccount(signer, store) },
            activePubkey = { clock.active },
            poolWriteRelays = { listOf(w1, w2) },
            scope = scope,
            clock = { clock.now },
            indexerRelays = listOf(idx),
            work = EmptyCoroutineContext
        )
        val messages = Collections.synchronizedList(mutableListOf<Int>())
        scope.launch { writer.messages.collect { messages += it } }
        val rl = signer.signEvent(10002, "", listOf(listOf("r", w1), listOf("r", "$w2/", "write"), listOf("x-unknown", "keep"), listOf("r", "wss://readonly.example", "read")), start - 5000)
        assertTrue(store.seen(rl))
        Harness(relays, signer, store, writer, clock, messages)
    }

    private fun Harness.sign(kind: Int, tags: List<List<String>>, content: String = "", at: Long = now - 100): NostrEvent =
        runBlocking { signer.signEvent(kind, content, tags, at) }

    private fun Harness.onRelays(e: NostrEvent) { relays.put(w1, e); relays.put(w2, e); relays.put(idx, e) }

    private fun Harness.enc(tags: List<List<String>>) = runBlocking { signer.nip44Encrypt(ListWriter.privateJson(tags), me) }
    private fun Harness.dec(content: String): List<List<String>> = runBlocking { ListWriter.parsePrivate(signer.nip44Decrypt(content, me))!! }

    private fun hex(n: Int) = n.toString(16).padStart(64, '0')

    private fun sent(r: ListResult): NostrEvent = (r as? ListResult.Sent)?.event ?: error("not sent: $r")

    // ------------------------------------------------------------------ sets by d

    @Test
    fun addAndRemoveInOnePeopleSetByDLeaveOtherSetsAndTagsAlone() = runBlocking {
        val h = harness()
        h.relays.leaky = true
        val friends = h.sign(30000, listOf(listOf("d", "friends"), listOf("title", "Friends"), listOf("p", hex(1), "wss://hint.example", "al"), listOf("p", hex(2))))
        val work = h.sign(30000, listOf(listOf("d", "work"), listOf("p", hex(9))), at = h.now - 10)
        h.onRelays(friends); h.onRelays(work)
        val added = sent(h.writer.set(30000, listOf("p", hex(3)), wanted = true, d = "friends", expected = true))
        assertEquals(friends.tags + listOf(listOf("p", hex(3))), added.tags)
        assertEquals(h.now, added.created_at)
        val removed = sent(h.writer.set(30000, listOf("p", hex(1).uppercase()), wanted = false, d = "friends", expected = true))
        assertEquals(listOf(listOf("d", "friends"), listOf("title", "Friends"), listOf("p", hex(2)), listOf("p", hex(3))), removed.tags)
        assertTrue(removed.created_at > added.created_at)
        // The other set was never written.
        assertEquals(work.id, h.relays.newestAt(w1, 30000, "work")!!.id)
        assertTrue(h.relays.published.all { e -> e.tags.first() == listOf("d", "friends") })
    }

    @Test
    fun aSetThatShouldExistButIsGoneIsRefused() = runBlocking {
        val h = harness()
        val r = h.writer.set(30003, listOf("e", hex(1)), wanted = true, d = "reads", expected = true)
        assertEquals(ListResult.Reason.NOT_FOUND, (r as ListResult.Refused).reason)
        assertTrue(h.relays.published.isEmpty())
        assertEquals(listOf(R.string.list_not_found), h.messages.toList())
    }

    @Test
    fun privateEntryGoesInAndOutOfThePrivatePartKeepingTheRest() = runBlocking {
        val h = harness()
        val priv = listOf(listOf("title", "Close"), listOf("p", hex(1)), listOf("x-private", "keep"))
        val set = h.sign(30000, listOf(listOf("d", "close")), h.enc(priv))
        h.onRelays(set)
        val a = sent(h.writer.set(30000, listOf("p", hex(2)), wanted = true, d = "close", place = ListPlace.PRIVATE, expected = true))
        assertEquals(set.tags, a.tags)
        assertEquals(priv + listOf(listOf("p", hex(2))), h.dec(a.content))
        val b = sent(h.writer.set(30000, listOf("p", hex(1)), wanted = false, d = "close", expected = true))
        assertEquals(set.tags, b.tags)
        assertEquals(listOf(listOf("title", "Close"), listOf("x-private", "keep"), listOf("p", hex(2))), h.dec(b.content))
        // The last private entry out leaves an encrypted empty part, never blank.
        val c = sent(h.writer.edit(30000, listOf(ListEdit.remove("p", hex(2)), ListEdit.remove("x-private", "keep"), ListEdit.remove("title", "Close")), d = "close", expected = true))
        assertTrue(c.content.isNotBlank())
        assertEquals(emptyList<List<String>>(), h.dec(c.content))
    }

    @Test
    fun renameReplacesTheTitleWhereItLives() = runBlocking {
        val h = harness()
        val pub = h.sign(30015, listOf(listOf("d", "interests"), listOf("title", "Interests"), listOf("t", "nostr"), listOf("t", "bitcoin")))
        h.onRelays(pub)
        val r = sent(h.writer.edit(30015, listOf(ListEdit.set("title", "Topics")), d = "interests", expected = true))
        assertEquals(listOf(listOf("d", "interests"), listOf("title", "Topics"), listOf("t", "nostr"), listOf("t", "bitcoin")), r.tags)
        // The same name again: nothing written.
        val before = h.relays.published.size
        assertEquals(ListResult.Unchanged, h.writer.edit(30015, listOf(ListEdit.set("title", "Topics")), d = "interests", expected = true))
        assertEquals(before, h.relays.published.size)

        // A private set: the title is inside the private part, and stays there.
        val priv = listOf(listOf("title", "Close"), listOf("p", hex(1)))
        val ps = h.sign(30000, listOf(listOf("d", "close")), h.enc(priv))
        h.onRelays(ps)
        val r2 = sent(h.writer.edit(30000, listOf(ListEdit.set("title", "Closest")), d = "close", place = ListPlace.PRIVATE, expected = true))
        assertEquals(ps.tags, r2.tags)
        assertEquals(listOf(listOf("title", "Closest"), listOf("p", hex(1))), h.dec(r2.content))
    }

    @Test
    fun createSetWritesDAndTitleAndRecordsMadeHere() = runBlocking {
        val h = harness()
        val e = sent(h.writer.createSet(30000, "family", "Family"))
        assertEquals(listOf(listOf("d", "family"), listOf("title", "Family")), e.tags)
        assertEquals("", e.content)
        assertTrue(h.store.madeHere("30000:family"))
        assertFalse(h.store.madeHerePrivate("30000:family"))
        // Entries go in afterwards.
        val a = sent(h.writer.set(30000, listOf("p", hex(1)), wanted = true, d = "family", expected = true))
        assertEquals(e.tags + listOf(listOf("p", hex(1))), a.tags)
    }

    @Test
    fun createPrivateSetHidesTheTitleAndTakesPrivateEntries() = runBlocking {
        val h = harness()
        val e = sent(h.writer.createSet(30003, "later", "Read later", private = true))
        assertEquals(listOf(listOf("d", "later")), e.tags)
        assertEquals(listOf(listOf("title", "Read later")), h.dec(e.content))
        assertTrue(h.store.madeHerePrivate("30003:later"))
        val a = sent(h.writer.set(30003, listOf("e", hex(5)), wanted = true, d = "later", place = ListPlace.PRIVATE, expected = true))
        assertEquals(listOf(listOf("d", "later")), a.tags)
        assertEquals(listOf(listOf("title", "Read later"), listOf("e", hex(5))), h.dec(a.content))
    }

    @Test
    fun createSetOnATakenNameIsRefusedUnlessItWasHiddenHere() = runBlocking {
        val h = harness()
        val existing = h.sign(30000, listOf(listOf("d", "friends"), listOf("title", "Friends"), listOf("p", hex(1))))
        h.onRelays(existing)
        val r = h.writer.createSet(30000, "friends", "Friends")
        assertEquals(ListResult.Reason.NAME_TAKEN, (r as ListResult.Refused).reason)
        assertEquals(listOf(R.string.list_name_taken), h.messages.toList())
        // Hidden here: creating it again shows it again, nothing is written.
        h.writer.hideSet(30000, "friends")
        assertTrue(h.writer.isHidden(30000, "friends"))
        assertEquals(ListResult.Unchanged, h.writer.createSet(30000, "friends", "Friends"))
        assertFalse(h.store.isHidden(30000, "friends"))
        assertFalse("30000:friends" in h.writer.hiddenSets.value)
        assertTrue(h.relays.published.isEmpty())
    }

    @Test
    fun createSetWithoutProofThatNoneExistsIsRefused() = runBlocking {
        val h = harness()
        h.relays.online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(idx))
        val r = h.writer.createSet(30000, "family", "Family")
        assertTrue(r is ListResult.Refused)
        h.relays.online.clear()
        val r2 = h.writer.createSet(30000, "family", "Family")
        assertEquals(ListResult.Reason.NOT_SENT, (r2 as ListResult.Refused).reason)
        assertTrue(h.relays.published.isEmpty())
    }

    @Test
    fun hidingASetPublishesNothingAndStaysHidden() = runBlocking {
        val h = harness()
        h.writer.hideSet(30003, "old-reads")
        assertTrue(h.relays.published.isEmpty())
        assertEquals(setOf("30003:old-reads"), h.writer.hiddenSets.value)
        assertTrue(h.store.isHidden(30003, "old-reads"))
        h.store.clear()
        h.writer.refreshOverlay()
        assertEquals(setOf("30003:old-reads"), h.writer.hiddenSets.value)
    }

    @Test
    fun anotherAppsSetIsReadOnlyUnlessTheUserEditsThatSet() = runBlocking {
        val h = harness()
        val foreign = h.sign(30000, listOf(listOf("d", "Other App Folks"), listOf("title", "Folks"), listOf("p", hex(1))))
        h.onRelays(foreign)
        assertFalse(h.writer.isEditableSet(foreign))
        val r = h.writer.set(30000, listOf("p", hex(2)), wanted = true, d = "Other App Folks", expected = true)
        assertEquals(ListResult.Reason.READ_ONLY, (r as ListResult.Refused).reason)
        assertEquals(listOf(R.string.list_read_only), h.messages.toList())
        assertTrue(h.relays.published.isEmpty())
        val ok = sent(h.writer.set(30000, listOf("p", hex(2)), wanted = true, d = "Other App Folks", expected = true, explicit = true))
        assertEquals(foreign.tags + listOf(listOf("p", hex(2))), ok.tags)
        // This app's own kind of set (slug d, only its tags) is editable without the explicit flag.
        assertTrue(h.writer.isEditableSet(h.sign(30015, listOf(listOf("d", "my-topics"), listOf("t", "x")))))
        assertFalse(h.writer.isEditableSet(h.sign(30015, listOf(listOf("d", "my-topics"), listOf("t", "x"), listOf("client", "other")))))
    }

    @Test
    fun groupsJoinTheSameIdOnASecondRelayAndLeaveOnlyOne() = runBlocking {
        val h = harness()
        val g = h.sign(10009, listOf(listOf("group", "_", "wss://a.example"), listOf("r", "wss://a.example")))
        h.onRelays(g)
        val j = sent(h.writer.edit(10009, listOf(ListEdit.add("group", "_", "wss://b.example"), ListEdit.add("r", "wss://b.example")), expected = true))
        assertEquals(g.tags + listOf(listOf("group", "_", "wss://b.example"), listOf("r", "wss://b.example")), j.tags)
        val l = sent(h.writer.set(10009, listOf("group", "_", "wss://a.example/"), wanted = false, expected = true))
        assertEquals(listOf(listOf("r", "wss://a.example"), listOf("group", "_", "wss://b.example"), listOf("r", "wss://b.example")), l.tags)
    }

    // ------------------------------------------------------------------ relay list markers

    @Test
    fun relayMarkerChangeIsOneEntryInPlace() = runBlocking {
        val h = harness()
        val old = h.store.latest(10002)!!
        val r = sent(h.writer.edit(10002, listOf(ListEdit.set("r", w2, "read")), expected = true))
        assertEquals(listOf(listOf("r", w1), listOf("r", w2, "read"), listOf("x-unknown", "keep"), listOf("r", "wss://readonly.example", "read")), r.tags)
        assertEquals(h.now, r.created_at)
        assertTrue(r.created_at > old.created_at)
        // The new list also goes to every relay in it.
        assertEquals(listOf(w1, w2, "wss://readonly.example"), ListWriter.extraPublishRelays(r))
    }

    @Test
    fun relayEditorBroadcastReplaysAddsRemovesAndMarkersInOneEvent() = runBlocking {
        val h = harness()
        val r = sent(h.writer.edit(10002, listOf(
            ListEdit.add("r", "wss://new.example"),
            ListEdit.remove("r", "wss://READONLY.example/"),
            ListEdit.set("r", w1, "write")
        ), expected = true))
        assertEquals(listOf(listOf("r", w1, "write"), listOf("r", "$w2/", "write"), listOf("x-unknown", "keep"), listOf("r", "wss://new.example")), r.tags)
    }

    @Test
    fun aMarkerChangeKeptOfflineIsReplayedLater() = runBlocking {
        val h = harness()
        h.relays.online.clear()
        assertEquals(ListResult.Pending, h.writer.edit(10002, listOf(ListEdit.set("r", w1, "read")), expected = true))
        assertTrue(h.writer.pendingChanges(10002).single().replace)
        h.relays.online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(w2), ListWriter.norm(idx))
        h.writer.resumeNow()
        assertTrue(h.writer.pendingChanges(10002).isEmpty())
        assertEquals(listOf("r", w1, "read"), h.store.latest(10002)!!.tags.first())
    }

    // ------------------------------------------------------------------ reorder

    @Test
    fun blossomReorderKeepsEveryTagAndUnknownOnesInPlace() = runBlocking {
        val h = harness()
        val tags = listOf(listOf("server", "https://one.example"), listOf("x-unknown", "z"), listOf("server", "https://two.example"))
        val old = h.sign(10063, tags)
        h.onRelays(old)
        val r = sent(h.writer.reorder(10063, listOf(listOf("server", "https://two.example"), listOf("server", "https://one.example")), expected = true))
        assertEquals(listOf(tags[2], tags[1], tags[0]), r.tags)
        assertEquals(ListResult.Unchanged, h.writer.reorder(10063, listOf(listOf("server", "https://two.example"), listOf("server", "https://one.example")), expected = true))
    }

    // ------------------------------------------------------------------ profile

    @Test
    fun profileChangesOnlyTheEditedFieldsWithASlowClock() = runBlocking {
        val h = harness()
        val content = """{"name":"alice","about":"hi","x_unknown":{"a":[1,2]},"lud16":"a@b.example"}"""
        val old = h.sign(0, listOf(listOf("i", "github:alice", "proof")), content, at = h.now + 300)
        h.onRelays(old)
        val r = sent(h.writer.setProfileText(mapOf("about" to "hello", "lud16" to ""), expected = true))
        assertEquals(old.created_at + 1, r.created_at)
        assertEquals(old.tags, r.tags)
        val n = ListWrite.profileFields(r.content)!!
        assertEquals(listOf("name", "about", "x_unknown"), n.keys.toList())
        assertEquals(JsonPrimitive("hello"), n["about"])
        assertEquals(ListWrite.profileFields(content)!!["x_unknown"], n["x_unknown"])
    }

    @Test
    fun profileExpectedButMissingOrUnreadableIsRefused() = runBlocking {
        val h = harness()
        val r = h.writer.setProfileText(mapOf("name" to "x"), expected = true)
        assertEquals(ListResult.Reason.NOT_FOUND, (r as ListResult.Refused).reason)
        h.onRelays(h.sign(0, emptyList(), "not json"))
        val r2 = h.writer.setProfileText(mapOf("name" to "x"))
        assertEquals(ListResult.Reason.NOT_FOUND, (r2 as ListResult.Refused).reason)
        assertTrue(h.relays.published.isEmpty())
    }

    @Test
    fun onboardingProfileWritesOnlyWhenNoneExists() = runBlocking {
        val h = harness()
        val first = sent(h.writer.setProfileText(mapOf("name" to "bob"), onlyIfMissing = true))
        assertEquals("""{"name":"bob"}""", first.content)
        assertEquals(ListResult.Unchanged, h.writer.setProfileText(mapOf("name" to "other"), onlyIfMissing = true))
        assertEquals(1, h.relays.published.size)
    }

    @Test
    fun profileOfflineIsNotSent() = runBlocking {
        val h = harness()
        h.relays.online.clear()
        val r = h.writer.setProfileText(mapOf("name" to "x"))
        assertEquals(ListResult.Reason.NOT_SENT, (r as ListResult.Refused).reason)
        assertEquals(listOf(R.string.list_not_sent), h.messages.toList())
    }

    // ------------------------------------------------------------------ app data

    @Test
    fun appDataStartsFromTheNewestAndKeepsItsTags() = runBlocking {
        val h = harness()
        val tags = listOf(listOf("d", "nwc-wallet-backup"), listOf("client", "Other"), listOf("encryption", "nip44"))
        val older = h.sign(30078, tags, "OLD1", at = h.now - 500)
        val newer = h.sign(30078, tags, "OLD2", at = h.now - 50)
        h.relays.put(w1, older); h.relays.put(w2, newer)
        var seen: NostrEvent? = null
        val r = sent(h.writer.setAppData("nwc-wallet-backup", listOf(listOf("client", "Pinket"))) { old -> seen = old; "NEW" })
        assertEquals(newer.id, seen?.id)
        assertEquals(tags, r.tags)
        assertEquals("NEW", r.content)
        // The newest already holds it: nothing written.
        val n = h.relays.published.size
        assertEquals(ListResult.Unchanged, h.writer.setAppData("nwc-wallet-backup", quiet = true) { old -> if (old?.content == "NEW") null else "NEW" })
        assertEquals(n, h.relays.published.size)
    }

    @Test
    fun aFirstAppDataVersionIsDThenFirstTags() = runBlocking {
        val h = harness()
        val r = sent(h.writer.setAppData("nwc-wallet-backup", listOf(listOf("client", "Pinket"), listOf("encryption", "nip44"))) { "C" })
        assertEquals(listOf(listOf("d", "nwc-wallet-backup"), listOf("client", "Pinket"), listOf("encryption", "nip44")), r.tags)
        assertNull(h.store.unconfirmed("30078:nwc-wallet-backup"))
    }
}
