package com.wisp.app.ui.component

import android.content.Context
import androidx.core.graphics.PaintCompat
import android.graphics.Paint

/**
 * Finds standard emoji by name. The names and keywords come from emojibase-data (MIT, see
 * licenses/), one emoji a line in assets/emoji-names.tsv: emoji, name, keywords.
 */
object EmojiSearch {
    class Entry(val emoji: String, val name: String, val words: List<String>)

    @Volatile private var loaded: List<Entry>? = null

    /** Reads the list once, leaving out emoji this phone has no picture for. */
    fun load(context: Context): List<Entry> {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val paint = Paint()
            val entries = context.assets.open("emoji-names.tsv").bufferedReader().useLines { lines ->
                parse(lines).filter { PaintCompat.hasGlyph(paint, it.emoji) }.toList()
            }
            loaded = entries
            return entries
        }
    }

    fun parse(lines: Sequence<String>): Sequence<Entry> = lines.mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 2 || parts[0].isEmpty()) return@mapNotNull null
        val name = parts[1].lowercase()
        val keywords = parts.getOrNull(2)?.lowercase()?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
        Entry(parts[0], name, splitWords(name) + keywords)
    }

    private fun splitWords(text: String) = text.split(' ', '-', '_', ':', ',', '(', ')').filter { it.isNotEmpty() }

    /**
     * Every word typed has to start a word of the emoji's name or keywords. Best first: the
     * name is exactly what was typed, the name begins with it, whole words match, then the
     * rest, each in the usual emoji order.
     */
    fun search(entries: List<Entry>, query: String, limit: Int = 96): List<String> {
        val typed = splitWords(query.trim().lowercase())
        if (typed.isEmpty()) return emptyList()
        val whole = typed.joinToString(" ")
        val ranked = ArrayList<Pair<Int, String>>()
        for (entry in entries) {
            if (!typed.all { t -> entry.words.any { it.startsWith(t) } }) continue
            val rank = when {
                entry.name == whole -> 0
                entry.name.startsWith("$whole ") -> 1
                typed.all { it in entry.words } -> 2
                entry.name.startsWith(whole) -> 3
                else -> 4
            }
            ranked.add(rank to entry.emoji)
        }
        return ranked.sortedBy { it.first }.take(limit).map { it.second }   // stable: keeps emoji order
    }

    /** Custom emoji whose shortcode contains what was typed; ones that start with it first. */
    fun searchShortcodes(shortcodes: Collection<String>, query: String): List<String> {
        val typed = query.trim().lowercase().trim(':')
        if (typed.isEmpty()) return emptyList()
        return shortcodes.filter { it.lowercase().contains(typed) }
            .sortedBy { if (it.lowercase().startsWith(typed)) 0 else 1 }
    }
}
