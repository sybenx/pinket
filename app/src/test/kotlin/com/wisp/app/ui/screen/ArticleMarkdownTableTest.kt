package com.wisp.app.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleMarkdownTableTest {

    @Test
    fun `parses a table between paragraphs`() {
        val blocks = parseMarkdownBlocks(
            """
            Intro line
            | Client | Status |
            | :--- | ---: |
            | **Amethyst** | ✅ Working |
            | Wisp \| fork | ❌ |
            After
            """.trimIndent()
        )
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Paragraph("Intro line"), blocks[0])
        assertEquals(
            MdBlock.Table(
                header = listOf("Client", "Status"),
                rows = listOf(listOf("**Amethyst**", "✅ Working"), listOf("Wisp | fork", "❌"))
            ),
            blocks[1]
        )
        assertEquals(MdBlock.Paragraph("After"), blocks[2])
    }

    @Test
    fun `pipe line without a delimiter row stays a paragraph`() {
        val blocks = parseMarkdownBlocks("| not | a table |")
        assertTrue(blocks.single() is MdBlock.Paragraph)
    }
}
