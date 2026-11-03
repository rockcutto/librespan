package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import android.content.Intent;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.ChooseContactActivity;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.xmpp.model.stanza.Iq;
import im.conversations.android.xmpp.model.stanza.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucInviteAffiliationSafetyTest {
    @Test public void unknownOfflineMembersAndExistingAffiliationsAreNeverReset() {
        for (String actor : List.of("owner", "admin", "member")) {
            for (String target : List.of("unknown", "none", "member", "admin", "owner")) {
                Service service = new Service();
                Account account = new Account(Jid.of("sender@example.test"), "");
                Conversation room = new Conversation("room", account, Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
                room.getMucOptions().getSelf().setAffiliation(actor);
                Iq disco = new Iq(Iq.Type.RESULT);
                disco.addChild("query", eu.siacs.conversations.xml.Namespace.DISCO_INFO)
                        .addChild("feature").setAttribute("var", "muc_membersonly");
                room.getMucOptions().updateConfiguration(new eu.siacs.conversations.entities.ServiceDiscoveryResult(disco));
                Jid invited = Jid.of("target@example.test/resource");
                MucOptions.User user = null;
                if (!target.equals("unknown")) {
                    user = new MucOptions.User(room.getMucOptions(), Jid.of("room@conference.example/target"));
                    user.setRealJid(invited.asBareJid()); user.setRole("participant"); user.setAffiliation(target);
                    room.getMucOptions().updateUser(user);
                }
                service.invite(room, invited); service.invite(room, invited);
                assertEquals(0, service.iqs);
                assertEquals(2, service.messages.size());
                assertEquals(room.getJid(), service.messages.get(0).getTo());
                assertEquals(invited.asBareJid().toString(), service.messages.get(0)
                        .findChild("x", "http://jabber.org/protocol/muc#user").findChild("invite").getAttribute("to"));
                if (user != null) assertEquals(target, user.getAffiliation().toString());
                assertEquals(actor, room.getMucOptions().getSelf().getAffiliation().toString());
            }
        }
    }

    @Test public void knownOutcastIsNotUnbannedOrInvitedImplicitly() {
        Service service = new Service();
        Account account = new Account(Jid.of("sender@example.test"), "");
        Conversation room = new Conversation("room", account, Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
        MucOptions.User user = new MucOptions.User(room.getMucOptions(), Jid.of("room@conference.example/target"));
        user.setRealJid(Jid.of("target@example.test")); user.setRole("participant"); user.setAffiliation("member");
        room.getMucOptions().updateUser(user);
        user.setAffiliation("outcast"); // A confirmed affiliation change can leave an existing user object cached.
        service.invite(room, user.getRealJid());
        assertEquals(0, service.iqs); assertTrue(service.messages.isEmpty());
        assertEquals(1, service.errors);
        assertEquals(MucOptions.Affiliation.OUTCAST, user.getAffiliation());
    }

    @Test public void ordinaryOpenRoomInvitationDoesNotGrantMembership() {
        Service service = new Service();
        Account account = new Account(Jid.of("sender@example.test"), "");
        Conversation room = new Conversation("room", account, Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
        service.invite(room, Jid.of("target@example.test"));
        assertEquals(0, service.iqs); assertEquals(1, service.messages.size());
    }

    @Test public void sharedInviteUiDoesNotReportBlockedInvitationAsSent() {
        Service service = new Service();
        Account account = new Account(Jid.of("sender@example.test"), "");
        Conversation room = new Conversation("room", account, Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
        service.room = room;
        MucOptions.User user = new MucOptions.User(room.getMucOptions(), Jid.of("room@conference.example/target"));
        user.setRealJid(Jid.of("target@example.test")); user.setRole("participant"); user.setAffiliation("member");
        room.getMucOptions().updateUser(user); user.setAffiliation("outcast");
        var data = new Intent().putExtra(ChooseContactActivity.EXTRA_CONVERSATION, room.getUuid())
                .putExtra(ChooseContactActivity.EXTRA_SELECT_MULTIPLE, true)
                .putExtra("contacts", new String[] {"target@example.test"});
        var invite = XmppActivity.ConferenceInvite.parse(data);
        assertNotNull(invite);
        Activity activity = new Activity(service);
        assertEquals(XmppActivity.ConferenceInvite.Result.INVITES_PENDING, invite.execute(activity));
        assertEquals(1, activity.pending);
        assertTrue(service.messages.isEmpty());
        user.setAffiliation("member");
        assertEquals(XmppActivity.ConferenceInvite.Result.INVITES_PENDING, invite.execute(activity));
        assertEquals(2, activity.pending);
        assertEquals(0, service.messages.size()); assertEquals(0, service.iqs);
    }

    public static final class Activity extends XmppActivity {
        int pending;
        Activity(Service service) { xmppConnectionService = service; }
        @Override public void startMucInvitations(Conversation room, java.util.Collection<Jid> jids) { pending++; }
        @Override protected void onBackendConnected() {}
        @Override protected void refreshUiReal() {}
    }

    static final class Service extends XmppConnectionService {
        int iqs; int errors;
        Conversation room;
        @Override public Conversation findConversationByUuid(String uuid) { return room; }
        final List<Message> messages = new ArrayList<>();
        @Override public void sendIqPacket(Account account, Iq iq, Consumer<Iq> callback) { iqs++; }
        @Override public void sendIqPacket(Account account, Iq iq, Consumer<Iq> callback, Long timeout) { iqs++; }
        @Override public void sendMessagePacket(Account account, Message message) { messages.add(message); }
        @Override public void showErrorToastInUi(int message) { errors++; }
    }
}
