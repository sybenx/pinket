package com.wisp.app.repo

import android.util.Log
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.DmMessage
import com.wisp.app.nostr.Nip09
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Asks relays to drop gift wraps the user deleted (NIP-59: a relay SHOULD delete a kind 1059
 * when its p-tagged recipient publishes a NIP-09 deletion for it). Every stored message's
 * gift wrap is addressed to the user, including their own sent copies, so the user can always
 * sign the request. Relays that don't support it just ignore it; the local copy is already gone.
 */
object DmDeletionPublisher {
    /** e-tags per kind 5 event, keeps each well under common 64 KB event limits. */
    private const val MAX_IDS_PER_EVENT = 200

    // Outlives the screen: deleting a conversation navigates away right after
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun launch(messages: List<DmMessage>, signer: NostrSigner, relayPool: RelayPool) {
        if (messages.isEmpty()) return
        scope.launch { publish(messages, signer, relayPool) }
    }

    suspend fun publish(messages: List<DmMessage>, signer: NostrSigner, relayPool: RelayPool) {
        val ids = messages.map { it.giftWrapId }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return
        val dmRelays = relayPool.getDmRelayUrls().toSet()
        // Relays the wraps came from, beyond the user's own DM inbox relays
        val sourceRelays = messages.flatMap { it.relayUrls }.toSet() - dmRelays
        for (chunk in ids.chunked(MAX_IDS_PER_EVENT)) {
            try {
                val event = signer.signEvent(kind = 5, content = "", tags = Nip09.buildDeletionTags(chunk, 1059))
                val msg = ClientMessage.event(event)
                // Without kind 10050 relays, sent copies went to the write relays
                if (relayPool.hasDmRelays()) relayPool.sendToDmRelays(msg) else relayPool.sendToWriteRelays(msg)
                for (url in sourceRelays) {
                    relayPool.sendToRelayOrEphemeral(url, msg, skipBadCheck = true, write = true)
                }
            } catch (e: Exception) {
                Log.w("DmDeletion", "Couldn't sign or send deletion for ${chunk.size} wraps: ${e.message}")
            }
        }
    }
}
