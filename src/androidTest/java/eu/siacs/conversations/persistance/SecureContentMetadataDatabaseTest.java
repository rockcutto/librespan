package eu.siacs.conversations.persistance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.storage.secure.SecureContentMetadata;
import eu.siacs.conversations.storage.secure.SecureContentMetadataRecord;
import eu.siacs.conversations.storage.secure.SecureContentState;

/** Instrumentation coverage for the S3.2 SQLCipher metadata mapping. */
@RunWith(AndroidJUnit4.class)
public class SecureContentMetadataDatabaseTest {

    private static final String DATABASE_NAME = "history";
    private Context context;
    private DatabaseBackendImpl databaseBackend;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        System.loadLibrary("sqlcipher");
        context.deleteDatabase(DATABASE_NAME);
        databaseBackend = new DatabaseBackendImpl(context, "secure-content-test");
        databaseBackend.getWritableDatabase();
    }

    @After
    public void tearDown() {
        if (databaseBackend != null) {
            databaseBackend.close();
        }
        if (context != null) {
            context.deleteDatabase(DATABASE_NAME);
        }
    }

    @Test
    public void createFindUpdateAndDeleteMetadata() {
        final SecureContentMetadata metadata =
                new SecureContentMetadata(
                        "content-id",
                        "message-id",
                        "image/jpeg",
                        128L,
                        SecureContentState.ALLOCATED,
                        1);
        final SecureContentMetadataRecord created =
                databaseBackend.create(metadata, "objects/content-id");

        assertEquals("content-id", created.getMetadata().getContentId());
        assertEquals(
                "content-id",
                databaseBackend.findByContentId("content-id").getMetadata().getContentId());
        assertEquals(
                "message-id",
                databaseBackend.findByMessageUuid("message-id").getMetadata().getMessageUuid());

        assertTrue(databaseBackend.updateState("content-id", SecureContentState.AVAILABLE));
        assertEquals(
                SecureContentState.AVAILABLE,
                databaseBackend.findByContentId("content-id").getMetadata().getState());

        assertTrue(databaseBackend.deleteMetadata("content-id"));
        assertNull(databaseBackend.findByContentId("content-id"));
        assertNull(databaseBackend.findByMessageUuid("message-id"));
    }

    @Test
    public void unknownPersistedStateFallsBackToFailed() {
        final SQLiteDatabase db = databaseBackend.getWritableDatabase();
        final ContentValues values = new ContentValues();
        values.put("content_id", "unknown-state");
        values.put("state", "future-state");
        values.put("created_at", 1L);
        values.put("updated_at", 1L);
        db.insertOrThrow("secure_content", null, values);

        assertEquals(
                SecureContentState.FAILED,
                databaseBackend.findByContentId("unknown-state").getMetadata().getState());
    }

    @Test
    public void upgradeAddsSecureTableWithoutChangingLegacyMessage() {
        final SQLiteDatabase db = databaseBackend.getWritableDatabase();
        final ContentValues account = new ContentValues();
        account.put(Account.UUID, "account-id");
        db.insertOrThrow(Account.TABLENAME, null, account);

        final ContentValues conversation = new ContentValues();
        conversation.put(Conversation.UUID, "conversation-id");
        conversation.put(Conversation.ACCOUNT, "account-id");
        db.insertOrThrow(Conversation.TABLENAME, null, conversation);

        final ContentValues message = new ContentValues();
        message.put(Message.UUID, "legacy-message-id");
        message.put(Message.CONVERSATION, "conversation-id");
        message.put(Message.RELATIVE_FILE_PATH, "/legacy/attachment.jpg");
        db.insertOrThrow(Message.TABLENAME, null, message);

        db.execSQL("DROP TABLE secure_content");
        databaseBackend.onUpgrade(db, 55, 56);

        try (final Cursor table =
                        db.rawQuery(
                                "SELECT name FROM sqlite_master WHERE type='table' AND name='secure_content'",
                                null);
                final Cursor legacyMessage =
                        db.query(
                                Message.TABLENAME,
                                new String[] {Message.RELATIVE_FILE_PATH},
                                Message.UUID + "=?",
                                new String[] {"legacy-message-id"},
                                null,
                                null,
                                null)) {
            assertTrue(table.moveToFirst());
            assertTrue(legacyMessage.moveToFirst());
            assertEquals("/legacy/attachment.jpg", legacyMessage.getString(0));
        }
    }
}
