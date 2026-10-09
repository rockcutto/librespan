package eu.siacs.conversations.persistance;

import static org.junit.Assert.*;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.ServiceDiscoveryResult;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;

import im.conversations.android.xmpp.model.stanza.Iq;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MessageRetractionJournalInstrumentationTest {

    private static final String DATABASE = "history";
    private static final Jid ROOM =
            Jid.of("room@conference.example");
    private static final Jid ALICE =
            Jid.of("room@conference.example/alice");
    private static final Jid BOB =
            Jid.of("room@conference.example/bob");

    private Context isolatedContext;
    private DatabaseBackendImpl backend;
    private SQLiteDatabase db;
    private Conversation room;
    private boolean ownsDatabase;

    @Before
    public void setUp() {
        final Context app = ApplicationProvider.getApplicationContext();
        isolatedContext = app.createDeviceProtectedStorageContext();

        assertNotNull(isolatedContext);
        assertTrue(isolatedContext.isDeviceProtectedStorage());
        assertNotEquals(
                app.getDatabasePath(DATABASE).getAbsolutePath(),
                isolatedContext.getDatabasePath(DATABASE).getAbsolutePath());

        assertFalse(
                "Refusing to touch an existing device-protected database",
                isolatedContext.getDatabasePath(DATABASE).exists());

        System.loadLibrary("sqlcipher");

        ownsDatabase = true;
        backend = new DatabaseBackendImpl(
                isolatedContext, "xep0424-isolated-test");
        db = backend.getWritableDatabase();

        final Account account = new Account(
                Jid.of("me@example.test"), "");

        room = new Conversation(
                "Test Room", account, ROOM,
                Conversation.MODE_MULTI, null);

        final ContentValues accountRow = new ContentValues();
        accountRow.put(Account.UUID, account.getUuid());
        db.insertOrThrow(Account.TABLENAME, null, accountRow);

        final ContentValues conversationRow = new ContentValues();
        conversationRow.put(Conversation.UUID, room.getUuid());
        conversationRow.put(Conversation.ACCOUNT, account.getUuid());
        db.insertOrThrow(
                Conversation.TABLENAME, null, conversationRow);

        final Iq disco = new Iq(Iq.Type.RESULT);
        final Element query =
                disco.addChild("query", Namespace.DISCO_INFO);
        query.addChild("feature")
                .setAttribute("var", "muc_nonanonymous");

        room.getMucOptions().updateConfiguration(
                new ServiceDiscoveryResult(disco));
    }

    @After
    public void tearDown() {
        if (backend != null) {
            backend.close();
        }
        if (ownsDatabase && isolatedContext != null) {
            isolatedContext.deleteDatabase(DATABASE);
        }
    }

    private Message insertOriginal() {
        final Message message = new Message(
                room, "original content",
                Message.ENCRYPTION_NONE,
                Message.STATUS_RECEIVED);
        message.setCounterpart(ALICE);
        message.setRoomStanzaId("room-id-123");
        backend.createMessage(message);
        return message;
    }

    private boolean record(
            String request, String target, Jid sender) {
        return backend.recordUnverifiedMucRetraction(
                room, request, target, sender, null, 100L);
    }

    @Test
    public void verifiedRetractionMovesToPendingAndScrubsOriginal() {
        final Message original = insertOriginal();

        assertTrue(record("request-retire", "room-id-123", ALICE));
        assertTrue(backend.verifyUnverifiedMucRetraction(
                room, "request-retire"));

        assertTrue(backend.beginVerifiedMucRetractionRetirement(
                room, "request-retire"));

        // The transition must not run a second time.
        assertFalse(backend.beginVerifiedMucRetractionRetirement(
                room, "request-retire"));

        final Message scrubbed =
                backend.getMessageWithRoomStanzaId(
                        room, "room-id-123");

        assertNotNull(scrubbed);
        assertEquals(original.getUuid(), scrubbed.getUuid());
        assertEquals("", scrubbed.getBody());
        assertTrue(scrubbed.isRetracted());
        assertFalse(scrubbed.isRetractionRetired());
        assertFalse(scrubbed.isModerated());

        try (Cursor cursor = db.rawQuery(
                "SELECT state,target_message_uuid "
                        + "FROM message_retraction "
                        + "WHERE retraction_request_id=?",
                new String[] {"request-retire"})) {
            assertTrue(cursor.moveToFirst());
            assertEquals(2, cursor.getInt(0));
            assertEquals(original.getUuid(), cursor.getString(1));
        }
    }

    @Test
    public void unverifiedRequestCannotScrubMessage() {
        insertOriginal();

        assertTrue(record(
                "request-unverified", "room-id-123", BOB));

        assertFalse(backend.beginVerifiedMucRetractionRetirement(
                room, "request-unverified"));

        assertEquals("original content",
                backend.getMessageWithRoomStanzaId(
                        room, "room-id-123").getBody());
    }

    @Test
    public void pendingThenVerifiedTransaction() {
        assertTrue(record(
                "request-456", "room-id-123", ALICE));

        assertEquals(1, backend.getUnverifiedMucRetractions(
                room, "room-id-123").size());

        // Original is not present yet.
        assertFalse(backend.verifyUnverifiedMucRetraction(
                room, "request-456"));

        final Message original = insertOriginal();

        assertTrue(backend.verifyUnverifiedMucRetraction(
                room, "request-456"));

        assertEquals(0, backend.getUnverifiedMucRetractions(
                room, "room-id-123").size());

        try (Cursor cursor = db.rawQuery(
                "SELECT state,target_message_uuid "
                        + "FROM message_retraction "
                        + "WHERE retraction_request_id=?",
                new String[] {"request-456"})) {
            assertTrue(cursor.moveToFirst());
            assertEquals(1, cursor.getInt(0));
            assertEquals(original.getUuid(), cursor.getString(1));
        }

        // Verification must not delete the original.
        assertEquals(
                "original content",
                backend.getMessageWithRoomStanzaId(
                        room, "room-id-123").getBody());

        assertFalse(backend.verifyUnverifiedMucRetraction(
                room, "request-456"));
    }

    @Test
    public void foreignSenderCannotVerify() {
        insertOriginal();

        assertTrue(record(
                "request-bob", "room-id-123", BOB));

        assertFalse(backend.verifyUnverifiedMucRetraction(
                room, "request-bob"));

        assertEquals(1, backend.getUnverifiedMucRetractions(
                room, "room-id-123").size());
    }

    @Test
    public void conflictingRequestDoesNotOverwriteJournal() {
        assertTrue(record(
                "request-1", "room-id-123", ALICE));

        assertTrue(record(
                "request-1", "room-id-123", ALICE));

        assertFalse(record(
                "request-1", "another-target", ALICE));

        assertFalse(record(
                "request-1", "room-id-123", BOB));

        assertEquals(1, backend.getUnverifiedMucRetractions(
                room, "room-id-123").size());
    }
}
