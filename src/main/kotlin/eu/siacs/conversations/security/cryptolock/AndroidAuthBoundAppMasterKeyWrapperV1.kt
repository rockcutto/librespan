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
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import androidx.annotation.RequiresApi
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class NormalAppMasterKeyFailure {
    UNSUPPORTED_PLATFORM,
    DEVICE_CREDENTIAL_UNAVAILABLE,
    KEY_ALREADY_EXISTS,
    KEY_MISSING,
    KEY_INVALIDATED,
    AUTHENTICATION_REQUIRED,
    AUTHENTICATION_FAILED,
    CRYPTO_FAILURE,
}

sealed class NormalAppMasterKeyResult<out T> {
    data class Success<T>(val value: T) : NormalAppMasterKeyResult<T>()

    data class Failure(
        val reason: NormalAppMasterKeyFailure,
        val diagnosticCode: String? = null,
    ) : NormalAppMasterKeyResult<Nothing>()
}

internal fun classifyNormalKeyFailure(error: Throwable): NormalAppMasterKeyFailure {
    var cause: Throwable? = error
    var depth = 0
    while (cause != null && depth++ < 8) {
        when (cause) {
            is KeyPermanentlyInvalidatedException,
            is UnrecoverableKeyException -> return NormalAppMasterKeyFailure.KEY_INVALIDATED
            is UserNotAuthenticatedException ->
                return NormalAppMasterKeyFailure.AUTHENTICATION_REQUIRED
            is InvalidKeyException -> return NormalAppMasterKeyFailure.KEY_INVALIDATED
        }
        if (cause.cause === cause) break
        cause = cause.cause
    }
    return NormalAppMasterKeyFailure.CRYPTO_FAILURE
}

/**
 * Android 11+ auth-per-use normal wrapper for the App Master Key.
 *
 * The Keystore key is non-exportable and accepts either BIOMETRIC_STRONG or DEVICE_CREDENTIAL for
 * each cryptographic operation. Because DEVICE_CREDENTIAL is part of the key policy, ordinary
 * biometric enrollment changes do not form the sole recovery path; PIN/pattern/password remains an
 * allowed authenticator. Permanent Keystore invalidation is surfaced distinctly so the caller can
 * transition to RECOVERY_REQUIRED instead of generating a replacement master key.
 */
@RequiresApi(Build.VERSION_CODES.R)
class AndroidAuthBoundAppMasterKeyWrapperV1(
    context: Context,
) {
    private val applicationContext = context.applicationContext

    fun supportStatus(): NormalAppMasterKeyFailure? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return NormalAppMasterKeyFailure.UNSUPPORTED_PLATFORM
        }
        val keyguard =
            applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                ?: return NormalAppMasterKeyFailure.DEVICE_CREDENTIAL_UNAVAILABLE
        if (!keyguard.isDeviceSecure) {
            return NormalAppMasterKeyFailure.DEVICE_CREDENTIAL_UNAVAILABLE
        }
        // canAuthenticate() describes prompt availability at this instant, not the state of an
        // existing Keystore key. In particular an enrollment change must be diagnosed by loading
        // and initializing that key. DEVICE_CREDENTIAL remains an allowed authenticator even when
        // no strong biometric is enrolled.
        return null
    }

    /**
     * Creates the normal wrapper key only for a fresh, not-yet-active profile setup.
     *
     * Existing aliases are never overwritten. Active-profile recovery must repair the wrapper via
     * the recovery flow and may not call this method as a silent replacement path.
     */
    fun createFreshSetupKey(): NormalAppMasterKeyResult<Unit> {
        supportStatus()?.let { return NormalAppMasterKeyResult.Failure(it) }
        return try {
            val keyStore = keyStore()
            if (keyStore.containsAlias(KEY_ALIAS)) {
                return NormalAppMasterKeyResult.Failure(
                    NormalAppMasterKeyFailure.KEY_ALREADY_EXISTS,
                )
            }
            val generator =
                KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    KEYSTORE_PROVIDER,
                )
            val spec =
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
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
            generator.generateKey()
            NormalAppMasterKeyResult.Success(Unit)
        } catch (_: GeneralSecurityException) {
            NormalAppMasterKeyResult.Failure(NormalAppMasterKeyFailure.CRYPTO_FAILURE)
        } catch (_: RuntimeException) {
            NormalAppMasterKeyResult.Failure(NormalAppMasterKeyFailure.CRYPTO_FAILURE)
        }
    }

    fun prepareWrap(): NormalAppMasterKeyResult<WrapOperation> {
        supportStatus()?.let { return NormalAppMasterKeyResult.Failure(it) }
        val key =
            when (val keyResult = loadExistingKey()) {
                is NormalAppMasterKeyResult.Success -> keyResult.value
                is NormalAppMasterKeyResult.Failure -> return keyResult
            }
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            if (iv == null || iv.size != NormalWrappedAppMasterKeyRecordV1.IV_BYTES) {
                iv?.fill(0)
                return NormalAppMasterKeyResult.Failure(
                    NormalAppMasterKeyFailure.CRYPTO_FAILURE,
                )
            }
            val header = NormalWrappedAppMasterKeyRecordCodecV1.authenticatedHeader(iv)
            NormalAppMasterKeyResult.Success(
                WrapOperation(
                    cipher = cipher,
                    iv = iv,
                    header = header,
                ),
            ).also {
                header.fill(0)
            }
        } catch (error: GeneralSecurityException) {
            NormalAppMasterKeyResult.Failure(classifyNormalKeyFailure(error))
        } catch (error: ProviderException) {
            NormalAppMasterKeyResult.Failure(classifyNormalKeyFailure(error))
        } catch (error: RuntimeException) {
            NormalAppMasterKeyResult.Failure(classifyNormalKeyFailure(error))
        }
    }

    fun prepareUnwrap(
        record: NormalWrappedAppMasterKeyRecordV1,
    ): NormalAppMasterKeyResult<UnwrapOperation> {
        supportStatus()?.let { return NormalAppMasterKeyResult.Failure(it) }
        val key =
            when (val keyResult = loadExistingKey()) {
                is NormalAppMasterKeyResult.Success -> keyResult.value
                is NormalAppMasterKeyResult.Failure -> return keyResult
            }
        val iv = record.iv()
        val header = record.authenticatedHeader()
        val ciphertext = record.ciphertext()
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_BITS, iv),
            )
            NormalAppMasterKeyResult.Success(
                UnwrapOperation(
                    cipher = cipher,
                    iv = iv,
                    header = header,
                    ciphertext = ciphertext,
                ),
            )
        } catch (error: GeneralSecurityException) {
            ciphertext.fill(0)
            NormalAppMasterKeyResult.Failure(classifyNormalKeyFailure(error))
        } catch (error: ProviderException) {
            ciphertext.fill(0)
            NormalAppMasterKeyResult.Failure(classifyNormalKeyFailure(error))
        } catch (error: RuntimeException) {
            ciphertext.fill(0)
            NormalAppMasterKeyResult.Failure(classifyNormalKeyFailure(error))
        } finally {
            iv.fill(0)
            header.fill(0)
        }
    }

    fun hasNormalKey(): Boolean =
        try {
            keyStore().containsAlias(KEY_ALIAS)
        } catch (_: GeneralSecurityException) {
            false
        }

    /**
     * Rollback-only cleanup for a setup transaction that has not reached ACTIVE.
     *
     * Callers must never use this as active-profile recovery; active KEY_MISSING/KEY_INVALIDATED
     * transitions to RECOVERY_REQUIRED instead.
     */
    internal fun deleteSetupKeyBeforeActivation(): NormalAppMasterKeyResult<Unit> =
        deleteKeyEntry()

    /**
     * Removes the normal App Master Key wrapping alias only after reverse migration has durably
     * switched account-key authority back to root_v1 and the ACTIVE profile record is gone.
     */
    internal fun deleteActiveKeyAfterDeactivation(): NormalAppMasterKeyResult<Unit> =
        deleteKeyEntry()

    /**
     * Recovery-only replacement. The recovery wrapper must already have authenticated the same
     * App Master Key before callers invoke this method.
     */
    internal fun replaceKeyForRecovery(): NormalAppMasterKeyResult<Unit> {
        // Never destroy the current alias before establishing that the platform can create a new
        // auth-bound key under the current device credential policy.
        supportStatus()?.let { return NormalAppMasterKeyResult.Failure(it) }
        when (val deleted = deleteKeyEntry()) {
            is NormalAppMasterKeyResult.Failure -> return deleted
            is NormalAppMasterKeyResult.Success -> Unit
        }
        return createFreshSetupKey()
    }

    private fun deleteKeyEntry(): NormalAppMasterKeyResult<Unit> =
        try {
            val store = keyStore()
            if (store.containsAlias(KEY_ALIAS)) {
                store.deleteEntry(KEY_ALIAS)
            }
            NormalAppMasterKeyResult.Success(Unit)
        } catch (_: GeneralSecurityException) {
            NormalAppMasterKeyResult.Failure(NormalAppMasterKeyFailure.CRYPTO_FAILURE)
        } catch (_: RuntimeException) {
            NormalAppMasterKeyResult.Failure(NormalAppMasterKeyFailure.CRYPTO_FAILURE)
        }

    private fun loadExistingKey(): NormalAppMasterKeyResult<SecretKey> =
        try {
            val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
            if (key == null) {
                NormalAppMasterKeyResult.Failure(NormalAppMasterKeyFailure.KEY_MISSING)
            } else {
                NormalAppMasterKeyResult.Success(key)
            }
        } catch (error: GeneralSecurityException) {
            NormalAppMasterKeyResult.Failure(classifyNormalKeyFailure(error))
        } catch (error: ProviderException) {
            NormalAppMasterKeyResult.Failure(classifyNormalKeyFailure(error))
        } catch (error: RuntimeException) {
            NormalAppMasterKeyResult.Failure(classifyNormalKeyFailure(error))
        }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    class WrapOperation internal constructor(
        private var cipher: Cipher?,
        iv: ByteArray,
        header: ByteArray,
    ) {
        private val ivValue = iv.copyOf()
        private val headerValue = header.copyOf()
        private var consumed = false

        fun cryptoObject(): BiometricPrompt.CryptoObject {
            check(!consumed) { "Normal App Master Key wrap operation is consumed" }
            return BiometricPrompt.CryptoObject(requireNotNull(cipher))
        }

        fun complete(
            appMasterKey: AppMasterKey,
        ): NormalAppMasterKeyResult<NormalWrappedAppMasterKeyRecordV1> =
            completeWithCipher(appMasterKey, null)

        fun completeAuthenticated(
            appMasterKey: AppMasterKey,
            authenticatedCryptoObject: BiometricPrompt.CryptoObject?,
        ): NormalAppMasterKeyResult<NormalWrappedAppMasterKeyRecordV1> =
            completeWithCipher(appMasterKey, authenticatedCryptoObject)

        private fun completeWithCipher(
            appMasterKey: AppMasterKey,
            authenticatedCryptoObject: BiometricPrompt.CryptoObject?,
        ): NormalAppMasterKeyResult<NormalWrappedAppMasterKeyRecordV1> {
            check(!consumed) { "Normal App Master Key wrap operation is consumed" }
            consumed = true
            val preparedCipher = requireNotNull(cipher)
            cipher = null
            // BiometricPrompt returns the CryptoObject associated with the successful
            // authenticate(CryptoObject, ...) transaction. For an authenticated completion use
            // that Cipher directly; Android Keystore binds authorization to that operation.
            val activeCipher =
                if (authenticatedCryptoObject != null) {
                    authenticatedCryptoObject.cipher
                        ?: run {
                            ivValue.fill(0)
                            headerValue.fill(0)
                            return NormalAppMasterKeyResult.Failure(
                                NormalAppMasterKeyFailure.CRYPTO_FAILURE,
                                "AUTH_CIPHER_MISSING",
                            )
                        }
                } else {
                    preparedCipher
                }

            return try {
                // For auth-per-use Keystore keys, do not feed AAD into the protected operation
                // before BiometricPrompt has authorized its operation handle. Some KeyMint
                // implementations defer updateAAD and then reject doFinal as unauthenticated.
                activeCipher.updateAAD(headerValue)
                val ciphertext =
                    appMasterKey.useCopy { plaintext ->
                        activeCipher.doFinal(plaintext)
                    }
                if (ciphertext.size != NormalWrappedAppMasterKeyRecordV1.CIPHERTEXT_BYTES) {
                    ciphertext.fill(0)
                    headerValue.fill(0)
                    return NormalAppMasterKeyResult.Failure(
                        NormalAppMasterKeyFailure.CRYPTO_FAILURE,
                        "CIPHERTEXT_SIZE",
                    )
                }
                NormalAppMasterKeyResult.Success(
                    NormalWrappedAppMasterKeyRecordV1(
                        iv = ivValue,
                        ciphertext = ciphertext,
                    ),
                ).also {
                    ciphertext.fill(0)
                    ivValue.fill(0)
                    headerValue.fill(0)
                }
            } catch (_: UserNotAuthenticatedException) {
                ivValue.fill(0)
                headerValue.fill(0)
                NormalAppMasterKeyResult.Failure(
                    NormalAppMasterKeyFailure.AUTHENTICATION_REQUIRED,
                    "USER_NOT_AUTHENTICATED",
                )
            } catch (_: KeyPermanentlyInvalidatedException) {
                ivValue.fill(0)
                headerValue.fill(0)
                NormalAppMasterKeyResult.Failure(
                    NormalAppMasterKeyFailure.KEY_INVALIDATED,
                    "KEY_INVALIDATED",
                )
            } catch (error: IllegalBlockSizeException) {
                ivValue.fill(0)
                headerValue.fill(0)
                NormalAppMasterKeyResult.Failure(
                    classifyNormalKeyFailure(error),
                    "ILLEGAL_BLOCK_SIZE",
                )
            } catch (error: BadPaddingException) {
                ivValue.fill(0)
                headerValue.fill(0)
                NormalAppMasterKeyResult.Failure(
                    classifyNormalKeyFailure(error),
                    "BAD_PADDING",
                )
            } catch (error: GeneralSecurityException) {
                ivValue.fill(0)
                headerValue.fill(0)
                NormalAppMasterKeyResult.Failure(
                    classifyNormalKeyFailure(error),
                    "GENERAL_SECURITY_" + error.javaClass.simpleName,
                )
            } catch (error: RuntimeException) {
                ivValue.fill(0)
                headerValue.fill(0)
                NormalAppMasterKeyResult.Failure(
                    classifyNormalKeyFailure(error),
                    "RUNTIME_" + error.javaClass.simpleName,
                )
            }
        }

        override fun toString(): String = "WrapOperation([REDACTED])"
    }

    class UnwrapOperation internal constructor(
        private var cipher: Cipher?,
        iv: ByteArray,
        header: ByteArray,
        ciphertext: ByteArray,
    ) {
        private val ivValue = iv.copyOf()
        private val headerValue = header.copyOf()
        private val ciphertextValue = ciphertext.copyOf()
        private var consumed = false

        fun cryptoObject(): BiometricPrompt.CryptoObject {
            check(!consumed) { "Normal App Master Key unwrap operation is consumed" }
            return BiometricPrompt.CryptoObject(requireNotNull(cipher))
        }

        fun complete(): NormalAppMasterKeyResult<AppMasterKey> =
            completeWithCipher(null)

        fun completeAuthenticated(
            authenticatedCryptoObject: BiometricPrompt.CryptoObject?,
        ): NormalAppMasterKeyResult<AppMasterKey> =
            completeWithCipher(authenticatedCryptoObject)

        private fun completeWithCipher(
            authenticatedCryptoObject: BiometricPrompt.CryptoObject?,
        ): NormalAppMasterKeyResult<AppMasterKey> {
            check(!consumed) { "Normal App Master Key unwrap operation is consumed" }
            consumed = true
            val preparedCipher = requireNotNull(cipher)
            cipher = null
            // Use the Cipher returned by the successful BiometricPrompt transaction. The
            // platform/Keystore operation handle is the authentication binding; no secondary IV
            // identity check is required here.
            val activeCipher =
                if (authenticatedCryptoObject != null) {
                    authenticatedCryptoObject.cipher
                        ?: run {
                            ciphertextValue.fill(0)
                            ivValue.fill(0)
                            headerValue.fill(0)
                            return NormalAppMasterKeyResult.Failure(
                                NormalAppMasterKeyFailure.CRYPTO_FAILURE,
                                "AUTH_CIPHER_MISSING",
                            )
                        }
                } else {
                    preparedCipher
                }

            return try {
                activeCipher.updateAAD(headerValue)
                val plaintext = activeCipher.doFinal(ciphertextValue)
                ciphertextValue.fill(0)
                ivValue.fill(0)
                headerValue.fill(0)
                if (plaintext.size != AppMasterKey.BYTE_LENGTH) {
                    plaintext.fill(0)
                    return NormalAppMasterKeyResult.Failure(
                        NormalAppMasterKeyFailure.CRYPTO_FAILURE,
                    )
                }
                NormalAppMasterKeyResult.Success(AppMasterKey.takeOwnership(plaintext))
            } catch (_: AEADBadTagException) {
                ciphertextValue.fill(0)
                ivValue.fill(0)
                headerValue.fill(0)
                NormalAppMasterKeyResult.Failure(
                    NormalAppMasterKeyFailure.AUTHENTICATION_FAILED,
                    "AEAD_BAD_TAG",
                )
            } catch (_: UserNotAuthenticatedException) {
                ciphertextValue.fill(0)
                ivValue.fill(0)
                headerValue.fill(0)
                NormalAppMasterKeyResult.Failure(
                    NormalAppMasterKeyFailure.AUTHENTICATION_REQUIRED,
                    "USER_NOT_AUTHENTICATED",
                )
            } catch (_: KeyPermanentlyInvalidatedException) {
                ciphertextValue.fill(0)
                ivValue.fill(0)
                headerValue.fill(0)
                NormalAppMasterKeyResult.Failure(
                    NormalAppMasterKeyFailure.KEY_INVALIDATED,
                    "KEY_INVALIDATED",
                )
            } catch (error: IllegalBlockSizeException) {
                ciphertextValue.fill(0)
                ivValue.fill(0)
                headerValue.fill(0)
                NormalAppMasterKeyResult.Failure(
                    classifyNormalKeyFailure(error),
                    "ILLEGAL_BLOCK_SIZE",
                )
            } catch (error: BadPaddingException) {
                ciphertextValue.fill(0)
                ivValue.fill(0)
                headerValue.fill(0)
                NormalAppMasterKeyResult.Failure(
                    classifyNormalKeyFailure(error),
                    "BAD_PADDING",
                )
            } catch (error: GeneralSecurityException) {
                ciphertextValue.fill(0)
                ivValue.fill(0)
                headerValue.fill(0)
                NormalAppMasterKeyResult.Failure(
                    classifyNormalKeyFailure(error),
                    "GENERAL_SECURITY_" + error.javaClass.simpleName,
                )
            } catch (error: RuntimeException) {
                ciphertextValue.fill(0)
                ivValue.fill(0)
                headerValue.fill(0)
                NormalAppMasterKeyResult.Failure(
                    classifyNormalKeyFailure(error),
                    "RUNTIME_" + error.javaClass.simpleName,
                )
            }
        }

        override fun toString(): String = "UnwrapOperation([REDACTED])"
    }

    companion object {
        const val MIN_API_LEVEL: Int = Build.VERSION_CODES.R
        const val ALLOWED_AUTHENTICATORS: Int =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "neocont.app-master.normal.v1"
        private const val KEY_SIZE_BITS = 256
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
