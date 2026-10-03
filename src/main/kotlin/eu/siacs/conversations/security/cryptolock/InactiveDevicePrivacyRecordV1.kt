// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.security.cryptolock

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

enum class InactiveDevicePrivacyStateV1 {
    ACTIVE,
    PRIVACY_SUSPENDED,
}

data class InactiveDevicePrivacyRecordV1(
    val state: InactiveDevicePrivacyStateV1,
    val lastUserActivityAt: Long,
    val suspendedAt: Long?,
    val updatedAt: Long,
) {
    init {
        require(lastUserActivityAt > 0L)
        require(updatedAt > 0L)
        require(
            (state == InactiveDevicePrivacyStateV1.ACTIVE && suspendedAt == null) ||
                (state == InactiveDevicePrivacyStateV1.PRIVACY_SUSPENDED &&
                    suspendedAt != null &&
                    suspendedAt > 0L),
        )
    }
}

internal object InactiveDevicePrivacyRecordCodecV1 {
    private const val MAGIC = 0x4e435056 // NCPV
    private const val VERSION = 1

    fun encode(record: InactiveDevicePrivacyRecordV1): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(record.state.ordinal)
                output.writeLong(record.lastUserActivityAt)
                output.writeLong(record.suspendedAt ?: -1L)
                output.writeLong(record.updatedAt)
            }
            buffer.toByteArray()
        }

    fun decode(bytes: ByteArray): InactiveDevicePrivacyRecordV1 {
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                    throw IllegalArgumentException("unsupported privacy state record")
                }
                val stateOrdinal = input.readInt()
                val state =
                    InactiveDevicePrivacyStateV1.entries.getOrNull(stateOrdinal)
                        ?: throw IllegalArgumentException("invalid privacy state")
                val lastUserActivityAt = input.readLong()
                val suspendedAtRaw = input.readLong()
                val updatedAt = input.readLong()
                if (input.available() != 0) {
                    throw IllegalArgumentException("trailing privacy state data")
                }
                return InactiveDevicePrivacyRecordV1(
                    state = state,
                    lastUserActivityAt = lastUserActivityAt,
                    suspendedAt = suspendedAtRaw.takeIf { it > 0L },
                    updatedAt = updatedAt,
                )
            }
        } catch (error: Exception) {
            if (error is IllegalArgumentException) throw error
            throw IllegalArgumentException("malformed privacy state record", error)
        }
    }
}

internal object InactiveDevicePrivacyPolicyV1 {
    const val INACTIVITY_TIMEOUT_MILLIS: Long = 24L * 60L * 60L * 1000L
    const val CLOCK_ROLLBACK_TOLERANCE_MILLIS: Long = 5L * 60L * 1000L
    const val FOREGROUND_TOUCH_INTERVAL_MILLIS: Long = 60L * 1000L

    fun shouldSuspend(
        record: InactiveDevicePrivacyRecordV1,
        now: Long,
        authenticatedForeground: Boolean,
    ): Boolean {
        if (record.state == InactiveDevicePrivacyStateV1.PRIVACY_SUSPENDED) {
            return true
        }
        if (authenticatedForeground) {
            return false
        }
        if (now <= 0L) {
            return true
        }
        if (now + CLOCK_ROLLBACK_TOLERANCE_MILLIS < record.lastUserActivityAt) {
            return true
        }
        if (now < record.lastUserActivityAt) {
            return false
        }
        return now - record.lastUserActivityAt >= INACTIVITY_TIMEOUT_MILLIS
    }

    fun active(now: Long): InactiveDevicePrivacyRecordV1 =
        InactiveDevicePrivacyRecordV1(
            state = InactiveDevicePrivacyStateV1.ACTIVE,
            lastUserActivityAt = now,
            suspendedAt = null,
            updatedAt = now,
        )

    fun suspended(
        previous: InactiveDevicePrivacyRecordV1?,
        now: Long,
    ): InactiveDevicePrivacyRecordV1 =
        InactiveDevicePrivacyRecordV1(
            state = InactiveDevicePrivacyStateV1.PRIVACY_SUSPENDED,
            lastUserActivityAt = previous?.lastUserActivityAt?.takeIf { it > 0L } ?: now,
            suspendedAt = now,
            updatedAt = now,
        )
}

sealed class InactiveDevicePrivacyStoreReadResultV1 {
    data object Absent : InactiveDevicePrivacyStoreReadResultV1()
    data object Corrupt : InactiveDevicePrivacyStoreReadResultV1()
    data class Present(val record: InactiveDevicePrivacyRecordV1) :
        InactiveDevicePrivacyStoreReadResultV1()
}
