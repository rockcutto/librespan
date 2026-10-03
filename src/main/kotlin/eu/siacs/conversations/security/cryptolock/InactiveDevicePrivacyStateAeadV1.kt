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
 * Installation-bound AEAD for the inactivity/privacy control record.
 *
 * The key is deliberately separate from activation, Secure Content and the auth-bound normal
 * wrapper. Read never creates a missing key; an existing unreadable record is fail-closed.
 */
internal class InactiveDevicePrivacyStateAeadV1 {
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
            throw GeneralSecurityException("unsupported privacy state envelope")
        }
        val ivSize = envelope[1].toInt() and 0xff
        if (ivSize != IV_SIZE_BYTES || envelope.size <= 2 + ivSize) {
            throw GeneralSecurityException("malformed privacy state envelope")
        }
        val key =
            existingKey()
                ?: throw GeneralSecurityException("privacy state key unavailable")
        val iv = envelope.copyOfRange(2, 2 + ivSize)
        val protectedBytes = envelope.copyOfRange(2 + ivSize, envelope.size)
        return try {
            Cipher.getInstance(CIPHER_TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                updateAAD(associatedData)
                doFinal(protectedBytes)
            }
        } catch (error: AEADBadTagException) {
            throw GeneralSecurityException("privacy state authentication failed", error)
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

    companion object {
        val ASSOCIATED_DATA: ByteArray =
            "NCS-INACTIVE-DEVICE-PRIVACY-1".toByteArray(Charsets.US_ASCII)

        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "neocont.inactive-device.privacy-state.v1"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val TAG_BITS = 128
        private const val IV_SIZE_BYTES = 12
        private const val FORMAT_VERSION: Byte = 1
        private const val MIN_ENVELOPE_SIZE = 2 + IV_SIZE_BYTES + 16
    }
}
