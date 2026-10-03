package eu.siacs.conversations.persistance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import eu.siacs.conversations.entities.Message;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MucModerationDatabaseMigrationTest {

    @Test
    public void version62MigrationPreservesRowsAndAddsModerationSchema() {
        final SQLiteDatabase db = SQLiteDatabase.create(null);
        try {
            db.execSQL("CREATE TABLE conversations(uuid TEXT PRIMARY KEY)");
            db.execSQL(
                    "CREATE TABLE messages("
                            + "uuid TEXT PRIMARY KEY,"
                            + "conversationUuid TEXT,"
                            + "body TEXT)");
            db.execSQL("INSERT INTO conversations(uuid) VALUES('conversation-a')");
            db.execSQL(
                    "INSERT INTO messages(uuid,conversationUuid,body) "
                            + "VALUES('message-a','conversation-a','legacy body')");

            for (final String statement : DatabaseBackendImpl.moderationMigrationStatements()) {
                db.execSQL(statement);
            }

            final Map<String, Column> columns = tableColumns(db, "messages");
            assertTrue(columns.containsKey(Message.ROOM_STANZA_ID));
            assertTrue(columns.containsKey(Message.MODERATED));
            assertTrue(columns.containsKey(Message.MODERATION_REASON));
            assertTrue(columns.containsKey(Message.MODERATED_BY));
            assertTrue(columns.containsKey(Message.MODERATED_AT));
            assertTrue(columns.containsKey(Message.MODERATION_RETIRED));
            assertEquals("0", columns.get(Message.MODERATED).defaultValue);
            assertEquals("0", columns.get(Message.MODERATED_AT).defaultValue);
            assertEquals("0", columns.get(Message.MODERATION_RETIRED).defaultValue);

            try (Cursor row =
                    db.rawQuery(
                            "SELECT body,"
                                    + Message.MODERATED
                                    + ","
                                    + Message.MODERATED_AT
                                    + ","
                                    + Message.MODERATION_RETIRED
                                    + " FROM messages WHERE uuid='message-a'",
                            null)) {
                assertTrue(row.moveToFirst());
                assertEquals("legacy body", row.getString(0));
                assertEquals(0, row.getInt(1));
                assertEquals(0L, row.getLong(2));
                assertEquals(0, row.getInt(3));
            }

            assertTrue(schemaObjectExists(db, "table", "message_moderation"));
            assertTrue(schemaObjectExists(db, "index", "message_room_stanza_index"));
        } finally {
            db.close();
        }
    }

    @Test
    public void moderationMarkerPrimaryKeyIsScopedByAccountConversationAndRoomId() {
        final SQLiteDatabase db = SQLiteDatabase.create(null);
        try {
            db.execSQL("CREATE TABLE conversations(uuid TEXT PRIMARY KEY)");
            db.execSQL(
                    "CREATE TABLE messages("
                            + "uuid TEXT PRIMARY KEY,"
                            + "conversationUuid TEXT,"
                            + "body TEXT)");
            db.execSQL("INSERT INTO conversations(uuid) VALUES('conversation-a')");
            db.execSQL("INSERT INTO conversations(uuid) VALUES('conversation-b')");
            for (final String statement : DatabaseBackendImpl.moderationMigrationStatements()) {
                db.execSQL(statement);
            }

            insertMarker(db, "account-a", "conversation-a", "same-id");
            insertMarker(db, "account-a", "conversation-b", "same-id");
            insertMarker(db, "account-b", "conversation-a", "same-id");

            try (Cursor cursor =
                    db.rawQuery(
                            "SELECT COUNT(*) FROM message_moderation WHERE room_stanza_id='same-id'",
                            null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals(3, cursor.getInt(0));
            }

            boolean duplicateRejected = false;
            try {
                insertMarker(db, "account-a", "conversation-a", "same-id");
            } catch (android.database.sqlite.SQLiteConstraintException expected) {
                duplicateRejected = true;
            }
            assertTrue(duplicateRejected);
        } finally {
            db.close();
        }
    }

    private static void insertMarker(
            final SQLiteDatabase db,
            final String accountUuid,
            final String conversationUuid,
            final String roomStanzaId) {
        final ContentValues values = new ContentValues();
        values.put("account_uuid", accountUuid);
        values.put("conversation_uuid", conversationUuid);
        values.put("room_stanza_id", roomStanzaId);
        values.put("moderated_at", 1L);
        db.insertOrThrow("message_moderation", null, values);
    }

    private static boolean schemaObjectExists(
            final SQLiteDatabase db, final String type, final String name) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT 1 FROM sqlite_master WHERE type=? AND name=?",
                        new String[] {type, name})) {
            return cursor.moveToFirst();
        }
    }

    private static Map<String, Column> tableColumns(
            final SQLiteDatabase db, final String tableName) {
        final Map<String, Column> columns = new HashMap<>();
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + tableName + ")", null)) {
            final int name = cursor.getColumnIndexOrThrow("name");
            final int defaultValue = cursor.getColumnIndexOrThrow("dflt_value");
            while (cursor.moveToNext()) {
                columns.put(
                        cursor.getString(name),
                        new Column(
                                cursor.isNull(defaultValue)
                                        ? null
                                        : cursor.getString(defaultValue)));
            }
        }
        return columns;
    }

    private static final class Column {
        final String defaultValue;

        Column(final String defaultValue) {
            this.defaultValue = defaultValue;
        }
    }
}
