package io.uacrf.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable transactional outbox entry committed atomically with PostgreSQL domain state.
 */
public record OutboxEventRecord(
        UUID outboxEventId,
        UUID tenantId,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        String eventType,
        String payloadJson,
        String payloadDigest,
        Instant occurredAt
) {
}
