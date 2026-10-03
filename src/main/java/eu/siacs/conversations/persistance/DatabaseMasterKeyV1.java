package eu.siacs.conversations.persistance;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Installation-bound SQLCipher Database Master Key.
 *
 * The random 256-bit DMK is never stored plaintext. Android Keystore protects only the DMK
 * wrapper; the wrapper is intentionally not user-auth-bound because metadata DB bootstrap must
 * remain independent from the High Security AMK lock/recovery state machine.
 */
public final class DatabaseMasterKeyV1 {
    public enum Phase {
        PREPARED,
        COMMITTED
    }

    public static final class Record {
        public final Phase phase;
        public final String passphrase;

        private Record(final Phase phase, final String passphrase) {
            this.phase = phase;
            this.passphrase = passphrase;
        }
    }

    private static final String PREFERENCES = "database_master_key_v1";
    private static final String KEY_PHASE = "phase";
    private static final String KEY_CIPHERTEXT = "ciphertext";
    private static final String KEY_IV = "iv";
    private static final String ALIAS = "neocont.database-master-key.v1";
    private static final int DMK_BYTES = 32;

    private DatabaseMasterKeyV1() {}

    public static synchronized boolean hasRecord(final Context context) {
        final SharedPreferences prefs =
                context.getApplicationContext()
                        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        return prefs.contains(KEY_PHASE)
                || prefs.contains(KEY_CIPHERTEXT)
                || prefs.contains(KEY_IV);
    }

    public static synchronized Record read(final Context context) {
        final SharedPreferences prefs =
                context.getApplicationContext()
                        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        final String phaseValue = prefs.getString(KEY_PHASE, null);
        final String ciphertextValue = prefs.getString(KEY_CIPHERTEXT, null);
        final String ivValue = prefs.getString(KEY_IV, null);
        if (phaseValue == null && ciphertextValue == null && ivValue == null) {
            return null;
        }
        if (phaseValue == null || ciphertextValue == null || ivValue == null) {
            throw new IllegalStateException("database master key record is incomplete");
        }

        final byte[] ciphertext = Base64.decode(ciphertextValue, Base64.NO_WRAP);
        final byte[] iv = Base64.decode(ivValue, Base64.NO_WRAP);
        byte[] dmk = null;
        try {
            final SecretKey key = loadKey();
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad());
            dmk = cipher.doFinal(ciphertext);
            if (dmk.length != DMK_BYTES) {
                throw new IllegalStateException("database master key has invalid size");
            }
            final String passphrase = Base64.encodeToString(dmk, Base64.NO_WRAP);
            return new Record(Phase.valueOf(phaseValue), passphrase);
        } catch (final Exception e) {
            throw new IllegalStateException("unable to unwrap database master key", e);
        } finally {
            java.util.Arrays.fill(ciphertext, (byte) 0);
            java.util.Arrays.fill(iv, (byte) 0);
            if (dmk != null) {
                java.util.Arrays.fill(dmk, (byte) 0);
            }
        }
    }

    public static synchronized Record createPrepared(final Context context) {
        final Record existing = read(context);
        if (existing != null) {
            return existing;
        }
        try {
            final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(ALIAS)) {
                // Alias without a durable wrapper record is an interrupted pre-authority setup.
                keyStore.deleteEntry(ALIAS);
            }

            final KeyGenerator generator =
                    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(
                    new KeyGenParameterSpec.Builder(
                                    ALIAS,
                                    KeyProperties.PURPOSE_ENCRYPT
                                            | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build());
            final SecretKey wrappingKey = generator.generateKey();

            final byte[] dmk = new byte[DMK_BYTES];
            new SecureRandom().nextBytes(dmk);
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey);
            cipher.updateAAD(aad());
            final byte[] ciphertext = cipher.doFinal(dmk);
            final byte[] iv = cipher.getIV();
            try {
                final boolean committed =
                        context.getApplicationContext()
                                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                                .edit()
                                .putString(KEY_PHASE, Phase.PREPARED.name())
                                .putString(
                                        KEY_CIPHERTEXT,
                                        Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                                .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                                .commit();
                if (!committed) {
                    throw new IllegalStateException("unable to persist database master key");
                }
                return new Record(
                        Phase.PREPARED,
                        Base64.encodeToString(dmk, Base64.NO_WRAP));
            } finally {
                java.util.Arrays.fill(dmk, (byte) 0);
                java.util.Arrays.fill(ciphertext, (byte) 0);
                java.util.Arrays.fill(iv, (byte) 0);
            }
        } catch (final Exception e) {
            throw new IllegalStateException("unable to create database master key", e);
        }
    }

    public static synchronized boolean markCommitted(final Context context) {
        final Record current = read(context);
        if (current == null) {
            return false;
        }
        return context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PHASE, Phase.COMMITTED.name())
                .commit();
    }

    public static synchronized void clear(final Context context) {
        try {
            final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(ALIAS)) {
                keyStore.deleteEntry(ALIAS);
            }
        } catch (final Exception ignored) {
        }
        context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    private static SecretKey loadKey() throws Exception {
        final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        final KeyStore.SecretKeyEntry entry =
                (KeyStore.SecretKeyEntry) keyStore.getEntry(ALIAS, null);
        if (entry == null) {
            throw new IllegalStateException("database master key wrapper is missing");
        }
        return entry.getSecretKey();
    }

    private static byte[] aad() {
        return "NeoCont|DatabaseMasterKey|v1".getBytes(StandardCharsets.UTF_8);
    }
}
