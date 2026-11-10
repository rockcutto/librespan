package eu.siacs.conversations.services;

import androidx.annotation.Nullable;

import java.util.List;

import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;

/**
 * Local-only dependency between an outgoing caption and its media when XEP-0367 is unavailable.
 * The existing mediaGroupId column persists the dependency; it is never sent as a wire relation.
 * A missing/ambiguous anchor must not allow the caption to be sent as an orphan.
 */
final class LocalDeferredMediaCaption {
    private static final String PREFIX = "librespan-local-caption:";

    private LocalDeferredMediaCaption() {}

    static void rememberWithoutXepRelation(final Message caption, final Message anchor) {
        if (caption == null || anchor == null || !caption.isTypeText()
                || !anchor.isFileOrImage() || caption.getConversation() != anchor.getConversation()
                || caption.getMediaGroupId() != null
                || anchor.getUuid() == null || anchor.getUuid().isEmpty()) {
            return;
        }
        for (final Element payload : caption.getPayloads()) {
            if ("attach-to".equals(payload.getName())
                    && Namespace.MESSAGE_ATTACHING.equals(payload.getNamespace())) {
                return; // Preserve the existing XEP-0367 path in direct chats.
            }
        }
        caption.setMediaGroupId(PREFIX + anchor.getUuid());
    }

    static boolean isManaged(final Message caption) {
        final String id = caption == null ? null : caption.getMediaGroupId();
        return caption != null && caption.isTypeText() && id != null
                && id.startsWith(PREFIX) && id.length() > PREFIX.length();
    }

    @Nullable
    static Message findAnchor(final Conversation conversation, final Message caption,
            final List<Message> snapshot) {
        if (!isManaged(caption) || caption.getConversation() != conversation
                || caption.getStatus() <= Message.STATUS_RECEIVED) {
            return null;
        }
        final String anchorUuid = caption.getMediaGroupId().substring(PREFIX.length());
        Message found = null;
        for (final Message media : snapshot) {
            if (media.getConversation() != conversation || !media.isFileOrImage()
                    || media.getStatus() <= Message.STATUS_RECEIVED || media.isDeleted()
                    || media.isModerated() || media.isRetracted()
                    || media.isPrivateMessage() != caption.isPrivateMessage()
                    || !anchorUuid.equals(media.getUuid())) {
                continue;
            }
            if (found != null) {
                return null; // Duplicate UUID: fail closed instead of choosing an arbitrary anchor.
            }
            found = media;
        }
        return found;
    }
}
