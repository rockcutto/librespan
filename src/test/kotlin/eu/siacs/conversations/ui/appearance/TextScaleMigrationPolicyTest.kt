package eu.siacs.conversations.ui.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextScaleMigrationPolicyTest {
    @Test
    fun missingCanonicalValueMigratesLegacyFalseToDefault() {
        assertEquals(TextScale.DEFAULT, TextScaleMigrationPolicy.candidate(false, false))
    }

    @Test
    fun missingCanonicalValueMigratesLegacyTrueToLarge() {
        assertEquals(TextScale.LARGE, TextScaleMigrationPolicy.candidate(false, true))
    }

    @Test
    fun anyExistingCanonicalValueWinsOverLegacyMirror() {
        assertNull(TextScaleMigrationPolicy.candidate(true, false))
        assertNull(TextScaleMigrationPolicy.candidate(true, true))
    }
}
