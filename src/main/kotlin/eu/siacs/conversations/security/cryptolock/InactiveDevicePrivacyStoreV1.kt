// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.security.cryptolock

import android.content.Context
import android.util.Base64

internal class InactiveDevicePrivacyStoreV1(
    context: Context,
    private val aead: InactiveDevicePrivacyStateAeadV1 =
        InactiveDevicePrivacyStateAeadV1(),
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )

    @Synchronized
    fun read(): InactiveDevicePrivacyStoreReadResultV1 {
        if (!preferences.contains(RECORD_KEY)) {
            return InactiveDevicePrivacyStoreReadResultV1.Absent
        }
        val encoded =
            preferences.getString(RECORD_KEY, null)
                ?: return InactiveDevicePrivacyStoreReadResultV1.Corrupt
        return try {
            val envelope = Base64.decode(encoded, Base64.NO_WRAP)
            val plaintext =
                try {
                    aead.decrypt(
                        envelope,
                        InactiveDevicePrivacyStateAeadV1.ASSOCIATED_DATA,
                    )
                } finally {
                    envelope.fill(0)
                }
            try {
                InactiveDevicePrivacyStoreReadResultV1.Present(
                    InactiveDevicePrivacyRecordCodecV1.decode(plaintext),
                )
            } finally {
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            InactiveDevicePrivacyStoreReadResultV1.Corrupt
        }
    }

    @Synchronized
    fun write(record: InactiveDevicePrivacyRecordV1): Boolean {
        val plaintext = InactiveDevicePrivacyRecordCodecV1.encode(record)
        return try {
            val envelope =
                aead.encrypt(
                    plaintext,
                    InactiveDevicePrivacyStateAeadV1.ASSOCIATED_DATA,
                )
            try {
                preferences.edit()
                    .putString(
                        RECORD_KEY,
                        Base64.encodeToString(envelope, Base64.NO_WRAP),
                    )
                    .commit()
            } finally {
                envelope.fill(0)
            }
        } catch (_: Exception) {
            false
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    fun clear(): Boolean =
        preferences.edit().remove(RECORD_KEY).commit()

    private companion object {
        const val PREFERENCES_NAME = "inactive_device_privacy_v1"
        const val RECORD_KEY = "record"
    }
}
