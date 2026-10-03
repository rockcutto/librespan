// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import android.content.Context
import java.nio.charset.StandardCharsets

object ScopedAccountSecretVaultV1 {
    @JvmStatic
    fun storeString(context: Context, accountUuid: String, type: String, scopeId: String, value: String?): Boolean {
        val secretType =
            try { AccountSecretTypeV1.valueOf(type) } catch (_: Exception) { return false }
        val key = AccountSecretKeyV1(accountUuid, secretType, scopeId)
        if (value.isNullOrEmpty()) {
            return when (PersistentAccountSecretVaultV1(context.applicationContext).delete(key)) {
                is AccountSecretVaultResultV1.Success -> true
                is AccountSecretVaultResultV1.Failure -> false
            }
        }
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        return try {
            val vault = PersistentAccountSecretVaultV1(context.applicationContext)
            when (vault.put(key, bytes)) {
                is AccountSecretVaultResultV1.Failure -> false
                is AccountSecretVaultResultV1.Success -> readString(context, accountUuid, type, scopeId) == value
            }
        } finally {
            bytes.fill(0)
        }
    }

    @JvmStatic
    fun readString(context: Context, accountUuid: String, type: String, scopeId: String): String? {
        val secretType =
            try { AccountSecretTypeV1.valueOf(type) } catch (_: Exception) { return null }
        val key = AccountSecretKeyV1(accountUuid, secretType, scopeId)
        return when (val opened = PersistentAccountSecretVaultV1(context.applicationContext).open(key)) {
            is AccountSecretVaultResultV1.Failure -> null
            is AccountSecretVaultResultV1.Success ->
                opened.value?.use { value -> value.useBytes { String(it, StandardCharsets.UTF_8) } }
        }
    }

    @JvmStatic
    fun storeBytes(context: Context, accountUuid: String, type: String, scopeId: String, value: ByteArray?): Boolean {
        val secretType =
            try { AccountSecretTypeV1.valueOf(type) } catch (_: Exception) { return false }
        val key = AccountSecretKeyV1(accountUuid, secretType, scopeId)
        if (value == null || value.isEmpty()) {
            return when (PersistentAccountSecretVaultV1(context.applicationContext).delete(key)) {
                is AccountSecretVaultResultV1.Success -> true
                is AccountSecretVaultResultV1.Failure -> false
            }
        }
        return when (PersistentAccountSecretVaultV1(context.applicationContext).put(key, value)) {
            is AccountSecretVaultResultV1.Failure -> false
            is AccountSecretVaultResultV1.Success -> {
                val opened = readBytes(context, accountUuid, type, scopeId)
                try {
                    opened != null && java.security.MessageDigest.isEqual(value, opened)
                } finally {
                    opened?.fill(0)
                }
            }
        }
    }

    @JvmStatic
    fun readBytes(context: Context, accountUuid: String, type: String, scopeId: String): ByteArray? {
        val secretType =
            try { AccountSecretTypeV1.valueOf(type) } catch (_: Exception) { return null }
        val key = AccountSecretKeyV1(accountUuid, secretType, scopeId)
        return when (val opened = PersistentAccountSecretVaultV1(context.applicationContext).open(key)) {
            is AccountSecretVaultResultV1.Failure -> null
            is AccountSecretVaultResultV1.Success ->
                opened.value?.use { value -> value.useBytes { it.copyOf() } }
        }
    }

    @JvmStatic
    fun isAvailable(context: Context, accountUuid: String): Boolean =
        try {
            // Migration/generation needs write authority, not merely a pre-existing read envelope.
            // ROOT_V1 or ACTIVE High Security may create account material; LOCKED cannot.
            PersistentSecureContentKeyMaterialStore(context.applicationContext)
                .accountSecretAeadForWrite(accountUuid)
            true
        } catch (_: Exception) {
            false
        }
}
