package io.uacrf.outbox;

import java.time.Duration;
import java.time.Instant;

/**
 * Deterministic bounded exponential backoff and lease-token validation for outbox and collector workers.
 */
public final class OutboxRelayPolicy {

    private static final Duration BASE_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_DELAY = Duration.ofMinutes(15);

    private OutboxRelayPolicy() {
    }

    public static Instant computeNextAttemptAt(Instant now, int attemptCount) {
        int clampedExponent = Math.min(Math.max(attemptCount, 0), 10);
        long delaySeconds = Math.min(BASE_DELAY.toSeconds() << clampedExponent, MAX_DELAY.toSeconds());
        return now.plusSeconds(delaySeconds);
    }

    public static boolean isLeaseActive(String expectedToken, String actualToken, Instant leaseExpiresAt, Instant now) {
        return expectedToken != null
                && expectedToken.equals(actualToken)
                && leaseExpiresAt != null
                && leaseExpiresAt.isAfter(now);
    }
}
