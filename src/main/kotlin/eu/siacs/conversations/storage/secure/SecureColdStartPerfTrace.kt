// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

import java.util.LinkedHashMap

/**
 * One-process cold-start timing recorder.
 *
 * Privacy contract: this trace records only fixed stage names, elapsed time and aggregate counts.
 * It never records JIDs, account/message/content IDs, filenames, URIs, message text or errors.
 *
 * Some outer stages intentionally contain inner stages (for example Store construction contains
 * metadata scan and recovery). TOTAL is wall-clock time and stage values must not be summed.
 */
object SecureColdStartPerfTrace {
    private val metricName = Regex("[a-z0-9_]{1,64}")
    private val lock = Any()
    private val stages = LinkedHashMap<String, Long>()
    private val counters = LinkedHashMap<String, Long>()
    private val milestones = LinkedHashMap<String, Long>()

    private var startedNanos = 0L
    private var lastEventNanos = 0L
    private var finishedNanos: Long? = null

    @Volatile
    private var snapshotListener: ((String) -> Unit)? = null

    @JvmStatic
    fun installSnapshotListener(listener: ((String) -> Unit)?) {
        snapshotListener = listener
    }

    @JvmStatic
    fun start() {
        synchronized(lock) {
            stages.clear()
            counters.clear()
            milestones.clear()
            startedNanos = System.nanoTime()
            lastEventNanos = startedNanos
            finishedNanos = null
        }
        notifyChanged()
    }

    @JvmStatic
    fun stage(name: String, durationNanos: Long) {
        if (!valid(name) || durationNanos < 0L) return
        synchronized(lock) {
            if (startedNanos == 0L || finishedNanos != null) return
            stages[name] = (stages[name] ?: 0L) + nanosToMillis(durationNanos)
            lastEventNanos = System.nanoTime()
        }
        notifyChanged()
    }

    @JvmStatic
    fun increment(name: String, delta: Long) {
        if (!valid(name) || delta < 0L) return
        synchronized(lock) {
            if (startedNanos == 0L || finishedNanos != null) return
            counters[name] = (counters[name] ?: 0L) + delta
            lastEventNanos = System.nanoTime()
        }
        notifyChanged()
    }

    @JvmStatic
    fun milestone(name: String) {
        if (!valid(name)) return
        synchronized(lock) {
            if (startedNanos == 0L || finishedNanos != null) return
            val now = System.nanoTime()
            milestones.putIfAbsent(name, nanosToMillis(now - startedNanos))
            lastEventNanos = now
        }
        notifyChanged()
    }

    @JvmStatic
    fun finish() {
        synchronized(lock) {
            if (startedNanos == 0L || finishedNanos != null) return
            val now = System.nanoTime()
            finishedNanos = now
            lastEventNanos = now
        }
        notifyChanged()
    }

    @JvmStatic
    fun clear() {
        synchronized(lock) {
            stages.clear()
            counters.clear()
            milestones.clear()
            startedNanos = 0L
            lastEventNanos = 0L
            finishedNanos = null
        }
        notifyChanged()
    }

    @JvmStatic
    fun hasStarted(): Boolean = synchronized(lock) { startedNanos != 0L }

    @JvmStatic
    fun report(): String =
        synchronized(lock) {
            buildString {
                appendLine("Cold Start Performance")
                appendLine("privacy: no JID / account UUID / message UUID / filename / URI / content")
                if (startedNanos == 0L) {
                    appendLine("status=not-recorded")
                    return@buildString
                }
                append("status=").appendLine(if (finishedNanos == null) "running" else "ready")
                appendLine("note: outer stages may contain inner stages; do not sum all stages")

                if (stages.isNotEmpty()) {
                    appendLine()
                    appendLine("stages:")
                    stages.forEach { (name, millis) ->
                        append("  ").append(name).append('=').append(millis).appendLine("ms")
                    }
                }
                if (counters.isNotEmpty()) {
                    appendLine()
                    appendLine("counts:")
                    counters.forEach { (name, value) ->
                        append("  ").append(name).append('=').appendLine(value.toString())
                    }
                }
                if (milestones.isNotEmpty()) {
                    appendLine()
                    appendLine("milestones:")
                    milestones.forEach { (name, millis) ->
                        append("  ").append(name).append('=').append(millis).appendLine("ms")
                    }
                }
                val end = finishedNanos ?: lastEventNanos
                appendLine()
                append("TOTAL=").append(nanosToMillis(end - startedNanos)).appendLine("ms")
            }
        }

    private fun notifyChanged() {
        snapshotListener?.invoke(report())
    }

    private fun valid(name: String): Boolean = metricName.matches(name)

    private fun nanosToMillis(nanos: Long): Long =
        if (nanos <= 0L) 0L else (nanos + 500_000L) / 1_000_000L
}
