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
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
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
  private final ObjectiveReviewAuthorityClient reviewerAuthority;
  private final ObjectiveWorkloadAuthorityClient workloadAuthority;

  ObjectivesService(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      ObjectiveReviewAuthorityClient reviewerAuthority,
      ObjectiveWorkloadAuthorityClient workloadAuthority) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.reviewerAuthority = reviewerAuthority;
    this.workloadAuthority = workloadAuthority;
  }

  @Transactional
  Objective propose(Jwt jwt, ProposeObjective request) {
    DelegationPrincipal actor = DelegationPrincipal.from(jwt);
    requireCapability(actor, "objectives.objective.write");
    requireGlobalScope(actor);
    return createObjective(jwt, actor, request);
  }

  @Transactional
  ObjectiveCreation createLocalIdempotent(Jwt jwt, ProposeObjective request, String idempotencyKey) {
    DelegationPrincipal actor = DelegationPrincipal.from(jwt);
    requireCapability(actor, "objectives.objective.write");
    requireGlobalScope(actor);
    validateIdempotencyKey(idempotencyKey);
    if (request.origin() != Origin.LOCAL || request.sourceRef() != null
        || request.sourceDefinitionId() != null || request.sourceRevision() != null
        || request.sourceVerifiedAt() != null) {
      throw new ObjectivesException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
          "This endpoint accepts local objectives without import provenance only.");
    }
    BigDecimal target = request.target().stripTrailingZeros();
    if (target.scale() > 6 || target.abs().compareTo(new BigDecimal("1000000000000000000")) >= 0) {
      throw new ObjectivesException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
          "The target must fit numeric(24,6) without rounding.");
    }
    Map<String, Object> material = new TreeMap<>();
    material.put("id", request.id());
    material.put("workload_id", request.workloadId());
    material.put("title", request.title());
    material.put("metric", request.metric());
    material.put("comparator", request.comparator().name());
    material.put("target", target.toPlainString());
    material.put("unit", request.unit());
    material.put("window_seconds", request.windowSeconds());
    material.put("origin", request.origin().name());
    ObjectiveImportFingerprint.Value semantics = validateLocalSemantics(request);
    if (semantics != null) {
      material.put("normalized_fingerprint_version", semantics.version());
      material.put("normalized_fingerprint", semantics.digest());
    }
    String digest = sha256(serialize(material));
    int inserted = jdbc.update(
        "INSERT INTO " + SCHEMA + "local_objective_creation "
            + "(tenant_id, environment_id, actor_id, idempotency_key, request_digest, objective_id) "
            + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        actor.tenantId(), actor.environmentId(), actor.principalId(), idempotencyKey, digest, request.id());
    if (inserted == 0) {
      List<Map<String, Object>> rows = jdbc.queryForList(
          "SELECT objective_id, request_digest FROM " + SCHEMA + "local_objective_creation "
              + "WHERE tenant_id = ? AND environment_id = ? AND actor_id = ? AND idempotency_key = ? FOR UPDATE",
          actor.tenantId(), actor.environmentId(), actor.principalId(), idempotencyKey);
      if (rows.isEmpty()) throw new IllegalStateException("The local objective creation record disappeared.");
      Map<String, Object> row = rows.getFirst();
      if (!digest.equals(row.get("request_digest").toString().trim())) {
        throw conflict("IDEMPOTENCY_CONFLICT", "The idempotency key was already used for different objective content.");
      }
      return new ObjectiveCreation(find(actor, row.get("objective_id").toString()), false);
    }
    return new ObjectiveCreation(createObjective(jwt, actor, request), true);
  }

  private Objective createObjective(Jwt jwt, DelegationPrincipal actor, ProposeObjective request) {
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
    ObjectiveImportFingerprint.Value semantics = validateLocalSemantics(request);
    workloadAuthority.check(
        jwt, request.workloadId(), ObjectiveWorkloadAuthorityClient.PROPOSAL_PURPOSE);
    String contentDigest = objectiveContentDigest(request.workloadId(), request.metric(), request.comparator().name(),
        request.target(), request.unit(), request.windowSeconds(), request.origin().name(),
        request.sourceDefinitionId(), request.sourceRevision(), semantics);
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
        contentDigest,
        actor.principalId());
    if (semantics != null) {
      jdbc.update("INSERT INTO " + SCHEMA + "local_objective_semantics "
          + "(tenant_id, environment_id, objective_id, objective_content_digest, fingerprint_version, "
          + "normalized_fingerprint, normalized_definition) VALUES (?, ?, ?, ?, ?, ?, ?)",
          actor.tenantId(), actor.environmentId(), request.id(), contentDigest, semantics.version(),
          semantics.digest(), ObjectiveImportFingerprint.canonical(request.normalizedDefinition()));
    }
    appendOutbox(actor, request.id(), 1, "objectives.objective.proposed", Map.of("state", "DRAFT"));
    return find(actor, request.id());
  }

  private static ObjectiveImportFingerprint.Value validateLocalSemantics(ProposeObjective request) {
    ObjectiveImportDefinition definition = request.normalizedDefinition();
    if (definition == null) return null;
    BigDecimal target = request.target().stripTrailingZeros();
    if (target.scale() > 6 || target.abs().compareTo(new BigDecimal("1000000000000000000")) >= 0) {
      throw new ObjectivesException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
          "The target must fit numeric(24,6) without rounding.");
    }
    if (request.origin() != Origin.LOCAL || !localSemanticsMatch(definition, request.metric(),
        request.comparator().name(), request.target(), request.unit(), request.windowSeconds())) {
      throw new ObjectivesException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
          "Normalized local semantics must match the objective's metric, target, unit, comparator and ISO duration.");
    }
    try {
      String canonical = ObjectiveImportFingerprint.canonical(definition);
      if (canonical.getBytes(StandardCharsets.UTF_8).length > 16384) {
        throw new IllegalArgumentException("The normalized definition is too large.");
      }
      return ObjectiveImportFingerprint.fingerprint(definition);
    } catch (IllegalArgumentException error) {
      throw new ObjectivesException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
          "The normalized local definition cannot be fingerprinted.");
    }
  }

  private static boolean localSemanticsMatch(ObjectiveImportDefinition definition, String metric, String comparator,
      BigDecimal target, String unit, int windowSeconds) {
    return metric.equals(definition.metric()) && unit.equals(definition.unit())
        && target.compareTo(definition.target()) == 0
        && java.time.Duration.ofSeconds(windowSeconds).toString().equals(definition.window())
        && (("LTE".equals(comparator) && definition.comparison() == ObjectiveImportDefinition.Comparison.AT_MOST)
            || ("GTE".equals(comparator) && definition.comparison() == ObjectiveImportDefinition.Comparison.AT_LEAST));
  }

  private static String objectiveContentDigest(String workloadId, String metric, String comparator,
      BigDecimal target, String unit, int windowSeconds, String origin, String sourceDefinitionId,
      String sourceRevision, ObjectiveImportFingerprint.Value semantics) {
    String canonical = String.join("|", workloadId, metric, comparator, target.stripTrailingZeros().toPlainString(),
        unit, Integer.toString(windowSeconds), origin, String.valueOf(sourceDefinitionId), String.valueOf(sourceRevision));
    if (semantics != null) canonical += "|NORMALIZED_DEFINITION_V1|" + semantics.version() + "|" + semantics.digest();
    return sha256(canonical);
  }

  @Transactional
  ImportPreviewAdmission previewImport(
      DelegationPrincipal actor, String idempotencyKey, ImportPreviewRequest request) {
    requireCapability(actor, "objectives.objective.write");
    requireGlobalScope(actor);
    validateIdempotencyKey(idempotencyKey);

    Map<String, Object> requestBody = new TreeMap<>();
    requestBody.put("connection_id", request.connectionId());
    requestBody.put("external_objective_id", request.externalObjectiveId());
    requestBody.put("target_workload_id", request.targetWorkloadId());
    requestBody.put("mapping_revision", request.mappingRevision());
    requestBody.put("expected_local_revision", request.expectedLocalRevision());
    String serializedRequest = serialize(requestBody);
    String requestDigest = sha256(serializedRequest);
    String commandId = UUID.randomUUID().toString();
    int inserted =
        jdbc.update(
            "INSERT INTO "
                + SCHEMA
                + "objective_import_preview_command (command_id, tenant_id, environment_id, actor_id, "
                + "idempotency_key, target_workload_id, request_digest, request_body, status) "
                + "VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?::jsonb, 'PENDING') "
                + "ON CONFLICT (tenant_id, environment_id, actor_id, idempotency_key) DO NOTHING",
            commandId,
            actor.tenantId(),
            actor.environmentId(),
            actor.principalId(),
            idempotencyKey,
            request.targetWorkloadId(),
            requestDigest,
            serializedRequest);
    if (inserted == 0) {
      List<Map<String, Object>> existing =
          jdbc.queryForList(
              "SELECT command_id, request_digest FROM "
                  + SCHEMA
                  + "objective_import_preview_command WHERE tenant_id = ? AND environment_id = ? "
                  + "AND actor_id = ? AND idempotency_key = ? FOR UPDATE",
              actor.tenantId(),
              actor.environmentId(),
              actor.principalId(),
              idempotencyKey);
      if (existing.isEmpty()) {
        throw new IllegalStateException("The idempotent import preview command disappeared.");
      }
      Map<String, Object> row = existing.getFirst();
      if (!requestDigest.equals(row.get("request_digest").toString().trim())) {
        throw conflict(
            "IDEMPOTENCY_CONFLICT",
            "The idempotency key was already used for a different import preview request.");
      }
      return importPreviewAdmission(row.get("command_id").toString(), false);
    }

    appendOutbox(
        actor,
        "OBJECTIVE_IMPORT_PREVIEW",
        commandId,
        1,
        "uacrf.s05.objective-import.preview.requested.v1",
        Map.of("status", "PENDING", "request_digest", requestDigest));
    return importPreviewAdmission(commandId, true);
  }

  @Transactional(readOnly = true)
  Page<PendingImportSummary> listImportPreviews(DelegationPrincipal actor, int pageSize, String cursor) {
    requireCapability(actor, "objectives.objective.read");
    requireGlobalScope(actor);
    UUID after = decodeUuidCursor(cursor);
    List<PendingImportSummary> rows = jdbc.query(
        "SELECT command_id, status, target_workload_id, created_at FROM " + SCHEMA
            + "objective_import_preview_command WHERE tenant_id = ? AND environment_id = ? "
            + "AND (?::uuid IS NULL OR command_id > ?::uuid) ORDER BY command_id LIMIT ?",
        (rs, n) -> new PendingImportSummary(rs.getString("command_id"), rs.getString("status"),
            rs.getString("target_workload_id"), rs.getTimestamp("created_at").toInstant().toString()),
        actor.tenantId(), actor.environmentId(), after, after, pageSize + 1);
    boolean more = rows.size() > pageSize;
    List<PendingImportSummary> items = more ? rows.subList(0, pageSize) : rows;
    String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString(
        items.getLast().receiptId().getBytes(StandardCharsets.UTF_8)) : null;
    return new Page<>(items, next);
  }

  @Transactional(readOnly = true)
  PendingImportReceipt getImportPreview(DelegationPrincipal actor, String id) {
    requireCapability(actor, "objectives.objective.read");
    requireGlobalScope(actor);
    UUID commandId;
    try {
      commandId = UUID.fromString(id);
    } catch (IllegalArgumentException error) {
      throw notFound();
    }
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT status FROM "
                + SCHEMA
                + "objective_import_preview_command WHERE tenant_id = ? AND environment_id = ? "
                + "AND command_id = ?",
            actor.tenantId(),
            actor.environmentId(),
            commandId);
    if (rows.isEmpty()) {
      throw notFound();
    }
    return new PendingImportReceipt(
        commandId.toString(),
        rows.getFirst().get("status").toString(),
        "/v1/objective-imports/" + commandId);
  }

  @Transactional(readOnly = true)
  SampleSourceRequest getSampleSourceRequest(DelegationPrincipal actor, String id) {
    requireCapability(actor, "objectives.objective.read");
    requireGlobalScope(actor);
    UUID commandId;
    try {
      commandId = UUID.fromString(id);
    } catch (IllegalArgumentException error) {
      throw notFound();
    }
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT request_body ->> 'connection_id' AS connection_id, "
            + "request_body ->> 'external_objective_id' AS external_id, "
            + "request_body ->> 'target_workload_id' AS body_workload_id, "
            + "request_body ->> 'mapping_revision' AS mapping_revision, "
            + "request_body ->> 'expected_local_revision' AS expected_local_revision, "
            + "(jsonb_typeof(request_body -> 'connection_id') = 'string' "
            + "AND jsonb_typeof(request_body -> 'external_objective_id') = 'string' "
            + "AND jsonb_typeof(request_body -> 'target_workload_id') = 'string' "
            + "AND jsonb_typeof(request_body -> 'mapping_revision') = 'number' "
            + "AND jsonb_typeof(request_body -> 'expected_local_revision') = 'number') AS valid_types, "
            + "(SELECT count(*) FROM jsonb_object_keys(request_body)) AS field_count, "
            + "target_workload_id, request_digest FROM " + SCHEMA
            + "objective_import_preview_command WHERE tenant_id = ? AND environment_id = ? "
            + "AND command_id = ? AND status = 'PENDING'",
        actor.tenantId(), actor.environmentId(), commandId);
    if (rows.isEmpty()) throw notFound();
    Map<String, Object> row = rows.getFirst();
    String connectionId = (String) row.get("connection_id");
    String externalId = (String) row.get("external_id");
    String workloadId = (String) row.get("target_workload_id");
    long mappingRevision;
    long localRevision;
    try {
      mappingRevision = Long.parseLong((String) row.get("mapping_revision"));
      localRevision = Long.parseLong((String) row.get("expected_local_revision"));
    } catch (NumberFormatException error) {
      throw new IllegalStateException("The retained import request revisions are invalid.", error);
    }
    if (connectionId == null || connectionId.isBlank() || connectionId.length() > 200
        || externalId == null || externalId.isBlank() || externalId.length() > 1000
        || workloadId == null || workloadId.isBlank() || workloadId.length() > 200
        || !workloadId.equals(row.get("body_workload_id"))
        || mappingRevision < 1 || localRevision < 0
        || !Boolean.TRUE.equals(row.get("valid_types"))
        || ((Number) row.get("field_count")).longValue() != 5) {
      throw new IllegalStateException("The retained import request is incomplete or inconsistent.");
    }
    Map<String, Object> retained = new TreeMap<>();
    retained.put("connection_id", connectionId);
    retained.put("external_objective_id", externalId);
    retained.put("target_workload_id", workloadId);
    retained.put("mapping_revision", mappingRevision);
    retained.put("expected_local_revision", localRevision);
    if (!sha256(serialize(retained)).equals(row.get("request_digest").toString().trim())) {
      throw new IllegalStateException("The retained import request digest does not match.");
    }
    return new SampleSourceRequest(connectionId, externalId, workloadId, row.get("request_digest").toString().trim());
  }

  record SampleSourceRequest(String connectionId, String externalObjectiveId, String targetWorkloadId,
      String requestDigest) {}

  SampleSourceRequest getSampleCaptureRequest(DelegationPrincipal actor, String id, String idempotencyKey) {
    requireCapability(actor, "objectives.objective.write");
    validateIdempotencyKey(idempotencyKey);
    return getSampleSourceRequest(actor, id);
  }

  @Transactional
  SampleCapture captureSample(DelegationPrincipal actor, String id, String idempotencyKey,
      SampleSourceRequest expected, ObjectiveImportSampleSourceClient.Fetched fetched) {
    requireCapability(actor, "objectives.objective.write");
    requireCapability(actor, "objectives.objective.read");
    requireGlobalScope(actor);
    validateIdempotencyKey(idempotencyKey);
    UUID commandId;
    try {
      commandId = UUID.fromString(id);
    } catch (IllegalArgumentException error) {
      throw notFound();
    }
    List<UUID> locked = jdbc.query(
        "SELECT command_id FROM " + SCHEMA
            + "objective_import_preview_command WHERE tenant_id = ? AND environment_id = ? "
            + "AND command_id = ? AND status = 'PENDING' FOR UPDATE",
        (rs, row) -> rs.getObject("command_id", UUID.class),
        actor.tenantId(), actor.environmentId(), commandId);
    if (locked.isEmpty()) throw notFound();
    if (!expected.equals(getSampleSourceRequest(actor, id))) {
      throw conflict("IMPORT_REQUEST_CHANGED", "The retained import request changed during source capture.");
    }
    var source = fetched.inspection();
    if (!expected.connectionId().equals(source.connectionId())
        || !expected.externalObjectiveId().equals(source.externalObjectiveId())
        || !expected.targetWorkloadId().equals(source.targetWorkloadId())) {
      throw new IllegalStateException("The captured synthetic source does not match the retained request.");
    }
    UUID captureId = UUID.randomUUID();
    int inserted = jdbc.update(
        "INSERT INTO " + SCHEMA + "synthetic_objective_source_capture "
            + "(capture_id, tenant_id, environment_id, command_id, actor_id, idempotency_key, request_digest, "
            + "connection_id, connection_revision, binding_id, binding_revision, target_workload_id, "
            + "source_system, external_objective_id, source_version, payload, payload_digest, "
            + "definition_fingerprint_version, definition_fingerprint, verification) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (tenant_id, environment_id, actor_id, idempotency_key) DO NOTHING",
        captureId, actor.tenantId(), actor.environmentId(), commandId, actor.principalId(), idempotencyKey,
        expected.requestDigest(), source.connectionId(), source.connectionRevision(), source.bindingId(),
        source.bindingRevision(), source.targetWorkloadId(), source.sourceSystem(), source.externalObjectiveId(),
        source.sourceVersion(), fetched.payload(), source.payloadDigest(), source.definitionFingerprint().version(),
        source.definitionFingerprint().digest(), source.verification());
    if (inserted == 0) {
      List<Map<String, Object>> rows = jdbc.queryForList(
          "SELECT capture_id, command_id, request_digest, captured_at FROM " + SCHEMA
              + "synthetic_objective_source_capture WHERE tenant_id = ? AND environment_id = ? "
              + "AND actor_id = ? AND idempotency_key = ?",
          actor.tenantId(), actor.environmentId(), actor.principalId(), idempotencyKey);
      if (rows.isEmpty()) throw new IllegalStateException("The synthetic capture receipt disappeared.");
      Map<String, Object> row = rows.getFirst();
      if (!commandId.equals(row.get("command_id"))
          || !expected.requestDigest().equals(row.get("request_digest").toString().trim())) {
        throw conflict("IDEMPOTENCY_CONFLICT", "The idempotency key belongs to a different import request.");
      }
      return new SampleCapture(row.get("capture_id").toString(), commandId.toString(),
          ((Timestamp) row.get("captured_at")).toInstant().toString(), false);
    }
    Timestamp created = jdbc.queryForObject(
        "SELECT captured_at FROM " + SCHEMA + "synthetic_objective_source_capture WHERE capture_id = ?",
        Timestamp.class, captureId);
    if (created == null) throw new IllegalStateException("The synthetic capture receipt disappeared.");
    return new SampleCapture(captureId.toString(), commandId.toString(), created.toInstant().toString(), true);
  }

  record SampleCapture(String captureId, String receiptId, String capturedAt, boolean newlyCreated) {}

  @Transactional(readOnly = true)
  RetainedSampleCapture getRetainedSampleCapture(DelegationPrincipal actor, String id, String captureId) {
    requireCapability(actor, "objectives.objective.read");
    requireGlobalScope(actor);
    UUID command;
    UUID capture;
    try {
      command = UUID.fromString(id);
      capture = UUID.fromString(captureId);
    } catch (IllegalArgumentException error) {
      throw notFound();
    }
    List<RetainedSampleCapture> rows = jdbc.query(
        "SELECT capture_id, command_id, captured_at, actor_id, request_digest, connection_id, "
            + "connection_revision, binding_id, binding_revision, target_workload_id, source_system, "
            + "external_objective_id, source_version, payload, payload_digest, "
            + "definition_fingerprint_version, definition_fingerprint, verification FROM " + SCHEMA
            + "synthetic_objective_source_capture WHERE tenant_id = ? AND environment_id = ? "
            + "AND command_id = ? AND capture_id = ?",
        (rs, n) -> new RetainedSampleCapture(rs.getString("capture_id"), rs.getString("command_id"),
            rs.getTimestamp("captured_at").toInstant().toString(), rs.getString("actor_id"),
            rs.getString("request_digest").trim(), rs.getString("connection_id"),
            rs.getLong("connection_revision"), rs.getString("binding_id"), rs.getLong("binding_revision"),
            rs.getString("target_workload_id"), rs.getString("source_system"), rs.getString("external_objective_id"),
            rs.getString("source_version"), rs.getString("payload"), rs.getString("payload_digest").trim(),
            rs.getString("definition_fingerprint_version"), rs.getString("definition_fingerprint").trim(),
            rs.getString("verification")),
        actor.tenantId(), actor.environmentId(), command, capture);
    if (rows.isEmpty()) throw notFound();
    return rows.getFirst();
  }

  record RetainedSampleCapture(String captureId, String receiptId, String capturedAt, String actorId,
      String requestDigest, String connectionId, long connectionRevision, String bindingId, long bindingRevision,
      String targetWorkloadId, String sourceSystem, String externalObjectiveId, String sourceVersion, String payload,
      String payloadDigest, String definitionFingerprintVersion, String definitionFingerprint, String verification) {}

  @Transactional(readOnly = true)
  SampleNormalizationRehearsal rehearseSampleNormalization(DelegationPrincipal actor, String id, String captureId,
      String targetUnit, boolean allowFractionToPercent) {
    RetainedSampleCapture capture = getRetainedSampleCapture(actor, id, captureId);
    if (targetUnit == null || targetUnit.isBlank() || targetUnit.length() > 80) {
      throw new ObjectivesException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "A valid target unit is required.");
    }
    ObjectiveImportDefinition source;
    ObjectiveImportFingerprint.Value original;
    try {
      source = ObjectiveImportSampleParser.parse(capture.payload());
      original = ObjectiveImportFingerprint.fingerprint(source);
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException("The retained synthetic capture is invalid.", error);
    }
    if (!sha256(capture.payload()).equals(capture.payloadDigest())
        || !original.version().equals(capture.definitionFingerprintVersion())
        || !original.digest().equals(capture.definitionFingerprint())) {
      throw new IllegalStateException("The retained synthetic capture fingerprints do not match.");
    }
    ObjectiveImportDefinition normalized;
    ObjectiveImportFingerprint.Value normalizedFingerprint;
    try {
      normalized = source.convert(targetUnit, source.window(), allowFractionToPercent);
      normalizedFingerprint = ObjectiveImportFingerprint.fingerprint(normalized);
    } catch (IllegalArgumentException error) {
      throw new ObjectivesException(HttpStatus.BAD_REQUEST, "INVALID_CONVERSION", error.getMessage());
    }
    return new SampleNormalizationRehearsal(capture.captureId(), capture.receiptId(), source, original,
        normalized, normalizedFingerprint, "SYNTHETIC_UNVERIFIED", "UNAPPROVED_REHEARSAL");
  }

  record SampleNormalizationRehearsal(String captureId, String receiptId, ObjectiveImportDefinition sourceDefinition,
      ObjectiveImportFingerprint.Value sourceFingerprint, ObjectiveImportDefinition normalizedDefinition,
      ObjectiveImportFingerprint.Value normalizedFingerprint, String verification, String status) {}

  @Transactional
  SyntheticMappingAdmission proposeSyntheticMapping(DelegationPrincipal actor, String id, String captureId,
      String idempotencyKey, SyntheticMappingProposalRequest request) {
    requireCapability(actor, "objectives.objective.write");
    requireCapability(actor, "objectives.objective.read");
    requireGlobalScope(actor);
    validateIdempotencyKey(idempotencyKey);
    UUID command;
    try {
      command = UUID.fromString(id);
    } catch (IllegalArgumentException error) {
      throw notFound();
    }
    List<UUID> locked = jdbc.query(
        "SELECT command_id FROM " + SCHEMA
            + "objective_import_preview_command WHERE tenant_id = ? AND environment_id = ? "
            + "AND command_id = ? AND status = 'PENDING' FOR UPDATE",
        (rs, row) -> rs.getObject("command_id", UUID.class),
        actor.tenantId(), actor.environmentId(), command);
    if (locked.isEmpty()) throw notFound();
    SampleNormalizationRehearsal rehearsal = rehearseSampleNormalization(
        actor, id, captureId, request.targetUnit(), request.allowFractionToPercent());
    Map<String, Object> input = new TreeMap<>();
    input.put("capture_id", rehearsal.captureId());
    input.put("receipt_id", rehearsal.receiptId());
    input.put("target_unit", request.targetUnit());
    input.put("allow_fraction_to_percent", request.allowFractionToPercent());
    input.put("reason", request.reason());
    String requestDigest = sha256(serialize(input));
    UUID proposalId = UUID.randomUUID();
    int inserted = jdbc.update(
        "INSERT INTO " + SCHEMA + "synthetic_objective_mapping_proposal "
            + "(proposal_id, tenant_id, environment_id, command_id, capture_id, proposed_by, idempotency_key, "
            + "request_digest, target_unit, allow_fraction_to_percent, reason, fingerprint_version, "
            + "source_fingerprint, normalized_fingerprint, status, verification) "
            + "VALUES (?, ?, ?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNAPPROVED', 'SYNTHETIC_UNVERIFIED') "
            + "ON CONFLICT (tenant_id, environment_id, proposed_by, idempotency_key) DO NOTHING",
        proposalId, actor.tenantId(), actor.environmentId(), command, rehearsal.captureId(), actor.principalId(),
        idempotencyKey, requestDigest, request.targetUnit(), request.allowFractionToPercent(), request.reason(),
        rehearsal.sourceFingerprint().version(), rehearsal.sourceFingerprint().digest(),
        rehearsal.normalizedFingerprint().digest());
    if (inserted == 0) {
      List<Map<String, Object>> rows = jdbc.queryForList(
          "SELECT proposal_id, request_digest FROM " + SCHEMA
              + "synthetic_objective_mapping_proposal WHERE tenant_id = ? AND environment_id = ? "
              + "AND proposed_by = ? AND idempotency_key = ?",
          actor.tenantId(), actor.environmentId(), actor.principalId(), idempotencyKey);
      if (rows.isEmpty()) throw new IllegalStateException("The synthetic mapping proposal disappeared.");
      Map<String, Object> existing = rows.getFirst();
      if (!requestDigest.equals(existing.get("request_digest").toString().trim())) {
        throw conflict("IDEMPOTENCY_CONFLICT", "The idempotency key belongs to a different mapping proposal.");
      }
      proposalId = (UUID) existing.get("proposal_id");
    }
    return new SyntheticMappingAdmission(
        getSyntheticMappingProposal(actor, id, captureId, proposalId.toString()), inserted == 1);
  }

  @Transactional(readOnly = true)
  Page<SyntheticMappingProposalSummary> listSyntheticMappingProposals(
      DelegationPrincipal actor, String id, String captureId, int pageSize, String cursor) {
    RetainedSampleCapture capture = getRetainedSampleCapture(actor, id, captureId);
    UUID command = UUID.fromString(capture.receiptId());
    UUID captureUuid = UUID.fromString(capture.captureId());
    String scope = sha256(serialize(new TreeMap<>(Map.of("tenant_id", actor.tenantId(),
        "environment_id", actor.environmentId(), "receipt_id", command.toString(), "capture_id", captureUuid.toString()))));
    String decoded = decodeCursor(cursor);
    UUID after = null;
    if (!decoded.isEmpty()) {
      String prefix = "SM1:" + scope + ":";
      if (!decoded.startsWith(prefix)) {
        throw new ObjectivesException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "cursor does not match this capture.");
      }
      String position = decoded.substring(prefix.length());
      try {
        after = UUID.fromString(position);
        if (!after.toString().equals(position)) throw new IllegalArgumentException();
      } catch (IllegalArgumentException error) {
        throw new ObjectivesException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "cursor is malformed.");
      }
    }
    List<SyntheticMappingProposalSummary> rows = jdbc.query(
        "SELECT proposal_id, proposed_at, proposed_by, target_unit, status, verification FROM " + SCHEMA
            + "synthetic_objective_mapping_proposal WHERE tenant_id = ? AND environment_id = ? "
            + "AND command_id = ? AND capture_id = ? AND (?::uuid IS NULL OR proposal_id > ?::uuid) "
            + "ORDER BY proposal_id LIMIT ?",
        (rs, row) -> new SyntheticMappingProposalSummary(rs.getString("proposal_id"),
            rs.getTimestamp("proposed_at").toInstant().toString(), rs.getString("proposed_by"),
            rs.getString("target_unit"), rs.getString("status"), rs.getString("verification")),
        actor.tenantId(), actor.environmentId(), command, captureUuid, after, after, pageSize + 1);
    boolean more = rows.size() > pageSize;
    List<SyntheticMappingProposalSummary> items = more ? rows.subList(0, pageSize) : rows;
    String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString(
        ("SM1:" + scope + ":" + items.getLast().proposalId()).getBytes(StandardCharsets.UTF_8)) : null;
    return new Page<>(items, next);
  }

  record SyntheticMappingProposalSummary(String proposalId, String proposedAt, String proposedBy,
      String targetUnit, String status, String verification) {}

  @Transactional(readOnly = true)
  SyntheticMappingProposal getSyntheticMappingProposal(
      DelegationPrincipal actor, String id, String captureId, String proposalId) {
    requireCapability(actor, "objectives.objective.read");
    requireGlobalScope(actor);
    UUID command;
    UUID capture;
    UUID proposal;
    try {
      command = UUID.fromString(id);
      capture = UUID.fromString(captureId);
      proposal = UUID.fromString(proposalId);
    } catch (IllegalArgumentException error) {
      throw notFound();
    }
    List<SyntheticMappingProposal> rows = jdbc.query(
        "SELECT proposal_id, command_id, capture_id, proposed_by, target_unit, allow_fraction_to_percent, "
            + "reason, fingerprint_version, source_fingerprint, normalized_fingerprint, status, verification, "
            + "proposed_at FROM " + SCHEMA + "synthetic_objective_mapping_proposal "
            + "WHERE tenant_id = ? AND environment_id = ? AND command_id = ? AND capture_id = ? AND proposal_id = ?",
        (rs, row) -> new SyntheticMappingProposal(rs.getString("proposal_id"), rs.getString("command_id"),
            rs.getString("capture_id"), rs.getString("proposed_by"), rs.getString("target_unit"),
            rs.getBoolean("allow_fraction_to_percent"), rs.getString("reason"), rs.getString("fingerprint_version"),
            rs.getString("source_fingerprint").trim(), rs.getString("normalized_fingerprint").trim(),
            rs.getString("status"), rs.getString("verification"), rs.getTimestamp("proposed_at").toInstant().toString(),
            null, null),
        actor.tenantId(), actor.environmentId(), command, capture, proposal);
    if (rows.isEmpty()) throw notFound();
    SyntheticMappingProposal value = rows.getFirst();
    return new SyntheticMappingProposal(value.proposalId(), value.receiptId(), value.captureId(), value.proposedBy(),
        value.targetUnit(), value.allowFractionToPercent(), value.reason(), value.fingerprintVersion(),
        value.sourceFingerprint(), value.normalizedFingerprint(), value.status(), value.verification(),
        value.proposedAt(), "SYNTHETIC_MAPPING_PROPOSAL_SHA256_V1", syntheticProposalDigest(actor, value));
  }

  private String syntheticProposalDigest(DelegationPrincipal actor, SyntheticMappingProposal value) {
    return sha256("{\"allow_fraction_to_percent\":" + value.allowFractionToPercent()
        + ",\"capture_id\":" + ObjectiveImportFingerprint.text(value.captureId())
        + ",\"environment_id\":" + ObjectiveImportFingerprint.text(actor.environmentId())
        + ",\"fingerprint_version\":" + ObjectiveImportFingerprint.text(value.fingerprintVersion())
        + ",\"normalized_fingerprint\":" + ObjectiveImportFingerprint.text(value.normalizedFingerprint())
        + ",\"proposal_id\":" + ObjectiveImportFingerprint.text(value.proposalId())
        + ",\"proposed_at\":" + ObjectiveImportFingerprint.text(value.proposedAt())
        + ",\"proposed_by\":" + ObjectiveImportFingerprint.text(value.proposedBy())
        + ",\"reason\":" + ObjectiveImportFingerprint.text(value.reason())
        + ",\"receipt_id\":" + ObjectiveImportFingerprint.text(value.receiptId())
        + ",\"source_fingerprint\":" + ObjectiveImportFingerprint.text(value.sourceFingerprint())
        + ",\"status\":" + ObjectiveImportFingerprint.text(value.status())
        + ",\"target_unit\":" + ObjectiveImportFingerprint.text(value.targetUnit())
        + ",\"tenant_id\":" + ObjectiveImportFingerprint.text(actor.tenantId())
        + ",\"verification\":" + ObjectiveImportFingerprint.text(value.verification()) + "}");
  }

  record SyntheticMappingAdmission(SyntheticMappingProposal proposal, boolean newlyCreated) {}

  record SyntheticMappingProposal(String proposalId, String receiptId, String captureId, String proposedBy,
      String targetUnit, boolean allowFractionToPercent, String reason, String fingerprintVersion,
      String sourceFingerprint, String normalizedFingerprint, String status, String verification, String proposedAt,
      String proposalDigestVersion, String proposalDigest) {}

  @Transactional
  SyntheticMappingReviewAdmission reviewSyntheticMapping(DelegationPrincipal actor, String id, String captureId,
      String proposalId, String key, SyntheticMappingReviewRequest request) {
    requireCapability(actor, "objectives.objective.read");
    requireCapability(actor, "objectives.objective.review");
    requireGlobalScope(actor);
    validateIdempotencyKey(key);
    UUID command;
    UUID capture;
    UUID proposal;
    try {
      command = UUID.fromString(id);
      capture = UUID.fromString(captureId);
      proposal = UUID.fromString(proposalId);
    } catch (IllegalArgumentException error) {
      throw notFound();
    }
    List<UUID> commands = jdbc.query("SELECT command_id FROM " + SCHEMA
        + "objective_import_preview_command WHERE tenant_id = ? AND environment_id = ? "
        + "AND command_id = ? AND status = 'PENDING' FOR UPDATE",
        (rs, row) -> rs.getObject("command_id", UUID.class), actor.tenantId(), actor.environmentId(), command);
    if (commands.isEmpty()) throw notFound();
    List<UUID> proposals = jdbc.query("SELECT proposal_id FROM " + SCHEMA
        + "synthetic_objective_mapping_proposal WHERE tenant_id = ? AND environment_id = ? "
        + "AND command_id = ? AND capture_id = ? AND proposal_id = ? FOR UPDATE",
        (rs, row) -> rs.getObject("proposal_id", UUID.class),
        actor.tenantId(), actor.environmentId(), command, capture, proposal);
    if (proposals.isEmpty()) throw notFound();
    SyntheticMappingProposal selected = getSyntheticMappingProposal(actor, id, captureId, proposalId);
    if (actor.principalId().equals(selected.proposedBy())) {
      throw conflict("REVIEWER_NOT_INDEPENDENT", "The proposer cannot review their own mapping proposal.");
    }
    if (!selected.proposalDigestVersion().equals(request.expectedProposalDigestVersion())
        || !selected.proposalDigest().equals(request.expectedProposalDigest())) {
      throw conflict("CONTENT_MISMATCH", "The synthetic mapping proposal differs from the reviewed content.");
    }
    SampleNormalizationRehearsal rehearsal = rehearseSampleNormalization(
        actor, id, captureId, selected.targetUnit(), selected.allowFractionToPercent());
    if (!selected.fingerprintVersion().equals(rehearsal.sourceFingerprint().version())
        || !selected.sourceFingerprint().equals(rehearsal.sourceFingerprint().digest())
        || !selected.normalizedFingerprint().equals(rehearsal.normalizedFingerprint().digest())) {
      throw conflict("CONTENT_MISMATCH", "The retained mapping proposal no longer matches its capture.");
    }
    reviewerAuthority.checkRequired(actor.tenantId(), actor.environmentId(), actor.principalId());
    Map<String, Object> material = new TreeMap<>();
    material.put("proposal_id", selected.proposalId());
    material.put("proposal_digest_version", selected.proposalDigestVersion());
    material.put("proposal_digest", selected.proposalDigest());
    material.put("decision", request.decision().name());
    material.put("reason", request.reason());
    String requestDigest = sha256(serialize(material));
    UUID reviewId = UUID.randomUUID();
    int inserted = jdbc.update("INSERT INTO " + SCHEMA + "synthetic_objective_mapping_review "
        + "(review_id, tenant_id, environment_id, command_id, capture_id, proposal_id, reviewer_id, "
        + "idempotency_key, request_digest, proposal_digest_version, proposal_digest, decision, reason, verification) "
        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SYNTHETIC_UNVERIFIED') ON CONFLICT DO NOTHING",
        reviewId, actor.tenantId(), actor.environmentId(), command, capture, proposal, actor.principalId(), key,
        requestDigest, selected.proposalDigestVersion(), selected.proposalDigest(), request.decision().name(),
        request.reason());
    if (inserted == 0) {
      List<Map<String, Object>> rows = jdbc.queryForList(
          "SELECT review_id, request_digest FROM " + SCHEMA
              + "synthetic_objective_mapping_review WHERE tenant_id = ? AND environment_id = ? "
              + "AND reviewer_id = ? AND idempotency_key = ?",
          actor.tenantId(), actor.environmentId(), actor.principalId(), key);
      if (rows.isEmpty()) {
        throw conflict("ALREADY_REVIEWED", "The synthetic mapping proposal already has a review decision.");
      }
      Map<String, Object> existing = rows.getFirst();
      if (!requestDigest.equals(existing.get("request_digest").toString().trim())) {
        throw conflict("IDEMPOTENCY_CONFLICT", "The idempotency key belongs to a different mapping review.");
      }
      reviewId = (UUID) existing.get("review_id");
    }
    return new SyntheticMappingReviewAdmission(
        getSyntheticMappingReview(actor, id, captureId, proposalId, reviewId.toString()), inserted == 1);
  }

  @Transactional(readOnly = true)
  SyntheticMappingReviews listSyntheticMappingReviews(
      DelegationPrincipal actor, String id, String captureId, String proposalId) {
    SyntheticMappingProposal proposal = getSyntheticMappingProposal(actor, id, captureId, proposalId);
    List<UUID> ids = jdbc.query("SELECT review_id FROM " + SCHEMA
        + "synthetic_objective_mapping_review WHERE tenant_id = ? AND environment_id = ? "
        + "AND command_id = ? AND capture_id = ? AND proposal_id = ?",
        (rs, row) -> rs.getObject("review_id", UUID.class), actor.tenantId(), actor.environmentId(),
        UUID.fromString(proposal.receiptId()), UUID.fromString(proposal.captureId()), UUID.fromString(proposal.proposalId()));
    return new SyntheticMappingReviews(ids.stream()
        .map(reviewId -> getSyntheticMappingReview(actor, id, captureId, proposalId, reviewId.toString())).toList());
  }

  record SyntheticMappingReviews(List<SyntheticMappingReview> items) {}

  @Transactional(readOnly = true)
  SyntheticMappingReview getSyntheticMappingReview(DelegationPrincipal actor, String id, String captureId,
      String proposalId, String reviewId) {
    requireCapability(actor, "objectives.objective.read");
    requireGlobalScope(actor);
    UUID command;
    UUID capture;
    UUID proposal;
    UUID review;
    try {
      command = UUID.fromString(id);
      capture = UUID.fromString(captureId);
      proposal = UUID.fromString(proposalId);
      review = UUID.fromString(reviewId);
    } catch (IllegalArgumentException error) {
      throw notFound();
    }
    List<SyntheticMappingReview> rows = jdbc.query(
        "SELECT review_id, command_id, capture_id, proposal_id, reviewer_id, proposal_digest_version, "
            + "proposal_digest, decision, reason, verification, reviewed_at FROM " + SCHEMA
            + "synthetic_objective_mapping_review WHERE tenant_id = ? AND environment_id = ? "
            + "AND command_id = ? AND capture_id = ? AND proposal_id = ? AND review_id = ?",
        (rs, row) -> new SyntheticMappingReview(rs.getString("review_id"), rs.getString("command_id"),
            rs.getString("capture_id"), rs.getString("proposal_id"), rs.getString("reviewer_id"),
            rs.getString("proposal_digest_version"), rs.getString("proposal_digest").trim(),
            rs.getString("decision"), rs.getString("reason"), rs.getString("verification"),
            rs.getTimestamp("reviewed_at").toInstant().toString()),
        actor.tenantId(), actor.environmentId(), command, capture, proposal, review);
    if (rows.isEmpty()) throw notFound();
    return rows.getFirst();
  }

  record SyntheticMappingReviewAdmission(SyntheticMappingReview review, boolean newlyCreated) {}

  record SyntheticMappingReview(String reviewId, String receiptId, String captureId, String proposalId,
      String reviewerId, String proposalDigestVersion, String proposalDigest, String decision, String reason,
      String verification, String reviewedAt) {}

  @Transactional
  SyntheticCandidateAdmission createSyntheticCandidate(DelegationPrincipal actor, String id, String captureId,
      String proposalId, String reviewId, String key, SyntheticCandidateRequest request) {
    requireCapability(actor, "objectives.objective.read");
    requireCapability(actor, "objectives.objective.write");
    requireGlobalScope(actor);
    validateIdempotencyKey(key);
    UUID command;
    try {
      command = UUID.fromString(id);
    } catch (IllegalArgumentException error) {
      throw notFound();
    }
    List<UUID> commands = jdbc.query("SELECT command_id FROM " + SCHEMA
        + "objective_import_preview_command WHERE tenant_id = ? AND environment_id = ? "
        + "AND command_id = ? AND status = 'PENDING' FOR UPDATE",
        (rs, row) -> rs.getObject("command_id", UUID.class), actor.tenantId(), actor.environmentId(), command);
    if (commands.isEmpty()) throw notFound();
    SyntheticMappingProposal proposal = getSyntheticMappingProposal(actor, id, captureId, proposalId);
    SyntheticMappingReview review = getSyntheticMappingReview(actor, id, captureId, proposalId, reviewId);
    if (!"APPROVE".equals(review.decision())) {
      throw conflict("MAPPING_NOT_APPROVED", "The historical synthetic review did not approve this proposal.");
    }
    if (!proposal.proposalDigestVersion().equals(request.expectedProposalDigestVersion())
        || !proposal.proposalDigest().equals(request.expectedProposalDigest())
        || !proposal.proposalDigestVersion().equals(review.proposalDigestVersion())
        || !proposal.proposalDigest().equals(review.proposalDigest())) {
      throw conflict("CONTENT_MISMATCH", "The synthetic proposal differs from the reviewed content.");
    }
    SampleNormalizationRehearsal rehearsal = rehearseSampleNormalization(
        actor, id, captureId, proposal.targetUnit(), proposal.allowFractionToPercent());
    if (!proposal.fingerprintVersion().equals(rehearsal.sourceFingerprint().version())
        || !proposal.sourceFingerprint().equals(rehearsal.sourceFingerprint().digest())
        || !proposal.normalizedFingerprint().equals(rehearsal.normalizedFingerprint().digest())) {
      throw conflict("CONTENT_MISMATCH", "The retained mapping proposal no longer matches its capture.");
    }
    String definition = ObjectiveImportFingerprint.canonical(rehearsal.normalizedDefinition());
    if (definition.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16384) {
      throw new IllegalStateException("The normalized synthetic definition exceeds the retained size limit.");
    }
    Map<String, Object> material = new TreeMap<>();
    material.put("receipt_id", proposal.receiptId());
    material.put("capture_id", proposal.captureId());
    material.put("proposal_id", proposal.proposalId());
    material.put("review_id", review.reviewId());
    material.put("proposal_digest_version", proposal.proposalDigestVersion());
    material.put("proposal_digest", proposal.proposalDigest());
    String requestDigest = sha256(serialize(material));
    UUID candidateId = UUID.randomUUID();
    int inserted = jdbc.update("INSERT INTO " + SCHEMA + "synthetic_normalized_candidate "
        + "(candidate_id, tenant_id, environment_id, command_id, capture_id, proposal_id, review_id, "
        + "created_by, idempotency_key, request_digest, proposal_digest_version, proposal_digest, "
        + "fingerprint_version, normalized_fingerprint, normalized_definition, status, verification) "
        + "VALUES (?, ?, ?, ?, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, 'UNCOMPARED', "
        + "'SYNTHETIC_UNVERIFIED') ON CONFLICT DO NOTHING",
        candidateId, actor.tenantId(), actor.environmentId(), command, proposal.captureId(), proposal.proposalId(),
        review.reviewId(), actor.principalId(), key, requestDigest, proposal.proposalDigestVersion(),
        proposal.proposalDigest(), rehearsal.normalizedFingerprint().version(),
        rehearsal.normalizedFingerprint().digest(), definition);
    if (inserted == 0) {
      List<Map<String, Object>> rows = jdbc.queryForList("SELECT candidate_id, request_digest FROM " + SCHEMA
          + "synthetic_normalized_candidate WHERE tenant_id = ? AND environment_id = ? "
          + "AND created_by = ? AND idempotency_key = ?", actor.tenantId(), actor.environmentId(),
          actor.principalId(), key);
      if (rows.isEmpty()) {
        throw conflict("ALREADY_RETAINED", "This historical synthetic review already has a candidate.");
      }
      Map<String, Object> existing = rows.getFirst();
      if (!requestDigest.equals(existing.get("request_digest").toString().trim())) {
        throw conflict("IDEMPOTENCY_CONFLICT", "The idempotency key belongs to a different synthetic candidate.");
      }
      candidateId = (UUID) existing.get("candidate_id");
    }
    return new SyntheticCandidateAdmission(
        getSyntheticCandidate(actor, id, captureId, proposalId, reviewId, candidateId.toString()), inserted == 1);
  }

  @Transactional(readOnly = true)
  SyntheticCandidate getSyntheticCandidate(DelegationPrincipal actor, String id, String captureId,
      String proposalId, String reviewId, String candidateId) {
    requireCapability(actor, "objectives.objective.read");
    requireGlobalScope(actor);
    UUID command;
    UUID capture;
    UUID proposal;
    UUID review;
    UUID candidate;
    try {
      command = UUID.fromString(id);
      capture = UUID.fromString(captureId);
      proposal = UUID.fromString(proposalId);
      review = UUID.fromString(reviewId);
      candidate = UUID.fromString(candidateId);
    } catch (IllegalArgumentException error) {
      throw notFound();
    }
    List<SyntheticCandidate> rows = jdbc.query("SELECT candidate_id, command_id, capture_id, proposal_id, "
        + "review_id, created_by, created_at, proposal_digest_version, proposal_digest, fingerprint_version, "
        + "normalized_fingerprint, normalized_definition, status, verification FROM " + SCHEMA
        + "synthetic_normalized_candidate WHERE tenant_id = ? AND environment_id = ? AND command_id = ? "
        + "AND capture_id = ? AND proposal_id = ? AND review_id = ? AND candidate_id = ?",
        (rs, row) -> {
          ObjectiveImportDefinition definition;
          try {
            definition = ObjectiveImportSampleParser.parse(rs.getString("normalized_definition"));
          } catch (IllegalArgumentException error) {
            throw new IllegalStateException("The retained synthetic candidate is invalid.", error);
          }
          var fingerprint = ObjectiveImportFingerprint.fingerprint(definition);
          if (!fingerprint.version().equals(rs.getString("fingerprint_version"))
              || !fingerprint.digest().equals(rs.getString("normalized_fingerprint").trim())) {
            throw new IllegalStateException("The retained synthetic candidate fingerprint does not match.");
          }
          return new SyntheticCandidate(rs.getString("candidate_id"), rs.getString("command_id"),
              rs.getString("capture_id"), rs.getString("proposal_id"), rs.getString("review_id"),
              rs.getString("created_by"), rs.getTimestamp("created_at").toInstant().toString(),
              rs.getString("proposal_digest_version"), rs.getString("proposal_digest").trim(), definition,
              fingerprint, rs.getString("status"), rs.getString("verification"));
        }, actor.tenantId(), actor.environmentId(), command, capture, proposal, review, candidate);
    if (rows.isEmpty()) throw notFound();
    return rows.getFirst();
  }

  record SyntheticCandidateAdmission(SyntheticCandidate candidate, boolean newlyCreated) {}

  record SyntheticCandidate(String candidateId, String receiptId, String captureId, String proposalId,
      String reviewId, String createdBy, String createdAt, String proposalDigestVersion, String proposalDigest,
      ObjectiveImportDefinition normalizedDefinition, ObjectiveImportFingerprint.Value normalizedFingerprint,
      String status, String verification) {}

  @Transactional(readOnly = true)
  SyntheticCandidates listSyntheticCandidates(DelegationPrincipal actor, String id, String captureId,
      String proposalId, String reviewId) {
    SyntheticMappingReview review = getSyntheticMappingReview(actor, id, captureId, proposalId, reviewId);
    List<UUID> ids = jdbc.query("SELECT candidate_id FROM " + SCHEMA
        + "synthetic_normalized_candidate WHERE tenant_id = ? AND environment_id = ? "
        + "AND command_id = ? AND capture_id = ? AND proposal_id = ? AND review_id = ?",
        (rs, row) -> rs.getObject("candidate_id", UUID.class), actor.tenantId(), actor.environmentId(),
        UUID.fromString(review.receiptId()), UUID.fromString(review.captureId()), UUID.fromString(review.proposalId()),
        UUID.fromString(review.reviewId()));
    return new SyntheticCandidates(ids.stream()
        .map(candidateId -> getSyntheticCandidate(actor, id, captureId, proposalId, reviewId, candidateId.toString()))
        .toList());
  }

  record SyntheticCandidates(List<SyntheticCandidate> items) {}

  @Transactional(readOnly = true)
  SyntheticLocalComparison compareSyntheticCandidate(DelegationPrincipal actor, String id, String captureId,
      String proposalId, String reviewId, String candidateId, String objectiveId, boolean includeLocalSemantics) {
    SyntheticCandidate candidate = getSyntheticCandidate(actor, id, captureId, proposalId, reviewId, candidateId);
    RetainedSampleCapture capture = getRetainedSampleCapture(actor, id, captureId);
    Objective local = find(actor, objectiveId);
    if (!capture.targetWorkloadId().equals(local.workloadId())) throw notFound();

    ObjectiveImportDefinition source = candidate.normalizedDefinition();
    String localComparison = switch (local.comparator()) {
      case "LTE" -> "AT_MOST";
      case "GTE" -> "AT_LEAST";
      default -> throw new IllegalStateException("The local objective comparator is invalid.");
    };
    boolean sameUnit = source.unit().equals(local.unit());
    LocalObjectiveSemantics retained = includeLocalSemantics ? findLocalObjectiveSemantics(actor, local) : null;
    ObjectiveImportDefinition localDefinition = retained == null ? null : retained.normalizedDefinition();
    SyntheticComparisonField window = localDefinition == null
        ? new SyntheticComparisonField("window", "NOT_COMPARABLE", source.window(),
            Integer.toString(local.windowSeconds()))
        : compareWindow(source.window(), localDefinition.window());
    SyntheticComparisonField measurement = localDefinition == null
        ? new SyntheticComparisonField("measurement_ref", "NOT_AVAILABLE", source.measurementRef(), null)
        : comparisonField("measurement_ref", source.measurementRef(), localDefinition.measurementRef());
    SyntheticComparisonField kind = localDefinition == null
        ? new SyntheticComparisonField("measurement_kind", "NOT_AVAILABLE", source.measurementKind().name(), null)
        : comparisonField("measurement_kind", source.measurementKind().name(), localDefinition.measurementKind().name());
    SyntheticComparisonField percentile = localDefinition == null
        ? new SyntheticComparisonField("percentile", "NOT_AVAILABLE",
            source.percentile() == null ? null : source.percentile().toPlainString(), null)
        : comparePercentile(source, localDefinition);
    List<SyntheticComparisonField> fields = List.of(
        comparisonField("metric", source.metric(), local.metric()),
        comparisonField("unit", source.unit(), local.unit()),
        comparisonField("comparison", source.comparison().name(), localComparison),
        new SyntheticComparisonField("target",
            sameUnit ? (source.target().compareTo(local.target()) == 0 ? "MATCH" : "DIFFERENT") : "NOT_COMPARABLE",
            source.target().toPlainString(), local.target().toPlainString()),
        window, measurement, kind, percentile);
    return new SyntheticLocalComparison(candidate.candidateId(), local.id(), local.workloadId(), local.revision(),
        local.contentDigest(), local.state(), "PARTIAL", "SYNTHETIC_UNVERIFIED", fields);
  }

  private static SyntheticComparisonField comparisonField(String field, String source, String local) {
    return new SyntheticComparisonField(field, source.equals(local) ? "MATCH" : "DIFFERENT", source, local);
  }

  private static SyntheticComparisonField compareWindow(String source, String local) {
    String result = "NOT_COMPARABLE";
    try {
      java.time.Duration duration = java.time.Duration.parse(source);
      if (!duration.isNegative() && !duration.isZero()) {
        result = duration.equals(java.time.Duration.parse(local)) ? "MATCH" : "DIFFERENT";
      }
    } catch (java.time.format.DateTimeParseException error) {
      // Non-duration source windows have no established conversion.
    }
    return new SyntheticComparisonField("window", result, source, local);
  }

  private static SyntheticComparisonField comparePercentile(ObjectiveImportDefinition source,
      ObjectiveImportDefinition local) {
    BigDecimal sourceValue = source.percentile();
    BigDecimal localValue = local.percentile();
    String result = source.measurementKind() != local.measurementKind() ? "NOT_COMPARABLE"
        : sourceValue == null ? (localValue == null ? "MATCH" : "DIFFERENT")
        : localValue != null && sourceValue.compareTo(localValue) == 0 ? "MATCH" : "DIFFERENT";
    return new SyntheticComparisonField("percentile", result,
        sourceValue == null ? null : sourceValue.toPlainString(),
        localValue == null ? null : localValue.toPlainString());
  }

  record SyntheticComparisonField(String field, String result, String syntheticValue, String localValue) {}

  record SyntheticLocalComparison(String candidateId, String objectiveId, String workloadId, long localRevision,
      String localContentDigest, String localState, String status, String verification,
      List<SyntheticComparisonField> fields) {}

  @Transactional(readOnly = true)
  Page<SampleCaptureSummary> listSampleCaptures(DelegationPrincipal actor, String id, int pageSize, String cursor) {
    PendingImportReceipt receipt = getImportPreview(actor, id);
    UUID command = UUID.fromString(receipt.receiptId());
    String scope = sha256(serialize(new TreeMap<>(Map.of("tenant_id", actor.tenantId(),
        "environment_id", actor.environmentId(), "receipt_id", command.toString()))));
    String decoded = decodeCursor(cursor);
    UUID after = null;
    if (!decoded.isEmpty()) {
      String prefix = "SC1:" + scope + ":";
      if (!decoded.startsWith(prefix)) {
        throw new ObjectivesException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "cursor does not match this request.");
      }
      String position = decoded.substring(prefix.length());
      try {
        after = UUID.fromString(position);
        if (!after.toString().equals(position)) throw new IllegalArgumentException();
      } catch (IllegalArgumentException error) {
        throw new ObjectivesException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "cursor is malformed.");
      }
    }
    List<SampleCaptureSummary> rows = jdbc.query(
        "SELECT capture_id, captured_at, verification FROM " + SCHEMA
            + "synthetic_objective_source_capture WHERE tenant_id = ? AND environment_id = ? "
            + "AND command_id = ? AND (?::uuid IS NULL OR capture_id > ?::uuid) ORDER BY capture_id LIMIT ?",
        (rs, n) -> new SampleCaptureSummary(rs.getString("capture_id"),
            rs.getTimestamp("captured_at").toInstant().toString(), rs.getString("verification")),
        actor.tenantId(), actor.environmentId(), command, after, after, pageSize + 1);
    boolean more = rows.size() > pageSize;
    List<SampleCaptureSummary> items = more ? rows.subList(0, pageSize) : rows;
    String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString(
        ("SC1:" + scope + ":" + items.getLast().captureId()).getBytes(StandardCharsets.UTF_8)) : null;
    return new Page<>(items, next);
  }

  record SampleCaptureSummary(String captureId, String capturedAt, String verification) {}

  @Transactional
  Objective submit(DelegationPrincipal actor, String id, Transition request) {
    requireCapability(actor, "objectives.objective.write");
    requireGlobalScope(actor);
    return submitObjective(actor, id, request.expectedRevision(), null);
  }

  @Transactional
  Objective submitIdempotent(
      DelegationPrincipal actor, String id, IdempotentSubmission request, String idempotencyKey) {
    requireCapability(actor, "objectives.objective.write");
    requireGlobalScope(actor);
    validateIdempotencyKey(idempotencyKey);
    Map<String, Object> material = new TreeMap<>();
    material.put("id", id);
    material.put("expected_revision", request.expectedRevision());
    material.put("expected_content_digest", request.expectedContentDigest());
    String digest = sha256(serialize(material));
    int inserted = jdbc.update(
        "INSERT INTO " + SCHEMA + "objective_submission "
            + "(tenant_id, environment_id, actor_id, idempotency_key, request_digest, objective_id) "
            + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        actor.tenantId(), actor.environmentId(), actor.principalId(), idempotencyKey, digest, id);
    if (inserted == 0) {
      List<Map<String, Object>> rows = jdbc.queryForList(
          "SELECT objective_id, request_digest FROM " + SCHEMA + "objective_submission "
              + "WHERE tenant_id = ? AND environment_id = ? AND actor_id = ? AND idempotency_key = ? FOR UPDATE",
          actor.tenantId(), actor.environmentId(), actor.principalId(), idempotencyKey);
      if (rows.isEmpty()) throw new IllegalStateException("The objective submission record disappeared.");
      Map<String, Object> row = rows.getFirst();
      if (!digest.equals(row.get("request_digest").toString().trim())) {
        throw conflict("IDEMPOTENCY_CONFLICT", "The idempotency key was already used for a different submission.");
      }
      return find(actor, row.get("objective_id").toString());
    }
    return submitObjective(actor, id, request.expectedRevision(), request.expectedContentDigest());
  }

  private Objective submitObjective(
      DelegationPrincipal actor, String id, long expectedRevision, String expectedContentDigest) {
    Map<String, Object> row = lock(actor, id);
    requireRevision(row, expectedRevision);
    requireState(row, "DRAFT");
    if (expectedContentDigest != null && !expectedContentDigest.equals(row.get("content_digest").toString().trim())) {
      throw conflict("CONTENT_MISMATCH", "The objective content changed since it was read.");
    }
    enforceConstraint(actor, row);
    move(actor, id, row, "SUBMITTED", null, null, "objectives.objective.submitted");
    return find(actor, id);
  }

  @Transactional
  Objective decide(DelegationPrincipal actor, String id, DecideObjective request) {
    requireCapability(actor, "objectives.objective.review");
    requireGlobalScope(actor);
    return decideObjective(actor, id, request.decision(), request.reason(), request.expectedRevision(), null);
  }

  @Transactional
  Objective decideIdempotent(
      DelegationPrincipal actor, String id, IdempotentReview request, String idempotencyKey) {
    requireCapability(actor, "objectives.objective.review");
    requireGlobalScope(actor);
    validateIdempotencyKey(idempotencyKey);
    Map<String, Object> material = new TreeMap<>();
    material.put("id", id);
    material.put("expected_revision", request.expectedRevision());
    material.put("expected_content_digest", request.expectedContentDigest());
    material.put("decision", request.decision().name());
    material.put("reason", request.reason());
    String digest = sha256(serialize(material));
    int inserted = jdbc.update(
        "INSERT INTO " + SCHEMA + "objective_review "
            + "(tenant_id, environment_id, actor_id, idempotency_key, request_digest, objective_id) "
            + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        actor.tenantId(), actor.environmentId(), actor.principalId(), idempotencyKey, digest, id);
    if (inserted == 0) {
      List<Map<String, Object>> rows = jdbc.queryForList(
          "SELECT objective_id, request_digest FROM " + SCHEMA + "objective_review "
              + "WHERE tenant_id = ? AND environment_id = ? AND actor_id = ? AND idempotency_key = ? FOR UPDATE",
          actor.tenantId(), actor.environmentId(), actor.principalId(), idempotencyKey);
      if (rows.isEmpty()) throw new IllegalStateException("The objective review record disappeared.");
      Map<String, Object> row = rows.getFirst();
      if (!digest.equals(row.get("request_digest").toString().trim())) {
        throw conflict("IDEMPOTENCY_CONFLICT", "The idempotency key was already used for a different review.");
      }
      return find(actor, row.get("objective_id").toString());
    }
    return decideObjective(actor, id, request.decision(), request.reason(), request.expectedRevision(),
        request.expectedContentDigest());
  }

  private Objective decideObjective(DelegationPrincipal actor, String id, Decision decision, String reason,
      long expectedRevision, String expectedContentDigest) {
    Map<String, Object> row = lock(actor, id);
    if (actor.principalId().equals(row.get("proposed_by"))) {
      throw conflict("REVIEWER_NOT_INDEPENDENT", "The proposer cannot decide their own objective.");
    }
    requireRevision(row, expectedRevision);
    requireState(row, "SUBMITTED");
    if (expectedContentDigest != null && !expectedContentDigest.equals(row.get("content_digest").toString().trim())) {
      throw conflict("CONTENT_MISMATCH", "The objective content changed since it was read.");
    }
    boolean approve = decision == Decision.APPROVE;
    move(
        actor,
        id,
        row,
        approve ? "APPROVED" : "REJECTED",
        reason,
        null,
        "objectives.objective.decided");
    jdbc.update(
        "INSERT INTO " + SCHEMA + "objective_decision "
            + "(tenant_id, environment_id, objective_id, revision, content_digest, decision, reason, decided_by) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        actor.tenantId(), actor.environmentId(), id, expectedRevision + 1,
        row.get("content_digest").toString().trim(), decision.name(), reason, actor.principalId());
    return find(actor, id);
  }

  @Transactional
  Objective activate(Jwt jwt, String id, Transition request) {
    DelegationPrincipal actor = DelegationPrincipal.from(jwt);
    requireCapability(actor, "objectives.objective.activate");
    requireGlobalScope(actor);
    return activateObjective(jwt, actor, id, request.expectedRevision(), null);
  }

  @Transactional
  Objective activateIdempotent(Jwt jwt, String id, IdempotentSubmission request, String key) {
    DelegationPrincipal actor = DelegationPrincipal.from(jwt);
    requireCapability(actor, "objectives.objective.activate");
    requireGlobalScope(actor);
    validateIdempotencyKey(key);
    Map<String, Object> material = new TreeMap<>();
    material.put("id", id);
    material.put("expected_revision", request.expectedRevision());
    material.put("expected_content_digest", request.expectedContentDigest());
    String digest = sha256(serialize(material));
    int inserted = jdbc.update(
        "INSERT INTO " + SCHEMA + "objective_activation "
            + "(tenant_id, environment_id, actor_id, idempotency_key, request_digest, objective_id) "
            + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        actor.tenantId(), actor.environmentId(), actor.principalId(), key, digest, id);
    if (inserted == 0) {
      List<Map<String, Object>> rows = jdbc.queryForList(
          "SELECT objective_id, request_digest FROM " + SCHEMA + "objective_activation "
              + "WHERE tenant_id = ? AND environment_id = ? AND actor_id = ? AND idempotency_key = ? FOR UPDATE",
          actor.tenantId(), actor.environmentId(), actor.principalId(), key);
      if (rows.isEmpty()) throw new IllegalStateException("The objective activation record disappeared.");
      Map<String, Object> row = rows.getFirst();
      if (!digest.equals(row.get("request_digest").toString().trim())) {
        throw conflict("IDEMPOTENCY_CONFLICT", "The idempotency key was already used for a different activation.");
      }
      return find(actor, row.get("objective_id").toString());
    }
    return activateObjective(jwt, actor, id, request.expectedRevision(), request.expectedContentDigest());
  }

  private Objective activateObjective(Jwt jwt, DelegationPrincipal actor, String id,
      long expectedRevision, String expectedContentDigest) {
    Map<String, Object> row = lock(actor, id);
    requireRevision(row, expectedRevision);
    requireState(row, "APPROVED");
    if (expectedContentDigest != null && !expectedContentDigest.equals(row.get("content_digest").toString().trim())) {
      throw conflict("CONTENT_MISMATCH", "The objective content changed since it was read.");
    }
    enforceConstraint(actor, row);
    reviewerAuthority.check(actor.tenantId(), actor.environmentId(), (String) row.get("decided_by"));
    workloadAuthority.check(
        jwt,
        (String) row.get("workload_id"),
        ObjectiveWorkloadAuthorityClient.ACTIVATION_PURPOSE);
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
  ObjectiveReceipt receipt(Jwt jwt, String workloadId, ReceiptRequest request) {
    DelegationPrincipal actor = DelegationPrincipal.from(jwt);
    requireCapability(actor, "objectives.objective.read");
    if (!"*".equals(actor.serviceScope())
        && !(workloadId.equals(actor.serviceScope())
            && "agent_workload".equals(jwt.getClaimAsString("resource_type"))
            && workloadId.equals(jwt.getClaimAsString("resource_id"))
            && request.purpose().equals(jwt.getClaimAsString("purpose"))
            && List.of("topology.graph.subgraph-selection", "topology.graph.difference-classification")
                .contains(request.purpose()))) {
      throw notFound();
    }
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
    requireGlobalScope(actor);
    return find(actor, id);
  }

  @Transactional(readOnly = true)
  LocalObjectiveSemantics getLocalObjectiveSemantics(DelegationPrincipal actor, String id) {
    requireCapability(actor, "objectives.objective.read");
    requireGlobalScope(actor);
    Objective objective = find(actor, id);
    LocalObjectiveSemantics semantics = findLocalObjectiveSemantics(actor, objective);
    if (semantics == null) throw notFound();
    return semantics;
  }

  private LocalObjectiveSemantics findLocalObjectiveSemantics(DelegationPrincipal actor, Objective objective) {
    List<LocalObjectiveSemantics> rows = jdbc.query("SELECT objective_content_digest, fingerprint_version, "
        + "normalized_fingerprint, normalized_definition FROM " + SCHEMA
        + "local_objective_semantics WHERE tenant_id = ? AND environment_id = ? AND objective_id = ?",
        (rs, row) -> {
          String stored = rs.getString("normalized_definition");
          ObjectiveImportDefinition definition;
          ObjectiveImportFingerprint.Value fingerprint;
          try {
            definition = ObjectiveImportSampleParser.parse(stored);
            fingerprint = ObjectiveImportFingerprint.fingerprint(definition);
          } catch (IllegalArgumentException error) {
            throw new IllegalStateException("The retained local objective semantics are invalid.", error);
          }
          String expected = objectiveContentDigest(objective.workloadId(), objective.metric(), objective.comparator(),
              objective.target(), objective.unit(), objective.windowSeconds(), objective.origin(),
              objective.sourceDefinitionId(), objective.sourceRevision(), fingerprint);
          if (!"LOCAL".equals(objective.origin()) || !ObjectiveImportFingerprint.canonical(definition).equals(stored)
              || !fingerprint.version().equals(rs.getString("fingerprint_version"))
              || !fingerprint.digest().equals(rs.getString("normalized_fingerprint").trim())
              || !objective.contentDigest().equals(rs.getString("objective_content_digest").trim())
              || !objective.contentDigest().equals(expected)
              || !localSemanticsMatch(definition, objective.metric(), objective.comparator(), objective.target(),
                  objective.unit(), objective.windowSeconds())) {
            throw new IllegalStateException("The retained local objective semantics do not match the objective.");
          }
          return new LocalObjectiveSemantics(objective.id(), objective.revision(), objective.contentDigest(),
              definition, fingerprint);
        }, actor.tenantId(), actor.environmentId(), objective.id());
    return rows.isEmpty() ? null : rows.getFirst();
  }

  record LocalObjectiveSemantics(String objectiveId, long objectiveRevision, String objectiveContentDigest,
      ObjectiveImportDefinition normalizedDefinition, ObjectiveImportFingerprint.Value normalizedFingerprint) {}

  @Transactional(readOnly = true)
  DecisionHistory decisionHistory(DelegationPrincipal actor, String id) {
    requireCapability(actor, "objectives.objective.read");
    requireGlobalScope(actor);
    find(actor, id);
    List<DecisionEvent> rows = jdbc.query(
        "SELECT aggregate_sequence, payload->>'state' AS decision_state, "
            + "payload->>'actor_id' AS actor_id, created_at FROM " + SCHEMA + "outbox_event "
            + "WHERE tenant_id = ? AND environment_id = ? AND aggregate_type = 'OBJECTIVE' "
            + "AND aggregate_id = ? AND event_type = 'objectives.objective.decided' "
            + "ORDER BY aggregate_sequence DESC, event_id DESC LIMIT 26",
        (rs, n) -> new DecisionEvent(rs.getLong("aggregate_sequence"), rs.getString("decision_state"),
            rs.getString("actor_id"), rs.getTimestamp("created_at").toInstant().toString()),
        actor.tenantId(), actor.environmentId(), id);
    boolean more = rows.size() > 25;
    return new DecisionHistory(id, more ? rows.subList(0, 25) : rows, more);
  }

  @Transactional(readOnly = true)
  DecisionRecordPage decisionRecords(DelegationPrincipal actor, String id, int pageSize, String cursor) {
    requireCapability(actor, "objectives.objective.read");
    requireGlobalScope(actor);
    find(actor, id);
    String decoded = decodeCursor(cursor);
    long maxRevision = Long.MAX_VALUE;
    if (!decoded.isEmpty()) {
      try {
        maxRevision = Long.parseLong(decoded);
        if (maxRevision < 1) throw new NumberFormatException();
      } catch (NumberFormatException error) {
        throw new ObjectivesException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "cursor is malformed.");
      }
    }
    List<DecisionRecord> rows = jdbc.query(
        "SELECT revision, content_digest, decision, reason, decided_by, decided_at FROM " + SCHEMA
            + "objective_decision WHERE tenant_id = ? AND environment_id = ? AND objective_id = ? "
            + "AND revision <= ? ORDER BY revision DESC LIMIT ?",
        (rs, n) -> new DecisionRecord(rs.getLong("revision"), rs.getString("content_digest").trim(),
            rs.getString("decision"), rs.getString("reason"), rs.getString("decided_by"),
            rs.getTimestamp("decided_at").toInstant().toString()),
        actor.tenantId(), actor.environmentId(), id, maxRevision, pageSize + 1);
    boolean more = rows.size() > pageSize;
    List<DecisionRecord> items = more ? rows.subList(0, pageSize) : rows;
    String next = more
        ? Base64.getUrlEncoder().withoutPadding().encodeToString(
            Long.toString(items.getLast().revision() - 1).getBytes(StandardCharsets.UTF_8))
        : null;
    return new DecisionRecordPage(id, items, next, true);
  }

  @Transactional(readOnly = true)
  Page<Objective> list(DelegationPrincipal actor, String workloadId, int pageSize, String cursor) {
    requireCapability(actor, "objectives.objective.read");
    requireGlobalScope(actor);
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
            "SELECT workload_id, metric, unit, target, content_digest, state, revision, proposed_by, decided_by FROM "
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
    appendOutbox(actor, "OBJECTIVE", aggregateId, sequence, eventType, body);
  }

  private void appendOutbox(
      DelegationPrincipal actor,
      String aggregateType,
      String aggregateId,
      long sequence,
      String eventType,
      Map<String, Object> body) {
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
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
        UUID.randomUUID(),
        actor.tenantId(),
        actor.environmentId(),
        aggregateType,
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

  private static UUID decodeUuidCursor(String cursor) {
    String decoded = decodeCursor(cursor);
    if (decoded.isEmpty()) return null;
    try {
      UUID value = UUID.fromString(decoded);
      if (!value.toString().equals(decoded)) throw new IllegalArgumentException();
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

  private static void validateIdempotencyKey(String key) {
    if (key == null
        || key.isBlank()
        || key.length() > 200
        || key.chars().anyMatch(Character::isISOControl)) {
      throw new ObjectivesException(
          HttpStatus.BAD_REQUEST,
          "INVALID_IDEMPOTENCY_KEY",
          "Idempotency-Key must be non-empty and at most 200 characters.");
    }
  }

  private String serialize(Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException error) {
      throw new IllegalStateException("Could not serialize the import preview request.", error);
    }
  }

  private static ImportPreviewAdmission importPreviewAdmission(String id, boolean newlyAccepted) {
    return new ImportPreviewAdmission(
        new CommandReceipt(
            id, "PENDING", "/v1/objective-imports/" + id, "NOT_ESTABLISHED"),
        newlyAccepted);
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
