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

/** Persistent, network-constrained subscription recovery. No new user consent is created. */
public final class FossPushAutoRetryWorker extends Worker {
    private static final String PREFIX = "foss-push-auto-retry-";

    public FossPushAutoRetryWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    static void schedule(final Context context, final FossPushStore.Record record,
            final boolean replace) {
        if (!FossPushPolicy.shouldAutoRetry(record.enabled, record.phase, record.failureReason)
                || record.publicKey.isEmpty()) return;
        final Data data = new Data.Builder()
                .putString("account", record.accountUuid)
                // The per-subscription public key is stable across token / IQ retry generations.
                .putString("owner", record.publicKey).build();
        final OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(FossPushAutoRetryWorker.class)
                .setInputData(data)
                .setInitialDelay(60, TimeUnit.SECONDS)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(PREFIX + record.accountUuid,
                replace ? ExistingWorkPolicy.REPLACE : ExistingWorkPolicy.KEEP, work);
    }

    static void cancel(final Context context, final String accountUuid) {
        WorkManager.getInstance(context).cancelUniqueWork(PREFIX + accountUuid);
    }

    @NonNull @Override public Result doWork() {
        final String accountUuid = getInputData().getString("account");
        final String owner = getInputData().getString("owner");
        if (accountUuid == null || owner == null) return Result.failure();
        final FossPushStore.Record record;
        try { record = new FossPushStore(getApplicationContext()).read(accountUuid); }
        catch (final RuntimeException unavailable) { return Result.retry(); }
        if (record == null || !owner.equals(record.publicKey)
                || !FossPushPolicy.shouldAutoRetry(record.enabled, record.phase, record.failureReason)) {
            return Result.success();
        }
        try {
            // The service rechecks ownership and phase; a queued retry is never a renewal.
            final Intent intent = new Intent(getApplicationContext(), XmppConnectionService.class);
            intent.setAction(XmppConnectionService.ACTION_FOSS_PUSH_AUTO_RETRY);
            intent.putExtra("push_account_uuid", accountUuid);
            intent.putExtra("push_retry_owner", owner);
            intent.putExtra(SystemEventReceiver.EXTRA_NEEDS_FOREGROUND_SERVICE, true);
            ContextCompat.startForegroundService(getApplicationContext(), intent);
        } catch (final RuntimeException denied) {
            // Android may refuse FGS from the background. WorkManager will retry later.
        }
        // Async IQ/FCM work happens in the service; completion cancels this unique work.
        // WorkManager caps exponential backoff, so no tight loop occurs during outages.
        return Result.retry();
    }
}
