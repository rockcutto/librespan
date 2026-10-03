package eu.siacs.conversations.security.applock

import android.content.Context
import android.os.SystemClock

/**
 * Process-local application lock coordinator.
 *
 * This class intentionally owns UI lock state only. It does not hold message plaintext, Secure
 * Content keys, database passphrases or recovery secrets. Future inactivity/key policy can call
 * [forceLock] and can attach key unwrap/retirement work to the same unlock-success boundary without
 * changing Activity gating.
 */
object AppLockController {
    const val PREFERENCE_ENABLED = "app_lock_enabled"
    const val PREFERENCE_BACKGROUND_TIMEOUT_MS = "app_lock_background_timeout_ms"

    private val monitor = Any()

    @Volatile
    private var policyStore: AppLockPolicyStore? = null

    @Volatile
    private var stateMachine: AppLockStateMachine? = null

    @Volatile
    private var unlockGate: AppUnlockGate = UiOnlyAppUnlockGate

    @JvmStatic
    fun initialize(context: Context) {
        if (stateMachine != null) return
        synchronized(monitor) {
            if (stateMachine != null) return
            val store = AppLockPolicyStore(context.applicationContext)
            policyStore = store
            stateMachine = AppLockStateMachine(store.load())
        }
    }

    @JvmStatic
    fun onProtectedActivityStarted(context: Context): Boolean {
        initialize(context)
        synchronized(monitor) {
            val state = requireNotNull(stateMachine)
            val needsUnlock = state.onProtectedActivityStarted(SystemClock.elapsedRealtime())
            return needsUnlock && state.beginUnlockUi()
        }
    }

    @JvmStatic
    fun onProtectedActivityStopped(context: Context) {
        initialize(context)
        synchronized(monitor) {
            requireNotNull(stateMachine).onProtectedActivityStopped(SystemClock.elapsedRealtime())
        }
    }

    /**
     * Completes an already successful platform-authentication event.
     *
     * Today the default gate is UI-only. The future inactive-device crypto profile installs a
     * bounded gate that unwraps the existing App Master Key. If that normal wrapper was invalidated
     * by biometric enrollment or another credential-binding change, the only data-preserving
     * result is RECOVERY_REQUIRED.
     */
    @JvmStatic
    fun completeLocalAuthentication(context: Context): AppUnlockOutcome {
        initialize(context)
        val outcome =
            try {
                unlockGate.completeAfterLocalAuthentication(context.applicationContext)
            } catch (_: Exception) {
                AppUnlockOutcome.RECOVERY_REQUIRED
            }
        synchronized(monitor) {
            val state = requireNotNull(stateMachine)
            when (outcome) {
                AppUnlockOutcome.UNLOCKED ->
                    state.markUnlocked(SystemClock.elapsedRealtime())
                AppUnlockOutcome.RECOVERY_REQUIRED ->
                    state.forceLock(AppLockReason.RECOVERY_REQUIRED)
                AppUnlockOutcome.CRYPTO_ERASED ->
                    state.forceLock(AppLockReason.CRYPTO_ERASED)
            }
        }
        return outcome
    }

    internal fun installUnlockGateForRuntime(gate: AppUnlockGate) {
        synchronized(monitor) {
            unlockGate = gate
        }
    }

    @JvmStatic
    fun onUnlockUiDismissed(context: Context) {
        initialize(context)
        synchronized(monitor) {
            requireNotNull(stateMachine).dismissUnlockUi(SystemClock.elapsedRealtime())
        }
    }

    /**
     * Applies an enable/disable choice only after the caller has authenticated the user.
     *
     * Enabling keeps the current authenticated session unlocked. A future process starts locked.
     */
    @JvmStatic
    fun setEnabledAfterAuthentication(
        context: Context,
        enabled: Boolean,
    ) {
        initialize(context)
        synchronized(monitor) {
            val store = requireNotNull(policyStore)
            store.setEnabled(enabled)
            requireNotNull(stateMachine).updatePolicy(
                store.load(),
                authenticatedNow = true,
                now = SystemClock.elapsedRealtime(),
            )
        }
    }

    /**
     * Prevents a trusted OS credential flow from being mistaken for ordinary app inactivity.
     */
    @JvmStatic
    fun beginTrustedExternalAuthentication(context: Context) {
        initialize(context)
        synchronized(monitor) {
            requireNotNull(stateMachine).beginTrustedExternalAuthentication()
        }
    }

    @JvmStatic
    fun endTrustedExternalAuthentication(context: Context) {
        initialize(context)
        synchronized(monitor) {
            requireNotNull(stateMachine).endTrustedExternalAuthentication(
                SystemClock.elapsedRealtime(),
            )
        }
    }

    /**
     * Device credential disappearance is a lock condition, never permission to disable App Lock.
     *
     * With the future auth-bound key hierarchy this same situation may mean the normal master-key
     * wrapper is unavailable. The recovery gate then transitions to RECOVERY_REQUIRED.
     */
    @JvmStatic
    fun onDeviceCredentialUnavailable(context: Context) {
        initialize(context)
        synchronized(monitor) {
            requireNotNull(stateMachine)
                .forceLock(AppLockReason.DEVICE_CREDENTIAL_UNAVAILABLE)
        }
    }

    @JvmStatic
    fun isEnabled(context: Context): Boolean {
        initialize(context)
        synchronized(monitor) {
            return requireNotNull(stateMachine).snapshot().enabled
        }
    }

    /**
     * Future inactivity/key-policy integration point.
     */
    @JvmStatic
    fun forceLock(
        context: Context,
        reason: AppLockReason = AppLockReason.MANUAL,
    ) {
        initialize(context)
        synchronized(monitor) {
            requireNotNull(stateMachine).forceLock(reason)
        }
    }

    @JvmStatic
    fun snapshot(context: Context): AppLockSnapshot {
        initialize(context)
        synchronized(monitor) {
            return requireNotNull(stateMachine).snapshot()
        }
    }
}
