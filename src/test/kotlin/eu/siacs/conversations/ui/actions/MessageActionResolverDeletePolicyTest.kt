package eu.siacs.conversations.ui.actions

import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MessageActionResolverDeletePolicyTest {
    @Test
    fun failedMediaStillOffersLocalDelete() {
        val account = Account(Jid.of("me@example.test"), "")
        val conversation = Conversation(
            "failed-delete-policy",
            account,
            Jid.of("peer@example.test"),
            Conversation.MODE_SINGLE,
            null,
        )
        val media = Message(conversation, "", Message.ENCRYPTION_NONE).apply {
            type = Message.TYPE_IMAGE
            status = Message.STATUS_SEND_FAILED
            setErrorMessage("upload failed")
        }

        val actions = MessageActionResolver().resolve(media, ownsMediaFile = false).map { it.type }

        assertTrue(MessageActionType.DELETE_LOCALLY in actions)
    }

    @Test
    fun mediaSheetKeepsSingleLocalDeleteAction() {
        val account = Account(Jid.of("me@example.test"), "")
        val conversation = Conversation(
            "delete-policy",
            account,
            Jid.of("peer@example.test"),
            Conversation.MODE_SINGLE,
            null,
        )
        val media = Message(conversation, "", Message.ENCRYPTION_NONE).apply {
            type = Message.TYPE_IMAGE
            status = Message.STATUS_RECEIVED
        }

        val actions = MessageActionResolver().resolve(media, ownsMediaFile = true).map { it.type }

        assertTrue(MessageActionType.DELETE_LOCALLY in actions)
        assertFalse(MessageActionType.DELETE_FILE in actions)
    }
    @Test
    fun moderatorDeleteReplacesLocalDeleteInActionSheet() {
        val account = Account(Jid.of("me@example.test"), "")
        val conversation = Conversation(
            "moderator-delete-policy",
            account,
            Jid.of("room@conference.example"),
            Conversation.MODE_MULTI,
            null,
        )
        val message = Message(conversation, "message", Message.ENCRYPTION_NONE).apply {
            status = Message.STATUS_RECEIVED
        }

        val actions =
            MessageActionResolver()
                .resolve(
                    message,
                    ownsMediaFile = false,
                    canModerateMessage = true
                )
                .map { it.type }

        assertTrue(MessageActionType.MODERATE_MESSAGE in actions)
        assertFalse(MessageActionType.DELETE_LOCALLY in actions)
    }

}
