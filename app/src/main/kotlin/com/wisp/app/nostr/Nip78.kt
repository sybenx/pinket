package com.wisp.app.nostr

/**
 * NIP-78: Arbitrary custom app data (kind 30078).
 * Used for the encrypted NWC connection backup: the URI is NIP-44 encrypted
 * to the user's own pubkey and published as an addressable event to relays.
 */
object Nip78 {
    const val KIND = 30078

    // ─── NWC connection backup ────────────────────────────────────────
    //
    // Cross-platform NWC URI backup per `NWC_BACKUP_PARITY.md`. iOS
    // and Android both publish to / read from the same flat `d` tag
    // (`nwc-wallet-backup` — no `wisp-` prefix, intentional, for
    // cross-platform interop). Content is the raw NWC URI string,
    // NIP-44 v2 encrypted to self.

    const val NWC_BACKUP_D_TAG = "nwc-wallet-backup"

    /** Build + sign a kind-30078 event carrying the encrypted NWC URI. */
    suspend fun createNwcBackupEvent(signer: NostrSigner, uri: String): NostrEvent {
        val encrypted = signer.nip44Encrypt(uri.trim(), signer.pubkeyHex)
        val tags = listOf(
            listOf("d", NWC_BACKUP_D_TAG),
            listOf("client", "Wisp"),
            listOf("encryption", "nip44")
        )
        return signer.signEvent(kind = KIND, content = encrypted, tags = tags)
    }

    /**
     * Decrypt a kind-30078 NWC-backup event and return the raw URI.
     * Returns null when the content is empty, decrypt fails, or the
     * plaintext doesn't look like a `nostr+walletconnect://` URI.
     */
    suspend fun decryptNwcBackup(signer: NostrSigner, event: NostrEvent): String? {
        if (event.content.isBlank()) return null
        return try {
            val decrypted = signer.nip44Decrypt(event.content, event.pubkey).trim()
            if (decrypted.startsWith("nostr+walletconnect://", ignoreCase = true)) decrypted else null
        } catch (_: Exception) {
            null
        }
    }

    /** Filter to fetch the user's NWC backup (single addressable event). */
    fun nwcBackupFilter(pubkeyHex: String): Filter = Filter(
        kinds = listOf(KIND),
        authors = listOf(pubkeyHex),
        dTags = listOf(NWC_BACKUP_D_TAG),
        limit = 1
    )
}
