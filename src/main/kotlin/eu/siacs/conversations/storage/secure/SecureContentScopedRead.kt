// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

/**
 * Internal operation-scoped read capability.
 *
 * This is deliberately not part of the public SecureContentStore contract: it exists only to
 * reuse account key-encryption material while a bounded batch of already-authorized reads is in
 * progress. It exposes neither key material nor storage locators.
 */
internal interface SecureContentScopedRead : AutoCloseable {
    /** Authenticated exact lookup within this bounded account/page read scope. */
    fun find(contentId: String): SecureContentObject?

    fun open(contentId: String): SecureContentReadSession

    override fun close()
}

/**
 * Bounded protected-text read plan created from durable database facts for one account/page.
 *
 * The plan is process-local and short-lived. A reference is exposed only when the batch query
 * proved account ownership, text/private type, PROTECTED mode and the exact durable relation, and
 * when the coordinator snapshot found no unresolved publication/retirement evidence for it.
 */
internal interface SecureMessagePayloadReadPlan : AutoCloseable {
    fun referenceFor(messageUuid: String): SecureMessagePayloadReference?

    fun open(context: SecureMessagePayloadContext): SecureMessagePayloadReadSession

    override fun close()
}
