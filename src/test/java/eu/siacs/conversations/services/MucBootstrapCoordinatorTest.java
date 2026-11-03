package eu.siacs.conversations.services;

import static org.junit.Assert.*;

import android.app.PendingIntent;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.generator.IqGenerator;
import eu.siacs.conversations.generator.PresenceGenerator;
import eu.siacs.conversations.parser.PresenceParser;
import eu.siacs.conversations.persistance.DatabaseBackendStub;
import eu.siacs.conversations.ui.UiCallback;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.forms.Data;
import im.conversations.android.xmpp.model.stanza.Iq;
import im.conversations.android.xmpp.model.stanza.Message;
import im.conversations.android.xmpp.model.stanza.Presence;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Production create/join/parser/bootstrap/F2A/F2C/generators; fake transport and timers only. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucBootstrapCoordinatorTest {
    static final Jid TARGET = Jid.of("first@example.test");
    static final Jid OTHER = Jid.of("second@example.test");
    static final String[] LISTS = {"owner", "admin", "member", "outcast"};

    @Test public void sentJoinDoesNotStartOwnerConfigurationOrInvitations() throws Exception {
        Fixture f = new Fixture(); f.start(List.of(TARGET));
        assertEquals(1, f.service.iqs.size()); assertEquals(1, f.service.presences.size());
        assertTrue(f.service.messages.isEmpty()); assertTrue(f.service.results.isEmpty());
    }
    @Test public void definitiveJoinErrorAndExistingJoinTimeoutTerminatePromptly() throws Exception {
        for (boolean timeout : List.of(false, true)) {
            Fixture f = new Fixture(); f.start(List.of(TARGET));
            if (timeout) f.service.deadlines.stream().filter(d -> d.seconds == eu.siacs.conversations.Config.CONNECT_TIMEOUT).findFirst().orElseThrow().fire();
            else {
                Presence error = f.self(false, false, "none"); error.setAttribute("type", "error");
                error.addChild("error").addChild("forbidden", Namespace.STANZAS);
                f.parser().parseConferencePresence(error, f.account);
            }
            f.terminal(); assertEquals(timeout ? MucBootstrapCoordinator.Reason.TIMEOUT : MucBootstrapCoordinator.Reason.REJECTED, f.result().reason);
            assertEquals(0, f.ownerSets()); assertTrue(f.service.messages.isEmpty());
        }
    }
    @Test public void cancelBeforeServerConfirmationPreventsAllBootstrapTraffic() throws Exception {
        Fixture f = new Fixture(); f.start(List.of(TARGET)); f.service.bootstrap.cancel();
        f.created(); f.terminal(); assertEquals(MucBootstrapCoordinator.Reason.CANCELLED, f.result().reason);
        assertEquals(1, f.service.iqs.size()); assertTrue(f.service.messages.isEmpty());
    }

    @Test public void current110And201OwnerBeginsOwnerGetOnly() throws Exception {
        Fixture f = new Fixture(); f.start(List.of(TARGET)); f.created();
        assertEquals(2, f.service.iqs.size());
        assertEquals(Iq.Type.GET, f.service.iqs.get(1).getType());
        assertNotNull(f.service.iqs.get(1).findChild("query", MucPasswordForm.OWNER));
        assertTrue(f.service.messages.isEmpty()); assertEquals(0, f.memberSets());
    }
    @Test public void old201AfterNewJoinAttemptCannotAuthorizeBootstrap() throws Exception {
        Fixture f = new Fixture(); f.start(List.of(TARGET)); Presence old = f.self(true, true, "owner");
        f.service.joinMucExplicitly(f.room()); // Pending join is deduplicated by the existing join lifecycle.
        f.room().getMucOptions().getJoinAttempt().finish();
        f.service.joinMucExplicitly(f.room());
        f.parser().parseConferencePresence(old, f.account);
        assertEquals(0, f.ownerSets()); assertEquals(0, f.memberSets()); assertTrue(f.service.messages.isEmpty());
    }
    @Test public void selfPresenceWithout201IsExistingRoomNotBootstrapAuthority() throws Exception {
        Fixture f = new Fixture(); f.start(List.of(TARGET));
        f.parser().parseConferencePresence(f.self(true, false, "owner"), f.account);
        f.terminal(); assertEquals(MucBootstrapCoordinator.Reason.EXISTING_ROOM, f.result().reason);
        assertFalse(f.result().serverCreated); assertEquals(1, f.service.iqs.size());
        assertEquals(0, f.memberSets()); assertTrue(f.service.messages.isEmpty());
    }
    @Test public void wrongOwnerAffiliationBlocksBootstrap() throws Exception {
        Fixture f = new Fixture(); f.start(List.of(TARGET));
        f.parser().parseConferencePresence(f.self(true, true, "member"), f.account);
        f.terminal(); assertEquals(MucBootstrapCoordinator.Reason.REJECTED, f.result().reason);
        assertEquals(1, f.service.iqs.size()); assertEquals(0, f.memberSets());
    }
    @Test public void uncorrelatedIdlessOrForeignRoomPresenceDoesNotStartConfig() throws Exception {
        for (String kind : List.of("idless", "wrong-id", "wrong-room", "missing110")) {
            Fixture f = new Fixture(); f.start(List.of(TARGET)); Presence packet = f.self(true, true, "owner");
            switch (kind) {
                case "idless": packet.getAttributes().remove("id"); break;
                case "wrong-id": packet.setId("muc-join-old-attempt"); break;
                case "wrong-room": packet.setFrom(Jid.of("other@conference.example/nick")); break;
                default: packet = f.self(false, true, "owner");
            }
            f.parser().parseConferencePresence(packet, f.account);
            assertEquals(1, f.service.iqs.size()); assertTrue(f.service.messages.isEmpty());
            f.overall().fire(); f.terminal();
        }
    }
    @Test public void configurationGetErrorStopsAdmission() throws Exception {
        Fixture f = new Fixture(); f.start(List.of(TARGET)); f.created(); f.reply(1, f.error());
        f.terminal(); assertEquals(0, f.ownerSets()); assertEquals(0, f.memberSets());
        assertTrue(f.service.messages.isEmpty()); assertEquals(0, f.service.success);
    }
    @Test public void configurationSetErrorStopsAdmission() throws Exception {
        Fixture f = new Fixture(); f.start(List.of(TARGET)); f.created(); f.reply(1, f.form(false)); f.reply(2, f.error());
        f.terminal(); assertEquals(MucBootstrapCoordinator.RoomState.NOT_CONFIGURED, f.result().roomState);
        assertEquals(1, f.ownerSets()); assertEquals(0, f.memberSets()); assertTrue(f.service.messages.isEmpty());
    }
    @Test public void configurationTimeoutTerminatesWithoutRetry() throws Exception {
        for (int stage : List.of(1, 2, 3, 4)) {
            Fixture f = new Fixture(); f.start(List.of(TARGET)); f.created();
            if (stage > 1) f.reply(1, f.form(false));
            if (stage > 2) f.reply(2, f.ok());
            if (stage > 3) f.reply(3, f.form(true));
            f.reply(stage, Iq.TIMEOUT); f.terminal();
            assertEquals(MucBootstrapCoordinator.Reason.TIMEOUT, f.result().reason);
            int count = f.service.iqs.size(); f.reply(stage, f.ok()); assertEquals(count, f.service.iqs.size());
            assertEquals(0, f.memberSets()); assertTrue(f.service.messages.isEmpty());
        }
    }
    @Test public void malformedOrIncompleteOwnerFormDoesNotSendConfigSet() throws Exception {
        for (String kind : List.of("missing", "wrong-type", "missing-member", "duplicate-member")) {
            Fixture f = new Fixture(); f.start(List.of(TARGET)); f.created(); Iq reply = f.form(false);
            Element form = reply.findChild("query", MucPasswordForm.OWNER).findChild("x", Namespace.DATA);
            if (kind.equals("missing")) reply.removeChild(reply.getChildren().get(0));
            if (kind.equals("wrong-type")) form.setAttribute("type", "result");
            if (kind.equals("missing-member")) form.getChildren().removeIf(e -> MucRoomConfiguration.MEMBERS.equals(e.getAttribute("var")));
            if (kind.equals("duplicate-member")) form.addChild("field").setAttribute("var", MucRoomConfiguration.MEMBERS).addChild("value").setContent("1");
            f.reply(1, reply); f.terminal(); assertEquals(0, f.ownerSets()); assertEquals(0, f.memberSets());
        }
    }
    @Test public void configurationReadbackMustConfirmClosedPolicy() throws Exception {
        Fixture f = new Fixture(); f.start(List.of(TARGET)); f.created(); f.reply(1, f.form(false)); f.reply(2, f.ok());
        f.reply(3, f.form(false)); f.terminal(); assertEquals(0, f.memberSets()); assertTrue(f.service.messages.isEmpty());
    }
    @Test public void duplicateConfigurationCallbacksCannotRepeatSetOrAdmission() throws Exception {
        Fixture f = new Fixture(); f.start(List.of(TARGET)); f.created();
        for (int i = 1; i <= 4; i++) {
            Iq reply = i == 1 ? f.form(false) : i == 2 ? f.ok() : i == 3 ? f.form(true) : f.disco(true);
            f.reply(i, reply); f.reply(i, reply);
        }
        assertEquals(1, f.ownerSets()); assertEquals(6, f.service.iqs.size());
        f.lists(5, TARGET, "member"); f.terminal(); assertEquals(1, f.service.messages.size());
    }
    @Test public void noneIsGrantedVerifiedThenDirectInvited() throws Exception {
        Fixture f = new Fixture(); f.setup(List.of(TARGET));
        assertTrue(f.service.messages.isEmpty()); int set = f.lists(5, TARGET, "none");
        assertEquals(1, f.memberSets()); assertTrue(f.service.messages.isEmpty());
        int readback = f.service.iqs.size(); f.reply(set, f.ok()); assertTrue(f.service.messages.isEmpty());
        f.lists(readback, TARGET, "member"); f.terminal();
        assertEquals(MucMembershipAdmission.Status.GRANTED_VERIFIED, f.result().recipients.get(TARGET).admission.status);
        assertEquals(MucBootstrapCoordinator.Invitation.HANDED_OFF, f.result().recipients.get(TARGET).invitation);
        assertEquals(1, f.service.success); assertEquals(1, f.service.bookmarks); f.safePackets();
    }
    @Test public void readbackAdminRemainsEligibleWithoutAnotherMembershipSet() throws Exception {
        Fixture f = new Fixture(); f.setup(List.of(TARGET)); int set = f.lists(5, TARGET, "none");
        int readback = f.service.iqs.size(); f.reply(set, f.ok()); f.lists(readback, TARGET, "admin"); f.terminal();
        assertEquals(MucMembershipAdmission.Status.ELIGIBLE_VERIFIED, f.result().recipients.get(TARGET).admission.status);
        assertEquals(1, f.result().handedOff()); assertEquals(1, f.memberSets());
    }
    @Test public void acknowledgedSetWithUnknownReadbackNeverInvites() throws Exception {
        Fixture f = new Fixture(); f.setup(List.of(TARGET)); int set = f.lists(5, TARGET, "none");
        int readback = f.service.iqs.size(); f.reply(set, f.ok()); f.reply(readback, f.error()); f.terminal();
        assertEquals(MucMembershipAdmission.WriteState.ACKNOWLEDGED, f.result().recipients.get(TARGET).admission.writeState);
        assertEquals(0, f.result().handedOff()); assertEquals(1, f.memberSets());
    }
    @Test public void busyAdmissionDoesNotBypassExistingMembershipLease() throws Exception {
        Fixture f = new Fixture(); f.start(List.of(TARGET)); f.created();
        f.reply(1, f.form(false)); f.reply(2, f.ok()); f.reply(3, f.form(true));
        var manual = f.service.admitMucMembership(f.account, f.room(), TARGET, result -> {});
        f.reply(4, f.disco(true)); f.terminal();
        assertEquals(MucMembershipAdmission.Status.BUSY, f.result().recipients.get(TARGET).admission.status);
        assertEquals(0, f.memberSets()); assertTrue(f.service.messages.isEmpty()); manual.cancel();
    }
    @Test public void redactedProtectedRoomSecretCannotBeClearedByConfiguration() throws Exception {
        Fixture f = new Fixture(); f.start(List.of(TARGET)); f.created(); Iq response = f.form(false);
        Data form = Data.parse(response.findChild("query", MucPasswordForm.OWNER).findChild("x", Namespace.DATA));
        form.put(MucPasswordForm.PROTECTED, "1").setAttribute("type", "boolean");
        form.put(MucPasswordForm.SECRET, "").setAttribute("type", "text-private");
        f.reply(1, response); f.terminal(); assertEquals(0, f.ownerSets()); assertEquals(0, f.memberSets());
    }

    @Test public void ownerAdminMemberAreInvitedWithoutSet() throws Exception {
        for (String role : List.of("owner", "admin", "member")) {
            Fixture f = new Fixture(); f.setup(List.of(TARGET)); f.lists(5, TARGET, role); f.terminal();
            assertEquals(0, f.memberSets()); assertEquals(1, f.result().handedOff()); f.safePackets();
        }
    }
    @Test public void outcastHasNoSetAndNoInvitation() throws Exception {
        Fixture f = new Fixture(); f.setup(List.of(TARGET)); f.lists(5, TARGET, "outcast"); f.terminal();
        assertEquals(0, f.memberSets()); assertEquals(0, f.result().handedOff());
        assertEquals(MucMembershipAdmission.Status.BANNED, f.result().recipients.get(TARGET).admission.status);
    }
    @Test public void unknownHasNoSetAndNoInvitation() throws Exception {
        Fixture f = new Fixture(); f.setup(List.of(TARGET)); f.reply(5, f.error()); f.terminal();
        assertEquals(0, f.memberSets()); assertTrue(f.service.messages.isEmpty());
        assertFalse(f.result().recipients.get(TARGET).admission.isEligible());
    }
    @Test public void uncertainWriteNeverRetriesOrInvites() throws Exception {
        Fixture f = new Fixture(); f.setup(List.of(TARGET)); int set = f.lists(5, TARGET, "none");
        f.reply(set, Iq.TIMEOUT); f.terminal();
        assertEquals(MucMembershipAdmission.Status.WRITE_UNCERTAIN, f.result().recipients.get(TARGET).admission.status);
        f.reply(set, f.ok()); assertEquals(1, f.memberSets()); assertTrue(f.service.messages.isEmpty());
    }
    @Test public void readbackOutcastAfterSetIsNotInvited() throws Exception {
        Fixture f = new Fixture(); f.setup(List.of(TARGET)); int set = f.lists(5, TARGET, "none");
        int readback = f.service.iqs.size(); f.reply(set, f.ok()); f.lists(readback, TARGET, "outcast");
        f.terminal(); assertEquals(1, f.memberSets()); assertTrue(f.service.messages.isEmpty());
        assertEquals(MucMembershipAdmission.WriteState.ACKNOWLEDGED, f.result().recipients.get(TARGET).admission.writeState);
    }
    @Test public void multipleRecipientsHaveSeparateAdmissionAndPartialOutcomes() throws Exception {
        Fixture f = new Fixture(); f.setup(List.of(TARGET, OTHER));
        f.lists(5, TARGET, "member"); assertTrue(f.service.results.isEmpty());
        f.lists(6, OTHER, "outcast"); f.terminal();
        assertEquals(2, f.result().recipients.size()); assertEquals(1, f.result().handedOff());
        assertEquals(MucBootstrapCoordinator.RoomState.CONFIGURED, f.result().roomState);
        assertEquals(1, f.service.success); assertEquals(0, f.service.archived); f.safePackets();
    }
    @Test public void bareDedupAndSelfExclusionDoNotAddRecipients() throws Exception {
        Fixture f = new Fixture(); f.setup(List.of(TARGET, TARGET.withResource("phone"), TARGET, f.account.getJid()));
        f.lists(5, TARGET, "member"); f.terminal();
        assertEquals(List.of(TARGET), new ArrayList<>(f.result().recipients.keySet()));
        assertEquals(1, f.service.messages.size()); assertEquals(0, f.memberSets());
    }
    @Test public void disconnectDuringConfigPreventsLateSetAndOldReconnectCallback() throws Exception {
        Fixture f = new Fixture(); f.start(List.of(TARGET)); f.created(); f.disconnect();
        f.account.setStatus(Account.State.ONLINE); f.reply(1, f.form(false)); f.terminal();
        assertEquals(MucBootstrapCoordinator.Reason.STALE, f.result().reason);
        assertEquals(0, f.ownerSets()); assertEquals(0, f.memberSets()); assertTrue(f.service.messages.isEmpty());
    }
    @Test public void disconnectAfterMembershipWriteNeverInvitesOrRetries() throws Exception {
        Fixture f = new Fixture(); f.setup(List.of(TARGET)); int set = f.lists(5, TARGET, "none");
        f.disconnect(); f.reply(set, f.ok()); f.terminal();
        assertEquals(1, f.memberSets()); assertTrue(f.service.messages.isEmpty());
        assertEquals(MucBootstrapCoordinator.RoomState.CONFIGURED, f.result().roomState);
        assertEquals(MucBootstrapCoordinator.Invitation.NOT_SENT, f.result().recipients.get(TARGET).invitation);
        assertTrue(f.result().recipients.get(TARGET).admissionUnconfirmed);
    }
    @Test public void resetAndConnectionReplacementStopConfigurationContinuation() throws Exception {
        for (String kind : List.of("options", "connection", "account", "archive")) {
            Fixture f = new Fixture(); f.start(List.of(TARGET)); f.created();
            if (kind.equals("options")) f.room().resetMucOptions();
            if (kind.equals("connection")) f.account.setXmppConnection(new XmppConnection(f.account, f.service));
            if (kind.equals("account")) f.service.registeredAccount = new Account(f.account.getJid(), "");
            if (kind.equals("archive")) f.room().setStatus(Conversation.STATUS_ARCHIVED);
            f.reply(1, f.form(false)); f.terminal(); assertEquals(0, f.ownerSets()); assertTrue(f.service.messages.isEmpty());
        }
    }
    @Test public void wholeOperationDeadlineAndDuplicateLateCallbacksAreFinite() throws Exception {
        Fixture f = new Fixture(); f.start(List.of(TARGET)); f.overall().fire(); f.terminal();
        f.created(); f.overall().fire(); assertEquals(1, f.service.results.size());
        assertEquals(MucBootstrapCoordinator.Reason.TIMEOUT, f.result().reason); assertTrue(f.service.messages.isEmpty());
    }
    @Test public void ownResourcesKeepFullJidHintsAndAreNotBlockedByBannedRecipient() throws Exception {
        Fixture f = new Fixture(); Jid own = f.account.getJid().withResource("other");
        f.service.resources = List.of(own, own, f.account.getJid());
        f.setup(List.of(TARGET)); assertEquals(1, f.service.messages.size());
        Message packet = f.service.messages.get(0); assertEquals(own, packet.getTo());
        assertNotNull(packet.findChild("no-store", "urn:xmpp:hints")); assertNotNull(packet.findChild("no-copy", "urn:xmpp:hints"));
        assertEquals("synthetic", packet.findChild("x", "jabber:x:conference").getAttribute("password"));
        f.lists(5, TARGET, "outcast"); f.terminal(); assertEquals(1, f.result().ownResources.size());
        assertEquals(0, f.memberSets()); assertEquals(0, f.result().handedOff());
    }
    @Test public void ownerConfigurationPreservesDefaultsAndUnknownFormFields() throws Exception {
        Fixture f = new Fixture(); f.start(List.of(TARGET)); f.created(); f.reply(1, f.form(false));
        Data submitted = Data.parse(f.service.iqs.get(2).findChild("query", MucPasswordForm.OWNER).findChild("x", Namespace.DATA));
        assertEquals("submit", submitted.getAttribute("type"));
        for (String key : IqGenerator.defaultGroupChatConfiguration().keySet()) {
            assertEquals(IqGenerator.defaultGroupChatConfiguration().getString(key), submitted.getValue(key));
        }
        assertEquals("preserve-me", submitted.getValue("server-required"));
        assertTrue(f.service.messages.isEmpty()); assertEquals(0, f.memberSets());
    }
    @Test public void admissionHandoffExceptionIsUnconfirmedAndNotRetried() throws Exception {
        Fixture f = new Fixture(); f.service.failMessage = true; f.setup(List.of(TARGET));
        f.lists(5, TARGET, "member"); f.terminal();
        assertEquals(MucBootstrapCoordinator.Invitation.UNCONFIRMED, f.result().recipients.get(TARGET).invitation);
        f.overall().fire(); assertEquals(1, f.service.messages.size()); assertEquals(0, f.memberSets());
    }
    @Test public void existingRoomCoordinatorRemainsDirectAndReadOnly() throws Exception {
        Fixture f = new Fixture(); f.setup(List.of());
        List<MucInvitationCoordinator.Summary> results = new ArrayList<>();
        int index = f.service.iqs.size(); f.service.inviteMucSafely(f.room(), List.of(TARGET), results::add);
        f.reply(index, f.disco(true)); f.lists(index + 1, TARGET, "member");
        assertEquals(1, results.size()); assertEquals(1, results.get(0).handedOff());
        assertEquals(0, f.memberSets()); f.safePackets();
    }

    static final class Fixture {
        final Service service = new Service();
        final Account account = new Account(Jid.of("creator@example.test/device"), "");
        Fixture() throws Exception {
            service.registeredAccount = account; account.setStatus(Account.State.ONLINE);
            account.setXmppConnection(new XmppConnection(account, service));
            service.databaseBackend = new DatabaseBackendStub();
            Field field = XmppConnectionService.class.getDeclaredField("mPresenceGenerator"); field.setAccessible(true);
            field.set(service, new PresenceGenerator(service) {
                @Override public Presence selfPresence(Account account, eu.siacs.conversations.entities.Presence.Status status, boolean personal) { return new Presence(); }
            });
        }
        Conversation room() { return service.room; }
        PresenceParser parser() { return new PresenceParser(service, account); }
        void start(List<Jid> jids) {
            assertTrue(service.createAdhocConference(account, "New room", jids, new UiCallback<Conversation>() {
                public void success(Conversation room) { service.success++; }
                public void error(int code, Conversation room) { service.errors++; }
                public void userInputRequired(PendingIntent intent, Conversation room) { fail("No extra UI workflow"); }
            }));
            reply(0, disco(false));
        }
        Presence self(boolean self, boolean created, String affiliation) {
            Presence packet = new Presence(); packet.setFrom(room().getMucOptions().getSelf().getFullJid());
            packet.setId(room().getMucOptions().getJoinAttempt().getPresenceId());
            Element x = packet.addChild("x", Namespace.MUC_USER);
            if (self) x.addChild("status").setAttribute("code", "110");
            if (created) x.addChild("status").setAttribute("code", "201");
            x.addChild("item").setAttribute("affiliation", affiliation).setAttribute("role", "moderator")
                    .setAttribute("jid", account.getJid());
            return packet;
        }
        void created() { parser().parseConferencePresence(self(true, true, "owner"), account); }
        void setup(List<Jid> jids) {
            start(jids); created(); reply(1, form(false)); reply(2, ok()); reply(3, form(true)); reply(4, disco(true));
        }
        Iq ok() { Iq reply = new Iq(Iq.Type.RESULT); reply.setFrom(room().getJid().asBareJid()); return reply; }
        Iq error() {
            Iq reply = new Iq(Iq.Type.ERROR); reply.setFrom(room().getJid().asBareJid());
            var error = new im.conversations.android.xmpp.model.error.Error();
            error.setCondition(new im.conversations.android.xmpp.model.error.Condition.Forbidden()); reply.addExtension(error); return reply;
        }
        Iq disco(boolean configured) {
            Iq reply = ok(); Element query = reply.addChild("query", Namespace.DISCO_INFO);
            if (configured) {
                query.addChild("feature").setAttribute("var", "muc_membersonly");
                query.addChild("feature").setAttribute("var", "muc_nonanonymous");
            }
            return reply;
        }
        Iq form(boolean configured) {
            Iq reply = ok(); Data form = new Data(); form.setAttribute("type", "form"); form.setFormType(MucPasswordForm.FORM);
            for (String key : IqGenerator.defaultGroupChatConfiguration().keySet()) {
                String value = IqGenerator.defaultGroupChatConfiguration().getString(key);
                if (!configured && MucRoomConfiguration.MEMBERS.equals(key)) value = "0";
                if (!configured && MucRoomConfiguration.WHOIS.equals(key)) value = "moderators";
                form.put(key, value).setAttribute("type", MucRoomConfiguration.WHOIS.equals(key) ? "list-single" : "boolean");
            }
            form.put("muc#roomconfig_roomname", "New room").setAttribute("type", "text-single");
            form.put("server-required", "preserve-me").setAttribute("type", "hidden");
            reply.addChild("query", MucPasswordForm.OWNER).addChild(form); return reply;
        }
        int lists(int first, Jid target, String affiliation) {
            int index = first;
            for (String role : LISTS) {
                assertEquals(Iq.Type.GET, service.iqs.get(index).getType());
                Iq reply = ok(); Element query = reply.addChild("query", Namespace.MUC_ADMIN);
                if (role.equals(affiliation)) query.addChild("item").setAttribute("jid", target).setAttribute("affiliation", role);
                int next = service.iqs.size(); reply(index, reply); index = next;
            }
            return index;
        }
        void reply(int index, Iq reply) { service.callbacks.get(index).accept(reply); }
        Deadline overall() { return service.deadlines.stream().filter(d -> d.seconds == MucBootstrapCoordinator.DEADLINE_SECONDS).findFirst().orElseThrow(); }
        MucBootstrapCoordinator.Result result() { return service.results.get(0); }
        void terminal() { assertEquals(1, service.results.size()); assertEquals(1, service.success + service.errors); safePackets(); }
        void disconnect() {
            account.setStatus(Account.State.OFFLINE); service.invalidateMucBootstraps(account); service.invalidateMucMembershipAdmissions(account);
        }
        int ownerSets() { return (int) service.iqs.stream().filter(i -> i.getType() == Iq.Type.SET && i.findChild("query", MucPasswordForm.OWNER) != null).count(); }
        int memberSets() { return (int) service.iqs.stream().filter(i -> i.getType() == Iq.Type.SET && i.findChild("query", Namespace.MUC_ADMIN) != null).count(); }
        void safePackets() {
            for (Iq iq : service.iqs) if (iq.getType() == Iq.Type.SET && iq.findChild("query", Namespace.MUC_ADMIN) != null) {
                for (Element item : iq.findChild("query", Namespace.MUC_ADMIN).getChildren()) {
                    assertEquals("member", item.getAttribute("affiliation"));
                    assertNotEquals(account.getJid().asBareJid().toString(), item.getAttribute("jid"));
                }
            }
            for (Message message : service.messages) {
                assertNotNull(message.findChild("x", "jabber:x:conference")); assertNull(message.findChild("x", Namespace.MUC_USER));
                assertEquals(room().getJid().asBareJid().toString(), message.findChild("x", "jabber:x:conference").getAttribute("jid"));
            }
        }
    }
    static final class Service extends XmppConnectionService {
        Conversation room; Account registeredAccount;
        MucBootstrapCoordinator.Request bootstrap;
        List<Jid> resources = List.of(); boolean failMessage; int success, errors, bookmarks, archived;
        final List<Iq> iqs = new ArrayList<>(); final List<Consumer<Iq>> callbacks = new ArrayList<>();
        final List<Message> messages = new ArrayList<>(); final List<Presence> presences = new ArrayList<>();
        final List<Deadline> deadlines = new ArrayList<>(); final List<MucBootstrapCoordinator.Result> results = new ArrayList<>();
        Service() { attachBaseContext(RuntimeEnvironment.getApplication()); }
        final AvatarService avatars = new AvatarService(this) {
            @Override public void clear(MucOptions options) {}
            @Override public void clear(MucOptions.User user) {}
        };
        @Override MucBootstrapCoordinator.Request startMucBootstrap(Conversation room, String name, Iterable<Jid> jids,
                Consumer<MucBootstrapCoordinator.Result> callback) {
            bootstrap = super.startMucBootstrap(room, name, jids, callback); return bootstrap;
        }
        @Override public String findConferenceServer(Account account, boolean onlyMain) { return "conference.example"; }
        @Override public Conversation findOrCreateConversation(Account account, Jid jid, MessageArchiveService.Query query,
                boolean muc, boolean joinAfterCreate, boolean async, Jid counterpart) {
            room = new Conversation("room", account, jid, Conversation.MODE_MULTI, null); return room;
        }
        @Override public Account findAccountByUuid(String uuid) { return registeredAccount; }
        @Override public Conversation findConversationByUuid(String uuid) { return room; }
        @Override public List<Conversation> findAll(Account account, Jid jid) { return room.getJid().asBareJid().equals(jid) ? List.of(room) : List.of(); }
        @Override public List<Conversation> getConversations() { return List.of(room); }
        @Override public AvatarService getAvatarService() { return avatars; }
        @Override public long getAutomaticMessageDeletionDate() { return 0L; }
        @Override public void updateConversationUi() {}
        @Override public void updateMucRosterUi() {}
        @Override public void updateConversation(Conversation room) {}
        @Override public void persistSelfNick(MucOptions.User self, boolean modified) {}
        @Override public void hydrateConversationSecrets(Conversation room) { room.getMucOptions().setPassword("synthetic"); }
        @Override public void saveConversationAsBookmark(Conversation room, String name) { bookmarks++; }
        @Override public void archiveConversation(Conversation room) { archived++; }
        @Override List<Jid> mucBootstrapOwnResources(Account account) { return resources; }
        @Override void showMucBootstrapResult(MucBootstrapCoordinator.Result result) { results.add(result); }
        @Override void executeMucAffiliationPreflight(Runnable work) { work.run(); }
        @Override ScheduledFuture<?> scheduleMucAffiliationPreflight(Runnable work, long seconds) { Deadline d = new Deadline(work, seconds); deadlines.add(d); return d; }
        @Override ScheduledFuture<?> scheduleMucJoinTimeout(Runnable work) { Deadline d = new Deadline(work, eu.siacs.conversations.Config.CONNECT_TIMEOUT); deadlines.add(d); return d; }
        @Override void dispatchMucJoinCatchup(Runnable work) {} // Existing MAM suite tests this separately.
        @Override public void sendIqPacket(Account account, Iq iq, Consumer<Iq> callback, Long timeout) { iqs.add(iq); callbacks.add(callback); }
        @Override public void sendIqPacket(Account account, Iq iq, Consumer<Iq> callback) { sendIqPacket(account, iq, callback, null); }
        @Override public void sendPresencePacket(Account account, Presence packet) { presences.add(packet); }
        @Override public void sendMessagePacket(Account account, Message message) { messages.add(message); if (failMessage) throw new IllegalStateException("Synthetic handoff failure"); }
    }
    static final class Deadline implements ScheduledFuture<Object> {
        final Runnable task; final long seconds; boolean cancelled;
        Deadline(Runnable task, long seconds) { this.task = task; this.seconds = seconds; }
        void fire() { task.run(); }
        public boolean cancel(boolean interrupt) { cancelled = true; return true; }
        public boolean isCancelled() { return cancelled; }
        public boolean isDone() { return cancelled; }
        public Object get() { return null; }
        public Object get(long value, TimeUnit unit) { return null; }
        public long getDelay(TimeUnit unit) { return 0; }
        public int compareTo(Delayed other) { return 0; }
    }
}
