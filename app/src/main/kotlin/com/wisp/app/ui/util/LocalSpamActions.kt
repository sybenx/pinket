package com.wisp.app.ui.util

import androidx.compose.runtime.compositionLocalOf
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.repo.ReplyGate

/**
 * "Spam" / "Not spam" for any post card, provided once at the app root so the
 * many PostCard call sites need no extra parameters.
 */
class SpamActions(
    /** Bumped when the votes change; collect it where a vote should redraw the screen. */
    val version: kotlinx.coroutines.flow.StateFlow<Int>,
    /** True when the post menu should offer a vote for this author (not you, not someone you follow). */
    val canVote: (authorPubkey: String) -> Boolean,
    /** Your current vote on the author, if any. */
    val authorVote: (authorPubkey: String) -> ReplyGate.OwnVote?,
    /**
     * True when a post should be folded away where posts are listed: you
     * voted it spam, or enough of the people you follow mute or report its
     * author. Memory lookups only, safe while composing.
     */
    val isFlagged: (NostrEvent) -> Boolean,
    val markSpam: (NostrEvent) -> Unit,
    val markNotSpam: (NostrEvent) -> Unit
)

val LocalSpamActions = compositionLocalOf<SpamActions?> { null }
