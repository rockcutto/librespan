package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import eu.siacs.conversations.entities.*;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class EarlyCatchupNotificationTest {
    @Test public void liveDuringMamDoesNotWaitForAnArchivePage() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            var query = f.archive(MessageArchiveService.MucHistoryPurpose.INITIAL_RECENT_BOOTSTRAP);
            f.parser.accept(f.packet(1, false));
            assertNotNull(f.service.getMessageArchiveService().findQuery(query.getQueryId()));
            assertEquals(1, f.service.notifications.alerts);
            f.service.notifications.finishArchiveBacklog(f.fixture.account);
            assertEquals(1, f.service.notifications.alerts);
        }
    }
    @Test public void liveMediaDuringSmAlertsBeforeHttpAndNeverAtSmFinishAgain() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.waitingForSm(true);
            f.parser.accept(f.packet(1, true));
            assertEquals(List.of("persist", "alert", "http"), f.service.events);
            f.waitingForSm(false);
            f.service.notifications.finishBacklog(true, f.fixture.account);
            f.service.notifications.refreshMediaNotification(f.message());
            assertEquals(1, f.service.notifications.alerts);
            assertEquals(1, f.stack(f.fixture.room).size());
        }
    }
    @Test public void everyMucArchivePurposeAndHttpCompletionRemainSilent() throws Exception {
        for (var purpose : MessageArchiveService.MucHistoryPurpose.values()) {
            try (var f = new EarlyNotificationTestSupport()) {
                var query = f.archive(purpose);
                for (int i = 0; i < 20; i++) f.parser.accept(f.archived(query, i, true));
                f.service.notifications.finishArchiveBacklog(f.fixture.account);
                if (f.message() != null) f.service.notifications.refreshMediaNotification(f.message());
                assertEquals(0, f.service.notifications.alerts);
                assertEquals(0, f.service.notifications.renders);
                assertTrue(f.stack(f.fixture.room).isEmpty());
                assertEquals(20, f.service.rows.size());
            }
        }
    }
    @Test public void mamReplayAfterLiveAndAfterHttpDoesNotAppendOrSignal() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.parser.accept(f.packet(1, true));
            var query = f.archive(MessageArchiveService.MucHistoryPurpose.MISSING_RANGE_RECOVERY);
            f.parser.accept(f.archived(query, 1, true));
            f.service.notifications.finishArchiveBacklog(f.fixture.account);
            f.service.notifications.refreshMediaNotification(f.message());
            f.parser.accept(f.archived(query, 1, true));
            assertEquals(1, f.service.notifications.alerts);
            assertEquals(1, f.stack(f.fixture.room).size());
            assertEquals(1, f.service.rows.size());
        }
    }
    @Test public void durableReplayWithResidentMissNeverPublishesAgain() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.waitingForSm(true);
            Message persisted = new Message(f.fixture.room, "old", Message.ENCRYPTION_NONE);
            persisted.setServerMsgId("archive-1"); persisted.setRemoteMsgId("event-1");
            persisted.setCounterpart(eu.siacs.conversations.xmpp.Jid.of("room@conference.example/peer"));
            f.service.rows.put(persisted.getUuid(), persisted);
            f.parser.accept(f.packet(1, true));
            assertEquals(0, f.service.notifications.alerts);
            assertTrue(f.stack(f.fixture.room).isEmpty());
            assertEquals(0, f.service.downloads);
        }
    }
    @Test public void delayedMucHistoryAndMutedOrUnhighlightedMucNeverAlert() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            try (var f = new EarlyNotificationTestSupport()) {
                var packet = f.packet(1, true);
                if (kind == 0) packet.addChild("delay", "urn:xmpp:delay")
                        .setAttribute("stamp", "2001-01-01T00:00:00Z");
                if (kind == 1) f.fixture.room.setMutedTill(Long.MAX_VALUE);
                if (kind == 2) f.fixture.room.setAttribute(Conversation.ATTRIBUTE_ALWAYS_NOTIFY, false);
                f.parser.accept(packet);
                f.service.notifications.refreshMediaNotification(f.message());
                assertEquals(0, f.service.notifications.alerts);
                assertTrue(f.stack(f.fixture.room).isEmpty());
            }
        }
    }
    @Test public void earlyEventsDoNotSuppressOtherBacklogEventsOrConversationGrouping() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.waitingForSm(true);
            f.parser.accept(f.packet(1, true));
            Conversation second = new Conversation("second", f.fixture.account,
                    eu.siacs.conversations.xmpp.Jid.of("second@conference.example"), Conversation.MODE_MULTI, null);
            second.setAttribute(Conversation.ATTRIBUTE_ALWAYS_NOTIFY, true);
            f.service.extraRooms.add(second);
            Message pending = new Message(second, "pending", Message.ENCRYPTION_NONE);
            pending.setStatus(Message.STATUS_RECEIVED); pending.markUnread();
            // An ambiguous SM event keeps the existing delayed policy, independently of early events.
            f.service.notifications.pushPersistedIncoming(pending, false);
            f.service.notifications.finishArchiveBacklog(f.fixture.account);
            assertEquals(1, f.service.notifications.alerts);
            f.waitingForSm(false);
            f.service.notifications.finishBacklog(true, f.fixture.account);
            assertEquals(2, f.service.notifications.alerts);
            assertEquals(List.of(second.getUuid()), f.service.notifications.lastConversations);
            assertEquals(1, f.stack(f.fixture.room).size());
            assertEquals(1, f.stack(second).size());
            f.service.notifications.finishBacklog(true, f.fixture.account);
            assertEquals(2, f.service.notifications.alerts);
        }
    }
    @Test public void persistenceFailureDuringSmNeverEntersNotificationStack() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.waitingForSm(true); f.service.persist = false;
            f.parser.accept(f.packet(1, true));
            f.service.notifications.finishBacklog(true, f.fixture.account);
            assertEquals(0, f.service.notifications.alerts);
            assertTrue(f.stack(f.fixture.room).isEmpty());
            assertEquals(0, f.service.downloads);
        }
    }
    @Test public void readAndPrivacyChangesDuringSmPreventHttpResurrection() throws Exception {
        for (boolean privacy : new boolean[] {false, true}) {
            try (var f = new EarlyNotificationTestSupport()) {
                f.waitingForSm(true); f.parser.accept(f.packet(1, true));
                if (privacy) { f.privacyBoundary(); f.service.notifications.epoch++; }
                else { f.message().markRead(); f.service.notifications.clearMessages(f.fixture.room); }
                f.waitingForSm(false);
                f.service.notifications.finishBacklog(true, f.fixture.account);
                f.service.notifications.refreshMediaNotification(f.message());
                assertEquals(1, f.service.notifications.alerts);
                assertTrue(f.stack(f.fixture.room).isEmpty());
            }
        }
    }
    @Test public void archiveCompletionCannotAlertRestoredUnreadMessages() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            Message old = new Message(f.fixture.room, "old", Message.ENCRYPTION_NONE);
            old.setStatus(Message.STATUS_RECEIVED); old.markUnread();
            f.service.notifications.pushFromBacklog(old);
            f.service.notifications.finishBacklog();
            f.service.notifications.finishArchiveBacklog(f.fixture.account);
            f.service.notifications.finishBacklog(true, f.fixture.account);
            assertEquals(0, f.service.notifications.alerts);
        }
    }
    @Test public void realMamResultAndFinCallbacksDoNotPublishHistoricalMessages() throws Exception {
        var h = new MamTestHarness();
        h.join();
        final int[] rendersAndAlerts = new int[2];
        var notifications = new NotificationService(h.service) {
            @Override void updateErrorNotification() {}
            @Override void executeMessagePublication(Runnable work) { work.run(); }
            @Override void scheduleRetiredNotificationCancellation(long generation) {}
            @Override long notificationCryptoEpoch() { return 1; }
            @Override boolean notificationAccessAllowed() { return true; }
            @Override void renderMessageNotifications(boolean alert, java.util.List<String> conversations, boolean summary) {
                rendersAndAlerts[0]++;
                if (alert) rendersAndAlerts[1]++;
            }
        };
        try {
            h.service.notificationOverride = notifications;
            h.result(0, 1);
            assertEquals(1, h.service.writes);
            h.finish(0, true, "archive-1", "archive-1");
            assertArrayEquals(new int[] {0, 0}, rendersAndAlerts);
        } finally { notifications.dispose(); }
    }

}
