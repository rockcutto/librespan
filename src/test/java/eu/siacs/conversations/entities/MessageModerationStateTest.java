package eu.siacs.conversations.entities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.database.MatrixCursor;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadMode;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MessageModerationStateTest {

    private static final Jid ROOM = Jid.of("room@conference.example");

    @Test
    public void moderationScrubsPresentationAndProtectedPayloadState() {
        final Conversation conversation = conversation();
        final Message message =
                new Message(conversation, "legacy body", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        message.setCounterpart(ROOM.withResource("alice"));
        message.setRoomStanzaId("room-id");
        message.setEncryptedBody("ciphertext");
        message.setRelativeFilePath("media/secret.jpg");
        message.setMediaGroupId("album-a");
        message.setType(Message.TYPE_IMAGE);
        message.setOob(true);
        message.addPayload(new Element("reply", "urn:xmpp:reply:0").setAttribute("id", "old"));
        message.setReactions(
                List.of(new Reaction("❤️", true, ROOM.withResource("alice"), null, "occupant-a")));
        message.setSecureMediaPresentationMetadata("image/jpeg", "secret.jpg", 123L);
        message.setSecureMessagePayloadMode(SecureMessagePayloadMode.PROTECTED);
        message.setVerifiedProtectedBody("protected secret");

        message.markModerated("moderator@example.test", "spam", 1234L);

        assertTrue(message.isModerated());
        assertEquals("", message.getBody());
        assertEquals("", message.getBodyForDisplaying().toString());
        assertNull(message.getEncryptedBody());
        assertNull(message.getVerifiedProtectedBodyOrNull());
        assertNull(message.getRelativeFilePath());
        assertNull(message.getMediaGroupId());
        assertEquals(Message.TYPE_TEXT, message.getType());
        assertFalse(message.isOOb());
        assertTrue(message.getPayloads().isEmpty());
        assertTrue(message.getReactionsNew().isEmpty());
        assertFalse(message.isSecureMediaPresentationMetadataResolved());
        assertNull(message.getSecureMediaMimeType());
        assertNull(message.getSecureMediaFileName());
        assertNull(message.getSecureMediaSizeBytes());
        assertEquals("moderator@example.test", message.getModeratedBy());
        assertEquals("spam", message.getModerationReason());
        assertEquals(1234L, message.getModeratedAt());
    }

    @Test
    public void moderatedProtectedTextCannotBeRehydratedBackIntoPresentation() {
        final Message message =
                new Message(
                        conversation(),
                        "plain",
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_RECEIVED);
        message.setSecureMessagePayloadMode(SecureMessagePayloadMode.PROTECTED);
        message.setVerifiedProtectedBody("verified secret");
        message.markModerated("mod", null, 8L);

        message.setVerifiedProtectedBody("attempted resurrection");
        message.promoteLegacyToVerifiedProtectedBody("attempted legacy resurrection");

        assertNull(message.getVerifiedProtectedBodyOrNull());
        assertFalse(message.hasVerifiedProtectedBody());
        assertEquals("", message.getBody());
        assertEquals("", message.getBodyForSecurePublication());
    }

    @Test
    public void moderationPersistenceRoundTripKeepsTombstoneAndCanonicalRoomIdentity() {
        final Conversation conversation = conversation();
        final Message message =
                new Message(conversation, "body", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        message.setCounterpart(ROOM.withResource("alice"));
        message.setRoomStanzaId("room-stanza-17");
        message.markModerated("mod@example.test", "reason", 4242L);
        message.setModerationRetired(true);

        final Message restored = roundTrip(message, conversation);

        assertTrue(restored.isModerated());
        assertEquals("room-stanza-17", restored.getRoomStanzaId());
        assertEquals("", restored.getBody());
        assertEquals("mod@example.test", restored.getModeratedBy());
        assertEquals("reason", restored.getModerationReason());
        assertEquals(4242L, restored.getModeratedAt());
        assertEquals(
                1,
                restored.getContentValues().getAsInteger(Message.MODERATION_RETIRED).intValue());
    }

    @Test
    public void secondTombstoneScrubRemovesFallbackPayloadAddedDuringArchiveParsing() {
        final Message message =
                new Message(
                        conversation(),
                        "",
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_RECEIVED);
        message.markModerated("mod", "reason", 7L);

        message.addPayload(
                new Element("fallback", "urn:xmpp:fallback:0")
                        .setAttribute("for", Namespace.MESSAGE_RETRACT));
        message.addPayload(new Element("reply", "urn:xmpp:reply:0").setAttribute("id", "old"));
        message.setRelativeFilePath("media/late.jpg");
        message.setOob(true);

        message.markModerated(
                message.getModeratedBy(),
                message.getModerationReason(),
                message.getModeratedAt());

        assertTrue(message.getPayloads().isEmpty());
        assertNull(message.getRelativeFilePath());
        assertFalse(message.isOOb());
        assertEquals("", message.getBody());
    }

    @Test
    public void repeatedModerationIsIdempotentAndDoesNotReopenRetirement() {
        final Message message =
                new Message(conversation(), "body", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        message.markModerated("first", "first reason", 1L);
        message.setModerationRetired(true);

        message.markModerated("second", "updated reason", 2L);

        assertTrue(message.isModerated());
        assertEquals("second", message.getModeratedBy());
        assertEquals("updated reason", message.getModerationReason());
        assertEquals(2L, message.getModeratedAt());
        assertEquals(
                1,
                message.getContentValues().getAsInteger(Message.MODERATION_RETIRED).intValue());
        assertEquals("", message.getBody());
    }

    @Test
    public void moderationIdFallsBackToLegacyServerIdOnlyWhenCanonicalRoomIdIsMissing() {
        final Conversation conversation = conversation();
        final Message legacy =
                new Message(
                        conversation,
                        "https://example.test",
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_SEND_RECEIVED);
        legacy.setServerMsgId("room-issued-id");
        conversation.add(legacy);

        assertSame(legacy, conversation.findMessageForModerationId("room-issued-id"));

        legacy.setRoomStanzaId("different-canonical-id");
        assertNull(conversation.findMessageForModerationId("room-issued-id"));
        assertSame(legacy, conversation.findMessageForModerationId("different-canonical-id"));
    }

    @Test
    public void moderatedResidentStillDeduplicatesHistoricalReplayByServerId() {
        final Conversation conversation = conversation();
        final Message moderated =
                new Message(
                        conversation,
                        "secret",
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_RECEIVED);
        moderated.setCounterpart(ROOM.withResource("alice"));
        moderated.setServerMsgId("archive-id");
        moderated.setRoomStanzaId("room-id");
        moderated.markModerated("mod", null, 5L);
        conversation.add(moderated);

        final Message replay =
                new Message(
                        conversation,
                        "secret resurrected by archive",
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_RECEIVED);
        replay.setCounterpart(ROOM.withResource("alice"));
        replay.setServerMsgId("archive-id");
        replay.setRoomStanzaId("room-id");

        assertSame(moderated, conversation.findDuplicateMessage(replay));
        assertTrue(moderated.isModerated());
        assertEquals("", moderated.getBody());
    }

    @Test
    public void moderatedTargetIsRemovedFromTimelineButDurableReplyReferenceCanRemainScrubbed() {
        final Conversation conversation = conversation();
        final Message target =
                new Message(
                        conversation,
                        "secret",
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_RECEIVED);
        target.setRoomStanzaId("room-id-visible");
        final Message reply =
                new Message(
                        conversation,
                        "reply",
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_RECEIVED);
        reply.setReplyMessage(target, true);
        conversation.add(target);
        conversation.add(reply);

        target.markModerated("mod", null, 7L);
        conversation.refreshModeratedReplyReferences(target);

        assertTrue(conversation.removeModeratedMessageFromTimeline(target));
        assertNull(conversation.findMessageWithRoomStanzaId("room-id-visible"));
        assertSame(target, reply.getReplyMessage());
        assertTrue(reply.getReplyMessage().isModerated());
        assertEquals("", reply.getReplyMessage().getBody());
    }

    @Test
    public void roomStanzaLookupFindsModeratedTargetOnHistoryPage() {
        final Conversation conversation = conversation();
        final Message target =
                new Message(
                        conversation,
                        "secret",
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_RECEIVED);
        target.setRoomStanzaId("room-id-history");
        target.markModerated("mod", null, 6L);
        conversation.historyPartMessages.add(target);

        assertSame(target, conversation.findMessageWithRoomStanzaId("room-id-history"));
    }

    @Test
    public void replyReferencesAreReboundToScrubbedTargetOnTimelineAndHistoryPage() {
        final Conversation conversation = conversation();
        final Message target =
                new Message(conversation, "secret target", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        final Message detachedTimeline = roundTrip(target, conversation);
        final Message detachedHistory = roundTrip(target, conversation);

        final Message timelineReply =
                new Message(conversation, "reply one", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        timelineReply.setReplyMessage(detachedTimeline, true);
        final Message historyReply =
                new Message(conversation, "reply two", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        historyReply.setReplyMessage(detachedHistory, true);

        conversation.add(timelineReply);
        conversation.historyPartMessages.add(historyReply);

        target.markModerated("mod", null, 3L);
        conversation.refreshModeratedReplyReferences(target);

        assertSame(target, timelineReply.getReplyMessage());
        assertSame(target, historyReply.getReplyMessage());
        assertTrue(timelineReply.getReplyMessage().isModerated());
        assertEquals("", timelineReply.getReplyMessage().getBody());
        assertEquals("", historyReply.getReplyMessage().getBody());
    }

    private static Message roundTrip(
            final Message message, final Conversation conversation) {
        final ContentValues values = message.getContentValues();
        final String[] columns = values.keySet().toArray(new String[0]);
        final Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            row[i] = values.get(columns[i]);
        }
        final MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(row);
        assertTrue(cursor.moveToFirst());
        try {
            return Message.fromCursor(cursor, conversation);
        } finally {
            cursor.close();
        }
    }

    private static Conversation conversation() {
        return new Conversation(
                "moderation",
                new Account(Jid.of("me@example.test"), ""),
                ROOM,
                Conversation.MODE_MULTI,
                null);
    }
}
