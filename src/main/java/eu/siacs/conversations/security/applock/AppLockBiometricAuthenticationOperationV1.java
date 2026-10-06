package eu.siacs.conversations.security.applock;

import android.app.KeyguardManager;
import android.content.Context;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * One-shot Android Keystore proof for the App Lock biometric gate.
 *
 * <p>The key is temporary and auth-per-use. App Lock proceeds only when the Cipher operation handed
 * to {@link BiometricPrompt} is returned by a successful authentication and can complete a
 * cryptographic operation. The alias is removed on every terminal path.</p>
 */
@RequiresApi(Build.VERSION_CODES.P)
public final class AppLockBiometricAuthenticationOperationV1 implements AutoCloseable {

    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS = "librespan.applock-biometric-proof.v1";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_SIZE_BITS = 256;
    private static final int CHALLENGE_BYTES = 32;

    private Cipher cipher;
    private byte[] challenge;
    private boolean consumed;

    private AppLockBiometricAuthenticationOperationV1(
            final Cipher cipher, final byte[] challenge) {
        this.cipher = cipher;
        this.challenge = challenge;
    }

    @Nullable
    public static AppLockBiometricAuthenticationOperationV1 prepare(final Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null;
        }
        final KeyguardManager keyguardManager =
                (KeyguardManager)
                        context.getApplicationContext()
                                .getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager == null || !keyguardManager.isKeyguardSecure()) {
            return null;
        }

        try {
            deleteGateKey();

            final KeyGenerator generator =
                    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
            final KeyGenParameterSpec.Builder specBuilder =
                    new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT)
                            .setKeySize(KEY_SIZE_BITS)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setUserAuthenticationRequired(true)
                            .setInvalidatedByBiometricEnrollment(true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                specBuilder.setUserAuthenticationParameters(
                        0, KeyProperties.AUTH_BIOMETRIC_STRONG);
            } else {
                // Android 9-10: -1 means every key use requires fresh biometric authentication.
                specBuilder.setUserAuthenticationValidityDurationSeconds(-1);
            }

            generator.init(specBuilder.build());
            final SecretKey key = generator.generateKey();

            final Cipher preparedCipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            preparedCipher.init(Cipher.ENCRYPT_MODE, key);

            final byte[] proof = new byte[CHALLENGE_BYTES];
            new SecureRandom().nextBytes(proof);
            return new AppLockBiometricAuthenticationOperationV1(preparedCipher, proof);
        } catch (final GeneralSecurityException | RuntimeException error) {
            deleteGateKey();
            return null;
        }
    }

    public BiometricPrompt.CryptoObject cryptoObject() {
        if (consumed || cipher == null) {
            throw new IllegalStateException("App Lock biometric proof operation is consumed");
        }
        return new BiometricPrompt.CryptoObject(cipher);
    }

    public boolean complete(@Nullable final BiometricPrompt.CryptoObject authenticatedCryptoObject) {
        if (consumed) {
            throw new IllegalStateException("App Lock biometric proof operation is consumed");
        }
        consumed = true;

        final Cipher preparedCipher = cipher;
        final byte[] proof = challenge;
        cipher = null;
        challenge = null;

        try {
            final Cipher authenticatedCipher =
                    authenticatedCryptoObject == null ? null : authenticatedCryptoObject.getCipher();
            if (preparedCipher == null
                    || proof == null
                    || authenticatedCipher != preparedCipher) {
                return false;
            }
            final byte[] output = authenticatedCipher.doFinal(proof);
            java.util.Arrays.fill(output, (byte) 0);
            return true;
        } catch (final GeneralSecurityException | RuntimeException error) {
            return false;
        } finally {
            if (proof != null) {
                java.util.Arrays.fill(proof, (byte) 0);
            }
            deleteGateKey();
        }
    }

    @Override
    public void close() {
        if (!consumed) {
            consumed = true;
            cipher = null;
            if (challenge != null) {
                java.util.Arrays.fill(challenge, (byte) 0);
                challenge = null;
            }
        }
        deleteGateKey();
    }

    private static void deleteGateKey() {
        try {
            final KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
            }
        } catch (final GeneralSecurityException
                | java.io.IOException
                | RuntimeException ignored) {
        }
    }
}
