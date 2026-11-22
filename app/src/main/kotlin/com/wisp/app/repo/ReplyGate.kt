package com.wisp.app.repo

/**
 * The one place that decides whether a reply from someone the user does not
 * follow is hidden. Shared by the thread view and notifications.
 *
 * Callers only consult this for authors the spam filter covers: not the user,
 * not followed, and only while the spam filter is on.
 *
 * Inputs:
 * - [OwnVote]: the user's own "Spam" / "Not spam" vote on the author or on
 *   what the post looks like (see [SpamVotesSnapshot.voteFor]).
 * - flaggedBy (m): how many of the user's follows mute the author (public
 *   mute list `p` tags) or reported them as spam, each follow counted once.
 *   Null when not known yet.
 * - followedBy (f): how many of the user's follows follow the author.
 *   Null when not known yet.
 *
 * Rules, in order:
 * 0. The user's own vote wins outright: "Spam" hides, "Not spam" keeps.
 * 1. Flagged by your follows: m >= 3 and m * 2 >= f -> hide.
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

    enum class OwnVote { SPAM, NOT_SPAM }

    enum class Verdict {
        /** Hide the reply (spam toggle / hidden list in notifications). */
        HIDE,
        /** Show the reply. */
        KEEP,
        /** The model has to decide but has no score yet: show it and score the author. */
        ASK_MODEL
    }

    /** True when a model score alone means spam. The only place the threshold is applied. */
    fun isSpamScore(score: Float): Boolean = score >= SPAM_THRESHOLD

    /** Rule 3 on its own. */
    fun modelVerdict(modelScore: Float?): Verdict = when {
        modelScore == null -> Verdict.ASK_MODEL
        isSpamScore(modelScore) -> Verdict.HIDE
        else -> Verdict.KEEP
    }

    /**
     * Graph-only part of the decision (rules 1 and 2). Returns null when the
     * graph has no say and the model should decide.
     */
    fun networkVerdict(
        flaggedBy: Int?,
        followedBy: Int?,
        muteSignalEnabled: Boolean,
        followSignalEnabled: Boolean
    ): Verdict? {
        if (muteSignalEnabled && flaggedBy != null && followedBy != null &&
            flaggedBy >= MIN_MUTED_BY && flaggedBy * MUTE_RATIO >= followedBy
        ) return Verdict.HIDE
        if (followSignalEnabled && followedBy != null && followedBy >= MIN_FOLLOWED_BY) {
            return Verdict.KEEP
        }
        return null
    }

    /**
     * Rules 0 to 2: everything that does not need the model. Returns null when
     * the model should decide. Call sites use this to skip scoring authors that
     * are already settled.
     */
    fun settledVerdict(
        ownVote: OwnVote?,
        flaggedBy: Int?,
        followedBy: Int?,
        muteSignalEnabled: Boolean,
        followSignalEnabled: Boolean
    ): Verdict? = when (ownVote) {
        OwnVote.SPAM -> Verdict.HIDE
        OwnVote.NOT_SPAM -> Verdict.KEEP
        null -> networkVerdict(flaggedBy, followedBy, muteSignalEnabled, followSignalEnabled)
    }

    fun decide(
        ownVote: OwnVote?,
        flaggedBy: Int?,
        followedBy: Int?,
        modelScore: Float?,
        muteSignalEnabled: Boolean,
        followSignalEnabled: Boolean
    ): Verdict =
        settledVerdict(ownVote, flaggedBy, followedBy, muteSignalEnabled, followSignalEnabled)
            ?: modelVerdict(modelScore)
}
