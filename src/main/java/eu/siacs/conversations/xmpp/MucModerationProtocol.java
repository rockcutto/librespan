package eu.siacs.conversations.xmpp;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.xmpp.model.stanza.Iq;

/** Wire identity and provenance rules shared by live and archived MUC moderation. */
public final class MucModerationProtocol {
    private MucModerationProtocol() {}

    public static boolean isAuthoritativeSender(final Jid from, final Jid room) {
        return from != null && room != null && from.isBareJid()
                && room.asBareJid().equals(from);
    }

    public static String roomStanzaId(final Element stanza, final Jid room) {
        if (stanza == null || room == null) return null;
        for (final Element child : stanza.getChildren()) {
            if ("stanza-id".equals(child.getName())
                    && Namespace.STANZA_IDS.equals(child.getNamespace())
                    && room.asBareJid().equals(
                            Jid.Invalid.getNullForInvalid(child.getAttributeAsJid("by")))) {
                final String id = child.getAttribute("id");
                if (id != null && !id.isEmpty()) return id;
            }
        }
        return null;
    }

    public static Element moderatedRetraction(final Element stanza) {
        final Element retract = stanza.findChild("retract", Namespace.MESSAGE_RETRACT);
        return retract != null
                        && retract.findChild("moderated", Namespace.MESSAGE_MODERATE) != null
                ? retract : null;
    }

    public static Element moderatedTombstone(final Element stanza) {
        final Element retracted = stanza.findChild("retracted", Namespace.MESSAGE_RETRACT);
        return retracted != null
                        && retracted.findChild("moderated", Namespace.MESSAGE_MODERATE) != null
                ? retracted : null;
    }

    public static ModerationTombstone archivedTombstone(
            final Element stanza, final Jid from, final Jid room) {
        final Element retracted = moderatedTombstone(stanza);
        // A MAM tombstone preserves the original message sender, so its inner 'from' can be a
        // full occupant JID (room/nick). The caller must already have validated the MAM wrapper as
        // coming from this room's archive. Keep the inner stanza scoped to the same room.
        if (retracted == null
                || from == null
                || room == null
                || !room.asBareJid().equals(from.asBareJid())) {
            return null;
        }
        final Element moderated = retracted.findChild("moderated", Namespace.MESSAGE_MODERATE);
        return new ModerationTombstone(
                moderated == null ? null : moderated.getAttribute("by"),
                retracted.findChildContent("reason"),
                retracted.getAttribute("stamp"));
    }

    public static ModerationEvent authoritativeLiveEvent(
            final Element stanza, final Jid from, final Jid room) {
        final Element retract = moderatedRetraction(stanza);
        if (retract == null || !isAuthoritativeSender(from, room)) {
            return null;
        }
        final String targetId = retract.getAttribute("id");
        if (targetId == null || targetId.isEmpty()) {
            return null;
        }
        final Element moderated = retract.findChild("moderated", Namespace.MESSAGE_MODERATE);
        return new ModerationEvent(
                targetId,
                moderated == null ? null : moderated.getAttribute("by"),
                retract.findChildContent("reason"));
    }

    public static final class ModerationEvent {
        public final String targetId;
        public final String by;
        public final String reason;

        private ModerationEvent(
                final String targetId, final String by, final String reason) {
            this.targetId = targetId;
            this.by = by;
            this.reason = reason;
        }
    }

    public static final class ModerationTombstone {
        public final String by;
        public final String reason;
        public final String stamp;

        private ModerationTombstone(
                final String by, final String reason, final String stamp) {
            this.by = by;
            this.reason = reason;
            this.stamp = stamp;
        }
    }

    public static boolean requestAccepted(final Iq response) {
        return response != null && response.getType() == Iq.Type.RESULT;
    }

    public static Iq moderationRequest(
            final Jid room, final String roomStanzaId, final String reason) {
        if (room == null || !room.isBareJid()
                || roomStanzaId == null || roomStanzaId.isEmpty()) {
            throw new IllegalArgumentException("MUC room stanza ID required");
        }
        final Iq iq = new Iq(Iq.Type.SET);
        iq.setTo(room);
        final Element moderate = iq.addChild("moderate", Namespace.MESSAGE_MODERATE);
        moderate.setAttribute("id", roomStanzaId);
        moderate.addChild("retract", Namespace.MESSAGE_RETRACT);
        if (reason != null && !reason.isBlank()) {
            moderate.addChild("reason").setContent(reason);
        }
        return iq;
    }
}
