package eu.siacs.conversations.ui.adapter;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.DimenRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.ImageViewCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.common.base.Strings;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ItemMediaBinding;
import eu.siacs.conversations.storage.secure.AndroidSecureMessageMediaThumbnailReader;
import eu.siacs.conversations.storage.secure.SecureMessageMediaUiBridge;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.ViewUtil;

public class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.MediaViewHolder> {

    // AsyncTask.execute() is serial on modern Android. Thumbnail work must stay bounded but
    // parallel so a visible grid does not decrypt/decode one cell at a time.
    private static final ExecutorService THUMBNAIL_EXECUTOR =
            Executors.newFixedThreadPool(
                    2,
                    runnable -> {
                        final Thread thread = new Thread(runnable, "attachment-thumbnail");
                        thread.setDaemon(true);
                        return thread;
                    });

    public interface OnAttachmentClickListener {
        void onAttachmentClick(Attachment attachment);
    }

    public interface OnAttachmentLongClickListener {
        void onAttachmentLongClick(Attachment attachment);
    }

    public static final List<String> DOCUMENT_MIMES =
            Arrays.asList(
                    "application/pdf",
                    "application/vnd.oasis.opendocument.text",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "text/x-tex",
                    "text/plain");
    public static final List<String> SPREAD_SHEET_MIMES =
            Arrays.asList(
                    "text/comma-separated-values",
                    "application/vnd.ms-excel",
                    "application/vnd.stardivision.calc",
                    "application/vnd.oasis.opendocument.spreadsheet",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    public static final List<String> SLIDE_SHOW_MIMES =
            Arrays.asList(
                    "application/vnd.ms-powerpoint",
                    "application/vnd.stardivision.impress",
                    "application/vnd.oasis.opendocument.presentation",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/vnd.openxmlformats-officedocument.presentationml.slideshow");

    private static final List<String> ARCHIVE_MIMES =
            Arrays.asList(
                    "application/x-7z-compressed",
                    "application/zip",
                    "application/rar",
                    "application/x-gtar",
                    "application/x-tar");
    public static final List<String> CODE_MIMES = Arrays.asList("text/html", "text/xml");

    private final ArrayList<Attachment> attachments = new ArrayList<>();

    private final XmppActivity activity;
    @Nullable private String accountUuid;
    @Nullable private OnAttachmentClickListener onAttachmentClickListener;
    @Nullable private OnAttachmentLongClickListener onAttachmentLongClickListener;

    private int mediaSize = 0;

    public MediaAdapter(XmppActivity activity, @DimenRes int mediaSize) {
        this(activity, mediaSize, null);
    }

    public MediaAdapter(
            XmppActivity activity, @DimenRes int mediaSize, @Nullable String accountUuid) {
        this.activity = activity;
        this.accountUuid = accountUuid;
        this.mediaSize = Math.round(activity.getResources().getDimension(mediaSize));
    }

    @SuppressWarnings("rawtypes")
    public static void setMediaSize(final RecyclerView recyclerView, int mediaSize) {
        final RecyclerView.Adapter adapter = recyclerView.getAdapter();
        if (adapter instanceof MediaAdapter mediaAdapter) {
            mediaAdapter.setMediaSize(mediaSize);
        }
    }

    public static @DrawableRes int getImageDrawable(final Attachment attachment) {
        if (attachment.getType() == Attachment.Type.LOCATION) {
            return R.drawable.ic_location_pin_48dp;
        } else if (attachment.getType() == Attachment.Type.RECORDING) {
            return R.drawable.ic_media_play_48dp;
        } else {
            return getImageDrawable(attachment.getMime());
        }
    }

    public static @DrawableRes int getImageDrawable(final String mime) {

        // TODO ideas for more mime types: XML, HTML documents, GPG/PGP files, eml files,
        // spreadsheets (table symbol)

        // add bz2 and tar.gz to archive detection

        if (Strings.isNullOrEmpty(mime)) {
            return R.drawable.ic_file_48dp;
        } else if (mime.startsWith("audio/")) {
            return R.drawable.ic_media_play_48dp;
        } else if (mime.equals("text/calendar") || (mime.equals("text/x-vcalendar"))) {
            return R.drawable.ic_event_48dp;
        } else if (mime.equals("text/x-vcard")) {
            return R.drawable.ic_person_48dp;
        } else if (mime.equals("application/vnd.android.package-archive")) {
            return R.drawable.ic_adb_48dp;
        } else if (ARCHIVE_MIMES.contains(mime)) {
            return R.drawable.ic_archive_48dp;
        } else if (mime.equals("application/epub+zip")
                || mime.equals("application/vnd.amazon.mobi8-ebook")
                || mime.equals("application/x-fictionbook+xml")) {
            return R.drawable.ic_book_48dp;
        } else if (DOCUMENT_MIMES.contains(mime)) {
            return R.drawable.ic_description_48dp;
        } else if (SPREAD_SHEET_MIMES.contains(mime)) {
            return R.drawable.ic_table_48dp;
        } else if (SLIDE_SHOW_MIMES.contains(mime)) {
            return R.drawable.ic_slideshow_48dp;
        } else if (mime.equals("application/gpx+xml")) {
            return R.drawable.ic_tour_48dp;
        } else if (mime.startsWith("image/")) {
            return R.drawable.ic_image_48dp;
        } else if (mime.startsWith("video/")) {
            return R.drawable.ic_movie_48dp;
        } else if (CODE_MIMES.contains(mime)) {
            return R.drawable.ic_code_48dp;
        } else if (mime.equals("message/rfc822")) {
            return R.drawable.ic_email_48dp;
        } else {
            return R.drawable.ic_file_48dp;
        }
    }

    static void renderPreview(final Attachment attachment, final ImageView imageView) {
        final String mime = attachment.getMime();
        final boolean playbackMarker =
                attachment.getType() == Attachment.Type.RECORDING
                        || (!Strings.isNullOrEmpty(mime) && mime.startsWith("audio/"));
        ImageViewCompat.setImageTintList(
                imageView,
                playbackMarker
                        ? null
                        : ColorStateList.valueOf(
                                MaterialColors.getColor(
                                        imageView,
                                        com.google.android.material.R.attr.colorOnSurface)));
        imageView.setImageResource(getImageDrawable(attachment));
        imageView.setBackgroundColor(
                MaterialColors.getColor(
                        imageView,
                        com.google.android.material.R.attr.colorSurfaceContainerHighest));
    }

    private static boolean cancelPotentialWork(Attachment attachment, ImageView imageView) {
        final BitmapWorkerTask bitmapWorkerTask = getBitmapWorkerTask(imageView);

        if (bitmapWorkerTask != null) {
            final Attachment oldAttachment = bitmapWorkerTask.attachment;
            if (oldAttachment == null || !oldAttachment.equals(attachment)) {
                bitmapWorkerTask.cancel(true);
            } else {
                return false;
            }
        }
        return true;
    }

    private static BitmapWorkerTask getBitmapWorkerTask(ImageView imageView) {
        if (imageView != null) {
            final Drawable drawable = imageView.getDrawable();
            if (drawable instanceof AsyncDrawable asyncDrawable) {
                return asyncDrawable.getBitmapWorkerTask();
            }
        }
        return null;
    }

    @NonNull
    @Override
    public MediaViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final LayoutInflater layoutInflater = LayoutInflater.from(parent.getContext());
        ItemMediaBinding binding =
                DataBindingUtil.inflate(layoutInflater, R.layout.item_media, parent, false);
        return new MediaViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull MediaViewHolder holder, int position) {
        final Attachment attachment = attachments.get(position);
        if (attachment.renderThumbnail()) {
            loadPreview(attachment, holder.binding.media);
        } else {
            cancelPotentialWork(attachment, holder.binding.media);
            renderPreview(attachment, holder.binding.media);
        }
        holder.binding.mediaCard
                .setOnLongClickListener(
                        v -> {
                            if (onAttachmentLongClickListener == null) {
                                return false;
                            }
                            onAttachmentLongClickListener.onAttachmentLongClick(attachment);
                            return true;
                        });
        holder.binding.mediaCard
                .setOnClickListener(
                        v -> {
                            if (onAttachmentClickListener != null) {
                                onAttachmentClickListener.onAttachmentClick(attachment);
                                return;
                            }
                            if (attachment.isSecureMedia() && accountUuid != null) {
                                SecureMessageMediaUiBridge.openOrFallback(
                                        activity,
                                        accountUuid,
                                        attachment.getUuid().toString(),
                                        attachment.getMime(),
                                        () ->
                                                android.widget.Toast.makeText(
                                                                activity,
                                                                R.string.file_deleted,
                                                                android.widget.Toast.LENGTH_SHORT)
                                                        .show());
                            } else {
                                ViewUtil.view(activity, attachment);
                            }
                        });
    }

    public void setAttachments(final List<Attachment> attachments) {
        this.attachments.clear();
        this.attachments.addAll(attachments);
        notifyDataSetChanged();
    }

    public void appendAttachments(final List<Attachment> attachments) {
        if (attachments.isEmpty()) {
            return;
        }
        final int start = this.attachments.size();
        this.attachments.addAll(attachments);
        notifyItemRangeInserted(start, attachments.size());
    }

    public void removeAttachmentByUuid(final String messageUuid) {
        for (int index = attachments.size() - 1; index >= 0; index--) {
            final Attachment attachment = attachments.get(index);
            if (attachment.getUuid().toString().equals(messageUuid)) {
                attachments.remove(index);
                notifyItemRemoved(index);
                return;
            }
        }
    }

    public void setAccountUuid(@Nullable final String accountUuid) {
        this.accountUuid = accountUuid;
    }

    public void setOnAttachmentClickListener(
            @Nullable final OnAttachmentClickListener onAttachmentClickListener) {
        this.onAttachmentClickListener = onAttachmentClickListener;
    }

    public void setOnAttachmentLongClickListener(
            @Nullable final OnAttachmentLongClickListener onAttachmentLongClickListener) {
        this.onAttachmentLongClickListener = onAttachmentLongClickListener;
    }

    private void setMediaSize(int mediaSize) {
        this.mediaSize = mediaSize;
    }

    private void loadPreview(Attachment attachment, ImageView imageView) {
        if (cancelPotentialWork(attachment, imageView)) {
            final Bitmap bm =
                    activity.xmppConnectionService
                            .getFileBackend()
                            .getPreviewForUri(attachment, mediaSize, true);
            if (bm != null) {
                cancelPotentialWork(attachment, imageView);
                imageView.setImageBitmap(bm);
                imageView.setBackgroundColor(Color.TRANSPARENT);
            } else {
                // TODO consider if this is still a good, general purpose loading color
                imageView.setBackgroundColor(
                        MaterialColors.getColor(
                                imageView,
                                com.google.android.material.R.attr.colorSurfaceContainerHighest));
                imageView.setImageDrawable(null);
                final BitmapWorkerTask task =
                        new BitmapWorkerTask(mediaSize, imageView, accountUuid);
                final AsyncDrawable asyncDrawable =
                        new AsyncDrawable(activity.getResources(), null, task);
                imageView.setImageDrawable(asyncDrawable);
                try {
                    task.executeOnExecutor(THUMBNAIL_EXECUTOR, attachment);
                } catch (final RejectedExecutionException ignored) {
                }
            }
        }
    }

    @Nullable
    public Attachment getAttachmentAt(final int position) {
        return position >= 0 && position < attachments.size() ? attachments.get(position) : null;
    }

    @Override
    public int getItemCount() {
        return attachments.size();
    }

    static class AsyncDrawable extends BitmapDrawable {
        private final WeakReference<BitmapWorkerTask> bitmapWorkerTaskReference;

        AsyncDrawable(Resources res, Bitmap bitmap, BitmapWorkerTask bitmapWorkerTask) {
            super(res, bitmap);
            bitmapWorkerTaskReference = new WeakReference<>(bitmapWorkerTask);
        }

        BitmapWorkerTask getBitmapWorkerTask() {
            return bitmapWorkerTaskReference.get();
        }
    }

    static class MediaViewHolder extends RecyclerView.ViewHolder {

        private final ItemMediaBinding binding;

        MediaViewHolder(ItemMediaBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static class BitmapWorkerTask extends AsyncTask<Attachment, Void, Bitmap> {
        private final WeakReference<ImageView> imageViewReference;
        private Attachment attachment = null;
        private final int mediaSize;
        @Nullable private final String accountUuid;

        BitmapWorkerTask(int mediaSize, ImageView imageView, @Nullable String accountUuid) {
            this.mediaSize = mediaSize;
            this.accountUuid = accountUuid;
            imageViewReference = new WeakReference<>(imageView);
        }

        @Override
        protected Bitmap doInBackground(final Attachment... params) {
            this.attachment = params[0];
            final XmppActivity activity = XmppActivity.find(imageViewReference);
            if (activity == null) {
                return null;
            }
            if (this.attachment.isSecureMedia()) {
                if (accountUuid == null) {
                    return null;
                }
                try {
                    final Conversations application =
                            (Conversations) activity.getApplication();
                    final Bitmap bitmap =
                            new AndroidSecureMessageMediaThumbnailReader(
                                            activity,
                                            application.getSecureContentStoreProvider().get())
                                    .load(
                                            accountUuid,
                                            this.attachment.getUuid().toString(),
                                            mediaSize,
                                            true);
                    if (bitmap != null) {
                        final String key =
                                "attachment_"
                                        + this.attachment.getUuid().toString()
                                        + "_"
                                        + mediaSize;
                        activity.xmppConnectionService.getBitmapCache().put(key, bitmap);
                    }
                    return bitmap;
                } catch (final Exception ignored) {
                    return null;
                }
            }
            return activity.xmppConnectionService
                    .getFileBackend()
                    .getPreviewForUri(this.attachment, mediaSize, false);
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (bitmap != null && !isCancelled()) {
                final ImageView imageView = imageViewReference.get();
                if (imageView != null) {
                    imageView.setImageBitmap(bitmap);
                    imageView.setBackgroundColor(0x00000000);
                }
            }
        }
    }
}
