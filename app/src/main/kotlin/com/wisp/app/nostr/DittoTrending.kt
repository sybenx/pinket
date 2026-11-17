package com.wisp.app.nostr

/** What the trending notes feed ranks by. Ditto has no per-metric ranking, only its own score. */
enum class TrendingWindow(val label: String, val days: Int?) {
    HOT("Hot", null),
    WEEK("Top 7d", 7),
    MONTH("Top 30d", 30),
    YEAR("Top 1y", 365),
    ALL("Top all time", null);

    companion object {
        val DEFAULT = HOT

        /** Saved names from older builds (or anything unknown) fall back to [DEFAULT]. */
        fun fromSaved(name: String?): TrendingWindow =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Trending data from a Ditto relay, all over standard Nostr:
 * - NIP-50 search sort extensions (`sort:hot`, `sort:top`) rank notes.
 * - NIP-32 kind 1985 labels in the `pub.ditto.trends` namespace list trending hashtags (`#t`)
 *   and notes (`#e`), signed by the Ditto server key and refreshed every few minutes.
 */
object DittoTrending {
    const val DEFAULT_RELAY = "wss://relay.ditto.pub"
    const val LABEL_NAMESPACE = "pub.ditto.trends"
    const val KIND_LABEL = 1985

    /** Server key that signs trend labels on relay.ditto.pub. Other Ditto servers use their own. */
    const val DITTO_PUB_TRENDS_PUBKEY = "00df159daeab58ec3818dad10ef751ac4340c63466234ef2e642b9481b141cc2"

    private const val DAY_SECONDS = 24L * 60 * 60

    /** Filter for ranked notes. Hot has no window; Top uses `since` (no `since` means all time). */
    fun notesFilter(window: TrendingWindow, nowSeconds: Long, limit: Int = 100): Filter =
        when (window) {
            TrendingWindow.HOT -> Filter(kinds = listOf(1), search = "sort:hot", limit = limit)
            else -> Filter(
                kinds = listOf(1),
                search = "sort:top",
                since = window.days?.let { nowSeconds - it * DAY_SECONDS },
                limit = limit
            )
        }

    /**
     * Filter for the newest trend label of [labelValue] (`#t` hashtags or `#e` notes).
     * On relay.ditto.pub the signer is known, so only its labels are accepted; on other relays
     * the newest label wins.
     */
    fun labelFilter(relayUrl: String, labelValue: String): Filter = Filter(
        kinds = listOf(KIND_LABEL),
        authors = knownSigner(relayUrl)?.let { listOf(it) },
        bigLTags = listOf(LABEL_NAMESPACE),
        lTags = listOf(labelValue),
        limit = 1
    )

    fun knownSigner(relayUrl: String): String? =
        if (normalizeRelay(relayUrl) == normalizeRelay(DEFAULT_RELAY)) DITTO_PUB_TRENDS_PUBKEY else null

    /** Newest event that is a trend label for [labelValue], or null. */
    fun newestLabel(events: List<NostrEvent>, labelValue: String, relayUrl: String): NostrEvent? {
        val signer = knownSigner(relayUrl)
        return events
            .filter { it.kind == KIND_LABEL && (signer == null || it.pubkey == signer) && isLabel(it, labelValue) }
            .maxByOrNull { it.created_at }
    }

    private fun isLabel(event: NostrEvent, labelValue: String): Boolean =
        event.tags.any { it.size >= 2 && it[0] == "L" && it[1] == LABEL_NAMESPACE } &&
            event.tags.any { it.size >= 2 && it[0] == "l" && it[1] == labelValue }

    /** Hashtags of a `#t` trend label, in the label's order, lowercased and without duplicates. */
    fun parseHashtags(event: NostrEvent): List<String> =
        event.tags.asSequence()
            .filter { it.size >= 2 && it[0] == "t" }
            .map { it[1].trim().removePrefix("#").lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    /** Note ids of an `#e` trend label, in the label's order, without duplicates. */
    fun parseNoteIds(event: NostrEvent): List<String> =
        event.tags.asSequence()
            .filter { it.size >= 2 && it[0] == "e" && it[1].length == 64 }
            .map { it[1].lowercase() }
            .distinct()
            .toList()

    /** Distinct authors of ranked notes, in rank order. */
    fun distinctAuthors(rankedNotes: List<NostrEvent>): List<String> =
        rankedNotes.asSequence().map { it.pubkey }.distinct().toList()

    private fun normalizeRelay(url: String): String = url.trim().trimEnd('/').lowercase()
}
