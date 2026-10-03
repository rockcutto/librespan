// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

/**
 * Runtime snapshot of one account-owned Secure Content Store object.
 *
 * This model intentionally exposes logical metadata only. It contains no raw path, File,
 * public URI, blob bytes, or key material. [metadata] supplies the mandatory ownership,
 * namespace, message relation, lifecycle, and crypto-version fields.
 */
data class SecureContentObject(
    val metadata: SecureContentMetadata,
) {
    val accountUuid: String
        get() = metadata.accountUuid

    val contentId: String
        get() = metadata.contentId

    val namespace: String
        get() = metadata.namespace

    val messageUuid: String?
        get() = metadata.messageUuid

    val state: SecureContentState
        get() = metadata.state

    val cryptoVersion: Int?
        get() = metadata.cryptoVersion

    val handle: SecureContentHandle
        get() = SecureContentHandle(
            accountUuid = accountUuid,
            contentId = contentId,
            mimeType = metadata.mimeType,
        )

    /**
     * A snapshot may be handed to a normal reader only after Store commit.
     * Authorization remains an account-scoped Store operation, never a property of contentId.
     */
    val isReaderVisible: Boolean
        get() = state == SecureContentState.COMMITTED
}
