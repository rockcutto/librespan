package eu.siacs.conversations.persistance;

import android.database.Cursor;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;

import java.io.File;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.PresenceTemplate;
import eu.siacs.conversations.entities.Roster;
import eu.siacs.conversations.entities.ServiceDiscoveryResult;
import eu.siacs.conversations.storage.secure.LegacyPlaintextMessageRecord;
import eu.siacs.conversations.storage.secure.LegacyPlaintextSqliteCleanupResult;
import eu.siacs.conversations.storage.secure.MessagePayloadClassification;
import eu.siacs.conversations.storage.secure.SecureContentMetadataStore;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadPublicationRecord;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadRetirementRecord;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadReference;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadMode;
import eu.siacs.conversations.services.ShortcutService;
import eu.siacs.conversations.utils.Resolver;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.mam.MamReference;

public interface DatabaseBackend extends SecureContentMetadataStore {
    SQLiteDatabase getWritableDatabase();
    SQLiteDatabase getReadableDatabase();

    boolean requiresMessageIndexRebuild();
    void rebuildMessagesIndex();
    void createConversation(Conversation conversation);
    void createMessage(Message message);

    /**
     * Atomically persists message metadata with an empty body and claims protected PENDING mode.
     */
    boolean createProtectedTextMessage(Message message, String accountUuid);

    /** Applies durable classification metadata without opening or decrypting Store content. */
    void loadSecureMessagePayloadModes(String accountUuid, List<Message> messages);
    void createAccount(Account account);
    void insertDiscoveryResult(ServiceDiscoveryResult result);
    ServiceDiscoveryResult findDiscoveryResult(final String hash, final String ver);
    void saveResolverResult(String domain, Resolver.Result result);
    Resolver.Result findResolverResult(String domain);
    void insertPresenceTemplate(PresenceTemplate template);
    List<PresenceTemplate> getPresenceTemplates();
    CopyOnWriteArrayList<Conversation> getConversations(int status);
    ArrayList<Message> getMessages(Conversation conversations, int limit);
    ArrayList<Message> getMessagesNearUuid(Conversation conversation, int limit, String uuid);
    ArrayList<Message> getMessagesByIds(Conversation conversation, Set<String> ids);
    ArrayList<Message> getMessagesByLocalUuids(
            Conversational conversation, Collection<String> uuids);
    ArrayList<Message> getMessages(Conversation conversation, int limit, long timestamp, boolean isForward);
    Cursor getLegacyMessageSearchCursor(final List<String> term, final String uuid);
    List<String> markFileAsDeleted(final File file, final boolean internal);
    void markFileAsDeleted(List<String> uuids);
    void markFilesAsChanged(List<FilePathInfo> files);
    List<FilePathInfo> getFilePathInfo();
    List<FilePath> getRelativeFilePaths(String account, Jid jid, int limit);
    List<AttachmentMessageRecord> getAttachmentMessageRecords(
            String account,
            Jid jid,
            long beforeTimeSent,
            String beforeMessageUuid,
            int limit);
    Map<Integer, FilePath> getRelativeFilePathsForConversationForMonth(String uuid, int year, int month);
    /** Stored even when the target has not yet been loaded from MAM. */
    boolean recordMessageModeration(Conversation conversation, String roomStanzaId,
            String by, String reason, long stamp);
    Moderation getMessageModeration(Conversation conversation, String roomStanzaId);
    List<String[]> getPendingModeratedMessageIds();
    void markMessageModerationRetired(String accountUuid, String messageUuid);
    /** Store an unverified XEP-0424 MUC event; never mutates the target. */
    boolean recordUnverifiedMucRetraction(
            Conversation room, String requestId, String targetRoomStanzaId,
            Jid senderFullJid, String senderOccupantId, long eventTime);

    /** Bounded snapshot of events awaiting sender verification. */
    List<PendingRetraction> getUnverifiedMucRetractions(
            Conversation room, String targetRoomStanzaId);

    /**
     * Verify an incoming MUC retraction against exactly one persisted
     * message. Does not scrub content or acknowledge remote deletion.
     */
    boolean verifyUnverifiedMucRetraction(
            Conversation room, String requestId);

    /**
     * Atomically scrub durable message presentation fields and move
     * VERIFIED -> RETIRE_PENDING. Does NOT retire SCS content.
     * Not to be invoked until runtime retirement/recovery is wired.
     */
    boolean beginVerifiedMucRetractionRetirement(
            Conversation room, String requestId);

    /** An account-scoped, durable SCS retirement job. */
    final class PendingRetractionRetirement {
        public final String accountUuid;
        public final String conversationUuid;
        public final String requestId;
        public final String messageUuid;

        public PendingRetractionRetirement(
                String accountUuid,
                String conversationUuid,
                String requestId,
                String messageUuid) {
            this.accountUuid = accountUuid;
            this.conversationUuid = conversationUuid;
            this.requestId = requestId;
            this.messageUuid = messageUuid;
        }
    }

    /** Returns up to 128 verified, scrubbed retirement jobs. */
    /** Verified events not yet transitioned to RETIRE_PENDING. */
    List<PendingRetractionRetirement>
            getVerifiedMucRetractionsToResume();

    List<PendingRetractionRetirement>
            getPendingMucRetractionRetirements();

    /**
     * Call only AFTER successful SCS retirement and cache invalidation.
     * Changes both durable states atomically.
     */
    boolean completeMucRetractionRetirement(
            PendingRetractionRetirement job);

    final class PendingRetraction {
        public final String requestId;
        public final String targetRoomStanzaId;
        public final String senderFullJid;
        public final String senderOccupantId;
        public final long eventTime;

        public PendingRetraction(
                String requestId, String targetRoomStanzaId,
                String senderFullJid, String senderOccupantId,
                long eventTime) {
            this.requestId = requestId;
            this.targetRoomStanzaId = targetRoomStanzaId;
            this.senderFullJid = senderFullJid;
            this.senderOccupantId = senderOccupantId;
            this.eventTime = eventTime;
        }
    }

    /** Account-scoped durable XEP-0424 retraction check. */
    boolean isMessageRetracted(String accountUuid, String messageUuid);

    /** True only for a verified, durably associated MUC retraction. */
    boolean hasVerifiedMucRetractionForRoomStanzaId(
            Conversation room, String roomStanzaId);

    Message getMessageWithRoomStanzaId(Conversation conversation, String roomStanzaId);

    default boolean hasMessageWithRoomStanzaId(
            final Conversation conversation, final String roomStanzaId) {
        if (conversation == null || roomStanzaId == null || roomStanzaId.isEmpty()) {
            return false;
        }
        final SQLiteDatabase db = getReadableDatabase();
        if (db == null) {
            return false;
        }
        try (final Cursor cursor =
                db.rawQuery(
                        "SELECT 1 FROM "
                                + Message.TABLENAME
                                + " WHERE "
                                + Message.CONVERSATION
                                + "=? AND "
                                + Message.ROOM_STANZA_ID
                                + "=? LIMIT 1",
                        new String[] {conversation.getUuid(), roomStanzaId})) {
            return cursor.moveToFirst();
        }
    }

    final class Moderation {
        public final String by;
        public final String reason;
        public final long stamp;

        public Moderation(String by, String reason, long stamp) {
            this.by = by;
            this.reason = reason;
            this.stamp = stamp;
        }
    }

    Message getMessageWithServerMsgId(
            final Conversation conversation, final String messageId);
    Message getMessageWithUuidOrRemoteId(
            final Conversation conversation, final String messageId);
    Message getMessageWithRemoteMsgId(
            final Conversation conversation, final String remoteMsgId);
    Conversation findConversation(final String uuid);
    Conversation findConversation(final Account account, final Jid contactJid, final Jid counterpart);
    String findConversationUuid(final Jid account, final Jid jid);
    boolean deleteConversation(final Account account, final Jid contactJid, final Jid counterpart);
    void updateConversation(final Conversation conversation);
    List<Account> getAccounts();
    List<Jid> getAccountJids(final boolean enabledOnly);
    boolean updateAccount(Account account);

    /** Retires only the v1 migrated authentication secrets from the legacy account row. */
    boolean scrubMigratedAccountSecrets(String accountUuid);

    /** Exact verification used before the Vault migration journal may become COMMITTED. */
    boolean areMigratedAccountSecretsScrubbed(String accountUuid);

    /**
     * Transitional source for the v1 Vault migration only. Callers must not use this after the
     * migration journal is authoritative.
     */
    Map<String, String> getLegacyAccountAuthenticationSecrets(String accountUuid);

    boolean deleteAccount(final Account account);
    boolean updateMessage(final Message message, final boolean includeBody);
    boolean updateMessage(final Message message, final String uuid);

    /** Account-scoped persistence boundary for future protected message payload references. */
    boolean hasMessageForAccount(String accountUuid, String messageUuid);

    /**
     * Captures message ownership identities for crypto-first conversation retirement.
     *
     * This returns identifiers only, never message bodies or storage paths. It deliberately joins
     * through the owning conversation account so a caller cannot retire another account's media
     * by supplying only a conversation UUID.
     */
    default List<String> getMessageUuidsForConversation(
            final String accountUuid, final String conversationUuid) {
        final ArrayList<String> result = new ArrayList<>();
        if (accountUuid == null || conversationUuid == null) {
            return result;
        }
        final String sql =
                "SELECT "
                        + Message.TABLENAME
                        + "."
                        + Message.UUID
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
                        + " WHERE "
                        + Message.TABLENAME
                        + "."
                        + Message.CONVERSATION
                        + "=? AND "
                        + Conversation.TABLENAME
                        + "."
                        + Conversation.ACCOUNT
                        + "=?";
        try (final Cursor cursor =
                getReadableDatabase().rawQuery(sql, new String[] {conversationUuid, accountUuid})) {
            while (cursor.moveToNext()) {
                result.add(cursor.getString(0));
            }
        }
        return result;
    }

    /**
     * Narrow classification boundary for the U4 opt-in protected outgoing text producer.
     * This is not a reader authorization capability.
     */
    boolean isOutgoingTextMessageForAccount(String accountUuid, String messageUuid);

    /** Account-scoped classification for incoming or outgoing text/private messages. */
    boolean isTextMessageForAccount(String accountUuid, String messageUuid);

    /**
     * Exact local storage/protection classification. LEGACY_PLAINTEXT is derived from the durable
     * Message.body fact and absence of a protected-mode row; it is never treated as Secure Content.
     */
    MessagePayloadClassification getMessagePayloadClassification(
            String accountUuid, String messageUuid);

    /** Cheap inventory for future bounded legacy-text migration and coverage reporting. */
    long countLegacyPlaintextMessages(String accountUuid);

    /**
     * Returns at most a small keyset page of durable LEGACY_PLAINTEXT rows.
     *
     * The plaintext is a short-lived migration input only. Implementations must scope by account,
     * exclude any row that already has a secure mode, and order by Message rowid so callers can
     * make forward progress without an OFFSET scan.
     */
    List<LegacyPlaintextMessageRecord> getLegacyPlaintextMessageBatch(
            String accountUuid, long afterRowId, int limit);

    /**
     * Returns the newest remaining LEGACY_PLAINTEXT rows for low-priority background migration.
     *
     * This is intentionally separate from the ascending keyset boundary used by explicit bounded
     * callers. No OFFSET is allowed; rows that successfully migrate naturally disappear from the
     * next newest-first selection.
     */
    List<LegacyPlaintextMessageRecord> getNewestLegacyPlaintextMessageBatch(
            String accountUuid, int limit);

    /**
     * Atomically promotes one already-published legacy payload to PROTECTED and retires its
     * Message.body plaintext. The exact account/message/content relation and expected body must
     * still match; otherwise the operation fails without modifying the legacy row.
     */
    boolean finalizeLegacyPlaintextMigration(
            String accountUuid,
            String messageUuid,
            String expectedBody,
            SecureMessagePayloadReference reference);

    /**
     * Performs the expensive one-shot SQLite residue cleanup only when no TEXT/PRIVATE body
     * plaintext remains. Implementations must fail closed instead of deleting unresolved bodies.
     */
    LegacyPlaintextSqliteCleanupResult cleanupMigratedLegacyPlaintextResidue();

    /** Durable fail-closed classification for an explicitly selected protected outgoing text. */
    SecureMessagePayloadMode getSecureMessagePayloadMode(String accountUuid, String messageUuid);
    boolean claimSecureMessagePayloadMode(
            String accountUuid, String messageUuid, SecureMessagePayloadMode mode);
    boolean updateSecureMessagePayloadMode(
            String accountUuid, String messageUuid, SecureMessagePayloadMode mode);

    SecureMessagePayloadReference findSecureMessagePayloadReference(
            String accountUuid, String messageUuid);

    /**
     * Bounded read-plan lookup for protected text.
     *
     * Implementations return only exact account-owned text/private messages whose durable mode is
     * PROTECTED and whose current account/message relation exists. The default preserves
     * compatibility for non-production backends; production overrides it with one joined query.
     */
    default List<SecureMessagePayloadReference> getValidatedProtectedTextPayloadReferences(
            final String accountUuid, final Collection<String> messageUuids) {
        final ArrayList<SecureMessagePayloadReference> references = new ArrayList<>();
        if (accountUuid == null || messageUuids == null || messageUuids.isEmpty()) {
            return references;
        }
        for (final String messageUuid : new java.util.LinkedHashSet<>(messageUuids)) {
            if (messageUuid == null
                    || !isTextMessageForAccount(accountUuid, messageUuid)
                    || getSecureMessagePayloadMode(accountUuid, messageUuid)
                            != SecureMessagePayloadMode.PROTECTED) {
                continue;
            }
            final SecureMessagePayloadReference reference =
                    findSecureMessagePayloadReference(accountUuid, messageUuid);
            if (reference != null) {
                references.add(reference);
            }
        }
        return references;
    }
    boolean linkSecureMessagePayloadReference(SecureMessagePayloadReference reference);
    boolean replaceSecureMessagePayloadReference(
            SecureMessagePayloadReference expected,
            SecureMessagePayloadReference replacement);
    boolean deleteSecureMessagePayloadReference(String accountUuid, String messageUuid);
    List<SecureMessagePayloadReference> getSecureMessagePayloadReferencesForConversation(
            String accountUuid, String conversationUuid);

    /** Durable non-plaintext evidence for coordinator publication/recovery. */
    boolean createSecureMessagePayloadPublication(
            SecureMessagePayloadPublicationRecord record);
    List<SecureMessagePayloadPublicationRecord> getSecureMessagePayloadPublications();
    boolean updateSecureMessagePayloadPublication(
            String publicationId,
            eu.siacs.conversations.storage.secure.SecureMessagePayloadPublicationPhase phase);
    boolean deleteSecureMessagePayloadPublication(String publicationId);

    /** Durable non-plaintext evidence for protected message payload retirement/recovery. */
    boolean createSecureMessagePayloadRetirement(
            SecureMessagePayloadRetirementRecord record);
    List<SecureMessagePayloadRetirementRecord> getSecureMessagePayloadRetirements();
    boolean updateSecureMessagePayloadRetirement(
            String retirementId,
            eu.siacs.conversations.storage.secure.SecureMessagePayloadRetirementPhase phase);
    boolean deleteSecureMessagePayloadRetirement(String retirementId);
    void readRoster(Roster roster);
    void writeRoster(final Roster roster);
    void deleteMessageInConversation(Conversation conversation, Message message);
    void deleteMessagesInConversation(Conversation conversation);
    LocalAccountDataSnapshot snapshotLocalAccountData(String accountUuid);

    /**
     * Approximate logical SCS payload size for one account.
     *
     * Production overrides this with a single aggregate query. The default keeps test/stub
     * implementations source-compatible and counts only COMMITTED records with known size.
     */
    default long getCommittedSecureContentSizeBytes(final String accountUuid) {
        long total = 0L;
        for (final eu.siacs.conversations.storage.secure.SecureContentMetadataRecord record
                : findByAccount(accountUuid)) {
            final eu.siacs.conversations.storage.secure.SecureContentMetadata metadata =
                    record.getMetadata();
            if (metadata.getState()
                            != eu.siacs.conversations.storage.secure.SecureContentState.COMMITTED
                    || metadata.getSizeBytes() == null) {
                continue;
            }
            final long size = metadata.getSizeBytes();
            total = Long.MAX_VALUE - total < size ? Long.MAX_VALUE : total + size;
        }
        return total;
    }

    void deleteLocalAccountMessageData(LocalAccountDataSnapshot snapshot);
    void expireOldMessages(long timestamp);
    MamReference getLastMessageReceived(Account account);
    long getLastTimeFingerprintUsed(Account account, String fingerprint);
    MamReference getLastClearDate(Account account);
    SessionRecord loadSession(Account account, SignalProtocolAddress contact);
    List<Integer> getSubDeviceSessions(Account account, SignalProtocolAddress contact);
    List<String> getKnownSignalAddresses(Account account);
    boolean containsSession(Account account, SignalProtocolAddress contact);
    void storeSession(
            Account account, SignalProtocolAddress contact, SessionRecord session);
    void deleteSession(Account account, SignalProtocolAddress contact);
    void deleteAllSessions(Account account, SignalProtocolAddress contact);
    PreKeyRecord loadPreKey(Account account, int preKeyId);
    boolean containsPreKey(Account account, int preKeyId);
    void storePreKey(Account account, PreKeyRecord record);
    int deletePreKey(Account account, int preKeyId);
    SignedPreKeyRecord loadSignedPreKey(Account account, int signedPreKeyId);
    List<SignedPreKeyRecord> loadSignedPreKeys(Account account);
    int getSignedPreKeysCount(Account account);
    boolean containsSignedPreKey(Account account, int signedPreKeyId);
    void storeSignedPreKey(Account account, SignedPreKeyRecord record);
    void deleteSignedPreKey(Account account, int signedPreKeyId);
    IdentityKeyPair loadOwnIdentityKeyPair(Account account);
    Set<IdentityKey> loadIdentityKeys(Account account, String name);
    Set<IdentityKey> loadIdentityKeys(
            Account account, String name, FingerprintStatus status);
    long numTrustedKeys(Account account, String name);
    void storePreVerification(
            Account account, String name, String fingerprint, FingerprintStatus status);
    FingerprintStatus getFingerprintStatus(Account account, String fingerprint);
    boolean setIdentityKeyTrust(
            Account account, String fingerprint, FingerprintStatus fingerprintStatus);
    boolean setIdentityKeyCertificate(
            Account account, String fingerprint, X509Certificate x509Certificate);
    X509Certificate getIdentityKeyCertifcate(Account account, String fingerprint);
    void storeIdentityKey(
            Account account, String name, IdentityKey identityKey, FingerprintStatus status);
    void storeOwnIdentityKeyPair(Account account, IdentityKeyPair identityKeyPair);
    int migrateLegacyConversationSecretsBatch(int limit);
    boolean hasLegacyConversationSecrets();
    boolean retireProtectedAccountScopedSecrets(Account account);
    int migrateLegacyPrivateCryptoStateBatch(Account account, int limit);
    boolean hasLegacyPrivateCryptoState(Account account);
    void resetOwnAxolotlStateForRestore(Account account);
    void wipeAxolotlDb(Account account);
    List<ShortcutService.FrequentContact> getFrequentContacts(final int days);
    Map<Integer, Integer> getMessagesCountGroupByDay(String conversationUuid, int year, int month);
}
