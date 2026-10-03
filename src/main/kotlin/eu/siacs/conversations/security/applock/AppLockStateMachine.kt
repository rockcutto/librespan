package eu.siacs.conversations.security.applock

internal class AppLockStateMachine(
    initialPolicy: AppLockPolicy,
) {
    private var policy: AppLockPolicy = initialPolicy
    private var locked: Boolean = initialPolicy.enabled
    private var reason: AppLockReason? =
        if (initialPolicy.enabled) AppLockReason.PROCESS_START else null
    private var protectedActivityCount: Int = 0
    private var unlockUiActive: Boolean = false
    private var trustedExternalAuthDepth: Int = 0
    private var lastUnlockedElapsedRealtime: Long? = null
    private var lastBackgroundElapsedRealtime: Long? = null

    fun onProtectedActivityStarted(now: Long): Boolean {
        if (protectedActivityCount == 0) {
            val backgroundAt = lastBackgroundElapsedRealtime
            if (policy.enabled &&
                !locked &&
                !unlockUiActive &&
                trustedExternalAuthDepth == 0 &&
                backgroundAt != null &&
                now - backgroundAt >= policy.backgroundTimeoutMs
            ) {
                locked = true
                reason = AppLockReason.BACKGROUND_TIMEOUT
            }
            lastBackgroundElapsedRealtime = null
        }
        protectedActivityCount++
        return policy.enabled && locked && !unlockUiActive
    }

    fun onProtectedActivityStopped(now: Long) {
        if (protectedActivityCount > 0) {
            protectedActivityCount--
        }
        if (protectedActivityCount == 0 &&
            !unlockUiActive &&
            trustedExternalAuthDepth == 0
        ) {
            lastBackgroundElapsedRealtime = now
        }
    }

    fun beginUnlockUi(): Boolean {
        if (!policy.enabled || !locked || unlockUiActive) {
            return false
        }
        unlockUiActive = true
        return true
    }

    fun markUnlocked(now: Long) {
        locked = false
        reason = null
        unlockUiActive = false
        lastBackgroundElapsedRealtime = null
        lastUnlockedElapsedRealtime = now
    }

    fun dismissUnlockUi(now: Long) {
        unlockUiActive = false
        if (policy.enabled) {
            locked = true
            if (protectedActivityCount == 0 && trustedExternalAuthDepth == 0) {
                lastBackgroundElapsedRealtime = now
            }
        }
    }

    fun updatePolicy(
        updated: AppLockPolicy,
        authenticatedNow: Boolean,
        now: Long,
    ) {
        policy = updated
        if (!updated.enabled) {
            locked = false
            reason = null
            unlockUiActive = false
            lastBackgroundElapsedRealtime = null
            return
        }
        if (authenticatedNow) {
            markUnlocked(now)
        } else {
            locked = true
            reason = AppLockReason.POLICY_CHANGED
        }
    }

    fun beginTrustedExternalAuthentication() {
        trustedExternalAuthDepth++
    }

    fun endTrustedExternalAuthentication(now: Long) {
        if (trustedExternalAuthDepth > 0) {
            trustedExternalAuthDepth--
        }
        if (trustedExternalAuthDepth == 0 &&
            protectedActivityCount == 0 &&
            !unlockUiActive
        ) {
            lastBackgroundElapsedRealtime = now
        }
    }

    fun forceLock(lockReason: AppLockReason) {
        if (!policy.enabled) {
            return
        }
        locked = true
        reason = lockReason
    }

    fun snapshot(): AppLockSnapshot =
        AppLockSnapshot(
            enabled = policy.enabled,
            locked = policy.enabled && locked,
            reason = reason,
            lastUnlockedElapsedRealtime = lastUnlockedElapsedRealtime,
            lastBackgroundElapsedRealtime = lastBackgroundElapsedRealtime,
        )
}
