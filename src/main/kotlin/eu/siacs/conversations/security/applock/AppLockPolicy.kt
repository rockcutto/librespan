package eu.siacs.conversations.security.applock

data class AppLockPolicy(
    val enabled: Boolean,
    val backgroundTimeoutMs: Long,
) {
    init {
        require(backgroundTimeoutMs >= 0L)
    }

    companion object {
        const val DEFAULT_BACKGROUND_TIMEOUT_MS: Long = 30_000L
    }
}

enum class AppLockReason {
    PROCESS_START,
    BACKGROUND_TIMEOUT,
    MANUAL,
    INACTIVITY_POLICY,
    POLICY_CHANGED,
    DEVICE_CREDENTIAL_UNAVAILABLE,
    RECOVERY_REQUIRED,
    CRYPTO_ERASED,
}

data class AppLockSnapshot(
    val enabled: Boolean,
    val locked: Boolean,
    val reason: AppLockReason?,
    val lastUnlockedElapsedRealtime: Long?,
    val lastBackgroundElapsedRealtime: Long?,
)
