package org.uacrf.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class EvidenceClient {
  private final RestClient restClient;

  EvidenceClient(@Qualifier("evidenceRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  EvidencePage list(String token, String cursor, String serviceId) {
    EvidencePage page = restClient.get()
        .uri(uri -> {
          var request = uri.path("/v1/evidence").queryParam("pageSize", 25);
          if (cursor != null && !cursor.isBlank()) {
            request.queryParam("cursor", cursor);
          }
          return request.build();
        })
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve().body(EvidencePage.class);
    if (page == null || page.items() == null
        || (page.nextCursor() != null && page.nextCursor().isBlank())
        || page.items().stream().anyMatch(item -> !valid(item)
            || (serviceId != null && !serviceId.equals(item.serviceId())))) {
      throw new IllegalStateException("S04 returned an incomplete evidence page.");
    }
    return page;
  }

  private static boolean valid(Evidence item) {
    return item != null && present(item.id()) && present(item.sourceRef())
        && present(item.sourceClass()) && present(item.qualificationState())
        && List.of("SAMPLE_LOCAL", "PROVIDER").contains(item.sourceClass())
        && List.of("SAMPLE_ONLY", "UNVERIFIED", "QUALIFIED", "REJECTED")
            .contains(item.qualificationState())
        && ("SAMPLE_LOCAL".equals(item.sourceClass()) == "SAMPLE_ONLY".equals(item.qualificationState()))
        && item.sequence() != null && item.sequence() > 0
        && present(item.observedAt()) && present(item.kind())
        && present(item.payloadDigest()) && item.payloadDigest().matches("^[0-9a-f]{64}$")
        && item.payloadByteLength() != null && item.payloadByteLength() >= 0
        && present(item.submittedBy()) && item.revision() != null && item.revision() > 0;
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }

  record EvidencePage(List<Evidence> items, @JsonProperty("next_cursor") String nextCursor) {}

  record Evidence(
      String id,
      @JsonProperty("source_class") String sourceClass,
      @JsonProperty("source_ref") String sourceRef,
      Long sequence,
      @JsonProperty("observed_at") String observedAt,
      String kind,
      @JsonProperty("service_id") String serviceId,
      @JsonProperty("schema_version") String schemaVersion,
      @JsonProperty("payload_digest") String payloadDigest,
      @JsonProperty("payload_byte_length") Long payloadByteLength,
      @JsonProperty("submitted_by") String submittedBy,
      @JsonProperty("qualification_state") String qualificationState,
      Long revision,
      @JsonProperty("reviewed_by") String reviewedBy,
      @JsonProperty("review_reason") String reviewReason) {}
}
