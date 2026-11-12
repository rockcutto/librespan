package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import android.app.Notification;
import android.app.NotificationManager;
import eu.siacs.conversations.entities.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Actual parser + automatic account reconnect/MAM owner + terminal fin + notification stack. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class EarlyMamOnlyNotificationTest {
    @Test public void authorizedWakeReconnectWithoutSmMamOnlyNotifiesOnce() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            var query = f.wakeReconnect(true);
            assertFalse(f.fixture.account.getXmppConnection().isWaitingForSmCatchup());
            f.parser.accept(f.directArchived(query, 1, true));
            assertEquals(0, f.service.notifications.alerts);
            assertEquals(1, f.service.rows.size());
            f.finishAccountMam(query, true, "archive-1", "archive-1");
            assertEquals(1, f.service.notifications.alerts);
            assertEquals(1, f.stack(f.fixture.room).size());
            f.service.notifications.refreshMediaNotification(f.message());
            f.service.notifications.finishBacklog(true, f.fixture.account);
            assertEquals(1, f.service.notifications.alerts);
        }
    }
    @Test public void timeOnlyCheckpointStillAdmitsUnreadRowsSilently() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            var query = f.wakeReconnect(false);
            f.parser.accept(f.directArchived(query, 1, true));
            f.finishAccountMam(query, true, "archive-1", "archive-1");
            assertEquals(0, f.service.notifications.alerts);
            assertEquals(1, f.stack(f.fixture.room).size());
        }
    }
    @Test public void liveAndMamReplayShareDurableIdentityAndOneAlert() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            var query = f.wakeReconnect(true);
            f.parser.accept(f.directPacket(1, true));
            f.parser.accept(f.directArchived(query, 1, true));
            f.finishAccountMam(query, true, "archive-1", "archive-1");
            assertEquals(1, f.service.notifications.alerts);
            assertEquals(1, f.stack(f.fixture.room).size());
            assertEquals(1, f.service.rows.size());
        }
    }
    @Test public void repeatMamQueryDoesNotAlertAlreadyStoredRows() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            var query = f.wakeReconnect(true);
            f.parser.accept(f.directArchived(query, 1, true));
            f.finishAccountMam(query, true, "archive-1", "archive-1");
            var repeat = f.wakeReconnect(true);
            f.parser.accept(f.directArchived(repeat, 1, true));
            f.finishAccountMam(repeat, true, "archive-1", "archive-1");
            assertEquals(1, f.service.notifications.alerts);
            assertEquals(1, f.stack(f.fixture.room).size());
        }
    }
    @Test public void failedPersistenceBarrierDoesNotReleasePreviouslyStagedRow() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            var query = f.wakeReconnect(true);
            f.parser.accept(f.directArchived(query, 1, true));
            f.service.persist = false;
            f.parser.accept(f.directArchived(query, 2, true));
            f.finishAccountMam(query, true, "archive-1", "archive-2");
            assertEquals(0, f.service.notifications.alerts);
            assertTrue(f.stack(f.fixture.room).isEmpty());
        }
    }
    @Test public void multipageCatchupReleasesOneBatchOnlyAfterTerminalFin() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            var query = f.wakeReconnect(true);
            f.parser.accept(f.directArchived(query, 1, true));
            f.finishAccountMam(query, false, "archive-1", "archive-1");
            var iq = f.service.mamRequests.get(f.service.mamRequests.size()-1);
            var next = f.service.getMessageArchiveService().findQuery(iq.findChild("query", query.version.namespace).getAttribute("queryid"));
            assertNotNull(next);
            assertEquals(0, f.service.notifications.alerts);
            f.parser.accept(f.directArchived(next, 2, true));
            f.finishAccountMam(next, true, "archive-2", "archive-2");
            assertEquals(1, f.service.notifications.alerts);
            assertEquals(2, f.stack(f.fixture.room).size());
        }
    }
    @Test public void readBeforeFinAndPrivacyTransitionRejectPendingMamPublication() throws Exception {
        for (boolean privacy : new boolean[] {false, true}) {
            try (var f = new EarlyNotificationTestSupport()) {
                var query = f.wakeReconnect(true);
                f.parser.accept(f.directArchived(query, 1, true));
                if (privacy) { f.privacyBoundary(); f.service.notifications.epoch++; }
                else f.message().markRead();
                f.finishAccountMam(query, true, "archive-1", "archive-1");
                assertEquals(0, f.service.notifications.alerts);
                assertTrue(f.stack(f.fixture.room).isEmpty());
            }
        }
    }
    @Test public void cursorFallbackLosesSoundAuthorityButKeepsUnreadStack() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            var query = f.wakeReconnect(true);
            f.service.mamCallbacks.get(f.service.mamCallbacks.size()-1).accept(MamTestHarness.itemNotFound());
            var iq = f.service.mamRequests.get(f.service.mamRequests.size()-1);
            var fallback = f.service.getMessageArchiveService().findQuery(iq.findChild("query", query.version.namespace).getAttribute("queryid"));
            assertNotNull(fallback);
            assertNull(fallback.getReference());
            f.parser.accept(f.directArchived(fallback, 1, true));
            f.finishAccountMam(fallback, true, "archive-1", "archive-1");
            assertEquals(0, f.service.notifications.alerts);
            assertEquals(1, f.stack(f.fixture.room).size());
        }
    }

    @Test public void multiConversationMamBatchGroupsAndThrottlesLaterEvents() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            var query = f.wakeReconnect(true);
            var second = new Conversation("second", f.fixture.account,
                    eu.siacs.conversations.xmpp.Jid.of("second@example.test"), Conversation.MODE_SINGLE, null);
            f.fixture.room.setNotificationThrottlingPeriod(60_000);
            second.setNotificationThrottlingPeriod(60_000);
            f.service.extraRooms.add(second);
            f.parser.accept(f.directArchived(query, 1, true));
            var other = f.directArchived(query, 2, true);
            other.findChild("result", query.version.namespace).findChild("forwarded", "urn:xmpp:forward:0")
                    .findChild("message").setAttribute("from", second.getJid().toString());
            f.parser.accept(other);
            f.finishAccountMam(query, true, "archive-1", "archive-2");
            assertEquals(1, f.service.notifications.alerts);
            assertEquals(2, f.service.notifications.lastConversations.size());
            assertEquals(1, f.stack(f.fixture.room).size()); assertEquals(1, f.stack(second).size());
            assertEquals(f.service.notifications.postedMessage(f.fixture.room.getUuid()).getNotification().getGroup(),
                    f.service.notifications.postedMessage(second.getUuid()).getNotification().getGroup());
            var next = f.wakeReconnect(true);
            f.parser.accept(f.directArchived(next, 3, true));
            f.finishAccountMam(next, true, "archive-3", "archive-3");
            assertEquals(1, f.service.notifications.alerts);
            assertEquals(2, f.stack(f.fixture.room).size());
        }
    }

    @Test public void clippedLongHistoryRecoveryDoesNotCreateNotifications() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.service.recoveryAge = 365L * 24 * 60 * 60 * 1000;
            var query = f.wakeReconnect(true);
            assertNull(query.getReference());
            f.parser.accept(f.directArchived(query, 1, true));
            f.finishAccountMam(query, true, "archive-1", "archive-1");
            assertEquals(0, f.service.notifications.alerts);
            assertTrue(f.stack(f.fixture.room).isEmpty());
        }
    }

}
