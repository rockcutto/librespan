package eu.siacs.conversations.xmpp.jingle;

import androidx.annotation.Nullable;

/**
 * Small state machine layered on top of libwebrtc congestion control.
 *
 * <p>The native stack remains responsible for moment-to-moment bandwidth estimation. This policy
 * only applies conservative media ceilings when several samples agree that the link is degraded,
 * and restores quality more slowly to avoid oscillation.</p>
 */
final class CallLinkQualityPolicy {

    static final long HANDOVER_RECOVERY_HOLD_MS = 8_000L;

    enum Level {
        GOOD(null, null, null, null, false),
        DEGRADED(900_000, 24, 1.5d, 32_000, false),
        POOR(450_000, 15, 2.0d, 24_000, true),
        CRITICAL(180_000, 10, 3.0d, 16_000, true);

        @Nullable final Integer maxVideoBitrateBps;
        @Nullable final Integer maxVideoFramerate;
        @Nullable final Double scaleResolutionDownBy;
        @Nullable final Integer maxAudioBitrateBps;
        final boolean adaptiveAudioPacketTime;

        Level(
                @Nullable final Integer maxVideoBitrateBps,
                @Nullable final Integer maxVideoFramerate,
                @Nullable final Double scaleResolutionDownBy,
                @Nullable final Integer maxAudioBitrateBps,
                final boolean adaptiveAudioPacketTime) {
            this.maxVideoBitrateBps = maxVideoBitrateBps;
            this.maxVideoFramerate = maxVideoFramerate;
            this.scaleResolutionDownBy = scaleResolutionDownBy;
            this.maxAudioBitrateBps = maxAudioBitrateBps;
            this.adaptiveAudioPacketTime = adaptiveAudioPacketTime;
        }
    }

    static final class Sample {
        @Nullable final Double availableOutgoingBitrateBps;
        @Nullable final Double roundTripTimeSeconds;
        @Nullable final Double packetLossFraction;
        @Nullable final Double jitterSeconds;

        Sample(
                @Nullable final Double availableOutgoingBitrateBps,
                @Nullable final Double roundTripTimeSeconds,
                @Nullable final Double packetLossFraction,
                @Nullable final Double jitterSeconds) {
            this.availableOutgoingBitrateBps = positiveOrNull(availableOutgoingBitrateBps);
            this.roundTripTimeSeconds = nonNegativeOrNull(roundTripTimeSeconds);
            this.packetLossFraction = nonNegativeOrNull(packetLossFraction);
            this.jitterSeconds = nonNegativeOrNull(jitterSeconds);
        }

        boolean hasSignal() {
            return availableOutgoingBitrateBps != null
                    || roundTripTimeSeconds != null
                    || packetLossFraction != null
                    || jitterSeconds != null;
        }

        @Nullable
        private static Double positiveOrNull(@Nullable final Double value) {
            return value != null && Double.isFinite(value) && value > 0d ? value : null;
        }

        @Nullable
        private static Double nonNegativeOrNull(@Nullable final Double value) {
            return value != null && Double.isFinite(value) && value >= 0d ? value : null;
        }
    }

    private Level level = Level.GOOD;
    private int consecutiveBadSamples = 0;
    private int consecutiveGoodSamples = 0;
    private long recoveryHoldUntilElapsedMs = 0L;

    Level getLevel() {
        return level;
    }

    Level onNetworkHandover(final long nowElapsedMs) {
        recoveryHoldUntilElapsedMs =
                Math.max(recoveryHoldUntilElapsedMs, nowElapsedMs + HANDOVER_RECOVERY_HOLD_MS);
        consecutiveBadSamples = 0;
        consecutiveGoodSamples = 0;
        if (level.ordinal() < Level.DEGRADED.ordinal()) {
            level = Level.DEGRADED;
        }
        return level;
    }

    Level update(final Sample sample, final long nowElapsedMs) {
        if (sample == null || !sample.hasSignal()) {
            return level;
        }
        final Level target = classify(sample);
        if (target.ordinal() > level.ordinal()) {
            consecutiveGoodSamples = 0;
            consecutiveBadSamples++;
            if (consecutiveBadSamples >= 2) {
                level = Level.values()[Math.min(level.ordinal() + 1, target.ordinal())];
                consecutiveBadSamples = 0;
            }
        } else if (target.ordinal() < level.ordinal()) {
            consecutiveBadSamples = 0;
            if (nowElapsedMs < recoveryHoldUntilElapsedMs) {
                consecutiveGoodSamples = 0;
                return level;
            }
            consecutiveGoodSamples++;
            if (consecutiveGoodSamples >= 5) {
                level = Level.values()[Math.max(level.ordinal() - 1, target.ordinal())];
                consecutiveGoodSamples = 0;
            }
        } else {
            consecutiveBadSamples = 0;
            consecutiveGoodSamples = 0;
        }
        return level;
    }

    static Level classify(final Sample sample) {
        int severity = Level.GOOD.ordinal();

        final Double bandwidth = sample.availableOutgoingBitrateBps;
        if (bandwidth != null) {
            if (bandwidth < 250_000d) {
                severity = Math.max(severity, Level.CRITICAL.ordinal());
            } else if (bandwidth < 550_000d) {
                severity = Math.max(severity, Level.POOR.ordinal());
            } else if (bandwidth < 1_100_000d) {
                severity = Math.max(severity, Level.DEGRADED.ordinal());
            }
        }

        final Double rtt = sample.roundTripTimeSeconds;
        if (rtt != null) {
            if (rtt >= 0.9d) {
                severity = Math.max(severity, Level.CRITICAL.ordinal());
            } else if (rtt >= 0.5d) {
                severity = Math.max(severity, Level.POOR.ordinal());
            } else if (rtt >= 0.25d) {
                severity = Math.max(severity, Level.DEGRADED.ordinal());
            }
        }

        final Double loss = sample.packetLossFraction;
        if (loss != null) {
            if (loss >= 0.15d) {
                severity = Math.max(severity, Level.CRITICAL.ordinal());
            } else if (loss >= 0.08d) {
                severity = Math.max(severity, Level.POOR.ordinal());
            } else if (loss >= 0.03d) {
                severity = Math.max(severity, Level.DEGRADED.ordinal());
            }
        }

        final Double jitter = sample.jitterSeconds;
        if (jitter != null) {
            if (jitter >= 0.15d) {
                severity = Math.max(severity, Level.CRITICAL.ordinal());
            } else if (jitter >= 0.08d) {
                severity = Math.max(severity, Level.POOR.ordinal());
            } else if (jitter >= 0.04d) {
                severity = Math.max(severity, Level.DEGRADED.ordinal());
            }
        }

        return Level.values()[severity];
    }
}
