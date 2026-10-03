package eu.siacs.conversations.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import eu.siacs.conversations.parser.MessageParser.RoomStanzaIdentityDisposition;

import eu.siacs.conversations.xml.Element;
import org.junit.Test;

public class MessageParserLegacyRetractionTest {

    @Test
    public void historicalOrForwardedInvitesNeverExecute() {
        org.junit.Assert.assertTrue(MessageParser.shouldExecuteInvite(false, false));
        org.junit.Assert.assertFalse(MessageParser.shouldExecuteInvite(true, false));
        org.junit.Assert.assertFalse(MessageParser.shouldExecuteInvite(false, true));
        org.junit.Assert.assertFalse(MessageParser.shouldExecuteInvite(true, true));
    }

    @Test
    public void trustedMucMamResultIdSuppressesKnownModeratedReplay() {
        org.junit.Assert.assertTrue(
                MessageParser.shouldSuppressTrustedMucMamReplay("room-id", true));
        org.junit.Assert.assertFalse(
                MessageParser.shouldSuppressTrustedMucMamReplay("room-id", false));
        org.junit.Assert.assertFalse(
                MessageParser.shouldSuppressTrustedMucMamReplay(null, true));
        org.junit.Assert.assertFalse(
                MessageParser.shouldSuppressTrustedMucMamReplay("", true));
    }

    @Test
    public void moderatedReplayWinsOverCollisionAndIsSuppressedFailClosed() {
        assertEquals(
                RoomStanzaIdentityDisposition.SUPPRESS_MODERATED_REPLAY,
                MessageParser.classifyRoomStanzaIdentity("room-id", true, true));
        assertEquals(
                RoomStanzaIdentityDisposition.SUPPRESS_MODERATED_REPLAY,
                MessageParser.classifyRoomStanzaIdentity("room-id", true, false));
    }

    @Test
    public void unmoderatedDuplicateRoomIdIsStillRejectedAsSpoofCollision() {
        assertEquals(
                RoomStanzaIdentityDisposition.REJECT_COLLISION,
                MessageParser.classifyRoomStanzaIdentity("room-id", false, true));
        assertEquals(
                RoomStanzaIdentityDisposition.ACCEPT,
                MessageParser.classifyRoomStanzaIdentity("room-id", false, false));
        assertEquals(
                RoomStanzaIdentityDisposition.NONE,
                MessageParser.classifyRoomStanzaIdentity(null, false, false));
    }

    @Test
    public void legacyRetractZeroStillSuppliesReplacementTargetAndClearsFallbackBody() {
        final im.conversations.android.xmpp.model.stanza.Message packet =
                new im.conversations.android.xmpp.model.stanza.Message();
        packet.setBody("legacy fallback");
        packet.addChild("apply-to", "urn:xmpp:fasten:0")
                .setAttribute("id", "legacy-target")
                .addChild("retract", "urn:xmpp:message-retract:0");

        assertEquals(
                "legacy-target",
                MessageParser.applyLegacyRetractionFallback(packet));
        // The classifier must not mutate the stanza by appending a duplicate <body/>.
        assertEquals("legacy fallback", packet.getBody().content);
    }

    @Test
    public void currentRetractOneDoesNotEnterLegacyFallback() {
        final im.conversations.android.xmpp.model.stanza.Message packet =
                new im.conversations.android.xmpp.model.stanza.Message();
        packet.setBody("visible");
        packet.addChild("apply-to", "urn:xmpp:fasten:0")
                .setAttribute("id", "current-target")
                .addChild("retract", "urn:xmpp:message-retract:1");

        assertNull(MessageParser.applyLegacyRetractionFallback(packet));
        assertEquals("visible", packet.getBody().content);
    }

    @Test
    public void wrongFastenNamespaceDoesNotEnterLegacyFallback() {
        final im.conversations.android.xmpp.model.stanza.Message packet =
                new im.conversations.android.xmpp.model.stanza.Message();
        packet.setBody("visible");
        packet.addChild("apply-to", "urn:xmpp:fasten:1")
                .setAttribute("id", "legacy-target")
                .addChild("retract", "urn:xmpp:message-retract:0");

        assertNull(MessageParser.applyLegacyRetractionFallback(packet));
        assertEquals("visible", packet.getBody().content);
    }
}
