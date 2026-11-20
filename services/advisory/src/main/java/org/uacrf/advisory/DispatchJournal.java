package org.uacrf.advisory;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

final class DispatchJournal {
  private final DataSource dataSource;

  DispatchJournal(DataSource dataSource) {
    this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
  }

  record Key(String tenantId, String environmentId, String attemptId) {
    Key {
      requireId(tenantId);
      requireId(environmentId);
      requireId(attemptId);
    }
  }

  record RunKey(String tenantId, String environmentId, String runId) {
    RunKey {
      requireId(tenantId);
      requireId(environmentId);
      requireId(runId);
    }
  }

  record Intent(Key key, String runId, String assignmentId, long generation, Instant runDeadline,
                AdvisoryBudget.Role role, String profileId, AdvisoryGateway.Request request,
                String contextDigest, InputPacket packet) {
    Intent {
      java.util.Objects.requireNonNull(key, "key");
      java.util.Objects.requireNonNull(packet, "packet");
      java.util.Objects.requireNonNull(runDeadline, "runDeadline");
      java.util.Objects.requireNonNull(role, "role");
      java.util.Objects.requireNonNull(request, "request");
      requireId(runId);
      requireId(assignmentId);
      requireId(profileId);
      if (generation < 1 || !key.attemptId().equals(request.attemptId())
          || contextDigest == null || !contextDigest.equals(packet.bytesDigest())
          || !UnicodeText.isValid(request.context())
          || !Arrays.equals(packet.bytes(), request.context().getBytes(StandardCharsets.UTF_8))) {
        throw new IllegalArgumentException("Dispatch request, context digest, and packet bytes must match.");
      }
    }

    RunKey runKey() {
      return new RunKey(key.tenantId(), key.environmentId(), runId);
    }
  }

  enum RecordingFence {
    ACTIVE, CANCELLED, EXPIRED
  }

  enum ReportedTokenStatus {
    UNAVAILABLE, WITHIN_ALLOCATION, EXCEEDS_ALLOCATION
  }

  enum RunUsageStatus {
    UNAVAILABLE, WITHIN_BUDGET, EXCEEDS_BUDGET
  }

  record RunUsage(long generation, long reportedTokens, int maxTokens, long responses,
                  long unknownOutcomes, long unresolvedIntents, RunUsageStatus status) {}

  record Entry(Intent intent, Optional<AdvisoryGateway.Outcome> outcome,
               Optional<RecordingFence> recordingFence, int tokenAllocation) {
    ReportedTokenStatus reportedTokenStatus() {
      if (outcome.orElse(null) instanceof AdvisoryGateway.Response response) {
        return (long) response.inputTokens() + response.outputTokens() > tokenAllocation
            ? ReportedTokenStatus.EXCEEDS_ALLOCATION : ReportedTokenStatus.WITHIN_ALLOCATION;
      }
      return ReportedTokenStatus.UNAVAILABLE;
    }
  }

  record RunSnapshot(long generation, long revision, Instant deadline, boolean cancelled, String cancelledBy,
                     String cancellationReason) {}

  enum AdmissionStatus {
    CREATED, REPLAYED, IDEMPOTENCY_CONFLICT, RUN_ID_CONFLICT
  }

  record AdmissionResult(AdmissionStatus status, Optional<RunKey> runKey) {
    AdmissionResult {
      Objects.requireNonNull(status, "status");
      runKey = Objects.requireNonNull(runKey, "runKey");
      boolean hasRun = status == AdmissionStatus.CREATED || status == AdmissionStatus.REPLAYED;
      if (runKey.isPresent() != hasRun) {
        throw new IllegalArgumentException("Admission result has an invalid run identity.");
      }
    }
  }

  record OutboxClaim(String eventId, String tenantId, String environmentId, String runId, long generation,
                     String eventType, String payloadJson, UUID claimToken, int attemptCount) {
    OutboxClaim {
      requireId(eventId);
      requireId(tenantId);
      requireId(environmentId);
      requireId(runId);
      requireId(eventType);
      Objects.requireNonNull(payloadJson, "payloadJson");
      Objects.requireNonNull(claimToken, "claimToken");
      if (generation < 1 || attemptCount < 1) {
        throw new IllegalArgumentException("Invalid outbox claim.");
      }
    }
  }

  enum UnresolvedState {
    DISPATCH_INTENT, UNKNOWN
  }

  record Unresolved(Key key, UnresolvedState state) {}

  // Reported usage is diagnostic only; unknown outcomes do not count as zero or authorize retries.
  Optional<RunUsage> runUsage(RunKey key, long generation) throws SQLException {
    java.util.Objects.requireNonNull(key, "key");
    if (generation < 1) {
      throw new IllegalArgumentException("Run generation must be positive.");
    }
    String sql = """
        SELECT fence.state, fence.max_tokens,
               count(journal.attempt_id) FILTER (WHERE journal.state = 'RESPONSE'),
               coalesce(sum(journal.input_tokens::bigint + journal.output_tokens::bigint)
                 FILTER (WHERE journal.state = 'RESPONSE'), 0),
               count(journal.attempt_id) FILTER (WHERE journal.state = 'UNKNOWN'),
               count(journal.attempt_id) FILTER (WHERE journal.state = 'DISPATCH_INTENT')
        FROM uacrf_advisory.run_fence fence
        LEFT JOIN uacrf_advisory.dispatch_journal journal
          ON journal.tenant_id = fence.tenant_id AND journal.environment_id = fence.environment_id
          AND journal.run_id = fence.run_id AND journal.generation = fence.generation
        WHERE fence.tenant_id = ? AND fence.environment_id = ? AND fence.run_id = ? AND fence.generation = ?
        GROUP BY fence.state, fence.max_tokens
        """;
    try (Connection connection = committedConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, key.tenantId());
      statement.setString(2, key.environmentId());
      statement.setString(3, key.runId());
      statement.setLong(4, generation);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return Optional.empty();
        }
        if ("DRAFT".equals(result.getString(1))) {
          throw new SQLException("Unactivated run fence cannot be used.");
        }
        long reportedTokens = result.getLong(4);
        int maxTokens = result.getInt(2);
        long unknownOutcomes = result.getLong(5);
        long unresolvedIntents = result.getLong(6);
        RunUsageStatus status = reportedTokens > maxTokens ? RunUsageStatus.EXCEEDS_BUDGET
            : unknownOutcomes > 0 || unresolvedIntents > 0 ? RunUsageStatus.UNAVAILABLE
            : RunUsageStatus.WITHIN_BUDGET;
        return Optional.of(new RunUsage(generation, reportedTokens, maxTokens, result.getLong(3),
            unknownOutcomes, unresolvedIntents, status));
      }
    }
  }

  // This snapshot identifies uncertainty; it does not authorize retries or resolve provider usage.
  List<Unresolved> unresolved(RunKey key, long generation) throws SQLException {
    java.util.Objects.requireNonNull(key, "key");
    if (generation < 1) {
      throw new IllegalArgumentException("Run generation must be positive.");
    }
    String sql = """
        SELECT attempt_id, state FROM uacrf_advisory.dispatch_journal
        WHERE tenant_id = ? AND environment_id = ? AND run_id = ? AND generation = ?
          AND state IN ('DISPATCH_INTENT', 'UNKNOWN')
        ORDER BY attempt_id
        LIMIT 4
        """;
    try (Connection connection = committedConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, key.tenantId());
      statement.setString(2, key.environmentId());
      statement.setString(3, key.runId());
      statement.setLong(4, generation);
      try (ResultSet result = statement.executeQuery()) {
        List<Unresolved> entries = new ArrayList<>();
        while (result.next()) {
          if (entries.size() == 3) {
            throw new SQLException("Run has more unresolved attempts than supported allocations.");
          }
          entries.add(new Unresolved(new Key(key.tenantId(), key.environmentId(), result.getString(1)),
              UnresolvedState.valueOf(result.getString(2))));
        }
        return List.copyOf(entries);
      }
    }
  }

  boolean registerRun(RunKey key, long generation, AdvisoryBudget budget, Instant deadline,
                      List<Intent> dispatchPlan) throws SQLException {
    validateRunAdmission(key, generation, budget, deadline);
    List<Intent> plan = validateDispatchPlan(key, generation, budget, deadline, dispatchPlan);
    try (Connection connection = committedConnection()) {
      connection.setAutoCommit(false);
      try {
        if (!insertDraftRun(connection, key, generation, budget, deadline)) {
          connection.rollback();
          return false;
        }
        insertDispatchPlan(connection, plan);
        activateRun(connection, key);
        connection.commit();
        return true;
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        throw failure;
      }
    }
  }

  AdmissionResult admitRun(RunKey key, long generation, AdvisoryRunRequest request, List<Intent> dispatchPlan,
                           RequesterAccessClient.Principal requester, String operation, String idempotencyKey)
      throws SQLException {
    Objects.requireNonNull(request, "request");
    validateRunAdmission(key, generation, request.budget(), request.deadline());
    Objects.requireNonNull(requester, "requester");
    if (!"HUMAN".equals(requester.kind())
        || !requester.tenantId().equals(key.tenantId())
        || !requester.environmentId().equals(key.environmentId())
        || !requester.capabilities().contains("advisory.run.create")) {
      throw new IllegalArgumentException("Requester authorization does not match the run scope.");
    }
    String principalId = requester.id();
    requireId(principalId);
    requireId(operation);
    validateIdempotencyKey(idempotencyKey);
    String requestDigest = request.requestDigest();

    try (Connection connection = committedConnection()) {
      connection.setAutoCommit(false);
      try {
        AdmissionResult existing = findAdmission(
            connection, key.tenantId(), key.environmentId(), principalId, operation, idempotencyKey, requestDigest);
        if (existing != null) {
          connection.commit();
          return existing;
        }
        List<Intent> plan = validateDispatchPlan(
            key, generation, request.budget(), request.deadline(), dispatchPlan);
        if (!insertDraftRun(connection, key, generation, request.budget(), request.deadline())) {
          connection.rollback();
          return findAdmissionAfterRace(key, principalId, operation, idempotencyKey, requestDigest);
        }
        insertDispatchPlan(connection, plan);

        String idempotencySql = """
            INSERT INTO uacrf_advisory.run_idempotency
              (tenant_id, environment_id, principal_id, operation, idempotency_key, request_digest, run_id)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;
        try (PreparedStatement statement = connection.prepareStatement(idempotencySql)) {
          statement.setString(1, key.tenantId());
          statement.setString(2, key.environmentId());
          statement.setString(3, principalId);
          statement.setString(4, operation);
          statement.setString(5, idempotencyKey);
          statement.setString(6, requestDigest);
          statement.setString(7, key.runId());
          if (statement.executeUpdate() == 0) {
            connection.rollback();
            AdmissionResult raced = findAdmissionAfterRace(key, principalId, operation, idempotencyKey, requestDigest);
            if (raced.status() == AdmissionStatus.RUN_ID_CONFLICT) {
              throw new SQLException("Idempotency record disappeared after a conflicting insert.");
            }
            return raced;
          }
        }

        String outboxSql = """
            INSERT INTO uacrf_advisory.outbox_event
              (event_id, tenant_id, environment_id, run_id, generation, event_type, payload, state)
            VALUES (?, ?, ?, ?, ?, 'uacrf.s09.run.admitted.v1',
              jsonb_build_object('run_id', ?, 'generation', ?, 'request_digest', ?), 'PENDING')
            """;
        String eventId = UUID.randomUUID().toString();
        try (PreparedStatement statement = connection.prepareStatement(outboxSql)) {
          statement.setString(1, eventId);
          statement.setString(2, key.tenantId());
          statement.setString(3, key.environmentId());
          statement.setString(4, key.runId());
          statement.setLong(5, generation);
          statement.setString(6, key.runId());
          statement.setLong(7, generation);
          statement.setString(8, requestDigest);
          statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO uacrf_advisory.outbox_delivery (event_id, state, attempt_count)
            VALUES (?, 'PENDING', 0)
            """)) {
          statement.setString(1, eventId);
          statement.executeUpdate();
        }
        activateRun(connection, key);
        connection.commit();
        return new AdmissionResult(AdmissionStatus.CREATED, Optional.of(key));
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        throw failure;
      }
    }
  }

  List<OutboxClaim> claimPendingOutbox(String tenantId, String environmentId, int batchSize, Duration lease)
      throws SQLException {
    requireId(tenantId);
    requireId(environmentId);
    Objects.requireNonNull(lease, "lease");
    if (batchSize < 1 || batchSize > 100 || lease.isNegative() || lease.isZero()
        || lease.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalArgumentException("Invalid outbox claim limits.");
    }
    long leaseMillis = lease.toMillis();
    if (leaseMillis < 1) {
      throw new IllegalArgumentException("Outbox claim lease must be at least one millisecond.");
    }
    String sql = """
        WITH candidates AS MATERIALIZED (
          SELECT delivery.event_id
          FROM uacrf_advisory.outbox_delivery delivery
          JOIN uacrf_advisory.outbox_event event ON event.event_id = delivery.event_id
          JOIN uacrf_advisory.run_fence fence
            ON fence.tenant_id = event.tenant_id AND fence.environment_id = event.environment_id
            AND fence.run_id = event.run_id AND fence.generation = event.generation
          WHERE event.tenant_id = ? AND event.environment_id = ? AND event.state = 'PENDING'
            AND fence.state = 'ACTIVE'
            AND extract(epoch FROM clock_timestamp()) * 1000000000
              < fence.deadline_epoch_second::numeric * 1000000000 + fence.deadline_nano
            AND ((delivery.state = 'PENDING' AND delivery.available_at <= clock_timestamp())
              OR (delivery.state = 'CLAIMED' AND delivery.lease_expires_at <= clock_timestamp()))
          ORDER BY event.created_at, event.event_id
          LIMIT ?
          FOR UPDATE OF delivery, fence SKIP LOCKED
        ), claimed AS (
          UPDATE uacrf_advisory.outbox_delivery delivery
          SET state = 'CLAIMED', attempt_count = delivery.attempt_count + 1,
              claim_token = gen_random_uuid(),
              lease_expires_at = clock_timestamp() + make_interval(secs => ?::double precision / 1000.0)
          FROM candidates
          WHERE delivery.event_id = candidates.event_id
          RETURNING delivery.event_id, delivery.claim_token, delivery.attempt_count
        )
        SELECT event.event_id, event.tenant_id, event.environment_id, event.run_id, event.generation,
               event.event_type, event.payload::text AS payload, claimed.claim_token, claimed.attempt_count
        FROM claimed
        JOIN uacrf_advisory.outbox_event event ON event.event_id = claimed.event_id
        ORDER BY event.created_at, event.event_id
        """;
    try (Connection connection = committedConnection()) {
      connection.setAutoCommit(false);
      try {
        List<OutboxClaim> claims = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
          statement.setString(1, tenantId);
          statement.setString(2, environmentId);
          statement.setInt(3, batchSize);
          statement.setLong(4, leaseMillis);
          try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
              claims.add(new OutboxClaim(result.getString("event_id"), result.getString("tenant_id"),
                  result.getString("environment_id"), result.getString("run_id"), result.getLong("generation"),
                  result.getString("event_type"), result.getString("payload"),
                  UUID.fromString(result.getString("claim_token")), result.getInt("attempt_count")));
            }
          }
        }
        connection.commit();
        return List.copyOf(claims);
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        throw failure;
      }
    }
  }

  boolean renewOutboxClaim(OutboxClaim claim, Duration extension) throws SQLException {
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(extension, "extension");
    if (extension.isNegative() || extension.isZero()
        || extension.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalArgumentException("Invalid outbox lease extension.");
    }
    long extensionMillis = extension.toMillis();
    if (extensionMillis < 1) {
      throw new IllegalArgumentException("Outbox lease extension must be at least one millisecond.");
    }
    try (Connection connection = committedConnection()) {
      connection.setAutoCommit(false);
      try {
        if (!lockActiveOutboxRun(connection, claim)) {
          connection.commit();
          return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
          UPDATE uacrf_advisory.outbox_delivery AS delivery
          SET lease_expires_at = LEAST(
            delivery.lease_expires_at + make_interval(secs => ?::double precision / 1000.0),
            clock_timestamp() + INTERVAL '5 minutes')
          FROM uacrf_advisory.outbox_event event
          JOIN uacrf_advisory.run_fence fence
            ON fence.tenant_id = event.tenant_id AND fence.environment_id = event.environment_id
            AND fence.run_id = event.run_id AND fence.generation = event.generation
          WHERE delivery.event_id = ? AND event.event_id = delivery.event_id
            AND event.tenant_id = ? AND event.environment_id = ? AND event.run_id = ?
            AND event.generation = ? AND event.event_type = ?
            AND fence.state = 'ACTIVE'
            AND extract(epoch FROM clock_timestamp()) * 1000000000
              < fence.deadline_epoch_second::numeric * 1000000000 + fence.deadline_nano
            AND delivery.state = 'CLAIMED' AND delivery.claim_token = ?
            AND delivery.lease_expires_at > clock_timestamp()
          """)) {
          statement.setLong(1, extensionMillis);
          bindOutboxClaim(statement, claim, 2);
          boolean renewed = statement.executeUpdate() == 1;
          connection.commit();
          return renewed;
        }
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        throw failure;
      }
    }
  }

  boolean releaseOutboxClaim(OutboxClaim claim, Duration retryDelay) throws SQLException {
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(retryDelay, "retryDelay");
    if (retryDelay.isNegative() || retryDelay.isZero()
        || retryDelay.compareTo(outboxRetryDelay(claim)) < 0
        || retryDelay.compareTo(Duration.ofMinutes(15)) > 0) {
      throw new IllegalArgumentException("Invalid outbox retry delay.");
    }
    long retryDelayMillis = retryDelay.toMillis();
    if (retryDelayMillis < 1) {
      throw new IllegalArgumentException("Outbox retry delay must be at least one millisecond.");
    }
    try (Connection connection = committedConnection()) {
      connection.setAutoCommit(false);
      try {
        if (!lockActiveOutboxRun(connection, claim)) {
          connection.commit();
          return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
          UPDATE uacrf_advisory.outbox_delivery AS delivery
          SET state = 'PENDING', claim_token = NULL, lease_expires_at = NULL,
              available_at = clock_timestamp()
                + make_interval(secs => ?::double precision / 1000.0)
          FROM uacrf_advisory.outbox_event event
          JOIN uacrf_advisory.run_fence fence
            ON fence.tenant_id = event.tenant_id AND fence.environment_id = event.environment_id
            AND fence.run_id = event.run_id AND fence.generation = event.generation
          WHERE delivery.event_id = ? AND event.event_id = delivery.event_id
            AND event.tenant_id = ? AND event.environment_id = ? AND event.run_id = ?
            AND event.generation = ? AND event.event_type = ?
            AND fence.state = 'ACTIVE'
            AND extract(epoch FROM clock_timestamp()) * 1000000000
              < fence.deadline_epoch_second::numeric * 1000000000 + fence.deadline_nano
            AND delivery.state = 'CLAIMED' AND delivery.claim_token = ?
            AND delivery.lease_expires_at > clock_timestamp()
          """)) {
          statement.setLong(1, retryDelayMillis);
          bindOutboxClaim(statement, claim, 2);
          boolean released = statement.executeUpdate() == 1;
          connection.commit();
          return released;
        }
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        throw failure;
      }
    }
  }

  static Duration outboxRetryBackoff(int attemptCount) {
    if (attemptCount < 1) {
      throw new IllegalArgumentException("Outbox attempt count must be positive.");
    }
    int exponent = Math.min(attemptCount - 1, 10);
    return Duration.ofSeconds(Math.min(1L << exponent, Duration.ofMinutes(15).toSeconds()));
  }

  static Duration outboxRetryDelay(OutboxClaim claim) {
    Objects.requireNonNull(claim, "claim");
    long baseSeconds = outboxRetryBackoff(claim.attemptCount()).toSeconds();
    long jitterWindowMillis = Math.min(baseSeconds * 500,
        (Duration.ofMinutes(15).toSeconds() - baseSeconds) * 1_000);
    long retryTokenBits = claim.claimToken().getLeastSignificantBits() & 0xffff_ffffL;
    long jitterMillis = retryTokenBits % (jitterWindowMillis + 1);
    return Duration.ofMillis(baseSeconds * 1_000 + jitterMillis);
  }

  boolean markOutboxPublished(OutboxClaim claim) throws SQLException {
    Objects.requireNonNull(claim, "claim");
    try (Connection connection = committedConnection()) {
      connection.setAutoCommit(false);
      try {
        if (!lockActiveOutboxRun(connection, claim)) {
          connection.commit();
          return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE uacrf_advisory.outbox_delivery AS delivery
            SET state = 'PUBLISHED', claim_token = NULL, lease_expires_at = NULL,
                published_at = clock_timestamp()
            FROM uacrf_advisory.outbox_event event
            JOIN uacrf_advisory.run_fence fence
              ON fence.tenant_id = event.tenant_id AND fence.environment_id = event.environment_id
              AND fence.run_id = event.run_id AND fence.generation = event.generation
            WHERE delivery.event_id = ? AND event.event_id = delivery.event_id
              AND event.tenant_id = ? AND event.environment_id = ? AND event.run_id = ?
              AND event.generation = ? AND event.event_type = ?
              AND fence.state = 'ACTIVE'
              AND extract(epoch FROM clock_timestamp()) * 1000000000
                < fence.deadline_epoch_second::numeric * 1000000000 + fence.deadline_nano
              AND delivery.state = 'CLAIMED' AND delivery.claim_token = ?
              AND delivery.lease_expires_at > clock_timestamp()
            """)) {
          bindOutboxClaim(statement, claim, 1);
          if (statement.executeUpdate() != 1) {
            connection.rollback();
            return false;
          }
        }
        connection.commit();
        return true;
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        throw failure;
      }
    }
  }

  private static boolean lockActiveOutboxRun(Connection connection, OutboxClaim claim) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT state = 'ACTIVE'
          AND extract(epoch FROM clock_timestamp()) * 1000000000
            < deadline_epoch_second::numeric * 1000000000 + deadline_nano
        FROM uacrf_advisory.run_fence
        WHERE tenant_id = ? AND environment_id = ? AND run_id = ? AND generation = ?
        FOR UPDATE
        """)) {
      statement.setString(1, claim.tenantId());
      statement.setString(2, claim.environmentId());
      statement.setString(3, claim.runId());
      statement.setLong(4, claim.generation());
      try (ResultSet result = statement.executeQuery()) {
        return result.next() && result.getBoolean(1);
      }
    }
  }

  private AdmissionResult findAdmissionAfterRace(
      RunKey requestedKey, String principalId, String operation, String idempotencyKey, String requestDigest)
      throws SQLException {
    try (Connection connection = committedConnection()) {
      AdmissionResult existing = findAdmission(connection, requestedKey.tenantId(), requestedKey.environmentId(),
          principalId, operation, idempotencyKey, requestDigest);
      return existing == null
          ? new AdmissionResult(AdmissionStatus.RUN_ID_CONFLICT, Optional.empty())
          : existing;
    }
  }

  private AdmissionResult findAdmission(
      Connection connection, String tenantId, String environmentId, String principalId, String operation,
      String idempotencyKey, String requestDigest) throws SQLException {
    String sql = """
        SELECT request_digest, run_id FROM uacrf_advisory.run_idempotency
        WHERE tenant_id = ? AND environment_id = ? AND principal_id = ? AND operation = ? AND idempotency_key = ?
        FOR UPDATE
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenantId);
      statement.setString(2, environmentId);
      statement.setString(3, principalId);
      statement.setString(4, operation);
      statement.setString(5, idempotencyKey);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return null;
        }
        if (!requestDigest.equals(result.getString(1))) {
          return new AdmissionResult(AdmissionStatus.IDEMPOTENCY_CONFLICT, Optional.empty());
        }
        RunKey retainedKey = new RunKey(tenantId, environmentId, result.getString(2));
        return new AdmissionResult(AdmissionStatus.REPLAYED, Optional.of(retainedKey));
      }
    }
  }

  private static void validateRunAdmission(RunKey key, long generation, AdvisoryBudget budget, Instant deadline) {
    Objects.requireNonNull(key, "key");
    if (generation < 1) {
      throw new IllegalArgumentException("Run generation must be positive.");
    }
    Objects.requireNonNull(budget, "budget");
    Objects.requireNonNull(deadline, "deadline");
  }

  private static void validateIdempotencyKey(String key) {
    if (key == null || key.isBlank() || key.indexOf('\0') >= 0
        || key.codePointCount(0, key.length()) > 200) {
      throw new IllegalArgumentException("Idempotency key must contain 1 to 200 non-whitespace characters.");
    }
  }

  private static List<Intent> validateDispatchPlan(
      RunKey key, long generation, AdvisoryBudget budget, Instant deadline, List<Intent> dispatchPlan) {
    Objects.requireNonNull(dispatchPlan, "dispatchPlan");
    Map<String, AdvisoryBudget.Allocation> allocations = new HashMap<>();
    for (var allocation : budget.allocations()) {
      allocations.put(allocation.agentProfileId(), allocation);
    }
    Set<String> profiles = new HashSet<>();
    Set<String> attempts = new HashSet<>();
    Set<String> assignments = new HashSet<>();
    if (dispatchPlan.size() != allocations.size()) {
      throw new IllegalArgumentException("Dispatch plan must contain one intent per run allocation.");
    }
    for (Intent intent : dispatchPlan) {
      Objects.requireNonNull(intent, "dispatch plan intent");
      AdvisoryBudget.Allocation allocation = allocations.get(intent.profileId());
      if (!intent.runKey().equals(key) || intent.generation() != generation
          || !intent.runDeadline().equals(deadline) || allocation == null
          || intent.role() != allocation.role() || intent.request().maxOutputTokens() > allocation.tokens()
          || !profiles.add(intent.profileId()) || !attempts.add(intent.key().attemptId())
          || !assignments.add(intent.assignmentId())) {
        throw new IllegalArgumentException("Dispatch plan must exactly cover the admitted run allocations.");
      }
    }
    return List.copyOf(dispatchPlan);
  }

  private static void insertDispatchPlan(Connection connection, List<Intent> plan) throws SQLException {
    String sql = """
        INSERT INTO uacrf_advisory.run_dispatch_plan
          (tenant_id, environment_id, run_id, generation, assignment_id, attempt_id, role, profile_id,
           model_ref, max_output_tokens, timeout_seconds, timeout_nanos, context_digest, packet_bytes,
           packet_bytes_digest)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (Intent intent : plan) {
        statement.setString(1, intent.key().tenantId());
        statement.setString(2, intent.key().environmentId());
        statement.setString(3, intent.runId());
        statement.setLong(4, intent.generation());
        statement.setString(5, intent.assignmentId());
        statement.setString(6, intent.key().attemptId());
        statement.setString(7, intent.role().name());
        statement.setString(8, intent.profileId());
        statement.setString(9, intent.request().model());
        statement.setInt(10, intent.request().maxOutputTokens());
        statement.setLong(11, intent.request().timeout().getSeconds());
        statement.setInt(12, intent.request().timeout().getNano());
        statement.setString(13, intent.contextDigest());
        statement.setBytes(14, intent.packet().bytes());
        statement.setString(15, intent.packet().bytesDigest());
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  private static boolean insertDraftRun(
      Connection connection, RunKey key, long generation, AdvisoryBudget budget, Instant deadline)
      throws SQLException {
    String runSql = """
        INSERT INTO uacrf_advisory.run_fence
          (tenant_id, environment_id, run_id, generation, max_calls, max_tokens, max_cost_units,
           deadline_epoch_second, deadline_nano, dispatch_plan_required, state, revision)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', 1)
        ON CONFLICT DO NOTHING
        """;
    try (PreparedStatement statement = connection.prepareStatement(runSql)) {
      statement.setString(1, key.tenantId());
      statement.setString(2, key.environmentId());
      statement.setString(3, key.runId());
      statement.setLong(4, generation);
      statement.setInt(5, budget.maxCalls());
      statement.setInt(6, budget.maxTokens());
      statement.setInt(7, budget.maxCostUnits());
      statement.setLong(8, deadline.getEpochSecond());
      statement.setInt(9, deadline.getNano());
      statement.setBoolean(10, true);
      if (statement.executeUpdate() == 0) {
        return false;
      }
    }

    String allocationSql = """
        INSERT INTO uacrf_advisory.run_allocation
          (tenant_id, environment_id, run_id, generation, role, profile_id, tokens, cost_units)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(allocationSql)) {
      for (var allocation : budget.allocations()) {
        statement.setString(1, key.tenantId());
        statement.setString(2, key.environmentId());
        statement.setString(3, key.runId());
        statement.setLong(4, generation);
        statement.setString(5, allocation.role().name());
        statement.setString(6, allocation.agentProfileId());
        statement.setInt(7, allocation.tokens());
        statement.setInt(8, allocation.costUnits());
        statement.addBatch();
      }
      statement.executeBatch();
    }
    return true;
  }

  private static void activateRun(Connection connection, RunKey key) throws SQLException {
    String activateSql = """
        UPDATE uacrf_advisory.run_fence SET state = 'ACTIVE'
        WHERE tenant_id = ? AND environment_id = ? AND run_id = ? AND state = 'DRAFT'
        """;
    try (PreparedStatement statement = connection.prepareStatement(activateSql)) {
      statement.setString(1, key.tenantId());
      statement.setString(2, key.environmentId());
      statement.setString(3, key.runId());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("Run activation failed.");
      }
    }
  }

  boolean cancel(RunKey key, long generation, long revision, String actor, String reason)
      throws SQLException {
    requireId(actor);
    if (generation < 1 || revision < 1 || reason == null || reason.isBlank() || reason.length() > 2000) {
      throw new IllegalArgumentException("Invalid cancellation.");
    }
    String sql = """
        UPDATE uacrf_advisory.run_fence
        SET state = 'CANCELLED', revision = 2, cancelled_by = ?, cancellation_reason = ?,
            cancelled_at = clock_timestamp()
        WHERE tenant_id = ? AND environment_id = ? AND run_id = ?
          AND generation = ? AND revision = ? AND state = 'ACTIVE'
        """;
    try (Connection connection = committedConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, actor);
      statement.setString(2, reason);
      statement.setString(3, key.tenantId());
      statement.setString(4, key.environmentId());
      statement.setString(5, key.runId());
      statement.setLong(6, generation);
      statement.setLong(7, revision);
      return statement.executeUpdate() == 1;
    }
  }

  Optional<RunSnapshot> findRun(RunKey key) throws SQLException {
    String sql = """
        SELECT generation, revision, state, cancelled_by, cancellation_reason, deadline_epoch_second, deadline_nano
        FROM uacrf_advisory.run_fence
        WHERE tenant_id = ? AND environment_id = ? AND run_id = ?
        """;
    try (Connection connection = committedConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, key.tenantId());
      statement.setString(2, key.environmentId());
      statement.setString(3, key.runId());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return Optional.empty();
        }
        if ("DRAFT".equals(result.getString(3))) {
          throw new SQLException("Unactivated run fence cannot be used.");
        }
        return Optional.of(new RunSnapshot(result.getLong(1), result.getLong(2),
            Instant.ofEpochSecond(result.getLong(6), result.getInt(7)),
            "CANCELLED".equals(result.getString(3)), result.getString(4), result.getString(5)));
      }
    }
  }

  // The run fence must retain the prepared request's deadline; neither a false result nor an
  // exception may be treated as permission to dispatch.
  boolean begin(Intent intent) throws SQLException {
    // Materialization acquires the run lock before evaluating the remaining run deadline.
    String sql = """
        WITH locked AS MATERIALIZED (
          SELECT tenant_id, environment_id, run_id, generation, state, max_tokens, dispatch_plan_required,
                 deadline_epoch_second, deadline_nano
          FROM uacrf_advisory.run_fence
          WHERE tenant_id = ? AND environment_id = ? AND run_id = ? AND generation = ?
            AND deadline_epoch_second = ? AND deadline_nano = ?
          FOR UPDATE
        ), observed AS MATERIALIZED (
          SELECT locked.*, clock_timestamp() AS admitted_at FROM locked
        )
        INSERT INTO uacrf_advisory.dispatch_journal
          (tenant_id, environment_id, run_id, assignment_id, attempt_id, generation, role, profile_id, model_ref,
           max_output_tokens, timeout_seconds, timeout_nanos, context_digest, packet_bytes, packet_bytes_digest, state)
        SELECT ?, ?, ?, ?, ?, ?, allocation.role, allocation.profile_id, ?, ?, ?, ?, ?, ?, ?, 'DISPATCH_INTENT'
        FROM observed fence
        JOIN uacrf_advisory.run_allocation allocation
          ON allocation.tenant_id = fence.tenant_id AND allocation.environment_id = fence.environment_id
          AND allocation.run_id = fence.run_id AND allocation.generation = fence.generation
        WHERE fence.state = 'ACTIVE'
          AND extract(epoch FROM fence.admitted_at) * 1000000000
            < fence.deadline_epoch_second::numeric * 1000000000 + fence.deadline_nano
          AND extract(epoch FROM fence.admitted_at) * 1000000000
            + ?::numeric * 1000000000 + ?::numeric
            < fence.deadline_epoch_second::numeric * 1000000000 + fence.deadline_nano
          AND allocation.tokens >= ?
          AND NOT EXISTS (
            SELECT 1 FROM uacrf_advisory.dispatch_journal unknown
            WHERE unknown.tenant_id = fence.tenant_id AND unknown.environment_id = fence.environment_id
              AND unknown.run_id = fence.run_id AND unknown.generation = fence.generation
              AND unknown.state = 'UNKNOWN'
          )
          AND (
            SELECT coalesce(sum(CASE journal.state
              WHEN 'RESPONSE' THEN journal.input_tokens::numeric + journal.output_tokens::numeric
              WHEN 'DISPATCH_INTENT' THEN journal.max_output_tokens::numeric
              ELSE 0::numeric END), 0)
            FROM uacrf_advisory.dispatch_journal journal
            WHERE journal.tenant_id = fence.tenant_id AND journal.environment_id = fence.environment_id
              AND journal.run_id = fence.run_id AND journal.generation = fence.generation
          ) + ?::numeric <= fence.max_tokens::numeric
          AND allocation.role = ? AND allocation.profile_id = ?
          AND EXISTS (
            SELECT 1 FROM uacrf_advisory.run_dispatch_plan plan
            WHERE plan.tenant_id = fence.tenant_id AND plan.environment_id = fence.environment_id
              AND plan.run_id = fence.run_id AND plan.generation = fence.generation
              AND plan.assignment_id = ? AND plan.attempt_id = ?
              AND plan.role = allocation.role AND plan.profile_id = allocation.profile_id
              AND plan.model_ref = ? AND plan.max_output_tokens = ?
              AND plan.timeout_seconds = ? AND plan.timeout_nanos = ?
              AND plan.context_digest = ? AND plan.packet_bytes = ? AND plan.packet_bytes_digest = ?
          )
        ON CONFLICT DO NOTHING
        """;
    try (Connection connection = committedConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, intent.key().tenantId());
      statement.setString(2, intent.key().environmentId());
      statement.setString(3, intent.runId());
      statement.setLong(4, intent.generation());
      statement.setLong(5, intent.runDeadline().getEpochSecond());
      statement.setInt(6, intent.runDeadline().getNano());
      statement.setString(7, intent.key().tenantId());
      statement.setString(8, intent.key().environmentId());
      statement.setString(9, intent.runId());
      statement.setString(10, intent.assignmentId());
      statement.setString(11, intent.key().attemptId());
      statement.setLong(12, intent.generation());
      statement.setString(13, intent.request().model());
      statement.setInt(14, intent.request().maxOutputTokens());
      statement.setLong(15, intent.request().timeout().getSeconds());
      statement.setInt(16, intent.request().timeout().getNano());
      statement.setString(17, intent.contextDigest());
      statement.setBytes(18, intent.packet().bytes());
      statement.setString(19, intent.packet().bytesDigest());
      statement.setLong(20, intent.request().timeout().getSeconds());
      statement.setInt(21, intent.request().timeout().getNano());
      statement.setInt(22, intent.request().maxOutputTokens());
      statement.setInt(23, intent.request().maxOutputTokens());
      statement.setString(24, intent.role().name());
      statement.setString(25, intent.profileId());
      statement.setString(26, intent.assignmentId());
      statement.setString(27, intent.key().attemptId());
      statement.setString(28, intent.request().model());
      statement.setInt(29, intent.request().maxOutputTokens());
      statement.setLong(30, intent.request().timeout().getSeconds());
      statement.setInt(31, intent.request().timeout().getNano());
      statement.setString(32, intent.contextDigest());
      statement.setBytes(33, intent.packet().bytes());
      statement.setString(34, intent.packet().bytesDigest());
      return statement.executeUpdate() == 1;
    }
  }

  boolean complete(Key key, AdvisoryGateway.Outcome outcome) throws SQLException {
    java.util.Objects.requireNonNull(outcome, "outcome");
    String sql = """
        WITH locked AS MATERIALIZED (
          SELECT fence.state, fence.deadline_epoch_second, fence.deadline_nano
          FROM uacrf_advisory.dispatch_journal journal
          JOIN uacrf_advisory.run_fence fence
            ON fence.tenant_id = journal.tenant_id AND fence.environment_id = journal.environment_id
            AND fence.run_id = journal.run_id AND fence.generation = journal.generation
          WHERE journal.tenant_id = ? AND journal.environment_id = ? AND journal.attempt_id = ?
            AND journal.state = 'DISPATCH_INTENT'
          FOR UPDATE OF fence
        ), observed AS MATERIALIZED (
          SELECT state, deadline_epoch_second, deadline_nano, clock_timestamp() AS recorded_at FROM locked
        )
        UPDATE uacrf_advisory.dispatch_journal journal
        SET state = ?, response_text = ?, input_tokens = ?, output_tokens = ?,
            unknown_reason = ?, completed_at = observed.recorded_at,
            recording_fence = CASE
              WHEN observed.state = 'CANCELLED' THEN 'CANCELLED'
              WHEN observed.state = 'ACTIVE' AND extract(epoch FROM observed.recorded_at) * 1000000000
                >= observed.deadline_epoch_second::numeric * 1000000000 + observed.deadline_nano THEN 'EXPIRED'
              WHEN observed.state = 'ACTIVE' THEN 'ACTIVE'
              ELSE 'INVALID'
            END
        FROM observed
        WHERE journal.tenant_id = ? AND journal.environment_id = ? AND journal.attempt_id = ?
          AND journal.state = 'DISPATCH_INTENT'
        """;
    try (Connection connection = committedConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, key.tenantId());
      statement.setString(2, key.environmentId());
      statement.setString(3, key.attemptId());
      if (outcome instanceof AdvisoryGateway.Response response) {
        statement.setString(4, "RESPONSE");
        statement.setString(5, response.text());
        statement.setInt(6, response.inputTokens());
        statement.setInt(7, response.outputTokens());
        statement.setNull(8, java.sql.Types.VARCHAR);
      } else if (outcome instanceof AdvisoryGateway.Unknown unknown) {
        statement.setString(4, "UNKNOWN");
        statement.setNull(5, java.sql.Types.VARCHAR);
        statement.setNull(6, java.sql.Types.INTEGER);
        statement.setNull(7, java.sql.Types.INTEGER);
        statement.setString(8, unknown.reason());
      } else {
        throw new IllegalArgumentException("Unsupported advisory outcome.");
      }
      statement.setString(9, key.tenantId());
      statement.setString(10, key.environmentId());
      statement.setString(11, key.attemptId());
      return statement.executeUpdate() == 1;
    }
  }

  Optional<Entry> find(Key key) throws SQLException {
    String sql = """
        SELECT journal.run_id, journal.assignment_id, journal.generation,
               fence.deadline_epoch_second, fence.deadline_nano, journal.role, journal.profile_id,
               journal.model_ref, journal.max_output_tokens, journal.timeout_seconds, journal.timeout_nanos,
               journal.context_digest, journal.packet_bytes, journal.packet_bytes_digest, journal.state,
               journal.response_text, journal.input_tokens, journal.output_tokens, journal.unknown_reason,
               journal.recording_fence, allocation.tokens
        FROM uacrf_advisory.dispatch_journal journal
        JOIN uacrf_advisory.run_fence fence
          ON fence.tenant_id = journal.tenant_id AND fence.environment_id = journal.environment_id
          AND fence.run_id = journal.run_id AND fence.generation = journal.generation
        JOIN uacrf_advisory.run_allocation allocation
          ON allocation.tenant_id = journal.tenant_id AND allocation.environment_id = journal.environment_id
          AND allocation.run_id = journal.run_id AND allocation.generation = journal.generation
          AND allocation.role = journal.role AND allocation.profile_id = journal.profile_id
        WHERE journal.tenant_id = ? AND journal.environment_id = ? AND journal.attempt_id = ?
        """;
    try (Connection connection = committedConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, key.tenantId());
      statement.setString(2, key.environmentId());
      statement.setString(3, key.attemptId());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return Optional.empty();
        }
        InputPacket packet = new InputPacket(result.getBytes(13));
        if (!packet.bytesDigest().equals(result.getString(14))) {
          throw new SQLException("Retained input packet digest does not match its bytes.");
        }
        AdvisoryGateway.Request request;
        try {
          String context = StandardCharsets.UTF_8.newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(packet.bytes())).toString();
          request = new AdvisoryGateway.Request(key.attemptId(), result.getString(8), context,
              result.getInt(9), Duration.ofSeconds(result.getLong(10), result.getInt(11)));
          if (request.maxOutputTokens() > result.getInt(21)) {
            throw new SQLException("Retained gateway output limit exceeds its sealed allocation.");
          }
        } catch (CharacterCodingException | IllegalArgumentException failure) {
          throw new SQLException("Retained gateway request is invalid.", failure);
        }
        Intent intent = new Intent(key, result.getString(1), result.getString(2),
            result.getLong(3), Instant.ofEpochSecond(result.getLong(4), result.getInt(5)),
            AdvisoryBudget.Role.valueOf(result.getString(6)), result.getString(7),
            request, result.getString(12), packet);
        Optional<AdvisoryGateway.Outcome> outcome = switch (result.getString(15)) {
          case "DISPATCH_INTENT" -> Optional.empty();
          case "RESPONSE" -> Optional.of(new AdvisoryGateway.Response(
              result.getString(16), result.getInt(17), result.getInt(18)));
          case "UNKNOWN" -> Optional.of(new AdvisoryGateway.Unknown(result.getString(19)));
          default -> throw new SQLException("Unrecognized dispatch journal state.");
        };
        String recordingFence = result.getString(20);
        return Optional.of(new Entry(intent, outcome,
            recordingFence == null ? Optional.empty() : Optional.of(RecordingFence.valueOf(recordingFence)),
            result.getInt(21)));
      }
    }
  }

  private Connection committedConnection() throws SQLException {
    Connection connection = dataSource.getConnection();
    try {
      if (!connection.getAutoCommit()) {
        throw new SQLException("Dispatch journal requires an auto-commit connection.");
      }
      return connection;
    } catch (SQLException failure) {
      try {
        connection.close();
      } catch (SQLException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  private static void bindOutboxClaim(PreparedStatement statement, OutboxClaim claim, int parameterIndex)
      throws SQLException {
    statement.setString(parameterIndex++, claim.eventId());
    statement.setString(parameterIndex++, claim.tenantId());
    statement.setString(parameterIndex++, claim.environmentId());
    statement.setString(parameterIndex++, claim.runId());
    statement.setLong(parameterIndex++, claim.generation());
    statement.setString(parameterIndex++, claim.eventType());
    statement.setObject(parameterIndex, claim.claimToken());
  }

  private static void requireId(String id) {
    if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}")) {
      throw new IllegalArgumentException("Invalid dispatch identifier.");
    }
  }
}
