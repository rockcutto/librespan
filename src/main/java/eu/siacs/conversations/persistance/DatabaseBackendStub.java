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
import java.util.Collections;
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
import eu.siacs.conversations.storage.secure.SecureContentMetadata;
import eu.siacs.conversations.storage.secure.SecureContentMetadataRecord;
import eu.siacs.conversations.storage.secure.SecureContentState;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadPublicationPhase;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadPublicationRecord;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadRetirementPhase;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadRetirementRecord;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadReference;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadMode;
import eu.siacs.conversations.services.ShortcutService;
import eu.siacs.conversations.utils.Resolver;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.mam.MamReference;

public class DatabaseBackendStub implements DatabaseBackend {
    @Override
    public SQLiteDatabase getWritableDatabase() {
        return null;
    }

    @Override
    public SQLiteDatabase getReadableDatabase() {
        return null;
    }

    @Override
    public boolean requiresMessageIndexRebuild() {
        return false;
    }

    @Override
    public void rebuildMessagesIndex() {

    }

    @Override
    public void createConversation(Conversation conversation) {

    }

    @Override
    public void createMessage(Message message) {

    }

    @Override
    public boolean createProtectedTextMessage(Message message, String accountUuid) {
        return false;
    }

    @Override
    public void loadSecureMessagePayloadModes(String accountUuid, List<Message> messages) {

    }

    @Override
    public void createAccount(Account account) {

    }

    @Override
    public void insertDiscoveryResult(ServiceDiscoveryResult result) {

    }

    @Override
    public ServiceDiscoveryResult findDiscoveryResult(String hash, String ver) {
        return null;
    }

    @Override
    public void saveResolverResult(String domain, Resolver.Result result) {

    }

    @Override
    public Resolver.Result findResolverResult(String domain) {
        return null;
    }

    @Override
    public void insertPresenceTemplate(PresenceTemplate template) {

    }

    @Override
    public List<PresenceTemplate> getPresenceTemplates() {
        return Collections.emptyList();
    }

    @Override
    public CopyOnWriteArrayList<Conversation> getConversations(int status) {
        return new CopyOnWriteArrayList<>();
    }

    @Override
    public ArrayList<Message> getMessages(Conversation conversations, int limit) {
        return null;
    }

    @Override
    public ArrayList<Message> getMessagesNearUuid(Conversation conversation, int limit, String uuid) {
        return null;
    }

    @Override
    public ArrayList<Message> getMessagesByIds(Conversation conversation, Set<String> ids) {
        return null;
    }

    @Override
    public ArrayList<Message> getMessagesByLocalUuids(
            Conversational conversation, Collection<String> uuids) {
        return new ArrayList<>();
    }

    @Override
    public ArrayList<Message> getMessages(Conversation conversation, int limit, long timestamp, boolean isForward) {
        return null;
    }

    @Override
    public Cursor getLegacyMessageSearchCursor(List<String> term, String uuid) {
        return null;
    }

    @Override
    public List<String> markFileAsDeleted(File file, boolean internal) {
        return Collections.emptyList();
    }

    @Override
    public void markFileAsDeleted(List<String> uuids) {

    }

    @Override
    public void markFilesAsChanged(List<FilePathInfo> files) {

    }

    @Override
    public List<FilePathInfo> getFilePathInfo() {
        return Collections.emptyList();
    }

    @Override
    public List<FilePath> getRelativeFilePaths(String account, Jid jid, int limit) {
        return Collections.emptyList();
    }

    @Override
    public List<AttachmentMessageRecord> getAttachmentMessageRecords(
            String account,
            Jid jid,
            long beforeTimeSent,
            String beforeMessageUuid,
            int limit) {
        return Collections.emptyList();
    }

    @Override
    public Map<Integer, FilePath> getRelativeFilePathsForConversationForMonth(String uuid, int year, int month) {
        return Collections.emptyMap();
    }

    @Override
    public boolean recordMessageModeration(Conversation conversation, String roomStanzaId,
            String by, String reason, long stamp) { return false; }

    @Override
    public Moderation getMessageModeration(Conversation conversation, String roomStanzaId) { return null; }

    @Override
    public List<String[]> getPendingModeratedMessageIds() { return java.util.Collections.emptyList(); }

    @Override
    public void markMessageModerationRetired(String accountUuid, String messageUuid) {}

    @Override
    public Message getMessageWithRoomStanzaId(Conversation conversation, String roomStanzaId) { return null; }

    @Override
    public Message getMessageWithServerMsgId(Conversation conversation, String messageId) {
        return null;
    }

    @Override
    public Message getMessageWithUuidOrRemoteId(Conversation conversation, String messageId) {
        return null;
    }

    @Override
    public Message getMessageWithRemoteMsgId(Conversation conversation, String remoteMsgId) {
        return null;
    }

    @Override
    public Conversation findConversation(String uuid) {
        return null;
    }

    @Override
    public Conversation findConversation(Account account, Jid contactJid, Jid counterpart) {
        return null;
    }

    @Override
    public String findConversationUuid(Jid account, Jid jid) {
        return "";
    }

    @Override
    public boolean deleteConversation(Account account, Jid contactJid, Jid counterpart) {
        return false;
    }

    @Override
    public void updateConversation(Conversation conversation) {

    }

    @Override
    public List<Account> getAccounts() {
        return Collections.emptyList();
    }

    @Override
    public List<Jid> getAccountJids(boolean enabledOnly) {
        return Collections.emptyList();
    }

    @Override
    public boolean updateAccount(Account account) {
        return false;
    }

    @Override
    public boolean scrubMigratedAccountSecrets(String accountUuid) {
        return false;
    }

    @Override
    public boolean areMigratedAccountSecretsScrubbed(String accountUuid) {
        return false;
    }

    @Override
    public Map<String, String> getLegacyAccountAuthenticationSecrets(String accountUuid) {
        return Collections.emptyMap();
    }

    @Override
    public boolean deleteAccount(Account account) {
        return false;
    }

    @Override
    public boolean updateMessage(Message message, boolean includeBody) {
        return false;
    }

    @Override
    public boolean updateMessage(Message message, String uuid) {
        return false;
    }

    @Override
    public void readRoster(Roster roster) {

    }

    @Override
    public void writeRoster(Roster roster) {

    }

    @Override
    public void deleteMessageInConversation(Conversation conversation, Message message) {

    }

    @Override
    public void deleteMessagesInConversation(Conversation conversation) {

    }

    @Override
    public LocalAccountDataSnapshot snapshotLocalAccountData(String accountUuid) {
        return new LocalAccountDataSnapshot(
                accountUuid, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    @Override
    public void deleteLocalAccountMessageData(LocalAccountDataSnapshot snapshot) {

    }

    @Override
    public void expireOldMessages(long timestamp) {

    }

    @Override
    public MamReference getLastMessageReceived(Account account) {
        return null;
    }

    @Override
    public long getLastTimeFingerprintUsed(Account account, String fingerprint) {
        return 0;
    }

    @Override
    public MamReference getLastClearDate(Account account) {
        return null;
    }

    @Override
    public SessionRecord loadSession(Account account, SignalProtocolAddress contact) {
        return null;
    }

    @Override
    public List<Integer> getSubDeviceSessions(Account account, SignalProtocolAddress contact) {
        return Collections.emptyList();
    }

    @Override
    public List<String> getKnownSignalAddresses(Account account) {
        return Collections.emptyList();
    }

    @Override
    public boolean containsSession(Account account, SignalProtocolAddress contact) {
        return false;
    }

    @Override
    public void storeSession(Account account, SignalProtocolAddress contact, SessionRecord session) {

    }

    @Override
    public void deleteSession(Account account, SignalProtocolAddress contact) {

    }

    @Override
    public void deleteAllSessions(Account account, SignalProtocolAddress contact) {

    }

    @Override
    public PreKeyRecord loadPreKey(Account account, int preKeyId) {
        return null;
    }

    @Override
    public boolean containsPreKey(Account account, int preKeyId) {
        return false;
    }

    @Override
    public void storePreKey(Account account, PreKeyRecord record) {

    }

    @Override
    public int deletePreKey(Account account, int preKeyId) {
        return 0;
    }

    @Override
    public SignedPreKeyRecord loadSignedPreKey(Account account, int signedPreKeyId) {
        return null;
    }

    @Override
    public List<SignedPreKeyRecord> loadSignedPreKeys(Account account) {
        return Collections.emptyList();
    }

    @Override
    public int getSignedPreKeysCount(Account account) {
        return 0;
    }

    @Override
    public boolean containsSignedPreKey(Account account, int signedPreKeyId) {
        return false;
    }

    @Override
    public void storeSignedPreKey(Account account, SignedPreKeyRecord record) {

    }

    @Override
    public void deleteSignedPreKey(Account account, int signedPreKeyId) {

    }

    @Override
    public IdentityKeyPair loadOwnIdentityKeyPair(Account account) {
        return null;
    }

    @Override
    public Set<IdentityKey> loadIdentityKeys(Account account, String name) {
        return Collections.emptySet();
    }

    @Override
    public Set<IdentityKey> loadIdentityKeys(Account account, String name, FingerprintStatus status) {
        return Collections.emptySet();
    }

    @Override
    public long numTrustedKeys(Account account, String name) {
        return 0;
    }

    @Override
    public void storePreVerification(Account account, String name, String fingerprint, FingerprintStatus status) {

    }

    @Override
    public FingerprintStatus getFingerprintStatus(Account account, String fingerprint) {
        return null;
    }

    @Override
    public boolean setIdentityKeyTrust(Account account, String fingerprint, FingerprintStatus fingerprintStatus) {
        return false;
    }

    @Override
    public boolean setIdentityKeyCertificate(Account account, String fingerprint, X509Certificate x509Certificate) {
        return false;
    }

    @Override
    public X509Certificate getIdentityKeyCertifcate(Account account, String fingerprint) {
        return null;
    }

    @Override
    public void storeIdentityKey(Account account, String name, IdentityKey identityKey, FingerprintStatus status) {

    }

    @Override
    public void storeOwnIdentityKeyPair(Account account, IdentityKeyPair identityKeyPair) {

    }

    @Override
    public int migrateLegacyConversationSecretsBatch(int limit) {
        return 0;
    }

    @Override
    public boolean hasLegacyConversationSecrets() {
        return false;
    }

    @Override
    public boolean retireProtectedAccountScopedSecrets(Account account) {
        return true;
    }

    @Override
    public int migrateLegacyPrivateCryptoStateBatch(Account account, int limit) {
        return 0;
    }

    @Override
    public boolean hasLegacyPrivateCryptoState(Account account) {
        return false;
    }

    @Override
    public void resetOwnAxolotlStateForRestore(Account account) {

    }

    @Override
    public void wipeAxolotlDb(Account account) {

    }

    @Override
    public List<ShortcutService.FrequentContact> getFrequentContacts(int days) {
        return Collections.emptyList();
    }

    @Override
    public Map<Integer, Integer> getMessagesCountGroupByDay(String conversationUuid, int year, int month) {
        return Collections.emptyMap();
    }
    @Override
    public boolean hasMessageForAccount(String accountUuid, String messageUuid) {
        return false;
    }

    @Override
    public MessagePayloadClassification getMessagePayloadClassification(
            String accountUuid, String messageUuid) {
        return MessagePayloadClassification.NOT_APPLICABLE;
    }

    @Override
    public long countLegacyPlaintextMessages(String accountUuid) {
        return 0L;
    }

    @Override
    public List<LegacyPlaintextMessageRecord> getLegacyPlaintextMessageBatch(
            String accountUuid, long afterRowId, int limit) {
        return Collections.emptyList();
    }

    @Override
    public List<LegacyPlaintextMessageRecord> getNewestLegacyPlaintextMessageBatch(
            String accountUuid, int limit) {
        return Collections.emptyList();
    }

    @Override
    public LegacyPlaintextSqliteCleanupResult cleanupMigratedLegacyPlaintextResidue() {
        return new LegacyPlaintextSqliteCleanupResult(
                false, 0L, false, false, false, false, -1, -1, -1L, 0L);
    }

    @Override
    public boolean finalizeLegacyPlaintextMigration(
            String accountUuid,
            String messageUuid,
            String expectedBody,
            SecureMessagePayloadReference reference) {
        return false;
    }

    @Override
    public boolean isOutgoingTextMessageForAccount(String accountUuid, String messageUuid) {
        return false;
    }

    @Override
    public boolean isTextMessageForAccount(String accountUuid, String messageUuid) {
        return false;
    }

    @Override
    public SecureMessagePayloadMode getSecureMessagePayloadMode(
            String accountUuid, String messageUuid) {
        return null;
    }

    @Override
    public boolean claimSecureMessagePayloadMode(
            String accountUuid, String messageUuid, SecureMessagePayloadMode mode) {
        return false;
    }

    @Override
    public boolean updateSecureMessagePayloadMode(
            String accountUuid, String messageUuid, SecureMessagePayloadMode mode) {
        return false;
    }

    @Override
    public SecureMessagePayloadReference findSecureMessagePayloadReference(
            String accountUuid, String messageUuid) {
        return null;
    }

    @Override
    public boolean linkSecureMessagePayloadReference(
            SecureMessagePayloadReference reference) {
        return false;
    }

    @Override
    public boolean replaceSecureMessagePayloadReference(
            SecureMessagePayloadReference expected,
            SecureMessagePayloadReference replacement) {
        return false;
    }

    @Override
    public boolean deleteSecureMessagePayloadReference(
            String accountUuid, String messageUuid) {
        return false;
    }

    @Override
    public List<SecureMessagePayloadReference> getSecureMessagePayloadReferencesForConversation(
            String accountUuid, String conversationUuid) {
        return Collections.emptyList();
    }

    @Override
    public boolean createSecureMessagePayloadPublication(
            SecureMessagePayloadPublicationRecord record) {
        return false;
    }

    @Override
    public List<SecureMessagePayloadPublicationRecord> getSecureMessagePayloadPublications() {
        return Collections.emptyList();
    }

    @Override
    public boolean updateSecureMessagePayloadPublication(
            String publicationId, SecureMessagePayloadPublicationPhase phase) {
        return false;
    }

    @Override
    public boolean deleteSecureMessagePayloadPublication(String publicationId) {
        return false;
    }

    @Override
    public boolean createSecureMessagePayloadRetirement(
            SecureMessagePayloadRetirementRecord record) {
        return false;
    }

    @Override
    public List<SecureMessagePayloadRetirementRecord> getSecureMessagePayloadRetirements() {
        return Collections.emptyList();
    }

    @Override
    public boolean updateSecureMessagePayloadRetirement(
            String retirementId, SecureMessagePayloadRetirementPhase phase) {
        return false;
    }

    @Override
    public boolean deleteSecureMessagePayloadRetirement(String retirementId) {
        return false;
    }

    @Override
    public SecureContentMetadataRecord create(
            SecureContentMetadata metadata, String storageLocator) {
        final long now = System.currentTimeMillis();
        return new SecureContentMetadataRecord(metadata, storageLocator, now, now);
    }

    /** Stub has no secure-content persistence and must reject all account-scoped access. */
    @Override
    public SecureContentMetadataRecord find(String accountUuid, String contentId) {
        return null;
    }

    @Override
    public List<SecureContentMetadataRecord> findByMessageUuid(
            String accountUuid, String messageUuid) {
        return Collections.emptyList();
    }

    @Override
    public List<SecureContentMetadataRecord> findByAccount(final String accountUuid) {
        return Collections.emptyList();
    }

    @Override
    public boolean updateState(
            String accountUuid, String contentId, SecureContentState state) {
        return false;
    }

    @Override
    public boolean updateStorageLocator(
            String accountUuid, String contentId, String storageLocator) {
        return false;
    }

    @Override
    public SecureContentMetadataRecord findByContentId(String contentId) {
        return null;
    }

    @Override
    public SecureContentMetadataRecord findByMessageUuid(String messageUuid) {
        return null;
    }

    @Override
    public boolean updateState(String contentId, SecureContentState state) {
        return false;
    }

    /** Stub has no secure-content persistence and must fail closed for scoped deletion. */
    @Override
    public boolean deleteMetadata(String accountUuid, String contentId) {
        return false;
    }

    @Override
    public boolean deleteMetadata(String contentId) {
        return false;
    }

}
