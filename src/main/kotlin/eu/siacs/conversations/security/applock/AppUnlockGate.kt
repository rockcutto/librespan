package eu.siacs.conversations.security.applock

import android.content.Context

/**
 * Result of the post-authentication cryptographic gate.
 *
 * App Lock v1 uses [UNLOCKED] because it is a UI-only lock. The future auth-gated key hierarchy
 * installs an implementation that attempts to restore the existing App Master Key after local
 * authentication. Keystore invalidation or a credential-binding change must return
 * [RECOVERY_REQUIRED] instead of generating replacement key material or bypassing the gate.
 */
enum class AppUnlockOutcome {
    UNLOCKED,
    RECOVERY_REQUIRED,
    CRYPTO_ERASED,
}

internal fun interface AppUnlockGate {
    fun completeAfterLocalAuthentication(context: Context): AppUnlockOutcome
}

internal object UiOnlyAppUnlockGate : AppUnlockGate {
    override fun completeAfterLocalAuthentication(context: Context): AppUnlockOutcome =
        AppUnlockOutcome.UNLOCKED
}
