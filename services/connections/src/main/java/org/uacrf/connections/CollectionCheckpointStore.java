package org.uacrf.connections;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.uacrf.connections.ProviderCollector.Observation;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
class CollectionCheckpointStore {
  private static final String SCHEMA = "uacrf_connections.";
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  CollectionCheckpointStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Transactional
  List<LeasedSource> claimDue(int limit, Duration leaseDuration) {
    if (limit < 1 || leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("Collection batch size and lease duration must be positive.");
    }
    List<LeasedSource> due =
        jdbc.query(
            "SELECT cp.tenant_id, cp.environment_id, cp.connection_id, cp.source_ref, "
                + "cp.checkpoint, cp.last_collected_at, cp.pending_observations, "
                + "cp.pending_next_checkpoint, cp.pending_collected_at, c.provider, "
                + "c.read_only_identity_ref, c.aws_external_id, "
                + "b.last_sequence FROM "
                + SCHEMA
                + "collection_checkpoint cp JOIN "
                + SCHEMA
                + "connection c ON c.tenant_id = cp.tenant_id "
                + "AND c.environment_id = cp.environment_id AND c.id = cp.connection_id JOIN "
                + SCHEMA
                + "source_binding b ON b.tenant_id = cp.tenant_id "
                + "AND b.environment_id = cp.environment_id "
                + "AND b.connection_id = cp.connection_id AND b.source_ref = cp.source_ref "
                + "WHERE c.status = 'ACTIVE' AND cp.next_collection_at <= now() "
                + "AND (cp.lease_until IS NULL OR cp.lease_until <= now()) "
                + "ORDER BY cp.next_collection_at, cp.tenant_id, cp.environment_id, "
                + "cp.connection_id, cp.source_ref LIMIT ? FOR UPDATE OF cp SKIP LOCKED",
            (rs, row) -> {
              Timestamp lastCollected = rs.getTimestamp("last_collected_at");
              Timestamp pendingCollected = rs.getTimestamp("pending_collected_at");
              String pendingJson = rs.getString("pending_observations");
              return new LeasedSource(
                  rs.getString("tenant_id"),
                  rs.getString("environment_id"),
                  rs.getString("connection_id"),
                  rs.getString("source_ref"),
                  rs.getString("provider"),
                  rs.getString("read_only_identity_ref"),
                  rs.getString("aws_external_id"),
                  rs.getString("checkpoint"),
                  lastCollected == null ? null : lastCollected.toInstant(),
                  rs.getLong("last_sequence"),
                  UUID.randomUUID(),
                  pendingJson == null ? null : deserialize(pendingJson),
                  rs.getString("pending_next_checkpoint"),
                  pendingCollected == null ? null : pendingCollected.toInstant());
            },
            limit);
    for (LeasedSource source : due) {
      int leased =
          jdbc.update(
              "UPDATE "
                  + SCHEMA
                  + "collection_checkpoint SET lease_token = ?, lease_until = now() + "
                  + "(? * interval '1 millisecond'), updated_at = now() WHERE tenant_id = ? "
                  + "AND environment_id = ? AND connection_id = ? AND source_ref = ?",
              source.leaseToken(),
              leaseDuration.toMillis(),
              source.tenantId(),
              source.environmentId(),
              source.connectionId(),
              source.sourceRef());
      if (leased != 1) {
        throw new IllegalStateException("Could not retain a collection lease.");
      }
    }
    return due;
  }

  @Transactional
  void renew(LeasedSource source, Duration leaseDuration) {
    if (leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("Collection lease duration must be positive.");
    }
    int updated =
        jdbc.update(
            "UPDATE "
                + SCHEMA
                + "collection_checkpoint SET lease_until = now() + "
                + "(? * interval '1 millisecond'), updated_at = now() WHERE tenant_id = ? "
                + "AND environment_id = ? AND connection_id = ? AND source_ref = ? "
                + "AND lease_token = ?",
            leaseDuration.toMillis(),
            source.tenantId(),
            source.environmentId(),
            source.connectionId(),
            source.sourceRef(),
            source.leaseToken());
    if (updated != 1) {
      throw new IllegalStateException("The collection lease expired before it could be renewed.");
    }
  }

  @Transactional
  void storePending(
      LeasedSource source,
      List<Observation> observations,
      String nextCheckpoint,
      Instant collectedAt) {
    if (observations == null || collectedAt == null) {
      throw new IllegalArgumentException("Pending observations and collection time are required.");
    }
    String serialized;
    try {
      serialized = objectMapper.writeValueAsString(observations);
    } catch (JacksonException error) {
      throw new IllegalStateException("Could not retain the pending collection batch.", error);
    }
    int updated =
        jdbc.update(
            "UPDATE "
                + SCHEMA
                + "collection_checkpoint SET pending_observations = CAST(? AS jsonb), "
                + "pending_next_checkpoint = ?, pending_collected_at = ?, updated_at = now() "
                + "WHERE tenant_id = ? AND environment_id = ? AND connection_id = ? "
                + "AND source_ref = ? AND lease_token = ? AND pending_observations IS NULL",
            serialized,
            nextCheckpoint,
            Timestamp.from(collectedAt),
            source.tenantId(),
            source.environmentId(),
            source.connectionId(),
            source.sourceRef(),
            source.leaseToken());
    if (updated != 1) {
      throw new IllegalStateException("Could not retain the pending batch under its collection lease.");
    }
  }

  @Transactional
  void complete(
      LeasedSource source,
      String status,
      String detail,
      String nextCheckpoint,
      Instant collectedAt,
      Instant nextCollectionAt,
      boolean advanceCheckpoint) {
    String update =
        "UPDATE "
            + SCHEMA
            + "collection_checkpoint SET checkpoint = ?, last_collected_at = ?, "
            + "next_collection_at = ?, lease_token = NULL, lease_until = NULL, "
            + "pending_observations = CASE WHEN ? THEN NULL ELSE pending_observations END, "
            + "pending_next_checkpoint = CASE WHEN ? THEN NULL ELSE pending_next_checkpoint END, "
            + "pending_collected_at = CASE WHEN ? THEN NULL ELSE pending_collected_at END, "
            + "last_status = ?, last_detail = ?, updated_at = now() WHERE tenant_id = ? "
            + "AND environment_id = ? AND connection_id = ? AND source_ref = ? AND lease_token = ?";
    int updated =
        jdbc.update(
            update,
            advanceCheckpoint ? nextCheckpoint : source.checkpoint(),
            advanceCheckpoint && collectedAt != null ? Timestamp.from(collectedAt) : source.lastCollectedAt(),
            Timestamp.from(nextCollectionAt),
            advanceCheckpoint,
            advanceCheckpoint,
            advanceCheckpoint,
            status,
            detail,
            source.tenantId(),
            source.environmentId(),
            source.connectionId(),
            source.sourceRef(),
            source.leaseToken());
    if (updated != 1) {
      throw new IllegalStateException("The collection lease expired before its result was retained.");
    }
  }

  private List<Observation> deserialize(String json) {
    try {
      return objectMapper.readValue(json, new TypeReference<List<Observation>>() {});
    } catch (JacksonException error) {
      throw new IllegalStateException("Stored pending collection batch is invalid.", error);
    }
  }

  record LeasedSource(
      String tenantId,
      String environmentId,
      String connectionId,
      String sourceRef,
      String provider,
      String readOnlyIdentityRef,
      String awsExternalId,
      String checkpoint,
      Instant lastCollectedAt,
      long lastSequence,
      UUID leaseToken,
      List<Observation> pendingObservations,
      String pendingNextCheckpoint,
      Instant pendingCollectedAt) {}
}
