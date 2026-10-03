package eu.siacs.conversations.xmpp;

import static org.junit.Assert.*;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.xmpp.model.stanza.Iq;
import org.junit.Test;

public class MucModerationProtocolTest {
    private static final Jid ROOM = Jid.of("room@conference.example");

    @Test
    public void requestUsesRoomAssignedStanzaIdAndLiteralCurrentNamespaces() {
        final Iq iq = MucModerationProtocol.moderationRequest(ROOM, "room-id-7", null);
        assertEquals(Iq.Type.SET, iq.getType());
        assertEquals(ROOM, iq.getTo());

        // Deliberately use literal URNs here so a bad Namespace constant cannot make
        // both production and test code agree on the same wrong wire value.
        assertEquals("urn:xmpp:message-moderate:1", Namespace.MESSAGE_MODERATE);
        assertEquals("urn:xmpp:message-retract:1", Namespace.MESSAGE_RETRACT);
        final Element moderate = iq.findChild("moderate", "urn:xmpp:message-moderate:1");
        assertNotNull(moderate);
        assertEquals("urn:xmpp:message-moderate:1", moderate.getNamespace());
        assertEquals("room-id-7", moderate.getAttribute("id"));
        final Element retract = moderate.findChild("retract", "urn:xmpp:message-retract:1");
        assertNotNull(retract);
        assertEquals("urn:xmpp:message-retract:1", retract.getNamespace());
        assertNull(moderate.findChild("reason"));
    }

    @Test
    public void iqResultOnlyAcknowledgesAcceptedRequest() {
        assertTrue(MucModerationProtocol.requestAccepted(new Iq(Iq.Type.RESULT)));
        assertFalse(MucModerationProtocol.requestAccepted(new Iq(Iq.Type.ERROR)));
        assertFalse(MucModerationProtocol.requestAccepted(Iq.TIMEOUT));
        assertFalse(MucModerationProtocol.requestAccepted(null));
    }

    @Test
    public void requestRejectsMissingTargetAndFullOccupantAddress() {
        assertThrows(IllegalArgumentException.class,
                () -> MucModerationProtocol.moderationRequest(ROOM, "", null));
        assertThrows(IllegalArgumentException.class,
                () -> MucModerationProtocol.moderationRequest(
                        Jid.of("room@conference.example/nick"), "id", null));
    }

    @Test
    public void roomIdNeverUsesOtherStanzaIdOrOriginId() {
        final Element message = new Element("message");
        message.addChild("origin-id", Namespace.STANZA_IDS).setAttribute("id", "client-id");
        message.addChild("stanza-id", Namespace.STANZA_IDS)
                .setAttribute("by", "elsewhere.example").setAttribute("id", "wrong");
        assertNull(MucModerationProtocol.roomStanzaId(message, ROOM));
        message.addChild("stanza-id", Namespace.STANZA_IDS)
                .setAttribute("by", ROOM).setAttribute("id", "room-id");
        assertEquals("room-id", MucModerationProtocol.roomStanzaId(message, ROOM));
    }

    @Test
    public void onlyBareRoomCanSendModerationEvent() {
        assertTrue(MucModerationProtocol.isAuthoritativeSender(ROOM, ROOM));
        assertFalse(MucModerationProtocol.isAuthoritativeSender(
                Jid.of("room@conference.example/nick"), ROOM));
        assertFalse(MucModerationProtocol.isAuthoritativeSender(
                Jid.of("other@conference.example"), ROOM));
    }

    @Test
    public void currentModerationAndTombstoneAreDistinctFromLegacy() {
        final Element live = new Element("message");
        final Element retract = live.addChild("retract", Namespace.MESSAGE_RETRACT);
        retract.setAttribute("id", "room-id");
        retract.addChild("moderated", Namespace.MESSAGE_MODERATE);
        assertSame(retract, MucModerationProtocol.moderatedRetraction(live));
        final Element old = new Element("message");
        old.addChild("apply-to", "urn:xmpp:fasten:0")
                .addChild("retract", "urn:xmpp:message-retract:0");
        assertNull(MucModerationProtocol.moderatedRetraction(old));
        final Element archived = new Element("message");
        final Element retracted = archived.addChild("retracted", Namespace.MESSAGE_RETRACT);
        retracted.addChild("moderated", Namespace.MESSAGE_MODERATE);
        assertSame(retracted, MucModerationProtocol.moderatedTombstone(archived));
    }

    @Test
    public void reasonIsOptionalAndNestedUnderModerate() {
        final Iq iq = MucModerationProtocol.moderationRequest(ROOM, "room-id", "spam");
        assertEquals("spam", iq.findChild("moderate", Namespace.MESSAGE_MODERATE)
                .findChildContent("reason"));
    }

    @Test
    public void mamResultIdCannotReplaceInnerRoomId() {
        final Element result = new Element("result");
        result.setAttribute("id", "archive-page-id");
        final Element forwarded = new Element("message");
        forwarded.addChild("stanza-id", Namespace.STANZA_IDS)
                .setAttribute("by", ROOM).setAttribute("id", "room-issued-id");
        assertEquals("room-issued-id", MucModerationProtocol.roomStanzaId(forwarded, ROOM));
        assertNotEquals(result.getAttribute("id"),
                MucModerationProtocol.roomStanzaId(forwarded, ROOM));
    }

    @Test
    public void emptyAndInvalidRoomStanzaIdsAreUnavailable() {
        final Element message = new Element("message");
        message.addChild("stanza-id", Namespace.STANZA_IDS)
                .setAttribute("by", ROOM).setAttribute("id", "");
        assertNull(MucModerationProtocol.roomStanzaId(message, ROOM));
        assertNull(MucModerationProtocol.roomStanzaId(message, null));
    }

    @Test
    public void occupantIssuedIdCannotBeModerated() {
        final Element message = new Element("message");
        message.addChild("stanza-id", Namespace.STANZA_IDS)
                .setAttribute("by", "room@conference.example/nick")
                .setAttribute("id", "untrusted-id");
        assertNull(MucModerationProtocol.roomStanzaId(message, ROOM));
    }

    @Test
    public void plainRetractionIsNotAProvenModerationEvent() {
        final Element message = new Element("message");
        message.addChild("retract", Namespace.MESSAGE_RETRACT).setAttribute("id", "target");
        assertNull(MucModerationProtocol.moderatedRetraction(message));
    }

    @Test
    public void tombstoneRequiresModerationMetadata() {
        final Element message = new Element("message");
        message.addChild("retracted", Namespace.MESSAGE_RETRACT);
        assertNull(MucModerationProtocol.moderatedTombstone(message));
    }

    @Test
    public void authoritativeLiveEventExtractsTargetActorAndReason() {
        final Element message = new Element("message");
        final Element retract = message.addChild("retract", Namespace.MESSAGE_RETRACT);
        retract.setAttribute("id", "room-id-9");
        retract.addChild("moderated", Namespace.MESSAGE_MODERATE)
                .setAttribute("by", "mod@example.test");
        retract.addChild("reason").setContent("spam");

        final MucModerationProtocol.ModerationEvent event =
                MucModerationProtocol.authoritativeLiveEvent(message, ROOM, ROOM);

        assertNotNull(event);
        assertEquals("room-id-9", event.targetId);
        assertEquals("mod@example.test", event.by);
        assertEquals("spam", event.reason);
    }

    @Test
    public void forgedOrIncompleteLiveEventIsRejected() {
        final Element message = new Element("message");
        final Element retract = message.addChild("retract", Namespace.MESSAGE_RETRACT);
        retract.setAttribute("id", "room-id");
        retract.addChild("moderated", Namespace.MESSAGE_MODERATE);

        assertNull(
                MucModerationProtocol.authoritativeLiveEvent(
                        message, Jid.of("room@conference.example/nick"), ROOM));
        assertNull(
                MucModerationProtocol.authoritativeLiveEvent(
                        message, Jid.of("other@conference.example"), ROOM));

        retract.setAttribute("id", "");
        assertNull(MucModerationProtocol.authoritativeLiveEvent(message, ROOM, ROOM));
    }

    @Test
    public void fallbackBodyDoesNotChangeModerationDetection() {
        final Element message = new Element("message");
        final Element retract = message.addChild("retract", Namespace.MESSAGE_RETRACT);
        retract.setAttribute("id", "target");
        retract.addChild("moderated", Namespace.MESSAGE_MODERATE);
        message.addChild("fallback", "urn:xmpp:fallback:0")
                .setAttribute("for", Namespace.MESSAGE_RETRACT);
        message.addChild("body").setContent("/me moderated a previous message");

        assertNotNull(MucModerationProtocol.moderatedRetraction(message));
        assertNotNull(MucModerationProtocol.authoritativeLiveEvent(message, ROOM, ROOM));
    }

    @Test
    public void archivedTombstoneExtractsActorReasonAndStamp() {
        final Element message = new Element("message");
        final Element retracted = message.addChild("retracted", Namespace.MESSAGE_RETRACT);
        retracted.setAttribute("stamp", "2026-10-02T00:00:00Z");
        retracted.addChild("moderated", Namespace.MESSAGE_MODERATE)
                .setAttribute("by", "mod@example.test");
        retracted.addChild("reason").setContent("spam");

        final MucModerationProtocol.ModerationTombstone tombstone =
                MucModerationProtocol.archivedTombstone(message, ROOM, ROOM);

        assertNotNull(tombstone);
        assertEquals("mod@example.test", tombstone.by);
        assertEquals("spam", tombstone.reason);
        assertEquals("2026-10-02T00:00:00Z", tombstone.stamp);
    }

    @Test
    public void archivedTombstoneAcceptsOriginalOccupantFromSameTrustedMamRoom() {
        final Element message = new Element("message");
        final Element retracted = message.addChild("retracted", Namespace.MESSAGE_RETRACT);
        retracted.addChild("moderated", Namespace.MESSAGE_MODERATE);

        assertNotNull(
                MucModerationProtocol.archivedTombstone(
                        message, Jid.of("room@conference.example/nick"), ROOM));
        assertNotNull(MucModerationProtocol.archivedTombstone(message, ROOM, ROOM));
    }

    @Test
    public void archivedTombstoneRejectsForeignSender() {
        final Element message = new Element("message");
        final Element retracted = message.addChild("retracted", Namespace.MESSAGE_RETRACT);
        retracted.addChild("moderated", Namespace.MESSAGE_MODERATE);

        assertNull(
                MucModerationProtocol.archivedTombstone(
                        message, Jid.of("other@conference.example/nick"), ROOM));
        assertNull(
                MucModerationProtocol.archivedTombstone(
                        message, Jid.of("other@conference.example"), ROOM));
    }

    @Test
    public void archivedTombstoneRejectsPlainRetractionMetadata() {
        final Element message = new Element("message");
        message.addChild("retracted", Namespace.MESSAGE_RETRACT)
                .setAttribute("stamp", "2026-10-02T00:00:00Z");

        assertNull(MucModerationProtocol.archivedTombstone(message, ROOM, ROOM));
    }

    @Test
    public void whitespaceReasonIsOmitted() {
        final Iq iq = MucModerationProtocol.moderationRequest(ROOM, "room-id", "   ");
        assertNull(iq.findChild("moderate", Namespace.MESSAGE_MODERATE).findChild("reason"));
    }
    @Test
    public void wrongModerationNamespacesAreRejectedFailClosed() {
        final Element wrongRetract = new Element("message");
        wrongRetract
                .addChild("retract", "urn:xmpp:message-retract:0")
                .setAttribute("id", "target")
                .addChild("moderated", "urn:xmpp:message-moderate:1");
        assertNull(MucModerationProtocol.moderatedRetraction(wrongRetract));
        assertNull(MucModerationProtocol.authoritativeLiveEvent(wrongRetract, ROOM, ROOM));

        final Element wrongModerate = new Element("message");
        wrongModerate
                .addChild("retract", "urn:xmpp:message-retract:1")
                .setAttribute("id", "target")
                .addChild("moderated", "urn:xmpp:message-moderate:0");
        assertNull(MucModerationProtocol.moderatedRetraction(wrongModerate));
        assertNull(MucModerationProtocol.authoritativeLiveEvent(wrongModerate, ROOM, ROOM));

        final Element wrongTombstone = new Element("message");
        wrongTombstone
                .addChild("retracted", "urn:xmpp:message-retract:0")
                .addChild("moderated", "urn:xmpp:message-moderate:1");
        assertNull(MucModerationProtocol.moderatedTombstone(wrongTombstone));
        assertNull(MucModerationProtocol.archivedTombstone(wrongTombstone, ROOM, ROOM));
    }

    @Test
    public void correctionsAndReactionsAreNotClassifiedAsModeration() {
        final Element correction = new Element("message");
        correction
                .addChild("replace", "urn:xmpp:message-correct:0")
                .setAttribute("id", "target");
        assertNull(MucModerationProtocol.moderatedRetraction(correction));
        assertNull(MucModerationProtocol.authoritativeLiveEvent(correction, ROOM, ROOM));

        final Element reaction = new Element("message");
        reaction
                .addChild("reactions", "urn:xmpp:reactions:0")
                .setAttribute("id", "target")
                .addChild("reaction")
                .setContent("ok");
        assertNull(MucModerationProtocol.moderatedRetraction(reaction));
        assertNull(MucModerationProtocol.authoritativeLiveEvent(reaction, ROOM, ROOM));
    }

}
