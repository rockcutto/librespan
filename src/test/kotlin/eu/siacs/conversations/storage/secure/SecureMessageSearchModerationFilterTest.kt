package eu.siacs.conversations.storage.secure

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessageSearchModerationFilterTest {

    @Test
    fun moderatedMessagesAreAlwaysExcludedFromSecureCandidateQuery() {
        val where = secureMessageSearchWhere(
            accountUuid = "account-a",
            conversationUuid = null,
            cursor = null,
        )

        assertTrue(where.sql.contains("message.${Message.MODERATED}=0"))
        assertTrue(where.sql.contains("conversation.${Conversation.ACCOUNT}=?"))
        assertEquals(listOf("account-a"), where.args)
    }

    @Test
    fun conversationAndCursorConstraintsKeepModerationFilter() {
        val where = secureMessageSearchWhere(
            accountUuid = "account-a",
            conversationUuid = "conversation-a",
            cursor = SecureMessageSearchCursor(1234L, "message-z"),
        )

        assertTrue(where.sql.contains("message.${Message.MODERATED}=0"))
        assertTrue(where.sql.contains("message.${Message.CONVERSATION}=?"))
        assertTrue(where.sql.contains("message.${Message.TIME_SENT}<?"))
        assertTrue(where.sql.contains("message.${Message.UUID}<?"))
        assertEquals(
            listOf("account-a", "conversation-a", "1234", "1234", "message-z"),
            where.args,
        )
    }
}
