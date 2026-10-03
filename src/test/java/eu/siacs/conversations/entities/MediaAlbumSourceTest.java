package eu.siacs.conversations.entities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import eu.siacs.conversations.ui.adapter.MediaAlbumSource;
import eu.siacs.conversations.xmpp.Jid;

import org.junit.Test;

public class MediaAlbumSourceTest {

    @Test
    public void emptySourceAlwaysReturnsNoAlbum() {
        final MediaAlbumSource source = MediaAlbumSource.empty();
        assertTrue(source.getAlbum(null).isEmpty());
        assertFalse(source.isContinuation(null));
    }

    @Test
    public void selectedSourceProvidesAlbumAndContinuationOnlyForChildren() {
        final Conversation conversation = conversation("one");
        final Message anchor = message(conversation, "anchor");
        final Message child = message(conversation, "child");
        final List<Message> album = Arrays.asList(anchor, child);

        final MediaAlbumSource source =
                MediaAlbumSource.select(
                        message -> message == child || message == anchor,
                        ignored -> album,
                        ignored -> Collections.emptyList());

        assertSame(album, source.getAlbum(anchor));
        assertSame(album, source.getAlbum(child));
        assertFalse(source.isContinuation(anchor));
        assertTrue(source.isContinuation(child));
    }

    @Test
    public void fallbackSourceIsUsedOutsideSelectedDirection() {
        final Conversation conversation = conversation("two");
        final Message incoming = message(conversation, "incoming");
        final Message outgoing = message(conversation, "outgoing");
        final List<Message> outgoingAlbum = Collections.singletonList(outgoing);

        final MediaAlbumSource source =
                MediaAlbumSource.select(
                        message -> message == incoming,
                        ignored -> Collections.emptyList(),
                        ignored -> outgoingAlbum);

        assertTrue(source.getAlbum(incoming).isEmpty());
        assertEquals(outgoingAlbum, source.getAlbum(outgoing));
    }

    private static Conversation conversation(final String id) {
        return new Conversation(
                id,
                "test",
                null,
                "account",
                Jid.of("contact@example.test"),
                0,
                Conversation.STATUS_AVAILABLE,
                Conversation.MODE_SINGLE,
                "",
                null);
    }

    private static Message message(final Conversation conversation, final String remoteId) {
        final Message message =
                new Message(
                        conversation,
                        "ordinary text",
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_RECEIVED);
        message.setRemoteMsgId(remoteId);
        return message;
    }
}
