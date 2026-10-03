package eu.siacs.conversations.crypto.axolotl;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.xmpp.Jid;

/**
 * Small local UX-only store used to remember which OMEMO fingerprint was last seen for a
 * contact/device pair. It never participates in encryption or XMPP trust decisions; those remain
 * owned by {@link FingerprintStatus} and the normal OMEMO store.
 */
public final class OmemoTrustUxStore {

    private static final String PREFS = "neocont_omemo_trust_ux_v1";
    private static final String SUFFIX_FINGERPRINT = ".fingerprint";
    private static final String SUFFIX_NEW = ".new";
    private static final String SUFFIX_CHANGED = ".changed";

    public enum State {
        VERIFIED,
        NEW,
        CHANGED,
        NEEDS_VERIFICATION,
        INACTIVE
    }

    private final SharedPreferences preferences;

    public OmemoTrustUxStore(@NonNull final Context context) {
        this.preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public State observe(
            @NonNull final Account account,
            @NonNull final Jid contactJid,
            @NonNull final XmppAxolotlSession session) {
        final String fingerprint = session.getFingerprint();
        final FingerprintStatus trust = session.getTrust();
        if (!trust.isActive()) {
            return State.INACTIVE;
        }
        if (fingerprint == null) {
            return State.NEEDS_VERIFICATION;
        }

        final String baseKey = key(account, contactJid, session.getRemoteAddress().getDeviceId());
        final String fingerprintKey = baseKey + SUFFIX_FINGERPRINT;
        final String newKey = baseKey + SUFFIX_NEW;
        final String changedKey = baseKey + SUFFIX_CHANGED;

        final String previousFingerprint = preferences.getString(fingerprintKey, null);
        boolean isNew = preferences.getBoolean(newKey, false);
        boolean isChanged = preferences.getBoolean(changedKey, false);

        final SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;

        if (previousFingerprint == null) {
            editor.putString(fingerprintKey, fingerprint);
            editor.putBoolean(newKey, !trust.isVerified());
            editor.putBoolean(changedKey, false);
            isNew = !trust.isVerified();
            isChanged = false;
            changed = true;
        } else if (!previousFingerprint.equals(fingerprint)) {
            editor.putString(fingerprintKey, fingerprint);
            editor.putBoolean(newKey, false);
            editor.putBoolean(changedKey, !trust.isVerified());
            isNew = false;
            isChanged = !trust.isVerified();
            changed = true;
        }

        if (trust.isVerified() && (isNew || isChanged)) {
            editor.putBoolean(newKey, false);
            editor.putBoolean(changedKey, false);
            isNew = false;
            isChanged = false;
            changed = true;
        }

        if (changed) {
            editor.apply();
        }

        if (trust.isVerified()) {
            return State.VERIFIED;
        }
        if (isChanged) {
            return State.CHANGED;
        }
        if (isNew) {
            return State.NEW;
        }
        return State.NEEDS_VERIFICATION;
    }

    public void markVerified(
            @NonNull final Account account,
            @NonNull final Jid contactJid,
            final int deviceId,
            @NonNull final String fingerprint) {
        final String baseKey = key(account, contactJid, deviceId);
        preferences.edit()
                .putString(baseKey + SUFFIX_FINGERPRINT, fingerprint)
                .putBoolean(baseKey + SUFFIX_NEW, false)
                .putBoolean(baseKey + SUFFIX_CHANGED, false)
                .apply();
    }

    private static String key(
            @NonNull final Account account, @NonNull final Jid contactJid, final int deviceId) {
        return account.getUuid()
                + "|"
                + contactJid.asBareJid()
                + "|"
                + deviceId;
    }
}
