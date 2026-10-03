package eu.siacs.conversations.ui.appearance

/** The user's persisted theme selection, normalized without writing it back. */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK, OLED;

    companion object {
        fun fromLegacy(value: String?): ThemeMode = LegacyThemeModeCodec.decode(value)
    }
}
