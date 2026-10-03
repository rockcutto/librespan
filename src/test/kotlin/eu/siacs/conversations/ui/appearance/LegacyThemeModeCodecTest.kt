package eu.siacs.conversations.ui.appearance

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyThemeModeCodecTest {
    @Test fun decodesLegacyValues() {
        assertEquals(ThemeMode.SYSTEM, LegacyThemeModeCodec.decode("automatic"))
        assertEquals(ThemeMode.LIGHT, LegacyThemeModeCodec.decode("light"))
        assertEquals(ThemeMode.DARK, LegacyThemeModeCodec.decode("dark"))
        assertEquals(ThemeMode.OLED, LegacyThemeModeCodec.decode("oledblack"))
    }

    @Test fun encodesLegacyValues() {
        assertEquals("automatic", LegacyThemeModeCodec.encode(ThemeMode.SYSTEM))
        assertEquals("light", LegacyThemeModeCodec.encode(ThemeMode.LIGHT))
        assertEquals("dark", LegacyThemeModeCodec.encode(ThemeMode.DARK))
        assertEquals("oledblack", LegacyThemeModeCodec.encode(ThemeMode.OLED))
    }

    @Test fun unknownValueUsesLegacyLightFallback() {
        assertEquals(ThemeMode.LIGHT, LegacyThemeModeCodec.decode("future-theme"))
        assertEquals(ThemeMode.LIGHT, LegacyThemeModeCodec.decode(null))
    }
}
