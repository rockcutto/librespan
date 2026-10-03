// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

internal enum class SecureContentRecoveryOperation { COMMIT, DELETE }

internal enum class SecureContentRecoveryPhase {
    PREPARED, BLOB_COMMITTED, KEY_ACTIVATED, METADATA_COMMITTED,
    KEY_INVALIDATED, BLOB_RETIRED, METADATA_RETIRED,
}

internal data class SecureContentRecoveryRecord(
    val transactionId: String,
    val operation: SecureContentRecoveryOperation,
    val phase: SecureContentRecoveryPhase,
    val context: SecureContentCryptoContext,
    val blobIdentifier: String?,
    val keyMaterialReference: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Store-private, account-protected recovery journal. It exposes no path or key material. */
internal class AccountProtectedSecureContentRecoveryStore(
    context: Context,
    private val keyMaterialStore: PersistentSecureContentKeyMaterialStore,
) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        "secure_content_recovery_v1", Context.MODE_PRIVATE,
    )

    @Synchronized
    fun create(
        operation: SecureContentRecoveryOperation,
        context: SecureContentCryptoContext,
        blobIdentifier: String?,
    ): SecureContentRecoveryRecord {
        val now = System.currentTimeMillis()
        val record = SecureContentRecoveryRecord(
            transactionId = UUID.randomUUID().toString(),
            operation = operation, phase = SecureContentRecoveryPhase.PREPARED, context = context,
            blobIdentifier = blobIdentifier, keyMaterialReference = context.contentId,
            createdAt = now, updatedAt = now,
        )
        check(write(record, allowCreate = true)) { "Unable to persist secure content recovery record" }
        return record
    }

    @Synchronized
    fun advance(
        record: SecureContentRecoveryRecord,
        phase: SecureContentRecoveryPhase,
    ): SecureContentRecoveryRecord {
        val next = record.copy(phase = phase, updatedAt = System.currentTimeMillis())
        check(write(next, allowCreate = false)) { "Unable to persist secure content recovery phase" }
        return next
    }

    @Synchronized
    fun remove(record: SecureContentRecoveryRecord): Boolean =
        preferences.edit().remove(recordKey(record.context.accountUuid, record.transactionId)).commit()

    internal fun allRecords(): List<SecureContentRecoveryRecord> = scan().records

    /**
     * A damaged record must not be indistinguishable from an absent one. A record with a readable
     * account routing header blocks that account; a malformed header blocks all readers.
     */
    internal fun hasUnresolved(context: SecureContentCryptoContext): Boolean {
        val scan = scan()
        return scan.hasUnattributableRecord ||
            context.accountUuid in scan.unreadableAccountUuids ||
            scan.records.any { it.context == context }
    }

    private fun scan(): RecoveryScan {
        val records = mutableListOf<SecureContentRecoveryRecord>()
        val unreadableAccountUuids = mutableSetOf<String>()
        var hasUnattributableRecord = false
        preferences.all.keys
            .filter { it.startsWith("record.") }
            .forEach { key ->
                val record = read(key)
                if (record != null) {
                    records.add(record)
                } else {
                    val accountUuid = routingAccountUuid(key)
                    if (accountUuid == null) {
                        hasUnattributableRecord = true
                    } else {
                        unreadableAccountUuids.add(accountUuid)
                    }
                }
            }
        return RecoveryScan(records, unreadableAccountUuids, hasUnattributableRecord)
    }

    private fun routingAccountUuid(key: String): String? {
        return try {
        val outer = preferences.getString(key, null) ?: return null
        val header = JSONObject(
            String(Base64.decode(outer, Base64.NO_WRAP), StandardCharsets.ISO_8859_1),
        )
        val accountUuid = header.optString("account", "")
        val transactionId = header.optString("transaction", "")
        if (accountUuid.isBlank() || transactionId.isBlank() ||
            recordKey(accountUuid, transactionId) != key
        ) {
            null
        } else {
            accountUuid
        }
    } catch (_: Exception) {
        null
    }
    }

    private data class RecoveryScan(
        val records: List<SecureContentRecoveryRecord>,
        val unreadableAccountUuids: Set<String>,
        val hasUnattributableRecord: Boolean,
    )

    private fun read(key: String): SecureContentRecoveryRecord? {
        return try {
        val outer = preferences.getString(key, null) ?: return null
        val header = JSONObject(String(Base64.decode(outer, Base64.NO_WRAP), StandardCharsets.ISO_8859_1))
        val accountUuid = header.optString("account", "")
        val transactionId = header.optString("transaction", "")
        if (accountUuid.isBlank() || transactionId.isBlank() || recordKey(accountUuid, transactionId) != key) return null
        val aead = keyMaterialStore.metadataAeadForRead(accountUuid) ?: return null
        val payload = aead.decrypt(Base64.decode(header.getString("payload"), Base64.NO_WRAP), SecureContentAssociatedData.forRecovery(accountUuid, transactionId))
        decode(JSONObject(String(payload, StandardCharsets.UTF_8)))
    } catch (_: Exception) { null }
    }

    private fun write(record: SecureContentRecoveryRecord, allowCreate: Boolean): Boolean {
        return try {
        val accountUuid = record.context.accountUuid
        val aead = if (allowCreate) keyMaterialStore.metadataAeadForWrite(accountUuid) else
            keyMaterialStore.metadataAeadForRead(accountUuid) ?: return false
        val protected = aead.encrypt(encode(record).toString().toByteArray(StandardCharsets.UTF_8),
            SecureContentAssociatedData.forRecovery(accountUuid, record.transactionId))
        val outer = JSONObject()
            .put("account", accountUuid)
            .put("transaction", record.transactionId)
            .put("payload", Base64.encodeToString(protected, Base64.NO_WRAP))
        preferences.edit().putString(recordKey(accountUuid, record.transactionId),
            Base64.encodeToString(outer.toString().toByteArray(StandardCharsets.ISO_8859_1), Base64.NO_WRAP)).commit()
    } catch (_: Exception) { false }
    }

    private fun encode(record: SecureContentRecoveryRecord): JSONObject = JSONObject()
        .put("transactionId", record.transactionId).put("operation", record.operation.name)
        .put("phase", record.phase.name).put("accountUuid", record.context.accountUuid)
        .put("contentId", record.context.contentId).put("namespace", record.context.namespace)
        .put("cryptoVersion", record.context.cryptoVersion)
        .put("blobIdentifier", record.blobIdentifier ?: JSONObject.NULL)
        .put("keyMaterialReference", record.keyMaterialReference)
        .put("createdAt", record.createdAt).put("updatedAt", record.updatedAt)

    private fun decode(json: JSONObject): SecureContentRecoveryRecord {
        val context = SecureContentCryptoContext(
            namespace = json.getString("namespace"), cryptoVersion = json.getInt("cryptoVersion"),
            accountUuid = json.getString("accountUuid"), contentId = json.getString("contentId"),
        )
        return SecureContentRecoveryRecord(
            transactionId = json.getString("transactionId"),
            operation = SecureContentRecoveryOperation.valueOf(json.getString("operation")),
            phase = SecureContentRecoveryPhase.valueOf(json.getString("phase")),
            context = context,
            blobIdentifier = if (json.isNull("blobIdentifier")) null else json.getString("blobIdentifier"),
            keyMaterialReference = json.getString("keyMaterialReference"),
            createdAt = json.getLong("createdAt"), updatedAt = json.getLong("updatedAt"),
        )
    }

    private fun recordKey(accountUuid: String, transactionId: String): String =
        "record." + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(
            SecureContentAssociatedData.forRecovery(accountUuid, transactionId)),
            Base64.NO_WRAP or Base64.URL_SAFE,
        )
}
