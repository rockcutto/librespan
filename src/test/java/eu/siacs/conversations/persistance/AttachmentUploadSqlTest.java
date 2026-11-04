package eu.siacs.conversations.persistance;

import static org.junit.Assert.*;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import eu.siacs.conversations.entities.Message;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Executes the exact production UPDATE predicate with SQLite, complementing device SQLCipher tests. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class AttachmentUploadSqlTest {
    @Test public void canonicalMediaParamsAndFragmentsSurviveCloseAndReopen() throws Exception {
        for (String params : new String[] {"|42", "|42|640|480", "|42|1920|1080", "|42|0|0|900"}) {
            File file = File.createTempFile("attachment-sql", ".db");
            try {
                SQLiteDatabase db = schema(file);
                db.execSQL("UPDATE messages SET body=? WHERE uuid='m'", new Object[] {params});
                String url = "aesgcm://upload.example.test/media#" + "ab".repeat(44);
                assertTrue(AttachmentUploadBody.isComplete(url + params, url));
                assertEquals(1, publish(db, params, url + params, "a"));
                // Status-only updates cannot overwrite the BODY committed above.
                ContentValues status = new ContentValues();
                status.put(Message.STATUS, Message.STATUS_SEND_RECEIVED);
                assertEquals(1, db.update(Message.TABLENAME, status, "uuid=?", new String[] {"m"}));
                db.close();
                try (SQLiteDatabase reopened = SQLiteDatabase.openDatabase(file.getPath(), null,
                        SQLiteDatabase.OPEN_READONLY)) {
                    assertEquals(url + params, body(reopened));
                }
            } finally {
                Files.deleteIfExists(file.toPath());
                Files.deleteIfExists(new File(file + "-journal").toPath());
            }
        }
    }

    @Test public void competingOrDuplicateCallbackCannotReplaceCommittedUrlOrStatus() throws Exception {
        try (SQLiteDatabase db = schema(null)) {
            assertEquals(1, publish(db, "|42", "https://upload.example.test/first|42", "a"));
            assertEquals(0, publish(db, "|42", "https://upload.example.test/second|42", "a"));
            assertEquals(0, publish(db, "|42", "https://upload.example.test/first|42", "a"));
            assertEquals("https://upload.example.test/first|42", body(db));
            try (Cursor c = db.rawQuery("SELECT status FROM messages WHERE uuid='m'", null)) {
                assertTrue(c.moveToFirst());
                assertEquals(Message.STATUS_UNSEND, c.getInt(0));
            }
        }
    }

    @Test public void protectedModeTombstonesIncomingAndMissingRowsRejectStalePublication() throws Exception {
        for (String mutation : new String[] {
                "UPDATE messages SET moderated=1", "UPDATE messages SET retracted=1",
                "UPDATE messages SET deleted=1", "UPDATE messages SET status=0",
                "UPDATE messages SET type=0", "DELETE FROM messages",
                "UPDATE conversations SET accountUuid='other'",
                "INSERT INTO " + constant("SECURE_MESSAGE_PAYLOAD_MODE_TABLENAME") + "("
                        + constant("SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID") + ","
                        + constant("SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID") + ") VALUES('a','m')"}) {
            try (SQLiteDatabase db = schema(null)) {
                db.execSQL(mutation);
                assertEquals(mutation, 0, publish(db, "|42", "https://upload.example.test/stale|42", "a"));
            }
        }
    }

    @Test public void onlyEligibleAttachmentTypesAndOwnedConversationMatch() throws Exception {
        for (int type : new int[] {Message.TYPE_IMAGE, Message.TYPE_FILE, Message.TYPE_PRIVATE_FILE}) {
            try (SQLiteDatabase db = schema(null)) {
                db.execSQL("UPDATE messages SET type=?", new Object[] {type});
                assertEquals(0, publish(db, "|42", "https://upload.example.test/f|42", "wrong"));
                assertEquals(1, publish(db, "|42", "https://upload.example.test/f|42", "a"));
            }
        }
    }

    private static SQLiteDatabase schema(File file) throws Exception {
        SQLiteDatabase db = file == null ? SQLiteDatabase.create(null)
                : SQLiteDatabase.openOrCreateDatabase(file, null);
        db.execSQL("CREATE TABLE conversations(uuid TEXT PRIMARY KEY, accountUuid TEXT)");
        db.execSQL("INSERT INTO conversations VALUES('c','a')");
        db.execSQL("CREATE TABLE messages(uuid TEXT PRIMARY KEY, conversationUuid TEXT, body TEXT,"
                + "type INTEGER, status INTEGER, moderated INTEGER DEFAULT 0,"
                + "retracted INTEGER DEFAULT 0, deleted INTEGER DEFAULT 0)");
        db.execSQL("INSERT INTO messages(uuid,conversationUuid,body,type,status) VALUES('m','c','|42',2,1)");
        db.execSQL("CREATE TABLE " + constant("SECURE_MESSAGE_PAYLOAD_MODE_TABLENAME") + "("
                + constant("SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID") + " TEXT,"
                + constant("SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID") + " TEXT)");
        return db;
    }

    private static int publish(SQLiteDatabase db, String pending, String complete, String account) {
        ContentValues values = new ContentValues();
        values.put(Message.BODY, complete);
        return db.update(Message.TABLENAME, values, DatabaseBackendImpl.outgoingAttachmentUploadSelection(),
                new String[] {"m", "c", pending, account, account});
    }
    private static String body(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT body FROM messages WHERE uuid='m'", null)) {
            assertTrue(c.moveToFirst());
            return c.getString(0);
        }
    }
    private static String constant(String name) throws Exception {
        Field f = DatabaseBackendImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        return (String) f.get(null);
    }
}
