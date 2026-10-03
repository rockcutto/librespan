// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.common.io.BaseEncoding
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Android Keystore backed account-scoped HMAC capability for the secure message blind index. */
class AndroidKeystoreSecureMessageSearchMacProvider : SecureMessageSearchMacProvider {
    private class AccountMacState(val mac: Mac)

    private val lock = Any()
    private val accountStates = mutableMapOf<String, AccountMacState>()
    private val keyStore: KeyStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    override fun hmacSha256(accountUuid: String, input: ByteArray): ByteArray =
        withAccountMac(accountUuid) { mac -> mac.hmacSha256(input) }

    override fun <T> withAccountMac(
        accountUuid: String,
        operation: (SecureMessageSearchMac) -> T,
    ): T {
        val state = synchronized(lock) {
            accountStates.getOrPut(accountUuid) {
                val key = loadOrCreateKey(aliasFor(accountUuid))
                AccountMacState(
                    Mac.getInstance(HMAC_ALGORITHM).apply { init(key) },
                )
            }
        }
        return synchronized(state) {
            try {
                operation(
                    SecureMessageSearchMac { input ->
                        // JCA Mac.doFinal() resets to the state established by init(key).
                        state.mac.doFinal(input)
                    },
                )
            } catch (error: Exception) {
                synchronized(lock) {
                    if (accountStates[accountUuid] === state) {
                        accountStates.remove(accountUuid)
                    }
                }
                throw error
            }
        }
    }

    private fun loadOrCreateKey(alias: String): SecretKey {
        val existing = keyStore.getKey(alias, null)
        if (existing is SecretKey) {
            return existing
        }
        val generator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun aliasFor(accountUuid: String): String {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(accountUuid.toByteArray(StandardCharsets.UTF_8))
        return ALIAS_PREFIX + BaseEncoding.base16().lowerCase().encode(digest)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val ALIAS_PREFIX = "neocont.secure-message-search.v1."
    }
}
