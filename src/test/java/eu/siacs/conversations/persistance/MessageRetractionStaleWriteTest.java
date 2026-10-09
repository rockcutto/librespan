package eu.siacs.conversations.persistance;

import static org.junit.Assert.*;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import eu.siacs.conversations.entities.Message;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MessageRetractionStaleWriteTest {

    private SQLiteDatabase database() {
        final SQLiteDatabase db = SQLiteDatabase.create(null);

        db.execSQL(
                "CREATE TABLE messages("
                        + "uuid TEXT PRIMARY KEY,"
                        + "body TEXT,"
                        + "retracted INTEGER NOT NULL DEFAULT 0)");

        db.execSQL(
                "INSERT INTO messages(uuid,body,retracted)"
                        + " VALUES('retired','',1)");

        db.execSQL(
                "INSERT INTO messages(uuid,body,retracted)"
                        + " VALUES('active','old',0)");

        return db;
    }

    @Test
    public void staleObjectCannotRestoreRetractedBody() {
        final SQLiteDatabase db = database();

        try {
            final ContentValues stale = new ContentValues();
            stale.put(Message.BODY, "secret plaintext");
            stale.put(Message.RETRACTED, 0);

            final int updated = db.update(
                    Message.TABLENAME,
                    stale,
                    DatabaseBackendImpl.activeMessageUpdateSelection(),
                    new String[] {"retired"});

            assertEquals(0, updated);

            try (Cursor cursor = db.rawQuery(
                    "SELECT body,retracted "
                            + "FROM messages WHERE uuid='retired'",
                    null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals("", cursor.getString(0));
                assertEquals(1, cursor.getInt(1));
            }
        } finally {
            db.close();
        }
    }

    @Test
    public void activeMessagesCanStillBeUpdated() {
        final SQLiteDatabase db = database();

        try {
            final ContentValues values = new ContentValues();
            values.put(Message.BODY, "new");

            assertEquals(1, db.update(
                    Message.TABLENAME,
                    values,
                    DatabaseBackendImpl.activeMessageUpdateSelection(),
                    new String[] {"active"}));

            try (Cursor cursor = db.rawQuery(
                    "SELECT body FROM messages WHERE uuid='active'",
                    null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals("new", cursor.getString(0));
            }
        } finally {
            db.close();
        }
    }

    @Test
    public void updateSelectionRequiresNonRetractedRow() {
        assertEquals(
                Message.UUID + "=? AND "
                        + Message.RETRACTED + "=0",
                DatabaseBackendImpl.activeMessageUpdateSelection());
    }
}
