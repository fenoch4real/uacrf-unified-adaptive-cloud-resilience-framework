package org.uacrf.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uacrf.security.DelegationPrincipal;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
class QualificationPolicyService {
  private static final String SCHEMA = "uacrf_evidence.";
  private static final String READ = "evidence.qualification.policy.read";
  private static final String WRITE = "evidence.qualification.policy.write";
  private static final String REVIEW = "evidence.qualification.policy.review";
  private static final String VISIBLE_SCOPE = "AUTHORIZED_VISIBLE_SCOPE_ONLY";
  private static final String CURRENT_POLICY =
      "SELECT h.id, h.service_id, h.purpose, h.current_revision, r.revision, r.expires_at, "
          + "r.definition::text AS definition, "
          + "r.digest, r.proposed_by, r.proposal_reason, r.proposed_at, "
          + "COALESCE(d.decision, 'DRAFT') AS decision, d.reviewer_id, d.reason AS review_reason, "
          + "d.decided_at AS reviewed_at FROM " + SCHEMA + "qualification_policy_head h "
          + "JOIN " + SCHEMA + "qualification_policy_revision r ON r.tenant_id = h.tenant_id "
          + "AND r.environment_id = h.environment_id AND r.id = h.id AND r.revision = h.current_revision "
          + "LEFT JOIN " + SCHEMA + "qualification_policy_decision d ON d.tenant_id = r.tenant_id "
          + "AND d.environment_id = r.environment_id AND d.id = r.id AND d.revision = r.revision ";
  private static final String POLICY_REVISION =
      "SELECT h.id, h.service_id, h.purpose, h.current_revision, r.revision, r.expires_at, "
          + "r.definition::text AS definition, "
          + "r.digest, r.proposed_by, r.proposal_reason, r.proposed_at, "
          + "COALESCE(d.decision, 'DRAFT') AS decision, d.reviewer_id, d.reason AS review_reason, "
          + "d.decided_at AS reviewed_at FROM " + SCHEMA + "qualification_policy_revision r "
          + "JOIN " + SCHEMA + "qualification_policy_head h ON h.tenant_id = r.tenant_id "
          + "AND h.environment_id = r.environment_id AND h.id = r.id "
          + "LEFT JOIN " + SCHEMA + "qualification_policy_decision d ON d.tenant_id = r.tenant_id "
          + "AND d.environment_id = r.environment_id AND d.id = r.id AND d.revision = r.revision ";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final EvidenceContractSchemas contracts;

  QualificationPolicyService(
      JdbcTemplate jdbc, ObjectMapper objectMapper, EvidenceContractSchemas contracts) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.contracts = contracts;
  }

  @Transactional
  JsonNode propose(DelegationPrincipal actor, JsonNode request, String idempotencyKey) {
    requireCapability(actor, WRITE);
    if (!contracts.validPolicyWrite(request)) {
      throw invalidRequest("The qualification policy does not match its contract.");
    }
    String id = request.path("id").asString();
    String serviceId = request.path("service_id").asString();
    requireServiceScope(actor, serviceId, true);
    String operation = "PROPOSE";
    String requestDigest = digestJson(request);
    lockIdempotency(actor, operation, idempotencyKey);
    JsonNode replay = replay(actor, operation, idempotencyKey, requestDigest);
    if (replay != null) {
      return replay;
    }
    lockPolicy(actor, id);

    List<Map<String, Object>> heads =
        jdbc.queryForList(
            "SELECT service_id, purpose, current_revision FROM " + SCHEMA
                + "qualification_policy_head WHERE tenant_id = ? AND environment_id = ? AND id = ? FOR UPDATE",
            actor.tenantId(),
            actor.environmentId(),
            id);
    long expected = request.path("expected_revision").asLong();
    long revision;
    if (heads.isEmpty()) {
      if (expected != 0) {
        throw conflict("REVISION_MISMATCH", "The policy does not exist at the expected revision.");
      }
      revision = 1;
      jdbc.update(
          "INSERT INTO " + SCHEMA + "qualification_policy_head "
              + "(tenant_id, environment_id, id, service_id, purpose, current_revision) "
              + "VALUES (?, ?, ?, ?, ?, ?)",
          actor.tenantId(),
          actor.environmentId(),
          id,
          serviceId,
          request.path("purpose").asString(),
          revision);
    } else {
      Map<String, Object> head = heads.getFirst();
      long current = ((Number) head.get("current_revision")).longValue();
      if (expected != current) {
        throw conflict("REVISION_MISMATCH", "The policy changed since it was read.");
      }
      if (!serviceId.equals(head.get("service_id"))
          || !request.path("purpose").asString().equals(head.get("purpose"))) {
        throw conflict("POLICY_SCOPE_IMMUTABLE", "The service and purpose cannot change between revisions.");
      }
      revision = current + 1;
      jdbc.update(
          "UPDATE " + SCHEMA + "qualification_policy_head SET current_revision = ? "
              + "WHERE tenant_id = ? AND environment_id = ? AND id = ?",
          revision,
          actor.tenantId(),
          actor.environmentId(),
          id);
    }

    String definition = writeJson(request.get("definition"));
    String policyDigest = sha256(definition);
    Instant expiresAt = Instant.parse(request.path("expires_at").asString());
    jdbc.update(
        "INSERT INTO " + SCHEMA + "qualification_policy_revision "
            + "(tenant_id, environment_id, id, revision, service_id, purpose, expires_at, definition, "
            + "digest, proposed_by, proposal_reason, proposed_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, now())",
        actor.tenantId(),
        actor.environmentId(),
        id,
        revision,
        serviceId,
        request.path("purpose").asString(),
        Timestamp.from(expiresAt),
        definition,
        policyDigest,
        actor.principalId(),
        request.path("reason").asString());
    ObjectNode response = response(findCurrent(actor, id), UUID.randomUUID().toString());
    recordIdempotency(actor, operation, idempotencyKey, requestDigest, id, revision, response);
    return response;
  }

  @Transactional
  JsonNode decide(
      DelegationPrincipal actor, String id, JsonNode request, String idempotencyKey) {
    requireCapability(actor, REVIEW);
    if (!contracts.validPolicyDecision(request)) {
      throw invalidRequest("The qualification policy decision does not match its contract.");
    }
    lockPolicy(actor, id);
    Map<String, Object> current = findCurrent(actor, id);
    requireServiceScope(actor, (String) current.get("service_id"), false);
    String operation = "DECIDE";
    String requestDigest = digestJson(request);
    lockIdempotency(actor, operation, idempotencyKey);
    JsonNode replay = replay(actor, operation, idempotencyKey, requestDigest);
    if (replay != null) {
      return replay;
    }
    if (actor.principalId().equals(current.get("proposed_by"))) {
      throw conflict("REVIEWER_NOT_INDEPENDENT", "The policy proposer cannot review their own policy.");
    }
    long revision = ((Number) current.get("revision")).longValue();
    if (request.path("expected_revision").asLong() != revision) {
      throw conflict("REVISION_MISMATCH", "The policy changed since it was read.");
    }
    if (!"DRAFT".equals(current.get("decision"))) {
      throw conflict("ALREADY_DECIDED", "Only draft policy revisions can be reviewed.");
    }

    String decision = "APPROVE".equals(request.path("decision").asString()) ? "APPROVED" : "REJECTED";
    jdbc.update(
        "INSERT INTO " + SCHEMA + "qualification_policy_decision "
            + "(tenant_id, environment_id, id, revision, decision, reviewer_id, reason, decided_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, now())",
        actor.tenantId(),
        actor.environmentId(),
        id,
        revision,
        decision,
        actor.principalId(),
        request.path("reason").asString());
    ObjectNode response = response(findRevision(actor, id, revision), UUID.randomUUID().toString());
    recordIdempotency(actor, operation, idempotencyKey, requestDigest, id, revision, response);
    return response;
  }

  @Transactional(readOnly = true)
  JsonNode list(
      DelegationPrincipal actor, String serviceId, int pageSize, String cursor, String purpose) {
    requireCapability(actor, READ);
    requireServiceScope(actor, serviceId, true);
    validatePageSize(pageSize);
    String afterId = decodeCursor(cursor);
    String sql =
        CURRENT_POLICY + "WHERE h.tenant_id = ? AND h.environment_id = ? AND h.service_id = ? "
            + "AND h.id > ? " + (purpose == null ? "" : "AND h.purpose = ? ")
            + "ORDER BY h.id LIMIT ?";
    List<Object> parameters = new ArrayList<>(
        List.of(actor.tenantId(), actor.environmentId(), serviceId, afterId));
    if (purpose != null) {
      if (purpose.isBlank() || purpose.length() > 500) {
        throw invalidRequest("purpose must contain 1 to 500 non-whitespace characters.");
      }
      parameters.add(purpose);
    }
    parameters.add(pageSize + 1);
    List<Map<String, Object>> rows = jdbc.queryForList(sql, parameters.toArray());
    boolean hasMore = rows.size() > pageSize;
    List<Map<String, Object>> visible = rows.subList(0, Math.min(pageSize, rows.size()));
    ArrayNode items = objectMapper.createArrayNode();
    visible.forEach(row -> items.add(policy(row)));
    ObjectNode response = objectMapper.createObjectNode();
    response.set("items", items);
    response.put("next_cursor", hasMore ? encodeCursor((String) visible.getLast().get("id")) : null);
    response.put("coverage", VISIBLE_SCOPE);
    return response;
  }

  @Transactional(readOnly = true)
  JsonNode get(DelegationPrincipal actor, String id, Long revision) {
    requireCapability(actor, READ);
    if (revision != null && revision < 1) {
      throw invalidRequest("revision must be at least 1.");
    }
    Map<String, Object> policy =
        revision == null ? findCurrent(actor, id) : findRevision(actor, id, revision);
    requireServiceScope(actor, (String) policy.get("service_id"), true);
    return response(policy, null);
  }

  @Transactional(readOnly = true)
  JsonNode history(DelegationPrincipal actor, String id, int pageSize, String cursor) {
    requireCapability(actor, READ);
    validatePageSize(pageSize);
    List<Map<String, Object>> heads =
        jdbc.queryForList(
            "SELECT service_id FROM " + SCHEMA
                + "qualification_policy_head WHERE tenant_id = ? AND environment_id = ? AND id = ?",
            actor.tenantId(),
            actor.environmentId(),
            id);
    if (heads.isEmpty()) {
      throw notFound();
    }
    requireServiceScope(actor, (String) heads.getFirst().get("service_id"), true);
    long beforeRevision = decodeRevisionCursor(cursor);
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            POLICY_REVISION + "WHERE r.tenant_id = ? AND r.environment_id = ? AND r.id = ? "
                + "AND r.revision < ? ORDER BY r.revision DESC LIMIT ?",
            actor.tenantId(),
            actor.environmentId(),
            id,
            beforeRevision,
            pageSize + 1);
    boolean hasMore = rows.size() > pageSize;
    List<Map<String, Object>> visible = rows.subList(0, Math.min(pageSize, rows.size()));
    ArrayNode items = objectMapper.createArrayNode();
    visible.forEach(row -> items.add(historyItem(row)));
    ObjectNode response = objectMapper.createObjectNode();
    response.set("items", items);
    response.put("next_cursor", hasMore ? encodeCursor(Long.toString(
        ((Number) visible.getLast().get("revision")).longValue())) : null);
    response.put("coverage", VISIBLE_SCOPE);
    return response;
  }

  private Map<String, Object> findCurrent(DelegationPrincipal actor, String id) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            CURRENT_POLICY + "WHERE h.tenant_id = ? AND h.environment_id = ? AND h.id = ?",
            actor.tenantId(),
            actor.environmentId(),
            id);
    if (rows.isEmpty()) {
      throw notFound();
    }
    return rows.getFirst();
  }

  private Map<String, Object> findRevision(DelegationPrincipal actor, String id, long revision) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            POLICY_REVISION + "WHERE r.tenant_id = ? AND r.environment_id = ? AND r.id = ? "
                + "AND r.revision = ?",
            actor.tenantId(),
            actor.environmentId(),
            id,
            revision);
    if (rows.isEmpty()) {
      throw notFound();
    }
    return rows.getFirst();
  }

  private ObjectNode response(Map<String, Object> row, String receiptId) {
    ObjectNode result = objectMapper.createObjectNode();
    result.set("policy", policy(row));
    if (receiptId == null) {
      result.putNull("receipt_id");
    } else {
      result.put("receipt_id", receiptId);
    }
    return result;
  }

  private ObjectNode policy(Map<String, Object> row) {
    ObjectNode policy = objectMapper.createObjectNode();
    policy.put("id", (String) row.get("id"));
    policy.put("revision", ((Number) row.get("revision")).longValue());
    policy.put("service_id", (String) row.get("service_id"));
    policy.put("purpose", (String) row.get("purpose"));
    policy.put("expires_at", ((Timestamp) row.get("expires_at")).toInstant().toString());
    policy.set("definition", readJson((String) row.get("definition")));
    policy.put("digest", ((String) row.get("digest")).trim());
    policy.put("proposed_by", (String) row.get("proposed_by"));
    policy.put("decision", (String) row.get("decision"));
    putNullable(policy, "reviewer_id", (String) row.get("reviewer_id"));
    Timestamp expiry = (Timestamp) row.get("expires_at");
    policy.put(
        "current_eligible",
        ((Number) row.get("current_revision")).longValue()
                == ((Number) row.get("revision")).longValue()
            && "APPROVED".equals(row.get("decision"))
            && expiry.toInstant().isAfter(Instant.now()));
    policy.put("proposed_at", ((Timestamp) row.get("proposed_at")).toInstant().toString());
    putNullable(policy, "proposal_reason", (String) row.get("proposal_reason"));
    putNullableTimestamp(policy, "reviewed_at", (Timestamp) row.get("reviewed_at"));
    putNullable(policy, "review_reason", (String) row.get("review_reason"));
    return policy;
  }

  private ObjectNode historyItem(Map<String, Object> row) {
    ObjectNode item = objectMapper.createObjectNode();
    item.put("id", (String) row.get("id"));
    item.put("revision", ((Number) row.get("revision")).longValue());
    item.put("expires_at", ((Timestamp) row.get("expires_at")).toInstant().toString());
    item.put("digest", ((String) row.get("digest")).trim());
    item.put("proposed_by", (String) row.get("proposed_by"));
    item.put("decision", (String) row.get("decision"));
    putNullable(item, "reviewer_id", (String) row.get("reviewer_id"));
    Timestamp expiry = (Timestamp) row.get("expires_at");
    item.put(
        "current_eligible",
        ((Number) row.get("current_revision")).longValue()
                == ((Number) row.get("revision")).longValue()
            && "APPROVED".equals(row.get("decision"))
            && expiry.toInstant().isAfter(Instant.now()));
    item.put("proposed_at", ((Timestamp) row.get("proposed_at")).toInstant().toString());
    putNullable(item, "proposal_reason", (String) row.get("proposal_reason"));
    putNullableTimestamp(item, "reviewed_at", (Timestamp) row.get("reviewed_at"));
    putNullable(item, "review_reason", (String) row.get("review_reason"));
    return item;
  }

  private void lockPolicy(DelegationPrincipal actor, String id) {
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        actor.tenantId() + "|" + actor.environmentId() + "|qualification-policy|" + id);
  }

  private void lockIdempotency(DelegationPrincipal actor, String operation, String key) {
    if (key == null || key.isBlank() || key.length() > 200 || key.chars().allMatch(Character::isWhitespace)) {
      throw invalidRequest("Idempotency-Key must contain 1 to 200 non-whitespace characters.");
    }
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        actor.tenantId() + "|" + actor.environmentId() + "|" + actor.principalId()
            + "|qualification-policy|" + operation + "|" + key);
  }

  private JsonNode replay(
      DelegationPrincipal actor, String operation, String key, String requestDigest) {
    List<Map<String, Object>> records =
        jdbc.queryForList(
            "SELECT request_digest, response::text AS response FROM "
                + SCHEMA + "qualification_policy_idempotency WHERE tenant_id = ? "
                + "AND environment_id = ? AND principal_id = ? AND operation = ? "
                + "AND idempotency_key = ? FOR UPDATE",
            actor.tenantId(),
            actor.environmentId(),
            actor.principalId(),
            operation,
            key);
    if (records.isEmpty()) {
      return null;
    }
    Map<String, Object> record = records.getFirst();
    if (!requestDigest.equals(((String) record.get("request_digest")).trim())) {
      throw conflict("IDEMPOTENCY_CONFLICT", "The idempotency key was already used for different content.");
    }
    return readJson((String) record.get("response"));
  }

  private void recordIdempotency(
      DelegationPrincipal actor,
      String operation,
      String key,
      String requestDigest,
      String policyId,
      long revision,
      JsonNode response) {
    jdbc.update(
        "INSERT INTO " + SCHEMA + "qualification_policy_idempotency "
            + "(tenant_id, environment_id, principal_id, operation, idempotency_key, request_digest, "
            + "policy_id, policy_revision, receipt_id, response) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
        actor.tenantId(),
        actor.environmentId(),
        actor.principalId(),
        operation,
        key,
        requestDigest,
        policyId,
        revision,
        response.path("receipt_id").asString(),
        writeJson(response));
  }

  private String digestJson(JsonNode value) {
    return sha256(writeJson(value));
  }

  private String writeJson(JsonNode value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException error) {
      throw new IllegalStateException("Could not serialize a qualification policy value.", error);
    }
  }

  private JsonNode readJson(String value) {
    try {
      return objectMapper.readTree(value);
    } catch (JacksonException error) {
      throw new IllegalStateException("Could not read persisted qualification policy JSON.", error);
    }
  }

  private static void putNullable(ObjectNode target, String name, String value) {
    if (value == null) {
      target.putNull(name);
    } else {
      target.put(name, value);
    }
  }

  private static void putNullableTimestamp(ObjectNode target, String name, Timestamp value) {
    if (value == null) {
      target.putNull(name);
    } else {
      target.put(name, value.toInstant().toString());
    }
  }

  private static void requireCapability(DelegationPrincipal actor, String capability) {
    if (!actor.capabilities().contains(capability)) {
      throw new EvidenceException(
          HttpStatus.FORBIDDEN,
          "PERMISSION_DENIED",
          "The current principal cannot perform this qualification policy operation.");
    }
  }

  private static void requireServiceScope(
      DelegationPrincipal actor, String serviceId, boolean allowGlobal) {
    boolean global = "*".equals(actor.serviceScope());
    if ((!global || !allowGlobal) && !serviceId.equals(actor.serviceScope())) {
      throw new EvidenceException(
          HttpStatus.FORBIDDEN,
          "PERMISSION_DENIED",
          "The qualification policy is outside the current service scope.");
    }
  }

  private static void validatePageSize(int pageSize) {
    if (pageSize < 1 || pageSize > 200) {
      throw invalidRequest("page_size must be between 1 and 200.");
    }
  }

  private static String decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return "";
    }
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      if (decoded.isBlank() || decoded.length() > 200) {
        throw new IllegalArgumentException();
      }
      return decoded;
    } catch (IllegalArgumentException error) {
      throw invalidRequest("cursor is malformed.");
    }
  }

  private static long decodeRevisionCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return Long.MAX_VALUE;
    }
    try {
      long revision = Long.parseLong(decodeCursor(cursor));
      if (revision < 1) {
        throw new IllegalArgumentException();
      }
      return revision;
    } catch (IllegalArgumentException error) {
      throw invalidRequest("cursor is malformed.");
    }
  }

  private static String encodeCursor(String value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the runtime.", error);
    }
  }

  private static EvidenceException invalidRequest(String message) {
    return new EvidenceException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
  }

  private static EvidenceException conflict(String code, String message) {
    return new EvidenceException(HttpStatus.CONFLICT, code, message);
  }

  private static EvidenceException notFound() {
    return new EvidenceException(HttpStatus.NOT_FOUND, "NOT_FOUND", "The qualification policy does not exist.");
  }
}
