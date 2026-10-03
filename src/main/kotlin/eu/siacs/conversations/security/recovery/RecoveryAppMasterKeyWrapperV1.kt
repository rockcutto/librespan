// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.security.recovery

import eu.siacs.conversations.security.cryptolock.AppMasterKey
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Recovery wrapper for the App Master Key.
 *
 * The record is portable across Keystore invalidation because the KEK is reconstructed only from
 * the 12-word recovery secret plus public record metadata. This does not persist the phrase, secret,
 * KEK or plaintext App Master Key.
 */
object RecoveryAppMasterKeyWrapperV1 {
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val AES = "AES"
    private const val TAG_BITS = RecoveryWrappedAppMasterKeyRecordV1.TAG_BYTES * 8

    fun wrap(
        recoverySecret: RecoverySecret,
        appMasterKey: AppMasterKey,
        random: SecureRandom = SecureRandom(),
        phraseFormat: RecoveryPhraseFormat = RecoveryPhraseFormats.BIP39_EN_V1,
    ): RecoveryWrappedAppMasterKeyRecordV1 {
        val salt = ByteArray(RecoveryKekKdfV1.SALT_BYTES)
        val nonce = ByteArray(RecoveryWrappedAppMasterKeyRecordV1.NONCE_BYTES)
        random.nextBytes(salt)
        random.nextBytes(nonce)

        val header =
            RecoveryWrappedAppMasterKeyRecordCodecV1.authenticatedHeader(
                kdfSalt = salt,
                nonce = nonce,
                phraseFormat = phraseFormat,
            )
        val kek = RecoveryKekKdfV1.derive(recoverySecret, salt, phraseFormat)
        return try {
            val ciphertext =
                kek.useCopy { kekBytes ->
                    appMasterKey.useCopy { plaintext ->
                        val cipher = Cipher.getInstance(CIPHER)
                        cipher.init(
                            Cipher.ENCRYPT_MODE,
                            SecretKeySpec(kekBytes, AES),
                            GCMParameterSpec(TAG_BITS, nonce),
                        )
                        cipher.updateAAD(header)
                        cipher.doFinal(plaintext)
                    }
                }
            RecoveryWrappedAppMasterKeyRecordV1(
                kdfSalt = salt,
                nonce = nonce,
                ciphertext = ciphertext,
                recoveryPhraseFormat = phraseFormat,
            ).also {
                ciphertext.fill(0)
            }
        } finally {
            kek.close()
            header.fill(0)
            salt.fill(0)
            nonce.fill(0)
        }
    }

    @Throws(RecoveryUnwrapException::class)
    fun unwrap(
        recoverySecret: RecoverySecret,
        record: RecoveryWrappedAppMasterKeyRecordV1,
    ): AppMasterKey {
        val salt = record.kdfSalt()
        val nonce = record.nonce()
        val ciphertext = record.ciphertext()
        val header = record.authenticatedHeader()
        val kek = RecoveryKekKdfV1.derive(
            recoverySecret,
            salt,
            record.recoveryPhraseFormat(),
        )

        try {
            val plaintext =
                kek.useCopy { kekBytes ->
                    val cipher = Cipher.getInstance(CIPHER)
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(kekBytes, AES),
                        GCMParameterSpec(TAG_BITS, nonce),
                    )
                    cipher.updateAAD(header)
                    cipher.doFinal(ciphertext)
                }
            if (plaintext.size != AppMasterKey.BYTE_LENGTH) {
                plaintext.fill(0)
                throw RecoveryUnwrapException("Recovery wrapper authentication failed")
            }
            return AppMasterKey.takeOwnership(plaintext)
        } catch (error: RecoveryUnwrapException) {
            throw error
        } catch (_: GeneralSecurityException) {
            // Do not distinguish wrong recovery secret from record tampering.
            throw RecoveryUnwrapException("Recovery wrapper authentication failed")
        } finally {
            kek.close()
            salt.fill(0)
            nonce.fill(0)
            ciphertext.fill(0)
            header.fill(0)
        }
    }
}

class RecoveryUnwrapException(message: String) : Exception(message)
