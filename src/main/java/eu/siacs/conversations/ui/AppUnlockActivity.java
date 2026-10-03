package eu.siacs.conversations.ui;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import eu.siacs.conversations.R;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.security.applock.AppLockController;
import eu.siacs.conversations.security.applock.AppUnlockOutcome;
import eu.siacs.conversations.security.cryptolock.PendingSecureContentNormalUnlockV1;
import eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionRuntimeV1;
import eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionStateV1;
import eu.siacs.conversations.security.cryptolock.SecureContentNormalUnlockPreparationV1;
import eu.siacs.conversations.utils.ThemeHelper;

public final class AppUnlockActivity extends AppCompatActivity {

    private static final int REQUEST_DEVICE_CREDENTIAL = 0x4c01;
    private static final int REQUEST_HIGH_SECURITY_RECOVERY = 0x4c02;

    private CancellationSignal biometricCancellationSignal;
    private boolean biometricPromptActive;
    private boolean credentialIntentActive;
    private boolean unlockCompleted;
    private long promptGeneration;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        setTheme(ThemeHelper.find(this));
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setFinishOnTouchOutside(false);

        final int padding = Math.round(32 * getResources().getDisplayMetrics().density);
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(padding, padding, padding, padding);

        final TextView title = new TextView(this);
        title.setText(R.string.app_lock_unlock_title);
        title.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        title.setGravity(Gravity.CENTER);
        content.addView(title);

        final TextView subtitle = new TextView(this);
        subtitle.setText(R.string.app_lock_unlock_summary);
        subtitle.setGravity(Gravity.CENTER);
        final LinearLayout.LayoutParams subtitleParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = Math.round(12 * getResources().getDisplayMetrics().density);
        content.addView(subtitle, subtitleParams);

        final MaterialButton unlock = new MaterialButton(this);
        unlock.setText(R.string.app_lock_unlock_action);
        unlock.setOnClickListener(view -> requestUnlock());
        final LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = Math.round(24 * getResources().getDisplayMetrics().density);
        content.addView(unlock, buttonParams);

        final SecureContentCryptoSessionStateV1 cryptoState =
                SecureContentCryptoSessionRuntimeV1.snapshot(this).getState();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && (cryptoState == SecureContentCryptoSessionStateV1.LOCKED
                        || cryptoState == SecureContentCryptoSessionStateV1.RECOVERY_REQUIRED)) {
            final MaterialButton recovery = new MaterialButton(this);
            recovery.setText(R.string.high_security_recovery_action);
            recovery.setOnClickListener(view -> showRecoveryRequired());
            final LinearLayout.LayoutParams recoveryParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            recoveryParams.topMargin = Math.round(8 * getResources().getDisplayMetrics().density);
            content.addView(recovery, recoveryParams);
        }

        setContentView(content);

        if (savedInstanceState == null) {
            requestUnlock();
        }
    }

    private void requestUnlock() {
        if (biometricPromptActive || credentialIntentActive) {
            return;
        }

        final SecureContentNormalUnlockPreparationV1 cryptoPreparation =
                SecureContentCryptoSessionRuntimeV1.prepareNormalUnlock(this);
        if (handleCryptoPreparation(cryptoPreparation, false)) {
            return;
        }

        if (!hasDeviceCredential()) {
            AppLockController.onDeviceCredentialUnavailable(this);
            showCredentialUnavailable();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requestBiometricUnlock();
        } else {
            requestDeviceCredential();
        }
    }

    private boolean handleCryptoPreparation(
            final SecureContentNormalUnlockPreparationV1 preparation,
            final boolean credentialOnly) {
        if (preparation instanceof SecureContentNormalUnlockPreparationV1.Pending) {
            final PendingSecureContentNormalUnlockV1 pending =
                    ((SecureContentNormalUnlockPreparationV1.Pending) preparation).getOperation();
            if (biometricPromptActive) {
                cancelBiometricForTransition();
            }

            // High Security accepts either strong biometrics or the device credential. Avoid
            // briefly opening a biometric-only prompt on devices where no strong biometric is
            // enrolled: go straight to the system PIN/pattern/password UI instead.
            final boolean useDeviceCredential =
                    credentialOnly || !hasStrongBiometricAuthentication();
            requestCryptoUnlock(pending, useDeviceCredential);
            return true;
        }
        if (preparation
                instanceof SecureContentNormalUnlockPreparationV1.ActivationRecoveryRequired) {
            showActivationRecoveryPending();
            return true;
        }
        if (preparation instanceof SecureContentNormalUnlockPreparationV1.RecoveryRequired) {
            showRecoveryRequired();
            return true;
        }
        if (preparation instanceof SecureContentNormalUnlockPreparationV1.Corrupt) {
            showCryptoUnlockUnavailable();
            return true;
        }
        if (preparation instanceof SecureContentNormalUnlockPreparationV1.CryptoErased) {
            showCryptoErased();
            return true;
        }
        if (preparation instanceof SecureContentNormalUnlockPreparationV1.Failure) {
            showCryptoUnlockUnavailable();
            return true;
        }
        return false;
    }

    private boolean hasDeviceCredential() {
        final KeyguardManager keyguardManager =
                (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        return keyguardManager != null && keyguardManager.isKeyguardSecure();
    }

    private boolean hasStrongBiometricAuthentication() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        final BiometricManager biometricManager = getSystemService(BiometricManager.class);
        if (biometricManager == null) {
            return false;
        }
        try {
            return biometricManager.canAuthenticate(
                            BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (final RuntimeException ignored) {
            // Enrollment can change while this Activity is starting. The device credential
            // remains a valid authenticator for an existing High Security wrap.
            return false;
        }
    }

    private void requestCryptoUnlock(
            final PendingSecureContentNormalUnlockV1 pending,
            final boolean credentialOnly) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || biometricPromptActive
                || credentialIntentActive) {
            return;
        }

        final long generation = ++promptGeneration;
        try {
            final BiometricPrompt.Builder builder =
                    new BiometricPrompt.Builder(this)
                            .setTitle(
                                    getString(
                                            credentialOnly
                                                    ? R.string.app_lock_device_credential_title
                                                    : R.string.app_lock_unlock_title))
                            .setSubtitle(
                                    getString(
                                            credentialOnly
                                                    ? R.string.app_lock_device_credential_prompt
                                                    : R.string.app_lock_unlock_summary));

            if (credentialOnly) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
                builder.setNegativeButton(
                        getString(R.string.app_lock_use_device_credential),
                        getMainExecutor(),
                        (dialog, which) -> {
                            if (!isCurrentPrompt(generation)) {
                                return;
                            }
                            clearCurrentPromptWithoutCancel();
                            requestDeviceCredential();
                        });
            }

            biometricCancellationSignal = new CancellationSignal();
            biometricPromptActive = true;
            final int[] failedBiometricAttempts = {0};
            builder.build()
                .authenticate(
                        pending.cryptoObject(),
                        biometricCancellationSignal,
                        getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(
                                    final BiometricPrompt.AuthenticationResult result) {
                                if (!isCurrentPrompt(generation)) {
                                    return;
                                }
                                failedBiometricAttempts[0] = 0;
                                clearCurrentPromptWithoutCancel();
                                final SecureContentCryptoSessionStateV1 state =
                                        SecureContentCryptoSessionRuntimeV1.completeNormalUnlock(
                                                AppUnlockActivity.this,
                                                pending,
                                                result.getCryptoObject());
                                if (state == SecureContentCryptoSessionStateV1.ACTIVE) {
                                    requestSecureContentPostUnlockRefresh();
                                    finishUnlocked();
                                } else if (state
                                        == SecureContentCryptoSessionStateV1.RECOVERY_REQUIRED) {
                                    showRecoveryRequired();
                                } else {
                                    showCryptoUnlockUnavailable();
                                }
                            }

                            @Override
                            public void onAuthenticationFailed() {
                                if (!isCurrentPrompt(generation) || credentialOnly) {
                                    return;
                                }
                                failedBiometricAttempts[0]++;
                                if (failedBiometricAttempts[0] < 3) {
                                    return;
                                }

                                // A failed biometric attempt does not authenticate the CryptoObject.
                                // Retire this prompt/operation and prepare a fresh auth-per-use
                                // unwrap for the system device credential instead.
                                cancelBiometricForTransition();
                                requestDeviceCredential();
                            }

                            @Override
                            public void onAuthenticationError(
                                    final int errorCode, final CharSequence errString) {
                                if (!isCurrentPrompt(generation)) {
                                    return;
                                }
                                clearCurrentPromptWithoutCancel();
                                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                                        || errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                                        ) {
                                    AppLockController.onUnlockUiDismissed(
                                            AppUnlockActivity.this);
                                    return;
                                }

                                // A biometric failure can fall back to the auth-per-use device
                                // credential. A credential-only prompt must never retry itself:
                                // some devices reject it immediately when enrollment changes.
                                if (credentialOnly) {
                                    final SecureContentNormalUnlockPreparationV1 retry =
                                            SecureContentCryptoSessionRuntimeV1.prepareNormalUnlock(
                                                    AppUnlockActivity.this);
                                    if (retry instanceof SecureContentNormalUnlockPreparationV1.RecoveryRequired) {
                                        showRecoveryRequired();
                                    } else {
                                        showCryptoUnlockUnavailable();
                                    }
                                } else {
                                    requestDeviceCredential();
                                }
                            }
                        });
        } catch (final RuntimeException ignored) {
            // A prompt or CryptoObject can be rejected after an enrollment change between
            // prepareUnwrap and authenticate. Re-read the key on the credential path; it will
            // distinguish permanent invalidation from a transient prompt failure.
            cancelBiometricForTransition();
            if (credentialOnly) {
                final SecureContentNormalUnlockPreparationV1 retry =
                        SecureContentCryptoSessionRuntimeV1.prepareNormalUnlock(this);
                if (retry instanceof SecureContentNormalUnlockPreparationV1.RecoveryRequired) {
                    showRecoveryRequired();
                } else {
                    showCryptoUnlockUnavailable();
                }
            } else {
                requestDeviceCredential();
            }
        }
    }

    private void requestBiometricUnlock() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P
                || biometricPromptActive
                || credentialIntentActive) {
            return;
        }

        final long generation = ++promptGeneration;
        final BiometricPrompt.Builder builder =
                new BiometricPrompt.Builder(this)
                        .setTitle(getString(R.string.app_lock_unlock_title))
                        .setSubtitle(getString(R.string.app_lock_unlock_summary));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        }

        builder.setNegativeButton(
                getString(R.string.app_lock_use_device_credential),
                getMainExecutor(),
                (dialog, which) -> {
                    if (!isCurrentPrompt(generation)) {
                        return;
                    }
                    clearCurrentPromptWithoutCancel();
                    requestDeviceCredential();
                });

        biometricCancellationSignal = new CancellationSignal();
        biometricPromptActive = true;
        try {
            builder.build()
                .authenticate(
                        biometricCancellationSignal,
                        getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(
                                    final BiometricPrompt.AuthenticationResult result) {
                                if (!isCurrentPrompt(generation)) {
                                    return;
                                }
                                clearCurrentPromptWithoutCancel();
                                finishUnlocked();
                            }

                            @Override
                            public void onAuthenticationError(
                                    final int errorCode, final CharSequence errString) {
                                if (!isCurrentPrompt(generation)) {
                                    return;
                                }
                                clearCurrentPromptWithoutCancel();
                                if (credentialIntentActive) {
                                    return;
                                }
                                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                                        || errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                                        ) {
                                    AppLockController.onUnlockUiDismissed(
                                            AppUnlockActivity.this);
                                    return;
                                }
                                requestDeviceCredential();
                            }
                        });
        } catch (final RuntimeException ignored) {
            cancelBiometricForTransition();
            requestDeviceCredential();
        }
    }

    private void requestDeviceCredential() {
        if (credentialIntentActive) {
            return;
        }

        final SecureContentNormalUnlockPreparationV1 cryptoPreparation =
                SecureContentCryptoSessionRuntimeV1.prepareNormalUnlock(this);
        if (handleCryptoPreparation(cryptoPreparation, true)) {
            return;
        }

        if (!hasDeviceCredential()) {
            AppLockController.onDeviceCredentialUnavailable(this);
            showCredentialUnavailable();
            return;
        }

        // Mark the fallback active before cancelling BiometricPrompt so its asynchronous
        // cancellation callback cannot turn this intentional transition into a dismissed unlock.
        credentialIntentActive = true;
        if (biometricPromptActive) {
            cancelBiometricForTransition();
        }

        final KeyguardManager keyguardManager =
                (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager == null || !keyguardManager.isKeyguardSecure()) {
            credentialIntentActive = false;
            AppLockController.onDeviceCredentialUnavailable(this);
            showCredentialUnavailable();
            return;
        }

        final Intent intent =
                keyguardManager.createConfirmDeviceCredentialIntent(
                        getString(R.string.app_lock_device_credential_title),
                        getString(R.string.app_lock_device_credential_prompt));
        if (intent == null) {
            credentialIntentActive = false;
            AppLockController.onUnlockUiDismissed(this);
            return;
        }
        startActivityForResult(intent, REQUEST_DEVICE_CREDENTIAL);
    }

    private boolean isCurrentPrompt(final long generation) {
        return generation == promptGeneration;
    }

    private void clearCurrentPromptWithoutCancel() {
        biometricPromptActive = false;
        biometricCancellationSignal = null;
    }

    private void cancelBiometricForTransition() {
        promptGeneration++;
        biometricPromptActive = false;
        final CancellationSignal cancellation = biometricCancellationSignal;
        biometricCancellationSignal = null;
        if (cancellation != null) {
            cancellation.cancel();
        }
    }

    @Override
    protected void onActivityResult(
            final int requestCode,
            final int resultCode,
            @Nullable final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_HIGH_SECURITY_RECOVERY) {
            if (resultCode == RESULT_OK) {
                // Recovery has restored the crypto session, but App Lock owns a separate
                // process-local state machine. Complete the same shared unlock boundary used by
                // normal device authentication so unlockUiActive/locked cannot remain stale.
                requestSecureContentPostUnlockRefresh();
                finishUnlocked();
            }
            return;
        }
        if (requestCode != REQUEST_DEVICE_CREDENTIAL) {
            return;
        }
        credentialIntentActive = false;
        if (resultCode == RESULT_OK) {
            finishUnlocked();
        } else {
            AppLockController.onUnlockUiDismissed(this);
        }
    }

    private void finishUnlocked() {
        final AppUnlockOutcome outcome = AppLockController.completeLocalAuthentication(this);
        if (outcome == AppUnlockOutcome.UNLOCKED) {
            unlockCompleted = true;
            requestSecureConnectivityReevaluation();
            finish();
            overridePendingTransition(0, 0);
        } else if (outcome == AppUnlockOutcome.RECOVERY_REQUIRED) {
            showRecoveryRequired();
        } else {
            showCryptoErased();
        }
    }

    private void requestSecureConnectivityReevaluation() {
        final Intent intent = new Intent(this, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_INTERNAL_PING);
        try {
            startService(intent);
        } catch (final IllegalStateException ignored) {
            // The normal periodic connection manager will retry when the service is available.
        }
    }

    private void requestSecureContentPostUnlockRefresh() {
        final Intent intent = new Intent(this, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_SECURE_CONTENT_UNLOCKED);
        try {
            startService(intent);
        } catch (final IllegalStateException ignored) {
            // The next resident/history load will hydrate protected text once crypto is available.
        }
    }

    private void showActivationRecoveryPending() {
        // Activation recovery is owned by XmppConnectionService because it needs the database-backed
        // migration participant. Ensure the service is running instead of leaving App Unlock in a
        // passive retry state after an interrupted/failed High Security setup.
        requestSecureConnectivityReevaluation();
        android.widget.Toast.makeText(
                        this,
                        R.string.app_lock_activation_recovery_pending,
                        android.widget.Toast.LENGTH_LONG)
                .show();
    }

    private void showRecoveryRequired() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            android.widget.Toast.makeText(
                            this,
                            R.string.app_lock_recovery_required,
                            android.widget.Toast.LENGTH_LONG)
                    .show();
            return;
        }
        startActivityForResult(
                new Intent(this, HighSecurityRecoveryActivity.class),
                REQUEST_HIGH_SECURITY_RECOVERY);
    }

    private void showCryptoErased() {
        android.widget.Toast.makeText(
                        this,
                        R.string.app_lock_crypto_erased,
                        android.widget.Toast.LENGTH_LONG)
                .show();
    }

    private void showCryptoUnlockUnavailable() {
        android.widget.Toast.makeText(
                        this,
                        R.string.app_lock_crypto_unlock_unavailable,
                        android.widget.Toast.LENGTH_LONG)
                .show();
    }

    private void showCredentialUnavailable() {
        android.widget.Toast.makeText(
                        this,
                        R.string.app_lock_device_credential_unavailable,
                        android.widget.Toast.LENGTH_LONG)
                .show();
    }

    @Override
    protected void onDestroy() {
        promptGeneration++;
        if (biometricCancellationSignal != null) {
            biometricCancellationSignal.cancel();
            biometricCancellationSignal = null;
        }
        biometricPromptActive = false;
        if (!unlockCompleted && isFinishing() && !isChangingConfigurations()) {
            AppLockController.onUnlockUiDismissed(this);
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }
}
