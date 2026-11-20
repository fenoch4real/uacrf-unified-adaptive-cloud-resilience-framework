package org.uacrf.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
          if (workloadId != null) request.queryParam("workload_id", workloadId);
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
      String state, Long revision, @JsonProperty("case_digest") String caseDigest) {}
  record Evaluation(
      String id, @JsonProperty("case_id") String caseId, Long revision,
      @JsonProperty("observation_cutoff") Instant observationCutoff,
      @JsonProperty("received_at") Instant receivedAt, String mode, String outcome,
      @JsonProperty("dependency_vector") EvaluationDependencies dependencyVector,
      @JsonProperty("result_digest") String resultDigest) {}
  record EvaluationDependencies(
      @JsonProperty("receipt_verification") String receiptVerification,
      @JsonProperty("receipt_reason_code") String receiptReasonCode) {}
}
