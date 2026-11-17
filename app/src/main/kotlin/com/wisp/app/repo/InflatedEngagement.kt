package com.wisp.app.repo

/**
 * Spots engagement that was manufactured rather than earned. Trending relays rank by raw
 * counts, so one account reacting over and over, or a group that reacts and reposts in
 * lockstep, can put a post at the top of the trending feed.
 */
object InflatedEngagement {
    // Below this many reactions a lopsided tally is normal (a friend double-tapping), not inflation
    const val MIN_REACTIONS = 10
    // Coordinated engagement: at least half of 15+ reactors also reposting within 2 minutes.
    // Organic trending posts run 5-30%; engagement rings and point-scoring games run ~70%.
    const val COORDINATED_MIN_REACTORS = 15
    const val PAIR_WINDOW_SECS = 120L

    /**
     * @param reactions pubkey -> number of reactions that account sent
     * @param reactedAt pubkey -> time of that account's first reaction
     * @param repostedAt pubkey -> time of that account's first repost
     */
    fun isInflated(
        reactions: Map<String, Int>,
        reactedAt: Map<String, Long>,
        repostedAt: Map<String, Long>
    ): Boolean {
        val counts = reactions.values
        val total = counts.sum()
        if (total >= MIN_REACTIONS && (counts.maxOrNull() ?: 0) * 2 > total) return true

        val reactors = reactedAt.size
        if (reactors < COORDINATED_MIN_REACTORS) return false
        val paired = reactedAt.count { (pubkey, reacted) ->
            val reposted = repostedAt[pubkey] ?: return@count false
            kotlin.math.abs(reposted - reacted) <= PAIR_WINDOW_SECS
        }
        return paired * 2 >= reactors
    }
}
