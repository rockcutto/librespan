package eu.siacs.conversations.ui.text;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Draws the original comma unchanged but reserves one normal-space width after it.
 *
 * <p>The underlying message text stays untouched, so reply/quote offsets, copying, search and the
 * XMPP payload remain identical to the received text.
 */
final class CommaGapSpan extends ReplacementSpan {

    @Override
    public int getSize(
            @NonNull final Paint paint,
            final CharSequence text,
            final int start,
            final int end,
            @Nullable final Paint.FontMetricsInt fm) {
        return (int) Math.ceil(
                paint.measureText(text, start, end) + paint.measureText(" "));
    }

    @Override
    public void draw(
            @NonNull final Canvas canvas,
            final CharSequence text,
            final int start,
            final int end,
            final float x,
            final int top,
            final int y,
            final int bottom,
            @NonNull final Paint paint) {
        canvas.drawText(text, start, end, x, y, paint);
    }
}
