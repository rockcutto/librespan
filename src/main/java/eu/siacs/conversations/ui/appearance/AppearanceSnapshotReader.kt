package eu.siacs.conversations.ui.appearance

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.google.android.material.color.DynamicColors
import eu.siacs.conversations.utils.ThemeHelper

/** Android observation bridge. Persistence compatibility belongs to [AppearanceRepository]. */
object AppearanceSnapshotReader {
    fun from(context: Context): AppearanceState {
        val settings = AppearanceRepository(context).readSettings()
        val systemIsDark = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return AppearanceStateResolver.resolve(
            settings,
            AppearanceEnvironment(
                systemIsDark = systemIsDark,
                automaticFollowsSystem = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                dynamicColorsAvailable = DynamicColors.isDynamicColorAvailable(),
                legacyThemeStyle = ThemeHelper.find(context),
                legacyThemeOverrideStyle = ThemeHelper.findThemeOverrideStyle(context),
            ),
        )
    }
}
