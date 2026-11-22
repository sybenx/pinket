package com.wisp.app.nostr

/**
 * Abstraction over event signing and NIP-44 encrypt/decrypt.
 * Backed by the locally-stored nsec via [LocalSigner].
 */
interface NostrSigner {
    val pubkeyHex: String
    suspend fun signEvent(kind: Int, content: String, tags: List<List<String>> = emptyList(), createdAt: Long = System.currentTimeMillis() / 1000): NostrEvent
    suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String
    suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String
    /** NIP-04 (`base64?iv=base64`). Only for reading and re-writing list content that already uses it. */
    suspend fun nip04Encrypt(plaintext: String, peerPubkeyHex: String): String
    suspend fun nip04Decrypt(ciphertext: String, peerPubkeyHex: String): String
}

/**
 * Signs events and performs NIP-44 operations locally using the private key.
 */
class LocalSigner(
    private val privkey: ByteArray,
    private val pubkey: ByteArray
) : NostrSigner {
    override val pubkeyHex: String = pubkey.toHex()

    override suspend fun signEvent(kind: Int, content: String, tags: List<List<String>>, createdAt: Long): NostrEvent {
        return NostrEvent.create(
            privkey = privkey,
            pubkey = pubkey,
            kind = kind,
            content = content,
            tags = tags,
            createdAt = createdAt
        )
    }

    override suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String {
        val convKey = Nip44.getConversationKey(privkey, peerPubkeyHex.hexToByteArray())
        return Nip44.encrypt(plaintext, convKey)
    }

    override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String {
        val convKey = Nip44.getConversationKey(privkey, peerPubkeyHex.hexToByteArray())
        return Nip44.decrypt(ciphertext, convKey)
    }

    // java.util.Base64 (same output as android.util.Base64 NO_WRAP) so this also runs in JVM unit tests.
    override suspend fun nip04Encrypt(plaintext: String, peerPubkeyHex: String): String {
        val secret = Nip04.computeSharedSecret(privkey, peerPubkeyHex.hexToByteArray())
        val (ciphertext, iv) = Nip04.encryptRaw(plaintext, secret)
        val enc = java.util.Base64.getEncoder()
        return enc.encodeToString(ciphertext) + "?iv=" + enc.encodeToString(iv)
    }

    override suspend fun nip04Decrypt(ciphertext: String, peerPubkeyHex: String): String {
        val parts = ciphertext.split("?iv=")
        require(parts.size == 2) { "Invalid NIP-04 content format" }
        val dec = java.util.Base64.getMimeDecoder()
        val secret = Nip04.computeSharedSecret(privkey, peerPubkeyHex.hexToByteArray())
        return Nip04.decryptRaw(dec.decode(parts[0]), dec.decode(parts[1]), secret)
    }
}
