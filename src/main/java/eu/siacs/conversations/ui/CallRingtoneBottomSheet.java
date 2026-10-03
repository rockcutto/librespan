package eu.siacs.conversations.ui;

import android.app.Activity;
import android.database.Cursor;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.radiobutton.MaterialRadioButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import eu.siacs.conversations.R;

/** In-app Material bottom sheet for call ringtone selection and short previews. */
public final class CallRingtoneBottomSheet {

    private static final long PREVIEW_MS = 4_000L;

    public interface Listener {
        void onRingtoneSelected(@Nullable Uri ringtone);

        void onChooseAudioFile();
    }

    private final Activity activity;
    private final Listener listener;
    private final BottomSheetDialog dialog;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();

    private Uri selectedUri;
    private Ringtone preview;

    private CallRingtoneBottomSheet(
            final Activity activity, @Nullable final Uri selectedUri, final Listener listener) {
        this.activity = activity;
        this.selectedUri = selectedUri;
        this.listener = listener;
        this.dialog = new BottomSheetDialog(activity);
    }

    public static void show(
            final Activity activity,
            @Nullable final Uri selectedUri,
            final Listener listener) {
        new CallRingtoneBottomSheet(activity, selectedUri, listener).show();
    }

    private void show() {
        final View content =
                LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_call_ringtone, null);
        final RecyclerView list = content.findViewById(R.id.call_ringtone_list);

        loadItems();
        final Adapter adapter = new Adapter();
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(adapter);

        dialog.setContentView(content);
        dialog.setOnDismissListener(ignored -> stopPreview());
        dialog.setOnShowListener(
                ignored -> {
                    final FrameLayout sheet =
                            dialog.findViewById(
                                    com.google.android.material.R.id.design_bottom_sheet);
                    if (sheet == null) {
                        return;
                    }
                    final int maxHeight =
                            Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.80f);
                    final ViewGroup.LayoutParams params = sheet.getLayoutParams();
                    params.height = maxHeight;
                    sheet.setLayoutParams(params);
                    final BottomSheetBehavior<FrameLayout> behavior =
                            BottomSheetBehavior.from(sheet);
                    behavior.setSkipCollapsed(true);
                    behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                });
        dialog.show();

        final int selectedPosition = adapter.selectedPosition();
        if (selectedPosition >= 0) {
            list.post(() -> list.scrollToPosition(selectedPosition));
        }
    }

    private void loadItems() {
        final List<Item> deviceRingtones = new ArrayList<>();
        boolean selectedIsDeviceRingtone = false;

        final RingtoneManager manager = new RingtoneManager(activity);
        manager.setType(RingtoneManager.TYPE_RINGTONE);
        Cursor cursor = null;
        try {
            cursor = manager.getCursor();
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    final int position = cursor.getPosition();
                    final Uri uri = manager.getRingtoneUri(position);
                    if (uri == null) {
                        continue;
                    }
                    String title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX);
                    if (title == null || title.trim().isEmpty()) {
                        title = resolveTitle(uri);
                    }
                    deviceRingtones.add(Item.option(title, uri));
                    if (sameUri(selectedUri, uri)) {
                        selectedIsDeviceRingtone = true;
                    }
                }
            }
        } catch (final RuntimeException ignored) {
            deviceRingtones.clear();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        items.add(Item.option(activity.getString(R.string.neocont_ringtone_default),
                Settings.System.DEFAULT_RINGTONE_URI));
        items.add(Item.option(activity.getString(R.string.neocont_ringtone_silent), null));

        if (selectedUri != null
                && !Settings.System.DEFAULT_RINGTONE_URI.equals(selectedUri)
                && !selectedIsDeviceRingtone) {
            items.add(Item.option(resolveTitle(selectedUri), selectedUri));
        }

        items.add(Item.header(activity.getString(R.string.neocont_device_ringtones)));
        items.addAll(deviceRingtones);
        items.add(Item.action(activity.getString(R.string.neocont_choose_audio_file)));
    }

    private String resolveTitle(final Uri uri) {
        try {
            final Ringtone ringtone = RingtoneManager.getRingtone(activity, uri);
            if (ringtone != null) {
                final String title = ringtone.getTitle(activity);
                if (title != null && !title.trim().isEmpty()) {
                    return title;
                }
            }
        } catch (final RuntimeException ignored) {
            // Fall through to the document display name.
        }

        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor =
                    activity.getContentResolver()
                            .query(
                                    uri,
                                    new String[] {OpenableColumns.DISPLAY_NAME},
                                    null,
                                    null,
                                    null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    final int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        final String title = cursor.getString(index);
                        if (title != null && !title.trim().isEmpty()) {
                            return title;
                        }
                    }
                }
            } catch (final RuntimeException ignored) {
                // Use a stable fallback below.
            }
        }
        return activity.getString(R.string.neocont_ringtone_unknown);
    }

    private void select(@Nullable final Uri uri) {
        if (sameUri(selectedUri, uri)) {
            preview(uri);
            return;
        }
        selectedUri = uri;
        listener.onRingtoneSelected(uri);
        preview(uri);
    }

    private void preview(@Nullable final Uri uri) {
        stopPreview();
        if (uri == null) {
            return;
        }
        try {
            preview = RingtoneManager.getRingtone(activity, uri);
            if (preview == null) {
                return;
            }
            preview.play();
            mainHandler.postDelayed(this::stopPreview, PREVIEW_MS);
        } catch (final RuntimeException ignored) {
            stopPreview();
        }
    }

    private void stopPreview() {
        mainHandler.removeCallbacksAndMessages(null);
        final Ringtone current = preview;
        preview = null;
        if (current != null) {
            try {
                current.stop();
            } catch (final RuntimeException ignored) {
                // Nothing else to clean up.
            }
        }
    }

    private static boolean sameUri(@Nullable final Uri left, @Nullable final Uri right) {
        return Objects.equals(left, right);
    }

    private static final class Item {
        private static final int OPTION = 0;
        private static final int HEADER = 1;
        private static final int ACTION = 2;

        final int kind;
        final String title;
        final Uri uri;

        private Item(final int kind, final String title, @Nullable final Uri uri) {
            this.kind = kind;
            this.title = title;
            this.uri = uri;
        }

        static Item option(final String title, @Nullable final Uri uri) {
            return new Item(OPTION, title, uri);
        }

        static Item header(final String title) {
            return new Item(HEADER, title, null);
        }

        static Item action(final String title) {
            return new Item(ACTION, title, null);
        }
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(final int position) {
            return items.get(position).kind;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(
                @NonNull final ViewGroup parent, final int viewType) {
            final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == Item.HEADER) {
                return new HeaderHolder(
                        inflater.inflate(R.layout.item_call_ringtone_header, parent, false));
            } else if (viewType == Item.ACTION) {
                return new ActionHolder(
                        inflater.inflate(R.layout.item_call_ringtone_action, parent, false));
            }
            return new OptionHolder(
                    inflater.inflate(R.layout.item_call_ringtone_option, parent, false));
        }

        @Override
        public void onBindViewHolder(
                @NonNull final RecyclerView.ViewHolder holder, final int position) {
            final Item item = items.get(position);
            if (holder instanceof OptionHolder) {
                final OptionHolder option = (OptionHolder) holder;
                option.title.setText(item.title);
                option.radio.setChecked(sameUri(selectedUri, item.uri));
                option.itemView.setOnClickListener(
                        view -> {
                            select(item.uri);
                            notifyDataSetChanged();
                        });
            } else if (holder instanceof HeaderHolder) {
                ((HeaderHolder) holder).title.setText(item.title);
            } else if (holder instanceof ActionHolder) {
                final ActionHolder action = (ActionHolder) holder;
                action.title.setText(item.title);
                action.itemView.setOnClickListener(
                        view -> {
                            stopPreview();
                            dialog.dismiss();
                            listener.onChooseAudioFile();
                        });
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        int selectedPosition() {
            for (int i = 0; i < items.size(); ++i) {
                final Item item = items.get(i);
                if (item.kind == Item.OPTION && sameUri(selectedUri, item.uri)) {
                    return i;
                }
            }
            return -1;
        }
    }

    private static final class OptionHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final MaterialRadioButton radio;

        OptionHolder(@NonNull final View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.call_ringtone_option_title);
            radio = itemView.findViewById(R.id.call_ringtone_option_radio);
            radio.setClickable(false);
        }
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;

        HeaderHolder(@NonNull final View itemView) {
            super(itemView);
            title = (TextView) itemView;
        }
    }

    private static final class ActionHolder extends RecyclerView.ViewHolder {
        final TextView title;

        ActionHolder(@NonNull final View itemView) {
            super(itemView);
            title = (TextView) itemView;
        }
    }
}
