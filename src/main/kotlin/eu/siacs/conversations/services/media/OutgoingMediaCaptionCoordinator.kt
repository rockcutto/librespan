package eu.siacs.conversations.services.media

import java.util.UUID
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.xml.Element
import eu.siacs.conversations.xml.Namespace

class OutgoingMediaCaptionCoordinator {
    private val pendingCaptions = HashMap<String, PendingCaption>()

    @Synchronized
    fun register(conversation: Conversation, body: String, relationAllowed: Boolean): String? {
        if (body.trim().isEmpty()) {
            return null
        }
        val id = UUID.randomUUID().toString()
        pendingCaptions[id] = PendingCaption(conversation, body, relationAllowed)
        return id
    }

    @Synchronized
    fun onMediaReleased(
        captionId: String?,
        anchor: Message?,
        relationReleased: Boolean,
    ): Message? {
        val pending = take(captionId) ?: return null
        val attachToAnchor = pending.relationAllowed && relationReleased && anchor != null
        return createCaption(pending, if (attachToAnchor) anchor else null)
    }

    @Synchronized
    fun discard(captionId: String?) {
        take(captionId)
    }

    @Synchronized
    fun hasPending(captionId: String?): Boolean =
        captionId != null && pendingCaptions.containsKey(captionId)

    private fun take(captionId: String?): PendingCaption? =
        if (captionId == null) null else pendingCaptions.remove(captionId)

    private fun createCaption(pending: PendingCaption, anchor: Message?): Message {
        val caption =
            Message(
                pending.conversation,
                pending.body,
                pending.conversation.getNextEncryption(),
            )
        Message.configurePrivateMessage(caption)
        if (anchor != null) {
            caption.addPayload(
                Element("attach-to", Namespace.MESSAGE_ATTACHING)
                    .setAttribute("id", anchor.getUuid()),
            )
        }
        return caption
    }

    private data class PendingCaption(
        val conversation: Conversation,
        val body: String,
        val relationAllowed: Boolean,
    )
}
