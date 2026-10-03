// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

/**
 * Boundary for explicit user-requested externalization of secure content.
 *
 * View, Share and Save remain separate operations. This API exposes no URI, FileProvider,
 * MediaStore or Intent details.
 */
interface ContentExportCoordinator {
    fun prepare(
        handle: SecureContentHandle,
        operation: ContentExportOperation,
    ): ContentExport
}

enum class ContentExportOperation {
    VIEW,
    SHARE,
    SAVE,
}

/**
 * Opaque result of a future export preparation.
 */
interface ContentExport {
    val operation: ContentExportOperation
}
