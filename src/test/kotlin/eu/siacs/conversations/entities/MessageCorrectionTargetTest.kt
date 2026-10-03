package eu.siacs.conversations.entities

import eu.siacs.conversations.storage.secure.SecureMessagePayloadMode
import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MessageCorrectionTargetTest {

    private val peer = Jid.of("peer@example.test")
    private val otherPeer = Jid.of("other@example.test")

    @Test
    fun latestCorrectionResolvesExactTarget() {
        val conversation = conversation()
        val latest = message(conversation, "latest")
        conversation.add(latest)

        assertSame(latest, conversation.findMessageWithRemoteIdAndCounterpart("latest", peer, true, false))
    }

    @Test
    fun olderTargetSurvivesNewerMessages() {
        val conversation = conversation()
        val original = message(conversation, "A")
        conversation.add(original)
        conversation.add(message(conversation, "B"))
        conversation.add(message(conversation, "C"))

        assertSame(original, conversation.findMessageWithRemoteIdAndCounterpart("A", peer, true, false))
    }

    @Test
    fun wrongCounterpartDirectionAndCarbonAreRejected() {
        val conversation = conversation()
        val incoming = message(conversation, "incoming")
        val outgoingCarbon = message(
            conversation,
            "outgoing-carbon",
            status = Message.STATUS_SEND,
            carbon = true,
        )
        conversation.add(incoming)
        conversation.add(outgoingCarbon)

        assertNull(conversation.findMessageWithRemoteIdAndCounterpart("incoming", otherPeer, true, false))
        assertNull(conversation.findMessageWithRemoteIdAndCounterpart("incoming", peer, false, false))
        assertNull(conversation.findMessageWithRemoteIdAndCounterpart("outgoing-carbon", peer, false, false))
    }

    @Test
    fun mediaCannotBecomeCorrectionTarget() {
        val conversation = conversation()
        val media = message(conversation, "media").apply { setType(Message.TYPE_IMAGE) }
        conversation.add(media)

        assertNull(conversation.findMessageWithRemoteIdAndCounterpart("media", peer, true, false))
    }

    @Test
    fun protectedHistoricalTargetRetainsItsLogicalMessageIdentity() {
        val conversation = conversation()
        val original = message(conversation, "A").apply {
            setSecureMessagePayloadMode(SecureMessagePayloadMode.PROTECTED)
            setVerifiedProtectedBody("protected A")
        }
        conversation.add(original)
        conversation.add(message(conversation, "B"))
        conversation.add(message(conversation, "C"))

        assertSame(original, conversation.findMessageWithRemoteIdAndCounterpart("A", peer, true, false))
    }

    @Test
    fun repeatedCorrectionsContinueToResolveOriginalRemoteId() {
        val conversation = conversation()
        val original = message(conversation, "A").apply {
            putEdited("B", null)
            putEdited("C", null)
        }
        conversation.add(original)

        assertSame(original, conversation.findMessageWithRemoteIdAndCounterpart("A", peer, true, false))
    }

    private fun conversation(): Conversation {
        val account = Account(Jid.of("me@example.test"), "")
        return Conversation(
            "correction-targets",
            account,
            peer,
            Conversation.MODE_SINGLE,
            null,
        )
    }

    private fun message(
        conversation: Conversation,
        remoteId: String,
        status: Int = Message.STATUS_RECEIVED,
        carbon: Boolean = false,
    ): Message =
        Message(conversation, "text", Message.ENCRYPTION_NONE, status).apply {
            setCounterpart(peer)
            setRemoteMsgId(remoteId)
            setCarbon(carbon)
        }
}
