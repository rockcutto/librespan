package eu.siacs.conversations.entities

import android.graphics.Typeface
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import eu.siacs.conversations.storage.secure.SecureMessagePayloadMode
import eu.siacs.conversations.utils.MessageMarkup
import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SecureMessagePresentationTest {

    @Test
    fun verifiedProtectedBodyDrivesBubbleAndReplyPresentation() {
        val protected = protectedText("hello")
        val reply = protectedText("quoted")
        protected.setReplyMessage(reply, false)

        assertEquals("", protected.durableBodyForTest())
        assertEquals("> quoted\nhello", protected.bodyForDisplaying.toString())
        assertEquals("hello", protected.getBodyForDisplaying(true).toString())
        assertEquals("hello", protected.getBodyForReplyPreview(null).toString())
    }

    @Test
    fun xep0394FormattingSurvivesReplyPreview() {
        val prepared = MessageMarkup.prepare("*bold* _italic_ ~strike~")
        val message = testMessage(prepared.getPlainBody())
        message.setMessageMarkup(prepared.getMarkup())

        val preview = message.getBodyForReplyPreview(null)

        assertEquals("bold italic strike", preview.toString())
        val styles = preview.getSpans(0, preview.length, StyleSpan::class.java)
        assertTrue(styles.any { it.style == Typeface.BOLD })
        assertTrue(styles.any { it.style == Typeface.ITALIC })
        assertEquals(1, preview.getSpans(0, preview.length, StrikethroughSpan::class.java).size)
    }

    @Test
    fun xep0393FormattingSurvivesReplyPreview() {
        val message = testMessage("*bold* _italic_ ~strike~")

        val preview = message.getBodyForReplyPreview(null)

        assertEquals("bold italic strike", preview.toString())
        val styles = preview.getSpans(0, preview.length, StyleSpan::class.java)
        assertTrue(styles.any { it.style == Typeface.BOLD })
        assertTrue(styles.any { it.style == Typeface.ITALIC })
        assertEquals(1, preview.getSpans(0, preview.length, StrikethroughSpan::class.java).size)
    }

    @Test
    fun unavailableProtectedPayloadNeverUsesDurableBodyFallback() {
        val message = testMessage("legacy fallback")
        message.setSecureMessagePayloadMode(SecureMessagePayloadMode.PROTECTED)

        assertEquals("", message.durableBodyForTest())
        assertEquals("", message.bodyForDisplaying.toString())
        assertEquals("", message.getBodyForReplyPreview(null).toString())
        assertFalse(message.hasMeCommand())
        assertFalse(message.bodyIsOnlyEmojis())
        assertFalse(message.isGeoUri())
    }

    @Test
    fun legacyTextPresentationIsUnchanged() {
        val message = testMessage("legacy text")

        assertEquals("legacy text", message.bodyForDisplaying.toString())
        assertEquals("legacy text", message.getBodyForReplyPreview(null).toString())
    }

    private fun protectedText(value: String): TestMessage =
        testMessage("legacy").apply {
            setSecureMessagePayloadMode(SecureMessagePayloadMode.PROTECTED)
            setVerifiedProtectedBody(value)
        }

    private fun testMessage(body: String): TestMessage =
        TestMessage(conversation(), body)

    private class TestMessage(conversation: Conversation, body: String) :
        Message(conversation, body, Message.ENCRYPTION_NONE) {
        fun durableBodyForTest(): String = body
    }

    private fun conversation(): Conversation {
        val account = Account(Jid.of("me@example.test"), "")
        return Conversation(
            "secure-presentation",
            account,
            Jid.of("peer@example.test"),
            Conversation.MODE_SINGLE,
            null,
        )
    }
}
