package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucPasswordPermissionsTest {
    @Test public void onlyExactOwnerOfConnectedRootMucCanManage() {
        Service service = new Service();
        Account account = new Account(Jid.of("owner@example.test"), "");
        account.setStatus(Account.State.ONLINE);
        account.setXmppConnection(new XmppConnection(account, service));
        Conversation room = new Conversation("room", account, Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
        room.getMucOptions().getSelf().setAffiliation("owner");
        assertTrue(MucPasswordController.canManage(room));
        for (String affiliation : new String[] {"admin", "member", "none"}) {
            room.getMucOptions().getSelf().setAffiliation(affiliation);
            room.getMucOptions().getSelf().setRole("moderator");
            assertFalse(MucPasswordController.canManage(room));
        }
        room.getMucOptions().getSelf().setAffiliation("owner");
        account.setStatus(Account.State.OFFLINE);
        assertFalse(MucPasswordController.canManage(room));
        account.setStatus(Account.State.ONLINE);
        account.setXmppConnection(null);
        assertFalse(MucPasswordController.canManage(room));
    }

    @Test public void losingOwnershipBeforeSubmissionCannotSendAnyIq() {
        Service service = new Service();
        Account account = new Account(Jid.of("owner@example.test"), "");
        account.setStatus(Account.State.ONLINE);
        account.setXmppConnection(new XmppConnection(account, service));
        Conversation room = new Conversation("room", account, Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
        room.getMucOptions().getSelf().setAffiliation("owner");
        assertTrue(MucPasswordController.canManage(room));
        room.getMucOptions().getSelf().setAffiliation("admin");
        AtomicReference<MucPasswordController.Status> status = new AtomicReference<>();
        new MucPasswordController(service).change(room, true, "new password", false, status::set);
        assertEquals(MucPasswordController.Status.REJECTED, status.get());
        assertEquals(0, service.requests);
    }

    @Test public void oneToOneAndPrivateMucConversationsDoNotExposeOwnerForm() {
        Service service = new Service();
        Account account = new Account(Jid.of("owner@example.test"), "");
        account.setStatus(Account.State.ONLINE);
        account.setXmppConnection(new XmppConnection(account, service));
        for (int mode : new int[] {Conversation.MODE_SINGLE, Conversation.MODE_MULTI}) {
            Conversation room = new Conversation("chat", account, Jid.of("room@conference.example"), mode,
                    mode == Conversation.MODE_MULTI ? Jid.of("room@conference.example/nick") : null);
            room.getMucOptions().getSelf().setAffiliation("owner");
            assertFalse(MucPasswordController.canManage(room));
        }
    }

    private static final class Service extends XmppConnectionService {
        int requests;
        @Override public void sendIqPacket(Account account, Iq packet, Consumer<Iq> callback, Long timeout) { requests++; }
    }
}
