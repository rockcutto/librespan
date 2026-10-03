// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import android.content.Context
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.persistance.DatabaseBackend
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONObject

enum class AccountSecretMigrationOutcomeV1 {
    COMMITTED,
    ALREADY_COMMITTED,
    LOCKED_OR_UNAVAILABLE,
    FAILED,
}

internal object AccountSecretAuthorityPolicyV1 {
    fun emptyMaskCanHydrate(privateKeyAlias: String?): Boolean =
        !privateKeyAlias.isNullOrEmpty()
}

object AccountSecretConnectionPolicyV1 {
    @JvmStatic
    fun permitsConnection(outcome: AccountSecretMigrationOutcomeV1): Boolean =
        outcome == AccountSecretMigrationOutcomeV1.COMMITTED ||
            outcome == AccountSecretMigrationOutcomeV1.ALREADY_COMMITTED
}


internal class AccountSecretMigrationCoordinatorV1 @JvmOverloads constructor(
    context: Context,
    private val database: DatabaseBackend,
    private val vault: PersistentAccountSecretVaultV1 =
        PersistentAccountSecretVaultV1(context.applicationContext),
    private val store: AccountSecretMigrationStoreV1 =
        AccountSecretMigrationStoreV1(context.applicationContext),
) {
    private val applicationContext = context.applicationContext

    fun migrateAndHydrate(account: Account): AccountSecretMigrationOutcomeV1 {
        val accountUuid = account.uuid
        return when (val read = store.read(accountUuid)) {
            AccountSecretMigrationReadResultV1.Corrupt ->
                if (database.areMigratedAccountSecretsScrubbed(accountUuid) &&
                    hydrateKnownAuthSecretsReadOnly(account)
                ) {
                    AccountSecretMigrationOutcomeV1.ALREADY_COMMITTED
                } else {
                    AccountSecretMigrationOutcomeV1.FAILED
                }
            is AccountSecretMigrationReadResultV1.Present ->
                when (read.record.phase) {
                    AccountSecretMigrationPhaseV1.PREPARED ->
                        completePrepared(account, read.record)
                    AccountSecretMigrationPhaseV1.COMMITTED -> {
                        if (!database.areMigratedAccountSecretsScrubbed(accountUuid)) {
                            AccountSecretMigrationOutcomeV1.FAILED
                        } else if (read.record.expectedMask == 0) {
                            when {
                                hydrateKnownAuthSecretsReadOnly(account) ->
                                    AccountSecretMigrationOutcomeV1.ALREADY_COMMITTED
                                AccountSecretAuthorityPolicyV1.emptyMaskCanHydrate(
                                    account.getPrivateKeyAlias(),
                                ) -> {
                                    // Client-certificate authentication legitimately has no
                                    // password/FAST/pre-auth secret in Account Secret Vault.
                                    account.markSecretVaultHydrated()
                                    AccountSecretMigrationOutcomeV1.ALREADY_COMMITTED
                                }
                                else ->
                                    AccountSecretMigrationOutcomeV1.LOCKED_OR_UNAVAILABLE
                            }
                        } else if (hydrateFromVault(account, read.record.expectedMask)) {
                            AccountSecretMigrationOutcomeV1.ALREADY_COMMITTED
                        } else {
                            AccountSecretMigrationOutcomeV1.LOCKED_OR_UNAVAILABLE
                        }
                    }
                }
            AccountSecretMigrationReadResultV1.Absent ->
                if (database.areMigratedAccountSecretsScrubbed(accountUuid) &&
                    hydrateKnownAuthSecretsReadOnly(account)
                ) {
                    AccountSecretMigrationOutcomeV1.ALREADY_COMMITTED
                } else {
                    migrateLegacy(account)
                }
        }
    }

    fun migrateNewAccount(account: Account): AccountSecretMigrationOutcomeV1 =
        migrateAndHydrate(account)

    private fun migrateLegacy(account: Account): AccountSecretMigrationOutcomeV1 {
        if (!vault.isWriteAvailable(account.uuid)) {
            account.clearTransientAuthenticationSecrets()
            return AccountSecretMigrationOutcomeV1.LOCKED_OR_UNAVAILABLE
        }
        val secrets = collectLegacySecrets(account.uuid)
        val expectedMask = secrets.fold(0) { mask, secret -> mask or secret.bit }
        val evidence = MessageDigest.getInstance("SHA-256")
        try {
            for (secret in secrets) {
                val bytes = secret.value.toByteArray(StandardCharsets.UTF_8)
                try {
                    when (vault.put(secret.key(account.uuid), bytes)) {
                        is AccountSecretVaultResultV1.Success -> Unit
                        is AccountSecretVaultResultV1.Failure ->
                            return AccountSecretMigrationOutcomeV1.LOCKED_OR_UNAVAILABLE
                    }
                    when (val opened = vault.open(secret.key(account.uuid))) {
                        is AccountSecretVaultResultV1.Failure ->
                            return AccountSecretMigrationOutcomeV1.FAILED
                        is AccountSecretVaultResultV1.Success -> {
                            val value = opened.value ?: return AccountSecretMigrationOutcomeV1.FAILED
                            value.use {
                                val matches =
                                    it.useBytes { stored ->
                                        MessageDigest.isEqual(bytes, stored)
                                    }
                                if (!matches) {
                                    return AccountSecretMigrationOutcomeV1.FAILED
                                }
                            }
                        }
                    }
                    evidence.update(secret.type.wireName.toByteArray(StandardCharsets.UTF_8))
                    evidence.update(0.toByte())
                    evidence.update(bytes)
                } finally {
                    bytes.fill(0)
                }
            }

            val digest = evidence.digest()
            val now = System.currentTimeMillis().coerceAtLeast(1L)
            val prepared =
                AccountSecretMigrationRecordV1(
                    accountUuid = account.uuid,
                    phase = AccountSecretMigrationPhaseV1.PREPARED,
                    expectedMask = expectedMask,
                    evidenceValue = digest,
                    updatedAt = now,
                )
            digest.fill(0)
            if (!store.write(prepared)) {
                return AccountSecretMigrationOutcomeV1.FAILED
            }
            return completePrepared(account, prepared)
        } catch (_: Exception) {
            return AccountSecretMigrationOutcomeV1.FAILED
        }
    }

    private fun completePrepared(
        account: Account,
        record: AccountSecretMigrationRecordV1,
    ): AccountSecretMigrationOutcomeV1 {
        if (!verifyPreparedEvidence(account.uuid, record)) {
            return AccountSecretMigrationOutcomeV1.LOCKED_OR_UNAVAILABLE
        }
        if (!database.scrubMigratedAccountSecrets(account.uuid)) {
            return AccountSecretMigrationOutcomeV1.FAILED
        }
        if (!database.areMigratedAccountSecretsScrubbed(account.uuid)) {
            return AccountSecretMigrationOutcomeV1.FAILED
        }
        val committed =
            AccountSecretMigrationRecordV1(
                accountUuid = record.accountUuid,
                phase = AccountSecretMigrationPhaseV1.COMMITTED,
                expectedMask = record.expectedMask,
                evidenceValue = record.evidence(),
                updatedAt = System.currentTimeMillis().coerceAtLeast(record.updatedAt),
            )
        if (!store.write(committed)) {
            return AccountSecretMigrationOutcomeV1.FAILED
        }
        account.clearTransientAuthenticationSecrets()
        return if (hydrateFromVault(account, committed.expectedMask)) {
            AccountSecretMigrationOutcomeV1.COMMITTED
        } else {
            AccountSecretMigrationOutcomeV1.LOCKED_OR_UNAVAILABLE
        }
    }

    private fun verifyPreparedEvidence(
        accountUuid: String,
        record: AccountSecretMigrationRecordV1,
    ): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        for (secret in Secret.values()) {
            if ((record.expectedMask and secret.bit) == 0) continue
            when (val opened = vault.open(secret.key(accountUuid))) {
                is AccountSecretVaultResultV1.Failure -> return false
                is AccountSecretVaultResultV1.Success -> {
                    val value = opened.value ?: return false
                    val ok =
                        value.use {
                            it.useBytes { bytes ->
                                if (bytes.isEmpty()) {
                                    false
                                } else {
                                    digest.update(
                                        secret.type.wireName.toByteArray(StandardCharsets.UTF_8),
                                    )
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
        val actual = digest.digest()
        val expected = record.evidence()
        return try {
            MessageDigest.isEqual(actual, expected)
        } finally {
            actual.fill(0)
            expected.fill(0)
        }
    }

    /**
     * Read-only recovery for an install where legacy auth fields have already been scrubbed but the
     * migration journal is unavailable. Only successfully decrypted, non-empty known auth records
     * are copied into transient Account fields. No journal/DB/Vault writes happen here.
     */
    private fun hydrateKnownAuthSecretsReadOnly(account: Account): Boolean {
        account.clearTransientAuthenticationSecrets()
        var hydratedAny = false
        for (secret in Secret.values()) {
            when (val opened = vault.open(secret.key(account.uuid))) {
                is AccountSecretVaultResultV1.Failure -> {
                    account.clearTransientAuthenticationSecrets()
                    return false
                }
                is AccountSecretVaultResultV1.Success -> {
                    val value = opened.value ?: continue
                    val text =
                        value.use {
                            it.useBytes { bytes ->
                                if (bytes.isEmpty()) null
                                else String(bytes, StandardCharsets.UTF_8)
                            }
                        } ?: continue
                    secret.apply(account, text)
                    hydratedAny = true
                }
            }
        }
        if (hydratedAny) {
            account.markSecretVaultHydrated()
        }
        return hydratedAny
    }

    fun hydrateFromVault(
        account: Account,
        expectedMaskOverride: Int? = null,
    ): Boolean {
        val read = store.read(account.uuid)
        val record =
            when (read) {
                is AccountSecretMigrationReadResultV1.Present -> read.record
                else -> return false
            }
        if (record.phase != AccountSecretMigrationPhaseV1.COMMITTED) {
            return false
        }
        val expectedMask = expectedMaskOverride ?: record.expectedMask
        if (expectedMask == 0) {
            account.clearTransientAuthenticationSecrets()
            if (
                AccountSecretAuthorityPolicyV1.emptyMaskCanHydrate(
                    account.getPrivateKeyAlias(),
                )
            ) {
                account.markSecretVaultHydrated()
                return true
            }
            return false
        }
        account.clearTransientAuthenticationSecrets()
        for (secret in Secret.values()) {
            if ((expectedMask and secret.bit) == 0) continue
            when (val opened = vault.open(secret.key(account.uuid))) {
                is AccountSecretVaultResultV1.Failure -> {
                    account.clearTransientAuthenticationSecrets()
                    return false
                }
                is AccountSecretVaultResultV1.Success -> {
                    val value = opened.value ?: run {
                        account.clearTransientAuthenticationSecrets()
                        return false
                    }
                    val text =
                        value.use {
                            it.useBytes { bytes -> String(bytes, StandardCharsets.UTF_8) }
                        }
                    secret.apply(account, text)
                }
            }
        }
        account.markSecretVaultHydrated()
        return true
    }

    private fun collectLegacySecrets(accountUuid: String): List<LegacySecret> {
        val legacy = database.getLegacyAccountAuthenticationSecrets(accountUuid)
        val result = ArrayList<LegacySecret>(3)
        legacy[Account.PASSWORD]?.takeIf { it.isNotEmpty() }?.let {
            result += LegacySecret(Secret.PASSWORD, it)
        }
        legacy[Account.FAST_TOKEN]?.takeIf { it.isNotEmpty() }?.let {
            result += LegacySecret(Secret.FAST_TOKEN, it)
        }
        legacy[Account.KEY_PRE_AUTH_REGISTRATION_TOKEN]
            ?.takeIf { it.isNotEmpty() }
            ?.let { result += LegacySecret(Secret.PRE_AUTH, it) }
        return result
    }

    private data class LegacySecret(
        val secret: Secret,
        val value: String,
    ) {
        val bit: Int get() = secret.bit
        val type: AccountSecretTypeV1 get() = secret.type
        fun key(accountUuid: String) = secret.key(accountUuid)
    }

    private enum class Secret(
        val bit: Int,
        val type: AccountSecretTypeV1,
    ) {
        PASSWORD(1, AccountSecretTypeV1.XMPP_PASSWORD) {
            override fun apply(account: Account, value: String) = account.setPassword(value)
        },
        FAST_TOKEN(2, AccountSecretTypeV1.FAST_TOKEN) {
            override fun apply(account: Account, value: String) = account.setFastTokenValue(value)
        },
        PRE_AUTH(4, AccountSecretTypeV1.PRE_AUTH_REGISTRATION_TOKEN) {
            override fun apply(account: Account, value: String) {
                account.setKey(Account.KEY_PRE_AUTH_REGISTRATION_TOKEN, value)
            }
        };

        fun key(accountUuid: String) =
            AccountSecretKeyV1(accountUuid, type)

        abstract fun apply(account: Account, value: String)
    }
}
