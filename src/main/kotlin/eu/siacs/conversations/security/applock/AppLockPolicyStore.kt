package eu.siacs.conversations.security.applock

import android.content.Context
import android.preference.PreferenceManager

internal class AppLockPolicyStore(context: Context) {
    private val preferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    fun load(): AppLockPolicy =
        AppLockPolicy(
            enabled = preferences.getBoolean(AppLockController.PREFERENCE_ENABLED, false),
            backgroundTimeoutMs =
                preferences
                    .getLong(
                        AppLockController.PREFERENCE_BACKGROUND_TIMEOUT_MS,
                        AppLockPolicy.DEFAULT_BACKGROUND_TIMEOUT_MS,
                    )
                    .coerceAtLeast(0L),
        )

    fun setEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(AppLockController.PREFERENCE_ENABLED, enabled)
            .apply()
    }
}
