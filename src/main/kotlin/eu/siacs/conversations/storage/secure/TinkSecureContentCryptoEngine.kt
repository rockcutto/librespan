// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException

/**
 * Internal key-material capability understood by the Tink adapter.
 *
 * The Tink keyset never crosses the public SecureContentKeyMaterialHandle contract. Persistent
 * serialization/encryption remains KeyMaterialStore responsibility.
 */
internal interface TinkSecureContentKeyMaterialHandle : SecureContentKeyMaterialHandle {
    val keysetHandle: KeysetHandle
}

/**
 * S5.4/S5.5 CryptoEngine implementation for Tink Streaming AEAD.
 *
 * The engine does not allocate keys, resolve material, open a filesystem location, own a blob
 * reference, or publish lifecycle state. It accepts only Store-controlled streams and the exact
 * validated SecureContentCryptoContext.
 */
class TinkSecureContentCryptoEngine : SecureContentCryptoEngine {
    override fun beginWrite(
        context: SecureContentCryptoContext,
        keyMaterial: SecureContentKeyMaterialHandle,
        protectedSink: SecureContentProtectedByteSink,
    ): SecureContentCryptoResult<SecureContentCryptoWriter> {
        if (context.cryptoVersion != 1) {
            return SecureContentCryptoResult.Failure(SecureContentCryptoFailure.UNSUPPORTED_VERSION)
        }
        val tinkHandle = keyMaterial as? TinkSecureContentKeyMaterialHandle
            ?: return SecureContentCryptoResult.Failure(SecureContentCryptoFailure.CONTEXT_MISMATCH)
        if (tinkHandle.context != context) {
            return SecureContentCryptoResult.Failure(SecureContentCryptoFailure.CONTEXT_MISMATCH)
        }

        return try {
            val stream = primitiveFor(tinkHandle)
                .newEncryptingStream(
                    protectedSink.openProtectedOutputStream(),
                    SecureContentAssociatedData.forContext(context),
                )
            SecureContentCryptoResult.Success(TinkWriter(context, stream))
        } catch (_: GeneralSecurityException) {
            SecureContentCryptoResult.Failure(SecureContentCryptoFailure.CONTEXT_MISMATCH)
        } catch (_: IOException) {
            SecureContentCryptoResult.Failure(SecureContentCryptoFailure.SOURCE_UNAVAILABLE)
        }
    }

    override fun openRead(
        context: SecureContentCryptoContext,
        keyMaterial: SecureContentKeyMaterialHandle,
        protectedSource: SecureContentProtectedByteSource,
    ): SecureContentCryptoResult<SecureContentCryptoReader> {
        if (context.cryptoVersion != 1) {
            return SecureContentCryptoResult.Failure(SecureContentCryptoFailure.UNSUPPORTED_VERSION)
        }
        val tinkHandle = keyMaterial as? TinkSecureContentKeyMaterialHandle
            ?: return SecureContentCryptoResult.Failure(SecureContentCryptoFailure.CONTEXT_MISMATCH)
        if (tinkHandle.context != context) {
            return SecureContentCryptoResult.Failure(SecureContentCryptoFailure.CONTEXT_MISMATCH)
        }

        return try {
            val stream = primitiveFor(tinkHandle)
                .newDecryptingStream(
                    protectedSource.openProtectedInputStream(),
                    SecureContentAssociatedData.forContext(context),
                )
            SecureContentCryptoResult.Success(TinkReader(context, stream))
        } catch (_: GeneralSecurityException) {
            SecureContentCryptoResult.Failure(SecureContentCryptoFailure.INTEGRITY_REJECTED)
        } catch (_: IOException) {
            SecureContentCryptoResult.Failure(SecureContentCryptoFailure.SOURCE_UNAVAILABLE)
        }
    }

    private fun primitiveFor(
        handle: TinkSecureContentKeyMaterialHandle,
    ): StreamingAead {
        StreamingAeadConfig.register()
        return handle.keysetHandle.getPrimitive(
            RegistryConfiguration.get(),
            StreamingAead::class.java,
        )
    }

    private class TinkWriter(
        override val context: SecureContentCryptoContext,
        private val plaintext: OutputStream,
    ) : SecureContentCryptoWriter {
        private var completed = false
        private var streamOpened = false

        override fun openPlaintextOutputStream(): OutputStream {
            check(!completed) { "writer is already complete" }
            check(!streamOpened) { "writer plaintext stream was already opened" }
            streamOpened = true
            return plaintext
        }

        override fun finish(): SecureContentCryptoResult<SecureContentCryptoWriteCandidate> =
            try {
                if (!completed) {
                    plaintext.close()
                    completed = true
                }
                SecureContentCryptoResult.Success(TinkWriteCandidate(context))
            } catch (_: IOException) {
                SecureContentCryptoResult.Failure(SecureContentCryptoFailure.FINALIZATION_FAILED)
            }

        override fun abort() {
            if (!completed) {
                try {
                    plaintext.close()
                } catch (_: IOException) {
                    // Store-owned abort/recovery remains fail closed.
                } finally {
                    completed = true
                }
            }
        }
    }

    private class TinkReader(
        override val context: SecureContentCryptoContext,
        private val plaintext: InputStream,
    ) : SecureContentCryptoReader {
        override fun openPlaintextInputStream(): InputStream = plaintext

        override fun close() {
            plaintext.close()
        }
    }

    private data class TinkWriteCandidate(
        override val context: SecureContentCryptoContext,
    ) : SecureContentCryptoWriteCandidate
}
