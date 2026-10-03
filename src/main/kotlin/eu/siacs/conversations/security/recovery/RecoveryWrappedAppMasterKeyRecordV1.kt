// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.security.recovery

import eu.siacs.conversations.security.cryptolock.AppMasterKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Persistent, non-secret metadata + ciphertext needed to recover the exact App Master Key.
 *
 * The plaintext recovery phrase/secret and plaintext App Master Key are never fields of this record.
 */
class RecoveryWrappedAppMasterKeyRecordV1 internal constructor(
    kdfSalt: ByteArray,
    nonce: ByteArray,
    ciphertext: ByteArray,
    recoveryPhraseFormat: RecoveryPhraseFormat = RecoveryPhraseFormats.BIP39_EN_V1,
) {
    private val kdfSaltValue = kdfSalt.copyOf()
    private val nonceValue = nonce.copyOf()
    private val ciphertextValue = ciphertext.copyOf()
    private val recoveryPhraseFormatValue = recoveryPhraseFormat

    init {
        require(kdfSaltValue.size == RecoveryKekKdfV1.SALT_BYTES)
        require(nonceValue.size == NONCE_BYTES)
        require(ciphertextValue.size == CIPHERTEXT_BYTES)
        require(
            RecoveryPhraseFormats.resolve(
                recoveryPhraseFormatValue.formatVersion,
                recoveryPhraseFormatValue.dictionaryId,
            ) == recoveryPhraseFormatValue
        )
    }

    val wrapperFormatVersion: Int
        get() = WRAPPER_FORMAT_VERSION

    val recoveryPhraseFormatVersion: Int
        get() = recoveryPhraseFormatValue.formatVersion

    val dictionaryId: String
        get() = recoveryPhraseFormatValue.dictionaryId

    val kdfVersion: Int
        get() = RecoveryKekKdfV1.KDF_VERSION

    val kdfId: String
        get() = RecoveryKekKdfV1.KDF_ID

    val aeadVersion: Int
        get() = AEAD_VERSION

    val aeadId: String
        get() = AEAD_ID

    fun kdfSalt(): ByteArray = kdfSaltValue.copyOf()

    fun nonce(): ByteArray = nonceValue.copyOf()

    fun ciphertext(): ByteArray = ciphertextValue.copyOf()

    internal fun recoveryPhraseFormat(): RecoveryPhraseFormat = recoveryPhraseFormatValue

    internal fun authenticatedHeader(): ByteArray =
        RecoveryWrappedAppMasterKeyRecordCodecV1.authenticatedHeader(
            kdfSalt = kdfSaltValue,
            nonce = nonceValue,
            phraseFormat = recoveryPhraseFormatValue,
        )

    override fun toString(): String =
        "RecoveryWrappedAppMasterKeyRecordV1(version=$WRAPPER_FORMAT_VERSION,[REDACTED])"

    companion object {
        const val WRAPPER_FORMAT_VERSION: Int = 1
        const val AEAD_VERSION: Int = 1
        const val AEAD_ID: String = "AES-256-GCM"
        const val NONCE_BYTES: Int = 12
        const val TAG_BYTES: Int = 16
        const val CIPHERTEXT_BYTES: Int = AppMasterKey.BYTE_LENGTH + TAG_BYTES
    }
}

object RecoveryWrappedAppMasterKeyRecordCodecV1 {
    private const val MAGIC: Int = 0x4e435257 // "NCRW"
    private const val MAX_RECORD_BYTES: Int = 4096
    private const val MAX_STRING_BYTES: Int = 64

    fun encode(record: RecoveryWrappedAppMasterKeyRecordV1): ByteArray {
        val header = record.authenticatedHeader()
        val ciphertext = record.ciphertext()
        return ByteArrayOutputStream(header.size + ciphertext.size).use { buffer ->
            buffer.write(header)
            buffer.write(ciphertext)
            buffer.toByteArray()
        }
    }

    @Throws(InvalidRecoveryWrappedRecordException::class)
    fun decode(encoded: ByteArray): RecoveryWrappedAppMasterKeyRecordV1 {
        if (encoded.isEmpty() || encoded.size > MAX_RECORD_BYTES) {
            throw InvalidRecoveryWrappedRecordException("Recovery wrapper record size is invalid")
        }

        try {
            DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                if (input.readInt() != MAGIC) {
                    throw InvalidRecoveryWrappedRecordException("Recovery wrapper magic mismatch")
                }
                if (input.readInt() != RecoveryWrappedAppMasterKeyRecordV1.WRAPPER_FORMAT_VERSION) {
                    throw InvalidRecoveryWrappedRecordException(
                        "Unsupported recovery wrapper format",
                    )
                }
                val phraseFormatVersion = input.readInt()
                val dictionaryId = readString(input)
                val phraseFormat =
                    RecoveryPhraseFormats.resolve(phraseFormatVersion, dictionaryId)
                        ?: throw InvalidRecoveryWrappedRecordException(
                            "Unsupported recovery phrase format",
                        )
                if (input.readInt() != RecoveryKekKdfV1.KDF_VERSION) {
                    throw InvalidRecoveryWrappedRecordException("Unsupported recovery KDF version")
                }
                if (readString(input) != RecoveryKekKdfV1.KDF_ID) {
                    throw InvalidRecoveryWrappedRecordException("Unsupported recovery KDF")
                }
                val salt = readExactBytes(input, RecoveryKekKdfV1.SALT_BYTES)
                if (input.readInt() != RecoveryWrappedAppMasterKeyRecordV1.AEAD_VERSION) {
                    salt.fill(0)
                    throw InvalidRecoveryWrappedRecordException("Unsupported recovery AEAD version")
                }
                if (readString(input) != RecoveryWrappedAppMasterKeyRecordV1.AEAD_ID) {
                    salt.fill(0)
                    throw InvalidRecoveryWrappedRecordException("Unsupported recovery AEAD")
                }
                val nonce = readExactBytes(input, RecoveryWrappedAppMasterKeyRecordV1.NONCE_BYTES)
                val ciphertextLength = input.readInt()
                if (ciphertextLength != RecoveryWrappedAppMasterKeyRecordV1.CIPHERTEXT_BYTES) {
                    salt.fill(0)
                    nonce.fill(0)
                    throw InvalidRecoveryWrappedRecordException(
                        "Recovery wrapper ciphertext size is invalid",
                    )
                }
                val ciphertext = ByteArray(ciphertextLength)
                input.readFully(ciphertext)
                if (input.available() != 0) {
                    salt.fill(0)
                    nonce.fill(0)
                    ciphertext.fill(0)
                    throw InvalidRecoveryWrappedRecordException(
                        "Recovery wrapper record has trailing data",
                    )
                }
                return RecoveryWrappedAppMasterKeyRecordV1(
                    kdfSalt = salt,
                    nonce = nonce,
                    ciphertext = ciphertext,
                    recoveryPhraseFormat = phraseFormat,
                ).also {
                    salt.fill(0)
                    nonce.fill(0)
                    ciphertext.fill(0)
                }
            }
        } catch (error: InvalidRecoveryWrappedRecordException) {
            throw error
        } catch (error: IOException) {
            throw InvalidRecoveryWrappedRecordException(
                "Recovery wrapper record is malformed",
                error,
            )
        } catch (error: RuntimeException) {
            throw InvalidRecoveryWrappedRecordException(
                "Recovery wrapper record is malformed",
                error,
            )
        }
    }

    internal fun authenticatedHeader(
        kdfSalt: ByteArray,
        nonce: ByteArray,
        phraseFormat: RecoveryPhraseFormat = RecoveryPhraseFormats.BIP39_EN_V1,
    ): ByteArray {
        require(kdfSalt.size == RecoveryKekKdfV1.SALT_BYTES)
        require(nonce.size == RecoveryWrappedAppMasterKeyRecordV1.NONCE_BYTES)

        return ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(RecoveryWrappedAppMasterKeyRecordV1.WRAPPER_FORMAT_VERSION)
                output.writeInt(phraseFormat.formatVersion)
                writeString(output, phraseFormat.dictionaryId)
                output.writeInt(RecoveryKekKdfV1.KDF_VERSION)
                writeString(output, RecoveryKekKdfV1.KDF_ID)
                writeBytes(output, kdfSalt)
                output.writeInt(RecoveryWrappedAppMasterKeyRecordV1.AEAD_VERSION)
                writeString(output, RecoveryWrappedAppMasterKeyRecordV1.AEAD_ID)
                writeBytes(output, nonce)
                output.writeInt(RecoveryWrappedAppMasterKeyRecordV1.CIPHERTEXT_BYTES)
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
            throw InvalidRecoveryWrappedRecordException(
                "Recovery wrapper string size is invalid",
            )
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
            throw InvalidRecoveryWrappedRecordException(
                "Recovery wrapper field size is invalid",
            )
        }
        return ByteArray(length).also(input::readFully)
    }
}

class InvalidRecoveryWrappedRecordException : Exception {
    constructor(message: String) : super(message)

    constructor(
        message: String,
        cause: Throwable,
    ) : super(message, cause)
}
