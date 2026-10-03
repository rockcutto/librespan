package eu.siacs.conversations.ui.text;

import android.graphics.Paint;
import android.text.style.LineHeightSpan;

/**
 * Adds a small amount of display-only breathing room to an existing blank paragraph line.
 *
 * <p>The span never mutates message text or offsets.
 */
public final class ParagraphSpacingSpan implements LineHeightSpan {

    private static final float EXTRA_LINE_HEIGHT_FRACTION = 0.15f;

    @Override
    public void chooseHeight(
            final CharSequence text,
            final int start,
            final int end,
            final int spanstartv,
            final int v,
            final Paint.FontMetricsInt fm) {
        final int lineHeight = Math.max(1, fm.descent - fm.ascent);
        final int extra = Math.max(1, Math.round(lineHeight * EXTRA_LINE_HEIGHT_FRACTION));
        fm.descent += extra;
        fm.bottom += extra;
    }
}
