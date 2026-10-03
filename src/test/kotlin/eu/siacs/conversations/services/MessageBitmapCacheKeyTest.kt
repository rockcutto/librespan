package eu.siacs.conversations.services

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MessageBitmapCacheKeyTest {
    @Test
    fun identitySeparatesAccountMessageSizeAndVariant() {
        val baseline = XmppConnectionService.bitmapCacheKey("account-a", "message-a", 288, "chat-fit")

        assertNotEquals(baseline, XmppConnectionService.bitmapCacheKey("account-b", "message-a", 288, "chat-fit"))
        assertNotEquals(baseline, XmppConnectionService.bitmapCacheKey("account-a", "message-b", 288, "chat-fit"))
        assertNotEquals(baseline, XmppConnectionService.bitmapCacheKey("account-a", "message-a", 144, "chat-fit"))
        assertNotEquals(baseline, XmppConnectionService.bitmapCacheKey("account-a", "message-a", 288, "viewer-page"))
    }

    @Test
    fun invalidIdentityIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            XmppConnectionService.bitmapCacheKey("account-a", "message-a", 0, "chat-fit")
        }
    }
}
