// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

import java.util.ArrayDeque
import java.util.LinkedHashMap

/**
 * Lightweight process-local timing recorder for the Secure Media hot path.
 *
 * Privacy contract:
 * - message UUID is used only as an in-memory correlation key and is never rendered;
 * - no JID, filename, URI, content bytes or exception text is recorded;
 * - rendered metadata is limited to direction, broad media class and byte size.
 *
 * This recorder deliberately has no Android or Logcat dependency. Android persistence/UI is
 * provided by SecureMediaPerfRuntime.
 */
object SecureMediaPerfTrace {
    private const val MAX_OPERATIONS = 20

    private val lock = Any()
    private val operations = ArrayDeque<Operation>()
    private val byMessageUuid = HashMap<String, Operation>()
    private var nextId = 1L

    @Volatile
    private var snapshotListener: ((String) -> Unit)? = null

    @JvmStatic
    fun installSnapshotListener(listener: ((String) -> Unit)?) {
        snapshotListener = listener
    }

    @JvmStatic
    fun start(
        direction: String,
        messageUuid: String,
        mimeType: String?,
        sizeBytes: Long?,
    ) {
        if (messageUuid.isBlank()) return
        synchronized(lock) {
            val existing = byMessageUuid[messageUuid]
            if (existing != null && existing.status != Status.FAILED) {
                existing.sizeBytes = existing.sizeBytes ?: sizeBytes
                return
            }
            val operation =
                Operation(
                    id = nextId++,
                    correlationKey = messageUuid,
                    direction = sanitizeDirection(direction),
                    mediaClass = mediaClass(mimeType),
                    sizeBytes = sizeBytes?.takeIf { it >= 0L },
                    startedNanos = System.nanoTime(),
                )
            operations.addLast(operation)
            byMessageUuid[messageUuid] = operation
            trimLocked()
        }
    }

    @JvmStatic
    fun stage(
        messageUuid: String,
        stage: String,
        durationNanos: Long,
    ) {
        if (messageUuid.isBlank() || durationNanos < 0L) return
        synchronized(lock) {
            val operation = byMessageUuid[messageUuid] ?: return
            val millis = nanosToMillis(durationNanos)
            operation.stages[stage] = (operation.stages[stage] ?: 0L) + millis
            operation.lastEventNanos = System.nanoTime()
        }
    }

    /**
     * Records wall-clock time not owned by an explicit timed stage.
     *
     * This is used only at async hand-off boundaries so TOTAL can be reconciled without putting
     * identifiers or payload details into diagnostics.
     */
    @JvmStatic
    fun gapSinceLastEvent(messageUuid: String, stage: String) {
        if (messageUuid.isBlank()) return
        synchronized(lock) {
            val operation = byMessageUuid[messageUuid] ?: return
            val now = System.nanoTime()
            val durationNanos = now - operation.lastEventNanos
            if (durationNanos < 0L) return
            val millis = nanosToMillis(durationNanos)
            operation.stages[stage] = (operation.stages[stage] ?: 0L) + millis
            operation.lastEventNanos = now
        }
    }

    @JvmStatic
    fun finish(messageUuid: String) {
        if (messageUuid.isBlank()) return
        synchronized(lock) {
            val operation = byMessageUuid[messageUuid] ?: return
            if (operation.status != Status.FAILED) {
                operation.status = Status.READY
                operation.lastEventNanos = System.nanoTime()
            }
        }
        notifyChanged()
    }

    @JvmStatic
    fun fail(messageUuid: String) {
        if (messageUuid.isBlank()) return
        synchronized(lock) {
            val operation = byMessageUuid[messageUuid] ?: return
            operation.status = Status.FAILED
            operation.lastEventNanos = System.nanoTime()
        }
        notifyChanged()
    }

    @JvmStatic
    fun clear() {
        synchronized(lock) {
            operations.clear()
            byMessageUuid.clear()
        }
        notifyChanged()
    }

    @JvmStatic
    fun hasRecords(): Boolean = synchronized(lock) { operations.isNotEmpty() }

    @JvmStatic
    fun report(): String =
        synchronized(lock) {
            buildString {
                appendLine("Secure Media Performance")
                appendLine("privacy: no JID / UUID / filename / URI / content")
                appendLine("records: ${operations.size}/$MAX_OPERATIONS")
                if (operations.isEmpty()) {
                    appendLine()
                    appendLine("Нет замеров. Выполните отправку или загрузку медиа с Secure Media.")
                    return@buildString
                }
                operations.toList().asReversed().forEach { operation ->
                    appendLine()
                    append('#').append(operation.id)
                        .append(' ')
                        .append(operation.direction)
                        .append(' ')
                        .append(operation.mediaClass)
                    operation.sizeBytes?.let {
                        append(' ').append(formatBytes(it))
                    }
                    append(" status=").append(operation.status.label)
                    appendLine()
                    operation.stages.forEach { (name, millis) ->
                        append("  ")
                            .append(name)
                            .append('=')
                            .append(millis)
                            .appendLine("ms")
                    }
                    append("  TOTAL=")
                        .append(nanosToMillis(operation.lastEventNanos - operation.startedNanos))
                        .appendLine("ms")
                }
            }
        }

    private fun notifyChanged() {
        val listener = snapshotListener ?: return
        listener(report())
    }

    private fun trimLocked() {
        while (operations.size > MAX_OPERATIONS) {
            val removed = operations.removeFirst()
            if (byMessageUuid[removed.correlationKey] === removed) {
                byMessageUuid.remove(removed.correlationKey)
            }
        }
    }

    private fun sanitizeDirection(direction: String): String =
        when (direction.lowercase()) {
            "incoming" -> "incoming"
            "outgoing" -> "outgoing"
            else -> "unknown"
        }

    private fun mediaClass(mimeType: String?): String =
        when {
            mimeType?.startsWith("image/") == true -> "image"
            mimeType?.startsWith("video/") == true -> "video"
            mimeType?.startsWith("audio/") == true -> "audio"
            mimeType == "application/pdf" -> "document"
            mimeType?.startsWith("text/") == true -> "document"
            mimeType != null -> "file"
            else -> "unknown"
        }

    private fun nanosToMillis(nanos: Long): Long =
        if (nanos <= 0L) 0L else (nanos + 500_000L) / 1_000_000L

    private fun formatBytes(bytes: Long): String =
        when {
            bytes >= 1024L * 1024L ->
                String.format(java.util.Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L ->
                String.format(java.util.Locale.US, "%.1fKB", bytes / 1024.0)
            else -> "${bytes}B"
        }

    private enum class Status(val label: String) {
        RUNNING("running"),
        READY("ready"),
        FAILED("failed"),
    }

    private data class Operation(
        val id: Long,
        val correlationKey: String,
        val direction: String,
        val mediaClass: String,
        var sizeBytes: Long?,
        val startedNanos: Long,
        var lastEventNanos: Long = startedNanos,
        var status: Status = Status.RUNNING,
        val stages: LinkedHashMap<String, Long> = LinkedHashMap(),
    )
}
