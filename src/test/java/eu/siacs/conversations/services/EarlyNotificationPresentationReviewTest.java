package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import android.app.Notification;
import android.app.NotificationManager;
import eu.siacs.conversations.entities.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class EarlyNotificationPresentationReviewTest {
    @Test @Config(sdk = {24, 26, 34})
    public void httpRefreshPreservesChannelIdentityAndGroupWithoutTouchingOtherConversation() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.parser.accept(f.packet(1, true));
            var manager = f.service.getSystemService(NotificationManager.class);
            String target = f.fixture.room.getUuid();
            int id = 1024 * 1024 * 2;
            var other = new androidx.core.app.NotificationCompat.Builder(f.service, "messages")
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setContentText("unchanged")
                    .setGroup("eu.siacs.conversations.messages").build();
            manager.notify("other-conversation", id, other);
            var before = f.service.notifications.postedMessage(target).getNotification();
            f.service.notifications.refreshMediaNotification(f.message());
            var after = f.service.notifications.postedMessage(target).getNotification();
            assertEquals(before.getGroup(), after.getGroup());
            if (android.os.Build.VERSION.SDK_INT >= 26) assertEquals(before.getChannelId(), after.getChannelId());
            assertTrue((after.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
            assertNull(after.sound); assertNull(after.vibrate);
            assertEquals(1, f.service.notifications.alerts);
            var unchanged = java.util.Arrays.stream(manager.getActiveNotifications())
                    .filter(n -> "other-conversation".equals(n.getTag())).findFirst().orElseThrow().getNotification();
            assertEquals("unchanged", unchanged.extras.getCharSequence(Notification.EXTRA_TEXT));
        }
    }
    @Test public void silentOriginalChannelStaysSilentOnHttpRefresh() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.parser.accept(f.packet(1, true));
            var manager = f.service.getSystemService(NotificationManager.class);
            int id = 1024 * 1024 * 2;
            manager.notify(f.fixture.room.getUuid(), id, new androidx.core.app.NotificationCompat.Builder(
                    f.service, "silent_messages").setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setGroup("eu.siacs.conversations.messages").build());
            f.service.notifications.refreshMediaNotification(f.message());
            assertEquals("silent_messages", f.service.notifications.postedMessage(
                    f.fixture.room.getUuid()).getNotification().getChannelId());
        }
    }
    @Test public void notificationChannelDisabledBeforeCompletionIsNotUpdated() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.parser.accept(f.packet(1, true));
            var manager = f.service.getSystemService(NotificationManager.class);
            var channel = manager.getNotificationChannel("messages");
            channel.setImportance(NotificationManager.IMPORTANCE_NONE);
            manager.createNotificationChannel(channel);
            assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel("messages").getImportance());
            int renders = f.service.notifications.renders;
            f.service.notifications.refreshMediaNotification(f.message());
            assertEquals(renders, f.service.notifications.renders);
        }
    }
    @Test public void invalidationDetachesPrivateStateWithoutBinderAndCancelsLater() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.parser.accept(f.packet(1, true));
            f.service.notifications.inInvalidation = true;
            try { f.privacyBoundary(); }
            finally { f.service.notifications.inInvalidation = false; }
            assertTrue(f.stack(f.fixture.room).isEmpty());
            assertEquals(0, f.service.notifications.cancellations);
            f.service.notifications.drainRetirements();
            assertTrue(f.service.notifications.cancellations > 0);
        }
    }
    @Test public void oldRetirementCannotCancelNewNotificationAfterUnlock() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.parser.accept(f.packet(1, true));
            Message old = f.message();
            f.privacyBoundary(); f.service.notifications.epoch++;
            f.parser.accept(f.packet(2, true));
            var manager = f.service.getSystemService(NotificationManager.class);
            f.service.notifications.drainRetirements();
            assertNotNull(f.service.notifications.postedMessage(f.fixture.room.getUuid()));
            int renders = f.service.notifications.renders;
            f.service.notifications.refreshMediaNotification(old);
            assertEquals(renders, f.service.notifications.renders);
            assertEquals(1, f.stack(f.fixture.room).size());
            assertEquals(f.message().getUuid(), f.stack(f.fixture.room).get(0).getUuid());
        }
    }
    @Test public void queuedRenderAndHttpCallbackCannotPublishAfterInvalidation() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.service.notifications.deferPublications = true;
            f.parser.accept(f.packet(1, true));
            f.service.notifications.refreshMediaNotification(f.message());
            f.privacyBoundary(); f.service.notifications.epoch++;
            f.service.notifications.drainPublications();
            assertEquals(0, f.service.notifications.renders);
            assertTrue(f.stack(f.fixture.room).isEmpty());
        }
    }
    @Test public void repeatedLockAndServiceDestroyRevokeQueuedWork() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.service.notifications.deferPublications = true;
            f.parser.accept(f.packet(1, true));
            f.privacyBoundary(); f.privacyBoundary();
            f.service.notifications.dispose();
            f.service.notifications.drainPublications();
            f.service.notifications.drainRetirements();
            assertEquals(0, f.service.notifications.renders);
            assertTrue(f.stack(f.fixture.room).isEmpty());
        }
    }
    @Test public void readWhileHttpRefreshIsQueuedDoesNotRepublish() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.parser.accept(f.packet(1, true));
            int renders = f.service.notifications.renders;
            f.service.notifications.deferPublications = true;
            f.service.notifications.refreshMediaNotification(f.message());
            f.message().markRead();
            f.service.notifications.drainPublications();
            assertEquals(renders, f.service.notifications.renders);
        }
    }
    @Test public void queuedLiveBurstDoesNotSpendOneEventAlertRightTwice() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.service.notifications.deferPublications = true;
            f.parser.accept(f.packet(1, true));
            f.parser.accept(f.packet(2, true));
            assertEquals(0, f.service.notifications.alerts);
            f.service.notifications.drainPublications();
            assertEquals(1, f.service.notifications.alerts);
            assertEquals(2, f.stack(f.fixture.room).size());
        }
    }

    @Test public void invalidationDoesNotWaitForRendererLockOrPerformBinderInCallback() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.parser.accept(f.packet(1, true));
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            f.service.notifications.duringMediaBuild = () -> {
                entered.countDown();
                try { if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("renderer release timeout"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            };
            Thread renderer = new Thread(() -> {
                try { f.service.notifications.refreshMediaNotification(f.message()); }
                catch (Throwable e) { failure.set(e); }
            });
            renderer.start();
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            Thread retire = new Thread(() -> {
                f.service.notifications.inInvalidation = true;
                try { f.privacyBoundary(); }
                catch (Throwable e) { failure.set(e); }
                finally { f.service.notifications.inInvalidation = false; }
            });
            try {
                retire.start(); retire.join(1000);
                assertFalse("invalidation must not wait for renderer monitor/Binder", retire.isAlive());
            } finally { release.countDown(); renderer.join(3000); retire.join(3000); }
            assertFalse(renderer.isAlive());
            assertNull(failure.get());
            assertTrue(f.stack(f.fixture.room).isEmpty());
            f.service.notifications.drainRetirements();
            assertNull(f.service.notifications.postedMessage(f.fixture.room.getUuid()));
        }
    }

    @Test public void delayedReadCancellationDoesNotDeleteNewPostInSameEpoch() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            f.parser.accept(f.packet(1, true));
            f.service.notifications.deferPublications = true;
            f.service.notifications.clearMessages(f.fixture.room);
            f.parser.accept(f.packet(2, true));
            Runnable oldCancel = f.service.notifications.publicationTasks.removeFirst();
            Runnable summary = f.service.notifications.publicationTasks.removeFirst();
            Runnable newest = f.service.notifications.publicationTasks.removeFirst();
            // Model a producer preempted between taking its cancellation lease and enqueueing.
            newest.run(); oldCancel.run(); summary.run();
            assertNotNull(f.service.notifications.postedMessage(f.fixture.room.getUuid()));
            assertEquals(0, f.service.notifications.cancellations);
            assertEquals(1, f.stack(f.fixture.room).size());
            assertEquals(f.message().getUuid(), f.stack(f.fixture.room).get(0).getUuid());
        }
    }

}
