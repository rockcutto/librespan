package eu.siacs.conversations.entities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import eu.siacs.conversations.ui.adapter.MediaAlbumSource;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;

import org.junit.Test;

public class MediaGalleryPresentationTest {

    private static final Jid ALICE = Jid.of("alice@example.test");

    @Test
    public void validAnchorAndChildrenProduceOneAlbumForEveryMember() {
        final Conversation conversation = conversation("one");
        final Message anchor = media(conversation, "anchor", 100);
        final Message laterChild = media(conversation, "later", 300);
        final Message earlierChild = media(conversation, "earlier", 200);
        attach(laterChild, "anchor");
        attach(earlierChild, "anchor");
        add(conversation, anchor, laterChild, earlierChild);

        final MediaGalleryPresentation presentation =
                MediaGalleryPresentation.forSnapshot(
                        conversation, 1, Arrays.asList(anchor, laterChild, earlierChild));

        final List<Message> expected = Arrays.asList(anchor, earlierChild, laterChild);
        assertTrue(presentation.hasAlbum(anchor));
        assertTrue(presentation.hasAlbum(earlierChild));
        assertTrue(presentation.hasAlbum(laterChild));
        assertEquals(expected, presentation.getAlbum(anchor));
        assertSame(presentation.getAlbum(anchor), presentation.getAlbum(earlierChild));
        assertSame(presentation.getAlbum(anchor), presentation.getAlbum(laterChild));
    }

    @Test
    public void normalAdjacentImagesDoNotProduceAnAlbum() {
        final Conversation conversation = conversation("two");
        final Message first = media(conversation, "first", 100);
        final Message second = media(conversation, "second", 200);
        final Message third = media(conversation, "third", 300);
        add(conversation, first, second, third);

        final MediaGalleryPresentation presentation =
                MediaGalleryPresentation.forSnapshot(
                        conversation, 1, Arrays.asList(first, second, third));

        assertFalse(presentation.hasAlbum(first));
        assertFalse(presentation.hasAlbum(second));
        assertFalse(presentation.hasAlbum(third));
        assertTrue(presentation.getAlbum(first).isEmpty());
    }

    @Test
    public void invalidRelationsDoNotProduceAnAlbum() {
        final Conversation conversation = conversation("three");
        final Message anchor = media(conversation, "anchor", 100);
        final Message duplicateChild = media(conversation, "child", 200);
        attach(duplicateChild, "anchor");
        attach(duplicateChild, "anchor");
        add(conversation, anchor, duplicateChild);

        final MediaGalleryPresentation presentation =
                MediaGalleryPresentation.forSnapshot(
                        conversation, 1, Arrays.asList(anchor, duplicateChild));

        assertFalse(presentation.hasAlbum(anchor));
        assertFalse(presentation.hasAlbum(duplicateChild));
        assertTrue(presentation.getAlbum(duplicateChild).isEmpty());
    }

    @Test
    public void oldPresentationIsInvalidAfterConversationRevisionChanges() {
        final Conversation conversation = conversation("four");
        final Message anchor = media(conversation, "anchor", 100);
        final Message child = media(conversation, "child", 200);
        attach(child, "anchor");
        add(conversation, anchor, child);

        final MediaGalleryPresentation oldPresentation =
                MediaGalleryPresentation.forSnapshot(conversation, 4, Arrays.asList(anchor, child));

        final Message incoming = media(conversation, "incoming", 300);
        add(conversation, incoming);
        final MediaGalleryPresentation updatedPresentation =
                MediaGalleryPresentation.forSnapshot(
                        conversation, 5, Arrays.asList(anchor, child, incoming));

        assertTrue(oldPresentation.isCurrentFor(conversation, 4));
        assertFalse(oldPresentation.isCurrentFor(conversation, 5));
        assertTrue(updatedPresentation.isCurrentFor(conversation, 5));
        assertTrue(
                updatedPresentation.matchesSnapshot(
                        conversation, Arrays.asList(anchor, child, incoming)));
        assertFalse(
                updatedPresentation.matchesSnapshot(conversation, Arrays.asList(anchor, child)));
        assertTrue(updatedPresentation.hasAlbum(anchor));
        assertFalse(updatedPresentation.hasAlbum(incoming));
    }

    @Test
    public void attachmentPayloadAdditionInvalidatesSnapshot() {
        final Conversation conversation = conversation("payload-change");
        final Message anchor = media(conversation, "anchor", 100);
        final Message child = media(conversation, "child", 200);
        add(conversation, anchor, child);

        final MediaGalleryPresentation stalePresentation =
                MediaGalleryPresentation.forSnapshot(conversation, 1, Arrays.asList(anchor, child));
        assertFalse(stalePresentation.hasAlbum(anchor));

        attach(child, "anchor");

        assertFalse(stalePresentation.matchesSnapshot(conversation, Arrays.asList(anchor, child)));

        final MediaGalleryPresentation refreshedPresentation =
                MediaGalleryPresentation.forSnapshot(conversation, 2, Arrays.asList(anchor, child));
        assertTrue(refreshedPresentation.hasAlbum(anchor));
        assertEquals(Arrays.asList(anchor, child), refreshedPresentation.getAlbum(anchor));
    }

    @Test
    public void delayedAnchorGroupsWhenPresentationRefreshes() {
        final Conversation conversation = conversation("delayed-anchor");
        final Message child = media(conversation, "child", 200);
        attach(child, "anchor");
        add(conversation, child);

        final MediaGalleryPresentation beforeAnchor =
                MediaGalleryPresentation.forSnapshot(conversation, 1, Arrays.asList(child));
        assertFalse(beforeAnchor.hasAlbum(child));

        final Message anchor = media(conversation, "anchor", 100);
        add(conversation, anchor);

        final MediaGalleryPresentation afterAnchor =
                MediaGalleryPresentation.forSnapshot(conversation, 2, Arrays.asList(anchor, child));
        assertEquals(Arrays.asList(anchor, child), afterAnchor.getAlbum(anchor));
        assertEquals(Arrays.asList(anchor, child), afterAnchor.getAlbum(child));
    }

    @Test
    public void liveIncomingChildBeforeAnchorRefreshesPresentationAndAdapterSource() {
        final Conversation conversation = conversation("live-refresh");
        final Message child = media(conversation, "child", 200);
        attach(child, "anchor");
        add(conversation, child);

        final MediaGalleryPresentation beforeAnchor =
                MediaGalleryPresentation.forSnapshot(conversation, 1, Arrays.asList(child));
        assertFalse(beforeAnchor.hasAlbum(child));

        final Message anchor = media(conversation, "anchor", 100);
        anchor.setRemoteMsgId(null);
        add(conversation, anchor);

        final List<Message> openConversationSnapshot = Arrays.asList(child, anchor);
        final MediaGalleryPresentation beforeAnchorIdentity =
                MediaGalleryPresentation.forSnapshot(conversation, 2, openConversationSnapshot);
        assertFalse(beforeAnchorIdentity.hasAlbum(child));

        anchor.setRemoteMsgId("anchor");

        assertFalse(beforeAnchorIdentity.matchesSnapshot(conversation, openConversationSnapshot));

        final MediaGalleryPresentation refreshedPresentation =
                MediaGalleryPresentation.forSnapshot(conversation, 3, openConversationSnapshot);
        final MediaAlbumSource adapterSource =
                MediaAlbumSource.select(
                        message -> message != null && message.getStatus() <= Message.STATUS_RECEIVED,
                        refreshedPresentation::getAlbum,
                        MediaAlbumSource.empty());

        assertEquals(Arrays.asList(anchor, child), adapterSource.getAlbum(anchor));
        assertEquals(Arrays.asList(anchor, child), adapterSource.getAlbum(child));
        assertTrue(adapterSource.isContinuation(child));
    }

    @Test
    public void incomingPresentationProvidesAlbumsOnlyForValidatedRelations() {
        final Conversation conversation = conversation("five");
        final Message anchor = media(conversation, "anchor", 100);
        final Message child = media(conversation, "child", 200);
        final Message ordinary = media(conversation, "ordinary", 300);
        attach(child, "anchor");
        add(conversation, anchor, child, ordinary);

        final MediaGalleryPresentation presentation =
                MediaGalleryPresentation.forSnapshot(
                        conversation, 1, Arrays.asList(anchor, child, ordinary));
        final MediaAlbumSource source =
                MediaAlbumSource.select(
                        message -> message != null && message.getStatus() <= Message.STATUS_RECEIVED,
                        presentation::getAlbum,
                        MediaAlbumSource.empty());

        assertEquals(Arrays.asList(anchor, child), source.getAlbum(anchor));
        assertTrue(source.isContinuation(child));
        assertTrue(source.getAlbum(ordinary).isEmpty());
    }

    @Test
    public void singleImageCaptionRemainsOutsideMediaAlbum() {
        final Conversation conversation = conversation("caption-single");
        final Message anchor = media(conversation, "anchor", 100);
        final Message caption = text(conversation, "caption", ALICE, 200);
        attach(caption, "anchor");
        add(conversation, anchor, caption);

        final MediaGalleryPresentation presentation =
                MediaGalleryPresentation.forSnapshot(conversation, 1, Arrays.asList(anchor, caption));

        assertTrue(presentation.getAlbum(anchor).isEmpty());
        assertSame(caption, presentation.getCaption(anchor));
        assertNull(presentation.getCaption(caption));
    }

    @Test
    public void captionDoesNotChangeTwoThreeOrFiveImageAlbums() {
        assertCaptionedAlbum(2);
        assertCaptionedAlbum(3);
        assertCaptionedAlbum(5);
    }

    @Test
    public void invalidCaptionsLeaveMediaPresentationUnchanged() {
        final Conversation duplicateConversation = conversation("caption-duplicate");
        final Message duplicateAnchor = media(duplicateConversation, "anchor", 100);
        final Message first = text(duplicateConversation, "caption-1", ALICE, 200);
        final Message second = text(duplicateConversation, "caption-2", ALICE, 300);
        attach(first, "anchor");
        attach(second, "anchor");
        add(duplicateConversation, duplicateAnchor, first, second);
        final MediaGalleryPresentation duplicatePresentation =
                MediaGalleryPresentation.forSnapshot(
                        duplicateConversation, 1, Arrays.asList(duplicateAnchor, first, second));
        assertNull(duplicatePresentation.getCaption(duplicateAnchor));
        assertTrue(duplicatePresentation.getAlbum(duplicateAnchor).isEmpty());

        final Conversation malformedConversation = conversation("caption-malformed");
        final Message malformedAnchor = media(malformedConversation, "anchor", 100);
        final Message malformedCaption = text(malformedConversation, "caption", ALICE, 200);
        malformedCaption.addPayload(new Element("attach-to", Namespace.MESSAGE_ATTACHING));
        add(malformedConversation, malformedAnchor, malformedCaption);
        final MediaGalleryPresentation malformedPresentation =
                MediaGalleryPresentation.forSnapshot(
                        malformedConversation, 1, Arrays.asList(malformedAnchor, malformedCaption));
        assertNull(malformedPresentation.getCaption(malformedAnchor));
        assertTrue(malformedPresentation.getAlbum(malformedAnchor).isEmpty());

        final Conversation senderConversation = conversation("caption-sender");
        final Message senderAnchor = media(senderConversation, "anchor", 100);
        final Message wrongSender = text(senderConversation, "caption", Jid.of("bob@example.test"), 200);
        attach(wrongSender, "anchor");
        add(senderConversation, senderAnchor, wrongSender);
        final MediaGalleryPresentation senderPresentation =
                MediaGalleryPresentation.forSnapshot(
                        senderConversation, 1, Arrays.asList(senderAnchor, wrongSender));
        assertNull(senderPresentation.getCaption(senderAnchor));
        assertTrue(senderPresentation.getAlbum(senderAnchor).isEmpty());

        final Conversation anchorConversation = conversation("caption-anchor-conversation");
        final Conversation captionConversation = conversation("caption-foreign-conversation");
        final Message foreignAnchor = media(anchorConversation, "anchor", 100);
        final Message foreignCaption = text(captionConversation, "caption", ALICE, 200);
        attach(foreignCaption, "anchor");
        add(anchorConversation, foreignAnchor);
        add(captionConversation, foreignCaption);
        final MediaGalleryPresentation foreignPresentation =
                MediaGalleryPresentation.forSnapshot(
                        anchorConversation, 1, Arrays.asList(foreignAnchor));
        assertNull(foreignPresentation.getCaption(foreignAnchor));
        assertTrue(foreignPresentation.getAlbum(foreignAnchor).isEmpty());
    }

    @Test
    public void captionBeforeAnchorResolvesOnlyAfterPresentationRebuild() {
        final Conversation conversation = conversation("caption-delayed");
        final Message caption = text(conversation, "caption", ALICE, 200);
        attach(caption, "anchor");
        add(conversation, caption);
        final MediaGalleryPresentation beforeAnchor =
                MediaGalleryPresentation.forSnapshot(conversation, 1, Arrays.asList(caption));
        assertNull(beforeAnchor.getCaption(caption));

        final Message anchor = media(conversation, "anchor", 100);
        add(conversation, anchor);
        final List<Message> refreshedSnapshot = Arrays.asList(anchor, caption);
        assertFalse(beforeAnchor.matchesSnapshot(conversation, refreshedSnapshot));
        final MediaGalleryPresentation afterAnchor =
                MediaGalleryPresentation.forSnapshot(conversation, 2, refreshedSnapshot);
        assertSame(caption, afterAnchor.getCaption(anchor));
        assertTrue(afterAnchor.getAlbum(anchor).isEmpty());
    }

    @Test
    public void captionFieldsInvalidatePresentationSnapshot() {
        final Conversation conversation = conversation("caption-fingerprint");
        final Message anchor = media(conversation, "anchor", 100);
        final Message caption = text(conversation, "caption", ALICE, 200);
        attach(caption, "anchor");
        add(conversation, anchor, caption);
        final List<Message> snapshot = Arrays.asList(anchor, caption);
        final MediaGalleryPresentation presentation =
                MediaGalleryPresentation.forSnapshot(conversation, 1, snapshot);
        assertSame(caption, presentation.getCaption(anchor));
        caption.body = "changed caption";
        assertFalse(presentation.matchesSnapshot(conversation, snapshot));
    }

    @Test
    public void equivalentHistorySnapshotsHaveSameAlbumAndCaption() {
        final Conversation original = conversation("caption-history-original");
        final Message originalAnchor = media(original, "anchor", 100);
        final Message originalChild = media(original, "child", 200);
        final Message originalCaption = text(original, "caption", ALICE, 300);
        attach(originalChild, "anchor");
        attach(originalCaption, "anchor");
        add(original, originalAnchor, originalChild, originalCaption);

        final Conversation reloaded = conversation("caption-history-reloaded");
        final Message reloadedAnchor = media(reloaded, "anchor", 100);
        final Message reloadedChild = media(reloaded, "child", 200);
        final Message reloadedCaption = text(reloaded, "caption", ALICE, 300);
        attach(reloadedChild, "anchor");
        attach(reloadedCaption, "anchor");
        add(reloaded, reloadedCaption, reloadedChild, reloadedAnchor);

        final MediaGalleryPresentation originalPresentation =
                MediaGalleryPresentation.forSnapshot(
                        original, 1, Arrays.asList(originalAnchor, originalChild, originalCaption));
        final MediaGalleryPresentation reloadedPresentation =
                MediaGalleryPresentation.forSnapshot(
                        reloaded, 1, Arrays.asList(reloadedAnchor, reloadedChild, reloadedCaption));

        assertEquals(remoteIds(originalPresentation.getAlbum(originalAnchor)),
                remoteIds(reloadedPresentation.getAlbum(reloadedAnchor)));
        assertEquals(originalPresentation.getCaption(originalAnchor).getRemoteMsgId(),
                reloadedPresentation.getCaption(reloadedAnchor).getRemoteMsgId());
    }

    private static void assertCaptionedAlbum(final int imageCount) {
        final Conversation conversation = conversation("caption-album-" + imageCount);
        final Message anchor = media(conversation, "anchor-" + imageCount, 100);
        final Message caption = text(conversation, "caption-" + imageCount, ALICE, 1000);
        final List<Message> expected = new ArrayList<>();
        expected.add(anchor);
        for (int index = 1; index < imageCount; index++) {
            final Message child = media(conversation, "child-" + imageCount + "-" + index, 100 + index);
            attach(child, anchor.getRemoteMsgId());
            expected.add(child);
        }
        attach(caption, anchor.getRemoteMsgId());
        final List<Message> snapshot = new ArrayList<>(expected);
        snapshot.add(caption);
        add(conversation, snapshot.toArray(new Message[0]));
        final MediaGalleryPresentation presentation =
                MediaGalleryPresentation.forSnapshot(conversation, 1, snapshot);
        assertEquals(expected, presentation.getAlbum(anchor));
        assertSame(caption, presentation.getCaption(anchor));
        assertFalse(presentation.getAlbum(anchor).contains(caption));
    }

    private static List<String> remoteIds(final List<Message> messages) {
        final List<String> remoteIds = new ArrayList<>(messages.size());
        for (final Message message : messages) remoteIds.add(message.getRemoteMsgId());
        return remoteIds;
    }

    private static Conversation conversation(final String id) {
        return new Conversation(id, "test", null, "account", Jid.of("contact@example.test"), 0,
                Conversation.STATUS_AVAILABLE, Conversation.MODE_SINGLE, "", null);
    }

    private static Message media(
            final Conversation conversation, final String remoteId, final long timeSent) {
        final Message message = new Message(conversation,
                "https://upload.example/" + remoteId + ".jpg",
                Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        message.setType(Message.TYPE_IMAGE);
        message.setRemoteMsgId(remoteId);
        message.setCounterpart(ALICE);
        message.timeSent = timeSent;
        return message;
    }

    private static Message text(
            final Conversation conversation, final String remoteId, final Jid counterpart,
            final long timeSent) {
        final Message message = new Message(conversation, "ordinary text",
                Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        message.setRemoteMsgId(remoteId);
        message.setCounterpart(counterpart);
        message.timeSent = timeSent;
        return message;
    }

    private static void attach(final Message message, final String anchorId) {
        message.addPayload(new Element("attach-to", Namespace.MESSAGE_ATTACHING).setAttribute("id", anchorId));
    }

    private static void add(final Conversation conversation, final Message... messages) {
        conversation.messages.addAll(Arrays.asList(messages));
    }
}
