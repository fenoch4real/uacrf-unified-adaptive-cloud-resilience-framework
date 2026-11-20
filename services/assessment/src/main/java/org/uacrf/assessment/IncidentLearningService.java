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
import org.springframework.beans.factory.annotation.Value;
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
  private final AssessmentService assessment;
  private final boolean reviewEnabled;
  private final boolean adaptationEnabled;

  IncidentLearningService(JdbcTemplate jdbc, ObjectMapper json, AssessmentService assessment,
      @Value("${uacrf.assessment.learning-review.enabled:false}") boolean reviewEnabled,
      @Value("${uacrf.assessment.adaptation.enabled:false}") boolean adaptationEnabled) {
    this.jdbc = jdbc;
    this.json = json;
    this.assessment = assessment;
    this.reviewEnabled = reviewEnabled;
    this.adaptationEnabled = adaptationEnabled;
  }

  @Transactional
  Incident createIncident(DelegationPrincipal actor, CreateIncident request, String key) {
    permit(actor, "assessment.incident.write", "assessment.learning.write");
    List<String> evidenceRefs = canonicalRefs(request.evidenceRefs());
    if (key != null) {
      if (key.isBlank() || key.length() > 200 || key.chars().anyMatch(Character::isWhitespace)) {
        throw new AssessmentException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
            "Idempotency-Key must be a non-empty token of at most 200 characters.");
      }
      String digest = hash(write(new CreateIncident(request.workloadId(), request.sourceCaseId(),
          request.observedFrom(), request.description(), evidenceRefs, request.reason())));
      int inserted = jdbc.update("INSERT INTO " + DB + "incident_idempotency "
              + "(tenant_id,environment_id,actor_id,idempotency_key,payload_digest) VALUES (?,?,?,?,?) "
              + "ON CONFLICT DO NOTHING",
          actor.tenantId(), actor.environmentId(), actor.principalId(), key, digest);
      if (inserted == 0) {
        List<IncidentRequest> prior = jdbc.query("SELECT payload_digest,incident_id FROM " + DB
                + "incident_idempotency WHERE tenant_id=? AND environment_id=? AND actor_id=? "
                + "AND idempotency_key=? FOR UPDATE",
            (rs, row) -> new IncidentRequest(rs.getString("payload_digest"), rs.getString("incident_id")),
            actor.tenantId(), actor.environmentId(), actor.principalId(), key);
        if (prior.isEmpty() || !digest.equals(prior.getFirst().digest())) {
          throw conflict("IDEMPOTENCY_CONFLICT", "The idempotency key was already used for different content.");
        }
        if (prior.getFirst().incidentId() == null) {
          throw conflict("COMMAND_IN_PROGRESS", "The original request has not completed.");
        }
        return findIncident(actor, prior.getFirst().incidentId(), false);
      }
    }
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
        "SELECT id,observation_cutoff,cutoff_epoch_second,cutoff_nano,dependency_vector::text "
            + "FROM " + DB + "evaluation WHERE tenant_id=? AND environment_id=? AND case_id=? "
            + "AND (cutoff_epoch_second < ? OR (cutoff_epoch_second = ? AND cutoff_nano <= ?)) "
            + "ORDER BY cutoff_epoch_second DESC,cutoff_nano DESC,revision DESC,id",
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

    if (!qualifiedReceiptIds.containsAll(evidenceRefs)) {
      throw conflict("EVIDENCE_NOT_IN_VERIFIED_EVALUATION",
          "Incident evidence_refs must be measurement receipts from a qualifying verified evaluation.");
    }
    Set<String> remaining = new HashSet<>(evidenceRefs);
    List<String> sourceEvaluationIds = new ArrayList<>();
    for (EvaluationEvidence evaluation : evaluations) {
      if (!hasVerifiedOwnerReceipts(evaluation.vector())) continue;
      Set<String> eligible = successfulMeasurementReceipts(
          evaluation.vector(), evaluation.observationCutoff(), request.observedFrom());
      if (Collections.disjoint(remaining, eligible)) continue;
      assessment.revalidateEvaluation(actor, evaluation.id());
      sourceEvaluationIds.add(evaluation.id());
      remaining.removeAll(eligible);
      if (remaining.isEmpty()) break;
    }
    if (!remaining.isEmpty()) {
      throw conflict("EVIDENCE_NOT_IN_VERIFIED_EVALUATION",
          "Incident evidence_refs must be measurement receipts from a qualifying verified evaluation.");
    }
    String id = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO " + DB
            + "incident (tenant_id,environment_id,id,workload_id,source_case_id,observed_from,"
            + "observed_from_epoch_second,observed_from_nano,description,evidence_refs,"
            + "source_evaluation_ids,reason,revision,created_by) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,1,?)",
        actor.tenantId(), actor.environmentId(), id, request.workloadId(), request.sourceCaseId(),
        Timestamp.from(request.observedFrom()), request.observedFrom().getEpochSecond(),
        request.observedFrom().getNano(), request.description(), write(evidenceRefs),
        write(sourceEvaluationIds), request.reason(), actor.principalId());
    if (key != null) {
      jdbc.update("UPDATE " + DB + "incident_idempotency SET incident_id=? "
              + "WHERE tenant_id=? AND environment_id=? AND actor_id=? AND idempotency_key=?",
          id, actor.tenantId(), actor.environmentId(), actor.principalId(), key);
    }
    return findIncident(actor, id, false);
  }

  private record IncidentRequest(String digest, String incidentId) {}

  @Transactional(readOnly = true)
  Incident getIncident(DelegationPrincipal actor, String id) {
    permit(actor, "assessment.incident.read");
    return findIncident(actor, id, false);
  }

  @Transactional(readOnly = true)
  IncidentPage listCaseIncidents(DelegationPrincipal actor, String caseId, int limit, String after) {
    permit(actor, "assessment.case.read");
    permit(actor, "assessment.incident.read");
    if (!AssessmentPagination.valid(limit, after)) {
      throw new AssessmentException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
          "A limit between 1 and 100 and a valid cursor are required.");
    }
    findCase(actor, caseId);
    List<Incident> rows = jdbc.query("SELECT * FROM " + DB
            + "incident WHERE tenant_id=? AND environment_id=? AND source_case_id=? "
            + "AND id>? ORDER BY id LIMIT ?",
        (rs, row) -> incidentRow(rs), actor.tenantId(), actor.environmentId(), caseId,
        after == null ? "" : after, limit + 1);
    boolean more = rows.size() > limit;
    List<Incident> items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
    return new IncidentPage(items, more ? items.getLast().id() : null);
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

  @Transactional(readOnly = true)
  LearningRecord getLearningRecord(DelegationPrincipal actor, String id) {
    permit(actor, "assessment.learning.read");
    return findLearningRecord(actor, id);
  }

  @Transactional
  LearningReview reviewLearningRecord(DelegationPrincipal actor, String id, ReviewLearningRecord request) {
    permit(actor, "assessment.learning.review");
    requireLearningReviewEnabled();
    LearningRecord record = findLearningRecord(actor, id, true);
    if (record.revision() != request.expectedRevision()) {
      throw conflict("REVISION_MISMATCH", "The learning record changed since it was read.");
    }
    if (actor.principalId().equals(record.proposedBy())) {
      throw conflict("INDEPENDENT_REVIEW_REQUIRED", "A learning record proposer cannot review it.");
    }
    if (learningReviewExists(actor, id)) {
      throw conflict("INVALID_STATE", "This learning record already has a review decision.");
    }
    if (request.decision() == LearningDecision.APPROVE) {
      verifyLearningRecordIntegrity(actor, record);
      verifyLearningEvidence(actor, record);
    }
    jdbc.update("INSERT INTO " + DB + "learning_review "
            + "(tenant_id,environment_id,learning_record_id,expected_revision,decision,reason,reviewed_by) "
            + "VALUES (?,?,?,?,?,?,?)", actor.tenantId(), actor.environmentId(), id,
        request.expectedRevision(), request.decision().name(), request.reason(), actor.principalId());
    LearningReview review = findLearningReview(actor, id);
    reviewEvent(actor, review);
    return review;
  }

  @Transactional(readOnly = true)
  LearningReview getLearningReview(DelegationPrincipal actor, String id) {
    permit(actor, "assessment.learning.read");
    return findLearningReview(actor, id);
  }

  @Transactional(readOnly = true)
  LearningReviewReceipt learningReviewReceipt(DelegationPrincipal actor, String id) {
    permit(actor, "assessment.learning.read");
    requireLearningReviewEnabled();
    LearningRecord record = findLearningRecord(actor, id);
    LearningReview review = findLearningReview(actor, id);
    if (!"APPROVE".equals(review.decision()) || review.expectedRevision() != record.revision()) {
      throw conflict("LEARNING_REVIEW_NOT_APPROVED", "An approved review for this revision is required.");
    }
    String contentDigest = verifyLearningRecordIntegrity(actor, record);
    String reviewDigest = hash(write(review));
    List<String> retainedDigests = jdbc.query("SELECT payload->>'owned_digest' FROM " + DB
            + "outbox_event WHERE tenant_id=? AND environment_id=? AND aggregate_type='LEARNING_REVIEW' "
            + "AND aggregate_id=? AND aggregate_sequence=1 AND event_type='uacrf.s07.learning.reviewed.v1'",
        (rs, row) -> rs.getString(1), actor.tenantId(), actor.environmentId(), id);
    if (retainedDigests.size() != 1 || !reviewDigest.equals(retainedDigests.getFirst())) {
      throw conflict("REVIEW_INTEGRITY_MISMATCH", "The retained review event does not match the review.");
    }
    Incident incident = verifyLearningEvidence(actor, record);
    return new LearningReviewReceipt(id, incident.id(), contentDigest, reviewDigest,
        incident.sourceEvaluationIds(), "VERIFIED", Instant.now(), false);
  }

  @Transactional
  AdaptationProposal proposeAdaptation(DelegationPrincipal actor, ProposeAdaptation request, String key) {
    permit(actor, "assessment.learning.write");
    permit(actor, "assessment.learning.read");
    if (!adaptationEnabled) throw conflict("FEATURE_DISABLED", "Adaptation proposals are disabled.");
    requireLearningReviewEnabled();
    if (key == null || key.isBlank() || key.length() > 200 || key.chars().anyMatch(Character::isWhitespace)) {
      throw new AssessmentException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
          "Idempotency-Key must be a non-empty token of at most 200 characters.");
    }
    String content = write(request);
    String digest = hash(content);
    int inserted = jdbc.update("INSERT INTO " + DB + "adaptation_idempotency "
            + "(tenant_id,environment_id,actor_id,idempotency_key,payload_digest) VALUES (?,?,?,?,?) "
            + "ON CONFLICT DO NOTHING",
        actor.tenantId(), actor.environmentId(), actor.principalId(), key, digest);
    if (inserted == 0) {
      List<AdaptationRequest> prior = jdbc.query("SELECT payload_digest,proposal_id FROM " + DB
              + "adaptation_idempotency WHERE tenant_id=? AND environment_id=? AND actor_id=? "
              + "AND idempotency_key=? FOR UPDATE",
          (rs, row) -> new AdaptationRequest(rs.getString("payload_digest"), rs.getString("proposal_id")),
          actor.tenantId(), actor.environmentId(), actor.principalId(), key);
      if (prior.isEmpty() || !digest.equals(prior.getFirst().digest())) {
        throw conflict("IDEMPOTENCY_CONFLICT", "The idempotency key was already used for different content.");
      }
      if (prior.getFirst().proposalId() == null) {
        throw conflict("COMMAND_IN_PROGRESS", "The original request has not completed.");
      }
      return findAdaptation(actor, prior.getFirst().proposalId());
    }
    LearningReviewReceipt receipt = learningReviewReceipt(actor, request.learningRecordId());
    String id = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO " + DB + "adaptation_proposal "
            + "(tenant_id,environment_id,id,learning_record_id,learning_record_digest,"
            + "learning_review_digest,learning_checked_at,content,state,created_by) "
            + "VALUES (?,?,?,?,?,?,?,?::jsonb,'DRAFT_UNVERIFIED',?)",
        actor.tenantId(), actor.environmentId(), id, request.learningRecordId(),
        receipt.learningRecordDigest(), receipt.reviewDigest(), Timestamp.from(receipt.checkedAt()),
        content, actor.principalId());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("owned_resource_id", id);
    payload.put("owned_revision", 1);
    payload.put("owned_digest", digest);
    payload.put("minimal_outcome_metadata", Map.of(
        "learning_record_id", request.learningRecordId(), "state", "DRAFT_UNVERIFIED"));
    payload.put("actor_id", actor.principalId());
    String serialized = write(payload);
    jdbc.update("INSERT INTO " + DB + "outbox_event "
            + "(event_id,tenant_id,environment_id,aggregate_type,aggregate_id,aggregate_sequence,"
            + "event_type,payload,payload_digest) VALUES (?,?,?,?,?,?,?,?::jsonb,?)",
        UUID.randomUUID(), actor.tenantId(), actor.environmentId(), "ADAPTATION_PROPOSAL", id, 1,
        "uacrf.s07.adaptation.proposed.v1", serialized, hash(serialized));
    jdbc.update("UPDATE " + DB + "adaptation_idempotency SET proposal_id=? "
            + "WHERE tenant_id=? AND environment_id=? AND actor_id=? AND idempotency_key=?",
        id, actor.tenantId(), actor.environmentId(), actor.principalId(), key);
    return findAdaptation(actor, id);
  }

  @Transactional(readOnly = true)
  AdaptationProposal getAdaptation(DelegationPrincipal actor, String id) {
    permit(actor, "assessment.learning.read");
    return findAdaptation(actor, id);
  }

  @Transactional(readOnly = true)
  AdaptationProposalPage listAdaptations(DelegationPrincipal actor, String learningRecordId,
      int limit, String after) {
    permit(actor, "assessment.learning.read");
    if (learningRecordId.isBlank() || learningRecordId.length() > 200
        || !AssessmentPagination.valid(limit, after)) {
      throw new AssessmentException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
          "A learning record, a limit between 1 and 100 and a valid cursor are required.");
    }
    findLearningRecord(actor, learningRecordId);
    List<AdaptationProposal> rows = jdbc.query("SELECT * FROM " + DB
            + "adaptation_proposal WHERE tenant_id=? AND environment_id=? AND learning_record_id=? "
            + "AND id>? ORDER BY id LIMIT ?",
        (rs, row) -> adaptationProposalRow(rs), actor.tenantId(), actor.environmentId(),
        learningRecordId, after == null ? "" : after, limit + 1);
    boolean more = rows.size() > limit;
    return new AdaptationProposalPage(List.copyOf(rows.subList(0, Math.min(rows.size(), limit))),
        more ? rows.get(limit - 1).id() : null);
  }

  private AdaptationProposal findAdaptation(DelegationPrincipal actor, String id) {
    List<AdaptationProposal> rows = jdbc.query("SELECT * FROM " + DB
            + "adaptation_proposal WHERE tenant_id=? AND environment_id=? AND id=?",
        (rs, row) -> adaptationProposalRow(rs),
        actor.tenantId(), actor.environmentId(), id);
    if (rows.isEmpty()) throw notFound();
    return rows.getFirst();
  }

  private AdaptationProposal adaptationProposalRow(ResultSet rs) throws SQLException {
    return new AdaptationProposal(rs.getString("id"),
        read(rs.getString("content"), ProposeAdaptation.class),
        rs.getString("learning_record_digest"), rs.getString("learning_review_digest"),
        rs.getTimestamp("learning_checked_at").toInstant(), rs.getString("state"),
        rs.getString("created_by"), rs.getTimestamp("created_at").toInstant());
  }

  private String verifyLearningRecordIntegrity(DelegationPrincipal actor, LearningRecord record) {
    List<LearningContent> contents = jdbc.query("SELECT content::text,content_digest FROM " + DB
            + "learning_record WHERE tenant_id=? AND environment_id=? AND id=?",
        (rs, row) -> new LearningContent(read(rs.getString("content"), ProposeLearningRecord.class),
            rs.getString("content_digest")), actor.tenantId(), actor.environmentId(), record.id());
    if (contents.size() != 1) {
      throw conflict("LEARNING_INTEGRITY_MISMATCH", "The retained learning proposal does not match its event.");
    }
    LearningContent retained = contents.getFirst();
    ProposeLearningRecord actual = new ProposeLearningRecord(record.incidentId(), record.experimentId(),
        record.hypotheses(), record.actionItems(), record.evidenceRefs(), record.expectedRevision(), record.reason());
    List<String> eventDigests = jdbc.query("SELECT payload->>'owned_digest' FROM " + DB
            + "outbox_event WHERE tenant_id=? AND environment_id=? AND aggregate_type='LEARNING_RECORD' "
            + "AND aggregate_id=? AND aggregate_sequence=1 AND event_type='uacrf.s07.learning.proposed.v1'",
        (rs, row) -> rs.getString(1), actor.tenantId(), actor.environmentId(), record.id());
    if (!actual.equals(retained.content()) || !hash(write(retained.content())).equals(retained.digest())
        || eventDigests.size() != 1 || !retained.digest().equals(eventDigests.getFirst())) {
      throw conflict("LEARNING_INTEGRITY_MISMATCH", "The retained learning proposal does not match its event.");
    }
    return retained.digest();
  }

  private Incident verifyLearningEvidence(DelegationPrincipal actor, LearningRecord record) {
    Incident incident = findIncident(actor, record.incidentId());
    if (record.expectedRevision() != incident.revision()
        || !new HashSet<>(incident.evidenceRefs()).containsAll(record.evidenceRefs())) {
      throw conflict("INCIDENT_PROVENANCE_INVALID",
          "Learning evidence must belong to the expected incident revision.");
    }
    if (incident.sourceEvaluationIds() == null) {
      throw conflict("INCIDENT_PROVENANCE_REQUIRED",
          "Approval requires the incident's retained evaluation provenance.");
    }
    Set<String> coveredReceipts = new HashSet<>();
    for (String evaluationId : incident.sourceEvaluationIds()) {
      List<EvaluationEvidence> evaluations = jdbc.query("SELECT id,observation_cutoff,dependency_vector::text "
              + "FROM " + DB + "evaluation WHERE tenant_id=? AND environment_id=? AND case_id=? AND id=?",
          (rs, row) -> evaluationEvidence(rs), actor.tenantId(), actor.environmentId(),
          incident.sourceCaseId(), evaluationId);
      if (evaluations.isEmpty() || !hasVerifiedOwnerReceipts(evaluations.getFirst().vector())
          || evaluations.getFirst().observationCutoff().isAfter(incident.observedFrom())) {
        throw conflict("INCIDENT_PROVENANCE_INVALID", "Incident evaluation provenance is not qualifying.");
      }
      EvaluationEvidence evaluation = evaluations.getFirst();
      coveredReceipts.addAll(successfulMeasurementReceipts(
          evaluation.vector(), evaluation.observationCutoff(), incident.observedFrom()));
      assessment.revalidateEvaluation(actor, evaluationId);
    }
    if (!coveredReceipts.containsAll(record.evidenceRefs())) {
      throw conflict("INCIDENT_PROVENANCE_INVALID",
          "Incident evaluation provenance does not cover the learning evidence.");
    }
    return incident;
  }

  private void requireLearningReviewEnabled() {
    if (!reviewEnabled) throw conflict("FEATURE_DISABLED", "Learning review is disabled.");
  }

  @Transactional(readOnly = true)
  LearningRecordPage listLearningRecords(DelegationPrincipal actor, String incidentId,
      int limit, String after) {
    permit(actor, "assessment.learning.read");
    permit(actor, "assessment.incident.read");
    if (!AssessmentPagination.valid(limit, after)) {
      throw new AssessmentException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
          "A limit between 1 and 100 and a valid cursor are required.");
    }
    findIncident(actor, incidentId);
    List<LearningRecord> rows = jdbc.query("SELECT * FROM " + DB
            + "learning_record WHERE tenant_id=? AND environment_id=? AND incident_id=? "
            + "AND id>? ORDER BY id LIMIT ?",
        (rs, row) -> learningRecordRow(rs), actor.tenantId(), actor.environmentId(), incidentId,
        after == null ? "" : after, limit + 1);
    boolean more = rows.size() > limit;
    List<LearningRecord> items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
    return new LearningRecordPage(items, more ? items.getLast().id() : null);
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
    return findLearningRecord(actor, id, false);
  }

  private LearningRecord findLearningRecord(DelegationPrincipal actor, String id, boolean lock) {
    List<LearningRecord> rows = jdbc.query("SELECT * FROM " + DB
            + "learning_record WHERE tenant_id=? AND environment_id=? AND id=?"
            + (lock ? " FOR UPDATE" : ""),
        (rs, row) -> learningRecordRow(rs), actor.tenantId(), actor.environmentId(), id);
    if (rows.isEmpty()) throw notFound();
    return rows.getFirst();
  }

  private boolean learningReviewExists(DelegationPrincipal actor, String id) {
    return jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + DB
        + "learning_review WHERE tenant_id=? AND environment_id=? AND learning_record_id=?)",
        Boolean.class, actor.tenantId(), actor.environmentId(), id);
  }

  private LearningReview findLearningReview(DelegationPrincipal actor, String id) {
    List<LearningReview> rows = jdbc.query("SELECT * FROM " + DB
            + "learning_review WHERE tenant_id=? AND environment_id=? AND learning_record_id=?",
        (rs, row) -> new LearningReview(rs.getString("learning_record_id"), rs.getLong("expected_revision"),
            rs.getString("decision"), rs.getString("reason"), rs.getString("reviewed_by"),
            rs.getTimestamp("reviewed_at").toInstant()),
        actor.tenantId(), actor.environmentId(), id);
    if (rows.isEmpty()) throw notFound();
    return rows.getFirst();
  }

  private Incident incidentRow(ResultSet rs) throws SQLException {
    String sourceEvaluationIds = rs.getString("source_evaluation_ids");
    Long epochSecond = rs.getObject("observed_from_epoch_second", Long.class);
    Instant observedFrom = epochSecond == null ? rs.getTimestamp("observed_from").toInstant()
        : Instant.ofEpochSecond(epochSecond, rs.getInt("observed_from_nano"));
    return new Incident(rs.getString("id"), rs.getString("workload_id"),
        rs.getString("source_case_id"), observedFrom,
        rs.getString("description"), readStringList(rs.getString("evidence_refs")),
        sourceEvaluationIds == null ? null : readStringList(sourceEvaluationIds),
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
    return new EvaluationEvidence(rs.getString("id"), Instant.parse(rs.getString("observation_cutoff")),
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

  private void reviewEvent(DelegationPrincipal actor, LearningReview review) {
    Map<String, Object> metadata = Map.of("decision", review.decision(),
        "expected_revision", review.expectedRevision());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("owned_resource_id", review.learningRecordId());
    payload.put("owned_revision", 1);
    payload.put("owned_digest", hash(write(review)));
    payload.put("minimal_outcome_metadata", metadata);
    payload.put("actor_id", actor.principalId());
    String serialized = write(payload);
    jdbc.update("INSERT INTO " + DB
            + "outbox_event (event_id,tenant_id,environment_id,aggregate_type,aggregate_id,"
            + "aggregate_sequence,event_type,payload,payload_digest) VALUES (?,?,?,?,?,?,?,?::jsonb,?)",
        UUID.randomUUID(), actor.tenantId(), actor.environmentId(), "LEARNING_REVIEW",
        review.learningRecordId(), 1, "uacrf.s07.learning.reviewed.v1", serialized, hash(serialized));
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
  private record EvaluationEvidence(String id, Instant observationCutoff, DependencyVector vector) {}
  private record LearningContent(ProposeLearningRecord content, String digest) {}
  private record AdaptationRequest(String digest, String proposalId) {}
}
