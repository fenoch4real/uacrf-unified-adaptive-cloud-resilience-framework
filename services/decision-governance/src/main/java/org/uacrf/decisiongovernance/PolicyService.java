package org.uacrf.decisiongovernance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.decisiongovernance.PolicyRequests.Definition;
import org.uacrf.decisiongovernance.PolicyRequests.ProposePolicy;
import org.uacrf.decisiongovernance.PolicyResponses.Policy;
import org.uacrf.decisiongovernance.PolicyResponses.PolicyPage;
import org.uacrf.decisiongovernance.PolicyResponses.PolicyRow;
import org.uacrf.decisiongovernance.PolicyResponses.PolicySummary;
import org.uacrf.decisiongovernance.PolicyResponses.ProposalResult;

@Service
class PolicyService {
  private static final String SCHEMA = "uacrf_decision_governance";
  private static final String ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$";
  private static final String OPERATION = "POST /v1/authorization-policy-bundles";
  private static final int MAX_PAGE_SIZE = 200;

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  PolicyService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Transactional
  ProposalResult propose(
      DelegationPrincipal actor, ProposePolicy request, String idempotencyKey) {
    requireCapability(actor, "authorization.policy.write");
    requireServiceScope(actor, request.serviceId());
    validateRequest(actor, request);
    validateIdempotencyKey(idempotencyKey);

    String requestDigest = sha256(canonicalJson(objectMapper.valueToTree(request)));
    int inserted =
        jdbc.update(
            "INSERT INTO "
                + SCHEMA
                + ".idempotency_record "
                + "(tenant_id, environment_id, principal_id, operation, idempotency_key, request_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            actor.tenantId(),
            actor.environmentId(),
            actor.principalId(),
            OPERATION,
            idempotencyKey,
            requestDigest);
    if (inserted == 0) {
      List<Map<String, Object>> records =
          jdbc.queryForList(
              "SELECT request_digest, policy_id, policy_revision FROM "
                  + SCHEMA
                  + ".idempotency_record WHERE tenant_id = ? AND environment_id = ? "
                  + "AND principal_id = ? AND operation = ? AND idempotency_key = ? FOR UPDATE",
              actor.tenantId(),
              actor.environmentId(),
              actor.principalId(),
              OPERATION,
              idempotencyKey);
      if (records.isEmpty() || !requestDigest.equals(records.getFirst().get("request_digest"))) {
        throw conflict(
            "IDEMPOTENCY_CONFLICT", "The idempotency key was already used for different content.");
      }
      Object policyId = records.getFirst().get("policy_id");
      Object revision = records.getFirst().get("policy_revision");
      if (!(policyId instanceof String id) || !(revision instanceof Number revisionNumber)) {
        throw conflict("COMMAND_IN_PROGRESS", "The original request has not completed.");
      }
      return new ProposalResult(
          findRevision(actor, id, revisionNumber.longValue(), null).policy(), null);
    }

    jdbc.update(
        "INSERT INTO "
            + SCHEMA
            + ".policy_head (tenant_id, environment_id, id, service_id, current_revision) "
            + "VALUES (?, ?, ?, ?, 0) ON CONFLICT DO NOTHING",
        actor.tenantId(),
        actor.environmentId(),
        request.id(),
        request.serviceId());
    List<Map<String, Object>> heads =
        jdbc.queryForList(
            "SELECT service_id, current_revision FROM "
                + SCHEMA
                + ".policy_head WHERE tenant_id = ? AND environment_id = ? AND id = ? FOR UPDATE",
            actor.tenantId(),
            actor.environmentId(),
            request.id());
    if (heads.isEmpty()) {
      throw conflict("REVISION_CONFLICT", "The policy revision could not be established.");
    }
    Map<String, Object> head = heads.getFirst();
    long currentRevision = ((Number) head.get("current_revision")).longValue();
    if (!request.serviceId().equals(head.get("service_id"))) {
      throw conflict("POLICY_ID_CONFLICT", "A policy ID cannot be moved to another service.");
    }
    if (request.expectedRevision() != currentRevision) {
      throw conflict(
          "REVISION_CONFLICT",
          "expected_revision does not match the latest immutable policy revision.");
    }
    if (currentRevision == Long.MAX_VALUE) {
      throw conflict("REVISION_LIMIT", "The policy revision limit has been reached.");
    }

    long revision = currentRevision + 1;
    Instant proposedAt = Instant.now();
    String canonicalText = policyMaterial(request);
    String digest = sha256(canonicalText);
    jdbc.update(
        "INSERT INTO "
            + SCHEMA
            + ".policy_revision "
            + "(tenant_id, environment_id, id, revision, service_id, expires_at, definition, "
            + "digest, proposed_by, proposal_reason, proposed_at, effective_from, canonical_text) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)",
        actor.tenantId(),
        actor.environmentId(),
        request.id(),
        revision,
        request.serviceId(),
        timestamp(request.expiresAt()),
        writeJson(request.definition()),
        digest,
        actor.principalId(),
        request.reason(),
        timestamp(proposedAt),
        timestamp(request.effectiveFrom()),
        canonicalText);
    jdbc.update(
        "UPDATE "
            + SCHEMA
            + ".policy_head SET current_revision = ? WHERE tenant_id = ? AND environment_id = ? AND id = ?",
        revision,
        actor.tenantId(),
        actor.environmentId(),
        request.id());
    jdbc.update(
        "UPDATE "
            + SCHEMA
            + ".idempotency_record SET policy_id = ?, policy_revision = ? "
            + "WHERE tenant_id = ? AND environment_id = ? AND principal_id = ? "
            + "AND operation = ? AND idempotency_key = ?",
        request.id(),
        revision,
        actor.tenantId(),
        actor.environmentId(),
        actor.principalId(),
        OPERATION,
        idempotencyKey);

    return new ProposalResult(findRevision(actor, request.id(), revision, null).policy(), null);
  }

  @Transactional(readOnly = true)
  PolicyPage list(
      DelegationPrincipal actor, String serviceId, int pageSize, String cursor) {
    requireCapability(actor, "authorization.policy.read");
    requireIdentifier(serviceId, "service_id");
    requireServiceScope(actor, serviceId);
    if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      throw invalid("page_size must be between 1 and 200.");
    }

    Cursor decodedCursor = cursor == null ? null : decodeCursor(cursor);
    String sql =
        "SELECT r.id, r.revision, r.service_id, r.expires_at, r.definition::text, r.digest, "
            + "r.proposed_by, r.proposal_reason, r.proposed_at, r.effective_from, r.canonical_text "
            + "FROM "
            + SCHEMA
            + ".policy_head h JOIN "
            + SCHEMA
            + ".policy_revision r ON r.tenant_id = h.tenant_id "
            + "AND r.environment_id = h.environment_id AND r.id = h.id "
            + "AND r.revision = h.current_revision "
            + "WHERE h.tenant_id = ? AND h.environment_id = ? AND h.service_id = ? "
            + (decodedCursor == null
                ? ""
                : "AND (r.proposed_at, r.id, r.revision) < (?, ?, ?) ")
            + "ORDER BY r.proposed_at DESC, r.id DESC, r.revision DESC LIMIT ?";
    List<Object> parameters = new ArrayList<>();
    parameters.add(actor.tenantId());
    parameters.add(actor.environmentId());
    parameters.add(serviceId);
    if (decodedCursor != null) {
      parameters.add(timestamp(decodedCursor.proposedAt()));
      parameters.add(decodedCursor.id());
      parameters.add(decodedCursor.revision());
    }
    parameters.add(pageSize + 1);
    List<PolicyRow> rows = jdbc.query(sql, (rs, row) -> mapPolicyRow(rs), parameters.toArray());
    boolean hasMore = rows.size() > pageSize;
    List<PolicyRow> visible = hasMore ? rows.subList(0, pageSize) : rows;
    String nextCursor =
        hasMore && !visible.isEmpty() ? encodeCursor(visible.getLast()) : null;
    List<PolicySummary> items = visible.stream().map(PolicyRow::summary).toList();
    return new PolicyPage(items, nextCursor, "AUTHORIZED_VISIBLE_SCOPE_ONLY");
  }

  @Transactional(readOnly = true)
  Policy get(DelegationPrincipal actor, String id, Long revision) {
    requireCapability(actor, "authorization.policy.read");
    requireIdentifier(id, "id");
    if (revision != null && revision < 1) {
      throw invalid("revision must be a positive integer.");
    }
    return findRevision(actor, id, revision, actor.serviceScope()).policy();
  }

  private PolicyRow findRevision(
      DelegationPrincipal actor, String id, Long revision, String serviceScope) {
    String sql =
        "SELECT r.id, r.revision, r.service_id, r.expires_at, r.definition::text, r.digest, "
            + "r.proposed_by, r.proposal_reason, r.proposed_at, r.effective_from, r.canonical_text "
            + "FROM "
            + SCHEMA
            + ".policy_revision r WHERE r.tenant_id = ? AND r.environment_id = ? AND r.id = ? "
            + (revision == null
                ? "AND r.revision = (SELECT current_revision FROM "
                    + SCHEMA
                    + ".policy_head WHERE tenant_id = r.tenant_id "
                    + "AND environment_id = r.environment_id AND id = r.id) "
                : "AND r.revision = ? ")
            + (serviceScope == null || "*".equals(serviceScope) ? "" : "AND r.service_id = ? ");
    List<Object> parameters = new ArrayList<>(List.of(actor.tenantId(), actor.environmentId(), id));
    if (revision != null) {
      parameters.add(revision);
    }
    if (serviceScope != null && !"*".equals(serviceScope)) {
      parameters.add(serviceScope);
    }
    List<PolicyRow> rows = jdbc.query(sql, (rs, row) -> mapPolicyRow(rs), parameters.toArray());
    if (rows.isEmpty()) {
      throw notFound("The policy is absent or not visible.");
    }
    return rows.getFirst();
  }

  private PolicyRow mapPolicyRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new PolicyRow(
        rs.getString("id"),
        rs.getLong("revision"),
        rs.getString("service_id"),
        rs.getTimestamp("expires_at").toInstant(),
        readDefinition(rs.getString("definition")),
        rs.getString("digest"),
        rs.getString("proposed_by"),
        rs.getString("proposal_reason"),
        rs.getTimestamp("proposed_at").toInstant(),
        rs.getTimestamp("effective_from").toInstant(),
        rs.getString("canonical_text"));
  }

  private Definition readDefinition(String json) {
    return objectMapper.readValue(json, Definition.class);
  }

  private String writeJson(Object value) {
    return objectMapper.writeValueAsString(value);
  }

  private void validateRequest(DelegationPrincipal actor, ProposePolicy request) {
    requireIdentifier(request.id(), "id");
    requireIdentifier(request.serviceId(), "service_id");
    requireIdentifier(actor.principalId(), "principal_id");
    if (request.reason() == null || request.reason().isBlank()) {
      throw invalid("reason must contain a non-whitespace character.");
    }
    if (request.definition() == null) {
      throw invalid("definition is required.");
    }
    Definition definition = request.definition();
    requireUnique(definition.allowedActionKinds(), "allowed_action_kinds");
    requireUnique(definition.allowedActorIds(), "allowed_actor_ids");
    requireUnique(definition.allowedResourceIds(), "allowed_resource_ids");
    requireUnique(definition.permittedProviders(), "permitted_providers");
    requireUnique(definition.permittedRegions(), "permitted_regions");
    requireUnique(definition.eligibleApproverIds(), "eligible_approver_ids");
    definition
        .allowedResourceIds()
        .forEach(value -> requireNonGlobalWildcard(value, "allowed_resource_ids"));
    definition
        .permittedRegions()
        .forEach(value -> requireNonGlobalWildcard(value, "permitted_regions"));
    if (request.expiresAt() == null || request.effectiveFrom() == null) {
      throw invalid("expires_at and effective_from are required.");
    }
    if (!request.expiresAt().isAfter(request.effectiveFrom())) {
      throw invalid("expires_at must be later than effective_from.");
    }
    if (definition.maximumCostUsd() == null
        || definition.maximumCostUsd().stripTrailingZeros().scale() > 6) {
      throw invalid("maximum_cost_usd must be a non-negative multiple of 0.000001.");
    }
  }

  private void requireCapability(DelegationPrincipal actor, String capability) {
    if (!actor.capabilities().contains(capability)) {
      throw new DecisionGovernanceException(
          HttpStatus.FORBIDDEN,
          "CAPABILITY_REQUIRED",
          "The verified S01 delegation does not grant " + capability + ".");
    }
  }

  private void requireServiceScope(DelegationPrincipal actor, String serviceId) {
    if (!"*".equals(actor.serviceScope()) && !actor.serviceScope().equals(serviceId)) {
      throw new DecisionGovernanceException(
          HttpStatus.FORBIDDEN,
          "SERVICE_SCOPE_REQUIRED",
          "The verified S01 delegation is not scoped to this service.");
    }
  }

  private static void requireIdentifier(String value, String field) {
    if (value == null || !value.matches(ID_PATTERN)) {
      throw invalid(field + " is malformed.");
    }
  }

  private static void requireUnique(List<?> values, String field) {
    if (values == null || values.isEmpty() || values.size() > 100) {
      throw invalid(field + " must contain between 1 and 100 values.");
    }
    if (values.stream().distinct().count() != values.size()) {
      throw invalid(field + " must not contain duplicate values.");
    }
  }

  private static void requireNonGlobalWildcard(String value, String field) {
    if (value == null || value.isBlank() || "*".equals(value)) {
      throw invalid(field + " must not use the global wildcard '*'.");
    }
  }

  private static void validateIdempotencyKey(String key) {
    if (key == null || key.isBlank() || key.length() > 200) {
      throw invalid("Idempotency-Key must contain 1 to 200 non-whitespace characters.");
    }
  }

  private String policyMaterial(ProposePolicy request) {
    Map<String, Object> material = new TreeMap<>();
    material.put("definition", objectMapper.valueToTree(request.definition()));
    material.put("effective_from", request.effectiveFrom().toString());
    material.put("expires_at", request.expiresAt().toString());
    material.put("id", request.id());
    material.put("service_id", request.serviceId());
    return canonicalJson(objectMapper.valueToTree(material));
  }

  private String canonicalJson(JsonNode value) {
    if (value.isObject()) {
      List<String> names = new ArrayList<>();
      names.addAll(value.propertyNames());
      names.sort(String::compareTo);
      StringBuilder output = new StringBuilder("{");
      for (int i = 0; i < names.size(); i++) {
        if (i > 0) {
          output.append(',');
        }
        String name = names.get(i);
        output.append(writeJson(name)).append(':').append(canonicalJson(value.get(name)));
      }
      return output.append('}').toString();
    }
    if (value.isArray()) {
      StringBuilder output = new StringBuilder("[");
      for (int i = 0; i < value.size(); i++) {
        if (i > 0) {
          output.append(',');
        }
        output.append(canonicalJson(value.get(i)));
      }
      return output.append(']').toString();
    }
    if (value.isTextual()) {
      return writeJson(value.textValue());
    }
    if (value.isIntegralNumber()) {
      return value.bigIntegerValue().toString();
    }
    if (value.isNumber()) {
      var decimal = value.decimalValue().stripTrailingZeros();
      return decimal.signum() == 0 ? "0" : decimal.toPlainString();
    }
    return value.toString();
  }

  private String sha256(String content) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime.", error);
    }
  }

  private String encodeCursor(PolicyRow row) {
    String value =
        row.proposedAt().toString() + "\n" + row.id() + "\n" + row.revision();
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private Cursor decodeCursor(String cursor) {
    if (cursor.isBlank() || cursor.length() > 500) {
      throw invalid("cursor is malformed.");
    }
    try {
      String decoded =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = decoded.split("\n", -1);
      if (parts.length != 3 || !parts[1].matches(ID_PATTERN) || !parts[2].matches("^[1-9][0-9]*$")) {
        throw invalid("cursor is malformed.");
      }
      long revision = Long.parseLong(parts[2]);
      return new Cursor(Instant.parse(parts[0]), parts[1], revision);
    } catch (IllegalArgumentException error) {
      throw invalid("cursor is malformed.");
    }
  }

  private static OffsetDateTime timestamp(Instant value) {
    return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static DecisionGovernanceException invalid(String message) {
    return new DecisionGovernanceException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
  }

  private static DecisionGovernanceException conflict(String code, String message) {
    return new DecisionGovernanceException(HttpStatus.CONFLICT, code, message);
  }

  private static DecisionGovernanceException notFound(String message) {
    return new DecisionGovernanceException(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", message);
  }

  private record Cursor(Instant proposedAt, String id, long revision) {}
}
