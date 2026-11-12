package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import eu.siacs.conversations.entities.Message;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class EarlyMediaNotificationTest {
    @Test public void parserPersistsAndAlertsBeforeStartingHttp() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.parser.accept(f.packet(1, true));
            assertEquals(List.of("persist", "alert", "http"), f.service.events);
            assertEquals(1, f.stack(f.fixture.room).size());
        }
    }
    @Test public void completionOrErrorOnlyRefreshesExistingStableIdentity() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.parser.accept(f.packet(1, true));
            Message reloaded = new Message(f.fixture.room, "remote media", Message.ENCRYPTION_NONE);
            reloaded.setUuid(f.message().getUuid()); reloaded.setStatus(Message.STATUS_RECEIVED);
            reloaded.markUnread();
            int badges = f.service.badges;
            // Shared destination of both HTTP implementations' success, timeout and manual offers.
            for (int i = 0; i < 3; i++) f.service.notifications.refreshMediaNotification(reloaded);
            assertEquals(1, f.service.notifications.alerts);
            assertEquals(1, f.stack(f.fixture.room).size());
            assertSame(reloaded, f.stack(f.fixture.room).get(0));
            assertEquals(badges, f.service.badges);
        }
    }
    @Test public void readOrDismissedMessagesCannotReturnFromHttp() throws Exception {
        for (boolean read : new boolean[] {false, true}) {
            try (var f = new EarlyNotificationTestSupport()) {
                f.parser.accept(f.packet(1, true));
                if (read) f.message().markRead();
                else f.service.notifications.clearMessages(f.fixture.room);
                int renders = f.service.notifications.renders;
                f.service.notifications.refreshMediaNotification(f.message());
                assertEquals(renders, f.service.notifications.renders);
                assertEquals(1, f.service.notifications.alerts);
            }
        }
    }
    @Test public void rejectedParserPersistenceNeverAlertsOrStartsDownload() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.service.persist = false;
            f.parser.accept(f.packet(1, true));
            assertEquals(List.of("persist"), f.service.events);
            assertTrue(f.stack(f.fixture.room).isEmpty());
            assertEquals(0, f.service.downloads);
        }
    }
    @Test public void lockOrEpochChangeRejectsLatePrivatePresentation() throws Exception {
        for (boolean lock : new boolean[] {false, true}) {
            try (var f = new EarlyNotificationTestSupport()) {
                f.parser.accept(f.packet(1, true));
                int renders = f.service.notifications.renders;
                if (lock) f.service.notifications.allowed = false;
                else f.service.notifications.epoch++;
                f.service.notifications.refreshMediaNotification(f.message());
                assertEquals(renders, f.service.notifications.renders);
                assertEquals(1, f.service.notifications.alerts);
            }
        }
    }
    @Test public void invalidationRetiresStackEvenAfterUnlock() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.parser.accept(f.packet(1, true));
            f.privacyBoundary();
            f.service.notifications.epoch++;
            int renders = f.service.notifications.renders;
            f.service.notifications.refreshMediaNotification(f.message());
            assertEquals(renders, f.service.notifications.renders);
            assertTrue(f.stack(f.fixture.room).isEmpty());
        }
    }
    @Test public void repeatedDeliveryDoesNotAppendOrAlertTwice() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.parser.accept(f.packet(1, true));
            f.parser.accept(f.packet(1, true));
            f.service.notifications.push(f.message());
            assertEquals(1, f.service.notifications.alerts);
            assertEquals(1, f.stack(f.fixture.room).size());
            assertEquals(1, f.service.downloads);
        }
    }
    @Test public void realLegacyHttpCompletionAndCancelNeverSignalAgain() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.parser.accept(f.packet(1, true));
            var type = Class.forName("eu.siacs.conversations.http.HttpDownloadConnection");
            var ctor = type.getDeclaredConstructor(Message.class,
                    eu.siacs.conversations.http.HttpConnectionManager.class);
            ctor.setAccessible(true);
            Object connection = ctor.newInstance(f.message(), f.service.downloadsManager);
            var accepted = type.getDeclaredField("acceptedAutomatically"); accepted.setAccessible(true);
            accepted.setBoolean(connection, true);
            var finish = type.getDeclaredMethod("finish"); finish.setAccessible(true);
            finish.invoke(connection);
            assertEquals(1, f.service.notifications.alerts);
            assertEquals(1, f.stack(f.fixture.room).size());
            type.getMethod("cancel").invoke(connection);
            assertEquals(1, f.service.notifications.alerts);
            assertEquals(1, f.stack(f.fixture.room).size());
        }
    }
    @Test public void realSecureHttpManualOfferAndCancelNeverSignalAgain() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.parser.accept(f.packet(1, true));
            var type = Class.forName("eu.siacs.conversations.http.SecureHttpDownloadConnection");
            var ctor = type.getDeclaredConstructor(Message.class,
                    eu.siacs.conversations.http.HttpConnectionManager.class);
            ctor.setAccessible(true);
            Object connection = ctor.newInstance(f.message(), f.service.downloadsManager);
            var offer = type.getDeclaredMethod("maybeAutoDownloadKnownSize", long.class, boolean.class);
            offer.setAccessible(true); offer.invoke(connection, Long.MAX_VALUE, false);
            assertEquals(1, f.service.notifications.alerts);
            assertEquals(1, f.stack(f.fixture.room).size());
            var cancel = type.getDeclaredMethod("cancel"); cancel.setAccessible(true);
            cancel.invoke(connection);
            assertEquals(1, f.service.notifications.alerts);
        }
    }
    @Test public void nonProtectedPersistenceUsesCheckedInsert() throws Exception {
        var service = new MucJoinReliabilityTest.TestService();
        var fixture = new MucJoinReliabilityTest.Fixture(service);
        final int[] writes = new int[2];
        service.databaseBackend = new eu.siacs.conversations.persistance.DatabaseBackendStub() {
            @Override public void createMessage(Message m) { writes[0]++; }
            @Override public boolean createMessageForMam(Message m) { writes[1]++; return false; }
        };
        Message media = new Message(fixture.room, "https://files.example.test/a.jpg", Message.ENCRYPTION_NONE);
        media.setOob(true);
        assertFalse(service.persistIncomingMessage(media));
        assertArrayEquals(new int[] {0, 1}, writes);
    }

    @Test public void systemDismissalBeforeDeleteIntentDoesNotResurrectStack() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.parser.accept(f.packet(1, true));
            // Keep the app stack intact: Android dismissed the notification before its service
            // delete-intent has run. Exercise the production active-notification lookup.
            f.service.notifications.useAndroidPostedState = true;
            var manager = f.service.getSystemService(android.app.NotificationManager.class);
            manager.createNotificationChannel(new android.app.NotificationChannel(
                    "early-test", "test", android.app.NotificationManager.IMPORTANCE_DEFAULT));
            int id = 1024 * 1024 * 2;
            manager.notify(f.fixture.room.getUuid(), id, new android.app.Notification.Builder(
                    f.service, "early-test").setSmallIcon(android.R.drawable.ic_dialog_info).build());
            assertTrue(f.service.notifications.isMessageNotificationPosted(f.fixture.room.getUuid()));
            manager.cancel(f.fixture.room.getUuid(), id);
            int renders = f.service.notifications.renders;
            f.service.notifications.refreshMediaNotification(f.message());
            assertEquals(renders, f.service.notifications.renders);
            assertEquals(1, f.stack(f.fixture.room).size());
        }
    }
    @Test public void protectedPublicationFailureReturnsFalseAtTheRealRepositoryBoundary() throws Exception {
        var service = new MucJoinReliabilityTest.TestService() {
            @Override boolean isSecureContentAccountAvailableForMutation(Message m) { return true; }
            @Override public eu.siacs.conversations.storage.secure.SecureMessageTextRepository
                    getSecureMessageTextRepository() {
                var coordinator = (eu.siacs.conversations.storage.secure.SecureMessagePayloadCoordinator)
                        java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[] {eu.siacs.conversations.storage.secure.SecureMessagePayloadCoordinator.class},
                                (proxy, method, args) -> { throw new java.io.IOException("injected publication failure"); });
                return new eu.siacs.conversations.storage.secure.SecureMessageTextRepository(
                        coordinator, databaseBackend);
            }
        };
        var fixture = new MucJoinReliabilityTest.Fixture(service);
        Message text = new Message(fixture.room, "protected", Message.ENCRYPTION_NONE);
        assertFalse(service.persistIncomingMessage(text));
    }

}
