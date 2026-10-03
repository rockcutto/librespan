package eu.siacs.conversations.entities

import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCorrectionIdentityTest {

    @Test
    fun archivedOutgoingMessageUsesOriginIdInsteadOfLocalUuid() {
        val message = ownText()
        val localUuid = message.uuid
        message.remoteMsgId = "mam-origin-id"

        assertEquals("mam-origin-id", message.correctionTargetId)
        assertTrue(message.isCorrectableMessage())
        assertFalse(localUuid == message.correctionTargetId)
    }

    @Test
    fun repeatedCorrectionKeepsOriginalTarget() {
        val message = ownText()
        message.remoteMsgId = "original-origin-id"
        message.putEdited("original-origin-id", "server-edit-one")
        message.remoteMsgId = "correction-two-origin-id"

        assertEquals("original-origin-id", message.correctionTargetId)
    }

    @Test
    fun incomingOrAmbiguousMessageIsNotCorrectable() {
        val message = ownText()
        message.status = Message.STATUS_RECEIVED
        message.remoteMsgId = "someone-else-id"

        assertNull(message.correctionTargetId)
        assertFalse(message.isCorrectableMessage())
    }

    private fun ownText(): Message {
        val account = Account(Jid.of("me@example.test"), "")
        val conversation = Conversation(
            "test",
            account,
            Jid.of("peer@example.test"),
            Conversation.MODE_SINGLE,
            null,
        )
        return Message(conversation, "body", Message.ENCRYPTION_NONE)
    }
}
