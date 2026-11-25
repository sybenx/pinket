package com.wisp.app.repo

import android.app.Application
import com.wisp.app.nostr.Keys
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.Nip09
import com.wisp.app.nostr.Nip25
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReactionRemovalTest {
    private fun signer(seed: Int) = Keys.fromPrivkey(ByteArray(32) { seed.toByte() }).let { LocalSigner(it.privkey, it.pubkey) }

    @Test fun `a reaction its author deletes comes off the count`() = runBlocking {
        val repo = EventRepository()
        val author = signer(1); val fan = signer(2); val stranger = signer(3)
        val note = author.signEvent(1, "hello")
        val heart = fan.signEvent(7, "+", Nip25.buildReactionTags(note))
        val rocket = stranger.signEvent(7, "🚀", Nip25.buildReactionTags(note))
        repo.addEvent(note); repo.addEvent(heart); repo.addEvent(rocket)
        assertEquals(2, repo.getReactionCount(note.id))

        // Someone else's deletion of the fan's heart changes nothing
        repo.addEvent(stranger.signEvent(5, "", Nip09.buildDeletionTags(heart.id, 7)))
        assertEquals(2, repo.getReactionCount(note.id))

        repo.addEvent(fan.signEvent(5, "", Nip09.buildDeletionTags(heart.id, 7)))
        assertEquals(1, repo.getReactionCount(note.id))
        assertEquals(setOf("🚀"), repo.getReactionDetails(note.id).keys)

        // A relay that ignored the deletion sends the heart again: still not counted
        repo.addEvent(heart)
        assertEquals(1, repo.getReactionCount(note.id))
    }

    @Test fun `my own removal from another app clears my heart here`() = runBlocking {
        val repo = EventRepository()
        val me = signer(4); val author = signer(1)
        repo.currentUserPubkey = me.pubkeyHex
        val note = author.signEvent(1, "hello")
        val heart = me.signEvent(7, "+", Nip25.buildReactionTags(note))
        repo.addEvent(note); repo.addEvent(heart)
        assertEquals(setOf("❤️"), repo.getUserReactionEmojis(note.id, me.pubkeyHex))

        repo.addEvent(me.signEvent(5, "", Nip09.buildDeletionTags(heart.id, 7)))
        assertEquals(emptySet<String>(), repo.getUserReactionEmojis(note.id, me.pubkeyHex))
        assertEquals(0, repo.getReactionCount(note.id))
    }
}
