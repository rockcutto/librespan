package eu.siacs.conversations.entities.media

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.xml.Element
import eu.siacs.conversations.xml.Namespace
import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLocalDeleteResolverTest {
    @Test
    fun singleMediaDeleteIncludesItsCaption() {
        val conversation = conversation("single")
        val anchor = outgoingMedia(conversation, "anchor")
        val caption = outgoingText(conversation, "caption")
        attach(caption, anchor.uuid)
        add(conversation, anchor, caption)

        assertEquals(
            listOf(anchor, caption),
            MediaLocalDeleteResolver.resolveDeleteTargets(
                conversation,
                anchor,
                conversation.snapshotMessages(),
            ),
        )
    }

    @Test
    fun outgoingLocalGroupDeleteIncludesEveryMemberAndCaption() {
        val conversation = conversation("outgoing-group")
        val anchor = outgoingMedia(conversation, "anchor").apply { mediaGroupId = "group" }
        val child = outgoingMedia(conversation, "child").apply { mediaGroupId = "group" }
        val caption = outgoingText(conversation, "caption")
        attach(caption, anchor.uuid)
        add(conversation, anchor, child, caption)

        assertEquals(
            listOf(anchor, child, caption),
            MediaLocalDeleteResolver.resolveDeleteTargets(
                conversation,
                child,
                conversation.snapshotMessages(),
            ),
        )
    }

    @Test
    fun incomingXepAlbumDeleteIncludesAlbumAndCaption() {
        val conversation = conversation("incoming-album")
        val anchor = incomingMedia(conversation, "anchor")
        val child = incomingMedia(conversation, "child")
        val caption = incomingText(conversation, "caption")
        attach(child, "anchor")
        attach(caption, "anchor")
        add(conversation, anchor, child, caption)

        assertEquals(
            listOf(anchor, child, caption),
            MediaLocalDeleteResolver.resolveDeleteTargets(
                conversation,
                child,
                conversation.snapshotMessages(),
            ),
        )
    }

    @Test
    fun ordinaryTextDeleteRemainsSingleMessage() {
        val conversation = conversation("ordinary")
        val message = outgoingText(conversation, "plain")
        add(conversation, message)

        assertEquals(
            listOf(message),
            MediaLocalDeleteResolver.resolveDeleteTargets(
                conversation,
                message,
                conversation.snapshotMessages(),
            ),
        )
    }

    private fun conversation(id: String): Conversation =
        Conversation(
            id,
            "test",
            null,
            "account",
            Jid.of("peer@example.test"),
            0,
            Conversation.STATUS_AVAILABLE,
            Conversation.MODE_SINGLE,
            "",
            null,
        )

    private fun outgoingMedia(conversation: Conversation, uuid: String): Message =
        Message(
            conversation,
            "https://upload.example/$uuid.jpg",
            Message.ENCRYPTION_NONE,
            Message.STATUS_SEND,
        ).apply {
            type = Message.TYPE_IMAGE
            setUuid(uuid)
            counterpart = PEER
        }

    private fun incomingMedia(conversation: Conversation, remoteId: String): Message =
        Message(
            conversation,
            "https://upload.example/$remoteId.jpg",
            Message.ENCRYPTION_NONE,
            Message.STATUS_RECEIVED,
        ).apply {
            type = Message.TYPE_IMAGE
            setRemoteMsgId(remoteId)
            counterpart = PEER
        }

    private fun outgoingText(conversation: Conversation, body: String): Message =
        Message(
            conversation,
            body,
            Message.ENCRYPTION_NONE,
            Message.STATUS_WAITING,
        ).apply {
            counterpart = PEER
        }

    private fun incomingText(conversation: Conversation, body: String): Message =
        Message(
            conversation,
            body,
            Message.ENCRYPTION_NONE,
            Message.STATUS_RECEIVED,
        ).apply {
            setRemoteMsgId(body)
            counterpart = PEER
        }

    private fun attach(message: Message, anchorId: String) {
        message.addPayload(
            Element("attach-to", Namespace.MESSAGE_ATTACHING).setAttribute("id", anchorId),
        )
    }

    private fun add(conversation: Conversation, vararg messages: Message) {
        messages.forEach(conversation::add)
    }

    private companion object {
        val PEER: Jid = Jid.of("peer@example.test")
    }
}
