package eu.siacs.conversations.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import eu.siacs.conversations.utils.Compatibility;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dedicated receiving connector. The existing UnifiedPushDistributor is untouched. */
public abstract class FossPushConnectorReceiver extends BroadcastReceiver {
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final String PREFIX = "org.unifiedpush.android.connector.";

    protected abstract boolean trusted(Context context, Intent intent,
            FossPushStore.Record record, String senderPackage);

    @Override
    public final void onReceive(final Context context, final Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        final String token;
        try { token = intent.getStringExtra("token"); }
        catch (final RuntimeException malformed) { return; }
        if (token == null) return;
        final String senderPackage = Build.VERSION.SDK_INT >= 34 ? getSentFromPackage() : null;
        final PendingResult pending = goAsync();
        try { WORK.execute(() -> {
            try {
                final String action = intent.getAction();
                final FossPushStore store = new FossPushStore(context);
                final boolean unregistered = (PREFIX + "UNREGISTERED").equals(action);
                final FossPushStore.Record candidate = unregistered
                        ? store.findByPreviousToken(token) : store.findByToken(token);
                final boolean activeUnregistered = unregistered && candidate == null;
                final FossPushStore.Record record = activeUnregistered
                        ? store.findByToken(token) : candidate;
                if (record == null || (!unregistered && !record.enabled)
                        || !trusted(context, intent, record, senderPackage)) return;
                if (unregistered) {
                    if (!new FossPushEventJournal(context).enqueue(
                            activeUnregistered ? "revoked" : "unregistered", token, "")) return;
                    final Intent serviceIntent = new Intent(context, XmppConnectionService.class);
                    serviceIntent.setAction(XmppConnectionService.ACTION_FOSS_PUSH_DRAIN);
                    Compatibility.startService(context, serviceIntent);
                    return;
                }
                if ((PREFIX + "NEW_ENDPOINT").equals(action)) {
                    final String endpoint = intent.getStringExtra("endpoint");
                    if (endpoint == null || endpoint.length() > 1000) return;
                    if (!new FossPushEventJournal(context).enqueue("endpoint", token, endpoint)) return;
                    ack(context, record, intent);
                    final Intent serviceIntent = new Intent(context, XmppConnectionService.class);
                    serviceIntent.setAction(XmppConnectionService.ACTION_FOSS_PUSH_DRAIN);
                    Compatibility.startService(context, serviceIntent);
                } else if ((PREFIX + "MESSAGE").equals(action)) {
                    ack(context, record, intent);
                    PushManagementService.wake(context, token, intent.getByteArrayExtra("bytesMessage"));
                } else if ((PREFIX + "REGISTRATION_FAILED").equals(action)) {
                    if (!new FossPushEventJournal(context).enqueue("failed", token,
                            intent.getStringExtra("reason"))) return;
                    final Intent serviceIntent = new Intent(context, XmppConnectionService.class);
                    serviceIntent.setAction(XmppConnectionService.ACTION_FOSS_PUSH_DRAIN);
                    Compatibility.startService(context, serviceIntent);
                } else if ((PREFIX + "TEMP_UNAVAILABLE").equals(action)) {
                    if (!new FossPushEventJournal(context).enqueue("unavailable", token, "")) return;
                    final Intent serviceIntent = new Intent(context, XmppConnectionService.class);
                    serviceIntent.setAction(XmppConnectionService.ACTION_FOSS_PUSH_DRAIN);
                    Compatibility.startService(context, serviceIntent);
                }
            } catch (final RuntimeException ignored) {
                // A malformed external broadcast must not crash the process or expose secrets.
            } finally { pending.finish(); }
        }); } catch (final RuntimeException rejected) { pending.finish(); }
    }

    private static void ack(final Context context, final FossPushStore.Record record, final Intent incoming) {
        final String id = incoming.getStringExtra("id");
        if (id == null || id.length() > 100 || !"external".equals(record.mode)) return;
        final Intent ack = new Intent("org.unifiedpush.android.distributor.MESSAGE_ACK");
        ack.setPackage(record.distributor);
        ack.putExtra("token", record.token);
        ack.putExtra("id", id);
        context.sendBroadcast(ack);
    }

    public static final class Embedded extends FossPushConnectorReceiver {
        @Override protected boolean trusted(final Context context, final Intent intent,
                final FossPushStore.Record record, final String senderPackage) {
            return "embedded".equals(record.mode);
        }
    }

    public static final class External extends FossPushConnectorReceiver {
        @Override protected boolean trusted(final Context context, final Intent intent,
                final FossPushStore.Record record, final String senderPackage) {
            if (!"external".equals(record.mode)) return false;
            // AND_3.1.0 replies contain a bearer token, not a distributor PendingIntent.
            // API 34+ can additionally authenticate a shared sender identity when present.
            return FossPushPolicy.trustedExternal(record.distributor, senderPackage);
        }
    }
}
