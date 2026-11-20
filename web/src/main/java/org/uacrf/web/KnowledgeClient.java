package org.uacrf.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class KnowledgeClient {
  private static final String IDENTIFIER = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$";
  private final RestClient restClient;

  KnowledgeClient(@Qualifier("knowledgeRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  List<ItemVersion> list(String token, String itemId, String purpose, Classification ceiling) {
    List<ItemVersion> items = restClient.get().uri(uri -> {
      var query = uri.path("/v1/knowledge-items");
      if (itemId != null) query.queryParam("item_id", "{itemId}");
      if (purpose != null) query.queryParam("purpose", "{purpose}");
      return query.build(java.util.Map.of("itemId", itemId == null ? "" : itemId,
          "purpose", purpose == null ? "" : purpose));
    }).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve().body(new ParameterizedTypeReference<List<ItemVersion>>() {});
    if (items == null || items.stream().anyMatch(item -> !valid(item, itemId, purpose, ceiling))) {
      throw new IllegalStateException("S08 returned incomplete or mismatched knowledge metadata.");
    }
    return List.copyOf(items);
  }

  private static boolean valid(ItemVersion item, String itemId, String purpose, Classification ceiling) {
    return item != null && item.itemId() != null && item.itemId().matches(IDENTIFIER)
        && (itemId == null || itemId.equals(item.itemId()))
        && item.version() != null && item.version().matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}$")
        && present(item.title()) && item.classification() != null
        && item.classification().ordinal() <= ceiling.ordinal()
        && validIdentifiers(item.allowedPurposes(), false)
        && (purpose == null || item.allowedPurposes().contains(purpose))
        && validIdentifiers(item.targetWorkloadIds(), true)
        && item.reviewDeadline() != null && present(item.reason())
        && item.sourceDigest() != null && item.sourceDigest().matches("^[0-9a-f]{64}$")
        && item.state() != null && item.revision() != null && item.revision() >= 1
        && present(item.proposedBy()) && item.createdAt() != null;
  }

  private static boolean validIdentifiers(List<String> values, boolean emptyAllowed) {
    return values != null && (emptyAllowed || !values.isEmpty()) && values.size() <= 100
        && values.stream().allMatch(value -> value != null && value.matches(IDENTIFIER));
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }

  enum Classification { PUBLIC, INTERNAL, RESTRICTED }
  enum State { CANDIDATE, APPROVED, REJECTED, SUPERSEDED, WITHDRAWN }

  record ItemVersion(@JsonProperty("item_id") String itemId, String version, String title,
      @JsonProperty("source_ref") String sourceRef, Classification classification,
      @JsonProperty("allowed_purposes") List<String> allowedPurposes,
      @JsonProperty("target_workload_ids") List<String> targetWorkloadIds,
      @JsonProperty("review_deadline") Instant reviewDeadline, String reason,
      @JsonProperty("source_digest") String sourceDigest, State state, Long revision,
      @JsonProperty("proposed_by") String proposedBy, @JsonProperty("reviewed_by") String reviewedBy,
      @JsonProperty("review_reason") String reviewReason, @JsonProperty("withdrawn_by") String withdrawnBy,
      @JsonProperty("withdrawal_reason") String withdrawalReason, @JsonProperty("created_at") Instant createdAt) {}
}
