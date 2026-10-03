// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

import com.google.crypto.tink.Aead
import eu.siacs.conversations.security.cryptolock.AppMasterKey
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Account-envelope AEAD derived from the App Master Key.
 *
 * A distinct 256-bit AES key is deterministically derived for each local account with HMAC-SHA256
 * domain separation. The derived bytes are bounded to this object and best-effort zeroed on close.
 */
internal class AppMasterKeyAccountAeadV1 private constructor(
    private val derivedKey: ByteArray,
    private val random: SecureRandom,
) : Aead, AutoCloseable {

    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val iv = ByteArray(IV_BYTES)
        random.nextBytes(iv)
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(derivedKey, KEY_ALGORITHM),
                GCMParameterSpec(TAG_BITS, iv),
            )
            cipher.updateAAD(associatedData)
            val ciphertext = cipher.doFinal(plaintext)
            byteArrayOf(FORMAT_VERSION, iv.size.toByte()) + iv + ciphertext
        } finally {
            iv.fill(0)
        }
    }

    override fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        if (ciphertext.size < MIN_ENVELOPE_BYTES || ciphertext[0] != FORMAT_VERSION) {
            throw GeneralSecurityException("unsupported gated account envelope")
        }
        val ivSize = ciphertext[1].toInt() and 0xff
        if (ivSize != IV_BYTES || ciphertext.size <= 2 + ivSize) {
            throw GeneralSecurityException("malformed gated account envelope")
        }
        val iv = ciphertext.copyOfRange(2, 2 + ivSize)
        val protectedBytes = ciphertext.copyOfRange(2 + ivSize, ciphertext.size)
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(derivedKey, KEY_ALGORITHM),
                GCMParameterSpec(TAG_BITS, iv),
            )
            cipher.updateAAD(associatedData)
            cipher.doFinal(protectedBytes)
        } catch (error: AEADBadTagException) {
            throw GeneralSecurityException("gated account envelope rejected", error)
        } finally {
            iv.fill(0)
            protectedBytes.fill(0)
        }
    }

    override fun close() {
        derivedKey.fill(0)
    }

    companion object {
        private const val HMAC = "HmacSHA256"
        private const val KEY_ALGORITHM = "AES"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val FORMAT_VERSION: Byte = 1
        private const val MIN_ENVELOPE_BYTES = 2 + IV_BYTES + 16

        fun fromMasterKey(
            masterKey: AppMasterKey,
            accountUuid: String,
            random: SecureRandom = SecureRandom(),
        ): AppMasterKeyAccountAeadV1 {
            require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
            val info = SecureContentAssociatedData.forAppMasterAccountKek(accountUuid)
            val derived =
                masterKey.useCopy { master ->
                    val mac = Mac.getInstance(HMAC)
                    mac.init(SecretKeySpec(master, HMAC))
                    mac.doFinal(info)
                }
            info.fill(0)
            return AppMasterKeyAccountAeadV1(derived, random)
        }
    }
}
