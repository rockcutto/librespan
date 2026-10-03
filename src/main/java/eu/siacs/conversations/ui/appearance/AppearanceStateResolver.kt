package eu.siacs.conversations.ui.appearance

data class AppearanceEnvironment(
    val systemIsDark: Boolean,
    val automaticFollowsSystem: Boolean,
    val dynamicColorsAvailable: Boolean,
    val legacyThemeStyle: Int,
    val legacyThemeOverrideStyle: Int?,
)

object AppearanceStateResolver {
    fun resolve(settings: AppearanceSettings, environment: AppearanceEnvironment): AppearanceState {
        val effectiveTheme = when (settings.themeMode) {
            ThemeMode.SYSTEM -> if (environment.automaticFollowsSystem && environment.systemIsDark) EffectiveTheme.DARK else EffectiveTheme.LIGHT
            ThemeMode.LIGHT -> EffectiveTheme.LIGHT
            ThemeMode.DARK -> EffectiveTheme.DARK
            ThemeMode.OLED -> EffectiveTheme.OLED
        }
        val dynamicColorsActive = settings.dynamicColorsRequested && environment.dynamicColorsAvailable
        val effectiveCustomAccentColor = if (!dynamicColorsActive && environment.legacyThemeOverrideStyle != null) settings.customAccentColor else null
        return AppearanceState(settings, effectiveTheme, environment.dynamicColorsAvailable, dynamicColorsActive, effectiveCustomAccentColor, environment.legacyThemeStyle, environment.legacyThemeOverrideStyle, settings.textScale)
    }
}
