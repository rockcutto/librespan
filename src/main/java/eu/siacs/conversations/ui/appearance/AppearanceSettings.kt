package eu.siacs.conversations.ui.appearance

/** Immutable record of user-facing appearance choices. */
data class AppearanceSettings(
    val themeMode: ThemeMode,
    val dynamicColorsRequested: Boolean,
    val customAccentColor: Int?,
    val colorfulChatBubbles: Boolean,
    val textScale: TextScale,
    val messageTextSizeSp: Float = TypographyPolicy.DEFAULT_MESSAGE_TEXT_SP,
)
