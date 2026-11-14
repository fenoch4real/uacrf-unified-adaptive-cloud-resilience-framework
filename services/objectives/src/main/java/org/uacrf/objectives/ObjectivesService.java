package org.uacrf.objectives;

import static org.uacrf.objectives.ObjectivesRequests.*;
import static org.uacrf.objectives.ObjectivesResponses.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uacrf.security.DelegationPrincipal;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
class ObjectivesService {
  private static final String SCHEMA = "uacrf_objectives.";
  private static final String COLUMNS =
      "id, workload_id, title, metric, comparator, target, unit, window_seconds, origin, source_ref, "
          + "source_definition_id, source_revision, source_verified_at, content_digest, state, revision, "
          + "proposed_by, decided_by, decision_reason, activated_by";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  ObjectivesService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Transactional
  Objective propose(DelegationPrincipal actor, ProposeObjective request) {
    requireCapability(actor, "objectives.objective.write");
    requireGlobalScope(actor);
    boolean hasSource =
        request.sourceRef() != null
            || request.sourceDefinitionId() != null
            || request.sourceRevision() != null
            || request.sourceVerifiedAt() != null;
    if (request.origin() == Origin.IMPORTED) {
      if (request.sourceRef() == null
          || request.sourceDefinitionId() == null
          || request.sourceRevision() == null
          || request.sourceVerifiedAt() == null) {
        throw new ObjectivesException(
            HttpStatus.BAD_REQUEST,
            "IMPORT_PROVENANCE_REQUIRED",
            "An imported objective must keep its source, definition identity, revision and verification time.");
      }
    } else if (hasSource) {
      throw new ObjectivesException(
          HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "A local objective cannot carry import provenance.");
    }
    String canonical =
        String.join(
            "|",
            request.workloadId(),
            request.metric(),
            request.comparator().name(),
            request.target().stripTrailingZeros().toPlainString(),
            request.unit(),
            Integer.toString(request.windowSeconds()),
            request.origin().name(),
            String.valueOf(request.sourceDefinitionId()),
            String.valueOf(request.sourceRevision()));
    jdbc.update(
        "INSERT INTO " + SCHEMA + "objective (tenant_id, environment_id, id, workload_id, title, metric, "
            + "comparator, target, unit, window_seconds, origin, source_ref, source_definition_id, "
            + "source_revision, source_verified_at, content_digest, state, revision, proposed_by) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', 1, ?)",
        actor.tenantId(),
        actor.environmentId(),
        request.id(),
        request.workloadId(),
        request.title(),
        request.metric(),
        request.comparator().name(),
        request.target(),
        request.unit(),
        request.windowSeconds(),
        request.origin().name(),
        request.sourceRef(),
        request.sourceDefinitionId(),
        request.sourceRevision(),
        request.sourceVerifiedAt() == null ? null : Timestamp.from(request.sourceVerifiedAt()),
        sha256(canonical),
        actor.principalId());
    appendOutbox(actor, request.id(), 1, "objectives.objective.proposed", Map.of("state", "DRAFT"));
    return find(actor, request.id());
  }

  @Transactional
  Objective submit(DelegationPrincipal actor, String id, Transition request) {
    requireCapability(actor, "objectives.objective.write");
    requireGlobalScope(actor);
    Map<String, Object> row = lock(actor, id);
    requireRevision(row, request.expectedRevision());
    requireState(row, "DRAFT");
    enforceConstraint(actor, row);
    move(actor, id, row, "SUBMITTED", null, null, "objectives.objective.submitted");
    return find(actor, id);
  }

  @Transactional
  Objective decide(DelegationPrincipal actor, String id, DecideObjective request) {
    requireCapability(actor, "objectives.objective.review");
    requireGlobalScope(actor);
    Map<String, Object> row = lock(actor, id);
    if (actor.principalId().equals(row.get("proposed_by"))) {
      throw conflict("REVIEWER_NOT_INDEPENDENT", "The proposer cannot decide their own objective.");
    }
    requireRevision(row, request.expectedRevision());
    requireState(row, "SUBMITTED");
    boolean approve = request.decision() == Decision.APPROVE;
    move(
        actor,
        id,
        row,
        approve ? "APPROVED" : "REJECTED",
        request.reason(),
        null,
        "objectives.objective.decided");
    return find(actor, id);
  }

  @Transactional
  Objective activate(DelegationPrincipal actor, String id, Transition request) {
    requireCapability(actor, "objectives.objective.activate");
    requireGlobalScope(actor);
    Map<String, Object> row = lock(actor, id);
    requireRevision(row, request.expectedRevision());
    requireState(row, "APPROVED");
    enforceConstraint(actor, row);
    List<Map<String, Object>> previous =
        jdbc.queryForList(
            "SELECT id, revision FROM " + SCHEMA + "objective WHERE tenant_id = ? AND environment_id = ? "
                + "AND workload_id = ? AND metric = ? AND state = 'ACTIVE' FOR UPDATE",
            actor.tenantId(),
            actor.environmentId(),
            row.get("workload_id"),
            row.get("metric"));
    for (Map<String, Object> old : previous) {
      String oldId = (String) old.get("id");
      long next = ((Number) old.get("revision")).longValue() + 1;
      jdbc.update(
          "UPDATE " + SCHEMA + "objective SET state = 'SUPERSEDED', revision = ? WHERE tenant_id = ? "
              + "AND environment_id = ? AND id = ?",
          next,
          actor.tenantId(),
          actor.environmentId(),
          oldId);
      appendOutbox(
          actor, oldId, next, "objectives.objective.superseded", Map.of("superseded_by", id, "state", "SUPERSEDED"));
    }
    move(actor, id, row, "ACTIVE", null, actor.principalId(), "objectives.objective.activated");
    return find(actor, id);
  }

  @Transactional
  Constraint setConstraint(DelegationPrincipal actor, String metric, SetConstraint request) {
    requireCapability(actor, "objectives.constraint.write");
    requireGlobalScope(actor);
    if (request.minTarget() != null
        && request.maxTarget() != null
        && request.minTarget().compareTo(request.maxTarget()) > 0) {
      throw new ObjectivesException(
          HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "min_target cannot exceed max_target.");
    }
    jdbc.update(
        "INSERT INTO " + SCHEMA + "organization_constraint (tenant_id, environment_id, metric, unit, "
            + "min_target, max_target, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (tenant_id, environment_id, metric) DO UPDATE SET unit = EXCLUDED.unit, "
            + "min_target = EXCLUDED.min_target, max_target = EXCLUDED.max_target, "
            + "updated_by = EXCLUDED.updated_by, updated_at = now()",
        actor.tenantId(),
        actor.environmentId(),
        metric,
        request.unit(),
        request.minTarget(),
        request.maxTarget(),
        actor.principalId());
    return new Constraint(metric, request.unit(), request.minTarget(), request.maxTarget());
  }

  @Transactional(readOnly = true)
  ObjectiveReceipt receipt(DelegationPrincipal actor, String workloadId, ReceiptRequest request) {
    requireCapability(actor, "objectives.objective.read");
    List<ActiveObjective> active =
        jdbc.query(
            "SELECT id, metric, comparator, target, unit, window_seconds, revision, content_digest FROM "
                + SCHEMA + "objective WHERE tenant_id = ? AND environment_id = ? AND workload_id = ? "
                + "AND state = 'ACTIVE' ORDER BY metric",
            (rs, n) ->
                new ActiveObjective(
                    rs.getString("id"),
                    rs.getString("metric"),
                    rs.getString("comparator"),
                    rs.getBigDecimal("target"),
                    rs.getString("unit"),
                    rs.getInt("window_seconds"),
                    rs.getLong("revision"),
                    rs.getString("content_digest")),
            actor.tenantId(),
            actor.environmentId(),
            workloadId);
    StringBuilder material = new StringBuilder(workloadId);
    for (ActiveObjective objective : active) {
      material.append('|').append(objective.id()).append(':').append(objective.revision())
          .append(':').append(objective.contentDigest());
    }
    return new ObjectiveReceipt(workloadId, request.purpose(), active, sha256(material.toString()));
  }

  @Transactional(readOnly = true)
  Objective get(DelegationPrincipal actor, String id) {
    requireCapability(actor, "objectives.objective.read");
    return find(actor, id);
  }

  @Transactional(readOnly = true)
  Page<Objective> list(DelegationPrincipal actor, String workloadId, int pageSize, String cursor) {
    requireCapability(actor, "objectives.objective.read");
    List<Object> args = new ArrayList<>(List.of(actor.tenantId(), actor.environmentId(), decodeCursor(cursor)));
    String filter = "";
    if (workloadId != null && !workloadId.isBlank()) {
      filter = " AND workload_id = ?";
      args.add(workloadId);
    }
    args.add(pageSize + 1);
    List<Objective> rows =
        jdbc.query(
            "SELECT " + COLUMNS + " FROM " + SCHEMA + "objective WHERE tenant_id = ? AND environment_id = ? "
                + "AND id > ?" + filter + " ORDER BY id LIMIT ?",
            (rs, n) -> objectiveRow(rs),
            args.toArray());
    boolean more = rows.size() > pageSize;
    List<Objective> items = more ? rows.subList(0, pageSize) : rows;
    String next =
        more
            ? Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(items.getLast().id().getBytes(StandardCharsets.UTF_8))
            : null;
    return new Page<>(items, next);
  }

  private void move(
      DelegationPrincipal actor,
      String id,
      Map<String, Object> row,
      String state,
      String reason,
      String activatedBy,
      String eventType) {
    long next = ((Number) row.get("revision")).longValue() + 1;
    String decidedBy = "objectives.objective.decided".equals(eventType) ? actor.principalId() : null;
    jdbc.update(
        "UPDATE " + SCHEMA + "objective SET state = ?, revision = ?, "
            + "decided_by = COALESCE(?, decided_by), decision_reason = COALESCE(?, decision_reason), "
            + "activated_by = COALESCE(?, activated_by), "
            + "activated_at = CASE WHEN CAST(? AS text) IS NULL THEN activated_at ELSE now() END "
            + "WHERE tenant_id = ? AND environment_id = ? AND id = ?",
        state,
        next,
        decidedBy,
        reason,
        activatedBy,
        activatedBy,
        actor.tenantId(),
        actor.environmentId(),
        id);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("state", state);
    body.put("workload_id", row.get("workload_id"));
    body.put("metric", row.get("metric"));
    body.put("content_digest", row.get("content_digest"));
    appendOutbox(actor, id, next, eventType, body);
  }

  private void enforceConstraint(DelegationPrincipal actor, Map<String, Object> row) {
    List<Map<String, Object>> rules =
        jdbc.queryForList(
            "SELECT unit, min_target, max_target FROM " + SCHEMA + "organization_constraint "
                + "WHERE tenant_id = ? AND environment_id = ? AND metric = ?",
            actor.tenantId(),
            actor.environmentId(),
            row.get("metric"));
    if (rules.isEmpty()) {
      return;
    }
    Map<String, Object> rule = rules.getFirst();
    BigDecimal target = (BigDecimal) row.get("target");
    BigDecimal min = (BigDecimal) rule.get("min_target");
    BigDecimal max = (BigDecimal) rule.get("max_target");
    boolean ok =
        row.get("unit").equals(rule.get("unit"))
            && (min == null || target.compareTo(min) >= 0)
            && (max == null || target.compareTo(max) <= 0);
    if (!ok) {
      throw conflict(
          "CONSTRAINT_VIOLATION", "The objective breaks a mandatory organization constraint.");
    }
  }

  private Map<String, Object> lock(DelegationPrincipal actor, String id) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT workload_id, metric, unit, target, content_digest, state, revision, proposed_by FROM "
                + SCHEMA + "objective WHERE tenant_id = ? AND environment_id = ? AND id = ? FOR UPDATE",
            actor.tenantId(),
            actor.environmentId(),
            id);
    if (rows.isEmpty()) {
      throw notFound();
    }
    return rows.getFirst();
  }

  private static void requireRevision(Map<String, Object> row, long expected) {
    if (((Number) row.get("revision")).longValue() != expected) {
      throw conflict("REVISION_MISMATCH", "The objective changed since it was read.");
    }
  }

  private static void requireState(Map<String, Object> row, String state) {
    if (!state.equals(row.get("state"))) {
      throw conflict("INVALID_STATE", "The objective is not in the " + state + " state.");
    }
  }

  private Objective find(DelegationPrincipal actor, String id) {
    List<Objective> rows =
        jdbc.query(
            "SELECT " + COLUMNS + " FROM " + SCHEMA + "objective WHERE tenant_id = ? "
                + "AND environment_id = ? AND id = ?",
            (rs, n) -> objectiveRow(rs),
            actor.tenantId(),
            actor.environmentId(),
            id);
    if (rows.isEmpty()) {
      throw notFound();
    }
    return rows.getFirst();
  }

  private static Objective objectiveRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    Timestamp verified = rs.getTimestamp("source_verified_at");
    return new Objective(
        rs.getString("id"),
        rs.getString("workload_id"),
        rs.getString("title"),
        rs.getString("metric"),
        rs.getString("comparator"),
        rs.getBigDecimal("target"),
        rs.getString("unit"),
        rs.getInt("window_seconds"),
        rs.getString("origin"),
        rs.getString("source_ref"),
        rs.getString("source_definition_id"),
        rs.getString("source_revision"),
        verified == null ? null : verified.toInstant().toString(),
        rs.getString("content_digest"),
        rs.getString("state"),
        rs.getLong("revision"),
        rs.getString("proposed_by"),
        rs.getString("decided_by"),
        rs.getString("decision_reason"),
        rs.getString("activated_by"));
  }

  private void appendOutbox(
      DelegationPrincipal actor, String aggregateId, long sequence, String eventType, Map<String, Object> body) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("actor_id", actor.principalId());
    envelope.put("event_type", eventType);
    envelope.put("aggregate_id", aggregateId);
    envelope.put("revision", sequence);
    envelope.putAll(body);
    String payload;
    try {
      payload = objectMapper.writeValueAsString(envelope);
    } catch (JacksonException error) {
      throw new IllegalStateException("Could not serialize the event.", error);
    }
    jdbc.update(
        "INSERT INTO " + SCHEMA + "outbox_event (event_id, tenant_id, environment_id, aggregate_type, "
            + "aggregate_id, aggregate_sequence, event_type, payload, payload_digest) "
            + "VALUES (?, ?, ?, 'OBJECTIVE', ?, ?, ?, ?::jsonb, ?)",
        UUID.randomUUID(),
        actor.tenantId(),
        actor.environmentId(),
        aggregateId,
        sequence,
        eventType,
        payload,
        sha256(payload));
  }

  static int validatePageSize(int pageSize) {
    if (pageSize < 1 || pageSize > 100) {
      throw new ObjectivesException(
          HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "page_size must be between 1 and 100.");
    }
    return pageSize;
  }

  private static String decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return "";
    }
    try {
      String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      if (value.isBlank() || value.length() > 200) {
        throw new IllegalArgumentException();
      }
      return value;
    } catch (IllegalArgumentException error) {
      throw new ObjectivesException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "cursor is malformed.");
    }
  }

  private static void requireCapability(DelegationPrincipal actor, String capability) {
    if (!actor.capabilities().contains(capability)) {
      throw new ObjectivesException(
          HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "The current principal cannot perform this objective operation.");
    }
  }

  private static void requireGlobalScope(DelegationPrincipal actor) {
    if (!"*".equals(actor.serviceScope())) {
      throw new ObjectivesException(
          HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "This objective operation requires a global service scope.");
    }
  }

  private static ObjectivesException notFound() {
    return new ObjectivesException(HttpStatus.NOT_FOUND, "NOT_FOUND", "The objective does not exist.");
  }

  private static ObjectivesException conflict(String code, String message) {
    return new ObjectivesException(HttpStatus.CONFLICT, code, message);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the runtime.", error);
    }
  }
}
