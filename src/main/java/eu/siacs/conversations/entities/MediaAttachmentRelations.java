package eu.siacs.conversations.entities;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import eu.siacs.conversations.entities.media.MediaCaptionResolver;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;

/**
 * Read-only resolver for validated XEP-0367 media attachment metadata.
 *
 * <p>Invalid or unsupported data always resolves to an empty result. This class does not infer
 * relations from local gallery metadata and does not backfill delayed messages.</p>
 */
public final class MediaAttachmentRelations {

    private static final String ATTACH_TO = "attach-to";

    private MediaAttachmentRelations() {
        throw new IllegalStateException("Utility class");
    }

    public static boolean isAttachmentChild(@Nullable final Message message) {
        return resolveAnchor(message) != null;
    }

    /**
     * Compatibility entry point for callers that still resolve captions from conversation state.
     *
     * <p>Caption validation itself lives in {@link MediaCaptionResolver}; presentation code must
     * supply a snapshot and avoid this per-row convenience method.</p>
     */
    @Nullable
    public static Message getCaption(@Nullable final Message anchor) {
        final Conversation conversation = getDirectConversation(anchor);
        return conversation == null
                ? null
                : MediaCaptionResolver.getCaption(anchor, snapshotMessages(conversation));
    }

    /** Compatibility entry point backed by {@link MediaCaptionResolver}. */
    public static boolean isCaptionChild(@Nullable final Message message) {
        final Conversation conversation = getDirectConversation(message);
        return conversation != null
                && MediaCaptionResolver.isCaptionChild(message, snapshotMessages(conversation));
    }

    @Nullable
    public static Message resolveAnchor(@Nullable final Message child) {
        final Conversation conversation = getDirectConversation(child);
        if (conversation == null || child == null || !isAttachableMedia(child)) {
            return null;
        }

        final String anchorId = getAttachmentId(child);
        if (anchorId == null) {
            return null;
        }

        final Message anchor =
                conversation.findMessageWithRemoteIdAndCounterpart(anchorId, child.getCounterpart());
        if (anchor == null
                || anchor == child
                || anchor.getConversation() != conversation
                || anchor.isPrivateMessage()
                || anchor.getStatus() != child.getStatus()
                || !sameCounterpart(anchor.getCounterpart(), child.getCounterpart())
                || !isAttachableMedia(anchor)
                || hasAttachmentPayload(anchor)) {
            return null;
        }

        return anchor;
    }

    /**
     * Returns the direct anchor and all currently resolvable direct children, or an empty list.
     *
     * <p>The anchor is first. Children are ordered by timestamp and UUID to make the result stable.
     * A normal individual media message is not treated as an anchor unless a valid child explicitly
     * references it.</p>
     */
    public static List<Message> getRelatedMedia(@Nullable final Message message) {
        final Conversation conversation = getDirectConversation(message);
        if (conversation == null || message == null || !isAttachableMedia(message)) {
            return Collections.emptyList();
        }

        final Message resolvedAnchor = resolveAnchor(message);
        if (resolvedAnchor != null) {
            return relatedMediaForAnchor(conversation, resolvedAnchor);
        }

        if (hasAttachmentPayload(message)) {
            return Collections.emptyList();
        }

        return relatedMediaForAnchor(conversation, message);
    }

    @Nullable
    private static Conversation getDirectConversation(@Nullable final Message message) {
        if (message == null || !(message.getConversation() instanceof Conversation conversation)) {
            return null;
        }
        if (conversation.getMode() != Conversational.MODE_SINGLE || message.isPrivateMessage()) {
            return null;
        }
        return conversation;
    }

    private static List<Message> relatedMediaForAnchor(
            final Conversation conversation, final Message anchor) {
        final List<Message> children = new ArrayList<>();
        final List<Message> messages;
        synchronized (conversation.messages) {
            messages = new ArrayList<>(conversation.messages);
        }

        for (final Message candidate : messages) {
            if (resolveAnchor(candidate) == anchor) {
                children.add(candidate);
            }
        }

        if (children.isEmpty()) {
            return Collections.emptyList();
        }

        children.sort(
                Comparator.comparingLong(Message::getTimeSent)
                        .thenComparing(
                                Message::getUuid,
                                Comparator.nullsFirst(Comparator.naturalOrder())));

        final List<Message> related = new ArrayList<>(children.size() + 1);
        related.add(anchor);
        related.addAll(children);
        return Collections.unmodifiableList(related);
    }

    @Nullable
    private static String getAttachmentId(final Message message) {
        Element attachment = null;
        for (final Element payload : message.getPayloads()) {
            if (!ATTACH_TO.equals(payload.getName())
                    || !Namespace.MESSAGE_ATTACHING.equals(payload.getNamespace())) {
                continue;
            }
            if (attachment != null
                    || !payload.getChildren().isEmpty()
                    || payload.getContent() != null) {
                return null;
            }
            attachment = payload;
        }

        if (attachment == null) {
            return null;
        }

        final String id = attachment.getAttribute("id");
        if (id == null || id.isEmpty() || !id.equals(id.trim())) {
            return null;
        }
        return id;
    }

    private static boolean hasAttachmentPayload(final Message message) {
        for (final Element payload : message.getPayloads()) {
            if (ATTACH_TO.equals(payload.getName())
                    && Namespace.MESSAGE_ATTACHING.equals(payload.getNamespace())) {
                return true;
            }
        }
        return false;
    }

    private static List<Message> snapshotMessages(final Conversation conversation) {
        synchronized (conversation.messages) {
            return new ArrayList<>(conversation.messages);
        }
    }

    private static boolean sameCounterpart(@Nullable final Jid left, @Nullable final Jid right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.equals(right)
                || left.asBareJid().equals(right.asBareJid());
    }

    private static boolean isAttachableMedia(final Message message) {
        if (!message.isFileOrImage() && !message.treatAsDownloadable()) {
            return false;
        }
        final String mimeType = message.getMimeType();
        return mimeType != null
                && (mimeType.startsWith("image/") || mimeType.startsWith("video/"));
    }
}
