package org.uacrf.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class ObjectivesClient {
  private final RestClient restClient;

  ObjectivesClient(@Qualifier("objectivesRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  ObjectivePage list(String token, String cursor, String workloadId) {
    ObjectivePage page = restClient.get()
        .uri(uri -> {
          var request = uri.path("/v1/objectives").queryParam("pageSize", 25);
          if (workloadId != null) request.queryParam("workloadId", workloadId);
          if (cursor != null && !cursor.isBlank()) request.queryParam("cursor", cursor);
          return request.build();
        })
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve().body(ObjectivePage.class);
    if (page == null || page.items() == null
        || (page.nextCursor() != null && page.nextCursor().isBlank())
        || page.items().stream().anyMatch(item -> !valid(item)
            || (workloadId != null && !workloadId.equals(item.workloadId())))) {
      throw new IllegalStateException("S05 returned an incomplete objective page.");
    }
    return page;
  }

  Objective createLocal(String token, String key, LocalObjective request) {
    Objective result = restClient.post().uri("/v1/objectives:local-idempotent-create")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", key)
        .body(request).retrieve().body(Objective.class);
    if (!valid(result) || !request.id().equals(result.id())
        || !request.workloadId().equals(result.workloadId())
        || !request.title().equals(result.title()) || !request.metric().equals(result.metric())
        || !request.comparator().equals(result.comparator())
        || request.target().compareTo(result.target()) != 0
        || !request.unit().equals(result.unit()) || !request.windowSeconds().equals(result.windowSeconds())
        || !"LOCAL".equals(result.origin()) || result.sourceRef() != null
        || result.sourceDefinitionId() != null || result.sourceRevision() != null
        || result.sourceVerifiedAt() != null) {
      throw new IllegalStateException("S05 returned an objective that does not match the request.");
    }
    return result;
  }

  Objective get(String token, String id) {
    Objective result = restClient.get().uri("/v1/objectives/{id}", id)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(Objective.class);
    if (!valid(result) || !id.equals(result.id())) {
      throw new IllegalStateException("S05 returned an invalid objective.");
    }
    return result;
  }

  DecisionHistory decisionHistory(String token, String id) {
    DecisionHistory result = restClient.get().uri("/v1/objectives/{id}/decision-history", id)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(DecisionHistory.class);
    if (result == null || !id.equals(result.objectiveId()) || result.items() == null
        || result.moreRecorded() == null || result.items().size() > 25
        || result.items().stream().anyMatch(event -> event == null || event.revision() == null
            || event.revision() < 1 || !present(event.recordedAt()))) {
      throw new IllegalStateException("S05 returned invalid decision history.");
    }
    return result;
  }

  Objective submit(String token, String id, String key, long revision, String digest) {
    Objective result = restClient.post().uri("/v1/objectives/{id}:idempotent-submit", id)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
        .body(new Submission(revision, digest)).retrieve().body(Objective.class);
    if (!valid(result) || !id.equals(result.id()) || !digest.equals(result.contentDigest())
        || result.revision() <= revision || "DRAFT".equals(result.state())) {
      throw new IllegalStateException("S05 returned an invalid submission result.");
    }
    return result;
  }

  Objective decide(String token, String id, String key, long revision, String digest,
      String decision, String reason, String reviewerId) {
    Objective result = restClient.post().uri("/v1/objectives/{id}:idempotent-decide", id)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
        .body(new Review(decision, reason, revision, digest)).retrieve().body(Objective.class);
    boolean matchingState = result != null && result.state() != null && ("APPROVE".equals(decision)
        ? List.of("APPROVED", "ACTIVE", "SUPERSEDED").contains(result.state())
        : "REJECTED".equals(result.state()));
    if (!valid(result) || !id.equals(result.id()) || !digest.equals(result.contentDigest())
        || result.revision() <= revision || !matchingState
        || !reviewerId.equals(result.decidedBy()) || !reason.equals(result.decisionReason())) {
      throw new IllegalStateException("S05 returned an invalid review result.");
    }
    return result;
  }

  private static boolean valid(Objective item) {
    return item != null && present(item.id()) && present(item.workloadId())
        && present(item.title()) && present(item.metric()) && present(item.unit())
        && present(item.comparator()) && List.of("GTE", "LTE").contains(item.comparator())
        && present(item.origin()) && List.of("LOCAL", "IMPORTED").contains(item.origin())
        && present(item.state())
        && List.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "ACTIVE", "SUPERSEDED")
            .contains(item.state())
        && item.target() != null && item.windowSeconds() != null && item.windowSeconds() > 0
        && item.revision() != null && item.revision() > 0 && present(item.proposedBy())
        && present(item.contentDigest()) && item.contentDigest().matches("^[0-9a-f]{64}$")
        && (!"IMPORTED".equals(item.origin())
            || (item.sourceRef() != null && item.sourceDefinitionId() != null
                && item.sourceRevision() != null && present(item.sourceVerifiedAt())));
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }

  record ObjectivePage(List<Objective> items, @JsonProperty("next_cursor") String nextCursor) {}

  record DecisionEvent(Long revision, String state, @JsonProperty("actor_id") String actorId,
      @JsonProperty("recorded_at") String recordedAt) {}

  record DecisionHistory(@JsonProperty("objective_id") String objectiveId, List<DecisionEvent> items,
      @JsonProperty("more_recorded") Boolean moreRecorded) {}

  record LocalObjective(String id, @JsonProperty("workload_id") String workloadId,
      String title, String metric, String comparator, BigDecimal target, String unit,
      @JsonProperty("window_seconds") Integer windowSeconds, String origin) {}

  record Submission(@JsonProperty("expected_revision") long expectedRevision,
      @JsonProperty("expected_content_digest") String expectedContentDigest) {}

  record Review(String decision, String reason, @JsonProperty("expected_revision") long expectedRevision,
      @JsonProperty("expected_content_digest") String expectedContentDigest) {}

  record Objective(
      String id,
      @JsonProperty("workload_id") String workloadId,
      String title,
      String metric,
      String comparator,
      BigDecimal target,
      String unit,
      @JsonProperty("window_seconds") Integer windowSeconds,
      String origin,
      @JsonProperty("source_ref") String sourceRef,
      @JsonProperty("source_definition_id") String sourceDefinitionId,
      @JsonProperty("source_revision") String sourceRevision,
      @JsonProperty("source_verified_at") String sourceVerifiedAt,
      @JsonProperty("content_digest") String contentDigest,
      String state,
      Long revision,
      @JsonProperty("proposed_by") String proposedBy,
      @JsonProperty("decided_by") String decidedBy,
      @JsonProperty("decision_reason") String decisionReason,
      @JsonProperty("activated_by") String activatedBy) {}
}
