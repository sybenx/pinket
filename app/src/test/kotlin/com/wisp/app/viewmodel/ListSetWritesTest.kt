package com.wisp.app.viewmodel

import android.app.Application
import com.wisp.app.R
import com.wisp.app.nostr.CustomEmoji
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.repo.MemoryOwnListStorage
import com.wisp.app.repo.OwnListStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The set and emoji list writes ListCrudManager makes (people, bookmark, interest and emoji sets, the emoji list), as
 * the edits its helpers compute, through ListWriter against fake relays. Throwaway keys generated here only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ListSetWritesTest {
    private val w1 = "wss://w1.example"
    private val w2 = "wss://w2.example"
    private val idx = "wss://idx.example"

    private class FakeRelays : ListSource, ListSink {
        val held = mutableMapOf<String, MutableList<NostrEvent>>()
        var online = mutableSetOf<String>()
        var accepting = mutableSetOf<String>()
        val published = mutableListOf<NostrEvent>()

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
            val sent = (writeRelays + otherRelays).map { ListWriter.norm(it) }.filter { it in online }.toSet()
            val acc = sent.filter { it in accepting }.toSet()
            for (u in acc) put(u, event)
            return ListPublishResult(sent, acc)
        }
    }

    private class Harness(
        val relays: FakeRelays,
        val signer: LocalSigner,
        val store: OwnListStore,
        val writer: ListWriter,
        val now: Long,
        val messages: MutableList<Int>
    ) {
        val me: String get() = signer.pubkeyHex
    }

    private fun harness(start: Long = 2_000_000L): Harness = runBlocking {
        val kp = Keys.generate()
        val signer = LocalSigner(kp.privkey, kp.pubkey)
        val relays = FakeRelays().apply {
            online = mutableSetOf(ListWriter.norm(w1), ListWriter.norm(w2), ListWriter.norm(idx))
            accepting = online.toMutableSet()
        }
        val store = OwnListStore(signer.pubkeyHex, MemoryOwnListStorage(), clock = { start }, entryId = { ListWriter.entryId(it) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val writer = ListWriter(
            source = relays, sink = relays,
            account = { ListAccount(signer, store) },
            activePubkey = { signer.pubkeyHex },
            poolWriteRelays = { listOf(w1, w2) },
            scope = scope,
            clock = { start },
            indexerRelays = listOf(idx),
            work = EmptyCoroutineContext
        )
        val messages = Collections.synchronizedList(mutableListOf<Int>())
        scope.launch { writer.messages.collect { messages += it } }
        val rl = signer.signEvent(10002, "", listOf(listOf("r", w1), listOf("r", w2)), start - 5000)
        assertTrue(store.seen(rl))
        Harness(relays, signer, store, writer, start, messages)
    }

    private fun Harness.sign(kind: Int, tags: List<List<String>>, content: String = "", at: Long = now - 100): NostrEvent =
        runBlocking { signer.signEvent(kind, content, tags, at) }

    private fun Harness.onRelays(e: NostrEvent) { relays.put(w1, e); relays.put(w2, e) }
    private fun Harness.enc(tags: List<List<String>>) = runBlocking { signer.nip44Encrypt(ListWriter.privateJson(tags), me) }
    private fun Harness.dec(content: String): List<List<String>> = runBlocking { ListWriter.parsePrivate(signer.nip44Decrypt(content, me))!! }

    private fun hex(n: Int) = n.toString(16).padStart(64, '0')
    private fun sent(r: ListResult): NostrEvent = (r as? ListResult.Sent)?.event ?: error("not sent: $r")

    @Test
    fun aMemberOfAPrivatePeopleSetGoesInThePrivatePartWherePlaceOfPutsIt() = runBlocking {
        val h = harness()
        val priv = listOf(listOf("title", "Close"), listOf("p", hex(1)))
        val set = h.sign(30000, listOf(listOf("d", "close"), listOf("x-unknown", "keep")), h.enc(priv))
        h.onRelays(set)
        assertTrue(h.store.seen(set))
        val place = ListCrudManager.placeOf(false, false, h.store.latest(30000, "close"), loadedPrivate = true, entryNames = setOf("p"))
        assertEquals(ListPlace.PRIVATE, place)
        val e = sent(h.writer.edit(30000, listOf(ListEdit.add("p", hex(2))), d = "close", place = place, expected = true, explicit = true))
        assertEquals(set.tags, e.tags)
        assertEquals(priv + listOf(listOf("p", hex(2))), h.dec(e.content))
    }

    @Test
    fun aNoteInABookmarkSetKeepsThePrivatePartByteForByte() = runBlocking {
        val h = harness()
        val big = (1..200).map { listOf("e", hex(1000 + it)) }
        val content = h.enc(big)
        val set = h.sign(30003, listOf(listOf("d", "reads"), listOf("title", "Reads"), listOf("e", hex(1)), listOf("a", "30023:${hex(9)}:x"), listOf("client", "other")), content)
        h.onRelays(set)
        assertTrue(h.store.seen(set))
        val place = ListCrudManager.placeOf(false, false, h.store.latest(30003, "reads"), loadedPrivate = false, entryNames = setOf("e", "a", "t"))
        assertEquals(ListPlace.PUBLIC, place)
        val e = sent(h.writer.edit(30003, listOf(ListEdit(listOf("e", hex(2), "wss://hint.example"), ListEdit.Op.ADD)), d = "reads", place = place, expected = true, explicit = true))
        assertEquals(content, e.content)
        assertEquals(set.tags + listOf(listOf("e", hex(2), "wss://hint.example")), e.tags)
        val r = sent(h.writer.edit(30003, listOf(ListEdit.remove("e", hex(1))), d = "reads", expected = true, explicit = true))
        assertEquals(content, r.content)
        assertEquals(e.tags.filterNot { it == listOf("e", hex(1)) }, r.tags)
    }

    @Test
    fun followAllAndUnfollowAllAreOneWriteAndMatchAnySpelling() = runBlocking {
        val h = harness()
        val set = h.sign(30015, listOf(listOf("d", "tech"), listOf("title", "Tech"), listOf("t", "Bitcoin"), listOf("t", "nostr")))
        h.onRelays(set)
        assertTrue(h.store.seen(set))
        val adds = ListCrudManager.hashtagEdits(set.tags, listOf("bitcoin", "rust", "kotlin"), wanted = true)
        assertEquals(listOf(ListEdit.add("t", "rust"), ListEdit.add("t", "kotlin")), adds)
        val a = sent(h.writer.edit(30015, adds, d = "tech", expected = true, explicit = true))
        assertEquals(set.tags + listOf(listOf("t", "rust"), listOf("t", "kotlin")), a.tags)
        assertEquals(1, h.relays.published.size)
        val removes = ListCrudManager.hashtagEdits(a.tags, listOf("bitcoin", "rust"), wanted = false)
        val r = sent(h.writer.edit(30015, removes, d = "tech", expected = true, explicit = true))
        assertEquals(listOf(listOf("d", "tech"), listOf("title", "Tech"), listOf("t", "nostr"), listOf("t", "kotlin")), r.tags)
        assertEquals(2, h.relays.published.size)
    }

    @Test
    fun theDefaultInterestSetIsStartedOnlyWhenNoneExists() = runBlocking {
        val h = harness()
        val first = sent(h.writer.edit(30015, listOf(ListEdit.add("t", "nostr")), d = "interests", title = "Interests"))
        assertEquals(listOf(listOf("d", "interests"), listOf("title", "Interests"), listOf("t", "nostr")), first.tags)
        // It exists now: the next follow goes onto it, the title is not added again.
        val next = sent(h.writer.edit(30015, listOf(ListEdit.add("t", "zaps")), d = "interests", title = "Interests"))
        assertEquals(first.tags + listOf(listOf("t", "zaps")), next.tags)
    }

    @Test
    fun anotherAppsSetChangesOnlyWhenTheUserEditsIt() = runBlocking {
        val h = harness()
        val set = h.sign(30015, listOf(listOf("d", "Their Topics"), listOf("title", "Topics"), listOf("t", "art"), listOf("client", "other")))
        h.onRelays(set)
        val auto = h.writer.edit(30015, listOf(ListEdit.add("t", "music")), d = "Their Topics")
        assertEquals(ListResult.Reason.READ_ONLY, (auto as ListResult.Refused).reason)
        assertTrue(R.string.list_read_only in h.messages)
        val mine = sent(h.writer.edit(30015, listOf(ListEdit.add("t", "music")), d = "Their Topics", expected = true, explicit = true))
        assertEquals(set.tags + listOf(listOf("t", "music")), mine.tags)
    }

    @Test
    fun theEmojiSetDialogWritesOnlyWhatChanged() = runBlocking {
        val h = harness()
        val set = h.sign(30030, listOf(
            listOf("d", "cats"), listOf("title", "Cats"), listOf("emoji", "a", "https://x/a.png"),
            listOf("x-unknown", "keep"), listOf("emoji", "b", "https://x/b.png"), listOf("emoji", "c", "https://x/c.png")
        ))
        h.onRelays(set)
        val opened = listOf(CustomEmoji("a", "https://x/a.png"), CustomEmoji("b", "https://x/b.png"), CustomEmoji("c", "https://x/c.png"))
        // The dialog closed with: b gone, c on a new image, d new, a kept, renamed.
        val closed = listOf(CustomEmoji("d", "https://x/d.png"), CustomEmoji("c", "https://x/c2.png"), CustomEmoji("a", "https://x/a.png"))
        val edits = ListCrudManager.emojiSetEdits("Cats", opened, "Kitties", closed)
        val e = sent(h.writer.edit(30030, edits, d = "cats", expected = true, explicit = true))
        assertEquals(listOf(
            listOf("d", "cats"), listOf("title", "Kitties"), listOf("emoji", "a", "https://x/a.png"),
            listOf("x-unknown", "keep"), listOf("emoji", "c", "https://x/c2.png"), listOf("emoji", "d", "https://x/d.png")
        ), e.tags)
        // Nothing changed: nothing to write.
        assertTrue(ListCrudManager.emojiSetEdits("Kitties", closed, "Kitties", closed).isEmpty())
    }

    @Test
    fun removingImageEmojisNeverTouchesTheSetsTheListUses() = runBlocking {
        val h = harness()
        val ref = "30030:${hex(7)}:cats"
        val list = h.sign(10030, listOf(listOf("emoji", "a", "https://x/a.png"), listOf("a", ref, "wss://r.example"), listOf("emoji", "b", "https://x/b.png")))
        h.onRelays(list)
        val e = sent(h.writer.edit(10030, listOf(ListEdit.remove("emoji", "a")), expected = true))
        assertEquals(listOf(listOf("a", ref, "wss://r.example"), listOf("emoji", "b", "https://x/b.png")), e.tags)
        // A shortcode already there gets its new image in place.
        val s = sent(h.writer.edit(10030, listOf(ListEdit.set("emoji", "b", "https://x/b2.png")), expected = true))
        assertEquals(listOf(listOf("a", ref, "wss://r.example"), listOf("emoji", "b", "https://x/b2.png")), s.tags)
        // Stop using the set: only its reference goes.
        val u = sent(h.writer.edit(10030, listOf(ListEdit.remove("a", ref)), expected = true))
        assertEquals(listOf(listOf("emoji", "b", "https://x/b2.png")), u.tags)
    }

    @Test
    fun hidingASetPublishesNothingAndMakingItAgainBringsItBack() = runBlocking {
        val h = harness()
        val created = sent(h.writer.createSet(30030, ListCrudManager.slug("My Cats"), "My Cats"))
        assertEquals(listOf(listOf("d", "my-cats"), listOf("title", "My Cats")), created.tags)
        val count = h.relays.published.size
        h.writer.hideSet(30030, "my-cats")
        assertTrue("30030:my-cats" in h.writer.hiddenSets.value)
        assertEquals(count, h.relays.published.size)
        assertTrue(h.relays.published.none { it.kind == 5 })
        assertEquals(ListResult.Unchanged, h.writer.createSet(30030, ListCrudManager.slug("My Cats"), "My Cats"))
        assertFalse(h.store.isHidden(30030, "my-cats"))
        assertEquals(count, h.relays.published.size)
    }
}
