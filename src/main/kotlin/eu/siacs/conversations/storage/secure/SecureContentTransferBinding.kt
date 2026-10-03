// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

/**
 * Exact non-path relation between a message transfer and one account-owned Secure Content object.
 *
 * A message UUID is a correlation value only. It does not authorize a read and must never be
 * resolved by choosing an arbitrary first object. Callers retain the complete account and content
 * identity when opening, deleting, or otherwise acting on the object.
 */
data class SecureContentTransferBinding(
    val accountUuid: String,
    val messageUuid: String,
    val contentId: String,
    val namespace: String = SecureContentMetadata.DEFAULT_NAMESPACE,
) {
    init {
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        require(messageUuid.isNotBlank()) { "messageUuid must not be blank" }
        require(contentId.isNotBlank()) { "contentId must not be blank" }
        require(namespace.isNotBlank()) { "namespace must not be blank" }
    }
}
