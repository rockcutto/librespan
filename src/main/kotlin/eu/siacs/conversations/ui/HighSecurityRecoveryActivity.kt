// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.ui

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import eu.siacs.conversations.R
import eu.siacs.conversations.security.cryptolock.InactiveDeviceRecoveryRepairTransactionV1
import eu.siacs.conversations.security.cryptolock.PendingRecoveryRepairVerificationV1
import eu.siacs.conversations.security.cryptolock.PendingRecoveryRepairWrapV1
import eu.siacs.conversations.security.cryptolock.RecoveryRepairResultV1
import eu.siacs.conversations.security.cryptolock.RecoveryRepairSessionV1
import eu.siacs.conversations.utils.ThemeHelper

class HighSecurityRecoveryActivity : AppCompatActivity() {
    private var transaction: InactiveDeviceRecoveryRepairTransactionV1? = null
    private var cancellation: CancellationSignal? = null
    private var promptGeneration = 0L
    private var activeSession: RecoveryRepairSessionV1? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeHelper.find(this))
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setFinishOnTouchOutside(false)
        showPhraseEntry()
    }

    override fun onDestroy() {
        promptGeneration++
        cancellation?.cancel()
        cancellation = null
        activeSession?.close()
        activeSession = null
        super.onDestroy()
    }

    private fun showPhraseEntry() {
        val root = root()
        title(root, getString(R.string.high_security_recovery_title))
        body(root, getString(R.string.high_security_recovery_intro))

        val layout = TextInputLayout(this)
        layout.hint = getString(R.string.high_security_recovery_phrase_hint)
        val input = TextInputEditText(this)
        input.inputType =
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        input.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        input.minLines = 3
        layout.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(layout, params(16))

        val recover = MaterialButton(this)
        recover.setText(R.string.high_security_recovery_action)
        recover.setOnClickListener {
            val phrase = input.text?.toString()
            if (phrase.isNullOrBlank()) {
                layout.error = getString(R.string.high_security_recovery_invalid)
                return@setOnClickListener
            }
            layout.error = null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                beginRecovery(phrase, layout)
                input.text?.clear()
                return@setOnClickListener
            }
            authenticateRecoveryOwner(
                onSuccess = {
                    beginRecovery(phrase, layout)
                    input.text?.clear()
                },
            )
        }
        root.addView(recover, params(24))
        setScrollable(root)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun authenticateRecoveryOwner(onSuccess: () -> Unit) {
        val generation = ++promptGeneration
        cancellation?.cancel()
        val signal = CancellationSignal()
        cancellation = signal
        try {
            val biometricManager = getSystemService(BiometricManager::class.java)
            val biometricAvailable =
                try {
                    biometricManager?.canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG,
                    ) == BiometricManager.BIOMETRIC_SUCCESS
                } catch (_: RuntimeException) {
                    false
                }
            val prompt = BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.high_security_recovery_auth_title))
                .setSubtitle(getString(R.string.high_security_recovery_auth_subtitle))
                .setAllowedAuthenticators(
                    if (biometricAvailable) {
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    } else {
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    },
                )
                .build()
            prompt.authenticate(
                signal,
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        if (generation != promptGeneration) return
                        cancellation = null
                        onSuccess()
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        if (generation != promptGeneration) return
                        cancellation = null
                    }
                },
            )
        } catch (_: RuntimeException) {
            cancellation = null
        }
    }

    private fun beginRecovery(
        phrase: CharSequence,
        layout: TextInputLayout,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            layout.error = getString(R.string.high_security_unsupported)
            return
        }
        val tx = InactiveDeviceRecoveryRepairTransactionV1(this)
        transaction = tx
        when (val result = tx.begin(phrase)) {
            is RecoveryRepairResultV1.Failure -> {
                layout.error = getString(R.string.high_security_recovery_invalid)
            }
            is RecoveryRepairResultV1.Success -> {
                activeSession?.close()
                activeSession = result.value.session
                requestWrapAuthentication(result.value)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestWrapAuthentication(pending: PendingRecoveryRepairWrapV1) {
        showStatus(R.string.high_security_recovery_wrap)
        authenticate(
            pending.operation.cryptoObject(),
            R.string.high_security_recovery_auth_title,
            onSuccess = { authenticatedCryptoObject ->
                when (
                    val result =
                        transaction?.completeWrap(pending, authenticatedCryptoObject)
                ) {
                    null -> {
                        pending.session.close()
                        activeSession = null
                        showFailure()
                    }
                    is RecoveryRepairResultV1.Failure -> {
                        pending.session.close()
                        activeSession = null
                        showFailure()
                    }
                    is RecoveryRepairResultV1.Success -> requestVerification(result.value)
                }
            },
            onError = {
                pending.session.close()
                activeSession = null
                showPhraseEntry()
            },
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestVerification(pending: PendingRecoveryRepairVerificationV1) {
        showStatus(R.string.high_security_recovery_verify)
        authenticate(
            pending.operation.cryptoObject(),
            R.string.high_security_recovery_verify_title,
            onSuccess = { authenticatedCryptoObject ->
                when (
                    transaction?.completeVerification(
                        pending,
                        authenticatedCryptoObject,
                    )
                ) {
                    is RecoveryRepairResultV1.Success -> {
                        activeSession = null
                        showComplete()
                    }
                    else -> {
                        pending.session.close()
                        activeSession = null
                        showFailure()
                    }
                }
            },
            onError = {
                pending.session.close()
                activeSession = null
                showPhraseEntry()
            },
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun authenticate(
        cryptoObject: BiometricPrompt.CryptoObject,
        titleRes: Int,
        onSuccess: (BiometricPrompt.CryptoObject?) -> Unit,
        onError: () -> Unit,
    ) {
        val generation = ++promptGeneration
        cancellation?.cancel()
        val signal = CancellationSignal()
        cancellation = signal
        try {
            val biometricManager = getSystemService(BiometricManager::class.java)
            val biometricAvailable =
                try {
                    biometricManager?.canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG,
                    ) == BiometricManager.BIOMETRIC_SUCCESS
                } catch (_: RuntimeException) {
                    false
                }
            val prompt = BiometricPrompt.Builder(this)
                .setTitle(getString(titleRes))
                .setSubtitle(getString(R.string.high_security_recovery_auth_subtitle))
                .setAllowedAuthenticators(
                    if (biometricAvailable) {
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    } else {
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    },
                )
                .build()
            prompt.authenticate(
                cryptoObject,
                signal,
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (generation != promptGeneration) return
                        cancellation = null
                        onSuccess(result.cryptoObject)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (generation != promptGeneration) return
                        cancellation = null
                        onError()
                    }
                },
            )
        } catch (_: RuntimeException) {
            // A credential/enrollment change may reject the CryptoObject after the new normal
            // key was prepared. The recovery wrapper and the old journal remain intact.
            cancellation = null
            onError()
        }
    }

    private fun showStatus(message: Int) {
        val root = root(Gravity.CENTER)
        title(root, getString(R.string.high_security_recovery_title))
        body(root, getString(message))
        setScrollable(root)
    }

    private fun showComplete() {
        val root = root()
        title(root, getString(R.string.high_security_recovery_complete_title))
        body(root, getString(R.string.high_security_recovery_complete_text))
        val done = MaterialButton(this)
        done.setText(R.string.high_security_done)
        done.setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }
        root.addView(done, params(24))
        setScrollable(root)
    }

    private fun showFailure() {
        val root = root()
        title(root, getString(R.string.high_security_recovery_failed_title))
        body(root, getString(R.string.high_security_recovery_failed_text))
        val retry = MaterialButton(this)
        retry.setText(R.string.high_security_recovery_retry)
        retry.setOnClickListener { showPhraseEntry() }
        root.addView(retry, params(24))
        setScrollable(root)
    }

    private fun root(gravity: Int = Gravity.TOP) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            this.gravity = gravity
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }

    private fun title(root: LinearLayout, text: CharSequence) {
        root.addView(TextView(this).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
        })
    }

    private fun body(root: LinearLayout, text: CharSequence) {
        root.addView(
            TextView(this).apply {
                this.text = text
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            },
            params(16),
        )
    }

    private fun params(top: Int) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(top) }

    private fun setScrollable(root: LinearLayout) {
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(scroll)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
