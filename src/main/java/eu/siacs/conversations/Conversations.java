package eu.siacs.conversations;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

import eu.siacs.conversations.services.EmojiInitializationService;
import eu.siacs.conversations.security.applock.AppLockController;
import eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionRuntimeV1;
import eu.siacs.conversations.storage.secure.SecureColdStartPerfRuntime;
import eu.siacs.conversations.storage.secure.SecureColdStartPerfTrace;
import eu.siacs.conversations.storage.secure.SecureContentAccountRegistry;
import eu.siacs.conversations.storage.secure.SecureContentStoreProvider;
import eu.siacs.conversations.storage.secure.SecureMessageSearchRuntime;
import eu.siacs.conversations.storage.secure.SecureMediaPerfRuntime;
import eu.siacs.conversations.xmpp.jingle.CallDiagnosticsRuntime;
import eu.siacs.conversations.utils.ExceptionHelper;
import eu.siacs.conversations.utils.ThemeHelper;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class Conversations extends Application {

    @SuppressLint("StaticFieldLeak")
    private static Context CONTEXT;
    private static final long UI_BACKGROUND_DEBOUNCE_MS = 700L;

    public interface UiStateListener {
        void onUiForegroundChanged(boolean foreground);
    }

    private SecureContentStoreProvider secureContentStoreProvider;
    private SecureContentAccountRegistry secureContentAccountRegistry;
    private final Handler uiStateHandler = new Handler(Looper.getMainLooper());
    private final Set<UiStateListener> uiStateListeners = new CopyOnWriteArraySet<>();
    private int startedActivityCount = 0;
    private boolean uiInForeground = false;
    private final Runnable markUiBackground =
            () -> {
                final boolean changed;
                synchronized (this) {
                    changed = startedActivityCount == 0 && uiInForeground;
                    if (changed) {
                        uiInForeground = false;
                    }
                }
                if (changed) {
                    notifyUiStateListeners(false);
                }
            };

    public static Context getContext() {
        return Conversations.CONTEXT;
    }

    /**
     * Returns the process-owned lazy Secure Content composition point.
     *
     * Callers must obtain its runtime Store from an I/O/background worker because first use can
     * perform secure-store recovery.
     */
    public SecureContentStoreProvider getSecureContentStoreProvider() {
        return secureContentStoreProvider;
    }

    /** Returns the process-owned membership authority for Secure Content Store. */
    public SecureContentAccountRegistry getSecureContentAccountRegistry() {
        return secureContentAccountRegistry;
    }

    public synchronized boolean isUiInForeground() {
        return uiInForeground;
    }

    public void addUiStateListener(final UiStateListener listener) {
        if (listener != null) {
            uiStateListeners.add(listener);
        }
    }

    public void removeUiStateListener(final UiStateListener listener) {
        if (listener != null) {
            uiStateListeners.remove(listener);
        }
    }

    private void notifyUiStateListeners(final boolean foreground) {
        for (final UiStateListener listener : uiStateListeners) {
            listener.onUiForegroundChanged(foreground);
        }
    }

    private void initializeUiStateTracking() {
        registerActivityLifecycleCallbacks(
                new ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

                    @Override
                    public void onActivityStarted(Activity activity) {
                        uiStateHandler.removeCallbacks(markUiBackground);
                        final boolean changed;
                        synchronized (Conversations.this) {
                            startedActivityCount++;
                            changed = !uiInForeground;
                            if (changed) {
                                uiInForeground = true;
                            }
                        }
                        if (changed) {
                            notifyUiStateListeners(true);
                        }
                    }

                    @Override
                    public void onActivityResumed(Activity activity) {}

                    @Override
                    public void onActivityPaused(Activity activity) {}

                    @Override
                    public void onActivityStopped(Activity activity) {
                        synchronized (Conversations.this) {
                            if (startedActivityCount > 0) {
                                startedActivityCount--;
                            }
                            if (startedActivityCount != 0) {
                                return;
                            }
                        }
                        // Coalesce activity-to-activity transitions and configuration changes.
                        uiStateHandler.removeCallbacks(markUiBackground);
                        uiStateHandler.postDelayed(markUiBackground, UI_BACKGROUND_DEBOUNCE_MS);
                    }

                    @Override
                    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

                    @Override
                    public void onActivityDestroyed(Activity activity) {}
                });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final long coldStartAppInitStarted = System.nanoTime();
        CONTEXT = this.getApplicationContext();
        initializeUiStateTracking();
        SecureContentCryptoSessionRuntimeV1.initialize(getApplicationContext());
        AppLockController.initialize(getApplicationContext());
        SecureColdStartPerfRuntime.initialize(getApplicationContext());
        SecureColdStartPerfTrace.start();
        SecureMessageSearchRuntime.enableProduction();
        SecureMediaPerfRuntime.initialize(getApplicationContext());
        CallDiagnosticsRuntime.initialize(getApplicationContext());
        secureContentAccountRegistry = new SecureContentAccountRegistry();
        secureContentStoreProvider =
                new SecureContentStoreProvider(getApplicationContext(), secureContentAccountRegistry);
        EmojiInitializationService.execute(getApplicationContext());
        ExceptionHelper.init(getApplicationContext());
        applyThemeSettings();
        System.loadLibrary("sqlcipher");
        SecureColdStartPerfTrace.stage(
                "application_init", System.nanoTime() - coldStartAppInitStarted);
    }

    public void applyThemeSettings() {
        final var sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (sharedPreferences == null) {
            return;
        }
        applyThemeSettings(sharedPreferences);
    }

    private void applyThemeSettings(final SharedPreferences sharedPreferences) {
        AppCompatDelegate.setDefaultNightMode(getDesiredNightMode(this, sharedPreferences));
        var dynamicColorsOptionsBuilder =
                new DynamicColorsOptions.Builder()
                        .setPrecondition((activity, t) -> isDynamicColorsDesired(activity));

        if (ThemeHelper.isOled(this)) {
            dynamicColorsOptionsBuilder.setOnAppliedCallback(new DynamicColors.OnAppliedCallback() {
                @Override
                public void onApplied(@NonNull Activity activity) {
                    activity.getTheme().applyStyle(R.style.DarkOLEDOverlay, true);
                }
            });
        }
        DynamicColors.applyToActivitiesIfAvailable(this, dynamicColorsOptionsBuilder.build());
    }

    public static int getDesiredNightMode(final Context context) {
        final var sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (sharedPreferences == null) {
            return AppCompatDelegate.getDefaultNightMode();
        }
        return getDesiredNightMode(context, sharedPreferences);
    }

    public static boolean isDynamicColorsDesired(final Context context) {
        final var preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getBoolean(AppSettings.DYNAMIC_COLORS, false);
    }

    private static int getDesiredNightMode(
            final Context context, final SharedPreferences sharedPreferences) {
        final String theme =
                sharedPreferences.getString(AppSettings.THEME, context.getString(R.string.theme));
        return getDesiredNightMode(theme);
    }

    public static int getDesiredNightMode(final String theme) {
        if ("automatic".equals(theme)) {
            return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        } else if ("light".equals(theme)) {
            return AppCompatDelegate.MODE_NIGHT_NO;
        } else {
            return AppCompatDelegate.MODE_NIGHT_YES;
        }
    }
}
