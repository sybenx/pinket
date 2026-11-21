package com.wisp.app.repo

/**
 * The one place that decides whether a reply from someone the user does not
 * follow is hidden. Shared by the thread view and notifications.
 *
 * Callers only consult this for authors the spam model would already
 * consider: not the user, not followed, not on the "Not spam" safelist, and
 * only while the spam filter is on.
 *
 * Inputs from the user's social graph:
 * - [mutedBy] (m): how many of the user's follows list the author in their
 *   public mute list (kind 10000 `p` tags). Null when not known yet.
 * - [followedBy] (f): how many of the user's follows follow the author.
 *   Null when not known yet.
 *
 * Rules, in order:
 * 1. Muted by your follows: m >= 3 and m * 2 >= f -> hide.
 * 2. Followed by your follows: f >= 3 -> keep, the model is not run.
 * 3. Otherwise the spam model decides (score >= 0.7 hides).
 *
 * Missing graph data turns rules 1 and 2 off, so behaviour equals the model alone.
 * The thresholds come from a measurement on real data; keep them as is.
 */
object ReplyGate {
    const val MIN_MUTED_BY = 3
    const val MUTE_RATIO = 2
    const val MIN_FOLLOWED_BY = 3
    const val SPAM_THRESHOLD = 0.7f

    enum class Verdict {
        /** Hide the reply (spam toggle / dropped from notifications). */
        HIDE,
        /** Show the reply. */
        KEEP,
        /** The model has to decide but has no score yet: show it and score the author. */
        ASK_MODEL
    }

    /**
     * Graph-only part of the decision. Returns null when the graph has no say
     * and the model should decide. Call sites use this to skip scoring authors
     * the graph already settled.
     */
    fun networkVerdict(
        mutedBy: Int?,
        followedBy: Int?,
        muteSignalEnabled: Boolean,
        followSignalEnabled: Boolean
    ): Verdict? {
        if (muteSignalEnabled && mutedBy != null && followedBy != null &&
            mutedBy >= MIN_MUTED_BY && mutedBy * MUTE_RATIO >= followedBy
        ) return Verdict.HIDE
        if (followSignalEnabled && followedBy != null && followedBy >= MIN_FOLLOWED_BY) {
            return Verdict.KEEP
        }
        return null
    }

    fun decide(
        mutedBy: Int?,
        followedBy: Int?,
        modelScore: Float?,
        muteSignalEnabled: Boolean,
        followSignalEnabled: Boolean
    ): Verdict {
        networkVerdict(mutedBy, followedBy, muteSignalEnabled, followSignalEnabled)?.let { return it }
        if (modelScore == null) return Verdict.ASK_MODEL
        return if (modelScore >= SPAM_THRESHOLD) Verdict.HIDE else Verdict.KEEP
    }
}
