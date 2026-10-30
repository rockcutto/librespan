// Temporary P1 diagnostics for fix/093-audit-fixes. No plaintext or identifiers.
package eu.siacs.conversations.storage.secure

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Small bounded, persisted in-app SCS trace. No Logcat dependency or UI-thread file writes. */
object ScsTextDiagnosticsRuntime {
    private const val MAX_EVENTS = 240
    private const val BIND_THROTTLE_MS = 3_000L
    private const val FLUSH_DELAY_MS = 400L
    private val allowedEvent = Regex("""SCS_DIAG [A-Za-z0-9_ .+=-]{1,180}""")
    private val lock = Any()
    private val records = ArrayDeque<String>()
    private val recentBindMisses = HashMap<String, Long>()
    private val ioExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "ScsTextDiagnostics").apply { isDaemon = true }
    }
    private var version = 0L
    private var flushScheduled = false
    private var cleared = false

    /** Caller must supply only hard-coded event names and numeric/boolean metadata. */
    @JvmStatic
    fun record(context: Context, event: String) {
        if (!allowedEvent.matches(event)) return
        val now = SystemClock.elapsedRealtime()
        val application = context.applicationContext
        synchronized(lock) {
            if (event.startsWith("SCS_DIAG bind-missing ")) {
                val previous = recentBindMisses[event]
                if (previous != null && now - previous < BIND_THROTTLE_MS) return
                if (recentBindMisses.size >= MAX_EVENTS) recentBindMisses.clear()
                recentBindMisses[event] = now
            }
            if (records.size >= MAX_EVENTS) records.removeFirst()
            records.addLast("unixMs=${System.currentTimeMillis()} upMs=$now $event")
            version++
            cleared = false
            if (!flushScheduled) scheduleFlush(application)
        }
    }

    private fun scheduleFlush(context: Context) {
        flushScheduled = true
        try {
            ioExecutor.schedule(Runnable { persist(context) }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
        } catch (_: RuntimeException) {
            flushScheduled = false
        }
    }

    private fun persist(context: Context) {
        val (snapshot, snapshotVersion) = synchronized(lock) { Pair(buildReportLocked(), version) }
        val atomic = AtomicFile(reportFile(context))
        var stream: FileOutputStream? = null
        try {
            atomic.baseFile.parentFile?.mkdirs()
            val output = atomic.startWrite()
            stream = output
            output.write(snapshot.toByteArray(StandardCharsets.UTF_8))
            atomic.finishWrite(output)
            stream = null
        } catch (_: Exception) {
            stream?.let {
                try { atomic.failWrite(it) } catch (_: Exception) { }
            }
        } finally {
            synchronized(lock) {
                flushScheduled = false
                if (version != snapshotVersion) scheduleFlush(context)
            }
        }
    }

    @JvmStatic
    fun report(context: Context): String {
        synchronized(lock) {
            if (records.isNotEmpty() || cleared) return buildReportLocked()
        }
        return try {
            AtomicFile(reportFile(context)).openRead()
                .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            "LibreSpan SCS text diagnostics\nrecords=0\nsanitized=true\n"
        }
    }

    @JvmStatic
    fun clear(context: Context) {
        synchronized(lock) {
            records.clear()
            recentBindMisses.clear()
            version++
            cleared = true
        }
        val app = context.applicationContext
        try {
            ioExecutor.execute {
                try {
                    AtomicFile(reportFile(app)).delete()
                } catch (_: Exception) {
                    // Diagnostics cleanup is best effort.
                }
            }
        } catch (_: RuntimeException) {
            // Diagnostics must not affect the UI.
        }
    }

    private fun buildReportLocked(): String = buildString {
        appendLine("LibreSpan SCS text diagnostics")
        appendLine("records=${records.size}")
        appendLine("sanitized=true")
        appendLine("fields=timing,object-hash,crypto-epoch,UI-revision,state")
        appendLine()
        records.forEach { appendLine(it) }
    }

    private fun reportFile(context: Context): File =
        File(File(context.applicationContext.cacheDir, "scs_text_diagnostics"), "latest.txt")
}
