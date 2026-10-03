package eu.siacs.conversations.ui.appearance

import android.content.Context

/** Typed coordinator for appearance actions; it owns no Views or lifecycle. */
class AppearanceController(
    private val repository: AppearanceRepository,
    private val readState: () -> AppearanceState,
) {
    fun selectTheme(mode: ThemeMode): AppearanceState = mutate { repository.setThemeMode(mode) }
    fun setDynamicColorsRequested(requested: Boolean): AppearanceState = mutate { repository.setDynamicColorsRequested(requested) }
    fun setCustomAccentColor(color: Int?): AppearanceState = mutate { repository.setCustomAccentColor(color) }
    fun setColorfulChatBubbles(enabled: Boolean): AppearanceState = mutate { repository.setColorfulChatBubbles(enabled) }
    fun setMessageTextSizeSp(value: Float): AppearanceState =
        mutate { repository.setMessageTextSizeSp(value) }
    fun migrateLegacyTextScaleIfNeeded(): AppearanceState =
        mutate { repository.migrateLegacyTextScaleIfNeeded() }
    fun migrateLegacyAccentIfNeeded(): AppearanceState =
        mutate { repository.migrateLegacyAccentIfNeeded() }
    fun selectNeoContColors(): AppearanceState = mutate { repository.setNeoContColors() }
    fun selectSystemColors(): AppearanceState = mutate { repository.setSystemColors() }
    fun selectCustomAccent(color: Int): AppearanceState = mutate { repository.setCustomAccent(color) }

    constructor(context: Context) : this(AppearanceRepository(context), { AppearanceSnapshotReader.from(context) })

    private fun mutate(change: () -> Unit): AppearanceState { change(); return readState() }
}
