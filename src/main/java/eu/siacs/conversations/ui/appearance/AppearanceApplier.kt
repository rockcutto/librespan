package eu.siacs.conversations.ui.appearance

/** Deterministic compatibility input for Android theme application. */
data class AppearanceApplyPlan(
    val baseThemeStyle: Int,
    val overrideThemeStyle: Int?,
    val dynamicColorsActive: Boolean,
    val oled: Boolean,
)

/**
 * Android application boundary. Current Activity/Application callers and ThemeHelper remain the
 * compatibility executors until lifecycle verification proves a cutover safe.
 */
object AppearanceApplier {
    fun plan(state: AppearanceState) = AppearanceApplyPlan(
        baseThemeStyle = state.effectiveThemeStyle,
        overrideThemeStyle = state.effectiveThemeOverrideStyle,
        dynamicColorsActive = state.dynamicColorsActive,
        oled = state.isOled,
    )
}
