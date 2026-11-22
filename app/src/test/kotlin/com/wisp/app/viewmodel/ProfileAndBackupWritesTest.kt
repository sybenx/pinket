package com.wisp.app.viewmodel

import android.app.Application
import com.wisp.app.R
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.ListWrite
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.Nip78
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The profile editor, onboarding profile and the wallet backup, as their call sites use the list writer: only the
 * fields the user changed go out, on your newest version; the backup is written only when it would change. Fake
 * relays, a real LocalSigner on a throwaway key generated here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ProfileAndBackupWritesTest {
    private val w1 = "wss://w1.example"
    private val w2 = "wss://w2.example"
    private val idx = "wss://idx.example"
    private val uriA = "nostr+walletconnect://" + "a".repeat(64) + "?relay=wss://r.example&secret=" + "1".repeat(64)
    private val uriB = "nostr+walletconnect://" + "b".repeat(64) + "?relay=wss://r.example&secret=" + "2".repeat(64)

    private class FakeRelays(val urls: Set<String>) : ListSource, ListSink {
        val held = mutableMapOf<String, MutableList<NostrEvent>>()
        var online = urls.toMutableSet()
        val published = mutableListOf<NostrEvent>()

        fun put(e: NostrEvent) { for (u in urls) held.getOrPut(u) { mutableListOf() }.add(e) }

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
            for (u in sent) held.getOrPut(u) { mutableListOf() }.add(event)
            return ListPublishResult(sent, sent)
        }
    }

    private class Clock(var now: Long)

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
        fun sign(kind: Int, tags: List<List<String>>, content: String, at: Long = now - 100): NostrEvent =
            runBlocking { signer.signEvent(kind, content, tags, at) }
    }

    private fun harness(): Harness = runBlocking {
        val kp = Keys.generate()
        val signer = LocalSigner(kp.privkey, kp.pubkey)
        val relays = FakeRelays(setOf(ListWriter.norm(w1), ListWriter.norm(w2), ListWriter.norm(idx)))
        val clock = Clock(1_000_000L)
        val store = OwnListStore(signer.pubkeyHex, MemoryOwnListStorage(), clock = { clock.now }, entryId = { ListWriter.entryId(it) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val writer = ListWriter(
            source = relays,
            sink = relays,
            account = { ListAccount(signer, store) },
            activePubkey = { signer.pubkeyHex },
            poolWriteRelays = { listOf(w1, w2) },
            scope = scope,
            clock = { clock.now },
            indexerRelays = listOf(idx),
            work = EmptyCoroutineContext
        )
        val messages = Collections.synchronizedList(mutableListOf<Int>())
        scope.launch { writer.messages.collect { messages += it } }
        val rl = signer.signEvent(10002, "", listOf(listOf("r", w1), listOf("r", w2)), clock.now - 5000)
        assertTrue(store.seen(rl))
        Harness(relays, signer, store, writer, clock, messages)
    }

    private fun sent(r: ListResult): NostrEvent = (r as? ListResult.Sent)?.event ?: error("not sent: $r")

    private fun profile(content: String, at: Long = 1): ProfileData =
        ProfileData.fromEvent(NostrEvent("", "00".repeat(32), at, 0, emptyList(), content, ""))!!

    // ------------------------------------------------------------------ editor diff (pure)

    @Test
    fun onlyTheFieldsTheUserChangedAreSent() {
        val start = profile("""{"name":"alice","display_name":"Alice A","about":"hi","lud16":"a@b.example","nip05":"alice@x.example"}""")
        val form = ProfileForm.of(start).copy(about = "hello", lud16 = "")
        assertEquals(mapOf("about" to "hello", "lud16" to ""), ProfileEdits.changes(start, form))
        assertEquals(emptyMap<String, String?>(), ProfileEdits.changes(start, ProfileForm.of(start)))
    }

    @Test
    fun theNameFieldChangesTheFieldsThatHeldTheShownName() {
        // A display name of its own: only it changes, the name (handle) stays.
        val distinct = profile("""{"name":"alice","display_name":"Alice A"}""")
        assertEquals(mapOf("display_name" to "Alice B"), ProfileEdits.changes(distinct, ProfileForm.of(distinct).copy(name = "Alice B")))
        // Both the same (as this editor wrote them): both change.
        val same = profile("""{"name":"Alice","display_name":"Alice"}""")
        assertEquals(mapOf("display_name" to "Bob", "name" to "Bob"), ProfileEdits.changes(same, ProfileForm.of(same).copy(name = "Bob")))
        // Only a name (blank display name): only the name.
        val onlyName = profile("""{"name":"alice","display_name":""}""")
        assertEquals(mapOf("name" to "al"), ProfileEdits.changes(onlyName, ProfileForm.of(onlyName).copy(name = "al")))
        // No profile yet: both, as before.
        assertEquals(mapOf("display_name" to "New", "name" to "New"), ProfileEdits.changes(null, ProfileForm(name = "New")))
    }

    @Test
    fun aNewerProfileNeverOverwritesWhatIsBeingTyped() {
        val start = profile("""{"name":"alice","about":"hi","picture":"https://p.example/1.png"}""", at = 1)
        val typed = ProfileForm.of(start).copy(about = "typing")
        val next = profile("""{"name":"alice2","about":"elsewhere","picture":"https://p.example/2.png"}""", at = 2)
        val shown = ProfileEdits.rebase(start, typed, next)
        assertEquals(ProfileForm(name = "alice2", about = "typing", picture = "https://p.example/2.png"), shown)
        // After the rebase only the typed field is a change.
        assertEquals(mapOf("about" to "typing"), ProfileEdits.changes(next, shown))
    }

    // ------------------------------------------------------------------ editor through the writer

    @Test
    fun theEditorSaveKeepsUnknownFieldsTagsAndChangesElsewhere() = runBlocking {
        val h = harness()
        // The editor opened with this version...
        val opened = h.sign(0, listOf(listOf("i", "github:alice", "proof")),
            """{"name":"alice","display_name":"Alice A","about":"hi","x_unknown":{"a":[1,2]},"lud16":"a@b.example"}""", at = h.now - 500)
        // ...and another app changed the about and the picture since (newest on the relays, with a slow clock here).
        val newest = h.sign(0, opened.tags,
            """{"name":"alice","display_name":"Alice A","about":"from elsewhere","x_unknown":{"a":[1,2]},"lud16":"a@b.example","picture":"https://p.example/x.png"}""",
            at = h.now + 200)
        h.relays.put(opened); h.relays.put(newest)
        val start = ProfileData.fromEvent(opened)
        val changes = ProfileEdits.changes(start, ProfileForm.of(start).copy(lud16 = "alice@w.example"))
        assertEquals(mapOf("lud16" to "alice@w.example"), changes)

        val r = sent(h.writer.setProfileText(changes, expected = start != null))
        assertEquals(newest.created_at + 1, r.created_at)
        assertEquals(newest.tags, r.tags)
        val n = ListWrite.profileFields(r.content)!!
        assertEquals(listOf("name", "display_name", "about", "x_unknown", "lud16", "picture"), n.keys.toList())
        assertEquals(JsonPrimitive("from elsewhere"), n["about"])
        assertEquals(JsonPrimitive("https://p.example/x.png"), n["picture"])
        assertEquals(JsonPrimitive("alice@w.example"), n["lud16"])
        assertEquals(ListWrite.profileFields(newest.content)!!["x_unknown"], n["x_unknown"])
        assertEquals(1, h.relays.published.size)

        // Saving the same again writes nothing.
        assertEquals(ListResult.Unchanged, h.writer.setProfileText(changes, expected = true))
        assertEquals(1, h.relays.published.size)
    }

    @Test
    fun aMissingProfileSaysProfileNotList() = runBlocking {
        val h = harness()
        val r = h.writer.setProfileText(mapOf("about" to "x"), expected = true)
        assertEquals(ListResult.Reason.NOT_FOUND, (r as ListResult.Refused).reason)
        assertEquals(listOf(R.string.profile_not_found), h.messages.toList())
        assertTrue(h.relays.published.isEmpty())
    }

    @Test
    fun aQuietProfileTryShowsNothing() = runBlocking {
        val h = harness()
        h.relays.online.clear()
        val r = h.writer.setProfileText(mapOf("name" to "bob"), onlyIfMissing = true, quiet = true)
        assertEquals(ListResult.Reason.NOT_SENT, (r as ListResult.Refused).reason)
        assertTrue(h.messages.isEmpty())
        // The last try is not quiet.
        h.writer.setProfileText(mapOf("name" to "bob"), onlyIfMissing = true)
        assertEquals(listOf(R.string.list_not_sent), h.messages.toList())
    }

    @Test
    fun onboardingKeepsAProfileMadeElsewhere() = runBlocking {
        val h = harness()
        val existing = h.sign(0, emptyList(), """{"name":"made elsewhere","about":"keep"}""")
        h.relays.put(existing)
        assertEquals(ListResult.Unchanged, h.writer.setProfileText(mapOf("name" to "new", "about" to "x"), onlyIfMissing = true))
        assertTrue(h.relays.published.isEmpty())
    }

    // ------------------------------------------------------------------ wallet backup (30078)

    @Test
    fun theWalletBackupIsWrittenOnlyWhenItWouldChange() = runBlocking {
        val h = harness()
        val first = sent(h.writer.setAppData(Nip78.NWC_BACKUP_D_TAG, Nip78.NWC_BACKUP_FIRST_TAGS, quiet = true) { old ->
            Nip78.nwcBackupContent(h.signer, old, uriA)
        })
        assertEquals(listOf(listOf("d", Nip78.NWC_BACKUP_D_TAG)) + Nip78.NWC_BACKUP_FIRST_TAGS, first.tags)
        assertEquals(uriA, Nip78.decryptNwcBackup(h.signer, first))

        // Reconnect with the same wallet: the newest backup already holds it, nothing is written.
        h.now += 60
        val again = h.writer.setAppData(Nip78.NWC_BACKUP_D_TAG, Nip78.NWC_BACKUP_FIRST_TAGS, quiet = true) { old ->
            Nip78.nwcBackupContent(h.signer, old, " $uriA ")
        }
        assertEquals(ListResult.Unchanged, again)
        assertEquals(1, h.relays.published.size)
        assertTrue(h.messages.isEmpty())
    }

    @Test
    fun aNewWalletKeepsTheTagsOfTheNewestBackup() = runBlocking {
        val h = harness()
        val tags = listOf(listOf("d", Nip78.NWC_BACKUP_D_TAG), listOf("client", "Other app"), listOf("encryption", "nip44"), listOf("x-extra", "1"))
        val other = h.sign(30078, tags, runBlocking { h.signer.nip44Encrypt(uriA, h.me) }, at = h.now + 100)
        h.relays.put(other)
        val r = sent(h.writer.setAppData(Nip78.NWC_BACKUP_D_TAG, Nip78.NWC_BACKUP_FIRST_TAGS, quiet = true) { old ->
            Nip78.nwcBackupContent(h.signer, old, uriB)
        })
        assertEquals(tags, r.tags)
        assertEquals(other.created_at + 1, r.created_at)
        assertEquals(uriB, Nip78.decryptNwcBackup(h.signer, r))
    }

    @Test
    fun backupContentIsNothingForAnotherKeysBackupOrABlankUri() = runBlocking {
        val h = harness()
        val kp = Keys.generate()
        val stranger = LocalSigner(kp.privkey, kp.pubkey)
        val theirs = stranger.signEvent(30078, stranger.nip44Encrypt(uriA, stranger.pubkeyHex), listOf(listOf("d", Nip78.NWC_BACKUP_D_TAG)))
        assertNull(Nip78.nwcBackupContent(h.signer, theirs, uriB))
        assertNull(Nip78.nwcBackupContent(h.signer, null, "  "))
        assertNotNull(Nip78.nwcBackupContent(h.signer, null, uriA))
    }
}
