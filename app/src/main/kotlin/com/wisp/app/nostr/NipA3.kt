package com.wisp.app.nostr

import com.wisp.app.util.Monero

/**
 * NIP-A3 payment targets: a replaceable kind 10133 event whose tags list where an
 * account can be paid, as `["payto", "<type>", "<address>"]`. Types are lowercase
 * per the spec; we lowercase when reading to be lenient, and also accept `xmr`
 * as an alias for `monero`.
 *
 * Lightning addresses still come from the profile's `lud16`; this list is only
 * read for the types Pinket knows how to pay (Monero today).
 */
object NipA3 {
    const val KIND_PAYMENT_TARGETS = 10133
    const val TAG_PAYTO = "payto"
    const val TYPE_MONERO = "monero"
    private val MONERO_TYPES = setOf(TYPE_MONERO, "xmr")

    data class PayTarget(val type: String, val address: String)

    private fun isPayto(tag: List<String>) = tag.size >= 3 && tag[0] == TAG_PAYTO

    private fun isMoneroTag(tag: List<String>) =
        isPayto(tag) && tag[1].trim().lowercase() in MONERO_TYPES

    /** Every well-formed payto entry in [tags], type lowercased, address trimmed. */
    fun parseTargets(tags: List<List<String>>): List<PayTarget> =
        tags.filter(::isPayto).mapNotNull { tag ->
            val type = tag[1].trim().lowercase()
            val address = tag[2].trim()
            if (type.isEmpty() || address.isEmpty()) null else PayTarget(type, address)
        }

    /** First Monero address in [tags] that passes [Monero.isValidAddress], or null. */
    fun moneroAddress(tags: List<List<String>>): String? =
        parseTargets(tags).firstOrNull { it.type in MONERO_TYPES && Monero.isValidAddress(it.address) }?.address

    /** First Monero entry in [tags] even if it does not validate (for the editor field). */
    fun firstMoneroEntry(tags: List<List<String>>): String? =
        parseTargets(tags).firstOrNull { it.type in MONERO_TYPES }?.address

    /**
     * Tags to publish when the owner sets their Monero address to [address] (null
     * clears it), given the newest existing list's [existingTags] (null when the
     * account has none). Returns null when nothing should be published: no existing
     * list and nothing to add, or the list already says exactly this.
     */
    fun planMoneroUpdate(existingTags: List<List<String>>?, address: String?): List<List<String>>? {
        if (existingTags == null) {
            return if (address == null) null else listOf(listOf(TAG_PAYTO, TYPE_MONERO, address))
        }
        val updated = withMoneroAddress(existingTags, address)
        return if (updated == existingTags) null else updated
    }

    /**
     * Returns [tags] with only the Monero entry changed. Every other tag (other payto
     * types, unknown tags) is kept as-is and in order. All existing `monero`/`xmr`
     * entries are dropped; when [address] is non-null a single
     * `["payto","monero",address]` takes the place of the first one removed, or is
     * appended when there was none.
     */
    fun withMoneroAddress(tags: List<List<String>>, address: String?): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var inserted = false
        for (tag in tags) {
            if (isMoneroTag(tag)) {
                if (address != null && !inserted) {
                    out.add(listOf(TAG_PAYTO, TYPE_MONERO, address))
                    inserted = true
                }
                continue
            }
            out.add(tag)
        }
        if (address != null && !inserted) out.add(listOf(TAG_PAYTO, TYPE_MONERO, address))
        return out
    }
}
