package eu.siacs.conversations.ui;

import android.app.KeyguardManager;
import android.database.Cursor;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.os.LocaleListCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.security.KeyStoreException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.OmemoSetting;
import eu.siacs.conversations.crypto.axolotl.OmemoIdentityBackup;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.CallIntegration;
import eu.siacs.conversations.services.MemorizingTrustManager;
import eu.siacs.conversations.services.NotificationService;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.receiver.UnifiedPushDistributor;
import eu.siacs.conversations.security.applock.AppLockController;
import eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionRuntimeV1;
import eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionSnapshotV1;
import eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionStateV1;
import eu.siacs.conversations.ui.activity.result.PickRingtone;
import eu.siacs.conversations.ui.appearance.AppearanceApplier;
import eu.siacs.conversations.ui.navigation.ProfileNavigation;
import eu.siacs.conversations.ui.appearance.AppearanceChoicePreference;
import eu.siacs.conversations.ui.appearance.AppearanceController;
import eu.siacs.conversations.ui.appearance.AppearancePreviewPreference;
import eu.siacs.conversations.ui.appearance.MessageTextSizePreference;
import eu.siacs.conversations.ui.appearance.SecureStorageAccountPreference;
import eu.siacs.conversations.ui.appearance.AppearanceSnapshotReader;
import eu.siacs.conversations.ui.appearance.ChatWallpaperPresets;
import eu.siacs.conversations.ui.appearance.ThemeMode;
import eu.siacs.conversations.ui.util.SettingsUtils;
import eu.siacs.conversations.ui.util.StyledAttributes;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.ChatBackgroundHelper;
import eu.siacs.conversations.utils.GeoHelper;
import eu.siacs.conversations.utils.ThemeHelper;
import eu.siacs.conversations.utils.TimeFrameUtils;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.Jid;

public class SettingsActivity extends XmppActivity implements OnSharedPreferenceChangeListener, PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    public static final String KEEP_FOREGROUND_SERVICE = "enable_foreground_service";
    public static final String AWAY_WHEN_SCREEN_IS_OFF = "away_when_screen_off";
    public static final String TREAT_VIBRATE_AS_SILENT = "treat_vibrate_as_silent";
    public static final String DND_ON_SILENT_MODE = "dnd_on_silent_mode";
    public static final String MANUALLY_CHANGE_PRESENCE = "manually_change_presence";
    public static final String BLIND_TRUST_BEFORE_VERIFICATION = "btbv";
    public static final String AUTOMATIC_MESSAGE_DELETION = "automatic_message_deletion";
    public static final String BROADCAST_LAST_ACTIVITY = "last_activity";
    public static final String THEME = "theme";
    public static final String THEME_OVERRIDE_COLOR = "themeOverrideColor";
    public static final String SHOW_DYNAMIC_TAGS = "show_dynamic_tags";
    public static final String OMEMO_SETTING = "omemo";
    public static final String PREVENT_SCREENSHOTS = "prevent_screenshots";
    public static final String GROUP_BY_TAGS = "groupByTags";
    static final String PREFERENCE_DEVELOPER_MODE = "developer_mode_enabled";
    private static final int DEVELOPER_UNLOCK_TAPS = 10;
    private static final String DEVELOPER_TOTORO = " _//|\n/oo |\n\\mm_|";

    private static final int REQUEST_OMEMO_IDENTITY_EXPORT_AUTH = 0xbf8710;
    private static final int REQUEST_OMEMO_IDENTITY_IMPORT_AUTH = 0xbf8711;
    private static final int REQUEST_OMEMO_IDENTITY_EXPORT_DOCUMENT = 0xbf8712;
    private static final int REQUEST_OMEMO_IDENTITY_IMPORT_DOCUMENT = 0xbf8713;
    private static final int REQUEST_APP_LOCK_POLICY_AUTH = 0xbf8715;
    private static final String STATE_PENDING_OMEMO_EXPORT_JID = "pending_omemo_export_jid";

    private SettingsFragment mSettingsFragment;
    private Jid pendingOmemoIdentityExportJid;
    // External device-credential and Storage Access Framework activities temporarily stop this
    // activity. XmppActivity unbinds from XmppConnectionService in onStop(), while the activity
    // result can arrive before the asynchronous re-bind has completed. Keep backup actions
    // pending and resume them from onBackendConnected() instead of reporting a false
    // "no XMPP account" error.
    private boolean pendingOmemoIdentityExportAfterAuth;
    private Uri pendingOmemoIdentityExportUri;
    private Uri pendingOmemoIdentityRestoreUri;
    private OmemoIdentityBackup.Header pendingOmemoIdentityRestoreHeader;
    private boolean applyingAppearanceColor;
    private Boolean pendingAppLockEnabled;
    private int developerUnlockTapCount = 0;

    private final ActivityResultLauncher<Uri> notificationRingtonePicker =
            registerForActivityResult(
                    new PickRingtone(RingtoneManager.TYPE_NOTIFICATION),
                    uri -> persistRingtone(AppSettings.NOTIFICATION_RINGTONE, uri));
    private final ActivityResultLauncher<Intent> callRingtoneFilePicker =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != RESULT_OK
                                || result.getData() == null
                                || result.getData().getData() == null) {
                            return;
                        }
                        final Intent data = result.getData();
                        final Uri uri = data.getData();
                        final int takeFlags =
                                data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                        if (takeFlags != 0) {
                            try {
                                getContentResolver()
                                        .takePersistableUriPermission(uri, takeFlags);
                            } catch (final SecurityException e) {
                                Log.w(
                                        Config.LOGTAG,
                                        "unable to persist custom call ringtone permission",
                                        e);
                            }
                        }
                        applyCallRingtone(uri);
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        final FragmentManager fm = getSupportFragmentManager();
        final Fragment currentFragment = fm.findFragmentById(R.id.settings_content);
        if (currentFragment instanceof SettingsFragment) {
            mSettingsFragment = (SettingsFragment) currentFragment;
        } else {
            mSettingsFragment = new SettingsFragment();
            fm.beginTransaction().replace(R.id.settings_content, mSettingsFragment).commitNow();
        }
        mSettingsFragment.setActivityIntent(getIntent());
        this.mTheme = findTheme();

        setTheme(this.mTheme);
        setSupportActionBar(findViewById(R.id.toolbar));
        configureActionBar(getSupportActionBar());
        if (getIntent().getBooleanExtra(SettingsFragment.EXTRA_LEGACY, false)
                && Strings.isNullOrEmpty(getIntent().getStringExtra(SettingsFragment.EXTRA_PAGE))) {
            setTitle(R.string.neocont_settings_advanced);
        }
        if (savedInstanceState != null) {
            final String pendingJid = savedInstanceState.getString(STATE_PENDING_OMEMO_EXPORT_JID);
            if (pendingJid != null) {
                try {
                    pendingOmemoIdentityExportJid = Jid.of(pendingJid).asBareJid();
                } catch (final IllegalArgumentException ignored) {
                    pendingOmemoIdentityExportJid = null;
                }
            }
        }
    }

    @Override
    public boolean onPreferenceStartScreen(
            final PreferenceFragmentCompat caller, final PreferenceScreen preferenceScreen) {
        final String screenKey = preferenceScreen.getKey();
        if (Strings.isNullOrEmpty(screenKey)) {
            return false;
        }
        final Intent intent = new Intent(this, SettingsActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(SettingsFragment.EXTRA_PAGE, screenKey);
        if (caller instanceof SettingsFragment
                && ((SettingsFragment) caller).isLegacyMode()) {
            intent.putExtra(SettingsFragment.EXTRA_LEGACY, true);
        }
        startActivity(intent);
        return true;
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingOmemoIdentityExportJid != null) {
            outState.putString(
                    STATE_PENDING_OMEMO_EXPORT_JID,
                    pendingOmemoIdentityExportJid.asBareJid().toString());
        }
    }

    @Override
    protected void onBackendConnected() {
        final Preference accountPreference =
                mSettingsFragment.findPreference(UnifiedPushDistributor.PREFERENCE_ACCOUNT);
        reconfigureUpAccountPreference(accountPreference);
        configureNeoContAccountPreferences();
        configureLocalAccountDataCleanup();
        refreshLocalStorageUsage();
        continuePendingOmemoIdentityAction();
    }

    private void configureNeoContNavigation() {
        final Preference profile = mSettingsFragment.findPreference("neocont_manage_account");
        final Preference manageAccounts =
                mSettingsFragment.findPreference("neocont_manage_accounts");
        final Preference devices =
                mSettingsFragment.findPreference("neocont_security_devices");

        if (profile != null) {
            profile.setOnPreferenceClickListener(
                    preference -> {
                        openNeoContAccount();
                        return true;
                    });
        }
        if (manageAccounts != null) {
            manageAccounts.setOnPreferenceClickListener(
                    preference -> {
                        AccountUtils.launchManageAccounts(this);
                        return true;
                    });
        }
        if (devices != null) {
            devices.setOnPreferenceClickListener(
                    preference -> {
                        openNeoContDevices();
                        return true;
                    });
        }
    }

    private void configureDeveloperSettings() {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(this);
        final boolean unlocked =
                preferences.getBoolean(PREFERENCE_DEVELOPER_MODE, false);

        final Preference developer =
                mSettingsFragment.findPreference("neocont_advanced");
        if (developer != null) {
            developer.setVisible(unlocked);
        }

        final Preference version =
                mSettingsFragment.findPreference("neocont_about_version");
        if (version == null) {
            return;
        }
        version.setSummary(BuildConfig.VERSION_NAME);
        version.setOnPreferenceClickListener(
                preference -> {
                    if (preferences.getBoolean(PREFERENCE_DEVELOPER_MODE, false)) {
                        return true;
                    }
                    developerUnlockTapCount++;
                    if (developerUnlockTapCount < DEVELOPER_UNLOCK_TAPS) {
                        return true;
                    }
                    developerUnlockTapCount = 0;
                    preferences.edit().putBoolean(PREFERENCE_DEVELOPER_MODE, true).apply();
                    final Preference developerPreference =
                            mSettingsFragment.findPreference("neocont_advanced");
                    if (developerPreference != null) {
                        developerPreference.setVisible(true);
                    }
                    Toast.makeText(
                                    this,
                                    DEVELOPER_TOTORO
                                            + "\n\n"
                                            + getString(R.string.neocont_developer_unlocked),
                                    Toast.LENGTH_LONG)
                            .show();
                    return true;
                });
    }

    private void configureLicensePreferences() {
        configureLicensePreference(
                "license_librespan",
                R.string.neocont_license_gpl3,
                "licenses/librespan/GPL-3.0.txt");
        configureLicensePreference(
                "license_native_security",
                R.string.neocont_license_mpl2,
                "licenses/librespan/MPL-2.0.txt");
        configureLicensePreference(
                "license_onest",
                R.string.neocont_license_ofl11,
                "licenses/onest/OFL.txt");
        configureLicensePreference(
                "license_tabler",
                R.string.neocont_license_mit,
                "licenses/tabler/LICENSE");
    }

    private void configureLicensePreference(
            final String key, final int titleRes, final String assetPath) {
        final Preference preference = mSettingsFragment.findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnPreferenceClickListener(
                clicked -> {
                    showBundledLicense(titleRes, assetPath);
                    return true;
                });
    }

    private void showBundledLicense(final int titleRes, final String assetPath) {
        final String text;
        try {
            text = readAssetText(assetPath);
        } catch (final IOException e) {
            Log.w(Config.LOGTAG, "unable to read bundled license " + assetPath, e);
            displayToast(getString(R.string.neocont_license_read_error));
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String readAssetText(final String assetPath) throws IOException {
        try (InputStream input = getAssets().open(assetPath);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void configureAppLockPreference() {
        final TwoStatePreference preference =
                (TwoStatePreference) mSettingsFragment.findPreference(
                        AppLockController.PREFERENCE_ENABLED);
        if (preference == null) {
            return;
        }
        preference.setChecked(AppLockController.isEnabled(this));
        preference.setOnPreferenceChangeListener(
                (changedPreference, newValue) -> {
                    final boolean enabled = Boolean.TRUE.equals(newValue);
                    final KeyguardManager keyguardManager =
                            (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                    if (keyguardManager == null || !keyguardManager.isKeyguardSecure()) {
                        displayToast(getString(R.string.app_lock_requires_screen_lock));
                        return false;
                    }
                    final Intent intent =
                            keyguardManager.createConfirmDeviceCredentialIntent(
                                    getString(R.string.app_lock_settings_auth_title),
                                    getString(R.string.app_lock_settings_auth_text));
                    if (intent == null) {
                        displayToast(getString(R.string.app_lock_auth_unavailable));
                        return false;
                    }
                    pendingAppLockEnabled = enabled;
                    AppLockController.beginTrustedExternalAuthentication(this);
                    startActivityForResult(intent, REQUEST_APP_LOCK_POLICY_AUTH);
                    return false;
                });
    }

    private void configureHighSecurityPreference() {
        final Preference rawPreference =
                mSettingsFragment.findPreference("high_security_protection");
        if (!(rawPreference instanceof AppearanceChoicePreference)) {
            return;
        }
        final AppearanceChoicePreference preference =
                (AppearanceChoicePreference) rawPreference;

        preference.setEnabled(true);
        preference.setChoiceAvailable(true);

        final SecureContentCryptoSessionSnapshotV1 snapshot =
                SecureContentCryptoSessionRuntimeV1.snapshot(this);
        final SecureContentCryptoSessionStateV1 state = snapshot.getState();
        switch (state) {
            case INACTIVE:
                preference.setValueLabel(R.string.neocont_security_state_off);
                preference.setSummary(R.string.neocont_high_security_summary);
                break;
            case ACTIVATION_RECOVERY_REQUIRED:
                preference.setValueLabel(R.string.neocont_security_state_attention);
                preference.setSummary(R.string.high_security_summary_activation_recovery);
                break;
            case ACTIVE:
            case LOCKED:
                preference.setValueLabel(R.string.neocont_security_state_on);
                preference.setSummary(R.string.neocont_high_security_summary);
                break;
            case RECOVERY_REQUIRED:
                preference.setValueLabel(R.string.neocont_security_state_attention);
                preference.setSummary(R.string.neocont_high_security_recovery_required);
                break;
            case CORRUPT:
                preference.setValueLabel(R.string.neocont_security_state_attention);
                preference.setSummary(R.string.high_security_summary_corrupt);
                preference.setEnabled(false);
                preference.setChoiceAvailable(false);
                break;
            case CRYPTO_ERASED:
                preference.setValueLabel(R.string.neocont_security_state_attention);
                preference.setSummary(R.string.high_security_summary_erased);
                preference.setEnabled(false);
                preference.setChoiceAvailable(false);
                break;
        }

        if (preference.isEnabled()) {
            preference.setOnPreferenceClickListener(
                    clicked -> {
                        startActivity(new Intent(this, HighSecuritySetupActivity.class));
                        return true;
                    });
        } else {
            preference.setOnPreferenceClickListener(null);
        }
    }

    private void configureNeoContChatPreferences() {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(this);

        final TwoStatePreference photoEditor =
                (TwoStatePreference)
                        mSettingsFragment.findPreference("photo_editor_before_send");
        if (photoEditor != null) {
            photoEditor.setPersistent(false);
            final boolean skipEditor =
                    preferences.getBoolean(
                            "skip_image_editor_screen",
                            getResources().getBoolean(R.bool.skip_image_editor_screen));
            photoEditor.setChecked(!skipEditor);
            photoEditor.setOnPreferenceChangeListener(
                    (preference, value) -> {
                        final boolean enabled = Boolean.TRUE.equals(value);
                        preferences
                                .edit()
                                .putBoolean("skip_image_editor_screen", !enabled)
                                .apply();
                        return true;
                    });
        }

        final TwoStatePreference videoEditor =
                (TwoStatePreference)
                        mSettingsFragment.findPreference("video_editor_before_send");
        if (videoEditor != null) {
            videoEditor.setPersistent(false);
            final boolean skipEditor =
                    preferences.getBoolean(
                            "skip_video_editor_screen",
                            getResources().getBoolean(R.bool.skip_video_editor_screen));
            videoEditor.setChecked(!skipEditor);
            videoEditor.setOnPreferenceChangeListener(
                    (preference, value) -> {
                        final boolean enabled = Boolean.TRUE.equals(value);
                        preferences
                                .edit()
                                .putBoolean("skip_video_editor_screen", !enabled)
                                .apply();
                        return true;
                    });
        }

        final AppearanceChoicePreference locationMethod =
                (AppearanceChoicePreference)
                        mSettingsFragment.findPreference("share_location_method");
        if (locationMethod != null) {
            final boolean pluginInstalled = GeoHelper.isLocationPluginInstalled(this);
            if (!pluginInstalled) {
                final PreferenceCategory attachments =
                        (PreferenceCategory) mSettingsFragment.findPreference("attachments");
                if (attachments != null) {
                    attachments.removePreference(locationMethod);
                }
            } else {
                updateLocationMethodChoice(locationMethod, preferences);
                locationMethod.setChoiceAvailable(true);
                locationMethod.setOnPreferenceClickListener(
                        preference -> {
                            showLocationMethodDialog(locationMethod, preferences);
                            return true;
                        });
            }
        }
    }

    private void updateChatWallpaperChoice(final AppearanceChoicePreference preference) {
        final File background = ChatBackgroundHelper.getBgFile(this, null);
        if (ChatWallpaperPresets.isCustomSelected(this) && background.exists()) {
            preference.setValueLabel(R.string.neocont_chat_wallpaper_custom);
            return;
        }
        preference.setValueLabel(ChatWallpaperPresets.getSelectedPreset(this).labelRes);
    }

    private void showChatWallpaperDialog(final AppearanceChoicePreference preference) {
        final File background = ChatBackgroundHelper.getBgFile(this, null);
        final boolean hasCustomBackground = background.exists();
        final ChatWallpaperPresets.Preset[] presets = ChatWallpaperPresets.Preset.values();
        final String selection = ChatWallpaperPresets.getSelection(this);

        final VisualChoiceSheet sheet =
                createVisualChoiceSheet(R.string.neocont_chat_wallpaper, 2);

        int index = 0;
        for (final ChatWallpaperPresets.Preset preset : presets) {
            final boolean selected = preset.id.equals(selection);
            addWallpaperChoiceCard(
                    sheet,
                    index++,
                    getText(preset.labelRes),
                    selected,
                    createWallpaperPreview(preset),
                    () -> {
                        ChatWallpaperPresets.selectPreset(this, preset);
                        ChatWallpaperPresets.prewarmForDisplay(this);
                        preference.setValueLabel(preset.labelRes);
                        refreshAppearancePreview();
                        sheet.dialog.dismiss();
                    });
        }

        final boolean customSelected =
                ChatWallpaperPresets.CUSTOM_ID.equals(selection) && hasCustomBackground;
        addVisualChoiceCard(
                sheet,
                index,
                getText(R.string.neocont_chat_wallpaper_custom),
                customSelected,
                createCustomWallpaperPreview(hasCustomBackground),
                () -> {
                    sheet.dialog.dismiss();
                    if (hasCustomBackground) {
                        ChatWallpaperPresets.selectCustom(this);
                        preference.setValueLabel(R.string.neocont_chat_wallpaper_custom);
                        refreshAppearancePreview();
                    } else if (hasStoragePermission(
                            ChatBackgroundHelper.REQUEST_IMPORT_BACKGROUND)) {
                        ChatBackgroundHelper.openBGPicker(this);
                    }
                });

        if (hasCustomBackground) {
            final MaterialButton remove = new MaterialButton(this);
            remove.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            remove.setStrokeWidth(0);
            remove.setTextColor(
                    StyledAttributes.getColor(
                            this, com.google.android.material.R.attr.colorPrimary));
            remove.setText(R.string.neocont_chat_wallpaper_remove_custom);
            remove.setOnClickListener(
                    view -> {
                        final boolean wasCustom = ChatWallpaperPresets.isCustomSelected(this);
                        if (background.delete()) {
                            if (wasCustom) {
                                ChatWallpaperPresets.selectPreset(
                                        this, ChatWallpaperPresets.Preset.MIST);
                                ChatWallpaperPresets.prewarmForDisplay(this);
                            }
                            updateChatWallpaperChoice(preference);
                            refreshAppearancePreview();
                            sheet.dialog.dismiss();
                            Toast.makeText(
                                            this,
                                            R.string.delete_background_success,
                                            Toast.LENGTH_SHORT)
                                    .show();
                        } else {
                            Toast.makeText(
                                            this,
                                            R.string.delete_background_failed,
                                            Toast.LENGTH_LONG)
                                    .show();
                        }
                    });
            final LinearLayout.LayoutParams buttonParams =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            buttonParams.setMargins(dpToPx(4), dpToPx(8), dpToPx(4), 0);
            sheet.root.addView(remove, buttonParams);
        }

        sheet.dialog.show();
    }

    private void updateLocationMethodChoice(
            final AppearanceChoicePreference preference,
            final SharedPreferences preferences) {
        final boolean usePlugin =
                GeoHelper.isLocationPluginInstalled(this)
                        && preferences.getBoolean(
                                "use_share_location_plugin",
                                getResources().getBoolean(R.bool.use_share_location_plugin));
        preference.setValueLabel(
                usePlugin
                        ? R.string.neocont_location_plugin
                        : R.string.neocont_location_builtin);
    }

    private void showLocationMethodDialog(
            final AppearanceChoicePreference preference,
            final SharedPreferences preferences) {
        final boolean usePlugin =
                preferences.getBoolean(
                        "use_share_location_plugin",
                        getResources().getBoolean(R.bool.use_share_location_plugin));
        final CharSequence[] entries = {
            getText(R.string.neocont_location_builtin),
            getText(R.string.neocont_location_plugin)
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.neocont_location_method)
                .setSingleChoiceItems(
                        entries,
                        usePlugin ? 1 : 0,
                        (dialog, which) -> {
                            final boolean selectedPlugin = which == 1;
                            preferences
                                    .edit()
                                    .putBoolean(
                                            "use_share_location_plugin",
                                            selectedPlugin)
                                    .apply();
                            preference.setValueLabel(
                                    selectedPlugin
                                            ? R.string.neocont_location_plugin
                                            : R.string.neocont_location_builtin);
                            dialog.dismiss();
                        })
                .show();
    }

    private void configureNeoContDataPreferences() {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(this);

        refreshLocalStorageUsage();

        final Preference imageQualityPreference =
                mSettingsFragment.findPreference("picture_compression");
        if (imageQualityPreference instanceof AppearanceChoicePreference) {
            final AppearanceChoicePreference imageQuality =
                    (AppearanceChoicePreference) imageQualityPreference;
            imageQuality.setPersistent(false);
            updateImageQualityChoice(imageQuality, preferences);
            imageQuality.setOnPreferenceClickListener(
                    preference -> {
                        showImageQualityDialog(imageQuality, preferences);
                        return true;
                    });
        }

        final Preference videoQualityPreference =
                mSettingsFragment.findPreference("video_compression");
        if (videoQualityPreference instanceof AppearanceChoicePreference) {
            final AppearanceChoicePreference videoQuality =
                    (AppearanceChoicePreference) videoQualityPreference;
            videoQuality.setPersistent(false);
            updateVideoQualityChoice(videoQuality, preferences);
            videoQuality.setOnPreferenceClickListener(
                    preference -> {
                        showVideoQualityDialog(videoQuality, preferences);
                        return true;
                    });
        }

        final Preference autoDownloadPreference =
                mSettingsFragment.findPreference("auto_accept_file_size");
        if (autoDownloadPreference instanceof AppearanceChoicePreference) {
            final AppearanceChoicePreference autoDownload =
                    (AppearanceChoicePreference) autoDownloadPreference;
            autoDownload.setPersistent(false);
            updateAutoDownloadChoice(autoDownload, preferences);
            autoDownload.setOnPreferenceClickListener(
                    preference -> {
                        showAutoDownloadDialog(autoDownload, preferences);
                        return true;
                    });
        }

        final Preference messageRetentionPreference =
                mSettingsFragment.findPreference(AUTOMATIC_MESSAGE_DELETION);
        if (messageRetentionPreference instanceof AppearanceChoicePreference) {
            final AppearanceChoicePreference messageRetention =
                    (AppearanceChoicePreference) messageRetentionPreference;
            messageRetention.setPersistent(false);
            updateMessageRetentionChoice(messageRetention, preferences);
            messageRetention.setOnPreferenceClickListener(
                    preference -> {
                        showMessageRetentionDialog(messageRetention, preferences);
                        return true;
                    });
        }

        final Preference appStorage = mSettingsFragment.findPreference("clean_cache");
        if (appStorage != null) {
            appStorage.setOnPreferenceClickListener(preference -> cleanCache());
        }
    }

    private void updateImageQualityChoice(
            final AppearanceChoicePreference preference,
            final SharedPreferences preferences) {
        final String value =
                getStringPreference(
                        preferences, "picture_compression", getString(R.string.picture_compression));
        switch (value) {
            case "never":
                preference.setValueLabel(R.string.neocont_image_quality_original);
                break;
            case "always":
                preference.setValueLabel(R.string.neocont_image_quality_compressed);
                break;
            case "auto":
            default:
                preference.setValueLabel(R.string.neocont_image_quality_auto);
                break;
        }
    }

    private void showImageQualityDialog(
            final AppearanceChoicePreference preference,
            final SharedPreferences preferences) {
        final CharSequence[] entries = {
            getText(R.string.neocont_image_quality_original),
            getText(R.string.neocont_image_quality_auto),
            getText(R.string.neocont_image_quality_compressed)
        };
        final String[] values = {"never", "auto", "always"};
        final String current =
                getStringPreference(
                        preferences, "picture_compression", getString(R.string.picture_compression));
        showDataChoiceDialog(
                preference,
                preferences,
                "picture_compression",
                entries,
                values,
                findChoiceIndex(values, current));
    }

    private void updateVideoQualityChoice(
            final AppearanceChoicePreference preference,
            final SharedPreferences preferences) {
        final String[] entries = getResources().getStringArray(R.array.video_compression_entries);
        final String[] values = getResources().getStringArray(R.array.video_compression_values);
        final String current =
                getStringPreference(
                        preferences, "video_compression", getString(R.string.video_compression));
        final int index = findChoiceIndex(values, current);
        preference.setValueLabel(entries[Math.max(0, index)]);
    }

    private void showVideoQualityDialog(
            final AppearanceChoicePreference preference,
            final SharedPreferences preferences) {
        final String[] entries = getResources().getStringArray(R.array.video_compression_entries);
        final String[] values = getResources().getStringArray(R.array.video_compression_values);
        final String current =
                getStringPreference(
                        preferences, "video_compression", getString(R.string.video_compression));
        showDataChoiceDialog(
                preference,
                preferences,
                "video_compression",
                entries,
                values,
                findChoiceIndex(values, current));
    }

    private void updateAutoDownloadChoice(
            final AppearanceChoicePreference preference,
            final SharedPreferences preferences) {
        final String[] entries = getResources().getStringArray(R.array.filesizes);
        final String[] values = getResources().getStringArray(R.array.filesize_values);
        final String defaultValue =
                String.valueOf(getResources().getInteger(R.integer.auto_accept_filesize));
        final String current =
                getStringPreference(preferences, "auto_accept_file_size", defaultValue);
        final int index = Math.max(0, findChoiceIndex(values, current));
        preference.setValueLabel(
                index == 0
                        ? entries[0]
                        : getString(R.string.neocont_download_up_to, entries[index]));
    }

    private void showAutoDownloadDialog(
            final AppearanceChoicePreference preference,
            final SharedPreferences preferences) {
        final String[] sourceEntries = getResources().getStringArray(R.array.filesizes);
        final String[] values = getResources().getStringArray(R.array.filesize_values);
        final CharSequence[] entries = new CharSequence[sourceEntries.length];
        entries[0] = sourceEntries[0];
        for (int i = 1; i < sourceEntries.length; ++i) {
            entries[i] = getString(R.string.neocont_download_up_to, sourceEntries[i]);
        }
        final String defaultValue =
                String.valueOf(getResources().getInteger(R.integer.auto_accept_filesize));
        final String current =
                getStringPreference(preferences, "auto_accept_file_size", defaultValue);
        showDataChoiceDialog(
                preference,
                preferences,
                "auto_accept_file_size",
                entries,
                values,
                findChoiceIndex(values, current));
    }

    private void updateMessageRetentionChoice(
            final AppearanceChoicePreference preference,
            final SharedPreferences preferences) {
        final int[] choices =
                getResources().getIntArray(R.array.automatic_message_deletion_values);
        final String defaultValue =
                String.valueOf(getResources().getInteger(R.integer.automatic_message_deletion));
        final String current =
                getStringPreference(preferences, AUTOMATIC_MESSAGE_DELETION, defaultValue);
        int selected = 0;
        for (int i = 0; i < choices.length; ++i) {
            if (Objects.equals(String.valueOf(choices[i]), current)) {
                selected = i;
                break;
            }
        }
        preference.setValueLabel(
                choices[selected] == 0
                        ? getText(R.string.neocont_message_retention_off)
                        : TimeFrameUtils.resolve(this, 1000L * choices[selected]));
    }

    private void showMessageRetentionDialog(
            final AppearanceChoicePreference preference,
            final SharedPreferences preferences) {
        final int[] choices =
                getResources().getIntArray(R.array.automatic_message_deletion_values);
        final CharSequence[] entries = new CharSequence[choices.length];
        final String[] values = new String[choices.length];
        final String defaultValue =
                String.valueOf(getResources().getInteger(R.integer.automatic_message_deletion));
        final String current =
                getStringPreference(preferences, AUTOMATIC_MESSAGE_DELETION, defaultValue);
        int selected = 0;
        for (int i = 0; i < choices.length; ++i) {
            values[i] = String.valueOf(choices[i]);
            entries[i] =
                    choices[i] == 0
                            ? getText(R.string.neocont_message_retention_off)
                            : TimeFrameUtils.resolve(this, 1000L * choices[i]);
            if (Objects.equals(values[i], current)) {
                selected = i;
            }
        }
        showDataChoiceDialog(
                preference,
                preferences,
                AUTOMATIC_MESSAGE_DELETION,
                entries,
                values,
                selected);
    }

    private void showDataChoiceDialog(
            final AppearanceChoicePreference preference,
            final SharedPreferences preferences,
            final String key,
            final CharSequence[] entries,
            final String[] values,
            final int selectedIndex) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(preference.getTitle())
                .setSingleChoiceItems(
                        entries,
                        Math.max(0, selectedIndex),
                        (dialog, which) -> {
                            preferences.edit().putString(key, values[which]).apply();
                            preference.setValueLabel(entries[which]);
                            dialog.dismiss();
                        })
                .show();
    }

    private int findChoiceIndex(final String[] values, final String current) {
        for (int i = 0; i < values.length; ++i) {
            if (Objects.equals(values[i], current)) {
                return i;
            }
        }
        return 0;
    }

    private String getStringPreference(
            final SharedPreferences preferences, final String key, final String defaultValue) {
        try {
            return preferences.getString(key, defaultValue);
        } catch (final ClassCastException ignored) {
            return defaultValue;
        }
    }

    private void configureNeoContAppearancePreferences() {
        final AppearanceController controller = new AppearanceController(this);
        applyingAppearanceColor = true;
        controller.migrateLegacyAccentIfNeeded();
        applyingAppearanceColor = false;
        final var initialAppearanceState = controller.migrateLegacyTextScaleIfNeeded();

        final Preference languagePreference =
                mSettingsFragment.findPreference("app_language");
        final AppearanceChoicePreference theme =
                (AppearanceChoicePreference) mSettingsFragment.findPreference(THEME);
        final MessageTextSizePreference textSize =
                (MessageTextSizePreference)
                        mSettingsFragment.findPreference("message_text_size_sp");
        final AppearanceChoicePreference colorSource =
                (AppearanceChoicePreference)
                        mSettingsFragment.findPreference("appearance_color_source");

        if (languagePreference instanceof AppearanceChoicePreference) {
            final AppearanceChoicePreference language =
                    (AppearanceChoicePreference) languagePreference;
            updateAppLanguageChoice(language);
            language.setOnPreferenceClickListener(
                    preference -> {
                        showAppLanguageDialog(language);
                        return true;
                    });
        }

        if (theme != null) {
            updateThemeChoice(theme);
            theme.setOnPreferenceClickListener(
                    preference -> {
                        showThemeDialog(controller, theme);
                        return true;
                    });
        }

        if (textSize != null) {
            textSize.setValueSp(initialAppearanceState.getMessageTextSizeSp());
            textSize.setOnPreferenceChangeListener(
                    (preference, value) -> {
                        final float requested = ((Number) value).floatValue();
                        final var state = controller.setMessageTextSizeSp(requested);
                        textSize.setValueSp(state.getMessageTextSizeSp());
                        refreshAppearancePreview();
                        return true;
                    });
        }

        if (colorSource != null) {
            updateColorSourceChoice(colorSource);
            colorSource.setOnPreferenceClickListener(
                    preference -> {
                        showColorSourceDialog(controller, colorSource);
                        return true;
                    });
        }

        final AppearanceChoicePreference wallpaper =
                (AppearanceChoicePreference) mSettingsFragment.findPreference("chat_wallpaper");
        if (wallpaper != null) {
            updateChatWallpaperChoice(wallpaper);
            wallpaper.setOnPreferenceClickListener(
                    preference -> {
                        showChatWallpaperDialog(wallpaper);
                        return true;
                    });
        }
    }

    private void updateAppLanguageChoice(
            final AppearanceChoicePreference preference) {
        preference.setValueLabel(appLanguageLabelRes(currentAppLanguage()));
    }

    private int appLanguageLabelRes(final String language) {
        if ("ru".equals(language)) {
            return R.string.neocont_language_russian;
        }
        if ("de".equals(language)) {
            return R.string.neocont_language_german;
        }
        return R.string.neocont_language_english;
    }

    private String currentAppLanguage() {
        final LocaleListCompat applicationLocales = AppCompatDelegate.getApplicationLocales();
        if (!applicationLocales.isEmpty() && applicationLocales.get(0) != null) {
            final String language = applicationLocales.get(0).getLanguage();
            if ("ru".equals(language) || "de".equals(language)) {
                return language;
            }
            return "en";
        }
        final java.util.Locale locale = getResources().getConfiguration().getLocales().get(0);
        if (locale != null) {
            final String language = locale.getLanguage();
            if ("ru".equals(language) || "de".equals(language)) {
                return language;
            }
        }
        return "en";
    }

    private void showAppLanguageDialog(final AppearanceChoicePreference preference) {
        final String[] baseTags = {"ru", "en", "de"};
        final String deviceLanguage = deviceAppLanguage();
        final String[] tags = new String[baseTags.length];
        int next = 0;
        tags[next++] = deviceLanguage;
        for (final String tag : baseTags) {
            if (!deviceLanguage.equals(tag)) {
                tags[next++] = tag;
            }
        }

        final CharSequence[] entries = new CharSequence[tags.length];
        for (int i = 0; i < tags.length; i++) {
            entries[i] = getText(appLanguageLabelRes(tags[i]));
        }

        final String current = currentAppLanguage();
        int selected = 0;
        for (int i = 0; i < tags.length; i++) {
            if (Objects.equals(current, tags[i])) {
                selected = i;
                break;
            }
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.neocont_app_language)
                .setSingleChoiceItems(
                        entries,
                        selected,
                        (dialog, which) -> {
                            final String selectedTag = tags[which];
                            dialog.dismiss();
                            if (Objects.equals(current, selectedTag)
                                    && !AppCompatDelegate.getApplicationLocales().isEmpty()) {
                                return;
                            }
                            preference.setValueLabel(entries[which]);
                            AppCompatDelegate.setApplicationLocales(
                                    LocaleListCompat.forLanguageTags(selectedTag));
                        })
                .show();
    }

    private String deviceAppLanguage() {
        final java.util.Locale locale =
                android.content.res.Resources.getSystem()
                        .getConfiguration()
                        .getLocales()
                        .get(0);
        if (locale != null) {
            final String language = locale.getLanguage();
            if ("ru".equals(language) || "de".equals(language)) {
                return language;
            }
        }
        return "en";
    }

    private void showColorSourceDialog(
            final AppearanceController controller,
            final AppearanceChoicePreference preference) {
        final AdaptiveBottomSheet.Sheet sheet =
                AdaptiveBottomSheet.create(this, R.string.appearance_color_source);
        populateColorSourceSheet(sheet, controller, preference);
        sheet.show();
    }

    private void populateColorSourceSheet(
            final AdaptiveBottomSheet.Sheet sheet,
            final AppearanceController controller,
            final AppearanceChoicePreference preference) {
        sheet.resetContent(R.string.appearance_color_source);
        final var state = AppearanceSnapshotReader.INSTANCE.from(this);
        final boolean systemAvailable = DynamicColors.isDynamicColorAvailable();
        final boolean dynamicSelected = state.getDynamicColorsActive() && systemAvailable;
        final Integer customColor = state.getSettings().getCustomAccentColor();
        final boolean customSelected = customColor != null && !dynamicSelected;

        addColorSourceRow(
                sheet.getContent(),
                getText(R.string.appearance_color_source_neocont),
                null,
                colorSourcePreviewColors(0, customColor),
                !dynamicSelected && !customSelected,
                false,
                () -> {
                    final boolean wasDynamic =
                            state.getSettings().getDynamicColorsRequested();
                    final boolean hadCustom = customColor != null;
                    ChatWallpaperPresets.resetProcessPrewarm();
                    applyingAppearanceColor = true;
                    controller.selectNeoContColors();
                    applyingAppearanceColor = false;
                    preference.setValueLabel(R.string.appearance_color_source_neocont);
                    refreshAppearancePreview();
                    sheet.dismiss();
                    if (wasDynamic) {
                        setDynamicColors(false);
                    } else if (hadCustom) {
                        recreate();
                    } else {
                        ChatWallpaperPresets.prewarmForDisplay(this);
                    }
                });

        if (systemAvailable) {
            addColorSourceRow(
                    sheet.getContent(),
                    getText(R.string.appearance_color_source_system),
                    null,
                    colorSourcePreviewColors(1, customColor),
                    dynamicSelected,
                    false,
                    () -> {
                        ChatWallpaperPresets.resetProcessPrewarm();
                        applyingAppearanceColor = true;
                        controller.selectSystemColors();
                        applyingAppearanceColor = false;
                        preference.setValueLabel(R.string.appearance_color_source_system);
                        refreshAppearancePreview();
                        sheet.dismiss();
                        setDynamicColors(true);
                    });
        }

        addColorSourceRow(
                sheet.getContent(),
                getText(R.string.appearance_color_source_custom),
                customSelected ? paletteNameForColor(customColor) : null,
                colorSourcePreviewColors(2, customColor),
                customSelected,
                true,
                () -> showAccentPaletteList(sheet, controller, preference));
    }

    private void showAccentPaletteList(
            final AdaptiveBottomSheet.Sheet sheet,
            final AppearanceController controller,
            final AppearanceChoicePreference preference) {
        sheet.resetContent(R.string.appearance_palettes_title);
        sheet.showBackAction(
                R.string.back,
                () -> populateColorSourceSheet(sheet, controller, preference));

        final String[] actualColors =
                getResources().getStringArray(R.array.themeAccentColorsV3);
        final String[] pickerColors =
                getResources().getStringArray(R.array.themeAccentPickerColorsV3);
        final CharSequence[] names =
                getResources().getTextArray(R.array.themeAccentNamesV3);
        final Integer current =
                AppearanceSnapshotReader.INSTANCE.from(this)
                        .getSettings()
                        .getCustomAccentColor();

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(
                list,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        final int count =
                Math.min(names.length, Math.min(actualColors.length, pickerColors.length));
        final int selectedIndex = current == null ? -1 : findAccentPaletteIndex(current);
        for (int i = 0; i < count; i++) {
            final int actualColor = Color.parseColor(actualColors[i]);
            final int displayColor = Color.parseColor(pickerColors[i]);
            final CharSequence name = names[i];
            final boolean selected = selectedIndex == i;
            addAccentPaletteRow(
                    list,
                    name,
                    displayColor,
                    selected,
                    () -> {
                        final var before = AppearanceSnapshotReader.INSTANCE.from(this);
                        ChatWallpaperPresets.resetProcessPrewarm();
                        applyingAppearanceColor = true;
                        controller.selectCustomAccent(actualColor);
                        applyingAppearanceColor = false;
                        preference.setValueLabel(name);
                        refreshAppearancePreview();
                        sheet.dismiss();
                        if (before.getSettings().getDynamicColorsRequested()) {
                            setDynamicColors(false);
                        } else {
                            recreate();
                        }
                    });
        }

        final int maxHeight =
                Math.round(getResources().getDisplayMetrics().heightPixels * 0.62f);
        final int desiredHeight = dpToPx(Math.max(1, count) * 58);
        sheet.getContent()
                .addView(
                        scroll,
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                Math.min(desiredHeight, maxHeight)));
    }

    private void updateColorSourceChoice(final AppearanceChoicePreference preference) {
        final var state = AppearanceSnapshotReader.INSTANCE.from(this);
        if (state.getDynamicColorsActive()) {
            preference.setValueLabel(R.string.appearance_color_source_system);
        } else if (state.getSettings().getCustomAccentColor() != null) {
            preference.setValueLabel(
                    paletteNameForColor(state.getSettings().getCustomAccentColor()));
        } else {
            preference.setValueLabel(R.string.appearance_color_source_neocont);
        }
    }

    private CharSequence paletteNameForColor(@Nullable final Integer color) {
        if (color == null) {
            return getText(R.string.appearance_color_source_custom);
        }
        final int index = findAccentPaletteIndex(color);
        final CharSequence[] names =
                getResources().getTextArray(R.array.themeAccentNamesV3);
        if (index >= 0 && index < names.length) {
            return names[index];
        }
        return getText(R.string.appearance_color_source_custom);
    }

    private int findAccentPaletteIndex(final int color) {
        final String[][] palettes = {
            getResources().getStringArray(R.array.themeAccentColorsV3),
            getResources().getStringArray(R.array.themeAccentColorsV2),
            getResources().getStringArray(R.array.themeColorsOverride)
        };
        for (final String[] palette : palettes) {
            for (int i = 0; i < palette.length; i++) {
                if (Color.parseColor(palette[i]) == color) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int[] colorSourcePreviewColors(
            final int source, @Nullable final Integer customColor) {
        if (source == 0) {
            return new int[] {0xFF3F7FC4, 0xFF2693A2, 0xFF2F867A};
        }
        if (source == 1) {
            return new int[] {
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorPrimary),
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorSecondary),
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorTertiary)
            };
        }
        final int base = customColor != null ? customColor : 0xFF775BC0;
        return new int[] {
            ColorUtils.blendARGB(base, Color.WHITE, 0.12f),
            base,
            ColorUtils.blendARGB(base, Color.BLACK, 0.14f)
        };
    }

    private void addColorSourceRow(
            final LinearLayout parent,
            final CharSequence label,
            @Nullable final CharSequence summary,
            final int[] colors,
            final boolean selected,
            final boolean navigates,
            final Runnable action) {
        final MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dpToPx(18));
        card.setCardElevation(0f);
        card.setUseCompatPadding(false);
        card.setClickable(true);
        card.setFocusable(true);

        final int primary =
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorPrimary);
        final int outline =
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorOutlineVariant);
        card.setCardBackgroundColor(
                StyledAttributes.getColor(
                        this,
                        selected
                                ? com.google.android.material.R.attr.colorPrimaryContainer
                                : com.google.android.material.R.attr.colorSurfaceContainerLow));
        card.setStrokeColor(selected ? primary : outline);
        card.setStrokeWidth(dpToPx(selected ? 2 : 1));
        card.setRippleColor(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 28)));

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dpToPx(68));
        row.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));

        final LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        final LinearLayout.LayoutParams dotsParams =
                new LinearLayout.LayoutParams(dpToPx(88), dpToPx(44));
        dotsParams.setMarginEnd(dpToPx(14));
        row.addView(dots, dotsParams);
        for (final int color : colors) {
            final View dot = new View(this);
            dot.setBackground(solidRoundedDrawable(color, dpToPx(99)));
            final LinearLayout.LayoutParams dotParams =
                    new LinearLayout.LayoutParams(dpToPx(22), dpToPx(22));
            dotParams.setMargins(dpToPx(3), 0, dpToPx(3), 0);
            dots.addView(dot, dotParams);
        }

        final LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        final TextView title = new TextView(this);
        title.setText(label);
        title.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        title.setTextColor(
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorOnSurface));
        text.addView(
                title,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        if (summary != null && summary.length() > 0) {
            final TextView summaryView = new TextView(this);
            summaryView.setText(summary);
            summaryView.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            summaryView.setTextColor(
                    StyledAttributes.getColor(
                            this,
                            com.google.android.material.R.attr.colorOnSurfaceVariant));
            text.addView(
                    summaryView,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        row.addView(
                text,
                new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final ImageView trailing = new ImageView(this);
        trailing.setImageResource(
                navigates
                        ? R.drawable.ic_chevron_right_unpadded_vector
                        : R.drawable.ic_check_24dp);
        trailing.setImageTintList(
                ColorStateList.valueOf(
                        StyledAttributes.getColor(
                                this,
                                selected
                                        ? com.google.android.material.R.attr.colorPrimary
                                        : com.google.android.material.R.attr.colorOnSurfaceVariant)));
        trailing.setVisibility(navigates || selected ? View.VISIBLE : View.INVISIBLE);
        row.addView(
                trailing,
                new LinearLayout.LayoutParams(dpToPx(28), dpToPx(28)));

        card.addView(row);
        card.setOnClickListener(view -> action.run());
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dpToPx(4), dpToPx(3), dpToPx(4), dpToPx(3));
        parent.addView(card, params);
    }

    private void addAccentPaletteRow(
            final LinearLayout parent,
            final CharSequence label,
            final int displayColor,
            final boolean selected,
            final Runnable action) {
        final MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dpToPx(16));
        card.setCardElevation(0f);
        card.setUseCompatPadding(false);
        card.setClickable(true);
        card.setFocusable(true);
        final int primary =
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorPrimary);
        card.setCardBackgroundColor(
                StyledAttributes.getColor(
                        this,
                        selected
                                ? com.google.android.material.R.attr.colorPrimaryContainer
                                : com.google.android.material.R.attr.colorSurface));
        card.setRippleColor(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 28)));

        final LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dpToPx(54));
        row.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));

        final View dot = new View(this);
        dot.setBackground(solidRoundedDrawable(displayColor, dpToPx(99)));
        final LinearLayout.LayoutParams dotParams =
                new LinearLayout.LayoutParams(dpToPx(30), dpToPx(30));
        dotParams.setMarginEnd(dpToPx(16));
        row.addView(dot, dotParams);

        final TextView title = new TextView(this);
        title.setText(label);
        title.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        title.setTextColor(
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorOnSurface));
        row.addView(
                title,
                new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final ImageView check = new ImageView(this);
        check.setImageResource(R.drawable.ic_check_24dp);
        check.setImageTintList(ColorStateList.valueOf(primary));
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        row.addView(
                check,
                new LinearLayout.LayoutParams(dpToPx(28), dpToPx(28)));

        card.addView(row);
        card.setOnClickListener(view -> action.run());
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2));
        parent.addView(card, params);
    }

    private void showThemeDialog(
            final AppearanceController controller,
            final AppearanceChoicePreference preference) {
        final CharSequence[] entries = getResources().getTextArray(R.array.themes);
        final String[] values = getResources().getStringArray(R.array.themes_values);
        final String current =
                PreferenceManager.getDefaultSharedPreferences(this)
                        .getString(THEME, getString(R.string.theme));

        final VisualChoiceSheet sheet =
                createVisualChoiceSheet(R.string.pref_theme_options, 2);

        final int count = Math.min(entries.length, values.length);
        for (int i = 0; i < count; i++) {
            final int index = i;
            final String value = values[i];
            addVisualChoiceCard(
                    sheet,
                    i,
                    entries[i],
                    Objects.equals(value, current),
                    createThemePreview(value),
                    () -> {
                        ChatWallpaperPresets.resetProcessPrewarm();
                        controller.selectTheme(themeMode(value));
                        preference.setValueLabel(entries[index]);
                        sheet.dialog.dismiss();
                    });
        }

        sheet.dialog.show();
    }

    private void updateThemeChoice(final AppearanceChoicePreference preference) {
        final CharSequence[] entries = getResources().getTextArray(R.array.themes);
        final String[] values = getResources().getStringArray(R.array.themes_values);
        final String current =
                PreferenceManager.getDefaultSharedPreferences(this)
                        .getString(THEME, getString(R.string.theme));

        for (int i = 0; i < values.length && i < entries.length; i++) {
            if (Objects.equals(values[i], current)) {
                preference.setValueLabel(entries[i]);
                return;
            }
        }
        preference.setValueLabel("");
    }

    private VisualChoiceSheet createVisualChoiceSheet(
            final int titleRes, final int columns) {
        final AdaptiveBottomSheet.Sheet baseSheet =
                AdaptiveBottomSheet.create(this, titleRes);
        final BottomSheetDialog dialog = baseSheet.getDialog();
        final LinearLayout root = baseSheet.getContent();

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);

        final GridLayout grid = new GridLayout(this);
        grid.setColumnCount(columns);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);
        scroll.addView(
                grid,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        return new VisualChoiceSheet(dialog, root, grid, columns);
    }

    private void addVisualChoiceCard(
            final VisualChoiceSheet sheet,
            final int index,
            final CharSequence label,
            final boolean selected,
            final View preview,
            final Runnable action) {
        final MaterialCardView card = new MaterialCardView(this);
        final GridLayout.LayoutParams cardParams =
                new GridLayout.LayoutParams(
                        GridLayout.spec(index / sheet.columns),
                        GridLayout.spec(index % sheet.columns, 1f));
        cardParams.width = 0;
        cardParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        cardParams.setMargins(dpToPx(5), dpToPx(5), dpToPx(5), dpToPx(5));
        card.setLayoutParams(cardParams);
        card.setRadius(dpToPx(18));
        card.setCardElevation(0f);
        card.setCardBackgroundColor(
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorSurfaceContainerLow));
        final int primary =
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorPrimary);
        final int outline =
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorOutlineVariant);
        card.setStrokeColor(selected ? primary : outline);
        card.setStrokeWidth(dpToPx(selected ? 2 : 1));
        card.setClickable(true);
        card.setFocusable(true);
        card.setRippleColor(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 28)));

        final FrameLayout frame = new FrameLayout(this);
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        if (preview != null) {
            final LinearLayout.LayoutParams previewParams =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(78));
            previewParams.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), 0);
            content.addView(preview, previewParams);
        }

        final TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        labelView.setGravity(Gravity.CENTER);
        labelView.setMaxLines(2);
        labelView.setPadding(dpToPx(8), dpToPx(10), dpToPx(8), dpToPx(12));
        content.addView(
                labelView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        frame.addView(
                content,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        if (selected) {
            final TextView check = new TextView(this);
            check.setText("✓");
            check.setGravity(Gravity.CENTER);
            check.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
            check.setTextColor(
                    StyledAttributes.getColor(
                            this, com.google.android.material.R.attr.colorOnPrimaryContainer));
            check.setBackground(
                    solidRoundedDrawable(
                            StyledAttributes.getColor(
                                    this,
                                    com.google.android.material.R.attr.colorPrimaryContainer),
                            dpToPx(99)));
            final FrameLayout.LayoutParams checkParams =
                    new FrameLayout.LayoutParams(dpToPx(26), dpToPx(26));
            checkParams.gravity = Gravity.TOP | Gravity.END;
            checkParams.setMargins(0, dpToPx(12), dpToPx(12), 0);
            frame.addView(check, checkParams);
        }

        card.addView(frame);
        card.setOnClickListener(view -> action.run());
        sheet.grid.addView(card);
    }

    private void addWallpaperChoiceCard(
            final VisualChoiceSheet sheet,
            final int index,
            final CharSequence label,
            final boolean selected,
            final View preview,
            final Runnable action) {
        final MaterialCardView card = new MaterialCardView(this);
        final GridLayout.LayoutParams cardParams =
                new GridLayout.LayoutParams(
                        GridLayout.spec(index / sheet.columns),
                        GridLayout.spec(index % sheet.columns, 1f));
        cardParams.width = 0;
        cardParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        cardParams.setMargins(dpToPx(5), dpToPx(5), dpToPx(5), dpToPx(5));
        card.setLayoutParams(cardParams);
        card.setRadius(dpToPx(18));
        card.setCardElevation(0f);
        card.setCardBackgroundColor(
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorSurfaceContainerLow));

        final int primary =
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorPrimary);
        final int outline =
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorOutlineVariant);
        card.setStrokeColor(
                selected
                        ? ColorUtils.setAlphaComponent(primary, 210)
                        : ColorUtils.setAlphaComponent(outline, 96));
        card.setStrokeWidth(dpToPx(1));
        card.setClickable(true);
        card.setFocusable(true);
        card.setRippleColor(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 28)));

        final FrameLayout frame = new FrameLayout(this);
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        if (preview != null) {
            final LinearLayout.LayoutParams previewParams =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(92));
            previewParams.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), 0);
            content.addView(preview, previewParams);
        }

        final TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        labelView.setGravity(Gravity.CENTER);
        labelView.setMaxLines(1);
        labelView.setPadding(dpToPx(8), dpToPx(9), dpToPx(8), dpToPx(11));
        content.addView(
                labelView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        frame.addView(
                content,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        if (selected) {
            final TextView check = new TextView(this);
            check.setText("✓");
            check.setGravity(Gravity.CENTER);
            check.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
            check.setTextColor(
                    StyledAttributes.getColor(
                            this, com.google.android.material.R.attr.colorOnPrimaryContainer));
            check.setBackground(
                    solidRoundedDrawable(
                            StyledAttributes.getColor(
                                    this,
                                    com.google.android.material.R.attr.colorPrimaryContainer),
                            dpToPx(99)));
            final FrameLayout.LayoutParams checkParams =
                    new FrameLayout.LayoutParams(dpToPx(22), dpToPx(22));
            checkParams.gravity = Gravity.TOP | Gravity.END;
            checkParams.setMargins(0, dpToPx(10), dpToPx(10), 0);
            frame.addView(check, checkParams);
        }

        card.addView(frame);
        card.setContentDescription(label);
        card.setOnClickListener(view -> action.run());
        sheet.grid.addView(card);
    }

    private View createThemePreview(final String value) {
        final FrameLayout preview = new FrameLayout(this);
        final int light = 0xFFF5F8FA;
        final int dark = 0xFF12181C;
        final int oled = Color.BLACK;

        final GradientDrawable background;
        if ("automatic".equals(value)) {
            background =
                    new GradientDrawable(
                            GradientDrawable.Orientation.LEFT_RIGHT,
                            new int[] {light, dark});
        } else {
            background = new GradientDrawable();
            background.setColor(
                    "oledblack".equals(value)
                            ? oled
                            : ("dark".equals(value) ? dark : light));
        }
        background.setCornerRadius(dpToPx(12));
        preview.setBackground(background);

        final boolean darkMode = "dark".equals(value) || "oledblack".equals(value);
        final int incoming =
                darkMode
                        ? ("oledblack".equals(value) ? 0xFF181D20 : 0xFF283138)
                        : 0xFFE3EAEE;
        final int outgoing = darkMode ? 0xFF245065 : 0xFFBCDCE9;
        addMiniBubble(preview, incoming, 52, Gravity.BOTTOM | Gravity.START, 10, 0, 0, 12);
        addMiniBubble(preview, outgoing, 64, Gravity.BOTTOM | Gravity.END, 0, 0, 10, 32);
        return preview;
    }

    private View createWallpaperPreview(final ChatWallpaperPresets.Preset preset) {
        final FrameLayout preview = new FrameLayout(this);
        final ChatWallpaperPresets.Resolved resolved =
                ChatWallpaperPresets.resolve(this, preset);

        // Use the exact same dithered renderer and resolved colors as the live chat. Do not call
        // createChatDrawable() here: the picker needs a small in-memory raster, not a persisted
        // full-screen chat raster.
        resolved.drawable.setCornerRadius(dpToPx(14));
        preview.setBackground(resolved.drawable);
        preview.setClipToOutline(true);

        final int incoming =
                StyledAttributes.getColor(this, R.attr.neoColorMessageIncomingSurface);
        final int incomingText =
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorOnSurfaceVariant);
        final int outgoing =
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorPrimaryContainer);
        final int outgoingText =
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorOnPrimaryContainer);

        addWallpaperMiniBubble(
                preview,
                incoming,
                incomingText,
                68,
                29,
                Gravity.TOP | Gravity.START,
                10,
                12,
                0,
                0);
        addWallpaperMiniBubble(
                preview,
                outgoing,
                outgoingText,
                78,
                29,
                Gravity.BOTTOM | Gravity.END,
                0,
                0,
                10,
                12);
        return preview;
    }

    private View createCustomWallpaperPreview(final boolean hasCustomBackground) {
        final FrameLayout preview = new FrameLayout(this);
        preview.setBackground(
                solidRoundedDrawable(
                        StyledAttributes.getColor(
                                this,
                                com.google.android.material.R.attr.colorSurfaceContainer),
                        dpToPx(14)));
        preview.setClipToOutline(true);

        if (hasCustomBackground) {
            final File background = ChatBackgroundHelper.getBgFile(this, null);
            final Bitmap thumbnail = decodeWallpaperPreview(background);
            if (thumbnail != null) {
                final ImageView image = new ImageView(this);
                image.setImageBitmap(thumbnail);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                preview.addView(
                        image,
                        new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                return preview;
            }
        }

        final ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_image_24dp);
        icon.setColorFilter(
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorOnSurfaceVariant));
        final FrameLayout.LayoutParams iconParams =
                new FrameLayout.LayoutParams(dpToPx(32), dpToPx(32));
        iconParams.gravity = Gravity.CENTER;
        preview.addView(icon, iconParams);

        if (!hasCustomBackground) {
            final TextView plus = new TextView(this);
            plus.setText("+");
            plus.setGravity(Gravity.CENTER);
            plus.setTextSize(16);
            plus.setTextColor(
                    StyledAttributes.getColor(
                            this, com.google.android.material.R.attr.colorOnPrimaryContainer));
            plus.setBackground(
                    solidRoundedDrawable(
                            StyledAttributes.getColor(
                                    this,
                                    com.google.android.material.R.attr.colorPrimaryContainer),
                            dpToPx(99)));
            final FrameLayout.LayoutParams plusParams =
                    new FrameLayout.LayoutParams(dpToPx(22), dpToPx(22));
            plusParams.gravity = Gravity.BOTTOM | Gravity.END;
            plusParams.setMargins(0, 0, dpToPx(10), dpToPx(10));
            preview.addView(plus, plusParams);
        }
        return preview;
    }

    @Nullable
    private Bitmap decodeWallpaperPreview(final File file) {
        if (file == null || !file.isFile()) {
            return null;
        }

        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        final int targetPx = Math.max(dpToPx(180), 1);
        int sampleSize = 1;
        while (bounds.outWidth / sampleSize > targetPx * 2
                || bounds.outHeight / sampleSize > targetPx * 2) {
            sampleSize *= 2;
        }

        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (final OutOfMemoryError ignored) {
            return null;
        }
    }

    private void addWallpaperMiniBubble(
            final FrameLayout parent,
            final int bubbleColor,
            final int textColor,
            final int widthDp,
            final int heightDp,
            final int gravity,
            final int leftDp,
            final int topDp,
            final int rightDp,
            final int bottomDp) {
        final FrameLayout bubble = new FrameLayout(this);
        bubble.setBackground(solidRoundedDrawable(bubbleColor, dpToPx(13)));
        final FrameLayout.LayoutParams bubbleParams =
                new FrameLayout.LayoutParams(dpToPx(widthDp), dpToPx(heightDp));
        bubbleParams.gravity = gravity;
        bubbleParams.setMargins(
                dpToPx(leftDp), dpToPx(topDp), dpToPx(rightDp), dpToPx(bottomDp));
        parent.addView(bubble, bubbleParams);

        final int lineColor = ColorUtils.setAlphaComponent(textColor, 82);
        final View firstLine = new View(this);
        firstLine.setBackground(solidRoundedDrawable(lineColor, dpToPx(99)));
        final FrameLayout.LayoutParams firstLineParams =
                new FrameLayout.LayoutParams(dpToPx(Math.max(24, widthDp - 22)), dpToPx(3));
        firstLineParams.gravity = Gravity.TOP | Gravity.START;
        firstLineParams.setMargins(dpToPx(10), dpToPx(8), 0, 0);
        bubble.addView(firstLine, firstLineParams);

        final View secondLine = new View(this);
        secondLine.setBackground(solidRoundedDrawable(lineColor, dpToPx(99)));
        final FrameLayout.LayoutParams secondLineParams =
                new FrameLayout.LayoutParams(
                        dpToPx(Math.max(18, (widthDp - 22) * 2 / 3)), dpToPx(3));
        secondLineParams.gravity = Gravity.TOP | Gravity.START;
        secondLineParams.setMargins(dpToPx(10), dpToPx(16), 0, 0);
        bubble.addView(secondLine, secondLineParams);
    }

    private void addMiniBubble(
            final FrameLayout parent,
            final int color,
            final int widthDp,
            final int gravity,
            final int leftDp,
            final int topDp,
            final int rightDp,
            final int bottomDp) {
        final View bubble = new View(this);
        bubble.setBackground(solidRoundedDrawable(color, dpToPx(8)));
        final FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(dpToPx(widthDp), dpToPx(14));
        params.gravity = gravity;
        params.setMargins(
                dpToPx(leftDp), dpToPx(topDp), dpToPx(rightDp), dpToPx(bottomDp));
        parent.addView(bubble, params);
    }

    private GradientDrawable solidRoundedDrawable(final int color, final float radius) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static final class VisualChoiceSheet {
        final BottomSheetDialog dialog;
        final LinearLayout root;
        final GridLayout grid;
        final int columns;

        VisualChoiceSheet(
                final BottomSheetDialog dialog,
                final LinearLayout root,
                final GridLayout grid,
                final int columns) {
            this.dialog = dialog;
            this.root = root;
            this.grid = grid;
            this.columns = columns;
        }
    }

    private int dpToPx(final int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private static ThemeMode themeMode(final String value) {
        switch (value) {
            case "automatic":
                return ThemeMode.SYSTEM;
            case "dark":
                return ThemeMode.DARK;
            case "oledblack":
                return ThemeMode.OLED;
            default:
                return ThemeMode.LIGHT;
        }
    }

    private void refreshAppearancePreview() {
        final AppearancePreviewPreference preview =
                (AppearancePreviewPreference)
                        mSettingsFragment.findPreference("neocont_appearance_preview");
        if (preview != null) {
            preview.refresh();
        }
    }

    private void openNeoContAccount() {
        if (!xmppConnectionServiceBound || xmppConnectionService == null) {
            displayToast(getString(R.string.neocont_no_xmpp_account));
            return;
        }
        if (xmppConnectionService.getAccounts().isEmpty()) {
            displayToast(getString(R.string.neocont_no_xmpp_account));
            return;
        }
        startActivity(ProfileNavigation.profileIntentForHome(this, xmppConnectionService));
    }

    private void openNeoContDevices() {
        if (!xmppConnectionServiceBound || xmppConnectionService == null) {
            displayToast(getString(R.string.neocont_no_xmpp_account));
            return;
        }

        final List<Account> accounts = new ArrayList<>(xmppConnectionService.getAccounts());
        if (accounts.isEmpty()) {
            displayToast(getString(R.string.neocont_no_xmpp_account));
            return;
        }
        if (accounts.size() == 1) {
            openNeoContDevices(accounts.get(0));
            return;
        }

        AccountChoiceBottomSheet.show(
                this,
                xmppConnectionService,
                R.string.profile_devices_choose_account,
                accounts,
                this::openNeoContDevices);
    }

    private void openNeoContDevices(final Account account) {
        startActivity(
                ProfileNavigation.contextualDevicesIntent(
                        this, account.getUuid()));
    }

    private void configureNeoContAccountPreferences() {
        final Preference manageAccount = mSettingsFragment.findPreference("neocont_manage_account");
        final Preference accountRoot = mSettingsFragment.findPreference("neocont_account");
        if (manageAccount == null && accountRoot == null) {
            return;
        }
        if (xmppConnectionService == null) {
            return;
        }
        final List<Account> accounts = xmppConnectionService.getAccounts();
        final String summary;
        if (accounts.isEmpty()) {
            summary = getString(R.string.neocont_no_xmpp_account);
        } else if (accounts.size() == 1) {
            final Account account = accounts.get(0);
            summary =
                    account.getJid().asBareJid()
                            + " · "
                            + getString(account.getStatus().getReadableId());
        } else {
            summary = getString(R.string.neocont_accounts_count, accounts.size());
        }
        if (accountRoot != null) {
            accountRoot.setSummary(summary);
        }
    }

    private void configureNotificationPreferences() {
        final PreferenceGroup messages =
                (PreferenceGroup) mSettingsFragment.findPreference(
                        "neocont_message_notifications");
        if (messages == null) {
            return;
        }

        final Preference systemSettings =
                mSettingsFragment.findPreference("message_notification_settings");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final Preference headsUp =
                    mSettingsFragment.findPreference(AppSettings.NOTIFICATION_HEADS_UP);
            final Preference vibrate =
                    mSettingsFragment.findPreference(AppSettings.NOTIFICATION_VIBRATE);
            final Preference ringtone =
                    mSettingsFragment.findPreference(AppSettings.NOTIFICATION_RINGTONE);
            if (headsUp != null) {
                messages.removePreference(headsUp);
            }
            if (vibrate != null) {
                messages.removePreference(vibrate);
            }
            if (ringtone != null) {
                messages.removePreference(ringtone);
            }
        } else if (systemSettings != null) {
            messages.removePreference(systemSettings);
        }
    }

    private void configureRingtonePreferences() {
        final Preference notification =
                mSettingsFragment.findPreference(AppSettings.NOTIFICATION_RINGTONE);
        if (notification != null) {
            notification.setOnPreferenceClickListener(
                    preference -> {
                        notificationRingtonePicker.launch(
                                currentRingtone(
                                        AppSettings.NOTIFICATION_RINGTONE,
                                        R.string.notification_ringtone));
                        return true;
                    });
        }

        final Preference callPreference =
                mSettingsFragment.findPreference(AppSettings.RINGTONE);
        if (callPreference instanceof AppearanceChoicePreference) {
            final AppearanceChoicePreference call =
                    (AppearanceChoicePreference) callPreference;
            updateCallRingtoneChoice(call);
            call.setOnPreferenceClickListener(
                    preference -> {
                        final Uri selected =
                                PickRingtone.noneToNull(
                                        currentRingtone(
                                                AppSettings.RINGTONE,
                                                R.string.incoming_call_ringtone));
                        CallRingtoneBottomSheet.show(
                                this,
                                selected,
                                new CallRingtoneBottomSheet.Listener() {
                                    @Override
                                    public void onRingtoneSelected(final Uri ringtone) {
                                        applyCallRingtone(ringtone);
                                    }

                                    @Override
                                    public void onChooseAudioFile() {
                                        openCallRingtoneFilePicker();
                                    }
                                });
                        return true;
                    });
        }
    }

    private Uri currentRingtone(final String key, final int defaultValue) {
        final String value =
                PreferenceManager.getDefaultSharedPreferences(this)
                        .getString(key, getString(defaultValue));
        return Strings.isNullOrEmpty(value)
                ? PickRingtone.nullToNone(null)
                : Uri.parse(value);
    }

    private void persistRingtone(final String key, final Uri result) {
        if (result == null) {
            return;
        }
        final Uri ringtone = PickRingtone.noneToNull(result);
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(key, ringtone == null ? "" : ringtone.toString())
                .apply();
    }

    private void applyCallRingtone(final Uri ringtone) {
        final Uri current =
                PickRingtone.noneToNull(
                        currentRingtone(
                                AppSettings.RINGTONE,
                                R.string.incoming_call_ringtone));
        if (!Objects.equals(current, ringtone)) {
            PreferenceManager.getDefaultSharedPreferences(this)
                    .edit()
                    .putString(
                            AppSettings.RINGTONE,
                            ringtone == null ? "" : ringtone.toString())
                    .apply();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationService.recreateIncomingCallChannel(this, ringtone);
            }
        }
        final Preference preference =
                mSettingsFragment.findPreference(AppSettings.RINGTONE);
        if (preference instanceof AppearanceChoicePreference) {
            updateCallRingtoneChoice((AppearanceChoicePreference) preference);
        }
    }

    private void openCallRingtoneFilePicker() {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        callRingtoneFilePicker.launch(intent);
    }

    private void updateCallRingtoneChoice(final AppearanceChoicePreference preference) {
        final Uri selected =
                PickRingtone.noneToNull(
                        currentRingtone(
                                AppSettings.RINGTONE,
                                R.string.incoming_call_ringtone));
        if (selected == null) {
            preference.setValueLabel(R.string.neocont_ringtone_silent);
            return;
        }
        if (Settings.System.DEFAULT_RINGTONE_URI.equals(selected)) {
            preference.setValueLabel(R.string.neocont_ringtone_default);
            return;
        }
        String title = null;
        try {
            final Ringtone ringtone = RingtoneManager.getRingtone(this, selected);
            title = ringtone == null ? null : ringtone.getTitle(this);
        } catch (final RuntimeException ignored) {
            // Fall through to the document display name.
        }
        if (Strings.isNullOrEmpty(title)) {
            title = queryDisplayName(selected);
        }
        preference.setValueLabel(
                Strings.isNullOrEmpty(title)
                        ? getText(R.string.neocont_ringtone_unknown)
                        : title);
    }

    private String queryDisplayName(final Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) {
            return null;
        }
        try (Cursor cursor =
                getContentResolver()
                        .query(
                                uri,
                                new String[] {OpenableColumns.DISPLAY_NAME},
                                null,
                                null,
                                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (final RuntimeException ignored) {
            // A stale or provider-owned URI should not break Settings.
        }
        return null;
    }

    private void configureCallPreferences() {
        final PreferenceCategory calls =
                (PreferenceCategory) mSettingsFragment.findPreference("neocont_call_options");
        if (calls == null) {
            return;
        }

        final Preference fullscreen =
                mSettingsFragment.findPreference("fullscreen_notification");
        if (fullscreen != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                fullscreen.setOnPreferenceClickListener(this::manageAppUseFullScreen);
            } else {
                calls.removePreference(fullscreen);
            }
        }

        final Preference callIntegration =
                mSettingsFragment.findPreference(AppSettings.CALL_INTEGRATION);
        if (callIntegration != null && !CallIntegration.selfManagedAvailable(this)) {
            calls.removePreference(callIntegration);
        }
    }

    private void reconfigureUpAccountPreference(final Preference preference) {
        final ListPreference listPreference;
        if (preference instanceof ListPreference) {
            listPreference = (ListPreference) preference;
        } else {
            return;
        }
        final List<CharSequence> accounts =
                ImmutableList.copyOf(
                        Lists.transform(
                                xmppConnectionService.getAccounts(),
                                a -> a.getJid().asBareJid().toString()));
        final ImmutableList.Builder<CharSequence> entries = new ImmutableList.Builder<>();
        final ImmutableList.Builder<CharSequence> entryValues = new ImmutableList.Builder<>();
        entries.add(getString(R.string.no_account_deactivated));
        entryValues.add("none");
        entries.addAll(accounts);
        entryValues.addAll(accounts);
        listPreference.setEntries(entries.build().toArray(new CharSequence[0]));
        listPreference.setEntryValues(entryValues.build().toArray(new CharSequence[0]));
        if (!accounts.contains(listPreference.getValue())) {
            listPreference.setValue("none");
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(this);

        changeOmemoSettingSummary();
        configureNeoContNavigation();
        configureDeveloperSettings();
        configureLicensePreferences();
        configureNeoContAppearancePreferences();
        configureNeoContChatPreferences();
        configureNeoContDataPreferences();
        configureNotificationPreferences();
        configureAppLockPreference();
        configureHighSecurityPreference();
        configureCallPreferences();
        configureRingtonePreferences();

        if (QuickConversationsService.isQuicksy()
                || QuickConversationsService.isPlayStoreFlavor()
                || Strings.isNullOrEmpty(Config.CHANNEL_DISCOVERY)) {
            final PreferenceCategory groupChats =
                    (PreferenceCategory) mSettingsFragment.findPreference("group_chats");
            final Preference channelDiscoveryMethod =
                    mSettingsFragment.findPreference("channel_discovery_method");
            if (groupChats != null && channelDiscoveryMethod != null) {
                groupChats.removePreference(channelDiscoveryMethod);
            }
        }

        if (QuickConversationsService.isQuicksy()) {
            final PreferenceCategory connectionOptions =
                    (PreferenceCategory) mSettingsFragment.findPreference("connection_options");
            PreferenceScreen expert = (PreferenceScreen) mSettingsFragment.findPreference("expert");
            if (connectionOptions != null && expert != null) {
                expert.removePreference(connectionOptions);
            }
        }

        PreferenceScreen mainPreferenceScreen =
                (PreferenceScreen) mSettingsFragment.findPreference("main_screen");

        final PreferenceGroup attachmentsCategory =
                (PreferenceGroup) mSettingsFragment.findPreference("attachments");
        final Preference legacyLocationPlugin =
                mSettingsFragment.findPreference("use_share_location_plugin");
        if (attachmentsCategory != null
                && legacyLocationPlugin != null
                && !GeoHelper.isLocationPluginInstalled(this)) {
            attachmentsCategory.removePreference(legacyLocationPlugin);
        }

        final var dynamicColors = (TwoStatePreference) mSettingsFragment.findPreference("dynamic_colors");

        if (dynamicColors != null) {
            if (!DynamicColors.isDynamicColorAvailable()) {
                PreferenceGroup notifications =
                        (PreferenceGroup) mSettingsFragment.findPreference("userinterface");
                if (notifications != null) {
                    notifications.removePreference(dynamicColors);
                }
            }
        }

        // this feature is only available on Huawei Android 6.
        PreferenceScreen huaweiPreferenceScreen =
                (PreferenceScreen) mSettingsFragment.findPreference("huawei");
        if (huaweiPreferenceScreen != null) {
            Intent intent = huaweiPreferenceScreen.getIntent();
            // remove when Api version is above M (Version 6.0) or if the intent is not callable
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M || !isCallable(intent)) {
                PreferenceGroup notifications =
                        (PreferenceGroup) mSettingsFragment.findPreference("notification_category");
                if (notifications != null) {
                    notifications.removePreference(huaweiPreferenceScreen);
                }
            }
        }

        final Preference automaticMessageDeletionPreference =
                mSettingsFragment.findPreference(AUTOMATIC_MESSAGE_DELETION);
        if (automaticMessageDeletionPreference instanceof ListPreference) {
            final ListPreference automaticMessageDeletionList =
                    (ListPreference) automaticMessageDeletionPreference;
            final int[] choices =
                    getResources().getIntArray(R.array.automatic_message_deletion_values);
            CharSequence[] entries = new CharSequence[choices.length];
            CharSequence[] entryValues = new CharSequence[choices.length];
            for (int i = 0; i < choices.length; ++i) {
                entryValues[i] = String.valueOf(choices[i]);
                if (choices[i] == 0) {
                    entries[i] = getString(R.string.never);
                } else {
                    entries[i] = TimeFrameUtils.resolve(this, 1000L * choices[i]);
                }
            }
            automaticMessageDeletionList.setEntries(entries);
            automaticMessageDeletionList.setEntryValues(entryValues);
        }

        boolean removeLocation =
                new Intent("eu.siacs.conversations.location.request")
                                .resolveActivity(getPackageManager())
                        == null;
        boolean removeVoice =
                new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
                                .resolveActivity(getPackageManager())
                        == null;

        ListPreference quickAction =
                (ListPreference) mSettingsFragment.findPreference("quick_action");
        if (quickAction != null && (removeLocation || removeVoice)) {
            ArrayList<CharSequence> entries =
                    new ArrayList<>(Arrays.asList(quickAction.getEntries()));
            ArrayList<CharSequence> entryValues =
                    new ArrayList<>(Arrays.asList(quickAction.getEntryValues()));
            int index = entryValues.indexOf("location");
            if (index > 0 && removeLocation) {
                entries.remove(index);
                entryValues.remove(index);
            }
            index = entryValues.indexOf("voice");
            if (index > 0 && removeVoice) {
                entries.remove(index);
                entryValues.remove(index);
            }
            quickAction.setEntries(entries.toArray(new CharSequence[entries.size()]));
            quickAction.setEntryValues(entryValues.toArray(new CharSequence[entryValues.size()]));
        }

        final Preference removeCertsPreference =
                mSettingsFragment.findPreference("remove_trusted_certificates");
        if (removeCertsPreference != null) {
            removeCertsPreference.setOnPreferenceClickListener(
                    preference -> {
                        final MemorizingTrustManager mtm =
                                xmppConnectionService.getMemorizingTrustManager();
                        final ArrayList<String> aliases = Collections.list(mtm.getCertificates());
                        if (aliases.size() == 0) {
                            displayToast(getString(R.string.toast_no_trusted_certs));
                            return true;
                        }
                        final ArrayList<Integer> selectedItems = new ArrayList<>();
                        final MaterialAlertDialogBuilder dialogBuilder =
                                new MaterialAlertDialogBuilder(SettingsActivity.this);
                        dialogBuilder.setTitle(
                                getResources().getString(R.string.dialog_manage_certs_title));
                        dialogBuilder.setMultiChoiceItems(
                                aliases.toArray(new CharSequence[aliases.size()]),
                                null,
                                (dialog, indexSelected, isChecked) -> {
                                    if (isChecked) {
                                        selectedItems.add(indexSelected);
                                    } else if (selectedItems.contains(indexSelected)) {
                                        selectedItems.remove(Integer.valueOf(indexSelected));
                                    }
                                    ((AlertDialog) dialog)
                                            .getButton(DialogInterface.BUTTON_POSITIVE)
                                            .setEnabled(selectedItems.size() > 0);
                                });

                        dialogBuilder.setPositiveButton(
                                getResources()
                                        .getString(R.string.dialog_manage_certs_positivebutton),
                                (dialog, which) -> {
                                    int count = selectedItems.size();
                                    if (count > 0) {
                                        for (int i = 0; i < count; i++) {
                                            try {
                                                Integer item =
                                                        Integer.valueOf(
                                                                selectedItems.get(i).toString());
                                                String alias = aliases.get(item);
                                                mtm.deleteCertificate(alias);
                                            } catch (KeyStoreException e) {
                                                e.printStackTrace();
                                                displayToast("Error: " + e.getLocalizedMessage());
                                            }
                                        }
                                        if (xmppConnectionServiceBound) {
                                            reconnectAccounts();
                                        }
                                        displayToast(
                                                getResources()
                                                        .getQuantityString(
                                                                R.plurals.toast_delete_certificates,
                                                                count,
                                                                count));
                                    }
                                });
                        dialogBuilder.setNegativeButton(
                                getResources()
                                        .getString(R.string.dialog_manage_certs_negativebutton),
                                null);
                        AlertDialog removeCertsDialog = dialogBuilder.create();
                        removeCertsDialog.show();
                        removeCertsDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                        return true;
                    });
        }

        final Preference exportOmemoIdentity =
                mSettingsFragment.findPreference("export_omemo_identity");
        if (exportOmemoIdentity != null) {
            exportOmemoIdentity.setOnPreferenceClickListener(
                    preference -> {
                        requestDeviceCredential(REQUEST_OMEMO_IDENTITY_EXPORT_AUTH);
                        return true;
                    });
        }

        final Preference importOmemoIdentity =
                mSettingsFragment.findPreference("import_omemo_identity");
        if (importOmemoIdentity != null) {
            importOmemoIdentity.setOnPreferenceClickListener(
                    preference -> {
                        requestDeviceCredential(REQUEST_OMEMO_IDENTITY_IMPORT_AUTH);
                        return true;
                    });
        }

        final Preference sendLogsPreference = mSettingsFragment.findPreference("send_logs");
        if (sendLogsPreference != null) {
            sendLogsPreference.setOnPreferenceClickListener(
                    preference -> {
                        final Intent intent = new Intent(this, SendLogActivity.class);
                        startActivity(intent);
                        return true;
                    });
        }

        final Preference secureMediaPerfPreference =
                mSettingsFragment.findPreference("secure_media_perf");
        if (secureMediaPerfPreference != null) {
            secureMediaPerfPreference.setOnPreferenceClickListener(
                    preference -> {
                        startActivity(new Intent(this, SecureMediaPerformanceActivity.class));
                        return true;
                    });
        }

        final Preference networkDiagnosticsPreference =
                mSettingsFragment.findPreference("network_diagnostics");
        if (networkDiagnosticsPreference != null) {
            networkDiagnosticsPreference.setOnPreferenceClickListener(
                    preference -> {
                        startActivity(new Intent(this, NetworkDiagnosticsActivity.class));
                        return true;
                    });
        }

        final Preference callDiagnosticsPreference =
                mSettingsFragment.findPreference("call_diagnostics");
        if (callDiagnosticsPreference != null) {
            callDiagnosticsPreference.setOnPreferenceClickListener(
                    preference -> {
                        startActivity(new Intent(this, CallDiagnosticsActivity.class));
                        return true;
                    });
        }

        if (Config.ONLY_INTERNAL_STORAGE) {
            final Preference cleanCachePreference = mSettingsFragment.findPreference("clean_cache");
            if (cleanCachePreference != null) {
                cleanCachePreference.setOnPreferenceClickListener(preference -> cleanCache());
            }

            final Preference cleanPrivateStoragePreference =
                    mSettingsFragment.findPreference("clean_private_storage");
            if (cleanPrivateStoragePreference != null) {
                cleanPrivateStoragePreference.setOnPreferenceClickListener(
                        preference -> cleanPrivateStorage());
            }
        }

        final Preference deleteOmemoPreference =
                mSettingsFragment.findPreference("delete_omemo_identities");
        if (deleteOmemoPreference != null) {
            deleteOmemoPreference.setOnPreferenceClickListener(
                    preference -> deleteOmemoIdentities());
        }
        if (Config.omemoOnly()) {
            final PreferenceCategory privacyCategory =
                    (PreferenceCategory) mSettingsFragment.findPreference("privacy");
            final Preference omemoPreference =mSettingsFragment.findPreference(OMEMO_SETTING);
            if (omemoPreference != null && privacyCategory != null) {
                privacyCategory.removePreference(omemoPreference);
            }
        }

        final Preference importBackgroundPreference = mSettingsFragment.findPreference("import_background");
        if (importBackgroundPreference != null) {
            importBackgroundPreference.setSummary(getString(R.string.pref_chat_background_summary));
            importBackgroundPreference.setOnPreferenceClickListener(preference -> {
                if (hasStoragePermission(ChatBackgroundHelper.REQUEST_IMPORT_BACKGROUND)) {
                    ChatBackgroundHelper.openBGPicker(this);
                }
                return true;
            });
        }

        final Preference deleteBackgroundPreference = mSettingsFragment.findPreference("delete_background");
        if (deleteBackgroundPreference != null) {
            deleteBackgroundPreference.setSummary(getString(R.string.pref_delete_background_summary));
            deleteBackgroundPreference.setOnPreferenceClickListener(preference -> {
                try {
                    File bgfile =  ChatBackgroundHelper.getBgFile(this, null);
                    if (bgfile.exists()) {
                        bgfile.delete();
                        Toast.makeText(this,R.string.delete_background_success,Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this,R.string.no_background_set,Toast.LENGTH_LONG).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this,R.string.delete_background_failed,Toast.LENGTH_LONG).show();
                    throw new RuntimeException(e);
                }
                return true;
            });
        }
    }

    private void changeOmemoSettingSummary() {
        final ListPreference omemoPreference =
                (ListPreference) mSettingsFragment.findPreference(OMEMO_SETTING);
        if (omemoPreference == null) {
            return;
        }
        final String value = omemoPreference.getValue();
        switch (value) {
            case "always":
                omemoPreference.setSummary(R.string.pref_omemo_setting_summary_always);
                break;
            case "default_on":
                omemoPreference.setSummary(R.string.pref_omemo_setting_summary_default_on);
                break;
            case "default_off":
                omemoPreference.setSummary(R.string.pref_omemo_setting_summary_default_off);
                break;
        }
    }

    private boolean isCallable(final Intent i) {
        return i != null
                && getPackageManager()
                                .queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)
                                .size()
                        > 0;
    }

    private void configureLocalAccountDataCleanup() {
        final Preference preference =
                mSettingsFragment.findPreference("clear_local_account_data");
        if (preference == null) {
            return;
        }
        preference.setOnPreferenceClickListener(
                ignored -> {
                    final List<Account> accounts =
                            new ArrayList<>(xmppConnectionService.getAccounts());
                    if (accounts.isEmpty()) {
                        displayToast(getString(R.string.clear_local_account_data_no_accounts));
                    } else if (accounts.size() == 1) {
                        confirmLocalAccountDataCleanup(accounts.get(0), preference);
                    } else {
                        AccountChoiceBottomSheet.show(
                                this,
                                xmppConnectionService,
                                R.string.clear_local_account_data_choose_account,
                                accounts,
                                account ->
                                        confirmLocalAccountDataCleanup(
                                                account, preference));
                    }
                    return true;
                });
    }

    private void confirmLocalAccountDataCleanup(
            final Account account, final Preference preference) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_local_account_data_confirm_title)
                .setMessage(
                        getString(
                                R.string.clear_local_account_data_confirm_message,
                                account.getJid().asBareJid().toString()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.clear_local_account_data_positive,
                        (dialog, which) -> runLocalAccountDataCleanup(account, preference))
                .show();
    }

    private void runLocalAccountDataCleanup(
            final Account account, final Preference preference) {
        preference.setEnabled(false);
        xmppConnectionService.clearLocalAccountData(
                account,
                success ->
                        runOnUiThread(
                                () -> {
                                    preference.setEnabled(true);
                                    displayToast(
                                            getString(
                                                    success
                                                            ? R.string.clear_local_account_data_success
                                                            : R.string.clear_local_account_data_failure));
                                    if (success) {
                                        refreshLocalStorageUsage();
                                    }
                                }));
    }

    private void refreshLocalStorageUsage() {
        final Preference rawTotal = mSettingsFragment.findPreference("local_storage_usage");
        final Preference rawCategory = mSettingsFragment.findPreference("neocont_storage_actions");
        if (!(rawTotal instanceof AppearanceChoicePreference)
                || !(rawCategory instanceof PreferenceCategory)
                || xmppConnectionService == null
                || !(getApplication() instanceof Conversations)) {
            return;
        }

        final AppearanceChoicePreference totalPreference =
                (AppearanceChoicePreference) rawTotal;
        final PreferenceCategory category = (PreferenceCategory) rawCategory;
        totalPreference.setChoiceAvailable(false);
        totalPreference.setValueLabel(R.string.neocont_storage_calculating);

        final List<Account> accounts = new ArrayList<>(xmppConnectionService.getAccounts());
        new Thread(
                        () -> {
                            final List<Long> sizes = new ArrayList<>(accounts.size());
                            final Conversations application = (Conversations) getApplication();
                            final var store = application.getSecureContentStoreProvider().get();
                            long total = 0L;
                            boolean allMeasured = true;
                            for (final Account account : accounts) {
                                final Long measured =
                                        store.committedStorageSizeBytes(account.getUuid());
                                sizes.add(measured);
                                if (measured == null) {
                                    allMeasured = false;
                                    continue;
                                }
                                final long size = Math.max(0L, measured);
                                total =
                                        Long.MAX_VALUE - total < size
                                                ? Long.MAX_VALUE
                                                : total + size;
                            }
                            final Long totalBytes = allMeasured ? total : null;
                            runOnUiThread(
                                    () ->
                                            renderSecureStorageUsage(
                                                    category,
                                                    totalPreference,
                                                    accounts,
                                                    sizes,
                                                    totalBytes));
                        },
                        "SecureStorageUsage")
                .start();
    }

    private void renderSecureStorageUsage(
            final PreferenceCategory category,
            final AppearanceChoicePreference totalPreference,
            final List<Account> accounts,
            final List<Long> sizes,
            @Nullable final Long totalBytes) {
        for (int i = category.getPreferenceCount() - 1; i >= 0; --i) {
            final Preference child = category.getPreference(i);
            if (child.getKey() != null && child.getKey().startsWith("scs_account_usage_")) {
                category.removePreference(child);
            }
        }

        totalPreference.setValueLabel(
                totalBytes == null
                        ? getString(R.string.neocont_storage_unavailable)
                        : formatSecureStorageMegabytes(totalBytes));
        totalPreference.setVisible(accounts.size() > 1);

        for (int i = 0; i < accounts.size(); ++i) {
            final Account account = accounts.get(i);
            final SecureStorageAccountPreference row =
                    new SecureStorageAccountPreference(this);
            row.setKey("scs_account_usage_" + account.getUuid());
            row.setOrder(30 + i);
            row.setTitle(account.getJid().asBareJid().toString());
            row.setAccountColor(UIHelper.getAccountColor(this, account.getJid()));
            final Long accountBytes = sizes.get(i);
            row.setSizeLabel(
                    accountBytes == null
                            ? getString(R.string.neocont_storage_unavailable)
                            : formatSecureStorageMegabytes(accountBytes));
            category.addPreference(row);
        }
    }

    private String formatSecureStorageMegabytes(final long bytes) {
        final long megabytes = Math.round(Math.max(0L, bytes) / (1024.0d * 1024.0d));
        return getString(R.string.neocont_storage_megabytes, megabytes);
    }

    private boolean cleanCache() {
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
        return true;
    }

    private boolean cleanPrivateStorage() {
        for (String type : Arrays.asList("Images", "Videos", "Files", "Recordings")) {
            cleanPrivateFiles(type);
        }
        return true;
    }

    private void cleanPrivateFiles(final String type) {
        try {
            File dir = new File(getFilesDir().getAbsolutePath(), "/" + type + "/");
            File[] array = dir.listFiles();
            if (array != null) {
                for (int b = 0; b < array.length; b++) {
                    String name = array[b].getName().toLowerCase();
                    if (name.equals(".nomedia")) {
                        continue;
                    }
                    if (array[b].isFile()) {
                        array[b].delete();
                    }
                }
            }
        } catch (Throwable e) {
            Log.e("CleanCache", e.toString());
        }
    }

    private boolean deleteOmemoIdentities() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(R.string.pref_delete_omemo_identities);
        final List<CharSequence> accounts = new ArrayList<>();
        for (Account account : xmppConnectionService.getAccounts()) {
            if (account.isEnabled()) {
                accounts.add(account.getJid().asBareJid().toString());
            }
        }
        final boolean[] checkedItems = new boolean[accounts.size()];
        builder.setMultiChoiceItems(
                accounts.toArray(new CharSequence[accounts.size()]),
                checkedItems,
                (dialog, which, isChecked) -> {
                    checkedItems[which] = isChecked;
                    final AlertDialog alertDialog = (AlertDialog) dialog;
                    for (boolean item : checkedItems) {
                        if (item) {
                            alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
                            return;
                        }
                    }
                    alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);
                });
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(
                R.string.delete_selected_keys,
                (dialog, which) -> {
                    for (int i = 0; i < checkedItems.length; ++i) {
                        if (checkedItems[i]) {
                            try {
                                Jid jid = Jid.of(accounts.get(i).toString());
                                Account account = xmppConnectionService.findAccountByJid(jid);
                                if (account != null) {
                                    account.getAxolotlService().regenerateKeys(true);
                                }
                            } catch (IllegalArgumentException e) {
                                //
                            }
                        }
                    }
                });
        AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        return true;
    }

    @Override
    public void onStop() {
        super.onStop();
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, String name) {
        final List<String> resendPresence =
                Arrays.asList(
                        "confirm_messages",
                        "allow_message_correction",
                        BROADCAST_LAST_ACTIVITY);
        if (name.equals(OMEMO_SETTING)) {
            OmemoSetting.load(this);
            changeOmemoSettingSummary();
        } else if (name.equals(KEEP_FOREGROUND_SERVICE)) {
            xmppConnectionService.toggleForegroundService();
        } else if (resendPresence.contains(name)) {
            if (xmppConnectionServiceBound) {
                xmppConnectionService.refreshAllPresences();
            }
        } else if (name.equals("dont_trust_system_cas")) {
            xmppConnectionService.updateMemorizingTrustManager();
            reconnectAccounts();
        } else if (name.equals("use_tor")) {
            if (preferences.getBoolean(name, false)) {
                displayToast(getString(R.string.audio_video_disabled_tor));
            }
            reconnectAccounts();
            xmppConnectionService.reinitializeMuclumbusService();
        } else if (name.equals(AUTOMATIC_MESSAGE_DELETION)) {
            xmppConnectionService.expireOldMessages(true);
        } else if (name.equals(THEME)) {
            ChatWallpaperPresets.resetProcessPrewarm();
            try {
                final var appearanceState = AppearanceSnapshotReader.INSTANCE.from(this);
                final var applyPlan = AppearanceApplier.INSTANCE.plan(appearanceState);
                if (this.mTheme != applyPlan.getBaseThemeStyle()
                        || appearanceState.getSettings().getThemeMode() == ThemeMode.SYSTEM) {
                    recreate();
                }
            } catch (final RuntimeException e) {
                final int theme = findTheme();
                if (this.mTheme != theme || ThemeHelper.isAutomatic(this)) {
                    recreate();
                }
            }
        } else if (name.equals(THEME_OVERRIDE_COLOR) && !applyingAppearanceColor) {
            ChatWallpaperPresets.resetProcessPrewarm();
            try {
                final var applyPlan = AppearanceApplier.INSTANCE.plan(AppearanceSnapshotReader.INSTANCE.from(this));
                if (!Objects.equals(this.mThemeOverrideStyle, applyPlan.getOverrideThemeStyle())) {
                    recreate();
                }
            } catch (final RuntimeException e) {
                final Integer currentOverrideStyle = ThemeHelper.findThemeOverrideStyle(this);
                if (!Objects.equals(this.mThemeOverrideStyle, currentOverrideStyle)) {
                    recreate();
                }
            }
        } else if (name.equals("message_text_size_sp") || name.equals("text_scale")) {
            refreshAppearancePreview();
        } else if (name.equals(PREVENT_SCREENSHOTS)) {
            SettingsUtils.applyScreenshotSetting(this);
        } else if (UnifiedPushDistributor.PREFERENCES.contains(name)) {
            final String pushServerPreference =
                    Strings.nullToEmpty(preferences.getString(
                            UnifiedPushDistributor.PREFERENCE_PUSH_SERVER,
                            getString(R.string.default_push_server))).trim();
            if (isJidInvalid(pushServerPreference) || isHttpUri(pushServerPreference)) {
                Toast.makeText(this,R.string.invalid_jid,Toast.LENGTH_LONG).show();
            }
            if (xmppConnectionService.reconfigurePushDistributor()) {
                xmppConnectionService.renewUnifiedPushEndpoints();
            }
        } else if (name.equals(SHOW_DYNAMIC_TAGS) || name.equals(GROUP_BY_TAGS)) {
            boolean dynamicTagsEnabled = preferences.getBoolean(SHOW_DYNAMIC_TAGS, false);
            boolean groupByTags = preferences.getBoolean(GROUP_BY_TAGS, false);

            if (name.equals(SHOW_DYNAMIC_TAGS) && !dynamicTagsEnabled && groupByTags) {
                preferences.edit().putBoolean(GROUP_BY_TAGS, false).apply();
                Preference preference = mSettingsFragment.findPreference(GROUP_BY_TAGS);
                if (preference instanceof TwoStatePreference) {
                    ((TwoStatePreference) preference).setChecked(false);
                }
            }

            if (name.equals(GROUP_BY_TAGS) && !dynamicTagsEnabled && groupByTags) {
                preferences.edit().putBoolean(SHOW_DYNAMIC_TAGS, true).apply();
                Preference preference = mSettingsFragment.findPreference(SHOW_DYNAMIC_TAGS);
                if (preference instanceof TwoStatePreference) {
                    ((TwoStatePreference) preference).setChecked(true);
                }
            }
        }
    }

    private static boolean isJidInvalid(final String input) {
        if (Strings.isNullOrEmpty(input)) {
            return true;
        }
        try {
            Jid.of(input);
            return false;
        } catch (final IllegalArgumentException e) {
            return true;
        }
    }

    private static boolean isHttpUri(final String input) {
        final URI uri;
        try {
            uri = new URI(input);
        } catch (final URISyntaxException e) {
            return false;
        }
        return Arrays.asList("http","https").contains(uri.getScheme());
    }

    @Override
    public void onResume() {
        super.onResume();
        SettingsUtils.applyScreenshotSetting(this);
        configureDeveloperSettings();
        configureHighSecurityPreference();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_OMEMO_IDENTITY_EXPORT_AUTH) {
            if (resultCode == RESULT_OK) {
                pendingOmemoIdentityExportAfterAuth = true;
                continuePendingOmemoIdentityAction();
            }
            return;
        }
        if (requestCode == REQUEST_OMEMO_IDENTITY_IMPORT_AUTH) {
            if (resultCode == RESULT_OK) {
                openOmemoIdentityBackup();
            }
            return;
        }
        if (requestCode == REQUEST_OMEMO_IDENTITY_EXPORT_DOCUMENT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                pendingOmemoIdentityExportUri = data.getData();
                continuePendingOmemoIdentityAction();
            }
            return;
        }
        if (requestCode == REQUEST_OMEMO_IDENTITY_IMPORT_DOCUMENT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                inspectOmemoIdentityBackup(data.getData());
            }
            return;
        }
        if (requestCode == REQUEST_APP_LOCK_POLICY_AUTH) {
            AppLockController.endTrustedExternalAuthentication(this);
            if (resultCode == RESULT_OK && pendingAppLockEnabled != null) {
                AppLockController.setEnabledAfterAuthentication(
                        this,
                        pendingAppLockEnabled.booleanValue());
                final TwoStatePreference preference =
                        (TwoStatePreference) mSettingsFragment.findPreference(
                                AppLockController.PREFERENCE_ENABLED);
                if (preference != null) {
                    preference.setChecked(pendingAppLockEnabled.booleanValue());
                }
            }
            pendingAppLockEnabled = null;
            return;
        }
        ChatBackgroundHelper.onActivityResult(this, requestCode, resultCode, data, null);
        if (requestCode == ChatBackgroundHelper.REQUEST_IMPORT_BACKGROUND
                && resultCode == RESULT_OK) {
            final AppearanceChoicePreference wallpaper =
                    (AppearanceChoicePreference)
                            mSettingsFragment.findPreference("chat_wallpaper");
            if (wallpaper != null) {
                updateChatWallpaperChoice(wallpaper);
                refreshAppearancePreview();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0)
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ChatBackgroundHelper.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
            } else {
                Toast.makeText(
                                this,
                                getString(
                                        R.string.no_storage_permission,
                                        getString(R.string.app_name)),
                                Toast.LENGTH_SHORT)
                        .show();
            }
    }

    private boolean requestDeviceCredential(final int requestCode) {
        final KeyguardManager keyguardManager =
                (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguardManager == null || !keyguardManager.isKeyguardSecure()) {
            displayToast(getString(R.string.omemo_identity_backup_requires_screen_lock));
            return false;
        }
        final Intent intent =
                keyguardManager.createConfirmDeviceCredentialIntent(
                        getString(R.string.omemo_identity_backup_auth_title),
                        getString(R.string.omemo_identity_backup_auth_text));
        if (intent == null) {
            displayToast(getString(R.string.omemo_identity_backup_auth_unavailable));
            return false;
        }
        startActivityForResult(intent, requestCode);
        return true;
    }

    private void continuePendingOmemoIdentityAction() {
        if (!xmppConnectionServiceBound || xmppConnectionService == null) {
            // connectToBackend() is driven by XmppActivity.onStart(). Nothing is wrong with the
            // account; we simply have to wait for ServiceConnection.onServiceConnected().
            return;
        }

        if (pendingOmemoIdentityExportAfterAuth) {
            pendingOmemoIdentityExportAfterAuth = false;
            selectAccountForOmemoIdentityExport();
            return;
        }

        if (pendingOmemoIdentityExportUri != null) {
            final Uri uri = pendingOmemoIdentityExportUri;
            pendingOmemoIdentityExportUri = null;
            exportOmemoIdentity(uri);
            return;
        }

        if (pendingOmemoIdentityRestoreUri != null && pendingOmemoIdentityRestoreHeader != null) {
            final Uri uri = pendingOmemoIdentityRestoreUri;
            final OmemoIdentityBackup.Header header = pendingOmemoIdentityRestoreHeader;
            pendingOmemoIdentityRestoreUri = null;
            pendingOmemoIdentityRestoreHeader = null;
            confirmOmemoIdentityRestore(uri, header);
        }
    }

    private void selectAccountForOmemoIdentityExport() {
        if (!xmppConnectionServiceBound || xmppConnectionService == null) {
            pendingOmemoIdentityExportAfterAuth = true;
            return;
        }
        final List<Account> accounts = new ArrayList<>(xmppConnectionService.getAccounts());
        if (accounts.isEmpty()) {
            displayToast(getString(R.string.omemo_identity_backup_no_account));
            return;
        }
        if (accounts.size() == 1) {
            createOmemoIdentityBackupDocument(accounts.get(0));
            return;
        }
        AccountChoiceBottomSheet.show(
                this,
                xmppConnectionService,
                R.string.omemo_identity_backup_choose_account,
                accounts,
                this::createOmemoIdentityBackupDocument);
    }

    private void createOmemoIdentityBackupDocument(final Account account) {
        pendingOmemoIdentityExportJid = account.getJid().asBareJid();
        final Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(OmemoIdentityBackup.MIME_TYPE);
        intent.putExtra(Intent.EXTRA_TITLE, OmemoIdentityBackup.suggestedFilename(account));
        startActivityForResult(intent, REQUEST_OMEMO_IDENTITY_EXPORT_DOCUMENT);
    }

    private void openOmemoIdentityBackup() {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_OMEMO_IDENTITY_IMPORT_DOCUMENT);
    }

    private Account findAccountForOmemoBackup(final Jid jid) {
        if (!xmppConnectionServiceBound || xmppConnectionService == null || jid == null) {
            return null;
        }
        return xmppConnectionService.findAccountByJid(jid.asBareJid());
    }

    private void exportOmemoIdentity(final Uri uri) {
        if (!xmppConnectionServiceBound || xmppConnectionService == null) {
            pendingOmemoIdentityExportUri = uri;
            return;
        }
        final Account account = findAccountForOmemoBackup(pendingOmemoIdentityExportJid);
        if (account == null || account.getAxolotlService() == null) {
            displayToast(getString(R.string.omemo_identity_backup_no_account));
            return;
        }
        pendingOmemoIdentityExportJid = null;
        final byte[] serializedIdentity = account.getAxolotlService().exportIdentityKeyPair();
        final String fingerprint = account.getAxolotlService().getOwnFingerprint();
        new Thread(
                        () -> {
                            try {
                                OmemoIdentityBackup.exportBackup(
                                        SettingsActivity.this, account, uri, serializedIdentity);
                                runOnUiThread(
                                        () ->
                                                new MaterialAlertDialogBuilder(SettingsActivity.this)
                                                        .setTitle(
                                                                R.string.omemo_identity_backup_created_title)
                                                        .setMessage(
                                                                getString(
                                                                        R.string.omemo_identity_backup_created_text,
                                                                        formatFingerprintForDisplay(fingerprint)))
                                                        .setPositiveButton(R.string.ok, null)
                                                        .show());
                            } catch (final Exception e) {
                                Log.e(Config.LOGTAG, "unable to export OMEMO identity backup", e);
                                runOnUiThread(
                                        () ->
                                                displayToast(
                                                        getString(
                                                                R.string.omemo_identity_backup_export_failed)));
                            }
                        },
                        "OmemoIdentityExport")
                .start();
    }

    private void inspectOmemoIdentityBackup(final Uri uri) {
        new Thread(
                        () -> {
                            try {
                                final OmemoIdentityBackup.Header header =
                                        OmemoIdentityBackup.readHeader(SettingsActivity.this, uri);
                                runOnUiThread(() -> confirmOmemoIdentityRestore(uri, header));
                            } catch (final Exception e) {
                                Log.e(Config.LOGTAG, "unable to read OMEMO identity backup", e);
                                runOnUiThread(
                                        () ->
                                                displayToast(
                                                        getString(
                                                                R.string.omemo_identity_backup_invalid_file)));
                            }
                        },
                        "OmemoIdentityInspect")
                .start();
    }

    private void confirmOmemoIdentityRestore(
            final Uri uri, final OmemoIdentityBackup.Header header) {
        if (!xmppConnectionServiceBound || xmppConnectionService == null) {
            pendingOmemoIdentityRestoreUri = uri;
            pendingOmemoIdentityRestoreHeader = header;
            return;
        }
        final Account account = findAccountForOmemoBackup(header.jid);
        if (account == null) {
            displayToast(
                    getString(
                            R.string.omemo_identity_backup_account_missing,
                            header.jid.asBareJid().toString()));
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.omemo_identity_backup_restore_title)
                .setMessage(
                        getString(
                                R.string.omemo_identity_backup_restore_warning,
                                header.jid.asBareJid().toString()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.restore_backup,
                        (dialog, which) -> restoreOmemoIdentity(account, uri))
                .show();
    }

    private void restoreOmemoIdentity(final Account account, final Uri uri) {
        new Thread(
                        () -> {
                            try {
                                final OmemoIdentityBackup.RestoredIdentity restored =
                                        OmemoIdentityBackup.readBackup(
                                                SettingsActivity.this, account, uri);
                                runOnUiThread(
                                        () -> applyRestoredOmemoIdentity(account, restored));
                            } catch (final OmemoIdentityBackup.WrongPasswordOrCorruptBackupException e) {
                                Log.w(Config.LOGTAG, "unable to decrypt OMEMO identity backup", e);
                                runOnUiThread(
                                        () ->
                                                displayToast(
                                                        getString(
                                                                R.string.omemo_identity_backup_decrypt_failed)));
                            } catch (final GeneralSecurityException | java.io.IOException e) {
                                Log.e(Config.LOGTAG, "unable to restore OMEMO identity backup", e);
                                runOnUiThread(
                                        () ->
                                                displayToast(
                                                        getString(
                                                                R.string.omemo_identity_backup_restore_failed)));
                            }
                        },
                        "OmemoIdentityImport")
                .start();
    }

    private void applyRestoredOmemoIdentity(
            final Account account, final OmemoIdentityBackup.RestoredIdentity restored) {
        if (!xmppConnectionServiceBound
                || xmppConnectionService == null
                || account.getAxolotlService() == null) {
            displayToast(getString(R.string.omemo_identity_backup_service_unavailable));
            return;
        }
        try {
            final String fingerprint =
                    account.getAxolotlService().restoreIdentityKeyPair(restored.identityKeyPair);
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.omemo_identity_backup_restored_title)
                    .setMessage(
                            getString(
                                    R.string.omemo_identity_backup_restored_text,
                                    formatFingerprintForDisplay(fingerprint)))
                    .setPositiveButton(R.string.ok, null)
                    .show();
        } catch (final RuntimeException e) {
            Log.e(Config.LOGTAG, "unable to apply restored OMEMO identity", e);
            displayToast(getString(R.string.omemo_identity_backup_restore_failed));
        }
    }

    private static String formatFingerprintForDisplay(final String fingerprint) {
        if (fingerprint == null) {
            return "";
        }
        final String normalized = fingerprint.replaceAll("[^0-9A-Fa-f]", "");
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < normalized.length(); i += 8) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(normalized, i, Math.min(i + 8, normalized.length()));
        }
        return builder.toString();
    }

    private void displayToast(final String msg) {
        runOnUiThread(() -> Toast.makeText(SettingsActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    private boolean manageAppUseFullScreen(final Preference preference) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false;
        }
        final var intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
        intent.setData(Uri.parse(String.format("package:%s", this.getPackageName())));
        try {
            startActivity(intent);
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(this, R.string.unsupported_operation, Toast.LENGTH_SHORT)
                    .show();
            return false;
        }
        return true;
    }

    private void reconnectAccounts() {
        for (Account account : xmppConnectionService.getAccounts()) {
            if (account.isEnabled()) {
                xmppConnectionService.reconnectAccountInBackground(account);
            }
        }
    }

    public void refreshUiReal() {
        // nothing to do. This Activity doesn't implement any listeners
    }
}
