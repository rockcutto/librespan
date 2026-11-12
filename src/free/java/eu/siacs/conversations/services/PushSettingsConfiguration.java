package eu.siacs.conversations.services;

import android.content.Context;
import android.util.Base64;
import eu.siacs.conversations.xmpp.PushGatewayProtocol;
import eu.siacs.conversations.xmpp.WebPushCrypto;
import java.security.GeneralSecurityException;
import java.util.Objects;

/** UI-only access to encrypted configuration. Never starts a delivery operation. */
public final class PushSettingsConfiguration {
    public static final class Snapshot {
        public final String gateway, vapid, mode, distributor, lastFailure, phase;
        public final boolean enabled, cancelling;
        Snapshot(FossPushStore.Record r) {
            gateway = r == null ? "" : r.service;
            vapid = r == null ? "" : r.vapid;
            mode = r == null ? "embedded" : r.mode;
            distributor = r == null ? "" : r.distributor;
            lastFailure = r == null ? "" : r.failureReason;
            phase = r == null ? "off" : r.phase;
            enabled = r != null && r.enabled;
            cancelling = r != null && (r.pendingDisable || r.pendingUnregister || r.pendingDistributor);
        }
        public boolean isRevoked(){return "error".equals(phase)&&"UNREGISTERED".equals(lastFailure);}
        public boolean matches(String g, String v, String m, String d) {
            return gateway.equals(g) && vapid.equals(v) && mode.equals(m) && distributor.equals(d);
        }
    }
    public static Snapshot read(Context context, String uuid) {
        return new Snapshot(new FossPushStore(context).read(uuid));
    }
    public static boolean valid(String gateway, String vapid) {
        try {
            PushGatewayProtocol.serviceJid(gateway);
            WebPushCrypto.decodePublicKey(Base64.decode(vapid, Base64.URL_SAFE | Base64.NO_WRAP));
            return true;
        } catch (RuntimeException | GeneralSecurityException invalid) { return false; }
    }
    public static boolean save(Context context, String uuid, String gateway, String vapid,
            String mode, String distributor) {
        if (!valid(gateway, vapid) || !("embedded".equals(mode) || "external".equals(mode))
                || ("external".equals(mode) && (distributor.isEmpty()
                || distributor.equals(context.getPackageName())))) return false;
        FossPushStore store = new FossPushStore(context);
        FossPushStore.Record r = store.read(uuid);
        if (new Snapshot(r).matches(gateway, vapid, mode, distributor)) return true;
        // Editing an active subscription requires the existing explicit connect operation.
        if (r != null && (r.enabled || r.pendingDisable || r.pendingUnregister || r.pendingDistributor))
            return false;
        if (r == null) {
            r = new FossPushStore.Record(); r.accountUuid = uuid; r.phase = "off"; r.generation = "";
        }
        r.service = gateway; r.vapid = vapid; r.mode = mode; r.distributor = distributor;
        store.write(r);
        return true;
    }
}
