// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates
import eu.siacs.conversations.security.cryptolock.ActivationJournalAeadV1
import eu.siacs.conversations.security.cryptolock.AppMasterKey
import eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionRuntimeV1
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Persistent v1 KeyMaterialStore.
 *
 * Account key-encryption material is stored as a Tink encrypted keyset wrapped by the
 * non-exportable Android Keystore root. Each content keyset is independently generated and stored
 * as a Tink encrypted keyset under its account material with S5.5 associated data.
 *
 * SharedPreferences contains only opaque protected envelopes and non-secret lifecycle facts. It is
 * never used as Tink's default keyset storage and exposes no keys or records through this API.
 */
class PersistentSecureContentKeyMaterialStore private constructor(
    context: Context,
    private val rootAead: AndroidKeystoreSecureContentRootAead,
) : SecureContentKeyMaterialStore {
    constructor(context: Context) : this(
        context = context,
        rootAead = AndroidKeystoreSecureContentRootAead(),
    )
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val migrationJournalAead = ActivationJournalAeadV1()
    private val migrationRandom = SecureRandom()
    @Volatile
    private var accountMigrationDiagnosticCode: String? = null

    internal fun lastAccountMigrationDiagnosticCode(): String? =
        accountMigrationDiagnosticCode

    /**
     * Retires the account-level envelope for an account-removal transaction.
     *
     * Stable ROOT_V1 and GATED_V1 authorities are supported because retirement only removes the
     * opaque account envelope; it does not unwrap it. Any migration/deactivation/corrupt authority
     * remains fail-closed. The caller must already have retired all account-owned Secure Content
     * objects before this checkpoint.
     */
    @Synchronized
    fun retireAccountKeyMaterialForRemoval(accountUuid: String): Boolean =
        retireAccountKeyMaterial(accountUuid)

    /** Compatibility entry point for already-deleted/onboarding accounts. */
    @Synchronized
    fun retireDeletedAccountKeyMaterial(accountUuid: String): Boolean =
        retireAccountKeyMaterial(accountUuid)

    private fun retireAccountKeyMaterial(accountUuid: String): Boolean {
        if (accountUuid.isBlank()) {
            return false
        }
        val authority = hierarchyAuthority()
        if (authority != AccountHierarchyAuthority.ROOT_V1 &&
            authority != AccountHierarchyAuthority.GATED_V1
        ) {
            return false
        }
        if (hasHierarchyTransitionState()) {
            return false
        }
        val recordKey = accountRecordKey(accountUuid)
        val hasState = preferences.contains(stateKey(recordKey))
        val hasPayload = preferences.contains(payloadKey(recordKey))
        if (hasState != hasPayload) {
            return false
        }
        if (!hasState) {
            return true
        }
        if (preferences.getString(stateKey(recordKey), null) != STATE_ACTIVE) {
            return false
        }
        return preferences.edit()
            .remove(stateKey(recordKey))
            .remove(payloadKey(recordKey))
            .commit()
    }

    init {
        AeadConfig.register()
        StreamingAeadConfig.register()
    }

    @Synchronized
    override fun beginWrite(
        context: SecureContentCryptoContext,
    ): SecureContentKeyMaterialResult<SecureContentKeyMaterialWriteSession> {
        if (hierarchyTransitionBlocksWrites()) {
            return SecureContentKeyMaterialResult.Failure(
                SecureContentKeyMaterialFailure.RECOVERY_IN_DOUBT,
            )
        }
        if (context.cryptoVersion != CRYPTO_VERSION) {
            return SecureContentKeyMaterialResult.Failure(
                SecureContentKeyMaterialFailure.UNSUPPORTED_VERSION,
            )
        }
        val recordKey = contentRecordKey(context)
        if (preferences.contains(stateKey(recordKey))) {
            return SecureContentKeyMaterialResult.Failure(
                SecureContentKeyMaterialFailure.MATERIAL_UNAVAILABLE,
            )
        }

        return try {
            val accountAead = accountAead(context.accountUuid, createIfMissing = true)
                ?: return SecureContentKeyMaterialResult.Failure(
                    SecureContentKeyMaterialFailure.MATERIAL_UNAVAILABLE,
                )
            val contentKeyset = KeysetHandle.generateNew(
                StreamingAeadKeyTemplates.AES128_GCM_HKDF_1MB,
            )
            val protectedEnvelope = TinkProtoKeysetFormat.serializeEncryptedKeyset(
                contentKeyset,
                accountAead,
                SecureContentAssociatedData.forContext(context),
            )
            if (!writeRecord(recordKey, STATE_CANDIDATE, protectedEnvelope)) {
                return SecureContentKeyMaterialResult.Failure(
                    SecureContentKeyMaterialFailure.PREPARATION_FAILED,
                )
            }
            val handle = RuntimeHandle(context, contentKeyset)
            SecureContentKeyMaterialResult.Success(
                WriteSession(
                    context = context,
                    recordKey = recordKey,
                    writerHandle = handle,
                ),
            )
        } catch (_: Exception) {
            SecureContentKeyMaterialResult.Failure(
                SecureContentKeyMaterialFailure.PREPARATION_FAILED,
            )
        }
    }

    @Synchronized
    override fun resolveForRead(
        context: SecureContentCryptoContext,
    ): SecureContentKeyMaterialResult<SecureContentKeyMaterialHandle> =
        resolveForReadInternal(context, accountAeadOverride = null)

    /**
     * Creates one bounded read scope. The account AEAD is unwrapped at most once and the primitive
     * remains reachable only through this scope until [ContentReadScope.close].
     */
    @Synchronized
    internal fun beginReadScope(accountUuid: String): ContentReadScope {
        val operationEpoch =
            try {
                cryptoEpochForOperation()
            } catch (_: Exception) {
                null
            }
        val accountAead =
            try {
                accountAead(accountUuid, createIfMissing = false)
            } catch (_: Exception) {
                null
            }
        SecureColdStartPerfTrace.increment("content_key_account_aead_resolves", 1)
        return ContentReadScope(accountUuid, accountAead, operationEpoch)
    }

    internal fun cryptoEpochForOperation(): Long? =
        when (hierarchyAuthority()) {
            AccountHierarchyAuthority.ROOT_V1 -> null
            AccountHierarchyAuthority.GATED_V1 ->
                SecureContentCryptoSessionRuntimeV1.activeEpoch()
                    ?: throw GeneralSecurityException("secure content crypto session is locked")
            AccountHierarchyAuthority.DEACTIVATING_ROOT_V1 ->
                throw GeneralSecurityException("account hierarchy deactivation is in progress")
            AccountHierarchyAuthority.CORRUPT ->
                throw GeneralSecurityException("account hierarchy authority is invalid")
        }

    internal fun isCryptoEpochValid(epoch: Long?): Boolean =
        when (hierarchyAuthority()) {
            AccountHierarchyAuthority.ROOT_V1 -> epoch == null
            AccountHierarchyAuthority.GATED_V1 ->
                epoch != null && SecureContentCryptoSessionRuntimeV1.isEpochActive(epoch)
            AccountHierarchyAuthority.DEACTIVATING_ROOT_V1 -> false
            AccountHierarchyAuthority.CORRUPT -> false
        }

    @Synchronized
    private fun resolveForReadInternal(
        context: SecureContentCryptoContext,
        accountAeadOverride: Aead?,
    ): SecureContentKeyMaterialResult<SecureContentKeyMaterialHandle> {
        if (context.cryptoVersion != CRYPTO_VERSION) {
            return SecureContentKeyMaterialResult.Failure(
                SecureContentKeyMaterialFailure.UNSUPPORTED_VERSION,
            )
        }

        val recordKey = contentRecordKey(context)
        return when (preferences.getString(stateKey(recordKey), null)) {
            STATE_INVALIDATED -> SecureContentKeyMaterialResult.Failure(
                SecureContentKeyMaterialFailure.MATERIAL_INVALIDATED,
            )
            STATE_CANDIDATE,
            STATE_READY_TO_COMMIT,
            STATE_FAILED,
            STATE_ABORTED -> SecureContentKeyMaterialResult.Failure(
                SecureContentKeyMaterialFailure.RECOVERY_IN_DOUBT,
            )
            STATE_ACTIVE -> loadContentHandle(context, recordKey, accountAeadOverride)
            else -> SecureContentKeyMaterialResult.Failure(
                SecureContentKeyMaterialFailure.MATERIAL_UNAVAILABLE,
            )
        }
    }

    @Synchronized
    override fun invalidate(
        context: SecureContentCryptoContext,
    ): SecureContentKeyMaterialResult<Unit> {
        val recordKey = contentRecordKey(context)
        val editor = preferences.edit()
            .putString(stateKey(recordKey), STATE_INVALIDATED)
            .remove(payloadKey(recordKey))
        return if (editor.commit()) {
            SecureContentKeyMaterialResult.Success(Unit)
        } else {
            SecureContentKeyMaterialResult.Failure(
                SecureContentKeyMaterialFailure.PREPARATION_FAILED,
            )
        }
    }

    /**
     * Account Secret Vault authenticator for write.
     *
     * This deliberately reuses the existing account key hierarchy: ROOT_V1 in normal mode and the
     * AMK-gated account envelope while High Security is active. No Vault-specific master key is
     * introduced.
     */
    @Synchronized
    internal fun accountSecretAeadForWrite(accountUuid: String): Aead {
        if (hierarchyTransitionBlocksWrites()) {
            throw GeneralSecurityException("account material migration is in progress")
        }
        return accountAead(accountUuid, createIfMissing = true)
            ?: throw GeneralSecurityException("account material unavailable")
    }

    /** Account Secret Vault authenticator for read; never creates missing account material. */
    @Synchronized
    internal fun accountSecretAeadForRead(accountUuid: String): Aead? =
        accountAead(accountUuid, createIfMissing = false)

    /**
     * Store-private metadata authenticator for allocation/write only.
     *
     * Creation is deliberately explicit: metadata reads and recovery must not manufacture account
     * material from an untrusted routing value.
     */
    @Synchronized
    internal fun metadataAeadForWrite(accountUuid: String): Aead {
        if (hierarchyTransitionBlocksWrites()) {
            throw GeneralSecurityException("account material migration is in progress")
        }
        return accountAead(accountUuid, createIfMissing = true)
            ?: throw GeneralSecurityException("account material unavailable")
    }

    /**
     * Store-private metadata authenticator for read/recovery only.
     *
     * A missing account envelope is unavailable. It must not create a new account key record.
     */
    @Synchronized
    internal fun metadataAeadForRead(accountUuid: String): Aead? =
        accountAead(accountUuid, createIfMissing = false)

    /** Store-private commit activation. Only the matching write transaction may invoke this. */
    @Synchronized
    internal fun activate(
        candidate: SecureContentKeyMaterialCommitCandidate,
    ): SecureContentKeyMaterialResult<Unit> {
        val runtimeCandidate = candidate as? CommitCandidate
            ?: return SecureContentKeyMaterialResult.Failure(
                SecureContentKeyMaterialFailure.CONTEXT_MISMATCH,
            )
        val state = preferences.getString(stateKey(runtimeCandidate.recordKey), null)
        if (state != STATE_READY_TO_COMMIT) {
            return SecureContentKeyMaterialResult.Failure(
                SecureContentKeyMaterialFailure.RECOVERY_IN_DOUBT,
            )
        }
        return if (preferences.edit()
                .putString(stateKey(runtimeCandidate.recordKey), STATE_ACTIVE)
                .commit()
        ) {
            SecureContentKeyMaterialResult.Success(Unit)
        } else {
            SecureContentKeyMaterialResult.Failure(
                SecureContentKeyMaterialFailure.PREPARATION_FAILED,
            )
        }
    }

    /** Store-private recovery query; it reveals no protected material. */
    internal fun stateFor(context: SecureContentCryptoContext): SecureContentKeyMaterialState? =
        preferences.getString(stateKey(contentRecordKey(context)), null)?.let(::toState)

    private fun loadContentHandle(
        context: SecureContentCryptoContext,
        recordKey: String,
        accountAeadOverride: Aead? = null,
    ): SecureContentKeyMaterialResult<SecureContentKeyMaterialHandle> {
        return try {
            val protectedEnvelope = readPayload(recordKey)
                ?: return SecureContentKeyMaterialResult.Failure(
                    SecureContentKeyMaterialFailure.MATERIAL_UNAVAILABLE,
                )
            val accountAead =
                accountAeadOverride
                    ?: accountAead(context.accountUuid, createIfMissing = false)
                    ?: return SecureContentKeyMaterialResult.Failure(
                        SecureContentKeyMaterialFailure.MATERIAL_UNAVAILABLE,
                    )
            val keyset = TinkProtoKeysetFormat.parseEncryptedKeyset(
                protectedEnvelope,
                accountAead,
                SecureContentAssociatedData.forContext(context),
            )
            SecureContentKeyMaterialResult.Success(RuntimeHandle(context, keyset))
        } catch (_: Exception) {
            SecureContentKeyMaterialResult.Failure(
                SecureContentKeyMaterialFailure.MATERIAL_UNAVAILABLE,
            )
        }
    }

    private fun accountAead(
        accountUuid: String,
        createIfMissing: Boolean,
    ): Aead? {
        val recordKey = accountRecordKey(accountUuid)
        val encrypted = readPayload(recordKey)
        val keyset =
            when (hierarchyAuthority()) {
                AccountHierarchyAuthority.ROOT_V1 ->
                    when {
                        encrypted != null ->
                            TinkProtoKeysetFormat.parseEncryptedKeyset(
                                encrypted,
                                rootAead,
                                SecureContentAssociatedData.forAccountMaterial(accountUuid),
                            )
                        !createIfMissing -> return null
                        else -> {
                            val generated = KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM)
                            val protectedEnvelope =
                                TinkProtoKeysetFormat.serializeEncryptedKeyset(
                                    generated,
                                    rootAead,
                                    SecureContentAssociatedData.forAccountMaterial(accountUuid),
                                )
                            if (!writeRecord(recordKey, STATE_ACTIVE, protectedEnvelope)) {
                                throw GeneralSecurityException("account material persistence failed")
                            }
                            generated
                        }
                    }
                AccountHierarchyAuthority.GATED_V1 ->
                    SecureContentCryptoSessionRuntimeV1.withActiveMasterKey { masterKey ->
                        AppMasterKeyAccountAeadV1.fromMasterKey(
                            masterKey,
                            accountUuid,
                        ).use { gatedAead ->
                            when {
                                encrypted != null ->
                                    TinkProtoKeysetFormat.parseEncryptedKeyset(
                                        encrypted,
                                        gatedAead,
                                        SecureContentAssociatedData.forGatedAccountMaterial(accountUuid),
                                    )
                                !createIfMissing -> null
                                else -> {
                                    val generated = KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM)
                                    val protectedEnvelope =
                                        TinkProtoKeysetFormat.serializeEncryptedKeyset(
                                            generated,
                                            gatedAead,
                                            SecureContentAssociatedData.forGatedAccountMaterial(accountUuid),
                                        )
                                    if (!writeRecord(recordKey, STATE_ACTIVE, protectedEnvelope)) {
                                        throw GeneralSecurityException(
                                            "gated account material persistence failed",
                                        )
                                    }
                                    generated
                                }
                            }
                        }
                    } ?: return null
                AccountHierarchyAuthority.DEACTIVATING_ROOT_V1 ->
                    throw GeneralSecurityException("account hierarchy deactivation is in progress")
                AccountHierarchyAuthority.CORRUPT ->
                    throw GeneralSecurityException("account hierarchy authority is invalid")
            }
        return keyset?.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    @Synchronized
    internal fun prepareAccountKeyMigration(
        transactionId: String,
        accountUuids: Collection<String>,
        appMasterKey: AppMasterKey,
    ): ByteArray? {
        accountMigrationDiagnosticCode = null
        if (transactionId.isBlank()) {
            accountMigrationDiagnosticCode = "PREPARE_TRANSACTION_ID_INVALID"
            return null
        }
        if (hierarchyAuthority() != AccountHierarchyAuthority.ROOT_V1) {
            accountMigrationDiagnosticCode = "PREPARE_AUTHORITY_NOT_ROOT"
            return null
        }
        if (!reconcileOrphanedAccountKeyRecords(accountUuids)) {
            accountMigrationDiagnosticCode = "PREPARE_ORPHAN_RECONCILE_FAILED"
            return null
        }
        val existingState = preferences.getString(HIERARCHY_MIGRATION_STATE, null)
        if (existingState != null) {
            return if (
                existingState == MIGRATION_PREPARED &&
                preferences.getString(HIERARCHY_MIGRATION_TRANSACTION, null) == transactionId
            ) {
                findPreparedAccountKeyMigration(transactionId, accountUuids).also {
                    accountMigrationDiagnosticCode =
                        if (it == null) "PREPARE_EXISTING_VERIFY_FAILED" else null
                }
            } else {
                accountMigrationDiagnosticCode = "PREPARE_EXISTING_STATE_CONFLICT"
                null
            }
        }

        val inventory =
            accountInventory(accountUuids)
                ?: run {
                    accountMigrationDiagnosticCode = "PREPARE_INVENTORY_MISMATCH"
                    return null
                }
        val candidates = linkedMapOf<String, ByteArray>()
        val entries = mutableListOf<SecureContentAccountMigrationManifestEntryV1>()
        var stage = "PREPARE_START"
        try {
            for ((recordKey, accountUuid) in inventory) {
                stage = "PREPARE_ROOT_ENVELOPE_READ"
                val rootEnvelope =
                    readPayload(recordKey)
                        ?: run {
                            accountMigrationDiagnosticCode = "PREPARE_ROOT_ENVELOPE_MISSING"
                            return null
                        }
                stage = "PREPARE_ROOT_DECRYPT"
                val sourceKeyset =
                    TinkProtoKeysetFormat.parseEncryptedKeyset(
                        rootEnvelope,
                        rootAead,
                        SecureContentAssociatedData.forAccountMaterial(accountUuid),
                    )
                stage = "PREPARE_GATED_REWRAP"
                AppMasterKeyAccountAeadV1.fromMasterKey(
                    appMasterKey,
                    accountUuid,
                    migrationRandom,
                ).use { gatedAead ->
                    val candidate =
                        TinkProtoKeysetFormat.serializeEncryptedKeyset(
                            sourceKeyset,
                            gatedAead,
                            SecureContentAssociatedData.forGatedAccountMaterial(accountUuid),
                        )
                    stage = "PREPARE_GATED_READBACK"
                    val candidateKeyset =
                        TinkProtoKeysetFormat.parseEncryptedKeyset(
                            candidate,
                            gatedAead,
                            SecureContentAssociatedData.forGatedAccountMaterial(accountUuid),
                        )
                    stage = "PREPARE_PROBE_VERIFY"
                    if (!verifySameAccountKeyset(
                            transactionId,
                            accountUuid,
                            sourceKeyset,
                            candidateKeyset,
                        )
                    ) {
                        candidate.fill(0)
                        accountMigrationDiagnosticCode = "PREPARE_PROBE_VERIFY_FAILED"
                        return null
                    }
                    candidates[recordKey] = candidate
                    entries +=
                        SecureContentAccountMigrationManifestEntryV1(
                            recordKey,
                            MessageDigest.getInstance("SHA-256").digest(candidate),
                        )
                }
            }

            stage = "PREPARE_MANIFEST_ENCODE"
            val manifest = SecureContentAccountMigrationManifestV1(transactionId, entries)
            val manifestBytes = SecureContentAccountMigrationManifestCodecV1.encode(manifest)
            stage = "PREPARE_MANIFEST_ENCRYPT"
            val protectedManifest =
                migrationJournalAead.encrypt(
                    manifestBytes,
                    SecureContentAssociatedData.forAccountMigrationManifest(transactionId),
                )
            val evidence = manifest.evidence()
            try {
                stage = "PREPARE_MANIFEST_WRITE"
                val editor =
                    preferences.edit()
                        .putString(HIERARCHY_AUTHORITY_KEY, AUTHORITY_ROOT_V1)
                        .putString(HIERARCHY_MIGRATION_TRANSACTION, transactionId)
                        .putString(HIERARCHY_MIGRATION_STATE, MIGRATION_PREPARED)
                        .putString(
                            HIERARCHY_MIGRATION_MANIFEST,
                            Base64.encodeToString(protectedManifest, Base64.NO_WRAP),
                        )
                candidates.forEach { (recordKey, candidate) ->
                    editor.putString(
                        migrationCandidateKey(transactionId, recordKey),
                        Base64.encodeToString(candidate, Base64.NO_WRAP),
                    )
                }
                if (!editor.commit()) {
                    evidence.fill(0)
                    accountMigrationDiagnosticCode = "PREPARE_MANIFEST_WRITE_FAILED"
                    return null
                }
                stage = "PREPARE_DURABLE_VERIFY"
                val verified =
                    findPreparedAccountKeyMigration(transactionId, accountUuids)
                        ?: run {
                            evidence.fill(0)
                            accountMigrationDiagnosticCode = "PREPARE_DURABLE_VERIFY_FAILED"
                            return null
                        }
                return if (MessageDigest.isEqual(evidence, verified)) {
                    accountMigrationDiagnosticCode = null
                    verified.also { evidence.fill(0) }
                } else {
                    verified.fill(0)
                    evidence.fill(0)
                    accountMigrationDiagnosticCode = "PREPARE_EVIDENCE_MISMATCH"
                    null
                }
            } finally {
                manifestBytes.fill(0)
                protectedManifest.fill(0)
            }
        } catch (error: Exception) {
            accountMigrationDiagnosticCode =
                stage + "_" + error.javaClass.simpleName
            return null
        } finally {
            candidates.values.forEach { it.fill(0) }
        }
    }

    @Synchronized
    internal fun findPreparedAccountKeyMigration(
        transactionId: String,
        accountUuids: Collection<String>,
    ): ByteArray? {
        if (
            hierarchyAuthority() != AccountHierarchyAuthority.ROOT_V1 ||
            preferences.getString(HIERARCHY_MIGRATION_TRANSACTION, null) != transactionId ||
            preferences.getString(HIERARCHY_MIGRATION_STATE, null) != MIGRATION_PREPARED
        ) {
            return null
        }
        val manifest = readMigrationManifest(transactionId) ?: return null
        if (!validatePreparedManifest(manifest, accountUuids)) return null
        return manifest.evidence()
    }

    @Synchronized
    internal fun accountKeyMigrationState(
        transactionId: String,
        evidence: ByteArray,
        accountUuids: Collection<String>,
    ): SecureContentAccountMigrationStateV1 {
        if (transactionId.isBlank() || evidence.size != 32) {
            return SecureContentAccountMigrationStateV1.CORRUPT
        }
        val storedTransaction =
            preferences.getString(HIERARCHY_MIGRATION_TRANSACTION, null)
                ?: return SecureContentAccountMigrationStateV1.ABSENT
        if (storedTransaction != transactionId) {
            return SecureContentAccountMigrationStateV1.CORRUPT
        }
        val manifest =
            readMigrationManifest(transactionId)
                ?: return SecureContentAccountMigrationStateV1.CORRUPT
        val expected = manifest.evidence()
        val matches = MessageDigest.isEqual(expected, evidence)
        expected.fill(0)
        if (!matches) return SecureContentAccountMigrationStateV1.CORRUPT

        return when (preferences.getString(HIERARCHY_MIGRATION_STATE, null)) {
            MIGRATION_PREPARED ->
                if (
                    hierarchyAuthority() == AccountHierarchyAuthority.ROOT_V1 &&
                    validatePreparedManifest(manifest, accountUuids)
                ) SecureContentAccountMigrationStateV1.PREPARED
                else SecureContentAccountMigrationStateV1.CORRUPT
            MIGRATION_COMMITTED ->
                if (
                    hierarchyAuthority() == AccountHierarchyAuthority.GATED_V1 &&
                    validateCommittedManifest(manifest, accountUuids)
                ) SecureContentAccountMigrationStateV1.COMMITTED
                else SecureContentAccountMigrationStateV1.CORRUPT
            else -> SecureContentAccountMigrationStateV1.CORRUPT
        }
    }

    @Synchronized
    internal fun commitAccountKeyMigration(
        transactionId: String,
        evidence: ByteArray,
        accountUuids: Collection<String>,
    ): Boolean {
        accountMigrationDiagnosticCode = null
        val preState = accountKeyMigrationState(transactionId, evidence, accountUuids)
        if (preState != SecureContentAccountMigrationStateV1.PREPARED) {
            accountMigrationDiagnosticCode = "COMMIT_PRESTATE_" + preState.name
            return false
        }

        val manifest =
            readMigrationManifest(transactionId)
                ?: run {
                    accountMigrationDiagnosticCode = "COMMIT_MANIFEST_READ_FAILED"
                    return false
                }
        val editor =
            preferences.edit()
                .putString(HIERARCHY_AUTHORITY_KEY, AUTHORITY_GATED_V1)
                .putString(HIERARCHY_ACTIVE_TRANSACTION, transactionId)
                .putString(HIERARCHY_MIGRATION_STATE, MIGRATION_COMMITTED)
        for (entry in manifest.entries) {
            val candidateKey = migrationCandidateKey(transactionId, entry.accountRecordKey)
            val encodedCandidate =
                preferences.getString(candidateKey, null)
                    ?: run {
                        accountMigrationDiagnosticCode = "COMMIT_CANDIDATE_MISSING"
                        return false
                    }
            editor.putString(payloadKey(entry.accountRecordKey), encodedCandidate)
            editor.remove(candidateKey)
        }
        if (!editor.commit()) {
            accountMigrationDiagnosticCode = "COMMIT_WRITE_FAILED"
            return false
        }

        val postState = accountKeyMigrationState(transactionId, evidence, accountUuids)
        return if (postState == SecureContentAccountMigrationStateV1.COMMITTED) {
            accountMigrationDiagnosticCode = null
            true
        } else {
            accountMigrationDiagnosticCode = "COMMIT_POSTSTATE_" + postState.name
            false
        }
    }

    @Synchronized
    internal fun rollbackAccountKeyMigration(
        transactionId: String,
        evidence: ByteArray?,
        accountUuids: Collection<String>,
    ): Boolean {
        if (hierarchyAuthority() == AccountHierarchyAuthority.GATED_V1) return false
        val storedTransaction =
            preferences.getString(HIERARCHY_MIGRATION_TRANSACTION, null)
                ?: return true
        if (storedTransaction != transactionId) return false
        if (evidence != null &&
            accountKeyMigrationState(transactionId, evidence, accountUuids) !=
                SecureContentAccountMigrationStateV1.PREPARED
        ) return false

        val manifest = readMigrationManifest(transactionId) ?: return false
        val editor = preferences.edit()
        manifest.entries.forEach { entry ->
            editor.remove(migrationCandidateKey(transactionId, entry.accountRecordKey))
        }
        editor
            .remove(HIERARCHY_MIGRATION_TRANSACTION)
            .remove(HIERARCHY_MIGRATION_STATE)
            .remove(HIERARCHY_MIGRATION_MANIFEST)
            .remove(HIERARCHY_AUTHORITY_KEY)
        return editor.commit()
    }


    @Synchronized
    internal fun prepareAccountKeyDeactivation(
        transactionId: String,
        accountUuids: Collection<String>,
        appMasterKey: AppMasterKey,
    ): ByteArray? {
        if (transactionId.isBlank() || hierarchyAuthority() != AccountHierarchyAuthority.GATED_V1) {
            return null
        }
        if (preferences.getString(HIERARCHY_MIGRATION_STATE, null) != MIGRATION_COMMITTED) {
            return null
        }
        val existingState = preferences.getString(HIERARCHY_DEACTIVATION_STATE, null)
        if (existingState != null) {
            return if (
                existingState == MIGRATION_PREPARED &&
                preferences.getString(HIERARCHY_DEACTIVATION_TRANSACTION, null) == transactionId
            ) {
                findPreparedAccountKeyDeactivation(transactionId, accountUuids)
            } else {
                null
            }
        }

        val inventory = accountInventory(accountUuids) ?: return null
        val candidates = linkedMapOf<String, ByteArray>()
        val entries = mutableListOf<SecureContentAccountMigrationManifestEntryV1>()
        try {
            for ((recordKey, accountUuid) in inventory) {
                val gatedEnvelope = readPayload(recordKey) ?: return null
                val sourceKeyset =
                    AppMasterKeyAccountAeadV1.fromMasterKey(
                        appMasterKey,
                        accountUuid,
                        migrationRandom,
                    ).use { gatedAead ->
                        TinkProtoKeysetFormat.parseEncryptedKeyset(
                            gatedEnvelope,
                            gatedAead,
                            SecureContentAssociatedData.forGatedAccountMaterial(accountUuid),
                        )
                    }
                val candidate =
                    TinkProtoKeysetFormat.serializeEncryptedKeyset(
                        sourceKeyset,
                        rootAead,
                        SecureContentAssociatedData.forAccountMaterial(accountUuid),
                    )
                val candidateKeyset =
                    TinkProtoKeysetFormat.parseEncryptedKeyset(
                        candidate,
                        rootAead,
                        SecureContentAssociatedData.forAccountMaterial(accountUuid),
                    )
                if (!verifySameAccountKeyset(
                        transactionId,
                        accountUuid,
                        sourceKeyset,
                        candidateKeyset,
                        SecureContentAssociatedData.forAccountDeactivationProbe(
                            transactionId,
                            accountUuid,
                        ),
                    )
                ) {
                    candidate.fill(0)
                    return null
                }
                candidates[recordKey] = candidate
                entries +=
                    SecureContentAccountMigrationManifestEntryV1(
                        recordKey,
                        MessageDigest.getInstance("SHA-256").digest(candidate),
                    )
            }

            val manifest = SecureContentAccountMigrationManifestV1(transactionId, entries)
            val manifestBytes = SecureContentAccountMigrationManifestCodecV1.encode(manifest)
            val protectedManifest =
                migrationJournalAead.encrypt(
                    manifestBytes,
                    SecureContentAssociatedData.forAccountDeactivationManifest(transactionId),
                )
            val evidence = manifest.evidence()
            try {
                val editor =
                    preferences.edit()
                        .putString(HIERARCHY_DEACTIVATION_TRANSACTION, transactionId)
                        .putString(HIERARCHY_DEACTIVATION_STATE, MIGRATION_PREPARED)
                        .putString(
                            HIERARCHY_DEACTIVATION_MANIFEST,
                            Base64.encodeToString(protectedManifest, Base64.NO_WRAP),
                        )
                candidates.forEach { (recordKey, candidate) ->
                    editor.putString(
                        deactivationCandidateKey(transactionId, recordKey),
                        Base64.encodeToString(candidate, Base64.NO_WRAP),
                    )
                }
                if (!editor.commit()) {
                    evidence.fill(0)
                    return null
                }
                val verified =
                    findPreparedAccountKeyDeactivation(transactionId, accountUuids)
                        ?: run {
                            evidence.fill(0)
                            return null
                        }
                return if (MessageDigest.isEqual(evidence, verified)) {
                    verified.also { evidence.fill(0) }
                } else {
                    verified.fill(0)
                    evidence.fill(0)
                    null
                }
            } finally {
                manifestBytes.fill(0)
                protectedManifest.fill(0)
            }
        } catch (_: Exception) {
            return null
        } finally {
            candidates.values.forEach { it.fill(0) }
        }
    }

    @Synchronized
    internal fun findPreparedAccountKeyDeactivation(
        transactionId: String,
        accountUuids: Collection<String>,
    ): ByteArray? {
        if (
            hierarchyAuthority() != AccountHierarchyAuthority.GATED_V1 ||
            preferences.getString(HIERARCHY_DEACTIVATION_TRANSACTION, null) != transactionId ||
            preferences.getString(HIERARCHY_DEACTIVATION_STATE, null) != MIGRATION_PREPARED
        ) {
            return null
        }
        val manifest = readDeactivationManifest(transactionId) ?: return null
        if (!validatePreparedDeactivationManifest(manifest, accountUuids)) return null
        return manifest.evidence()
    }

    @Synchronized
    internal fun accountKeyDeactivationState(
        transactionId: String,
        evidence: ByteArray,
        accountUuids: Collection<String>,
    ): SecureContentAccountMigrationStateV1 {
        if (transactionId.isBlank() || evidence.size != 32) {
            return SecureContentAccountMigrationStateV1.CORRUPT
        }
        if (preferences.getString(HIERARCHY_DEACTIVATION_TRANSACTION, null) != transactionId) {
            return SecureContentAccountMigrationStateV1.ABSENT
        }
        val manifest =
            readDeactivationManifest(transactionId)
                ?: return SecureContentAccountMigrationStateV1.CORRUPT
        val expected = manifest.evidence()
        val matches = MessageDigest.isEqual(expected, evidence)
        expected.fill(0)
        if (!matches) return SecureContentAccountMigrationStateV1.CORRUPT

        return when (preferences.getString(HIERARCHY_DEACTIVATION_STATE, null)) {
            MIGRATION_PREPARED ->
                if (
                    hierarchyAuthority() == AccountHierarchyAuthority.GATED_V1 &&
                    validatePreparedDeactivationManifest(manifest, accountUuids)
                ) SecureContentAccountMigrationStateV1.PREPARED
                else SecureContentAccountMigrationStateV1.CORRUPT
            MIGRATION_COMMITTED ->
                if (
                    hierarchyAuthority() == AccountHierarchyAuthority.DEACTIVATING_ROOT_V1 &&
                    validateCommittedManifest(manifest, accountUuids)
                ) SecureContentAccountMigrationStateV1.COMMITTED
                else SecureContentAccountMigrationStateV1.CORRUPT
            else -> SecureContentAccountMigrationStateV1.CORRUPT
        }
    }

    @Synchronized
    internal fun commitAccountKeyDeactivation(
        transactionId: String,
        evidence: ByteArray,
        accountUuids: Collection<String>,
    ): Boolean {
        if (
            accountKeyDeactivationState(transactionId, evidence, accountUuids) !=
                SecureContentAccountMigrationStateV1.PREPARED
        ) return false

        val manifest = readDeactivationManifest(transactionId) ?: return false
        val editor =
            preferences.edit()
                .putString(HIERARCHY_AUTHORITY_KEY, AUTHORITY_DEACTIVATING_ROOT_V1)
                .remove(HIERARCHY_ACTIVE_TRANSACTION)
                .remove(HIERARCHY_MIGRATION_TRANSACTION)
                .remove(HIERARCHY_MIGRATION_STATE)
                .remove(HIERARCHY_MIGRATION_MANIFEST)
                .putString(HIERARCHY_DEACTIVATION_STATE, MIGRATION_COMMITTED)
        for (entry in manifest.entries) {
            val candidateKey = deactivationCandidateKey(transactionId, entry.accountRecordKey)
            val encodedCandidate = preferences.getString(candidateKey, null) ?: return false
            editor.putString(payloadKey(entry.accountRecordKey), encodedCandidate)
            editor.remove(candidateKey)
        }
        if (!editor.commit()) return false

        return accountKeyDeactivationState(transactionId, evidence, accountUuids) ==
            SecureContentAccountMigrationStateV1.COMMITTED
    }

    @Synchronized
    internal fun rollbackAccountKeyDeactivation(
        transactionId: String,
        evidence: ByteArray?,
        accountUuids: Collection<String>,
    ): Boolean {
        if (hierarchyAuthority() != AccountHierarchyAuthority.GATED_V1) return false
        val storedTransaction =
            preferences.getString(HIERARCHY_DEACTIVATION_TRANSACTION, null)
                ?: return true
        if (storedTransaction != transactionId) return false
        if (evidence != null &&
            accountKeyDeactivationState(transactionId, evidence, accountUuids) !=
                SecureContentAccountMigrationStateV1.PREPARED
        ) return false

        val manifest = readDeactivationManifest(transactionId) ?: return false
        val editor = preferences.edit()
        manifest.entries.forEach { entry ->
            editor.remove(deactivationCandidateKey(transactionId, entry.accountRecordKey))
        }
        editor
            .remove(HIERARCHY_DEACTIVATION_TRANSACTION)
            .remove(HIERARCHY_DEACTIVATION_STATE)
            .remove(HIERARCHY_DEACTIVATION_MANIFEST)
        return editor.commit()
    }

    @Synchronized
    internal fun finalizeAccountKeyDeactivation(transactionId: String): Boolean {
        if (
            hierarchyAuthority() != AccountHierarchyAuthority.DEACTIVATING_ROOT_V1 ||
            preferences.getString(HIERARCHY_DEACTIVATION_TRANSACTION, null) != transactionId ||
            preferences.getString(HIERARCHY_DEACTIVATION_STATE, null) != MIGRATION_COMMITTED
        ) {
            return false
        }
        return preferences.edit()
            .putString(HIERARCHY_AUTHORITY_KEY, AUTHORITY_ROOT_V1)
            .remove(HIERARCHY_DEACTIVATION_TRANSACTION)
            .remove(HIERARCHY_DEACTIVATION_STATE)
            .remove(HIERARCHY_DEACTIVATION_MANIFEST)
            .commit()
    }

    @Synchronized
    internal fun isAccountKeyDeactivationFinalized(
        accountUuids: Collection<String>,
    ): Boolean {
        if (
            hierarchyAuthority() != AccountHierarchyAuthority.ROOT_V1 ||
            preferences.contains(HIERARCHY_DEACTIVATION_TRANSACTION) ||
            preferences.contains(HIERARCHY_DEACTIVATION_STATE) ||
            preferences.contains(HIERARCHY_DEACTIVATION_MANIFEST) ||
            preferences.contains(HIERARCHY_ACTIVE_TRANSACTION) ||
            preferences.contains(HIERARCHY_MIGRATION_TRANSACTION) ||
            preferences.contains(HIERARCHY_MIGRATION_STATE) ||
            preferences.contains(HIERARCHY_MIGRATION_MANIFEST)
        ) {
            return false
        }
        val inventory = accountInventory(accountUuids) ?: return false
        return try {
            inventory.all { (recordKey, accountUuid) ->
                val envelope = readPayload(recordKey) ?: return@all false
                try {
                    TinkProtoKeysetFormat.parseEncryptedKeyset(
                        envelope,
                        rootAead,
                        SecureContentAssociatedData.forAccountMaterial(accountUuid),
                    )
                    true
                } finally {
                    envelope.fill(0)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun verifySameAccountKeyset(
        transactionId: String,
        accountUuid: String,
        source: KeysetHandle,
        candidate: KeysetHandle,
        aadOverride: ByteArray? = null,
    ): Boolean {
        val probe = ByteArray(32)
        migrationRandom.nextBytes(probe)
        val aad =
            aadOverride
                ?: SecureContentAssociatedData.forAccountMigrationProbe(transactionId, accountUuid)
        return try {
            val sourceAead = source.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            val candidateAead = candidate.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            val protected = sourceAead.encrypt(probe, aad)
            val recovered =
                try {
                    candidateAead.decrypt(protected, aad)
                } finally {
                    protected.fill(0)
                }
            try {
                MessageDigest.isEqual(probe, recovered)
            } finally {
                recovered.fill(0)
            }
        } catch (_: Exception) {
            false
        } finally {
            probe.fill(0)
            aad.fill(0)
        }
    }

    private fun hasHierarchyTransitionState(): Boolean =
        preferences.contains(HIERARCHY_MIGRATION_TRANSACTION) ||
            preferences.contains(HIERARCHY_MIGRATION_STATE) ||
            preferences.contains(HIERARCHY_MIGRATION_MANIFEST) ||
            preferences.contains(HIERARCHY_DEACTIVATION_TRANSACTION) ||
            preferences.contains(HIERARCHY_DEACTIVATION_STATE) ||
            preferences.contains(HIERARCHY_DEACTIVATION_MANIFEST)

    /**
     * Repairs the historical account-removal bug that could leave a complete ACTIVE account
     * envelope after its local Account row was gone.
     *
     * Only complete ACTIVE state+payload pairs are pruned. Partial records, unknown states, a
     * non-root authority, or any hierarchy transition remain fail-closed.
     */
    private fun reconcileOrphanedAccountKeyRecords(
        accountUuids: Collection<String>,
    ): Boolean {
        if (hierarchyAuthority() != AccountHierarchyAuthority.ROOT_V1 ||
            hasHierarchyTransitionState()
        ) {
            return false
        }

        val knownRecordKeys = linkedSetOf<String>()
        for (accountUuid in accountUuids) {
            if (accountUuid.isBlank()) return false
            knownRecordKeys += accountRecordKey(accountUuid)
        }

        val stateRecords =
            preferences.all.keys
                .filter { it.startsWith(ACCOUNT_RECORD_PREFIX) && it.endsWith(STATE_SUFFIX) }
                .mapTo(linkedSetOf()) { it.removeSuffix(STATE_SUFFIX) }
        val payloadRecords =
            preferences.all.keys
                .filter { it.startsWith(ACCOUNT_RECORD_PREFIX) && it.endsWith(PAYLOAD_SUFFIX) }
                .mapTo(linkedSetOf()) { it.removeSuffix(PAYLOAD_SUFFIX) }
        if (stateRecords != payloadRecords) {
            return false
        }

        val orphanRecords = stateRecords - knownRecordKeys
        if (orphanRecords.isEmpty()) {
            return true
        }
        if (orphanRecords.any { preferences.getString(stateKey(it), null) != STATE_ACTIVE }) {
            return false
        }

        val editor = preferences.edit()
        orphanRecords.forEach { recordKey ->
            editor.remove(stateKey(recordKey))
            editor.remove(payloadKey(recordKey))
        }
        if (!editor.commit()) {
            return false
        }

        val remainingStateRecords =
            preferences.all.keys
                .filter { it.startsWith(ACCOUNT_RECORD_PREFIX) && it.endsWith(STATE_SUFFIX) }
                .mapTo(linkedSetOf()) { it.removeSuffix(STATE_SUFFIX) }
        val remainingPayloadRecords =
            preferences.all.keys
                .filter { it.startsWith(ACCOUNT_RECORD_PREFIX) && it.endsWith(PAYLOAD_SUFFIX) }
                .mapTo(linkedSetOf()) { it.removeSuffix(PAYLOAD_SUFFIX) }
        return remainingStateRecords == remainingPayloadRecords &&
            knownRecordKeys.containsAll(remainingStateRecords)
    }

    private fun accountInventory(
        accountUuids: Collection<String>,
    ): LinkedHashMap<String, String>? {
        val known = linkedMapOf<String, String>()
        for (accountUuid in accountUuids) {
            if (accountUuid.isBlank()) return null
            val recordKey = accountRecordKey(accountUuid)
            val previous = known.put(recordKey, accountUuid)
            if (previous != null && previous != accountUuid) return null
        }

        val stateRecords =
            preferences.all.keys
                .filter { it.startsWith(ACCOUNT_RECORD_PREFIX) && it.endsWith(STATE_SUFFIX) }
                .mapTo(linkedSetOf()) { it.removeSuffix(STATE_SUFFIX) }
        val payloadRecords =
            preferences.all.keys
                .filter { it.startsWith(ACCOUNT_RECORD_PREFIX) && it.endsWith(PAYLOAD_SUFFIX) }
                .mapTo(linkedSetOf()) { it.removeSuffix(PAYLOAD_SUFFIX) }
        if (stateRecords != payloadRecords || !known.keys.containsAll(stateRecords)) return null
        if (stateRecords.any { preferences.getString(stateKey(it), null) != STATE_ACTIVE }) {
            return null
        }

        val inventory = linkedMapOf<String, String>()
        stateRecords.sorted().forEach { recordKey ->
            inventory[recordKey] = known[recordKey] ?: return null
        }
        return inventory
    }

    private fun validatePreparedManifest(
        manifest: SecureContentAccountMigrationManifestV1,
        accountUuids: Collection<String>,
    ): Boolean {
        val inventory = accountInventory(accountUuids) ?: return false
        if (inventory.keys.sorted() != manifest.entries.map { it.accountRecordKey }.sorted()) {
            return false
        }
        return manifest.entries.all { entry ->
            val candidate =
                readBase64Preference(
                    migrationCandidateKey(manifest.transactionId, entry.accountRecordKey),
                ) ?: return@all false
            try {
                MessageDigest.isEqual(
                    MessageDigest.getInstance("SHA-256").digest(candidate),
                    entry.candidateDigest,
                )
            } finally {
                candidate.fill(0)
            }
        }
    }

    private fun validateCommittedManifest(
        manifest: SecureContentAccountMigrationManifestV1,
        accountUuids: Collection<String>,
    ): Boolean {
        val inventory = accountInventory(accountUuids) ?: return false
        if (inventory.keys.sorted() != manifest.entries.map { it.accountRecordKey }.sorted()) {
            return false
        }
        return manifest.entries.all { entry ->
            val current = readPayload(entry.accountRecordKey) ?: return@all false
            try {
                MessageDigest.isEqual(
                    MessageDigest.getInstance("SHA-256").digest(current),
                    entry.candidateDigest,
                )
            } finally {
                current.fill(0)
            }
        }
    }

    private fun readMigrationManifest(
        transactionId: String,
    ): SecureContentAccountMigrationManifestV1? {
        return try {
            val envelope = readBase64Preference(HIERARCHY_MIGRATION_MANIFEST) ?: return null
            val plaintext =
                migrationJournalAead.decrypt(
                    envelope,
                    SecureContentAssociatedData.forAccountMigrationManifest(transactionId),
                )
            try {
                SecureContentAccountMigrationManifestCodecV1.decode(plaintext)
                    .takeIf { it.transactionId == transactionId }
            } finally {
                envelope.fill(0)
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun validatePreparedDeactivationManifest(
        manifest: SecureContentAccountMigrationManifestV1,
        accountUuids: Collection<String>,
    ): Boolean {
        val inventory = accountInventory(accountUuids) ?: return false
        if (inventory.keys.sorted() != manifest.entries.map { it.accountRecordKey }.sorted()) {
            return false
        }
        return manifest.entries.all { entry ->
            val candidate =
                readBase64Preference(
                    deactivationCandidateKey(manifest.transactionId, entry.accountRecordKey),
                ) ?: return@all false
            try {
                MessageDigest.isEqual(
                    MessageDigest.getInstance("SHA-256").digest(candidate),
                    entry.candidateDigest,
                )
            } finally {
                candidate.fill(0)
            }
        }
    }

    private fun readDeactivationManifest(
        transactionId: String,
    ): SecureContentAccountMigrationManifestV1? {
        return try {
            val envelope = readBase64Preference(HIERARCHY_DEACTIVATION_MANIFEST) ?: return null
            val plaintext =
                migrationJournalAead.decrypt(
                    envelope,
                    SecureContentAssociatedData.forAccountDeactivationManifest(transactionId),
                )
            try {
                SecureContentAccountMigrationManifestCodecV1.decode(plaintext)
                    .takeIf { it.transactionId == transactionId }
            } finally {
                envelope.fill(0)
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readBase64Preference(key: String): ByteArray? =
        try {
            preferences.getString(key, null)?.let {
                Base64.decode(it, Base64.NO_WRAP)
            }
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun hierarchyTransitionBlocksWrites(): Boolean {
        if (preferences.getString(HIERARCHY_DEACTIVATION_STATE, null) != null) {
            return true
        }
        return when (preferences.getString(HIERARCHY_MIGRATION_STATE, null)) {
            null, MIGRATION_COMMITTED -> hierarchyAuthority() == AccountHierarchyAuthority.CORRUPT
            else -> true
        }
    }

    private fun hierarchyAuthority(): AccountHierarchyAuthority =
        when (preferences.getString(HIERARCHY_AUTHORITY_KEY, null)) {
            null, AUTHORITY_ROOT_V1 -> AccountHierarchyAuthority.ROOT_V1
            AUTHORITY_GATED_V1 -> AccountHierarchyAuthority.GATED_V1
            AUTHORITY_DEACTIVATING_ROOT_V1 -> AccountHierarchyAuthority.DEACTIVATING_ROOT_V1
            else -> AccountHierarchyAuthority.CORRUPT
        }

    private fun migrationCandidateKey(
        transactionId: String,
        accountRecordKey: String,
    ): String =
        HIERARCHY_MIGRATION_CANDIDATE_PREFIX +
            digest(transactionId.toByteArray()) +
            "." +
            accountRecordKey

    private fun deactivationCandidateKey(
        transactionId: String,
        accountRecordKey: String,
    ): String =
        HIERARCHY_DEACTIVATION_CANDIDATE_PREFIX +
            digest(transactionId.toByteArray()) +
            "." +
            accountRecordKey

    private fun writeRecord(
        recordKey: String,
        state: String,
        payload: ByteArray,
    ): Boolean =
        preferences.edit()
            .putString(stateKey(recordKey), state)
            .putString(payloadKey(recordKey), Base64.encodeToString(payload, Base64.NO_WRAP))
            .commit()

    private fun readPayload(recordKey: String): ByteArray? =
        try {
            preferences.getString(payloadKey(recordKey), null)?.let {
                Base64.decode(it, Base64.NO_WRAP)
            }
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun accountRecordKey(accountUuid: String): String =
        "account." + digest(accountUuid.toByteArray())

    private fun contentRecordKey(context: SecureContentCryptoContext): String =
        "content." + digest(SecureContentAssociatedData.forContext(context))

    private fun stateKey(recordKey: String): String = "$recordKey.state"

    private fun payloadKey(recordKey: String): String = "$recordKey.payload"

    private fun digest(value: ByteArray): String =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(value),
            Base64.NO_WRAP or Base64.URL_SAFE,
        )

    internal inner class ContentReadScope(
        private val accountUuid: String,
        accountAead: Aead?,
        private val cryptoEpoch: Long?,
    ) : AutoCloseable {
        private var accountAead: Aead? = accountAead
        private var closed = false

        fun resolveForRead(
            context: SecureContentCryptoContext,
        ): SecureContentKeyMaterialResult<SecureContentKeyMaterialHandle> =
            synchronized(this@PersistentSecureContentKeyMaterialStore) {
                if (closed ||
                    context.accountUuid != accountUuid ||
                    !isCryptoEpochValid(cryptoEpoch)
                ) {
                    return@synchronized SecureContentKeyMaterialResult.Failure(
                        SecureContentKeyMaterialFailure.CONTEXT_MISMATCH,
                    )
                }
                val scopedAead =
                    accountAead
                        ?: return@synchronized SecureContentKeyMaterialResult.Failure(
                            SecureContentKeyMaterialFailure.MATERIAL_UNAVAILABLE,
                        )
                resolveForReadInternal(context, scopedAead)
            }

        override fun close() {
            synchronized(this@PersistentSecureContentKeyMaterialStore) {
                accountAead = null
                closed = true
            }
        }
    }

    private class RuntimeHandle(
        override val context: SecureContentCryptoContext,
        override val keysetHandle: KeysetHandle,
    ) : TinkSecureContentKeyMaterialHandle

    private inner class WriteSession(
        override val context: SecureContentCryptoContext,
        private val recordKey: String,
        override val writerHandle: SecureContentKeyMaterialHandle,
    ) : SecureContentKeyMaterialWriteSession {
        override var state: SecureContentKeyMaterialState = SecureContentKeyMaterialState.CANDIDATE
            private set

        override fun prepareCommit(): SecureContentKeyMaterialResult<SecureContentKeyMaterialCommitCandidate> {
            if (state != SecureContentKeyMaterialState.CANDIDATE) {
                return SecureContentKeyMaterialResult.Failure(
                    SecureContentKeyMaterialFailure.RECOVERY_IN_DOUBT,
                )
            }
            if (!preferences.edit().putString(stateKey(recordKey), STATE_READY_TO_COMMIT).commit()) {
                state = SecureContentKeyMaterialState.FAILED
                return SecureContentKeyMaterialResult.Failure(
                    SecureContentKeyMaterialFailure.PREPARATION_FAILED,
                )
            }
            state = SecureContentKeyMaterialState.READY_TO_COMMIT
            return SecureContentKeyMaterialResult.Success(CommitCandidate(context, recordKey))
        }

        override fun abort() {
            if (state == SecureContentKeyMaterialState.CANDIDATE ||
                state == SecureContentKeyMaterialState.READY_TO_COMMIT
            ) {
                preferences.edit()
                    .putString(stateKey(recordKey), STATE_ABORTED)
                    .remove(payloadKey(recordKey))
                    .commit()
                state = SecureContentKeyMaterialState.ABORTED
            }
        }
    }

    private data class CommitCandidate(
        override val context: SecureContentCryptoContext,
        val recordKey: String,
    ) : SecureContentKeyMaterialCommitCandidate {
        override val state: SecureContentKeyMaterialState
            get() = SecureContentKeyMaterialState.READY_TO_COMMIT
    }

    private fun toState(value: String): SecureContentKeyMaterialState? = when (value) {
        STATE_CANDIDATE -> SecureContentKeyMaterialState.CANDIDATE
        STATE_READY_TO_COMMIT -> SecureContentKeyMaterialState.READY_TO_COMMIT
        STATE_ACTIVE -> SecureContentKeyMaterialState.ACTIVE
        STATE_ABORTED -> SecureContentKeyMaterialState.ABORTED
        STATE_FAILED -> SecureContentKeyMaterialState.FAILED
        STATE_INVALIDATED -> SecureContentKeyMaterialState.INVALIDATED
        else -> null
    }

    private enum class AccountHierarchyAuthority {
        ROOT_V1,
        GATED_V1,
        DEACTIVATING_ROOT_V1,
        CORRUPT,
    }

    private companion object {
        const val PREFERENCES_NAME = "secure_content_key_material_v1"
        const val CRYPTO_VERSION = 1

        const val ACCOUNT_RECORD_PREFIX = "account."
        const val STATE_SUFFIX = ".state"
        const val PAYLOAD_SUFFIX = ".payload"
        const val HIERARCHY_AUTHORITY_KEY = "hierarchy.authority"
        const val HIERARCHY_ACTIVE_TRANSACTION = "hierarchy.active_transaction"
        const val HIERARCHY_MIGRATION_TRANSACTION = "hierarchy.migration.transaction"
        const val HIERARCHY_MIGRATION_STATE = "hierarchy.migration.state"
        const val HIERARCHY_MIGRATION_MANIFEST = "hierarchy.migration.manifest"
        const val HIERARCHY_MIGRATION_CANDIDATE_PREFIX = "hierarchy.migration.candidate."
        const val HIERARCHY_DEACTIVATION_TRANSACTION = "hierarchy.deactivation.transaction"
        const val HIERARCHY_DEACTIVATION_STATE = "hierarchy.deactivation.state"
        const val HIERARCHY_DEACTIVATION_MANIFEST = "hierarchy.deactivation.manifest"
        const val HIERARCHY_DEACTIVATION_CANDIDATE_PREFIX = "hierarchy.deactivation.candidate."
        const val AUTHORITY_ROOT_V1 = "root_v1"
        const val AUTHORITY_GATED_V1 = "app_master_v1"
        const val AUTHORITY_DEACTIVATING_ROOT_V1 = "deactivating_root_v1"
        const val MIGRATION_PREPARED = "prepared"
        const val MIGRATION_COMMITTED = "committed"

        const val STATE_CANDIDATE = "candidate"
        const val STATE_READY_TO_COMMIT = "ready_to_commit"
        const val STATE_ACTIVE = "active"
        const val STATE_ABORTED = "aborted"
        const val STATE_FAILED = "failed"
        const val STATE_INVALIDATED = "invalidated"
    }
}
