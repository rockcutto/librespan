package eu.siacs.conversations.ui.util;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

import androidx.annotation.ColorInt;
import androidx.core.graphics.ColorUtils;

/**
 * Small, dependency-light helpers for chat chrome that visually belongs to the wallpaper.
 *
 * <p>The first pass deliberately avoids blur: the island surface keeps the Material surface
 * color for readability, mixes in a bounded amount of the visible wallpaper color and remains
 * slightly translucent so the background still reads through it.
 */
public final class ChatChromeTint {

    private static final float WALLPAPER_MIX = 0.28f;
    private static final int ISLAND_ALPHA = 232;
    private static final int ISLAND_FALLBACK_ALPHA = 246;
    private static final double MIN_TEXT_CONTRAST = 4.5d;
    private static final float PROTECTION_TONE_MIX = 0.82f;
    private static final int PROTECTION_ALPHA = 236;
    private static final int PROTECTION_MID_ALPHA = 188;
    private static final float TOP_PROTECTION_TONE_MIX = 0.74f;
    private static final int TOP_PROTECTION_ALPHA = 232;
    private static final int TOP_PROTECTION_MID_ALPHA = 176;
    private static final float BAND_FRACTION = 0.18f;
    private static final int SAMPLE_COLUMNS = 24;
    private static final int SAMPLE_ROWS = 12;

    private ChatChromeTint() {}

    @ColorInt
    public static int resolveIslandColor(
            @ColorInt final int surfaceColor,
            @ColorInt final int wallpaperColor,
            @ColorInt final int foregroundColor) {
        final int opaqueSurface = ColorUtils.setAlphaComponent(surfaceColor, 255);
        final int opaqueWallpaper = ColorUtils.setAlphaComponent(wallpaperColor, 255);
        final int opaqueForeground = ColorUtils.setAlphaComponent(foregroundColor, 255);

        float wallpaperMix = WALLPAPER_MIX;
        for (int attempt = 0; attempt < 7; attempt++) {
            final int mixed =
                    ColorUtils.blendARGB(opaqueSurface, opaqueWallpaper, wallpaperMix);
            final int candidate = ColorUtils.setAlphaComponent(mixed, ISLAND_ALPHA);
            final int visibleCandidate =
                    ColorUtils.compositeColors(candidate, opaqueWallpaper);
            if (ColorUtils.calculateContrast(opaqueForeground, visibleCandidate)
                    >= MIN_TEXT_CONTRAST) {
                return candidate;
            }
            wallpaperMix *= 0.55f;
        }

        final int fallback =
                ColorUtils.setAlphaComponent(opaqueSurface, ISLAND_FALLBACK_ALPHA);
        final int visibleFallback =
                ColorUtils.compositeColors(fallback, opaqueWallpaper);
        if (ColorUtils.calculateContrast(opaqueForeground, visibleFallback)
                >= MIN_TEXT_CONTRAST) {
            return fallback;
        }
        return opaqueSurface;
    }

    public static GradientDrawable createSystemBarProtection(
            @ColorInt final int visibleColor,
            final boolean darkIcons,
            final boolean topEdge) {
        final int target = darkIcons ? Color.WHITE : Color.BLACK;
        final float toneMix = topEdge ? TOP_PROTECTION_TONE_MIX : PROTECTION_TONE_MIX;
        final int toned =
                ColorUtils.blendARGB(
                        ColorUtils.setAlphaComponent(visibleColor, 255),
                        target,
                        toneMix);
        final int transparent = ColorUtils.setAlphaComponent(toned, 0);
        if (topEdge) {
            final int edge =
                    ColorUtils.setAlphaComponent(toned, TOP_PROTECTION_ALPHA);
            final int middle =
                    ColorUtils.setAlphaComponent(toned, TOP_PROTECTION_MID_ALPHA);
            return new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {edge, middle, transparent});
        }
        final int edge = ColorUtils.setAlphaComponent(toned, PROTECTION_ALPHA);
        final int middle = ColorUtils.setAlphaComponent(toned, PROTECTION_MID_ALPHA);
        return new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {edge, middle, transparent});
    }

    public static GradientDrawable createOverviewHeaderProtection(
            @ColorInt final int surfaceColor) {
        final int opaqueSurface = ColorUtils.setAlphaComponent(surfaceColor, 255);
        final int strong = ColorUtils.setAlphaComponent(opaqueSurface, 220);
        final int middle = ColorUtils.setAlphaComponent(opaqueSurface, 112);
        final int transparent = ColorUtils.setAlphaComponent(opaqueSurface, 0);
        return new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {strong, middle, transparent});
    }

    @ColorInt
    public static int applyScrim(
            @ColorInt final int wallpaperColor, @ColorInt final int scrimColor) {
        return ColorUtils.compositeColors(scrimColor, wallpaperColor);
    }

    public static boolean shouldUseDarkSystemIcons(@ColorInt final int visibleColor) {
        return ColorUtils.calculateLuminance(visibleColor) > 0.56d;
    }

    /**
     * Samples the actually visible top or bottom band of a centerCrop image.
     *
     * <p>The calculation maps the ImageView viewport back into bitmap coordinates before taking a
     * small fixed grid of samples, so portrait/landscape wallpaper crops do not bias the tint
     * toward pixels that are not on screen.
     */
    @ColorInt
    public static int sampleVisibleBand(
            final Bitmap bitmap,
            final int viewportWidth,
            final int viewportHeight,
            final boolean topBand) {
        if (bitmap == null
                || bitmap.isRecycled()
                || bitmap.getWidth() <= 0
                || bitmap.getHeight() <= 0
                || viewportWidth <= 0
                || viewportHeight <= 0) {
            return Color.BLACK;
        }

        final int bitmapWidth = bitmap.getWidth();
        final int bitmapHeight = bitmap.getHeight();
        final float scale =
                Math.max(
                        viewportWidth / (float) bitmapWidth,
                        viewportHeight / (float) bitmapHeight);
        final float cropWidth = Math.min(bitmapWidth, viewportWidth / scale);
        final float cropHeight = Math.min(bitmapHeight, viewportHeight / scale);
        final float cropLeft = Math.max(0f, (bitmapWidth - cropWidth) / 2f);
        final float cropTop = Math.max(0f, (bitmapHeight - cropHeight) / 2f);

        final float bandTop =
                topBand
                        ? cropTop
                        : cropTop + cropHeight * (1f - BAND_FRACTION);
        final float bandHeight = cropHeight * BAND_FRACTION;

        long red = 0L;
        long green = 0L;
        long blue = 0L;
        int samples = 0;

        for (int y = 0; y < SAMPLE_ROWS; y++) {
            final float sourceY =
                    bandTop + ((y + 0.5f) / SAMPLE_ROWS) * bandHeight;
            final int bitmapY =
                    clamp(Math.round(sourceY), 0, bitmapHeight - 1);
            for (int x = 0; x < SAMPLE_COLUMNS; x++) {
                final float sourceX =
                        cropLeft + ((x + 0.5f) / SAMPLE_COLUMNS) * cropWidth;
                final int bitmapX =
                        clamp(Math.round(sourceX), 0, bitmapWidth - 1);
                final int color = bitmap.getPixel(bitmapX, bitmapY);
                red += Color.red(color);
                green += Color.green(color);
                blue += Color.blue(color);
                samples++;
            }
        }

        if (samples == 0) {
            return Color.BLACK;
        }
        return Color.rgb(
                (int) (red / samples),
                (int) (green / samples),
                (int) (blue / samples));
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}
