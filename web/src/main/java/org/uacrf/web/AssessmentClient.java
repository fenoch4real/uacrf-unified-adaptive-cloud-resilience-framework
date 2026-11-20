package org.uacrf.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class AssessmentClient {
  static final int PAGE_SIZE = 50;
  private static final String CURSOR_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
  private final RestClient restClient;

  AssessmentClient(@Qualifier("assessmentRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  List<AssessmentCase> listCases(String token, String workloadId) {
    List<AssessmentCase> cases = restClient.get()
        .uri(uri -> {
          var request = uri.path("/v1/cases");
          if (workloadId != null) return request.queryParam("workload_id", "{workloadId}").build(workloadId);
          return request.build();
        })
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve().body(new ParameterizedTypeReference<List<AssessmentCase>>() {});
    if (cases == null || cases.stream().anyMatch(item -> !valid(item)
        || (workloadId != null && !workloadId.equals(item.workloadId())))) {
      throw new IllegalStateException("S07 returned an incomplete or mismatched case list.");
    }
    return List.copyOf(cases);
  }

  AssessmentCase createCase(String token, String key, CaseCreation request) {
    AssessmentCase item = restClient.post().uri("/v1/cases:idempotent-create")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", key).body(request).retrieve().body(AssessmentCase.class);
    if (!valid(item) || !request.workloadId().equals(item.workloadId())
        || !request.purpose().equals(item.purpose()) || !request.metric().equals(item.metric())
        || !request.comparator().equals(item.comparator()) || request.threshold().compareTo(item.threshold()) != 0
        || !request.unit().equals(item.unit()) || !request.windowSeconds().equals(item.windowSeconds())
        || !Objects.equals(request.objectiveRef(), item.objectiveRef())
        || !Objects.equals(request.graphRef(), item.graphRef())
        || item.requiredSourceKinds() == null
        || item.requiredSourceKinds().stream().anyMatch(Objects::isNull)
        || item.requiredSourceKinds().size() != request.requiredSourceKinds().size()
        || !new TreeSet<>(request.requiredSourceKinds()).equals(new TreeSet<>(item.requiredSourceKinds()))) {
      throw new IllegalStateException("S07 returned an incomplete or mismatched case receipt.");
    }
    return item;
  }

  AssessmentCase getCase(String token, String caseId) {
    AssessmentCase item = restClient.get()
        .uri(uri -> uri.path("/v1/cases/{id}").build(caseId))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve().body(AssessmentCase.class);
    if (!validCaseDetails(item) || !caseId.equals(item.id())) {
      throw new IllegalStateException("S07 returned incomplete or mismatched case details.");
    }
    return item;
  }

  AssessmentCase sealCase(String token, String caseId, long expectedRevision) {
    AssessmentCase item = restClient.post()
        .uri(uri -> uri.path("/v1/cases/{id}:seal").build(caseId))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body(new CaseTransition(expectedRevision)).retrieve().body(AssessmentCase.class);
    if (!validCaseDetails(item) || !caseId.equals(item.id()) || !"SEALED".equals(item.state())
        || item.revision() != expectedRevision + 1) {
      throw new IllegalStateException("S07 returned an incomplete or mismatched seal receipt.");
    }
    return item;
  }

  List<Evaluation> listEvaluations(String token, String caseId) {
    List<Evaluation> evaluations = restClient.get()
        .uri(uri -> uri.path("/v1/cases/{id}/evaluations").build(caseId))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve().body(new ParameterizedTypeReference<List<Evaluation>>() {});
    if (evaluations == null || evaluations.stream().anyMatch(item -> !valid(item, caseId))) {
      throw new IllegalStateException("S07 returned an incomplete or mismatched evaluation list.");
    }
    return List.copyOf(evaluations);
  }

  Evaluation evaluateCase(String token, String caseId, String key, EvaluationRequest request) {
    Evaluation item = restClient.post()
        .uri(uri -> uri.path("/v1/cases/{id}:idempotent-evaluate").build(caseId))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", key).body(request).retrieve().body(Evaluation.class);
    if (!valid(item, caseId) || !request.observationCutoff().equals(item.observationCutoff())
        || !request.mode().equals(item.mode())
        || ("FAILED".equals(item.dependencyVector().receiptVerification())
            && !"INSUFFICIENT_EVIDENCE".equals(item.outcome()))
        || (request.measurements().isEmpty() && !"INSUFFICIENT_EVIDENCE".equals(item.outcome()))) {
      throw new IllegalStateException("S07 returned an incomplete or mismatched evaluation receipt.");
    }
    return item;
  }

  List<Recommendation> listRecommendations(String token, String workloadId) {
    List<Recommendation> recommendations = restClient.get()
        .uri(uri -> uri.path("/v1/recommendations").queryParam("workload_id", "{workloadId}")
            .build(workloadId))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve().body(new ParameterizedTypeReference<List<Recommendation>>() {});
    if (recommendations == null || recommendations.stream().anyMatch(item -> !valid(item, workloadId))) {
      throw new IllegalStateException("S07 returned an incomplete or mismatched recommendation list.");
    }
    return List.copyOf(recommendations);
  }

  IncidentPage listIncidents(String token, String caseId, String after) {
    IncidentPage page = restClient.get()
        .uri(uri -> {
          var request = uri.path("/v1/cases/{id}/incidents").queryParam("limit", PAGE_SIZE);
          if (after != null) request.queryParam("after", "{after}");
          return after == null ? request.build(caseId) : request.build(caseId, after);
        }).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve().body(IncidentPage.class);
    if (page == null || page.items() == null || page.items().stream().anyMatch(item ->
        item == null || !validCursor(item.id()) || !caseId.equals(item.sourceCaseId())
            || !present(item.workloadId()) || item.observedFrom() == null || !present(item.description())
            || !validStrings(item.evidenceRefs()) || (item.sourceEvaluationIds() != null
                && !validStrings(item.sourceEvaluationIds()))
            || !present(item.reason()) || item.revision() == null || item.revision() != 1
            || !present(item.createdBy()) || item.createdAt() == null)
        || !validPage(page.items().stream().map(Incident::id).toList(), page.nextCursor(), after)) {
      throw new IllegalStateException("S07 returned an incomplete or mismatched incident page.");
    }
    return new IncidentPage(List.copyOf(page.items()), page.nextCursor());
  }

  Incident createIncident(String token, String key, IncidentCreation request) {
    Incident item = restClient.post().uri("/v1/incidents:idempotent-create")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", key).body(request).retrieve().body(Incident.class);
    if (item == null || !validCursor(item.id()) || !request.sourceCaseId().equals(item.sourceCaseId())
        || !request.workloadId().equals(item.workloadId()) || !request.observedFrom().equals(item.observedFrom())
        || !request.description().equals(item.description()) || !request.reason().equals(item.reason())
        || !validStrings(item.evidenceRefs())
        || !new TreeSet<>(request.evidenceRefs()).equals(new TreeSet<>(item.evidenceRefs()))
        || item.evidenceRefs().size() != new TreeSet<>(item.evidenceRefs()).size()
        || !validStrings(item.sourceEvaluationIds()) || item.revision() == null || item.revision() != 1
        || !present(item.createdBy()) || item.createdAt() == null) {
      throw new IllegalStateException("S07 returned an incomplete or mismatched incident receipt.");
    }
    return item;
  }

  Incident getIncident(String token, String incidentId) {
    Incident item = restClient.get().uri(uri -> uri.path("/v1/incidents/{id}").build(incidentId))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(Incident.class);
    if (item == null || !validCursor(item.id()) || !incidentId.equals(item.id())
        || !present(item.workloadId()) || !present(item.sourceCaseId()) || item.observedFrom() == null
        || !present(item.description()) || !validStrings(item.evidenceRefs())
        || (item.sourceEvaluationIds() != null && !validStrings(item.sourceEvaluationIds()))
        || !present(item.reason()) || item.revision() == null || item.revision() != 1
        || !present(item.createdBy()) || item.createdAt() == null) {
      throw new IllegalStateException("S07 returned incomplete or mismatched incident details.");
    }
    return item;
  }

  LearningRecord proposeLearningRecord(String token, String key, LearningProposal request) {
    LearningRecord item = restClient.post().uri("/v1/learning-records:idempotent-propose")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", key).body(request).retrieve().body(LearningRecord.class);
    if (item == null || !validCursor(item.id()) || !request.incidentId().equals(item.incidentId())
        || !Objects.equals(request.experimentId(), item.experimentId())
        || !request.hypotheses().equals(item.hypotheses())
        || !request.actionItems().equals(item.actionItems()) || !validStrings(item.evidenceRefs())
        || !new TreeSet<>(request.evidenceRefs()).equals(new TreeSet<>(item.evidenceRefs()))
        || item.evidenceRefs().size() != new TreeSet<>(item.evidenceRefs()).size()
        || !Objects.equals(request.expectedRevision(), item.expectedRevision())
        || !request.reason().equals(item.reason()) || !"PROPOSED".equals(item.state())
        || item.revision() == null || item.revision() != 1 || !present(item.proposedBy()) || item.createdAt() == null) {
      throw new IllegalStateException("S07 returned an incomplete or mismatched learning proposal.");
    }
    return item;
  }

  LearningRecordPage listLearningRecords(String token, String incidentId, String after) {
    LearningRecordPage page = restClient.get()
        .uri(uri -> {
          var request = uri.path("/v1/incidents/{id}/learning-records").queryParam("limit", PAGE_SIZE);
          if (after != null) request.queryParam("after", "{after}");
          return after == null ? request.build(incidentId) : request.build(incidentId, after);
        }).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve().body(LearningRecordPage.class);
    if (page == null || page.items() == null || page.items().stream().anyMatch(item ->
        !validLearningRecord(item) || !incidentId.equals(item.incidentId()))
        || !validPage(page.items().stream().map(LearningRecord::id).toList(), page.nextCursor(), after)) {
      throw new IllegalStateException("S07 returned an incomplete or mismatched learning-record page.");
    }
    return new LearningRecordPage(List.copyOf(page.items()), page.nextCursor());
  }

  LearningRecord getLearningRecord(String token, String id) {
    LearningRecord item = restClient.get().uri(uri -> uri.path("/v1/learning-records/{id}").build(id))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(LearningRecord.class);
    if (!validLearningRecord(item) || !id.equals(item.id())) {
      throw new IllegalStateException("S07 returned incomplete or mismatched learning-record details.");
    }
    return item;
  }

  LearningReview getLearningReview(String token, String id) {
    LearningReview item = restClient.get().uri(uri -> uri.path("/v1/learning-records/{id}/review").build(id))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(LearningReview.class);
    if (!validReview(item, id)) throw new IllegalStateException("S07 returned an invalid learning review.");
    return item;
  }

  LearningReview reviewLearningRecord(String token, String id, LearningReviewRequest request) {
    LearningReview item = restClient.post().uri(uri -> uri.path("/v1/learning-records/{id}:review").build(id))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).body(request).retrieve().body(LearningReview.class);
    if (!validReview(item, id) || !request.expectedRevision().equals(item.expectedRevision())
        || !request.decision().equals(item.decision()) || !request.reason().equals(item.reason())) {
      throw new IllegalStateException("S07 returned an incomplete or mismatched learning review.");
    }
    return item;
  }

  private static boolean validLearningRecord(LearningRecord item) {
    return item != null && validCursor(item.id()) && validCursor(item.incidentId())
        && validStrings(item.hypotheses()) && validStrings(item.evidenceRefs())
        && item.actionItems() != null && !item.actionItems().isEmpty()
        && item.actionItems().size() <= 100 && item.actionItems().stream().allMatch(action ->
            action != null && present(action.description()) && present(action.ownerId())
                && action.dueAt() != null && present(action.closureTest()))
        && item.expectedRevision() != null && item.expectedRevision() >= 1
        && present(item.reason()) && "PROPOSED".equals(item.state())
        && item.revision() != null && item.revision() == 1
        && present(item.proposedBy()) && item.createdAt() != null;
  }

  private static boolean validReview(LearningReview item, String id) {
    return item != null && id.equals(item.learningRecordId())
        && item.expectedRevision() != null && item.expectedRevision() == 1
        && ("APPROVE".equals(item.decision()) || "REJECT".equals(item.decision()))
        && present(item.reason()) && present(item.reviewedBy()) && item.reviewedAt() != null;
  }

  static boolean validCursor(String cursor) {
    return cursor != null && cursor.matches(CURSOR_PATTERN);
  }

  private static boolean validPage(List<String> ids, String next, String after) {
    if (ids.size() > PAGE_SIZE || (next != null && (ids.size() != PAGE_SIZE
        || !validCursor(next) || !next.equals(ids.getLast())))) return false;
    String previous = after;
    for (String id : ids) {
      if (previous != null && previous.compareTo(id) >= 0) return false;
      previous = id;
    }
    return true;
  }

  private static boolean validStrings(List<String> items) {
    return items != null && !items.isEmpty() && items.size() <= 100
        && items.stream().allMatch(AssessmentClient::present);
  }

  private static boolean valid(Recommendation item, String workloadId) {
    if (item == null || !present(item.id()) || !present(item.caseId()) || !present(item.evaluationId())
        || !workloadId.equals(item.workloadId()) || !"INVESTIGATE_BREACH".equals(item.kind())
        || !Boolean.FALSE.equals(item.executable()) || item.effectBounds() == null) return false;
    RecommendationBounds bounds = item.effectBounds();
    return present(bounds.metric()) && present(bounds.unit()) && bounds.threshold() != null
        && ("LTE".equals(bounds.comparator()) || "GTE".equals(bounds.comparator()))
        && "INVESTIGATE_ONLY".equals(bounds.effect());
  }

  private static boolean valid(Evaluation item, String caseId) {
    return item != null && present(item.id()) && caseId.equals(item.caseId())
        && item.revision() != null && item.revision() > 0
        && item.observationCutoff() != null && item.receivedAt() != null
        && item.mode() != null && List.of("SHADOW", "DRY_RUN", "LIVE").contains(item.mode())
        && item.outcome() != null
        && List.of("CONFIRMED_SUCCESS", "CONFIRMED_BREACH", "INSUFFICIENT_EVIDENCE").contains(item.outcome())
        && item.dependencyVector() != null
        && item.dependencyVector().receiptVerification() != null
        && List.of("SKIPPED", "VERIFIED", "FAILED").contains(item.dependencyVector().receiptVerification())
        && (!"FAILED".equals(item.dependencyVector().receiptVerification())
            || present(item.dependencyVector().receiptReasonCode()))
        && item.resultDigest() != null && item.resultDigest().matches("[a-f0-9]{64}");
  }

  private static boolean valid(AssessmentCase item) {
    return item != null && present(item.id()) && present(item.workloadId())
        && present(item.purpose()) && present(item.metric()) && present(item.unit())
        && ("LTE".equals(item.comparator()) || "GTE".equals(item.comparator())) && item.threshold() != null
        && item.windowSeconds() != null && item.windowSeconds() > 0
        && item.revision() != null && item.revision() > 0
        && (("DRAFT".equals(item.state()) && item.caseDigest() == null)
            || ("SEALED".equals(item.state()) && item.caseDigest() != null
                && item.caseDigest().matches("[a-f0-9]{64}")));
  }

  private static boolean validCaseDetails(AssessmentCase item) {
    return valid(item) && item.objectiveRef() != null && present(item.objectiveRef().id())
        && item.objectiveRef().revision() != null && item.objectiveRef().revision() > 0
        && present(item.objectiveRef().digest()) && item.graphRef() != null && present(item.graphRef().id())
        && item.graphRef().revision() != null && item.graphRef().revision() > 0 && present(item.graphRef().digest())
        && item.requiredSourceKinds() != null && !item.requiredSourceKinds().isEmpty()
        && item.requiredSourceKinds().stream().allMatch(AssessmentClient::present)
        && item.requiredSourceKinds().stream().distinct().count() == item.requiredSourceKinds().size();
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }

  record AssessmentCase(
      String id, @JsonProperty("workload_id") String workloadId, String purpose, String metric,
      String comparator, BigDecimal threshold, String unit, @JsonProperty("window_seconds") Integer windowSeconds,
      String state, Long revision, @JsonProperty("case_digest") String caseDigest,
      @JsonProperty("objective_ref") ObjectiveRef objectiveRef, @JsonProperty("graph_ref") GraphRef graphRef,
      @JsonProperty("required_source_kinds") List<String> requiredSourceKinds) {}
  record CaseCreation(
      @JsonProperty("workload_id") String workloadId, String purpose, String metric,
      String comparator, BigDecimal threshold, String unit, @JsonProperty("window_seconds") Integer windowSeconds,
      @JsonProperty("objective_ref") ObjectiveRef objectiveRef, @JsonProperty("graph_ref") GraphRef graphRef,
      @JsonProperty("required_source_kinds") List<String> requiredSourceKinds) {}
  record ObjectiveRef(@JsonProperty("objective_id") String id, Long revision, String digest) {}
  record GraphRef(@JsonProperty("graph_id") String id, Long revision, String digest) {}
  record CaseTransition(@JsonProperty("expected_revision") long expectedRevision) {}
  record EvaluationRequest(@JsonProperty("observation_cutoff") Instant observationCutoff, String mode,
      List<EvaluationMeasurement> measurements) {}
  record EvaluationMeasurement(@JsonProperty("source_receipt_id") @NotBlank @Size(max=200) String sourceReceiptId,
      @JsonProperty("source_kind") @NotBlank @Size(max=200) String sourceKind,
      @JsonProperty("observed_at") @NotNull Instant observedAt,
      @NotNull @Digits(integer=100, fraction=50) BigDecimal value, @NotBlank @Size(max=30) String unit) {}
  record Evaluation(
      String id, @JsonProperty("case_id") String caseId, Long revision,
      @JsonProperty("observation_cutoff") Instant observationCutoff,
      @JsonProperty("received_at") Instant receivedAt, String mode, String outcome,
      @JsonProperty("dependency_vector") EvaluationDependencies dependencyVector,
      @JsonProperty("result_digest") String resultDigest) {}
  record EvaluationDependencies(
      @JsonProperty("receipt_verification") String receiptVerification,
      @JsonProperty("receipt_reason_code") String receiptReasonCode) {}
  record Recommendation(
      String id, @JsonProperty("evaluation_id") String evaluationId,
      @JsonProperty("case_id") String caseId, @JsonProperty("workload_id") String workloadId,
      String kind, @JsonProperty("effect_bounds") RecommendationBounds effectBounds,
      Boolean executable) {}
  record RecommendationBounds(String metric, String comparator, BigDecimal threshold, String unit, String effect) {}
  record IncidentPage(List<Incident> items, @JsonProperty("next_cursor") String nextCursor) {}
  record IncidentCreation(@JsonProperty("workload_id") String workloadId,
      @JsonProperty("source_case_id") String sourceCaseId, @JsonProperty("observed_from") Instant observedFrom,
      String description, @JsonProperty("evidence_refs") List<String> evidenceRefs, String reason) {}
  record Incident(String id, @JsonProperty("workload_id") String workloadId,
      @JsonProperty("source_case_id") String sourceCaseId, @JsonProperty("observed_from") Instant observedFrom,
      String description, @JsonProperty("evidence_refs") List<String> evidenceRefs,
      @JsonProperty("source_evaluation_ids") List<String> sourceEvaluationIds, String reason, Long revision,
      @JsonProperty("created_by") String createdBy, @JsonProperty("created_at") Instant createdAt) {}
  record LearningRecordPage(List<LearningRecord> items, @JsonProperty("next_cursor") String nextCursor) {}
  record LearningProposal(@JsonProperty("incident_id") String incidentId,
      @JsonProperty("experiment_id") String experimentId, List<String> hypotheses,
      @JsonProperty("action_items") List<LearningAction> actionItems,
      @JsonProperty("evidence_refs") List<String> evidenceRefs,
      @JsonProperty("expected_revision") Long expectedRevision, String reason) {}
  record LearningRecord(String id, @JsonProperty("incident_id") String incidentId,
      @JsonProperty("experiment_id") String experimentId, List<String> hypotheses,
      @JsonProperty("action_items") List<LearningAction> actionItems,
      @JsonProperty("evidence_refs") List<String> evidenceRefs,
      @JsonProperty("expected_revision") Long expectedRevision, String reason, String state, Long revision,
      @JsonProperty("proposed_by") String proposedBy, @JsonProperty("created_at") Instant createdAt) {}
  record LearningAction(@NotBlank @Size(max=2000) String description,
      @JsonProperty("owner_id") @NotBlank @Size(max=200) String ownerId,
      @JsonProperty("due_at") @NotNull Instant dueAt,
      @JsonProperty("proposed_version_ref") @Size(max=1000) String proposedVersionRef,
      @JsonProperty("closure_test") @NotBlank @Size(max=2000) String closureTest) {}
  record LearningReviewRequest(@JsonProperty("expected_revision") Long expectedRevision, String decision, String reason) {}
  record LearningReview(@JsonProperty("learning_record_id") String learningRecordId,
      @JsonProperty("expected_revision") Long expectedRevision, String decision, String reason,
      @JsonProperty("reviewed_by") String reviewedBy, @JsonProperty("reviewed_at") Instant reviewedAt) {}
}
