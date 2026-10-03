package eu.siacs.conversations.entities;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import eu.siacs.conversations.entities.media.MediaCaptionPresentation;
import eu.siacs.conversations.entities.media.MediaCaptionResolver;
import eu.siacs.conversations.xml.Element;

/**
 * Immutable, conversation-scoped presentation snapshot for validated incoming XEP-0367 media
 * relations.
 *
 * <p>Build this class outside adapter row binding and replace it when the owning conversation
 * revision changes. It never changes messages, payloads, or the conversation list.</p>
 */
public final class MediaGalleryPresentation {

    private final Conversation conversation;
    private final long revision;
    private final List<Message> snapshot;
    private final List<String> relationStates;
    private final Map<Message, List<Message>> albums;

    private MediaGalleryPresentation(
            final Conversation conversation,
            final long revision,
            final List<Message> snapshot,
            final List<String> relationStates,
            final Map<Message, List<Message>> albums) {
        this.conversation = conversation;
        this.revision = revision;
        this.snapshot = snapshot;
        this.relationStates = relationStates;
        this.albums = albums;
    }

    /**
     * Resolves all usable direct XEP-0367 relations in one immutable conversation snapshot.
     *
     * <p>The caller owns {@code revision} and must increment it whenever the conversation message
     * state used for presentation changes.</p>
     */
    public static MediaGalleryPresentation forSnapshot(
            final Conversation conversation, final long revision, final List<Message> snapshot) {
        final List<Message> messages =
                Collections.unmodifiableList(new ArrayList<>(snapshot));
        final List<String> relationStates = relationStates(messages);
        final Map<Message, List<Message>> albums = new IdentityHashMap<>();
        if (conversation.getMode() != Conversational.MODE_SINGLE) {
            return new MediaGalleryPresentation(
                    conversation,
                    revision,
                    messages,
                    relationStates,
                    Collections.unmodifiableMap(albums));
        }
        final Map<Message, List<Message>> childrenByAnchor = new IdentityHashMap<>();

        for (final Message candidate : messages) {
            if (candidate == null || candidate.getConversation() != conversation) {
                continue;
            }
            final Message anchor = MediaAttachmentRelations.resolveAnchor(candidate);
            if (anchor == null || !containsIdentity(messages, anchor)) {
                continue;
            }
            childrenByAnchor.computeIfAbsent(anchor, ignored -> new ArrayList<>()).add(candidate);
        }

        for (final Map.Entry<Message, List<Message>> entry : childrenByAnchor.entrySet()) {
            final List<Message> children = entry.getValue();
            children.sort(
                    Comparator.comparingLong(Message::getTimeSent)
                            .thenComparing(
                                    Message::getUuid,
                                    Comparator.nullsFirst(Comparator.naturalOrder())));

            final List<Message> album = new ArrayList<>(children.size() + 1);
            album.add(entry.getKey());
            album.addAll(children);
            final List<Message> immutableAlbum = Collections.unmodifiableList(album);

            for (final Message message : immutableAlbum) {
                albums.put(message, immutableAlbum);
            }
        }

        return new MediaGalleryPresentation(
                conversation,
                revision,
                messages,
                relationStates,
                Collections.unmodifiableMap(albums));
    }

    public boolean hasAlbum(@Nullable final Message message) {
        return message != null && albums.containsKey(message);
    }

    /** Returns an immutable album with at least an anchor and one validated child. */
    public List<Message> getAlbum(@Nullable final Message message) {
        final List<Message> album = message == null ? null : albums.get(message);
        return album == null ? Collections.emptyList() : album;
    }

    /**
     * Compatibility lookup only. Caption presentation is owned by {@link MediaCaptionPresentation};
     * adapter binding must use that immutable caption snapshot instead.
     */
    @Nullable
    public Message getCaption(@Nullable final Message anchor) {
        return MediaCaptionResolver.getCaption(anchor, snapshot);
    }

    /** Returns false after the owner replaces or invalidates the conversation presentation state. */
    public boolean isCurrentFor(final Conversation conversation, final long revision) {
        return this.conversation == conversation && this.revision == revision;
    }

    /** Returns true only when this immutable presentation was built for the supplied snapshot. */
    public boolean matchesSnapshot(
            final Conversation conversation, final List<Message> snapshot) {
        return this.conversation == conversation
                && this.snapshot.equals(snapshot)
                && this.relationStates.equals(relationStates(snapshot));
    }

    /**
     * Captures only fields that can affect media or caption relation resolution. Incoming messages
     * may be completed in place after the row has already been shown, so object identity and
     * payload count alone are not sufficient to detect a stale presentation.
     */
    private static List<String> relationStates(final List<Message> messages) {
        final List<String> states = new ArrayList<>(messages.size());
        for (final Message message : messages) {
            if (message == null) {
                states.add(null);
                continue;
            }
            final StringBuilder state = new StringBuilder();
            state.append(message.getUuid()).append('\u0000');
            state.append(message.getRemoteMsgId()).append('\u0000');
            state.append(message.getCounterpart()).append('\u0000');
            state.append(message.getTrueCounterpart()).append('\u0000');
            state.append(message.getStatus()).append('\u0000');
            state.append(message.getType()).append('\u0000');
            state.append(message.getBody()).append('\u0000');
            state.append(message.getTimeSent()).append('\u0000');
            state.append(message.isPrivateMessage()).append('\u0000');
            state.append(message.isFileOrImage()).append('\u0000');
            state.append(message.treatAsDownloadable()).append('\u0000');
            state.append(message.isOOb()).append('\u0000');
            state.append(message.isGeoUri()).append('\u0000');
            state.append(message.getReplyOrReaction() != null).append('\u0000');
            state.append(message.edited()).append('\u0000');
            state.append(message.getMimeType()).append('\u0000');
            for (final Element payload : message.getPayloads()) {
                state.append(payload).append('\u0000');
            }
            states.add(state.toString());
        }
        return Collections.unmodifiableList(states);
    }

    private static boolean containsIdentity(final List<Message> messages, final Message target) {
        for (final Message message : messages) {
            if (message == target) {
                return true;
            }
        }
        return false;
    }
}
