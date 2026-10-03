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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.siacs.conversations.R
import eu.siacs.conversations.persistance.DatabaseBackendProvider
import eu.siacs.conversations.security.cryptolock.ActivationFailure
import eu.siacs.conversations.security.cryptolock.ActivationResult
import eu.siacs.conversations.security.cryptolock.ActivationStoreReadResult
import eu.siacs.conversations.security.cryptolock.ActivationSetupSessionV1
import eu.siacs.conversations.security.cryptolock.DeactivationResult
import eu.siacs.conversations.security.cryptolock.DeactivationSetupSessionV1
import eu.siacs.conversations.security.cryptolock.DeactivationStoreReadResultV1
import eu.siacs.conversations.security.cryptolock.InactiveDeviceDeactivationPhase
import eu.siacs.conversations.security.cryptolock.InactiveDeviceProtectionDeactivationStoreV1
import eu.siacs.conversations.security.cryptolock.InactiveDeviceProtectionDeactivationTransactionV1
import eu.siacs.conversations.security.cryptolock.PendingDeactivationAuthenticationV1
import eu.siacs.conversations.security.cryptolock.AndroidAuthBoundAppMasterKeyWrapperV1
import eu.siacs.conversations.security.cryptolock.InactiveDeviceActivationPhase
import eu.siacs.conversations.security.cryptolock.InactiveDeviceProtectionActivationStoreV1
import eu.siacs.conversations.security.cryptolock.InactiveDeviceProtectionActivationTransactionV1
import eu.siacs.conversations.security.cryptolock.PendingNormalVerificationV1
import eu.siacs.conversations.security.cryptolock.PendingNormalWrapV1
import eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionRuntimeV1
import eu.siacs.conversations.security.recovery.GeneratedRecoveryPhrase
import eu.siacs.conversations.security.recovery.RecoveryPhraseChallenge
import eu.siacs.conversations.security.recovery.RecoveryPhraseCodecV1
import eu.siacs.conversations.storage.secure.PersistentSecureContentAccountKeyMigrationParticipantV1
import eu.siacs.conversations.storage.secure.PersistentSecureContentAccountKeyDeactivationParticipantV1
import eu.siacs.conversations.utils.ThemeHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HighSecuritySetupActivity : BaseActivity() {

    internal class SetupSecretViewModel : ViewModel() {
        var generated: GeneratedRecoveryPhrase? = null
        var challenge: RecoveryPhraseChallenge? = null
        var challengeVisible: Boolean = false

        fun clearSecrets() {
            generated?.close()
            generated = null
            challenge = null
            challengeVisible = false
        }

        override fun onCleared() {
            clearSecrets()
            super.onCleared()
        }
    }

    private enum class Stage {
        WARNING,
        PHRASE,
        CHALLENGE,
        AUTH_WRAP,
        AUTH_VERIFY,
        MIGRATING,
        COMPLETE,
        ACTIVE_PROFILE,
        DEACTIVATION_WARNING,
        DEACTIVATION_AUTH,
        DEACTIVATING,
        DEACTIVATION_COMPLETE,
        ERROR,
    }

    private var stage: Stage = Stage.WARNING
    private lateinit var setupSecrets: SetupSecretViewModel

    private var generated: GeneratedRecoveryPhrase?
        get() = setupSecrets.generated
        set(value) {
            setupSecrets.generated = value
        }

    private var challenge: RecoveryPhraseChallenge?
        get() = setupSecrets.challenge
        set(value) {
            setupSecrets.challenge = value
        }
    private var transaction: InactiveDeviceProtectionActivationTransactionV1? = null
    private var activeSession: ActivationSetupSessionV1? = null
    private var biometricCancellation: CancellationSignal? = null
    private var promptGeneration: Long = 0L
    private var lastAuthenticationType: Int? = null
    private var migrationStarted = false
    private var deactivationStarted = false
    private var deactivationTransaction: InactiveDeviceProtectionDeactivationTransactionV1? = null
    private var deactivationSession: DeactivationSetupSessionV1? = null
    private val migrationExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeHelper.find(this))
        super.onCreate(savedInstanceState)
        setupSecrets = ViewModelProvider(this)[SetupSecretViewModel::class.java]
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setFinishOnTouchOutside(false)

        // Recovery words are retained across configuration recreation only in process memory.
        // They are never placed in Bundle, preferences or SQLite. Process death still discards
        // them and safely restarts setup from the durable activation boundary.
        openFromDurableState()
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun isCryptoSessionGateProtected(): Boolean = false

    override fun onBackPressed() {
        when (stage) {
            Stage.MIGRATING,
            Stage.DEACTIVATING -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.high_security_activation_title)
                    .setMessage(R.string.high_security_setup_in_progress)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
            Stage.AUTH_WRAP,
            Stage.AUTH_VERIFY -> cancelSetupAfterTransactionStarted()
            Stage.DEACTIVATION_AUTH -> cancelDeactivationBeforeMigration()
            Stage.PHRASE,
            Stage.CHALLENGE -> {
                setupSecrets.clearSecrets()
                finish()
            }
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        biometricCancellation?.cancel()
        biometricCancellation = null
        if (isFinishing) {
            setupSecrets.clearSecrets()
        }
        if (isFinishing && !migrationStarted && stage in setOf(Stage.AUTH_WRAP, Stage.AUTH_VERIFY)) {
            activeSession?.close()
            activeSession = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                transaction?.abortBeforeMigration()
            }
        }
        if (isFinishing && !deactivationStarted && stage == Stage.DEACTIVATION_AUTH) {
            deactivationSession?.close()
            deactivationSession = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                deactivationTransaction?.abortBeforeMigration()
            }
        }
        migrationExecutor.shutdown()
        super.onDestroy()
    }

    private fun openFromDurableState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showUnsupported()
            return
        }
        openFromDurableStateR()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun openFromDurableStateR() {
        val support = AndroidAuthBoundAppMasterKeyWrapperV1(this).supportStatus()
        if (support != null) {
            showUnsupported()
            return
        }

        val deactivationStore = InactiveDeviceProtectionDeactivationStoreV1(this)
        when (val deactivation = deactivationStore.read()) {
            DeactivationStoreReadResultV1.Corrupt -> {
                showTerminalError()
                return
            }
            is DeactivationStoreReadResultV1.Present -> {
                recoverDeactivationBoundary()
                return
            }
            DeactivationStoreReadResultV1.Absent -> Unit
        }

        val store = InactiveDeviceProtectionActivationStoreV1(this)
        when (val read = store.read()) {
            ActivationStoreReadResult.Absent -> {
                if (generated != null && challenge != null) {
                    if (setupSecrets.challengeVisible) {
                        showChallenge()
                    } else {
                        showPhrase()
                    }
                } else {
                    showWarning(false)
                }
            }
            ActivationStoreReadResult.Corrupt -> showTerminalError()
            is ActivationStoreReadResult.Present -> {
                when (read.record.phase) {
                    InactiveDeviceActivationPhase.ACTIVE -> {
                        SecureContentCryptoSessionRuntimeV1.reconcileDurableState(this)
                        showActiveProfile()
                    }
                    InactiveDeviceActivationPhase.PREPARING,
                    InactiveDeviceActivationPhase.NORMAL_WRAPPED -> rollbackIncompleteSetup()
                    InactiveDeviceActivationPhase.WRAPPERS_VERIFIED,
                    InactiveDeviceActivationPhase.MIGRATION_PREPARED,
                    InactiveDeviceActivationPhase.MIGRATION_COMMITTED -> recoverMigrationBoundary()
                    InactiveDeviceActivationPhase.ROLLBACK_REQUIRED -> showTerminalError()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun rollbackIncompleteSetup() {
        val tx = transaction ?: InactiveDeviceProtectionActivationTransactionV1(this).also {
            transaction = it
        }
        when (tx.abortBeforeMigration()) {
            is ActivationResult.Success -> showWarning(true)
            is ActivationResult.Failure -> showTerminalError()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recoverMigrationBoundary() {
        val tx = transaction ?: InactiveDeviceProtectionActivationTransactionV1(this).also {
            transaction = it
        }
        val participant =
            PersistentSecureContentAccountKeyMigrationParticipantV1(
                this,
                DatabaseBackendProvider.getInstance(this),
            )
        val result =
            try {
                tx.recoverMigrationBoundary(participant)
            } finally {
                participant.close()
            }

        when (result) {
            is ActivationResult.Failure ->
                showActivationFailure(result.reason, Stage.CHALLENGE)
            is ActivationResult.Success -> {
                when (result.value) {
                    InactiveDeviceActivationPhase.ACTIVE -> {
                        SecureContentCryptoSessionRuntimeV1.reconcileDurableState(this)
                        showActiveProfile()
                    }
                    InactiveDeviceActivationPhase.PREPARING,
                    InactiveDeviceActivationPhase.NORMAL_WRAPPED,
                    InactiveDeviceActivationPhase.WRAPPERS_VERIFIED -> rollbackIncompleteSetup()
                    else -> showTerminalError()
                }
            }
        }
    }


    @RequiresApi(Build.VERSION_CODES.R)
    private fun recoverDeactivationBoundary() {
        val tx =
            deactivationTransaction
                ?: InactiveDeviceProtectionDeactivationTransactionV1(this).also {
                    deactivationTransaction = it
                }
        val participant =
            PersistentSecureContentAccountKeyDeactivationParticipantV1(
                this,
                DatabaseBackendProvider.getInstance(this),
            )
        val result =
            try {
                tx.recover(participant)
            } finally {
                participant.close()
            }
        when (result) {
            is DeactivationResult.Success -> {
                SecureContentCryptoSessionRuntimeV1.reconcileDurableState(this)
                if (result.value == null) {
                    showActiveProfile()
                } else {
                    showDeactivationComplete()
                }
            }
            is DeactivationResult.Failure -> showTerminalError()
        }
    }

    private fun showActiveProfile() {
        stage = Stage.ACTIVE_PROFILE
        val root = createRoot()
        addTitle(root, getString(R.string.high_security_active_title))
        addBody(root, getString(R.string.high_security_active_text))

        val disable = MaterialButton(this)
        disable.setText(R.string.high_security_disable_action)
        disable.setOnClickListener { showDeactivationWarning() }
        root.addView(disable, buttonParams())

        val close = MaterialButton(this)
        close.setText(R.string.high_security_close)
        close.setOnClickListener { finish() }
        root.addView(close, secondaryButtonParams())
        setScrollableContent(root)
    }

    private fun showDeactivationWarning() {
        stage = Stage.DEACTIVATION_WARNING
        val root = createRoot()
        addTitle(root, getString(R.string.high_security_disable_title))
        addBody(root, getString(R.string.high_security_disable_warning))

        val continueButton = MaterialButton(this)
        continueButton.setText(R.string.high_security_continue)
        continueButton.setOnClickListener { beginDeactivation() }
        root.addView(continueButton, buttonParams())

        val cancel = MaterialButton(this)
        cancel.setText(android.R.string.cancel)
        cancel.setOnClickListener { showActiveProfile() }
        root.addView(cancel, secondaryButtonParams())
        setScrollableContent(root)
    }

    private fun beginDeactivation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showUnsupported()
            return
        }
        beginDeactivationR()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun beginDeactivationR() {
        val tx = InactiveDeviceProtectionDeactivationTransactionV1(this)
        deactivationTransaction = tx
        when (val result = tx.begin()) {
            is DeactivationResult.Failure -> {
                when (result.reason) {
                    eu.siacs.conversations.security.cryptolock.DeactivationFailure.RECOVERY_REQUIRED ->
                        showError(
                            getString(R.string.high_security_disable_title),
                            getString(R.string.high_security_disable_recovery_required),
                        )
                    else -> showTerminalError()
                }
            }
            is DeactivationResult.Success ->
                requestDeactivationAuthentication(result.value)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestDeactivationAuthentication(
        pending: PendingDeactivationAuthenticationV1,
    ) {
        stage = Stage.DEACTIVATION_AUTH
        showDeactivationStatus(R.string.high_security_disable_auth_text)
        authenticateCrypto(
            pending.operation.cryptoObject(),
            R.string.high_security_disable_auth_title,
            R.string.high_security_disable_auth_text,
            onSuccess = { authenticatedCryptoObject ->
                when (
                    val result =
                        deactivationTransaction?.completeAuthentication(
                            pending,
                            authenticatedCryptoObject,
                        )
                ) {
                    null -> showTerminalError()
                    is DeactivationResult.Failure -> cancelDeactivationBeforeMigration()
                    is DeactivationResult.Success -> {
                        deactivationSession = result.value
                        migrateAndDeactivate(result.value)
                    }
                }
            },
            onError = { _, _ ->
                cancelDeactivationBeforeMigration()
            },
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun migrateAndDeactivate(session: DeactivationSetupSessionV1) {
        stage = Stage.DEACTIVATING
        deactivationStarted = true
        showDeactivationStatus(R.string.high_security_disable_migrating)

        val tx = deactivationTransaction ?: return showTerminalError()
        migrationExecutor.execute {
            val participant =
                PersistentSecureContentAccountKeyDeactivationParticipantV1(
                    applicationContext,
                    DatabaseBackendProvider.getInstance(applicationContext),
                )
            val result =
                try {
                    tx.migrateAndDeactivate(session, participant)
                } finally {
                    participant.close()
                }
            runOnUiThread {
                deactivationSession = null
                when (result) {
                    is DeactivationResult.Success -> showDeactivationComplete()
                    is DeactivationResult.Failure -> {
                        session.close()
                        showTerminalError()
                    }
                }
            }
        }
    }

    private fun cancelDeactivationBeforeMigration() {
        biometricCancellation?.cancel()
        biometricCancellation = null
        deactivationSession?.close()
        deactivationSession = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !deactivationStarted) {
            deactivationTransaction?.abortBeforeMigration()
        }
        showActiveProfile()
    }

    private fun showDeactivationStatus(messageRes: Int) {
        val root = createRoot(Gravity.CENTER)
        addTitle(root, getString(R.string.high_security_disable_progress_title))
        addBody(root, getString(messageRes))
        setScrollableContent(root)
    }

    private fun showDeactivationComplete() {
        stage = Stage.DEACTIVATION_COMPLETE
        deactivationStarted = false
        setResult(RESULT_OK)
        val root = createRoot()
        addTitle(root, getString(R.string.high_security_disable_complete_title))
        addBody(root, getString(R.string.high_security_disable_complete_text))
        val done = MaterialButton(this)
        done.setText(R.string.high_security_done)
        done.setOnClickListener { finish() }
        root.addView(done, buttonParams())
        setScrollableContent(root)
    }

    private fun showWarning(rolledBack: Boolean) {
        stage = Stage.WARNING
        val root = createRoot()
        addTitle(root, getString(R.string.high_security_warning_title))
        if (rolledBack) {
            addBody(root, getString(R.string.high_security_setup_rolled_back))
        }
        addBody(root, getString(R.string.high_security_warning_text))

        val continueButton = MaterialButton(this)
        continueButton.setText(R.string.high_security_continue)
        continueButton.setOnClickListener { generateAndShowPhrase() }
        root.addView(continueButton, buttonParams())

        val cancel = MaterialButton(this)
        cancel.setText(android.R.string.cancel)
        cancel.setOnClickListener { finish() }
        root.addView(cancel, secondaryButtonParams())
        setScrollableContent(root)
    }

    private fun generateAndShowPhrase() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showUnsupported()
            return
        }
        setupSecrets.clearSecrets()
        generated = RecoveryPhraseCodecV1.generate()
        challenge = RecoveryPhraseChallenge.generate()
        setupSecrets.challengeVisible = false
        showPhrase()
    }

    private fun showPhrase() {
        val current = generated ?: return showTerminalError()
        stage = Stage.PHRASE
        val root = createRoot()
        addTitle(root, getString(R.string.high_security_phrase_title))
        addBody(root, getString(R.string.high_security_phrase_intro))

        val grid = GridLayout(this)
        grid.columnCount = 2
        grid.rowCount = 6
        grid.setPadding(0, dp(12), 0, dp(8))
        current.phrase.words().forEachIndexed { index, word ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val number = TextView(this).apply {
                text = (index + 1).toString()
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_LabelMedium,
                )
                setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ),
                )
                gravity = Gravity.END
                minWidth = dp(18)
            }
            item.addView(
                number,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            val value = TextView(this).apply {
                text = word
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
                )
                setTextIsSelectable(false)
            }
            item.addView(
                value,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    marginStart = dp(8)
                },
            )

            val params =
                GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(index / 6, 1f)
                    rowSpec = GridLayout.spec(index % 6)
                    setMargins(dp(4), dp(8), dp(8), dp(8))
                }
            grid.addView(item, params)
        }
        root.addView(
            grid,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val saved = MaterialButton(this)
        saved.setText(R.string.high_security_phrase_saved)
        saved.setOnClickListener {
            showChallenge()
        }
        root.addView(saved, buttonParams())
        setScrollableContent(root)
    }

    private fun showChallenge() {
        val current = generated ?: return showTerminalError()
        val currentChallenge = challenge ?: return showTerminalError()
        stage = Stage.CHALLENGE
        val root = createRoot()
        addTitle(root, getString(R.string.high_security_challenge_title))
        addBody(root, getString(R.string.high_security_challenge_intro))

        val fields = linkedMapOf<Int, EditText>()
        currentChallenge.positions.forEach { position ->
            // Keep the recovery challenge on platform widgets. This screen is security-critical and
            // does not need TextInputLayout's extra rendering/state machinery.
            val edit =
                EditText(this).apply {
                    hint = getString(R.string.high_security_word_position, position)
                    inputType =
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    setSingleLine(true)
                }
            fields[position] = edit
            root.addView(edit, fieldParams())
        }

        val verify = MaterialButton(this)
        verify.setText(R.string.high_security_check_phrase)
        verify.setOnClickListener {
            val answers =
                fields.mapValues { (_, edit) ->
                    edit.text?.toString().orEmpty()
                }
            if (!currentChallenge.matches(current.phrase, answers)) {
                fields.values.forEach {
                    it.error = getString(R.string.high_security_challenge_failed)
                }
                return@setOnClickListener
            }
            fields.values.forEach { it.error = null }
            beginActivationAfterVerifiedPhrase()
        }
        root.addView(verify, buttonParams())
        setScrollableContent(root)
        setupSecrets.challengeVisible = true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun beginActivationAfterVerifiedPhraseR() {
        val current = generated ?: return showTerminalError()
        val tx = InactiveDeviceProtectionActivationTransactionV1(this)
        transaction = tx
        val result =
            tx.beginAfterRecoveryPhraseVerified(
                current.secret,
                current.phrase.format,
            )
        setupSecrets.clearSecrets()

        when (result) {
            is ActivationResult.Failure ->
                showActivationFailure(result.reason, Stage.CHALLENGE)
            is ActivationResult.Success -> {
                activeSession = result.value.session
                requestNormalWrapAuthentication(result.value)
            }
        }
    }

    private fun beginActivationAfterVerifiedPhrase() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showUnsupported()
            return
        }
        beginActivationAfterVerifiedPhraseR()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestNormalWrapAuthentication(pending: PendingNormalWrapV1) {
        stage = Stage.AUTH_WRAP
        showActivationStatus(R.string.high_security_activation_wrap)
        authenticateCrypto(
            pending.operation.cryptoObject(),
            R.string.high_security_auth_wrap_title,
            R.string.high_security_auth_wrap_subtitle,
            onSuccess = { authenticatedCryptoObject ->
                val result =
                    transaction?.completeNormalWrap(pending, authenticatedCryptoObject)
                        ?: return@authenticateCrypto showTerminalError()
                when (result) {
                    is ActivationResult.Failure ->
                        failSetupAfterTransactionStarted(
                            result.reason,
                            result.diagnosticCode,
                        )
                    is ActivationResult.Success ->
                        window.decorView.post {
                            requestNormalVerificationAuthentication(result.value)
                        }
                }
            },
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestNormalVerificationAuthentication(pending: PendingNormalVerificationV1) {
        stage = Stage.AUTH_VERIFY
        showActivationStatus(R.string.high_security_activation_verify)
        authenticateCrypto(
            pending.operation.cryptoObject(),
            R.string.high_security_auth_verify_title,
            R.string.high_security_auth_verify_subtitle,
            onSuccess = { authenticatedCryptoObject ->
                val result =
                    transaction?.completeNormalVerification(pending, authenticatedCryptoObject)
                        ?: return@authenticateCrypto showTerminalError()
                when (result) {
                    is ActivationResult.Failure ->
                        failSetupAfterTransactionStarted(
                            result.reason,
                            result.diagnosticCode,
                        )
                    is ActivationResult.Success -> {
                        activeSession = result.value
                        migrateAndActivate(result.value)
                    }
                }
            },
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun authenticateCrypto(
        cryptoObject: BiometricPrompt.CryptoObject,
        titleRes: Int,
        subtitleRes: Int,
        onSuccess: (BiometricPrompt.CryptoObject?) -> Unit,
        onError: ((Int, CharSequence) -> Unit)? = null,
    ) {
        val generation = ++promptGeneration
        lastAuthenticationType = null
        biometricCancellation?.cancel()
        val cancellation = CancellationSignal()
        biometricCancellation = cancellation
        val prompt =
            BiometricPrompt.Builder(this)
                .setTitle(getString(titleRes))
                .setSubtitle(getString(subtitleRes))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build()

        prompt.authenticate(
            cryptoObject,
            cancellation,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (generation != promptGeneration) return
                    biometricCancellation = null
                    lastAuthenticationType = result.authenticationType
                    onSuccess(result.cryptoObject)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (generation != promptGeneration) return
                    biometricCancellation = null
                    if (onError != null) {
                        onError(errorCode, errString)
                    } else {
                        cancelSetupAfterTransactionStarted(
                            systemAuthenticationFailureMessage(errorCode, errString),
                            "ANDROID_AUTH_$errorCode",
                        )
                    }
                }
            },
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun migrateAndActivate(session: ActivationSetupSessionV1) {
        stage = Stage.MIGRATING
        migrationStarted = true
        showActivationStatus(R.string.high_security_activation_migrate)

        val tx = transaction ?: return showTerminalError()
        migrationExecutor.execute {
            val participant =
                PersistentSecureContentAccountKeyMigrationParticipantV1(
                    applicationContext,
                    DatabaseBackendProvider.getInstance(applicationContext),
                )
            val result =
                try {
                    tx.migrateAndActivate(session, participant)
                } finally {
                    participant.close()
                }
            runOnUiThread {
                activeSession = null
                when (result) {
                    is ActivationResult.Success -> showComplete()
                    is ActivationResult.Failure -> {
                        session.close()
                        showActivationFailure(
                            result.reason,
                            Stage.MIGRATING,
                            result.diagnosticCode,
                        )
                    }
                }
            }
        }
    }

    private fun failSetupAfterTransactionStarted(
        reason: ActivationFailure,
        diagnosticCode: String? = null,
    ) {
        val failedStage = stage
        cleanupSetupAfterTransactionStarted()
        showActivationFailure(reason, failedStage, diagnosticCode)
    }

    private fun cancelSetupAfterTransactionStarted(
        reason: String? = null,
        diagnosticCode: String? = null,
    ) {
        val failedStage = stage
        cleanupSetupAfterTransactionStarted()
        if (reason == null) {
            showError(
                getString(R.string.high_security_error_title),
                getString(R.string.high_security_setup_cancelled),
            )
        } else {
            showError(
                getString(R.string.high_security_error_title),
                getString(
                    R.string.high_security_failure_details,
                    activationStageLabel(failedStage),
                    reason,
                    diagnosticCode.orEmpty(),
                ),
            )
        }
    }

    private fun cleanupSetupAfterTransactionStarted() {
        biometricCancellation?.cancel()
        biometricCancellation = null
        activeSession?.close()
        activeSession = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !migrationStarted) {
            transaction?.abortBeforeMigration()
        }
        stage = Stage.ERROR
    }

    private fun showActivationFailure(
        reason: ActivationFailure,
        failedStage: Stage = stage,
        diagnosticCode: String? = null,
    ) {
        stage = Stage.ERROR
        val authenticationCode =
            if (failedStage == Stage.AUTH_WRAP || failedStage == Stage.AUTH_VERIFY) {
                when (lastAuthenticationType) {
                    BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC -> "BIOMETRIC"
                    BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL ->
                        "DEVICE_CREDENTIAL"
                    else -> "AUTH_TYPE_UNKNOWN"
                }
            } else {
                null
            }
        val code =
            listOfNotNull(
                reason.name,
                diagnosticCode?.takeIf { it.isNotBlank() },
                authenticationCode,
            ).joinToString("/")
        showError(
            getString(R.string.high_security_error_title),
            getString(
                R.string.high_security_failure_details,
                activationStageLabel(failedStage),
                activationFailureMessage(reason),
                code,
            ),
        )
    }

    private fun activationStageLabel(value: Stage): String =
        getString(
            when (value) {
                Stage.CHALLENGE -> R.string.high_security_failure_stage_prepare
                Stage.AUTH_WRAP -> R.string.high_security_failure_stage_wrap
                Stage.AUTH_VERIFY -> R.string.high_security_failure_stage_verify
                Stage.MIGRATING -> R.string.high_security_failure_stage_migrate
                else -> R.string.high_security_failure_stage_setup
            },
        )

    private fun activationFailureMessage(reason: ActivationFailure): String =
        getString(
            when (reason) {
                ActivationFailure.UNSUPPORTED_PLATFORM ->
                    R.string.high_security_failure_unsupported_platform
                ActivationFailure.DEVICE_CREDENTIAL_UNAVAILABLE ->
                    R.string.high_security_failure_device_credential
                ActivationFailure.EXISTING_TRANSACTION ->
                    R.string.high_security_failure_existing_transaction
                ActivationFailure.JOURNAL_CORRUPT ->
                    R.string.high_security_failure_journal_corrupt
                ActivationFailure.NORMAL_KEY_CONFLICT ->
                    R.string.high_security_failure_normal_key_conflict
                ActivationFailure.NORMAL_KEY_UNAVAILABLE ->
                    R.string.high_security_failure_normal_key_unavailable
                ActivationFailure.AUTHENTICATION_REQUIRED ->
                    R.string.high_security_failure_authentication_required
                ActivationFailure.RECOVERY_WRAP_FAILED ->
                    R.string.high_security_failure_recovery_wrap
                ActivationFailure.RECOVERY_SELF_CHECK_FAILED ->
                    R.string.high_security_failure_recovery_self_check
                ActivationFailure.NORMAL_WRAP_FAILED ->
                    R.string.high_security_failure_normal_wrap
                ActivationFailure.NORMAL_VERIFY_FAILED ->
                    R.string.high_security_failure_normal_verify
                ActivationFailure.WRAPPER_VERIFICATION_FAILED ->
                    R.string.high_security_failure_wrapper_verification
                ActivationFailure.MIGRATION_FAILED ->
                    R.string.high_security_failure_migration
                ActivationFailure.PERSISTENCE_FAILED ->
                    R.string.high_security_failure_persistence
                ActivationFailure.INVALID_STATE ->
                    R.string.high_security_failure_invalid_state
                ActivationFailure.ROLLBACK_FAILED ->
                    R.string.high_security_failure_rollback
            },
        )

    private fun systemAuthenticationFailureMessage(
        errorCode: Int,
        errString: CharSequence,
    ): String =
        when (errorCode) {
            BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ->
                getString(R.string.high_security_failure_auth_cancelled)
            BiometricPrompt.BIOMETRIC_ERROR_CANCELED ->
                getString(R.string.high_security_failure_auth_interrupted)
            BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT,
            BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT ->
                getString(R.string.high_security_failure_auth_lockout)
            else ->
                getString(
                    R.string.high_security_failure_auth_system,
                    errString.toString(),
                )
        }

    private fun showActivationStatus(messageRes: Int) {
        val root = createRoot(Gravity.CENTER)
        addTitle(root, getString(R.string.high_security_activation_title))
        addBody(root, getString(messageRes))
        setScrollableContent(root)
    }

    private fun showComplete() {
        stage = Stage.COMPLETE
        migrationStarted = false
        setResult(RESULT_OK)
        val root = createRoot()
        addTitle(root, getString(R.string.high_security_complete_title))
        addBody(root, getString(R.string.high_security_complete_text))
        val done = MaterialButton(this)
        done.setText(R.string.high_security_done)
        done.setOnClickListener { finish() }
        root.addView(done, buttonParams())
        setScrollableContent(root)
    }

    private fun showUnsupported() {
        stage = Stage.ERROR
        showError(
            getString(R.string.high_security_error_title),
            getString(R.string.high_security_unsupported),
        )
    }

    private fun showTerminalError() {
        stage = Stage.ERROR
        showError(
            getString(R.string.high_security_error_title),
            getString(R.string.high_security_error_text),
        )
    }

    private fun showError(title: String, message: String) {
        val root = createRoot()
        addTitle(root, title)
        addBody(root, message)
        val close = MaterialButton(this)
        close.setText(R.string.high_security_close)
        close.setOnClickListener { finish() }
        root.addView(close, buttonParams())
        setScrollableContent(root)
    }

    private fun createRoot(gravity: Int = Gravity.TOP): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            this.gravity = gravity
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }

    private fun addTitle(root: LinearLayout, value: CharSequence) {
        val title = TextView(this)
        title.text = value
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun addBody(root: LinearLayout, value: CharSequence) {
        val body = TextView(this)
        body.text = value
        body.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        body.setLineSpacing(0f, 1.1f)
        root.addView(
            body,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )
    }

    private fun setScrollableContent(root: LinearLayout) {
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

    private fun buttonParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(24) }

    private fun secondaryButtonParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }

    private fun fieldParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
