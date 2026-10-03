package eu.siacs.conversations.entities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import im.conversations.android.xmpp.model.stanza.Iq;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;

public class MucOccupantIdentityTest {

    private static final Jid ROOM = Jid.of("room@conference.example");

    @Test
    public void ownerSnapshotEnablesSelfRevokeOnlyWhenAnotherOwnerIsKnown() {
        final MucOptions options = conversation("owner-snapshot").getMucOptions();
        options.getSelf().setAffiliation("owner");

        final MucOptions.User selfOwner = affiliationUser(options, "me@example.test", "owner");
        options.applyAffiliationListSnapshot(
                MucOptions.Affiliation.OWNER, java.util.List.of(selfOwner));

        assertTrue(options.isOwnerAffiliationListKnown());
        assertEquals(1, options.getKnownOwnerCount());
        assertFalse(options.canSelfRevokeOwner());

        final MucOptions.User otherOwner =
                affiliationUser(options, "other@example.test", "owner");
        options.applyAffiliationListSnapshot(
                MucOptions.Affiliation.OWNER, java.util.List.of(selfOwner, otherOwner));

        assertEquals(2, options.getKnownOwnerCount());
        assertTrue(options.canSelfRevokeOwner());
        assertSame(otherOwner, options.findUserByRealJid(Jid.of("other@example.test")));
        assertNull(options.findUserByRealJid(Jid.of("me@example.test")));
    }

    @Test
    public void ownerAndAdminSnapshotsExposeOfflinePrivilegedUsers() {
        final MucOptions options = conversation("offline-affiliations").getMucOptions();
        final MucOptions.User owner =
                affiliationUser(options, "owner@example.test", "owner");
        final MucOptions.User admin =
                affiliationUser(options, "admin@example.test", "admin");

        options.applyAffiliationListSnapshot(
                MucOptions.Affiliation.OWNER, java.util.List.of(owner));
        options.applyAffiliationListSnapshot(
                MucOptions.Affiliation.ADMIN, java.util.List.of(admin));

        assertTrue(options.isOwnerAffiliationListKnown());
        assertTrue(options.isAdminAffiliationListKnown());
        assertSame(owner, options.findUserByRealJid(Jid.of("owner@example.test")));
        assertSame(admin, options.findUserByRealJid(Jid.of("admin@example.test")));
    }

    @Test
    public void occupantIdIsAcceptedOnlyForAdvertisedFeatureAndValidLength() {
        final MucOptions options = conversation("feature").getMucOptions();

        assertNull(options.acceptedOccupantId("stable"));
        enableOccupantIds(options);

        assertEquals("stable", options.acceptedOccupantId("stable"));
        assertNull(options.acceptedOccupantId(""));
        assertNull(options.acceptedOccupantId("x".repeat(129)));
        assertEquals("x".repeat(128), options.acceptedOccupantId("x".repeat(128)));
    }

    @Test
    public void sameOccupantReplacesOldNickInRoomRoster() {
        final MucOptions options = conversation("rename").getMucOptions();
        final MucOptions.User before = user(options, "alice", "occupant-a");
        final MucOptions.User after = user(options, "alice2", "occupant-a");

        options.updateUser(before);
        options.updateUser(after);

        assertEquals(1, options.getUserCount());
        assertNull(options.findUserByFullJid(ROOM.withResource("alice")));
        assertSame(after, options.findUserByFullJid(ROOM.withResource("alice2")));
        assertSame(after, options.findUserByOccupantId("occupant-a"));
    }

    @Test
    public void sameOccupantWithKnownRealJidIsNotReportedAsNew() {
        final MucOptions options = conversation("same-real-jid").getMucOptions();
        final MucOptions.User before = user(options, "alice", "occupant-a");
        before.setRealJid(Jid.of("alice@example.test"));
        final MucOptions.User after = user(options, "alice2", "occupant-a");
        after.setRealJid(Jid.of("alice@example.test"));

        assertTrue(options.updateUser(before));
        assertFalse(options.updateUser(after));
        assertSame(after, options.findUserByOccupantId("occupant-a"));
    }

    @Test
    public void messageResolverPrefersOccupantIdAcrossNickChange() {
        final Conversation conversation = conversation("message-resolution");
        final MucOptions options = conversation.getMucOptions();
        final MucOptions.User current = user(options, "alice2", "occupant-a");
        options.updateUser(current);

        final Message historical =
                new Message(conversation, "old message", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        historical.setCounterpart(ROOM.withResource("alice"));
        historical.setTrueCounterpart(Jid.of("other@example.test"));
        historical.setOccupantId("occupant-a");

        assertSame(current, options.resolveUser(historical));
    }

    @Test
    public void resolverFallsBackToRealJidWhenOccupantIdIsUnavailable() {
        final Conversation conversation = conversation("fallback");
        final MucOptions options = conversation.getMucOptions();
        final Jid realJid = Jid.of("alice@example.test");
        final MucOptions.User current = user(options, "alice2", null);
        current.setRealJid(realJid);
        options.updateUser(current);

        final Message historical =
                new Message(conversation, "old message", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        historical.setCounterpart(ROOM.withResource("alice"));
        historical.setTrueCounterpart(realJid);

        assertSame(current, options.resolveUser(historical));
    }

    @Test
    public void readMarkersTreatSameOccupantAcrossRenamesAsOneIdentity() {
        final ReadByMarker before =
                ReadByMarker.from(ROOM.withResource("alice"), null, "occupant-a");
        final ReadByMarker after =
                ReadByMarker.from(ROOM.withResource("alice2"), null, "occupant-a");
        final Set<ReadByMarker> markers = new HashSet<>();

        markers.add(before);
        markers.add(after);

        assertEquals(1, markers.size());
        assertTrue(ReadByMarker.contains(after, markers));
    }

    @Test
    public void readMarkersDoNotConflateReusedNickAcrossDifferentOccupants() {
        final ReadByMarker first =
                ReadByMarker.from(ROOM.withResource("alice"), null, "occupant-a");
        final ReadByMarker second =
                ReadByMarker.from(ROOM.withResource("alice"), null, "occupant-b");
        final Set<ReadByMarker> markers = new HashSet<>();

        markers.add(first);
        markers.add(second);

        assertEquals(2, markers.size());
        assertFalse(first.equals(second));
        assertFalse(ReadByMarker.contains(
                ReadByMarker.from(ROOM.withResource("alice2"), null, "occupant-c"),
                markers));
    }

    @Test
    public void readMarkerResolverUsesOccupantBeforeStaleNick() {
        final MucOptions options = conversation("read-marker-resolver").getMucOptions();
        final MucOptions.User current = user(options, "alice2", "occupant-a");
        options.updateUser(current);

        final ReadByMarker historical =
                ReadByMarker.from(ROOM.withResource("alice"), null, "occupant-a");

        assertSame(current, options.findUser(historical));
    }

    @Test
    public void readMarkerJsonRoundTripPreservesOccupantIdentity() {
        final ReadByMarker marker =
                ReadByMarker.from(ROOM.withResource("alice"), null, "occupant-a");

        final ReadByMarker restored = ReadByMarker.fromJson(marker.toJson());

        assertEquals("occupant-a", restored.getOccupantId());
        assertEquals(ROOM.withResource("alice"), restored.getFullJid());
        assertEquals(marker, restored);
    }

    @Test
    public void messageReadMarkerRejectsSelfOccupantButAcceptsDifferentOccupantWithSameNick() {
        final Conversation conversation = conversation("read-marker");
        final Message message =
                new Message(conversation, "message", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        message.setCounterpart(ROOM.withResource("alice"));
        message.setOccupantId("occupant-a");

        assertFalse(
                message.addReadByMarker(
                        ReadByMarker.from(
                                ROOM.withResource("alice2"), null, "occupant-a")));
        assertTrue(
                message.addReadByMarker(
                        ReadByMarker.from(
                                ROOM.withResource("alice"), null, "occupant-b")));
    }

    @Test
    public void invalidMessageOccupantIdFailsClosed() {
        final Conversation conversation = conversation("invalid-message-id");
        final Message message =
                new Message(conversation, "message", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);

        message.setOccupantId("x".repeat(129));

        assertNull(message.getOccupantId());
    }

    @Test
    public void roomScopedResolversDoNotLinkSameOccupantIdAcrossRooms() {
        final Conversation first = conversation("room-one");
        final Conversation second =
                new Conversation(
                        "room-two",
                        new Account(Jid.of("me@example.test"), ""),
                        Jid.of("other@conference.example"),
                        Conversation.MODE_MULTI,
                        null);
        final MucOptions.User firstUser =
                user(first.getMucOptions(), "alice", "same-opaque-id");
        final MucOptions.User secondUser =
                new MucOptions.User(
                        second.getMucOptions(),
                        Jid.of("other@conference.example/alice"));
        secondUser.setRole("participant");
        secondUser.setOccupantId("same-opaque-id");
        first.getMucOptions().updateUser(firstUser);
        second.getMucOptions().updateUser(secondUser);

        final Message firstMessage =
                new Message(first, "message", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        firstMessage.setCounterpart(ROOM.withResource("old"));
        firstMessage.setOccupantId("same-opaque-id");

        assertSame(firstUser, first.getMucOptions().resolveUser(firstMessage));
        assertFalse(first.getMucOptions().resolveUser(firstMessage) == secondUser);
    }

    private static Conversation conversation(final String id) {
        return new Conversation(
                id,
                new Account(Jid.of("me@example.test"), ""),
                ROOM,
                Conversation.MODE_MULTI,
                null);
    }

    private static MucOptions.User user(
            final MucOptions options,
            final String nick,
            final String occupantId) {
        final MucOptions.User user =
                new MucOptions.User(options, ROOM.withResource(nick));
        user.setRole("participant");
        user.setOccupantId(occupantId);
        return user;
    }

    private static MucOptions.User affiliationUser(
            final MucOptions options, final String jid, final String affiliation) {
        final MucOptions.User user = new MucOptions.User(options, null);
        user.setRealJid(Jid.of(jid));
        user.setAffiliation(affiliation);
        user.setRole("none");
        return user;
    }

    private static void enableOccupantIds(final MucOptions options) {
        final Iq result = new Iq(Iq.Type.RESULT);
        final Element query = result.addChild("query", Namespace.DISCO_INFO);
        query.addChild("feature").setAttribute("var", Namespace.OCCUPANT_ID);
        options.updateConfiguration(new ServiceDiscoveryResult(result));
    }
}
