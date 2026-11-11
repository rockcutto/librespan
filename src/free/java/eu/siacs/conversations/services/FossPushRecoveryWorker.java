package eu.siacs.conversations.services;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import eu.siacs.conversations.receiver.SystemEventReceiver;
import java.util.concurrent.TimeUnit;

/** Finite retries; work survives process death and never treats a service start as completion. */
public final class FossPushRecoveryWorker extends Worker {
    public FossPushRecoveryWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }
    static void schedule(Context c, String uuid) {
        Data data = new Data.Builder().putString("uuid", uuid == null ? "" : uuid).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(FossPushRecoveryWorker.class)
                .setInputData(data).setInitialDelay(10, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build();
        WorkManager.getInstance(c).enqueueUniqueWork("foss-push-recovery-" + (uuid == null ? "events" : uuid),
                ExistingWorkPolicy.KEEP, request);
    }
    @NonNull @Override public Result doWork() {
        Context c = getApplicationContext();
        String uuid = getInputData().getString("uuid");
        boolean events = uuid == null || uuid.isEmpty();
        FossPushStore.Record record = events ? null : new FossPushStore(c).read(uuid);
        if (events ? !new FossPushEventJournal(c).hasPending()
                : record == null || !record.enabled || !record.pendingWake) return Result.success();
        try {
            Intent intent = new Intent(c, XmppConnectionService.class);
            intent.setAction(events ? XmppConnectionService.ACTION_FOSS_PUSH_DRAIN
                    : XmppConnectionService.ACTION_FOSS_PUSH_WAKE);
            if (!events) intent.putExtra("push_account_uuid", uuid);
            intent.putExtra(SystemEventReceiver.EXTRA_NEEDS_FOREGROUND_SERVICE, true);
            ContextCompat.startForegroundService(c, intent);
        } catch (RuntimeException denied) { /* Retry without bypassing Android restrictions. */ }
        return getRunAttemptCount() >= 5 ? Result.failure() : Result.retry();
    }
}
