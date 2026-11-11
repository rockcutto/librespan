package eu.siacs.conversations.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import org.json.JSONArray;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Install-bound per-account push records. Failure to unwrap never enables push. */
final class FossPushStore {
    static final String FILE = "foss_push_client_v1";
    private static final String ALIAS = "librespan.foss-push-client.v1";
    private static final Object WRITE_LOCK = new Object();
    private final SharedPreferences preferences;

    FossPushStore(final Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static final class Record {
        String accountUuid;
        String service = "";
        String mode = "";
        String distributor = "";
        String vapid = "";
        String token = "";
        String endpoint = "";
        String publicKey = "";
        String privateKey = "";
        String auth = "";
        String node = "";
        String secret = "";
        String previousService = "";
        String previousNode = "";
        String previousEndpoint = "";
        String previousToken = "";
        String previousMode = "";
        String previousDistributor = "";
        boolean pendingDisable;
        boolean pendingUnregister;
        boolean pendingDistributor;
        ArrayList<String> pendingTokens = new ArrayList<>();
        boolean pendingWake;
        boolean enabled;
        String phase;
        String failureReason = "";
        String generation;
        long lastWake;
        long expiresAtMillis;
        long revision;
        long renewConfirmedAt;
        boolean renewPending;
        int attempts;
    }

    synchronized Record read(final String uuid) {
        final String value = preferences.getString(uuid, null);
        if (value == null) return null;
        try {
            final byte[] wire = Base64.decode(value, Base64.NO_WRAP);
            if (wire.length < 13) throw new IllegalStateException("Invalid push record");
            final byte[] iv = java.util.Arrays.copyOfRange(wire, 0, 12);
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
            cipher.updateAAD(uuid.getBytes(StandardCharsets.UTF_8));
            final JSONObject j = new JSONObject(new String(
                    cipher.doFinal(wire, 12, wire.length - 12), StandardCharsets.UTF_8));
            final Record r = new Record();
            r.accountUuid = uuid;
            r.service = j.optString("service", "");
            r.mode = j.optString("mode", "");
            r.distributor = j.optString("distributor", "");
            r.vapid = j.optString("vapid", "");
            r.token = j.optString("token", "");
            r.endpoint = j.optString("endpoint", "");
            r.publicKey = j.optString("publicKey", "");
            r.privateKey = j.optString("privateKey", "");
            r.auth = j.optString("auth", "");
            r.node = j.optString("node", "");
            r.secret = j.optString("secret", "");
            r.previousService = j.optString("previousService", "");
            r.previousNode = j.optString("previousNode", "");
            r.previousEndpoint = j.optString("previousEndpoint", "");
            r.previousToken = j.optString("previousToken", "");
            r.previousMode = j.optString("previousMode", "");
            r.previousDistributor = j.optString("previousDistributor", "");
            r.pendingDisable = j.optBoolean("pendingDisable");
            r.pendingUnregister = j.optBoolean("pendingUnregister");
            r.pendingDistributor = j.optBoolean("pendingDistributor");
            final JSONArray tokens = j.optJSONArray("pendingTokens");
            if (tokens != null) {
                for (int i = 0; i < tokens.length(); i++) {
                    final String token = tokens.optString(i, "");
                    if (!token.isEmpty()) r.pendingTokens.add(token);
                }
            } else if (r.pendingDistributor && !r.previousToken.isEmpty()) {
                r.pendingTokens.add(r.previousToken);
            }
            r.pendingWake = j.optBoolean("pendingWake");
            r.enabled = j.optBoolean("enabled");
            r.phase = j.optString("phase", "off");
            r.failureReason = j.optString("failureReason", "");
            r.generation = j.optString("generation", "");
            r.lastWake = j.optLong("lastWake");
            r.expiresAtMillis = j.optLong("expiresAtMillis");
            r.revision = j.optLong("revision");
            r.renewConfirmedAt = j.optLong("renewConfirmedAt");
            r.renewPending = j.optBoolean("renewPending");
            r.attempts = j.optInt("attempts");
            return r;
        } catch (final Exception e) {
            throw new IllegalStateException("Unable to read push state", e);
        }
    }

    synchronized Record findByToken(final String token) {
        if (token == null || token.length() < 20) return null;
        for (final String uuid : preferences.getAll().keySet()) {
            final Record record = read(uuid);
            if (record != null && MessageDigest.isEqual(
                    token.getBytes(StandardCharsets.UTF_8),
                    record.token.getBytes(StandardCharsets.UTF_8))) {
                return record;
            }
        }
        return null;
    }

    synchronized Record findByPreviousToken(final String token) {
        if (token == null || token.length() < 20) return null;
        for (final String uuid : preferences.getAll().keySet()) {
            final Record record = read(uuid);
            if (record != null && record.pendingDistributor) {
                for (final String pending : record.pendingTokens) {
                    if (MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                            pending.getBytes(StandardCharsets.UTF_8))) return record;
                }
            }
        }
        return null;
    }

    synchronized ArrayList<Record> cancellationRecords(final String accountUuid) {
        final ArrayList<Record> records = new ArrayList<>();
        for (final String key : preferences.getAll().keySet()) {
            if (!key.startsWith(accountUuid + "~")) continue;
            final Record r = read(key);
            if (r != null && (r.pendingDisable || r.pendingUnregister || r.pendingDistributor))
                records.add(r);
        }
        return records;
    }

    synchronized void write(final Record r) {
        if (r == null || r.accountUuid == null) throw new IllegalArgumentException("Missing account");
        synchronized (WRITE_LOCK) {
        try {
            final Record persisted = read(r.accountUuid);
            if (persisted != null && persisted.revision != r.revision)
                throw new IllegalStateException("Concurrent push state update");
            final long nextRevision = r.revision + 1;
            final JSONObject j = new JSONObject();
            j.put("service", r.service).put("mode", r.mode).put("distributor", r.distributor)
                    .put("vapid", r.vapid).put("token", r.token).put("endpoint", r.endpoint)
                    .put("publicKey", r.publicKey).put("privateKey", r.privateKey).put("auth", r.auth)
                    .put("node", r.node).put("secret", r.secret)
                    .put("previousService", r.previousService).put("previousNode", r.previousNode)
                    .put("previousEndpoint", r.previousEndpoint)
                    .put("previousToken", r.previousToken).put("previousMode", r.previousMode)
                    .put("previousDistributor", r.previousDistributor)
                    .put("pendingDisable", r.pendingDisable).put("pendingUnregister", r.pendingUnregister)
                    .put("pendingDistributor", r.pendingDistributor)
                    .put("pendingTokens", new JSONArray(r.pendingTokens))
                    .put("pendingWake", r.pendingWake)
                    .put("enabled", r.enabled).put("phase", r.phase)
                    .put("failureReason", r.failureReason)
                    .put("generation", r.generation).put("lastWake", r.lastWake)
                    .put("expiresAtMillis", r.expiresAtMillis)
                    .put("revision", nextRevision)
                    .put("renewConfirmedAt", r.renewConfirmedAt).put("renewPending", r.renewPending)
                    .put("attempts", r.attempts);
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            cipher.updateAAD(r.accountUuid.getBytes(StandardCharsets.UTF_8));
            final byte[] ciphertext = cipher.doFinal(j.toString().getBytes(StandardCharsets.UTF_8));
            final byte[] iv = cipher.getIV();
            final byte[] wire = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, wire, 0, iv.length);
            System.arraycopy(ciphertext, 0, wire, iv.length, ciphertext.length);
            if (!preferences.edit().putString(r.accountUuid,
                    Base64.encodeToString(wire, Base64.NO_WRAP)).commit()) {
                throw new IllegalStateException("Unable to persist push state");
            }
            r.revision = nextRevision;
        } catch (final Exception e) {
            throw new IllegalStateException("Unable to persist push state", e);
        }
        }
    }

    static SecretKey key(final boolean create) throws Exception {
        final KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        }
        if (!create) throw new IllegalStateException("Push key unavailable");
        final KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }
}
