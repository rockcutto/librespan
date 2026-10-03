package eu.siacs.conversations.ui.appearance

import android.content.Context
import android.graphics.Color
import androidx.preference.PreferenceManager
import eu.siacs.conversations.AppSettings
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.SettingsActivity

/** Compatibility boundary for persisted appearance choices. */
class AppearanceRepository(private val context: Context) {
    private val preferences get() = PreferenceManager.getDefaultSharedPreferences(context)

    fun readSettings(): AppearanceSettings {
        val textScale = readLegacyTextScale()
        return AppearanceSettings(
            themeMode = LegacyThemeModeCodec.decode(
                preferences.getString(AppSettings.THEME, context.getString(R.string.theme)),
            ),
            dynamicColorsRequested = preferences.getBoolean(AppSettings.DYNAMIC_COLORS, false),
            customAccentColor = preferences
                .getInt(SettingsActivity.THEME_OVERRIDE_COLOR, NO_CUSTOM_ACCENT)
                .takeUnless { it == NO_CUSTOM_ACCENT },
            colorfulChatBubbles = preferences.getBoolean(
                AppSettings.COLORFUL_CHAT_BUBBLES,
                context.resources.getBoolean(R.bool.use_green_background),
            ),
            textScale = textScale,
            messageTextSizeSp = readMessageTextSizeSp(textScale),
        )
    }

    fun setThemeMode(value: ThemeMode) {
        preferences.edit().putString(AppSettings.THEME, LegacyThemeModeCodec.encode(value)).apply()
    }

    fun setDynamicColorsRequested(value: Boolean) {
        preferences.edit().putBoolean(AppSettings.DYNAMIC_COLORS, value).apply()
    }

    fun setCustomAccentColor(value: Int?) {
        preferences.edit().putInt(SettingsActivity.THEME_OVERRIDE_COLOR, value ?: NO_CUSTOM_ACCENT).apply()
    }

    fun setNeoContColors() {
        preferences.edit()
            .putBoolean(AppSettings.DYNAMIC_COLORS, false)
            .putInt(SettingsActivity.THEME_OVERRIDE_COLOR, NO_CUSTOM_ACCENT)
            .apply()
    }

    fun setSystemColors() {
        preferences.edit()
            .putBoolean(AppSettings.DYNAMIC_COLORS, true)
            .putInt(SettingsActivity.THEME_OVERRIDE_COLOR, NO_CUSTOM_ACCENT)
            .apply()
    }

    fun setCustomAccent(value: Int) {
        preferences.edit()
            .putBoolean(AppSettings.DYNAMIC_COLORS, false)
            .putInt(SettingsActivity.THEME_OVERRIDE_COLOR, value)
            .apply()
    }

    fun setColorfulChatBubbles(value: Boolean) {
        preferences.edit().putBoolean(AppSettings.COLORFUL_CHAT_BUBBLES, value).apply()
    }

    fun migrateLegacyAccentIfNeeded() {
        val current = preferences.getInt(SettingsActivity.THEME_OVERRIDE_COLOR, NO_CUSTOM_ACCENT)
        if (current == NO_CUSTOM_ACCENT) return

        val v3 = context.resources.getStringArray(R.array.themeAccentColorsV3)
        if (v3.any { Color.parseColor(it) == current }) return

        val v2 = context.resources.getStringArray(R.array.themeAccentColorsV2)
        val legacy = context.resources.getStringArray(R.array.themeColorsOverride)
        val index = sequenceOf(v2, legacy)
            .map { palette -> palette.indexOfFirst { Color.parseColor(it) == current } }
            .firstOrNull { it >= 0 }
            ?: return
        if (index >= v3.size) return

        preferences.edit()
            .putInt(SettingsActivity.THEME_OVERRIDE_COLOR, Color.parseColor(v3[index]))
            .apply()
    }

    /** Migrates the old four text presets into the continuous 12..32sp canonical value once. */
    fun migrateLegacyTextScaleIfNeeded() {
        if (preferences.contains(MESSAGE_TEXT_SIZE_SP)) return
        val legacyScale = readLegacyTextScale()
        preferences.edit()
            .putFloat(
                MESSAGE_TEXT_SIZE_SP,
                TypographyPolicy.sizeSp(TypographyRole.MESSAGE, legacyScale),
            )
            .apply()
    }

    fun setMessageTextSizeSp(value: Float) {
        val normalized = TypographyPolicy.normalizeMessageTextSp(value)
        val legacyScale = TypographyPolicy.legacyScaleForMessageSp(normalized)
        preferences.edit()
            .putFloat(MESSAGE_TEXT_SIZE_SP, normalized)
            .putString(TEXT_SCALE, TextScaleCodec.encode(legacyScale))
            .putBoolean(
                AppSettings.LARGE_FONT,
                legacyScale == TextScale.LARGE || legacyScale == TextScale.EXTRA_LARGE,
            )
            .apply()
    }

    private fun readLegacyTextScale(): TextScale {
        if (preferences.contains(MESSAGE_TEXT_SIZE_SP)) {
            return TypographyPolicy.legacyScaleForMessageSp(
                preferences.getFloat(MESSAGE_TEXT_SIZE_SP, TypographyPolicy.DEFAULT_MESSAGE_TEXT_SP),
            )
        }
        return TextScaleCodec.decode(preferences.getString(TEXT_SCALE, null))
            ?: if (
                preferences.getBoolean(
                    AppSettings.LARGE_FONT,
                    context.resources.getBoolean(R.bool.large_font),
                )
            ) {
                TextScale.LARGE
            } else {
                TextScale.DEFAULT
            }
    }

    private fun readMessageTextSizeSp(legacyScale: TextScale): Float {
        val value = if (preferences.contains(MESSAGE_TEXT_SIZE_SP)) {
            preferences.getFloat(MESSAGE_TEXT_SIZE_SP, TypographyPolicy.DEFAULT_MESSAGE_TEXT_SP)
        } else {
            TypographyPolicy.sizeSp(TypographyRole.MESSAGE, legacyScale)
        }
        return TypographyPolicy.normalizeMessageTextSp(value)
    }

    private companion object {
        const val NO_CUSTOM_ACCENT = -1
        const val TEXT_SCALE = "text_scale"
        const val MESSAGE_TEXT_SIZE_SP = "message_text_size_sp"
    }
}
