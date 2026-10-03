package eu.siacs.conversations.persistance;

import android.content.Context;

import java.io.File;

import eu.siacs.conversations.storage.secure.SecureRetiringDatabaseBackendProxy;

public class DatabaseBackendProvider {
    private static final String DATABASE_NAME = "history";

    private static DatabaseBackend instance = null;

    public static synchronized DatabaseBackend getInstance(Context context) {
        if (instance != null) {
            return instance;
        }

        final Context applicationContext = context.getApplicationContext();
        final File dbFile = applicationContext.getDatabasePath(DATABASE_NAME);
        final DatabaseMasterKeyV1.Record managedRecord =
                DatabaseMasterKeyV1.read(applicationContext);

        if (managedRecord == null) {
            if (dbFile.exists()) {
                throw new IllegalStateException(
                        "database exists without managed database master key");
            }

            final DatabaseMasterKeyV1.Record prepared =
                    DatabaseMasterKeyV1.createPrepared(applicationContext);
            final DatabaseBackendImpl backend =
                    new DatabaseBackendImpl(applicationContext, prepared.passphrase);
            backend.getWritableDatabase();
            if (!DatabaseMasterKeyV1.markCommitted(applicationContext)) {
                throw new IllegalStateException(
                        "unable to commit initial database master key");
            }
            instance =
                    SecureRetiringDatabaseBackendProxy.wrap(
                            applicationContext, backend);
            return instance;
        }

        final DatabaseBackendImpl backend =
                new DatabaseBackendImpl(applicationContext, managedRecord.passphrase);

        // Always open the real writable backend. This is required for SQLite crash recovery
        // (for example a hot rollback journal) and preserves the original SQLCipher exception
        // if the managed key or database is actually unusable.
        backend.getWritableDatabase();

        if (managedRecord.phase == DatabaseMasterKeyV1.Phase.PREPARED
                && !DatabaseMasterKeyV1.markCommitted(applicationContext)) {
            throw new IllegalStateException(
                    "unable to commit prepared database master key");
        }

        instance =
                SecureRetiringDatabaseBackendProxy.wrap(
                        applicationContext, backend);
        return instance;
    }
}
