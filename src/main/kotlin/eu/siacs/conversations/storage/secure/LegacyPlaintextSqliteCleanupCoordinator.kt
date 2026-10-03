// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import android.content.Context
import eu.siacs.conversations.persistance.DatabaseBackend

/**
 * One-shot owner for expensive post-migration SQLite residue cleanup.
 *
 * The backend remains the fail-closed authority for eligibility. Completion is persisted only
 * after all logical/FTS/WAL/freelist checks succeed, so process death before that point retries.
 */
class LegacyPlaintextSqliteCleanupCoordinator(
    context: Context,
    private val databaseBackend: DatabaseBackend,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isCompleted(): Boolean = preferences.getBoolean(COMPLETED_KEY, false)

    @Synchronized
    fun runIfEligible(): LegacyPlaintextSqliteCleanupResult? {
        if (isCompleted()) {
            return null
        }
        val result = databaseBackend.cleanupMigratedLegacyPlaintextResidue()
        if (result.completed) {
            check(preferences.edit().putBoolean(COMPLETED_KEY, true).commit()) {
                "Unable to persist SQLite plaintext cleanup completion"
            }
        }
        return result
    }

    private companion object {
        const val PREFERENCES_NAME = "legacy_plaintext_sqlite_cleanup_v1"
        const val COMPLETED_KEY = "completed"
    }
}
