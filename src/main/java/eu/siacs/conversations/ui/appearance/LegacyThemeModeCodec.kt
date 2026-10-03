package eu.siacs.conversations.ui.appearance

/** Pure compatibility codec for the values persisted by the existing theme preference. */
object LegacyThemeModeCodec {
    fun decode(value: String?): ThemeMode = when (value) {
        SYSTEM_VALUE -> ThemeMode.SYSTEM
        LIGHT_VALUE -> ThemeMode.LIGHT
        DARK_VALUE -> ThemeMode.DARK
        OLED_VALUE -> ThemeMode.OLED
        else -> ThemeMode.LIGHT
    }

    fun encode(themeMode: ThemeMode): String = when (themeMode) {
        ThemeMode.SYSTEM -> SYSTEM_VALUE
        ThemeMode.LIGHT -> LIGHT_VALUE
        ThemeMode.DARK -> DARK_VALUE
        ThemeMode.OLED -> OLED_VALUE
    }

    const val SYSTEM_VALUE = "automatic"
    const val LIGHT_VALUE = "light"
    const val DARK_VALUE = "dark"
    const val OLED_VALUE = "oledblack"
}
