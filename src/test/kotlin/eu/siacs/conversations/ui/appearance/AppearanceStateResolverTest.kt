package eu.siacs.conversations.ui.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceStateResolverTest {
    @Test
    fun automaticUsesSystemLightAndDarkWhenSupported() {
        assertEquals(EffectiveTheme.LIGHT, resolve(ThemeMode.SYSTEM, systemIsDark = false).effectiveTheme)
        assertEquals(EffectiveTheme.DARK, resolve(ThemeMode.SYSTEM, systemIsDark = true).effectiveTheme)
    }

    @Test
    fun automaticFallsBackToLegacyLightWhenSystemFollowingIsUnsupported() {
        assertEquals(
            EffectiveTheme.LIGHT,
            resolve(ThemeMode.SYSTEM, systemIsDark = true, automaticFollowsSystem = false)
                .effectiveTheme,
        )
    }

    @Test
    fun explicitThemesResolveToTheirVisualPalette() {
        assertEquals(EffectiveTheme.LIGHT, resolve(ThemeMode.LIGHT).effectiveTheme)
        assertEquals(EffectiveTheme.DARK, resolve(ThemeMode.DARK).effectiveTheme)
        assertEquals(EffectiveTheme.OLED, resolve(ThemeMode.OLED).effectiveTheme)
    }

    @Test
    fun dynamicRequestRemainsDistinctFromCapabilityAndSuppressesCustomAccentOnlyWhenActive() {
        val unavailable = resolve(ThemeMode.LIGHT, dynamicRequested = true, dynamicAvailable = false)
        assertTrue(unavailable.settings.dynamicColorsRequested)
        assertFalse(unavailable.dynamicColorsActive)
        assertEquals(CUSTOM_ACCENT, unavailable.effectiveCustomAccentColor)

        val active = resolve(ThemeMode.LIGHT, dynamicRequested = true, dynamicAvailable = true)
        assertTrue(active.dynamicColorsActive)
        assertNull(active.effectiveCustomAccentColor)
    }

    @Test
    fun disabledDynamicColorsLeaveSupportedCustomAccentEffective() {
        val state = resolve(ThemeMode.DARK, dynamicRequested = false, dynamicAvailable = true)

        assertFalse(state.dynamicColorsActive)
        assertEquals(CUSTOM_ACCENT, state.effectiveCustomAccentColor)
        assertEquals(OVERRIDE_STYLE, state.effectiveThemeOverrideStyle)
    }

    @Test
    fun largeFontRepresentsOnlyExistingMessageTextChoice() {
        assertEquals(TextScale.DEFAULT, resolve(ThemeMode.LIGHT).textScale)
        assertEquals(
            TextScale.LARGE,
            resolve(ThemeMode.LIGHT, largeMessageFont = true).textScale,
        )
    }

    @Test
    fun textScaleCodecUsesOnlyCanonicalPersistedValues() {
        assertEquals(TextScale.SMALL, TextScaleCodec.decode("small"))
        assertEquals(TextScale.DEFAULT, TextScaleCodec.decode("default"))
        assertEquals(TextScale.LARGE, TextScaleCodec.decode("large"))
        assertEquals(TextScale.EXTRA_LARGE, TextScaleCodec.decode("extra_large"))
        assertNull(TextScaleCodec.decode("unknown"))
    }

    @Test
    fun bubblePreferenceFlagsRemainAvailableInRequestedSettings() {
        val disabled = resolve(ThemeMode.LIGHT, colorfulChatBubbles = false)
        val enabled = resolve(ThemeMode.LIGHT, colorfulChatBubbles = true)

        assertFalse(disabled.settings.colorfulChatBubbles)
        assertTrue(enabled.settings.colorfulChatBubbles)
    }

    @Test
    fun unknownLegacyThemeSafelyMatchesLegacyVisualLightFallback() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromLegacy("future-theme"))
        assertEquals(EffectiveTheme.LIGHT, resolve(ThemeMode.fromLegacy("future-theme")).effectiveTheme)
    }

    private fun resolve(
        themeMode: ThemeMode,
        systemIsDark: Boolean = false,
        automaticFollowsSystem: Boolean = true,
        dynamicRequested: Boolean = false,
        dynamicAvailable: Boolean = false,
        largeMessageFont: Boolean = false,
        colorfulChatBubbles: Boolean = false,
    ): AppearanceState =
        AppearanceStateResolver.resolve(
            AppearanceSettings(
                themeMode = themeMode,
                dynamicColorsRequested = dynamicRequested,
                customAccentColor = CUSTOM_ACCENT,
                colorfulChatBubbles = colorfulChatBubbles,
                textScale = if (largeMessageFont) TextScale.LARGE else TextScale.DEFAULT,
            ),
            AppearanceEnvironment(
                systemIsDark = systemIsDark,
                automaticFollowsSystem = automaticFollowsSystem,
                dynamicColorsAvailable = dynamicAvailable,
                legacyThemeStyle = THEME_STYLE,
                legacyThemeOverrideStyle = OVERRIDE_STYLE,
            ),
        )

    private companion object {
        const val CUSTOM_ACCENT = 0xff3c464d.toInt()
        const val THEME_STYLE = 101
        const val OVERRIDE_STYLE = 202
    }
}
