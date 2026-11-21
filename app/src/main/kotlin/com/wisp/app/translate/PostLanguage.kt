package com.wisp.app.translate

/**
 * What language a post is in, and how to translate it without breaking it. Plain Kotlin, no Android, so unit
 * tests can run it.
 *
 * Order: the post's own NIP-32 tag, then a language remembered for its author, then a guess from its words alone
 * (links, nostr references, hashtags, mentions, emoji and :shortcodes: taken out first), counted as sure only when
 * one language is clearly ahead. Links, references, mentions, hashtags and shortcodes go through translation as
 * numbered placeholders and come back untouched.
 */
object PostLanguage {
    private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    private val REF = Regex("""(?i)\b(?:nostr:)?(?:npub|nprofile|note|nevent|naddr)1[02-9ac-hj-np-z]{8,}""")
    private val HASHTAG = Regex("""(?<![\p{L}\p{N}_])#[\p{L}\p{N}_]+""")
    private val MENTION = Regex("""(?<![\p{L}\p{N}_.])[@＠][\p{L}\p{N}_.-]+""")
    private val SHORTCODE = Regex(""":[A-Za-z0-9_+-]{2,}:""")
    private val EMOJI = Regex("""[\x{1F000}-\x{1FAFF}\x{2600}-\x{27BF}\x{2B00}-\x{2BFF}\x{FE0F}\x{200D}\x{1F1E6}-\x{1F1FF}\x{E0020}-\x{E007F}]""")
    private val PROTECTED = Regex(listOf(URL, REF, SHORTCODE, HASHTAG, MENTION).joinToString("|") { "(?:${it.pattern})" })
    private val PLACEHOLDER = Regex("""⟦\s*(\d+)\s*⟧""")
    private val TWO_LETTERS = Regex("^[a-z]{2}$")

    /** Detector and locale codes that mean the same language: iw→he, in→id, ji→yi; region dropped. */
    fun normalize(code: String): String {
        val c = code.trim().lowercase().substringBefore('-').substringBefore('_')
        return when (c) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            else -> c
        }
    }

    /** The post's own language tag (NIP-32: `L ISO-639-1`, `l <code> ISO-639-1`), if it has one. */
    fun tagged(tags: List<List<String>>): String? {
        val namespaced = tags.any { it.size > 1 && it[0] == "L" && it[1] == "ISO-639-1" }
        return tags.firstOrNull {
            it.size > 1 && it[0] == "l" &&
                (it.getOrNull(2) == "ISO-639-1" || namespaced) &&
                TWO_LETTERS.matches(it[1].lowercase())
        }?.get(1)?.let(::normalize)
    }

    /** The words alone, for guessing the language. */
    fun clean(text: String): String =
        listOf(URL, REF, SHORTCODE, HASHTAG, MENTION, EMOJI)
            .fold(text) { t, r -> r.replace(t, " ") }
            .replace(Regex("""\s+"""), " ")
            .trim()

    /** Enough words to guess from: at least 12 letters. */
    fun guessable(cleaned: String): Boolean = cleaned.count { it.isLetter() } >= 12

    /** A guess: sure of [lang], or unsure ([lang] null) with the likeliest few to offer. */
    data class Guess(val lang: String?, val likeliest: List<String>) {
        val sure: Boolean get() = lang != null

        companion object {
            fun known(lang: String) = Guess(lang, listOf(lang))
            val NONE = Guess(null, emptyList())
        }
    }

    /** From the detector's candidates (language, confidence): sure only when the top one is clearly ahead. */
    fun decide(candidates: List<Pair<String, Float>>, atLeast: Float = 0.6f, ahead: Float = 0.25f): Guess {
        val c = candidates
            .filter { it.first.isNotEmpty() && it.first != "und" }
            .map { normalize(it.first) to it.second }
            .sortedByDescending { it.second }
            .distinctBy { it.first }
        val top = c.firstOrNull() ?: return Guess.NONE
        val second = c.getOrNull(1)?.second ?: 0f
        return if (top.second >= atLeast && top.second - second >= ahead) Guess.known(top.first)
        else Guess(null, c.take(3).map { it.first })
    }

    /** [text] with links, references, mentions, hashtags and shortcodes as numbered placeholders, and what they were. */
    fun protect(text: String): Pair<String, List<String>> {
        val kept = ArrayList<String>()
        val out = PROTECTED.replace(text) { m -> kept += m.value; "⟦${kept.size - 1}⟧" }
        return out to kept
    }

    /** Placeholders back (translators sometimes add spaces inside them); any it lost go at the end, so nothing is dropped. */
    fun restore(translated: String, kept: List<String>): String {
        val used = HashSet<Int>()
        var out = PLACEHOLDER.replace(translated) { m ->
            val i = m.groupValues[1].toIntOrNull()
            val v = i?.let { kept.getOrNull(it) }
            if (v != null) { used += i; v } else ""
        }
        val lost = kept.indices.filter { it !in used }.map { kept[it] }
        if (lost.isNotEmpty()) out = out.trimEnd() + " " + lost.joinToString(" ")
        return out
    }

    /**
     * Translate [text] with its links and the like protected, paragraph by paragraph (split on line breaks, so they
     * stay where they were). [line] translates one non-blank line; null from it means the whole translation failed.
     */
    suspend fun translateProtected(text: String, line: suspend (String) -> String?): String? {
        val (protectedText, kept) = protect(text)
        val out = protectedText.split("\n").map { l ->
            if (l.isBlank() || PLACEHOLDER.replace(l, "").isBlank()) l else line(l) ?: return null
        }.joinToString("\n")
        return restore(out, kept)
    }
}
