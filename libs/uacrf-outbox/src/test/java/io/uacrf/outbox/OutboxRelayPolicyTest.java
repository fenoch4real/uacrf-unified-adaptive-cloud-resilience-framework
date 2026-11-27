package io.uacrf.outbox;

import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class OutboxRelayPolicyTest {

    @Test
    void capsExponentialBackoffAtFifteenMinutes() {
        Instant now = Instant.parse("2026-11-26T16:30:00Z");
        assertEquals(now.plusSeconds(1), OutboxRelayPolicy.computeNextAttemptAt(now, 0));
        assertEquals(now.plusSeconds(4), OutboxRelayPolicy.computeNextAttemptAt(now, 2));
        assertEquals(now.plusSeconds(900), OutboxRelayPolicy.computeNextAttemptAt(now, 20));
    }

    @Test
    void rejectsStaleOrExpiredLeaseTokens() {
        Instant now = Instant.parse("2026-11-26T16:30:00Z");
        assertTrue(OutboxRelayPolicy.isLeaseActive("tok-1", "tok-1", now.plusSeconds(10), now));
        assertFalse(OutboxRelayPolicy.isLeaseActive("tok-1", "tok-2", now.plusSeconds(10), now));
        assertFalse(OutboxRelayPolicy.isLeaseActive("tok-1", "tok-1", now.minusSeconds(1), now));
    }
}
