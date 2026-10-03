// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

import com.google.crypto.tink.Aead
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal enum class AccountSecretTypeV1(val wireName: String) {
    XMPP_PASSWORD("xmpp_password"),
    FAST_TOKEN("fast_token"),
    PRE_AUTH_REGISTRATION_TOKEN("pre_auth_registration_token"),
    MUC_PASSWORD("muc_password"),
    BOOKMARK_PASSWORD("bookmark_password"),
    DRAFT_TEXT("draft_text"),
    OTR_KEYPAIR("otr_keypair"),
    OMEMO_IDENTITY("omemo_identity"),
    OMEMO_SIGNED_PREKEY("omemo_signed_prekey"),
    OMEMO_PREKEY("omemo_prekey"),
    OMEMO_SESSION("omemo_session"),
}

/**
 * Logical secret identity. [scopeId] is "account" for account-wide values and may later be a stable
 * conversation/bookmark identifier for scoped secrets. It is authenticated, never interpreted as a
 * filesystem path, and never used as a persistence key in plaintext.
 */
internal data class AccountSecretKeyV1(
    val accountUuid: String,
    val type: AccountSecretTypeV1,
    val scopeId: String = ACCOUNT_SCOPE,
) {
    init {
        require(accountUuid.isNotBlank() && accountUuid.length <= MAX_ACCOUNT_UUID_CHARS)
        require(scopeId.isNotBlank() && scopeId.length <= MAX_SCOPE_ID_CHARS)
        require(!accountUuid.any { it == '\u0000' })
        require(!scopeId.any { it == '\u0000' })
    }

    companion object {
        const val ACCOUNT_SCOPE = "account"
        private const val MAX_ACCOUNT_UUID_CHARS = 256
        private const val MAX_SCOPE_ID_CHARS = 512
    }
}

internal enum class AccountSecretVaultFailureV1 {
    UNAVAILABLE,
    CORRUPT,
    CRYPTO_FAILURE,
    PERSISTENCE_FAILURE,
    VALUE_TOO_LARGE,
}

internal sealed class AccountSecretVaultResultV1<out T> {
    data class Success<T>(val value: T) : AccountSecretVaultResultV1<T>()
    data class Failure(val reason: AccountSecretVaultFailureV1) :
        AccountSecretVaultResultV1<Nothing>()
}

/** Mutable, closeable plaintext value. The Vault never returns a String secret. */
internal class AccountSecretValueV1 internal constructor(
    private val bytes: ByteArray,
) : AutoCloseable {
    private var closed = false

    fun <T> useBytes(block: (ByteArray) -> T): T {
        check(!closed) { "account secret is closed" }
        return block(bytes)
    }

    override fun close() {
        if (!closed) {
            bytes.fill(0)
            closed = true
        }
    }
}

/**
 * Supplies the account AEAD already governed by the Secure Content account hierarchy.
 *
 * Persistent implementations MUST NOT manufacture a new independent master hierarchy for Vault.
 */
internal interface AccountSecretVaultAeadProviderV1 {
    fun forWrite(accountUuid: String): Aead?
    fun forRead(accountUuid: String): Aead?
}

/**
 * Opaque ciphertext record storage. Implementations synchronously copy/consume [ciphertext] before
 * returning from [write].
 */
internal interface AccountSecretVaultRecordStoreV1 {
    fun read(recordId: String): ByteArray?
    fun write(recordId: String, ciphertext: ByteArray): Boolean
    fun delete(recordId: String): Boolean
}

internal object AccountSecretVaultAssociatedDataV1 {
    private val marker = "NCS-ACCOUNT-SECRET-VAULT".toByteArray(StandardCharsets.US_ASCII)
    private const val VERSION = 1

    fun forKey(key: AccountSecretKeyV1): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(marker)
                output.writeInt(VERSION)
                writeUtf8(output, key.accountUuid)
                writeUtf8(output, key.type.wireName)
                writeUtf8(output, key.scopeId)
            }
            bytes.toByteArray()
        }

    private fun writeUtf8(output: DataOutputStream, value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        output.writeInt(encoded.size)
        output.write(encoded)
        encoded.fill(0)
    }
}

internal object AccountSecretVaultRecordIdentityV1 {
    fun forKey(key: AccountSecretKeyV1): String {
        val aad = AccountSecretVaultAssociatedDataV1.forKey(key)
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(aad)
            try {
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
        } finally {
            aad.fill(0)
        }
    }

    private const val RECORD_PREFIX = "v1."
    private const val HEX = "0123456789abcdef"
}

/**
 * Account Secret Vault core.
 *
 * No list/bulk-dump API exists by design. Missing record and unavailable crypto are distinct so a
 * future migration coordinator cannot mistake a locked Vault for permission to use legacy DB data.
 */
internal class AccountSecretVaultV1(
    private val aeadProvider: AccountSecretVaultAeadProviderV1,
    private val recordStore: AccountSecretVaultRecordStoreV1,
) {
    fun put(
        key: AccountSecretKeyV1,
        plaintext: ByteArray,
    ): AccountSecretVaultResultV1<Unit> {
        if (plaintext.size > MAX_SECRET_BYTES) {
            return AccountSecretVaultResultV1.Failure(
                AccountSecretVaultFailureV1.VALUE_TOO_LARGE,
            )
        }
        val aead =
            try {
                aeadProvider.forWrite(key.accountUuid)
            } catch (_: Exception) {
                null
            } ?: return AccountSecretVaultResultV1.Failure(
                AccountSecretVaultFailureV1.UNAVAILABLE,
            )
        val aad = AccountSecretVaultAssociatedDataV1.forKey(key)
        val ciphertext =
            try {
                aead.encrypt(plaintext, aad)
            } catch (_: Exception) {
                aad.fill(0)
                return AccountSecretVaultResultV1.Failure(
                    AccountSecretVaultFailureV1.CRYPTO_FAILURE,
                )
            }
        aad.fill(0)
        return try {
            if (recordStore.write(AccountSecretVaultRecordIdentityV1.forKey(key), ciphertext)) {
                AccountSecretVaultResultV1.Success(Unit)
            } else {
                AccountSecretVaultResultV1.Failure(
                    AccountSecretVaultFailureV1.PERSISTENCE_FAILURE,
                )
            }
        } finally {
            ciphertext.fill(0)
        }
    }

    fun open(
        key: AccountSecretKeyV1,
    ): AccountSecretVaultResultV1<AccountSecretValueV1?> {
        val ciphertext =
            recordStore.read(AccountSecretVaultRecordIdentityV1.forKey(key))
                ?: return AccountSecretVaultResultV1.Success(null)
        val aead =
            try {
                aeadProvider.forRead(key.accountUuid)
            } catch (_: Exception) {
                null
            } ?: run {
                ciphertext.fill(0)
                return AccountSecretVaultResultV1.Failure(
                    AccountSecretVaultFailureV1.UNAVAILABLE,
                )
            }
        val aad = AccountSecretVaultAssociatedDataV1.forKey(key)
        return try {
            val plaintext = aead.decrypt(ciphertext, aad)
            AccountSecretVaultResultV1.Success(AccountSecretValueV1(plaintext))
        } catch (_: Exception) {
            AccountSecretVaultResultV1.Failure(
                AccountSecretVaultFailureV1.CORRUPT,
            )
        } finally {
            aad.fill(0)
            ciphertext.fill(0)
        }
    }

    /**
     * Deletion requires the account protection boundary to be readable. A locked High Security
     * session therefore cannot silently destroy a Vault record.
     */
    fun delete(
        key: AccountSecretKeyV1,
    ): AccountSecretVaultResultV1<Unit> {
        val authorized =
            try {
                aeadProvider.forRead(key.accountUuid)
            } catch (_: Exception) {
                null
            } ?: return AccountSecretVaultResultV1.Failure(
                AccountSecretVaultFailureV1.UNAVAILABLE,
            )
        // Keep the primitive live through the authorization decision without exposing it.
        authorized.hashCode()
        return if (recordStore.delete(AccountSecretVaultRecordIdentityV1.forKey(key))) {
            AccountSecretVaultResultV1.Success(Unit)
        } else {
            AccountSecretVaultResultV1.Failure(
                AccountSecretVaultFailureV1.PERSISTENCE_FAILURE,
            )
        }
    }

    private companion object {
        const val MAX_SECRET_BYTES = 1024 * 1024
    }
}
