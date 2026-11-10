package eu.siacs.conversations.services;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.media.MediaCaptionResolver;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class LocalDeferredMediaCaptionTest {
    @Test
    public void mucImageCaptionWaitsForUploadAndDoesNotEmitXepRelation() {
        final Conversation room = conversation(Conversation.MODE_MULTI);
        final Message media = media(room, "image-1");
        final Message caption = caption(room);
        final List<Message> snapshot = Arrays.asList(media, caption);

        LocalDeferredMediaCaption.rememberWithoutXepRelation(caption, media);

        assertTrue(LocalDeferredMediaCaption.isManaged(caption));
        assertTrue(caption.getPayloads().isEmpty());
        assertNull(MediaCaptionResolver.resolveAnchor(caption, snapshot));
        assertSame(media, LocalDeferredMediaCaption.findAnchor(room, caption, snapshot));
        assertFalse(XmppConnectionService.isMediaTransactionTransportReady(media, snapshot));

        // HTTP upload completed and committed its URL; the stanza has been queued for MUC.
        media.setBody("https://upload.example/image-1.jpg|1024");
        media.setStatus(Message.STATUS_UNSEND);
        assertTrue(XmppConnectionService.isMediaTransactionTransportReady(media, snapshot));
    }

    @Test
    public void mucAlbumCaptionWaitsUntilEveryImageHasAnUploadUrl() {
        final Conversation room = conversation(Conversation.MODE_MULTI);
        final Message first = media(room, "first-album");
        final Message second = media(room, "second-album");
        first.setMediaGroupId("album-1");
        second.setMediaGroupId("album-1");
        final Message caption = caption(room);
        LocalDeferredMediaCaption.rememberWithoutXepRelation(caption, first);
        final List<Message> snapshot = Arrays.asList(first, second, caption);

        first.setBody("https://upload.example/first.jpg|1024");
        first.setStatus(Message.STATUS_UNSEND);
        assertSame(first, LocalDeferredMediaCaption.findAnchor(room, caption, snapshot));
        assertFalse(XmppConnectionService.isMediaTransactionTransportReady(first, snapshot));

        second.setBody("https://upload.example/second.jpg|2048");
        second.setStatus(Message.STATUS_UNSEND);
        assertTrue(XmppConnectionService.isMediaTransactionTransportReady(first, snapshot));
    }

    @Test
    public void pendingCaptionAnchorSurvivesDatabaseSerializationAndRestart() {
        final Conversation room = conversation(Conversation.MODE_MULTI);
        final Message media = media(room, "image-2");
        final Message caption = caption(room);
        LocalDeferredMediaCaption.rememberWithoutXepRelation(caption, media);
        final String durableMarker = caption.getContentValues().getAsString(Message.MEDIA_GROUP_ID);
        assertNotNull(durableMarker);

        final Message loadedMedia = media(room, media.getUuid());
        loadedMedia.setBody("https://upload.example/image-2.jpg|1024");
        loadedMedia.setStatus(Message.STATUS_UNSEND);
        final Message loadedCaption = caption(room);
        loadedCaption.setMediaGroupId(durableMarker);
        assertSame(loadedMedia, LocalDeferredMediaCaption.findAnchor(room, loadedCaption,
                Arrays.asList(loadedMedia, loadedCaption)));
        assertTrue(XmppConnectionService.isMediaTransactionTransportReady(loadedMedia,
                Arrays.asList(loadedMedia, loadedCaption)));
    }

    @Test
    public void missingOrFailedMediaNeverReleasesCaption() {
        final Conversation room = conversation(Conversation.MODE_MULTI);
        final Message media = media(room, "image-3");
        final Message caption = caption(room);
        LocalDeferredMediaCaption.rememberWithoutXepRelation(caption, media);
        assertNull(LocalDeferredMediaCaption.findAnchor(room, caption,
                Collections.singletonList(caption)));

        media.setBody("https://upload.example/image-3.jpg|1024");
        media.setStatus(Message.STATUS_SEND_FAILED);
        final List<Message> snapshot = Arrays.asList(media, caption);
        assertSame(media, LocalDeferredMediaCaption.findAnchor(room, caption, snapshot));
        assertFalse(XmppConnectionService.isMediaTransactionTransportReady(media, snapshot));
    }

    @Test
    public void unrelatedMediaCannotSatisfyLocalCaptionDependency() {
        final Conversation room = conversation(Conversation.MODE_MULTI);
        final Message original = media(room, "image-4");
        final Message unrelated = media(room, "other-4");
        final Message caption = caption(room);
        LocalDeferredMediaCaption.rememberWithoutXepRelation(caption, original);
        unrelated.setBody("https://upload.example/other-4.jpg|1024");
        unrelated.setStatus(Message.STATUS_UNSEND);
        assertNull(LocalDeferredMediaCaption.findAnchor(room, caption,
                Arrays.asList(unrelated, caption)));
    }

    @Test
    public void directChatAttachmentRelationIsPreserved() {
        final Conversation direct = conversation(Conversation.MODE_SINGLE);
        final Message media = media(direct, "image-5");
        final Message caption = caption(direct);
        caption.addPayload(new Element("attach-to", Namespace.MESSAGE_ATTACHING)
                .setAttribute("id", media.getUuid()));
        LocalDeferredMediaCaption.rememberWithoutXepRelation(caption, media);
        assertNull(caption.getMediaGroupId());
        assertEquals(1, caption.getPayloads().size());
        assertFalse(LocalDeferredMediaCaption.isManaged(caption));
    }

    private static Conversation conversation(final int mode) {
        return new Conversation("local-caption-test", "test", null, "account",
                Jid.of(mode == Conversation.MODE_MULTI
                        ? "room@conference.example" : "contact@example.test"),
                0, Conversation.STATUS_AVAILABLE, mode, "", null);
    }

    private static Message media(final Conversation conversation, final String uuid) {
        final Message media = new Message(conversation, "", Message.ENCRYPTION_NONE,
                Message.STATUS_WAITING);
        media.setType(Message.TYPE_IMAGE);
        media.setUuid(uuid);
        return media;
    }

    private static Message caption(final Conversation conversation) {
        return new Message(conversation, "photo caption", Message.ENCRYPTION_NONE,
                Message.STATUS_WAITING);
    }
}
