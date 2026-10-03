package eu.siacs.conversations.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.exifinterface.media.ExifInterface;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.ui.interfaces.OnAvatarPublication;

/**
 * One-purpose avatar editor. The visible selection is circular; only the compatible square
 * export is staged, and the original picked document is never modified.
 */
public class AvatarCropEditorActivity extends XmppActivity implements OnAvatarPublication {
    private static final int REQUEST_CHOOSE = 0xA71;
    private Account account;
    private AvatarCropView cropView;
    private TextView hint;
    private Uri stagedUri;
    private MenuItem doneItem;
    private boolean pickerLaunched;
    private boolean publishing;

    @Override
    protected void onCreate(final Bundle state) {
        super.onCreate(state);

        final int toolbarHeight = dp(56);
        final int footerHeight = dp(64);
        final int surface =
                com.google.android.material.color.MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorSurface,
                        Color.BLACK);
        final int onSurface =
                com.google.android.material.color.MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOnSurface,
                        Color.WHITE);
        final int onSurfaceVariant =
                com.google.android.material.color.MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                        onSurface);

        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(surface);

        // The crop mask belongs only to the photo stage. Keeping toolbar/actions and the gesture
        // hint outside this view guarantees that no editor control can ever sit under the mask.
        cropView = new AvatarCropView();
        cropView.setBackgroundColor(Color.BLACK);
        final FrameLayout.LayoutParams cropParams =
                new FrameLayout.LayoutParams(-1, -1);
        cropParams.topMargin = toolbarHeight;
        cropParams.bottomMargin = footerHeight;
        root.addView(cropView, cropParams);

        final MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(R.string.avatar_crop_title);
        toolbar.setTitleTextColor(onSurface);
        toolbar.setBackgroundColor(surface);
        toolbar.setElevation(0f);
        toolbar.setNavigationOnClickListener(view -> finish());
        root.addView(toolbar, new FrameLayout.LayoutParams(-1, toolbarHeight, Gravity.TOP));
        setSupportActionBar(toolbar);

        hint = new TextView(this);
        hint.setText(R.string.avatar_crop_hint);
        hint.setTextColor(onSurfaceVariant);
        hint.setBackgroundColor(surface);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(20), dp(8), dp(20), dp(12));
        root.addView(
                hint,
                new FrameLayout.LayoutParams(-1, footerHeight, Gravity.BOTTOM));
        setContentView(root);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        doneItem = menu.add(R.string.done);
        doneItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        updateDoneState();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item == doneItem) {
            publish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void refreshUiReal() {
        // Crop/editor state is local. Avatar publication callbacks update the UI explicitly.
    }

    @Override
    protected void onBackendConnected() {
        account = extractAccount(getIntent());
        if (account == null) {
            finish();
            return;
        }
        if (!pickerLaunched && !cropView.hasImage()) {
            pickerLaunched = true;
            choosePhoto();
        }
    }

    private void choosePhoto() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, getString(R.string.attach_choose_picture)), REQUEST_CHOOSE);
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CHOOSE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }
        if (!cropView.setImage(data.getData())) {
            Toast.makeText(this, R.string.error_publish_avatar_converting, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        updateDoneState();
    }

    private void updateDoneState() {
        if (doneItem != null) {
            doneItem.setEnabled(!publishing && cropView != null && cropView.isReady());
        }
    }

    private void publish() {
        if (publishing || account == null || cropView == null || !cropView.isReady()) {
            return;
        }
        publishing = true;
        updateDoneState();
        try {
            stagedUri = writeStagedAvatar(cropView.render());
            xmppConnectionService.publishAvatarAsync(account, stagedUri, false, this);
        } catch (final IOException | RuntimeException e) {
            publishing = false;
            deleteStaged();
            updateDoneState();
            Toast.makeText(this, R.string.error_publish_avatar_converting, Toast.LENGTH_SHORT).show();
        }
    }

    private Uri writeStagedAvatar(final Bitmap bitmap) throws IOException {
        final File directory = new File(new File(getCacheDir(), "AvatarCrop"), account.getUuid());
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create avatar crop staging directory");
        }
        final File file = new File(directory, UUID.randomUUID() + ".png");
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("Unable to encode avatar crop");
            }
        }
        return Uri.fromFile(file);
    }

    private void deleteStaged() {
        if (stagedUri != null && "file".equals(stagedUri.getScheme())) {
            final String path = stagedUri.getPath();
            if (path != null) {
                new File(path).delete();
            }
        }
        stagedUri = null;
    }

    @Override
    public void onAvatarPublicationSucceeded() {
        deleteStaged();
        runOnUiThread(() -> {
            Toast.makeText(this, R.string.avatar_has_been_published, Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    @Override
    public void onAvatarPublicationFailed(final int res) {
        publishing = false;
        deleteStaged();
        runOnUiThread(() -> {
            updateDoneState();
            Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onDestroy() {
        if (!publishing) {
            deleteStaged();
        }
        super.onDestroy();
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class AvatarCropView extends android.view.View {
        private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private final android.view.GestureDetector gestureDetector;
        private Bitmap bitmap;
        private final RectF circle = new RectF();
        private float minScale;
        private float currentScale;
        private float lastX;
        private float lastY;

        AvatarCropView() {
            super(AvatarCropEditorActivity.this);
            shadePaint.setStyle(Paint.Style.FILL);
            shadePaint.setColor(Color.argb(96, 0, 0, 0));
            scaleDetector = new ScaleGestureDetector(AvatarCropEditorActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(final ScaleGestureDetector detector) {
                    zoom(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                    hideHint();
                    return true;
                }
            });
            gestureDetector = new android.view.GestureDetector(AvatarCropEditorActivity.this, new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDoubleTap(final MotionEvent event) {
                    if (!isReady()) {
                        reset();
                        return true;
                    }
                    final float target = currentScale > minScale * 1.25f ? minScale : minScale * 1.65f;
                    zoom(target / currentScale, circle.centerX(), circle.centerY());
                    hideHint();
                    return true;
                }
            });
        }

        boolean setImage(final Uri uri) {
            try {
                final Bitmap decoded = decode(uri);
                if (decoded == null) {
                    return false;
                }
                if (bitmap != null && bitmap != decoded && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                bitmap = decoded;
                if (getWidth() > 0 && getHeight() > 0 && circle.width() > 0f) {
                    reset();
                } else {
                    post(this::reset);
                }
                invalidate();
                return true;
            } catch (final IOException | RuntimeException e) {
                return false;
            }
        }

        boolean hasImage() {
            return bitmap != null && !bitmap.isRecycled();
        }

        boolean isReady() {
            return hasImage()
                    && circle.width() > 0f
                    && circle.height() > 0f
                    && minScale > 0f
                    && currentScale > 0f
                    && Float.isFinite(minScale)
                    && Float.isFinite(currentScale);
        }

        private Bitmap decode(final Uri uri) throws IOException {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(input, null, bounds);
            }
            int sample = 1;
            while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 2048) sample *= 2;
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            final Bitmap decoded;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    return null;
                }
                decoded = BitmapFactory.decodeStream(input, null, options);
            }
            if (decoded == null) {
                return null;
            }
            return rotateForExif(decoded, readExifRotation(uri));
        }

        private int readExifRotation(final Uri uri) {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    return 0;
                }
                final ExifInterface exif = new ExifInterface(input);
                switch (exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_UNDEFINED)) {
                    case ExifInterface.ORIENTATION_ROTATE_90:
                        return 90;
                    case ExifInterface.ORIENTATION_ROTATE_180:
                        return 180;
                    case ExifInterface.ORIENTATION_ROTATE_270:
                        return 270;
                    default:
                        return 0;
                }
            } catch (final IOException | SecurityException e) {
                return 0;
            }
        }

        private Bitmap rotateForExif(final Bitmap source, final int degrees) {
            if (degrees == 0) {
                return source;
            }
            final Matrix rotation = new Matrix();
            rotation.postRotate(degrees);
            final Bitmap rotated =
                    Bitmap.createBitmap(
                            source, 0, 0, source.getWidth(), source.getHeight(), rotation, true);
            if (rotated != source && !source.isRecycled()) {
                source.recycle();
            }
            return rotated;
        }

        @Override protected void onSizeChanged(final int width, final int height, final int oldWidth, final int oldHeight) {
            // Keep the crop aperture comfortably inside the dedicated stage even on short or
            // landscape screens. The photo remains visible around it as positioning context.
            final float diameter = Math.min(width * .72f, height * .78f);
            circle.set(
                    (width - diameter) / 2f,
                    (height - diameter) / 2f,
                    (width + diameter) / 2f,
                    (height + diameter) / 2f);
            reset();
        }

        private void reset() {
            if (bitmap == null || circle.width() == 0) return;
            minScale = Math.max(circle.width() / bitmap.getWidth(), circle.height() / bitmap.getHeight());
            currentScale = minScale;
            matrix.reset();
            matrix.postScale(minScale, minScale);
            matrix.postTranslate(circle.centerX() - bitmap.getWidth() * minScale / 2f, circle.centerY() - bitmap.getHeight() * minScale / 2f);
            invalidate();
            AvatarCropEditorActivity.this.updateDoneState();
        }

        private void zoom(final float factor, final float pivotX, final float pivotY) {
            if (!isReady() || !Float.isFinite(factor) || factor <= 0f) {
                reset();
                return;
            }
            final float next = Math.max(minScale, Math.min(minScale * 4f, currentScale * factor));
            matrix.postScale(next / currentScale, next / currentScale, pivotX, pivotY);
            currentScale = next;
            clamp();
            invalidate();
        }

        private void clamp() {
            final RectF bounds = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
            matrix.mapRect(bounds);
            float dx = 0, dy = 0;
            if (bounds.left > circle.left) dx = circle.left - bounds.left;
            else if (bounds.right < circle.right) dx = circle.right - bounds.right;
            if (bounds.top > circle.top) dy = circle.top - bounds.top;
            else if (bounds.bottom < circle.bottom) dy = circle.bottom - bounds.bottom;
            matrix.postTranslate(dx, dy);
        }

        @Override public boolean onTouchEvent(final MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastX = event.getX(); lastY = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress()) {
                final float dx = event.getX() - lastX, dy = event.getY() - lastY;
                matrix.postTranslate(dx, dy); clamp(); invalidate();
                lastX = event.getX(); lastY = event.getY(); hideHint();
            }
            return true;
        }

        private void hideHint() {
            if (hint.getVisibility() == VISIBLE) hint.setVisibility(GONE);
        }

        Bitmap render() {
            if (!isReady()) {
                throw new IllegalStateException("Avatar crop is not initialized");
            }
            final Bitmap output = Bitmap.createBitmap(Config.AVATAR_SIZE, Config.AVATAR_SIZE, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(output);
            canvas.scale(Config.AVATAR_SIZE / circle.width(), Config.AVATAR_SIZE / circle.height());
            canvas.translate(-circle.left, -circle.top);
            canvas.drawBitmap(bitmap, matrix, imagePaint);
            return output;
        }

        @Override protected void onDraw(final Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null) return;
            canvas.drawBitmap(bitmap, matrix, imagePaint);
            final android.graphics.Path shade = new android.graphics.Path();
            shade.setFillType(android.graphics.Path.FillType.EVEN_ODD);
            shade.addRect(0, 0, getWidth(), getHeight(), android.graphics.Path.Direction.CW);
            shade.addCircle(circle.centerX(), circle.centerY(), circle.width() / 2f, android.graphics.Path.Direction.CCW);
            canvas.drawPath(shade, shadePaint);
        }
    }
}
