package com.wisp.app.ui.util

import android.os.SystemClock
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Keeps the rows that are on screen exactly where they are while the list behind them changes.
 *
 * A live list (posts streaming in from many places) gets new rows in the middle, rows that move,
 * and rows that go away. Shown as is, every one of those pushes what the reader is looking at.
 * This holder sits between the live list and the LazyColumn and only lets changes through where
 * they cannot be seen:
 *
 *  - new rows above the first row on screen go in at once (the list keeps its place by key)
 *  - new rows below the last row on screen go in at once
 *  - a new row that belongs between two rows on screen waits until that spot has scrolled away
 *  - a row on screen that moved or was removed stays put until it has scrolled away
 *  - a load that arrives in pieces is gathered and shown in one go, not row by row
 *
 * The holder owns the LazyListState. Keep it somewhere that outlives the screen (a view model) so
 * coming back from another screen lands on the same row, not on whatever now has the same index.
 */
@Stable
class StableListHolder<T : Any>(
    /** True when the first rows come from memory in one piece and can be shown without waiting. */
    private val showFirstAtOnce: Boolean = false,
    private val keyOf: (T) -> Any
) {
    val listState = LazyListState()

    /** What the LazyColumn should show. */
    var shown: List<T> by mutableStateOf(emptyList())
        private set

    /** Rows that exist but are waiting for their spot to leave the screen. */
    var held: Int by mutableIntStateOf(0)
        private set

    /** The part of [held] that is new rows waiting above the first row, under a screen's header. */
    var heldAbove: Int by mutableIntStateOf(0)
        private set

    private var source: List<T> = emptyList()
    private var context: Any? = null
    private var hasContext = false
    private var dirty = false
    private var gatherDeadline = 0L

    private var changeUntil = 0L
    private var changeOnReturn = false

    /**
     * A row that must not move even when rows above it change (the note a thread was opened on,
     * while the notes it replies to are still arriving). Null once the reader has scrolled.
     */
    var pinKey: Any? = null

    /** Show the live list as it is right now. For when the reader asked for it (a tap). */
    fun syncNow() = adopt(source)

    /**
     * The reader just changed the list themselves (deleted a post, blocked or muted someone,
     * opened a folded branch). For the next moment the live list is shown as it is, since the
     * change on screen is the one they asked for.
     */
    fun expectChange() {
        changeUntil = SystemClock.uptimeMillis() + CHANGE_WINDOW_MS
    }

    /**
     * The reader is leaving this screen to change the list (writing a reply). The change is let
     * through when they come back, however long they were away.
     */
    fun expectChangeOnReturn() {
        changeOnReturn = true
    }

    internal fun onBound() {
        if (changeOnReturn) {
            changeOnReturn = false
            expectChange()
        }
    }

    private fun adopt(list: List<T>) {
        shown = list
        held = 0
        heldAbove = 0
        dirty = false
        gatherDeadline = 0L
    }

    /** Waits for a load to go quiet, at most until the deadline, so it shows in one go. */
    private suspend fun gather() {
        val now = SystemClock.uptimeMillis()
        if (gatherDeadline == 0L) gatherDeadline = now + GATHER_MAX_MS
        val wait = minOf(GATHER_QUIET_MS, gatherDeadline - now)
        if (wait > 0) delay(wait)
    }

    /** Called with every new version of the live list. A newer call cancels an older one. */
    internal suspend fun offer(newSource: List<T>, newContext: Any?) {
        val contextChanged = hasContext && newContext != context
        hasContext = true
        context = newContext
        source = newSource

        if (contextChanged) {
            // A different list altogether (another feed, another account). Nothing to hold on to.
            shown = emptyList()
            held = 0
            heldAbove = 0
            dirty = false
            gatherDeadline = 0L
        }
        if (SystemClock.uptimeMillis() < changeUntil) {
            adopt(source)
            return
        }
        if (shown.isEmpty()) {
            if (newSource.isEmpty()) return
            if (!showFirstAtOnce) gather()
            adopt(source)
            return
        }
        if (newSource.isEmpty()) {
            // Cleared, usually just before a refill. Keep what is there for a moment.
            delay(GATHER_MAX_MS)
            adopt(source)
            return
        }
        if (merge()) {
            gatherDeadline = 0L
            return
        }
        // None of the rows on screen are in the list any more.
        val keys = HashSet<Any>(newSource.size * 2)
        newSource.forEach { keys.add(keyOf(it)) }
        if (shown.any { keyOf(it) in keys }) {
            // Only those rows went away (deleted, blocked). The rest of the list is the same one.
            adopt(source)
            return
        }
        // A different set of rows altogether: a reload. Swap once it has gone quiet.
        gather()
        adopt(source)
        listState.requestScrollToItem(0)
    }

    internal fun onViewportMoved() {
        if (dirty && !merge()) adopt(source)
    }

    /** Returns false when the rows on screen are no longer part of the live list. */
    private fun merge(): Boolean {
        val src = source
        val cur = shown
        if (cur === src) {
            held = 0
            heldAbove = 0
            dirty = false
            return true
        }
        val curIndex = HashMap<Any, Int>(cur.size * 2)
        cur.forEachIndexed { i, t -> curIndex[keyOf(t)] = i }

        var first = Int.MAX_VALUE
        var last = -1
        var anchorIsOurs = true
        var pinIndex = -1
        var pinOffset = 0
        val pin = pinKey
        val anchorLazyIndex = listState.firstVisibleItemIndex
        for (v in listState.layoutInfo.visibleItemsInfo) {
            val i = curIndex[v.key]
            if (i == null) {
                if (v.index == anchorLazyIndex && last == -1) anchorIsOurs = false
                continue
            }
            if (i < first) first = i
            if (i > last) last = i
            if (pin != null && v.key == pin) {
                pinIndex = i
                pinOffset = v.offset
            }
        }
        // With a pinned row on screen, only it and the rows below it are held; rows above it may
        // change, and the scroll position is moved so the pinned row stays where it is.
        val pinned = pinIndex >= 0 && srcHas(src, pin)
        if (pinned) first = pinIndex
        if (last == -1) {
            // None of our rows are on screen, so nothing can be seen moving.
            adopt(src)
            return true
        }

        val srcIndex = HashMap<Any, Int>(src.size * 2)
        src.forEachIndexed { i, t -> srcIndex[keyOf(t)] = i }
        // Rows on screen keep their place, but show their newest version.
        val run = cur.subList(first, last + 1).map { srcIndex[keyOf(it)]?.let(src::get) ?: it }
        val runKeys = HashSet<Any>(run.size * 2)
        run.forEach { runKeys.add(keyOf(it)) }

        val positions = run.mapNotNull { srcIndex[keyOf(it)] }
        if (positions.isEmpty()) return false
        // Rows on screen that moved elsewhere in the live list must not drag the rest with them:
        // place the block by the rows that kept their order.
        val steady = longestIncreasingRun(positions)
        val minPos = steady.first()
        val maxPos = steady.last()

        val before = ArrayList<T>(minPos)
        val after = ArrayList<T>((src.size - maxPos).coerceAtLeast(0))
        var waiting = 0
        var above = 0
        for (i in src.indices) {
            val item = src[i]
            if (keyOf(item) in runKeys) continue
            when {
                i < minPos -> before.add(item)
                i > maxPos -> after.add(item)
                else -> waiting++
            }
        }
        if (!anchorIsOurs && first == 0) {
            // Something of the screen's own (a header) sits above our first row and is what the
            // list holds on to, so a row put in above ours would push ours down.
            waiting += before.size
            above = before.size
            before.clear()
        }

        val out = ArrayList<T>(before.size + run.size + after.size)
        out.addAll(before)
        out.addAll(run)
        out.addAll(after)

        var same = out.size == src.size
        if (same) {
            for (i in out.indices) {
                if (keyOf(out[i]) != keyOf(src[i])) {
                    same = false
                    break
                }
            }
        }
        val aboveChanged = pinned && !sameKeys(cur, pinIndex, out, before.size)
        if (same) {
            adopt(src)
        } else {
            shown = out
            held = waiting
            heldAbove = above
            dirty = true
        }
        if (aboveChanged) listState.requestScrollToItem(before.size, -pinOffset)
        return true
    }

    private fun srcHas(src: List<T>, key: Any?): Boolean = key != null && src.any { keyOf(it) == key }

    /** True when the first [countA] rows of [a] are the same rows as the first [countB] of [b]. */
    private fun sameKeys(a: List<T>, countA: Int, b: List<T>, countB: Int): Boolean {
        if (countA != countB) return false
        for (i in 0 until countA) if (keyOf(a[i]) != keyOf(b[i])) return false
        return true
    }

    private fun longestIncreasingRun(values: List<Int>): List<Int> {
        if (values.size <= 1) return values
        val len = IntArray(values.size) { 1 }
        val prev = IntArray(values.size) { -1 }
        var best = 0
        for (i in values.indices) {
            for (j in 0 until i) {
                if (values[j] < values[i] && len[j] + 1 > len[i]) {
                    len[i] = len[j] + 1
                    prev[i] = j
                }
            }
            if (len[i] > len[best]) best = i
        }
        val out = ArrayList<Int>(len[best])
        var k = best
        while (k != -1) {
            out.add(values[k])
            k = prev[k]
        }
        out.reverse()
        return out
    }

    private companion object {
        const val GATHER_QUIET_MS = 200L
        const val GATHER_MAX_MS = 1500L
        const val CHANGE_WINDOW_MS = 3000L
    }
}

/**
 * Feeds [source] into the holder and returns the rows to show. [context] names which list this is
 * (which feed, which account): when it changes, the old rows are dropped instead of held.
 */
@Composable
fun <T : Any> StableListHolder<T>.bind(source: List<T>, context: Any? = null): List<T> {
    val latestSource by rememberUpdatedState(source)
    val latestContext by rememberUpdatedState(context)
    LaunchedEffect(this) {
        onBound()
        snapshotFlow { latestSource to latestContext }
            .collectLatest { (s, c) -> offer(s, c) }
    }
    LaunchedEffect(this) {
        snapshotFlow {
            val items = listState.layoutInfo.visibleItemsInfo
            items.firstOrNull()?.key to items.lastOrNull()?.key
        }.distinctUntilChanged().collect { onViewportMoved() }
    }
    return shown
}
