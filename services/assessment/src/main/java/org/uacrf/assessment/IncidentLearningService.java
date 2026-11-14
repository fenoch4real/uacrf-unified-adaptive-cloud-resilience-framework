package org.uacrf.assessment;

import static org.uacrf.assessment.AssessmentRequests.*;
import static org.uacrf.assessment.AssessmentResponses.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uacrf.security.DelegationPrincipal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class IncidentLearningService {
  private static final String DB = "uacrf_assessment.";
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  IncidentLearningService(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Transactional
  Incident createIncident(DelegationPrincipal actor, CreateIncident request) {
    permit(actor, "assessment.incident.write", "assessment.learning.write");
    CaseRef sourceCase = findCase(actor, request.sourceCaseId());
    if (!sourceCase.workloadId().equals(request.workloadId())) {
      throw notFound();
    }
    if (!"SEALED".equals(sourceCase.state())) {
      throw conflict("INVALID_STATE", "An incident requires a sealed source case.");
    }

    Set<String> qualifiedReceiptIds = new HashSet<>();
    boolean verifiedEvaluation = false;
    List<EvaluationEvidence> evaluations = jdbc.query(
        "SELECT observation_cutoff,cutoff_epoch_second,cutoff_nano,dependency_vector::text "
            + "FROM " + DB + "evaluation WHERE tenant_id=? AND environment_id=? AND case_id=? "
            + "AND (cutoff_epoch_second < ? OR (cutoff_epoch_second = ? AND cutoff_nano <= ?))",
        (rs, row) -> evaluationEvidence(rs), actor.tenantId(), actor.environmentId(),
        request.sourceCaseId(), request.observedFrom().getEpochSecond(),
        request.observedFrom().getEpochSecond(), request.observedFrom().getNano());
    for (EvaluationEvidence evaluation : evaluations) {
      if (!hasVerifiedOwnerReceipts(evaluation.vector())) continue;
      Set<String> successfulReceipts = successfulMeasurementReceipts(
          evaluation.vector(), evaluation.observationCutoff(), request.observedFrom());
      if (!successfulReceipts.isEmpty()) {
        verifiedEvaluation = true;
        qualifiedReceiptIds.addAll(successfulReceipts);
      }
    }
    if (!verifiedEvaluation) {
      throw conflict("VERIFIED_EVALUATION_REQUIRED",
          "An incident requires a VERIFIED evaluation with qualified S04, S05, and S06 receipts at or before observed_from.");
    }

    List<String> evidenceRefs = canonicalRefs(request.evidenceRefs());
    if (!qualifiedReceiptIds.containsAll(evidenceRefs)) {
      throw conflict("EVIDENCE_NOT_IN_VERIFIED_EVALUATION",
          "Incident evidence_refs must be measurement receipts from a qualifying verified evaluation.");
    }
    String id = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO " + DB
            + "incident (tenant_id,environment_id,id,workload_id,source_case_id,observed_from,"
            + "description,evidence_refs,reason,revision,created_by) "
            + "VALUES (?,?,?,?,?,?,?,?::jsonb,?,1,?)",
        actor.tenantId(), actor.environmentId(), id, request.workloadId(), request.sourceCaseId(),
        Timestamp.from(request.observedFrom()), request.description(), write(evidenceRefs), request.reason(),
        actor.principalId());
    return findIncident(actor, id, false);
  }

  @Transactional(readOnly = true)
  Incident getIncident(DelegationPrincipal actor, String id) {
    permit(actor, "assessment.incident.read");
    return findIncident(actor, id, false);
  }

  @Transactional
  LearningRecord proposeLearningRecord(DelegationPrincipal actor, ProposeLearningRecord request) {
    permit(actor, "assessment.learning.write", "assessment.incident.write");
    Incident incident = findIncident(actor, request.incidentId(), true);
    if (incident.revision() != request.expectedRevision()) {
      throw conflict("REVISION_MISMATCH", "The incident changed since it was read.");
    }
    List<String> evidenceRefs = canonicalRefs(request.evidenceRefs());
    if (!new HashSet<>(incident.evidenceRefs()).containsAll(evidenceRefs)) {
      throw conflict("EVIDENCE_NOT_IN_INCIDENT",
          "Learning evidence_refs must be retained on the linked incident.");
    }
    ProposeLearningRecord content = new ProposeLearningRecord(
        incident.id(), request.experimentId(), List.copyOf(request.hypotheses()),
        List.copyOf(request.actionItems()), evidenceRefs, request.expectedRevision(), request.reason());
    String id = UUID.randomUUID().toString();
    String serialized = write(content);
    String digest = hash(serialized);
    jdbc.update("INSERT INTO " + DB
            + "learning_record (tenant_id,environment_id,id,incident_id,content,evidence_refs,"
            + "content_digest,state,revision,proposed_by) "
            + "VALUES (?,?,?,?,?::jsonb,?::jsonb,?,'PROPOSED',1,?)",
        actor.tenantId(), actor.environmentId(), id, incident.id(), serialized,
        write(evidenceRefs), digest, actor.principalId());
    event(actor, id, digest, content);
    return findLearningRecord(actor, id);
  }

  private CaseRef findCase(DelegationPrincipal actor, String id) {
    List<CaseRef> rows = jdbc.query("SELECT workload_id,state FROM " + DB
            + "analysis_case WHERE tenant_id=? AND environment_id=? AND id=?",
        (rs, row) -> new CaseRef(rs.getString("workload_id"), rs.getString("state")),
        actor.tenantId(), actor.environmentId(), id);
    if (rows.isEmpty()) throw notFound();
    return rows.getFirst();
  }

  private Incident findIncident(DelegationPrincipal actor, String id, boolean lock) {
    List<Incident> rows = jdbc.query("SELECT * FROM " + DB
            + "incident WHERE tenant_id=? AND environment_id=? AND id=?"
            + (lock ? " FOR UPDATE" : ""),
        (rs, row) -> incidentRow(rs), actor.tenantId(), actor.environmentId(), id);
    if (rows.isEmpty()) throw notFound();
    return rows.getFirst();
  }

  private Incident findIncident(DelegationPrincipal actor, String id) {
    return findIncident(actor, id, false);
  }

  private LearningRecord findLearningRecord(DelegationPrincipal actor, String id) {
    return jdbc.queryForObject("SELECT * FROM " + DB
            + "learning_record WHERE tenant_id=? AND environment_id=? AND id=?",
        (rs, row) -> learningRecordRow(rs), actor.tenantId(), actor.environmentId(), id);
  }

  private Incident incidentRow(ResultSet rs) throws SQLException {
    return new Incident(rs.getString("id"), rs.getString("workload_id"),
        rs.getString("source_case_id"), rs.getTimestamp("observed_from").toInstant(),
        rs.getString("description"), readStringList(rs.getString("evidence_refs")),
        rs.getString("reason"), rs.getLong("revision"), rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant());
  }

  private LearningRecord learningRecordRow(ResultSet rs) throws SQLException {
    ProposeLearningRecord content = read(rs.getString("content"), ProposeLearningRecord.class);
    return new LearningRecord(rs.getString("id"), rs.getString("incident_id"),
        content.experimentId(), content.hypotheses(), content.actionItems(),
        readStringList(rs.getString("evidence_refs")), content.expectedRevision(), content.reason(),
        rs.getString("state"), rs.getLong("revision"), rs.getString("proposed_by"),
        rs.getTimestamp("created_at").toInstant());
  }

  private EvaluationEvidence evaluationEvidence(ResultSet rs) throws SQLException {
    return new EvaluationEvidence(Instant.parse(rs.getString("observation_cutoff")),
        read(rs.getString("dependency_vector"), DependencyVector.class));
  }

  private boolean hasVerifiedOwnerReceipts(DependencyVector vector) {
    return vector != null && "VERIFIED".equals(vector.receiptVerification())
        && validObjectiveReceipt(vector.objectiveReceipt())
        && validGraphReceipt(vector.graphReceipt());
  }

  private Set<String> successfulMeasurementReceipts(
      DependencyVector vector, Instant cutoff, Instant observedFrom) {
    if (vector.measurementReceipts() == null) return Set.of();
    Set<String> receipts = new HashSet<>();
    for (MeasurementReceipt receipt : vector.measurementReceipts()) {
      if (receipt.usableAsEvidence() && "QUALIFIED".equals(receipt.qualificationState())
          && nonBlank(receipt.evidenceId()) && receipt.payloadDigest() != null
          && receipt.payloadDigest().matches("[0-9a-f]{64}") && receipt.revision() >= 1
          && receipt.observedAt() != null && !receipt.observedAt().isAfter(cutoff)
          && !receipt.observedAt().isAfter(observedFrom)) {
        receipts.add(receipt.evidenceId());
      }
    }
    return receipts;
  }

  private static boolean validObjectiveReceipt(ObjectiveReceipt receipt) {
    return receipt != null && nonBlank(receipt.workloadId()) && nonBlank(receipt.setDigest())
        && nonBlank(receipt.objectiveId()) && nonBlank(receipt.digest()) && receipt.revision() >= 1;
  }

  private static boolean validGraphReceipt(GraphReceipt receipt) {
    return receipt != null && nonBlank(receipt.graphId()) && nonBlank(receipt.digest())
        && receipt.revision() >= 1;
  }

  private List<String> readStringList(String value) {
    JsonNode node = json.readTree(value);
    List<String> values = new ArrayList<>();
    node.forEach(item -> values.add(item.asString()));
    return List.copyOf(values);
  }

  private List<String> canonicalRefs(List<String> refs) {
    return List.copyOf(new TreeSet<>(refs));
  }

  private void event(DelegationPrincipal actor, String id, String digest,
      ProposeLearningRecord content) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("incident_id", content.incidentId());
    metadata.put("state", "PROPOSED");
    metadata.put("expected_revision", content.expectedRevision());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("owned_resource_id", id);
    payload.put("owned_revision", 1);
    payload.put("owned_digest", digest);
    payload.put("minimal_outcome_metadata", metadata);
    payload.put("actor_id", actor.principalId());
    String serialized = write(payload);
    jdbc.update("INSERT INTO " + DB
            + "outbox_event (event_id,tenant_id,environment_id,aggregate_type,aggregate_id,"
            + "aggregate_sequence,event_type,payload,payload_digest) VALUES (?,?,?,?,?,?,?,?::jsonb,?)",
        UUID.randomUUID(), actor.tenantId(), actor.environmentId(), "LEARNING_RECORD", id, 1,
        "uacrf.s07.learning.proposed.v1", serialized, hash(serialized));
  }

  private String write(Object value) { return json.writeValueAsString(value); }
  private <T> T read(String value, Class<T> type) { return json.readValue(value, type); }
  private static String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }

  private static void permit(DelegationPrincipal actor, String capability, String alternate) {
    if ((!actor.capabilities().contains(capability)
            && (alternate == null || !actor.capabilities().contains(alternate)))
        || !"*".equals(actor.serviceScope())) {
      throw denied();
    }
  }

  private static void permit(DelegationPrincipal actor, String capability) {
    permit(actor, capability, null);
  }

  private static AssessmentException denied() {
    return new AssessmentException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED",
        "The current principal cannot perform this incident or learning operation.");
  }
  private static AssessmentException conflict(String code, String message) {
    return new AssessmentException(HttpStatus.CONFLICT, code, message);
  }
  private static AssessmentException notFound() {
    return new AssessmentException(HttpStatus.NOT_FOUND, "NOT_FOUND",
        "The assessment record does not exist.");
  }

  private record CaseRef(String workloadId, String state) {}
  private record EvaluationEvidence(Instant observationCutoff, DependencyVector vector) {}
}
