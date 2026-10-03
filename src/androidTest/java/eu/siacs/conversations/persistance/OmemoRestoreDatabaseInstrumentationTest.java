package eu.siacs.conversations.persistance;

import static org.junit.Assert.assertEquals;
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

import eu.siacs.conversations.crypto.axolotl.SQLiteAxolotlStore;
import eu.siacs.conversations.entities.Account;

/**
 * Real SQLCipher coverage for the destructive half of OMEMO identity restore.
 *
 * <p>Restoring our long-lived identity must reset device-local ratchets/prekeys and the old local
 * identity while preserving peer identities and their trust metadata.
 */
@RunWith(AndroidJUnit4.class)
public class OmemoRestoreDatabaseInstrumentationTest {

    private static final String DATABASE_NAME = "history";
    private static final String ACCOUNT_A = "omemo-restore-account-a";
    private static final String ACCOUNT_B = "omemo-restore-account-b";

    private Context context;
    private DatabaseBackendImpl databaseBackend;
    private SQLiteDatabase db;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        System.loadLibrary("sqlcipher");
        context.deleteDatabase(DATABASE_NAME);
        databaseBackend = new DatabaseBackendImpl(context, "omemo-restore-test");
        db = databaseBackend.getWritableDatabase();
        insertAccount(ACCOUNT_A);
        insertAccount(ACCOUNT_B);
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
    public void restoreResetDeletesOwnDeviceStateButPreservesPeerTrustAndOtherAccount() {
        insertIdentity(ACCOUNT_A, "me@example.test", 1, "own-a", "VERIFIED", 1);
        insertIdentity(ACCOUNT_A, "peer@example.test", 0, "peer-a", "VERIFIED", 1);
        insertIdentity(ACCOUNT_B, "other@example.test", 1, "own-b", "TRUSTED", 1);
        insertIdentity(ACCOUNT_B, "peer@example.test", 0, "peer-b", "VERIFIED", 1);

        insertSession(ACCOUNT_A, "peer@example.test", 23);
        insertPreKey(ACCOUNT_A, 7);
        insertSignedPreKey(ACCOUNT_A, 11);

        insertSession(ACCOUNT_B, "peer@example.test", 99);
        insertPreKey(ACCOUNT_B, 17);
        insertSignedPreKey(ACCOUNT_B, 21);

        DatabaseBackendImpl.resetOwnAxolotlRowsForRestore(db, ACCOUNT_A);

        assertEquals(0, count(SQLiteAxolotlStore.SESSION_TABLENAME, ACCOUNT_A, null));
        assertEquals(0, count(SQLiteAxolotlStore.PREKEY_TABLENAME, ACCOUNT_A, null));
        assertEquals(0, count(SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME, ACCOUNT_A, null));
        assertEquals(
                0,
                count(
                        SQLiteAxolotlStore.IDENTITIES_TABLENAME,
                        ACCOUNT_A,
                        SQLiteAxolotlStore.OWN + "=1"));

        assertPeerIdentityPreserved(ACCOUNT_A, "peer-a", "VERIFIED", 1);

        assertEquals(1, count(SQLiteAxolotlStore.SESSION_TABLENAME, ACCOUNT_B, null));
        assertEquals(1, count(SQLiteAxolotlStore.PREKEY_TABLENAME, ACCOUNT_B, null));
        assertEquals(1, count(SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME, ACCOUNT_B, null));
        assertEquals(2, count(SQLiteAxolotlStore.IDENTITIES_TABLENAME, ACCOUNT_B, null));
    }

    private void insertAccount(final String uuid) {
        final ContentValues values = new ContentValues();
        values.put(Account.UUID, uuid);
        db.insertOrThrow(Account.TABLENAME, null, values);
    }

    private void insertIdentity(
            final String account,
            final String name,
            final int own,
            final String fingerprint,
            final String trust,
            final int active) {
        final ContentValues values = new ContentValues();
        values.put(SQLiteAxolotlStore.ACCOUNT, account);
        values.put(SQLiteAxolotlStore.NAME, name);
        values.put(SQLiteAxolotlStore.OWN, own);
        values.put(SQLiteAxolotlStore.FINGERPRINT, fingerprint);
        values.put(SQLiteAxolotlStore.TRUST, trust);
        values.put(SQLiteAxolotlStore.ACTIVE, active);
        values.put(SQLiteAxolotlStore.LAST_ACTIVATION, 123L);
        values.put(SQLiteAxolotlStore.KEY, "identity-key-" + fingerprint);
        db.insertOrThrow(SQLiteAxolotlStore.IDENTITIES_TABLENAME, null, values);
    }

    private void insertSession(final String account, final String name, final int deviceId) {
        final ContentValues values = new ContentValues();
        values.put(SQLiteAxolotlStore.ACCOUNT, account);
        values.put(SQLiteAxolotlStore.NAME, name);
        values.put(SQLiteAxolotlStore.DEVICE_ID, deviceId);
        values.put(SQLiteAxolotlStore.KEY, "session-" + deviceId);
        db.insertOrThrow(SQLiteAxolotlStore.SESSION_TABLENAME, null, values);
    }

    private void insertPreKey(final String account, final int id) {
        insertKeyRow(SQLiteAxolotlStore.PREKEY_TABLENAME, account, id, "prekey-");
    }

    private void insertSignedPreKey(final String account, final int id) {
        insertKeyRow(SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME, account, id, "signed-");
    }

    private void insertKeyRow(
            final String table, final String account, final int id, final String prefix) {
        final ContentValues values = new ContentValues();
        values.put(SQLiteAxolotlStore.ACCOUNT, account);
        values.put(SQLiteAxolotlStore.ID, id);
        values.put(SQLiteAxolotlStore.KEY, prefix + id);
        db.insertOrThrow(table, null, values);
    }

    private int count(final String table, final String account, final String extraSelection) {
        final String selection =
                SQLiteAxolotlStore.ACCOUNT
                        + "=?"
                        + (extraSelection == null ? "" : " AND " + extraSelection);
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT COUNT(*) FROM " + table + " WHERE " + selection,
                        new String[] {account})) {
            assertTrue(cursor.moveToFirst());
            return cursor.getInt(0);
        }
    }

    private void assertPeerIdentityPreserved(
            final String account,
            final String fingerprint,
            final String expectedTrust,
            final int expectedActive) {
        try (Cursor cursor =
                db.query(
                        SQLiteAxolotlStore.IDENTITIES_TABLENAME,
                        new String[] {
                            SQLiteAxolotlStore.OWN,
                            SQLiteAxolotlStore.TRUST,
                            SQLiteAxolotlStore.ACTIVE
                        },
                        SQLiteAxolotlStore.ACCOUNT
                                + "=? AND "
                                + SQLiteAxolotlStore.FINGERPRINT
                                + "=?",
                        new String[] {account, fingerprint},
                        null,
                        null,
                        null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(0, cursor.getInt(0));
            assertEquals(expectedTrust, cursor.getString(1));
            assertEquals(expectedActive, cursor.getInt(2));
        }
    }
}
