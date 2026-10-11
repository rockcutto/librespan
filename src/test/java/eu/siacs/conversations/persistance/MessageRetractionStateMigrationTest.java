package eu.siacs.conversations.persistance;

import static org.junit.Assert.*;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import eu.siacs.conversations.entities.Message;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MessageRetractionStateMigrationTest {

    private SQLiteDatabase oldDatabase() {
        final SQLiteDatabase db = SQLiteDatabase.create(null);
        db.execSQL(
                "CREATE TABLE messages("
                        + "uuid TEXT PRIMARY KEY,"
                        + "body TEXT,"
                        + "moderated INTEGER NOT NULL DEFAULT 0)");
        db.execSQL(
                "INSERT INTO messages(uuid,body,moderated)"
                        + " VALUES('original','keep this content',0)");
        return db;
    }

    private void migrate(SQLiteDatabase db) {
        for (final String sql :
                DatabaseBackendImpl.retractionMessageMigrationStatements()) {
            db.execSQL(sql);
        }
    }

    @Test
    public void migrationPreservesOriginalMessage() {
        final SQLiteDatabase db = oldDatabase();
        try {
            migrate(db);

            try (Cursor cursor = db.rawQuery(
                    "SELECT body,moderated,retracted,retractionRetired "
                            + "FROM messages WHERE uuid='original'",
                    null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals("keep this content", cursor.getString(0));
                assertEquals(0, cursor.getInt(1));
                assertEquals(0, cursor.getInt(2));
                assertEquals(0, cursor.getInt(3));
            }
        } finally {
            db.close();
        }
    }

    @Test
    public void retractionStateIsIndependentFromModeration() {
        final SQLiteDatabase db = oldDatabase();
        try {
            migrate(db);

            db.execSQL(
                    "UPDATE messages SET retracted=1 "
                            + "WHERE uuid='original'");

            try (Cursor cursor = db.rawQuery(
                    "SELECT body,moderated,retracted,retractionRetired "
                            + "FROM messages WHERE uuid='original'",
                    null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals("keep this content", cursor.getString(0));
                assertEquals(0, cursor.getInt(1));
                assertEquals(1, cursor.getInt(2));
                assertEquals(0, cursor.getInt(3));
            }
        } finally {
            db.close();
        }
    }

    @Test
    public void expectedColumnNamesAreStable() {
        assertEquals("retracted", Message.RETRACTED);
        assertEquals("retractionRetired",
                Message.RETRACTION_RETIRED);
    }
}
