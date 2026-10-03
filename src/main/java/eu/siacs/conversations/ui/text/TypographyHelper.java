package eu.siacs.conversations.ui.text;

import android.text.Spannable;
import android.text.SpannableStringBuilder;

import java.util.Locale;

/**
 * Conservative display-only typography for ordinary chat prose.
 *
 * <p>The message payload is never changed. Replacements are deliberately length-preserving so
 * existing spans keep their exact offsets. The language profile is inferred from the displayed
 * text itself and is intentionally independent of the app/system locale.
 */
public final class TypographyHelper {

    private static final char NBSP = '\u00A0';
    private static final char EN_DASH = '\u2013';
    private static final char EM_DASH = '\u2014';

    private TypographyHelper() {}

    public static void apply(final SpannableStringBuilder text) {
        if (text == null || text.length() == 0) {
            return;
        }
        applyInternal(text, detectProfile(text));
        applyPresentationSpans(text);
    }

    public static void applyCompact(final SpannableStringBuilder text) {
        if (text == null || text.length() == 0) {
            return;
        }
        applyInternal(text, detectProfile(text));
    }

    public static Locale inferTextLocale(final CharSequence text) {
        final Profile profile = detectProfile(text == null ? "" : text);
        switch (profile) {
            case RUSSIAN:
                return Locale.forLanguageTag("ru");
            case GERMAN:
                return Locale.forLanguageTag("de");
            case ENGLISH:
            default:
                return Locale.forLanguageTag("en");
        }
    }

    static String applyPlainForTest(final String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        final StringBuilder text = new StringBuilder(source);
        applyInternal(text, detectProfile(text));
        return text.toString();
    }

    private static void applyInternal(final StringBuilder text, final Profile profile) {
        final char proseDash = profile == Profile.GERMAN ? EN_DASH : EM_DASH;
        boolean inCode = false;
        for (int index = 0; index < text.length(); index++) {
            final char current = text.charAt(index);
            if (current == '`') {
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                continue;
            }
            if (current == ',' && shouldVisuallySeparateComma(text, index)) {
                text.insert(index + 1, " ");
                index++;
                continue;
            }
            if (current == '-'
                    && index > 0
                    && index + 1 < text.length()
                    && text.charAt(index - 1) == ' '
                    && text.charAt(index + 1) == ' ') {
                text.setCharAt(index - 1, NBSP);
                text.setCharAt(index, proseDash);
                continue;
            }
            if (current == ' ' && shouldKeepWithNextWord(text, index, profile)) {
                text.setCharAt(index, NBSP);
            }
        }
    }

    private static void applyInternal(
            final SpannableStringBuilder text, final Profile profile) {
        final char proseDash = profile == Profile.GERMAN ? EN_DASH : EM_DASH;
        boolean inCode = false;
        for (int index = 0; index < text.length(); index++) {
            final char current = text.charAt(index);
            if (current == '`') {
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                continue;
            }
            if (current == ',' && shouldVisuallySeparateComma(text, index)) {
                if (text.getSpans(index, index + 1, CommaGapSpan.class).length == 0) {
                    text.setSpan(
                            new CommaGapSpan(),
                            index,
                            index + 1,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                continue;
            }
            if (current == '-'
                    && index > 0
                    && index + 1 < text.length()
                    && text.charAt(index - 1) == ' '
                    && text.charAt(index + 1) == ' ') {
                text.replace(index - 1, index, String.valueOf(NBSP));
                text.replace(index, index + 1, String.valueOf(proseDash));
                continue;
            }
            if (current == ' ' && shouldKeepWithNextWord(text, index, profile)) {
                text.replace(index, index + 1, String.valueOf(NBSP));
            }
        }
    }

    private static void applyPresentationSpans(final SpannableStringBuilder text) {
        for (final ParagraphSpacingSpan span :
                text.getSpans(0, text.length(), ParagraphSpacingSpan.class)) {
            text.removeSpan(span);
        }

        boolean inCode = false;
        for (int index = 0; index + 1 < text.length(); index++) {
            final char current = text.charAt(index);
            if (current == '`') {
                inCode = !inCode;
                continue;
            }
            if (inCode || current != '\n' || text.charAt(index + 1) != '\n') {
                continue;
            }
            if (text.getSpans(
                                    index,
                                    Math.min(text.length(), index + 2),
                                    android.text.style.QuoteSpan.class)
                            .length
                    > 0) {
                continue;
            }
            text.setSpan(
                    new ParagraphSpacingSpan(),
                    index + 1,
                    index + 2,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static Profile detectProfile(final CharSequence text) {
        int germanScore = 0;
        int englishScore = 0;
        boolean inCode = false;
        final StringBuilder word = new StringBuilder();

        for (int index = 0; index <= text.length(); index++) {
            final char current = index < text.length() ? text.charAt(index) : ' ';
            if (current == '`') {
                final int[] score = scoreWordAndReset(word, germanScore, englishScore);
                germanScore = score[0];
                englishScore = score[1];
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                continue;
            }
            if (isCyrillic(current)) {
                return Profile.RUSSIAN;
            }
            if (isStrongGermanCharacter(current)) {
                return Profile.GERMAN;
            }
            if (Character.isLetter(current)) {
                word.append(Character.toLowerCase(current));
            } else if (word.length() > 0) {
                final int[] score = scoreWordAndReset(word, germanScore, englishScore);
                germanScore = score[0];
                englishScore = score[1];
            }
        }

        return germanScore >= 2 && germanScore > englishScore
                ? Profile.GERMAN
                : Profile.ENGLISH;
    }

    private static int[] scoreWordAndReset(
            final StringBuilder word,
            final int germanScore,
            final int englishScore) {
        if (word.length() == 0) {
            return new int[] {germanScore, englishScore};
        }
        final String value = word.toString().toLowerCase(Locale.ROOT);
        word.setLength(0);
        return new int[] {
            germanScore + (isGermanMarker(value) ? 1 : 0),
            englishScore + (isEnglishMarker(value) ? 1 : 0)
        };
    }

    static boolean shouldVisuallySeparateComma(
            final CharSequence text, final int commaIndex) {
        if (text == null
                || commaIndex < 0
                || commaIndex + 1 >= text.length()
                || text.charAt(commaIndex) != ','
                || !Character.isLetter(text.charAt(commaIndex + 1))) {
            return false;
        }
        return !isTechnicalToken(text, commaIndex);
    }

    private static boolean isTechnicalToken(
            final CharSequence text, final int index) {
        int start = index;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }
        int end = index + 1;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        final String token =
                text.subSequence(start, end).toString().toLowerCase(Locale.ROOT);
        return token.contains("://")
                || token.startsWith("www.")
                || token.contains("@")
                || (token.contains("/") && token.contains("."));
    }

    private static boolean shouldKeepWithNextWord(
            final CharSequence text,
            final int spaceIndex,
            final Profile profile) {
        if (shouldKeepSpecialPair(text, spaceIndex)) {
            return true;
        }
        int start = spaceIndex;
        while (start > 0 && Character.isLetter(text.charAt(start - 1))) {
            start--;
            if (spaceIndex - start > 3) {
                return false;
            }
        }
        final int length = spaceIndex - start;
        if (length < 1 || length > 3) {
            return false;
        }

        final String word =
                text.subSequence(start, spaceIndex).toString().toLowerCase(Locale.ROOT);
        if (!isGlueWord(word, profile)) {
            return false;
        }

        int next = spaceIndex + 1;
        if (next >= text.length()) {
            return false;
        }
        if (isOpeningPunctuation(text.charAt(next))) {
            next++;
        }
        return next < text.length()
                && (Character.isLetterOrDigit(text.charAt(next))
                        || text.charAt(next) == '#'
                        || text.charAt(next) == '@');
    }

    private static boolean shouldKeepSpecialPair(
            final CharSequence text, final int spaceIndex) {
        return isNumberUnitPair(text, spaceIndex)
                || isInitialSurnamePair(text, spaceIndex)
                || isNumberSymbolPair(text, spaceIndex);
    }

    private static boolean isNumberUnitPair(
            final CharSequence text, final int spaceIndex) {
        if (spaceIndex <= 0 || !Character.isDigit(text.charAt(spaceIndex - 1))) {
            return false;
        }
        return isKnownUnit(readFollowingUnitToken(text, spaceIndex + 1));
    }

    private static String readFollowingUnitToken(
            final CharSequence text, final int start) {
        if (start >= text.length()) {
            return "";
        }
        final StringBuilder token = new StringBuilder();
        int index = start;
        while (index < text.length() && token.length() < 4) {
            final char value = text.charAt(index);
            if (!(Character.isLetter(value) || value == '%' || value == '°')) {
                break;
            }
            token.append(Character.toLowerCase(value));
            index++;
        }
        return token.toString();
    }

    private static boolean isKnownUnit(final String unit) {
        switch (unit) {
            case "%":
            case "b":
            case "kb":
            case "mb":
            case "gb":
            case "tb":
            case "kib":
            case "mib":
            case "gib":
            case "ms":
            case "s":
            case "sec":
            case "min":
            case "h":
            case "hr":
            case "d":
            case "px":
            case "dp":
            case "sp":
            case "hz":
            case "khz":
            case "mhz":
            case "ghz":
            case "v":
            case "mv":
            case "a":
            case "ma":
            case "w":
            case "kw":
            case "°c":
            case "°f":
            case "кб":
            case "мб":
            case "гб":
            case "тб":
            case "мс":
            case "с":
            case "сек":
            case "мин":
            case "ч":
            case "д":
            case "дн":
            case "мм":
            case "см":
            case "м":
            case "км":
            case "г":
            case "кг":
                return true;
            default:
                return false;
        }
    }

    private static boolean isInitialSurnamePair(
            final CharSequence text, final int spaceIndex) {
        if (spaceIndex < 2
                || text.charAt(spaceIndex - 1) != '.'
                || !Character.isUpperCase(text.charAt(spaceIndex - 2))) {
            return false;
        }
        if (spaceIndex > 2 && Character.isLetter(text.charAt(spaceIndex - 3))) {
            return false;
        }
        final int next = spaceIndex + 1;
        return next < text.length() && Character.isUpperCase(text.charAt(next));
    }

    private static boolean isNumberSymbolPair(
            final CharSequence text, final int spaceIndex) {
        if (spaceIndex <= 0) {
            return false;
        }
        final char symbol = text.charAt(spaceIndex - 1);
        if (symbol != '№' && symbol != '§') {
            return false;
        }
        final int next = spaceIndex + 1;
        return next < text.length() && Character.isLetterOrDigit(text.charAt(next));
    }

    private static boolean isGlueWord(final String word, final Profile profile) {
        switch (profile) {
            case RUSSIAN:
                return isRussianGlueWord(word);
            case GERMAN:
                return isGermanGlueWord(word);
            case ENGLISH:
            default:
                return isEnglishGlueWord(word);
        }
    }

    private static boolean isRussianGlueWord(final String word) {
        switch (word) {
            case "в":
            case "во":
            case "к":
            case "ко":
            case "с":
            case "со":
            case "у":
            case "о":
            case "об":
            case "от":
            case "до":
            case "из":
            case "за":
            case "на":
            case "по":
            case "и":
            case "а":
            case "но":
            case "не":
            case "ни":
                return true;
            default:
                return false;
        }
    }

    private static boolean isEnglishGlueWord(final String word) {
        switch (word) {
            case "i":
            case "a":
            case "an":
            case "the":
            case "as":
            case "at":
            case "by":
            case "in":
            case "of":
            case "on":
            case "or":
            case "to":
            case "and":
                return true;
            default:
                return false;
        }
    }

    private static boolean isGermanGlueWord(final String word) {
        switch (word) {
            case "am":
            case "an":
            case "im":
            case "in":
            case "ob":
            case "um":
            case "zu":
            case "zum":
            case "zur":
            case "von":
            case "mit":
            case "bei":
            case "auf":
            case "aus":
            case "vor":
            case "der":
            case "die":
            case "das":
            case "den":
            case "dem":
            case "des":
            case "ein":
            case "und":
                return true;
            default:
                return false;
        }
    }

    private static boolean isGermanMarker(final String word) {
        switch (word) {
            case "der":
            case "die":
            case "das":
            case "den":
            case "dem":
            case "des":
            case "ein":
            case "eine":
            case "einer":
            case "einem":
            case "einen":
            case "und":
            case "aber":
            case "nicht":
            case "ich":
            case "bin":
            case "wir":
            case "ihr":
            case "ist":
            case "sind":
            case "war":
            case "mit":
            case "für":
            case "von":
            case "zu":
            case "im":
            case "am":
            case "auf":
            case "bei":
            case "aus":
            case "nach":
            case "über":
            case "vor":
            case "zum":
            case "zur":
                return true;
            default:
                return false;
        }
    }

    private static boolean isEnglishMarker(final String word) {
        switch (word) {
            case "the":
            case "and":
            case "but":
            case "not":
            case "you":
            case "we":
            case "they":
            case "he":
            case "she":
            case "it":
            case "is":
            case "are":
            case "was":
            case "were":
            case "with":
            case "for":
            case "from":
            case "to":
            case "on":
            case "at":
            case "by":
            case "of":
            case "as":
                return true;
            default:
                return false;
        }
    }

    private static boolean isCyrillic(final char value) {
        return (value >= '\u0400' && value <= '\u052F')
                || (value >= '\u2DE0' && value <= '\u2DFF')
                || (value >= '\uA640' && value <= '\uA69F');
    }

    private static boolean isStrongGermanCharacter(final char value) {
        switch (Character.toLowerCase(value)) {
            case 'ä':
            case 'ö':
            case 'ü':
            case 'ß':
                return true;
            default:
                return false;
        }
    }

    private static boolean isOpeningPunctuation(final char value) {
        return value == '«'
                || value == '„'
                || value == '“'
                || value == '"'
                || value == '('
                || value == '[';
    }

    private enum Profile {
        RUSSIAN,
        ENGLISH,
        GERMAN
    }
}
