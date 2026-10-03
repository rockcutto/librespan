package eu.siacs.conversations.ui.attachments;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatSeekBar;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;

import java.util.Arrays;

/**
 * SeekBar that renders decoded audio amplitudes instead of an empty track.
 *
 * Progress semantics remain the normal SeekBar semantics; only the track visualization is custom.
 */
public final class AttachmentWaveformSeekBar extends AppCompatSeekBar {

    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inactivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] amplitudes = new float[0];

    public AttachmentWaveformSeekBar(final Context context) {
        super(context);
        init();
    }

    public AttachmentWaveformSeekBar(
            final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AttachmentWaveformSeekBar(
            final Context context,
            @Nullable final AttributeSet attrs,
            final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setMax(100);
        setSplitTrack(false);
        setProgressDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    public void setAmplitudes(@Nullable final float[] values) {
        amplitudes =
                values == null || values.length == 0
                        ? new float[0]
                        : Arrays.copyOf(values, values.length);
        invalidate();
    }

    public void clearAmplitudes() {
        amplitudes = new float[0];
        invalidate();
    }

    @Override
    protected synchronized void onDraw(final Canvas canvas) {
        drawWaveform(canvas);
        // The progress drawable is transparent; super still provides the normal seek thumb and
        // accessibility/touch behavior.
        super.onDraw(canvas);
    }

    private void drawWaveform(final Canvas canvas) {
        final int left = getPaddingLeft();
        final int right = getWidth() - getPaddingRight();
        final int top = getPaddingTop();
        final int bottom = getHeight() - getPaddingBottom();
        final int width = right - left;
        final int height = bottom - top;
        if (width <= 0 || height <= 0) {
            return;
        }

        final int activeColor =
                getProgressTintList() != null
                        ? getProgressTintList().getDefaultColor()
                        : MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary);
        activePaint.setColor(activeColor);
        inactivePaint.setColor(
                getProgressTintList() != null
                        ? ColorUtils.setAlphaComponent(activeColor, 96)
                        : MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorOnSurfaceVariant));

        final float density = getResources().getDisplayMetrics().density;
        final float minBarHeight = Math.max(2f * density, height * 0.08f);
        final float maxBarHeight = Math.max(minBarHeight, height * 0.72f);
        final float centerY = top + height / 2f;
        final float progressFraction = getMax() <= 0 ? 0f : getProgress() / (float) getMax();
        final float progressX = left + width * progressFraction;

        if (amplitudes.length == 0) {
            inactivePaint.setStrokeWidth(Math.max(1f, density));
            canvas.drawLine(left, centerY, right, centerY, inactivePaint);
            return;
        }

        final float slotWidth = width / (float) amplitudes.length;
        final float barWidth = Math.max(1.5f * density, slotWidth * 0.52f);
        final float radius = barWidth / 2f;

        for (int i = 0; i < amplitudes.length; i++) {
            final float normalized = Math.max(0f, Math.min(1f, amplitudes[i]));
            final float barHeight =
                    minBarHeight + normalized * (maxBarHeight - minBarHeight);
            final float x = left + slotWidth * (i + 0.5f);
            final Paint paint = x <= progressX ? activePaint : inactivePaint;
            canvas.drawRoundRect(
                    x - barWidth / 2f,
                    centerY - barHeight / 2f,
                    x + barWidth / 2f,
                    centerY + barHeight / 2f,
                    radius,
                    radius,
                    paint);
        }
    }
}
