package eu.siacs.conversations.ui;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceManager;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.services.CallIntegration;
import eu.siacs.conversations.utils.Compatibility;

public class SettingsFragment extends PreferenceFragmentCompat {

    public static final String EXTRA_PAGE = "page";
    public static final String EXTRA_LEGACY = "legacy";

    private String page = null;
    private boolean legacyMode = false;

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        final Intent activityIntent = getActivity() == null ? null : getActivity().getIntent();
        readActivityIntent(activityIntent);

        // Legacy PreferenceScreen intents predate the current settings hierarchy and carry their
        // old page key without EXTRA_LEGACY. Current pages are namespaced with "neocont_", so an
        // unnamespaced page targeted at SettingsActivity must keep using the legacy preference tree.
        if (!legacyMode && isLegacyPage(page)) {
            legacyMode = true;
        }

        setPreferencesFromResource(
                legacyMode ? R.xml.preferences : R.xml.preferences_neocont, rootKey);

        if (legacyMode) {
            if (!Config.ONLY_INTERNAL_STORAGE) {
                final PreferenceCategory category =
                        findPreference("security_options");
                if (category != null) {
                    final Preference cleanCache = findPreference("clean_cache");
                    final Preference cleanPrivateStorage =
                            findPreference("clean_private_storage");
                    if (cleanCache != null) {
                        category.removePreference(cleanCache);
                    }
                    if (cleanPrivateStorage != null) {
                        category.removePreference(cleanPrivateStorage);
                    }
                }
            }
        } else if (!Config.ONLY_INTERNAL_STORAGE) {
            final PreferenceCategory storageActions =
                    findPreference("neocont_storage_actions");
            if (storageActions != null) {
                final Preference cleanCache = findPreference("clean_cache");
                final Preference cleanPrivateStorage =
                        findPreference("clean_private_storage");
                if (cleanCache != null) {
                    storageActions.removePreference(cleanCache);
                }
                if (cleanPrivateStorage != null) {
                    storageActions.removePreference(cleanPrivateStorage);
                }
            }
        }

        // Apply the same Android-version-specific notification cleanup to both
        // the legacy and the modern settings trees. On Android 8+ notification
        // sound, vibration and importance belong to the system notification channel;
        // on older Android versions the channel-settings row is unavailable.
        Compatibility.removeUnusedPreferences(this);
        if (!legacyMode) {
            removeUnavailableModernPreferences();
        }

        if (!TextUtils.isEmpty(page)) {
            openPreferenceScreen(page);
        }
    }

    private void removeUnavailableModernPreferences() {
        final PreferenceGroup messages = findPreference("neocont_message_notifications");
        if (messages != null) {
            final Preference systemSettings = findPreference("message_notification_settings");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                removePreference(messages, findPreference(AppSettings.NOTIFICATION_HEADS_UP));
                removePreference(messages, findPreference(AppSettings.NOTIFICATION_VIBRATE));
                removePreference(messages, findPreference(AppSettings.NOTIFICATION_RINGTONE));
            } else {
                removePreference(messages, systemSettings);
            }
        }

        final PreferenceGroup calls = findPreference("neocont_call_options");
        if (calls != null) {
            final Preference fullscreen = findPreference("fullscreen_notification");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                removePreference(calls, fullscreen);
            }
            if (getContext() != null && !CallIntegration.selfManagedAvailable(getContext())) {
                removePreference(calls, findPreference(AppSettings.CALL_INTEGRATION));
            }
        }
    }

    private static void removePreference(
            final PreferenceGroup group, final Preference preference) {
        if (preference != null) {
            group.removePreference(preference);
        }
    }

    public boolean isLegacyMode() {
        return legacyMode;
    }

    public boolean isMainPage() {
        return !legacyMode && TextUtils.isEmpty(page);
    }

    public void setActivityIntent(final Intent intent) {
        final boolean wasEmpty = TextUtils.isEmpty(page);
        readActivityIntent(intent);
        if (getPreferenceScreen() != null && wasEmpty && !TextUtils.isEmpty(page)) {
            openPreferenceScreen(page);
        }
    }

    private void readActivityIntent(final Intent intent) {
        if (intent == null) {
            return;
        }
        legacyMode = intent.getBooleanExtra(EXTRA_LEGACY, legacyMode);
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getExtras() != null) {
            page = intent.getExtras().getString(EXTRA_PAGE);
            if (!legacyMode && isLegacyPage(page)) {
                legacyMode = true;
            }
        }
    }

    private static boolean isLegacyPage(final String page) {
        return !TextUtils.isEmpty(page) && !page.startsWith("neocont_");
    }

    private void openPreferenceScreen(final String screenName) {
        if (TextUtils.isEmpty(screenName)) {
            return;
        }
        if ("neocont_advanced".equals(screenName)
                && getContext() != null
                && !PreferenceManager.getDefaultSharedPreferences(getContext())
                        .getBoolean(SettingsActivity.PREFERENCE_DEVELOPER_MODE, false)) {
            return;
        }
        final Preference preference = findPreference(screenName);
        if (!(preference instanceof PreferenceScreen)) {
            return;
        }
        final PreferenceScreen preferenceScreen = (PreferenceScreen) preference;
        if (getActivity() != null) {
            getActivity().setTitle(preferenceScreen.getTitle());
        }
        setPreferenceScreen(preferenceScreen);
    }
}
