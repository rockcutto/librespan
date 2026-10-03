// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import android.content.ContentValues
import android.content.Context
import android.util.Base64
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.security.cryptolock.ActivationJournalAeadV1
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONObject

internal enum class AccountSecretMigrationPhaseV1 {
    PREPARED,
    COMMITTED,
}

internal class AccountSecretMigrationRecordV1(
    val accountUuid: String,
    val phase: AccountSecretMigrationPhaseV1,
    val expectedMask: Int,
    evidenceValue: ByteArray,
    val updatedAt: Long,
) {
    private val evidenceValue: ByteArray = evidenceValue.copyOf()

    init {
        require(accountUuid.isNotBlank())
        require(expectedMask >= 0)
        require(evidenceValue.size == EVIDENCE_BYTES)
        require(updatedAt > 0L)
    }

    fun evidence(): ByteArray = evidenceValue.copyOf()

    companion object {
        const val EVIDENCE_BYTES = 32
    }
}

internal sealed class AccountSecretMigrationReadResultV1 {
    data object Absent : AccountSecretMigrationReadResultV1()
    data object Corrupt : AccountSecretMigrationReadResultV1()
    data class Present(val record: AccountSecretMigrationRecordV1) :
        AccountSecretMigrationReadResultV1()
}

internal object AccountSecretMigrationRecordCodecV1 {
    private const val MAGIC = 0x4e43534d // NCSM
    private const val VERSION = 1
    private const val MAX_ACCOUNT_UUID_BYTES = 512
    private const val MAX_RECORD_BYTES = 2048

    fun encode(record: AccountSecretMigrationRecordV1): ByteArray {
        val evidence = record.evidence()
        return try {
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeInt(MAGIC)
                    out.writeInt(VERSION)
                    writeUtf8(out, record.accountUuid)
                    out.writeUTF(record.phase.name)
                    out.writeInt(record.expectedMask)
                    out.writeInt(evidence.size)
                    out.write(evidence)
                    out.writeLong(record.updatedAt)
                }
                bytes.toByteArray()
            }
        } finally {
            evidence.fill(0)
        }
    }

    fun decode(encoded: ByteArray): AccountSecretMigrationRecordV1 {
        require(encoded.isNotEmpty() && encoded.size <= MAX_RECORD_BYTES)
        DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            require(input.readInt() == MAGIC)
            require(input.readInt() == VERSION)
            val accountUuid = readUtf8(input)
            val phase = AccountSecretMigrationPhaseV1.valueOf(input.readUTF())
            val expectedMask = input.readInt()
            val evidenceSize = input.readInt()
            require(evidenceSize == AccountSecretMigrationRecordV1.EVIDENCE_BYTES)
            val evidence = ByteArray(evidenceSize).also(input::readFully)
            val updatedAt = input.readLong()
            require(input.available() == 0)
            return try {
                AccountSecretMigrationRecordV1(
                    accountUuid,
                    phase,
                    expectedMask,
                    evidence,
                    updatedAt,
                )
            } finally {
                evidence.fill(0)
            }
        }
    }

    private fun writeUtf8(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_ACCOUNT_UUID_BYTES)
        out.writeInt(bytes.size)
        out.write(bytes)
        bytes.fill(0)
    }

    private fun readUtf8(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 1..MAX_ACCOUNT_UUID_BYTES)
        return ByteArray(size).also(input::readFully).toString(StandardCharsets.UTF_8)
    }
}

internal class AccountSecretMigrationStoreV1(
    context: Context,
    private val aead: ActivationJournalAeadV1 = ActivationJournalAeadV1(),
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun read(accountUuid: String): AccountSecretMigrationReadResultV1 {
        val encoded = preferences.getString(recordKey(accountUuid), null)
            ?: return AccountSecretMigrationReadResultV1.Absent
        return try {
            val envelope = Base64.decode(encoded, Base64.NO_WRAP)
            val aad = associatedData(accountUuid)
            val plaintext = aead.decrypt(envelope, aad)
            try {
                val record = AccountSecretMigrationRecordCodecV1.decode(plaintext)
                if (record.accountUuid != accountUuid) {
                    AccountSecretMigrationReadResultV1.Corrupt
                } else {
                    AccountSecretMigrationReadResultV1.Present(record)
                }
            } finally {
                envelope.fill(0)
                aad.fill(0)
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            AccountSecretMigrationReadResultV1.Corrupt
        }
    }

    @Synchronized
    fun clear(accountUuid: String): Boolean =
        preferences.edit().remove(recordKey(accountUuid)).commit()

    @Synchronized
    fun write(record: AccountSecretMigrationRecordV1): Boolean =
        try {
            val plaintext = AccountSecretMigrationRecordCodecV1.encode(record)
            val aad = associatedData(record.accountUuid)
            val envelope = aead.encrypt(plaintext, aad)
            try {
                preferences.edit()
                    .putString(
                        recordKey(record.accountUuid),
                        Base64.encodeToString(envelope, Base64.NO_WRAP),
                    )
                    .commit()
            } finally {
                plaintext.fill(0)
                aad.fill(0)
                envelope.fill(0)
            }
        } catch (_: Exception) {
            false
        }

    private fun recordKey(accountUuid: String): String {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(accountUuid.toByteArray(StandardCharsets.UTF_8))
        return try {
            buildString(RECORD_PREFIX.length + digest.size * 2) {
                append(RECORD_PREFIX)
                digest.forEach { value ->
                    append(HEX[(value.toInt() ushr 4) and 0x0f])
                    append(HEX[value.toInt() and 0x0f])
                }
            }
        } finally {
            digest.fill(0)
        }
    }

    private fun associatedData(accountUuid: String): ByteArray =
        ("NeoCont|AccountSecretMigration|v1|" + accountUuid)
            .toByteArray(StandardCharsets.UTF_8)

    private companion object {
        const val PREFERENCES_NAME = "account_secret_migration_v1"
        const val RECORD_PREFIX = "account."
        const val HEX = "0123456789abcdef"
    }
}

/**
 * Java-visible write policy. PREPARED is already an authority boundary: once a verified Vault
 * candidate exists, normal account updates may never repopulate retired legacy secret fields.
 * Corrupt migration state also fails closed.
 */
object AccountSecretPersistencePolicyV1 {
    @JvmStatic
    fun sanitizeForDatabase(
        context: Context,
        accountUuid: String,
        values: ContentValues,
    ): ContentValues {
        val read = AccountSecretMigrationStoreV1(context).read(accountUuid)
        if (read is AccountSecretMigrationReadResultV1.Absent) {
            return values
        }
        values.putNull(Account.PASSWORD)
        values.putNull(Account.FAST_TOKEN)
        val keys = JSONObject(values.getAsString(Account.KEYS) ?: "{}")
        keys.remove(Account.KEY_PRE_AUTH_REGISTRATION_TOKEN)
        values.put(Account.KEYS, keys.toString())
        return values
    }

    @JvmStatic
    fun legacyPersistenceAllowed(
        context: Context,
        accountUuid: String,
    ): Boolean =
        AccountSecretMigrationStoreV1(context).read(accountUuid) is
            AccountSecretMigrationReadResultV1.Absent
}

internal enum class AccountSecretDurableUpdateV1 {
    KEEP,
    PUT,
    DELETE,
}

internal object AccountSecretRuntimeUpdatePolicyV1 {
    fun decide(
        value: String?,
        explicitRetirement: Boolean,
    ): AccountSecretDurableUpdateV1 =
        when {
            explicitRetirement -> AccountSecretDurableUpdateV1.DELETE
            value.isNullOrEmpty() -> AccountSecretDurableUpdateV1.KEEP
            else -> AccountSecretDurableUpdateV1.PUT
        }
}

/**
 * Keeps already-migrated runtime authentication values authoritative in Vault while preventing
 * locked/null runtime placeholders from deleting durable secrets.
 */
object AccountSecretRuntimePersistenceV1 {
    private data class RuntimeSecret(
        val bit: Int,
        val type: AccountSecretTypeV1,
        val value: String?,
    )

    @JvmStatic
    fun persistIfHydrated(
        context: Context,
        account: Account,
    ): Boolean {
        val applicationContext = context.applicationContext
        val store = AccountSecretMigrationStoreV1(applicationContext)
        val read = store.read(account.uuid)
        val current =
            (read as? AccountSecretMigrationReadResultV1.Present)
                ?.record
                ?.takeIf { it.phase == AccountSecretMigrationPhaseV1.COMMITTED }

        val secrets =
            listOf(
                RuntimeSecret(1, AccountSecretTypeV1.XMPP_PASSWORD, account.password),
                RuntimeSecret(2, AccountSecretTypeV1.FAST_TOKEN, account.fastToken),
                RuntimeSecret(
                    4,
                    AccountSecretTypeV1.PRE_AUTH_REGISTRATION_TOKEN,
                    account.getKey(Account.KEY_PRE_AUTH_REGISTRATION_TOKEN),
                ),
            )
        val hasRuntimeCredential = secrets.any { !it.value.isNullOrEmpty() }

        // A locked/partially-hydrated Account may legitimately contain null placeholders. Null is
        // never authorization to destroy a durable Vault secret. Explicit retirement has dedicated
        // APIs (account deletion, token retirement) and must not be inferred from transient state.
        if (!account.isSecretVaultHydrated && !hasRuntimeCredential) {
            return true
        }

        val vault = PersistentAccountSecretVaultV1(applicationContext)
        var mask = current?.expectedMask ?: 0
        val retireFastToken = account.isFastTokenRetirementRequested

        for (secret in secrets) {
            val explicitRetirement =
                secret.type == AccountSecretTypeV1.FAST_TOKEN && retireFastToken
            when (
                AccountSecretRuntimeUpdatePolicyV1.decide(
                    secret.value,
                    explicitRetirement,
                )
            ) {
                AccountSecretDurableUpdateV1.KEEP -> continue
                AccountSecretDurableUpdateV1.DELETE -> {
                    // Retire authority first. Ciphertext cleanup happens only after the new journal
                    // is durable, so a process death can never leave a journal requiring a token
                    // that has already been physically deleted.
                    mask = mask and secret.bit.inv()
                }
                AccountSecretDurableUpdateV1.PUT -> {
                    val value = secret.value ?: return false
                    val bytes = value.toByteArray(StandardCharsets.UTF_8)
                    try {
                        when (vault.put(AccountSecretKeyV1(account.uuid, secret.type), bytes)) {
                            is AccountSecretVaultResultV1.Failure -> return false
                            is AccountSecretVaultResultV1.Success -> Unit
                        }
                        when (val opened = vault.open(AccountSecretKeyV1(account.uuid, secret.type))) {
                            is AccountSecretVaultResultV1.Failure -> return false
                            is AccountSecretVaultResultV1.Success -> {
                                val stored = opened.value ?: return false
                                val matches =
                                    stored.use {
                                        it.useBytes { candidate ->
                                            MessageDigest.isEqual(bytes, candidate)
                                        }
                                    }
                                if (!matches) {
                                    return false
                                }
                            }
                        }
                        mask = mask or secret.bit
                    } finally {
                        bytes.fill(0)
                    }
                }
            }
        }

        // If nothing changed and the runtime was already authenticated, keep the existing journal.
        if (current != null &&
            mask == current.expectedMask &&
            account.isSecretVaultHydrated &&
            !retireFastToken
        ) {
            return true
        }

        // Rebuild evidence from the authoritative encrypted Vault records. This makes password
        // re-entry a transactional repair of both Vault and journal without ever writing plaintext
        // back to SQLite.
        val digest = MessageDigest.getInstance("SHA-256")
        for (secret in secrets) {
            if ((mask and secret.bit) == 0) continue
            when (val opened = vault.open(AccountSecretKeyV1(account.uuid, secret.type))) {
                is AccountSecretVaultResultV1.Failure -> return false
                is AccountSecretVaultResultV1.Success -> {
                    val stored = opened.value ?: return false
                    val ok =
                        stored.use {
                            it.useBytes { bytes ->
                                if (bytes.isEmpty()) {
                                    false
                                } else {
                                    digest.update(secret.type.wireName.toByteArray(StandardCharsets.UTF_8))
                                    digest.update(0.toByte())
                                    digest.update(bytes)
                                    true
                                }
                            }
                        }
                    if (!ok) return false
                }
            }
        }
        val evidence = digest.digest()
        val repaired =
            try {
                AccountSecretMigrationRecordV1(
                    accountUuid = account.uuid,
                    phase = AccountSecretMigrationPhaseV1.COMMITTED,
                    expectedMask = mask,
                    evidenceValue = evidence,
                    updatedAt = System.currentTimeMillis().coerceAtLeast(current?.updatedAt ?: 1L),
                )
            } finally {
                evidence.fill(0)
            }
        if (!store.write(repaired)) {
            return false
        }

        if (retireFastToken) {
            when (
                vault.delete(
                    AccountSecretKeyV1(
                        account.uuid,
                        AccountSecretTypeV1.FAST_TOKEN,
                    ),
                )
            ) {
                is AccountSecretVaultResultV1.Failure -> return false
                is AccountSecretVaultResultV1.Success ->
                    account.markFastTokenRetirementPersisted()
            }
        }

        if (hasRuntimeCredential) {
            account.markSecretVaultHydrated()
        }
        return true
    }
}

object AccountSecretRetirementV1 {
    @JvmStatic
    fun retireDeletedAccount(
        context: Context,
        accountUuid: String,
    ): Boolean {
        val applicationContext = context.applicationContext
        val vault = PersistentAccountSecretVaultV1(applicationContext)
        val migrationStore = AccountSecretMigrationStoreV1(applicationContext)
        if (!vault.deleteAccountAuthenticationSecrets(accountUuid)) {
            return false
        }
        return migrationStore.clear(accountUuid)
    }
}
