package eu.siacs.conversations.utils;

import android.text.SpannableStringBuilder;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;

/**
 * Small XEP-0394 bridge around the existing XEP-0393 composer syntax.
 *
 * <p>The composer may still use styling directives internally. At the message boundary this class
 * can produce a clean body plus XEP-0394 markup, keep XEP-0393 for a compatible peer, or degrade
 * to clean plain text.
 */
public final class MessageMarkup {

    public enum WireMode {
        MARKUP,
        STYLING,
        PLAIN
    }

    private enum InlineStyle {
        STRONG("strong", '*'),
        EMPHASIS("emphasis", '_'),
        DELETED("deleted", '~'),
        CODE("code", '`');

        final String element;
        final char marker;

        InlineStyle(final String element, final char marker) {
            this.element = element;
            this.marker = marker;
        }

        @Nullable
        static InlineStyle fromKeyword(final String keyword) {
            return switch (keyword) {
                case "*" -> STRONG;
                case "_" -> EMPHASIS;
                case "~" -> DELETED;
                case "`" -> CODE;
                default -> null;
            };
        }

        @Nullable
        static InlineStyle fromElement(final String element) {
            for (final InlineStyle style : values()) {
                if (style.element.equals(element)) {
                    return style;
                }
            }
            return null;
        }
    }

    private record Range(int start, int end, InlineStyle style) {}

    public static final class Prepared {
        private final String plainBody;
        @Nullable private final Element markup;

        private Prepared(final String plainBody, @Nullable final Element markup) {
            this.plainBody = plainBody;
            this.markup = markup;
        }

        public String getPlainBody() {
            return plainBody;
        }

        @Nullable
        public Element getMarkup() {
            return markup;
        }

        public boolean hasMarkup() {
            return markup != null && !markup.getChildren().isEmpty();
        }
    }

    private MessageMarkup() {}

    public static Prepared prepare(final String source) {
        if (source == null || source.isEmpty()) {
            return new Prepared(source == null ? "" : source, null);
        }

        final List<ImStyleParser.Style> parsed = ImStyleParser.parse(source);
        if (parsed.isEmpty()) {
            return new Prepared(source, null);
        }

        final boolean[] removed = new boolean[source.length()];
        final List<RawRange> rawRanges = new ArrayList<>();

        for (final ImStyleParser.Style style : parsed) {
            final int keywordLength = style.getKeyword().length();
            final int contentStart = style.getStart() + keywordLength;
            final int contentEnd =
                    style.hasClosingKeyword()
                            ? style.getEnd() - keywordLength + 1
                            : style.getEnd() + 1;
            if (contentStart >= contentEnd) {
                continue;
            }

            markRemoved(removed, style.getStart(), style.getStart() + keywordLength);
            if (style.hasClosingKeyword()) {
                markRemoved(
                        removed,
                        style.getEnd() - keywordLength + 1,
                        style.getEnd() + 1);
            }

            if ("```".equals(style.getKeyword())) {
                rawRanges.add(new RawRange(contentStart, contentEnd, null, true));
            } else {
                final InlineStyle inlineStyle = InlineStyle.fromKeyword(style.getKeyword());
                if (inlineStyle != null) {
                    rawRanges.add(new RawRange(contentStart, contentEnd, inlineStyle, false));
                }
            }
        }

        final String plainBody = stripRemoved(source, removed);
        if (rawRanges.isEmpty()) {
            return new Prepared(plainBody, null);
        }

        final List<Range> inlineRanges = new ArrayList<>();
        final List<int[]> blockCodeRanges = new ArrayList<>();
        for (final RawRange range : rawRanges) {
            final int start = cleanCodePointOffset(source, removed, range.start);
            final int end = cleanCodePointOffset(source, removed, range.end);
            if (start >= end) {
                continue;
            }
            if (range.blockCode) {
                blockCodeRanges.add(new int[] {start, end});
            } else if (range.style != null) {
                inlineRanges.add(new Range(start, end, range.style));
            }
        }

        final Element markup = new Element("markup", Namespace.MESSAGE_MARKUP);
        appendNonOverlappingInlineSpans(markup, inlineRanges);
        for (final int[] block : blockCodeRanges) {
            markup.addChild("bcode", Namespace.MESSAGE_MARKUP)
                    .setAttribute("start", Integer.toString(block[0]))
                    .setAttribute("end", Integer.toString(block[1]));
        }

        return markup.getChildren().isEmpty()
                ? new Prepared(plainBody, null)
                : new Prepared(plainBody, markup);
    }

    public static WireMode selectWireMode(
            @Nullable final Conversation conversation, final int encryption) {
        if (conversation == null) {
            return WireMode.PLAIN;
        }

        final boolean encrypted =
                encryption == Message.ENCRYPTION_AXOLOTL
                        || encryption == Message.ENCRYPTION_OTR;

        // XEP-0394 is additive: an unsupported peer ignores <markup/> and still receives the
        // clean body, so unencrypted styling must not depend on transient presence/disco state.
        //
        // With legacy OMEMO/OTR this client encrypts one textual payload. Sending <markup/>
        // alongside it would leak formatting ranges outside E2EE, therefore encrypted formatted
        // text uses XEP-0393 directives inside the encrypted body. This also works for offline
        // LibreSpan devices where no live disco information exists.
        return encrypted ? WireMode.STYLING : WireMode.MARKUP;
    }

    public static String bodyForMode(
            final String stylingSource, final Prepared prepared, final WireMode mode) {
        return mode == WireMode.STYLING ? stylingSource : prepared.getPlainBody();
    }

    public static SpannableStringBuilder presentationText(
            @Nullable final CharSequence source,
            @Nullable final Element markup,
            final boolean stylingDisabled) {
        final String text = source == null ? "" : source.toString();
        if (stylingDisabled || text.isEmpty()) {
            return new SpannableStringBuilder(text);
        }

        if (markup != null) {
            final SpannableStringBuilder result = new SpannableStringBuilder(text);
            StylingHelper.formatMarkup(result, markup);
            return result;
        }

        // XEP-0393 keeps directives in the textual payload. Presentation-only surfaces must
        // render equivalent spans without exposing the wire markers themselves.
        final Prepared prepared = prepare(text);
        final SpannableStringBuilder result =
                new SpannableStringBuilder(prepared.getPlainBody());
        if (prepared.getMarkup() != null) {
            StylingHelper.formatMarkup(result, prepared.getMarkup());
        }
        return result;
    }

    public static boolean canApplyToDisplayedBody(
            @Nullable final Message message, @Nullable final CharSequence displayedBody) {
        return message != null
                && displayedBody != null
                && message.getMessageMarkup() != null
                && message.getBody().contentEquals(displayedBody);
    }

    @Nullable
    public static Element markupForMode(
            final Prepared prepared, final WireMode mode, final int codePointOffset) {
        if (mode != WireMode.MARKUP || !prepared.hasMarkup()) {
            return null;
        }
        return shiftedCopy(prepared.getMarkup(), codePointOffset);
    }

    @Nullable
    public static Element shiftedCopy(@Nullable final Element markup, final int offset) {
        if (markup == null
                || !"markup".equals(markup.getName())
                || !Namespace.MESSAGE_MARKUP.equals(markup.getNamespace())) {
            return null;
        }
        final Element copy = new Element("markup", Namespace.MESSAGE_MARKUP);
        for (final Element child : markup.getChildren()) {
            final Integer start = parseNonNegative(child.getAttribute("start"));
            final Integer end = parseNonNegative(child.getAttribute("end"));
            if (start == null || end == null || end < start) {
                continue;
            }
            if ("span".equals(child.getName())) {
                final Element span =
                        copy.addChild("span", Namespace.MESSAGE_MARKUP)
                                .setAttribute("start", Integer.toString(start + offset))
                                .setAttribute("end", Integer.toString(end + offset));
                for (final Element styleElement : child.getChildren()) {
                    if (InlineStyle.fromElement(styleElement.getName()) != null) {
                        span.addChild(styleElement.getName(), Namespace.MESSAGE_MARKUP);
                    }
                }
                if (span.getChildren().isEmpty()) {
                    copy.removeChild(span);
                }
            } else if ("bcode".equals(child.getName())) {
                copy.addChild("bcode", Namespace.MESSAGE_MARKUP)
                        .setAttribute("start", Integer.toString(start + offset))
                        .setAttribute("end", Integer.toString(end + offset));
            }
        }
        return copy.getChildren().isEmpty() ? null : copy;
    }

    public static String toStylingText(final String body, @Nullable final Element markup) {
        if (body == null || body.isEmpty() || markup == null) {
            return body == null ? "" : body;
        }

        final List<StyledSegment> segments = readInlineSegments(markup, body);
        if (segments.isEmpty()) {
            return body;
        }

        mergeAdjacentEqualSegments(segments);
        final StringBuilder result = new StringBuilder(body);
        for (int i = segments.size() - 1; i >= 0; i--) {
            final StyledSegment segment = segments.get(i);
            final int startChar = offsetByCodePoints(body, segment.start);
            final int endChar = offsetByCodePoints(body, segment.end);
            if (startChar < 0 || endChar < startChar) {
                continue;
            }
            final String open = markers(segment.styles, false);
            final String close = markers(segment.styles, true);
            result.insert(endChar, close);
            result.insert(startChar, open);
        }
        return result.toString();
    }

    private static List<StyledSegment> readInlineSegments(
            final Element markup, final String body) {
        final int totalCodePoints = body.codePointCount(0, body.length());
        final List<StyledSegment> segments = new ArrayList<>();
        for (final Element child : markup.getChildren()) {
            if (!"span".equals(child.getName())) {
                continue;
            }
            final Integer start = parseNonNegative(child.getAttribute("start"));
            final Integer end = parseNonNegative(child.getAttribute("end"));
            if (start == null || end == null || start >= end || end > totalCodePoints) {
                continue;
            }
            final EnumSet<InlineStyle> styles = EnumSet.noneOf(InlineStyle.class);
            for (final Element styleElement : child.getChildren()) {
                final InlineStyle style = InlineStyle.fromElement(styleElement.getName());
                if (style != null) {
                    styles.add(style);
                }
            }
            if (!styles.isEmpty()) {
                segments.add(new StyledSegment(start, end, styles));
            }
        }
        segments.sort((a, b) -> Integer.compare(a.start, b.start));
        return segments;
    }

    private static void mergeAdjacentEqualSegments(final List<StyledSegment> segments) {
        for (int i = 0; i + 1 < segments.size(); ) {
            final StyledSegment current = segments.get(i);
            final StyledSegment next = segments.get(i + 1);
            if (current.end == next.start && current.styles.equals(next.styles)) {
                segments.set(
                        i,
                        new StyledSegment(
                                current.start,
                                next.end,
                                EnumSet.copyOf(current.styles)));
                segments.remove(i + 1);
            } else {
                i++;
            }
        }
    }

    private static String markers(final Set<InlineStyle> styles, final boolean reverse) {
        final InlineStyle[] order = {
            InlineStyle.STRONG,
            InlineStyle.EMPHASIS,
            InlineStyle.DELETED,
            InlineStyle.CODE
        };
        final StringBuilder builder = new StringBuilder();
        if (reverse) {
            for (int i = order.length - 1; i >= 0; i--) {
                if (styles.contains(order[i])) {
                    builder.append(order[i].marker);
                }
            }
        } else {
            for (final InlineStyle style : order) {
                if (styles.contains(style)) {
                    builder.append(style.marker);
                }
            }
        }
        return builder.toString();
    }

    private static void appendNonOverlappingInlineSpans(
            final Element markup, final List<Range> ranges) {
        if (ranges.isEmpty()) {
            return;
        }

        final TreeSet<Integer> boundaries = new TreeSet<>();
        for (final Range range : ranges) {
            boundaries.add(range.start());
            boundaries.add(range.end());
        }
        final List<Integer> ordered = new ArrayList<>(boundaries);
        for (int i = 0; i + 1 < ordered.size(); i++) {
            final int start = ordered.get(i);
            final int end = ordered.get(i + 1);
            if (start >= end) {
                continue;
            }
            final EnumSet<InlineStyle> active = EnumSet.noneOf(InlineStyle.class);
            for (final Range range : ranges) {
                if (range.start() <= start && range.end() >= end) {
                    active.add(range.style());
                }
            }
            if (active.isEmpty()) {
                continue;
            }
            final Element span =
                    markup.addChild("span", Namespace.MESSAGE_MARKUP)
                            .setAttribute("start", Integer.toString(start))
                            .setAttribute("end", Integer.toString(end));
            for (final InlineStyle style : InlineStyle.values()) {
                if (active.contains(style)) {
                    span.addChild(style.element, Namespace.MESSAGE_MARKUP);
                }
            }
        }
    }

    private static void markRemoved(final boolean[] removed, final int start, final int end) {
        for (int i = Math.max(0, start); i < Math.min(removed.length, end); i++) {
            removed[i] = true;
        }
    }

    private static String stripRemoved(final String source, final boolean[] removed) {
        final StringBuilder plain = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            if (!removed[i]) {
                plain.append(source.charAt(i));
            }
        }
        return plain.toString();
    }

    private static int cleanCodePointOffset(
            final String source, final boolean[] removed, final int boundary) {
        int codePoints = 0;
        int index = 0;
        final int limit = Math.max(0, Math.min(boundary, source.length()));
        while (index < limit) {
            if (removed[index]) {
                index++;
                continue;
            }
            final int codePoint = source.codePointAt(index);
            final int count = Character.charCount(codePoint);
            boolean wholeCodePointVisible = true;
            for (int i = 0; i < count && index + i < removed.length; i++) {
                if (removed[index + i]) {
                    wholeCodePointVisible = false;
                    break;
                }
            }
            if (wholeCodePointVisible) {
                codePoints++;
            }
            index += count;
        }
        return codePoints;
    }

    private static int offsetByCodePoints(final String body, final int codePointOffset) {
        final int count = body.codePointCount(0, body.length());
        if (codePointOffset < 0 || codePointOffset > count) {
            return -1;
        }
        return body.offsetByCodePoints(0, codePointOffset);
    }

    @Nullable
    private static Integer parseNonNegative(@Nullable final String value) {
        if (value == null) {
            return null;
        }
        try {
            final int parsed = Integer.parseInt(value);
            return parsed < 0 ? null : parsed;
        } catch (final NumberFormatException ignored) {
            return null;
        }
    }

    private record RawRange(
            int start,
            int end,
            @Nullable InlineStyle style,
            boolean blockCode) {}

    private static final class StyledSegment {
        final int start;
        final int end;
        final EnumSet<InlineStyle> styles;

        StyledSegment(
                final int start,
                final int end,
                final EnumSet<InlineStyle> styles) {
            this.start = start;
            this.end = end;
            this.styles = styles;
        }
    }
}
