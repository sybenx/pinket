package com.wisp.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmojiSearchTest {
    private val entries = File("src/main/assets/emoji-names.tsv").useLines { EmojiSearch.parse(it).toList() }

    @Test fun `the bundled list has the whole standard set`() {
        assertTrue(entries.size > 1800)
        assertTrue(entries.all { it.emoji.isNotBlank() && it.name.isNotBlank() })
    }

    @Test fun `a name finds its emoji first`() {
        assertEquals("🚀", EmojiSearch.search(entries, "rocket").first())
        assertEquals("🔥", EmojiSearch.search(entries, "fire").first())
        assertTrue(EmojiSearch.search(entries, "thumbs up").first().startsWith("👍"))
    }

    @Test fun `part of a word and keywords both match`() {
        assertTrue("🚀" in EmojiSearch.search(entries, "rock"))
        assertTrue("😂" in EmojiSearch.search(entries, "laugh"))
        assertTrue("😂" in EmojiSearch.search(entries, "  JOY "))
        assertTrue("🇨🇦" in EmojiSearch.search(entries, "canada"))
    }

    @Test fun `every typed word has to match`() {
        val found = EmojiSearch.search(entries, "red heart")
        assertEquals("❤️", found.first())
        assertTrue("💙" !in found)
        assertTrue(EmojiSearch.search(entries, "zzzzqq").isEmpty())
        assertTrue(EmojiSearch.search(entries, "   ").isEmpty())
    }

    @Test fun `custom emoji match on their shortcode`() {
        val found = EmojiSearch.searchShortcodes(listOf("pepe_laugh", "laughing_cat", "ostrich"), ":laugh")
        assertEquals(listOf("laughing_cat", "pepe_laugh"), found)
    }
}
