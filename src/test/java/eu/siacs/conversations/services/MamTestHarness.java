package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import android.content.ContentValues;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.parser.MessageParser;
import eu.siacs.conversations.persistance.DatabaseBackendStub;
import eu.siacs.conversations.services.MucJoinReliabilityTest.Fixture;
import eu.siacs.conversations.services.MucJoinReliabilityTest.TestService;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadCoordinator;
import eu.siacs.conversations.storage.secure.SecureMessageTextRepository;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.mam.MamReference;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;

/** Controlled transport and persistence seam. NOT an SQLite/fsync or real Store crash fixture. */
final class MamTestHarness {
    static final String MAM = "urn:xmpp:mam:2", RSM = "http://jabber.org/protocol/rsm";
    final RecordingService service;
    final Fixture f;
    final MessageParser parser;
    final long floorTime;
    MamTestHarness() throws Exception { this(null, null, null); }
    MamTestHarness(ContentValues saved, Account account, Map<String, Message> durable) throws Exception {
        service = new RecordingService();
        Conversation restored = saved == null ? null : restore(saved, account);
        f = account == null ? new Fixture(service) : new Fixture(service, account, restored);
        f.account.setXmppConnection(new XmppConnection(f.account, service));
        var axolotl = Account.class.getDeclaredField("axolotlService");
        axolotl.setAccessible(true); axolotl.set(f.account, new AxolotlService(f.account, service));
        service.databaseBackend = new DatabaseBackendStub() {
            @Override public void updateConversation(Conversation c) {
                if (service.failConversationWrite) throw new IllegalStateException("injected write failure");
                service.saved = new ContentValues(c.getContentValues());
            }
            @Override public boolean updateConversationForMam(Conversation c) {
                if (service.zeroConversationWrite) return false;
                updateConversation(c);
                return true;
            }
            @Override public ArrayList<Message> getMessagesByLocalUuids(
                    eu.siacs.conversations.entities.Conversational c, Collection<String> ids) {
                ArrayList<Message> found = new ArrayList<>();
                for (Message m : service.rows.values()) {
                    if (c.getUuid().equals(m.getConversationUuid()) && ids.contains(m.getUuid())) found.add(m);
                }
                return found;
            }
            @Override public eu.siacs.conversations.storage.secure.SecureMessagePayloadMode getSecureMessagePayloadMode(
                    String accountUuid, String uuid) {
                return service.rows.values().stream().filter(m -> uuid.equals(m.getUuid()))
                        .findFirst().map(Message::getSecureMessagePayloadMode).orElse(null);
            }
            @Override public boolean updateMessage(Message m, boolean includeBody) {
                service.updates++;
                return !service.failMessageUpdate;
            }
            @Override public boolean createProtectedTextMessage(Message m, String accountUuid) { return !service.dropProtectedMetadata; }
            @Override public Message getMessageWithServerMsgId(Conversation c, String id) { return service.rows.get(id); }
            @Override public Message getMessageWithRemoteMsgId(Conversation c, String id) {
                return service.rows.values().stream().filter(m -> id.equals(m.getRemoteMsgId())).findFirst().orElse(null);
            }
        };
        if (durable != null) {
            service.rows.putAll(durable);
            // Only these test-mode successful rows model prior commits. Metadata-only rows do not.
            durable.values().stream().filter(m -> m.getSecureMessagePayloadMode() == null)
                    .forEach(m -> service.simulatedCommitted.add(m.getUuid()));
        }
        floorTime = System.currentTimeMillis() - 60_000;
        if (saved == null) f.room.setMucRecoveryAnchor(new MamReference(floorTime, "floor:opaque:z"));
        parser = new MessageParser(service, f.account);
    }
    static Conversation restore(ContentValues v, Account account) {
        Conversation c = new Conversation(v.getAsString(Conversation.UUID), v.getAsString(Conversation.NAME),
                v.getAsString(Conversation.CONTACT), v.getAsString(Conversation.ACCOUNT),
                Jid.of(v.getAsString(Conversation.CONTACTJID)), v.getAsLong(Conversation.CREATED),
                v.getAsInteger(Conversation.STATUS), v.getAsInteger(Conversation.MODE),
                v.getAsString(Conversation.ATTRIBUTES), null);
        c.setAccount(account); return c;
    }
    void join() {
        service.joinMuc(f.room);
        Iq disco = new Iq(Iq.Type.RESULT);
        var info = disco.addChild("query", Namespace.DISCO_INFO);
        info.addChild("feature").setAttribute("var", MAM);
        info.addChild("feature").setAttribute("var", Namespace.STANZA_IDS);
        service.iqs.get(service.iqs.size() - 1).accept(disco);
        f.selfPresence(); drain();
    }
    void drain() { for (int i = 0; i < 8 && !service.catchups.isEmpty(); i++) service.runCatchups(); }
    String id(int i) { return service.mamRequests.get(i).findChild("query", MAM).getAttribute("queryid"); }
    MessageArchiveService.Query query(int i) { return service.getMessageArchiveService().findQuery(id(i)); }
    String cursor(int i, String direction) {
        return service.mamRequests.get(i).findChild("query", MAM).findChild("set", RSM).findChildContent(direction);
    }
    im.conversations.android.xmpp.model.stanza.Message live(int n, long time) {
        var m = new im.conversations.android.xmpp.model.stanza.Message();
        m.setFrom(Jid.of("room@conference.example/peer")); m.setTo(f.account.getJid());
        m.setAttribute("type", "groupchat"); m.setId("message-" + n); m.setBody("synthetic " + n);
        m.addChild("stanza-id", Namespace.STANZA_IDS).setAttribute("by", f.room.getJid().asBareJid().toString())
                .setAttribute("id", "archive-" + n);
        m.addChild("delay", "urn:xmpp:delay").setAttribute("stamp", java.time.Instant.ofEpochMilli(time).toString());
        return m;
    }
    im.conversations.android.xmpp.model.stanza.Message envelope(String query, int n, long time) {
        var m = new im.conversations.android.xmpp.model.stanza.Message();
        m.setFrom(f.room.getJid().asBareJid()); m.setTo(f.account.getJid());
        var result = m.addChild("result", MAM).setAttribute("queryid", query).setAttribute("id", "archive-" + n);
        var forwarded = new im.conversations.android.xmpp.model.forward.Forwarded(); result.addChild(forwarded);
        forwarded.addChild("delay", "urn:xmpp:delay").setAttribute("stamp", java.time.Instant.ofEpochMilli(time).toString());
        forwarded.addChild(live(n, time)); return m;
    }
    void result(int page, int n) { parser.accept(envelope(id(page), n, floorTime + n * 1000L)); }
    static Iq fin(boolean complete, String first, String last) {
        Iq iq = new Iq(Iq.Type.RESULT);
        var fin = iq.addChild("fin", MAM).setAttribute("complete", String.valueOf(complete));
        var set = fin.addChild("set", RSM);
        if (first != null) set.addChild("first").setContent(first);
        if (last != null) set.addChild("last").setContent(last);
        return iq;
    }
    void finish(int i, boolean complete, String first, String last) {
        service.mamCallbacks.get(i).accept(fin(complete, first, last)); drain();
    }
    void timeout(int i) { service.mamCallbacks.get(i).accept(Iq.TIMEOUT); drain(); }
    static Iq itemNotFound() {
        Iq iq = new Iq(Iq.Type.ERROR);
        var error = new im.conversations.android.xmpp.model.error.Error();
        error.setCondition(new im.conversations.android.xmpp.model.error.Condition.ItemNotFound());
        iq.addExtension(error); return iq;
    }
    static class RecordingService extends TestService {
        final Map<String, Message> rows = new LinkedHashMap<>();
        final Set<String> simulatedCommitted = new HashSet<>();
        int calls, writes, failureAt = -1, failures, updates;
        boolean zeroConversationWrite, failMessageUpdate;
        boolean throwWrite, realScsFailure, failConversationWrite, dropProtectedMetadata;
        ContentValues saved;
        Consumer<Consumer<Iq>> duringMamSend;
        SecureMessageTextRepository failingRepository;
        @Override boolean writeMucRecoveryAnchorRow(Conversation c) {
            return databaseBackend.updateConversationForMam(c);
        }
        @Override public boolean isMamMessagePersisted(Message m) {
            // Successful storage is deliberately simulated in this transport fixture. Unknown,
            // pending and unavailable rows go through the actual production validation method.
            if (m != null && simulatedCommitted.contains(m.getUuid())) return true;
            return super.isMamMessagePersisted(m);
        }
        @Override boolean isSecureContentAccountAvailableForMutation(Message m) { return true; }
        @Override public SecureMessageTextRepository getSecureMessageTextRepository() {
            if (failingRepository == null) {
                SecureMessagePayloadCoordinator coordinator = (SecureMessagePayloadCoordinator) Proxy.newProxyInstance(
                        SecureMessagePayloadCoordinator.class.getClassLoader(), new Class<?>[]{SecureMessagePayloadCoordinator.class},
                        (p, method, args) -> { throw new IOException("injected SCS failure"); });
                failingRepository = new SecureMessageTextRepository(coordinator, databaseBackend);
            }
            return failingRepository;
        }
        @Override public boolean persistIncomingMessage(Message m) {
            calls++;
            if (calls == failureAt) {
                failures++;
                if (realScsFailure) return super.persistIncomingMessage(m);
                if (throwWrite) throw new IllegalStateException("injected DB exception");
                return false;
            }
            rows.put(m.getServerMsgId(), m); simulatedCommitted.add(m.getUuid()); writes++; return true;
        }
        @Override public Conversation find(Account a, Jid j, Jid counterpart) { return room; }
        @Override public boolean checkIsArchived(Account a, Jid j, Jid counterpart) { return false; }
        @Override public Conversation findOrCreateConversation(Account a, Jid j, MessageArchiveService.Query q,
                boolean muc, boolean join, boolean async, Jid counterpart) { return room; }
        @Override public boolean confirmMessages() { return false; }
        @Override public boolean sendChatStates() { return false; }
        @Override public void sendIqPacket(Account a, Iq iq, Consumer<Iq> callback) {
            super.sendIqPacket(a, iq, callback);
            if (duringMamSend != null) duringMamSend.accept(callback);
        }
        @Override public NotificationService getNotificationService() {
            return new NotificationService(this) {
                @Override void updateErrorNotification() {}
                @Override public void push(Message m) {}
                @Override public void pushFromBacklog(Message m) {}
                @Override public void finishBacklog(boolean notify, Account a) {}
            };
        }
    }
}
