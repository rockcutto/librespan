package eu.siacs.conversations.ui.adapter;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ItemMediaPreviewBinding;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.ui.ConversationFragment;
import eu.siacs.conversations.ui.ShowLocationActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.Attachment;

public class MediaPreviewAdapter
        extends RecyclerView.Adapter<MediaPreviewAdapter.MediaPreviewViewHolder> {

    private final ArrayList<Attachment> mediaPreviews = new ArrayList<>();

    private final ConversationFragment conversationFragment;

    public MediaPreviewAdapter(ConversationFragment fragment) {
        this.conversationFragment = fragment;
    }

    @NonNull
    @Override
    public MediaPreviewViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final LayoutInflater layoutInflater = LayoutInflater.from(parent.getContext());
        ItemMediaPreviewBinding binding =
                DataBindingUtil.inflate(layoutInflater, R.layout.item_media_preview, parent, false);
        return new MediaPreviewViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull MediaPreviewViewHolder holder, int position) {
        final Context context = conversationFragment.getActivity();
        final Attachment attachment = mediaPreviews.get(position);
        if (attachment.renderThumbnail()) {
            holder.binding.mediaPreview.setImageAlpha(255);
            loadPreview(attachment, holder.binding.mediaPreview);
        } else {
            cancelPotentialWork(attachment, holder.binding.mediaPreview);
            MediaAdapter.renderPreview(attachment, holder.binding.mediaPreview);
        }
        holder.binding.deleteButton.setOnClickListener(
                v -> {
                    final int pos = mediaPreviews.indexOf(attachment);
                    mediaPreviews.remove(pos);
                    conversationFragment.onMediaPreviewRemoved(attachment);
                    notifyItemRemoved(pos);
                    conversationFragment.toggleInputMethod();
                });
        holder.binding.mediaPreview.setOnClickListener(
                v -> {
                    if (attachment.getType() == Attachment.Type.IMAGE) {
                        conversationFragment.editImage(attachment.getUri());
                        return;
                    }
                    final String mime = attachment.getMime();
                    if (mime != null && mime.startsWith("video/")) {
                        conversationFragment.editVideo(attachment.getUri());
                        return;
                    }
                    view(context, attachment);
                });
        bindPreparationError(holder, attachment);
    }

    private void bindPreparationError(
            final MediaPreviewViewHolder holder, final Attachment attachment) {
        final int errorResId = attachment.getPreparationErrorResId();
        if (errorResId == 0) {
            holder.binding.mediaError.setVisibility(android.view.View.GONE);
            holder.binding.mediaError.setOnClickListener(null);
            holder.binding.mediaPreviewCard.setStrokeWidth(0);
            holder.binding.mediaPreview.setImageAlpha(255);
            return;
        }

        final Context context = holder.binding.getRoot().getContext();
        holder.binding.mediaError.setText(compactPreparationError(errorResId));
        holder.binding.mediaError.setContentDescription(context.getText(errorResId));
        holder.binding.mediaError.setVisibility(android.view.View.VISIBLE);
        holder.binding.mediaError.setOnClickListener(
                v ->
                        new MaterialAlertDialogBuilder(context)
                                .setMessage(errorResId)
                                .setPositiveButton(android.R.string.ok, null)
                                .show());
        holder.binding.mediaPreviewCard.setStrokeWidth(
                Math.max(1, Math.round(context.getResources().getDisplayMetrics().density)));
        holder.binding.mediaPreviewCard.setStrokeColor(
                MaterialColors.getColor(
                        holder.binding.mediaPreviewCard,
                        com.google.android.material.R.attr.colorError));
        holder.binding.mediaPreview.setImageAlpha(190);
    }

    private int compactPreparationError(final int errorResId) {
        if (errorResId == R.string.error_security_exception
                || errorResId == R.string.error_security_exception_during_image_copy) {
            return R.string.attachment_error_no_access;
        }
        if (errorResId == R.string.error_file_not_found) {
            return R.string.attachment_error_not_found;
        }
        if (errorResId == R.string.error_not_an_image_file
                || errorResId == R.string.error_compressing_image
                || errorResId == R.string.error_out_of_memory) {
            return R.string.attachment_error_processing;
        }
        return R.string.attachment_error_prepare;
    }

    public void markPreparationError(final Attachment attachment, final int errorResId) {
        if (attachment == null || errorResId == 0) {
            return;
        }
        final int position = mediaPreviews.indexOf(attachment);
        if (position < 0) {
            return;
        }
        attachment.setPreparationErrorResId(errorResId);
        notifyItemChanged(position);
    }

    public void clearPreparationErrors() {
        boolean changed = false;
        for (final Attachment attachment : mediaPreviews) {
            if (attachment.getPreparationErrorResId() != 0) {
                attachment.clearPreparationError();
                changed = true;
            }
        }
        if (changed) {
            notifyDataSetChanged();
        }
    }

    private static void view(final Context context, final Attachment attachment) {
        final Intent view = new Intent(Intent.ACTION_VIEW);
        if (attachment.getType() == Attachment.Type.LOCATION) {
            view.setClass(context, ShowLocationActivity.class);
            view.setData(attachment.getUri());
        } else {
            final Uri uri = FileBackend.getUriForUri(context, attachment.getUri());
            view.setDataAndType(uri, attachment.getMime());
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        try {
            context.startActivity(view);
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(context, R.string.no_application_found_to_open_file, Toast.LENGTH_SHORT)
                    .show();
        } catch (final SecurityException e) {
            Toast.makeText(
                            context,
                            R.string.sharing_application_not_grant_permission,
                            Toast.LENGTH_SHORT)
                    .show();
        }
    }

    public boolean containsUri(final Uri uri) {
        for (final Attachment attachment : mediaPreviews) {
            if (attachment.getUri().equals(uri)) {
                return true;
            }
        }
        return false;
    }

    public void replaceOrAddMediaPreview(Uri originalUri, Uri editedUri, Attachment.Type type) {
        boolean replaced = false;
        for(int i = 0; i < mediaPreviews.size(); i++) {
            Attachment current = mediaPreviews.get(i);
            if (current.getUri().equals(originalUri)) {
                replaced = true;
                mediaPreviews.set(i, Attachment.of(conversationFragment.getActivity(), editedUri, current.getType()).get(0));
            }
        }

        if (!replaced) {
            mediaPreviews.addAll(Attachment.of(conversationFragment.getActivity(), editedUri, type));
        }

        notifyDataSetChanged();
    }

    public void addMediaPreviews(List<Attachment> attachments) {
        this.mediaPreviews.addAll(attachments);
        notifyDataSetChanged();
    }

    private void loadPreview(Attachment attachment, ImageView imageView) {
        if (cancelPotentialWork(attachment, imageView)) {
            XmppActivity activity = (XmppActivity) conversationFragment.getActivity();
            final Bitmap bm =
                    activity.xmppConnectionService
                            .getFileBackend()
                            .getPreviewForUri(
                                    attachment,
                                    Math.round(
                                            activity.getResources()
                                                    .getDimension(R.dimen.media_preview_size)),
                                    true);
            if (bm != null) {
                cancelPotentialWork(attachment, imageView);
                imageView.setImageBitmap(bm);
                imageView.setBackgroundColor(0x00000000);
            } else {
                imageView.setBackgroundColor(
                        ContextCompat.getColor(imageView.getContext(), R.color.grey800));
                imageView.setImageDrawable(null);
                final BitmapWorkerTask task = new BitmapWorkerTask(imageView);
                final AsyncDrawable asyncDrawable =
                        new AsyncDrawable(
                                conversationFragment.getActivity().getResources(), null, task);
                imageView.setImageDrawable(asyncDrawable);
                try {
                    task.execute(attachment);
                } catch (final RejectedExecutionException ignored) {
                }
            }
        }
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

    @Override
    public int getItemCount() {
        return mediaPreviews.size();
    }

    public boolean hasAttachments() {
        return !mediaPreviews.isEmpty();
    }

    public ArrayList<Attachment> getAttachments() {
        return mediaPreviews;
    }

    public void clearPreviews() {
        this.mediaPreviews.clear();
    }

    static class MediaPreviewViewHolder extends RecyclerView.ViewHolder {

        private final ItemMediaPreviewBinding binding;

        MediaPreviewViewHolder(ItemMediaPreviewBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
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

    private static class BitmapWorkerTask extends AsyncTask<Attachment, Void, Bitmap> {
        private final WeakReference<ImageView> imageViewReference;
        private Attachment attachment = null;

        BitmapWorkerTask(ImageView imageView) {
            imageViewReference = new WeakReference<>(imageView);
        }

        @Override
        protected Bitmap doInBackground(Attachment... params) {
            this.attachment = params[0];
            final XmppActivity activity = XmppActivity.find(imageViewReference);
            if (activity == null) {
                return null;
            }
            return activity.xmppConnectionService
                    .getFileBackend()
                    .getPreviewForUri(
                            this.attachment,
                            Math.round(
                                    activity.getResources()
                                            .getDimension(R.dimen.media_preview_size)),
                            false);
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
