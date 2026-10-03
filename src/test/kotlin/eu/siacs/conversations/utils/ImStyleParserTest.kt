package eu.siacs.conversations.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImStyleParserTest {

    @Test
    fun strongSpanUsesFirstValidClosingDirective() {
        val styles = ImStyleParser.parse("*strong*plain*")

        assertEquals(1, styles.size)
        assertEquals("*", styles[0].keyword)
        assertEquals(0, styles[0].start)
        assertEquals(7, styles[0].end)
    }

    @Test
    fun spansDoNotEscapeTheirLine() {
        assertTrue(ImStyleParser.parse("*not \n strong*").isEmpty())
    }

    @Test
    fun differentDirectivesCanNest() {
        val styles = ImStyleParser.parse("*strong _emphasis_*")

        assertEquals(listOf("*", "_"), styles.map { it.keyword })
    }

    @Test
    fun preformattedBlockStartsOnlyAtLineStart() {
        val text = "plain ```not a block```\n```code\n```"
        val blocks = ImStyleParser.parse(text).filter { it.keyword == "```" }

        assertEquals(1, blocks.size)
        assertEquals(text.indexOf("\n```") + 1, blocks[0].start)
        assertTrue(blocks[0].hasClosingKeyword())
    }

    @Test
    fun preformattedBlockMayEndAtEndOfMessage() {
        val text = "```java\nprintln()"
        val block = ImStyleParser.parse(text).single { it.keyword == "```" }

        assertEquals(0, block.start)
        assertEquals(text.lastIndex, block.end)
        assertFalse(block.hasClosingKeyword())
    }

    @Test
    fun preformattedBlockClosingLineMustContainOnlyDelimiter() {
        val text = "```\ncode\n```x\nmore"
        val block = ImStyleParser.parse(text).single { it.keyword == "```" }

        assertEquals(text.lastIndex, block.end)
        assertFalse(block.hasClosingKeyword())
    }
}
