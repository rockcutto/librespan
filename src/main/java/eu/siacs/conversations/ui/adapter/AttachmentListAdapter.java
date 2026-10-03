package eu.siacs.conversations.ui.adapter;

import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import eu.siacs.conversations.R;
import eu.siacs.conversations.storage.secure.SecureMessageMediaUiBridge;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.attachments.AttachmentAudioPlayer;
import eu.siacs.conversations.ui.attachments.AttachmentEntry;
import eu.siacs.conversations.ui.attachments.AttachmentWaveformSeekBar;
import eu.siacs.conversations.ui.util.ViewUtil;
import eu.siacs.conversations.utils.MimeUtils;

public final class AttachmentListAdapter
        extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnAttachmentActionListener {
        void onAttachmentActions(AttachmentEntry entry);
    }

    private static final int VIEW_FILE = 0;
    private static final int VIEW_AUDIO = 1;

    private final XmppActivity activity;
    private final AttachmentAudioPlayer audioPlayer;
    private OnAttachmentActionListener onAttachmentActionListener;
    private final ArrayList<AttachmentEntry> entries = new ArrayList<>();

    public AttachmentListAdapter(final XmppActivity activity) {
        this.activity = activity;
        this.audioPlayer = new AttachmentAudioPlayer(activity);
    }

    public void setOnAttachmentActionListener(
            final OnAttachmentActionListener onAttachmentActionListener) {
        this.onAttachmentActionListener = onAttachmentActionListener;
    }

    public void stopAudio() {
        audioPlayer.stop();
    }

    public void release() {
        audioPlayer.release();
    }

    public void setEntries(final List<AttachmentEntry> items) {
        entries.clear();
        entries.addAll(items);
        notifyDataSetChanged();
    }

    public void appendEntries(final List<AttachmentEntry> items) {
        if (items.isEmpty()) {
            return;
        }
        final int start = entries.size();
        entries.addAll(items);
        notifyItemRangeInserted(start, items.size());
    }

    public int size() {
        return entries.size();
    }
    public void removeByMessageUuid(final String messageUuid) {
        for (int index = entries.size() - 1; index >= 0; index--) {
            if (messageUuid.equals(entries.get(index).messageUuid)) {
                entries.remove(index);
                notifyItemRemoved(index);
                return;
            }
        }
    }


    @Override
    public int getItemViewType(final int position) {
        return entries.get(position).category == AttachmentEntry.Category.AUDIO
                ? VIEW_AUDIO
                : VIEW_FILE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(
            @NonNull final ViewGroup parent, final int viewType) {
        final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_AUDIO) {
            final View view = inflater.inflate(R.layout.item_attachment_audio, parent, false);
            return new AudioViewHolder(view);
        }
        final View view = inflater.inflate(R.layout.item_attachment_row, parent, false);
        return new FileViewHolder(view);
    }
    @Override
    public void onBindViewHolder(
            @NonNull final RecyclerView.ViewHolder holder, final int position) {
        final AttachmentEntry entry = entries.get(position);
        if (holder instanceof AudioViewHolder) {
            final AudioViewHolder audio = (AudioViewHolder) holder;
            audio.subtitle.setText("· " + subtitle(entry));
            audioPlayer.bind(
                    entry,
                    audio.itemView,
                    audio.playPause,
                    audio.progress,
                    audio.runtime);
            audio.actions.setOnClickListener(
                    ignored -> {
                        if (onAttachmentActionListener != null) {
                            onAttachmentActionListener.onAttachmentActions(entry);
                        }
                    });
            return;
        }
        final FileViewHolder file = (FileViewHolder) holder;
        file.icon.setImageResource(MediaAdapter.getImageDrawable(entry.mimeType));
        file.title.setText(displayName(entry));
        file.subtitle.setText(subtitle(entry));
        file.itemView.setOnClickListener(ignored -> open(entry));
        file.actions.setOnClickListener(
                ignored -> {
                    if (onAttachmentActionListener != null) {
                        onAttachmentActionListener.onAttachmentActions(entry);
                    }
                });
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    private String displayName(final AttachmentEntry entry) {
        if (entry.fileName != null && !entry.fileName.trim().isEmpty()) {
            return entry.fileName;
        }
        if (entry.category == AttachmentEntry.Category.AUDIO) {
            return activity.getString(R.string.audio);
        }
        final String extension = MimeUtils.guessExtensionFromMimeType(entry.mimeType);
        if (extension != null && !extension.trim().isEmpty()) {
            return activity.getString(R.string.file) + "." + extension;
        }
        return activity.getString(R.string.file);
    }

    private String subtitle(final AttachmentEntry entry) {
        final ArrayList<String> parts = new ArrayList<>(3);
        if (entry.sizeBytes > 0) {
            parts.add(Formatter.formatFileSize(activity, entry.sizeBytes));
        }
        parts.add(
                DateUtils.formatDateTime(
                        activity,
                        entry.timeSent,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH));
        return android.text.TextUtils.join(" · ", parts);
    }

    private void open(final AttachmentEntry entry) {
        if (entry.isSecure()) {
            SecureMessageMediaUiBridge.openOrFallback(
                    activity,
                    entry.accountUuid,
                    entry.messageUuid,
                    entry.mimeType,
                    () ->
                            Toast.makeText(
                                            activity,
                                            R.string.file_deleted,
                                            Toast.LENGTH_SHORT)
                                    .show());
            return;
        }
        if (entry.legacyPath == null) {
            Toast.makeText(activity, R.string.file_deleted, Toast.LENGTH_SHORT).show();
            return;
        }
        final File file =
                activity.xmppConnectionService.getFileBackend().getFileForPath(entry.legacyPath);
        ViewUtil.view(
                activity,
                new eu.siacs.conversations.entities.DownloadableFile(file.getAbsolutePath()));
    }

    static final class FileViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        final MaterialButton actions;

        FileViewHolder(@NonNull final View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.icon);
            title = itemView.findViewById(R.id.title);
            subtitle = itemView.findViewById(R.id.subtitle);
            actions = itemView.findViewById(R.id.actions);
        }
    }

    static final class AudioViewHolder extends RecyclerView.ViewHolder {
        final MaterialButton playPause;
        final AttachmentWaveformSeekBar progress;
        final TextView runtime;
        final TextView subtitle;
        final MaterialButton actions;

        AudioViewHolder(@NonNull final View itemView) {
            super(itemView);
            playPause = itemView.findViewById(R.id.audio_play_pause);
            progress = itemView.findViewById(R.id.audio_progress);
            runtime = itemView.findViewById(R.id.audio_runtime);
            subtitle = itemView.findViewById(R.id.subtitle);
            actions = itemView.findViewById(R.id.actions);
        }
    }
}
