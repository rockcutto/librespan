package eu.siacs.conversations.utils

import eu.siacs.conversations.xml.Namespace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MessageMarkupTest {

    @Test
    fun strongProducesCleanBodyAndCodePointRange() {
        val prepared = MessageMarkup.prepare("*Слились* просто")

        assertEquals("Слились просто", prepared.getPlainBody())
        val markup = prepared.getMarkup()
        assertNotNull(markup)
        assertEquals(Namespace.MESSAGE_MARKUP, markup!!.getNamespace())
        val span = markup.getChildren().single()
        assertEquals("span", span.getName())
        assertEquals("0", span.getAttribute("start"))
        assertEquals("7", span.getAttribute("end"))
        assertEquals("strong", span.getChildren().single().getName())
    }

    @Test
    fun emojiCountsAsOneCodePoint() {
        val prepared = MessageMarkup.prepare("*🙂a*")

        assertEquals("🙂a", prepared.getPlainBody())
        val span = prepared.getMarkup()!!.getChildren().single()
        assertEquals("0", span.getAttribute("start"))
        assertEquals("2", span.getAttribute("end"))
    }

    @Test
    fun nestedStylesBecomeNonOverlappingMarkupSpans() {
        val prepared = MessageMarkup.prepare("*bold _both_*")

        assertEquals("bold both", prepared.getPlainBody())
        val spans = prepared.getMarkup()!!.getChildren()
        assertEquals(2, spans.size)
        assertEquals("0", spans[0].getAttribute("start"))
        assertEquals("5", spans[0].getAttribute("end"))
        assertEquals(listOf("strong"), spans[0].getChildren().map { it.getName() })
        assertEquals("5", spans[1].getAttribute("start"))
        assertEquals("9", spans[1].getAttribute("end"))
        assertEquals(
            listOf("strong", "emphasis"),
            spans[1].getChildren().map { it.getName() }
        )
    }

    @Test
    fun allInlineStylesProduceExpectedMarkupSemantics() {
        val prepared = MessageMarkup.prepare("*bold* _italic_ ~strike~ `code`")

        assertEquals("bold italic strike code", prepared.getPlainBody())
        val spans = prepared.getMarkup()!!.getChildren()
        assertEquals(4, spans.size)
        assertEquals(
            listOf("strong", "emphasis", "deleted", "code"),
            spans.map { it.getChildren().single().getName() }
        )
        assertEquals(
            listOf("0" to "4", "5" to "11", "12" to "18", "19" to "23"),
            spans.map { it.getAttribute("start") to it.getAttribute("end") }
        )
    }

    @Test
    fun plainTextDoesNotCreateMarkup() {
        val prepared = MessageMarkup.prepare("hello")

        assertEquals("hello", prepared.getPlainBody())
        assertNull(prepared.getMarkup())
    }

    @Test
    fun simpleMarkupCanBeRestoredForComposerEditing() {
        val prepared = MessageMarkup.prepare("*bold*")

        assertEquals(
            "*bold*",
            MessageMarkup.toStylingText(prepared.getPlainBody(), prepared.getMarkup())
        )
    }

    @Test
    fun replyOffsetIsAppliedInCodePoints() {
        val prepared = MessageMarkup.prepare("*🙂a*")
        val shifted = MessageMarkup.markupForMode(
            prepared,
            MessageMarkup.WireMode.MARKUP,
            5
        )

        val span = shifted!!.getChildren().single()
        assertEquals("5", span.getAttribute("start"))
        assertEquals("7", span.getAttribute("end"))
    }    @Test
    fun stylingWireModeKeepsDirectivesInsideEncryptedBody() {
        val prepared = MessageMarkup.prepare("*bold*")

        assertEquals(
            "*bold*",
            MessageMarkup.bodyForMode(
                "*bold*",
                prepared,
                MessageMarkup.WireMode.STYLING
            )
        )
        assertNull(
            MessageMarkup.markupForMode(
                prepared,
                MessageMarkup.WireMode.STYLING,
                0
            )
        )
    }

    @Test
    fun markupWireModeUsesCleanBodyAndSeparateMarkup() {
        val prepared = MessageMarkup.prepare("*bold*")

        assertEquals(
            "bold",
            MessageMarkup.bodyForMode(
                "*bold*",
                prepared,
                MessageMarkup.WireMode.MARKUP
            )
        )
        assertNotNull(
            MessageMarkup.markupForMode(
                prepared,
                MessageMarkup.WireMode.MARKUP,
                0
            )
        )
    }


}
