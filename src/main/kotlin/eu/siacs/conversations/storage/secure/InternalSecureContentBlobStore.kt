// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Private app-internal protected-byte implementation.
 *
 * The physical layout is deliberately an implementation detail: no path, URI, or file crosses
 * the BlobStore boundary. References contain random opaque identifiers only. This class does not
 * authorize accounts, inspect crypto context, or publish reader visibility.
 */
internal class InternalSecureContentBlobStore(
    context: Context,
) : SecureContentBlobStore {
    private val root = File(context.noBackupFilesDir, ROOT_DIRECTORY)
    private val stagingDirectory = File(root, STAGING_DIRECTORY)
    private val committedDirectory = File(root, COMMITTED_DIRECTORY)

    init {
        requireDirectory(stagingDirectory)
        requireDirectory(committedDirectory)
    }

    override fun put(): SecureContentBlobResult<SecureContentBlobWriteSession> =
        try {
            val identifier = UUID.randomUUID().toString()
            val stagedFile = File(stagingDirectory, identifier)
            SecureContentBlobResult.Success(StagedWrite(identifier, stagedFile))
        } catch (_: IOException) {
            SecureContentBlobResult.Failure(SecureContentBlobFailure.STAGING_UNAVAILABLE)
        }

    override fun get(
        reference: SecureContentBlobReference,
    ): SecureContentBlobResult<SecureContentProtectedByteSource> {
        val identifier = reference.identifierOrNull()
            ?: return SecureContentBlobResult.Failure(SecureContentBlobFailure.REFERENCE_UNAVAILABLE)
        val file = File(committedDirectory, identifier)
        if (!file.isFile) {
            return SecureContentBlobResult.Failure(SecureContentBlobFailure.SOURCE_UNAVAILABLE)
        }
        return SecureContentBlobResult.Success(
            object : SecureContentProtectedByteSource {
                override fun openProtectedInputStream(): InputStream = FileInputStream(file)
            },
        )
    }

    override fun delete(
        reference: SecureContentBlobReference,
    ): SecureContentBlobResult<Unit> {
        val identifier = reference.identifierOrNull()
            ?: return SecureContentBlobResult.Failure(SecureContentBlobFailure.REFERENCE_UNAVAILABLE)
        val file = File(committedDirectory, identifier)
        return if (!file.exists() || file.delete()) {
            SecureContentBlobResult.Success(Unit)
        } else {
            SecureContentBlobResult.Failure(SecureContentBlobFailure.RETIREMENT_FAILED)
        }
    }

    override fun exists(
        reference: SecureContentBlobReference,
    ): SecureContentBlobResult<Boolean> {
        val identifier = reference.identifierOrNull()
            ?: return SecureContentBlobResult.Failure(SecureContentBlobFailure.REFERENCE_UNAVAILABLE)
        return SecureContentBlobResult.Success(File(committedDirectory, identifier).isFile)
    }

    /**
     * Commits a Store-owned staged candidate. This is intentionally not part of the public
     * BlobStore contract: only the runtime coordinator can turn a candidate into a reference.
     */
    internal fun commit(candidate: SecureContentBlobCommitCandidate): SecureContentBlobResult<Reference> {
        val staged = candidate as? CommitCandidate
            ?: return SecureContentBlobResult.Failure(SecureContentBlobFailure.PREPARATION_FAILED)
        if (!isValidIdentifier(staged.identifier) || !staged.file.isFile) {
            return SecureContentBlobResult.Failure(SecureContentBlobFailure.PREPARATION_FAILED)
        }
        val committed = File(committedDirectory, staged.identifier)
        return if (staged.file.renameTo(committed)) {
            SecureContentBlobResult.Success(Reference(staged.identifier))
        } else {
            SecureContentBlobResult.Failure(SecureContentBlobFailure.PREPARATION_FAILED)
        }
    }

    internal fun referenceFor(candidate: SecureContentBlobCommitCandidate): SecureContentBlobReference? =
        (candidate as? CommitCandidate)
            ?.takeIf { isValidIdentifier(it.identifier) }
            ?.let { Reference(it.identifier) }

    internal fun reference(identifier: String): SecureContentBlobReference = Reference(identifier)

    /** Best-effort cleanup of a non-published staged object during recovery. */
    internal fun discardStaged(reference: SecureContentBlobReference) {
        val identifier = reference.identifierOrNull() ?: return
        File(stagingDirectory, identifier).delete()
    }

    internal fun identifier(reference: SecureContentBlobReference): String? =
        reference.identifierOrNull()

    internal fun committedSize(reference: SecureContentBlobReference): Long? {
        val identifier = reference.identifierOrNull() ?: return null
        val file = File(committedDirectory, identifier)
        return file.takeIf { it.isFile }?.length()
    }

    private inner class StagedWrite(
        private val identifier: String,
        private val file: File,
    ) : SecureContentBlobWriteSession {
        private var stream: FileOutputStream? = null
        private var terminal = false

        override val protectedSink = object : SecureContentProtectedByteSink {
            override fun openProtectedOutputStream(): OutputStream {
                check(!terminal) { "Blob write session is terminal" }
                check(stream == null) { "Protected output stream was already opened" }
                return FileOutputStream(file).also { stream = it }
            }
        }

        override fun prepareCommit(): SecureContentBlobResult<SecureContentBlobCommitCandidate> {
            if (terminal || stream == null) {
                return SecureContentBlobResult.Failure(SecureContentBlobFailure.PREPARATION_FAILED)
            }
            return try {
                stream?.flush()
                stream?.close()
                stream = null
                terminal = true
                SecureContentBlobResult.Success(CommitCandidate(identifier, file))
            } catch (_: IOException) {
                abort()
                SecureContentBlobResult.Failure(SecureContentBlobFailure.PREPARATION_FAILED)
            }
        }

        override fun abort() {
            if (terminal) return
            terminal = true
            try {
                stream?.close()
            } catch (_: IOException) {
                // Best-effort staging cleanup; coordinator records a durable failure if required.
            }
            stream = null
            file.delete()
        }
    }

    internal data class CommitCandidate(
        val identifier: String,
        val file: File,
    ) : SecureContentBlobCommitCandidate

    internal data class Reference(
        val identifier: String,
    ) : SecureContentBlobReference

    /**
     * Blob identifiers are Store-private UUIDs. Treat every identifier recovered from durable
     * metadata as hostile until it matches this representation, so an invalid record cannot
     * escape the internal staging/committed directories.
     */
    private fun SecureContentBlobReference.identifierOrNull(): String? =
        (this as? Reference)?.identifier?.takeIf(::isValidIdentifier)

    private fun isValidIdentifier(identifier: String): Boolean =
        try {
            UUID.fromString(identifier).toString() == identifier
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun requireDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create secure content storage")
        }
        if (!directory.isDirectory) {
            throw IOException("Secure content storage is not a directory")
        }
    }

    private companion object {
        const val ROOT_DIRECTORY = "secure-content-v1"
        const val STAGING_DIRECTORY = "staging"
        const val COMMITTED_DIRECTORY = "committed"
    }
}
