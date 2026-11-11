package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.ArrayList;

public class FossPushPolicyTest {
    @Test public void normativeRepliesNeedNoDistributorExtraOnAndroid24And33() {
        assertTrue(FossPushPolicy.trustedExternal("org.example.distributor", null));
    }

    @Test public void sharedIdentityOnAndroid34And35MustMatchSelection() {
        assertTrue(FossPushPolicy.trustedExternal("org.example.distributor", "org.example.distributor"));
        assertFalse(FossPushPolicy.trustedExternal("org.example.distributor", "org.evil"));
        assertFalse(FossPushPolicy.trustedExternal("", null));
    }

    @Test public void lateFailureFollowingEndpointIsIgnored() {
        assertTrue(FossPushPolicy.acceptRegistrationFailure(true, "", true));
        assertFalse(FossPushPolicy.acceptRegistrationFailure(true, "https://example.org/p", true));
        assertFalse(FossPushPolicy.acceptRegistrationFailure(false, "", true));
        assertFalse(FossPushPolicy.acceptRegistrationFailure(true, "", false));
    }

    @Test public void allSpecifiedFailureReasonsRemainDistinct() {
        for (final String reason : new String[] {"NETWORK", "ACTION_REQUIRED", "VAPID_REQUIRED"}) {
            assertEquals(reason, FossPushPolicy.failureReason(reason));
        }
        assertEquals("INTERNAL_ERROR", FossPushPolicy.failureReason(null));
        assertEquals("INTERNAL_ERROR", FossPushPolicy.failureReason("INTERNAL_ERROR"));
    }

    @Test public void repeatedFailuresRetainEachPendingCancellation() {
        final ArrayList<String> tokens = new ArrayList<>();
        FossPushPolicy.queueCancellation(tokens, "first-token");
        FossPushPolicy.queueCancellation(tokens, "second-token");
        FossPushPolicy.queueCancellation(tokens, "first-token");
        assertEquals(2, tokens.size());
        assertEquals("first-token", tokens.get(0));
        assertEquals("second-token", tokens.get(1));
    }
}
