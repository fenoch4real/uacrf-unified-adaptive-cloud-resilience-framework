package org.uacrf.advisory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

  record Intent(Key key, String runId, String assignmentId, long generation,
                AdvisoryBudget.Role role, String profileId, String contextDigest, InputPacket packet) {
    Intent {
      java.util.Objects.requireNonNull(key, "key");
      java.util.Objects.requireNonNull(packet, "packet");
      java.util.Objects.requireNonNull(role, "role");
      requireId(runId);
      requireId(assignmentId);
      requireId(profileId);
      if (generation < 1 || contextDigest == null || !contextDigest.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Invalid dispatch generation or context digest.");
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

  enum UnresolvedState {
    DISPATCH_INTENT, UNKNOWN
  }

  record Unresolved(Key key, UnresolvedState state) {}

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

  boolean registerRun(RunKey key, long generation, AdvisoryBudget budget, Instant deadline) throws SQLException {
    if (generation < 1) {
      throw new IllegalArgumentException("Run generation must be positive.");
    }
    java.util.Objects.requireNonNull(budget, "budget");
    java.util.Objects.requireNonNull(deadline, "deadline");
    String runSql = """
        INSERT INTO uacrf_advisory.run_fence
          (tenant_id, environment_id, run_id, generation, max_calls, max_tokens, max_cost_units,
           deadline_epoch_second, deadline_nano, state, revision)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', 1)
        ON CONFLICT DO NOTHING
        """;
    String allocationSql = """
        INSERT INTO uacrf_advisory.run_allocation
          (tenant_id, environment_id, run_id, generation, role, profile_id, tokens, cost_units)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
    String activateSql = """
        UPDATE uacrf_advisory.run_fence SET state = 'ACTIVE'
        WHERE tenant_id = ? AND environment_id = ? AND run_id = ? AND state = 'DRAFT'
        """;
    try (Connection connection = committedConnection()) {
      connection.setAutoCommit(false);
      try {
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
          if (statement.executeUpdate() == 0) {
            connection.rollback();
            return false;
          }
        }
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
        try (PreparedStatement statement = connection.prepareStatement(activateSql)) {
          statement.setString(1, key.tenantId());
          statement.setString(2, key.environmentId());
          statement.setString(3, key.runId());
          if (statement.executeUpdate() != 1) {
            throw new SQLException("Run activation failed.");
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

  // A false result or an exception must never be treated as permission to dispatch.
  boolean begin(Intent intent) throws SQLException {
    // Materialization acquires the run lock before evaluating the deadline.
    String sql = """
        WITH locked AS MATERIALIZED (
          SELECT tenant_id, environment_id, run_id, generation, state, deadline_epoch_second, deadline_nano
          FROM uacrf_advisory.run_fence
          WHERE tenant_id = ? AND environment_id = ? AND run_id = ? AND generation = ?
          FOR UPDATE
        )
        INSERT INTO uacrf_advisory.dispatch_journal
          (tenant_id, environment_id, run_id, assignment_id, attempt_id, generation, role, profile_id, context_digest,
           packet_bytes, packet_bytes_digest, state)
        SELECT ?, ?, ?, ?, ?, ?, allocation.role, allocation.profile_id, ?, ?, ?, 'DISPATCH_INTENT'
        FROM locked fence
        JOIN uacrf_advisory.run_allocation allocation
          ON allocation.tenant_id = fence.tenant_id AND allocation.environment_id = fence.environment_id
          AND allocation.run_id = fence.run_id AND allocation.generation = fence.generation
        WHERE fence.state = 'ACTIVE'
          AND extract(epoch FROM clock_timestamp()) * 1000000000
            < fence.deadline_epoch_second::numeric * 1000000000 + fence.deadline_nano
          AND allocation.role = ? AND allocation.profile_id = ?
        ON CONFLICT DO NOTHING
        """;
    try (Connection connection = committedConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, intent.key().tenantId());
      statement.setString(2, intent.key().environmentId());
      statement.setString(3, intent.runId());
      statement.setLong(4, intent.generation());
      statement.setString(5, intent.key().tenantId());
      statement.setString(6, intent.key().environmentId());
      statement.setString(7, intent.runId());
      statement.setString(8, intent.assignmentId());
      statement.setString(9, intent.key().attemptId());
      statement.setLong(10, intent.generation());
      statement.setString(11, intent.contextDigest());
      statement.setBytes(12, intent.packet().bytes());
      statement.setString(13, intent.packet().bytesDigest());
      statement.setString(14, intent.role().name());
      statement.setString(15, intent.profileId());
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
        SELECT journal.run_id, journal.assignment_id, journal.generation, journal.role, journal.profile_id,
               journal.context_digest, journal.packet_bytes, journal.packet_bytes_digest, journal.state,
               journal.response_text, journal.input_tokens, journal.output_tokens, journal.unknown_reason,
               journal.recording_fence, allocation.tokens
        FROM uacrf_advisory.dispatch_journal journal
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
        InputPacket packet = new InputPacket(result.getBytes(7));
        if (!packet.bytesDigest().equals(result.getString(8))) {
          throw new SQLException("Retained input packet digest does not match its bytes.");
        }
        Intent intent = new Intent(key, result.getString(1), result.getString(2),
            result.getLong(3), AdvisoryBudget.Role.valueOf(result.getString(4)), result.getString(5),
            result.getString(6), packet);
        Optional<AdvisoryGateway.Outcome> outcome = switch (result.getString(9)) {
          case "DISPATCH_INTENT" -> Optional.empty();
          case "RESPONSE" -> Optional.of(new AdvisoryGateway.Response(
              result.getString(10), result.getInt(11), result.getInt(12)));
          case "UNKNOWN" -> Optional.of(new AdvisoryGateway.Unknown(result.getString(13)));
          default -> throw new SQLException("Unrecognized dispatch journal state.");
        };
        String recordingFence = result.getString(14);
        return Optional.of(new Entry(intent, outcome,
            recordingFence == null ? Optional.empty() : Optional.of(RecordingFence.valueOf(recordingFence)),
            result.getInt(15)));
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

  private static void requireId(String id) {
    if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}")) {
      throw new IllegalArgumentException("Invalid dispatch identifier.");
    }
  }
}
