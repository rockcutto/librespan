package eu.siacs.conversations.services;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/** AND_3.1.0 bind target. Distributor holds process importance during delivery. */
public final class FossPushForegroundBridge extends Service {
    private final IBinder binder = new Binder();

    @Override public IBinder onBind(final Intent intent) {
        return intent != null && "org.unifiedpush.android.connector.RAISE_TO_FOREGROUND"
                .equals(intent.getAction()) ? binder : null;
    }
}
