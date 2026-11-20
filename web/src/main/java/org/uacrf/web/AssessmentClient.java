package org.uacrf.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
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
}
