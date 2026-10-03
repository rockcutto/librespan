// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

/**
 * Account-scoped logical identity for content owned by the Secure Content Store.
 *
 * A handle deliberately contains no filesystem path, public URI, or Android/UI state.
 * [contentId] is not an authorization token: the local [accountUuid] is required for
 * every store operation.
 */
data class SecureContentHandle(
    val accountUuid: String,
    val contentId: String,
    val mimeType: String?,
) {
    init {
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        require(contentId.isNotBlank()) { "contentId must not be blank" }
    }
}
