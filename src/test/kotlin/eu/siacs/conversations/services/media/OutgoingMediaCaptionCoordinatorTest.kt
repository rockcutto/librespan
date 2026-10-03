package eu.siacs.conversations.services.media

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.xml.Namespace
import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingMediaCaptionCoordinatorTest {
    @Test
    fun emptyCaptionDoesNotCreatePendingState() {
        val coordinator = OutgoingMediaCaptionCoordinator()

        assertNull(coordinator.register(conversation(), " \n ", true))
    }

    @Test
    fun singleCaptionIsCreatedOnlyAfterAnchorReleaseAndOnlyOnce() {
        val coordinator = OutgoingMediaCaptionCoordinator()
        val conversation = conversation()
        val anchor = media(conversation, "anchor")

        val id = coordinator.register(conversation, " caption ", true)

        assertTrue(coordinator.hasPending(id))
        val caption = coordinator.onMediaReleased(id, anchor, true)

        assertFalse(coordinator.hasPending(id))
        assertEquals(" caption ", caption?.getBody())
        assertEquals(listOf(anchor.getUuid()), attachTo(caption))
        assertNull(caption?.getReplyOrReaction())
        assertNull(coordinator.onMediaReleased(id, anchor, true))
    }

    @Test
    fun albumsOfTwoFiveAndTenMembersReleaseOneCaptionAfterTheirAnchor() {
        for (count in listOf(2, 5, 10)) {
            val coordinator = OutgoingMediaCaptionCoordinator()
            val conversation = conversation("album-$count")
            val media = (0 until count).map { media(conversation, "anchor-$count-$it") }
            val id = coordinator.register(conversation, "caption-$count", true)

            val caption = coordinator.onMediaReleased(id, media.first(), true)

            assertEquals(listOf(media.first().getUuid()), attachTo(caption))
            assertNull(caption?.getMediaGroupId())
            assertFalse(coordinator.hasPending(id))
        }
    }

    @Test
    fun unsupportedMediaFallsBackButFailedMediaDiscardsCaption() {
        val coordinator = OutgoingMediaCaptionCoordinator()
        val conversation = conversation("fallback")
        val anchor = media(conversation, "anchor")
        val unsupportedId = coordinator.register(conversation, "unsupported", false)

        val unsupported = coordinator.onMediaReleased(unsupportedId, anchor, true)

        assertTrue(attachTo(unsupported).isEmpty())
        assertNull(coordinator.onMediaReleased(unsupportedId, anchor, true))

        val failedId = coordinator.register(conversation, "failed", true)
        assertTrue(coordinator.hasPending(failedId))

        coordinator.discard(failedId)

        assertFalse(coordinator.hasPending(failedId))
    }

    private fun conversation(id: String = "conversation"): Conversation = TestConversation(id)

    private class TestConversation(id: String) :
        Conversation(
            id,
            "test",
            null,
            "account",
            Jid.of("contact@example.test"),
            0,
            Conversation.STATUS_AVAILABLE,
            Conversation.MODE_SINGLE,
            "",
            null,
        ) {
        override fun getNextEncryption(): Int = Message.ENCRYPTION_NONE
    }

    private fun media(conversation: Conversation, uuid: String): Message =
        Message(
            conversation,
            "https://upload.example/$uuid.jpg",
            Message.ENCRYPTION_NONE,
            Message.STATUS_UNSEND,
        ).apply {
            setUuid(uuid)
        }

    private fun attachTo(message: Message?): List<String> =
        message?.getPayloads()
            ?.filter { it.getName() == "attach-to" && it.getNamespace() == Namespace.MESSAGE_ATTACHING }
            ?.mapNotNull { it.getAttribute("id") }
            ?: emptyList()
}
