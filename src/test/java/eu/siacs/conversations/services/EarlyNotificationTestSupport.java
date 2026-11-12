package eu.siacs.conversations.services;

import android.os.SystemClock;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.entities.*;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.parser.MessageParser;
import eu.siacs.conversations.persistance.DatabaseBackendStub;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import java.lang.reflect.Field;
import java.util.*;
import org.robolectric.shadows.ShadowSystemClock;
import java.time.Duration;

/** Real parser, notification stack/filter/backlog; controlled durability and Android output. */
final class EarlyNotificationTestSupport implements AutoCloseable {
    final Service service = new Service();
    final MucJoinReliabilityTest.Fixture fixture = new MucJoinReliabilityTest.Fixture(service);
    final MessageParser parser;
    EarlyNotificationTestSupport() throws Exception {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10));
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            var manager = service.getSystemService(android.app.NotificationManager.class);
            manager.createNotificationChannel(new android.app.NotificationChannel(
                    "messages", "messages", android.app.NotificationManager.IMPORTANCE_HIGH));
            manager.createNotificationChannel(new android.app.NotificationChannel(
                    "silent_messages", "silent", android.app.NotificationManager.IMPORTANCE_LOW));
        }
        fixture.room.setAttribute(Conversation.ATTRIBUTE_ALWAYS_NOTIFY, true);
        fixture.room.setStatus(Conversation.STATUS_AVAILABLE);
        fixture.account.setXmppConnection(new XmppConnection(fixture.account, service));
        Field axolotl = Account.class.getDeclaredField("axolotlService");
        axolotl.setAccessible(true);
        axolotl.set(fixture.account, new AxolotlService(fixture.account, service));
        service.databaseBackend = new DatabaseBackendStub() {
            @Override public eu.siacs.conversations.xmpp.mam.MamReference getLastMessageReceived(Account account) {
                return service.durableCheckpoint;
            }
            @Override public eu.siacs.conversations.xmpp.mam.MamReference getLastClearDate(Account account) {
                return new eu.siacs.conversations.xmpp.mam.MamReference(0);
            }
            @Override public Message getMessageWithServerMsgId(Conversation c, String id) {
                return service.rows.values().stream().filter(m -> c.getUuid().equals(m.getConversationUuid())
                        && id.equals(m.getServerMsgId())).findFirst().orElse(null);
            }
            @Override public Message getMessageWithRemoteMsgId(Conversation c, String id) {
                return service.rows.values().stream().filter(m -> c.getUuid().equals(m.getConversationUuid())
                        && id.equals(m.getRemoteMsgId())).findFirst().orElse(null);
            }
        };
        parser = new MessageParser(service, fixture.account);
    }
    im.conversations.android.xmpp.model.stanza.Message packet(int n, boolean media) {
        var packet = new im.conversations.android.xmpp.model.stanza.Message();
        packet.setFrom(Jid.of("room@conference.example/peer"));
        packet.setTo(fixture.account.getJid());
        packet.setAttribute("type", "groupchat"); packet.setId("event-" + n);
        packet.setBody(media ? "https://files.example.test/" + n + ".jpg" : "event " + n);
        packet.addChild("stanza-id", "urn:xmpp:sid:0")
                .setAttribute("by", fixture.room.getJid().toString()).setAttribute("id", "archive-" + n);
        if (media) packet.addChild("x", "jabber:x:oob").addChild("url")
                .setContent("https://files.example.test/" + n + ".jpg");
        return packet;
    }
    Message message() { return service.last; }
    @SuppressWarnings("unchecked") MessageArchiveService.Query archive(
            MessageArchiveService.MucHistoryPurpose purpose) throws Exception {
        MessageArchiveService.Query query = service.getMessageArchiveService().new Query(fixture.account,
                MessageArchiveService.Version.MAM_2,
                new eu.siacs.conversations.xmpp.mam.MamReference(0), 0);
        Field owner = MessageArchiveService.Query.class.getDeclaredField("conversation");
        owner.setAccessible(true); owner.set(query, fixture.room);
        Field intent = MessageArchiveService.Query.class.getDeclaredField("mucHistoryPurpose");
        intent.setAccessible(true); intent.set(query, purpose);
        Field active = MessageArchiveService.class.getDeclaredField("queries"); active.setAccessible(true);
        ((Set<MessageArchiveService.Query>)active.get(service.getMessageArchiveService())).add(query);
        return query;
    }
    im.conversations.android.xmpp.model.stanza.Message archived(
            MessageArchiveService.Query query, int n, boolean media) {
        var envelope = new im.conversations.android.xmpp.model.stanza.Message();
        envelope.setFrom(fixture.room.getJid().asBareJid()); envelope.setTo(fixture.account.getJid());
        var result = envelope.addChild("result", query.version.namespace)
                .setAttribute("queryid", query.getQueryId()).setAttribute("id", "archive-" + n);
        var forwarded = new im.conversations.android.xmpp.model.forward.Forwarded();
        result.addChild(forwarded);
        forwarded.addChild("delay", "urn:xmpp:delay").setAttribute("stamp", "2001-01-01T00:00:00Z");
        forwarded.addChild(packet(n, media)); return envelope;
    }
    MessageArchiveService.Query wakeReconnect(boolean anchored) {
        // Controlled authorized-wake/reconnect boundary. No FCM receiver/protocol is modified or
        // faked as cryptographically verified: the test starts after its existing validation.
        fixture.account.setStatus(Account.State.OFFLINE);
        fixture.room.setMode(Conversation.MODE_SINGLE);
        service.durableCheckpoint = new eu.siacs.conversations.xmpp.mam.MamReference(
                System.currentTimeMillis() - service.recoveryAge, anchored ? "durable-checkpoint" : null);
        fixture.account.setXmppConnection(new XmppConnection(fixture.account, service) {
            final Features advertised = new Features(this) {
                @Override public List<String> getAccountFeatures() { return List.of("urn:xmpp:mam:2", "urn:xmpp:sid:0"); }
                @Override public boolean stanzaIds() { return true; }
            };
            @Override public Features getFeatures() { return advertised; }
            @Override public long getLastSessionEstablished() { return System.currentTimeMillis(); }
        });
        fixture.account.setStatus(Account.State.ONLINE);
        service.getMessageArchiveService().onAdvancedStreamFeaturesAvailable(fixture.account);
        var iq = service.mamRequests.get(service.mamRequests.size()-1);
        return service.getMessageArchiveService().findQuery(iq.findChild("query", "urn:xmpp:mam:2").getAttribute("queryid"));
    }
    im.conversations.android.xmpp.model.stanza.Message directPacket(int n, boolean media) {
        var stanza = packet(n, media);
        stanza.setAttribute("type", "chat"); stanza.setFrom(fixture.room.getJid().asBareJid());
        stanza.findChild("stanza-id", "urn:xmpp:sid:0")
                .setAttribute("by", fixture.account.getJid().asBareJid().toString());
        return stanza;
    }
    im.conversations.android.xmpp.model.stanza.Message directArchived(MessageArchiveService.Query query, int n, boolean media) {
        var envelope = archived(query, n, media);
        envelope.setFrom(fixture.account.getJid().asBareJid());
        var forwarded = envelope.findChild("result", query.version.namespace).findChild("forwarded", "urn:xmpp:forward:0");
        var oldMessage = forwarded.findChild("message"); forwarded.getChildren().remove(oldMessage);
        forwarded.addChild(directPacket(n, media));
        return envelope;
    }
    void finishAccountMam(MessageArchiveService.Query query, boolean complete, String first, String last) {
        var iq = new im.conversations.android.xmpp.model.stanza.Iq(im.conversations.android.xmpp.model.stanza.Iq.Type.RESULT);
        var fin = iq.addChild("fin", query.version.namespace).setAttribute("complete", String.valueOf(complete));
        var set = fin.addChild("set", "http://jabber.org/protocol/rsm");
        if (first != null) set.addChild("first").setContent(first);
        if (last != null) set.addChild("last").setContent(last);
        service.mamCallbacks.get(service.mamCallbacks.size()-1).accept(iq);
    }
    List<Message> stack(Conversation c) throws Exception { return stackFor(service.notifications, c); }
    @SuppressWarnings("unchecked") static List<Message> stackFor(NotificationService notifications, Conversation c) throws Exception {
        var method = NotificationService.class.getDeclaredMethod("presentation"); method.setAccessible(true);
        Object state = method.invoke(notifications);
        Field field = state.getClass().getDeclaredField("messages"); field.setAccessible(true);
        return ((Map<String,ArrayList<Message>>)field.get(state))
                .getOrDefault(c.getUuid(), new ArrayList<>());
    }
    void waitingForSm(boolean waiting) throws Exception {
        Field field = XmppConnection.class.getDeclaredField("mWaitingForSmCatchup"); field.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean)field.get(fixture.account.getXmppConnection())).set(waiting);
    }
    void privacyBoundary() throws Exception {
        var method = NotificationService.class.getDeclaredMethod("retireNotificationPresentation");
        method.setAccessible(true); method.invoke(service.notifications);
    }
    @Override public void close() { service.notifications.dispose(); }

    static final class Service extends MucJoinReliabilityTest.TestService {
        @Override public long getLongPreference(String name, int res) {
            if ("notification_throttling_period".equals(name)) return -1L;
            return super.getLongPreference(name, res);
        }
        final List<String> events = new ArrayList<>();
        final Map<String,Message> rows = new LinkedHashMap<>();
        final Output notifications = new Output(this);
        boolean persist = true;
        long recoveryAge = 60_000L;
        int downloads, badges;
        Message last;
        eu.siacs.conversations.xmpp.mam.MamReference durableCheckpoint =
                new eu.siacs.conversations.xmpp.mam.MamReference(0);
        final HttpConnectionManager downloadsManager = new HttpConnectionManager(this) {
            @Override public void updateConversationUi(boolean force) {}
            @Override public long getAutoAcceptFileSize() { return 1000000L; }
            @Override public void createNewDownloadConnection(Message message) {
                events.add("http"); downloads++;
            }
        };
        @Override public boolean persistIncomingMessage(Message m) {
            events.add("persist");
            if (!persist) return false;
            last = m; rows.put(m.getUuid(), m); return true;
        }
        @Override public boolean persistIncomingMamMessage(Message m) { return persistIncomingMessage(m); }
        @Override public boolean isMamMessagePersisted(Message m) { return rows.containsKey(m.getUuid()); }
        @Override public NotificationService getNotificationService() { return notifications; }
        @Override public HttpConnectionManager getHttpConnectionManager() { return downloadsManager; }
        private Conversation conversationFor(Jid jid) {
            return getConversations().stream().filter(c -> c.getJid().asBareJid().equals(jid.asBareJid()))
                    .findFirst().orElse(room);
        }
        @Override public Conversation find(Account a, Jid j, Jid counterpart) { return conversationFor(j); }
        @Override public boolean checkIsArchived(Account a, Jid j, Jid counterpart) { return false; }
        @Override public Conversation findOrCreateConversation(Account a, Jid j, MessageArchiveService.Query q,
                boolean muc, boolean join, boolean async, Jid counterpart) { return conversationFor(j); }
        @Override public boolean confirmMessages() { return false; }
        @Override public boolean sendChatStates() { return false; }
        @Override public boolean isScreenLocked() { return true; }
        @Override public synchronized void updateUnreadCountBadge() { badges++; }
        @Override public Conversation findConversationByUuid(String uuid) {
            return getConversations().stream().filter(c -> c.getUuid().equals(uuid)).findFirst().orElse(null);
        }
    }
    static final class Output extends NotificationService {
        final Service service;
        int renders, alerts;
        long epoch = 1;
        boolean allowed = true;
        boolean posted = true;
        boolean useAndroidPostedState;
        List<String> lastConversations;
        boolean deferPublications;
        boolean inInvalidation;
        Runnable duringMediaBuild;
        int cancellations;
        final java.util.ArrayDeque<Runnable> publicationTasks = new java.util.ArrayDeque<>();
        final java.util.ArrayDeque<Long> retirementTasks = new java.util.ArrayDeque<>();
        void drainPublications() { while (!publicationTasks.isEmpty()) publicationTasks.remove().run(); }
        void drainRetirements() { while (!retirementTasks.isEmpty()) cancelRetiredNotifications(retirementTasks.remove()); }
        @Override void executeMessagePublication(Runnable work) {
            if (deferPublications) publicationTasks.add(work); else work.run();
        }
        @Override void scheduleRetiredNotificationCancellation(long generation) { retirementTasks.add(generation); }
        @Override void cancelMessageInAndroid(String tag) {
            if (inInvalidation) throw new AssertionError("Binder cancellation inside invalidation callback");
            cancellations++; super.cancelMessageInAndroid(tag);
        }
        @Override android.service.notification.StatusBarNotification postedMessage(String conversation) {
            return posted ? super.postedMessage(conversation) : null;
        }
        Output(Service service) { super(service); this.service = service; }
        @Override long notificationCryptoEpoch() { return epoch; }
        @Override boolean notificationAccessAllowed() { return allowed; }
        @Override boolean isMessageNotificationPosted(String conversationUuid) {
            return useAndroidPostedState ? super.isMessageNotificationPosted(conversationUuid) : posted;
        }
        @Override public boolean notificationsFromStrangers() { return true; }
        @Override androidx.core.app.NotificationCompat.Builder buildMediaRefreshContent(
                ArrayList<Message> messages, String channel) {
            renders++; service.events.add("refresh");
            if (duringMediaBuild != null) duringMediaBuild.run();
            return new androidx.core.app.NotificationCompat.Builder(service, channel)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentText(messages.get(messages.size()-1).getBody());
        }
        @Override void renderMessageNotifications(boolean alert, List<String> conversations, boolean summary) {
            renders++; if (alert) alerts++;
            lastConversations = conversations;
            service.events.add(alert ? "alert" : "refresh");
            for (eu.siacs.conversations.entities.Conversation conversation : service.getConversations()) {
                if (conversation == null) continue;
                try { if (stackFor(this, conversation).isEmpty()) continue; }
                catch (Exception e) { throw new AssertionError(e); }
                var notification = new androidx.core.app.NotificationCompat.Builder(service,
                        alert ? "messages" : "silent_messages").setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setGroup("eu.siacs.conversations.messages").setContentText("test message").build();
                notify(conversation.getUuid(), 1024 * 1024 * 2, notification);
            }
        }
    }
}
