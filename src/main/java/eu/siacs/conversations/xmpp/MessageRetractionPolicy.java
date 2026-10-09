package eu.siacs.conversations.xmpp;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;

/** Conservative eligibility and sender verification for XEP-0424 in MUC. */
public final class MessageRetractionPolicy {
    private MessageRetractionPolicy() {}

    /** An own, sent, user-visible message with a reliable room-assigned ID. */
    public static boolean canRetractOwnMucMessage(
            final Conversation room, final Message message) {
        if (room == null || message == null
                || message.getConversation() != room
                || room.getMode() != Conversation.MODE_MULTI
                || room.getAccount().getStatus() != Account.State.ONLINE
                || !room.getMucOptions().online()
                || message.isCarbon()
                || message.isDeleted()
                || message.isModerated()
                || message.isPrivateMessage()
                || (message.getStatus() != Message.STATUS_SEND_RECEIVED
                    && message.getStatus() != Message.STATUS_SEND_DISPLAYED)
                || message.getRoomStanzaId() == null
                || message.getRoomStanzaId().isBlank()) {
            return false;
        }

        final int type = message.getType();
        if (type != Message.TYPE_TEXT
                && type != Message.TYPE_IMAGE
                && type != Message.TYPE_FILE) {
            return false;
        }

        return !message.getBody().isEmpty()
                || message.isFileOrImage()
                || message.hasProtectedTextPayload();
    }

    /**
     * Verify a retraction against an existing MUC message.
     * Caller must obtain the sender and occupant ID from the validated stanza,
     * not from user-supplied body text.
     */
    public static boolean isAuthorizedIncomingMucRetraction(
            final Conversation room,
            final Message original,
            final String targetRoomId,
            final Jid retractionFrom,
            final String retractionOccupantId) {
        if (room == null || original == null
                || original.getConversation() != room
                || room.getMode() != Conversation.MODE_MULTI
                || original.isPrivateMessage()
                || original.isModerated()
                || targetRoomId == null
                || targetRoomId.isBlank()
                || !targetRoomId.equals(original.getRoomStanzaId())
                || retractionFrom == null
                || retractionFrom.isBareJid()
                || !room.getJid().asBareJid()
                        .equals(retractionFrom.asBareJid())) {
            return false;
        }

        final MucOptions options = room.getMucOptions();

        if (options.nonanonymous()) {
            final Jid originalFrom = original.getCounterpart();
            return originalFrom != null
                    && !originalFrom.isBareJid()
                    && originalFrom.equals(retractionFrom);
        }

        // Semi-anonymous MUC: never fall back to nick comparison.
        final String originalId =
                options.acceptedOccupantId(original.getOccupantId());
        final String retractionId =
                options.acceptedOccupantId(retractionOccupantId);

        return originalId != null
                && retractionId != null
                && originalId.equals(retractionId);
    }
}
