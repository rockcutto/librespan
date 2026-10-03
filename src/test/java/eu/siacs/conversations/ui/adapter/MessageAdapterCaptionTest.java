package eu.siacs.conversations.ui.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MediaGalleryPresentation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;

import org.junit.Test;

public class MessageAdapterCaptionTest {

    private static final Jid ALICE = Jid.of("alice@example.test");
    private static final Jid BOB = Jid.of("bob@example.test");

    @Test
    public void captionsRenderOnAnchorWithoutChangingOneTwoThreeOrFiveMediaMembers() {
        assertCaptionedMedia(1);
        assertCaptionedMedia(2);
        assertCaptionedMedia(3);
        assertCaptionedMedia(5);
    }

    @Test
    public void onlyValidatedCaptionChildrenAreHidden() {
        final Conversation validConversation = conversation("caption-valid");
        final Message validAnchor = media(validConversation, "anchor", ALICE, 100);
        final Message validCaption = text(validConversation, "caption", ALICE, 200);
        attach(validCaption, "anchor");
        add(validConversation, validAnchor, validCaption);

        final MediaGalleryPresentation validPresentation =
                MediaGalleryPresentation.forSnapshot(
                        validConversation, 1, Arrays.asList(validAnchor, validCaption));
        assertSame(
                validCaption,
                MessageAdapter.getCaptionForPresentation(validPresentation, validAnchor));
        assertTrue(MessageAdapter.shouldHideMediaCaptionChild(validCaption));

        final Conversation duplicateConversation = conversation("caption-duplicate");
        final Message duplicateAnchor = media(duplicateConversation, "anchor", ALICE, 100);
        final Message first = text(duplicateConversation, "caption-1", ALICE, 200);
        final Message second = text(duplicateConversation, "caption-2", ALICE, 300);
        attach(first, "anchor");
        attach(second, "anchor");
        add(duplicateConversation, duplicateAnchor, first, second);

        final MediaGalleryPresentation duplicatePresentation =
                MediaGalleryPresentation.forSnapshot(
                        duplicateConversation, 1, Arrays.asList(duplicateAnchor, first, second));
        assertNull(MessageAdapter.getCaptionForPresentation(duplicatePresentation, duplicateAnchor));
        assertFalse(MessageAdapter.shouldHideMediaCaptionChild(first));
        assertFalse(MessageAdapter.shouldHideMediaCaptionChild(second));

        final Conversation senderConversation = conversation("caption-sender");
        final Message senderAnchor = media(senderConversation, "anchor", ALICE, 100);
        final Message wrongSender = text(senderConversation, "caption", BOB, 200);
        attach(wrongSender, "anchor");
        add(senderConversation, senderAnchor, wrongSender);
        assertFalse(MessageAdapter.shouldHideMediaCaptionChild(wrongSender));

        final Conversation anchorConversation = conversation("caption-anchor-conversation");
        final Conversation captionConversation = conversation("caption-foreign-conversation");
        final Message foreignAnchor = media(anchorConversation, "anchor", ALICE, 100);
        final Message foreignCaption = text(captionConversation, "caption", ALICE, 200);
        attach(foreignCaption, "anchor");
        add(anchorConversation, foreignAnchor);
        add(captionConversation, foreignCaption);
        final MediaGalleryPresentation foreignPresentation =
                MediaGalleryPresentation.forSnapshot(
                        anchorConversation, 1, Arrays.asList(foreignAnchor));
        assertNull(MessageAdapter.getCaptionForPresentation(foreignPresentation, foreignAnchor));
        assertFalse(MessageAdapter.shouldHideMediaCaptionChild(foreignCaption));

        final Conversation malformedConversation = conversation("caption-malformed");
        final Message malformedAnchor = media(malformedConversation, "anchor", ALICE, 100);
        final Message malformed = text(malformedConversation, "caption", ALICE, 200);
        malformed.addPayload(new Element("attach-to", Namespace.MESSAGE_ATTACHING));
        add(malformedConversation, malformedAnchor, malformed);
        assertFalse(MessageAdapter.shouldHideMediaCaptionChild(malformed));
    }

    @Test
    public void ordinaryMediaAndTextDoNotGainCaptionRendering() {
        final Conversation conversation = conversation("ordinary");
        final Message media = media(conversation, "media", ALICE, 100);
        final Message text = text(conversation, "text", ALICE, 200);
        add(conversation, media, text);

        final MediaGalleryPresentation presentation =
                MediaGalleryPresentation.forSnapshot(conversation, 1, Arrays.asList(media, text));

        assertNull(MessageAdapter.getCaptionForPresentation(presentation, media));
        assertNull(MessageAdapter.getCaptionForPresentation(presentation, text));
        assertFalse(MessageAdapter.shouldHideMediaCaptionChild(text));
    }

    private static void assertCaptionedMedia(final int imageCount) {
        final Conversation conversation = conversation("caption-" + imageCount);
        final Message anchor = media(conversation, "anchor-" + imageCount, ALICE, 100);
        final Message caption = text(conversation, "caption-" + imageCount, ALICE, 1000);
        final List<Message> media = new ArrayList<>();
        media.add(anchor);
        for (int index = 1; index < imageCount; index++) {
            final Message child =
                    media(
                            conversation,
                            "child-" + imageCount + "-" + index,
                            ALICE,
                            100 + index);
            attach(child, anchor.getRemoteMsgId());
            media.add(child);
        }
        attach(caption, anchor.getRemoteMsgId());
        final List<Message> snapshot = new ArrayList<>(media);
        snapshot.add(caption);
        add(conversation, snapshot.toArray(new Message[0]));

        final MediaGalleryPresentation presentation =
                MediaGalleryPresentation.forSnapshot(conversation, 1, snapshot);

        if (imageCount == 1) {
            assertTrue(presentation.getAlbum(anchor).isEmpty());
        } else {
            assertEquals(media, presentation.getAlbum(anchor));
        }
        assertSame(caption, MessageAdapter.getCaptionForPresentation(presentation, anchor));
        assertFalse(presentation.getAlbum(anchor).contains(caption));
        assertTrue(MessageAdapter.shouldHideMediaCaptionChild(caption));
    }

    private static Conversation conversation(final String id) {
        return new TestConversation(id);
    }

    private static Message media(
            final Conversation conversation,
            final String remoteId,
            final Jid counterpart,
            final long timeSent) {
        final Message message =
                new TestMessage(
                        conversation,
                        "https://upload.example/" + remoteId + ".jpg",
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_RECEIVED);
        message.setType(Message.TYPE_IMAGE);
        message.setRemoteMsgId(remoteId);
        message.setCounterpart(counterpart);
        ((TestMessage) message).setTimeSentForTest(timeSent);
        return message;
    }

    private static Message text(
            final Conversation conversation,
            final String remoteId,
            final Jid counterpart,
            final long timeSent) {
        final Message message =
                new TestMessage(
                        conversation,
                        "ordinary text",
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_RECEIVED);
        message.setRemoteMsgId(remoteId);
        message.setCounterpart(counterpart);
        ((TestMessage) message).setTimeSentForTest(timeSent);
        return message;
    }

    private static final class TestConversation extends Conversation {

        private TestConversation(final String id) {
            super(
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

        private void addMessagesForTest(final Message... messages) {
            this.messages.addAll(Arrays.asList(messages));
        }
    }

    private static final class TestMessage extends Message {

        private TestMessage(
                final Conversation conversation,
                final String body,
                final int encryption,
                final int status) {
            super(conversation, body, encryption, status);
        }

        private void setTimeSentForTest(final long timeSent) {
            this.timeSent = timeSent;
        }
    }

    private static void attach(final Message message, final String anchorId) {
        message.addPayload(
                new Element("attach-to", Namespace.MESSAGE_ATTACHING).setAttribute("id", anchorId));
    }

    private static void add(final Conversation conversation, final Message... messages) {
        ((TestConversation) conversation).addMessagesForTest(messages);
    }
}
