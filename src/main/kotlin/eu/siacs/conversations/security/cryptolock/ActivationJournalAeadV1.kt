// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.security.cryptolock

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Installation-bound AEAD used only for the activation journal.
 *
 * It is intentionally separate from the Secure Content v1 root and from the auth-bound normal
 * App Master Key wrapper. Read never creates a missing key: loss of the installation journal key
 * makes an existing transaction unreadable/fail-closed rather than manufacturing a replacement.
 */
internal class ActivationJournalAeadV1 {
    fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyForWrite())
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        return byteArrayOf(FORMAT_VERSION, iv.size.toByte()) + iv + ciphertext
    }

    @Throws(GeneralSecurityException::class)
    fun decrypt(
        envelope: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        if (envelope.size < MIN_ENVELOPE_SIZE || envelope[0] != FORMAT_VERSION) {
            throw GeneralSecurityException("unsupported activation journal envelope")
        }
        val ivSize = envelope[1].toInt() and 0xff
        if (ivSize != IV_SIZE_BYTES || envelope.size <= 2 + ivSize) {
            throw GeneralSecurityException("malformed activation journal envelope")
        }
        val key =
            existingKey()
                ?: throw GeneralSecurityException("activation journal key unavailable")
        val iv = envelope.copyOfRange(2, 2 + ivSize)
        val protectedBytes = envelope.copyOfRange(2 + ivSize, envelope.size)
        return try {
            Cipher.getInstance(CIPHER_TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                updateAAD(associatedData)
                doFinal(protectedBytes)
            }
        } catch (error: AEADBadTagException) {
            throw GeneralSecurityException("activation journal authentication failed", error)
        } finally {
            iv.fill(0)
            protectedBytes.fill(0)
        }
    }

    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    @Synchronized
    private fun keyForWrite(): SecretKey =
        existingKey() ?: run {
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
                    .build()
            generator.init(spec)
            generator.generateKey()
        }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "neocont.inactive-device.activation-journal.v1"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_BITS = 128
        const val IV_SIZE_BYTES = 12
        const val FORMAT_VERSION: Byte = 1
        const val MIN_ENVELOPE_SIZE = 2 + IV_SIZE_BYTES + 16
    }
}
