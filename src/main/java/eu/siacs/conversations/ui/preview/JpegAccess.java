package eu.siacs.conversations.ui.preview;

import android.content.Context;
import eu.siacs.conversations.security.cryptolock.InactiveDevicePrivacyRuntimeV1;
import eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionRuntimeV1;

/** Bridge to the existing crypto retirement boundary. It owns no keys, timers or disk state. */
public final class JpegAccess {
    private JpegAccess() {}
    static {
        SecureContentCryptoSessionRuntimeV1.addPresentationInvalidationListener(()->{
            JpegLoader.INSTANCE.invalidateForPrivacy();
            JpegPreviewView.retireInvalidBindings();
        });
    }
    public static JpegLoader.Permit capture(Context context) {
        Context app=context.getApplicationContext();
        SecureContentCryptoSessionRuntimeV1.initialize(app);
        return capture(JpegLoader.INSTANCE,()->!InactiveDevicePrivacyRuntimeV1.isPrivacySuspended(app)
                && SecureContentCryptoSessionRuntimeV1.isBackgroundCryptoAvailable(app));
    }
    static JpegLoader.Permit capture(JpegLoader loader,java.util.function.BooleanSupplier available) {
        long epoch=loader.privacyEpoch();
        // Never query either runtime from a loader/permit critical section. Lock order is
        // privacy -> crypto -> loader, including synchronous retirement notifications.
        if(!available.getAsBoolean()) {
            loader.invalidateForPrivacy();
            JpegPreviewView.retireInvalidBindings();
            return null;
        }
        // A lock/unlock between authorization and issuance invalidates this attempt as well.
        return loader.permitForEpoch(epoch);
    }
}
