// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

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
 * Application-installation root wrapping AEAD backed by a non-exportable Android Keystore key.
 *
 * It is used only to protect account-scoped key-encryption material. It never exposes the key,
 * alias, provider detail, or ciphertext framing through public Secure Content APIs.
 */
internal class AndroidKeystoreSecureContentRootAead : com.google.crypto.tink.Aead {
    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, rootKey())
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        return byteArrayOf(FORMAT_VERSION, iv.size.toByte()) + iv + ciphertext
    }

    override fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        if (ciphertext.size < MIN_ENVELOPE_SIZE || ciphertext[0] != FORMAT_VERSION) {
            throw GeneralSecurityException("unsupported secure-content root envelope")
        }
        val ivSize = ciphertext[1].toInt() and 0xff
        if (ivSize != IV_SIZE_BYTES || ciphertext.size <= 2 + ivSize) {
            throw GeneralSecurityException("malformed secure-content root envelope")
        }

        val iv = ciphertext.copyOfRange(2, 2 + ivSize)
        val protectedBytes = ciphertext.copyOfRange(2 + ivSize, ciphertext.size)
        return try {
            Cipher.getInstance(CIPHER_TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, rootKey(), GCMParameterSpec(TAG_BITS, iv))
                updateAAD(associatedData)
                doFinal(protectedBytes)
            }
        } catch (exception: AEADBadTagException) {
            throw GeneralSecurityException("secure-content root envelope rejected", exception)
        }
    }

    @Synchronized
    private fun rootKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
            load(null)
        }
        val existing = keyStore.getEntry(ROOT_ALIAS, null) as? KeyStore.SecretKeyEntry
        return existing?.secretKey ?: generateRootKey()
    }

    private fun generateRootKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        val spec = KeyGenParameterSpec.Builder(
            ROOT_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(ROOT_KEY_SIZE_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val ROOT_ALIAS = "neocont.secure-content.root.v1"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val ROOT_KEY_SIZE_BITS = 256
        const val TAG_BITS = 128
        const val IV_SIZE_BYTES = 12
        const val FORMAT_VERSION: Byte = 1
        const val MIN_ENVELOPE_SIZE = 2 + IV_SIZE_BYTES + 16
    }
}
