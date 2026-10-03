package eu.siacs.conversations.ui.appearance

/** The visual palette currently selected after resolving the user's requested [ThemeMode]. */
enum class EffectiveTheme {
    LIGHT,
    DARK,
    OLED,
}

/**
 * Immutable observational snapshot of the existing appearance implementation.
 *
 * Style ids are retained so callers can compare this snapshot with the legacy ThemeHelper result;
 * this class does not apply them.
 */
data class AppearanceState(
    val settings: AppearanceSettings,
    val effectiveTheme: EffectiveTheme,
    val dynamicColorsAvailable: Boolean,
    val dynamicColorsActive: Boolean,
    val effectiveCustomAccentColor: Int?,
    val effectiveThemeStyle: Int,
    val effectiveThemeOverrideStyle: Int?,
    val textScale: TextScale,
) {
    val messageTextSizeSp: Float
        get() = settings.messageTextSizeSp

    val isDark: Boolean
        get() = effectiveTheme != EffectiveTheme.LIGHT

    val isOled: Boolean
        get() = effectiveTheme == EffectiveTheme.OLED
}
