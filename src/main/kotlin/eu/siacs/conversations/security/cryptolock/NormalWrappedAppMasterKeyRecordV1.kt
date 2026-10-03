// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.security.cryptolock

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Persistent ciphertext produced by the auth-bound normal App Master Key wrapper.
 *
 * No Keystore key bytes, biometric data, device credential, or plaintext App Master Key are
 * serialized here. The numeric Keystore key version maps to an internal alias in the Android
 * adapter and is authenticated as part of the record header.
 */
class NormalWrappedAppMasterKeyRecordV1 internal constructor(
    iv: ByteArray,
    ciphertext: ByteArray,
) {
    private val ivValue = iv.copyOf()
    private val ciphertextValue = ciphertext.copyOf()

    init {
        require(ivValue.size == IV_BYTES)
        require(ciphertextValue.size == CIPHERTEXT_BYTES)
    }

    val wrapperFormatVersion: Int
        get() = WRAPPER_FORMAT_VERSION

    val keystoreKeyVersion: Int
        get() = KEYSTORE_KEY_VERSION

    val authPolicyVersion: Int
        get() = AUTH_POLICY_VERSION

    val authPolicyId: String
        get() = AUTH_POLICY_ID

    val aeadVersion: Int
        get() = AEAD_VERSION

    val aeadId: String
        get() = AEAD_ID

    fun iv(): ByteArray = ivValue.copyOf()

    fun ciphertext(): ByteArray = ciphertextValue.copyOf()

    internal fun authenticatedHeader(): ByteArray =
        NormalWrappedAppMasterKeyRecordCodecV1.authenticatedHeader(ivValue)

    override fun toString(): String =
        "NormalWrappedAppMasterKeyRecordV1(version=$WRAPPER_FORMAT_VERSION,[REDACTED])"

    companion object {
        const val WRAPPER_FORMAT_VERSION: Int = 1
        const val KEYSTORE_KEY_VERSION: Int = 1
        const val AUTH_POLICY_VERSION: Int = 1
        const val AUTH_POLICY_ID: String = "BIOMETRIC_STRONG|DEVICE_CREDENTIAL"
        const val AEAD_VERSION: Int = 1
        const val AEAD_ID: String = "AES-256-GCM"
        const val IV_BYTES: Int = 12
        const val TAG_BYTES: Int = 16
        const val CIPHERTEXT_BYTES: Int = AppMasterKey.BYTE_LENGTH + TAG_BYTES
    }
}

object NormalWrappedAppMasterKeyRecordCodecV1 {
    private const val MAGIC: Int = 0x4e434e57 // "NCNW"
    private const val MAX_RECORD_BYTES: Int = 4096
    private const val MAX_STRING_BYTES: Int = 96

    fun encode(record: NormalWrappedAppMasterKeyRecordV1): ByteArray {
        val header = record.authenticatedHeader()
        val ciphertext = record.ciphertext()
        return try {
            ByteArrayOutputStream(header.size + ciphertext.size).use { buffer ->
                buffer.write(header)
                buffer.write(ciphertext)
                buffer.toByteArray()
            }
        } finally {
            header.fill(0)
            ciphertext.fill(0)
        }
    }

    @Throws(InvalidNormalWrappedRecordException::class)
    fun decode(encoded: ByteArray): NormalWrappedAppMasterKeyRecordV1 {
        if (encoded.isEmpty() || encoded.size > MAX_RECORD_BYTES) {
            throw InvalidNormalWrappedRecordException("Normal wrapper record size is invalid")
        }

        try {
            DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                if (input.readInt() != MAGIC) {
                    throw InvalidNormalWrappedRecordException("Normal wrapper magic mismatch")
                }
                if (input.readInt() != NormalWrappedAppMasterKeyRecordV1.WRAPPER_FORMAT_VERSION) {
                    throw InvalidNormalWrappedRecordException("Unsupported normal wrapper format")
                }
                if (input.readInt() != NormalWrappedAppMasterKeyRecordV1.KEYSTORE_KEY_VERSION) {
                    throw InvalidNormalWrappedRecordException("Unsupported Keystore key version")
                }
                if (input.readInt() != NormalWrappedAppMasterKeyRecordV1.AUTH_POLICY_VERSION) {
                    throw InvalidNormalWrappedRecordException("Unsupported auth policy version")
                }
                if (readString(input) != NormalWrappedAppMasterKeyRecordV1.AUTH_POLICY_ID) {
                    throw InvalidNormalWrappedRecordException("Unsupported auth policy")
                }
                if (input.readInt() != NormalWrappedAppMasterKeyRecordV1.AEAD_VERSION) {
                    throw InvalidNormalWrappedRecordException("Unsupported normal wrapper AEAD version")
                }
                if (readString(input) != NormalWrappedAppMasterKeyRecordV1.AEAD_ID) {
                    throw InvalidNormalWrappedRecordException("Unsupported normal wrapper AEAD")
                }

                val iv = readExactBytes(input, NormalWrappedAppMasterKeyRecordV1.IV_BYTES)
                val ciphertextLength = input.readInt()
                if (ciphertextLength != NormalWrappedAppMasterKeyRecordV1.CIPHERTEXT_BYTES) {
                    iv.fill(0)
                    throw InvalidNormalWrappedRecordException(
                        "Normal wrapper ciphertext size is invalid",
                    )
                }
                val ciphertext = ByteArray(ciphertextLength)
                input.readFully(ciphertext)
                if (input.available() != 0) {
                    iv.fill(0)
                    ciphertext.fill(0)
                    throw InvalidNormalWrappedRecordException(
                        "Normal wrapper record has trailing data",
                    )
                }

                return NormalWrappedAppMasterKeyRecordV1(
                    iv = iv,
                    ciphertext = ciphertext,
                ).also {
                    iv.fill(0)
                    ciphertext.fill(0)
                }
            }
        } catch (error: InvalidNormalWrappedRecordException) {
            throw error
        } catch (error: IOException) {
            throw InvalidNormalWrappedRecordException(
                "Normal wrapper record is malformed",
                error,
            )
        } catch (error: RuntimeException) {
            throw InvalidNormalWrappedRecordException(
                "Normal wrapper record is malformed",
                error,
            )
        }
    }

    internal fun authenticatedHeader(iv: ByteArray): ByteArray {
        require(iv.size == NormalWrappedAppMasterKeyRecordV1.IV_BYTES)

        return ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(NormalWrappedAppMasterKeyRecordV1.WRAPPER_FORMAT_VERSION)
                output.writeInt(NormalWrappedAppMasterKeyRecordV1.KEYSTORE_KEY_VERSION)
                output.writeInt(NormalWrappedAppMasterKeyRecordV1.AUTH_POLICY_VERSION)
                writeString(output, NormalWrappedAppMasterKeyRecordV1.AUTH_POLICY_ID)
                output.writeInt(NormalWrappedAppMasterKeyRecordV1.AEAD_VERSION)
                writeString(output, NormalWrappedAppMasterKeyRecordV1.AEAD_ID)
                writeBytes(output, iv)
                output.writeInt(NormalWrappedAppMasterKeyRecordV1.CIPHERTEXT_BYTES)
            }
            buffer.toByteArray()
        }
    }

    private fun writeString(
        output: DataOutputStream,
        value: String,
    ) {
        writeBytes(output, value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeBytes(
        output: DataOutputStream,
        value: ByteArray,
    ) {
        output.writeInt(value.size)
        output.write(value)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        if (length !in 1..MAX_STRING_BYTES) {
            throw InvalidNormalWrappedRecordException("Normal wrapper string size is invalid")
        }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun readExactBytes(
        input: DataInputStream,
        expectedLength: Int,
    ): ByteArray {
        val length = input.readInt()
        if (length != expectedLength) {
            throw InvalidNormalWrappedRecordException("Normal wrapper field size is invalid")
        }
        return ByteArray(length).also(input::readFully)
    }
}

class InvalidNormalWrappedRecordException : Exception {
    constructor(message: String) : super(message)

    constructor(
        message: String,
        cause: Throwable,
    ) : super(message, cause)
}
