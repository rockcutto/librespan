package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import android.app.Notification;
import android.app.NotificationManager;
import eu.siacs.conversations.entities.Message;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Native notifications survive while the process-owned lease map and resident stack are empty.
 * Real cancel/restore/renderer paths; controlled publication lane. No test process is killed. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class EarlyNotificationProcessDeathTest {
    private static final int MESSAGE_ID = 1024 * 1024 * 2;

    @SuppressWarnings("unchecked") private static Map<String, ?> owners() throws Exception {
        Field field = NotificationService.class.getDeclaredField("POSTED_MESSAGE_OWNERS");
        field.setAccessible(true); return (Map<String, ?>)field.get(null);
    }
    private static Notification notification(EarlyNotificationTestSupport f, String text) {
        return new androidx.core.app.NotificationCompat.Builder(f.service, "messages")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentText(text).build();
    }
    private static void nativePost(EarlyNotificationTestSupport f, String tag, int id, String text) {
        f.service.getSystemService(NotificationManager.class).notify(tag, id, notification(f, text));
    }
    private static Notification active(EarlyNotificationTestSupport f, String tag, int id) {
        for (var value : f.service.getSystemService(NotificationManager.class).getActiveNotifications())
            if (value.getId() == id && Objects.equals(value.getTag(), tag)) return value.getNotification();
        return null;
    }
    private static void cancel(NotificationService service, String tag, int id) throws Exception {
        if (tag == null) service.cancel(id);
        else {
            var method = NotificationService.class.getDeclaredMethod("cancel", String.class, int.class);
            method.setAccessible(true); method.invoke(service, tag, id);
        }
    }
    private static void newPost(EarlyNotificationTestSupport f, String tag) {
        if (tag == null) f.service.notifications.notify(MESSAGE_ID, notification(f, "new"));
        else f.service.notifications.notify(tag, MESSAGE_ID, notification(f, "new"));
    }

    @Test @Config(sdk = {24, 26, 34})
    public void ownerlessCancelHandlesTaggedAndUntaggedAndroidNotifications() throws Exception {
        for (String tag : new String[] {null, "restored-child"}) {
            try (var f = new EarlyNotificationTestSupport()) {
                owners().clear();
                nativePost(f, tag, MESSAGE_ID, "old process");
                assertTrue(owners().isEmpty()); assertNotNull(active(f, tag, MESSAGE_ID));
                f.service.notifications.deferPublications = true;
                int badges = f.service.badges;
                cancel(f.service.notifications, tag, MESSAGE_ID);
                assertNotNull("cancellation stays outside caller/crypto monitor", active(f, tag, MESSAGE_ID));
                f.service.notifications.drainPublications();
                assertNull(active(f, tag, MESSAGE_ID));
                assertTrue(owners().isEmpty());
                assertEquals(badges, f.service.badges); assertEquals(0, f.service.notifications.alerts);
            }
        }
    }
    @Test public void clearConversationBeforeUnreadRehydrationStillCancelsOldChild() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            owners().clear(); String tag = f.fixture.room.getUuid();
            nativePost(f, tag, MESSAGE_ID, "old process");
            assertTrue(f.stack(f.fixture.room).isEmpty());
            f.service.notifications.deferPublications = true;
            f.service.notifications.clearMessages(f.fixture.room);
            f.service.notifications.drainPublications();
            assertNull(active(f, tag, MESSAGE_ID));
            assertTrue(f.stack(f.fixture.room).isEmpty()); assertEquals(0, f.service.notifications.renders);
        }
    }
    @Test public void clearAllRealRendererCancelsOwnerlessSummaryWithEmptyStack() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            owners().clear(); nativePost(f, null, MESSAGE_ID, "old summary");
            final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
            var service = new NotificationService(f.service) {
                @Override void executeMessagePublication(Runnable job) { jobs.add(job); }
                @Override void scheduleRetiredNotificationCancellation(long generation) {}
            };
            try {
                service.clearMessages();
                while (!jobs.isEmpty()) jobs.remove().run();
                assertNull(active(f, null, MESSAGE_ID));
            } finally { service.dispose(); }
        }
    }
    @Test public void restoredUnreadRowReadBeforeFinishCancelsUnownedNativeChild() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            owners().clear(); String tag = f.fixture.room.getUuid();
            nativePost(f, tag, MESSAGE_ID, "old process");
            Message row = new Message(f.fixture.room, "unread", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
            row.markUnread();
            // XmppConnectionService.restoreMessages() feeds this exact production entry point.
            f.service.notifications.pushFromBacklog(row);
            assertEquals(1, f.stack(f.fixture.room).size()); assertTrue(owners().isEmpty());
            row.markRead(); f.service.notifications.clearMessages(f.fixture.room);
            assertNull(active(f, tag, MESSAGE_ID)); assertTrue(f.stack(f.fixture.room).isEmpty());
            assertEquals(0, f.service.notifications.alerts);
        }
    }
    @Test public void absentLeaseCancellationCannotDeleteNewPostOfSameIdentity() throws Exception {
        for (String tag : new String[] {null, "restored-child"}) {
            try (var f = new EarlyNotificationTestSupport()) {
                owners().clear(); nativePost(f, tag, MESSAGE_ID, "old");
                f.service.notifications.deferPublications = true;
                cancel(f.service.notifications, tag, MESSAGE_ID);
                newPost(f, tag); // Controlled replacement before the delayed cancellation runs.
                Object replacementOwner = owners().get(tag == null ? "" : tag);
                assertNotNull(replacementOwner);
                f.service.notifications.drainPublications();
                assertEquals("new", active(f, tag, MESSAGE_ID).extras.getCharSequence(Notification.EXTRA_TEXT));
                assertSame(replacementOwner, owners().get(tag == null ? "" : tag));
            }
        }
    }
    @Test public void knownLeaseStillProtectsNewRevision() throws Exception {
        for (String tag : new String[] {null, "known-child"}) {
            try (var f = new EarlyNotificationTestSupport()) {
                owners().clear(); newPost(f, tag);
                f.service.notifications.deferPublications = true;
                cancel(f.service.notifications, tag, MESSAGE_ID);
                newPost(f, tag);
                Object replacementOwner = owners().get(tag == null ? "" : tag);
                f.service.notifications.drainPublications();
                assertNotNull(active(f, tag, MESSAGE_ID));
                assertSame(replacementOwner, owners().get(tag == null ? "" : tag));
            }
        }
    }
    @Test public void absentLeaseCancellationCannotDeleteNewEpochPost() throws Exception {
        for (String tag : new String[] {null, "restored-child"}) {
            try (var f = new EarlyNotificationTestSupport()) {
                owners().clear(); nativePost(f, tag, MESSAGE_ID, "old");
                f.service.notifications.deferPublications = true;
                cancel(f.service.notifications, tag, MESSAGE_ID);
                f.privacyBoundary(); f.service.notifications.epoch++;
                newPost(f, tag);
                f.service.notifications.drainPublications();
                f.service.notifications.drainRetirements();
                assertEquals("new", active(f, tag, MESSAGE_ID).extras.getCharSequence(Notification.EXTRA_TEXT));
            }
        }
    }
    @Test public void retiredGenerationCannotCancelUnownedReplacement() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            owners().clear(); nativePost(f, null, MESSAGE_ID, "old");
            f.service.notifications.deferPublications = true;
            f.service.notifications.cancel(MESSAGE_ID);
            f.privacyBoundary(); f.service.notifications.epoch++;
            // No owner is present: generation, not just owner-null, must reject this stale task.
            nativePost(f, null, MESSAGE_ID, "new unowned");
            f.service.notifications.drainPublications();
            assertEquals("new unowned", active(f, null, MESSAGE_ID).extras.getCharSequence(Notification.EXTRA_TEXT));
        }
    }
    @Test public void oldServiceCancellationCannotDeleteNewServicePost() throws Exception {
        try (var old = new EarlyNotificationTestSupport()) {
            owners().clear(); nativePost(old, "child", MESSAGE_ID, "old");
            old.service.notifications.deferPublications = true;
            cancel(old.service.notifications, "child", MESSAGE_ID);
            old.service.notifications.dispose();
            try (var replacement = new EarlyNotificationTestSupport()) {
                newPost(replacement, "child");
                old.service.notifications.drainPublications();
                old.service.notifications.drainRetirements();
                assertNotNull(active(replacement, "child", MESSAGE_ID));
            }
        }
    }
    @Test public void restoreCompletionMintsLeaseBeforeStaleOwnerlessCancellation() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            owners().clear(); String tag = f.fixture.room.getUuid();
            nativePost(f, tag, MESSAGE_ID, "old process");
            f.service.notifications.deferPublications = true;
            cancel(f.service.notifications, tag, MESSAGE_ID);
            Message row = new Message(f.fixture.room, "restored", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
            row.markUnread(); f.service.notifications.pushFromBacklog(row);
            // Production startup ends with finishBacklog(false, null); no audible restore.
            f.service.notifications.finishBacklog();
            Runnable stale = f.service.notifications.publicationTasks.removeFirst();
            Runnable restoredRender = f.service.notifications.publicationTasks.removeFirst();
            restoredRender.run(); stale.run();
            assertNotNull(owners().get(tag)); assertNotNull(active(f, tag, MESSAGE_ID));
            assertEquals(1, f.stack(f.fixture.room).size()); assertEquals(0, f.service.notifications.alerts);
        }
    }
    @Test public void cancellationKeepsOtherIdsAndOtherTagsUntouched() throws Exception {
        try (var f = new EarlyNotificationTestSupport()) {
            owners().clear(); nativePost(f, "target", MESSAGE_ID, "old target");
            newPost(f, "other"); nativePost(f, "target", 777, "other id");
            nativePost(f, null, 778, "other summary id");
            cancel(f.service.notifications, "target", MESSAGE_ID);
            assertNull(active(f, "target", MESSAGE_ID));
            assertNotNull(active(f, "other", MESSAGE_ID)); assertNotNull(active(f, "target", 777));
            cancel(f.service.notifications, "target", 777);
            assertNull(active(f, "target", 777)); assertNotNull(active(f, "other", MESSAGE_ID));
            f.service.notifications.cancel(778); assertNull(active(f, null, 778));
        }
    }
}
