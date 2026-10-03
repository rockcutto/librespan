package eu.siacs.conversations.ui.util;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

/** Lightweight shared play marker for video thumbnails in chat and attachment surfaces. */
public final class VideoThumbnailOverlay {

    private VideoThumbnailOverlay() {}

    public static void draw(final Bitmap bitmap, final Resources resources) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }

        final float shortSide = Math.min(bitmap.getWidth(), bitmap.getHeight());
        if (shortSide <= 0f) {
            return;
        }

        final float density = resources == null ? 1f : resources.getDisplayMetrics().density;
        final float diameter = Math.min(shortSide * 0.26f, 42f * density);
        final float radius = diameter / 2f;
        final float centerX = bitmap.getWidth() / 2f;
        final float centerY = bitmap.getHeight() / 2f;

        final Canvas canvas = new Canvas(bitmap);

        final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setColor(Color.argb(112, 0, 0, 0));
        canvas.drawCircle(centerX, centerY, radius, background);

        // Slight optical shift to the right keeps the triangle visually centred in the circle.
        final float triangleHeight = diameter * 0.44f;
        final float triangleWidth = diameter * 0.36f;
        final float triangleCenterX = centerX + diameter * 0.035f;
        final float left = triangleCenterX - triangleWidth * 0.42f;
        final float right = triangleCenterX + triangleWidth * 0.58f;
        final float top = centerY - triangleHeight / 2f;
        final float bottom = centerY + triangleHeight / 2f;

        final Path triangle = new Path();
        triangle.moveTo(left, top);
        triangle.lineTo(right, centerY);
        triangle.lineTo(left, bottom);
        triangle.close();

        final Paint foreground = new Paint(Paint.ANTI_ALIAS_FLAG);
        foreground.setColor(Color.argb(242, 255, 255, 255));
        canvas.drawPath(triangle, foreground);
    }
}
