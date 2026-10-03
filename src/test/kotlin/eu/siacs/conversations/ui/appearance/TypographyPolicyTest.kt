package eu.siacs.conversations.ui.appearance

import org.junit.Assert.assertEquals
import org.junit.Test

class TypographyPolicyTest {
    @Test fun messageAndCaptionPreserveLegacyBaseline() {
        assertEquals(14f, TypographyPolicy.sizeSp(TypographyRole.MESSAGE, TextScale.DEFAULT))
        assertEquals(15f, TypographyPolicy.sizeSp(TypographyRole.MESSAGE, TextScale.LARGE))
        assertEquals(14f, TypographyPolicy.sizeSp(TypographyRole.CAPTION, TextScale.DEFAULT))
        assertEquals(15f, TypographyPolicy.sizeSp(TypographyRole.CAPTION, TextScale.LARGE))
    }
}
