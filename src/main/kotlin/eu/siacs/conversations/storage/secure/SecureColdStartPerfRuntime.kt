// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Persists only the sanitized text emitted by [SecureColdStartPerfTrace] so the current cold-start
 * result remains available in the in-app diagnostics screen without Logcat.
 */
object SecureColdStartPerfRuntime {
    private const val DIRECTORY = "secure_cold_start_perf"
    private const val FILE_NAME = "latest.txt"

    private val persistenceExecutor = Executors.newSingleThreadExecutor()

    @JvmStatic
    fun initialize(context: Context) {
        val app = context.applicationContext
        SecureColdStartPerfTrace.installSnapshotListener { snapshot ->
            persistenceExecutor.execute { persist(app, snapshot) }
        }
    }

    @JvmStatic
    fun report(context: Context): String {
        if (SecureColdStartPerfTrace.hasStarted()) {
            return SecureColdStartPerfTrace.report()
        }
        return try {
            reportFile(context).takeIf { it.isFile }?.readText()
                ?: SecureColdStartPerfTrace.report()
        } catch (_: IOException) {
            SecureColdStartPerfTrace.report()
        }
    }

    @JvmStatic
    fun clear(context: Context) {
        SecureColdStartPerfTrace.clear()
        try {
            reportFile(context).delete()
        } catch (_: Exception) {
            // Diagnostics cleanup is best effort only.
        }
    }

    private fun persist(context: Context, snapshot: String) {
        try {
            val file = reportFile(context)
            file.parentFile?.mkdirs()
            file.writeText(snapshot)
        } catch (_: IOException) {
            // Diagnostics must never affect startup behavior.
        }
    }

    private fun reportFile(context: Context): File =
        File(File(context.applicationContext.cacheDir, DIRECTORY), FILE_NAME)
}
