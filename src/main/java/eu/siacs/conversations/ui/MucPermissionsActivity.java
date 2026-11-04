package eu.siacs.conversations.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.services.MucPasswordController;
import eu.siacs.conversations.services.MucRoomConfiguration;
import eu.siacs.conversations.services.MucRoomConfigurationController;
import eu.siacs.conversations.ui.util.MucConfiguration;
import java.util.Arrays;

/** Full-screen owner settings. Switches are drafts until the server confirms the owner form. */
public final class MucPermissionsActivity extends XmppActivity {
    public static final String EXTRA_ROOM_UUID = "uuid";
    private static final String STATE_NAMES = "muc_permission_names";
    private static final String STATE_BASE = "muc_permission_base";
    private static final String STATE_VALUES = "muc_permission_values";

    private LinearLayout rows;
    private ProgressBar progress;
    private TextView status;
    private MaterialButton action;
    private Conversation room;
    private MucRoomConfigurationController controller;
    private MucRoomConfiguration original;
    private MucConfiguration configuration;
    private boolean[] selected;
    private String[] pendingNames;
    private boolean[] pendingBase;
    private boolean[] pendingValues;
    private boolean busy;
    private boolean saving;
    private boolean needsReload;
    private int generation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_muc_permissions);
        this.mTheme = findTheme();
        setTheme(this.mTheme);
        setSupportActionBar(findViewById(R.id.toolbar));
        configureActionBar(getSupportActionBar());
        setTitle(R.string.muc_admin_permissions);
        rows = findViewById(R.id.muc_permission_rows);
        progress = findViewById(R.id.muc_permission_progress);
        status = findViewById(R.id.muc_permission_status);
        action = findViewById(R.id.muc_permission_action);
        action.setOnClickListener(v -> {
            if (needsReload || configuration == null) load();
            else save();
        });
        if (savedInstanceState != null) {
            pendingNames = savedInstanceState.getStringArray(STATE_NAMES);
            pendingBase = savedInstanceState.getBooleanArray(STATE_BASE);
            pendingValues = savedInstanceState.getBooleanArray(STATE_VALUES);
        }
        setLoading(true);
    }

    @Override
    protected void refreshUiReal() {
        // Owner-form callbacks drive this screen; it has no conversation timeline to refresh.
    }

    @Override
    protected void onBackendConnected() {
        final String uuid = getIntent().getStringExtra(EXTRA_ROOM_UUID);
        room = uuid == null ? null : xmppConnectionService.findConversationByUuid(uuid);
        if (room == null) {
            Toast.makeText(this, R.string.muc_config_rejected, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        load();
    }

    private void load() {
        if (xmppConnectionService == null || room == null) return;
        if (controller != null) controller.cancel();
        controller = null;
        original = null;
        configuration = null;
        selected = null;
        needsReload = false;
        saving = false;
        rows.removeAllViews();
        setLoading(true);
        if (!MucPasswordController.canManage(room) || !room.getMucOptions().online()) {
            showFailure(R.string.muc_config_rejected);
            return;
        }
        final int request = ++generation;
        final MucRoomConfigurationController current =
                new MucRoomConfigurationController(xmppConnectionService, room);
        controller = current;
        current.query(result -> runOnUiThread(() -> {
            if (!valid(request, current)) return;
            if (result.status != MucRoomConfigurationController.Status.READY
                    || result.configuration == null) {
                showFailure(statusMessage(result.status));
                return;
            }
            original = result.configuration;
            final MucOptions options = room.getMucOptions();
            final boolean advanced = options.getSelf().getAffiliation()
                    .ranks(MucOptions.Affiliation.ADMIN)
                    || options.getSelf().getRole().ranks(MucOptions.Role.MODERATOR);
            configuration = MucConfiguration.get(this, advanced, options, original);
            if (configuration.names.length == 0) {
                showFailure(R.string.muc_config_unsupported);
                return;
            }
            selected = configuration.values.clone();
            if (Arrays.equals(pendingNames, configuration.names)
                    && Arrays.equals(pendingBase, configuration.values)
                    && pendingValues != null && pendingValues.length == selected.length) {
                selected = pendingValues.clone();
            }
            pendingNames = null;
            pendingBase = null;
            pendingValues = null;
            renderRows();
            setLoading(false);
        }));
    }

    private boolean valid(int request, MucRoomConfigurationController current) {
        return request == generation && current == controller && !isFinishing() && !isDestroyed();
    }

    private void renderRows() {
        rows.removeAllViews();
        for (int i = 0; i < configuration.names.length; i++) {
            final int index = i;
            final View row = LayoutInflater.from(this)
                    .inflate(R.layout.item_muc_permission_setting, rows, false);
            final TextView title = row.findViewById(R.id.muc_permission_title);
            final TextView summary = row.findViewById(R.id.muc_permission_summary);
            final SwitchCompat toggle = row.findViewById(R.id.muc_permission_switch);
            title.setText(configuration.names[i]);
            final boolean memberLocked = configuration.isMembersOption(i)
                    && !original.canChangeMembers(room.getMucOptions());
            final String explanation = getString(configuration.descriptionForOption(i));
            summary.setText(memberLocked
                    ? explanation + "\n" + getString(R.string.muc_permissions_members_locked_summary)
                    : explanation);
            toggle.setChecked(selected[i]);
            toggle.setContentDescription(configuration.names[i]);
            row.setEnabled(!memberLocked);
            toggle.setEnabled(!memberLocked);
            title.setEnabled(!memberLocked);
            summary.setEnabled(!memberLocked);
            toggle.setOnCheckedChangeListener((button, checked) -> {
                selected[index] = checked;
                updateAction();
            });
            row.setOnClickListener(v -> {
                if (!busy && toggle.isEnabled()) toggle.toggle();
            });
            rows.addView(row);
        }
        updateAction();
    }

    private void updateAction() {
        action.setText(needsReload || configuration == null
                ? R.string.muc_permissions_reload : R.string.save);
        action.setEnabled(!busy && (needsReload || configuration == null
                || !configuration.toBundle(selected).isEmpty()));
        for (int i = 0; i < rows.getChildCount(); i++) {
            final View row = rows.getChildAt(i);
            final SwitchCompat toggle = row.findViewById(R.id.muc_permission_switch);
            if (toggle == null) continue;
            final boolean locked = configuration != null && configuration.isMembersOption(i)
                    && !original.canChangeMembers(room.getMucOptions());
            toggle.setEnabled(!busy && !needsReload && !locked);
            row.setEnabled(!busy && !needsReload && !locked);
        }
    }

    private void save() {
        if (busy || controller == null || original == null || selected == null) return;
        final Bundle changes = configuration.toBundle(selected);
        if (changes.isEmpty()) return;
        if ("1".equals(changes.getString(MucRoomConfiguration.MEMBERS))) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.muc_members_only_label)
                    .setMessage(R.string.muc_members_only_warning)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.confirm, (dialog, which) -> submit(changes))
                    .show();
        } else {
            submit(changes);
        }
    }

    private void submit(Bundle changes) {
        if (busy || controller == null || !MucPasswordController.canManage(room)) return;
        final int request = generation;
        final MucRoomConfigurationController current = controller;
        saving = true;
        setLoading(true);
        current.save(original, changes, result -> runOnUiThread(() -> {
            if (!valid(request, current)) return;
            if (result.status == MucRoomConfigurationController.Status.SAVED) {
                Toast.makeText(this, R.string.muc_config_saved, Toast.LENGTH_SHORT).show();
                finish();
            } else {
                showFailure(statusMessage(result.status));
            }
        }));
    }

    private void setLoading(boolean value) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        status.setVisibility(value ? View.VISIBLE : View.GONE);
        if (value) status.setText(saving ? R.string.muc_permissions_saving : R.string.muc_config_loading);
        updateAction();
    }

    private void showFailure(int message) {
        busy = false;
        saving = false;
        needsReload = true;
        progress.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(message);
        updateAction();
    }

    private static int statusMessage(MucRoomConfigurationController.Status code) {
        return code == MucRoomConfigurationController.Status.CONFLICT
                ? R.string.muc_config_conflict
                : code == MucRoomConfigurationController.Status.UNSUPPORTED
                        ? R.string.muc_config_unsupported
                : code == MucRoomConfigurationController.Status.REJECTED
                        ? R.string.muc_config_rejected
                : code == MucRoomConfigurationController.Status.TIMEOUT
                        ? R.string.muc_config_timeout
                : R.string.muc_config_unconfirmed;
    }

    private boolean hasChanges() {
        return configuration != null && selected != null
                && !configuration.toBundle(selected).isEmpty();
    }

    @Override
    public void onBackPressed() {
        if (saving) {
            Toast.makeText(this, R.string.muc_permissions_saving, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!busy && hasChanges()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.muc_permissions_discard_title)
                    .setMessage(R.string.muc_permissions_discard_summary)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.muc_permissions_discard, (dialog, which) -> finish())
                    .show();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (configuration != null && selected != null) {
            outState.putStringArray(STATE_NAMES, configuration.names);
            outState.putBooleanArray(STATE_BASE, configuration.values);
            outState.putBooleanArray(STATE_VALUES, selected);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onStop() {
        if (configuration != null && selected != null) {
            pendingNames = configuration.names.clone();
            pendingBase = configuration.values.clone();
            pendingValues = selected.clone();
        }
        ++generation;
        if (controller != null) controller.cancel();
        controller = null;
        super.onStop();
    }
}
