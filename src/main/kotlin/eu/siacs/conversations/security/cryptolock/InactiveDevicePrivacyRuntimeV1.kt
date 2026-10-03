// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.security.cryptolock

import android.content.Context

/**
 * Durable 24h inactivity policy for an ACTIVE inactive-device crypto profile.
 *
 * Ordinary App Lock is intentionally independent. PRIVACY_SUSPENDED is sticky until a successful
 * crypto-session unlock explicitly calls [onCryptoSessionUnlocked].
 */
object InactiveDevicePrivacyRuntimeV1 {
    private val monitor = Any()
    private var protectedForegroundCount = 0

    @JvmStatic
    fun onProtectedActivityStarted(context: Context): Boolean =
        synchronized(monitor) {
            val applicationContext = context.applicationContext
            val now = System.currentTimeMillis()

            // A foreground transition must not refresh an already-expired 24h window merely
            // because the process still has an active AMK in RAM. Check the durable deadline
            // before this first foreground Activity becomes authenticated activity.
            if (protectedForegroundCount == 0 &&
                evaluateLocked(
                    applicationContext,
                    now,
                    authenticatedForeground = false,
                )
            ) {
                protectedForegroundCount++
                return@synchronized true
            }

            protectedForegroundCount++
            evaluateLocked(
                applicationContext,
                now,
                authenticatedForeground = cryptoSessionIsActive(context),
            )
        }

    @JvmStatic
    fun onProtectedActivityStopped(context: Context) {
        synchronized(monitor) {
            if (protectedForegroundCount > 0) {
                protectedForegroundCount--
            }
            if (protectedForegroundCount == 0 && cryptoSessionIsActive(context)) {
                recordAuthenticatedActivityLocked(
                    context.applicationContext,
                    System.currentTimeMillis(),
                    force = true,
                )
            }
        }
    }

    @JvmStatic
    fun evaluateForService(context: Context): Boolean =
        synchronized(monitor) {
            val authenticatedForeground =
                protectedForegroundCount > 0 && cryptoSessionIsActive(context)
            evaluateLocked(
                context.applicationContext,
                System.currentTimeMillis(),
                authenticatedForeground,
                suspendCrypto = false,
            )
        }

    @JvmStatic
    fun isPrivacySuspended(context: Context): Boolean =
        synchronized(monitor) {
            evaluateLocked(
                context.applicationContext,
                System.currentTimeMillis(),
                authenticatedForeground = false,
            )
        }

    /**
     * First activation is the only unauthenticated creation of an ACTIVE privacy record: it is
     * called while the activation transaction still owns the verified App Master Key.
     */
    internal fun onCryptoSessionActivated(context: Context) {
        synchronized(monitor) {
            val now = System.currentTimeMillis()
            InactiveDevicePrivacyStoreV1(context.applicationContext)
                .write(InactiveDevicePrivacyPolicyV1.active(now))
        }
    }

    /**
     * A successful normal/recovery crypto unwrap is authoritative permission to leave
     * PRIVACY_SUSPENDED and start a fresh 24h inactivity window.
     */
    internal fun onCryptoSessionUnlocked(context: Context) {
        synchronized(monitor) {
            val now = System.currentTimeMillis()
            InactiveDevicePrivacyStoreV1(context.applicationContext)
                .write(InactiveDevicePrivacyPolicyV1.active(now))
        }
    }

    private fun evaluateLocked(
        context: Context,
        now: Long,
        authenticatedForeground: Boolean,
        suspendCrypto: Boolean = true,
    ): Boolean {
        if (!durableProfileIsActive(context)) {
            return false
        }

        val store = InactiveDevicePrivacyStoreV1(context)
        val read = store.read()
        val record =
            when (read) {
                InactiveDevicePrivacyStoreReadResultV1.Absent,
                InactiveDevicePrivacyStoreReadResultV1.Corrupt -> {
                    val suspended =
                        InactiveDevicePrivacyPolicyV1.suspended(
                            previous = null,
                            now = now.coerceAtLeast(1L),
                        )
                    store.write(suspended)
                    if (suspendCrypto) {
                        SecureContentCryptoSessionRuntimeV1.suspendForPrivacy(context)
                    }
                    return true
                }
                is InactiveDevicePrivacyStoreReadResultV1.Present -> read.record
            }

        if (
            InactiveDevicePrivacyPolicyV1.shouldSuspend(
                record,
                now,
                authenticatedForeground,
            )
        ) {
            val suspended =
                if (record.state == InactiveDevicePrivacyStateV1.PRIVACY_SUSPENDED) {
                    record
                } else {
                    InactiveDevicePrivacyPolicyV1.suspended(record, now.coerceAtLeast(1L))
                }
            if (suspended !== record) {
                store.write(suspended)
            }
            if (suspendCrypto) {
                SecureContentCryptoSessionRuntimeV1.suspendForPrivacy(context)
            }
            return true
        }

        if (authenticatedForeground) {
            recordAuthenticatedActivityLocked(context, now, force = false, current = record)
        }
        return false
    }

    private fun recordAuthenticatedActivityLocked(
        context: Context,
        now: Long,
        force: Boolean,
        current: InactiveDevicePrivacyRecordV1? = null,
    ) {
        if (!durableProfileIsActive(context) || !cryptoSessionIsActive(context)) {
            return
        }
        val store = InactiveDevicePrivacyStoreV1(context)
        val record =
            current
                ?: when (val read = store.read()) {
                    is InactiveDevicePrivacyStoreReadResultV1.Present -> read.record
                    else -> null
                }
        if (record?.state == InactiveDevicePrivacyStateV1.PRIVACY_SUSPENDED) {
            return
        }
        if (
            !force &&
            record != null &&
            now >= record.lastUserActivityAt &&
            now - record.lastUserActivityAt <
                InactiveDevicePrivacyPolicyV1.FOREGROUND_TOUCH_INTERVAL_MILLIS
        ) {
            return
        }
        store.write(InactiveDevicePrivacyPolicyV1.active(now.coerceAtLeast(1L)))
    }

    private fun cryptoSessionIsActive(context: Context): Boolean =
        SecureContentCryptoSessionRuntimeV1.snapshot(context).state ==
            SecureContentCryptoSessionStateV1.ACTIVE

    private fun durableProfileIsActive(context: Context): Boolean =
        when (
            val read =
                InactiveDeviceProtectionActivationStoreV1(
                    context.applicationContext,
                ).read()
        ) {
            is ActivationStoreReadResult.Present ->
                read.record.phase == InactiveDeviceActivationPhase.ACTIVE
            else -> false
        }
}
