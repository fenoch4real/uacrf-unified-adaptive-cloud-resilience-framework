package org.uacrf.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.OwnerReceiptSignature;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
class QualificationWaiverService {
  private static final String SCHEMA = "uacrf_evidence.";
  private static final String READ = "evidence.qualification.waiver.read";
  private static final String WRITE = "evidence.qualification.waiver.write";
  private static final String REVIEW = "evidence.qualification.waiver.review";
  private static final String REVOKE = "evidence.qualification.waiver.revoke";
  private static final String WAIVER_ROW =
      "SELECT w.tenant_id, w.environment_id, w.id, w.policy_id, w.policy_revision, w.requirement_id, "
          + "w.observation_window_start, w.observation_window_end, w.expires_at, w.gap_types, "
          + "w.reason, w.service_id, w.purpose, w.requested_by, w.digest, w.signing_material, w.proposed_at, "
          + "d.decision, d.reviewer_id, d.reason AS review_reason, d.decided_at AS reviewed_at, "
          + "d.trust_revision, d.trust_digest, d.trust_key_id, d.trust_key_digest, d.assertion_digest, "
          + "r.revoked_by, r.reason AS revocation_reason, r.revoked_at, "
          + "h.current_revision AS current_policy_revision, current_policy.expires_at AS current_policy_expires_at, "
          + "COALESCE(current_decision.decision, 'DRAFT') AS current_policy_decision, "
          + "trust_head.revision AS stored_trust_revision, trust_head.digest AS stored_trust_digest "
          + "FROM " + SCHEMA + "qualification_waiver w "
          + "JOIN " + SCHEMA + "qualification_policy_head h ON h.tenant_id = w.tenant_id "
          + "AND h.environment_id = w.environment_id AND h.id = w.policy_id "
          + "JOIN " + SCHEMA + "qualification_policy_revision current_policy "
          + "ON current_policy.tenant_id = h.tenant_id AND current_policy.environment_id = h.environment_id "
          + "AND current_policy.id = h.id AND current_policy.revision = h.current_revision "
          + "LEFT JOIN " + SCHEMA + "qualification_policy_decision current_decision "
          + "ON current_decision.tenant_id = current_policy.tenant_id "
          + "AND current_decision.environment_id = current_policy.environment_id "
          + "AND current_decision.id = current_policy.id AND current_decision.revision = current_policy.revision "
          + "LEFT JOIN " + SCHEMA + "qualification_waiver_decision d "
          + "ON d.tenant_id = w.tenant_id AND d.environment_id = w.environment_id AND d.waiver_id = w.id "
          + "LEFT JOIN " + SCHEMA + "qualification_waiver_revocation r "
          + "ON r.tenant_id = w.tenant_id AND r.environment_id = w.environment_id AND r.waiver_id = w.id "
          + "LEFT JOIN " + SCHEMA + "qualification_waiver_trust_head trust_head "
          + "ON trust_head.tenant_id = w.tenant_id AND trust_head.environment_id = w.environment_id "
          + "AND trust_head.service_id = w.service_id ";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final EvidenceContractSchemas contracts;
  private final QualificationWaiverTrustSet trust;
  private final QualificationWaiverAssertionVerifier assertionVerifier;

  QualificationWaiverService(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      EvidenceContractSchemas contracts,
      QualificationWaiverTrustSet trust,
      QualificationWaiverAssertionVerifier assertionVerifier) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.contracts = contracts;
    this.trust = trust;
    this.assertionVerifier = assertionVerifier;
  }

  @Transactional
  JsonNode propose(DelegationPrincipal actor, JsonNode request, String idempotencyKey) {
    requireCapability(actor, WRITE);
    if (!contracts.validWaiverWrite(request)) {
      throw invalidRequest("The qualification waiver does not match its contract.");
    }
    validateIdempotencyKey(idempotencyKey);
    String id = request.path("id").asString();
    String policyId = request.path("policy_id").asString();
    long policyRevision = request.path("policy_revision").asLong();
    String requirementId = request.path("requirement_id").asString();
    Map<String, Object> policy =
        findPolicyRevision(actor, policyId, policyRevision);
    String serviceId = (String) policy.get("service_id");
    requireServiceScope(actor, serviceId);

    String operation = "PROPOSE";
    String requestDigest = digestJson(request);
    lockIdempotency(actor, operation, idempotencyKey);
    String priorReceipt = replayReceipt(actor, operation, idempotencyKey, requestDigest, id);
    if (priorReceipt != null) {
      return response(find(actor, id), priorReceipt);
    }
    lockWaiver(actor, id);
    requireApprovedCurrentPolicy(policy, policyRevision, requirementId);

    Instant windowStart = parseInstant(request.path("observation_window_start").asString());
    Instant windowEnd = parseInstant(request.path("observation_window_end").asString());
    Instant expiresAt = parseInstant(request.path("expires_at").asString());
    Instant now = Instant.now();
    Instant policyExpiresAt = ((Timestamp) policy.get("expires_at")).toInstant();
    if (!windowStart.isBefore(windowEnd)
        || !expiresAt.isAfter(now)
        || expiresAt.isAfter(policyExpiresAt)) {
      throw invalidRequest(
          "The observation window must be ordered and waiver expiry must be future and no later than policy expiry.");
    }
    List<String> gapTypes = new ArrayList<>();
    request.path("gap_types").forEach(value -> gapTypes.add(value.asString()));
    gapTypes.sort(String::compareTo);
    String reason = request.path("reason").asString();
    String purpose = (String) policy.get("purpose");
    ObjectNode signingMaterial =
        signingMaterial(
            id,
            policyId,
            policyRevision,
            requirementId,
            windowStart,
            windowEnd,
            expiresAt,
            gapTypes,
            reason,
            serviceId,
            purpose,
            actor.principalId());
    String canonicalText = signingMaterial.path("canonical_text").asString();
    String digest = signingMaterial.path("digest").asString();
    Instant proposedAt = Instant.now();
    jdbc.update(
        "INSERT INTO " + SCHEMA + "qualification_waiver "
            + "(tenant_id, environment_id, id, policy_id, policy_revision, requirement_id, "
            + "observation_window_start, observation_window_end, expires_at, gap_types, reason, "
            + "service_id, purpose, requested_by, digest, signing_material, proposed_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        actor.tenantId(),
        actor.environmentId(),
        id,
        policyId,
        policyRevision,
        requirementId,
        Timestamp.from(windowStart),
        Timestamp.from(windowEnd),
        Timestamp.from(expiresAt),
        gapTypes.toArray(String[]::new),
        reason,
        serviceId,
        purpose,
        actor.principalId(),
        digest,
        canonicalText,
        Timestamp.from(proposedAt));
    String receiptId = UUID.randomUUID().toString();
    ObjectNode result = response(find(actor, id), receiptId);
    recordIdempotency(actor, operation, idempotencyKey, requestDigest, id, receiptId, result);
    return result;
  }

  @Transactional
  JsonNode decide(DelegationPrincipal actor, String id, JsonNode request, String idempotencyKey) {
    requireCapability(actor, REVIEW);
    if (!contracts.validWaiverDecision(request)) {
      throw invalidRequest("The qualification waiver decision does not match its contract.");
    }
    validateIdempotencyKey(idempotencyKey);
    lockWaiver(actor, id);
    Map<String, Object> waiver = find(actor, id);
    requireServiceScope(actor, (String) waiver.get("service_id"));
    String operation = "DECIDE";
    String requestDigest = digestJson(request);
    lockIdempotency(actor, operation, idempotencyKey);
    String priorReceipt = replayReceipt(actor, operation, idempotencyKey, requestDigest, id);
    if (priorReceipt != null) {
      return response(find(actor, id), priorReceipt);
    }
    if (waiver.get("decision") != null || waiver.get("revoked_at") != null) {
      throw conflict("ALREADY_DECIDED", "Only an undecided qualification waiver can be reviewed.");
    }
    if (actor.principalId().equals(waiver.get("requested_by"))) {
      throw conflict("REVIEWER_NOT_INDEPENDENT", "The waiver proposer cannot review their own waiver.");
    }
    if (request.path("expected_revision").asLong() != 1) {
      throw conflict("REVISION_MISMATCH", "The qualification waiver changed since it was read.");
    }
    String decision = request.path("decision").asString();
    String reason = request.path("reason").asString();
    QualificationWaiverAssertionVerifier.Verified verified = null;
    if ("APPROVE".equals(decision)) {
      Map<String, Object> policy =
          findPolicyRevision(
              actor,
              (String) waiver.get("policy_id"),
              ((Number) waiver.get("policy_revision")).longValue());
      requireApprovedCurrentPolicy(
          policy,
          ((Number) waiver.get("policy_revision")).longValue(),
          (String) waiver.get("requirement_id"));
      verified =
          assertionVerifier.verify(
              request.path("signed_assertion").asString(),
              actor,
              id,
              (String) waiver.get("service_id"),
              ((String) waiver.get("digest")).trim());
      advanceTrustHead(actor, (String) waiver.get("service_id"));
    }
    String resultDecision = "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
    jdbc.update(
        "INSERT INTO " + SCHEMA + "qualification_waiver_decision "
            + "(tenant_id, environment_id, waiver_id, decision, reviewer_id, reason, decided_at, "
            + "trust_revision, trust_digest, trust_key_id, trust_key_digest, assertion_digest, assertion_jti) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        actor.tenantId(),
        actor.environmentId(),
        id,
        resultDecision,
        actor.principalId(),
        reason,
        Timestamp.from(Instant.now()),
        verified == null ? null : trust.revision(),
        verified == null ? null : trust.digest(),
        verified == null ? null : verified.key().keyId(),
        verified == null ? null : verified.key().keyDigest(),
        verified == null ? null : verified.assertionDigest(),
        verified == null ? null : verified.assertionJti());
    String receiptId = UUID.randomUUID().toString();
    ObjectNode result = response(find(actor, id), receiptId);
    recordIdempotency(actor, operation, idempotencyKey, requestDigest, id, receiptId, result);
    return result;
  }

  @Transactional
  JsonNode revoke(DelegationPrincipal actor, String id, JsonNode request, String idempotencyKey) {
    requireCapability(actor, REVOKE);
    if (!contracts.validWaiverRevoke(request)) {
      throw invalidRequest("The qualification waiver revocation does not match its contract.");
    }
    validateIdempotencyKey(idempotencyKey);
    lockWaiver(actor, id);
    Map<String, Object> waiver = find(actor, id);
    requireServiceScope(actor, (String) waiver.get("service_id"));
    String operation = "REVOKE";
    String requestDigest = digestJson(request);
    lockIdempotency(actor, operation, idempotencyKey);
    String priorReceipt = replayReceipt(actor, operation, idempotencyKey, requestDigest, id);
    if (priorReceipt != null) {
      return response(find(actor, id), priorReceipt);
    }
    if (waiver.get("revoked_at") != null) {
      throw conflict("ALREADY_REVOKED", "The qualification waiver has already been revoked.");
    }
    String state = state(waiver);
    if (!"DRAFT".equals(state) && !"APPROVED".equals(state)) {
      throw conflict("WAIVER_NOT_REVOCABLE", "Only a draft or approved waiver can be revoked.");
    }
    long revision = currentRevision(waiver);
    if (request.path("expected_revision").asLong() != revision) {
      throw conflict("REVISION_MISMATCH", "The qualification waiver changed since it was read.");
    }
    jdbc.update(
        "INSERT INTO " + SCHEMA + "qualification_waiver_revocation "
            + "(tenant_id, environment_id, waiver_id, revoked_by, reason, revoked_at) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        actor.tenantId(),
        actor.environmentId(),
        id,
        actor.principalId(),
        request.path("reason").asString(),
        Timestamp.from(Instant.now()));
    String receiptId = UUID.randomUUID().toString();
    ObjectNode result = response(find(actor, id), receiptId);
    recordIdempotency(actor, operation, idempotencyKey, requestDigest, id, receiptId, result);
    return result;
  }

  @Transactional(readOnly = true)
  JsonNode get(DelegationPrincipal actor, String id) {
    requireCapability(actor, READ);
    Map<String, Object> waiver = find(actor, id);
    requireServiceScope(actor, (String) waiver.get("service_id"));
    return response(waiver, null);
  }

  @Transactional(readOnly = true)
  JsonNode list(DelegationPrincipal actor, String serviceId, int pageSize, String cursor, String policyId) {
    requireCapability(actor, READ);
    requireServiceScope(actor, serviceId);
    validatePageSize(pageSize);
    String afterId = decodeCursor(cursor);
    String sql =
        "SELECT id FROM " + SCHEMA + "qualification_waiver WHERE tenant_id = ? AND environment_id = ? "
            + "AND service_id = ? AND id > ? "
            + (policyId == null ? "" : "AND policy_id = ? ")
            + "ORDER BY id LIMIT ?";
    List<Object> parameters = new ArrayList<>(
        List.of(actor.tenantId(), actor.environmentId(), serviceId, afterId));
    if (policyId != null) {
      if (!policyId.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$")) {
        throw invalidRequest("policy_id is malformed.");
      }
      parameters.add(policyId);
    }
    parameters.add(pageSize + 1);
    List<String> ids =
        jdbc.query(sql, (rs, row) -> rs.getString(1), parameters.toArray());
    boolean hasMore = ids.size() > pageSize;
    List<String> visibleIds = ids.subList(0, Math.min(pageSize, ids.size()));
    ArrayNode items = objectMapper.createArrayNode();
    for (String id : visibleIds) {
      items.add(waiver(find(actor, id)));
    }
    ObjectNode result = objectMapper.createObjectNode();
    result.set("items", items);
    result.put(
        "next_cursor",
        hasMore
            ? Base64.getUrlEncoder().withoutPadding()
                .encodeToString(visibleIds.getLast().getBytes(StandardCharsets.UTF_8))
            : null);
    result.put("coverage", "AUTHORIZED_VISIBLE_SCOPE_ONLY");
    return result;
  }

  private void requireApprovedCurrentPolicy(
      Map<String, Object> policy, long expectedRevision, String requirementId) {
    if (((Number) policy.get("current_revision")).longValue() != expectedRevision
        || !"APPROVED".equals(policy.get("decision"))
        || !((Timestamp) policy.get("expires_at")).toInstant().isAfter(Instant.now())) {
      throw conflict(
          "POLICY_NOT_CURRENT",
          "A waiver must reference the current, approved, unexpired qualification policy revision.");
    }
    JsonNode definition = readJson((String) policy.get("definition"));
    JsonNode requirements = definition.path("requirements");
    for (JsonNode requirement : requirements) {
      if (requirementId.equals(requirement.path("id").asString())) {
        if (requirement.path("waivable").asBoolean(false)) {
          return;
        }
        throw conflict(
            "REQUIREMENT_NOT_WAIVABLE",
            "The referenced policy requirement is not explicitly marked waivable.");
      }
    }
    throw invalidRequest("requirement_id is not present in the referenced policy revision.");
  }

  private Map<String, Object> findPolicyRevision(
      DelegationPrincipal actor, String policyId, long policyRevision) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT h.service_id, h.current_revision, r.purpose, r.expires_at, "
                + "r.definition::text AS definition, COALESCE(d.decision, 'DRAFT') AS decision "
                + "FROM " + SCHEMA + "qualification_policy_head h "
                + "JOIN " + SCHEMA + "qualification_policy_revision r ON r.tenant_id = h.tenant_id "
                + "AND r.environment_id = h.environment_id AND r.id = h.id AND r.revision = ? "
                + "LEFT JOIN " + SCHEMA + "qualification_policy_decision d ON d.tenant_id = r.tenant_id "
                + "AND d.environment_id = r.environment_id AND d.id = r.id AND d.revision = r.revision "
                + "WHERE h.tenant_id = ? AND h.environment_id = ? AND h.id = ?",
            policyRevision,
            actor.tenantId(),
            actor.environmentId(),
            policyId);
    if (rows.isEmpty()) {
      throw notFound();
    }
    return rows.getFirst();
  }

  private Map<String, Object> find(DelegationPrincipal actor, String id) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            WAIVER_ROW + "WHERE w.tenant_id = ? AND w.environment_id = ? AND w.id = ?",
            actor.tenantId(),
            actor.environmentId(),
            id);
    if (rows.isEmpty()) {
      throw notFound();
    }
    return rows.getFirst();
  }

  private ObjectNode response(Map<String, Object> row, String receiptId) {
    ObjectNode response = objectMapper.createObjectNode();
    response.set("waiver", waiver(row));
    if (receiptId == null) {
      response.putNull("receipt_id");
    } else {
      response.put("receipt_id", receiptId);
    }
    return response;
  }

  private ObjectNode waiver(Map<String, Object> row) {
    ObjectNode result = objectMapper.createObjectNode();
    result.put("id", (String) row.get("id"));
    result.put("policy_id", (String) row.get("policy_id"));
    result.put("policy_revision", ((Number) row.get("policy_revision")).longValue());
    result.put("requirement_id", (String) row.get("requirement_id"));
    result.put("observation_window_start", ((Timestamp) row.get("observation_window_start")).toInstant().toString());
    result.put("observation_window_end", ((Timestamp) row.get("observation_window_end")).toInstant().toString());
    result.put("expires_at", ((Timestamp) row.get("expires_at")).toInstant().toString());
    ArrayNode gapTypes = objectMapper.createArrayNode();
    for (String gapType : readGapTypes(row.get("gap_types"))) {
      gapTypes.add(gapType);
    }
    result.set("gap_types", gapTypes);
    result.put("reason", (String) row.get("reason"));
    result.put("service_id", (String) row.get("service_id"));
    result.put("purpose", (String) row.get("purpose"));
    result.put("requested_by", (String) row.get("requested_by"));
    long revision = currentRevision(row);
    result.put("revision", revision);
    String state = state(row);
    result.put("state", state);
    putNullable(result, "reviewer_id", (String) row.get("reviewer_id"));
    result.put("current_eligible", currentEligible(row, state));
    result.put("digest", ((String) row.get("digest")).trim());
    ObjectNode signingMaterial = objectMapper.createObjectNode();
    signingMaterial.put("algorithm", "SHA-256");
    signingMaterial.put("encoding", "UTF-8");
    signingMaterial.put("canonical_text", (String) row.get("signing_material"));
    signingMaterial.put("digest", ((String) row.get("digest")).trim());
    result.set("signing_material", signingMaterial);
    putNullable(result, "trust_digest", trimNullable((String) row.get("trust_digest")));
    putNullable(result, "assertion_digest", trimNullable((String) row.get("assertion_digest")));
    result.put("proposed_at", ((Timestamp) row.get("proposed_at")).toInstant().toString());
    putNullable(result, "review_reason", (String) row.get("review_reason"));
    putNullableTimestamp(result, "reviewed_at", (Timestamp) row.get("reviewed_at"));
    putNullable(result, "revoked_by", (String) row.get("revoked_by"));
    putNullable(result, "revocation_reason", (String) row.get("revocation_reason"));
    putNullableTimestamp(result, "revoked_at", (Timestamp) row.get("revoked_at"));
    return result;
  }

  private static List<String> readGapTypes(Object value) {
    try {
      Array array = (Array) value;
      Object[] elements = (Object[]) array.getArray();
      List<String> gapTypes = new ArrayList<>(elements.length);
      for (Object element : elements) {
        gapTypes.add((String) element);
      }
      return List.copyOf(gapTypes);
    } catch (SQLException error) {
      throw new IllegalStateException("Could not read qualification-waiver gap types.", error);
    }
  }

  private boolean currentEligible(Map<String, Object> row, String state) {
    Timestamp expiry = (Timestamp) row.get("expires_at");
    Timestamp policyExpiry = (Timestamp) row.get("current_policy_expires_at");
    if (!"APPROVED".equals(state)
        || expiry == null
        || !expiry.toInstant().isAfter(Instant.now())
        || policyExpiry == null
        || !policyExpiry.toInstant().isAfter(Instant.now())
        || !"APPROVED".equals(row.get("current_policy_decision"))
        || ((Number) row.get("current_policy_revision")).longValue()
            != ((Number) row.get("policy_revision")).longValue()
        || !trust.configured()
        || row.get("trust_key_id") == null
        || row.get("trust_key_digest") == null
        || !trust.stillTrusts(
            (String) row.get("trust_key_id"),
            ((String) row.get("trust_key_digest")).trim(),
            (String) row.get("tenant_id"),
            (String) row.get("environment_id"),
            (String) row.get("service_id"),
            (String) row.get("reviewer_id"))) {
      return false;
    }
    Number storedRevision = (Number) row.get("stored_trust_revision");
    String storedDigest = trimNullable((String) row.get("stored_trust_digest"));
    return storedRevision != null
        && trust.revision() >= storedRevision.longValue()
        && (trust.revision() != storedRevision.longValue()
            || trust.digest().equals(storedDigest));
  }

  private void advanceTrustHead(DelegationPrincipal actor, String serviceId) {
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        actor.tenantId() + "|" + actor.environmentId() + "|qualification-waiver-trust|" + serviceId);
    jdbc.update(
        "INSERT INTO " + SCHEMA + "qualification_waiver_trust_head "
            + "(tenant_id, environment_id, service_id, revision, digest) VALUES (?, ?, ?, ?, ?) "
            + "ON CONFLICT DO NOTHING",
        actor.tenantId(),
        actor.environmentId(),
        serviceId,
        trust.revision(),
        trust.digest());
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT revision, digest FROM " + SCHEMA + "qualification_waiver_trust_head "
                + "WHERE tenant_id = ? AND environment_id = ? AND service_id = ? FOR UPDATE",
            actor.tenantId(),
            actor.environmentId(),
            serviceId);
    long currentRevision = ((Number) rows.getFirst().get("revision")).longValue();
    String currentDigest = ((String) rows.getFirst().get("digest")).trim();
    if (trust.revision() < currentRevision) {
      throw new EvidenceException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "TRUST_SET_STALE",
          "The configured waiver trust set is older than the last accepted revision.");
    }
    if (trust.revision() == currentRevision && !trust.digest().equals(currentDigest)) {
      throw new EvidenceException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "TRUST_SET_CONFLICT",
          "The configured waiver trust set changed without increasing its revision.");
    }
    if (trust.revision() > currentRevision) {
      jdbc.update(
          "UPDATE " + SCHEMA + "qualification_waiver_trust_head "
              + "SET revision = ?, digest = ?, updated_at = now() "
              + "WHERE tenant_id = ? AND environment_id = ? AND service_id = ?",
          trust.revision(),
          trust.digest(),
          actor.tenantId(),
          actor.environmentId(),
          serviceId);
    }
  }

  private ObjectNode signingMaterial(
      String id,
      String policyId,
      long policyRevision,
      String requirementId,
      Instant windowStart,
      Instant windowEnd,
      Instant expiresAt,
      List<String> gapTypes,
      String reason,
      String serviceId,
      String purpose,
      String requestedBy) {
    Map<String, Object> material = new TreeMap<>();
    material.put("id", id);
    material.put("policy_id", policyId);
    material.put("policy_revision", policyRevision);
    material.put("requirement_id", requirementId);
    material.put("observation_window_start", windowStart.toString());
    material.put("observation_window_end", windowEnd.toString());
    material.put("expires_at", expiresAt.toString());
    material.put("gap_types", gapTypes);
    material.put("reason", reason);
    material.put("service_id", serviceId);
    material.put("purpose", purpose);
    material.put("requested_by", requestedBy);
    String json = writeJson(objectMapper.valueToTree(material));
    String canonicalText = OwnerReceiptSignature.canonicalize(json);
    ObjectNode result = objectMapper.createObjectNode();
    result.put("canonical_text", canonicalText);
    result.put("digest", sha256(canonicalText));
    return result;
  }

  private String replayReceipt(
      DelegationPrincipal actor,
      String operation,
      String key,
      String requestDigest,
      String expectedWaiverId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT request_digest, waiver_id, receipt_id FROM "
                + SCHEMA + "qualification_waiver_idempotency WHERE tenant_id = ? "
                + "AND environment_id = ? AND principal_id = ? AND operation = ? "
                + "AND idempotency_key = ? FOR UPDATE",
            actor.tenantId(),
            actor.environmentId(),
            actor.principalId(),
            operation,
            key);
    if (rows.isEmpty()) {
      return null;
    }
    Map<String, Object> row = rows.getFirst();
    if (!requestDigest.equals(((String) row.get("request_digest")).trim())
        || !expectedWaiverId.equals(row.get("waiver_id"))) {
      throw conflict(
          "IDEMPOTENCY_CONFLICT",
          "The idempotency key was already used for different qualification-waiver content.");
    }
    return (String) row.get("receipt_id");
  }

  private void recordIdempotency(
      DelegationPrincipal actor,
      String operation,
      String key,
      String requestDigest,
      String waiverId,
      String receiptId,
      JsonNode response) {
    jdbc.update(
        "INSERT INTO " + SCHEMA + "qualification_waiver_idempotency "
            + "(tenant_id, environment_id, principal_id, operation, idempotency_key, request_digest, "
            + "waiver_id, receipt_id, response) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
        actor.tenantId(),
        actor.environmentId(),
        actor.principalId(),
        operation,
        key,
        requestDigest,
        waiverId,
        receiptId,
        writeJson(response));
  }

  private void lockWaiver(DelegationPrincipal actor, String id) {
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        actor.tenantId() + "|" + actor.environmentId() + "|qualification-waiver|" + id);
  }

  private void lockIdempotency(DelegationPrincipal actor, String operation, String key) {
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        actor.tenantId()
            + "|"
            + actor.environmentId()
            + "|"
            + actor.principalId()
            + "|qualification-waiver|"
            + operation
            + "|"
            + key);
  }

  private String digestJson(JsonNode value) {
    return sha256(OwnerReceiptSignature.canonicalize(writeJson(value)));
  }

  private String writeJson(JsonNode value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException error) {
      throw new IllegalStateException("Could not serialize a qualification waiver value.", error);
    }
  }

  private JsonNode readJson(String value) {
    try {
      return objectMapper.readTree(value);
    } catch (JacksonException error) {
      throw new IllegalStateException("Could not read persisted qualification policy JSON.", error);
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime.", error);
    }
  }

  private static Instant parseInstant(String value) {
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (java.time.DateTimeException error) {
      throw invalidRequest("A waiver timestamp is not a valid date-time.");
    }
  }

  private static void validateIdempotencyKey(String key) {
    if (key == null || key.isBlank() || key.length() > 200
        || key.chars().allMatch(Character::isWhitespace)) {
      throw invalidRequest("Idempotency-Key must contain 1 to 200 non-whitespace characters.");
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

  private static long currentRevision(Map<String, Object> row) {
    if (row.get("revoked_at") != null) {
      return 3;
    }
    return row.get("decision") == null ? 1 : 2;
  }

  private static String state(Map<String, Object> row) {
    if (row.get("revoked_at") != null) {
      return "REVOKED";
    }
    return row.get("decision") == null ? "DRAFT" : (String) row.get("decision");
  }

  private static String trimNullable(String value) {
    return value == null ? null : value.trim();
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
          "The current principal cannot perform this qualification waiver operation.");
    }
  }

  private static void requireServiceScope(DelegationPrincipal actor, String serviceId) {
    if (!serviceId.equals(actor.serviceScope())) {
      throw new EvidenceException(
          HttpStatus.FORBIDDEN,
          "PERMISSION_DENIED",
          "The qualification waiver is outside the current exact service scope.");
    }
  }

  private static EvidenceException notFound() {
    return new EvidenceException(
        HttpStatus.NOT_FOUND,
        "NOT_FOUND",
        "The requested qualification policy or waiver was not found.");
  }

  private static EvidenceException invalidRequest(String message) {
    return new EvidenceException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
  }

  private static EvidenceException conflict(String code, String message) {
    return new EvidenceException(HttpStatus.CONFLICT, code, message);
  }
}
