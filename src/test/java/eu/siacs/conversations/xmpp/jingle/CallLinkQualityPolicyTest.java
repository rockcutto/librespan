package eu.siacs.conversations.xmpp.jingle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CallLinkQualityPolicyTest {

    @Test
    public void classifiesWorstAvailableSignal() {
        assertEquals(
                CallLinkQualityPolicy.Level.GOOD,
                CallLinkQualityPolicy.classify(sample(2_000_000d, 0.05d, 0.0d, 0.005d)));
        assertEquals(
                CallLinkQualityPolicy.Level.DEGRADED,
                CallLinkQualityPolicy.classify(sample(900_000d, 0.10d, 0.01d, 0.01d)));
        assertEquals(
                CallLinkQualityPolicy.Level.POOR,
                CallLinkQualityPolicy.classify(sample(2_000_000d, 0.55d, 0.01d, 0.01d)));
        assertEquals(
                CallLinkQualityPolicy.Level.CRITICAL,
                CallLinkQualityPolicy.classify(sample(2_000_000d, 0.10d, 0.18d, 0.01d)));
    }

    @Test
    public void requiresTwoBadSamplesAndOnlyDropsOneStepAtATime() {
        final CallLinkQualityPolicy policy = new CallLinkQualityPolicy();
        final CallLinkQualityPolicy.Sample critical = sample(150_000d, 1.0d, 0.20d, 0.20d);

        assertEquals(CallLinkQualityPolicy.Level.GOOD, policy.update(critical, 0L));
        assertEquals(CallLinkQualityPolicy.Level.DEGRADED, policy.update(critical, 2_000L));
        assertEquals(CallLinkQualityPolicy.Level.DEGRADED, policy.update(critical, 4_000L));
        assertEquals(CallLinkQualityPolicy.Level.POOR, policy.update(critical, 6_000L));
    }

    @Test
    public void requiresFiveGoodSamplesBeforeRecovery() {
        final CallLinkQualityPolicy policy = new CallLinkQualityPolicy();
        final CallLinkQualityPolicy.Sample poor = sample(400_000d, 0.6d, 0.10d, 0.09d);
        final CallLinkQualityPolicy.Sample good = sample(2_000_000d, 0.05d, 0.0d, 0.005d);

        policy.update(poor, 0L);
        policy.update(poor, 2_000L);
        assertEquals(CallLinkQualityPolicy.Level.DEGRADED, policy.getLevel());

        for (int i = 0; i < 4; i++) {
            assertEquals(
                    CallLinkQualityPolicy.Level.DEGRADED,
                    policy.update(good, 4_000L + i * 2_000L));
        }
        assertEquals(CallLinkQualityPolicy.Level.GOOD, policy.update(good, 12_000L));
    }

    @Test
    public void handoverImmediatelyCapsVideoAndHoldsRecovery() {
        final CallLinkQualityPolicy policy = new CallLinkQualityPolicy();
        final CallLinkQualityPolicy.Sample good = sample(2_000_000d, 0.05d, 0.0d, 0.005d);

        assertEquals(CallLinkQualityPolicy.Level.DEGRADED, policy.onNetworkHandover(1_000L));

        for (int i = 0; i < 8; i++) {
            assertEquals(
                    CallLinkQualityPolicy.Level.DEGRADED,
                    policy.update(good, 1_500L + i * 1_000L));
        }

        for (int i = 0; i < 4; i++) {
            assertEquals(
                    CallLinkQualityPolicy.Level.DEGRADED,
                    policy.update(good, 10_000L + i * 2_000L));
        }
        assertEquals(CallLinkQualityPolicy.Level.GOOD, policy.update(good, 18_000L));
    }

    @Test
    public void audioSurvivalProfilesKeepFecCapableFloor() {
        assertNull(CallLinkQualityPolicy.Level.GOOD.maxAudioBitrateBps);
        assertFalse(CallLinkQualityPolicy.Level.GOOD.adaptiveAudioPacketTime);

        assertEquals(
                Integer.valueOf(32_000),
                CallLinkQualityPolicy.Level.DEGRADED.maxAudioBitrateBps);
        assertFalse(CallLinkQualityPolicy.Level.DEGRADED.adaptiveAudioPacketTime);

        assertEquals(
                Integer.valueOf(24_000), CallLinkQualityPolicy.Level.POOR.maxAudioBitrateBps);
        assertTrue(CallLinkQualityPolicy.Level.POOR.adaptiveAudioPacketTime);

        assertEquals(
                Integer.valueOf(16_000),
                CallLinkQualityPolicy.Level.CRITICAL.maxAudioBitrateBps);
        assertTrue(CallLinkQualityPolicy.Level.CRITICAL.adaptiveAudioPacketTime);
    }

    @Test
    public void missingStatsDoNotChangeQuality() {
        final CallLinkQualityPolicy policy = new CallLinkQualityPolicy();
        policy.onNetworkHandover(0L);

        assertEquals(
                CallLinkQualityPolicy.Level.DEGRADED,
                policy.update(new CallLinkQualityPolicy.Sample(null, null, null, null), 20_000L));
    }

    private static CallLinkQualityPolicy.Sample sample(
            final Double bandwidth,
            final Double rtt,
            final Double loss,
            final Double jitter) {
        return new CallLinkQualityPolicy.Sample(bandwidth, rtt, loss, jitter);
    }
}
