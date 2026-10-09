package eu.siacs.conversations.xmpp;

import static org.junit.Assert.*;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.xmpp.model.stanza.Message;
import org.junit.Test;

public class MessageRetractionProtocolTest {
    private static final Jid ROOM = Jid.of("room@conference.example");
    private static final Jid SELF = Jid.of("me@example.test/device");

    @Test
    public void groupchatRetractionUsesRoomIdAndNewRequestId() {
        final Message packet = MessageRetractionProtocol.newGroupchatRetraction(
                ROOM, SELF, "room-issued-123", "request-456");

        assertEquals(Message.Type.GROUPCHAT, packet.getType());
        assertEquals(ROOM, packet.getTo());
        assertEquals(SELF, packet.getFrom());
        assertEquals("request-456", packet.getId());

        final Element retract =
                packet.findChild("retract", "urn:xmpp:message-retract:1");
        assertNotNull(retract);
        assertEquals("room-issued-123", retract.getAttribute("id"));

        final Element fallback =
                packet.findChild("fallback", "urn:xmpp:fallback:0");
        assertNotNull(fallback);
        assertEquals(Namespace.MESSAGE_RETRACT, fallback.getAttribute("for"));
        assertNotNull(packet.findChild("store", "urn:xmpp:hints"));
        assertNotNull(packet.findChild("body"));
        assertEquals("room-issued-123",
                MessageRetractionProtocol.plainRetractionTarget(packet));
    }

    @Test
    public void invalidRoomOrMissingTargetIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MessageRetractionProtocol.newGroupchatRetraction(
                        Jid.of("room@conference.example/nick"),
                        SELF, "target", "request"));
        assertThrows(IllegalArgumentException.class,
                () -> MessageRetractionProtocol.newGroupchatRetraction(
                        ROOM, SELF, "", "request"));
    }

    @Test
    public void moderationIsNotPlainRetraction() {
        final Element stanza = new Element("message");
        stanza.addChild("retract", Namespace.MESSAGE_RETRACT)
                .setAttribute("id", "target")
                .addChild("moderated", Namespace.MESSAGE_MODERATE);

        assertNull(MessageRetractionProtocol.plainRetractionTarget(stanza));
    }

    @Test
    public void tombstoneIdBelongsToRequest() {
        final Element stanza = new Element("message");
        stanza.addChild("retracted", Namespace.MESSAGE_RETRACT)
                .setAttribute("id", "request-456");

        assertEquals("request-456",
                MessageRetractionProtocol.plainTombstoneRequestId(stanza));
        assertNull(MessageRetractionProtocol.plainRetractionTarget(stanza));
    }

    @Test
    public void unknownRetractionStillSuppressesFallback() {
        final Element stanza = new Element("message");
        stanza.addChild("retract", Namespace.MESSAGE_RETRACT)
                .setAttribute("id", "unknown-target");
        stanza.addChild("body").setContent("untrusted fallback");

        assertTrue(
                MessageRetractionProtocol.isPlainRetractionMessage(stanza));
    }

    @Test
    public void malformedRetractionStillSuppressesFallback() {
        final Element stanza = new Element("message");
        stanza.addChild("retract", Namespace.MESSAGE_RETRACT);

        assertTrue(
                MessageRetractionProtocol.isPlainRetractionMessage(stanza));
        assertNull(
                MessageRetractionProtocol.plainRetractionTarget(stanza));
    }

    @Test
    public void moderationAndLegacyFormatRemainSeparate() {
        final Element moderated = new Element("message");
        moderated.addChild("retract", Namespace.MESSAGE_RETRACT)
                .setAttribute("id", "target")
                .addChild("moderated", Namespace.MESSAGE_MODERATE);

        assertFalse(
                MessageRetractionProtocol.isPlainRetractionMessage(moderated));

        final Element legacy = new Element("message");
        legacy.addChild("apply-to", "urn:xmpp:fasten:0")
                .addChild("retract", "urn:xmpp:message-retract:0");

        assertFalse(
                MessageRetractionProtocol.isPlainRetractionMessage(legacy));
    }

    @Test
    public void liveMucDeliveryAcceptedButForwardingRejected() {
        final Jid alice =
                Jid.of("room@conference.example/alice");

        assertTrue(MessageRetractionProtocol.isTrustedMucDelivery(
                true, alice, null, false, false));

        assertFalse(MessageRetractionProtocol.isTrustedMucDelivery(
                true, alice, null, true, false));

        assertFalse(MessageRetractionProtocol.isTrustedMucDelivery(
                false, alice, null, false, false));
    }

    @Test
    public void mamRetractionMustBelongToQueriedRoom() {
        final Jid alice =
                Jid.of("room@conference.example/alice");

        assertTrue(MessageRetractionProtocol.isTrustedMucDelivery(
                true, alice, ROOM, true, true));

        assertFalse(MessageRetractionProtocol.isTrustedMucDelivery(
                true, alice,
                Jid.of("other@conference.example"),
                true, true));

        assertFalse(MessageRetractionProtocol.isTrustedMucDelivery(
                true, alice, ROOM, true, false));
    }

    @Test
    public void bareRoomSenderIsNotOrdinaryAuthorRetraction() {
        assertFalse(MessageRetractionProtocol.isTrustedMucDelivery(
                true, ROOM, null, false, false));
    }

    @Test
    public void missingRetractIdIsRejected() {
        final Element stanza = new Element("message");
        stanza.addChild("retract", Namespace.MESSAGE_RETRACT);

        assertNull(MessageRetractionProtocol.plainRetractionTarget(stanza));
    }
}
