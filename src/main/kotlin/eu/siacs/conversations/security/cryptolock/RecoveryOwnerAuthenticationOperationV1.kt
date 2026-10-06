// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.security.cryptolock

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

/**
 * One-shot, Keystore-backed proof of device-owner authentication for High Security recovery.
 *
 * The normal App Master Key wrapping key may be missing or invalid when recovery starts, so the
 * recovery owner gate cannot rely on it. This helper creates a temporary auth-per-use Keystore key
 * and requires the exact Cipher operation handed to BiometricPrompt to complete successfully before
 * recovery is allowed to replace the normal wrapping key.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal class RecoveryOwnerAuthenticationOperationV1 private constructor(
    private var cipher: Cipher?,
    private var challenge: ByteArray?,
) : AutoCloseable {
    private var consumed = false

    fun cryptoObject(): BiometricPrompt.CryptoObject {
        check(!consumed) { "Recovery owner authentication operation is consumed" }
        return BiometricPrompt.CryptoObject(requireNotNull(cipher))
    }

    fun complete(authenticatedCryptoObject: BiometricPrompt.CryptoObject?): Boolean {
        check(!consumed) { "Recovery owner authentication operation is consumed" }
        consumed = true
        val preparedCipher = cipher
        val proof = challenge
        cipher = null
        challenge = null
        return try {
            val authenticatedCipher = authenticatedCryptoObject?.cipher
            if (preparedCipher == null || proof == null || authenticatedCipher !== preparedCipher) {
                false
            } else {
                val output = authenticatedCipher.doFinal(proof)
                output.fill(0)
                true
            }
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: ProviderException) {
            false
        } catch (_: RuntimeException) {
            false
        } finally {
            proof?.fill(0)
            deleteGateKey()
        }
    }

    override fun close() {
        if (!consumed) {
            consumed = true
            cipher = null
            challenge?.fill(0)
            challenge = null
        }
        deleteGateKey()
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "librespan.recovery-owner-auth.v1"
        private const val KEY_SIZE_BITS = 256
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val CHALLENGE_BYTES = 32

        fun prepare(context: Context): RecoveryOwnerAuthenticationOperationV1? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return null
            }
            val keyguard =
                context.applicationContext.getSystemService(Context.KEYGUARD_SERVICE)
                    as? KeyguardManager ?: return null
            if (!keyguard.isDeviceSecure) {
                return null
            }

            return try {
                deleteGateKey()
                val generator =
                    KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        KEYSTORE_PROVIDER,
                    )
                val spec =
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT,
                    )
                        .setKeySize(KEY_SIZE_BITS)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setUserAuthenticationRequired(true)
                        .setUserAuthenticationParameters(
                            0,
                            KeyProperties.AUTH_BIOMETRIC_STRONG or
                                KeyProperties.AUTH_DEVICE_CREDENTIAL,
                        )
                        .build()
                generator.init(spec)
                val key = generator.generateKey()

                val preparedCipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                preparedCipher.init(Cipher.ENCRYPT_MODE, key)
                val proof = ByteArray(CHALLENGE_BYTES)
                SecureRandom().nextBytes(proof)
                RecoveryOwnerAuthenticationOperationV1(preparedCipher, proof)
            } catch (_: GeneralSecurityException) {
                deleteGateKey()
                null
            } catch (_: ProviderException) {
                deleteGateKey()
                null
            } catch (_: RuntimeException) {
                deleteGateKey()
                null
            }
        }

        private fun deleteGateKey() {
            try {
                val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    keyStore.deleteEntry(KEY_ALIAS)
                }
            } catch (_: GeneralSecurityException) {
            } catch (_: RuntimeException) {
            }
        }
    }
}
