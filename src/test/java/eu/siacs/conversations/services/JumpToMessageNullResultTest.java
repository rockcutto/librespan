package eu.siacs.conversations.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.DatabaseBackendStub;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class JumpToMessageNullResultTest {

    @Test
    public void missingAnchorReturnsNotFoundWithoutCrashing() throws InterruptedException {
        assertNotFound(null);
    }

    @Test
    public void emptyPageReturnsNotFoundWithoutCrashing() throws InterruptedException {
        assertNotFound(new ArrayList<>());
    }

    private static void assertNotFound(final ArrayList<Message> result)
            throws InterruptedException {
        final XmppConnectionService service = new XmppConnectionService();

        service.databaseBackend =
                new DatabaseBackendStub() {
                    @Override
                    public ArrayList<Message> getMessagesNearUuid(
                            final Conversation conversation, final int limit, final String uuid) {
                        return result;
                    }
                };

        final CountDownLatch callback = new CountDownLatch(1);
        final AtomicInteger found = new AtomicInteger();
        final AtomicInteger notFound = new AtomicInteger();

        service.jumpToMessage(
                null,
                "deleted-message-id",
                new XmppConnectionService.JumpToMessageListener() {
                    @Override
                    public void onSuccess() {
                        found.incrementAndGet();
                        callback.countDown();
                    }

                    @Override
                    public void onNotFound() {
                        notFound.incrementAndGet();
                        callback.countDown();
                    }
                });

        assertTrue("jumpToMessage did not finish", callback.await(5, TimeUnit.SECONDS));
        assertEquals(0, found.get());
        assertEquals(1, notFound.get());
    }
}
