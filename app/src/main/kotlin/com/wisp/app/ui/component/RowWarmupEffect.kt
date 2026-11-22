package com.wisp.app.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.LocalTranslationRepository
import kotlinx.coroutines.flow.distinctUntilChanged

private const val WARM_AHEAD = 8
private const val WARM_BEHIND = 4

/**
 * Gets the posts just outside the screen ready before they scroll in (picture sizes, link
 * previews, quoted notes, translation), so they show up at their final height instead of growing
 * once they are on screen. [rows] is what the list shows; [upcoming] is the live list, whose first
 * rows are warmed while nothing is shown yet.
 */
@Composable
fun <T : Any> WarmRowsAhead(
    listState: LazyListState,
    rows: List<T>,
    upcoming: List<T>,
    eventRepo: EventRepository?,
    autoTranslate: Boolean,
    keyOf: (T) -> Any,
    eventOf: (T) -> NostrEvent?
) {
    val context = LocalContext.current.applicationContext
    val translationRepo = LocalTranslationRepository.current
    val autoLoadMedia = LocalMediaSettings.current.autoLoadMedia
    val latestRows by rememberUpdatedState(rows)

    fun warm(item: T) {
        val event = eventOf(item) ?: return
        RowWarmup.warm(context, event, eventRepo, translationRepo, autoLoadMedia, autoTranslate)
    }

    val firstUpcoming = upcoming.firstOrNull()?.let(keyOf)
    LaunchedEffect(rows.isEmpty(), firstUpcoming) {
        if (rows.isEmpty()) upcoming.take(WARM_AHEAD).forEach(::warm)
    }
    LaunchedEffect(listState, autoLoadMedia, autoTranslate) {
        snapshotFlow {
            val items = listState.layoutInfo.visibleItemsInfo
            items.firstOrNull()?.key to items.lastOrNull()?.key
        }.distinctUntilChanged().collect { (firstKey, lastKey) ->
            val list = latestRows
            if (list.isEmpty()) return@collect
            var first = -1
            var last = -1
            for (i in list.indices) {
                val k = keyOf(list[i])
                if (first == -1 && k == firstKey) first = i
                if (k == lastKey) { last = i; break }
            }
            // Header or footer rows of the screen's own can be the first or last thing visible.
            if (last == -1) last = if (first == -1) -1 else first
            if (first == -1) first = 0
            for (i in (last + 1)..minOf(last + WARM_AHEAD, list.lastIndex)) warm(list[i])
            for (i in maxOf(first - WARM_BEHIND, 0) until first) warm(list[i])
        }
    }
}
