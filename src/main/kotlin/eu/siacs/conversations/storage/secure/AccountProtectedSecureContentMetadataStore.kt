// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.crypto.tink.Aead
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Private v1 metadata persistence.
 *
 * Each serialized record is authenticated and encrypted with account-scoped material. The
 * preference key is only a non-secret digest index; it is not an account/content authorization
 * capability. A malformed, unverifiable, or mismatched record is treated as unavailable.
 */
internal class AccountProtectedSecureContentMetadataStore(
    context: Context,
    private val keyMaterialStore: PersistentSecureContentKeyMaterialStore,
) : SecureContentMetadataStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val authenticatedCache = SecureContentAuthenticatedMetadataCache()
    private val messageRelationIndex = SecureContentMessageRelationIndex()
    private var fullSnapshotLoaded = false

    /**
     * Drops process-local authenticated metadata after a crypto-epoch transition.
     *
     * This deliberately does not scan persistent metadata. Exact content lookups authenticate
     * records lazily; callers that explicitly enumerate by account/message pay for a full snapshot.
     */
    @Synchronized
    internal fun resetAuthenticatedState() {
        authenticatedCache.rebuild(emptyList())
        messageRelationIndex.rebuild(emptyList())
        fullSnapshotLoaded = false
    }

    @Synchronized
    internal fun reloadAuthenticatedSnapshot() {
        val scanStarted = System.nanoTime()
        val records = readAllAuthenticatedRecordsFromDisk()
        SecureColdStartPerfTrace.stage(
            "store_metadata_scan",
            System.nanoTime() - scanStarted,
        )
        SecureColdStartPerfTrace.increment("store_metadata_records", records.size.toLong())
        authenticatedCache.rebuild(records)
        val indexStarted = System.nanoTime()
        messageRelationIndex.rebuild(records)
        SecureColdStartPerfTrace.stage(
            "store_message_index_rebuild",
            System.nanoTime() - indexStarted,
        )
        fullSnapshotLoaded = true
    }

    @Synchronized
    private fun ensureFullSnapshotLoaded() {
        if (!fullSnapshotLoaded) {
            reloadAuthenticatedSnapshot()
        }
    }

    @Synchronized
    override fun create(
        metadata: SecureContentMetadata,
        storageLocator: String?,
    ): SecureContentMetadataRecord {
        val now = System.currentTimeMillis()
        val normalized = metadata.copy(createdAt = metadata.createdAt.takeIf { it > 0L } ?: now)
        val record = SecureContentMetadataRecord(normalized, storageLocator, now)
        check(writeNew(record)) { "Unable to persist secure content metadata" }
        authenticatedCache.put(record)
        messageRelationIndex.add(record)
        return record
    }

    /**
     * Exact lookup stays O(1) with respect to total Secure Content size.
     *
     * The deterministic preference key lets us authenticate only the requested record instead of
     * eagerly decrypting every metadata entry at process start.
     */
    @Synchronized
    override fun find(accountUuid: String, contentId: String): SecureContentMetadataRecord? {
        val readScope = SecureContentMetadataAeadReadScope(::aeadForRead)
        return try {
            findInternal(accountUuid, contentId, readScope)
        } finally {
            SecureColdStartPerfTrace.increment(
                "store_metadata_exact_aead_resolves",
                readScope.resolveCount.toLong(),
            )
        }
    }

    /**
     * Bounded account/page lookup scope. It reuses the account metadata AEAD only for the lifetime
     * of the current read plan and never builds a global metadata snapshot.
     */
    @Synchronized
    internal fun beginReadScope(accountUuid: String): MetadataReadScope {
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        return MetadataReadScope(
            accountUuid,
            SecureContentMetadataAeadReadScope(::aeadForRead),
        )
    }

    private fun findInternal(
        accountUuid: String,
        contentId: String,
        readScope: SecureContentMetadataAeadReadScope,
    ): SecureContentMetadataRecord? {
        authenticatedCache.find(accountUuid, contentId)?.let { return it }
        val record =
            readByPreferenceKey(recordKey(accountUuid, contentId), readScope)
                ?.takeIf {
                    it.accountUuid == accountUuid &&
                        it.contentId == contentId
                }
        if (record != null) {
            authenticatedCache.put(record)
            messageRelationIndex.add(record)
        }
        return record
    }

    internal inner class MetadataReadScope(
        private val accountUuid: String,
        private val readScope: SecureContentMetadataAeadReadScope,
    ) : AutoCloseable {
        private var closed = false
        private var reads = 0

        fun find(contentId: String): SecureContentMetadataRecord? =
            synchronized(this@AccountProtectedSecureContentMetadataStore) {
                check(!closed) { "Secure metadata read scope is closed" }
                reads++
                findInternal(accountUuid, contentId, readScope)
            }

        override fun close() {
            synchronized(this@AccountProtectedSecureContentMetadataStore) {
                if (closed) return
                SecureColdStartPerfTrace.increment(
                    "store_metadata_scoped_reads",
                    reads.toLong(),
                )
                SecureColdStartPerfTrace.increment(
                    "store_metadata_scoped_aead_resolves",
                    readScope.resolveCount.toLong(),
                )
                SecureColdStartPerfTrace.increment(
                    "store_metadata_scoped_accounts_resolved",
                    readScope.resolvedAccountCount.toLong(),
                )
                closed = true
            }
        }
    }

    @Synchronized
    override fun findByMessageUuid(
        accountUuid: String,
        messageUuid: String,
    ): List<SecureContentMetadataRecord> {
        val persistedCandidates = readRelationCandidates(accountUuid, messageUuid)
        val contentIds =
            when {
                persistedCandidates == null -> emptyList()
                persistedCandidates.isNotEmpty() -> persistedCandidates.toList()
                fullSnapshotLoaded -> messageRelationIndex.contentIds(accountUuid, messageUuid)
                else -> emptyList()
            }
        if (contentIds.isEmpty()) return emptyList()

        val readScope = SecureContentMetadataAeadReadScope(::aeadForRead)
        return try {
            contentIds.mapNotNull { contentId ->
                findInternal(accountUuid, contentId, readScope)
                    ?.takeIf { it.messageUuid == messageUuid }
            }
        } finally {
            SecureColdStartPerfTrace.increment(
                "store_relation_aead_resolves",
                readScope.resolveCount.toLong(),
            )
            SecureColdStartPerfTrace.increment(
                "store_relation_candidates",
                contentIds.size.toLong(),
            )
        }
    }

    @Synchronized
    override fun findByAccount(accountUuid: String): List<SecureContentMetadataRecord> {
        ensureFullSnapshotLoaded()
        return authenticatedCache.findByAccount(accountUuid)
    }

    @Synchronized
    override fun updateState(
        accountUuid: String,
        contentId: String,
        state: SecureContentState,
    ): Boolean {
        val current = find(accountUuid, contentId) ?: return false
        val updated =
            current.copy(
                metadata = current.metadata.copy(state = state),
                updatedAt = System.currentTimeMillis(),
            )
        if (!write(updated)) return false
        authenticatedCache.put(updated)
        return true
    }

    @Synchronized
    override fun updateStorageLocator(
        accountUuid: String,
        contentId: String,
        storageLocator: String?,
    ): Boolean {
        val current = find(accountUuid, contentId) ?: return false
        val updated =
            current.copy(
                storageLocator = storageLocator,
                updatedAt = System.currentTimeMillis(),
            )
        if (!write(updated)) return false
        authenticatedCache.put(updated)
        return true
    }

    @Synchronized
    override fun deleteMetadata(accountUuid: String, contentId: String): Boolean {
        val existing = find(accountUuid, contentId) ?: return false
        val editor =
            preferences.edit()
                .remove(recordKey(accountUuid, contentId))
                .remove(incompleteKey(accountUuid, contentId))
        existing.messageUuid?.let { messageUuid ->
            updateRelationEditor(
                editor,
                accountUuid,
                messageUuid,
                contentId,
                add = false,
            )
        }
        val deleted = editor.commit()
        if (deleted) {
            authenticatedCache.remove(accountUuid, contentId)
            messageRelationIndex.remove(existing)
        }
        return deleted
    }

    /**
     * One-time compatibility maintenance for stores created before the persistent relation
     * accelerator existed. The scan is intentionally never part of normal lookup/cold start.
     *
     * Returned relation candidates remain non-authoritative: every consumer still authenticates
     * the exact metadata record selected by contentId.
     */
    @Synchronized
    internal fun backfillMessageRelationIndexIfNeeded(): Boolean {
        if (preferences.getInt(RELATION_INDEX_VERSION_KEY, 0) >= RELATION_INDEX_VERSION) {
            return false
        }

        val records = readAllAuthenticatedRecordsFromDisk(traceCounters = false)
        val relations = LinkedHashMap<String, LinkedHashSet<String>>()
        records.forEach { record ->
            val messageUuid = record.messageUuid ?: return@forEach
            relations
                .getOrPut(relationKey(record.accountUuid, messageUuid)) { LinkedHashSet() }
                .add(record.contentId)
        }

        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith(RELATION_PREFIX) || it.startsWith(INCOMPLETE_PREFIX) }
            .forEach { key -> editor.remove(key) }
        relations.forEach { (key, ids) ->
            editor.putString(key, encodeContentIds(ids))
        }
        records
            .filter { it.metadata.state.isIncompleteForRecovery() }
            .forEach { record ->
                editor.putString(
                    incompleteKey(record.accountUuid, record.contentId),
                    encodeIncompleteRoute(record.accountUuid, record.contentId),
                )
            }
        editor.putInt(RELATION_INDEX_VERSION_KEY, RELATION_INDEX_VERSION)
        check(editor.commit()) { "Unable to persist Secure Content relation index" }
        return true
    }

    /**
     * Small startup recovery inventory. Only objects whose write lifecycle was non-terminal when
     * the process stopped are listed; committed history is never enumerated here.
     */
    @Synchronized
    internal fun incompleteRecords(
        createdBeforeExclusive: Long? = null,
    ): List<SecureContentMetadataRecord> {
        val readScope = SecureContentMetadataAeadReadScope(::aeadForRead)
        val records = ArrayList<SecureContentMetadataRecord>()
        try {
            preferences.all
                .filterKeys { it.startsWith(INCOMPLETE_PREFIX) }
                .forEach { (key, raw) ->
                    val route = decodeIncompleteRoute(raw as? String) ?: return@forEach
                    if (incompleteKey(route.first, route.second) != key) return@forEach
                    val record = findInternal(route.first, route.second, readScope)
                        ?: return@forEach
                    if (record.metadata.state.isIncompleteForRecovery() &&
                        (createdBeforeExclusive == null ||
                            record.createdAt < createdBeforeExclusive)
                    ) {
                        records.add(record)
                    } else if (!record.metadata.state.isIncompleteForRecovery()) {
                        preferences.edit().remove(key).apply()
                    }
                }
        } finally {
            SecureColdStartPerfTrace.increment(
                "store_incomplete_aead_resolves",
                readScope.resolveCount.toLong(),
            )
        }
        return records
    }

    /**
     * Explicit full enumeration only. Normal startup/recovery and exact content reads must not call
     * this method because its cost grows with the entire Secure Content store.
     */
    @Synchronized
    internal fun allRecords(): List<SecureContentMetadataRecord> {
        ensureFullSnapshotLoaded()
        return authenticatedCache.allRecords()
    }

    private fun readAllAuthenticatedRecordsFromDisk(
        traceCounters: Boolean = true,
    ): List<SecureContentMetadataRecord> {
        val readScope = SecureContentMetadataAeadReadScope(::aeadForRead)
        val records =
            preferences.all.keys
                .filter { it.startsWith(RECORD_PREFIX) }
                .mapNotNull { key -> readByPreferenceKey(key, readScope) }
        if (traceCounters) {
            SecureColdStartPerfTrace.increment(
                "store_metadata_aead_resolves",
                readScope.resolveCount.toLong(),
            )
            SecureColdStartPerfTrace.increment(
                "store_metadata_accounts_resolved",
                readScope.resolvedAccountCount.toLong(),
            )
        }
        return records
    }

    private fun readByPreferenceKey(
        preferenceKey: String,
        readScope: SecureContentMetadataAeadReadScope,
    ): SecureContentMetadataRecord? {
        return try {
            val encoded = preferences.getString(preferenceKey, null) ?: return null
            val ciphertext = Base64.decode(encoded, Base64.NO_WRAP)
            val preliminary = JSONObject(String(ciphertext, StandardCharsets.ISO_8859_1))
            // The opaque index alone is deliberately insufficient: select the AEAD only after
            // reading the account from authenticated plaintext is impossible. Therefore records
            // contain an independently protected routing header in the preference key.
            val accountUuid = preliminary.optString(ROUTING_ACCOUNT, "")
            val contentId = preliminary.optString(ROUTING_CONTENT, "")
            if (accountUuid.isBlank() || contentId.isBlank() ||
                recordKey(accountUuid, contentId) != preferenceKey
            ) {
                return null
            }
            val plaintext = readScope.forAccount(accountUuid)?.decrypt(
                Base64.decode(preliminary.optString(ROUTING_PAYLOAD, ""), Base64.NO_WRAP),
                SecureContentAssociatedData.forMetadata(accountUuid, contentId),
            ) ?: return null
            decodeRecord(JSONObject(String(plaintext, StandardCharsets.UTF_8)))
                .takeIf {
                    it.accountUuid == accountUuid &&
                        it.contentId == contentId
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeNew(record: SecureContentMetadataRecord): Boolean {
        return try {
            val messageUuid = record.messageUuid
            val editor =
                preferences.edit()
                    .putString(
                        recordKey(record.accountUuid, record.contentId),
                        encodeRecordEnvelope(record),
                    )
            updateIncompleteEditor(editor, record)
            if (messageUuid != null &&
                !updateRelationEditor(
                    editor,
                    record.accountUuid,
                    messageUuid,
                    record.contentId,
                    add = true,
                )
            ) {
                return false
            }
            editor.commit()
        } catch (_: Exception) {
            false
        }
    }

    private fun write(record: SecureContentMetadataRecord): Boolean =
        try {
            val editor =
                preferences.edit()
                    .putString(
                        recordKey(record.accountUuid, record.contentId),
                        encodeRecordEnvelope(record),
                    )
            updateIncompleteEditor(editor, record)
            editor.commit()
        } catch (_: Exception) {
            false
        }

    private fun encodeRecordEnvelope(record: SecureContentMetadataRecord): String {
        val accountUuid = record.accountUuid
        val contentId = record.contentId
        val plaintext = encodeRecord(record).toString().toByteArray(StandardCharsets.UTF_8)
        val protected = aeadForWrite(accountUuid).encrypt(
            plaintext,
            SecureContentAssociatedData.forMetadata(accountUuid, contentId),
        )
        // This routing header is deliberately non-authoritative. Its values are re-bound by
        // AEAD associated data and the canonical digest key before a record is accepted.
        val envelope = JSONObject()
            .put(ROUTING_ACCOUNT, accountUuid)
            .put(ROUTING_CONTENT, contentId)
            .put(ROUTING_PAYLOAD, Base64.encodeToString(protected, Base64.NO_WRAP))
        return Base64.encodeToString(
            envelope.toString().toByteArray(StandardCharsets.ISO_8859_1),
            Base64.NO_WRAP,
        )
    }

    private fun updateIncompleteEditor(
        editor: SharedPreferences.Editor,
        record: SecureContentMetadataRecord,
    ) {
        val key = incompleteKey(record.accountUuid, record.contentId)
        if (record.metadata.state.isIncompleteForRecovery()) {
            editor.putString(
                key,
                encodeIncompleteRoute(record.accountUuid, record.contentId),
            )
        } else {
            editor.remove(key)
        }
    }

    /**
     * Persistent relation data is only a routing accelerator. A malformed value is therefore
     * treated as unavailable and never trusted to authorize or disambiguate a Store object.
     */
    private fun readRelationCandidates(
        accountUuid: String,
        messageUuid: String,
    ): LinkedHashSet<String>? {
        val raw = preferences.getString(relationKey(accountUuid, messageUuid), null)
            ?: return LinkedHashSet()
        return try {
            val array = JSONArray(raw)
            val result = LinkedHashSet<String>()
            for (index in 0 until array.length()) {
                val contentId = array.optString(index, "")
                if (contentId.isBlank()) return null
                result.add(contentId)
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    private fun updateRelationEditor(
        editor: SharedPreferences.Editor,
        accountUuid: String,
        messageUuid: String,
        contentId: String,
        add: Boolean,
    ): Boolean {
        val ids = readRelationCandidates(accountUuid, messageUuid) ?: return false
        if (add) {
            ids.add(contentId)
        } else {
            ids.remove(contentId)
        }
        val key = relationKey(accountUuid, messageUuid)
        if (ids.isEmpty()) {
            editor.remove(key)
        } else {
            editor.putString(key, encodeContentIds(ids))
        }
        return true
    }

    private fun encodeContentIds(ids: Iterable<String>): String =
        JSONArray().apply { ids.forEach { contentId -> put(contentId) } }.toString()

    private fun encodeIncompleteRoute(accountUuid: String, contentId: String): String =
        JSONObject()
            .put(ROUTING_ACCOUNT, accountUuid)
            .put(ROUTING_CONTENT, contentId)
            .toString()

    private fun decodeIncompleteRoute(raw: String?): Pair<String, String>? {
        if (raw == null) return null
        return try {
            val json = JSONObject(raw)
            val accountUuid = json.optString(ROUTING_ACCOUNT, "")
            val contentId = json.optString(ROUTING_CONTENT, "")
            if (accountUuid.isBlank() || contentId.isBlank()) null
            else Pair(accountUuid, contentId)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads/recovery resolve existing account material only. Routing values originate outside the
     * authenticated record payload and therefore must never trigger key-material creation.
     */
    private fun aeadForRead(accountUuid: String): Aead? =
        keyMaterialStore.metadataAeadForRead(accountUuid)

    /** Allocation/write is the only metadata path permitted to create account material. */
    private fun aeadForWrite(accountUuid: String): Aead =
        keyMaterialStore.metadataAeadForWrite(accountUuid)

    private fun encodeRecord(record: SecureContentMetadataRecord): JSONObject =
        JSONObject()
            .put("accountUuid", record.accountUuid)
            .put("contentId", record.contentId)
            .put("namespace", record.namespace)
            .put("messageUuid", record.messageUuid ?: JSONObject.NULL)
            .put("mimeType", record.metadata.mimeType ?: JSONObject.NULL)
            .put("fileName", record.metadata.fileName ?: JSONObject.NULL)
            .put("sizeBytes", record.metadata.sizeBytes ?: JSONObject.NULL)
            .put("state", record.metadata.state.persistedValue)
            .put("cryptoVersion", record.metadata.cryptoVersion ?: JSONObject.NULL)
            .put("createdAt", record.createdAt)
            .put("storageLocator", record.storageLocator ?: JSONObject.NULL)
            .put("updatedAt", record.updatedAt)

    private fun decodeRecord(json: JSONObject): SecureContentMetadataRecord {
        val metadata = SecureContentMetadata(
            accountUuid = json.getString("accountUuid"),
            contentId = json.getString("contentId"),
            namespace = json.getString("namespace"),
            messageUuid = json.optStringOrNull("messageUuid"),
            mimeType = json.optStringOrNull("mimeType"),
            fileName = json.optStringOrNull("fileName"),
            sizeBytes = json.optLongOrNull("sizeBytes"),
            state = SecureContentState.fromPersistedValue(json.getString("state")),
            cryptoVersion = json.optIntOrNull("cryptoVersion"),
            createdAt = json.getLong("createdAt"),
        )
        return SecureContentMetadataRecord(
            metadata = metadata,
            storageLocator = json.optStringOrNull("storageLocator"),
            updatedAt = json.getLong("updatedAt"),
        )
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (isNull(name)) null else getLong(name)

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (isNull(name)) null else getInt(name)

    private fun recordKey(accountUuid: String, contentId: String): String =
        RECORD_PREFIX + digest(
            SecureContentAssociatedData.forMetadata(accountUuid, contentId),
        )

    private fun relationKey(accountUuid: String, messageUuid: String): String =
        RELATION_PREFIX + digest(
            ("relation-v1\u0000" + accountUuid + "\u0000" + messageUuid)
                .toByteArray(StandardCharsets.UTF_8),
        )

    private fun incompleteKey(accountUuid: String, contentId: String): String =
        INCOMPLETE_PREFIX + digest(
            SecureContentAssociatedData.forMetadata(accountUuid, contentId),
        )

    private fun SecureContentState.isIncompleteForRecovery(): Boolean =
        this != SecureContentState.COMMITTED &&
            this != SecureContentState.ABORTED &&
            this != SecureContentState.FAILED

    private fun digest(value: ByteArray): String =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(value),
            Base64.NO_WRAP or Base64.URL_SAFE,
        )

    private companion object {
        const val PREFERENCES_NAME = "secure_content_metadata_v1"
        const val RECORD_PREFIX = "record."
        const val RELATION_PREFIX = "relation."
        const val INCOMPLETE_PREFIX = "incomplete."
        const val RELATION_INDEX_VERSION_KEY = "relation_index_version"
        const val RELATION_INDEX_VERSION = 1
        const val ROUTING_ACCOUNT = "account"
        const val ROUTING_CONTENT = "content"
        const val ROUTING_PAYLOAD = "payload"
    }
}
