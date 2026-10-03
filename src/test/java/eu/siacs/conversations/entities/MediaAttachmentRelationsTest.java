package eu.siacs.conversations.entities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;

import org.junit.Test;

public class MediaAttachmentRelationsTest {

    private static final Jid ALICE = Jid.of("alice@example.test");
    private static final Jid BOB = Jid.of("bob@example.test");

    @Test
    public void validChildResolvesAnchorAndRelatedMedia() {
        final Conversation conversation = conversation("one", Conversation.MODE_SINGLE);
        final Message anchor = media(conversation, "anchor", ALICE, 100);
        final Message child = media(conversation, "child", ALICE, 200);
        attach(child, "anchor");
        add(conversation, anchor, child);

        assertTrue(MediaAttachmentRelations.isAttachmentChild(child));
        assertSame(anchor, MediaAttachmentRelations.resolveAnchor(child));
        assertEquals(Arrays.asList(anchor, child), MediaAttachmentRelations.getRelatedMedia(child));
    }

    @Test
    public void multipleChildrenUseSameAnchorAndDeterministicOrder() {
        final Conversation conversation = conversation("two", Conversation.MODE_SINGLE);
        final Message anchor = media(conversation, "anchor", ALICE, 100);
        final Message laterChild = media(conversation, "later", ALICE, 300);
        final Message earlierChild = media(conversation, "earlier", ALICE, 200);
        attach(laterChild, "anchor");
        attach(earlierChild, "anchor");
        add(conversation, anchor, laterChild, earlierChild);

        assertSame(anchor, MediaAttachmentRelations.resolveAnchor(laterChild));
        assertSame(anchor, MediaAttachmentRelations.resolveAnchor(earlierChild));
        assertEquals(
                Arrays.asList(anchor, earlierChild, laterChild),
                MediaAttachmentRelations.getRelatedMedia(anchor));
    }

    @Test
    public void invalidRelationsReturnSafeEmptyResults() {
        final Conversation conversation = conversation("three", Conversation.MODE_SINGLE);
        final Message anchor = media(conversation, "anchor", ALICE, 100);
        final Message missingAnchor = media(conversation, "missing", ALICE, 200);
        final Message wrongSender = media(conversation, "wrong-sender", BOB, 300);
        final Message duplicate = media(conversation, "duplicate", ALICE, 400);
        attach(missingAnchor, "does-not-exist");
        attach(wrongSender, "anchor");
        attach(duplicate, "anchor");
        attach(duplicate, "anchor");
        add(conversation, anchor, missingAnchor, wrongSender, duplicate);

        assertSafeEmpty(missingAnchor);
        assertSafeEmpty(wrongSender);
        assertSafeEmpty(duplicate);
    }

    @Test
    public void relationCannotAttachToAnotherChild() {
        final Conversation conversation = conversation("four", Conversation.MODE_SINGLE);
        final Message anchor = media(conversation, "anchor", ALICE, 100);
        final Message child = media(conversation, "child", ALICE, 200);
        final Message grandchild = media(conversation, "grandchild", ALICE, 300);
        attach(child, "anchor");
        attach(grandchild, "child");
        add(conversation, anchor, child, grandchild);

        assertSame(anchor, MediaAttachmentRelations.resolveAnchor(child));
        assertSafeEmpty(grandchild);
    }

    @Test
    public void wrongConversationMucAndTextNeverResolve() {
        final Conversation first = conversation("five-a", Conversation.MODE_SINGLE);
        final Conversation second = conversation("five-b", Conversation.MODE_SINGLE);
        final Message foreignAnchor = media(second, "anchor", ALICE, 100);
        final Message child = media(first, "child", ALICE, 200);
        attach(child, "anchor");
        add(first, child);
        add(second, foreignAnchor);

        final Conversation muc = conversation("five-muc", Conversation.MODE_MULTI);
        final Message mucAnchor = media(muc, "muc-anchor", ALICE, 100);
        final Message mucChild = media(muc, "muc-child", ALICE, 200);
        attach(mucChild, "muc-anchor");
        add(muc, mucAnchor, mucChild);

        final Message text = text(first, "text", ALICE, 300);
        attach(text, "anchor");
        add(first, text);

        assertSafeEmpty(child);
        assertSafeEmpty(mucChild);
        assertSafeEmpty(text);
    }

    @Test
    public void malformedAndUnknownPayloadsNeverThrow() {
        final Conversation conversation = conversation("six", Conversation.MODE_SINGLE);
        final Message noId = media(conversation, "no-id", ALICE, 100);
        final Message nested = media(conversation, "nested", ALICE, 200);
        final Message textContent = media(conversation, "text-content", ALICE, 300);
        final Message unknown = media(conversation, "unknown", ALICE, 400);
        noId.addPayload(new Element("attach-to", Namespace.MESSAGE_ATTACHING));
        nested.addPayload(
                new Element("attach-to", Namespace.MESSAGE_ATTACHING)
                        .setAttribute("id", "anchor")
                        .addChild("unexpected"));
        textContent.addPayload(
                new Element("attach-to", Namespace.MESSAGE_ATTACHING)
                        .setAttribute("id", "anchor")
                        .setContent("unexpected"));
        attach(unknown, "unknown-anchor");
        add(conversation, noId, nested, textContent, unknown);

        assertSafeEmpty(noId);
        assertSafeEmpty(nested);
        assertSafeEmpty(textContent);
        assertSafeEmpty(unknown);
    }

    @Test
    public void noCaptionReturnsNull() {
        final Conversation conversation = conversation("caption-none", Conversation.MODE_SINGLE);
        final Message anchor = media(conversation, "anchor", ALICE, 100);
        add(conversation, anchor);

        assertNull(MediaAttachmentRelations.getCaption(anchor));
        assertFalse(MediaAttachmentRelations.isCaptionChild(anchor));
    }

    @Test
    public void validCaptionResolvesOnlyForMediaAnchor() {
        final Conversation conversation = conversation("caption-valid", Conversation.MODE_SINGLE);
        final Message anchor = media(conversation, "anchor", ALICE, 100);
        final Message caption = text(conversation, "caption", ALICE, 200);
        attach(caption, "anchor");
        add(conversation, anchor, caption);

        assertSame(caption, MediaAttachmentRelations.getCaption(anchor));
        assertTrue(MediaAttachmentRelations.isCaptionChild(caption));
    }

    @Test
    public void captionsRejectDuplicatesAndMismatchedPeers() {
        final Conversation conversation = conversation("caption-invalid", Conversation.MODE_SINGLE);
        final Message anchor = media(conversation, "anchor", ALICE, 100);
        final Message first = text(conversation, "first", ALICE, 200);
        final Message second = text(conversation, "second", ALICE, 300);
        attach(first, "anchor");
        attach(second, "anchor");
        add(conversation, anchor, first, second);

        assertNull(MediaAttachmentRelations.getCaption(anchor));
        assertFalse(MediaAttachmentRelations.isCaptionChild(first));
        assertFalse(MediaAttachmentRelations.isCaptionChild(second));

        final Conversation wrongPeerConversation = conversation("caption-wrong-peer", Conversation.MODE_SINGLE);
        final Message peerAnchor = media(wrongPeerConversation, "peer-anchor", ALICE, 100);
        final Message wrongSender = text(wrongPeerConversation, "wrong-sender", BOB, 200);
        attach(wrongSender, "peer-anchor");
        add(wrongPeerConversation, peerAnchor, wrongSender);

        assertNoCaption(peerAnchor, wrongSender);
    }

    @Test
    public void captionsRejectWrongConversationAndDirection() {
        final Conversation first = conversation("caption-first", Conversation.MODE_SINGLE);
        final Conversation second = conversation("caption-second", Conversation.MODE_SINGLE);
        final Message foreignAnchor = media(first, "anchor", ALICE, 100);
        final Message foreignCaption = text(second, "caption", ALICE, 200);
        attach(foreignCaption, "anchor");
        add(first, foreignAnchor);
        add(second, foreignCaption);

        assertNull(MediaAttachmentRelations.getCaption(foreignAnchor));
        assertFalse(MediaAttachmentRelations.isCaptionChild(foreignCaption));

        final Conversation directionConversation = conversation("caption-direction", Conversation.MODE_SINGLE);
        final Message incomingAnchor = media(directionConversation, "direction-anchor", ALICE, 100);
        final Message outgoingCaption =
                text(
                        directionConversation,
                        "direction-caption",
                        ALICE,
                        200,
                        "ordinary text",
                        Message.STATUS_SEND);
        attach(outgoingCaption, "direction-anchor");
        add(directionConversation, incomingAnchor, outgoingCaption);

        assertNoCaption(incomingAnchor, outgoingCaption);
    }

    @Test
    public void captionsRejectMediaChildNonMediaAndSpecialText() {
        final Conversation conversation = conversation("caption-shapes", Conversation.MODE_SINGLE);
        final Message anchor = media(conversation, "anchor", ALICE, 100);
        final Message child = media(conversation, "child", ALICE, 200);
        final Message captionOnChild = text(conversation, "caption-child", ALICE, 300);
        attach(child, "anchor");
        attach(captionOnChild, "child");
        add(conversation, anchor, child, captionOnChild);

        assertNoCaption(anchor, captionOnChild);
        assertNull(MediaAttachmentRelations.getCaption(child));

        final Conversation nonMediaConversation = conversation("caption-non-media", Conversation.MODE_SINGLE);
        final Message textAnchor = text(nonMediaConversation, "text-anchor", ALICE, 100);
        final Message textCaption = text(nonMediaConversation, "text-caption", ALICE, 200);
        attach(textCaption, "text-anchor");
        add(nonMediaConversation, textAnchor, textCaption);
        assertNoCaption(textAnchor, textCaption);

        final Conversation specialConversation = conversation("caption-special", Conversation.MODE_SINGLE);
        final Message specialAnchor = media(specialConversation, "special-anchor", ALICE, 100);
        final Message empty = text(specialConversation, "empty", ALICE, 200, "  ", Message.STATUS_RECEIVED);
        final Message oob = text(specialConversation, "oob", ALICE, 300);
        final Message downloadable =
                text(
                        specialConversation,
                        "downloadable",
                        ALICE,
                        400,
                        "https://upload.example/downloadable.jpg",
                        Message.STATUS_RECEIVED);
        oob.setOob(true);
        downloadable.setOob(true);
        attach(empty, "special-anchor");
        attach(oob, "special-anchor");
        attach(downloadable, "special-anchor");
        add(specialConversation, specialAnchor, empty, oob, downloadable);
        assertNull(MediaAttachmentRelations.getCaption(specialAnchor));
        assertFalse(MediaAttachmentRelations.isCaptionChild(empty));
        assertFalse(MediaAttachmentRelations.isCaptionChild(oob));
        assertFalse(MediaAttachmentRelations.isCaptionChild(downloadable));
    }

    @Test
    public void captionsRejectMalformedPayloadsWithoutThrowing() {
        final Conversation conversation = conversation("caption-malformed", Conversation.MODE_SINGLE);
        final Message anchor = media(conversation, "anchor", ALICE, 100);
        final Message missingId = text(conversation, "missing", ALICE, 200);
        final Message duplicate = text(conversation, "duplicate", ALICE, 300);
        final Message content = text(conversation, "content", ALICE, 400);
        final Message nested = text(conversation, "nested", ALICE, 500);
        missingId.addPayload(new Element("attach-to", Namespace.MESSAGE_ATTACHING));
        attach(duplicate, "anchor");
        attach(duplicate, "anchor");
        content.addPayload(
                new Element("attach-to", Namespace.MESSAGE_ATTACHING)
                        .setAttribute("id", "anchor")
                        .setContent("unexpected"));
        nested.addPayload(
                new Element("attach-to", Namespace.MESSAGE_ATTACHING)
                        .setAttribute("id", "anchor")
                        .addChild("unexpected"));
        add(conversation, anchor, missingId, duplicate, content, nested);

        try {
            assertNull(MediaAttachmentRelations.getCaption(anchor));
            assertFalse(MediaAttachmentRelations.isCaptionChild(missingId));
            assertFalse(MediaAttachmentRelations.isCaptionChild(duplicate));
            assertFalse(MediaAttachmentRelations.isCaptionChild(content));
            assertFalse(MediaAttachmentRelations.isCaptionChild(nested));
        } catch (final Throwable throwable) {
            fail("Malformed caption payload must not throw: " + throwable);
        }
    }

    @Test
    public void delayedCaptionResolvesAfterAnchorArrives() {
        final Conversation conversation = conversation("caption-delayed", Conversation.MODE_SINGLE);
        final Message caption = text(conversation, "caption", ALICE, 100);
        attach(caption, "anchor");
        add(conversation, caption);

        assertFalse(MediaAttachmentRelations.isCaptionChild(caption));

        final Message anchor = media(conversation, "anchor", ALICE, 200);
        add(conversation, anchor);

        assertSame(caption, MediaAttachmentRelations.getCaption(anchor));
        assertTrue(MediaAttachmentRelations.isCaptionChild(caption));
    }

    @Test
    public void captionResolutionIsStableAfterHistoryReload() {
        final Conversation first = conversation("caption-history-1", Conversation.MODE_SINGLE);
        final Message firstAnchor = media(first, "anchor", ALICE, 100);
        final Message firstCaption = text(first, "caption", ALICE, 200);
        attach(firstCaption, "anchor");
        add(first, firstAnchor, firstCaption);

        final Conversation reloaded = conversation("caption-history-2", Conversation.MODE_SINGLE);
        final Message reloadedAnchor = media(reloaded, "anchor", ALICE, 100);
        final Message reloadedCaption = text(reloaded, "caption", ALICE, 200);
        attach(reloadedCaption, "anchor");
        add(reloaded, reloadedCaption, reloadedAnchor);

        assertSame(firstCaption, MediaAttachmentRelations.getCaption(firstAnchor));
        assertSame(reloadedCaption, MediaAttachmentRelations.getCaption(reloadedAnchor));
        assertTrue(MediaAttachmentRelations.isCaptionChild(firstCaption));
        assertTrue(MediaAttachmentRelations.isCaptionChild(reloadedCaption));
    }

    private static void assertSafeEmpty(final Message message) {
        try {
            assertFalse(MediaAttachmentRelations.isAttachmentChild(message));
            assertNull(MediaAttachmentRelations.resolveAnchor(message));
            assertTrue(MediaAttachmentRelations.getRelatedMedia(message).isEmpty());
        } catch (final Throwable throwable) {
            fail("External payload must not throw: " + throwable);
        }
    }

    private static void assertNoCaption(final Message anchor, final Message caption) {
        assertNull(MediaAttachmentRelations.getCaption(anchor));
        assertFalse(MediaAttachmentRelations.isCaptionChild(caption));
    }

    private static Conversation conversation(final String id, final int mode) {
        return new Conversation(
                id,
                "test",
                null,
                "account",
                Jid.of("contact@example.test"),
                0,
                Conversation.STATUS_AVAILABLE,
                mode,
                "",
                null);
    }

    private static Message media(
            final Conversation conversation,
            final String remoteId,
            final Jid counterpart,
            final long timeSent) {
        final Message message =
                new Message(
                        conversation,
                        "https://upload.example/" + remoteId + ".jpg",
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_RECEIVED);
        message.setType(Message.TYPE_IMAGE);
        message.setRemoteMsgId(remoteId);
        message.setCounterpart(counterpart);
        message.timeSent = timeSent;
        return message;
    }

    private static Message text(
            final Conversation conversation,
            final String remoteId,
            final Jid counterpart,
            final long timeSent) {
        return text(conversation, remoteId, counterpart, timeSent, "ordinary text", Message.STATUS_RECEIVED);
    }

    private static Message text(
            final Conversation conversation,
            final String remoteId,
            final Jid counterpart,
            final long timeSent,
            final String body,
            final int status) {
        final Message message = new Message(conversation, body, Message.ENCRYPTION_NONE, status);
        message.setRemoteMsgId(remoteId);
        message.setCounterpart(counterpart);
        message.timeSent = timeSent;
        return message;
    }

    private static void attach(final Message child, final String anchorId) {
        child.addPayload(new Element("attach-to", Namespace.MESSAGE_ATTACHING).setAttribute("id", anchorId));
    }

    private static void add(final Conversation conversation, final Message... messages) {
        conversation.messages.addAll(Arrays.asList(messages));
    }
}
