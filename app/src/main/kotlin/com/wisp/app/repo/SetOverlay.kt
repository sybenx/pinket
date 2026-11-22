package com.wisp.app.repo

import com.wisp.app.nostr.CustomEmoji
import com.wisp.app.nostr.ListWrite

/**
 * ListWriter's pending changes laid over a set (or the emoji list) as the app shows it, so a button flips at once.
 * The projection itself changes only from a signed version; these helpers only compute what to show meanwhile.
 * Entries compare as ListWrite.key does (`p` and `e` in lowercase, others as written).
 */
object SetOverlay {

    /** Pending changes grouped by set (d), only those of [kind] (not the markers of sets being created). */
    fun byD(pending: List<PendingChange>, kind: Int): Map<String, List<PendingChange>> =
        pending.filter { it.kind == kind && it.d != null && !it.creating }.groupBy { it.d!! }

    /**
     * Sets of [kind] being created right now ([PendingChange.creating] markers): d to the title to show. A repository
     * shows each one that it has not loaded yet as an empty set, so a new set appears at once.
     */
    fun creating(pending: List<PendingChange>, kind: Int): Map<String, String> =
        pending.filter { it.creating && it.kind == kind && it.d != null }
            .associate { it.d!! to (it.title?.takeIf { t -> t.isNotBlank() } ?: it.d) }

    /** The values of the [name] entries as shown: [base] minus pending removes, plus pending adds (base order kept). */
    fun values(base: Collection<String>, name: String, pending: List<PendingChange>): Set<String> {
        val mine = pending.filter { it.entry.size >= 2 && it.entry[0] == name }
        if (mine.isEmpty()) return base as? Set<String> ?: base.toSet()
        val keyOf = { v: String -> ListWrite.key(listOf(name, v))?.second ?: v }
        val shown = LinkedHashMap<String, String>()
        for (v in base) shown.putIfAbsent(keyOf(v), v)
        for (p in mine) {
            val k = keyOf(p.entry[1])
            if (p.wanted) shown.putIfAbsent(k, p.entry[1]) else shown.remove(k)
        }
        return LinkedHashSet(shown.values)
    }

    /** The title as shown: the last pending title change, else [base]. */
    fun title(base: String, pending: List<PendingChange>): String =
        pending.lastOrNull { it.wanted && it.entry.size >= 2 && it.entry[0] == "title" }?.entry?.get(1) ?: base

    /**
     * Image emojis as shown: a pending remove takes the shortcode out, a pending add appends it when missing, a
     * pending replace gives the shortcode its new image in place (or appends it).
     */
    fun emojis(base: List<CustomEmoji>, pending: List<PendingChange>): List<CustomEmoji> {
        val mine = pending.filter { it.entry.size >= 2 && it.entry[0] == "emoji" }
        if (mine.isEmpty()) return base
        val out = base.toMutableList()
        for (p in mine) {
            val code = p.entry[1]
            val url = p.entry.getOrNull(2).orEmpty()
            when {
                !p.wanted -> out.removeAll { it.shortcode == code }
                p.replace -> {
                    val i = out.indexOfFirst { it.shortcode == code }
                    if (i >= 0) out[i] = CustomEmoji(code, url) else out += CustomEmoji(code, url)
                }
                out.none { it.shortcode == code } -> out += CustomEmoji(code, url)
            }
        }
        return out
    }
}
