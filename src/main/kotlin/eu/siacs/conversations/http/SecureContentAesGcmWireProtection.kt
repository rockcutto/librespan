package eu.siacs.conversations.http

import java.io.InputStream
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.io.CipherInputStream
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * Per-upload XEP-0454 transformation. These keys belong to the wire message only and are never
 * stored as Secure Content key material.
 */
class SecureContentAesGcmWireProtection(
    key: ByteArray,
    iv: ByteArray,
) {
    private val key = key.clone()
    private val iv = iv.clone()

    init {
        require(this.key.size == 32) { "XEP-0454 AES key must be 32 bytes" }
        require(this.iv.size == 12) { "XEP-0454 GCM IV must be 12 bytes" }
    }

    fun expectedWireSize(plaintextSizeBytes: Long): Long {
        require(plaintextSizeBytes >= 0) { "plaintextSizeBytes must not be negative" }
        return Math.addExact(plaintextSizeBytes, TAG_SIZE_BYTES)
    }

    fun protect(plaintext: InputStream): InputStream {
        val cipher = GCMBlockCipher(AESEngine())
        cipher.init(
            true,
            AEADParameters(
                KeyParameter(key),
                TAG_SIZE_BITS,
                iv,
            ),
        )
        return CipherInputStream(plaintext, cipher)
    }

    private companion object {
        const val TAG_SIZE_BITS = 128
        const val TAG_SIZE_BYTES = 16L
    }
}
