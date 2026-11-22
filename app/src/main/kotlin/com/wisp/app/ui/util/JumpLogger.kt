package com.wisp.app.ui.util

import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.wisp.app.BuildConfig

/**
 * Debug and staging builds only: logs every time a row that is already on screen changes height, or moves
 * while nobody is scrolling. `adb logcat -s NoJump` lists what made a list jump.
 */
@Composable
fun JumpLogger(listState: LazyListState, name: String) {
    if (!BuildConfig.DEBUG && BuildConfig.BUILD_TYPE != "staging") return
    LaunchedEffect(listState, name) {
        var prev: Map<Any, Pair<Int, Int>> = emptyMap()
        var prevFirst: Any? = null
        // The frame a scroll ends in still carries its last movement, so a move only counts
        // when the list was already at rest in the frame before.
        var wasScrolling = false
        snapshotFlow {
            val info = listState.layoutInfo
            Triple(
                info.visibleItemsInfo.associate { it.key to (it.offset to it.size) },
                listState.isScrollInProgress,
                info.viewportStartOffset
            )
        }.collect { (now, scrolling, viewportStart) ->
            val first = now.entries.minByOrNull { it.value.first }?.key
            for ((key, v) in now) {
                val old = prev[key] ?: continue
                val (offset, size) = v
                val k = key.toString().take(12)
                if (old.second != size) {
                    val above = offset < viewportStart
                    Log.w(
                        "NoJump",
                        "$name RESIZE $k ${old.second}->$size (${size - old.second}) " +
                            "top=$offset scrolling=$scrolling" + if (above) " cutByTop" else ""
                    )
                } else if (!scrolling && !wasScrolling && old.first != offset) {
                    Log.w("NoJump", "$name SHIFT $k ${old.first}->$offset (${offset - old.first})")
                }
            }
            if (!scrolling && !wasScrolling && prevFirst != null && first != prevFirst && prevFirst in now.keys) {
                Log.w("NoJump", "$name NEWFIRST ${first.toString().take(12)} was ${prevFirst.toString().take(12)}")
            }
            prev = now
            wasScrolling = scrolling
            prevFirst = first
        }
    }
}
