package eu.siacs.conversations.entities.media

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.xml.Element
import eu.siacs.conversations.xml.Namespace
import eu.siacs.conversations.xmpp.Jid

object MediaCaptionResolver {
    private const val ATTACH_TO = "attach-to"

    @JvmStatic
    fun getCaption(anchor: Message?, snapshot: List<Message>): Message? {
        return try {
            if (anchor == null || !containsIdentity(snapshot, anchor) || !isCaptionAnchor(anchor)) {
                return null
            }

            var caption: Message? = null
            for (candidate in snapshot) {
                if (resolveAnchorInternal(candidate, snapshot) !== anchor) {
                    continue
                }
                if (caption != null) {
                    return null
                }
                caption = candidate
            }
            caption
        } catch (_: RuntimeException) {
            null
        }
    }

    @JvmStatic
    fun isCaptionChild(message: Message?, snapshot: List<Message>): Boolean {
        return try {
            val anchor = resolveAnchorInternal(message, snapshot) ?: return false
            getCaption(anchor, snapshot) === message
        } catch (_: RuntimeException) {
            false
        }
    }

    @JvmStatic
    fun resolveAnchor(caption: Message?, snapshot: List<Message>): Message? {
        return try {
            resolveAnchorInternal(caption, snapshot)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun resolveAnchorInternal(caption: Message?, snapshot: List<Message>): Message? {
        if (caption == null || !containsIdentity(snapshot, caption) || !isCaptionCandidate(caption)) {
            return null
        }

        val conversation = directConversation(caption) ?: return null
        val attachmentId = attachmentId(caption) ?: return null
        var anchor: Message? = null

        for (candidate in snapshot) {
            if (candidate === caption ||
                candidate.getConversation() !== conversation ||
                candidate.isPrivateMessage() ||
                !sameDirection(candidate, caption) ||
                !sameCounterpart(candidate.getCounterpart(), caption.getCounterpart()) ||
                !isCaptionAnchor(candidate) ||
                attachmentId != anchorIdentity(candidate)) {
                continue
            }
            if (anchor != null) {
                return null
            }
            anchor = candidate
        }

        return anchor
    }

    private fun isCaptionAnchor(message: Message): Boolean =
        directConversation(message) != null && isAttachableMedia(message) && !hasAttachmentPayload(message)

    private fun isCaptionCandidate(message: Message): Boolean {
        if (!message.isTypeText() ||
            message.isFileOrImage() ||
            message.treatAsDownloadable() ||
            message.isOOb() ||
            message.isGeoUri() ||
            message.getReplyOrReaction() != null ||
            directConversation(message) == null) {
            return false
        }
        // The attach-to relation is transport metadata and must stay stable while protected
        // text is temporarily body-less (for example before/after SCS hydration). The adapter
        // suppresses the empty visual caption until verified plaintext becomes available.
        val hasCaptionPayload =
            message.hasProtectedTextPayload() || !message.getBody().isNullOrBlank()
        return hasCaptionPayload && attachmentId(message) != null
    }

    private fun directConversation(message: Message?): Conversation? {
        val candidate = message ?: return null
        val conversation = candidate.getConversation()
        return if (conversation is Conversation &&
            conversation.getMode() == Conversational.MODE_SINGLE &&
            !candidate.isPrivateMessage()) {
            conversation
        } else {
            null
        }
    }

    private fun anchorIdentity(anchor: Message): String? {
        val identity =
            if (anchor.getStatus() > Message.STATUS_RECEIVED) anchor.getUuid()
            else anchor.getRemoteMsgId()
        return identity?.takeIf { it.isNotEmpty() }
    }

    private fun attachmentId(message: Message): String? {
        var attachment: Element? = null
        for (payload in message.getPayloads()) {
            if (payload.getName() != ATTACH_TO || payload.getNamespace() != Namespace.MESSAGE_ATTACHING) {
                continue
            }
            if (attachment != null || payload.getChildren().isNotEmpty() || payload.getContent() != null) {
                return null
            }
            attachment = payload
        }

        val id = attachment?.getAttribute("id") ?: return null
        return id.takeIf { it.isNotEmpty() && it == it.trim() }
    }

    private fun hasAttachmentPayload(message: Message): Boolean =
        message.getPayloads().any {
            it.getName() == ATTACH_TO && it.getNamespace() == Namespace.MESSAGE_ATTACHING
        }

    private fun isIncoming(message: Message): Boolean =
        message.getStatus() <= Message.STATUS_RECEIVED

    private fun sameDirection(left: Message, right: Message): Boolean =
        isIncoming(left) == isIncoming(right)

    private fun sameCounterpart(left: Jid?, right: Jid?): Boolean =
        left == right || (left != null && right != null &&
            (left == right || left.asBareJid() == right.asBareJid()))

    private fun containsIdentity(messages: List<Message>, target: Message): Boolean =
        messages.any { it === target }

    private fun isAttachableMedia(message: Message): Boolean {
        if (!message.isFileOrImage() && !message.treatAsDownloadable()) {
            return false
        }
        // Incoming HTTP/SCS media may be relation-complete before Content-Type/filename
        // hydration resolves its final presentation MIME. Unknown is therefore provisional
        // media; an explicitly known non-image/non-video MIME is still rejected.
        val mimeType = message.getMimeType()
        return mimeType == null ||
            mimeType.startsWith("image/") ||
            mimeType.startsWith("video/")
    }
}
