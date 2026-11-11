package eu.siacs.conversations.services;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import eu.siacs.conversations.receiver.SystemEventReceiver;
import java.util.concurrent.TimeUnit;

/** Persistent, bounded attempt to ask the normal XMPP lifecycle to renew. */
public final class FossPushRenewWorker extends Worker {
    public FossPushRenewWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }
    static void schedule(Context context, String account, String generation, long expires) {
        FossPushStore.Record record = new FossPushStore(context).read(account);
        if (record == null || !record.enabled || !generation.equals(record.generation)) return;
        long due = expires > 0 ? expires - TimeUnit.DAYS.toMillis(7)
                : record.renewConfirmedAt + TimeUnit.DAYS.toMillis(30);
        long delay = Math.max(TimeUnit.MINUTES.toMillis(15), due - System.currentTimeMillis());
        Data data = new Data.Builder().putString("account", account).putString("generation", generation)
                .putLong("confirmed", record.renewConfirmedAt).build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(FossPushRenewWorker.class)
                .setInputData(data).setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build();
        WorkManager.getInstance(context).enqueueUniqueWork("foss-push-renew-" + account,
                ExistingWorkPolicy.REPLACE, work);
    }
    static void cancel(Context context, String account) {
        WorkManager.getInstance(context).cancelUniqueWork("foss-push-renew-" + account);
    }
    @NonNull @Override public Result doWork() {
        String account = getInputData().getString("account");
        String generation = getInputData().getString("generation");
        if (account == null || generation == null) return Result.failure();
        FossPushStore.Record record = new FossPushStore(getApplicationContext()).read(account);
        if (record == null || !record.enabled || !generation.equals(record.generation)) return Result.success();
        if (record.renewConfirmedAt > getInputData().getLong("confirmed", 0)) return Result.success();
        try {
            Intent intent = new Intent(getApplicationContext(), XmppConnectionService.class);
            intent.setAction(XmppConnectionService.ACTION_FOSS_PUSH_RENEW);
            intent.putExtra("push_account_uuid", account);
            intent.putExtra("generation", generation);
            intent.putExtra(SystemEventReceiver.EXTRA_NEEDS_FOREGROUND_SERVICE, true);
            ContextCompat.startForegroundService(getApplicationContext(), intent);
        } catch (RuntimeException denied) { /* Android may refuse background FGS. */ }
        return getRunAttemptCount() >= 4 ? Result.failure() : Result.retry();
    }
}
