package eu.siacs.conversations.services;

import static org.junit.Assert.*;

import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.ui.adapter.MucUserGrouping;
import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.xmpp.model.stanza.Presence;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucLiveAffiliationTest {
    @Test
    public void repeatedPromotionAndDemotionWithCachedJoinIdRefreshBothListeners()
            throws Exception {
        assertTransitions("cached", true);
    }

    @Test
    public void repeatedPromotionAndDemotionWithFreshOrMissingIdStillWork()
            throws Exception {
        assertTransitions("fresh", true);
        assertTransitions(null, true);
    }

    @Test
    public void serverIdentifiedSelfWithout110CanChangeAffiliation()
            throws Exception {
        assertTransitions(null, false);
        assertTransitions("cached", false);
    }

    private void assertTransitions(final String idMode, final boolean include110)
            throws Exception {
        final var f = active();
        final String id = "cached".equals(idMode) ? currentId(f) : idMode;
        final int sent = f.service.sent.size();
        final int iq = f.service.iqs.size();
        final int catchups = f.service.catchups.size();
        for (String[] change : new String[][] {
                {"admin", "moderator"},
                {"member", "participant"},
                {"admin", "moderator"},
                {"owner", "moderator"},
                {"admin", "moderator"},
                {"member", "moderator"},
                {"member", "participant"}}) {
            final String affiliation = change[0];
            final String role = change[1];
            final var previous = f.options().getSelf();
            final var previousAffiliation = previous.getAffiliation();
            final int ui = f.service.ui;
            final int roster = f.service.roster;
            f.parser.accept(update(f, id, affiliation, role, include110));
            assertEquals(MucOptions.Affiliation.of(affiliation),
                    f.options().getSelf().getAffiliation());
            assertEquals(MucOptions.Role.of(role), f.options().getSelf().getRole());
            assertEquals(affiliation, f.room.getAttribute("affiliation"));
            assertEquals(role, f.room.getAttribute("role"));
            assertNotSame(previous, f.options().getSelf());
            assertEquals(previousAffiliation, previous.getAffiliation());
            assertEquals(ui + 1, f.service.ui);
            assertEquals(roster + 1, f.service.roster);
            assertEquals("admin".equals(affiliation) || "owner".equals(affiliation),
                    f.options().getSelf().getAffiliation().ranks(MucOptions.Affiliation.ADMIN));
            assertEquals("moderator".equals(role),
                    f.options().getSelf().getRole().ranks(MucOptions.Role.MODERATOR));
            final MucUserGrouping.Section expectedSection =
                    "owner".equals(affiliation) ? MucUserGrouping.Section.OWNERS
                    : "admin".equals(affiliation) ? MucUserGrouping.Section.ADMINS
                    : "moderator".equals(role) ? MucUserGrouping.Section.MODERATORS
                    : MucUserGrouping.Section.PARTICIPANTS;
            assertEquals(expectedSection,
                    MucUserGrouping.sectionFor(f.options().getSelf().getAffiliation(),
                            f.options().getSelf().getRole()));
            assertEquals(0, f.options().getUserCount()); // self is never a duplicate roster row
            assertTrue(f.options().online());
            assertFalse(f.options().getJoinAttempt().isPending());
        }
        assertEquals(sent, f.service.sent.size());
        assertEquals(iq, f.service.iqs.size());
        assertEquals(catchups, f.service.catchups.size());
    }

    @Test
    public void obsoleteJoinIdCannotPromoteOrDemoteCurrentSession() throws Exception {
        final var f = active();
        unchanged(f, update(f, "muc-join-obsolete", "admin", "moderator", true));
        f.parser.accept(update(f, currentId(f), "admin", "moderator", true));
        assertEquals(MucOptions.Affiliation.ADMIN, f.options().getSelf().getAffiliation());
        unchanged(f, update(f, "muc-join-obsolete", "member", "participant", true));
    }

    @Test
    public void delayedPresenceCannotRollBackLivePermissions() throws Exception {
        for (String namespace : new String[] {"urn:xmpp:delay", "jabber:x:delay"}) {
            final var f = active();
            f.parser.accept(update(f, currentId(f), "admin", "moderator", true));
            Presence stale = update(f, currentId(f), "member", "participant", true);
            stale.addChild(namespace.equals("urn:xmpp:delay") ? "delay" : "x", namespace)
                    .setAttribute("stamp", "2020-01-01T00:00:00Z");
            unchanged(f, stale);
        }
    }

    @Test
    public void missing110RequiresOurRealJid() throws Exception {
        final var f = active();
        Presence otherAccount = update(f, null, "admin", "moderator", false);
        otherAccount.findChild("x", Namespace.MUC_USER).findChild("item")
                .setAttribute("jid", "someone-else@example.test/device");
        unchanged(f, otherAccount);
        Presence missingJid = new Presence();
        missingJid.setFrom(f.options().getSelf().getFullJid());
        missingJid.addChild("x", Namespace.MUC_USER).addChild("item")
                .setAttribute("affiliation", "admin").setAttribute("role", "moderator");
        unchanged(f, missingJid);
    }

    @Test
    public void cachedIdCannotAuthenticateMalformedOrContradictorySelfUpdates()
            throws Exception {
        final var f = active();
        Presence otherNick = update(f, currentId(f), "admin", "moderator", true);
        otherNick.setFrom(f.room.getJid().asBareJid().withResource("another-occupant"));
        unchanged(f, otherNick);
        Presence wrongJid = update(f, currentId(f), "admin", "moderator", true);
        wrongJid.findChild("x", Namespace.MUC_USER).findChild("item")
                .setAttribute("jid", "other@example.test/device");
        unchanged(f, wrongJid);
        unchanged(f, update(f, currentId(f), "invalid", "moderator", true));
        unchanged(f, update(f, currentId(f), "admin", null, true));
    }

    @Test
    public void unrelatedOccupantPresenceIsNotDiscarded() throws Exception {
        final var f = active();
        Presence peer = new Presence();
        peer.setFrom(f.room.getJid().asBareJid().withResource("peer"));
        peer.addChild("x", Namespace.MUC_USER).addChild("item")
                .setAttribute("affiliation", "member").setAttribute("role", "participant")
                .setAttribute("jid", "peer@example.test");
        final int before = f.service.roster;
        f.parser.accept(peer);
        assertEquals(MucOptions.Affiliation.MEMBER,
                f.options().findUserByFullJid(peer.getFrom()).getAffiliation());
        assertEquals(before + 1, f.service.roster);
    }

    @Test
    public void completedJoinErrorRemainsRejected() throws Exception {
        final var f = active();
        unchanged(f, f.error(currentId(f), "forbidden"));
    }

    @Test
    public void cachedPresenceCannotReviveFinishedOfflineSession() throws Exception {
        final var f = active();
        f.options().setOffline();
        unchanged(f, update(f, currentId(f), "admin", "moderator", true));
        assertFalse(f.options().online());
    }

    private static MucJoinReliabilityTest.Fixture active() throws Exception {
        final var f = new MucJoinReliabilityTest.Fixture();
        f.join();
        f.selfPresence();
        return f;
    }

    private static String currentId(final MucJoinReliabilityTest.Fixture f) {
        return f.options().getJoinAttempt().getPresenceId();
    }

    private static Presence update(final MucJoinReliabilityTest.Fixture f, final String id,
            final String affiliation, final String role, final boolean selfCode) {
        final Presence packet = new Presence();
        packet.setFrom(f.options().getSelf().getFullJid());
        if (id != null) packet.setId(id);
        final var x = packet.addChild("x", Namespace.MUC_USER);
        if (selfCode) x.addChild("status").setAttribute("code", "110");
        final var item = x.addChild("item").setAttribute("affiliation", affiliation);
        if (role != null) item.setAttribute("role", role);
        item.setAttribute("jid", f.account.getJid().toString());
        return packet;
    }

    private static void unchanged(final MucJoinReliabilityTest.Fixture f,
            final Presence packet) {
        final var self = f.options().getSelf();
        final int ui = f.service.ui;
        final int roster = f.service.roster;
        f.parser.accept(packet);
        assertSame(self, f.options().getSelf());
        assertEquals(ui, f.service.ui);
        assertEquals(roster, f.service.roster);
    }
}
