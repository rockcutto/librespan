// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

/**
 * Non-secret evidence produced by the one-shot SQLite plaintext residue cleanup.
 *
 * No message text, token, UUID or account identity is retained in this result.
 */
data class LegacyPlaintextSqliteCleanupResult(
    val eligible: Boolean,
    val plaintextBodyRowsBefore: Long,
    val secureDeleteEnabled: Boolean,
    val ftsCanaryObservedBeforeRebuild: Boolean,
    val ftsCanaryAbsentAfterRebuild: Boolean,
    val ftsIntegrityVerified: Boolean,
    val walCheckpointBusy: Int,
    val walFramesRemaining: Int,
    val freelistPagesAfterVacuum: Long,
    val plaintextBodyRowsAfter: Long,
) {
    val completed: Boolean
        get() =
            eligible &&
                plaintextBodyRowsBefore == 0L &&
                secureDeleteEnabled &&
                ftsCanaryObservedBeforeRebuild &&
                ftsCanaryAbsentAfterRebuild &&
                ftsIntegrityVerified &&
                walCheckpointBusy == 0 &&
                walFramesRemaining == 0 &&
                freelistPagesAfterVacuum == 0L &&
                plaintextBodyRowsAfter == 0L
}
