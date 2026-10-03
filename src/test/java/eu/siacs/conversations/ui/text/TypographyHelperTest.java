package eu.siacs.conversations.ui.text;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TypographyHelperTest {

    @Test
    public void keepsShortRussianPrepositionsWithFollowingWord() {
        assertEquals(
                "Я был в\u00A0доме и\u00A0смотрел на\u00A0улицу",
                TypographyHelper.applyPlainForTest(
                        "Я был в доме и смотрел на улицу"));
    }

    @Test
    public void replacesOnlySpacedHyphenWithEmDash() {
        assertEquals(
                "Это\u00A0— пример, а\u00A0test-value не\u00A0меняется",
                TypographyHelper.applyPlainForTest(
                        "Это - пример, а test-value не меняется"));
    }

    @Test
    public void appliesEnglishDisplayTypographyFromTextNotLocale() {
        assertEquals(
                "Meet me in\u00A0the\u00A0park\u00A0— and\u00A0bring a\u00A0coat",
                TypographyHelper.applyPlainForTest(
                        "Meet me in the park - and bring a coat"));
    }

    @Test
    public void keepsTypographicDashWithPreviousWord() {
        assertEquals(
                "word\u00A0— next",
                TypographyHelper.applyPlainForTest("word - next"));
        assertEquals(
                "Grüße\u00A0– weiter",
                TypographyHelper.applyPlainForTest("Grüße - weiter"));
    }

    @Test
    public void keepsEnglishIWithFollowingWord() {
        assertEquals(
                "I\u00A0think this is fine",
                TypographyHelper.applyPlainForTest(
                        "I think this is fine"));
    }

    @Test
    public void appliesGermanDisplayTypographyFromTextNotLocale() {
        assertEquals(
                "Ich bin im\u00A0Park\u00A0– und\u00A0gehe zur\u00A0Bahn",
                TypographyHelper.applyPlainForTest(
                        "Ich bin im Park - und gehe zur Bahn"));
    }

    @Test
    public void strongGermanCharactersSelectGermanDashWithoutLocale() {
        assertEquals(
                "Grüße\u00A0– bis später",
                TypographyHelper.applyPlainForTest(
                        "Grüße - bis später"));
    }

    @Test
    public void keepsNumberWithKnownUnit() {
        assertEquals(
                "File is 10\u00A0MB and\u00A0took 250\u00A0ms",
                TypographyHelper.applyPlainForTest(
                        "File is 10 MB and took 250 ms"));
        assertEquals(
                "Размер 5\u00A0ГБ за\u00A02\u00A0мин",
                TypographyHelper.applyPlainForTest(
                        "Размер 5 ГБ за 2 мин"));
    }

    @Test
    public void keepsInitialWithSurnameAndNumberSymbols() {
        assertEquals(
                "A.\u00A0Smith §\u00A03",
                TypographyHelper.applyPlainForTest(
                        "A. Smith § 3"));
        assertEquals(
                "И.\u00A0Иванов №\u00A05",
                TypographyHelper.applyPlainForTest(
                        "И. Иванов № 5"));
    }

    @Test
    public void doesNotBindUnknownNumberWordPair() {
        assertEquals(
                "version 2 beta",
                TypographyHelper.applyPlainForTest(
                        "version 2 beta"));
    }

    @Test
    public void visuallySeparatesCommaFollowedByLetter() {
        assertEquals(
                "Привет, мир; hello, world",
                TypographyHelper.applyPlainForTest(
                        "Привет,мир; hello,world"));
    }

    @Test
    public void leavesDecimalCommaUntouched() {
        assertEquals(
                "Версия 1,5 и\u00A02,75",
                TypographyHelper.applyPlainForTest(
                        "Версия 1,5 и 2,75"));
    }

    @Test
    public void leavesUrlAndEmailLikeTokensUntouched() {
        assertEquals(
                "https://example.test/a,b name,last@example.test",
                TypographyHelper.applyPlainForTest(
                        "https://example.test/a,b name,last@example.test"));
    }

    @Test
    public void detectsTextLanguageForHyphenationIndependentlyOfAppLocale() {
        assertEquals(
                "ru",
                TypographyHelper.inferTextLocale("Это длинное русское сообщение").getLanguage());
        assertEquals(
                "en",
                TypographyHelper.inferTextLocale("This is a longer English message").getLanguage());
        assertEquals(
                "de",
                TypographyHelper.inferTextLocale("Ich bin im Park und gehe zur Bahn").getLanguage());
    }

    @Test
    public void leavesBacktickCodeUntouched() {
        assertEquals(
                "Текст\u00A0— да, `cmd - arg в file,word`",
                TypographyHelper.applyPlainForTest(
                        "Текст - да, `cmd - arg в file,word`"));
        assertEquals(
                "Text\u00A0— yes, `cmd - arg in file`",
                TypographyHelper.applyPlainForTest(
                        "Text - yes, `cmd - arg in file`"));
    }
}
