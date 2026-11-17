package org.uacrf.assessment;

import static org.uacrf.assessment.AssessmentRequests.*;
import static org.uacrf.assessment.AssessmentResponses.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uacrf.security.DelegationPrincipal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class ModelVersionService {
  private static final String DB = "uacrf_assessment.";
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final AssessmentService assessment;
  private final boolean enabled;

  ModelVersionService(JdbcTemplate jdbc, ObjectMapper json, AssessmentService assessment,
      @Value("${uacrf.assessment.models.enabled:false}") boolean enabled) {
    this.jdbc = jdbc;
    this.json = json;
    this.assessment = assessment;
    this.enabled = enabled;
  }

  @Transactional
  ModelVersion retain(DelegationPrincipal actor, RetainModelVersion request) {
    permit(actor, "assessment.model.write", true);
    requireEnabled();
    String id = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO " + DB + "model_version "
        + "(tenant_id,environment_id,id,model_id,version,artifact_ref,digest,model_card_ref,evaluation_pack_ref,"
        + "reason,state,revision,proposed_by) VALUES (?,?,?,?,?,?,?,?,?,?,'CANDIDATE',1,?)",
        actor.tenantId(), actor.environmentId(), id, request.modelId(), request.version(),
        request.artifactRef(), request.digest(), request.modelCardRef(), request.evaluationPackRef(),
        request.reason(), actor.principalId());
    event(actor, id, 1, "uacrf.s07.model-version.retained.v1", Map.of(
        "model_id", request.modelId(), "version", request.version(), "state", "CANDIDATE"));
    return find(actor, id, false);
  }

  @Transactional(readOnly = true)
  ModelVersion get(DelegationPrincipal actor, String id) {
    permit(actor, "assessment.model.read", false);
    return find(actor, id, false);
  }

  @Transactional(readOnly = true)
  List<ModelVersion> list(DelegationPrincipal actor, String modelId) {
    permit(actor, "assessment.model.read", false);
    return jdbc.query("SELECT * FROM " + DB + "model_version WHERE tenant_id=? AND environment_id=? "
            + "AND (CAST(? AS text) IS NULL OR model_id=?) ORDER BY model_id,version,id",
        (rs, row) -> modelVersionRow(rs), actor.tenantId(), actor.environmentId(), modelId, modelId);
  }

  @Transactional
  ModelEvaluationDeclaration declareEvaluation(DelegationPrincipal actor, DeclareModelEvaluation request) {
    permit(actor, "assessment.model.write", true);
    requireEnabled();
    if (!request.trainingThrough().isBefore(request.holdoutFrom())
        || request.holdoutFrom().isAfter(request.holdoutThrough())) {
      throw invalid("Declared training must end before the holdout begins, and the holdout must not end earlier.");
    }
    ModelVersion model = find(actor, request.modelVersionId(), false);
    String id = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO " + DB + "model_evaluation_declaration "
            + "(tenant_id,environment_id,id,model_version_id,model_digest,training_data_digest,"
            + "holdout_data_digest,result_ref,result_digest,training_through,holdout_from,holdout_through,"
            + "reason,status,declared_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'DECLARED_UNVERIFIED',?)",
        actor.tenantId(), actor.environmentId(), id, model.id(), model.digest(),
        request.trainingDataDigest(), request.holdoutDataDigest(), request.resultRef(), request.resultDigest(),
        request.trainingThrough().toString(), request.holdoutFrom().toString(),
        request.holdoutThrough().toString(), request.reason(), actor.principalId());
    event(actor, "MODEL_EVALUATION_DECLARATION", id, 1, "uacrf.s07.model-evaluation.declared.v1",
        Map.of("model_version_id", model.id(), "status", "DECLARED_UNVERIFIED"));
    return findEvaluationDeclaration(actor, id);
  }

  @Transactional(readOnly = true)
  ModelEvaluationDeclaration getEvaluationDeclaration(DelegationPrincipal actor, String id) {
    permit(actor, "assessment.model.read", false);
    return findEvaluationDeclaration(actor, id);
  }

  @Transactional(readOnly = true)
  ModelEvaluationDeclarationPage listEvaluationDeclarations(
      DelegationPrincipal actor, String modelVersionId, int limit, String after) {
    permit(actor, "assessment.model.read", false);
    if (modelVersionId.isBlank() || modelVersionId.length() > 200
        || !AssessmentPagination.valid(limit, after)) {
      throw invalid("A model version, a limit between 1 and 100 and a valid cursor are required.");
    }
    find(actor, modelVersionId, false);
    List<ModelEvaluationDeclaration> rows = jdbc.query("SELECT * FROM " + DB
            + "model_evaluation_declaration WHERE tenant_id=? AND environment_id=? "
            + "AND model_version_id=? AND id>? ORDER BY id LIMIT ?",
        (rs, row) -> modelEvaluationDeclarationRow(rs), actor.tenantId(), actor.environmentId(),
        modelVersionId, after == null ? "" : after, limit + 1);
    boolean more = rows.size() > limit;
    List<ModelEvaluationDeclaration> items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
    return new ModelEvaluationDeclarationPage(items, more ? items.getLast().id() : null);
  }

  private ModelEvaluationDeclaration findEvaluationDeclaration(DelegationPrincipal actor, String id) {
    List<ModelEvaluationDeclaration> rows = jdbc.query("SELECT * FROM " + DB
            + "model_evaluation_declaration WHERE tenant_id=? AND environment_id=? AND id=?",
        (rs, row) -> modelEvaluationDeclarationRow(rs),
        actor.tenantId(), actor.environmentId(), id);
    if (rows.isEmpty()) throw notFound();
    return rows.getFirst();
  }

  private ModelEvaluationDeclaration modelEvaluationDeclarationRow(ResultSet rs) throws SQLException {
    return new ModelEvaluationDeclaration(rs.getString("id"), rs.getString("model_version_id"),
        rs.getString("model_digest"), rs.getString("training_data_digest"), rs.getString("holdout_data_digest"),
        rs.getString("result_ref"), rs.getString("result_digest"), Instant.parse(rs.getString("training_through")),
        Instant.parse(rs.getString("holdout_from")), Instant.parse(rs.getString("holdout_through")),
        rs.getString("reason"), rs.getString("status"), rs.getString("declared_by"), instant(rs, "created_at"));
  }

  @Transactional
  ModelVersion review(DelegationPrincipal actor, String id, ReviewModelVersion request) {
    permit(actor, "assessment.model.review", true);
    requireEnabled();
    ModelVersion item = find(actor, id, true);
    checkRevision(item, request.expectedRevision());
    if (!"CANDIDATE".equals(item.state())) {
      throw conflict("INVALID_STATE", "Only a candidate model version can be reviewed.");
    }
    if (actor.principalId().equals(item.proposedBy())) {
      throw conflict("INDEPENDENT_REVIEW_REQUIRED", "A model version proposer cannot review it.");
    }
    List<String> evaluationIds = request.evaluationResultIds() == null
        ? List.of() : List.copyOf(request.evaluationResultIds());
    if (new HashSet<>(evaluationIds).size() != evaluationIds.size()) {
      throw invalid("Evaluation result IDs must be unique.");
    }
    List<String> outcomes = new ArrayList<>();
    for (String evaluationId : evaluationIds) {
      outcomes.add(verifiedEvaluationOutcome(actor, evaluationId));
      assessment.revalidateEvaluationForModel(actor, evaluationId);
    }
    if (request.decision() == ModelDecision.APPROVE
        && outcomes.stream().noneMatch("CONFIRMED_SUCCESS"::equals)) {
      throw conflict("PASSING_EVALUATION_REQUIRED",
          "Approval requires at least one passing verified evaluation result.");
    }
    String newState = request.decision() == ModelDecision.APPROVE ? "APPROVED" : "REJECTED";
    jdbc.update("UPDATE " + DB + "model_version SET state=?,revision=revision+1,reviewed_by=?,decision=?,"
            + "evaluation_result_ids=?::jsonb,review_reason=?,reviewed_at=clock_timestamp() "
            + "WHERE tenant_id=? AND environment_id=? AND id=?",
        newState, actor.principalId(), request.decision().name(), write(evaluationIds), request.reason(),
        actor.tenantId(), actor.environmentId(), id);
    event(actor, id, item.revision() + 1, "uacrf.s07.model-version.review-decided.v1",
        Map.of("decision", request.decision().name(), "state", newState));
    return find(actor, id, false);
  }

  @Transactional
  ModelVersion promote(DelegationPrincipal actor, String id, PromoteModelVersion request) {
    permit(actor, "assessment.model.write", true);
    requireEnabled();
    ModelVersion item = find(actor, id, true);
    checkRevision(item, request.expectedRevision());
    if (!"APPROVED".equals(item.state())) {
      throw conflict("INVALID_STATE", "Only an approved model version can be promoted.");
    }
    if (actor.principalId().equals(item.proposedBy()) || actor.principalId().equals(item.reviewedBy())) {
      throw conflict("INDEPENDENT_PROMOTION_REQUIRED",
          "The proposer and reviewer cannot promote this model version.");
    }
    List<String> outcomes = new ArrayList<>();
    for (String evaluationId : item.evaluationResultIds()) {
      outcomes.add(verifiedEvaluationOutcome(actor, evaluationId));
      assessment.revalidateEvaluationForModel(actor, evaluationId);
    }
    if (outcomes.stream().noneMatch("CONFIRMED_SUCCESS"::equals)) {
      throw conflict("PASSING_EVALUATION_REQUIRED", "Promotion requires a passing verified evaluation result.");
    }
    requireModelSpecificEvaluation();
    String lockKey = write(List.of(actor.tenantId(), actor.environmentId(), item.modelId(),
        request.targetMode().name()));
    jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, lockKey);
    List<ModelVersion> active = jdbc.query("SELECT * FROM " + DB
            + "model_version WHERE tenant_id=? AND environment_id=? AND model_id=? AND target_mode=? "
            + "AND state='ACTIVE' FOR UPDATE",
        (rs, row) -> modelVersionRow(rs), actor.tenantId(), actor.environmentId(),
        item.modelId(), request.targetMode().name());
    for (ModelVersion old : active) {
      jdbc.update("UPDATE " + DB + "model_version SET state='SUPERSEDED',revision=revision+1 "
              + "WHERE tenant_id=? AND environment_id=? AND id=?",
          actor.tenantId(), actor.environmentId(), old.id());
      event(actor, old.id(), old.revision() + 1, "uacrf.s07.model-version.superseded.v1",
          Map.of("model_id", old.modelId(), "target_mode", request.targetMode().name(),
              "superseded_by", item.id()));
    }
    jdbc.update("UPDATE " + DB + "model_version SET state='ACTIVE',revision=revision+1,target_mode=?,"
            + "promoted_by=?,promotion_reason=?,promoted_at=clock_timestamp() "
            + "WHERE tenant_id=? AND environment_id=? AND id=?",
        request.targetMode().name(), actor.principalId(), request.reason(),
        actor.tenantId(), actor.environmentId(), id);
    event(actor, id, item.revision() + 1, "uacrf.s07.model-version.promoted.v1",
        Map.of("model_id", item.modelId(), "target_mode", request.targetMode().name()));
    return find(actor, id, false);
  }

  private void requireModelSpecificEvaluation() {
    throw conflict("MODEL_EVALUATION_REQUIRED",
        "Model promotion is unavailable until model-specific evaluation and acceptance are implemented.");
  }

  private String verifiedEvaluationOutcome(DelegationPrincipal actor, String id) {
    List<String> rows = jdbc.query("SELECT outcome,dependency_vector::text FROM " + DB
            + "evaluation WHERE tenant_id=? AND environment_id=? AND id=?",
        (rs, row) -> {
          JsonNode vector = json.readTree(rs.getString("dependency_vector"));
          if (!"VERIFIED".equals(text(vector, "receipt_verification"))
              || !vector.hasNonNull("objective_receipt")
              || !vector.hasNonNull("graph_receipt")
              || !verifiedMeasurements(vector.get("measurement_receipts"))) {
            throw conflict("UNVERIFIED_EVALUATION",
                "Each evaluation result must contain verified S04, S05, and S06 receipts.");
          }
          return rs.getString("outcome");
        }, actor.tenantId(), actor.environmentId(), id);
    if (rows.isEmpty()) throw notFound();
    String outcome = rows.getFirst();
    if ("INSUFFICIENT_EVIDENCE".equals(outcome)) {
      throw conflict("INSUFFICIENT_EVIDENCE", "An evaluation with insufficient evidence cannot be reviewed.");
    }
    return outcome;
  }

  private boolean verifiedMeasurements(JsonNode receipts) {
    if (receipts == null || !receipts.isArray() || receipts.isEmpty()) return false;
    for (JsonNode receipt : receipts) {
      if (!receipt.path("usable_as_evidence").asBoolean(false)
          || !receipt.hasNonNull("evidence_id")
          || !receipt.hasNonNull("payload_digest")) return false;
    }
    return true;
  }

  private static String text(JsonNode node, String key) {
    JsonNode value = node.get(key);
    return value == null || value.isNull() ? null : value.asString();
  }

  private ModelVersion find(DelegationPrincipal actor, String id, boolean lock) {
    List<ModelVersion> rows = jdbc.query("SELECT * FROM " + DB
            + "model_version WHERE tenant_id=? AND environment_id=? AND id=?"
            + (lock ? " FOR UPDATE" : ""),
        (rs, row) -> modelVersionRow(rs), actor.tenantId(), actor.environmentId(), id);
    if (rows.isEmpty()) throw notFound();
    return rows.getFirst();
  }

  private ModelVersion modelVersionRow(ResultSet rs) throws SQLException {
    return new ModelVersion(rs.getString("id"), rs.getString("model_id"), rs.getString("version"),
        rs.getString("artifact_ref"), rs.getString("digest"), rs.getString("model_card_ref"),
        rs.getString("evaluation_pack_ref"), rs.getString("reason"), rs.getString("state"),
        rs.getLong("revision"), rs.getString("proposed_by"), instant(rs, "created_at"),
        rs.getString("reviewed_by"), rs.getString("decision"),
        readStringList(rs.getString("evaluation_result_ids")), rs.getString("review_reason"),
        instantOrNull(rs, "reviewed_at"), rs.getString("promoted_by"), rs.getString("target_mode"),
        rs.getString("promotion_reason"), instantOrNull(rs, "promoted_at"));
  }

  private List<String> readStringList(String value) {
    JsonNode node = json.readTree(value);
    List<String> result = new ArrayList<>();
    node.forEach(item -> result.add(item.asString()));
    return List.copyOf(result);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getTimestamp(column).toInstant();
  }

  private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
    var value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private void event(DelegationPrincipal actor, String id, long revision, String eventType,
      Map<String, Object> metadata) {
    event(actor, "MODEL_VERSION", id, revision, eventType, metadata);
  }

  private void event(DelegationPrincipal actor, String aggregateType, String id, long revision, String eventType,
      Map<String, Object> metadata) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("owned_resource_id", id);
    payload.put("owned_revision", revision);
    payload.put("minimal_outcome_metadata", metadata);
    payload.put("actor_id", actor.principalId());
    String serialized = write(payload);
    jdbc.update("INSERT INTO " + DB + "outbox_event "
            + "(event_id,tenant_id,environment_id,aggregate_type,aggregate_id,aggregate_sequence,"
            + "event_type,payload,payload_digest) VALUES (?,?,?,?,?,?,?,?::jsonb,?)",
        UUID.randomUUID(), actor.tenantId(), actor.environmentId(), aggregateType, id, revision,
        eventType, serialized, hash(serialized));
  }

  private String write(Object value) { return json.writeValueAsString(value); }
  private static String hash(String value) {
    try {
      return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void requireEnabled() {
    if (!enabled) throw conflict("FEATURE_DISABLED", "Model-version governance is disabled.");
  }

  private static void checkRevision(ModelVersion item, long expected) {
    if (item.revision() != expected) {
      throw conflict("REVISION_MISMATCH", "The model version changed since it was read.");
    }
  }

  private static void permit(DelegationPrincipal actor, String capability, boolean write) {
    if (!actor.capabilities().contains(capability) || (write && !"*".equals(actor.serviceScope()))) {
      throw new AssessmentException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED",
          "The current principal cannot perform this model-version operation.");
    }
  }

  private static AssessmentException invalid(String message) {
    return new AssessmentException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
  }
  private static AssessmentException conflict(String code, String message) {
    return new AssessmentException(HttpStatus.CONFLICT, code, message);
  }
  private static AssessmentException notFound() {
    return new AssessmentException(HttpStatus.NOT_FOUND, "NOT_FOUND",
        "The assessment record does not exist.");
  }
}
