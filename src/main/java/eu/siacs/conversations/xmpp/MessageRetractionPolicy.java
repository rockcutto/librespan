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
                || room.getAccount() == null
                || room.getAccount().getStatus() != Account.State.ONLINE
                || !room.getMucOptions().online()
                || !room.getMucOptions().participating()
                || message.isRetracted()
                || (message.isCarbon()
                    && !hasVerifiedOwnOccupantId(room, message))
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
     * MUC self-echo or archived own message may be marked as Carbon.
     * Accept it only when XEP-0421 identifies our current occupant.
     */
    private static boolean hasVerifiedOwnOccupantId(
            final Conversation room, final Message message) {
        final MucOptions options = room.getMucOptions();
        final String occupantId =
                options.acceptedOccupantId(message.getOccupantId());
        return occupantId != null && options.isSelf(occupantId);
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

        // XEP-0424: non-anonymous MUCs require the same full JID.
        // This remains mandatory when XEP-0421 is also advertised.
        if (options.nonanonymous()) {
            final Jid originalFrom = original.getCounterpart();
            if (originalFrom == null
                    || originalFrom.isBareJid()
                    || !originalFrom.equals(retractionFrom)) {
                return false;
            }

            // Nicknames can be reused. If XEP-0421 is advertised,
            // require occupant identity in addition to the full JID.
            if (!options.occupantId()) {
                return true;
            }
        }

        // Semi-anonymous MUCs and non-anonymous MUCs with XEP-0421 require matching IDs.
        // acceptedOccupantId() also checks advertised support.
        final String originalId =
                options.acceptedOccupantId(original.getOccupantId());
        final String retractionId =
                options.acceptedOccupantId(retractionOccupantId);

        return originalId != null
                && originalId.equals(retractionId);
    }
}
