// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

/**
 * Classifies the intended local storage treatment without performing storage work.
 */
interface SecureContentStoragePolicy {
    fun treatmentFor(origin: SecureContentOrigin): SecureContentTreatment
}

/**
 * Conceptual origin of content considered by storage policy.
 */
enum class SecureContentOrigin {
    REMOTE_CONTENT,
    LOCAL_USER_SELECTED,
    LEGACY_EXISTING,
    EXPLICIT_EXPORT,
}

/**
 * Intended ownership boundary selected by a future policy implementation.
 */
enum class SecureContentTreatment {
    SECURE_STORE,
    LEGACY_COMPATIBILITY,
    EXTERNAL_DESTINATION,
}
