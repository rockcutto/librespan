package eu.siacs.conversations.persistance;

import static org.junit.Assert.*;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MessageRetractionDatabaseMigrationTest {

    private static SQLiteDatabase database() {
        final SQLiteDatabase db = SQLiteDatabase.create(null);
        db.execSQL(
                "CREATE TABLE conversations(uuid TEXT PRIMARY KEY)");
        db.execSQL(
                "CREATE TABLE messages("
                        + "uuid TEXT PRIMARY KEY,"
                        + "conversationUuid TEXT,"
                        + "body TEXT)");
        db.execSQL(
                "INSERT INTO conversations(uuid) VALUES('room-a')");
        db.execSQL(
                "INSERT INTO conversations(uuid) VALUES('room-b')");
        db.execSQL(
                "INSERT INTO messages(uuid,conversationUuid,body)"
                        + " VALUES('msg-1','room-a','original body')");
        return db;
    }

    private static void migrate(SQLiteDatabase db) {
        for (final String statement :
                DatabaseBackendImpl.retractionMigrationStatements()) {
            db.execSQL(statement);
        }
    }

    private static void insert(
            SQLiteDatabase db,
            String account,
            String room,
            String request,
            String target) {
        final ContentValues values = new ContentValues();
        values.put("account_uuid", account);
        values.put("conversation_uuid", room);
        values.put("retraction_request_id", request);
        values.put("target_room_stanza_id", target);
        values.put("sender_full_jid",
                "room@conference.example/alice");
        values.put("sender_occupant_id", "occupant-alice");
        values.put("event_time", 100L);
        db.insertOrThrow("message_retraction", null, values);
    }

    @Test
    public void migrationPreservesExistingMessageAndIsIdempotent() {
        final SQLiteDatabase db = database();
        try {
            migrate(db);
            migrate(db);

            try (Cursor cursor = db.rawQuery(
                    "SELECT body FROM messages WHERE uuid='msg-1'",
                    null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals("original body", cursor.getString(0));
            }
        } finally {
            db.close();
        }
    }

    @Test
    public void pendingEventIsStoredWithoutOriginalMessage() {
        final SQLiteDatabase db = database();
        try {
            migrate(db);

            insert(db, "account-a", "room-a",
                    "request-1", "unknown-target");

            try (Cursor cursor = db.rawQuery(
                    "SELECT state, target_message_uuid "
                            + "FROM message_retraction "
                            + "WHERE retraction_request_id='request-1'",
                    null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals(0, cursor.getInt(0));
                assertTrue(cursor.isNull(1));
            }
        } finally {
            db.close();
        }
    }

    @Test
    public void sameTargetCanExistInDifferentRooms() {
        final SQLiteDatabase db = database();
        try {
            migrate(db);

            insert(db, "account-a", "room-a",
                    "request-a", "same-target");
            insert(db, "account-a", "room-b",
                    "request-b", "same-target");

            try (Cursor cursor = db.rawQuery(
                    "SELECT COUNT(*) FROM message_retraction",
                    null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals(2, cursor.getInt(0));
            }
        } finally {
            db.close();
        }
    }

    @Test
    public void duplicateRequestIdInSameRoomIsRejected() {
        final SQLiteDatabase db = database();
        try {
            migrate(db);

            insert(db, "account-a", "room-a",
                    "request-1", "target-a");

            assertThrows(SQLiteConstraintException.class,
                    () -> insert(db, "account-a", "room-a",
                            "request-1", "target-b"));
        } finally {
            db.close();
        }
    }

    @Test
    public void invalidJournalStateIsRejected() {
        final SQLiteDatabase db = database();
        try {
            migrate(db);

            insert(db, "account-a", "room-a",
                    "request-1", "target");

            assertThrows(SQLiteConstraintException.class,
                    () -> db.execSQL(
                            "UPDATE message_retraction "
                                    + "SET state=99 "
                                    + "WHERE retraction_request_id='request-1'"));
        } finally {
            db.close();
        }
    }
}
