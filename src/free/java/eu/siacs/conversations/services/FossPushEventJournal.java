package eu.siacs.conversations.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Encrypted, ordered control events; acknowledgement follows durable commit. */
final class FossPushEventJournal {
    static final String FILE = "foss_push_events_v1";
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;
    private final Context context;
    FossPushEventJournal(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
    private JSONArray read() throws Exception {
        String raw = prefs.getString("queue", null);
        if (raw == null) return new JSONArray();
        byte[] wire = Base64.decode(raw, Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, FossPushStore.key(false),
                new GCMParameterSpec(128, Arrays.copyOfRange(wire, 0, 12)));
        return new JSONArray(new String(cipher.doFinal(wire, 12, wire.length - 12), StandardCharsets.UTF_8));
    }
    private void write(JSONArray queue) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, FossPushStore.key(true));
        byte[] encoded = cipher.doFinal(queue.toString().getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] wire = new byte[iv.length + encoded.length];
        System.arraycopy(iv, 0, wire, 0, iv.length);
        System.arraycopy(encoded, 0, wire, iv.length, encoded.length);
        if (!prefs.edit().putString("queue", Base64.encodeToString(wire, Base64.NO_WRAP)).commit())
            throw new IllegalStateException("Unable to persist push event");
    }
    boolean enqueue(String action, String token, String value) {
        synchronized (LOCK) {
            try {
                JSONArray q = read();
                for (int i = 0; i < q.length(); i++) {
                    JSONObject e = q.getJSONObject(i);
                    if (action.equals(e.getString("action")) && token.equals(e.getString("token"))
                            && java.util.Objects.equals(value == null ? "" : value, e.optString("value", ""))) {
                        FossPushRecoveryWorker.schedule(context, null);
                        return true;
                    }
                }
                if (q.length() >= 256) return false;
                q.put(new JSONObject().put("action", action).put("token", token)
                        .put("value", value == null ? "" : value));
                write(q);
                FossPushRecoveryWorker.schedule(context, null);
                return true;
            } catch (Exception e) { return false; }
        }
    }
    boolean hasPending() {
        synchronized (LOCK) {
            try { return read().length() > 0; }
            catch (Exception e) { return true; }
        }
    }
    boolean revoked(String token) {
        synchronized (LOCK) {
            try {
                JSONArray q = read();
                for (int i = 0; i < q.length(); i++) {
                    JSONObject e = q.getJSONObject(i);
                    if ("revoked".equals(e.getString("action")) && token.equals(e.getString("token"))) return true;
                }
                return false;
            } catch (Exception e) { return true; }
        }
    }
    void drain(PushManagementService manager) {
        synchronized (LOCK) {
            try {
                JSONArray q = read();
                while (q.length() > 0) {
                    JSONObject e = q.getJSONObject(0);
                    String action = e.getString("action"), token = e.getString("token");
                    String value = e.optString("value", "");
                    if ("endpoint".equals(action)) manager.onEndpoint(token, value);
                    else if ("revoked".equals(action)) manager.onActiveUnregistered(token);
                    else if ("unregistered".equals(action)) manager.onDistributorUnregistered(token);
                    else if ("failed".equals(action)) manager.onFailed(token, value);
                    else if ("unavailable".equals(action)) manager.onUnavailable(token);
                    else throw new IllegalStateException("Unknown event");
                    JSONArray remaining = new JSONArray();
                    for (int i = 1; i < q.length(); i++) remaining.put(q.get(i));
                    write(remaining);
                    q = remaining;
                }
            } catch (Exception ignored) { /* Preserve the unprocessed journal for retry. */ }
        }
    }
}
