package eu.siacs.conversations.persistance;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import net.zetetic.database.sqlcipher.SQLiteConnection;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook;
import net.zetetic.database.sqlcipher.SQLiteOpenHelper;

import android.os.Environment;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.common.base.Stopwatch;

import org.json.JSONException;
import org.json.JSONObject;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.crypto.axolotl.SQLiteAxolotlStore;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.IndividualMessage;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.PresenceTemplate;
import eu.siacs.conversations.entities.Roster;
import eu.siacs.conversations.entities.ServiceDiscoveryResult;
import eu.siacs.conversations.storage.secure.AccountSecretPersistencePolicyV1;
import eu.siacs.conversations.storage.secure.AccountSecretRuntimePersistenceV1;
import eu.siacs.conversations.storage.secure.LegacyPlaintextMessageRecord;
import eu.siacs.conversations.storage.secure.LegacyPlaintextSqliteCleanupResult;
import eu.siacs.conversations.storage.secure.MessagePayloadClassification;
import eu.siacs.conversations.storage.secure.SecureContentMetadata;
import eu.siacs.conversations.storage.secure.SecureContentMetadataRecord;
import eu.siacs.conversations.storage.secure.SecureContentState;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadPublicationPhase;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadPublicationRecord;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadRetirementPhase;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadRetirementRecord;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadContext;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadMode;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadReference;
import eu.siacs.conversations.storage.secure.ScopedAccountSecretVaultV1;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.services.ShortcutService;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.CursorUtils;
import eu.siacs.conversations.utils.FtsUtils;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.utils.Resolver;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.MessageRetractionPolicy;
import eu.siacs.conversations.xmpp.mam.MamReference;

import org.jxmpp.jid.parts.Localpart;
import org.jxmpp.stringprep.XmppStringprepException;

class DatabaseBackendImpl extends SQLiteOpenHelper implements DatabaseBackend {

    private final Context applicationContext;

    private static final String DATABASE_NAME = "history";
    private static final int DATABASE_VERSION = 64;
    private static final int MAX_LEGACY_PLAINTEXT_MIGRATION_BATCH = 50;

    private static final String MODERATION_TABLENAME = "message_moderation";
    private static final String CREATE_MODERATION_TABLE =
            "CREATE TABLE IF NOT EXISTS " + MODERATION_TABLENAME
                    + " (account_uuid TEXT NOT NULL, conversation_uuid TEXT NOT NULL,"
                    + " room_stanza_id TEXT NOT NULL, moderated_by TEXT, reason TEXT,"
                    + " moderated_at INTEGER NOT NULL,"
                    + " PRIMARY KEY(account_uuid, conversation_uuid, room_stanza_id),"
                    + " FOREIGN KEY(conversation_uuid) REFERENCES " + Conversation.TABLENAME
                    + "(" + Conversation.UUID + ") ON DELETE CASCADE)";
    private static final String CREATE_ROOM_STANZA_INDEX =
            "CREATE INDEX IF NOT EXISTS message_room_stanza_index ON " + Message.TABLENAME
                    + "(" + Message.CONVERSATION + ", " + Message.ROOM_STANZA_ID + ")";

    static List<String> moderationMigrationStatements() {
        return Arrays.asList(
                "ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.ROOM_STANZA_ID + " TEXT",
                "ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.MODERATED
                        + " INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.MODERATION_REASON + " TEXT",
                "ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.MODERATED_BY + " TEXT",
                "ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.MODERATED_AT
                        + " INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.MODERATION_RETIRED
                        + " INTEGER NOT NULL DEFAULT 0",
                CREATE_ROOM_STANZA_INDEX,
                CREATE_MODERATION_TABLE);
    }

    // XEP-0424 journal. No message bodies or media locations.
    // State: 0=UNVERIFIED, 1=VERIFIED,
    //        2=RETIRE_PENDING, 3=RETIRED.
    private static final String RETRACTION_TABLENAME =
            "message_retraction";

    private static final String CREATE_RETRACTION_TABLE =
            "CREATE TABLE IF NOT EXISTS " + RETRACTION_TABLENAME
                    + " (account_uuid TEXT NOT NULL,"
                    + " conversation_uuid TEXT NOT NULL,"
                    + " retraction_request_id TEXT NOT NULL,"
                    + " target_room_stanza_id TEXT NOT NULL,"
                    + " target_message_uuid TEXT,"
                    + " sender_full_jid TEXT NOT NULL,"
                    + " sender_occupant_id TEXT,"
                    + " event_time INTEGER NOT NULL,"
                    + " state INTEGER NOT NULL DEFAULT 0"
                    + " CHECK(state IN (0,1,2,3)),"
                    + " PRIMARY KEY(account_uuid, conversation_uuid,"
                    + " retraction_request_id),"
                    + " FOREIGN KEY(conversation_uuid) REFERENCES "
                    + Conversation.TABLENAME + "(" + Conversation.UUID
                    + ") ON DELETE CASCADE)";

    private static final String CREATE_RETRACTION_TARGET_INDEX =
            "CREATE INDEX IF NOT EXISTS message_retraction_target_idx ON "
                    + RETRACTION_TABLENAME
                    + "(account_uuid, conversation_uuid,"
                    + " target_room_stanza_id)";

    static List<String> retractionMigrationStatements() {
        return Arrays.asList(
                CREATE_RETRACTION_TABLE,
                CREATE_RETRACTION_TARGET_INDEX);
    }

    static List<String> retractionMessageMigrationStatements() {
        return Arrays.asList(
                "ALTER TABLE " + Message.TABLENAME
                        + " ADD COLUMN " + Message.RETRACTED
                        + " INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE " + Message.TABLENAME
                        + " ADD COLUMN " + Message.RETRACTION_RETIRED
                        + " INTEGER NOT NULL DEFAULT 0");
    }

    private static final String SECURE_CONTENT_TABLENAME = "secure_content";
    private static final String SECURE_CONTENT_ID = "content_id";
    private static final String SECURE_CONTENT_MESSAGE_UUID = "message_uuid";
    private static final String SECURE_CONTENT_MIME_TYPE = "mime_type";
    private static final String SECURE_CONTENT_SIZE_BYTES = "size_bytes";
    private static final String SECURE_CONTENT_STATE = "state";
    private static final String SECURE_CONTENT_STORAGE_LOCATOR = "storage_locator";
    private static final String SECURE_CONTENT_CRYPTO_VERSION = "crypto_version";
    private static final String SECURE_CONTENT_CREATED_AT = "created_at";
    private static final String SECURE_CONTENT_UPDATED_AT = "updated_at";

    private static final String SECURE_MESSAGE_PAYLOAD_REFERENCE_TABLENAME =
            "secure_message_payload_reference";
    private static final String SECURE_MESSAGE_PAYLOAD_PUBLICATION_TABLENAME =
            "secure_message_payload_publication";
    private static final String SECURE_MESSAGE_PAYLOAD_RETIREMENT_TABLENAME =
            "secure_message_payload_retirement";
    private static final String SECURE_MESSAGE_PAYLOAD_MODE_TABLENAME =
            "secure_message_payload_mode";
    private static final String SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID = "account_uuid";
    private static final String SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID = "message_uuid";
    private static final String SECURE_MESSAGE_PAYLOAD_CONTENT_ID = "content_id";
    private static final String SECURE_MESSAGE_PAYLOAD_PREVIOUS_CONTENT_ID =
            "previous_content_id";
    private static final String SECURE_MESSAGE_PAYLOAD_PUBLICATION_ID = "publication_id";
    private static final String SECURE_MESSAGE_PAYLOAD_RETIREMENT_ID = "retirement_id";
    private static final String SECURE_MESSAGE_PAYLOAD_NAMESPACE = "namespace";
    private static final String SECURE_MESSAGE_PAYLOAD_PHASE = "phase";
    private static final String SECURE_MESSAGE_PAYLOAD_MODE = "mode";
    private static final String SECURE_MESSAGE_PAYLOAD_CREATED_AT = "created_at";
    private static final String SECURE_MESSAGE_PAYLOAD_UPDATED_AT = "updated_at";

    private static final String CREATE_SECURE_MESSAGE_PAYLOAD_REFERENCE_STATEMENT =
            "CREATE TABLE IF NOT EXISTS "
                    + SECURE_MESSAGE_PAYLOAD_REFERENCE_TABLENAME
                    + "("
                    + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_CONTENT_ID
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_CREATED_AT
                    + " NUMBER NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_UPDATED_AT
                    + " NUMBER NOT NULL, PRIMARY KEY("
                    + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                    + ", "
                    + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                    + "), UNIQUE("
                    + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                    + ", "
                    + SECURE_MESSAGE_PAYLOAD_CONTENT_ID
                    + "), FOREIGN KEY("
                    + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                    + ") REFERENCES "
                    + Message.TABLENAME
                    + "("
                    + Message.UUID
                    + ") ON DELETE CASCADE);";

    private static final String CREATE_SECURE_MESSAGE_PAYLOAD_PUBLICATION_STATEMENT =
            "CREATE TABLE IF NOT EXISTS "
                    + SECURE_MESSAGE_PAYLOAD_PUBLICATION_TABLENAME
                    + "("
                    + SECURE_MESSAGE_PAYLOAD_PUBLICATION_ID
                    + " TEXT PRIMARY KEY, "
                    + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_CONTENT_ID
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_PREVIOUS_CONTENT_ID
                    + " TEXT, "
                    + SECURE_MESSAGE_PAYLOAD_NAMESPACE
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_PHASE
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_CREATED_AT
                    + " NUMBER NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_UPDATED_AT
                    + " NUMBER NOT NULL);";

    // Frozen v57 schema. Do not add later columns here: onUpgrade must replay the historical
    // schema first and let later versioned migrations evolve it deterministically.
    private static final String CREATE_SECURE_MESSAGE_PAYLOAD_PUBLICATION_V57_STATEMENT =
            "CREATE TABLE IF NOT EXISTS "
                    + SECURE_MESSAGE_PAYLOAD_PUBLICATION_TABLENAME
                    + "("
                    + SECURE_MESSAGE_PAYLOAD_PUBLICATION_ID
                    + " TEXT PRIMARY KEY, "
                    + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_CONTENT_ID
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_NAMESPACE
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_PHASE
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_CREATED_AT
                    + " NUMBER NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_UPDATED_AT
                    + " NUMBER NOT NULL);";

    private static final String CREATE_SECURE_MESSAGE_PAYLOAD_RETIREMENT_STATEMENT =
            "CREATE TABLE IF NOT EXISTS "
                    + SECURE_MESSAGE_PAYLOAD_RETIREMENT_TABLENAME
                    + "("
                    + SECURE_MESSAGE_PAYLOAD_RETIREMENT_ID
                    + " TEXT PRIMARY KEY, "
                    + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_CONTENT_ID
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_NAMESPACE
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_PHASE
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_CREATED_AT
                    + " NUMBER NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_UPDATED_AT
                    + " NUMBER NOT NULL);";

    private static final String CREATE_SECURE_MESSAGE_PAYLOAD_MODE_STATEMENT =
            "CREATE TABLE IF NOT EXISTS "
                    + SECURE_MESSAGE_PAYLOAD_MODE_TABLENAME
                    + "("
                    + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_MODE
                    + " TEXT NOT NULL, "
                    + SECURE_MESSAGE_PAYLOAD_UPDATED_AT
                    + " NUMBER NOT NULL, PRIMARY KEY("
                    + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                    + ", "
                    + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                    + "), FOREIGN KEY("
                    + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                    + ") REFERENCES "
                    + Message.TABLENAME
                    + "("
                    + Message.UUID
                    + ") ON DELETE CASCADE);";

    private static final String CREATE_SECURE_CONTENT_STATEMENT =
            "CREATE TABLE IF NOT EXISTS "
                    + SECURE_CONTENT_TABLENAME
                    + "("
                    + SECURE_CONTENT_ID
                    + " TEXT PRIMARY KEY, "
                    + SECURE_CONTENT_MESSAGE_UUID
                    + " TEXT UNIQUE, "
                    + SECURE_CONTENT_MIME_TYPE
                    + " TEXT, "
                    + SECURE_CONTENT_SIZE_BYTES
                    + " NUMBER, "
                    + SECURE_CONTENT_STATE
                    + " TEXT NOT NULL, "
                    + SECURE_CONTENT_STORAGE_LOCATOR
                    + " TEXT, "
                    + SECURE_CONTENT_CRYPTO_VERSION
                    + " NUMBER, "
                    + SECURE_CONTENT_CREATED_AT
                    + " NUMBER NOT NULL, "
                    + SECURE_CONTENT_UPDATED_AT
                    + " NUMBER NOT NULL);";

    private static boolean requiresMessageIndexRebuild = false;
    private static final String CREATE_CONTATCS_STATEMENT =
            "create table "
                    + Contact.TABLENAME
                    + "("
                    + Contact.ACCOUNT
                    + " TEXT, "
                    + Contact.SERVERNAME
                    + " TEXT, "
                    + Contact.SYSTEMNAME
                    + " TEXT,"
                    + Contact.PRESENCE_NAME
                    + " TEXT,"
                    + Contact.JID
                    + " TEXT,"
                    + Contact.KEYS
                    + " TEXT,"
                    + Contact.PHOTOURI
                    + " TEXT,"
                    + Contact.OPTIONS
                    + " NUMBER,"
                    + Contact.SYSTEMACCOUNT
                    + " NUMBER, "
                    + Contact.AVATAR
                    + " TEXT, "
                    + Contact.LAST_PRESENCE
                    + " TEXT, "
                    + Contact.LAST_TIME
                    + " NUMBER, "
                    + Contact.RTP_CAPABILITY
                    + " TEXT,"
                    + Contact.GROUPS
                    + " TEXT, FOREIGN KEY("
                    + Contact.ACCOUNT
                    + ") REFERENCES "
                    + Account.TABLENAME
                    + "("
                    + Account.UUID
                    + ") ON DELETE CASCADE, UNIQUE("
                    + Contact.ACCOUNT
                    + ", "
                    + Contact.JID
                    + ") ON CONFLICT REPLACE);";

    private static final String CREATE_DISCOVERY_RESULTS_STATEMENT =
            "create table "
                    + ServiceDiscoveryResult.TABLENAME
                    + "("
                    + ServiceDiscoveryResult.HASH
                    + " TEXT, "
                    + ServiceDiscoveryResult.VER
                    + " TEXT, "
                    + ServiceDiscoveryResult.RESULT
                    + " TEXT, "
                    + "UNIQUE("
                    + ServiceDiscoveryResult.HASH
                    + ", "
                    + ServiceDiscoveryResult.VER
                    + ") ON CONFLICT REPLACE);";

    private static final String CREATE_PRESENCE_TEMPLATES_STATEMENT =
            "CREATE TABLE "
                    + PresenceTemplate.TABELNAME
                    + "("
                    + PresenceTemplate.UUID
                    + " TEXT, "
                    + PresenceTemplate.LAST_USED
                    + " NUMBER,"
                    + PresenceTemplate.MESSAGE
                    + " TEXT,"
                    + PresenceTemplate.STATUS
                    + " TEXT,"
                    + "UNIQUE("
                    + PresenceTemplate.MESSAGE
                    + ","
                    + PresenceTemplate.STATUS
                    + ") ON CONFLICT REPLACE);";

    private static final String CREATE_PREKEYS_STATEMENT =
            "CREATE TABLE "
                    + SQLiteAxolotlStore.PREKEY_TABLENAME
                    + "("
                    + SQLiteAxolotlStore.ACCOUNT
                    + " TEXT,  "
                    + SQLiteAxolotlStore.ID
                    + " INTEGER, "
                    + SQLiteAxolotlStore.KEY
                    + " TEXT, FOREIGN KEY("
                    + SQLiteAxolotlStore.ACCOUNT
                    + ") REFERENCES "
                    + Account.TABLENAME
                    + "("
                    + Account.UUID
                    + ") ON DELETE CASCADE, "
                    + "UNIQUE( "
                    + SQLiteAxolotlStore.ACCOUNT
                    + ", "
                    + SQLiteAxolotlStore.ID
                    + ") ON CONFLICT REPLACE"
                    + ");";

    private static final String CREATE_SIGNED_PREKEYS_STATEMENT =
            "CREATE TABLE "
                    + SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME
                    + "("
                    + SQLiteAxolotlStore.ACCOUNT
                    + " TEXT,  "
                    + SQLiteAxolotlStore.ID
                    + " INTEGER, "
                    + SQLiteAxolotlStore.KEY
                    + " TEXT, FOREIGN KEY("
                    + SQLiteAxolotlStore.ACCOUNT
                    + ") REFERENCES "
                    + Account.TABLENAME
                    + "("
                    + Account.UUID
                    + ") ON DELETE CASCADE, "
                    + "UNIQUE( "
                    + SQLiteAxolotlStore.ACCOUNT
                    + ", "
                    + SQLiteAxolotlStore.ID
                    + ") ON CONFLICT REPLACE"
                    + ");";

    private static final String CREATE_SESSIONS_STATEMENT =
            "CREATE TABLE "
                    + SQLiteAxolotlStore.SESSION_TABLENAME
                    + "("
                    + SQLiteAxolotlStore.ACCOUNT
                    + " TEXT,  "
                    + SQLiteAxolotlStore.NAME
                    + " TEXT, "
                    + SQLiteAxolotlStore.DEVICE_ID
                    + " INTEGER, "
                    + SQLiteAxolotlStore.KEY
                    + " TEXT, FOREIGN KEY("
                    + SQLiteAxolotlStore.ACCOUNT
                    + ") REFERENCES "
                    + Account.TABLENAME
                    + "("
                    + Account.UUID
                    + ") ON DELETE CASCADE, "
                    + "UNIQUE( "
                    + SQLiteAxolotlStore.ACCOUNT
                    + ", "
                    + SQLiteAxolotlStore.NAME
                    + ", "
                    + SQLiteAxolotlStore.DEVICE_ID
                    + ") ON CONFLICT REPLACE"
                    + ");";

    private static final String CREATE_IDENTITIES_STATEMENT =
            "CREATE TABLE "
                    + SQLiteAxolotlStore.IDENTITIES_TABLENAME
                    + "("
                    + SQLiteAxolotlStore.ACCOUNT
                    + " TEXT,  "
                    + SQLiteAxolotlStore.NAME
                    + " TEXT, "
                    + SQLiteAxolotlStore.OWN
                    + " INTEGER, "
                    + SQLiteAxolotlStore.FINGERPRINT
                    + " TEXT, "
                    + SQLiteAxolotlStore.CERTIFICATE
                    + " BLOB, "
                    + SQLiteAxolotlStore.TRUST
                    + " TEXT, "
                    + SQLiteAxolotlStore.ACTIVE
                    + " NUMBER, "
                    + SQLiteAxolotlStore.LAST_ACTIVATION
                    + " NUMBER,"
                    + SQLiteAxolotlStore.KEY
                    + " TEXT, FOREIGN KEY("
                    + SQLiteAxolotlStore.ACCOUNT
                    + ") REFERENCES "
                    + Account.TABLENAME
                    + "("
                    + Account.UUID
                    + ") ON DELETE CASCADE, "
                    + "UNIQUE( "
                    + SQLiteAxolotlStore.ACCOUNT
                    + ", "
                    + SQLiteAxolotlStore.NAME
                    + ", "
                    + SQLiteAxolotlStore.FINGERPRINT
                    + ") ON CONFLICT IGNORE"
                    + ");";

    private static final String RESOLVER_RESULTS_TABLENAME = "resolver_results";

    private static final String CREATE_RESOLVER_RESULTS_TABLE =
            "create table "
                    + RESOLVER_RESULTS_TABLENAME
                    + "("
                    + Resolver.Result.DOMAIN
                    + " TEXT,"
                    + Resolver.Result.HOSTNAME
                    + " TEXT,"
                    + Resolver.Result.IP
                    + " BLOB,"
                    + Resolver.Result.PRIORITY
                    + " NUMBER,"
                    + Resolver.Result.DIRECT_TLS
                    + " NUMBER,"
                    + Resolver.Result.AUTHENTICATED
                    + " NUMBER,"
                    + Resolver.Result.PORT
                    + " NUMBER,"
                    + "UNIQUE("
                    + Resolver.Result.DOMAIN
                    + ") ON CONFLICT REPLACE"
                    + ");";

    private static final String CREATE_MESSAGE_TIME_INDEX =
            "CREATE INDEX message_time_index ON "
                    + Message.TABLENAME
                    + "("
                    + Message.TIME_SENT
                    + ")";
    private static final String CREATE_MESSAGE_CONVERSATION_INDEX =
            "CREATE INDEX message_conversation_index ON "
                    + Message.TABLENAME
                    + "("
                    + Message.CONVERSATION
                    + ")";
    private static final String CREATE_MESSAGE_DELETED_INDEX =
            "CREATE INDEX message_deleted_index ON "
                    + Message.TABLENAME
                    + "("
                    + Message.DELETED
                    + ")";
    private static final String CREATE_MESSAGE_RELATIVE_FILE_PATH_INDEX =
            "CREATE INDEX message_file_path_index ON "
                    + Message.TABLENAME
                    + "("
                    + Message.RELATIVE_FILE_PATH
                    + ")";
    private static final String CREATE_MESSAGE_TYPE_INDEX =
            "CREATE INDEX message_type_index ON " + Message.TABLENAME + "(" + Message.TYPE + ")";

    private static final String CREATE_MESSAGE_INDEX_TABLE =
            "CREATE VIRTUAL TABLE messages_index USING fts4"
                    + " (uuid,body,notindexed=\"uuid\",content=\""
                    + Message.TABLENAME
                    + "\",tokenize='unicode61')";
    private static final String CREATE_MESSAGE_INSERT_TRIGGER =
            "CREATE TRIGGER after_message_insert AFTER INSERT ON "
                    + Message.TABLENAME
                    + " BEGIN INSERT INTO messages_index(rowid,uuid,body)"
                    + " VALUES(NEW.rowid,NEW.uuid,NEW.body); END;";
    private static final String CREATE_MESSAGE_UPDATE_TRIGGER =
            "CREATE TRIGGER after_message_update UPDATE OF uuid,body ON "
                    + Message.TABLENAME
                    + " BEGIN UPDATE messages_index SET body=NEW.body,uuid=NEW.uuid WHERE"
                    + " rowid=OLD.rowid; END;";
    private static final String CREATE_MESSAGE_DELETE_TRIGGER =
            "CREATE TRIGGER after_message_delete AFTER DELETE ON "
                    + Message.TABLENAME
                    + " BEGIN DELETE FROM messages_index WHERE rowid=OLD.rowid; END;";
    private static final String COPY_PREEXISTING_ENTRIES =
            "INSERT INTO messages_index(messages_index) VALUES('rebuild');";


    private static final SQLiteDatabaseHook hook = new SQLiteDatabaseHook() {
        @Override
        public void preKey(SQLiteConnection connection) {

        }

        @Override
        public void postKey(SQLiteConnection connection) {
            connection.executeRaw("PRAGMA journal_mode = WAL;", null, null);
            connection.executeRaw("PRAGMA synchronous = NORMAL;", null, null);
        }
    };

    DatabaseBackendImpl(Context context, String password) {
        super(context, DATABASE_NAME, password.getBytes(StandardCharsets.UTF_8), null, DATABASE_VERSION, 0, null, hook, false);
        this.applicationContext = context.getApplicationContext();
    }

    private static ContentValues createFingerprintStatusContentValues(
            FingerprintStatus.Trust trust, boolean active) {
        ContentValues values = new ContentValues();
        values.put(SQLiteAxolotlStore.TRUST, trust.toString());
        values.put(SQLiteAxolotlStore.ACTIVE, active ? 1 : 0);
        return values;
    }

    @Override
    public  boolean requiresMessageIndexRebuild() {
        return requiresMessageIndexRebuild;
    }

    @Override
    public void rebuildMessagesIndex() {
        final SQLiteDatabase db = getWritableDatabase();
        final Stopwatch stopwatch = Stopwatch.createStarted();
        db.execSQL(COPY_PREEXISTING_ENTRIES);
        requiresMessageIndexRebuild = false;
        Log.d(Config.LOGTAG, "rebuilt message index in " + stopwatch.stop().toString());
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.execSQL("PRAGMA foreign_keys=ON");
        db.rawQuery("PRAGMA secure_delete=ON", null).close();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "create table "
                        + Account.TABLENAME
                        + "("
                        + Account.UUID
                        + " TEXT PRIMARY KEY,"
                        + Account.USERNAME
                        + " TEXT,"
                        + Account.SERVER
                        + " TEXT,"
                        + Account.PASSWORD
                        + " TEXT,"
                        + Account.DISPLAY_NAME
                        + " TEXT, "
                        + Account.STATUS
                        + " TEXT,"
                        + Account.STATUS_MESSAGE
                        + " TEXT,"
                        + Account.ROSTERVERSION
                        + " TEXT,"
                        + Account.OPTIONS
                        + " NUMBER, "
                        + Account.AVATAR
                        + " TEXT, "
                        + Account.KEYS
                        + " TEXT, "
                        + Account.HOSTNAME
                        + " TEXT, "
                        + Account.RESOURCE
                        + " TEXT,"
                        + Account.PINNED_MECHANISM
                        + " TEXT,"
                        + Account.PINNED_CHANNEL_BINDING
                        + " TEXT,"
                        + Account.FAST_MECHANISM
                        + " TEXT,"
                        + Account.FAST_TOKEN
                        + " TEXT,"
                        + Account.PORT
                        + " NUMBER DEFAULT 5222)");
        db.execSQL(
                "create table "
                        + Conversation.TABLENAME
                        + " ("
                        + Conversation.UUID
                        + " TEXT PRIMARY KEY, "
                        + Conversation.NAME
                        + " TEXT, "
                        + Conversation.CONTACT
                        + " TEXT, "
                        + Conversation.ACCOUNT
                        + " TEXT, "
                        + Conversation.CONTACTJID
                        + " TEXT, "
                        + Conversation.CREATED
                        + " NUMBER, "
                        + Conversation.STATUS
                        + " NUMBER, "
                        + Conversation.MODE
                        + " NUMBER, "
                        +  Conversation.NEXT_COUNTERPART
                        + " TEXT, "
                        + Conversation.ATTRIBUTES
                        + " TEXT, FOREIGN KEY("
                        + Conversation.ACCOUNT
                        + ") REFERENCES "
                        + Account.TABLENAME
                        + "("
                        + Account.UUID
                        + ") ON DELETE CASCADE);");
        db.execSQL(
                "create table "
                        + Message.TABLENAME
                        + "( "
                        + Message.UUID
                        + " TEXT PRIMARY KEY, "
                        + Message.CONVERSATION
                        + " TEXT, "
                        + Message.TIME_SENT
                        + " NUMBER, "
                        + Message.COUNTERPART
                        + " TEXT, "
                        + Message.TRUE_COUNTERPART
                        + " TEXT,"
                        + Message.BODY
                        + " TEXT, "
                        + Message.ENCRYPTION
                        + " NUMBER, "
                        + Message.STATUS
                        + " NUMBER,"
                        + Message.TYPE
                        + " NUMBER, "
                        + Message.RELATIVE_FILE_PATH
                        + " TEXT, "
                        + Message.SERVER_MSG_ID
                        + " TEXT, "
                        + Message.ROOM_STANZA_ID + " TEXT, "
                        + Message.MODERATED + " INTEGER NOT NULL DEFAULT 0, "
                        + Message.MODERATION_REASON + " TEXT, "
                        + Message.MODERATED_BY + " TEXT, "
                        + Message.MODERATED_AT + " INTEGER NOT NULL DEFAULT 0, "
                        + Message.MODERATION_RETIRED + " INTEGER NOT NULL DEFAULT 0, "
                        + Message.RETRACTED + " INTEGER NOT NULL DEFAULT 0, "
                        + Message.RETRACTION_RETIRED + " INTEGER NOT NULL DEFAULT 0, "
                        + Message.FINGERPRINT
                        + " TEXT, "
                        + Message.CARBON
                        + " INTEGER, "
                        + Message.EDITED
                        + " TEXT, "
                        + Message.READ
                        + " NUMBER DEFAULT 1, "
                        + Message.OOB
                        + " INTEGER, "
                        + Message.ERROR_MESSAGE
                        + " TEXT,"
                        + Message.READ_BY_MARKERS
                        + " TEXT,"
                        + Message.MARKABLE
                        + " NUMBER DEFAULT 0,"
                        + Message.DELETED
                        + " NUMBER DEFAULT 0,"
                        + Message.BODY_LANGUAGE
                        + " TEXT,"
                        + Message.PAYLOADS
                        + " TEXT,"
                        + Message.OCCUPANT_ID
                        + " TEXT,"
                        + Message.REACTIONS
                        + " TEXT,"
                        + Message.REMOTE_MSG_ID
                        + " TEXT, "
                        + Message.MEDIA_GROUP_ID
                        + " TEXT, FOREIGN KEY("
                        + Message.CONVERSATION
                        + ") REFERENCES "
                        + Conversation.TABLENAME
                        + "("
                        + Conversation.UUID
                        + ") ON DELETE CASCADE);");
        db.execSQL(CREATE_MESSAGE_TIME_INDEX);
        db.execSQL(CREATE_MESSAGE_CONVERSATION_INDEX);
        db.execSQL(CREATE_MESSAGE_DELETED_INDEX);
        db.execSQL(CREATE_MESSAGE_RELATIVE_FILE_PATH_INDEX);
        db.execSQL(CREATE_MESSAGE_TYPE_INDEX);
        db.execSQL(CREATE_ROOM_STANZA_INDEX);
        db.execSQL(CREATE_MODERATION_TABLE);
        for (final String statement : retractionMigrationStatements()) {
            db.execSQL(statement);
        }
        db.execSQL(CREATE_SECURE_CONTENT_STATEMENT);
        db.execSQL(CREATE_SECURE_MESSAGE_PAYLOAD_REFERENCE_STATEMENT);
        db.execSQL(CREATE_SECURE_MESSAGE_PAYLOAD_PUBLICATION_STATEMENT);
        db.execSQL(CREATE_SECURE_MESSAGE_PAYLOAD_RETIREMENT_STATEMENT);
        db.execSQL(CREATE_SECURE_MESSAGE_PAYLOAD_MODE_STATEMENT);
        db.execSQL(CREATE_CONTATCS_STATEMENT);
        db.execSQL(CREATE_DISCOVERY_RESULTS_STATEMENT);
        db.execSQL(CREATE_SESSIONS_STATEMENT);
        db.execSQL(CREATE_PREKEYS_STATEMENT);
        db.execSQL(CREATE_SIGNED_PREKEYS_STATEMENT);
        db.execSQL(CREATE_IDENTITIES_STATEMENT);
        db.execSQL(CREATE_PRESENCE_TEMPLATES_STATEMENT);
        db.execSQL(CREATE_RESOLVER_RESULTS_TABLE);
        db.execSQL(CREATE_MESSAGE_INDEX_TABLE);
        db.execSQL(CREATE_MESSAGE_INSERT_TRIGGER);
        db.execSQL(CREATE_MESSAGE_UPDATE_TRIGGER);
        db.execSQL(CREATE_MESSAGE_DELETE_TRIGGER);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2 && newVersion >= 2) {
            db.execSQL(
                    "update "
                            + Account.TABLENAME
                            + " set "
                            + Account.OPTIONS
                            + " = "
                            + Account.OPTIONS
                            + " | 8");
        }
        if (oldVersion < 3 && newVersion >= 3) {
            db.execSQL(
                    "ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.TYPE + " NUMBER");
        }
        if (oldVersion < 5 && newVersion >= 5) {
            db.execSQL("DROP TABLE " + Contact.TABLENAME);
            db.execSQL(CREATE_CONTATCS_STATEMENT);
            db.execSQL("UPDATE " + Account.TABLENAME + " SET " + Account.ROSTERVERSION + " = NULL");
        }
        if (oldVersion < 6 && newVersion >= 6) {
            db.execSQL(
                    "ALTER TABLE "
                            + Message.TABLENAME
                            + " ADD COLUMN "
                            + Message.TRUE_COUNTERPART
                            + " TEXT");
        }
        if (oldVersion < 7 && newVersion >= 7) {
            db.execSQL(
                    "ALTER TABLE "
                            + Message.TABLENAME
                            + " ADD COLUMN "
                            + Message.REMOTE_MSG_ID
                            + " TEXT");
            db.execSQL(
                    "ALTER TABLE " + Contact.TABLENAME + " ADD COLUMN " + Contact.AVATAR + " TEXT");
            db.execSQL(
                    "ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.AVATAR + " TEXT");
        }
        if (oldVersion < 8 && newVersion >= 8) {
            db.execSQL(
                    "ALTER TABLE "
                            + Conversation.TABLENAME
                            + " ADD COLUMN "
                            + Conversation.ATTRIBUTES
                            + " TEXT");
        }
        if (oldVersion < 9 && newVersion >= 9) {
            db.execSQL(
                    "ALTER TABLE "
                            + Contact.TABLENAME
                            + " ADD COLUMN "
                            + Contact.LAST_TIME
                            + " NUMBER");
            db.execSQL(
                    "ALTER TABLE "
                            + Contact.TABLENAME
                            + " ADD COLUMN "
                            + Contact.LAST_PRESENCE
                            + " TEXT");
        }
        if (oldVersion < 10 && newVersion >= 10) {
            db.execSQL(
                    "ALTER TABLE "
                            + Message.TABLENAME
                            + " ADD COLUMN "
                            + Message.RELATIVE_FILE_PATH
                            + " TEXT");
        }
        if (oldVersion < 11 && newVersion >= 11) {
            db.execSQL(
                    "ALTER TABLE " + Contact.TABLENAME + " ADD COLUMN " + Contact.GROUPS + " TEXT");
            db.execSQL("delete from " + Contact.TABLENAME);
            db.execSQL("update " + Account.TABLENAME + " set " + Account.ROSTERVERSION + " = NULL");
        }
        if (oldVersion < 12 && newVersion >= 12) {
            db.execSQL(
                    "ALTER TABLE "
                            + Message.TABLENAME
                            + " ADD COLUMN "
                            + Message.SERVER_MSG_ID
                            + " TEXT");
        }
        if (oldVersion < 13 && newVersion >= 13) {
            db.execSQL("delete from " + Contact.TABLENAME);
            db.execSQL("update " + Account.TABLENAME + " set " + Account.ROSTERVERSION + " = NULL");
        }
        if (oldVersion < 14 && newVersion >= 14) {
            canonicalizeJids(db);
        }
        if (oldVersion < 15 && newVersion >= 15) {
            recreateAxolotlDb(db);
            db.execSQL(
                    "ALTER TABLE "
                            + Message.TABLENAME
                            + " ADD COLUMN "
                            + Message.FINGERPRINT
                            + " TEXT");
        }
        if (oldVersion < 16 && newVersion >= 16) {
            db.execSQL(
                    "ALTER TABLE "
                            + Message.TABLENAME
                            + " ADD COLUMN "
                            + Message.CARBON
                            + " INTEGER");
        }
        if (oldVersion < 19 && newVersion >= 19) {
            db.execSQL(
                    "ALTER TABLE "
                            + Account.TABLENAME
                            + " ADD COLUMN "
                            + Account.DISPLAY_NAME
                            + " TEXT");
        }
        if (oldVersion < 20 && newVersion >= 20) {
            db.execSQL(
                    "ALTER TABLE "
                            + Account.TABLENAME
                            + " ADD COLUMN "
                            + Account.HOSTNAME
                            + " TEXT");
            db.execSQL(
                    "ALTER TABLE "
                            + Account.TABLENAME
                            + " ADD COLUMN "
                            + Account.PORT
                            + " NUMBER DEFAULT 5222");
        }
        if (oldVersion < 26 && newVersion >= 26) {
            db.execSQL(
                    "ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.STATUS + " TEXT");
            db.execSQL(
                    "ALTER TABLE "
                            + Account.TABLENAME
                            + " ADD COLUMN "
                            + Account.STATUS_MESSAGE
                            + " TEXT");
        }
        if (oldVersion < 40 && newVersion >= 40) {
            db.execSQL(
                    "ALTER TABLE "
                            + Account.TABLENAME
                            + " ADD COLUMN "
                            + Account.RESOURCE
                            + " TEXT");
        }
        /* Any migrations that alter the Account table need to happen BEFORE this migration, as it
         * depends on account de-serialization.
         */
        if (oldVersion < 17 && newVersion >= 17 && newVersion < 31) {
            List<Account> accounts = getAccounts(db);
            for (Account account : accounts) {
                String ownDeviceIdString =
                        account.getKey(SQLiteAxolotlStore.JSONKEY_REGISTRATION_ID);
                if (ownDeviceIdString == null) {
                    continue;
                }
                int ownDeviceId = Integer.valueOf(ownDeviceIdString);
                SignalProtocolAddress ownAddress =
                        new SignalProtocolAddress(
                                account.getJid().asBareJid().toString(), ownDeviceId);
                deleteSession(db, account, ownAddress);
                IdentityKeyPair identityKeyPair = loadOwnIdentityKeyPair(db, account);
                if (identityKeyPair != null) {
                    String[] selectionArgs = {
                        account.getUuid(),
                        CryptoHelper.bytesToHex(identityKeyPair.getPublicKey().serialize())
                    };
                    ContentValues values = new ContentValues();
                    values.put(SQLiteAxolotlStore.TRUSTED, 2);
                    db.update(
                            SQLiteAxolotlStore.IDENTITIES_TABLENAME,
                            values,
                            SQLiteAxolotlStore.ACCOUNT
                                    + " = ? AND "
                                    + SQLiteAxolotlStore.FINGERPRINT
                                    + " = ? ",
                            selectionArgs);
                } else {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": could not load own identity key pair");
                }
            }
        }
        if (oldVersion < 18 && newVersion >= 18) {
            db.execSQL(
                    "ALTER TABLE "
                            + Message.TABLENAME
                            + " ADD COLUMN "
                            + Message.READ
                            + " NUMBER DEFAULT 1");
        }

        if (oldVersion < 21 && newVersion >= 21) {
            List<Account> accounts = getAccounts(db);
            for (Account account : accounts) {
                account.clearLegacyEncryptionSignature();
                db.update(
                        Account.TABLENAME,
                        account.getContentValues(),
                        Account.UUID + "=?",
                        new String[] {account.getUuid()});
            }
        }

        if (oldVersion >= 15 && oldVersion < 22 && newVersion >= 22) {
            db.execSQL(
                    "ALTER TABLE "
                            + SQLiteAxolotlStore.IDENTITIES_TABLENAME
                            + " ADD COLUMN "
                            + SQLiteAxolotlStore.CERTIFICATE);
        }

        if (oldVersion < 23 && newVersion >= 23) {
            db.execSQL(CREATE_DISCOVERY_RESULTS_STATEMENT);
        }

        if (oldVersion < 24 && newVersion >= 24) {
            db.execSQL(
                    "ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.EDITED + " TEXT");
        }

        if (oldVersion < 25 && newVersion >= 25) {
            db.execSQL(
                    "ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.OOB + " INTEGER");
        }

        if (oldVersion < 26 && newVersion >= 26) {
            db.execSQL(CREATE_PRESENCE_TEMPLATES_STATEMENT);
        }

        if (oldVersion < 27 && newVersion >= 27) {
            db.execSQL("DELETE FROM " + ServiceDiscoveryResult.TABLENAME);
        }

        if (oldVersion < 28 && newVersion >= 28) {
            canonicalizeJids(db);
        }

        if (oldVersion < 29 && newVersion >= 29) {
            db.execSQL(
                    "ALTER TABLE "
                            + Message.TABLENAME
                            + " ADD COLUMN "
                            + Message.ERROR_MESSAGE
                            + " TEXT");
        }
        if (oldVersion >= 15 && oldVersion < 31 && newVersion >= 31) {
            db.execSQL(
                    "ALTER TABLE "
                            + SQLiteAxolotlStore.IDENTITIES_TABLENAME
                            + " ADD COLUMN "
                            + SQLiteAxolotlStore.TRUST
                            + " TEXT");
            db.execSQL(
                    "ALTER TABLE "
                            + SQLiteAxolotlStore.IDENTITIES_TABLENAME
                            + " ADD COLUMN "
                            + SQLiteAxolotlStore.ACTIVE
                            + " NUMBER");
            HashMap<Integer, ContentValues> migration = new HashMap<>();
            migration.put(
                    0, createFingerprintStatusContentValues(FingerprintStatus.Trust.TRUSTED, true));
            migration.put(
                    1, createFingerprintStatusContentValues(FingerprintStatus.Trust.TRUSTED, true));
            migration.put(
                    2,
                    createFingerprintStatusContentValues(FingerprintStatus.Trust.UNTRUSTED, true));
            migration.put(
                    3,
                    createFingerprintStatusContentValues(
                            FingerprintStatus.Trust.COMPROMISED, false));
            migration.put(
                    4,
                    createFingerprintStatusContentValues(FingerprintStatus.Trust.TRUSTED, false));
            migration.put(
                    5,
                    createFingerprintStatusContentValues(FingerprintStatus.Trust.TRUSTED, false));
            migration.put(
                    6,
                    createFingerprintStatusContentValues(FingerprintStatus.Trust.UNTRUSTED, false));
            migration.put(
                    7,
                    createFingerprintStatusContentValues(
                            FingerprintStatus.Trust.VERIFIED_X509, true));
            migration.put(
                    8,
                    createFingerprintStatusContentValues(
                            FingerprintStatus.Trust.VERIFIED_X509, false));
            for (Map.Entry<Integer, ContentValues> entry : migration.entrySet()) {
                String whereClause = SQLiteAxolotlStore.TRUSTED + "=?";
                String[] where = {String.valueOf(entry.getKey())};
                db.update(
                        SQLiteAxolotlStore.IDENTITIES_TABLENAME,
                        entry.getValue(),
                        whereClause,
                        where);
            }
        }
        if (oldVersion >= 15 && oldVersion < 32 && newVersion >= 32) {
            db.execSQL(
                    "ALTER TABLE "
                            + SQLiteAxolotlStore.IDENTITIES_TABLENAME
                            + " ADD COLUMN "
                            + SQLiteAxolotlStore.LAST_ACTIVATION
                            + " NUMBER");
            ContentValues defaults = new ContentValues();
            defaults.put(SQLiteAxolotlStore.LAST_ACTIVATION, System.currentTimeMillis());
            db.update(SQLiteAxolotlStore.IDENTITIES_TABLENAME, defaults, null, null);
        }
        if (oldVersion >= 15 && oldVersion < 33 && newVersion >= 33) {
            String whereClause = SQLiteAxolotlStore.OWN + "=1";
            db.update(
                    SQLiteAxolotlStore.IDENTITIES_TABLENAME,
                    createFingerprintStatusContentValues(FingerprintStatus.Trust.VERIFIED, true),
                    whereClause,
                    null);
        }

        if (oldVersion < 34 && newVersion >= 34) {
            db.execSQL(CREATE_MESSAGE_TIME_INDEX);

            final File oldPicturesDirectory =
                    new File(
                            Environment.getExternalStoragePublicDirectory(
                                            Environment.DIRECTORY_PICTURES)
                                    + "/Conversations/");
            final File oldFilesDirectory =
                    new File(Environment.getExternalStorageDirectory() + "/Conversations/");
            final File newFilesDirectory =
                    new File(
                            Environment.getExternalStorageDirectory()
                                    + "/Conversations/Media/Conversations Files/");
            final File newVideosDirectory =
                    new File(
                            Environment.getExternalStorageDirectory()
                                    + "/Conversations/Media/Conversations Videos/");
            if (oldPicturesDirectory.exists() && oldPicturesDirectory.isDirectory()) {
                final File newPicturesDirectory =
                        new File(
                                Environment.getExternalStorageDirectory()
                                        + "/Conversations/Media/Conversations Images/");
                newPicturesDirectory.getParentFile().mkdirs();
                if (oldPicturesDirectory.renameTo(newPicturesDirectory)) {
                    Log.d(
                            Config.LOGTAG,
                            "moved "
                                    + oldPicturesDirectory.getAbsolutePath()
                                    + " to "
                                    + newPicturesDirectory.getAbsolutePath());
                }
            }
            if (oldFilesDirectory.exists() && oldFilesDirectory.isDirectory()) {
                newFilesDirectory.mkdirs();
                newVideosDirectory.mkdirs();
                final File[] files = oldFilesDirectory.listFiles();
                if (files == null) {
                    return;
                }
                for (File file : files) {
                    if (file.getName().equals(".nomedia")) {
                        if (file.delete()) {
                            Log.d(
                                    Config.LOGTAG,
                                    "deleted nomedia file in "
                                            + oldFilesDirectory.getAbsolutePath());
                        }
                    } else if (file.isFile()) {
                        final String name = file.getName();
                        boolean isVideo = false;
                        int start = name.lastIndexOf('.') + 1;
                        if (start < name.length()) {
                            String mime =
                                    MimeUtils.guessMimeTypeFromExtension(name.substring(start));
                            isVideo = mime != null && mime.startsWith("video/");
                        }
                        File dst =
                                new File(
                                        (isVideo ? newVideosDirectory : newFilesDirectory)
                                                        .getAbsolutePath()
                                                + "/"
                                                + file.getName());
                        if (file.renameTo(dst)) {
                            Log.d(Config.LOGTAG, "moved " + file + " to " + dst);
                        }
                    }
                }
            }
        }
        if (oldVersion < 35 && newVersion >= 35) {
            db.execSQL(CREATE_MESSAGE_CONVERSATION_INDEX);
        }
        if (oldVersion < 36 && newVersion >= 36) {
            List<Account> accounts = getAccounts(db);
            for (Account account : accounts) {
                account.setOption(Account.OPTION_REQUIRES_ACCESS_MODE_CHANGE, true);
                account.setOption(Account.OPTION_LOGGED_IN_SUCCESSFULLY, false);
                db.update(
                        Account.TABLENAME,
                        account.getContentValues(),
                        Account.UUID + "=?",
                        new String[] {account.getUuid()});
            }
        }

        if (oldVersion < 37 && newVersion >= 37) {
            db.execSQL(
                    "ALTER TABLE "
                            + Message.TABLENAME
                            + " ADD COLUMN "
                            + Message.READ_BY_MARKERS
                            + " TEXT");
        }

        if (oldVersion < 38 && newVersion >= 38) {
            db.execSQL(
                    "ALTER TABLE "
                            + Message.TABLENAME
                            + " ADD COLUMN "
                            + Message.MARKABLE
                            + " NUMBER DEFAULT 0");
        }

        if (oldVersion < 39 && newVersion >= 39) {
            db.execSQL(CREATE_RESOLVER_RESULTS_TABLE);
        }

        if (QuickConversationsService.isQuicksy() && oldVersion < 43 && newVersion >= 43) {
            List<Account> accounts = getAccounts(db);
            for (Account account : accounts) {
                account.setOption(Account.OPTION_MAGIC_CREATE, true);
                db.update(
                        Account.TABLENAME,
                        account.getContentValues(),
                        Account.UUID + "=?",
                        new String[] {account.getUuid()});
            }
        }

        if (oldVersion < 44 && newVersion >= 44) {
            db.execSQL(
                    "ALTER TABLE "
                            + Message.TABLENAME
                            + " ADD COLUMN "
                            + Message.DELETED
                            + " NUMBER DEFAULT 0");
            db.execSQL(CREATE_MESSAGE_DELETED_INDEX);
            db.execSQL(CREATE_MESSAGE_RELATIVE_FILE_PATH_INDEX);
            db.execSQL(CREATE_MESSAGE_TYPE_INDEX);
        }

        if (oldVersion < 45 && newVersion >= 45) {
            db.execSQL("ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.BODY_LANGUAGE);
        }

        if (oldVersion < 46 && newVersion >= 46) {
            final long start = SystemClock.elapsedRealtime();
            db.rawQuery("PRAGMA secure_delete = FALSE", null).close();
            db.execSQL("update " + Message.TABLENAME + " set " + Message.EDITED + "IS NULL");
            db.rawQuery("PRAGMA secure_delete=ON", null).close();
            final long diff = SystemClock.elapsedRealtime() - start;
            Log.d(Config.LOGTAG, "deleted old edit information in " + diff + "ms");
        }
        if (oldVersion < 47 && newVersion >= 47) {
            db.execSQL(
                    "ALTER TABLE "
                            + Contact.TABLENAME
                            + " ADD COLUMN "
                            + Contact.PRESENCE_NAME
                            + " TEXT");
        }
        if (oldVersion < 48 && newVersion >= 48) {
            db.execSQL(
                    "ALTER TABLE "
                            + Contact.TABLENAME
                            + " ADD COLUMN "
                            + Contact.RTP_CAPABILITY
                            + " TEXT");
        }
        if (oldVersion < 49 && newVersion >= 49) {
            db.beginTransaction();
            db.execSQL("DROP TRIGGER IF EXISTS after_message_insert;");
            db.execSQL("DROP TRIGGER IF EXISTS after_message_update;");
            db.execSQL("DROP TRIGGER IF EXISTS after_message_delete;");
            db.execSQL("DROP TABLE IF EXISTS messages_index;");
            // a hack that should not be necessary, but
            // there was at least one occurence when SQLite failed at this
            db.execSQL("DROP TABLE IF EXISTS messages_index_docsize;");
            db.execSQL("DROP TABLE IF EXISTS messages_index_segdir;");
            db.execSQL("DROP TABLE IF EXISTS messages_index_segments;");
            db.execSQL("DROP TABLE IF EXISTS messages_index_stat;");
            db.execSQL(CREATE_MESSAGE_INDEX_TABLE);
            db.execSQL(CREATE_MESSAGE_INSERT_TRIGGER);
            db.execSQL(CREATE_MESSAGE_UPDATE_TRIGGER);
            db.execSQL(CREATE_MESSAGE_DELETE_TRIGGER);
            db.setTransactionSuccessful();
            db.endTransaction();
            requiresMessageIndexRebuild = true;
        }
        if (oldVersion < 50 && newVersion >= 50) {
            db.execSQL(
                    "ALTER TABLE "
                            + Account.TABLENAME
                            + " ADD COLUMN "
                            + Account.PINNED_MECHANISM
                            + " TEXT");
            db.execSQL(
                    "ALTER TABLE "
                            + Account.TABLENAME
                            + " ADD COLUMN "
                            + Account.PINNED_CHANNEL_BINDING
                            + " TEXT");
        }
        if (oldVersion < 51 && newVersion >= 51) {
            db.execSQL(
                    "ALTER TABLE "
                            + Account.TABLENAME
                            + " ADD COLUMN "
                            + Account.FAST_MECHANISM
                            + " TEXT");
            db.execSQL(
                    "ALTER TABLE "
                            + Account.TABLENAME
                            + " ADD COLUMN "
                            + Account.FAST_TOKEN
                            + " TEXT");
        }

        if (oldVersion < 52 && newVersion >= 52) {
            db.execSQL("ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.PAYLOADS + " TEXT");
        }

        if (oldVersion < 53 && newVersion >= 53) {
            db.execSQL("ALTER TABLE " + Conversation.TABLENAME + " ADD COLUMN " + Conversation.NEXT_COUNTERPART + " TEXT");
        }

        if (oldVersion < 54 && newVersion >= 54) {
            db.execSQL(
                    "ALTER TABLE "
                            + Message.TABLENAME
                            + " ADD COLUMN "
                            + Message.OCCUPANT_ID
                            + " TEXT");
            db.execSQL(
                    "ALTER TABLE "
                            + Message.TABLENAME
                            + " ADD COLUMN "
                            + Message.REACTIONS
                            + " TEXT");

            try (final Cursor cursor =
                    db.query(
                            Account.TABLENAME,
                            new String[] {Account.UUID, Account.USERNAME},
                            null,
                            null,
                            null,
                            null,
                            null)) {
                while (cursor != null && cursor.moveToNext()) {
                    final var uuid = cursor.getString(0);
                    final var username = cursor.getString(1);
                    final Localpart localpart;
                    try {
                        localpart = Localpart.fromUnescaped(username);
                    } catch (final XmppStringprepException e) {
                        Log.d(Config.LOGTAG, "unable to parse jid");
                        continue;
                    }
                    final var contentValues = new ContentValues();
                    contentValues.putNull(Account.ROSTERVERSION);
                    contentValues.put(Account.USERNAME, localpart.toString());
                    db.update(
                            Account.TABLENAME,
                            contentValues,
                            Account.UUID + "=?",
                            new String[] {uuid});
                }
            }
        }

        if (oldVersion < 55 && newVersion >= 55) {
            db.execSQL(
                    "ALTER TABLE "
                            + Message.TABLENAME
                            + " ADD COLUMN "
                            + Message.MEDIA_GROUP_ID
                            + " TEXT");
        }
        if (oldVersion < 56 && newVersion >= 56) {
            db.execSQL(CREATE_SECURE_CONTENT_STATEMENT);
        }
        if (oldVersion < 57 && newVersion >= 57) {
            db.execSQL(CREATE_SECURE_MESSAGE_PAYLOAD_REFERENCE_STATEMENT);
            db.execSQL(CREATE_SECURE_MESSAGE_PAYLOAD_PUBLICATION_V57_STATEMENT);
        }
        if (oldVersion < 58 && newVersion >= 58) {
            db.execSQL(CREATE_SECURE_MESSAGE_PAYLOAD_RETIREMENT_STATEMENT);
        }
        if (oldVersion < 59 && newVersion >= 59) {
            db.execSQL(CREATE_SECURE_MESSAGE_PAYLOAD_MODE_STATEMENT);
        }
        if (oldVersion < 60 && newVersion >= 60) {
            // Some development builds created the v57 table from the then-current schema, which
            // already contained previous_content_id. Keep this migration idempotent so those
            // databases recover without data loss.
            if (!tableHasColumn(
                    db,
                    SECURE_MESSAGE_PAYLOAD_PUBLICATION_TABLENAME,
                    SECURE_MESSAGE_PAYLOAD_PREVIOUS_CONTENT_ID)) {
                db.execSQL(
                        "ALTER TABLE "
                                + SECURE_MESSAGE_PAYLOAD_PUBLICATION_TABLENAME
                                + " ADD COLUMN "
                                + SECURE_MESSAGE_PAYLOAD_PREVIOUS_CONTENT_ID
                                + " TEXT");
            }
        }
        if (oldVersion < 62 && newVersion >= 62) {
            for (final String statement : moderationMigrationStatements()) {
                db.execSQL(statement);
            }
        }
        if (oldVersion < 63 && newVersion >= 63) {
            for (final String statement : retractionMigrationStatements()) {
                db.execSQL(statement);
            }
        }
        if (oldVersion < 64 && newVersion >= 64) {
            for (final String statement :
                    retractionMessageMigrationStatements()) {
                db.execSQL(statement);
            }
        }
        if (oldVersion < 61 && newVersion >= 61) {
            // Historical databases can contain intact legacy message bodies while the external
            // FTS4 content table is empty or stale. Rebuild exactly once after upgrade; protected
            // messages retain an empty durable body and therefore never contribute plaintext.
            requiresMessageIndexRebuild = true;
        }
    }

    private static boolean tableHasColumn(
            final SQLiteDatabase db, final String tableName, final String columnName) {
        try (final Cursor cursor =
                db.rawQuery("PRAGMA table_info(" + tableName + ")", null)) {
            final int nameIndex = cursor.getColumnIndexOrThrow("name");
            while (cursor.moveToNext()) {
                if (columnName.equals(cursor.getString(nameIndex))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void canonicalizeJids(SQLiteDatabase db) {
        // migrate db to new, canonicalized JID domainpart representation

        // Conversation table
        Cursor cursor = db.rawQuery("select * from " + Conversation.TABLENAME, new String[0]);
        while (cursor.moveToNext()) {
            String newJid;
            try {
                newJid =
                        Jid.of(
                                        cursor.getString(
                                                cursor.getColumnIndexOrThrow(
                                                        Conversation.CONTACTJID)))
                                .toString();
            } catch (final IllegalArgumentException e) {
                Log.e(
                        Config.LOGTAG,
                        "Failed to migrate Conversation CONTACTJID "
                                + cursor.getString(
                                        cursor.getColumnIndexOrThrow(Conversation.CONTACTJID))
                                + ". Skipping...",
                        e);
                continue;
            }

            final String[] updateArgs = {
                newJid, cursor.getString(cursor.getColumnIndexOrThrow(Conversation.UUID)),
            };
            db.execSQL(
                    "update "
                            + Conversation.TABLENAME
                            + " set "
                            + Conversation.CONTACTJID
                            + " = ? "
                            + " where "
                            + Conversation.UUID
                            + " = ?",
                    updateArgs);
        }
        cursor.close();

        // Contact table
        cursor = db.rawQuery("select * from " + Contact.TABLENAME, new String[0]);
        while (cursor.moveToNext()) {
            String newJid;
            try {
                newJid =
                        Jid.of(cursor.getString(cursor.getColumnIndexOrThrow(Contact.JID)))
                                .toString();
            } catch (final IllegalArgumentException e) {
                Log.e(
                        Config.LOGTAG,
                        "Failed to migrate Contact JID "
                                + cursor.getString(cursor.getColumnIndexOrThrow(Contact.JID))
                                + ":  Skipping...",
                        e);
                continue;
            }

            final String[] updateArgs = {
                newJid,
                cursor.getString(cursor.getColumnIndexOrThrow(Contact.ACCOUNT)),
                cursor.getString(cursor.getColumnIndexOrThrow(Contact.JID)),
            };
            db.execSQL(
                    "update "
                            + Contact.TABLENAME
                            + " set "
                            + Contact.JID
                            + " = ? "
                            + " where "
                            + Contact.ACCOUNT
                            + " = ? "
                            + " AND "
                            + Contact.JID
                            + " = ?",
                    updateArgs);
        }
        cursor.close();

        // Account table
        cursor = db.rawQuery("select * from " + Account.TABLENAME, new String[0]);
        while (cursor.moveToNext()) {
            String newServer;
            try {
                newServer =
                        Jid.of(
                                        cursor.getString(
                                                cursor.getColumnIndexOrThrow(Account.USERNAME)),
                                        cursor.getString(
                                                cursor.getColumnIndexOrThrow(Account.SERVER)),
                                        null)
                                .getDomain()
                                .toString();
            } catch (final IllegalArgumentException e) {
                Log.e(
                        Config.LOGTAG,
                        "Failed to migrate Account SERVER "
                                + cursor.getString(cursor.getColumnIndexOrThrow(Account.SERVER))
                                + ". Skipping...",
                        e);
                continue;
            }

            String[] updateArgs = {
                newServer, cursor.getString(cursor.getColumnIndexOrThrow(Account.UUID)),
            };
            db.execSQL(
                    "update "
                            + Account.TABLENAME
                            + " set "
                            + Account.SERVER
                            + " = ? "
                            + " where "
                            + Account.UUID
                            + " = ?",
                    updateArgs);
        }
        cursor.close();
    }

    @Override
    public void createConversation(Conversation conversation) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.insert(Conversation.TABLENAME, null, conversation.getContentValues());
    }

    static boolean applyModerationMarker(
            final Message message, @Nullable final Moderation moderation) {
        if (message == null || moderation == null || message.isModerated()) {
            return false;
        }
        message.markModerated(moderation.by, moderation.reason, moderation.stamp);
        return true;
    }

    private void applyModerationMarker(final Message message) {
        if (message != null
                && message.getConversation() instanceof Conversation conversation
                && message.getRoomStanzaId() != null) {
            applyModerationMarker(
                    message, getMessageModeration(conversation, message.getRoomStanzaId()));
        }
    }

    @Override
    public void createMessage(Message message) {
        applyModerationMarker(message);
        SQLiteDatabase db = this.getWritableDatabase();
        db.insert(Message.TABLENAME, null, message.getContentValues());
    }

    @Override
    public boolean createProtectedTextMessage(
            final Message message, final String accountUuid) {
        if (message == null
                || accountUuid == null
                || (message.getType() != Message.TYPE_TEXT
                        && message.getType() != Message.TYPE_PRIVATE)) {
            return false;
        }
        final SQLiteDatabase db = getWritableDatabase();
        final String ownershipSql =
                "SELECT 1 FROM "
                        + Conversation.TABLENAME
                        + " WHERE "
                        + Conversation.UUID
                        + "=? AND "
                        + Conversation.ACCOUNT
                        + "=? LIMIT 1";
        try (final Cursor cursor =
                db.rawQuery(
                        ownershipSql,
                        new String[] {message.getConversationUuid(), accountUuid})) {
            if (!cursor.moveToFirst()) {
                return false;
            }
        }
        final ContentValues messageValues = message.getContentValues();
        messageValues.put(Message.BODY, "");
        final ContentValues modeValues = new ContentValues();
        modeValues.put(SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID, accountUuid);
        modeValues.put(SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID, message.getUuid());
        modeValues.put(
                SECURE_MESSAGE_PAYLOAD_MODE,
                SecureMessagePayloadMode.PENDING.getPersistedValue());
        modeValues.put(SECURE_MESSAGE_PAYLOAD_UPDATED_AT, System.currentTimeMillis());
        db.beginTransaction();
        try {
            if (db.insert(Message.TABLENAME, null, messageValues) == -1) {
                return false;
            }
            if (db.insertWithOnConflict(
                            SECURE_MESSAGE_PAYLOAD_MODE_TABLENAME,
                            null,
                            modeValues,
                            SQLiteDatabase.CONFLICT_ABORT)
                    == -1) {
                return false;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        message.setSecureMessagePayloadMode(SecureMessagePayloadMode.PENDING);
        return true;
    }

    @Override
    public void loadSecureMessagePayloadModes(
            final String accountUuid, final List<Message> messages) {
        if (accountUuid == null || messages == null || messages.isEmpty()) {
            return;
        }
        final StringBuilder selection =
                new StringBuilder(SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID + "=? AND "
                        + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID + " IN (");
        final List<String> args = new ArrayList<>();
        args.add(accountUuid);
        for (int i = 0; i < messages.size(); ++i) {
            if (i > 0) {
                selection.append(',');
            }
            selection.append('?');
            args.add(messages.get(i).getUuid());
        }
        selection.append(')');
        final Map<String, SecureMessagePayloadMode> modes = new HashMap<>();
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                SECURE_MESSAGE_PAYLOAD_MODE_TABLENAME,
                                new String[] {
                                    SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID,
                                    SECURE_MESSAGE_PAYLOAD_MODE
                                },
                                selection.toString(),
                                args.toArray(new String[0]),
                                null,
                                null,
                                null)) {
            while (cursor.moveToNext()) {
                modes.put(
                        cursor.getString(
                                cursor.getColumnIndexOrThrow(
                                        SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID)),
                        SecureMessagePayloadMode.fromPersistedValue(
                                cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                                SECURE_MESSAGE_PAYLOAD_MODE))));
            }
        }
        for (final Message message : messages) {
            message.setSecureMessagePayloadMode(modes.get(message.getUuid()));
        }
    }

    @Override
    public void createAccount(Account account) {
        SQLiteDatabase db = this.getWritableDatabase();
        final ContentValues values =
                AccountSecretPersistencePolicyV1.sanitizeForDatabase(
                        applicationContext, account.getUuid(), account.getContentValues());
        db.insert(Account.TABLENAME, null, values);
    }

    @Override
    public void insertDiscoveryResult(ServiceDiscoveryResult result) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.insert(ServiceDiscoveryResult.TABLENAME, null, result.getContentValues());
    }

    @Override
    public ServiceDiscoveryResult findDiscoveryResult(final String hash, final String ver) {
        SQLiteDatabase db = this.getReadableDatabase();
        String[] selectionArgs = {hash, ver};
        Cursor cursor =
                db.query(
                        ServiceDiscoveryResult.TABLENAME,
                        null,
                        ServiceDiscoveryResult.HASH + "=? AND " + ServiceDiscoveryResult.VER + "=?",
                        selectionArgs,
                        null,
                        null,
                        null);
        if (cursor.getCount() == 0) {
            cursor.close();
            return null;
        }
        cursor.moveToFirst();

        ServiceDiscoveryResult result = null;
        try {
            result = new ServiceDiscoveryResult(cursor);
        } catch (JSONException e) {
            /* result is still null */
        }

        cursor.close();
        return result;
    }

    @Override
    public void saveResolverResult(String domain, Resolver.Result result) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues contentValues = result.toContentValues();
        contentValues.put(Resolver.Result.DOMAIN, domain);
        db.insert(RESOLVER_RESULTS_TABLENAME, null, contentValues);
    }

    @Override
    public synchronized Resolver.Result findResolverResult(String domain) {
        SQLiteDatabase db = this.getReadableDatabase();
        String where = Resolver.Result.DOMAIN + "=?";
        String[] whereArgs = {domain};
        final Cursor cursor =
                db.query(RESOLVER_RESULTS_TABLENAME, null, where, whereArgs, null, null, null);
        Resolver.Result result = null;
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    result = Resolver.Result.fromCursor(cursor);
                }
            } catch (Exception e) {
                Log.d(
                        Config.LOGTAG,
                        "unable to find cached resolver result in database " + e.getMessage());
                return null;
            } finally {
                cursor.close();
            }
        }
        return result;
    }

    @Override
    public void insertPresenceTemplate(PresenceTemplate template) {
        SQLiteDatabase db = this.getWritableDatabase();
        String whereToDelete = PresenceTemplate.MESSAGE + "=?";
        String[] whereToDeleteArgs = {template.getStatusMessage()};
        db.delete(PresenceTemplate.TABELNAME, whereToDelete, whereToDeleteArgs);
        db.delete(
                PresenceTemplate.TABELNAME,
                PresenceTemplate.UUID
                        + " not in (select "
                        + PresenceTemplate.UUID
                        + " from "
                        + PresenceTemplate.TABELNAME
                        + " order by "
                        + PresenceTemplate.LAST_USED
                        + " desc limit 9)",
                null);
        db.insert(PresenceTemplate.TABELNAME, null, template.getContentValues());
    }

    @Override
    public List<PresenceTemplate> getPresenceTemplates() {
        ArrayList<PresenceTemplate> templates = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor =
                db.query(
                        PresenceTemplate.TABELNAME,
                        null,
                        null,
                        null,
                        null,
                        null,
                        PresenceTemplate.LAST_USED + " desc");
        while (cursor.moveToNext()) {
            templates.add(PresenceTemplate.fromCursor(cursor));
        }
        cursor.close();
        return templates;
    }

    @Override
    public CopyOnWriteArrayList<Conversation> getConversations(int status) {
        CopyOnWriteArrayList<Conversation> list = new CopyOnWriteArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        String[] selectionArgs = {Integer.toString(status)};
        Cursor cursor =
                db.rawQuery(
                        "select * from "
                                + Conversation.TABLENAME
                                + " where "
                                + Conversation.STATUS
                                + " = ? and "
                                + Conversation.CONTACTJID
                                + " is not null order by "
                                + Conversation.CREATED
                                + " desc",
                        selectionArgs);
        while (cursor.moveToNext()) {
            final Conversation conversation = Conversation.fromCursor(cursor);
            if (conversation.getJid() instanceof Jid.Invalid) {
                continue;
            }
            list.add(conversation);
        }
        cursor.close();
        return list;
    }

    @Override
    public ArrayList<Message> getMessages(Conversation conversations, int limit) {
        return getMessages(conversations, limit, -1, false);
    }


    @Override
    @Nullable
    public ArrayList<Message> getMessagesNearUuid(Conversation conversation, int limit, String uuid) {
        SQLiteDatabase db = this.getReadableDatabase();

        String[] selectionArgs = {conversation.getUuid(), uuid, uuid, uuid};
        Cursor cursor = db.query(Message.TABLENAME, null, Message.CONVERSATION
                + "=? and " + Message.MODERATED + "=0 and " + Message.RETRACTED + "=0" + verifiedRetractionVisibilityFilter() + " and (" + Message.SERVER_MSG_ID + "=? or " + Message.REMOTE_MSG_ID +  "=? or " + Message.UUID + "=?)", selectionArgs, null, null, Message.TIME_SENT
                + " DESC", String.valueOf(1));
        CursorUtils.upgradeCursorWindowSize(cursor);
        Message anchorMessage = null;
        while (cursor.moveToNext()) {
            try {
                anchorMessage = Message.fromCursor(cursor, conversation);
            } catch (Exception e) {
                Log.e(Config.LOGTAG, "unable to restore message");
            }
        }

        cursor.close();

        if (anchorMessage == null) {
            return null;
        }

        List<Message> prev = getMessages(conversation, limit / 2, anchorMessage.getTimeSent(), false);
        List<Message> next = getMessages(conversation, limit / 2, anchorMessage.getTimeSent(), true);

        ArrayList<Message> list = new ArrayList<>(prev);
        list.add(anchorMessage);
        list.addAll(next);

        return list;
    }

    @Override
    public ArrayList<Message> getMessagesByIds(Conversation conversation, Set<String> ids) {
        SQLiteDatabase db = this.getReadableDatabase();
        List<String> parameters = new ArrayList<>();

        for (String id : ids) {
            parameters.add("?");
        }

        String parametersString = TextUtils.join(",", parameters);

        String[] selectionArgs = new String[ids.size() * 3 + 1];
        selectionArgs[0] = conversation.getUuid();
        int ind = 1;

        for (int i=0;i<3;i++) {
            for (String id : ids) {
                selectionArgs[ind] = id;
                ind++;
            }
        }

        Cursor cursor = db.query(Message.TABLENAME, null, Message.CONVERSATION
                + "=? and (" + Message.SERVER_MSG_ID + " in (" + parametersString + ") or " + Message.REMOTE_MSG_ID +  " in (" + parametersString + ") or " + Message.UUID + " in (" + parametersString + "))", selectionArgs, null, null, Message.TIME_SENT
                + " DESC", String.valueOf(ids.size()));
        CursorUtils.upgradeCursorWindowSize(cursor);
        ArrayList<Message> list = new ArrayList<>();

        while (cursor.moveToNext()) {
            try {
                Message m = Message.fromCursor(cursor, conversation);
                list.add(m);
            } catch (Exception e) {
                Log.e(Config.LOGTAG, "unable to restore message");
            }
        }

        return list;
    }

    @Override
    public ArrayList<Message> getMessagesByLocalUuids(
            Conversational conversation, Collection<String> uuids) {
        final ArrayList<Message> messages = new ArrayList<>();
        if (uuids.isEmpty()) {
            return messages;
        }
        if (uuids.size() > 50) {
            throw new IllegalArgumentException("Secure search UUID batch exceeds 50");
        }

        final List<String> parameters = new ArrayList<>();
        for (int i = 0; i < uuids.size(); i++) {
            parameters.add("?");
        }
        final String[] selectionArgs = new String[uuids.size() + 1];
        selectionArgs[0] = conversation.getUuid();
        int index = 1;
        for (final String uuid : uuids) {
            selectionArgs[index++] = uuid;
        }

        final Cursor cursor =
                getReadableDatabase()
                        .query(
                                Message.TABLENAME,
                                null,
                                Message.CONVERSATION
                                        + "=? AND "
                                        + Message.UUID
                                        + " IN ("
                                        + TextUtils.join(",", parameters)
                                        + ")",
                                selectionArgs,
                                null,
                                null,
                                Message.TIME_SENT + " DESC",
                                String.valueOf(uuids.size()));
        try {
            CursorUtils.upgradeCursorWindowSize(cursor);
            while (cursor.moveToNext()) {
                try {
                    messages.add(IndividualMessage.fromCursor(cursor, conversation));
                } catch (Exception e) {
                    Log.e(Config.LOGTAG, "unable to restore secure search message");
                }
            }
        } finally {
            cursor.close();
        }
        return messages;
    }

    @Override
    public ArrayList<Message> getMessages(Conversation conversation, int limit, long timestamp, boolean isForward) {
        ArrayList<Message> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor;
        String comparsionOperation = isForward ? ">?" : "<?";
        String sorting = isForward ? " ASC" : " DESC";
        if (timestamp == -1) {
            if (conversation.getNextCounterpart() != null && conversation.hasPermanentCounterpart() && conversation.getMode() == Conversational.MODE_MULTI) {
                String[] selectionArgs = {conversation.getUuid(), String.valueOf(Message.TYPE_PRIVATE), String.valueOf(Message.TYPE_PRIVATE_FILE), conversation.getNextCounterpart().toString()};
                cursor = db.query(Message.TABLENAME, null, Message.CONVERSATION
                    + "=? and " + Message.MODERATED + "=0 AND " + Message.RETRACTED + "=0" + verifiedRetractionVisibilityFilter() + " and (" + Message.TYPE + "=? or " + Message.TYPE + "=?) and " + Message.COUNTERPART + "=?" , selectionArgs, null, null, Message.TIME_SENT
                    + sorting, String.valueOf(limit));
            } else if (conversation.getNextCounterpart() != null && conversation.hasPermanentCounterpart()) {
                String[] selectionArgs = {conversation.getUuid(), String.valueOf(Message.ENCRYPTION_OTR), conversation.getNextCounterpart().toString()};
                cursor = db.query(Message.TABLENAME, null, Message.CONVERSATION
                        + "=? and " + Message.MODERATED + "=0 AND " + Message.RETRACTED + "=0" + verifiedRetractionVisibilityFilter() + " and " + Message.ENCRYPTION + "=? and " + Message.COUNTERPART + "=?" , selectionArgs, null, null, Message.TIME_SENT
                        + sorting, String.valueOf(limit));
            } else {
                String[] selectionArgs = {conversation.getUuid()};
                cursor = db.query(Message.TABLENAME, null, Message.CONVERSATION
                        + "=? and " + Message.MODERATED + "=0 AND " + Message.RETRACTED + "=0" + verifiedRetractionVisibilityFilter(), selectionArgs, null, null, Message.TIME_SENT
                        + sorting, String.valueOf(limit));
            }
        } else {
            if (conversation.getNextCounterpart() != null && conversation.hasPermanentCounterpart() && conversation.getMode() == Conversational.MODE_MULTI) {
                String[] selectionArgs = {conversation.getUuid(), String.valueOf(Message.TYPE_PRIVATE), String.valueOf(Message.TYPE_PRIVATE_FILE), conversation.getNextCounterpart().toString(), Long.toString(timestamp)};
                cursor = db.query(Message.TABLENAME, null, Message.CONVERSATION
                        + "=? and " + Message.MODERATED + "=0 AND " + Message.RETRACTED + "=0" + verifiedRetractionVisibilityFilter() + " and (" + Message.TYPE + "=? or " + Message.TYPE + "=?) and " + Message.COUNTERPART + "=? and " + Message.TIME_SENT + comparsionOperation, selectionArgs, null, null, Message.TIME_SENT
                        + sorting, String.valueOf(limit));
            } else if (conversation.getNextCounterpart() != null && conversation.hasPermanentCounterpart()) {
                String[] selectionArgs = {conversation.getUuid(), String.valueOf(Message.ENCRYPTION_OTR), conversation.getNextCounterpart().toString(), Long.toString(timestamp)};
                cursor = db.query(Message.TABLENAME, null, Message.CONVERSATION
                        + "=? and " + Message.MODERATED + "=0 AND " + Message.RETRACTED + "=0" + verifiedRetractionVisibilityFilter() + " and " + Message.ENCRYPTION + "=? and " + Message.COUNTERPART + "=? and " + Message.TIME_SENT + comparsionOperation, selectionArgs, null, null, Message.TIME_SENT
                        + sorting, String.valueOf(limit));
            } else {
                String[] selectionArgs = {conversation.getUuid(),
                        Long.toString(timestamp)};
                cursor = db.query(Message.TABLENAME, null, Message.CONVERSATION
                                + "=? and " + Message.MODERATED + "=0 AND " + Message.RETRACTED + "=0" + verifiedRetractionVisibilityFilter() + " and " + Message.TIME_SENT + comparsionOperation, selectionArgs,
                        null, null, Message.TIME_SENT + sorting,
                        String.valueOf(limit));
            }
        }
        CursorUtils.upgradeCursorWindowSize(cursor);
        while (cursor.moveToNext()) {
            try {
                Message m = Message.fromCursor(cursor, conversation);
                if (isForward) {
                    list.add(m);
                } else {
                    list.add(0, m);
                }
            } catch (Exception e) {
                Log.e(Config.LOGTAG, "unable to restore message");
            }
        }

        cursor.close();
        return list;
    }

    @Override
    public Cursor getLegacyMessageSearchCursor(final List<String> term, final String uuid) {
        final SQLiteDatabase db = this.getReadableDatabase();
        final StringBuilder SQL = new StringBuilder();
        final String[] selectionArgs;
        SQL.append(
                "SELECT "
                        + Message.TABLENAME
                        + ".*,"
                        + Conversation.TABLENAME
                        + "."
                        + Conversation.CONTACTJID
                        + ","
                        + Conversation.TABLENAME
                        + "."
                        + Conversation.ACCOUNT
                        + ","
                        + Conversation.TABLENAME
                        + "."
                        + Conversation.MODE
                        + ","
                        + Conversation.TABLENAME
 + "."
 + Conversation.NEXT_COUNTERPART
                        + " FROM "
                        + Message.TABLENAME
                        + " JOIN "
                        + Conversation.TABLENAME
                        + " ON "
                        + Message.TABLENAME
                        + "."
                        + Message.CONVERSATION
                        + "="
                        + Conversation.TABLENAME
                        + "."
                        + Conversation.UUID
                        + " JOIN messages_index ON messages_index.rowid=messages.rowid WHERE "
                        + Message.ENCRYPTION
                        + " NOT IN("
                        + Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE
                        + ","
                        + Message.ENCRYPTION_PGP
                        + ","
                        + Message.ENCRYPTION_DECRYPTION_FAILED
                        + ","
                        + Message.ENCRYPTION_AXOLOTL_FAILED
                        + ") AND "
                        + Message.TYPE
                        + " IN("
                        + Message.TYPE_TEXT
                        + ","
                        + Message.TYPE_PRIVATE
                        + ") AND "
                        + Message.TABLENAME
                        + "."
                        + Message.MODERATED
                        + "=0 AND NOT EXISTS (SELECT 1 FROM "
                        + SECURE_MESSAGE_PAYLOAD_MODE_TABLENAME
                        + " secure_mode WHERE secure_mode."
                        + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                        + "="
                        + Message.TABLENAME
                        + "."
                        + Message.UUID
                        + " AND secure_mode."
                        + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                        + "="
                        + Conversation.TABLENAME
                        + "."
                        + Conversation.ACCOUNT
                        + ") AND messages_index.body MATCH ?");
        if (uuid == null) {
            selectionArgs = new String[] {FtsUtils.toMatchString(term)};
        } else {
            selectionArgs = new String[] {FtsUtils.toMatchString(term), uuid};
            SQL.append(" AND " + Conversation.TABLENAME + '.' + Conversation.UUID + "=?");
        }
        SQL.append(" ORDER BY " + Message.TIME_SENT + " DESC limit " + Config.MAX_SEARCH_RESULTS);
        return db.rawQuery(SQL.toString(), selectionArgs);
    }

    @Override
    public List<String> markFileAsDeleted(final File file, final boolean internal) {
        SQLiteDatabase db = this.getReadableDatabase();
        String selection;
        String[] selectionArgs;
        if (internal) {
            final String name = file.getName();
            if (name.endsWith(".pgp")) {
                selection =
                        "("
                                + Message.RELATIVE_FILE_PATH
                                + " IN(?,?) OR ("
                                + Message.RELATIVE_FILE_PATH
                                + "=? and encryption in(1,4))) and type in (1,2,5)";
                selectionArgs =
                        new String[] {
                            file.getAbsolutePath(), name, name.substring(0, name.length() - 4)
                        };
            } else {
                selection = Message.RELATIVE_FILE_PATH + " IN(?,?) and type in (1,2,5)";
                selectionArgs = new String[] {file.getAbsolutePath(), name};
            }
        } else {
            selection = Message.RELATIVE_FILE_PATH + "=? and type in (1,2,5)";
            selectionArgs = new String[] {file.getAbsolutePath()};
        }
        final List<String> uuids = new ArrayList<>();
        Cursor cursor =
                db.query(
                        Message.TABLENAME,
                        new String[] {Message.UUID},
                        selection,
                        selectionArgs,
                        null,
                        null,
                        null);
        while (cursor != null && cursor.moveToNext()) {
            uuids.add(cursor.getString(0));
        }
        if (cursor != null) {
            cursor.close();
        }
        markFileAsDeleted(uuids);
        return uuids;
    }

    @Override
    public void markFileAsDeleted(List<String> uuids) {
        SQLiteDatabase db = this.getReadableDatabase();
        final ContentValues contentValues = new ContentValues();
        final String where = Message.UUID + "=?";
        contentValues.put(Message.DELETED, 1);
        db.beginTransaction();
        for (String uuid : uuids) {
            db.update(Message.TABLENAME, contentValues, where, new String[] {uuid});
        }
        db.setTransactionSuccessful();
        db.endTransaction();
    }

    @Override
    public void markFilesAsChanged(List<FilePathInfo> files) {
        SQLiteDatabase db = this.getReadableDatabase();
        final String where = Message.UUID + "=?";
        db.beginTransaction();
        for (FilePathInfo info : files) {
            final ContentValues contentValues = new ContentValues();
            contentValues.put(Message.DELETED, info.deleted ? 1 : 0);
            db.update(Message.TABLENAME, contentValues, where, new String[] {info.uuid.toString()});
        }
        db.setTransactionSuccessful();
        db.endTransaction();
    }

    @Override
    public List<FilePathInfo> getFilePathInfo() {
        final SQLiteDatabase db = this.getReadableDatabase();
        final Cursor cursor =
                db.query(
                        Message.TABLENAME,
                        new String[] {Message.UUID, Message.RELATIVE_FILE_PATH, Message.DELETED},
                        "type in (1,2,5) and " + Message.RELATIVE_FILE_PATH + " is not null",
                        null,
                        null,
                        null,
                        null);
        final List<FilePathInfo> list = new ArrayList<>();
        while (cursor != null && cursor.moveToNext()) {
            list.add(
                    new FilePathInfo(
                            cursor.getString(0), cursor.getString(1), cursor.getInt(2) > 0));
        }
        if (cursor != null) {
            cursor.close();
        }
        return list;
    }

    @Override
    public List<FilePath> getRelativeFilePaths(String account, Jid jid, int limit) {
        SQLiteDatabase db = this.getReadableDatabase();
        final String SQL =
                "select uuid,relativeFilePath from messages where type in (1,2,5) and deleted=0 and"
                        + " conversationUuid in (select uuid from conversations where"
                        + " accountUuid=? and (contactJid=? or contactJid like ?)) order by"
                        + " timeSent desc";
        final String[] args = {account, jid.toString(), jid.toString() + "/%"};
        Cursor cursor = db.rawQuery(SQL + (limit > 0 ? " limit " + limit : ""), args);
        List<FilePath> filesPaths = new ArrayList<>();
        while (cursor.moveToNext()) {
            filesPaths.add(new FilePath(cursor.getString(0), cursor.getString(1)));
        }
        cursor.close();
        return filesPaths;
    }

    @Override
    public List<AttachmentMessageRecord> getAttachmentMessageRecords(
            final String account,
            final Jid jid,
            final long beforeTimeSent,
            final String beforeMessageUuid,
            final int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("attachment page limit must be positive");
        }
        final StringBuilder sql =
                new StringBuilder(
                        "SELECT uuid,conversationUuid,relativeFilePath,body,type,timeSent"
                                + " FROM messages"
                                + " WHERE type IN (1,2,5)"
                                + " AND deleted=0"
                                + " AND conversationUuid IN (SELECT uuid FROM conversations"
                                + " WHERE accountUuid=? AND (contactJid=? OR contactJid LIKE ?))");
        final ArrayList<String> args = new ArrayList<>();
        args.add(account);
        args.add(jid.toString());
        args.add(jid.toString() + "/%");
        if (beforeMessageUuid != null) {
            sql.append(
                    " AND (timeSent < ? OR (timeSent = ? AND uuid < ?))");
            args.add(Long.toString(beforeTimeSent));
            args.add(Long.toString(beforeTimeSent));
            args.add(beforeMessageUuid);
        }
        sql.append(" ORDER BY timeSent DESC, uuid DESC LIMIT ?");
        args.add(Integer.toString(limit));

        final ArrayList<AttachmentMessageRecord> records = new ArrayList<>();
        try (final Cursor cursor =
                getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (cursor.moveToNext()) {
                records.add(
                        new AttachmentMessageRecord(
                                cursor.getString(0),
                                cursor.getString(1),
                                cursor.isNull(2) ? null : cursor.getString(2),
                                cursor.isNull(3) ? null : cursor.getString(3),
                                cursor.getInt(4),
                                cursor.getLong(5)));
            }
        }
        return records;
    }

    @Override
    public Map<Integer, FilePath> getRelativeFilePathsForConversationForMonth(String conversationUuid, int year, int month) {
        final var db = this.getReadableDatabase();

        Map<Integer, FilePath> dayToFilePath = new HashMap<>();

        // Calculate the start and end timestamps for the given month
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month - 1, 1, 0, 0, 0); // Month is 0-indexed in Calendar
        calendar.set(Calendar.MILLISECOND, 0);
        long startTimeMillis = calendar.getTimeInMillis();

        calendar.add(Calendar.MONTH, 1);
        calendar.add(Calendar.MILLISECOND, -1);
        long endTimeMillis = calendar.getTimeInMillis();

        long offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;

        String sql = "SELECT " +
                Message.UUID + ", " +
                Message.RELATIVE_FILE_PATH + ", " +
                "CAST(strftime('%d', " + Message.TIME_SENT + " / 1000 + " + offset +", 'unixepoch') AS INTEGER) AS day_of_month, " +
                "COUNT(" + Message.UUID + ") AS message_count " +
                "FROM " + Message.TABLENAME + " " +
                "WHERE " + Message.CONVERSATION + " = ? " +
                "AND " + Message.TIME_SENT + " >= ? " +
                "AND " + Message.TIME_SENT + " <= ? " +
                "AND " + Message.DELETED + " = 0 " +
                "AND " + Message.RELATIVE_FILE_PATH + " IS NOT NULL " +
                "GROUP BY day_of_month " +
                "ORDER BY day_of_month ASC;";

        String[] selectionArgs = {
                conversationUuid,
                String.valueOf(startTimeMillis),
                String.valueOf(endTimeMillis)
        };

        Cursor cursor = db.rawQuery(sql, selectionArgs);

        if (cursor != null) {
            try {
                int dayOfMonthIndex = cursor.getColumnIndex("day_of_month");
                int messageCountIndex = cursor.getColumnIndex("message_count");
                int uuidIndex = cursor.getColumnIndex(Message.UUID);
                int relativePathIndex = cursor.getColumnIndex(Message.RELATIVE_FILE_PATH);

                if (dayOfMonthIndex != -1 && messageCountIndex != -1) {
                    while (cursor.moveToNext()) {
                        int day = cursor.getInt(dayOfMonthIndex);
                        String uuid = cursor.getString(uuidIndex);
                        String relativePath = cursor.getString(relativePathIndex);
                        dayToFilePath.put(day, new FilePath(uuid, relativePath));
                    }
                }
            } finally {
                cursor.close();
            }
        }
        return dayToFilePath;


        /*Calendar calendar = Calendar.getInstance();
        calendar.set(year, month - 1, 1, 0, 0, 0); // Month is 0-indexed in Calendar
        calendar.set(Calendar.MILLISECOND, 0);
        long startTimeMillis = calendar.getTimeInMillis();

        calendar.add(Calendar.MONTH, 1);
        calendar.add(Calendar.MILLISECOND, -1);
        long endTimeMillis = calendar.getTimeInMillis();

        SQLiteDatabase db = this.getReadableDatabase();
        final String SQL =
                "select uuid,relativeFilePath from messages where type in (1,2,5) and deleted=0 and"
                        + " "
                        + Message.RELATIVE_FILE_PATH
                        + " is not null and conversationUuid=? AND " + Message.TIME_SENT + " >= ? AND " + Message.TIME_SENT + " <= ?  order by"
                        + " timeSent desc";
        String[] args = {
                uuid,
                String.valueOf(startTimeMillis),
                String.valueOf(endTimeMillis)
        };
        Cursor cursor = db.rawQuery(SQL, args);
        List<FilePath> filesPaths = new ArrayList<>();
        while (cursor.moveToNext()) {
            filesPaths.add(new FilePath(cursor.getString(0), cursor.getString(1)));
        }
        cursor.close();
        return filesPaths; */
    }

    @Override
    public boolean recordMessageModeration(final Conversation conversation, final String roomStanzaId,
            final String by, final String reason, final long stamp) {
        if (conversation == null || conversation.getMode() != Conversation.MODE_MULTI
                || roomStanzaId == null || roomStanzaId.isEmpty()) return false;
        final SQLiteDatabase db = getWritableDatabase();
        try (final Cursor cursor =
                db.rawQuery(
                        "SELECT COUNT(*) FROM "
                                + Message.TABLENAME
                                + " WHERE "
                                + Message.CONVERSATION
                                + "=? AND "
                                + Message.ROOM_STANZA_ID
                                + "=?",
                        new String[] {conversation.getUuid(), roomStanzaId})) {
            if (cursor.moveToFirst() && cursor.getInt(0) > 1) {
                Log.w(Config.LOGTAG, "refusing ambiguous MUC moderation target");
                return false;
            }
        }
        final ContentValues marker = new ContentValues();
        marker.put("account_uuid", conversation.getAccount().getUuid());
        marker.put("conversation_uuid", conversation.getUuid());
        marker.put("room_stanza_id", roomStanzaId);
        marker.put("moderated_by", by);
        marker.put("reason", reason);
        marker.put("moderated_at", stamp);
        final ContentValues scrubbed = moderationScrubValues(by, reason, stamp);
        db.beginTransaction();
        try {
            final long inserted = db.insertWithOnConflict(
                    MODERATION_TABLENAME, null, marker, SQLiteDatabase.CONFLICT_IGNORE);
            if (inserted == -1 && getMessageModeration(conversation, roomStanzaId) == null) {
                return false;
            }
            db.update(Message.TABLENAME, scrubbed,
                    Message.CONVERSATION + "=? AND " + Message.ROOM_STANZA_ID + "=?",
                    new String[]{conversation.getUuid(), roomStanzaId});
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    static ContentValues moderationScrubValues(
            final String by, final String reason, final long stamp) {
        final ContentValues scrubbed = new ContentValues();
        scrubbed.put(Message.MODERATED, 1);
        scrubbed.put(Message.MODERATED_BY, by);
        scrubbed.put(Message.MODERATION_REASON, reason);
        scrubbed.put(Message.MODERATED_AT, stamp);
        scrubbed.put(Message.MODERATION_RETIRED, 0);
        scrubbed.put(Message.BODY, "");
        scrubbed.put(Message.TYPE, Message.TYPE_TEXT);
        scrubbed.putNull(Message.RELATIVE_FILE_PATH);
        scrubbed.putNull(Message.MEDIA_GROUP_ID);
        scrubbed.putNull(Message.PAYLOADS);
        scrubbed.putNull(Message.REACTIONS);
        scrubbed.put(Message.OOB, 0);
        return scrubbed;
    }

    @Override
    public List<String[]> getPendingModeratedMessageIds() {
        final List<String[]> pending = new ArrayList<>();
        final String sql = "SELECT conversation." + Conversation.ACCOUNT + ",message." + Message.UUID
                + " FROM " + Message.TABLENAME + " message JOIN " + Conversation.TABLENAME
                + " conversation ON message." + Message.CONVERSATION + "=conversation."
                + Conversation.UUID + " WHERE message." + Message.MODERATED + "=1 AND message."
                + Message.MODERATION_RETIRED + "=0";
        try (final Cursor cursor = getReadableDatabase().rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                pending.add(new String[]{cursor.getString(0), cursor.getString(1)});
            }
        }
        return pending;
    }

    @Override
    public void markMessageModerationRetired(final String accountUuid, final String messageUuid) {
        final ContentValues values = new ContentValues();
        values.put(Message.MODERATION_RETIRED, 1);
        getWritableDatabase().update(Message.TABLENAME, values,
                Message.UUID + "=? AND " + Message.CONVERSATION
                        + " IN (SELECT " + Conversation.UUID + " FROM " + Conversation.TABLENAME
                        + " WHERE " + Conversation.ACCOUNT + "=?)",
                new String[]{messageUuid, accountUuid});
    }

    @Override
    public Moderation getMessageModeration(
            final Conversation conversation, final String roomStanzaId) {
        if (conversation == null || roomStanzaId == null || roomStanzaId.isEmpty()) return null;
        try (final Cursor cursor = getReadableDatabase().query(
                MODERATION_TABLENAME,
                new String[]{"moderated_by", "reason", "moderated_at"},
                "account_uuid=? AND conversation_uuid=? AND room_stanza_id=?",
                new String[]{conversation.getAccount().getUuid(), conversation.getUuid(), roomStanzaId},
                null, null, null)) {
            return cursor.moveToFirst()
                    ? new Moderation(cursor.getString(0), cursor.getString(1), cursor.getLong(2))
                    : null;
        }
    }

    private static final int MAX_PENDING_RETRACTIONS_PER_ACCOUNT = 1024;
    private static final int MAX_PENDING_RETRACTIONS_PER_ROOM = 128;
    private static final int MAX_PENDING_RETRACTIONS_PER_SENDER = 32;

    static boolean hasRetractionJournalCapacity(
            final long accountPending,
            final long roomPending,
            final long senderPending) {
        return accountPending >= 0
                && accountPending < MAX_PENDING_RETRACTIONS_PER_ACCOUNT
                && roomPending >= 0
                && roomPending < MAX_PENDING_RETRACTIONS_PER_ROOM
                && senderPending >= 0
                && senderPending < MAX_PENDING_RETRACTIONS_PER_SENDER;
    }

    private static long countPendingMucRetractions(
            final SQLiteDatabase db,
            final String accountUuid,
            final String conversationUuid,
            final String senderFullJid) {
        final StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM " + RETRACTION_TABLENAME
                        + " WHERE account_uuid=? AND state=0");
        final List<String> args = new ArrayList<>();
        args.add(accountUuid);

        if (conversationUuid != null) {
            sql.append(" AND conversation_uuid=?");
            args.add(conversationUuid);
        }
        if (senderFullJid != null) {
            sql.append(" AND sender_full_jid=?");
            args.add(senderFullJid);
        }

        try (final Cursor cursor = db.rawQuery(
                sql.toString(), args.toArray(new String[0]))) {
            return cursor.moveToFirst() ? cursor.getLong(0) : Long.MAX_VALUE;
        }
    }

    static boolean validUnverifiedMucRetraction(
            final Conversation room,
            final String requestId,
            final String targetRoomStanzaId,
            final Jid senderFullJid,
            final String senderOccupantId,
            final long eventTime) {
        return room != null
                && room.getMode() == Conversation.MODE_MULTI
                && room.getJid() != null
                && senderFullJid != null
                && !senderFullJid.isBareJid()
                && room.getJid().asBareJid()
                        .equals(senderFullJid.asBareJid())
                && requestId != null
                && !requestId.isBlank()
                && requestId.length() <= 512
                && targetRoomStanzaId != null
                && !targetRoomStanzaId.isBlank()
                && targetRoomStanzaId.length() <= 512
                && senderFullJid.toString().length() <= 1024
                && (senderOccupantId == null
                        || MucOptions.isValidOccupantIdValue(senderOccupantId))
                && eventTime > 0;
    }

    @Override
    public boolean recordUnverifiedMucRetraction(
            final Conversation room,
            final String requestId,
            final String targetRoomStanzaId,
            final Jid senderFullJid,
            final String senderOccupantId,
            final long eventTime) {
        if (!validUnverifiedMucRetraction(
                room, requestId, targetRoomStanzaId,
                senderFullJid, senderOccupantId, eventTime)) {
            return false;
        }

        final SQLiteDatabase db = getWritableDatabase();
        final String accountUuid = room.getAccount().getUuid();
        final String conversationUuid = room.getUuid();

        final ContentValues values = new ContentValues();
        values.put("account_uuid", accountUuid);
        values.put("conversation_uuid", conversationUuid);
        values.put("retraction_request_id", requestId);
        values.put("target_room_stanza_id", targetRoomStanzaId);
        values.put("sender_full_jid", senderFullJid.toString());
        values.put("sender_occupant_id", senderOccupantId);
        values.put("event_time", eventTime);
        values.put("state", 0);

        db.beginTransaction();
        try {
            // Check an existing request before enforcing capacity.
            // Exact replays remain idempotent even when the journal is full.
            try (final Cursor cursor = db.query(
                    RETRACTION_TABLENAME,
                    new String[] {
                            "target_room_stanza_id",
                            "sender_full_jid",
                            "sender_occupant_id"
                    },
                    "account_uuid=? AND conversation_uuid=?"
                            + " AND retraction_request_id=?",
                    new String[] {
                            accountUuid, conversationUuid, requestId
                    },
                    null, null, null)) {
                if (cursor.moveToFirst()) {
                    final boolean same =
                            targetRoomStanzaId.equals(cursor.getString(0))
                            && senderFullJid.toString().equals(
                                    cursor.getString(1))
                            && java.util.Objects.equals(
                                    senderOccupantId, cursor.getString(2));
                    if (same) {
                        db.setTransactionSuccessful();
                    }
                    return same;
                }
            }

            final long accountPending = countPendingMucRetractions(
                    db, accountUuid, null, null);
            final long roomPending = countPendingMucRetractions(
                    db, accountUuid, conversationUuid, null);
            final long senderPending = countPendingMucRetractions(
                    db, accountUuid, conversationUuid,
                    senderFullJid.toString());

            if (!hasRetractionJournalCapacity(
                    accountPending, roomPending, senderPending)) {
                return false;
            }

            final long inserted = db.insertWithOnConflict(
                    RETRACTION_TABLENAME, null, values,
                    SQLiteDatabase.CONFLICT_IGNORE);
            if (inserted == -1) {
                return false;
            }

            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    static boolean canVerifyMucRetraction(
            final Conversation room,
            final PendingRetraction pending,
            final Message original) {
        if (room == null || pending == null || original == null
                || pending.requestId == null
                || pending.requestId.isBlank()
                || pending.targetRoomStanzaId == null
                || pending.targetRoomStanzaId.isBlank()
                || pending.senderFullJid == null
                || pending.eventTime <= 0) {
            return false;
        }

        final Jid sender;
        try {
            sender = Jid.of(pending.senderFullJid);
        } catch (final RuntimeException invalidJid) {
            return false;
        }

        return MessageRetractionPolicy.isAuthorizedIncomingMucRetraction(
                room,
                original,
                pending.targetRoomStanzaId,
                sender,
                pending.senderOccupantId);
    }

    /**
     * Select only journal entries still tied to a scrubbed message
     * in the same account and conversation.
     */
    private static String pendingRetractionRetirementSource() {
        return " FROM " + RETRACTION_TABLENAME + " r"
                + " JOIN " + Message.TABLENAME + " m"
                + " ON m." + Message.UUID
                + "=r.target_message_uuid"
                + " AND m." + Message.CONVERSATION
                + "=r.conversation_uuid"
                + " AND m." + Message.ROOM_STANZA_ID
                + "=r.target_room_stanza_id"
                + " JOIN " + Conversation.TABLENAME + " c"
                + " ON c." + Conversation.UUID
                + "=r.conversation_uuid"
                + " AND c." + Conversation.ACCOUNT
                + "=r.account_uuid"
                + " WHERE r.state=2"
                + " AND m." + Message.RETRACTED + "=1"
                + " AND m." + Message.RETRACTION_RETIRED + "=0"
                + " AND m." + Message.BODY + "=''"
                + " AND m." + Message.RELATIVE_FILE_PATH + " IS NULL"
                + " AND m." + Message.MEDIA_GROUP_ID + " IS NULL"
                + " AND m." + Message.PAYLOADS + " IS NULL"
                + " AND m." + Message.REACTIONS + " IS NULL"
                + " AND m." + Message.OOB + "=0";
    }

    @Override
    public List<PendingRetractionRetirement>
            getVerifiedMucRetractionsToResume() {
        final List<PendingRetractionRetirement> jobs =
                new ArrayList<>();

        final String sql =
                "SELECT r.account_uuid,"
                        + "r.conversation_uuid,"
                        + "r.retraction_request_id,"
                        + "r.target_message_uuid"
                        + " FROM " + RETRACTION_TABLENAME + " r"
                        + " JOIN " + Conversation.TABLENAME + " c"
                        + " ON c." + Conversation.UUID
                        + "=r.conversation_uuid"
                        + " AND c." + Conversation.ACCOUNT
                        + "=r.account_uuid"
                        + " JOIN " + Message.TABLENAME + " m"
                        + " ON m." + Message.UUID
                        + "=r.target_message_uuid"
                        + " AND m." + Message.CONVERSATION
                        + "=r.conversation_uuid"
                        + " AND m." + Message.ROOM_STANZA_ID
                        + "=r.target_room_stanza_id"
                        + " WHERE r.state=1"
                        + " AND m." + Message.RETRACTED + "=0"
                        + " AND m." + Message.MODERATED + "=0"
                        + " ORDER BY r.event_time ASC"
                        + " LIMIT 128";

        try (final Cursor cursor =
                getReadableDatabase().rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                jobs.add(new PendingRetractionRetirement(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3)));
            }
        }

        return jobs;
    }

    @Override
    public List<PendingRetractionRetirement>
            getPendingMucRetractionRetirements() {
        final List<PendingRetractionRetirement> jobs =
                new ArrayList<>();
        final String sql =
                "SELECT r.account_uuid,"
                        + "r.conversation_uuid,"
                        + "r.retraction_request_id,"
                        + "r.target_message_uuid"
                        + pendingRetractionRetirementSource()
                        + " ORDER BY r.event_time ASC,"
                        + "r.retraction_request_id ASC LIMIT 128";

        try (final Cursor cursor =
                getReadableDatabase().rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                jobs.add(new PendingRetractionRetirement(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3)));
            }
        }
        return jobs;
    }

    @Override
    public boolean completeMucRetractionRetirement(
            final PendingRetractionRetirement job) {
        if (job == null
                || job.accountUuid == null
                || job.accountUuid.isBlank()
                || job.conversationUuid == null
                || job.conversationUuid.isBlank()
                || job.requestId == null
                || job.requestId.isBlank()
                || job.messageUuid == null
                || job.messageUuid.isBlank()) {
            return false;
        }

        final SQLiteDatabase db = getWritableDatabase();

        db.beginTransaction();
        try {
            final String targetRoomId;

            final String lookup =
                    "SELECT r.target_room_stanza_id"
                            + pendingRetractionRetirementSource()
                            + " AND r.account_uuid=?"
                            + " AND r.conversation_uuid=?"
                            + " AND r.retraction_request_id=?"
                            + " AND r.target_message_uuid=?";

            try (final Cursor cursor = db.rawQuery(
                    lookup,
                    new String[] {
                            job.accountUuid,
                            job.conversationUuid,
                            job.requestId,
                            job.messageUuid
                    })) {
                if (!cursor.moveToFirst()) {
                    return false;
                }
                targetRoomId = cursor.getString(0);
                if (targetRoomId == null || cursor.moveToNext()) {
                    return false;
                }
            }

            final ContentValues messageState =
                    new ContentValues();
            messageState.put(Message.RETRACTION_RETIRED, 1);

            final int messageUpdated = db.update(
                    Message.TABLENAME,
                    messageState,
                    Message.UUID + "=? AND "
                            + Message.CONVERSATION + "=? AND "
                            + Message.ROOM_STANZA_ID + "=? AND "
                            + Message.RETRACTED + "=1 AND "
                            + Message.RETRACTION_RETIRED + "=0",
                    new String[] {
                            job.messageUuid,
                            job.conversationUuid,
                            targetRoomId
                    });

            if (messageUpdated != 1) {
                return false;
            }

            final ContentValues eventState =
                    new ContentValues();
            eventState.put("state", 3);

            final int journalUpdated = db.update(
                    RETRACTION_TABLENAME,
                    eventState,
                    "account_uuid=? AND conversation_uuid=?"
                            + " AND retraction_request_id=?"
                            + " AND target_message_uuid=?"
                            + " AND target_room_stanza_id=?"
                            + " AND state=2",
                    new String[] {
                            job.accountUuid,
                            job.conversationUuid,
                            job.requestId,
                            job.messageUuid,
                            targetRoomId
                    });

            if (journalUpdated != 1) {
                // Both SQL changes are rolled back.
                return false;
            }

            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    static ContentValues retractionScrubValues() {
        final ContentValues values = new ContentValues();
        values.put(Message.RETRACTED, 1);
        values.put(Message.RETRACTION_RETIRED, 0);
        values.put(Message.BODY, "");
        values.put(Message.TYPE, Message.TYPE_TEXT);
        values.putNull(Message.BODY_LANGUAGE);
        values.putNull(Message.RELATIVE_FILE_PATH);
        values.putNull(Message.MEDIA_GROUP_ID);
        values.putNull(Message.PAYLOADS);
        values.putNull(Message.REACTIONS);
        values.put(Message.OOB, 0);
        // Keep roomStanzaId, origin identity, and request deduplication IDs.
        // Never alter XEP-0425 moderation state here.
        return values;
    }

    @Override
    public boolean beginVerifiedMucRetractionRetirement(
            final Conversation room, final String requestId) {
        if (room == null
                || room.getMode() != Conversation.MODE_MULTI
                || room.getAccount() == null
                || requestId == null
                || requestId.isBlank()
                || requestId.length() > 512) {
            return false;
        }

        final String accountUuid = room.getAccount().getUuid();
        final String conversationUuid = room.getUuid();
        final SQLiteDatabase db = getWritableDatabase();

        db.beginTransaction();
        try {
            // Ensure that this persisted conversation belongs to the
            // account for which the journal request was verified.
            try (final Cursor owner = db.query(
                    Conversation.TABLENAME,
                    new String[] {Conversation.ACCOUNT},
                    Conversation.UUID + "=?",
                    new String[] {conversationUuid},
                    null, null, null)) {
                if (!owner.moveToFirst()
                        || !accountUuid.equals(owner.getString(0))) {
                    return false;
                }
            }

            final String targetRoomId;
            final String targetUuid;

            try (final Cursor event = db.query(
                    RETRACTION_TABLENAME,
                    new String[] {
                            "target_room_stanza_id",
                            "target_message_uuid"
                    },
                    "account_uuid=? AND conversation_uuid=?"
                            + " AND retraction_request_id=? AND state=1",
                    new String[] {
                            accountUuid, conversationUuid, requestId
                    },
                    null, null, null)) {
                if (!event.moveToFirst()) {
                    return false;
                }
                targetRoomId = event.getString(0);
                targetUuid = event.getString(1);
            }

            if (targetUuid == null || targetUuid.isBlank()
                    || targetRoomId == null || targetRoomId.isBlank()) {
                return false;
            }

            // Recheck identity and uniqueness inside this transaction.
            // A room stanza ID must resolve to exactly one message.
            try (final Cursor target = db.query(
                    Message.TABLENAME,
                    new String[] {
                            Message.UUID,
                            Message.MODERATED,
                            Message.RETRACTED,
                            Message.TYPE
                    },
                    Message.CONVERSATION + "=? AND "
                            + Message.ROOM_STANZA_ID + "=?",
                    new String[] {
                            conversationUuid, targetRoomId
                    },
                    null, null, null, "2")) {

                if (!target.moveToFirst()
                        || !targetUuid.equals(target.getString(0))
                        || target.getInt(1) != 0
                        || target.getInt(2) != 0) {
                    return false;
                }

                final int type = target.getInt(3);
                if (type != Message.TYPE_TEXT
                        && type != Message.TYPE_IMAGE
                        && type != Message.TYPE_FILE) {
                    return false;
                }

                if (target.moveToNext()) {
                    // Ambiguous room-assigned ID: never erase either.
                    return false;
                }
            }

            final int messageUpdated = db.update(
                    Message.TABLENAME,
                    retractionScrubValues(),
                    Message.UUID + "=? AND "
                            + Message.CONVERSATION + "=? AND "
                            + Message.ROOM_STANZA_ID + "=? AND "
                            + Message.MODERATED + "=0 AND "
                            + Message.RETRACTED + "=0",
                    new String[] {
                            targetUuid, conversationUuid, targetRoomId
                    });

            if (messageUpdated != 1) {
                return false;
            }

            final ContentValues transition = new ContentValues();
            transition.put("state", 2);

            final int eventUpdated = db.update(
                    RETRACTION_TABLENAME,
                    transition,
                    "account_uuid=? AND conversation_uuid=?"
                            + " AND retraction_request_id=?"
                            + " AND target_room_stanza_id=?"
                            + " AND target_message_uuid=? AND state=1",
                    new String[] {
                            accountUuid, conversationUuid,
                            requestId, targetRoomId, targetUuid
                    });

            if (eventUpdated != 1) {
                // No setTransactionSuccessful(): message scrub rolls back.
                return false;
            }

            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public boolean verifyUnverifiedMucRetraction(
            final Conversation room, final String requestId) {
        if (room == null
                || room.getMode() != Conversation.MODE_MULTI
                || requestId == null
                || requestId.isBlank()
                || requestId.length() > 512) {
            return false;
        }

        final String accountUuid = room.getAccount().getUuid();
        final String conversationUuid = room.getUuid();
        final SQLiteDatabase db = getWritableDatabase();

        db.beginTransaction();
        try {
            final PendingRetraction pending;

            try (final Cursor cursor = db.query(
                    RETRACTION_TABLENAME,
                    new String[] {
                            "retraction_request_id",
                            "target_room_stanza_id",
                            "sender_full_jid",
                            "sender_occupant_id",
                            "event_time",
                            "state",
                            "target_message_uuid"
                    },
                    "account_uuid=? AND conversation_uuid=?"
                            + " AND retraction_request_id=?",
                    new String[] {
                            accountUuid, conversationUuid, requestId
                    },
                    null, null, null)) {

                if (!cursor.moveToFirst()
                        || cursor.getInt(5) != 0
                        || !cursor.isNull(6)) {
                    return false;
                }

                pending = new PendingRetraction(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getLong(4));
            }

            final Message original;

            // Fail closed when room identity is missing or ambiguous.
            try (final Cursor cursor = db.query(
                    Message.TABLENAME,
                    null,
                    Message.CONVERSATION + "=? AND "
                            + Message.ROOM_STANZA_ID + "=?",
                    new String[] {
                            conversationUuid, pending.targetRoomStanzaId
                    },
                    null, null, null, "2")) {

                if (!cursor.moveToFirst()) {
                    return false;
                }

                original = Message.fromCursor(cursor, room);

                if (cursor.moveToNext()) {
                    return false;
                }
            }

            if (!canVerifyMucRetraction(room, pending, original)) {
                return false;
            }

            final ContentValues values = new ContentValues();
            values.put("target_message_uuid", original.getUuid());
            values.put("state", 1);

            final int updated = db.update(
                    RETRACTION_TABLENAME,
                    values,
                    "account_uuid=? AND conversation_uuid=?"
                            + " AND retraction_request_id=?"
                            + " AND target_room_stanza_id=?"
                            + " AND sender_full_jid=?"
                            + " AND state=0"
                            + " AND target_message_uuid IS NULL",
                    new String[] {
                            accountUuid,
                            conversationUuid,
                            requestId,
                            pending.targetRoomStanzaId,
                            pending.senderFullJid
                    });

            if (updated != 1) {
                return false;
            }

            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public List<PendingRetraction> getUnverifiedMucRetractions(
            final Conversation room, final String targetRoomStanzaId) {
        if (room == null
                || room.getMode() != Conversation.MODE_MULTI
                || targetRoomStanzaId == null
                || targetRoomStanzaId.isBlank()) {
            return Collections.emptyList();
        }

        final List<PendingRetraction> result = new ArrayList<>();

        try (final Cursor cursor = getReadableDatabase().query(
                RETRACTION_TABLENAME,
                new String[] {
                        "retraction_request_id",
                        "target_room_stanza_id",
                        "sender_full_jid",
                        "sender_occupant_id",
                        "event_time"
                },
                "account_uuid=? AND conversation_uuid=?"
                        + " AND target_room_stanza_id=? AND state=0",
                new String[] {
                        room.getAccount().getUuid(),
                        room.getUuid(),
                        targetRoomStanzaId
                },
                null, null,
                "event_time ASC, retraction_request_id ASC",
                "128")) {
            while (cursor.moveToNext()) {
                result.add(new PendingRetraction(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getLong(4)));
            }
        }
        return result;
    }

    @Override
    public boolean hasVerifiedMucRetractionForRoomStanzaId(
            final Conversation room, final String roomStanzaId) {
        if (room == null
                || room.getAccount() == null
                || room.getMode() != Conversation.MODE_MULTI
                || roomStanzaId == null
                || roomStanzaId.isBlank()) {
            return false;
        }

        final String sql =
                "SELECT 1 FROM " + RETRACTION_TABLENAME + " r"
                        + " JOIN " + Message.TABLENAME + " m"
                        + " ON m." + Message.UUID
                        + "=r.target_message_uuid"
                        + " AND m." + Message.CONVERSATION
                        + "=r.conversation_uuid"
                        + " AND m." + Message.ROOM_STANZA_ID
                        + "=r.target_room_stanza_id"
                        + " WHERE r.account_uuid=?"
                        + " AND r.conversation_uuid=?"
                        + " AND r.target_room_stanza_id=?"
                        + " AND r.state BETWEEN 1 AND 3"
                        + " LIMIT 1";

        try (final Cursor cursor =
                getReadableDatabase().rawQuery(
                        sql,
                        new String[] {
                                room.getAccount().getUuid(),
                                room.getUuid(),
                                roomStanzaId
                        })) {
            return cursor.moveToFirst();
        }
    }

    /**
     * A VERIFIED journal entry must not become visible while the
     * asynchronous SQLCipher/SCS retirement is still pending.
     */
    static String verifiedRetractionVisibilityFilter() {
        return " AND NOT EXISTS (SELECT 1 FROM "
                + RETRACTION_TABLENAME + " vr"
                + " WHERE vr.conversation_uuid="
                + Message.TABLENAME + "." + Message.CONVERSATION
                + " AND vr.target_message_uuid="
                + Message.TABLENAME + "." + Message.UUID
                + " AND vr.state BETWEEN 1 AND 3)";
    }

    @Override
    public boolean isMessageRetracted(
            final String accountUuid, final String messageUuid) {
        if (accountUuid == null || accountUuid.isBlank()
                || messageUuid == null || messageUuid.isBlank()) {
            return false;
        }

        final String sql =
                "SELECT m." + Message.RETRACTED
                        + " FROM " + Message.TABLENAME + " m"
                        + " JOIN " + Conversation.TABLENAME + " c"
                        + " ON c." + Conversation.UUID
                        + "=m." + Message.CONVERSATION
                        + " WHERE m." + Message.UUID + "=?"
                        + " AND c." + Conversation.ACCOUNT + "=?"
                        + " LIMIT 1";

        try (final Cursor cursor =
                getReadableDatabase().rawQuery(
                        sql, new String[] {messageUuid, accountUuid})) {
            return cursor.moveToFirst() && cursor.getInt(0) != 0;
        }
    }

    @Override
    public Message getMessageWithRoomStanzaId(
            final Conversation conversation, final String roomStanzaId) {
        if (conversation == null || roomStanzaId == null || roomStanzaId.isEmpty()) return null;
        try (final Cursor cursor = getReadableDatabase().query(
                Message.TABLENAME, null,
                Message.CONVERSATION + "=? AND " + Message.ROOM_STANZA_ID + "=?",
                new String[]{conversation.getUuid(), roomStanzaId}, null, null, null, "2")) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            final Message message = Message.fromCursor(cursor, conversation);
            return cursor.moveToNext() ? null : message;
        }
    }

    @Override
    public Message getMessageWithServerMsgId(
            final Conversation conversation, final String messageId) {
        final var db = this.getReadableDatabase();
        final String sql =
                "select * from messages where conversationUuid=? and serverMsgId=? LIMIT 1";
        final String[] args = {conversation.getUuid(), messageId};
        final Cursor cursor = db.rawQuery(sql, args);
        if (cursor == null) {
            return null;
        }
        final Message message;
        if (cursor.moveToFirst()) {
            message = Message.fromCursor(cursor, conversation);
        } else {
            message = null;
        }
        cursor.close();
        return message;
    }

    @Override
    public Message getMessageWithUuidOrRemoteId(
            final Conversation conversation, final String messageId) {
        final var db = this.getReadableDatabase();
        final String sql =
                "select * from messages where conversationUuid=? and (uuid=? OR remoteMsgId=?)"
                        + " LIMIT 1";
        final String[] args = {conversation.getUuid(), messageId, messageId};
        final Cursor cursor = db.rawQuery(sql, args);
        if (cursor == null) {
            return null;
        }
        final Message message;
        if (cursor.moveToFirst()) {
            message = Message.fromCursor(cursor, conversation);
        } else {
            message = null;
        }
        cursor.close();
        return message;
    }

    @Override
    public Message getMessageWithRemoteMsgId(
            final Conversation conversation, final String remoteMsgId) {
        final var db = this.getReadableDatabase();
        final String sql =
                "select * from messages where conversationUuid=? and remoteMsgId=? LIMIT 1";
        final String[] args = {conversation.getUuid(), remoteMsgId};
        final Cursor cursor = db.rawQuery(sql, args);
        if (cursor == null) {
            return null;
        }
        final Message message;
        if (cursor.moveToFirst()) {
            message = Message.fromCursor(cursor, conversation);
        } else {
            message = null;
        }
        cursor.close();
        return message;
    }

    @Override
    public Conversation findConversation(final String uuid) {
        final var db = this.getReadableDatabase();
        final String[] selectionArgs = {uuid};
        try (final Cursor cursor =
                db.query(
                        Conversation.TABLENAME,
                        null,
                        Conversation.UUID + "=?",
                        selectionArgs,
                        null,
                        null,
                        null)) {
            if (cursor.getCount() == 0) {
                return null;
            }
            cursor.moveToFirst();
            final Conversation conversation = Conversation.fromCursor(cursor);
            if (conversation.getJid() instanceof Jid.Invalid) {
                return null;
            }
            return conversation;
        }
    }

    @Override
    public Conversation findConversation(final Account account, final Jid contactJid, final Jid counterpart) {
        SQLiteDatabase db = this.getReadableDatabase();

        if (counterpart != null) {
            String[] selectionArgs = {account.getUuid(),
                    contactJid.asBareJid().toString() + "/%",
                    contactJid.asBareJid().toString(),
                    counterpart.toString()
            };
            try (final Cursor cursor = db.query(Conversation.TABLENAME, null,
                    Conversation.ACCOUNT + "=? AND (" + Conversation.CONTACTJID
                            + " like ? OR " + Conversation.CONTACTJID + "=?) AND " + Conversation.NEXT_COUNTERPART + "=?", selectionArgs, null, null, null)) {
                if (cursor.getCount() != 0) {
                    cursor.moveToFirst();

                    final Conversation conversation = Conversation.fromCursor(cursor);
                    if (conversation.getJid() instanceof Jid.Invalid) {
                        return null;
                    }
                    conversation.setAccount(account);
                    return conversation;
                }
            }
        } else {
            String[] selectionArgs = new String[]{
                    account.getUuid(),
                    contactJid.asBareJid().toString() + "/%",
                    contactJid.asBareJid().toString()
            };

            try(final Cursor cursor = db.query(Conversation.TABLENAME, null,
                    Conversation.ACCOUNT + "=? AND (" + Conversation.CONTACTJID
                            + " like ? OR " + Conversation.CONTACTJID + "=?) AND " + Conversation.NEXT_COUNTERPART + " IS NULL", selectionArgs, null, null, null)) {
                if (cursor.getCount() == 0) {
                    return null;
                }

                cursor.moveToFirst();

                final Conversation conversation = Conversation.fromCursor(cursor);
                if (conversation.getJid() instanceof Jid.Invalid) {
                    return null;
                }
                conversation.setAccount(account);
                return conversation;
            }
        }

        return null;
    }

    @Override
    public String findConversationUuid(final Jid account, final Jid jid) {
        final SQLiteDatabase db = this.getReadableDatabase();
        final String[] selectionArgs = {
            account.getLocal(),
            account.getDomain().toString(),
            jid.asBareJid().toString() + "/%",
            jid.asBareJid().toString()
        };
        try (final Cursor cursor =
                db.rawQuery(
                        "SELECT conversations.uuid FROM conversations JOIN accounts ON"
                            + " conversations.accountUuid=accounts.uuid WHERE accounts.username=?"
                            + " AND accounts.server=? AND (contactJid=? OR contactJid LIKE ?)",
                        selectionArgs)) {
            if (cursor.getCount() == 0) {
                return null;
            }
            cursor.moveToFirst();
            return cursor.getString(0);
        }
    }

    @Override
    public boolean deleteConversation(final Account account, final Jid contactJid, final Jid counterpart) {
        SQLiteDatabase db = this.getWritableDatabase();

        if (counterpart != null) {
            String[] selectionArgs = {account.getUuid(),
                    contactJid.asBareJid().toString() + "/%",
                    contactJid.asBareJid().toString(),
                    counterpart.toString()
            };

            int rows = db.delete(Conversation.TABLENAME,
                    Conversation.ACCOUNT + "=? AND (" + Conversation.CONTACTJID
                            + " like ? OR " + Conversation.CONTACTJID + "=?) AND " + Conversation.NEXT_COUNTERPART + "=?", selectionArgs);

            return rows == 1;
        } else {
            String[] selectionArgs = new String[]{
                    account.getUuid(),
                    contactJid.asBareJid().toString() + "/%",
                    contactJid.asBareJid().toString()
            };

            int rows = db.delete(Conversation.TABLENAME,
                    Conversation.ACCOUNT + "=? AND (" + Conversation.CONTACTJID
                            + " like ? OR " + Conversation.CONTACTJID + "=?) AND " + Conversation.NEXT_COUNTERPART + " IS NULL", selectionArgs);

            return rows == 1;

        }
    }

    @Override
    public void updateConversation(final Conversation conversation) {
        final SQLiteDatabase db = this.getWritableDatabase();
        final String[] args = {conversation.getUuid()};
        db.update(
                Conversation.TABLENAME,
                conversation.getContentValues(),
                Conversation.UUID + "=?",
                args);
    }

    @Override
    public List<Account> getAccounts() {
        SQLiteDatabase db = this.getReadableDatabase();
        return getAccounts(db);
    }

    @Override
    public List<Jid> getAccountJids(final boolean enabledOnly) {
        final SQLiteDatabase db = this.getReadableDatabase();
        final List<Jid> jids = new ArrayList<>();
        final String[] columns = new String[] {Account.USERNAME, Account.SERVER};
        final String where = enabledOnly ? "not options & (1 <<1)" : null;
        try (final Cursor cursor =
                db.query(Account.TABLENAME, columns, where, null, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                jids.add(Jid.of(cursor.getString(0), cursor.getString(1), null));
            }
        } catch (final Exception e) {
            return jids;
        }
        return jids;
    }

    private List<Account> getAccounts(SQLiteDatabase db) {
        final List<Account> list = new ArrayList<>();
        try (final Cursor cursor =
                db.query(Account.TABLENAME, null, null, null, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                list.add(Account.fromCursor(cursor));
            }
        }
        return list;
    }

    @Override
    public boolean updateAccount(Account account) {
        if (!AccountSecretRuntimePersistenceV1.persistIfHydrated(
                applicationContext, account)) {
            return false;
        }
        final var db = this.getWritableDatabase();
        final String[] args = {account.getUuid()};
        final ContentValues values =
                AccountSecretPersistencePolicyV1.sanitizeForDatabase(
                        applicationContext, account.getUuid(), account.getContentValues());
        final int rows =
                db.update(Account.TABLENAME, values, Account.UUID + "=?", args);
        return rows == 1;
    }

    @Override
    public boolean scrubMigratedAccountSecrets(final String accountUuid) {
        if (accountUuid == null || accountUuid.isBlank()) {
            return false;
        }
        final SQLiteDatabase db = this.getWritableDatabase();
        final String[] args = {accountUuid};
        try (final Cursor cursor =
                db.query(
                        Account.TABLENAME,
                        new String[] {Account.KEYS},
                        Account.UUID + "=?",
                        args,
                        null,
                        null,
                        null)) {
            if (!cursor.moveToFirst()) {
                return false;
            }
            final JSONObject keys;
            try {
                keys = new JSONObject(cursor.getString(0) == null ? "{}" : cursor.getString(0));
            } catch (final JSONException e) {
                return false;
            }
            keys.remove(Account.KEY_PRE_AUTH_REGISTRATION_TOKEN);
            final ContentValues values = new ContentValues();
            values.putNull(Account.PASSWORD);
            values.putNull(Account.FAST_TOKEN);
            values.put(Account.KEYS, keys.toString());
            return db.update(Account.TABLENAME, values, Account.UUID + "=?", args) == 1;
        }
    }

    @Override
    public boolean areMigratedAccountSecretsScrubbed(final String accountUuid) {
        if (accountUuid == null || accountUuid.isBlank()) {
            return false;
        }
        final SQLiteDatabase db = this.getReadableDatabase();
        final String[] args = {accountUuid};
        try (final Cursor cursor =
                db.query(
                        Account.TABLENAME,
                        new String[] {Account.PASSWORD, Account.FAST_TOKEN, Account.KEYS},
                        Account.UUID + "=?",
                        args,
                        null,
                        null,
                        null)) {
            if (!cursor.moveToFirst()) {
                return false;
            }
            if (!cursor.isNull(0) || !cursor.isNull(1)) {
                return false;
            }
            try {
                final JSONObject keys =
                        new JSONObject(cursor.getString(2) == null ? "{}" : cursor.getString(2));
                return !keys.has(Account.KEY_PRE_AUTH_REGISTRATION_TOKEN);
            } catch (final JSONException e) {
                return false;
            }
        }
    }

    @Override
    public Map<String, String> getLegacyAccountAuthenticationSecrets(final String accountUuid) {
        final Map<String, String> result = new HashMap<>();
        if (accountUuid == null || accountUuid.isBlank()) {
            return result;
        }
        final String[] args = {accountUuid};
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                Account.TABLENAME,
                                new String[] {Account.PASSWORD, Account.FAST_TOKEN, Account.KEYS},
                                Account.UUID + "=?",
                                args,
                                null,
                                null,
                                null)) {
            if (!cursor.moveToFirst()) {
                return result;
            }
            if (!cursor.isNull(0)) {
                final String value = cursor.getString(0);
                if (value != null && !value.isEmpty()) {
                    result.put(Account.PASSWORD, value);
                }
            }
            if (!cursor.isNull(1)) {
                final String value = cursor.getString(1);
                if (value != null && !value.isEmpty()) {
                    result.put(Account.FAST_TOKEN, value);
                }
            }
            try {
                final JSONObject keys =
                        new JSONObject(cursor.getString(2) == null ? "{}" : cursor.getString(2));
                final String preAuth =
                        keys.optString(Account.KEY_PRE_AUTH_REGISTRATION_TOKEN, null);
                if (preAuth != null && !preAuth.isEmpty()) {
                    result.put(Account.KEY_PRE_AUTH_REGISTRATION_TOKEN, preAuth);
                }
            } catch (final JSONException ignored) {
                // A malformed legacy KEYS object is not permission to guess/rewrite secrets.
            }
        }
        return result;
    }

    @Override
    public boolean deleteAccount(final Account account) {
        final var db = this.getWritableDatabase();
        final String[] args = {account.getUuid()};
        final int rows = db.delete(Account.TABLENAME, Account.UUID + "=?", args);
        return rows == 1;
    }

    /**
     * A retired XEP-0424 message must never be rewritten by a stale
     * resident Message object. Retirement uses separate SQL operations.
     */
    static String activeMessageUpdateSelection() {
        return Message.UUID + "=? AND " + Message.RETRACTED + "=0";
    }

    @Override
    public boolean updateMessage(final Message message, final boolean includeBody) {
        applyModerationMarker(message);
        final var db = this.getWritableDatabase();
        final String[] args = {message.getUuid()};
        final var contentValues = message.getContentValues();
        contentValues.remove(Message.UUID);
        if (!includeBody && !message.isModerated()) {
            contentValues.remove(Message.BODY);
        } else {
            removeBodyIfDurablyProtected(message, message.getUuid(), contentValues);
        }
        final int rows = db.update(
                Message.TABLENAME,
                contentValues,
                activeMessageUpdateSelection(),
                args);
        return rows == 1;
    }

    @Override
    public boolean updateMessage(final Message message, final String uuid) {
        applyModerationMarker(message);
        final var db = this.getWritableDatabase();
        final String[] args = {uuid};
        final var contentValues = message.getContentValues();
        removeBodyIfDurablyProtected(message, uuid, contentValues);
        final int rows = db.update(
                Message.TABLENAME,
                contentValues,
                activeMessageUpdateSelection(),
                args);
        return rows == 1;
    }

    /**
     * A stale resident Message may still hold its pre-migration body after a background promotion.
     * Durable secure classification is authoritative: once any secure mode exists, generic Message
     * updates are never allowed to write body plaintext back into SQLite.
     */
    private void removeBodyIfDurablyProtected(
            final Message message,
            final String durableMessageUuid,
            final ContentValues contentValues) {
        if (message == null
                || durableMessageUuid == null
                || contentValues == null
                || !contentValues.containsKey(Message.BODY)
                || (message.getType() != Message.TYPE_TEXT
                        && message.getType() != Message.TYPE_PRIVATE)
                || message.getConversation() == null
                || message.getConversation().getAccount() == null) {
            return;
        }
        final String accountUuid = message.getConversation().getAccount().getUuid();
        if (message.isModerated()) {
            contentValues.put(Message.BODY, "");
        } else if (getSecureMessagePayloadMode(accountUuid, durableMessageUuid) != null) {
            contentValues.remove(Message.BODY);
        }
    }

    @Override
    public void readRoster(Roster roster) {
        final SQLiteDatabase db = this.getReadableDatabase();
        final String[] args = {roster.getAccount().getUuid()};
        try (final Cursor cursor =
                db.query(Contact.TABLENAME, null, Contact.ACCOUNT + "=?", args, null, null, null)) {
            while (cursor.moveToNext()) {
                roster.initContact(Contact.fromCursor(cursor));
            }
        }
    }

    @Override
    public void writeRoster(final Roster roster) {
        long start = SystemClock.elapsedRealtime();
        final Account account = roster.getAccount();
        final SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        for (Contact contact : roster.getContacts()) {
            if (contact.getOption(Contact.Options.IN_ROSTER)
                    || contact.hasAvatarOrPresenceName()
                    || contact.getOption(Contact.Options.SYNCED_VIA_OTHER)) {
                db.insert(Contact.TABLENAME, null, contact.getContentValues());
            } else {
                String where = Contact.ACCOUNT + "=? AND " + Contact.JID + "=?";
                String[] whereArgs = {account.getUuid(), contact.getJid().toString()};
                db.delete(Contact.TABLENAME, where, whereArgs);
            }
        }
        db.setTransactionSuccessful();
        db.endTransaction();
        account.setRosterVersion(roster.getVersion());
        updateAccount(account);
        long duration = SystemClock.elapsedRealtime() - start;
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid() + ": persisted roster in " + duration + "ms");
    }

    @Override
    public void deleteMessageInConversation(Conversation conversation, Message message) {
        long start = SystemClock.elapsedRealtime();
        final SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        final String[] args = {conversation.getUuid(), message.getUuid()};
        int num = db.delete(Message.TABLENAME, Message.CONVERSATION + "=? AND " + Message.UUID + "=?", args);
        db.setTransactionSuccessful();
        db.endTransaction();

        Log.d(
                Config.LOGTAG,
                "deleted "
                        + num
                        + " messages for "
                        + conversation.getJid().asBareJid()
                        + " in "
                        + (SystemClock.elapsedRealtime() - start)
                        + "ms");
    }

    @Override
    public void deleteMessagesInConversation(Conversation conversation) {
        long start = SystemClock.elapsedRealtime();
        final SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        final String[] args = {conversation.getUuid()};
        int num = db.delete(Message.TABLENAME, Message.CONVERSATION + "=?", args);
        db.setTransactionSuccessful();
        db.endTransaction();
        Log.d(
                Config.LOGTAG,
                "deleted "
                        + num
                        + " messages for "
                        + conversation.getJid().asBareJid()
                        + " in "
                        + (SystemClock.elapsedRealtime() - start)
                        + "ms");
    }

    @Override
    public LocalAccountDataSnapshot snapshotLocalAccountData(final String accountUuid) {
        final long emptyConversationClearTimestamp = System.currentTimeMillis();
        final Map<String, LocalAccountDataSnapshot.ConversationClearMarker> conversations =
                new java.util.LinkedHashMap<>();
        final Set<String> messageUuids = new LinkedHashSet<>();
        final Set<String> legacyMediaPaths = new LinkedHashSet<>();
        final String sql =
                "SELECT c." + Conversation.UUID + ",m." + Message.UUID + ",m."
                        + Message.RELATIVE_FILE_PATH + ",m." + Message.TIME_SENT + ",m."
                        + Message.SERVER_MSG_ID + " FROM " + Conversation.TABLENAME + " c LEFT JOIN "
                        + Message.TABLENAME + " m ON m." + Message.CONVERSATION + "=c."
                        + Conversation.UUID + " WHERE c." + Conversation.ACCOUNT
                        + "=? ORDER BY c." + Conversation.UUID + ",m." + Message.TIME_SENT + " DESC";
        try (final Cursor cursor = getReadableDatabase().rawQuery(sql, new String[] {accountUuid})) {
            while (cursor.moveToNext()) {
                final String conversationUuid = cursor.getString(0);
                final String messageUuid = cursor.isNull(1) ? null : cursor.getString(1);
                if (!conversations.containsKey(conversationUuid)) {
                    conversations.put(
                            conversationUuid,
                            new LocalAccountDataSnapshot.ConversationClearMarker(
                                    conversationUuid,
                                    messageUuid == null
                                            ? emptyConversationClearTimestamp
                                            : cursor.getLong(3) + 1000L,
                                    messageUuid == null || cursor.isNull(4)
                                            ? null
                                            : cursor.getString(4)));
                }
                if (messageUuid != null) {
                    messageUuids.add(messageUuid);
                }
                if (!cursor.isNull(2)) {
                    legacyMediaPaths.add(cursor.getString(2));
                }
            }
        }
        return new LocalAccountDataSnapshot(
                accountUuid,
                new ArrayList<>(conversations.values()),
                new ArrayList<>(messageUuids),
                new ArrayList<>(legacyMediaPaths));
    }

    @Override
    public void deleteLocalAccountMessageData(final LocalAccountDataSnapshot snapshot) {
        final SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (final LocalAccountDataSnapshot.ConversationClearMarker marker
                    : snapshot.getConversations()) {
                final String[] selection = {
                    marker.getConversationUuid(), snapshot.getAccountUuid()
                };
                try (final Cursor cursor =
                        db.query(
                                Conversation.TABLENAME,
                                new String[] {Conversation.ATTRIBUTES},
                                Conversation.UUID + "=? AND " + Conversation.ACCOUNT + "=?",
                                selection,
                                null,
                                null,
                                null,
                                "1")) {
                    if (!cursor.moveToFirst()) {
                        continue;
                    }
                    final JSONObject attributes = new JSONObject(cursor.getString(0));
                    final String markerValue =
                            marker.getServerMessageId() == null
                                    ? Long.toString(marker.getClearTimestamp())
                                    : marker.getClearTimestamp() + ":" + marker.getServerMessageId();
                    attributes.put(Conversation.ATTRIBUTE_LAST_CLEAR_HISTORY, markerValue);
                    attributes.remove(Conversation.ATTRIBUTE_CORRECTING_MESSAGE);
                    final ContentValues values = new ContentValues();
                    values.put(Conversation.ATTRIBUTES, attributes.toString());
                    db.update(
                            Conversation.TABLENAME,
                            values,
                            Conversation.UUID + "=? AND " + Conversation.ACCOUNT + "=?",
                            selection);
                } catch (final JSONException exception) {
                    throw new IllegalStateException("Unable to persist local clear marker", exception);
                }
            }
            final String[] accountArgs = {snapshot.getAccountUuid()};
            db.delete(
                    SECURE_MESSAGE_PAYLOAD_REFERENCE_TABLENAME,
                    SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID + "=?",
                    accountArgs);
            db.delete(
                    SECURE_MESSAGE_PAYLOAD_PUBLICATION_TABLENAME,
                    SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID + "=?",
                    accountArgs);
            db.delete(
                    SECURE_MESSAGE_PAYLOAD_RETIREMENT_TABLENAME,
                    SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID + "=?",
                    accountArgs);
            db.delete(
                    SECURE_MESSAGE_PAYLOAD_MODE_TABLENAME,
                    SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID + "=?",
                    accountArgs);
            db.delete(
                    Message.TABLENAME,
                    Message.CONVERSATION + " IN (SELECT " + Conversation.UUID + " FROM "
                            + Conversation.TABLENAME + " WHERE " + Conversation.ACCOUNT + "=?)",
                    accountArgs);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void expireOldMessages(long timestamp) {
        final String[] args = {String.valueOf(timestamp)};
        SQLiteDatabase db = this.getReadableDatabase();
        db.beginTransaction();
        db.delete(Message.TABLENAME, "timeSent<?", args);
        db.setTransactionSuccessful();
        db.endTransaction();
    }

    @Override
    public MamReference getLastMessageReceived(Account account) {
        Cursor cursor = null;

        try {
            SQLiteDatabase db = this.getReadableDatabase();
            String sql =
                    "select messages.timeSent,messages.serverMsgId from accounts join conversations"
                        + " on accounts.uuid=conversations.accountUuid join messages on"
                        + " conversations.uuid=messages.conversationUuid where accounts.uuid=? and"
                        + " (messages.status=0 or messages.carbon=1 or messages.serverMsgId not"
                        + " null) and (conversations.mode=0 or (messages.serverMsgId not null and"
                        + " messages.type=4)) order by messages.timesent desc limit 1";
            String[] args = {account.getUuid()};
            cursor = db.rawQuery(sql, args);
            if (cursor.getCount() == 0) {
                return null;
            } else {
                cursor.moveToFirst();
                return new MamReference(cursor.getLong(0), cursor.getString(1));
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    @Override
    public long getLastTimeFingerprintUsed(Account account, String fingerprint) {
        String SQL =
                "select messages.timeSent from accounts join conversations on"
                        + " accounts.uuid=conversations.accountUuid join messages on"
                        + " conversations.uuid=messages.conversationUuid where accounts.uuid=? and"
                        + " messages.axolotl_fingerprint=? order by messages.timesent desc limit 1";
        String[] args = {account.getUuid(), fingerprint};
        Cursor cursor = getReadableDatabase().rawQuery(SQL, args);
        long time;
        if (cursor.moveToFirst()) {
            time = cursor.getLong(0);
        } else {
            time = 0;
        }
        cursor.close();
        return time;
    }

    @Override
    public MamReference getLastClearDate(Account account) {
        SQLiteDatabase db = this.getReadableDatabase();
        String[] columns = {Conversation.ATTRIBUTES};
        String selection = Conversation.ACCOUNT + "=?";
        String[] args = {account.getUuid()};
        Cursor cursor =
                db.query(Conversation.TABLENAME, columns, selection, args, null, null, null);
        MamReference maxClearDate = new MamReference(0);
        while (cursor.moveToNext()) {
            try {
                final JSONObject o = new JSONObject(cursor.getString(0));
                maxClearDate =
                        MamReference.max(
                                maxClearDate,
                                MamReference.fromAttribute(
                                        o.getString(Conversation.ATTRIBUTE_LAST_CLEAR_HISTORY)));
            } catch (Exception e) {
                // ignored
            }
        }
        cursor.close();
        return maxClearDate;
    }

    private Cursor getCursorForSession(Account account, SignalProtocolAddress contact) {
        final SQLiteDatabase db = this.getReadableDatabase();
        String[] selectionArgs = {
            account.getUuid(), contact.getName(), Integer.toString(contact.getDeviceId())
        };
        return db.query(
                SQLiteAxolotlStore.SESSION_TABLENAME,
                null,
                SQLiteAxolotlStore.ACCOUNT
                        + " = ? AND "
                        + SQLiteAxolotlStore.NAME
                        + " = ? AND "
                        + SQLiteAxolotlStore.DEVICE_ID
                        + " = ? ",
                selectionArgs,
                null,
                null,
                null);
    }

    @Override
    public SessionRecord loadSession(Account account, SignalProtocolAddress contact) {
        final String scope =
                "session|" + contact.getName() + "|" + contact.getDeviceId();
        byte[] serialized = null;
        try (final Cursor cursor = getCursorForSession(account, contact)) {
            if (cursor.getCount() == 0) {
                return null;
            }
            cursor.moveToFirst();
            final String legacy =
                    cursor.getString(cursor.getColumnIndex(SQLiteAxolotlStore.KEY));
            if (legacy != null) {
                if (!ScopedAccountSecretVaultV1.isAvailable(applicationContext, account.getUuid())) {
                    return null;
                }
                serialized = Base64.decode(legacy, Base64.DEFAULT);
                if (!ScopedAccountSecretVaultV1.storeBytes(
                        applicationContext,
                        account.getUuid(),
                        "OMEMO_SESSION",
                        scope,
                        serialized)) {
                    java.util.Arrays.fill(serialized, (byte) 0);
                    return null;
                }
                final ContentValues values = new ContentValues();
                values.putNull(SQLiteAxolotlStore.KEY);
                getWritableDatabase()
                        .update(
                                SQLiteAxolotlStore.SESSION_TABLENAME,
                                values,
                                SQLiteAxolotlStore.ACCOUNT
                                        + "=? AND "
                                        + SQLiteAxolotlStore.NAME
                                        + "=? AND "
                                        + SQLiteAxolotlStore.DEVICE_ID
                                        + "=?",
                                new String[] {
                                    account.getUuid(),
                                    contact.getName(),
                                    Integer.toString(contact.getDeviceId())
                                });
            } else {
                serialized =
                        ScopedAccountSecretVaultV1.readBytes(
                                applicationContext,
                                account.getUuid(),
                                "OMEMO_SESSION",
                                scope);
            }
        }
        if (serialized == null) {
            return null;
        }
        try {
            return new SessionRecord(serialized);
        } catch (IOException e) {
            throw new AssertionError(e);
        } finally {
            java.util.Arrays.fill(serialized, (byte) 0);
        }
    }

    @Override
    public List<Integer> getSubDeviceSessions(Account account, SignalProtocolAddress contact) {
        final SQLiteDatabase db = this.getReadableDatabase();
        return getSubDeviceSessions(db, account, contact);
    }

    private List<Integer> getSubDeviceSessions(
            SQLiteDatabase db, Account account, SignalProtocolAddress contact) {
        List<Integer> devices = new ArrayList<>();
        String[] columns = {SQLiteAxolotlStore.DEVICE_ID};
        String[] selectionArgs = {account.getUuid(), contact.getName()};
        Cursor cursor =
                db.query(
                        SQLiteAxolotlStore.SESSION_TABLENAME,
                        columns,
                        SQLiteAxolotlStore.ACCOUNT + " = ? AND " + SQLiteAxolotlStore.NAME + " = ?",
                        selectionArgs,
                        null,
                        null,
                        null);

        while (cursor.moveToNext()) {
            devices.add(cursor.getInt(cursor.getColumnIndex(SQLiteAxolotlStore.DEVICE_ID)));
        }

        cursor.close();
        return devices;
    }

    @Override
    public List<String> getKnownSignalAddresses(Account account) {
        List<String> addresses = new ArrayList<>();
        String[] colums = {"DISTINCT " + SQLiteAxolotlStore.NAME};
        String[] selectionArgs = {account.getUuid()};
        Cursor cursor =
                getReadableDatabase()
                        .query(
                                SQLiteAxolotlStore.SESSION_TABLENAME,
                                colums,
                                SQLiteAxolotlStore.ACCOUNT + " = ?",
                                selectionArgs,
                                null,
                                null,
                                null);
        while (cursor.moveToNext()) {
            addresses.add(cursor.getString(0));
        }
        cursor.close();
        return addresses;
    }

    @Override
    public boolean containsSession(Account account, SignalProtocolAddress contact) {
        Cursor cursor = getCursorForSession(account, contact);
        int count = cursor.getCount();
        cursor.close();
        return count != 0;
    }

    @Override
    public void storeSession(
            Account account, SignalProtocolAddress contact, SessionRecord session) {
        final byte[] serialized = session.serialize();
        final String scope =
                "session|" + contact.getName() + "|" + contact.getDeviceId();
        try {
            if (!ScopedAccountSecretVaultV1.storeBytes(
                    applicationContext,
                    account.getUuid(),
                    "OMEMO_SESSION",
                    scope,
                    serialized)) {
                throw new IllegalStateException("OMEMO session vault unavailable");
            }
            SQLiteDatabase db = this.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(SQLiteAxolotlStore.NAME, contact.getName());
            values.put(SQLiteAxolotlStore.DEVICE_ID, contact.getDeviceId());
            values.putNull(SQLiteAxolotlStore.KEY);
            values.put(SQLiteAxolotlStore.ACCOUNT, account.getUuid());
            db.insert(SQLiteAxolotlStore.SESSION_TABLENAME, null, values);
        } finally {
            java.util.Arrays.fill(serialized, (byte) 0);
        }
    }

    @Override
    public void deleteSession(Account account, SignalProtocolAddress contact) {
        final String scope =
                "session|" + contact.getName() + "|" + contact.getDeviceId();
        if (!ScopedAccountSecretVaultV1.storeBytes(
                applicationContext,
                account.getUuid(),
                "OMEMO_SESSION",
                scope,
                null)) {
            throw new IllegalStateException("unable to retire OMEMO session vault record");
        }
        SQLiteDatabase db = this.getWritableDatabase();
        deleteSession(db, account, contact);
    }

    private void deleteSession(SQLiteDatabase db, Account account, SignalProtocolAddress contact) {
        String[] args = {
            account.getUuid(), contact.getName(), Integer.toString(contact.getDeviceId())
        };
        db.delete(
                SQLiteAxolotlStore.SESSION_TABLENAME,
                SQLiteAxolotlStore.ACCOUNT
                        + " = ? AND "
                        + SQLiteAxolotlStore.NAME
                        + " = ? AND "
                        + SQLiteAxolotlStore.DEVICE_ID
                        + " = ? ",
                args);
    }

    @Override
    public void deleteAllSessions(Account account, SignalProtocolAddress contact) {
        final List<Integer> devices = getSubDeviceSessions(account, contact);
        for (final int deviceId : devices) {
            final String scope = "session|" + contact.getName() + "|" + deviceId;
            if (!ScopedAccountSecretVaultV1.storeBytes(
                    applicationContext,
                    account.getUuid(),
                    "OMEMO_SESSION",
                    scope,
                    null)) {
                throw new IllegalStateException("unable to retire OMEMO session vault records");
            }
        }
        SQLiteDatabase db = this.getWritableDatabase();
        String[] args = {account.getUuid(), contact.getName()};
        db.delete(
                SQLiteAxolotlStore.SESSION_TABLENAME,
                SQLiteAxolotlStore.ACCOUNT + "=? AND " + SQLiteAxolotlStore.NAME + " = ?",
                args);
    }

    private Cursor getCursorForPreKey(Account account, int preKeyId) {
        SQLiteDatabase db = this.getReadableDatabase();
        String[] columns = {SQLiteAxolotlStore.ID, SQLiteAxolotlStore.KEY};
        String[] selectionArgs = {account.getUuid(), Integer.toString(preKeyId)};
        Cursor cursor =
                db.query(
                        SQLiteAxolotlStore.PREKEY_TABLENAME,
                        columns,
                        SQLiteAxolotlStore.ACCOUNT + "=? AND " + SQLiteAxolotlStore.ID + "=?",
                        selectionArgs,
                        null,
                        null,
                        null);

        return cursor;
    }

    @Override
    public PreKeyRecord loadPreKey(Account account, int preKeyId) {
        final String scope = "prekey|" + preKeyId;
        byte[] serialized = null;
        try (final Cursor cursor = getCursorForPreKey(account, preKeyId)) {
            if (cursor.getCount() == 0) {
                return null;
            }
            cursor.moveToFirst();
            final String legacy =
                    cursor.getString(cursor.getColumnIndex(SQLiteAxolotlStore.KEY));
            if (legacy != null) {
                if (!ScopedAccountSecretVaultV1.isAvailable(applicationContext, account.getUuid())) {
                    return null;
                }
                serialized = Base64.decode(legacy, Base64.DEFAULT);
                if (!ScopedAccountSecretVaultV1.storeBytes(
                        applicationContext,
                        account.getUuid(),
                        "OMEMO_PREKEY",
                        scope,
                        serialized)) {
                    java.util.Arrays.fill(serialized, (byte) 0);
                    return null;
                }
                final ContentValues values = new ContentValues();
                values.putNull(SQLiteAxolotlStore.KEY);
                getWritableDatabase()
                        .update(
                                SQLiteAxolotlStore.PREKEY_TABLENAME,
                                values,
                                SQLiteAxolotlStore.ACCOUNT + "=? AND " + SQLiteAxolotlStore.ID + "=?",
                                new String[] {account.getUuid(), Integer.toString(preKeyId)});
            } else {
                serialized =
                        ScopedAccountSecretVaultV1.readBytes(
                                applicationContext,
                                account.getUuid(),
                                "OMEMO_PREKEY",
                                scope);
            }
        }
        if (serialized == null) {
            return null;
        }
        try {
            return new PreKeyRecord(serialized);
        } catch (IOException e) {
            throw new AssertionError(e);
        } finally {
            java.util.Arrays.fill(serialized, (byte) 0);
        }
    }

    @Override
    public boolean containsPreKey(Account account, int preKeyId) {
        Cursor cursor = getCursorForPreKey(account, preKeyId);
        int count = cursor.getCount();
        cursor.close();
        return count != 0;
    }

    @Override
    public void storePreKey(Account account, PreKeyRecord record) {
        final byte[] serialized = record.serialize();
        final String scope = "prekey|" + record.getId();
        try {
            if (!ScopedAccountSecretVaultV1.storeBytes(
                    applicationContext,
                    account.getUuid(),
                    "OMEMO_PREKEY",
                    scope,
                    serialized)) {
                throw new IllegalStateException("OMEMO prekey vault unavailable");
            }
            SQLiteDatabase db = this.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(SQLiteAxolotlStore.ID, record.getId());
            values.putNull(SQLiteAxolotlStore.KEY);
            values.put(SQLiteAxolotlStore.ACCOUNT, account.getUuid());
            db.insert(SQLiteAxolotlStore.PREKEY_TABLENAME, null, values);
        } finally {
            java.util.Arrays.fill(serialized, (byte) 0);
        }
    }

    @Override
    public int deletePreKey(Account account, int preKeyId) {
        if (!ScopedAccountSecretVaultV1.storeBytes(
                applicationContext,
                account.getUuid(),
                "OMEMO_PREKEY",
                "prekey|" + preKeyId,
                null)) {
            return 0;
        }
        SQLiteDatabase db = this.getWritableDatabase();
        String[] args = {account.getUuid(), Integer.toString(preKeyId)};
        return db.delete(
                SQLiteAxolotlStore.PREKEY_TABLENAME,
                SQLiteAxolotlStore.ACCOUNT + "=? AND " + SQLiteAxolotlStore.ID + "=?",
                args);
    }

    private Cursor getCursorForSignedPreKey(Account account, int signedPreKeyId) {
        SQLiteDatabase db = this.getReadableDatabase();
        String[] columns = {SQLiteAxolotlStore.KEY};
        String[] selectionArgs = {account.getUuid(), Integer.toString(signedPreKeyId)};
        Cursor cursor =
                db.query(
                        SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                        columns,
                        SQLiteAxolotlStore.ACCOUNT + "=? AND " + SQLiteAxolotlStore.ID + "=?",
                        selectionArgs,
                        null,
                        null,
                        null);

        return cursor;
    }

    @Override
    public SignedPreKeyRecord loadSignedPreKey(Account account, int signedPreKeyId) {
        final String scope = "signed|" + signedPreKeyId;
        byte[] serialized = null;
        try (final Cursor cursor = getCursorForSignedPreKey(account, signedPreKeyId)) {
            if (cursor.getCount() == 0) {
                return null;
            }
            cursor.moveToFirst();
            final String legacy =
                    cursor.getString(cursor.getColumnIndex(SQLiteAxolotlStore.KEY));
            if (legacy != null) {
                if (!ScopedAccountSecretVaultV1.isAvailable(applicationContext, account.getUuid())) {
                    return null;
                }
                serialized = Base64.decode(legacy, Base64.DEFAULT);
                if (!ScopedAccountSecretVaultV1.storeBytes(
                        applicationContext,
                        account.getUuid(),
                        "OMEMO_SIGNED_PREKEY",
                        scope,
                        serialized)) {
                    java.util.Arrays.fill(serialized, (byte) 0);
                    return null;
                }
                final ContentValues values = new ContentValues();
                values.putNull(SQLiteAxolotlStore.KEY);
                getWritableDatabase()
                        .update(
                                SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                                values,
                                SQLiteAxolotlStore.ACCOUNT + "=? AND " + SQLiteAxolotlStore.ID + "=?",
                                new String[] {account.getUuid(), Integer.toString(signedPreKeyId)});
            } else {
                serialized =
                        ScopedAccountSecretVaultV1.readBytes(
                                applicationContext,
                                account.getUuid(),
                                "OMEMO_SIGNED_PREKEY",
                                scope);
            }
        }
        if (serialized == null) {
            return null;
        }
        try {
            return new SignedPreKeyRecord(serialized);
        } catch (IOException e) {
            throw new AssertionError(e);
        } finally {
            java.util.Arrays.fill(serialized, (byte) 0);
        }
    }

    @Override
    public List<SignedPreKeyRecord> loadSignedPreKeys(Account account) {
        List<SignedPreKeyRecord> prekeys = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        String[] columns = {SQLiteAxolotlStore.ID, SQLiteAxolotlStore.KEY};
        String[] selectionArgs = {account.getUuid()};
        Cursor cursor =
                db.query(
                        SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                        columns,
                        SQLiteAxolotlStore.ACCOUNT + "=?",
                        selectionArgs,
                        null,
                        null,
                        null);

        while (cursor.moveToNext()) {
            final int id = cursor.getInt(cursor.getColumnIndex(SQLiteAxolotlStore.ID));
            final SignedPreKeyRecord record = loadSignedPreKey(account, id);
            if (record != null) {
                prekeys.add(record);
            }
        }
        cursor.close();
        return prekeys;
    }

    @Override
    public int getSignedPreKeysCount(Account account) {
        String[] columns = {"count(*)"};
        String[] selectionArgs = {account.getUuid()};
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor =
                db.query(
                        SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                        columns,
                        SQLiteAxolotlStore.ACCOUNT + "=?",
                        selectionArgs,
                        null,
                        null,
                        null);
        final int count;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        } else {
            count = 0;
        }
        cursor.close();
        return count;
    }

    @Override
    public boolean containsSignedPreKey(Account account, int signedPreKeyId) {
        Cursor cursor = getCursorForSignedPreKey(account, signedPreKeyId);
        int count = cursor.getCount();
        cursor.close();
        return count != 0;
    }

    @Override
    public void storeSignedPreKey(Account account, SignedPreKeyRecord record) {
        final byte[] serialized = record.serialize();
        final String scope = "signed|" + record.getId();
        try {
            if (!ScopedAccountSecretVaultV1.storeBytes(
                    applicationContext,
                    account.getUuid(),
                    "OMEMO_SIGNED_PREKEY",
                    scope,
                    serialized)) {
                throw new IllegalStateException("OMEMO signed prekey vault unavailable");
            }
            SQLiteDatabase db = this.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(SQLiteAxolotlStore.ID, record.getId());
            values.putNull(SQLiteAxolotlStore.KEY);
            values.put(SQLiteAxolotlStore.ACCOUNT, account.getUuid());
            db.insert(SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME, null, values);
        } finally {
            java.util.Arrays.fill(serialized, (byte) 0);
        }
    }

    @Override
    public void deleteSignedPreKey(Account account, int signedPreKeyId) {
        if (!ScopedAccountSecretVaultV1.storeBytes(
                applicationContext,
                account.getUuid(),
                "OMEMO_SIGNED_PREKEY",
                "signed|" + signedPreKeyId,
                null)) {
            throw new IllegalStateException("unable to retire OMEMO signed prekey vault record");
        }
        SQLiteDatabase db = this.getWritableDatabase();
        String[] args = {account.getUuid(), Integer.toString(signedPreKeyId)};
        db.delete(
                SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                SQLiteAxolotlStore.ACCOUNT + "=? AND " + SQLiteAxolotlStore.ID + "=?",
                args);
    }

    private Cursor getIdentityKeyCursor(Account account, String name, boolean own) {
        final SQLiteDatabase db = this.getReadableDatabase();
        return getIdentityKeyCursor(db, account, name, own);
    }

    private Cursor getIdentityKeyCursor(
            SQLiteDatabase db, Account account, String name, boolean own) {
        return getIdentityKeyCursor(db, account, name, own, null);
    }

    private Cursor getIdentityKeyCursor(Account account, String fingerprint) {
        final SQLiteDatabase db = this.getReadableDatabase();
        return getIdentityKeyCursor(db, account, fingerprint);
    }

    private Cursor getIdentityKeyCursor(SQLiteDatabase db, Account account, String fingerprint) {
        return getIdentityKeyCursor(db, account, null, null, fingerprint);
    }

    private Cursor getIdentityKeyCursor(
            SQLiteDatabase db, Account account, String name, Boolean own, String fingerprint) {
        String[] columns = {
            SQLiteAxolotlStore.TRUST,
            SQLiteAxolotlStore.ACTIVE,
            SQLiteAxolotlStore.LAST_ACTIVATION,
            SQLiteAxolotlStore.KEY
        };
        ArrayList<String> selectionArgs = new ArrayList<>(4);
        selectionArgs.add(account.getUuid());
        String selectionString = SQLiteAxolotlStore.ACCOUNT + " = ?";
        if (name != null) {
            selectionArgs.add(name);
            selectionString += " AND " + SQLiteAxolotlStore.NAME + " = ?";
        }
        if (fingerprint != null) {
            selectionArgs.add(fingerprint);
            selectionString += " AND " + SQLiteAxolotlStore.FINGERPRINT + " = ?";
        }
        if (own != null) {
            selectionArgs.add(own ? "1" : "0");
            selectionString += " AND " + SQLiteAxolotlStore.OWN + " = ?";
        }
        Cursor cursor =
                db.query(
                        SQLiteAxolotlStore.IDENTITIES_TABLENAME,
                        columns,
                        selectionString,
                        selectionArgs.toArray(new String[selectionArgs.size()]),
                        null,
                        null,
                        null);

        return cursor;
    }

    @Override
    public IdentityKeyPair loadOwnIdentityKeyPair(Account account) {
        SQLiteDatabase db = getReadableDatabase();
        return loadOwnIdentityKeyPair(db, account);
    }

    private IdentityKeyPair loadOwnIdentityKeyPair(SQLiteDatabase db, Account account) {
        final String name = account.getJid().asBareJid().toString();
        byte[] serialized = null;
        try (final Cursor cursor = getIdentityKeyCursor(db, account, name, true)) {
            if (cursor.getCount() == 0) {
                return null;
            }
            cursor.moveToFirst();
            final String legacy =
                    cursor.getString(cursor.getColumnIndex(SQLiteAxolotlStore.KEY));
            if (legacy != null) {
                if (!ScopedAccountSecretVaultV1.isAvailable(applicationContext, account.getUuid())) {
                    return null;
                }
                serialized = Base64.decode(legacy, Base64.DEFAULT);
                if (!ScopedAccountSecretVaultV1.storeBytes(
                        applicationContext,
                        account.getUuid(),
                        "OMEMO_IDENTITY",
                        "identity",
                        serialized)) {
                    java.util.Arrays.fill(serialized, (byte) 0);
                    return null;
                }
                final ContentValues values = new ContentValues();
                values.putNull(SQLiteAxolotlStore.KEY);
                getWritableDatabase()
                        .update(
                                SQLiteAxolotlStore.IDENTITIES_TABLENAME,
                                values,
                                SQLiteAxolotlStore.ACCOUNT
                                        + "=? AND "
                                        + SQLiteAxolotlStore.NAME
                                        + "=? AND "
                                        + SQLiteAxolotlStore.OWN
                                        + "=1",
                                new String[] {account.getUuid(), name});
            } else {
                serialized =
                        ScopedAccountSecretVaultV1.readBytes(
                                applicationContext,
                                account.getUuid(),
                                "OMEMO_IDENTITY",
                                "identity");
            }
        }
        if (serialized == null) {
            return null;
        }
        try {
            return new IdentityKeyPair(serialized);
        } catch (InvalidKeyException e) {
            return null;
        } finally {
            java.util.Arrays.fill(serialized, (byte) 0);
        }
    }

    @Override
    public Set<IdentityKey> loadIdentityKeys(Account account, String name) {
        return loadIdentityKeys(account, name, null);
    }

    @Override
    public Set<IdentityKey> loadIdentityKeys(
            Account account, String name, FingerprintStatus status) {
        Set<IdentityKey> identityKeys = new HashSet<>();
        Cursor cursor = getIdentityKeyCursor(account, name, false);

        while (cursor.moveToNext()) {
            if (status != null && !FingerprintStatus.fromCursor(cursor).equals(status)) {
                continue;
            }
            try {
                String key = cursor.getString(cursor.getColumnIndex(SQLiteAxolotlStore.KEY));
                if (key != null) {
                    identityKeys.add(new IdentityKey(Base64.decode(key, Base64.DEFAULT), 0));
                } else {
                    Log.d(
                            Config.LOGTAG,
                            AxolotlService.getLogprefix(account)
                                    + "Missing key (possibly preverified) in database for account"
                                    + account.getJid().asBareJid()
                                    + ", address: "
                                    + name);
                }
            } catch (InvalidKeyException e) {
                Log.d(
                        Config.LOGTAG,
                        AxolotlService.getLogprefix(account)
                                + "Encountered invalid IdentityKey in database for account"
                                + account.getJid().asBareJid()
                                + ", address: "
                                + name);
            }
        }
        cursor.close();

        return identityKeys;
    }

    @Override
    public long numTrustedKeys(Account account, String name) {
        SQLiteDatabase db = getReadableDatabase();
        String[] args = {
            account.getUuid(),
            name,
            FingerprintStatus.Trust.TRUSTED.toString(),
            FingerprintStatus.Trust.VERIFIED.toString(),
            FingerprintStatus.Trust.VERIFIED_X509.toString()
        };
        return 1; /*DatabaseUtils.queryNumEntries(
                db,
                SQLiteAxolotlStore.IDENTITIES_TABLENAME,
                SQLiteAxolotlStore.ACCOUNT
                        + " = ?"
                        + " AND "
                        + SQLiteAxolotlStore.NAME
                        + " = ?"
                        + " AND ("
                        + SQLiteAxolotlStore.TRUST
                        + " = ? OR "
                        + SQLiteAxolotlStore.TRUST
                        + " = ? OR "
                        + SQLiteAxolotlStore.TRUST
                        + " = ?)"
                        + " AND "
                        + SQLiteAxolotlStore.ACTIVE
                        + " > 0",
                args);*/
    }

    private void storeIdentityKey(
            Account account,
            String name,
            boolean own,
            String fingerprint,
            String base64Serialized,
            FingerprintStatus status) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(SQLiteAxolotlStore.ACCOUNT, account.getUuid());
        values.put(SQLiteAxolotlStore.NAME, name);
        values.put(SQLiteAxolotlStore.OWN, own ? 1 : 0);
        values.put(SQLiteAxolotlStore.FINGERPRINT, fingerprint);
        values.put(SQLiteAxolotlStore.KEY, base64Serialized);
        values.putAll(status.toContentValues());
        String where =
                SQLiteAxolotlStore.ACCOUNT
                        + "=? AND "
                        + SQLiteAxolotlStore.NAME
                        + "=? AND "
                        + SQLiteAxolotlStore.FINGERPRINT
                        + " =?";
        String[] whereArgs = {account.getUuid(), name, fingerprint};
        int rows = db.update(SQLiteAxolotlStore.IDENTITIES_TABLENAME, values, where, whereArgs);
        if (rows == 0) {
            db.insert(SQLiteAxolotlStore.IDENTITIES_TABLENAME, null, values);
        }
    }

    @Override
    public void storePreVerification(
            Account account, String name, String fingerprint, FingerprintStatus status) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(SQLiteAxolotlStore.ACCOUNT, account.getUuid());
        values.put(SQLiteAxolotlStore.NAME, name);
        values.put(SQLiteAxolotlStore.OWN, 0);
        values.put(SQLiteAxolotlStore.FINGERPRINT, fingerprint);
        values.putAll(status.toContentValues());
        db.insert(SQLiteAxolotlStore.IDENTITIES_TABLENAME, null, values);
    }

    @Override
    public FingerprintStatus getFingerprintStatus(Account account, String fingerprint) {
        Cursor cursor = getIdentityKeyCursor(account, fingerprint);
        final FingerprintStatus status;
        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            status = FingerprintStatus.fromCursor(cursor);
        } else {
            status = null;
        }
        cursor.close();
        return status;
    }

    @Override
    public boolean setIdentityKeyTrust(
            Account account, String fingerprint, FingerprintStatus fingerprintStatus) {
        SQLiteDatabase db = this.getWritableDatabase();
        return setIdentityKeyTrust(db, account, fingerprint, fingerprintStatus);
    }

    private boolean setIdentityKeyTrust(
            SQLiteDatabase db, Account account, String fingerprint, FingerprintStatus status) {
        String[] selectionArgs = {account.getUuid(), fingerprint};
        int rows =
                db.update(
                        SQLiteAxolotlStore.IDENTITIES_TABLENAME,
                        status.toContentValues(),
                        SQLiteAxolotlStore.ACCOUNT
                                + " = ? AND "
                                + SQLiteAxolotlStore.FINGERPRINT
                                + " = ? ",
                        selectionArgs);
        return rows == 1;
    }

    @Override
    public boolean setIdentityKeyCertificate(
            Account account, String fingerprint, X509Certificate x509Certificate) {
        SQLiteDatabase db = this.getWritableDatabase();
        String[] selectionArgs = {account.getUuid(), fingerprint};
        try {
            ContentValues values = new ContentValues();
            values.put(SQLiteAxolotlStore.CERTIFICATE, x509Certificate.getEncoded());
            return db.update(
                            SQLiteAxolotlStore.IDENTITIES_TABLENAME,
                            values,
                            SQLiteAxolotlStore.ACCOUNT
                                    + " = ? AND "
                                    + SQLiteAxolotlStore.FINGERPRINT
                                    + " = ? ",
                            selectionArgs)
                    == 1;
        } catch (CertificateEncodingException e) {
            Log.d(Config.LOGTAG, "could not encode certificate");
            return false;
        }
    }

    @Override
    public X509Certificate getIdentityKeyCertifcate(Account account, String fingerprint) {
        SQLiteDatabase db = this.getReadableDatabase();
        String[] selectionArgs = {account.getUuid(), fingerprint};
        String[] colums = {SQLiteAxolotlStore.CERTIFICATE};
        String selection =
                SQLiteAxolotlStore.ACCOUNT + " = ? AND " + SQLiteAxolotlStore.FINGERPRINT + " = ? ";
        Cursor cursor =
                db.query(
                        SQLiteAxolotlStore.IDENTITIES_TABLENAME,
                        colums,
                        selection,
                        selectionArgs,
                        null,
                        null,
                        null);
        if (cursor.getCount() < 1) {
            return null;
        } else {
            cursor.moveToFirst();
            byte[] certificate =
                    cursor.getBlob(cursor.getColumnIndex(SQLiteAxolotlStore.CERTIFICATE));
            cursor.close();
            if (certificate == null || certificate.length == 0) {
                return null;
            }
            try {
                CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
                return (X509Certificate)
                        certificateFactory.generateCertificate(
                                new ByteArrayInputStream(certificate));
            } catch (CertificateException e) {
                Log.d(Config.LOGTAG, "certificate exception " + e.getMessage());
                return null;
            }
        }
    }

    @Override
    public void storeIdentityKey(
            Account account, String name, IdentityKey identityKey, FingerprintStatus status) {
        storeIdentityKey(
                account,
                name,
                false,
                CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize()),
                Base64.encodeToString(identityKey.serialize(), Base64.DEFAULT),
                status);
    }

    @Override
    public void storeOwnIdentityKeyPair(Account account, IdentityKeyPair identityKeyPair) {
        final byte[] serialized = identityKeyPair.serialize();
        try {
            if (!ScopedAccountSecretVaultV1.storeBytes(
                    applicationContext,
                    account.getUuid(),
                    "OMEMO_IDENTITY",
                    "identity",
                    serialized)) {
                throw new IllegalStateException("OMEMO identity vault unavailable");
            }
            storeIdentityKey(
                    account,
                    account.getJid().asBareJid().toString(),
                    true,
                    CryptoHelper.bytesToHex(identityKeyPair.getPublicKey().serialize()),
                    null,
                    FingerprintStatus.createActiveVerified(false));
        } finally {
            java.util.Arrays.fill(serialized, (byte) 0);
        }
    }

    private void recreateAxolotlDb(SQLiteDatabase db) {
        Log.d(
                Config.LOGTAG,
                AxolotlService.LOGPREFIX + " : " + ">>> (RE)CREATING AXOLOTL DATABASE <<<");
        db.execSQL("DROP TABLE IF EXISTS " + SQLiteAxolotlStore.SESSION_TABLENAME);
        db.execSQL(CREATE_SESSIONS_STATEMENT);
        db.execSQL("DROP TABLE IF EXISTS " + SQLiteAxolotlStore.PREKEY_TABLENAME);
        db.execSQL(CREATE_PREKEYS_STATEMENT);
        db.execSQL("DROP TABLE IF EXISTS " + SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME);
        db.execSQL(CREATE_SIGNED_PREKEYS_STATEMENT);
        db.execSQL("DROP TABLE IF EXISTS " + SQLiteAxolotlStore.IDENTITIES_TABLENAME);
        db.execSQL(CREATE_IDENTITIES_STATEMENT);
    }

    @Override
    public int migrateLegacyConversationSecretsBatch(int limit) {
        if (limit <= 0) {
            return 0;
        }
        final int bounded = Math.min(limit, 50);
        final ArrayList<String[]> candidates = new ArrayList<>();
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                Conversation.TABLENAME,
                                new String[] {
                                    Conversation.UUID,
                                    Conversation.ACCOUNT,
                                    Conversation.ATTRIBUTES
                                },
                                Conversation.ATTRIBUTES
                                        + " LIKE ? OR "
                                        + Conversation.ATTRIBUTES
                                        + " LIKE ?",
                                new String[] {"%\"muc_password\"%", "%\"next_message\"%"},
                                null,
                                null,
                                Conversation.CREATED + " DESC",
                                Integer.toString(bounded))) {
            while (cursor.moveToNext()) {
                candidates.add(
                        new String[] {
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getString(2)
                        });
            }
        }

        int migrated = 0;
        for (final String[] candidate : candidates) {
            final String conversationUuid = candidate[0];
            final String accountUuid = candidate[1];
            final String encodedAttributes = candidate[2];
            if (conversationUuid == null
                    || accountUuid == null
                    || encodedAttributes == null
                    || !ScopedAccountSecretVaultV1.isAvailable(
                            applicationContext, accountUuid)) {
                continue;
            }

            final JSONObject attributes;
            try {
                attributes = new JSONObject(encodedAttributes);
            } catch (final JSONException ignored) {
                continue;
            }

            boolean changed = false;
            final String mucPassword = attributes.optString("muc_password", null);
            if (mucPassword != null && !mucPassword.isEmpty()) {
                if (!ScopedAccountSecretVaultV1.storeString(
                        applicationContext,
                        accountUuid,
                        "MUC_PASSWORD",
                        conversationUuid,
                        mucPassword)) {
                    continue;
                }
                attributes.remove("muc_password");
                changed = true;
            } else if (attributes.has("muc_password")) {
                attributes.remove("muc_password");
                changed = true;
            }

            final String draft = attributes.optString("next_message", null);
            if (draft != null && !draft.isEmpty()) {
                if (!ScopedAccountSecretVaultV1.storeString(
                        applicationContext,
                        accountUuid,
                        "DRAFT_TEXT",
                        conversationUuid,
                        draft)) {
                    continue;
                }
                attributes.remove("next_message");
                changed = true;
            } else if (attributes.has("next_message")) {
                attributes.remove("next_message");
                changed = true;
            }

            if (!changed) {
                continue;
            }
            final ContentValues values = new ContentValues();
            values.put(Conversation.ATTRIBUTES, attributes.toString());
            if (getWritableDatabase()
                            .update(
                                    Conversation.TABLENAME,
                                    values,
                                    Conversation.UUID + "=? AND " + Conversation.ACCOUNT + "=?",
                                    new String[] {conversationUuid, accountUuid})
                    == 1) {
                migrated++;
            }
        }
        return migrated;
    }

    @Override
    public boolean hasLegacyConversationSecrets() {
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                Conversation.TABLENAME,
                                new String[] {"1"},
                                Conversation.ATTRIBUTES
                                        + " LIKE ? OR "
                                        + Conversation.ATTRIBUTES
                                        + " LIKE ?",
                                new String[] {"%\"muc_password\"%", "%\"next_message\"%"},
                                null,
                                null,
                                null,
                                "1")) {
            return cursor.moveToFirst();
        }
    }

    @Override
    public boolean retireProtectedAccountScopedSecrets(Account account) {
        if (account == null
                || !ScopedAccountSecretVaultV1.isAvailable(
                        applicationContext, account.getUuid())) {
            return false;
        }
        final String accountUuid = account.getUuid();
        boolean ok = true;

        ok &=
                ScopedAccountSecretVaultV1.storeString(
                        applicationContext, accountUuid, "OTR_KEYPAIR", "account", null);
        ok &=
                ScopedAccountSecretVaultV1.storeBytes(
                        applicationContext, accountUuid, "OMEMO_IDENTITY", "identity", null);

        final ArrayList<String[]> conversationScopes = new ArrayList<>();
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                Conversation.TABLENAME,
                                new String[] {
                                    Conversation.UUID,
                                    Conversation.CONTACTJID,
                                    Conversation.MODE
                                },
                                Conversation.ACCOUNT + "=?",
                                new String[] {accountUuid},
                                null,
                                null,
                                null)) {
            while (cursor.moveToNext()) {
                conversationScopes.add(
                        new String[] {
                            cursor.getString(0),
                            cursor.getString(1),
                            Integer.toString(cursor.getInt(2))
                        });
            }
        }
        for (final String[] scope : conversationScopes) {
            ok &=
                    ScopedAccountSecretVaultV1.storeString(
                            applicationContext,
                            accountUuid,
                            "DRAFT_TEXT",
                            scope[0],
                            null);
            ok &=
                    ScopedAccountSecretVaultV1.storeString(
                            applicationContext,
                            accountUuid,
                            "MUC_PASSWORD",
                            scope[0],
                            null);
            if (Integer.toString(Conversation.MODE_MULTI).equals(scope[2])
                    && scope[1] != null) {
                ok &=
                        ScopedAccountSecretVaultV1.storeString(
                                applicationContext,
                                accountUuid,
                                "BOOKMARK_PASSWORD",
                                scope[1],
                                null);
            }
        }

        final ArrayList<Integer> preKeys = new ArrayList<>();
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                SQLiteAxolotlStore.PREKEY_TABLENAME,
                                new String[] {SQLiteAxolotlStore.ID},
                                SQLiteAxolotlStore.ACCOUNT + "=?",
                                new String[] {accountUuid},
                                null,
                                null,
                                null)) {
            while (cursor.moveToNext()) {
                preKeys.add(cursor.getInt(0));
            }
        }
        for (final int id : preKeys) {
            ok &=
                    ScopedAccountSecretVaultV1.storeBytes(
                            applicationContext,
                            accountUuid,
                            "OMEMO_PREKEY",
                            "prekey|" + id,
                            null);
        }

        final ArrayList<Integer> signedPreKeys = new ArrayList<>();
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                                new String[] {SQLiteAxolotlStore.ID},
                                SQLiteAxolotlStore.ACCOUNT + "=?",
                                new String[] {accountUuid},
                                null,
                                null,
                                null)) {
            while (cursor.moveToNext()) {
                signedPreKeys.add(cursor.getInt(0));
            }
        }
        for (final int id : signedPreKeys) {
            ok &=
                    ScopedAccountSecretVaultV1.storeBytes(
                            applicationContext,
                            accountUuid,
                            "OMEMO_SIGNED_PREKEY",
                            "signed|" + id,
                            null);
        }

        final ArrayList<String[]> sessionScopes = new ArrayList<>();
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                SQLiteAxolotlStore.SESSION_TABLENAME,
                                new String[] {
                                    SQLiteAxolotlStore.NAME,
                                    SQLiteAxolotlStore.DEVICE_ID
                                },
                                SQLiteAxolotlStore.ACCOUNT + "=?",
                                new String[] {accountUuid},
                                null,
                                null,
                                null)) {
            while (cursor.moveToNext()) {
                sessionScopes.add(
                        new String[] {
                            cursor.getString(0),
                            Integer.toString(cursor.getInt(1))
                        });
            }
        }
        for (final String[] scope : sessionScopes) {
            ok &=
                    ScopedAccountSecretVaultV1.storeBytes(
                            applicationContext,
                            accountUuid,
                            "OMEMO_SESSION",
                            "session|" + scope[0] + "|" + scope[1],
                            null);
        }
        return ok;
    }

    @Override
    public int migrateLegacyPrivateCryptoStateBatch(Account account, int limit) {
        if (account == null || limit <= 0) {
            return 0;
        }
        final int bounded = Math.min(limit, 50);
        int migrated = 0;

        if (migrated < bounded && hasLegacyOwnIdentity(account)) {
            if (loadOwnIdentityKeyPair(account) != null && !hasLegacyOwnIdentity(account)) {
                migrated++;
            }
        }

        final ArrayList<Integer> signedIds = new ArrayList<>();
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                                new String[] {SQLiteAxolotlStore.ID},
                                SQLiteAxolotlStore.ACCOUNT
                                        + "=? AND "
                                        + SQLiteAxolotlStore.KEY
                                        + " IS NOT NULL",
                                new String[] {account.getUuid()},
                                null,
                                null,
                                SQLiteAxolotlStore.ID + " DESC",
                                Integer.toString(bounded - migrated))) {
            while (cursor.moveToNext()) {
                signedIds.add(cursor.getInt(0));
            }
        }
        for (final int id : signedIds) {
            if (migrated >= bounded) break;
            if (loadSignedPreKey(account, id) != null && !hasLegacySignedPreKey(account, id)) {
                migrated++;
            }
        }

        final ArrayList<Integer> preKeyIds = new ArrayList<>();
        if (migrated < bounded) {
            try (final Cursor cursor =
                    getReadableDatabase()
                            .query(
                                    SQLiteAxolotlStore.PREKEY_TABLENAME,
                                    new String[] {SQLiteAxolotlStore.ID},
                                    SQLiteAxolotlStore.ACCOUNT
                                            + "=? AND "
                                            + SQLiteAxolotlStore.KEY
                                            + " IS NOT NULL",
                                    new String[] {account.getUuid()},
                                    null,
                                    null,
                                    SQLiteAxolotlStore.ID + " DESC",
                                    Integer.toString(bounded - migrated))) {
                while (cursor.moveToNext()) {
                    preKeyIds.add(cursor.getInt(0));
                }
            }
        }
        for (final int id : preKeyIds) {
            if (migrated >= bounded) break;
            if (loadPreKey(account, id) != null && !hasLegacyPreKey(account, id)) {
                migrated++;
            }
        }

        final ArrayList<SignalProtocolAddress> sessions = new ArrayList<>();
        if (migrated < bounded) {
            try (final Cursor cursor =
                    getReadableDatabase()
                            .query(
                                    SQLiteAxolotlStore.SESSION_TABLENAME,
                                    new String[] {
                                        SQLiteAxolotlStore.NAME,
                                        SQLiteAxolotlStore.DEVICE_ID
                                    },
                                    SQLiteAxolotlStore.ACCOUNT
                                            + "=? AND "
                                            + SQLiteAxolotlStore.KEY
                                            + " IS NOT NULL",
                                    new String[] {account.getUuid()},
                                    null,
                                    null,
                                    SQLiteAxolotlStore.NAME + " ASC",
                                    Integer.toString(bounded - migrated))) {
                while (cursor.moveToNext()) {
                    sessions.add(
                            new SignalProtocolAddress(
                                    cursor.getString(0),
                                    cursor.getInt(1)));
                }
            }
        }
        for (final SignalProtocolAddress address : sessions) {
            if (migrated >= bounded) break;
            if (loadSession(account, address) != null && !hasLegacySession(account, address)) {
                migrated++;
            }
        }
        return migrated;
    }

    @Override
    public boolean hasLegacyPrivateCryptoState(Account account) {
        if (account == null) {
            return false;
        }
        final String[] args = {account.getUuid()};
        final String[] tables = {
            SQLiteAxolotlStore.IDENTITIES_TABLENAME,
            SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
            SQLiteAxolotlStore.PREKEY_TABLENAME,
            SQLiteAxolotlStore.SESSION_TABLENAME
        };
        for (final String table : tables) {
            final String selection =
                    SQLiteAxolotlStore.ACCOUNT
                            + "=? AND "
                            + SQLiteAxolotlStore.KEY
                            + " IS NOT NULL"
                            + (SQLiteAxolotlStore.IDENTITIES_TABLENAME.equals(table)
                                    ? " AND " + SQLiteAxolotlStore.OWN + "=1"
                                    : "");
            try (final Cursor cursor =
                    getReadableDatabase()
                            .query(
                                    table,
                                    new String[] {"1"},
                                    selection,
                                    args,
                                    null,
                                    null,
                                    null,
                                    "1")) {
                if (cursor.moveToFirst()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasLegacyOwnIdentity(final Account account) {
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                SQLiteAxolotlStore.IDENTITIES_TABLENAME,
                                new String[] {"1"},
                                SQLiteAxolotlStore.ACCOUNT
                                        + "=? AND "
                                        + SQLiteAxolotlStore.OWN
                                        + "=1 AND "
                                        + SQLiteAxolotlStore.KEY
                                        + " IS NOT NULL",
                                new String[] {account.getUuid()},
                                null,
                                null,
                                null,
                                "1")) {
            return cursor.moveToFirst();
        }
    }

    private boolean hasLegacySignedPreKey(final Account account, final int id) {
        return hasLegacyKeyById(SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME, account, id);
    }

    private boolean hasLegacyPreKey(final Account account, final int id) {
        return hasLegacyKeyById(SQLiteAxolotlStore.PREKEY_TABLENAME, account, id);
    }

    private boolean hasLegacyKeyById(
            final String table, final Account account, final int id) {
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                table,
                                new String[] {"1"},
                                SQLiteAxolotlStore.ACCOUNT
                                        + "=? AND "
                                        + SQLiteAxolotlStore.ID
                                        + "=? AND "
                                        + SQLiteAxolotlStore.KEY
                                        + " IS NOT NULL",
                                new String[] {account.getUuid(), Integer.toString(id)},
                                null,
                                null,
                                null,
                                "1")) {
            return cursor.moveToFirst();
        }
    }

    private boolean hasLegacySession(
            final Account account, final SignalProtocolAddress address) {
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                SQLiteAxolotlStore.SESSION_TABLENAME,
                                new String[] {"1"},
                                SQLiteAxolotlStore.ACCOUNT
                                        + "=? AND "
                                        + SQLiteAxolotlStore.NAME
                                        + "=? AND "
                                        + SQLiteAxolotlStore.DEVICE_ID
                                        + "=? AND "
                                        + SQLiteAxolotlStore.KEY
                                        + " IS NOT NULL",
                                new String[] {
                                    account.getUuid(),
                                    address.getName(),
                                    Integer.toString(address.getDeviceId())
                                },
                                null,
                                null,
                                null,
                                "1")) {
            return cursor.moveToFirst();
        }
    }

    private boolean retireOmemoVaultState(final Account account) {
        if (account == null
                || !ScopedAccountSecretVaultV1.isAvailable(
                        applicationContext, account.getUuid())) {
            return false;
        }
        final String accountUuid = account.getUuid();
        boolean ok =
                ScopedAccountSecretVaultV1.storeBytes(
                        applicationContext,
                        accountUuid,
                        "OMEMO_IDENTITY",
                        "identity",
                        null);

        final ArrayList<Integer> preKeys = new ArrayList<>();
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                SQLiteAxolotlStore.PREKEY_TABLENAME,
                                new String[] {SQLiteAxolotlStore.ID},
                                SQLiteAxolotlStore.ACCOUNT + "=?",
                                new String[] {accountUuid},
                                null,
                                null,
                                null)) {
            while (cursor.moveToNext()) {
                preKeys.add(cursor.getInt(0));
            }
        }
        for (final int id : preKeys) {
            ok &=
                    ScopedAccountSecretVaultV1.storeBytes(
                            applicationContext,
                            accountUuid,
                            "OMEMO_PREKEY",
                            "prekey|" + id,
                            null);
        }

        final ArrayList<Integer> signedPreKeys = new ArrayList<>();
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                                new String[] {SQLiteAxolotlStore.ID},
                                SQLiteAxolotlStore.ACCOUNT + "=?",
                                new String[] {accountUuid},
                                null,
                                null,
                                null)) {
            while (cursor.moveToNext()) {
                signedPreKeys.add(cursor.getInt(0));
            }
        }
        for (final int id : signedPreKeys) {
            ok &=
                    ScopedAccountSecretVaultV1.storeBytes(
                            applicationContext,
                            accountUuid,
                            "OMEMO_SIGNED_PREKEY",
                            "signed|" + id,
                            null);
        }

        final ArrayList<String[]> sessions = new ArrayList<>();
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                SQLiteAxolotlStore.SESSION_TABLENAME,
                                new String[] {
                                    SQLiteAxolotlStore.NAME,
                                    SQLiteAxolotlStore.DEVICE_ID
                                },
                                SQLiteAxolotlStore.ACCOUNT + "=?",
                                new String[] {accountUuid},
                                null,
                                null,
                                null)) {
            while (cursor.moveToNext()) {
                sessions.add(
                        new String[] {
                            cursor.getString(0),
                            Integer.toString(cursor.getInt(1))
                        });
            }
        }
        for (final String[] session : sessions) {
            ok &=
                    ScopedAccountSecretVaultV1.storeBytes(
                            applicationContext,
                            accountUuid,
                            "OMEMO_SESSION",
                            "session|" + session[0] + "|" + session[1],
                            null);
        }
        return ok;
    }

    @Override
    public void resetOwnAxolotlStateForRestore(final Account account) {
        if (!retireOmemoVaultState(account)) {
            throw new IllegalStateException("unable to retire local OMEMO state");
        }
        resetOwnAxolotlRowsForRestore(getWritableDatabase(), account.getUuid());
    }

    static void resetOwnAxolotlRowsForRestore(
            final SQLiteDatabase db, final String accountUuid) {
        if (db == null || accountUuid == null || accountUuid.isEmpty()) {
            throw new IllegalArgumentException("database and accountUuid are required");
        }
        final String[] deleteArgs = {accountUuid};
        db.beginTransaction();
        try {
            db.delete(
                    SQLiteAxolotlStore.SESSION_TABLENAME,
                    SQLiteAxolotlStore.ACCOUNT + " = ?",
                    deleteArgs);
            db.delete(
                    SQLiteAxolotlStore.PREKEY_TABLENAME,
                    SQLiteAxolotlStore.ACCOUNT + " = ?",
                    deleteArgs);
            db.delete(
                    SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                    SQLiteAxolotlStore.ACCOUNT + " = ?",
                    deleteArgs);
            db.delete(
                    SQLiteAxolotlStore.IDENTITIES_TABLENAME,
                    SQLiteAxolotlStore.ACCOUNT
                            + " = ? AND "
                            + SQLiteAxolotlStore.OWN
                            + " = 1",
                    deleteArgs);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void wipeAxolotlDb(Account account) {
        if (!retireOmemoVaultState(account)) {
            throw new IllegalStateException("unable to retire OMEMO vault state");
        }
        String accountName = account.getUuid();
        Log.d(
                Config.LOGTAG,
                AxolotlService.getLogprefix(account)
                        + ">>> WIPING AXOLOTL DATABASE FOR ACCOUNT "
                        + accountName
                        + " <<<");
        SQLiteDatabase db = this.getWritableDatabase();
        String[] deleteArgs = {accountName};
        db.delete(
                SQLiteAxolotlStore.SESSION_TABLENAME,
                SQLiteAxolotlStore.ACCOUNT + " = ?",
                deleteArgs);
        db.delete(
                SQLiteAxolotlStore.PREKEY_TABLENAME,
                SQLiteAxolotlStore.ACCOUNT + " = ?",
                deleteArgs);
        db.delete(
                SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                SQLiteAxolotlStore.ACCOUNT + " = ?",
                deleteArgs);
        db.delete(
                SQLiteAxolotlStore.IDENTITIES_TABLENAME,
                SQLiteAxolotlStore.ACCOUNT + " = ?",
                deleteArgs);
    }

    @Override
    public List<ShortcutService.FrequentContact> getFrequentContacts(final int days) {
        final var db = this.getReadableDatabase();
        final String SQL =
                "select "
                        + Conversation.TABLENAME
                        + "."
                        + Conversation.UUID
                        + ","
                        + Conversation.TABLENAME
                        + "."
                        + Conversation.ACCOUNT
                        + ","
                        + Conversation.TABLENAME
                        + "."
                        + Conversation.CONTACTJID
                        + " from "
                        + Conversation.TABLENAME
                        + " join "
                        + Message.TABLENAME
                        + " on conversations.uuid=messages.conversationUuid where"
                        + " messages.status!=0 and carbon==0  and conversations.mode=0 and"
                        + " messages.timeSent>=? group by conversations.uuid order by count(body)"
                        + " desc limit 4;";
        String[] whereArgs =
                new String[] {
                    String.valueOf(System.currentTimeMillis() - (Config.MILLISECONDS_IN_DAY * days))
                };
        Cursor cursor = db.rawQuery(SQL, whereArgs);
        ArrayList<ShortcutService.FrequentContact> contacts = new ArrayList<>();
        while (cursor.moveToNext()) {
            try {
                contacts.add(
                        new ShortcutService.FrequentContact(
                                cursor.getString(0),
                                cursor.getString(1),
                                Jid.of(cursor.getString(2))));
            } catch (final Exception e) {
                Log.e(Config.LOGTAG, "could not create frequent contact", e);
            }
        }
        cursor.close();
        return contacts;
    }

    @Override
    public Map<Integer, Integer> getMessagesCountGroupByDay(String conversationUuid, int year, int month) {
        final var db = this.getReadableDatabase();

        Map<Integer, Integer> messagesPerDay = new HashMap<>();

        // Calculate the start and end timestamps for the given month
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month - 1, 1, 0, 0, 0); // Month is 0-indexed in Calendar
        calendar.set(Calendar.MILLISECOND, 0);
        long startTimeMillis = calendar.getTimeInMillis();

        calendar.add(Calendar.MONTH, 1);
        calendar.add(Calendar.MILLISECOND, -1);
        long endTimeMillis = calendar.getTimeInMillis();

        long offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;

        String sql = "SELECT " +
                "CAST(strftime('%d', " + Message.TIME_SENT + " / 1000 + " + offset +", 'unixepoch') AS INTEGER) AS day_of_month, " +
                "COUNT(" + Message.UUID + ") AS message_count " +
                "FROM " + Message.TABLENAME + " " +
                "WHERE " + Message.CONVERSATION + " = ? " +
                "AND " + Message.TIME_SENT + " >= ? " +
                "AND " + Message.TIME_SENT + " <= ? " +
                "AND " + Message.DELETED + " = 0 " +
                "GROUP BY day_of_month " +
                "ORDER BY day_of_month ASC;";

        String[] selectionArgs = {
                conversationUuid,
                String.valueOf(startTimeMillis),
                String.valueOf(endTimeMillis)
        };

        Cursor cursor = db.rawQuery(sql, selectionArgs);

        if (cursor != null) {
            try {
                int dayOfMonthIndex = cursor.getColumnIndex("day_of_month");
                int messageCountIndex = cursor.getColumnIndex("message_count");

                if (dayOfMonthIndex != -1 && messageCountIndex != -1) {
                    while (cursor.moveToNext()) {
                        int day = cursor.getInt(dayOfMonthIndex);
                        int count = cursor.getInt(messageCountIndex);
                        messagesPerDay.put(day, count);
                    }
                }
            } finally {
                cursor.close();
            }
        }
        return messagesPerDay;
    }
    @Override
    public boolean hasMessageForAccount(
            final String accountUuid, final String messageUuid) {
        if (accountUuid == null || messageUuid == null) {
            return false;
        }
        final String selection =
                "SELECT 1 FROM "
                        + Message.TABLENAME
                        + " JOIN "
                        + Conversation.TABLENAME
                        + " ON "
                        + Message.TABLENAME
                        + "."
                        + Message.CONVERSATION
                        + "="
                        + Conversation.TABLENAME
                        + "."
                        + Conversation.UUID
                        + " WHERE "
                        + Message.TABLENAME
                        + "."
                        + Message.UUID
                        + "=? AND "
                        + Conversation.TABLENAME
                        + "."
                        + Conversation.ACCOUNT
                        + "=? LIMIT 1";
        try (final Cursor cursor =
                getReadableDatabase().rawQuery(selection, new String[] {messageUuid, accountUuid})) {
            return cursor.moveToFirst();
        }
    }

    @Override
    public MessagePayloadClassification getMessagePayloadClassification(
            final String accountUuid, final String messageUuid) {
        if (accountUuid == null || messageUuid == null) {
            return MessagePayloadClassification.NOT_APPLICABLE;
        }
        final String sql =
                "SELECT m."
                        + Message.TYPE
                        + ",m."
                        + Message.BODY
                        + ",s."
                        + SECURE_MESSAGE_PAYLOAD_MODE
                        + " FROM "
                        + Message.TABLENAME
                        + " m JOIN "
                        + Conversation.TABLENAME
                        + " c ON m."
                        + Message.CONVERSATION
                        + "=c."
                        + Conversation.UUID
                        + " LEFT JOIN "
                        + SECURE_MESSAGE_PAYLOAD_MODE_TABLENAME
                        + " s ON s."
                        + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                        + "=c."
                        + Conversation.ACCOUNT
                        + " AND s."
                        + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                        + "=m."
                        + Message.UUID
                        + " WHERE m."
                        + Message.UUID
                        + "=? AND c."
                        + Conversation.ACCOUNT
                        + "=? LIMIT 1";
        try (final Cursor cursor =
                getReadableDatabase().rawQuery(sql, new String[] {messageUuid, accountUuid})) {
            if (!cursor.moveToFirst()) {
                return MessagePayloadClassification.NOT_APPLICABLE;
            }
            final int type = cursor.getInt(0);
            final String body = cursor.isNull(1) ? null : cursor.getString(1);
            final SecureMessagePayloadMode mode =
                    cursor.isNull(2)
                            ? null
                            : SecureMessagePayloadMode.fromPersistedValue(cursor.getString(2));
            return MessagePayloadClassification.classify(
                    mode,
                    type == Message.TYPE_TEXT || type == Message.TYPE_PRIVATE,
                    body != null && !body.isEmpty());
        }
    }

    @Override
    public long countLegacyPlaintextMessages(final String accountUuid) {
        if (accountUuid == null) {
            return 0L;
        }
        final String sql =
                "SELECT COUNT(*) FROM "
                        + Message.TABLENAME
                        + " m JOIN "
                        + Conversation.TABLENAME
                        + " c ON m."
                        + Message.CONVERSATION
                        + "=c."
                        + Conversation.UUID
                        + " LEFT JOIN "
                        + SECURE_MESSAGE_PAYLOAD_MODE_TABLENAME
                        + " s ON s."
                        + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                        + "=c."
                        + Conversation.ACCOUNT
                        + " AND s."
                        + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                        + "=m."
                        + Message.UUID
                        + " WHERE c."
                        + Conversation.ACCOUNT
                        + "=? AND m."
                        + Message.TYPE
                        + " IN (?,?) AND m."
                        + Message.BODY
                        + " IS NOT NULL AND LENGTH(m."
                        + Message.BODY
                        + ")>0 AND s."
                        + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                        + " IS NULL";
        final String[] args = {
            accountUuid,
            Integer.toString(Message.TYPE_TEXT),
            Integer.toString(Message.TYPE_PRIVATE)
        };
        try (final Cursor cursor = getReadableDatabase().rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    @Override
    public List<LegacyPlaintextMessageRecord> getLegacyPlaintextMessageBatch(
            final String accountUuid, final long afterRowId, final int limit) {
        if (accountUuid == null || afterRowId < 0 || limit <= 0) {
            return Collections.emptyList();
        }
        final int boundedLimit = Math.min(limit, MAX_LEGACY_PLAINTEXT_MIGRATION_BATCH);
        final String sql =
                "SELECT m.rowid,m."
                        + Message.UUID
                        + ",m."
                        + Message.BODY
                        + " FROM "
                        + Message.TABLENAME
                        + " m JOIN "
                        + Conversation.TABLENAME
                        + " c ON m."
                        + Message.CONVERSATION
                        + "=c."
                        + Conversation.UUID
                        + " LEFT JOIN "
                        + SECURE_MESSAGE_PAYLOAD_MODE_TABLENAME
                        + " s ON s."
                        + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                        + "=c."
                        + Conversation.ACCOUNT
                        + " AND s."
                        + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                        + "=m."
                        + Message.UUID
                        + " WHERE c."
                        + Conversation.ACCOUNT
                        + "=? AND m.rowid>? AND m."
                        + Message.TYPE
                        + " IN (?,?) AND m."
                        + Message.BODY
                        + " IS NOT NULL AND LENGTH(m."
                        + Message.BODY
                        + ")>0 AND s."
                        + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                        + " IS NULL ORDER BY m.rowid ASC LIMIT ?";
        final String[] args = {
            accountUuid,
            Long.toString(afterRowId),
            Integer.toString(Message.TYPE_TEXT),
            Integer.toString(Message.TYPE_PRIVATE),
            Integer.toString(boundedLimit)
        };
        final List<LegacyPlaintextMessageRecord> result = new ArrayList<>(boundedLimit);
        try (final Cursor cursor = getReadableDatabase().rawQuery(sql, args)) {
            while (cursor.moveToNext()) {
                result.add(
                        new LegacyPlaintextMessageRecord(
                                cursor.getLong(0),
                                accountUuid,
                                cursor.getString(1),
                                cursor.getString(2)));
            }
        }
        return result;
    }

    @Override
    public List<LegacyPlaintextMessageRecord> getNewestLegacyPlaintextMessageBatch(
            final String accountUuid, final int limit) {
        if (accountUuid == null || limit <= 0) {
            return Collections.emptyList();
        }
        final int boundedLimit = Math.min(limit, MAX_LEGACY_PLAINTEXT_MIGRATION_BATCH);
        final String sql =
                "SELECT m.rowid,m."
                        + Message.UUID
                        + ",m."
                        + Message.BODY
                        + " FROM "
                        + Message.TABLENAME
                        + " m JOIN "
                        + Conversation.TABLENAME
                        + " c ON m."
                        + Message.CONVERSATION
                        + "=c."
                        + Conversation.UUID
                        + " LEFT JOIN "
                        + SECURE_MESSAGE_PAYLOAD_MODE_TABLENAME
                        + " s ON s."
                        + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                        + "=c."
                        + Conversation.ACCOUNT
                        + " AND s."
                        + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                        + "=m."
                        + Message.UUID
                        + " WHERE c."
                        + Conversation.ACCOUNT
                        + "=? AND m."
                        + Message.TYPE
                        + " IN (?,?) AND m."
                        + Message.BODY
                        + " IS NOT NULL AND LENGTH(m."
                        + Message.BODY
                        + ")>0 AND s."
                        + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                        + " IS NULL ORDER BY m."
                        + Message.TIME_SENT
                        + " DESC,m.rowid DESC LIMIT ?";
        final String[] args = {
            accountUuid,
            Integer.toString(Message.TYPE_TEXT),
            Integer.toString(Message.TYPE_PRIVATE),
            Integer.toString(boundedLimit)
        };
        final List<LegacyPlaintextMessageRecord> result = new ArrayList<>(boundedLimit);
        try (final Cursor cursor = getReadableDatabase().rawQuery(sql, args)) {
            while (cursor.moveToNext()) {
                result.add(
                        new LegacyPlaintextMessageRecord(
                                cursor.getLong(0),
                                accountUuid,
                                cursor.getString(1),
                                cursor.getString(2)));
            }
        }
        return result;
    }

    @Override
    public synchronized LegacyPlaintextSqliteCleanupResult cleanupMigratedLegacyPlaintextResidue() {
        final SQLiteDatabase db = getWritableDatabase();
        final long plaintextBefore = countPlaintextTextBodies(db);
        if (plaintextBefore != 0L) {
            return new LegacyPlaintextSqliteCleanupResult(
                    false,
                    plaintextBefore,
                    readPragmaLong(db, "PRAGMA secure_delete") != 0L,
                    false,
                    false,
                    false,
                    -1,
                    -1,
                    readPragmaLong(db, "PRAGMA freelist_count"),
                    plaintextBefore);
        }

        final String canaryToken =
                "neocontforensiccanary" + UUID.randomUUID().toString().replace("-", "");
        final long canaryRowId = Long.MAX_VALUE - Math.abs(System.nanoTime() % 1_000_000L);
        boolean canaryObserved = false;
        boolean canaryAbsent = false;
        boolean ftsIntegrityVerified = false;
        boolean secureDeleteEnabled = false;
        int checkpointBusy = -1;
        int walFramesRemaining = -1;
        long freelistAfterVacuum = -1L;
        long plaintextAfter = -1L;

        try {
            db.rawQuery("PRAGMA secure_delete=ON", null).close();
            secureDeleteEnabled = readPragmaLong(db, "PRAGMA secure_delete") != 0L;
            if (!secureDeleteEnabled) {
                throw new IllegalStateException("SQLite secure_delete could not be enabled");
            }

            db.execSQL(
                    "INSERT INTO messages_index(rowid,uuid,body) VALUES(?,?,?)",
                    new Object[] {canaryRowId, "forensic-cleanup-canary", canaryToken});
            canaryObserved = countFtsToken(db, canaryToken) > 0L;
            if (!canaryObserved) {
                throw new IllegalStateException("FTS cleanup canary was not indexed");
            }

            db.execSQL(COPY_PREEXISTING_ENTRIES);
            canaryAbsent = countFtsToken(db, canaryToken) == 0L;
            if (!canaryAbsent) {
                throw new IllegalStateException("FTS cleanup canary survived rebuild");
            }

            db.execSQL("INSERT INTO messages_index(messages_index) VALUES('integrity-check')");
            ftsIntegrityVerified = true;

            checkpointWalTruncate(db);
            db.execSQL("VACUUM");
            final int[] finalCheckpoint = checkpointWalTruncate(db);
            checkpointBusy = finalCheckpoint[0];
            walFramesRemaining = finalCheckpoint[1];
            freelistAfterVacuum = readPragmaLong(db, "PRAGMA freelist_count");
            plaintextAfter = countPlaintextTextBodies(db);

            if (countFtsToken(db, canaryToken) != 0L) {
                throw new IllegalStateException("FTS cleanup canary reappeared");
            }
        } finally {
            // Rebuild is idempotent and also removes a canary if an exception interrupted cleanup
            // before the normal rebuild point. Never leave synthetic forensic data searchable.
            try {
                if (countFtsToken(db, canaryToken) != 0L) {
                    db.execSQL(COPY_PREEXISTING_ENTRIES);
                }
            } catch (final RuntimeException ignored) {
            }
        }

        return new LegacyPlaintextSqliteCleanupResult(
                true,
                plaintextBefore,
                secureDeleteEnabled,
                canaryObserved,
                canaryAbsent,
                ftsIntegrityVerified,
                checkpointBusy,
                walFramesRemaining,
                freelistAfterVacuum,
                plaintextAfter);
    }

    private long countPlaintextTextBodies(final SQLiteDatabase db) {
        final String sql =
                "SELECT COUNT(*) FROM "
                        + Message.TABLENAME
                        + " WHERE "
                        + Message.TYPE
                        + " IN (?,?) AND "
                        + Message.BODY
                        + " IS NOT NULL AND LENGTH("
                        + Message.BODY
                        + ")>0";
        try (final Cursor cursor =
                db.rawQuery(
                        sql,
                        new String[] {
                            Integer.toString(Message.TYPE_TEXT),
                            Integer.toString(Message.TYPE_PRIVATE)
                        })) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    private long countFtsToken(final SQLiteDatabase db, final String token) {
        try (final Cursor cursor =
                db.rawQuery(
                        "SELECT COUNT(*) FROM messages_index WHERE messages_index MATCH ?",
                        new String[] {token})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    private long readPragmaLong(final SQLiteDatabase db, final String pragma) {
        try (final Cursor cursor = db.rawQuery(pragma, null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1L;
        }
    }

    private int[] checkpointWalTruncate(final SQLiteDatabase db) {
        try (final Cursor cursor = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) {
            if (!cursor.moveToFirst() || cursor.getColumnCount() < 3) {
                throw new IllegalStateException("SQLite WAL checkpoint returned no status");
            }
            final int busy = cursor.getInt(0);
            final int logFrames = cursor.getInt(1);
            final int checkpointedFrames = cursor.getInt(2);
            if (busy != 0) {
                throw new IllegalStateException("SQLite WAL checkpoint remained busy");
            }
            return new int[] {busy, logFrames, checkpointedFrames};
        }
    }

    @Override
    public boolean finalizeLegacyPlaintextMigration(
            final String accountUuid,
            final String messageUuid,
            final String expectedBody,
            final SecureMessagePayloadReference reference) {
        if (accountUuid == null
                || messageUuid == null
                || expectedBody == null
                || expectedBody.isEmpty()
                || reference == null
                || !accountUuid.equals(reference.getAccountUuid())
                || !messageUuid.equals(reference.getMessageUuid())) {
            return false;
        }
        final SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            final String sql =
                    "SELECT m."
                            + Message.TYPE
                            + ",m."
                            + Message.BODY
                            + ",s."
                            + SECURE_MESSAGE_PAYLOAD_MODE
                            + ",r."
                            + SECURE_MESSAGE_PAYLOAD_CONTENT_ID
                            + " FROM "
                            + Message.TABLENAME
                            + " m JOIN "
                            + Conversation.TABLENAME
                            + " c ON m."
                            + Message.CONVERSATION
                            + "=c."
                            + Conversation.UUID
                            + " LEFT JOIN "
                            + SECURE_MESSAGE_PAYLOAD_MODE_TABLENAME
                            + " s ON s."
                            + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                            + "=c."
                            + Conversation.ACCOUNT
                            + " AND s."
                            + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                            + "=m."
                            + Message.UUID
                            + " LEFT JOIN "
                            + SECURE_MESSAGE_PAYLOAD_REFERENCE_TABLENAME
                            + " r ON r."
                            + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                            + "=c."
                            + Conversation.ACCOUNT
                            + " AND r."
                            + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                            + "=m."
                            + Message.UUID
                            + " WHERE m."
                            + Message.UUID
                            + "=? AND c."
                            + Conversation.ACCOUNT
                            + "=? LIMIT 1";
            final int type;
            final String body;
            final SecureMessagePayloadMode mode;
            final String contentId;
            try (final Cursor cursor =
                    db.rawQuery(sql, new String[] {messageUuid, accountUuid})) {
                if (!cursor.moveToFirst()) {
                    return false;
                }
                type = cursor.getInt(0);
                body = cursor.isNull(1) ? null : cursor.getString(1);
                mode =
                        cursor.isNull(2)
                                ? null
                                : SecureMessagePayloadMode.fromPersistedValue(cursor.getString(2));
                contentId = cursor.isNull(3) ? null : cursor.getString(3);
            }
            if ((type != Message.TYPE_TEXT && type != Message.TYPE_PRIVATE)
                    || !reference.getContentId().equals(contentId)
                    || (mode != null && mode != SecureMessagePayloadMode.PROTECTED)) {
                return false;
            }
            if (mode == SecureMessagePayloadMode.PROTECTED
                    && (body == null || body.isEmpty())) {
                db.setTransactionSuccessful();
                return true;
            }
            if (!expectedBody.equals(body)) {
                return false;
            }

            if (mode == null) {
                final ContentValues modeValues = new ContentValues();
                modeValues.put(SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID, accountUuid);
                modeValues.put(SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID, messageUuid);
                modeValues.put(
                        SECURE_MESSAGE_PAYLOAD_MODE,
                        SecureMessagePayloadMode.PROTECTED.getPersistedValue());
                modeValues.put(SECURE_MESSAGE_PAYLOAD_UPDATED_AT, System.currentTimeMillis());
                if (db.insertWithOnConflict(
                                SECURE_MESSAGE_PAYLOAD_MODE_TABLENAME,
                                null,
                                modeValues,
                                SQLiteDatabase.CONFLICT_ABORT)
                        == -1) {
                    return false;
                }
            }

            final ContentValues bodyValues = new ContentValues();
            bodyValues.put(Message.BODY, "");
            if (db.update(
                            Message.TABLENAME,
                            bodyValues,
                            Message.UUID + "=?",
                            new String[] {messageUuid})
                    != 1) {
                return false;
            }
            // The existing after_message_update trigger removes the legacy plaintext tokens from
            // messages_index in the same transaction as the body retirement and mode promotion.
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public boolean isOutgoingTextMessageForAccount(
            final String accountUuid, final String messageUuid) {
        if (accountUuid == null || messageUuid == null) {
            return false;
        }
        final String selection =
                "SELECT 1 FROM "
                        + Message.TABLENAME
                        + " JOIN "
                        + Conversation.TABLENAME
                        + " ON "
                        + Message.TABLENAME
                        + "."
                        + Message.CONVERSATION
                        + "="
                        + Conversation.TABLENAME
                        + "."
                        + Conversation.UUID
                        + " WHERE "
                        + Message.TABLENAME
                        + "."
                        + Message.UUID
                        + "=? AND "
                        + Conversation.TABLENAME
                        + "."
                        + Conversation.ACCOUNT
                        + "=? AND "
                        + Message.TABLENAME
                        + "."
                        + Message.TYPE
                        + "=? AND "
                        + Message.TABLENAME
                        + "."
                        + Message.STATUS
                        + " IN (?,?,?,?,?,?) LIMIT 1";
        final String[] selectionArgs = {
            messageUuid,
            accountUuid,
            Integer.toString(Message.TYPE_TEXT),
            Integer.toString(Message.STATUS_UNSEND),
            Integer.toString(Message.STATUS_SEND),
            Integer.toString(Message.STATUS_SEND_FAILED),
            Integer.toString(Message.STATUS_WAITING),
            Integer.toString(Message.STATUS_SEND_RECEIVED),
            Integer.toString(Message.STATUS_SEND_DISPLAYED)
        };
        try (final Cursor cursor =
                getReadableDatabase().rawQuery(selection, selectionArgs)) {
            return cursor.moveToFirst();
        }
    }

    @Override
    public boolean isTextMessageForAccount(
            final String accountUuid, final String messageUuid) {
        if (accountUuid == null || messageUuid == null) {
            return false;
        }
        final String selection =
                "SELECT 1 FROM "
                        + Message.TABLENAME
                        + " JOIN "
                        + Conversation.TABLENAME
                        + " ON "
                        + Message.TABLENAME
                        + "."
                        + Message.CONVERSATION
                        + "="
                        + Conversation.TABLENAME
                        + "."
                        + Conversation.UUID
                        + " WHERE "
                        + Message.TABLENAME
                        + "."
                        + Message.UUID
                        + "=? AND "
                        + Conversation.TABLENAME
                        + "."
                        + Conversation.ACCOUNT
                        + "=? AND "
                        + Message.TABLENAME
                        + "."
                        + Message.TYPE
                        + " IN (?,?) LIMIT 1";
        final String[] selectionArgs = {
            messageUuid,
            accountUuid,
            Integer.toString(Message.TYPE_TEXT),
            Integer.toString(Message.TYPE_PRIVATE)
        };
        try (final Cursor cursor =
                getReadableDatabase().rawQuery(selection, selectionArgs)) {
            return cursor.moveToFirst();
        }
    }

    @Override
    @Nullable
    public SecureMessagePayloadMode getSecureMessagePayloadMode(
            final String accountUuid, final String messageUuid) {
        if (!hasMessageForAccount(accountUuid, messageUuid)) {
            return null;
        }
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                SECURE_MESSAGE_PAYLOAD_MODE_TABLENAME,
                                new String[] {SECURE_MESSAGE_PAYLOAD_MODE},
                                SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                                        + "=? AND "
                                        + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                                        + "=?",
                                new String[] {accountUuid, messageUuid},
                                null,
                                null,
                                null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return SecureMessagePayloadMode.fromPersistedValue(
                    cursor.getString(cursor.getColumnIndexOrThrow(SECURE_MESSAGE_PAYLOAD_MODE)));
        }
    }

    @Override
    public boolean claimSecureMessagePayloadMode(
            final String accountUuid,
            final String messageUuid,
            final SecureMessagePayloadMode mode) {
        if (mode == null || !hasMessageForAccount(accountUuid, messageUuid)) {
            return false;
        }
        if (getSecureMessagePayloadMode(accountUuid, messageUuid) != null) {
            return false;
        }
        final ContentValues values = new ContentValues();
        values.put(SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID, accountUuid);
        values.put(SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID, messageUuid);
        values.put(SECURE_MESSAGE_PAYLOAD_MODE, mode.getPersistedValue());
        values.put(SECURE_MESSAGE_PAYLOAD_UPDATED_AT, System.currentTimeMillis());
        return getWritableDatabase()
                        .insertWithOnConflict(
                                SECURE_MESSAGE_PAYLOAD_MODE_TABLENAME,
                                null,
                                values,
                                SQLiteDatabase.CONFLICT_IGNORE)
                != -1;
    }

    @Override
    public boolean updateSecureMessagePayloadMode(
            final String accountUuid,
            final String messageUuid,
            final SecureMessagePayloadMode mode) {
        if (mode == null || !hasMessageForAccount(accountUuid, messageUuid)) {
            return false;
        }
        final ContentValues values = new ContentValues();
        values.put(SECURE_MESSAGE_PAYLOAD_MODE, mode.getPersistedValue());
        values.put(SECURE_MESSAGE_PAYLOAD_UPDATED_AT, System.currentTimeMillis());
        return getWritableDatabase()
                        .update(
                                SECURE_MESSAGE_PAYLOAD_MODE_TABLENAME,
                                values,
                                SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                                        + "=? AND "
                                        + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                                        + "=?",
                                new String[] {accountUuid, messageUuid})
                == 1;
    }

    @Override
    @Nullable
    public SecureMessagePayloadReference findSecureMessagePayloadReference(
            final String accountUuid, final String messageUuid) {
        if (!hasMessageForAccount(accountUuid, messageUuid)) {
            return null;
        }
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                SECURE_MESSAGE_PAYLOAD_REFERENCE_TABLENAME,
                                new String[] {SECURE_MESSAGE_PAYLOAD_CONTENT_ID},
                                SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                                        + "=? AND "
                                        + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                                        + "=?",
                                new String[] {accountUuid, messageUuid},
                                null,
                                null,
                                null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return new SecureMessagePayloadReference(
                    accountUuid,
                    messageUuid,
                    cursor.getString(cursor.getColumnIndexOrThrow(SECURE_MESSAGE_PAYLOAD_CONTENT_ID)),
                    SecureMessagePayloadContext.NAMESPACE);
        }
    }

    @Override
    public List<SecureMessagePayloadReference> getValidatedProtectedTextPayloadReferences(
            final String accountUuid, final Collection<String> messageUuids) {
        if (accountUuid == null || messageUuids == null || messageUuids.isEmpty()) {
            return Collections.emptyList();
        }
        final LinkedHashSet<String> uniqueMessageUuids = new LinkedHashSet<>();
        for (final String messageUuid : messageUuids) {
            if (messageUuid != null && !messageUuid.isEmpty()) {
                uniqueMessageUuids.add(messageUuid);
            }
        }
        if (uniqueMessageUuids.isEmpty()) {
            return Collections.emptyList();
        }

        final List<String> args = new ArrayList<>();
        args.add(accountUuid);
        args.add(SecureMessagePayloadMode.PROTECTED.getPersistedValue());
        args.add(accountUuid);
        args.add(Integer.toString(Message.TYPE_TEXT));
        args.add(Integer.toString(Message.TYPE_PRIVATE));

        final StringBuilder sql =
                new StringBuilder(
                        "SELECT ref."
                                + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                                + ",ref."
                                + SECURE_MESSAGE_PAYLOAD_CONTENT_ID
                                + " FROM "
                                + SECURE_MESSAGE_PAYLOAD_REFERENCE_TABLENAME
                                + " ref JOIN "
                                + SECURE_MESSAGE_PAYLOAD_MODE_TABLENAME
                                + " mode ON mode."
                                + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                                + "=ref."
                                + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                                + " AND mode."
                                + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                                + "=ref."
                                + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                                + " JOIN "
                                + Message.TABLENAME
                                + " message ON message."
                                + Message.UUID
                                + "=ref."
                                + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                                + " JOIN "
                                + Conversation.TABLENAME
                                + " conversation ON message."
                                + Message.CONVERSATION
                                + "=conversation."
                                + Conversation.UUID
                                + " WHERE ref."
                                + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                                + "=? AND mode."
                                + SECURE_MESSAGE_PAYLOAD_MODE
                                + "=? AND conversation."
                                + Conversation.ACCOUNT
                                + "=? AND message."
                                + Message.TYPE
                                + " IN (?,?) AND ref."
                                + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                                + " IN (");
        int index = 0;
        for (final String messageUuid : uniqueMessageUuids) {
            if (index++ > 0) {
                sql.append(',');
            }
            sql.append('?');
            args.add(messageUuid);
        }
        sql.append(')');

        final List<SecureMessagePayloadReference> references = new ArrayList<>();
        try (final Cursor cursor =
                getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (cursor.moveToNext()) {
                references.add(
                        new SecureMessagePayloadReference(
                                accountUuid,
                                cursor.getString(0),
                                cursor.getString(1),
                                SecureMessagePayloadContext.NAMESPACE));
            }
        }
        return references;
    }

    @Override
    public boolean linkSecureMessagePayloadReference(
            final SecureMessagePayloadReference reference) {
        if (!hasMessageForAccount(reference.getAccountUuid(), reference.getMessageUuid())) {
            return false;
        }
        final SecureMessagePayloadReference existing =
                findSecureMessagePayloadReference(
                        reference.getAccountUuid(), reference.getMessageUuid());
        if (existing != null) {
            return existing.equals(reference);
        }
        final long now = System.currentTimeMillis();
        final ContentValues values = new ContentValues();
        values.put(SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID, reference.getAccountUuid());
        values.put(SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID, reference.getMessageUuid());
        values.put(SECURE_MESSAGE_PAYLOAD_CONTENT_ID, reference.getContentId());
        values.put(SECURE_MESSAGE_PAYLOAD_CREATED_AT, now);
        values.put(SECURE_MESSAGE_PAYLOAD_UPDATED_AT, now);
        return getWritableDatabase()
                        .insertWithOnConflict(
                                SECURE_MESSAGE_PAYLOAD_REFERENCE_TABLENAME,
                                null,
                                values,
                                SQLiteDatabase.CONFLICT_IGNORE)
                != -1;
    }

    @Override
    public boolean replaceSecureMessagePayloadReference(
            final SecureMessagePayloadReference expected,
            final SecureMessagePayloadReference replacement) {
        if (!expected.getAccountUuid().equals(replacement.getAccountUuid())
                || !expected.getMessageUuid().equals(replacement.getMessageUuid())
                || !hasMessageForAccount(
                        replacement.getAccountUuid(), replacement.getMessageUuid())) {
            return false;
        }
        final ContentValues values = new ContentValues();
        values.put(SECURE_MESSAGE_PAYLOAD_CONTENT_ID, replacement.getContentId());
        values.put(SECURE_MESSAGE_PAYLOAD_UPDATED_AT, System.currentTimeMillis());
        return getWritableDatabase()
                        .update(
                                SECURE_MESSAGE_PAYLOAD_REFERENCE_TABLENAME,
                                values,
                                SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                                        + "=? AND "
                                        + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                                        + "=? AND "
                                        + SECURE_MESSAGE_PAYLOAD_CONTENT_ID
                                        + "=?",
                                new String[] {
                                    expected.getAccountUuid(),
                                    expected.getMessageUuid(),
                                    expected.getContentId()
                                })
                == 1;
    }

    @Override
    public boolean deleteSecureMessagePayloadReference(
            final String accountUuid, final String messageUuid) {
        if (!hasMessageForAccount(accountUuid, messageUuid)) {
            return false;
        }
        return getWritableDatabase()
                        .delete(
                                SECURE_MESSAGE_PAYLOAD_REFERENCE_TABLENAME,
                                SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                                        + "=? AND "
                                        + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                                        + "=?",
                                new String[] {accountUuid, messageUuid})
                <= 1;
    }

    @Override
    public List<SecureMessagePayloadReference> getSecureMessagePayloadReferencesForConversation(
            final String accountUuid, final String conversationUuid) {
        if (accountUuid == null || conversationUuid == null) {
            return Collections.emptyList();
        }
        final List<SecureMessagePayloadReference> references = new ArrayList<>();
        final String selection =
                "SELECT "
                        + SECURE_MESSAGE_PAYLOAD_REFERENCE_TABLENAME
                        + "."
                        + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                        + ", "
                        + SECURE_MESSAGE_PAYLOAD_REFERENCE_TABLENAME
                        + "."
                        + SECURE_MESSAGE_PAYLOAD_CONTENT_ID
                        + " FROM "
                        + SECURE_MESSAGE_PAYLOAD_REFERENCE_TABLENAME
                        + " JOIN "
                        + Message.TABLENAME
                        + " ON "
                        + SECURE_MESSAGE_PAYLOAD_REFERENCE_TABLENAME
                        + "."
                        + SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID
                        + "="
                        + Message.TABLENAME
                        + "."
                        + Message.UUID
                        + " JOIN "
                        + Conversation.TABLENAME
                        + " ON "
                        + Message.TABLENAME
                        + "."
                        + Message.CONVERSATION
                        + "="
                        + Conversation.TABLENAME
                        + "."
                        + Conversation.UUID
                        + " WHERE "
                        + SECURE_MESSAGE_PAYLOAD_REFERENCE_TABLENAME
                        + "."
                        + SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID
                        + "=? AND "
                        + Conversation.TABLENAME
                        + "."
                        + Conversation.UUID
                        + "=? AND "
                        + Conversation.TABLENAME
                        + "."
                        + Conversation.ACCOUNT
                        + "=?";
        try (final Cursor cursor =
                getReadableDatabase().rawQuery(
                        selection, new String[] {accountUuid, conversationUuid, accountUuid})) {
            while (cursor.moveToNext()) {
                references.add(
                        new SecureMessagePayloadReference(
                                accountUuid,
                                cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                                SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID)),
                                cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                                SECURE_MESSAGE_PAYLOAD_CONTENT_ID)),
                                SecureMessagePayloadContext.NAMESPACE));
            }
        }
        return references;
    }

    @Override
    public boolean createSecureMessagePayloadPublication(
            final SecureMessagePayloadPublicationRecord record) {
        if (!hasMessageForAccount(record.getAccountUuid(), record.getMessageUuid())) {
            return false;
        }
        final ContentValues values = new ContentValues();
        values.put(SECURE_MESSAGE_PAYLOAD_PUBLICATION_ID, record.getPublicationId());
        values.put(SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID, record.getAccountUuid());
        values.put(SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID, record.getMessageUuid());
        values.put(SECURE_MESSAGE_PAYLOAD_CONTENT_ID, record.getContentId());
        if (record.getPreviousContentId() == null) {
            values.putNull(SECURE_MESSAGE_PAYLOAD_PREVIOUS_CONTENT_ID);
        } else {
            values.put(
                    SECURE_MESSAGE_PAYLOAD_PREVIOUS_CONTENT_ID,
                    record.getPreviousContentId());
        }
        values.put(SECURE_MESSAGE_PAYLOAD_NAMESPACE, record.getNamespace());
        values.put(SECURE_MESSAGE_PAYLOAD_PHASE, record.getPhase().name());
        values.put(SECURE_MESSAGE_PAYLOAD_CREATED_AT, record.getCreatedAt());
        values.put(SECURE_MESSAGE_PAYLOAD_UPDATED_AT, record.getUpdatedAt());
        return getWritableDatabase()
                        .insert(
                                SECURE_MESSAGE_PAYLOAD_PUBLICATION_TABLENAME,
                                null,
                                values)
                != -1;
    }

    @Override
    public List<SecureMessagePayloadPublicationRecord> getSecureMessagePayloadPublications() {
        final List<SecureMessagePayloadPublicationRecord> records = new ArrayList<>();
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                SECURE_MESSAGE_PAYLOAD_PUBLICATION_TABLENAME,
                                null,
                                null,
                                null,
                                null,
                                null,
                                SECURE_MESSAGE_PAYLOAD_CREATED_AT + " ASC")) {
            while (cursor.moveToNext()) {
                records.add(
                            new SecureMessagePayloadPublicationRecord(
                                    cursor.getString(
                                            cursor.getColumnIndexOrThrow(
                                                    SECURE_MESSAGE_PAYLOAD_PUBLICATION_ID)),
                                    cursor.getString(
                                            cursor.getColumnIndexOrThrow(
                                                    SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID)),
                                    cursor.getString(
                                            cursor.getColumnIndexOrThrow(
                                                    SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID)),
                                    cursor.getString(
                                            cursor.getColumnIndexOrThrow(
                                                    SECURE_MESSAGE_PAYLOAD_CONTENT_ID)),
                                    cursor.getString(
                                            cursor.getColumnIndexOrThrow(
                                                    SECURE_MESSAGE_PAYLOAD_NAMESPACE)),
                                    SecureMessagePayloadPublicationPhase.valueOf(
                                            cursor.getString(
                                                    cursor.getColumnIndexOrThrow(
                                                            SECURE_MESSAGE_PAYLOAD_PHASE))),
                                    cursor.getLong(
                                            cursor.getColumnIndexOrThrow(
                                                    SECURE_MESSAGE_PAYLOAD_CREATED_AT)),
                                    cursor.getLong(
                                            cursor.getColumnIndexOrThrow(
                                                    SECURE_MESSAGE_PAYLOAD_UPDATED_AT)),
                                    cursor.getString(
                                            cursor.getColumnIndexOrThrow(
                                                    SECURE_MESSAGE_PAYLOAD_PREVIOUS_CONTENT_ID))));
            }
        }
        return records;
    }

    @Override
    public boolean updateSecureMessagePayloadPublication(
            final String publicationId,
            final SecureMessagePayloadPublicationPhase phase) {
        final ContentValues values = new ContentValues();
        values.put(SECURE_MESSAGE_PAYLOAD_PHASE, phase.name());
        values.put(SECURE_MESSAGE_PAYLOAD_UPDATED_AT, System.currentTimeMillis());
        return getWritableDatabase()
                        .update(
                                SECURE_MESSAGE_PAYLOAD_PUBLICATION_TABLENAME,
                                values,
                                SECURE_MESSAGE_PAYLOAD_PUBLICATION_ID + "=?",
                                new String[] {publicationId})
                == 1;
    }

    @Override
    public boolean deleteSecureMessagePayloadPublication(final String publicationId) {
        return getWritableDatabase()
                        .delete(
                                SECURE_MESSAGE_PAYLOAD_PUBLICATION_TABLENAME,
                                SECURE_MESSAGE_PAYLOAD_PUBLICATION_ID + "=?",
                                new String[] {publicationId})
                == 1;
    }

    @Override
    public boolean createSecureMessagePayloadRetirement(
            final SecureMessagePayloadRetirementRecord record) {
        if (!hasMessageForAccount(record.getAccountUuid(), record.getMessageUuid())) {
            return false;
        }
        final ContentValues values = new ContentValues();
        values.put(SECURE_MESSAGE_PAYLOAD_RETIREMENT_ID, record.getRetirementId());
        values.put(SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID, record.getAccountUuid());
        values.put(SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID, record.getMessageUuid());
        values.put(SECURE_MESSAGE_PAYLOAD_CONTENT_ID, record.getContentId());
        values.put(SECURE_MESSAGE_PAYLOAD_NAMESPACE, record.getNamespace());
        values.put(SECURE_MESSAGE_PAYLOAD_PHASE, record.getPhase().name());
        values.put(SECURE_MESSAGE_PAYLOAD_CREATED_AT, record.getCreatedAt());
        values.put(SECURE_MESSAGE_PAYLOAD_UPDATED_AT, record.getUpdatedAt());
        return getWritableDatabase()
                        .insert(
                                SECURE_MESSAGE_PAYLOAD_RETIREMENT_TABLENAME,
                                null,
                                values)
                != -1;
    }

    @Override
    public List<SecureMessagePayloadRetirementRecord> getSecureMessagePayloadRetirements() {
        final List<SecureMessagePayloadRetirementRecord> records = new ArrayList<>();
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                SECURE_MESSAGE_PAYLOAD_RETIREMENT_TABLENAME,
                                null,
                                null,
                                null,
                                null,
                                null,
                                SECURE_MESSAGE_PAYLOAD_CREATED_AT + " ASC")) {
            while (cursor.moveToNext()) {
                records.add(
                        new SecureMessagePayloadRetirementRecord(
                                cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                                SECURE_MESSAGE_PAYLOAD_RETIREMENT_ID)),
                                cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                                SECURE_MESSAGE_PAYLOAD_ACCOUNT_UUID)),
                                cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                                SECURE_MESSAGE_PAYLOAD_MESSAGE_UUID)),
                                cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                                SECURE_MESSAGE_PAYLOAD_CONTENT_ID)),
                                cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                                SECURE_MESSAGE_PAYLOAD_NAMESPACE)),
                                SecureMessagePayloadRetirementPhase.valueOf(
                                        cursor.getString(
                                                cursor.getColumnIndexOrThrow(
                                                        SECURE_MESSAGE_PAYLOAD_PHASE))),
                                cursor.getLong(
                                        cursor.getColumnIndexOrThrow(
                                                SECURE_MESSAGE_PAYLOAD_CREATED_AT)),
                                cursor.getLong(
                                        cursor.getColumnIndexOrThrow(
                                                SECURE_MESSAGE_PAYLOAD_UPDATED_AT))));
            }
        }
        return records;
    }

    @Override
    public boolean updateSecureMessagePayloadRetirement(
            final String retirementId,
            final SecureMessagePayloadRetirementPhase phase) {
        final ContentValues values = new ContentValues();
        values.put(SECURE_MESSAGE_PAYLOAD_PHASE, phase.name());
        values.put(SECURE_MESSAGE_PAYLOAD_UPDATED_AT, System.currentTimeMillis());
        return getWritableDatabase()
                        .update(
                                SECURE_MESSAGE_PAYLOAD_RETIREMENT_TABLENAME,
                                values,
                                SECURE_MESSAGE_PAYLOAD_RETIREMENT_ID + "=?",
                                new String[] {retirementId})
                == 1;
    }

    @Override
    public boolean deleteSecureMessagePayloadRetirement(final String retirementId) {
        return getWritableDatabase()
                        .delete(
                                SECURE_MESSAGE_PAYLOAD_RETIREMENT_TABLENAME,
                                SECURE_MESSAGE_PAYLOAD_RETIREMENT_ID + "=?",
                                new String[] {retirementId})
                == 1;
    }

    @Override
    public SecureContentMetadataRecord create(
            final SecureContentMetadata metadata, @Nullable final String storageLocator) {
        final long now = System.currentTimeMillis();
        final SQLiteDatabase db = getWritableDatabase();
        db.insertOrThrow(
                SECURE_CONTENT_TABLENAME,
                null,
                secureContentValues(metadata, storageLocator, now, now));
        return new SecureContentMetadataRecord(metadata, storageLocator, now, now);
    }

    /**
     * The pre-S3 table cannot carry account ownership. Do not expose its records through the
     * account-scoped runtime boundary until an explicit schema/data migration exists.
     */
    @Override
    @Nullable
    public SecureContentMetadataRecord find(
            final String accountUuid, final String contentId) {
        final SecureContentMetadataRecord record = findByContentId(contentId);
        return record != null && accountUuid.equals(record.getAccountUuid()) ? record : null;
    }

    @Override
    public List<SecureContentMetadataRecord> findByMessageUuid(
            final String accountUuid, final String messageUuid) {
        if (accountUuid == null
                || accountUuid.isEmpty()
                || messageUuid == null
                || messageUuid.isEmpty()) {
            return Collections.emptyList();
        }
        final String sql =
                "SELECT secure_content.* FROM "
                        + SECURE_CONTENT_TABLENAME
                        + " secure_content JOIN "
                        + Message.TABLENAME
                        + " message ON secure_content."
                        + SECURE_CONTENT_MESSAGE_UUID
                        + "=message."
                        + Message.UUID
                        + " JOIN "
                        + Conversation.TABLENAME
                        + " conversation ON message."
                        + Message.CONVERSATION
                        + "=conversation."
                        + Conversation.UUID
                        + " WHERE conversation."
                        + Conversation.ACCOUNT
                        + "=? AND secure_content."
                        + SECURE_CONTENT_MESSAGE_UUID
                        + "=?";
        try (final Cursor cursor =
                getReadableDatabase().rawQuery(sql, new String[] {accountUuid, messageUuid})) {
            if (!cursor.moveToFirst()) {
                return Collections.emptyList();
            }
            final SecureContentMetadataRecord legacy = secureContentRecordFromCursor(cursor);
            final SecureContentMetadata metadata = legacy.getMetadata();
            final SecureContentMetadata accountOwned =
                    new SecureContentMetadata(
                            accountUuid,
                            metadata.getContentId(),
                            metadata.getNamespace(),
                            metadata.getMessageUuid(),
                            metadata.getMimeType(),
                            metadata.getFileName(),
                            metadata.getSizeBytes(),
                            metadata.getState(),
                            metadata.getCryptoVersion(),
                            metadata.getCreatedAt());
            return Collections.singletonList(
                    new SecureContentMetadataRecord(
                            accountOwned,
                            legacy.getStorageLocator(),
                            legacy.getUpdatedAt()));
        }
    }

    @Override
    public List<SecureContentMetadataRecord> findByAccount(final String accountUuid) {
        final List<SecureContentMetadataRecord> records = new ArrayList<>();
        final String sql =
                "SELECT secure_content.* FROM " + SECURE_CONTENT_TABLENAME + " secure_content "
                        + "JOIN " + Message.TABLENAME + " message ON secure_content."
                        + SECURE_CONTENT_MESSAGE_UUID + "=message." + Message.UUID + " "
                        + "JOIN " + Conversation.TABLENAME + " conversation ON message."
                        + Message.CONVERSATION + "=conversation." + Conversation.UUID + " "
                        + "WHERE conversation." + Conversation.ACCOUNT + "=?";
        try (final Cursor cursor =
                getReadableDatabase().rawQuery(sql, new String[] {accountUuid})) {
            while (cursor.moveToNext()) {
                final SecureContentMetadataRecord legacy = secureContentRecordFromCursor(cursor);
                final SecureContentMetadata metadata = legacy.getMetadata();
                final SecureContentMetadata accountOwned =
                        new SecureContentMetadata(
                                accountUuid,
                                metadata.getContentId(),
                                metadata.getNamespace(),
                                metadata.getMessageUuid(),
                                metadata.getMimeType(),
                                metadata.getFileName(),
                                metadata.getSizeBytes(),
                                metadata.getState(),
                                metadata.getCryptoVersion(),
                                metadata.getCreatedAt());
                records.add(
                        new SecureContentMetadataRecord(
                                accountOwned,
                                legacy.getStorageLocator(),
                                legacy.getUpdatedAt()));
            }
        }
        return records;
    }

    @Override
    public long getCommittedSecureContentSizeBytes(final String accountUuid) {
        final String sql =
                "SELECT COALESCE(SUM(secure_content."
                        + SECURE_CONTENT_SIZE_BYTES
                        + "),0) FROM "
                        + SECURE_CONTENT_TABLENAME
                        + " secure_content "
                        + "JOIN "
                        + Message.TABLENAME
                        + " message ON secure_content."
                        + SECURE_CONTENT_MESSAGE_UUID
                        + "=message."
                        + Message.UUID
                        + " "
                        + "JOIN "
                        + Conversation.TABLENAME
                        + " conversation ON message."
                        + Message.CONVERSATION
                        + "=conversation."
                        + Conversation.UUID
                        + " "
                        + "WHERE conversation."
                        + Conversation.ACCOUNT
                        + "=? AND secure_content."
                        + SECURE_CONTENT_STATE
                        + "=? AND secure_content."
                        + SECURE_CONTENT_SIZE_BYTES
                        + " IS NOT NULL";
        try (final Cursor cursor =
                getReadableDatabase()
                        .rawQuery(
                                sql,
                                new String[] {
                                    accountUuid,
                                    eu.siacs.conversations.storage.secure.SecureContentState
                                            .COMMITTED
                                            .getPersistedValue()
                                })) {
            return cursor.moveToFirst() ? Math.max(0L, cursor.getLong(0)) : 0L;
        }
    }

    @Override
    public boolean updateState(
            final String accountUuid,
            final String contentId,
            final SecureContentState state) {
        return find(accountUuid, contentId) != null && updateState(contentId, state);
    }

    @Override
    public boolean updateStorageLocator(
            final String accountUuid,
            final String contentId,
            @Nullable final String storageLocator) {
        return false;
    }

    @Override
    @Nullable
    public SecureContentMetadataRecord findByContentId(final String contentId) {
        return findSecureContent(SECURE_CONTENT_ID + "=?", new String[] {contentId});
    }

    @Override
    @Nullable
    public SecureContentMetadataRecord findByMessageUuid(final String messageUuid) {
        return findSecureContent(SECURE_CONTENT_MESSAGE_UUID + "=?", new String[] {messageUuid});
    }

    @Override
    public boolean updateState(final String contentId, final SecureContentState state) {
        final ContentValues values = new ContentValues();
        values.put(SECURE_CONTENT_STATE, state.getPersistedValue());
        values.put(SECURE_CONTENT_UPDATED_AT, System.currentTimeMillis());
        return getWritableDatabase()
                        .update(
                                SECURE_CONTENT_TABLENAME,
                                values,
                                SECURE_CONTENT_ID + "=?",
                                new String[] {contentId})
                == 1;
    }

    /**
     * Legacy database table has no account owner column. It therefore cannot satisfy a new
     * account-scoped delete without a future migration and must fail closed.
     */
    @Override
    public boolean deleteMetadata(final String accountUuid, final String contentId) {
        final SecureContentMetadataRecord record = findByContentId(contentId);
        return record != null
                && accountUuid.equals(record.getAccountUuid())
                && deleteMetadata(contentId);
    }

    @Override
    public boolean deleteMetadata(final String contentId) {
        return getWritableDatabase()
                        .delete(
                                SECURE_CONTENT_TABLENAME,
                                SECURE_CONTENT_ID + "=?",
                                new String[] {contentId})
                == 1;
    }

    @Nullable
    private SecureContentMetadataRecord findSecureContent(
            final String selection, final String[] selectionArgs) {
        try (final Cursor cursor =
                getReadableDatabase()
                        .query(
                                SECURE_CONTENT_TABLENAME,
                                null,
                                selection,
                                selectionArgs,
                                null,
                                null,
                                null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return secureContentRecordFromCursor(cursor);
        }
    }

    private static ContentValues secureContentValues(
            final SecureContentMetadata metadata,
            @Nullable final String storageLocator,
            final long createdAt,
            final long updatedAt) {
        final ContentValues values = new ContentValues();
        values.put(SECURE_CONTENT_ID, metadata.getContentId());
        if (metadata.getMessageUuid() == null) {
            values.putNull(SECURE_CONTENT_MESSAGE_UUID);
        } else {
            values.put(SECURE_CONTENT_MESSAGE_UUID, metadata.getMessageUuid());
        }
        if (metadata.getMimeType() == null) {
            values.putNull(SECURE_CONTENT_MIME_TYPE);
        } else {
            values.put(SECURE_CONTENT_MIME_TYPE, metadata.getMimeType());
        }
        if (metadata.getSizeBytes() == null) {
            values.putNull(SECURE_CONTENT_SIZE_BYTES);
        } else {
            values.put(SECURE_CONTENT_SIZE_BYTES, metadata.getSizeBytes());
        }
        values.put(SECURE_CONTENT_STATE, metadata.getState().getPersistedValue());
        if (storageLocator == null) {
            values.putNull(SECURE_CONTENT_STORAGE_LOCATOR);
        } else {
            values.put(SECURE_CONTENT_STORAGE_LOCATOR, storageLocator);
        }
        if (metadata.getCryptoVersion() == null) {
            values.putNull(SECURE_CONTENT_CRYPTO_VERSION);
        } else {
            values.put(SECURE_CONTENT_CRYPTO_VERSION, metadata.getCryptoVersion());
        }
        values.put(SECURE_CONTENT_CREATED_AT, createdAt);
        values.put(SECURE_CONTENT_UPDATED_AT, updatedAt);
        return values;
    }

    private static SecureContentMetadataRecord secureContentRecordFromCursor(final Cursor cursor) {
        final Long sizeBytes =
                cursor.isNull(cursor.getColumnIndexOrThrow(SECURE_CONTENT_SIZE_BYTES))
                        ? null
                        : cursor.getLong(cursor.getColumnIndexOrThrow(SECURE_CONTENT_SIZE_BYTES));
        final Integer cryptoVersion =
                cursor.isNull(cursor.getColumnIndexOrThrow(SECURE_CONTENT_CRYPTO_VERSION))
                        ? null
                        : cursor.getInt(cursor.getColumnIndexOrThrow(SECURE_CONTENT_CRYPTO_VERSION));
        final SecureContentMetadata metadata =
                new SecureContentMetadata(
                        cursor.getString(cursor.getColumnIndexOrThrow(SECURE_CONTENT_ID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(SECURE_CONTENT_MESSAGE_UUID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(SECURE_CONTENT_MIME_TYPE)),
                        sizeBytes,
                        SecureContentState.fromPersistedValue(
                                cursor.getString(cursor.getColumnIndexOrThrow(SECURE_CONTENT_STATE))),
                        cryptoVersion != null && cryptoVersion >= 0 ? cryptoVersion : null);
        return new SecureContentMetadataRecord(
                metadata,
                cursor.getString(cursor.getColumnIndexOrThrow(SECURE_CONTENT_STORAGE_LOCATOR)),
                cursor.getLong(cursor.getColumnIndexOrThrow(SECURE_CONTENT_CREATED_AT)),
                cursor.getLong(cursor.getColumnIndexOrThrow(SECURE_CONTENT_UPDATED_AT)));
    }

}
