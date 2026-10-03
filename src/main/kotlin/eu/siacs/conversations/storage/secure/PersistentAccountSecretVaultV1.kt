// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import android.content.Context
import android.util.Base64

/**
 * Android persistence adapter for Account Secret Vault v1.
 *
 * SharedPreferences contains only opaque AEAD ciphertext under hashed logical record identifiers.
 * The AEAD itself is the existing account primitive from PersistentSecureContentKeyMaterialStore,
 * so ROOT_V1 <-> GATED_V1 account migration automatically changes Vault availability without
 * introducing another master key.
 */
internal class PersistentAccountSecretVaultV1(
    context: Context,
    private val keyMaterialStore: PersistentSecureContentKeyMaterialStore =
        PersistentSecureContentKeyMaterialStore(context.applicationContext),
) {
    private val applicationContext = context.applicationContext
    private val preferences =
        applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val core =
        AccountSecretVaultV1(
            aeadProvider =
                object : AccountSecretVaultAeadProviderV1 {
                    override fun forWrite(accountUuid: String) =
                        try {
                            keyMaterialStore.accountSecretAeadForWrite(accountUuid)
                        } catch (_: Exception) {
                            null
                        }

                    override fun forRead(accountUuid: String) =
                        try {
                            keyMaterialStore.accountSecretAeadForRead(accountUuid)
                        } catch (_: Exception) {
                            null
                        }
                },
            recordStore =
                object : AccountSecretVaultRecordStoreV1 {
                    override fun read(recordId: String): ByteArray? {
                        val encoded = preferences.getString(recordId, null) ?: return null
                        return try {
                            Base64.decode(encoded, Base64.NO_WRAP)
                        } catch (_: IllegalArgumentException) {
                            // Preserve "record exists but corrupt" semantics for the core.
                            byteArrayOf(0)
                        }
                    }

                    override fun write(recordId: String, ciphertext: ByteArray): Boolean {
                        val encoded = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
                        return preferences.edit().putString(recordId, encoded).commit()
                    }

                    override fun delete(recordId: String): Boolean =
                        preferences.edit().remove(recordId).commit()
                },
        )

    fun isWriteAvailable(accountUuid: String): Boolean =
        try {
            keyMaterialStore.accountSecretAeadForWrite(accountUuid)
            true
        } catch (_: Exception) {
            false
        }

    fun put(
        key: AccountSecretKeyV1,
        plaintext: ByteArray,
    ): AccountSecretVaultResultV1<Unit> = core.put(key, plaintext)

    fun open(
        key: AccountSecretKeyV1,
    ): AccountSecretVaultResultV1<AccountSecretValueV1?> = core.open(key)

    fun delete(
        key: AccountSecretKeyV1,
    ): AccountSecretVaultResultV1<Unit> = core.delete(key)

    fun deleteAccountAuthenticationSecrets(accountUuid: String): Boolean {
        val keys =
            listOf(
                AccountSecretKeyV1(accountUuid, AccountSecretTypeV1.XMPP_PASSWORD),
                AccountSecretKeyV1(accountUuid, AccountSecretTypeV1.FAST_TOKEN),
                AccountSecretKeyV1(accountUuid, AccountSecretTypeV1.PRE_AUTH_REGISTRATION_TOKEN),
            )
        return keys.all { key ->
            when (core.delete(key)) {
                is AccountSecretVaultResultV1.Success -> true
                is AccountSecretVaultResultV1.Failure -> false
            }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "account_secret_vault_v1"
    }
}
