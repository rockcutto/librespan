package eu.siacs.conversations.services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class FossPushAutoRetryPolicyTest {
    @Test public void networkAndGatewayFailuresRecoverWithoutUserClick() {
        assertTrue(FossPushPolicy.shouldAutoRetry(true, "error", "NETWORK"));
        assertTrue(FossPushPolicy.shouldAutoRetry(true, "error", "INTERNAL_ERROR"));
        assertTrue(FossPushPolicy.shouldAutoRetry(true, "error", ""));
        assertTrue(FossPushPolicy.shouldAutoRetry(true, "connecting", ""));
        assertTrue(FossPushPolicy.shouldAutoRetry(true, "enabling", ""));
    }

    @Test public void disabledAndConfirmedSubscriptionsNeverAutoReconnect() {
        assertFalse(FossPushPolicy.shouldAutoRetry(false, "error", "NETWORK"));
        assertFalse(FossPushPolicy.shouldAutoRetry(false, "off", ""));
        assertFalse(FossPushPolicy.shouldAutoRetry(true, "connected", ""));
        assertFalse(FossPushPolicy.shouldAutoRetry(true, "off", ""));
    }

    @Test public void revokedOrUserActionRequiredIsNotSilentlyReEnabled() {
        assertFalse(FossPushPolicy.shouldAutoRetry(false, "error", "UNREGISTERED"));
        assertFalse(FossPushPolicy.shouldAutoRetry(true, "error", "UNREGISTERED"));
        assertFalse(FossPushPolicy.shouldAutoRetry(true, "error", "VAPID_REQUIRED"));
        assertFalse(FossPushPolicy.shouldAutoRetry(true, "error", "ACTION_REQUIRED"));
    }
}
