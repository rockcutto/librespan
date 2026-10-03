package eu.siacs.conversations.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.security.applock.AppLockController;
import eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionRuntimeV1;
import eu.siacs.conversations.security.cryptolock.InactiveDevicePrivacyRuntimeV1;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.util.SettingsUtils;

public abstract class BaseActivity extends AppCompatActivity {
    private Boolean isDynamicColors;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsUtils.applyScreenshotSetting(this);
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        setupColors();
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        super.setContentView(view, params);
        setupColors();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        setupColors();
    }

    @Override
    public void onStart() {
        super.onStart();
        final boolean privacySuspended;
        if (isInactiveDeviceActivityTracked()) {
            privacySuspended =
                    InactiveDevicePrivacyRuntimeV1.onProtectedActivityStarted(this);
            if (privacySuspended) {
                requestSecureConnectivityReevaluation();
            }
        } else {
            privacySuspended = false;
        }
        final boolean appLockNeedsUnlock =
                isAppLockProtected() && AppLockController.onProtectedActivityStarted(this);
        // High Security is independent from the UI-only App Lock policy. Activities such as the
        // call UI may intentionally bypass App Lock, but they must never bypass a locked crypto
        // session or a durable privacy suspension.
        final boolean cryptoSessionNeedsUnlock =
                isCryptoSessionGateProtected()
                        && SecureContentCryptoSessionRuntimeV1.requiresAuthentication(this);
        if (privacySuspended || appLockNeedsUnlock || cryptoSessionNeedsUnlock) {
            startActivity(
                    new Intent(this, AppUnlockActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
        }
        final int desiredNightMode = Conversations.getDesiredNightMode(this);
        if (setDesiredNightMode(desiredNightMode)) {
            return;
        }
        final boolean isDynamicColors = Conversations.isDynamicColorsDesired(this);
        setDynamicColors(isDynamicColors);
    }

    @Override
    protected void onStop() {
        if (isInactiveDeviceActivityTracked()) {
            InactiveDevicePrivacyRuntimeV1.onProtectedActivityStopped(this);
        }
        if (isAppLockProtected()) {
            AppLockController.onProtectedActivityStopped(this);
        }
        super.onStop();
    }

    private void requestSecureConnectivityReevaluation() {
        final Intent intent = new Intent(this, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_INTERNAL_PING);
        try {
            startService(intent);
        } catch (final IllegalStateException ignored) {
            // A running service evaluates the same gate periodically.
        }
    }

    /**
     * Inactivity tracking is independent from the unlock overlay. Call UI deliberately bypasses
     * App Lock but still counts as authenticated foreground use while the crypto session is active.
     */
    protected boolean isInactiveDeviceActivityTracked() {
        return true;
    }

    protected boolean isAppLockProtected() {
        return true;
    }

    /**
     * High Security management surfaces may need to render while the secure crypto session is
     * locked or requires recovery. They still remain protected by App Lock and inactivity
     * tracking; only the generic crypto-session redirect is bypassed.
     */
    protected boolean isCryptoSessionGateProtected() {
        return true;
    }

    @Override
    protected void onResume(){
        super.onResume();
        SettingsUtils.applyScreenshotSetting(this);
    }

    public void setDynamicColors(final boolean isDynamicColors) {
        if (this.isDynamicColors == null) {
            this.isDynamicColors = isDynamicColors;
        } else {
            if (this.isDynamicColors != isDynamicColors) {
                Log.i(
                        "Recreating {} because dynamic color setting has changed",
                        getClass().getSimpleName());
                recreate();
            }
        }
    }

    public boolean setDesiredNightMode(final int desiredNightMode) {
        if (desiredNightMode == AppCompatDelegate.getDefaultNightMode()) {
            return false;
        }
        AppCompatDelegate.setDefaultNightMode(desiredNightMode);
        Log.i("Recreating {} because desired night mode has changed", getClass().getSimpleName());
        recreate();
        return true;
    }

    private void setupColors() {
        View view = getWindow().getDecorView();
        Activities.setStatusAndNavigationBarColors(this, view);
    }
}
