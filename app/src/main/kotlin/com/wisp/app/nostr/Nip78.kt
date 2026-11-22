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

    /** The tags after `d` of a first backup (an existing backup keeps its own tags, see ListWriter.setAppData). */
    val NWC_BACKUP_FIRST_TAGS: List<List<String>> = listOf(
        listOf("client", "Pinket"),
        listOf("encryption", "nip44")
    )

    /**
     * The content of your NWC backup holding [uri] (NIP-44 to yourself), or null when there is nothing to write:
     * [old] (your newest backup) already holds that URI, it is not yours, or [uri] is blank. Signed only by
     * ListWriter.setAppData, which keeps every tag of [old].
     */
    suspend fun nwcBackupContent(signer: NostrSigner, old: NostrEvent?, uri: String): String? {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return null
        if (old != null) {
            if (!old.pubkey.equals(signer.pubkeyHex, ignoreCase = true)) return null
            if (decryptNwcBackup(signer, old) == trimmed) return null
        }
        return signer.nip44Encrypt(trimmed, signer.pubkeyHex)
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
