package eu.siacs.conversations.xmpp;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.xmpp.model.stanza.Message;

/** XEP-0424 wire format only; sender authorization belongs to policy. */
public final class MessageRetractionProtocol {
    private MessageRetractionProtocol() {}

    public static Message newGroupchatRetraction(
            final Jid room,
            final Jid sender,
            final String targetRoomId,
            final String requestId) {
        if (room == null || !room.isBareJid()
                || sender == null
                || targetRoomId == null || targetRoomId.isBlank()
                || requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Invalid MUC retraction identity");
        }

        final Message packet = new Message();
        packet.setType(Message.Type.GROUPCHAT);
        packet.setTo(room);
        packet.setFrom(sender);
        packet.setId(requestId);

        packet.addChild("retract", Namespace.MESSAGE_RETRACT)
                .setAttribute("id", targetRoomId);
        packet.addChild("fallback", "urn:xmpp:fallback:0")
                .setAttribute("for", Namespace.MESSAGE_RETRACT);
        packet.setBody(
                "/me retracted a previous message, "
                        + "but your client does not support retraction.");
        packet.addChild("store", "urn:xmpp:hints");
        return packet;
    }

    /**
     * Detect even malformed retraction requests: their fallback body
     * must never become a regular chat message.
     */
    public static boolean isPlainRetractionMessage(final Element stanza) {
        if (stanza == null) return false;
        final Element retract =
                stanza.findChild("retract", Namespace.MESSAGE_RETRACT);
        return retract != null
                && retract.findChild("moderated", Namespace.MESSAGE_MODERATE) == null;
    }

    /**
     * Caller must validate the MAM wrapper before setting archiveValidated.
     * A forwarded stanza must belong to the same room as the MAM query.
     */
    public static boolean isTrustedMucDelivery(
            final boolean groupchat,
            final Jid from,
            final Jid archiveRoom,
            final boolean forwarded,
            final boolean archiveValidated) {
        if (!groupchat || from == null || from.isBareJid()) {
            return false;
        }
        if (archiveRoom == null) {
            return !forwarded;
        }
        return archiveValidated
                && archiveRoom.asBareJid()
                        .equals(from.asBareJid());
    }

    /** Parsing only. The caller MUST validate the sender before applying. */
    public static String plainRetractionTarget(final Element stanza) {
        if (stanza == null) return null;

        final Element retract =
                stanza.findChild("retract", Namespace.MESSAGE_RETRACT);
        if (retract == null
                || retract.findChild("moderated", Namespace.MESSAGE_MODERATE) != null) {
            return null;
        }

        final String id = retract.getAttribute("id");
        return id == null || id.isBlank() ? null : id;
    }

    /**
     * Tombstone id identifies the RETRACTION REQUEST, not the target message.
     * This method does not authorize or apply an archived retraction.
     */
    public static String plainTombstoneRequestId(final Element stanza) {
        if (stanza == null) return null;

        final Element retracted =
                stanza.findChild("retracted", Namespace.MESSAGE_RETRACT);
        if (retracted == null
                || retracted.findChild("moderated", Namespace.MESSAGE_MODERATE) != null) {
            return null;
        }

        final String id = retracted.getAttribute("id");
        return id == null || id.isBlank() ? null : id;
    }
}
