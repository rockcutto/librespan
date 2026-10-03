package eu.siacs.conversations.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;

import org.junit.Test;

public class MediaSendBatchTest {

    @Test
    public void supportedBatchesUseFirstMessageAsAnchorForTwoFiveAndTenMembers() {
        verifySupportedBatch(2);
        verifySupportedBatch(5);
        verifySupportedBatch(10);
    }

    @Test
    public void anchorDoesNotReleaseTheBatchBeforeEveryChildIsReady() {
        final List<Message> messages = messages(2);
        final MediaSendBatch batch = new MediaSendBatch("batch", 2, true);
        batch.register(messages.get(0), true);
        batch.register(messages.get(1), true);

        assertTrue(batch.onReady(messages.get(0)).isEmpty());
        assertEquals(Arrays.asList(messages.get(0), messages.get(1)), batch.onReady(messages.get(1)));
        assertEquals(Arrays.asList(messages.get(0).getUuid()), attachTo(messages.get(1)));
        assertTrue(batch.isComplete());
    }

    @Test
    public void readyChildrenWaitForTheAnchorThenReceiveOneRelation() {
        final List<Message> messages = messages(2);
        final MediaSendBatch batch = new MediaSendBatch("batch", 2, true);
        batch.register(messages.get(0), true);
        batch.register(messages.get(1), true);

        assertTrue(batch.onReady(messages.get(1)).isEmpty());
        assertEquals(Arrays.asList(messages.get(0), messages.get(1)), batch.onReady(messages.get(0)));
        assertTrue(attachTo(messages.get(0)).isEmpty());
        assertEquals(Arrays.asList(messages.get(0).getUuid()), attachTo(messages.get(1)));
    }

    @Test
    public void attachmentRelationTargetsWireIdentity() {
        final List<Message> messages = messages(2);
        final Message anchor = messages.get(0);
        final Message child = messages.get(1);
        anchor.setWireUuid("wire-anchor-id");

        final MediaSendBatch batch = new MediaSendBatch("batch", 2, true);
        batch.register(anchor, true);
        batch.register(child, true);

        assertTrue(batch.onReady(child).isEmpty());
        assertEquals(Arrays.asList(anchor, child), batch.onReady(anchor));
        assertEquals(Arrays.asList("wire-anchor-id"), attachTo(child));
    }

    @Test
    public void unsupportedPeerFallsBackToOrdinaryMediaWithoutWaiting() {
        final List<Message> messages = messages(2);
        final MediaSendBatch batch = new MediaSendBatch("batch", 2, false);
        batch.register(messages.get(0), true);

        assertEquals(Arrays.asList(messages.get(0)), batch.onReady(messages.get(0)));
        batch.register(messages.get(1), true);
        assertEquals(Arrays.asList(messages.get(1)), batch.onReady(messages.get(1)));
        assertTrue(attachTo(messages.get(0)).isEmpty());
        assertTrue(attachTo(messages.get(1)).isEmpty());
    }

    @Test
    public void nonMediaAndSingleMediaFallBackToOrdinaryMessages() {
        final List<Message> mixed = messages(2);
        final MediaSendBatch mixedBatch = new MediaSendBatch("mixed", 2, true);
        mixedBatch.register(mixed.get(0), true);
        mixedBatch.register(mixed.get(1), false);

        assertEquals(Arrays.asList(mixed.get(0)), mixedBatch.onReady(mixed.get(0)));
        assertEquals(Arrays.asList(mixed.get(1)), mixedBatch.onReady(mixed.get(1)));
        assertTrue(attachTo(mixed.get(0)).isEmpty());
        assertTrue(attachTo(mixed.get(1)).isEmpty());

        final Message single = messages(1).get(0);
        final MediaSendBatch singleBatch = new MediaSendBatch("single", 1, true);
        singleBatch.register(single, true);
        assertEquals(Arrays.asList(single), singleBatch.onReady(single));
        assertNull(singleBatch.getAnchorMessage());
        assertTrue(attachTo(single).isEmpty());
    }

    @Test
    public void failedAtomicAlbumDoesNotLeakReadyChildren() {
        final List<Message> messages = messages(2);
        final MediaSendBatch batch = new MediaSendBatch("batch", 2, true);
        batch.register(messages.get(0), true);
        batch.register(messages.get(1), true);

        assertTrue(batch.onReady(messages.get(1)).isEmpty());
        assertTrue(batch.onFailed(messages.get(0)).isEmpty());
        assertTrue(attachTo(messages.get(1)).isEmpty());
        assertTrue(batch.isComplete());
    }

    private static void verifySupportedBatch(final int count) {
        final List<Message> messages = messages(count);
        final MediaSendBatch batch = new MediaSendBatch("batch-" + count, count, true);
        for (final Message message : messages) {
            batch.register(message, true);
        }

        assertNull(batch.getAnchorMessage());
        for (int i = messages.size() - 1; i > 0; i--) {
            assertTrue(batch.onReady(messages.get(i)).isEmpty());
        }
        final List<Message> dispatched = batch.onReady(messages.get(0));

        assertEquals(count, dispatched.size());
        assertEquals(messages.get(0), batch.getAnchorMessage());
        assertEquals(messages.get(0), dispatched.get(0));
        assertTrue(attachTo(messages.get(0)).isEmpty());
        for (int i = 1; i < messages.size(); i++) {
            assertEquals(Arrays.asList(messages.get(0).getUuid()), attachTo(messages.get(i)));
        }
        assertTrue(batch.isComplete());
    }

    private static List<Message> messages(final int count) {
        final Conversation conversation =
                new Conversation(
                        "conversation",
                        "test",
                        null,
                        "account",
                        Jid.of("contact@example.test"),
                        0,
                        Conversation.STATUS_AVAILABLE,
                        Conversation.MODE_SINGLE,
                        "",
                        null);
        final List<Message> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(
                    new Message(
                            conversation,
                            "https://upload.example/" + i + ".jpg",
                            Message.ENCRYPTION_NONE,
                            Message.STATUS_UNSEND));
        }
        return messages;
    }

    private static List<String> attachTo(final Message message) {
        final List<String> ids = new ArrayList<>();
        for (final Element payload : message.getPayloads()) {
            if ("attach-to".equals(payload.getName())
                    && Namespace.MESSAGE_ATTACHING.equals(payload.getNamespace())) {
                ids.add(payload.getAttribute("id"));
            }
        }
        return ids;
    }
}
