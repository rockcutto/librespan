package eu.siacs.conversations.ui.appearance;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.TypedValue;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.android.material.R;

import eu.siacs.conversations.utils.ThemeHelper;

/**
 * Built-in chat wallpaper pairs.
 *
 * <p>Each preset has a separately tuned light/dark pair. The live primary color is mixed in only
 * lightly so custom accents and Material You influence the atmosphere without turning the whole
 * chat into a saturated color field.
 */
public final class ChatWallpaperPresets {

    public static final String PREFERENCE_KEY = "chat_wallpaper_preset";
    public static final String CUSTOM_ID = "custom";
    public static final String DEFAULT_ID = "mist";

    // The wallpaper should sit behind the conversation chrome, not turn into a second accent
    // surface. Keep Material/accent influence deliberately restrained, especially in dark mode.
    private static final float LIGHT_TOP_ACCENT_MIX = 0.02f;
    private static final float LIGHT_BOTTOM_ACCENT_MIX = 0.07f;
    private static final float DARK_TOP_ACCENT_MIX = 0.025f;
    private static final float DARK_BOTTOM_ACCENT_MIX = 0.09f;

    // CPU pre-dither amplitude in 8-bit channel units. The noise is applied before rounding
    // interpolated gradient values to ARGB_8888, so it removes quantization contours instead of
    // merely drawing translucent grain over an already banded GPU gradient.
    private static final float DITHER_AMPLITUDE = 1.75f;
    private static final int RASTER_FORMAT_VERSION = 1;
    private static final String RASTER_DIRECTORY = "chat_wallpaper_rasters";
    private static final String RASTER_FILE_PREFIX = "chat_wallpaper_v";
    private static final int RASTER_CACHE_KB = 24 * 1024;
    private static final int RASTER_STRIPE_ROWS = 32;
    private static final ExecutorService RASTER_IO_EXECUTOR =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        final Thread thread = new Thread(runnable, "chat-wallpaper-raster-io");
                        thread.setDaemon(true);
                        thread.setPriority(Thread.MIN_PRIORITY);
                        return thread;
                    });
    private static final LruCache<String, Bitmap> RASTER_CACHE =
            new LruCache<String, Bitmap>(RASTER_CACHE_KB) {
                @Override
                protected int sizeOf(final String key, final Bitmap value) {
                    return Math.max(1, value.getAllocationByteCount() / 1024);
                }
            };
    private static String processPrewarmKey;

    private ChatWallpaperPresets() {}

    /**
     * Ensures the selected full-screen raster is ready for this process.
     *
     * <p>On a normal restart this loads the previously generated PNG from app-private no-backup
     * storage. The expensive gradient rasterization only runs when the render key changed or the
     * persistent cache was removed. Call this only after the effective Activity theme/accent has
     * been applied. Chat rendering itself is never allowed to trigger a full-screen rasterization.
     */
    public static synchronized void prewarmOncePerProcess(final Context context) {
        if (context == null || isCustomSelected(context)) {
            processPrewarmKey = "custom";
            return;
        }
        final RasterSize rasterSize = resolveRasterSize(context);
        final int width = rasterSize.width;
        final int height = rasterSize.height;
        final Resolved resolved = resolve(context);
        if (!(resolved.drawable instanceof DitheredGradientDrawable)) {
            return;
        }
        final DitheredGradientDrawable drawable =
                (DitheredGradientDrawable) resolved.drawable;
        final String key = drawable.cacheKey(width, height);
        if (key.equals(processPrewarmKey)) {
            return;
        }
        drawable.prewarm(context, width, height);
        processPrewarmKey = key;
    }

    /**
     * Rebuilds or loads the selected built-in wallpaper immediately, e.g. while Appearance
     * settings are still open. Returning to chats is therefore a memory-cache read only.
     */
    public static synchronized void prewarmForDisplay(final Context context) {
        if (context == null || isCustomSelected(context)) {
            processPrewarmKey = "custom";
            return;
        }
        final RasterSize rasterSize = resolveRasterSize(context);
        final int width = rasterSize.width;
        final int height = rasterSize.height;
        final Resolved resolved = resolve(context);
        if (resolved.drawable instanceof DitheredGradientDrawable) {
            final DitheredGradientDrawable drawable =
                    (DitheredGradientDrawable) resolved.drawable;
            drawable.prewarm(context, width, height);
            processPrewarmKey = drawable.cacheKey(width, height);
        }
    }

    /**
     * Marks only the in-memory prewarm state stale. Persistent cache files remain available and
     * are reused automatically if the resulting render key still matches.
     */
    public static synchronized void resetProcessPrewarm() {
        processPrewarmKey = null;
    }

    public enum Preset {
        MIST(
                "mist",
                eu.siacs.conversations.R.string.neocont_chat_wallpaper_mist,
                0xFFFBFCFD,
                0xFFF0F5F6,
                0xFF101416,
                0xFF172228),
        NORTH(
                "north",
                eu.siacs.conversations.R.string.neocont_chat_wallpaper_north,
                0xFFF8FBFD,
                0xFFEAF3F8,
                0xFF10151A,
                0xFF122B38),
        TWILIGHT(
                "twilight",
                eu.siacs.conversations.R.string.neocont_chat_wallpaper_twilight,
                0xFFFBFAFD,
                0xFFEEF0F8,
                0xFF111318,
                0xFF20213A),
        FOREST(
                "forest",
                eu.siacs.conversations.R.string.neocont_chat_wallpaper_forest,
                0xFFFAFCFA,
                0xFFF1F4F1,
                0xFF111513,
                0xFF19201C),
        SAND(
                "sand",
                eu.siacs.conversations.R.string.neocont_chat_wallpaper_sand,
                0xFFFCFBF8,
                0xFFF4EFE7,
                0xFF151310,
                0xFF28221C);

        public final String id;
        @StringRes public final int labelRes;
        @ColorInt final int lightTop;
        @ColorInt final int lightBottom;
        @ColorInt final int darkTop;
        @ColorInt final int darkBottom;

        Preset(
                final String id,
                @StringRes final int labelRes,
                @ColorInt final int lightTop,
                @ColorInt final int lightBottom,
                @ColorInt final int darkTop,
                @ColorInt final int darkBottom) {
            this.id = id;
            this.labelRes = labelRes;
            this.lightTop = lightTop;
            this.lightBottom = lightBottom;
            this.darkTop = darkTop;
            this.darkBottom = darkBottom;
        }

        @NonNull
        public static Preset fromId(final String id) {
            if (id != null) {
                for (final Preset preset : values()) {
                    if (preset.id.equals(id)) {
                        return preset;
                    }
                }
            }
            return MIST;
        }
    }

    public static final class Resolved {
        public final Preset preset;
        @ColorInt public final int topColor;
        @ColorInt public final int bottomColor;
        public final GradientDrawable drawable;
        public final String renderKey;

        private Resolved(
                final Preset preset,
                @ColorInt final int topColor,
                @ColorInt final int bottomColor,
                final GradientDrawable drawable) {
            this.preset = preset;
            this.topColor = topColor;
            this.bottomColor = bottomColor;
            this.drawable = drawable;
            this.renderKey =
                    preset.id
                            + ":"
                            + Integer.toHexString(topColor)
                            + ":"
                            + Integer.toHexString(bottomColor);
        }

        /**
         * The live chat and the settings preview intentionally share this exact drawable.
         *
         * <p>The gradient itself stays authoritative. A deterministic one-level, per-pixel dither
         * is composited on top to break 8-bit banding without introducing the visible square/tile
         * structure of the old bitmap resource.
         */
        public Drawable createChatDrawable(final Context context) {
            if (drawable instanceof DitheredGradientDrawable) {
                ((DitheredGradientDrawable) drawable).configureForChat(context);
            }
            return drawable;
        }
    }

    public static String getSelection(final Context context) {
        final var preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (preferences.contains(PREFERENCE_KEY)) {
            return preferences.getString(PREFERENCE_KEY, DEFAULT_ID);
        }

        // Preserve the old global custom wallpaper across the new preset migration. Fresh
        // installs have no file and therefore start on Mist.
        final File legacyGlobal =
                new File(
                        context.getFilesDir()
                                + File.separator
                                + "backgrounds"
                                + File.separator
                                + "bg.jpg");
        if (legacyGlobal.exists()) {
            preferences.edit().putString(PREFERENCE_KEY, CUSTOM_ID).apply();
            return CUSTOM_ID;
        }
        return DEFAULT_ID;
    }

    public static boolean isCustomSelected(final Context context) {
        return CUSTOM_ID.equals(getSelection(context));
    }

    public static void selectPreset(final Context context, final Preset preset) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(PREFERENCE_KEY, preset == null ? DEFAULT_ID : preset.id)
                .apply();
    }

    public static void selectCustom(final Context context) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(PREFERENCE_KEY, CUSTOM_ID)
                .apply();
    }

    public static Preset getSelectedPreset(final Context context) {
        final String selection = getSelection(context);
        return CUSTOM_ID.equals(selection) ? Preset.MIST : Preset.fromId(selection);
    }

    public static Resolved resolve(final Context context) {
        return resolve(context, getSelectedPreset(context));
    }

    public static Resolved resolve(final Context context, final Preset preset) {
        final boolean dark = ThemeHelper.isDark(ThemeHelper.find(context));
        final int fallbackAccent = dark ? 0xFF90C5DC : 0xFF3E7087;
        final int accent = resolveThemeColor(context, R.attr.colorPrimary, fallbackAccent);

        final int baseTop = dark ? preset.darkTop : preset.lightTop;
        final int baseBottom = dark ? preset.darkBottom : preset.lightBottom;
        final float topMix = dark ? DARK_TOP_ACCENT_MIX : LIGHT_TOP_ACCENT_MIX;
        final float bottomMix = dark ? DARK_BOTTOM_ACCENT_MIX : LIGHT_BOTTOM_ACCENT_MIX;

        final int top = ColorUtils.blendARGB(baseTop, accent, topMix);
        final int bottom = ColorUtils.blendARGB(baseBottom, accent, bottomMix);
        final int middle = ColorUtils.blendARGB(top, bottom, 0.52f);

        final GradientDrawable drawable =
                new DitheredGradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        new int[] {top, middle, bottom});
        drawable.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return new Resolved(preset, top, bottom, drawable);
    }

    /**
     * GradientDrawable-compatible renderer backed by a one-to-one ARGB_8888 bitmap.
     *
     * <p>Android's GPU gradient path can quantize very low-contrast dark gradients into visible
     * diagonal contours. Drawing translucent noise afterwards does not reliably fix that because
     * the gradient has already been quantized. This renderer interpolates the gradient in float,
     * adds deterministic sub-two-level luminance noise, and only then rounds into the final
     * ARGB_8888 pixels. The chat and appearance preview therefore share the exact same
     * pre-dithered renderer.
     */
    private static final class DitheredGradientDrawable extends GradientDrawable {
        private final Orientation orientation;
        private final int[] colors;
        private final Paint bitmapPaint = new Paint();
        private final RectF drawBounds = new RectF();
        private final Path clipPath = new Path();

        private Bitmap renderedBitmap;
        private int renderedWidth;
        private int renderedHeight;
        private int preferredRasterWidth;
        private int preferredRasterHeight;
        private Context rasterContext;
        private float cornerRadius;
        private float[] cornerRadii;
        private int alpha = 255;

        DitheredGradientDrawable(final Orientation orientation, final int[] colors) {
            super(orientation, colors);
            this.orientation = orientation;
            this.colors = colors.clone();
            bitmapPaint.setAntiAlias(false);
            bitmapPaint.setFilterBitmap(false);
            bitmapPaint.setDither(false);
        }

        void prewarm(final Context context, final int width, final int height) {
            final String key = cacheKey(width, height);
            final Bitmap cached = RASTER_CACHE.get(key);
            if (cached != null && !cached.isRecycled()) {
                return;
            }

            final Bitmap persisted = loadPersistentRaster(context, width, height);
            if (persisted != null) {
                RASTER_CACHE.put(key, persisted);
                return;
            }

            final Bitmap rendered = renderGradient(width, height);
            if (rendered != null) {
                RASTER_CACHE.put(key, rendered);
                persistRaster(context, rendered, width, height);
            }
        }

        void configureForChat(final Context context) {
            final RasterSize rasterSize = resolveRasterSize(context);
            preferredRasterWidth = rasterSize.width;
            preferredRasterHeight = rasterSize.height;
            rasterContext = context.getApplicationContext();
        }

        @Override
        public void setCornerRadius(final float radius) {
            super.setCornerRadius(radius);
            cornerRadius = radius;
            cornerRadii = null;
        }

        @Override
        public void setCornerRadii(final float[] radii) {
            super.setCornerRadii(radii);
            cornerRadii = radii == null ? null : radii.clone();
            cornerRadius = 0f;
        }

        @Override
        public void setAlpha(final int alpha) {
            super.setAlpha(alpha);
            this.alpha = Math.max(0, Math.min(255, alpha));
            bitmapPaint.setAlpha(this.alpha);
        }

        @Override
        public void draw(@NonNull final Canvas canvas) {
            final android.graphics.Rect bounds = getBounds();
            if (bounds.isEmpty()) {
                return;
            }

            final int width = bounds.width();
            final int height = bounds.height();
            ensureRenderedBitmap(width, height);
            if (renderedBitmap == null) {
                // Never expose the GPU gradient as a user-visible fallback: on low-contrast dark
                // palettes it produces obvious quantization bands. If raster generation ever
                // fails completely, use a calm flat surface instead of showing a staircase.
                final int save = canvas.save();
                canvas.clipRect(bounds);
                canvas.drawColor(colors[Math.min(colors.length - 1, colors.length / 2)]);
                canvas.restoreToCount(save);
                return;
            }

            drawBounds.set(bounds);
            final int save = canvas.save();
            canvas.clipRect(drawBounds);
            if (cornerRadii != null) {
                clipPath.reset();
                clipPath.addRoundRect(drawBounds, cornerRadii, Path.Direction.CW);
                canvas.clipPath(clipPath);
            } else if (cornerRadius > 0f) {
                clipPath.reset();
                clipPath.addRoundRect(
                        drawBounds, cornerRadius, cornerRadius, Path.Direction.CW);
                canvas.clipPath(clipPath);
            }

            bitmapPaint.setAlpha(alpha);
            canvas.drawBitmap(renderedBitmap, bounds.left, bounds.top, bitmapPaint);
            canvas.restoreToCount(save);
        }

        private void ensureRenderedBitmap(final int width, final int height) {
            if (renderedBitmap != null
                    && !renderedBitmap.isRecycled()
                    && renderedWidth == width
                    && renderedHeight == height) {
                return;
            }

            final String exactKey = cacheKey(width, height);
            Bitmap exact = RASTER_CACHE.get(exactKey);
            if ((exact == null || exact.isRecycled()) && rasterContext != null) {
                exact = loadPersistentRaster(rasterContext, width, height);
                if (exact != null) {
                    RASTER_CACHE.put(exactKey, exact);
                }
            }
            if (exact != null && !exact.isRecycled()) {
                renderedWidth = width;
                renderedHeight = height;
                renderedBitmap = exact;
                return;
            }

            // The chat view is normally slightly shorter than the physical display because of
            // system/chrome insets. Reuse the full-screen prewarm 1:1 and crop it; do not rescale
            // and do not rasterize again.
            if (preferredRasterWidth > 0 && preferredRasterHeight > 0) {
                final String preferredKey =
                        cacheKey(preferredRasterWidth, preferredRasterHeight);
                Bitmap preferred = RASTER_CACHE.get(preferredKey);
                if ((preferred == null || preferred.isRecycled()) && rasterContext != null) {
                    preferred =
                            loadPersistentRaster(
                                    rasterContext,
                                    preferredRasterWidth,
                                    preferredRasterHeight);
                    if (preferred != null) {
                        RASTER_CACHE.put(preferredKey, preferred);
                    }
                }
                if (preferred != null
                        && !preferred.isRecycled()
                        && preferred.getWidth() >= width
                        && preferred.getHeight() >= height) {
                    renderedWidth = width;
                    renderedHeight = height;
                    renderedBitmap = preferred;
                    return;
                }
            }

            // A cache miss is rare (first install, cache cleanup, renderer/theme/size change).
            // Prefer paying this one-time CPU cost over ever exposing a visibly banded gradient.
            renderedWidth = width;
            renderedHeight = height;
            renderedBitmap = renderGradient(width, height);
            if (renderedBitmap != null) {
                RASTER_CACHE.put(exactKey, renderedBitmap);
                if (rasterContext != null) {
                    persistRaster(rasterContext, renderedBitmap, width, height);
                }
            }
        }

        private String cacheKey(final int width, final int height) {
            return "v"
                    + RASTER_FORMAT_VERSION
                    + ":"
                    + width
                    + "x"
                    + height
                    + ":"
                    + orientation.name()
                    + ":"
                    + Integer.toHexString(java.util.Arrays.hashCode(colors));
        }

        private File persistentRasterFile(
                final Context context, final int width, final int height) {
            final File directory =
                    new File(context.getNoBackupFilesDir(), RASTER_DIRECTORY);
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                return null;
            }
            final String fileName =
                    RASTER_FILE_PREFIX
                            + RASTER_FORMAT_VERSION
                            + "_"
                            + width
                            + "x"
                            + height
                            + "_"
                            + orientation.name()
                            + "_"
                            + Integer.toHexString(java.util.Arrays.hashCode(colors))
                            + ".png";
            return new File(directory, fileName);
        }

        private Bitmap loadPersistentRaster(
                final Context context, final int width, final int height) {
            final File file = persistentRasterFile(context, width, height);
            if (file == null || !file.isFile()) {
                return null;
            }

            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            final Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                if (decoded != null && !decoded.isRecycled()) {
                    decoded.recycle();
                }
                if (!file.delete()) {
                    file.deleteOnExit();
                }
                return null;
            }
            return decoded;
        }

        private void persistRaster(
                final Context context,
                final Bitmap bitmap,
                final int width,
                final int height) {
            if (context == null || bitmap == null || bitmap.isRecycled()) {
                return;
            }
            final Context appContext = context.getApplicationContext();
            RASTER_IO_EXECUTOR.execute(
                    () -> persistRasterNow(appContext, bitmap, width, height));
        }

        private void persistRasterNow(
                final Context context,
                final Bitmap bitmap,
                final int width,
                final int height) {
            final File target = persistentRasterFile(context, width, height);
            if (target == null || bitmap.isRecycled()) {
                return;
            }
            final File directory = target.getParentFile();
            if (directory == null) {
                return;
            }
            final File temporary = new File(directory, target.getName() + ".tmp");

            boolean written = false;
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                // PNG is lossless. Compression and fsync are deliberately off the UI thread;
                // the already-rendered bitmap is available in the RAM cache immediately.
                written = bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                output.flush();
                output.getFD().sync();
            } catch (final IOException ignored) {
                written = false;
            }

            if (!written) {
                if (!temporary.delete()) {
                    temporary.deleteOnExit();
                }
                return;
            }

            if (target.exists() && !target.delete()) {
                if (!temporary.delete()) {
                    temporary.deleteOnExit();
                }
                return;
            }
            if (!temporary.renameTo(target)) {
                if (!temporary.delete()) {
                    temporary.deleteOnExit();
                }
                return;
            }

            final File[] stale = directory.listFiles();
            if (stale == null) {
                return;
            }
            for (final File candidate : stale) {
                if (!candidate.equals(target)
                        && candidate.getName().startsWith(RASTER_FILE_PREFIX)) {
                    if (!candidate.delete()) {
                        candidate.deleteOnExit();
                    }
                }
            }
        }

        private Bitmap renderGradient(final int width, final int height) {
            if (width <= 0 || height <= 0) {
                return null;
            }

            final Bitmap bitmap =
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

            // Write several scanlines per native call. This keeps peak scratch memory bounded
            // while avoiding one Bitmap.setPixels() JNI crossing for every screen row.
            final int stripeRows = Math.min(RASTER_STRIPE_ROWS, height);
            final int[] stripePixels = new int[width * stripeRows];

            final float maxX = Math.max(1, width - 1);
            final float maxY = Math.max(1, height - 1);
            final float denominator = maxX * maxX + maxY * maxY;

            // These expressions are exactly the ones previously evaluated for every pixel.
            // Precomputing their X-only parts removes millions of repeated multiplications without
            // changing the interpolated coordinates or dither sequence.
            final float[] xProjection = new float[width];
            final int[] xNoiseSeed = new int[width];
            for (int x = 0; x < width; x++) {
                xProjection[x] = x * maxX;
                xNoiseSeed[x] = x * 0x1f123bb5;
            }

            final boolean threeStopGradient = colors.length >= 3;
            final int color0 = colors[0];
            final int color1 = colors[Math.min(1, colors.length - 1)];
            final int color2 = colors[Math.min(2, colors.length - 1)];

            final int r0 = Color.red(color0);
            final int g0 = Color.green(color0);
            final int b0 = Color.blue(color0);
            final int r1 = Color.red(color1);
            final int g1 = Color.green(color1);
            final int b1 = Color.blue(color1);
            final int r2 = Color.red(color2);
            final int g2 = Color.green(color2);
            final int b2 = Color.blue(color2);

            for (int stripeStart = 0; stripeStart < height; stripeStart += stripeRows) {
                final int rows = Math.min(stripeRows, height - stripeStart);
                int outputIndex = 0;

                for (int row = 0; row < rows; row++) {
                    final int y = stripeStart + row;
                    final float yProjection = y * maxY;
                    final int yNoiseSeed = y * 0x5f356495 ^ 0x6d2b79f5;

                    for (int x = 0; x < width; x++) {
                        final float t;
                        if (orientation == Orientation.TL_BR) {
                            t = clamp01((xProjection[x] + yProjection) / denominator);
                        } else {
                            // Non-default orientations are rare; preserve the general path.
                            t = gradientPosition(x, y, maxX, maxY, denominator);
                        }

                        final float segment;
                        final int fromR;
                        final int fromG;
                        final int fromB;
                        final int toR;
                        final int toG;
                        final int toB;
                        if (threeStopGradient) {
                            if (t <= 0.5f) {
                                fromR = r0;
                                fromG = g0;
                                fromB = b0;
                                toR = r1;
                                toG = g1;
                                toB = b1;
                                segment = t * 2f;
                            } else {
                                fromR = r1;
                                fromG = g1;
                                fromB = b1;
                                toR = r2;
                                toG = g2;
                                toB = b2;
                                segment = (t - 0.5f) * 2f;
                            }
                        } else {
                            fromR = r0;
                            fromG = g0;
                            fromB = b0;
                            toR = r1;
                            toG = g1;
                            toB = b1;
                            segment = t;
                        }

                        int hash = xNoiseSeed[x] ^ yNoiseSeed;
                        hash ^= hash >>> 15;
                        hash *= 0x2c1b3c6d;
                        hash ^= hash >>> 12;
                        hash *= 0x297a2d39;
                        hash ^= hash >>> 15;
                        final float unit = (hash & 0xffff) / 65535f;
                        final float noise = (unit * 2f - 1f) * DITHER_AMPLITUDE;

                        final int red =
                                clamp8(
                                        Math.round(
                                                fromR
                                                        + (toR - fromR) * segment
                                                        + noise));
                        final int green =
                                clamp8(
                                        Math.round(
                                                fromG
                                                        + (toG - fromG) * segment
                                                        + noise));
                        final int blue =
                                clamp8(
                                        Math.round(
                                                fromB
                                                        + (toB - fromB) * segment
                                                        + noise));

                        stripePixels[outputIndex++] =
                                0xff000000 | (red << 16) | (green << 8) | blue;
                    }
                }

                bitmap.setPixels(
                        stripePixels,
                        0,
                        width,
                        0,
                        stripeStart,
                        width,
                        rows);
            }

            return bitmap;
        }

        private float clamp01(final float value) {
            return Math.max(0f, Math.min(1f, value));
        }

        private float gradientPosition(
                final int x,
                final int y,
                final float maxX,
                final float maxY,
                final float denominator) {
            final float t;
            if (orientation == Orientation.TL_BR) {
                // Match a LinearGradient from the top-left to the bottom-right by projecting
                // the pixel onto that diagonal rather than averaging normalized X/Y.
                t = (x * maxX + y * maxY) / denominator;
            } else if (orientation == Orientation.TR_BL) {
                t = ((maxX - x) * maxX + y * maxY) / denominator;
            } else if (orientation == Orientation.TOP_BOTTOM) {
                t = y / maxY;
            } else if (orientation == Orientation.LEFT_RIGHT) {
                t = x / maxX;
            } else {
                // Built-in wallpapers currently use TL_BR. Keep a deterministic fallback for
                // future orientations instead of silently returning to the GPU gradient path.
                t = (x * maxX + y * maxY) / denominator;
            }
            return Math.max(0f, Math.min(1f, t));
        }

        private float ditherNoise(final int x, final int y) {
            int hash = x * 0x1f123bb5 ^ y * 0x5f356495 ^ 0x6d2b79f5;
            hash ^= hash >>> 15;
            hash *= 0x2c1b3c6d;
            hash ^= hash >>> 12;
            hash *= 0x297a2d39;
            hash ^= hash >>> 15;

            final float unit = (hash & 0xffff) / 65535f;
            return (unit * 2f - 1f) * DITHER_AMPLITUDE;
        }

        private int clamp8(final int value) {
            return Math.max(0, Math.min(255, value));
        }
    }

    private static RasterSize resolveRasterSize(final Context context) {
        if (context instanceof Activity) {
            final Activity activity = (Activity) context;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                final Rect bounds =
                        activity.getWindowManager().getCurrentWindowMetrics().getBounds();
                return new RasterSize(
                        Math.max(1, bounds.width()),
                        Math.max(1, bounds.height()));
            }

            final DisplayMetrics realMetrics = new DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getRealMetrics(realMetrics);
            return new RasterSize(
                    Math.max(1, realMetrics.widthPixels),
                    Math.max(1, realMetrics.heightPixels));
        }

        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return new RasterSize(
                Math.max(1, metrics.widthPixels),
                Math.max(1, metrics.heightPixels));
    }

    private static final class RasterSize {
        final int width;
        final int height;

        RasterSize(final int width, final int height) {
            this.width = width;
            this.height = height;
        }
    }

    @ColorInt
    private static int resolveThemeColor(
            final Context context, final int attribute, @ColorInt final int fallback) {
        final TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attribute, value, true)) {
            return fallback;
        }
        if (value.resourceId != 0) {
            try {
                return ContextCompat.getColor(context, value.resourceId);
            } catch (final Resources.NotFoundException ignored) {
                return fallback;
            }
        }
        return value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && value.type <= TypedValue.TYPE_LAST_COLOR_INT
                ? value.data
                : fallback;
    }
}
