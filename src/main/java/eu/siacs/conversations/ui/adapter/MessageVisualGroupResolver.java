package eu.siacs.conversations.ui.adapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.utils.UIHelper;

/**
 * Pure presentation grouping for consecutive chat bubbles.
 *
 * <p>This deliberately does not reuse {@link Message#mergeable(Message)}. Message.mergeable()
 * protects semantic/text merge behavior and has encryption/status/read-marker constraints that
 * should not split a visual sender run.
 */
final class MessageVisualGroupResolver {

    private static final long VISUAL_GROUP_WINDOW_MS = 5 * 60 * 1000L;

    enum Position {
        SINGLE,
        FIRST,
        MIDDLE,
        LAST;

        boolean startsGroup() {
            return this == SINGLE || this == FIRST;
        }

        boolean endsGroup() {
            return this == SINGLE || this == LAST;
        }
    }

    private MessageVisualGroupResolver() {}

    @NonNull
    static Position resolve(
            @NonNull final Message current,
            @Nullable final Message previousVisibleBubble,
            @Nullable final Message nextVisibleBubble) {
        final boolean withPrevious = sameGroup(previousVisibleBubble, current);
        final boolean withNext = sameGroup(current, nextVisibleBubble);
        if (withPrevious && withNext) {
            return Position.MIDDLE;
        } else if (withPrevious) {
            return Position.LAST;
        } else if (withNext) {
            return Position.FIRST;
        } else {
            return Position.SINGLE;
        }
    }

    static boolean sameGroup(
            @Nullable final Message left,
            @Nullable final Message right) {
        if (!isBubble(left) || !isBubble(right)) {
            return false;
        }
        if (left.getConversation() != right.getConversation()) {
            return false;
        }
        if (isIncoming(left) != isIncoming(right)) {
            return false;
        }

        // Counterpart is the visual author for received MUC/private messages and the target for
        // sent private messages. Requiring it to match also prevents PMs from joining a room run.
        if (!Objects.equals(left.getCounterpart(), right.getCounterpart())) {
            return false;
        }

        final boolean leftPrivate = left.getType() == Message.TYPE_PRIVATE;
        final boolean rightPrivate = right.getType() == Message.TYPE_PRIVATE;
        if (leftPrivate != rightPrivate) {
            return false;
        }

        if (!UIHelper.sameDay(left.getTimeSent(), right.getTimeSent())) {
            return false;
        }
        return Math.abs(right.getTimeSent() - left.getTimeSent()) <= VISUAL_GROUP_WINDOW_MS;
    }

    private static boolean isIncoming(@NonNull final Message message) {
        return message.getStatus() <= Message.STATUS_RECEIVED;
    }

    private static boolean isBubble(@Nullable final Message message) {
        return message != null
                && message.getType() != Message.TYPE_STATUS
                && message.getType() != Message.TYPE_RTP_SESSION;
    }
}
