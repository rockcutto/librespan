package eu.siacs.conversations.services;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;

/**
 * Ephemeral coordinator for one user-initiated outgoing media batch.
 *
 * <p>The batch token is local scheduling state. XMPP identity is derived exclusively from the
 * selected anchor message UUID, which MessageGenerator serializes as XEP-0359 origin-id.</p>
 */
final class MediaSendBatch {

    private final String batchId;
    private final int expectedMembers;
    private final List<Member> members = new ArrayList<>();
    private final boolean relationAllowed;

    @Nullable private Member anchor;
    private boolean sealed;
    private boolean fallback;
    private boolean aborted;

    MediaSendBatch(
            final String batchId, final int expectedMembers, final boolean relationAllowed) {
        this.batchId = batchId;
        this.expectedMembers = expectedMembers;
        this.relationAllowed = relationAllowed;
        this.fallback = expectedMembers < 2 || !relationAllowed;
    }

    String getBatchId() {
        return batchId;
    }

    synchronized void register(final Message message, final boolean eligibleMedia) {
        if (find(message) != null || sealed) {
            return;
        }
        members.add(new Member(message));
        if (!eligibleMedia) {
            fallback = true;
        }
        if (members.size() == expectedMembers) {
            sealed = true;
        }
    }

    synchronized List<Message> onReady(final Message message) {
        final Member member = find(message);
        if (member == null || member.finished) {
            return Collections.emptyList();
        }
        member.ready = true;
        if (aborted) {
            member.finished = true;
            return Collections.emptyList();
        }
        return releaseReadyMessages();
    }

    synchronized List<Message> onFailed(final Message message) {
        final Member member = find(message);
        if (member == null || member.finished) {
            return Collections.emptyList();
        }
        member.finished = true;
        if (!fallback) {
            // An album with a relation/caption must arrive in the UI as one result. Do not
            // degrade a partially prepared album into durable individual messages.
            aborted = true;
            for (final Member candidate : members) {
                if (candidate.ready) {
                    candidate.finished = true;
                }
            }
            return Collections.emptyList();
        }
        // A non-XEP-0367 fallback has no atomic relation to preserve.
        fallback = true;
        return releaseReadyMessages();
    }

    synchronized boolean isComplete() {
        if (!sealed || members.size() != expectedMembers) {
            return false;
        }
        for (final Member member : members) {
            if (!member.finished) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    synchronized Message getAnchorMessage() {
        return anchor == null ? null : anchor.message;
    }

    private List<Message> releaseReadyMessages() {
        if (!sealed && !fallback) {
            return Collections.emptyList();
        }
        if (fallback) {
            return releaseOrdinaryReadyMessages();
        }
        if (!allMembersReady()) {
            return Collections.emptyList();
        }

        anchor = members.get(0);
        final List<Message> readyMessages = new ArrayList<>(members.size());
        for (final Member member : members) {
            if (member != anchor) {
                addAttachTo(member.message, anchor.message.getWireUuid());
            }
            release(member, readyMessages);
        }
        return readyMessages;
    }

    private boolean allMembersReady() {
        for (final Member member : members) {
            if (!member.ready || member.finished) {
                return false;
            }
        }
        return true;
    }

    private List<Message> releaseOrdinaryReadyMessages() {
        final List<Message> readyMessages = new ArrayList<>();
        for (final Member member : members) {
            if (member.ready && !member.finished) {
                release(member, readyMessages);
            }
        }
        return readyMessages;
    }

    private static void release(final Member member, final List<Message> readyMessages) {
        member.finished = true;
        readyMessages.add(member.message);
    }

    private static void addAttachTo(final Message child, final String anchorId) {
        child.addPayload(
                new Element("attach-to", Namespace.MESSAGE_ATTACHING).setAttribute("id", anchorId));
    }

    @Nullable
    private Member find(final Message message) {
        for (final Member member : members) {
            if (member.message == message) {
                return member;
            }
        }
        return null;
    }

    private static final class Member {

        private final Message message;
        private boolean ready;
        private boolean finished;

        private Member(final Message message) {
            this.message = message;
        }
    }
}
