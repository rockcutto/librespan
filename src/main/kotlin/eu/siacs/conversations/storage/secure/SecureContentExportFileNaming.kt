// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import eu.siacs.conversations.utils.MimeUtils

/**
 * Produces a user-visible basename for a temporary explicit Secure Content export.
 *
 * The export scope itself remains random. Only the final basename carries authenticated metadata
 * so Android ContentResolver/OpenableColumns can preserve the original attachment name when the
 * export is forwarded or shared.
 */
internal object SecureContentExportFileNaming {
    fun resolve(
        metadata: SecureContentMetadata,
        messageUuid: String,
    ): String {
        sanitize(metadata.fileName)?.let { return it }

        val extension =
            MimeUtils.guessExtensionFromMimeType(metadata.mimeType)
                ?.trim()
                ?.trimStart('.')
                ?.takeIf { it.isNotEmpty() }
        return if (extension == null) messageUuid else "$messageUuid.$extension"
    }

    private fun sanitize(candidate: String?): String? {
        val basename =
            candidate
                ?.replace('\\', '/')
                ?.substringAfterLast('/')
                ?.replace(Regex("[\\x00-\\x1f\\x7f]"), "_")
                ?.trim()
        return basename?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
    }
}
