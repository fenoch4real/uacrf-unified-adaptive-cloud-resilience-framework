package org.uacrf.web;

import com.fasterxml.jackson.annotation.JsonProperty;
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
}
