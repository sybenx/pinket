package com.wisp.app.viewmodel

import android.app.Application
import com.wisp.app.R
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.repo.MemoryOwnListStorage
import com.wisp.app.repo.OwnListStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
 * ListWriter against fake relays, with a real LocalSigner on a throwaway key generated here.
 * Robolectric only for android.util.Base64 inside NIP-44.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ListWriterTest {
    private val w1 = "wss://w1.example"
    private val w2 = "wss://w2.example"
    private val idx = "wss://idx.example"

    /** Relays in memory: each keeps the events it was given; [online] answer and take events; [accepting] say OK. */
    private class FakeRelays : ListSource, ListSink {
        val held = mutableMapOf<String, MutableList<NostrEvent>>()
        var online = mutableSetOf<String>()
        var accepting = mutableSetOf<String>()
        var fetchDelayMs = 0L
        var onFetch: () -> Unit = {}
        /** Also return what relays hold for other authors, kinds and sets (the writer must filter them out). */
        var leaky = false
        var fetches = 0
        val published = mutableListOf<NostrEvent>()

        fun put(url: String, e: NostrEvent) { held.getOrPut(ListWriter.norm(url)) { mutableListOf() }.add(e) }

        fun newestAt(url: String, kind: Int): NostrEvent? = held[ListWriter.norm(url)].orEmpty().filter { it.kind == kind }
            .maxWithOrNull(compareBy<NostrEvent> { it.created_at }.thenByDescending { it.id })

        override suspend fun fetch(request: ListFetchRequest): ListFetchResult {
            fetches++
            if (fetchDelayMs > 0) delay(fetchDelayMs)
            onFetch()
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

    private class Clock(var now: Long, var active: String? = null)

    private class Harness(
        val relays: FakeRelays,
        val signer: LocalSigner,
        val store: OwnListStore,
        val writer: ListWriter,
        val clock: Clock,
        /** Toasts emitted (string ids), in order. */
        val messages: MutableList<Int>,
        /** What [ListWriter.needsConfirm] asked for. */
        val confirms: MutableList<List<List<String>>>
    ) {
        val me: String get() = signer.pubkeyHex
        var now: Long
            get() = clock.now
            set(v) { clock.now = v }
        var active: String?
            get() = clock.active
            set(v) { clock.active = v }
    }

    private fun harness(
        legacy: Map<String, Long> = emptyMap(),
        withRelayList: Boolean = true,
        newAccount: Boolean = false,
        start: Long = 1_000_000L,
        /** The signer the writer gets for the account (the store and [Harness.signer] stay the account's own). */
        wrap: (LocalSigner) -> NostrSigner = { it }
    ): Harness = runBlocking {
        val kp = Keys.generate()
        val signer = LocalSigner(kp.privkey, kp.pubkey)
        val relays = FakeRelays().apply {
            online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(w2), ListWriter.norm(idx))
            accepting = online.toMutableSet()
        }
        val clock = Clock(start, signer.pubkeyHex)
        val store = OwnListStore(signer.pubkeyHex, MemoryOwnListStorage(), legacy, clock = { clock.now }, entryId = { ListWriter.entryId(it) })
        val acctSigner = wrap(signer)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val writer = ListWriter(
            source = relays,
            sink = relays,
            account = { ListAccount(acctSigner, store) },
            activePubkey = { clock.active },
            poolWriteRelays = { listOf(w1, w2) },
            scope = scope,
            clock = { clock.now },
            indexerRelays = listOf(idx),
            work = EmptyCoroutineContext
        )
        val messages = Collections.synchronizedList(mutableListOf<Int>())
        val confirms = Collections.synchronizedList(mutableListOf<List<List<String>>>())
        // Before any change: the flows do not replay.
        scope.launch { writer.messages.collect { messages += it } }
        scope.launch { writer.needsConfirm.collect { confirms += it } }
        val h = Harness(relays, signer, store, writer, clock, messages, confirms)
        if (newAccount) store.markNewAccount()
        if (withRelayList) {
            val rl = signer.signEvent(10002, "", listOf(listOf("r", w1), listOf("r", "$w2/", "write"), listOf("r", "wss://readonly.example", "read")), maxOf(1L, start - 5000))
            assertTrue(store.seen(rl))
        }
        h
    }

    private fun Harness.sign(kind: Int, tags: List<List<String>>, content: String = "", at: Long = now - 100): NostrEvent =
        runBlocking { signer.signEvent(kind, content, tags, at) }

    /** Put a version on both write relays (and optionally the indexer). */
    private fun Harness.onRelays(e: NostrEvent, indexer: Boolean = true) {
        relays.put(w1, e); relays.put(w2, e); if (indexer) relays.put(idx, e)
    }

    private fun Harness.enc44(tags: List<List<String>>) = runBlocking { signer.nip44Encrypt(ListWriter.privateJson(tags), me) }
    private fun Harness.dec(content: String): List<List<String>> = runBlocking {
        val plain = if (content.contains("?iv=")) signer.nip04Decrypt(content, me) else signer.nip44Decrypt(content, me)
        ListWriter.parsePrivate(plain)!!
    }

    private fun hex(n: Int) = n.toString(16).padStart(64, '0')

    // ------------------------------------------------------------------ the plan's required cases

    @Test
    fun bookmarkTapKeepsAnEightKilobyteEncryptedContentByteForByte() = runBlocking {
        val h = harness()
        val priv = (1..100).map { listOf("e", hex(it)) }
        val content = h.enc44(priv)
        assertTrue(content.length >= 8000)
        val old = h.sign(10003, listOf(listOf("e", hex(900), "wss://hint.example"), listOf("r", "https://x.example")), content)
        h.onRelays(old)
        val r = h.writer.set(10003, listOf("e", hex(901)), wanted = true)
        assertTrue(r is ListResult.Sent)
        val e = (r as ListResult.Sent).event
        assertEquals(content, e.content)
        assertEquals(old.tags + listOf(listOf("e", hex(901))), e.tags)
        assertEquals(e.id, h.relays.newestAt(w1, 10003)!!.id)
    }

    @Test
    fun unbookmarkTakesOutOnlyThatNoteAndKeepsEveryOtherTagInPlace() = runBlocking {
        val h = harness()
        val content = h.enc44(listOf(listOf("e", hex(7)), listOf("r", "https://private.example")))
        val tags = listOf(
            listOf("t", "nostr"), listOf("e", hex(1), "wss://hint.example"), listOf("a", "30023:${hex(9)}:post"),
            listOf("r", "https://x.example"), listOf("x-unknown", "a", "b"), listOf("e", hex(2))
        )
        h.onRelays(h.sign(10003, tags, content))
        // The tap carries a different relay hint than the stored tag: the entry is the note id alone.
        val r = h.writer.set(10003, listOf("e", hex(1), "wss://other.example"), wanted = false) as ListResult.Sent
        assertEquals(content, r.event.content)
        assertEquals(tags.filterNot { it[0] == "e" && it[1] == hex(1) }, r.event.tags)
    }

    @Test
    fun bookmarkWithRelayHintAppendsAPublicTagAndARepeatIsUnchanged() = runBlocking {
        val h = harness()
        val tags = listOf(listOf("e", hex(1)), listOf("t", "nostr"))
        h.onRelays(h.sign(10003, tags, ""))
        val r = h.writer.set(10003, listOf("e", hex(5), "wss://hint.example"), wanted = true) as ListResult.Sent
        assertEquals(tags + listOf(listOf("e", hex(5), "wss://hint.example")), r.event.tags)
        assertEquals("", r.event.content)
        // Already in (any hint): nothing written.
        assertEquals(ListResult.Unchanged, h.writer.set(10003, listOf("e", hex(5)), wanted = true))
        assertEquals(ListResult.Unchanged, h.writer.set(10003, listOf("e", hex(1), "wss://x.example"), wanted = true))
    }

    @Test
    fun muteKeepsPublicTagsAndOtherPrivateEntries() = runBlocking {
        val h = harness()
        val pub = listOf(listOf("p", hex(1)), listOf("t", "spam"), listOf("x-unknown", "1", "2"))
        val priv = listOf(listOf("p", hex(2)), listOf("t", "other"), listOf("e", hex(3)), listOf("zz", "q"), listOf("word", "Hello"))
        val old = h.sign(10000, pub, h.enc44(priv))
        h.onRelays(old)
        val r = h.writer.set(10000, listOf("p", hex(4)), wanted = true, place = ListPlace.PRIVATE_OR_PUBLIC) as ListResult.Sent
        assertEquals(pub, r.event.tags)
        assertEquals(priv + listOf(listOf("p", hex(4))), h.dec(r.event.content))
    }

    @Test
    fun unmuteRemovesFromBothParts() = runBlocking {
        val h = harness()
        val pub = listOf(listOf("p", hex(1)), listOf("p", hex(9)), listOf("p", hex(1).uppercase()))
        val priv = listOf(listOf("p", hex(1)), listOf("p", hex(8)))
        h.onRelays(h.sign(10000, pub, h.enc44(priv)))
        val r = h.writer.set(10000, listOf("p", hex(1)), wanted = false) as ListResult.Sent
        assertEquals(listOf(listOf("p", hex(9))), r.event.tags)
        assertEquals(listOf(listOf("p", hex(8))), h.dec(r.event.content))
    }

    @Test
    fun mutedWordRemovedByAnySpelling() = runBlocking {
        val h = harness()
        val priv = listOf(listOf("word", "Bitcoin"), listOf("word", "keep"))
        h.onRelays(h.sign(10000, emptyList(), h.enc44(priv)))
        val r = h.writer.set(10000, listOf("word", "bitcoin"), wanted = false) as ListResult.Sent
        assertEquals(listOf(listOf("word", "keep")), h.dec(r.event.content))
    }

    @Test
    fun nip04ContentStaysNip04() = runBlocking {
        val h = harness()
        val priv = listOf(listOf("p", hex(2)))
        val content = h.signer.nip04Encrypt(ListWriter.privateJson(priv), h.me)
        h.onRelays(h.sign(10000, emptyList(), content))
        val r = h.writer.set(10000, listOf("p", hex(3)), wanted = true, place = ListPlace.PRIVATE_OR_PUBLIC) as ListResult.Sent
        assertTrue(r.event.content.contains("?iv="))
        assertEquals(priv + listOf(listOf("p", hex(3))), h.dec(r.event.content))
    }

    @Test
    fun opaqueContentRefusesPrivateChangesAndMuteGoesPublicWithContentUnchanged() = runBlocking {
        val h = harness()
        val garbage = "bm90IGEgdmFsaWQgbmlwNDQgcGF5bG9hZA=="
        val old = h.sign(10000, listOf(listOf("p", hex(1))), garbage)
        h.onRelays(old)
        // Private only: refused, nothing sent.
        val refused = h.writer.set(10000, listOf("p", hex(5)), wanted = true, place = ListPlace.PRIVATE)
        assertEquals(ListResult.Reason.NOT_FOUND, (refused as ListResult.Refused).reason)
        assertTrue(h.relays.published.isEmpty())
        // Unmuting something that may only be in the unreadable part: refused.
        val unmute = h.writer.set(10000, listOf("p", hex(7)), wanted = false)
        assertTrue(unmute is ListResult.Refused)
        assertTrue(h.relays.published.isEmpty())
        // A mute: a public tag, the content byte for byte.
        val r = h.writer.set(10000, listOf("p", hex(6)), wanted = true, place = ListPlace.PRIVATE_OR_PUBLIC) as ListResult.Sent
        assertEquals(garbage, r.event.content)
        assertEquals(old.tags + listOf(listOf("p", hex(6))), r.event.tags)
        assertTrue(h.store.pending("10000").isEmpty())
    }

    @Test
    fun followKeepsUnknownTagsHintsPetnamesAndContent() = runBlocking {
        val h = harness()
        val tags = listOf(
            listOf("p", hex(1), "wss://hint.example", "alice"),
            listOf("t", "nostr"),
            listOf("a", "34550:${hex(2)}:community"),
            listOf("p", hex(3), "", "bob"),
            listOf("whatever")
        )
        val content = """{"wss://relay.example":{"read":true,"write":true}}"""
        h.onRelays(h.sign(3, tags, content))
        val r = h.writer.set(3, listOf("p", hex(4)), wanted = true) as ListResult.Sent
        assertEquals(tags + listOf(listOf("p", hex(4))), r.event.tags)
        assertEquals(content, r.event.content)
        val un = h.writer.set(3, listOf("p", hex(1)), wanted = false) as ListResult.Sent
        assertEquals(tags.drop(1) + listOf(listOf("p", hex(4))), un.event.tags)
        assertEquals(content, un.event.content)
    }

    @Test
    fun refusesWhenAListIsExpectedButNoneIsFound() = runBlocking {
        val h = harness(legacy = mapOf("10003" to 500_000L))
        val r = h.writer.set(10003, listOf("e", hex(1)), wanted = true)
        assertEquals(ListResult.Reason.NOT_FOUND, (r as ListResult.Refused).reason)
        assertTrue(h.relays.published.isEmpty())
        assertTrue(h.store.pending("10003").isEmpty())
        // The toast, and the button flips back.
        assertEquals(listOf(R.string.list_not_found), h.messages.toList())
        assertTrue(h.writer.overlay.value.isEmpty())
    }

    @Test
    fun anOlderVersionThanThisDeviceHadWaitsForProofThenBuildsOnTheRelayCopy() = runBlocking {
        // The old code showed a version from 999_950 that never reached a relay; relays have 999_000.
        val h = harness(legacy = mapOf("10003" to 999_950L))
        val relayCopy = h.sign(10003, listOf(listOf("e", hex(1)), listOf("t", "keep")), at = 999_000L)
        h.onRelays(relayCopy)
        // One of the two listed write relays answers: no proof that nothing newer exists. Kept, nothing signed.
        h.relays.online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(idx))
        assertEquals(ListResult.Pending, h.writer.set(10003, listOf("e", hex(2)), wanted = true))
        assertTrue(h.relays.published.isEmpty())
        assertEquals(999_950L, h.store.watermark("10003"))
        // Both answer with the same older copy: the floor comes down and the change goes on top of the relay copy.
        h.relays.online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(w2), ListWriter.norm(idx))
        h.writer.resumeNow()
        assertEquals(999_000L, h.store.watermark("10003"))
        val newest = h.relays.newestAt(w1, 10003)!!
        assertEquals(relayCopy.tags + listOf(listOf("e", hex(2))), newest.tags)
        assertTrue(h.store.pending("10003").isEmpty())
    }

    @Test
    fun zeroRelaysAnsweringKeepsTheChangePending() = runBlocking {
        val h = harness()
        h.onRelays(h.sign(10003, listOf(listOf("e", hex(1)))))
        h.relays.online.clear()
        val r = h.writer.set(10003, listOf("e", hex(2)), wanted = true)
        assertEquals(ListResult.Pending, r)
        assertTrue(h.relays.published.isEmpty())
        assertEquals(1, h.store.pending("10003").size)
        assertEquals(true, h.writer.wanted(10003, null, listOf("e", hex(2))))
        // One toast the first time, not again for the next kept change or a retry.
        assertEquals(listOf(R.string.list_will_save), h.messages.toList())
        assertEquals(ListResult.Pending, h.writer.set(10003, listOf("e", hex(3)), wanted = true))
        h.writer.resumeNow()
        assertEquals(listOf(R.string.list_will_save), h.messages.toList())
    }

    @Test
    fun indexerAnswersAloneNeverSuffice() = runBlocking {
        val h = harness()
        h.onRelays(h.sign(10003, listOf(listOf("e", hex(1)))))
        h.relays.online = mutableSetOf(ListWriter.norm(idx))
        val r = h.writer.set(10003, listOf("e", hex(2)), wanted = true)
        assertEquals(ListResult.Pending, r)
        assertTrue(h.relays.published.isEmpty())
    }

    @Test
    fun createdAtIsOldPlusOneWithASlowClock() = runBlocking {
        val h = harness()
        val old = h.sign(10003, listOf(listOf("e", hex(1))), at = h.now + 100)
        h.onRelays(old)
        val r = h.writer.set(10003, listOf("e", hex(2)), wanted = true) as ListResult.Sent
        assertEquals(old.created_at + 1, r.event.created_at)
    }

    @Test
    fun refusesAVersionDatedFarAhead() = runBlocking {
        val h = harness()
        val old = h.sign(10003, listOf(listOf("e", hex(1))), at = h.now + 5_000)
        h.onRelays(old)
        val r = h.writer.set(10003, listOf("e", hex(2)), wanted = true)
        assertTrue(r is ListResult.Refused)
        assertTrue(h.relays.published.isEmpty())
    }

    @Test
    fun createNeverOverwritesAnExistingD() = runBlocking {
        val h = harness()
        h.onRelays(h.sign(30000, listOf(listOf("d", "friends"), listOf("p", hex(1)))))
        val r = h.writer.createIfMissing(30000, listOf(listOf("p", hex(2))), d = "friends", title = "Friends")
        assertEquals(ListResult.Unchanged, r)
        assertTrue(h.relays.published.isEmpty())
        // A d nobody has: written as a first list (d, title, entries).
        val c = h.writer.createIfMissing(30000, listOf(listOf("p", hex(2))), d = "family", title = "Family") as ListResult.Sent
        assertEquals(listOf(listOf("d", "family"), listOf("title", "Family"), listOf("p", hex(2))), c.event.tags)
    }

    @Test
    fun defaultDmRelayListOnlyWhenNoneExists() = runBlocking {
        val h = harness()
        val first = h.writer.createIfMissing(10050, listOf(listOf("relay", "wss://dm.example")))
        assertTrue(first is ListResult.Sent)
        // Seen now: never again, even if the relays lose it.
        h.relays.held.clear()
        assertEquals(ListResult.Unchanged, h.writer.createIfMissing(10050, listOf(listOf("relay", "wss://dm.example"))))
        assertEquals(1, h.relays.published.size)
    }

    @Test
    fun twoQuickTapsBothLand() = runBlocking {
        val h = harness()
        h.onRelays(h.sign(10003, listOf(listOf("e", hex(1)))))
        h.relays.fetchDelayMs = 30
        val results = listOf(
            async { h.writer.set(10003, listOf("e", hex(2)), wanted = true) },
            async { h.writer.set(10003, listOf("e", hex(3)), wanted = true) }
        ).awaitAll()
        assertTrue(results.all { it is ListResult.Sent })
        val newest = h.relays.newestAt(w1, 10003)!!
        assertEquals(listOf(listOf("e", hex(1)), listOf("e", hex(2)), listOf("e", hex(3))), newest.tags)
    }

    // ------------------------------------------------------------------ the brief's extra cases

    @Test
    fun aRepeatTapIsUnchanged() = runBlocking {
        val h = harness()
        h.onRelays(h.sign(10003, listOf(listOf("e", hex(1)))))
        assertTrue(h.writer.set(10003, listOf("e", hex(2)), wanted = true) is ListResult.Sent)
        assertEquals(ListResult.Unchanged, h.writer.set(10003, listOf("e", hex(2)), wanted = true))
        assertEquals(ListResult.Unchanged, h.writer.set(10003, listOf("e", hex(9)), wanted = false))
        assertEquals(1, h.relays.published.size)
    }

    @Test
    fun offlineChangeIsPendingAndReplaysOntoANewerVersion() = runBlocking {
        val h = harness()
        h.onRelays(h.sign(10003, listOf(listOf("e", hex(1)))))
        h.relays.online.clear()
        assertEquals(ListResult.Pending, h.writer.set(10003, listOf("e", hex(2)), wanted = true))
        assertEquals(true, h.writer.overlay.value[ListWriter.overlayKey(10003, null, listOf("e", hex(2)))])
        // Meanwhile another app wrote a newer version with its own change.
        val other = h.sign(10003, listOf(listOf("e", hex(1)), listOf("e", hex(7))), at = h.now - 10)
        h.onRelays(other)
        h.relays.online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(w2), ListWriter.norm(idx))
        h.writer.resumeNow()
        val newest = h.relays.newestAt(w1, 10003)!!
        assertEquals(listOf(listOf("e", hex(1)), listOf("e", hex(7)), listOf("e", hex(2))), newest.tags)
        assertTrue(h.store.pending("10003").isEmpty())
        assertTrue(h.writer.overlay.value.isEmpty())
    }

    @Test
    fun aPendingChangeThatBecameANoOpIsDropped() = runBlocking {
        val h = harness()
        h.onRelays(h.sign(10003, listOf(listOf("e", hex(1)))))
        h.relays.online.clear()
        h.writer.set(10003, listOf("e", hex(2)), wanted = true)
        h.onRelays(h.sign(10003, listOf(listOf("e", hex(1)), listOf("e", hex(2))), at = h.now - 10))
        h.relays.online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(w2))
        h.writer.resumeNow()
        assertTrue(h.relays.published.isEmpty())
        assertTrue(h.store.pending("10003").isEmpty())
    }

    @Test
    fun aStaleIndexerAnswerLosesToTheNewerWriteRelayAnswer() = runBlocking {
        val h = harness()
        val stale = h.sign(10003, listOf(listOf("e", hex(1))), at = h.now - 500)
        val fresh = h.sign(10003, listOf(listOf("e", hex(1)), listOf("e", hex(5))), at = h.now - 50)
        h.relays.put(idx, stale)
        h.relays.put(w1, fresh)
        h.relays.put(w2, fresh)
        val r = h.writer.set(10003, listOf("e", hex(6)), wanted = true) as ListResult.Sent
        assertEquals(fresh.tags + listOf(listOf("e", hex(6))), r.event.tags)
    }

    @Test
    fun anAccountSwitchMidFetchSignsNothing() = runBlocking {
        val h = harness()
        h.onRelays(h.sign(10003, listOf(listOf("e", hex(1)))))
        val other = Keys.generate()
        h.relays.onFetch = { h.active = LocalSigner(other.privkey, other.pubkey).pubkeyHex }
        val r = h.writer.set(10003, listOf("e", hex(2)), wanted = true)
        assertEquals(ListResult.Pending, r)
        assertTrue(h.relays.published.isEmpty())
        assertTrue(h.messages.isEmpty())
        // Kept for this account only, to be written when it is active again.
        assertEquals(1, h.store.pending("10003").size)
    }

    @Test
    fun aPublishWithNoOkStaysUnconfirmedAndIsResentAsIs() = runBlocking {
        val h = harness()
        h.onRelays(h.sign(10003, listOf(listOf("e", hex(1)))))
        h.relays.accepting.clear()
        assertEquals(ListResult.Pending, h.writer.set(10003, listOf("e", hex(2)), wanted = true))
        val signed = h.store.unconfirmed("10003")
        assertNotNull(signed)
        assertEquals(1, h.relays.published.size)
        assertEquals(signed!!.id, h.store.latest(10003)!!.id)
        // The button already shows it (the projection loaded the signed version; the change stays pending).
        assertEquals(1, h.store.pending("10003").size)

        h.relays.accepting = h.relays.online.toMutableSet()
        h.writer.resumeNow()
        assertEquals(2, h.relays.published.size)
        assertEquals(signed.id, h.relays.published[1].id)
        assertNull(h.store.unconfirmed("10003"))
        assertTrue(h.store.pending("10003").isEmpty())
    }

    @Test
    fun aThousandEntryPrivatePartNearTheLimit() = runBlocking {
        val h = harness()
        val priv = MutableList(1000) { listOf("t", "x") }
        var i = 0
        while (ListWriter.privateJson(priv).length + 63 + 14 <= ListWriter.NIP44_MAX) {
            priv[i] = listOf("p", hex(i + 1)); i++
        }
        val content = h.enc44(priv)
        val old = h.sign(10000, listOf(listOf("p", hex(5000))), content)
        h.onRelays(old)
        // A short word still fits: private.
        val word = h.writer.set(10000, listOf("word", "hi"), wanted = true, place = ListPlace.PRIVATE_OR_PUBLIC) as ListResult.Sent
        val afterWord = h.dec(word.event.content)
        assertEquals(1001, afterWord.size)
        assertEquals(old.tags, word.event.tags)
        // A person no longer fits in NIP-44: a public tag, the private part byte for byte.
        h.now += 5
        val mute = h.writer.set(10000, listOf("p", hex(6000)), wanted = true, place = ListPlace.PRIVATE_OR_PUBLIC) as ListResult.Sent
        assertEquals(word.event.content, mute.event.content)
        assertEquals(old.tags + listOf(listOf("p", hex(6000))), mute.event.tags)
        // And unmuting one of the thousand works.
        val un = h.writer.set(10000, listOf("p", hex(1)), wanted = false) as ListResult.Sent
        assertEquals(1000, h.dec(un.event.content).size)
    }

    @Test
    fun aFirstFollowListNeedsConfirmation() = runBlocking {
        val h = harness()
        val r = h.writer.set(3, listOf("p", hex(1)), wanted = true)
        assertEquals(ListResult.Reason.NEEDS_CONFIRM, (r as ListResult.Refused).reason)
        assertTrue(h.relays.published.isEmpty())
        // No toast (the dialog asks), and the caller had the answer, so nothing on needsConfirm.
        assertTrue(h.messages.isEmpty())
        assertTrue(h.confirms.isEmpty())
        val ok = h.writer.set(3, listOf("p", hex(1)), wanted = true, confirmFirstList = true) as ListResult.Sent
        assertEquals(listOf(listOf("p", hex(1))), ok.event.tags)
        assertEquals("", ok.event.content)
    }

    @Test
    fun noFirstListWithoutYourWriteRelaysSayingThereIsNone() = runBlocking {
        val h = harness()
        h.relays.online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(idx)) // w2 silent: 1 of 2 listed
        // Half of your write relays answered (enough to read), but proving that no list exists needs 2 of them.
        val r = h.writer.set(10001, listOf("e", hex(1)), wanted = true)
        assertEquals(ListResult.Reason.NOT_FOUND, (r as ListResult.Refused).reason)
        assertTrue(h.relays.published.isEmpty())
        // Not kept: the button flips back at once, with the toast.
        assertTrue(h.store.pending("10001").isEmpty())
        assertTrue(h.writer.overlay.value.isEmpty())
        assertEquals(listOf(R.string.list_not_found), h.messages.toList())
        // With both listed write relays answering, a first list is written.
        h.relays.online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(w2))
        assertTrue(h.writer.set(10001, listOf("e", hex(1)), wanted = true) is ListResult.Sent)
        assertEquals(listOf(listOf("e", hex(1))), h.relays.newestAt(w1, 10001)!!.tags)
    }

    @Test
    fun anAccountWithNoRelayListAnywhereUsesTheAppsRelays() = runBlocking {
        val h = harness(withRelayList = false)
        // One relay alone saying there is no relay list proves nothing: kept.
        h.relays.online = mutableSetOf(ListWriter.norm(w1))
        assertEquals(ListResult.Pending, h.writer.set(10003, listOf("e", hex(1)), wanted = true))
        assertTrue(h.relays.published.isEmpty())
        h.store.clearPending("10003")
        // Every relay asked has none: your lists can only be on the app's relays. A follow asks first, as for any
        // first follow list ...
        h.relays.online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(w2), ListWriter.norm(idx))
        val f = h.writer.set(3, listOf("p", hex(1)), wanted = true)
        assertEquals(ListResult.Reason.NEEDS_CONFIRM, (f as ListResult.Refused).reason)
        assertTrue(h.writer.set(3, listOf("p", hex(1)), wanted = true, confirmFirstList = true) is ListResult.Sent)
        // ... and a bookmark starts a first list once those relays said there is none.
        val b = h.writer.set(10003, listOf("e", hex(1)), wanted = true) as ListResult.Sent
        assertEquals(listOf(listOf("e", hex(1))), b.event.tags)
        // A list they do have is built on.
        val old = h.sign(10001, listOf(listOf("e", hex(5)), listOf("x", "keep")))
        h.onRelays(old)
        val p = h.writer.set(10001, listOf("e", hex(6)), wanted = true) as ListResult.Sent
        assertEquals(old.tags + listOf(listOf("e", hex(6))), p.event.tags)
    }

    @Test
    fun anExistingAccountLearnsItsRelayListFirst() = runBlocking {
        val h = harness(withRelayList = false)
        val rl = h.sign(10002, listOf(listOf("r", w1), listOf("r", w2)), at = h.now - 9000)
        h.relays.put(idx, rl)
        h.onRelays(h.sign(10003, listOf(listOf("e", hex(1)))))
        val r = h.writer.set(10003, listOf("e", hex(2)), wanted = true)
        assertTrue(r is ListResult.Sent)
        assertNotNull(h.store.latest(10002))
    }

    @Test
    fun aNewAccountOnThisDeviceSkipsTheFetch() = runBlocking {
        val h = harness(withRelayList = false, newAccount = true)
        val r = h.writer.set(10003, listOf("e", hex(1)), wanted = true) as ListResult.Sent
        assertEquals(0, h.relays.fetches)
        assertEquals(listOf(listOf("e", hex(1))), r.event.tags)
        // The next change does fetch, and builds on it.
        val r2 = h.writer.set(10003, listOf("e", hex(2)), wanted = true) as ListResult.Sent
        assertTrue(h.relays.fetches > 0)
        assertEquals(listOf(listOf("e", hex(1)), listOf("e", hex(2))), r2.event.tags)
    }

    @Test
    fun followManyWritesOneEventAndKeepsEverythingElse() = runBlocking {
        val h = harness()
        val tags = listOf(listOf("p", hex(1), "", "pet"), listOf("t", "x"))
        h.onRelays(h.sign(3, tags, "{}"))
        val r = h.writer.setMany(3, listOf(listOf("p", hex(1)), listOf("p", hex(2)), listOf("p", hex(3))), wanted = true) as ListResult.Sent
        assertEquals(tags + listOf(listOf("p", hex(2)), listOf("p", hex(3))), r.event.tags)
        assertEquals("{}", r.event.content)
        assertEquals(1, h.relays.published.size)
    }

    @Test
    fun theSignedVersionIsProjectedAndOnlyNewerFetchedOnes() = runBlocking {
        val h = harness()
        val projected = mutableListOf<String>()
        val writer = ListWriter(
            source = h.relays, sink = h.relays,
            account = { ListAccount(h.signer, h.store) }, activePubkey = { h.active },
            poolWriteRelays = { listOf(w1, w2) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            project = { projected += it.id }, clock = { h.now }, indexerRelays = listOf(idx), work = EmptyCoroutineContext
        )
        val old = h.sign(10003, listOf(listOf("e", hex(1))))
        h.onRelays(old)
        val r = writer.set(10003, listOf("e", hex(2)), wanted = true) as ListResult.Sent
        assertEquals(listOf(old.id, r.event.id), projected)
        assertFalse(h.store.seen(old))
    }

    // ------------------------------------------------------------------ review fixes

    @Test
    fun readingNeedsHalfOfAllYourWriteRelaysNotJustTheOnesAsked() = runBlocking {
        val h = harness(withRelayList = false)
        val w3 = "wss://w3.example"
        assertTrue(h.store.seen(h.sign(10002, listOf(listOf("r", w1), listOf("r", w2), listOf("r", w3)), at = h.now - 4000)))
        // Another app wrote the newest version to w1 and w2; w3 missed it and is the only one that answers now.
        val older = h.sign(10003, listOf(listOf("e", hex(1))), at = h.now - 500)
        val newer = h.sign(10003, listOf(listOf("e", hex(1)), listOf("e", hex(2))), at = h.now - 50)
        h.relays.put(w3, older)
        h.relays.put(w1, newer)
        h.relays.put(w2, newer)
        h.relays.online = mutableSetOf(ListWriter.norm(w3), ListWriter.norm(idx))
        h.relays.accepting = h.relays.online.toMutableSet()
        assertEquals(ListResult.Pending, h.writer.set(10003, listOf("e", hex(3)), wanted = true))
        assertTrue(h.relays.published.isEmpty())
        assertEquals(1, h.store.pending("10003").size)
        // When enough of them answer, the change goes on the newest version.
        h.relays.online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(w3), ListWriter.norm(idx))
        h.relays.accepting = h.relays.online.toMutableSet()
        h.writer.resumeNow()
        assertEquals(newer.tags + listOf(listOf("e", hex(3))), h.relays.newestAt(w1, 10003)!!.tags)
    }

    @Test
    fun aNewAccountFetchesOnceSignUpIsOver() = runBlocking {
        val h = harness(withRelayList = false, newAccount = true)
        h.now += OwnListStore.NEW_ACCOUNT_WINDOW + 1
        // Meanwhile the key was used in another app, which made bookmarks.
        val other = h.sign(10003, listOf(listOf("e", hex(1)), listOf("t", "keep")))
        h.onRelays(other)
        val r = h.writer.set(10003, listOf("e", hex(2)), wanted = true) as ListResult.Sent
        assertTrue(h.relays.fetches > 0)
        assertEquals(other.tags + listOf(listOf("e", hex(2))), r.event.tags)
    }

    @Test
    fun aFirstListNeedsTwoWriteRelaysSayingThereIsNone() = runBlocking {
        val h = harness(withRelayList = false)
        assertTrue(h.store.seen(h.sign(10002, listOf(listOf("r", w1), listOf("r", w2)), at = h.now - 4000)))
        h.relays.online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(idx))
        // One of your two write relays answered "none": not enough to start bookmarks from nothing.
        val r = h.writer.set(10003, listOf("e", hex(1)), wanted = true)
        assertEquals(ListResult.Reason.NOT_FOUND, (r as ListResult.Refused).reason)
        assertTrue(h.relays.published.isEmpty())
        assertTrue(h.store.pending("10003").isEmpty())
    }

    @Test
    fun withOneListedWriteRelayItsAnswerIsEnoughForAFirstList() = runBlocking {
        val h = harness(withRelayList = false)
        assertTrue(h.store.seen(h.sign(10002, listOf(listOf("r", w1)), at = h.now - 4000)))
        // Your only write relay answered "none": a first list the user asked for may start.
        val r = h.writer.set(10003, listOf("e", hex(1)), wanted = true) as ListResult.Sent
        assertEquals(listOf(listOf("e", hex(1))), r.event.tags)
        // A follow list still asks the user first.
        val f = h.writer.set(3, listOf("p", hex(1)), wanted = true)
        assertEquals(ListResult.Reason.NEEDS_CONFIRM, (f as ListResult.Refused).reason)
        // A list the app would start by itself still needs two relays saying there is none.
        assertEquals(ListResult.Pending, h.writer.createIfMissing(10050, listOf(listOf("relay", "wss://dm.example"))))
    }

    @Test
    fun aKeptFirstFollowAsksThroughNeedsConfirm() = runBlocking {
        val h = harness()
        h.relays.online.clear()
        assertEquals(ListResult.Pending, h.writer.set(3, listOf("p", hex(1)), wanted = true))
        h.relays.online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(w2), ListWriter.norm(idx))
        h.writer.resumeNow()
        assertEquals(listOf(listOf(listOf("p", hex(1)))), h.confirms.toList())
        assertTrue(h.relays.published.isEmpty())
        assertTrue(h.store.pending("3").isEmpty())
        // No "not found" toast for it.
        assertEquals(listOf(R.string.list_will_save), h.messages.toList())
    }

    @Test
    fun aSigningFailureRefusesAndDropsTheChange() = runBlocking {
        val h = harness(wrap = { a ->
            object : NostrSigner by a {
                override suspend fun signEvent(kind: Int, content: String, tags: List<List<String>>, createdAt: Long): NostrEvent =
                    throw IllegalStateException("broken key")
            }
        })
        h.onRelays(h.sign(10003, listOf(listOf("e", hex(1)))))
        val r = h.writer.set(10003, listOf("e", hex(2)), wanted = true)
        assertEquals(ListResult.Reason.NOT_SENT, (r as ListResult.Refused).reason)
        assertTrue(h.relays.published.isEmpty())
        assertTrue(h.store.pending("10003").isEmpty())
        assertTrue(h.writer.overlay.value.isEmpty())
        assertEquals(listOf(R.string.list_not_sent), h.messages.toList())
        // Nothing left to replay.
        h.writer.resumeNow()
        assertTrue(h.relays.published.isEmpty())
    }

    @Test
    fun anEncryptionThatReturnsNothingIsRefused() = runBlocking {
        val h = harness(wrap = { a ->
            object : NostrSigner by a {
                override suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String = ""
            }
        })
        h.onRelays(h.sign(10000, listOf(listOf("p", hex(1))), h.enc44(listOf(listOf("p", hex(2))))))
        val r = h.writer.set(10000, listOf("p", hex(3)), wanted = true, place = ListPlace.PRIVATE_OR_PUBLIC)
        assertEquals(ListResult.Reason.BLOCKED, (r as ListResult.Refused).reason)
        assertTrue(h.relays.published.isEmpty())
    }

    @Test
    fun anEncryptionThatDoesNotReadBackIsRefused() = runBlocking {
        val h = harness(wrap = { a ->
            object : NostrSigner by a {
                // Encrypts something else than it was given.
                override suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String = a.nip44Encrypt("[]", peerPubkeyHex)
            }
        })
        h.onRelays(h.sign(10000, listOf(listOf("p", hex(1))), h.enc44(listOf(listOf("p", hex(2))))))
        val r = h.writer.set(10000, listOf("p", hex(3)), wanted = true, place = ListPlace.PRIVATE_OR_PUBLIC)
        assertEquals(ListResult.Reason.BLOCKED, (r as ListResult.Refused).reason)
        assertTrue(h.relays.published.isEmpty())
    }

    @Test
    fun aSignerThatSignsWithAnotherKeyIsRefused() = runBlocking {
        val b = Keys.generate().let { LocalSigner(it.privkey, it.pubkey) }
        val h = harness(wrap = { a ->
            object : NostrSigner by a {
                override suspend fun signEvent(kind: Int, content: String, tags: List<List<String>>, createdAt: Long): NostrEvent =
                    b.signEvent(kind, content, tags, createdAt)
            }
        })
        val old = h.sign(10003, listOf(listOf("e", hex(1))))
        h.onRelays(old)
        val r = h.writer.set(10003, listOf("e", hex(2)), wanted = true)
        assertEquals(ListResult.Reason.NOT_SENT, (r as ListResult.Refused).reason)
        assertTrue(h.relays.published.isEmpty())
        assertEquals(old.id, h.store.latest(10003)?.id)
    }

    @Test
    fun otherAuthorsKindsAndSetsReturnedByARelayAreNeverTheStart() = runBlocking {
        val h = harness()
        h.relays.leaky = true
        val mine = h.sign(10003, listOf(listOf("e", hex(1))), at = h.now - 500)
        h.onRelays(mine)
        val stranger = Keys.generate().let { LocalSigner(it.privkey, it.pubkey) }
        h.onRelays(runBlocking { stranger.signEvent(10003, "", listOf(listOf("e", hex(9))), h.now - 10) })
        h.onRelays(h.sign(10000, listOf(listOf("p", hex(8))), at = h.now - 10))
        h.onRelays(h.sign(30003, listOf(listOf("d", "x"), listOf("e", hex(7))), at = h.now - 10))
        val r = h.writer.set(10003, listOf("e", hex(2)), wanted = true) as ListResult.Sent
        assertEquals(mine.tags + listOf(listOf("e", hex(2))), r.event.tags)
    }

    @Test
    fun muteOnBlankContentGoesPrivateNip44() = runBlocking {
        val h = harness()
        val old = h.sign(10000, listOf(listOf("p", hex(1)), listOf("t", "x")), "")
        h.onRelays(old)
        val r = h.writer.set(10000, listOf("p", hex(2)), wanted = true, place = ListPlace.PRIVATE_OR_PUBLIC) as ListResult.Sent
        assertEquals(old.tags, r.event.tags)
        assertFalse(r.event.content.contains("?iv="))
        assertTrue(r.event.content.isNotBlank())
        assertEquals(listOf(listOf("p", hex(2))), h.dec(r.event.content))
    }

    @Test
    fun firstMuteListIsPrivate() = runBlocking {
        val h = harness()
        val r = h.writer.set(10000, listOf("p", hex(2)), wanted = true, place = ListPlace.PRIVATE_OR_PUBLIC) as ListResult.Sent
        assertEquals(emptyList<List<String>>(), r.event.tags)
        assertEquals(listOf(listOf("p", hex(2))), h.dec(r.event.content))
    }

    @Test
    fun muteAlreadyInEitherPartWritesNothing() = runBlocking {
        val h = harness()
        h.onRelays(h.sign(10000, listOf(listOf("p", hex(1))), h.enc44(listOf(listOf("p", hex(2))))))
        assertEquals(ListResult.Unchanged, h.writer.set(10000, listOf("p", hex(1)), wanted = true, place = ListPlace.PRIVATE_OR_PUBLIC))
        assertEquals(ListResult.Unchanged, h.writer.set(10000, listOf("p", hex(2).uppercase()), wanted = true, place = ListPlace.PRIVATE_OR_PUBLIC))
        assertTrue(h.relays.published.isEmpty())
    }

    // ------------------------------------------------------------------ the shared vectors, through ListWriter

    /**
     * marble-content-fixtures' vectors.json replayed through the code that signs (not only ListWrite.plan): each found
     * version re-signed (yours with the account key, others with a throwaway key) and put on both write relays.
     */
    @Test
    fun theSharedVectorsHoldThroughListWriter() = runBlocking {
        val v = Json.parseToJsonElement(javaClass.getResource("/lists/vectors.json")!!.readText()).jsonObject
        val now = v["now"]!!.jsonPrimitive.long
        fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
        fun tags(a: JsonArray?): List<List<String>> = a?.map { t -> t.jsonArray.map { it.jsonPrimitive.content } } ?: emptyList()
        // Cannot be replayed: Pinket refuses a list dated ahead (refusesAVersionDatedFarAhead), and re-signing
        // changes the ids a same-second tie is decided by.
        val skip = listOf("a clock behind the list", "the same moment: the lowest id is the newest")
        var replayed = 0
        for (group in listOf("writes", "nothing", "refuse")) {
            for (c in v[group]!!.jsonArray.map { it.jsonObject }) {
                val name = c.str("name")!!
                if (skip.any { name.startsWith(it) }) continue
                val h = harness(start = now)
                h.relays.leaky = true
                val other = Keys.generate().let { LocalSigner(it.privkey, it.pubkey) }
                for (f in c["found"]!!.jsonArray.map { it.jsonObject }) {
                    val s: NostrSigner = if (f.str("pubkey") == v.str("me")) h.signer else other
                    val e = s.signEvent(f["kind"]!!.jsonPrimitive.int, f.str("content") ?: "", tags(f["tags"]!!.jsonArray), f["created_at"]!!.jsonPrimitive.long)
                    h.relays.put(w1, e); h.relays.put(w2, e)
                }
                val ch = c["change"]!!.jsonObject
                val add = (ch["add"] as? JsonArray)?.map { it.jsonPrimitive.content }
                val entry = add ?: (ch["remove"] as JsonArray).map { it.jsonPrimitive.content }
                val r = h.writer.set(
                    c["kind"]!!.jsonPrimitive.int, entry, wanted = add != null,
                    d = c.str("d")?.ifEmpty { null },
                    expected = c["expected"]!!.jsonPrimitive.content.toBoolean(),
                    title = c.str("title")?.ifEmpty { null }
                )
                when (group) {
                    "writes" -> {
                        val w = c["write"]!!.jsonObject
                        val e = (r as? ListResult.Sent ?: error("$name: $r")).event
                        assertEquals(name, tags(w["tags"]!!.jsonArray), e.tags)
                        assertEquals(name, w.str("content"), e.content)
                        assertEquals(name, w["created_at"]!!.jsonPrimitive.long, e.created_at)
                    }
                    "nothing" -> assertEquals(name, ListResult.Unchanged, r)
                    else -> assertEquals(name, ListResult.Refused(ListResult.Reason.NOT_FOUND, "no list found"), r)
                }
                replayed++
            }
        }
        assertEquals(14, replayed)
    }
}
