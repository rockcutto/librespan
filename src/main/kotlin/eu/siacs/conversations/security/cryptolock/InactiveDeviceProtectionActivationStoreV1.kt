// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.security.cryptolock

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets

internal sealed class ActivationStoreReadResult {
    data object Absent : ActivationStoreReadResult()

    data class Present(val record: InactiveDeviceActivationRecordV1) :
        ActivationStoreReadResult()

    data object Corrupt : ActivationStoreReadResult()
}

/**
 * Durable single-transaction activation journal.
 *
 * A damaged/unreadable record is never treated as INACTIVE. The journal contains only wrapped
 * master-key records and non-secret transaction state, and is excluded from Android backup/device
 * transfer because its meaning is installation-bound.
 */
internal class InactiveDeviceProtectionActivationStoreV1(
    context: Context,
    private val journalAead: ActivationJournalAeadV1 = ActivationJournalAeadV1(),
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun read(): ActivationStoreReadResult {
        val encoded = preferences.getString(RECORD_KEY, null)
            ?: return ActivationStoreReadResult.Absent
        return try {
            val envelope = Base64.decode(encoded, Base64.NO_WRAP)
            val plaintext = journalAead.decrypt(envelope, ASSOCIATED_DATA)
            try {
                ActivationStoreReadResult.Present(
                    InactiveDeviceActivationRecordCodecV1.decode(plaintext),
                )
            } finally {
                envelope.fill(0)
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            ActivationStoreReadResult.Corrupt
        }
    }

    @Synchronized
    fun write(record: InactiveDeviceActivationRecordV1): Boolean =
        try {
            val plaintext = InactiveDeviceActivationRecordCodecV1.encode(record)
            val envelope = journalAead.encrypt(plaintext, ASSOCIATED_DATA)
            try {
                preferences.edit()
                    .putString(RECORD_KEY, Base64.encodeToString(envelope, Base64.NO_WRAP))
                    .commit()
            } finally {
                plaintext.fill(0)
                envelope.fill(0)
            }
        } catch (_: Exception) {
            false
        }

    @Synchronized
    fun clear(): Boolean =
        preferences.edit().remove(RECORD_KEY).commit()

    private companion object {
        const val PREFERENCES_NAME = "inactive_device_activation_v1"
        const val RECORD_KEY = "activation"
        val ASSOCIATED_DATA =
            "NeoCont|InactiveDeviceActivationJournal|v1"
                .toByteArray(StandardCharsets.UTF_8)
    }
}
