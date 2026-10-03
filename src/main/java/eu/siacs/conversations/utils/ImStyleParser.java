/*
 * Copyright (c) 2017, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ImStyleParser {

    private static final List<Character> KEYWORDS = Arrays.asList('*', '_', '~', '`');
    private static final List<Character> NO_SUB_PARSING_KEYWORDS = Arrays.asList('`');
    private static final boolean ALLOW_EMPTY = false;

    public static List<Style> parse(CharSequence text) {
        if (text == null || text.length() == 0) {
            return new ArrayList<>();
        }
        return parse(text, 0, text.length() - 1);
    }

    public static List<Style> parse(CharSequence text, int start, int end) {
        final List<Style> styles = new ArrayList<>();
        if (text == null || start < 0 || start > end || start >= text.length()) {
            return styles;
        }
        end = Math.min(end, text.length() - 1);

        for (int i = start; i <= end; ++i) {
            if (isPreformattedBlockStart(text, i, end)) {
                final int closingEnd = seekEndBlock(text, i + 3, end);
                final boolean hasClosingKeyword = closingEnd != -1;
                final int styleEnd = hasClosingKeyword ? closingEnd : end;
                if (styleEnd >= i + 3 || ALLOW_EMPTY) {
                    styles.add(new Style("```", i, styleEnd, hasClosingKeyword));
                    i = styleEnd;
                    continue;
                }
            }

            final char c = text.charAt(i);
            if (KEYWORDS.contains(c)
                    && precededByWhiteSpace(text, i, start)
                    && !followedByWhitespace(text, i, end)) {
                final int to = seekEnd(text, c, i + 1, end);
                if (to != -1 && (to != i + 1 || ALLOW_EMPTY)) {
                    styles.add(new Style(c, i, to));
                    if (!NO_SUB_PARSING_KEYWORDS.contains(c)) {
                        styles.addAll(parse(text, i + 1, to - 1));
                    }
                    i = to;
                }
            }
        }
        return styles;
    }

    private static boolean isPreformattedBlockStart(
            final CharSequence text, final int index, final int end) {
        if (index + 2 > end || (index != 0 && text.charAt(index - 1) != '\n')) {
            return false;
        }
        return text.charAt(index) == '`'
                && text.charAt(index + 1) == '`'
                && text.charAt(index + 2) == '`';
    }

    private static boolean precededByWhiteSpace(
            final CharSequence text, final int index, final int start) {
        return index == start || Character.isWhitespace(text.charAt(index - 1));
    }

    private static boolean followedByWhitespace(
            final CharSequence text, final int index, final int end) {
        return index >= end || Character.isWhitespace(text.charAt(index + 1));
    }

    private static int seekEnd(
            final CharSequence text, final char needle, final int start, final int end) {
        for (int i = start; i <= end; ++i) {
            final char c = text.charAt(i);
            if (c == needle && !Character.isWhitespace(text.charAt(i - 1))) {
                return i;
            } else if (c == '\n') {
                return -1;
            }
        }
        return -1;
    }

    private static int seekEndBlock(
            final CharSequence text, final int start, final int end) {
        int cursor = start;

        while (cursor <= end && text.charAt(cursor) != '\n') {
            cursor++;
        }
        if (cursor > end) {
            return -1;
        }
        cursor++;

        while (cursor <= end) {
            final int lineStart = cursor;
            while (cursor <= end && text.charAt(cursor) != '\n') {
                cursor++;
            }
            int lineEndExclusive = cursor;
            if (lineEndExclusive > lineStart && text.charAt(lineEndExclusive - 1) == '\r') {
                lineEndExclusive--;
            }
            if (lineEndExclusive - lineStart == 3
                    && text.charAt(lineStart) == '`'
                    && text.charAt(lineStart + 1) == '`'
                    && text.charAt(lineStart + 2) == '`') {
                return lineStart + 2;
            }
            cursor++;
        }
        return -1;
    }

    public static class Style {

        private final String keyword;
        private final int start;
        private final int end;
        private final boolean hasClosingKeyword;

        public Style(char character, int start, int end) {
            this(String.valueOf(character), start, end, true);
        }

        public Style(String keyword, int start, int end) {
            this(keyword, start, end, true);
        }

        public Style(String keyword, int start, int end, boolean hasClosingKeyword) {
            this.keyword = keyword;
            this.start = start;
            this.end = end;
            this.hasClosingKeyword = hasClosingKeyword;
        }

        public String getKeyword() {
            return keyword;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }

        public boolean hasClosingKeyword() {
            return hasClosingKeyword;
        }
    }
}
