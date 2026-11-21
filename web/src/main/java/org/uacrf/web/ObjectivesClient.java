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

  DecisionRecordPage decisionRecords(String token, String id, String cursor) {
    DecisionRecordPage result = restClient.get().uri(uri -> {
      var request = uri.path("/v1/objectives/{id}/decision-records").queryParam("pageSize", 25);
      if (cursor != null && !cursor.isBlank()) request.queryParam("cursor", cursor);
      return request.build(id);
    }).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(DecisionRecordPage.class);
    if (result == null || !id.equals(result.objectiveId()) || result.items() == null
        || !Boolean.TRUE.equals(result.historyMayBeIncomplete()) || result.items().size() > 25
        || (result.nextCursor() != null && result.nextCursor().isBlank())
        || result.items().stream().anyMatch(item -> item == null || item.revision() == null
            || item.revision() < 2 || !present(item.contentDigest()) || !present(item.decision())
            || !present(item.reason()) || !present(item.decidedBy()) || !present(item.decidedAt()))) {
      throw new IllegalStateException("S05 returned invalid decision records.");
    }
    return result;
  }

  ImportReceipt previewImport(String token, String key, ImportPreview request) {
    ImportReceipt receipt = restClient.post().uri("/v1/objective-imports:preview")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
        .body(request).retrieve().body(ImportReceipt.class);
    if (!validImportReceipt(receipt) || !"NOT_ESTABLISHED".equals(receipt.runtimeVerification())) {
      throw new IllegalStateException("S05 returned an invalid import preview receipt.");
    }
    return receipt;
  }

  ImportReceipt importReceipt(String token, String id) {
    ImportReceipt receipt = restClient.get().uri("/v1/objective-imports/{id}", id)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(ImportReceipt.class);
    if (!validImportReceipt(receipt) || !id.equals(receipt.receiptId())) {
      throw new IllegalStateException("S05 returned an invalid import preview receipt.");
    }
    return receipt;
  }

  ImportSample inspectImportSample(String token, String id) {
    if (!validUuid(id)) throw new IllegalStateException("Invalid import receipt ID.");
    ImportSample sample = restClient.get().uri("/v1/objective-imports/{id}/sample-source", id)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(ImportSample.class);
    if (sample == null || !present(sample.connectionId()) || !present(sample.bindingId())
        || !present(sample.targetWorkloadId()) || !present(sample.sourceSystem())
        || !sample.sourceSystem().startsWith("sample:")
        || !present(sample.externalObjectiveId()) || !sample.externalObjectiveId().startsWith("sample:")
        || !present(sample.sourceVersion()) || !sample.sourceVersion().startsWith("sample-")
        || sample.connectionRevision() == null || sample.connectionRevision() < 1
        || sample.bindingRevision() == null || sample.bindingRevision() < 1
        || !present(sample.payloadDigest()) || !sample.payloadDigest().matches("[a-f0-9]{64}")
        || !"SYNTHETIC_UNVERIFIED".equals(sample.verification()) || !valid(sample.definition())
        || sample.definitionFingerprint() == null
        || !"OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1".equals(sample.definitionFingerprint().version())
        || !present(sample.definitionFingerprint().digest())
        || !sample.definitionFingerprint().digest().matches("[a-f0-9]{64}")) {
      throw new IllegalStateException("S05 returned an invalid synthetic source inspection.");
    }
    return sample;
  }

  private static boolean valid(ImportDefinition value) {
    return value != null && present(value.metric()) && present(value.unit()) && present(value.window())
        && present(value.measurementRef()) && value.target() != null && value.target().signum() >= 0
        && present(value.comparison()) && present(value.measurementKind())
        && List.of("AT_MOST", "AT_LEAST", "EQUAL").contains(value.comparison())
        && List.of("THRESHOLD", "PERCENTILE", "RATIO", "RECOVERY_TIME", "OUTCOME").contains(value.measurementKind())
        && ("PERCENTILE".equals(value.measurementKind())
            ? value.percentile() != null && value.percentile().signum() > 0
                && value.percentile().compareTo(BigDecimal.valueOf(100)) <= 0
            : value.percentile() == null)
        && (!"percent".equals(value.unit()) || value.target().compareTo(BigDecimal.valueOf(100)) <= 0);
  }

  ImportPage listImports(String token, String cursor) {
    ImportPage page = restClient.get().uri(uri -> {
      var request = uri.path("/v1/objective-imports").queryParam("pageSize", 25);
      if (cursor != null && !cursor.isBlank()) request.queryParam("cursor", cursor);
      return request.build();
    }).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(ImportPage.class);
    if (page == null || page.items() == null || page.items().size() > 25
        || (page.nextCursor() != null && page.nextCursor().isBlank())
        || page.items().stream().anyMatch(item -> item == null || !validUuid(item.receiptId())
            || !"PENDING".equals(item.status()) || !present(item.targetWorkloadId())
            || !present(item.createdAt()))) {
      throw new IllegalStateException("S05 returned an invalid import request page.");
    }
    return page;
  }

  private static boolean validImportReceipt(ImportReceipt receipt) {
    if (receipt == null || !present(receipt.receiptId()) || !"PENDING".equals(receipt.status())
        || !("/v1/objective-imports/" + receipt.receiptId()).equals(receipt.readResource())) return false;
    return validUuid(receipt.receiptId());
  }

  static boolean validUuid(String value) {
    if (value == null) return false;
    try {
      return java.util.UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException error) {
      return false;
    }
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

  Objective activate(String token, String id, String key, long revision, String digest,
      String activatorId, String reviewerId, String reason) {
    Objective result = restClient.post().uri("/v1/objectives/{id}:idempotent-activate", id)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
        .body(new Submission(revision, digest)).retrieve().body(Objective.class);
    if (!valid(result) || !id.equals(result.id()) || !digest.equals(result.contentDigest())
        || result.revision() <= revision || !List.of("ACTIVE", "SUPERSEDED").contains(result.state())
        || !activatorId.equals(result.activatedBy()) || !reviewerId.equals(result.decidedBy())
        || !reason.equals(result.decisionReason())) {
      throw new IllegalStateException("S05 returned an invalid activation result.");
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

  record DecisionRecord(Long revision, @JsonProperty("content_digest") String contentDigest,
      String decision, String reason, @JsonProperty("decided_by") String decidedBy,
      @JsonProperty("decided_at") String decidedAt) {}

  record DecisionRecordPage(@JsonProperty("objective_id") String objectiveId, List<DecisionRecord> items,
      @JsonProperty("next_cursor") String nextCursor,
      @JsonProperty("history_may_be_incomplete") Boolean historyMayBeIncomplete) {}

  record ImportPreview(@JsonProperty("connection_id") String connectionId,
      @JsonProperty("external_objective_id") String externalObjectiveId,
      @JsonProperty("target_workload_id") String targetWorkloadId,
      @JsonProperty("mapping_revision") Long mappingRevision,
      @JsonProperty("expected_local_revision") Long expectedLocalRevision) {}

  record ImportReceipt(@JsonProperty("receipt_id") String receiptId, String status,
      @JsonProperty("read_resource") String readResource,
      @JsonProperty("runtime_verification") String runtimeVerification) {}

  record ImportSample(@JsonProperty("connection_id") String connectionId,
      @JsonProperty("connection_revision") Long connectionRevision,
      @JsonProperty("binding_id") String bindingId, @JsonProperty("binding_revision") Long bindingRevision,
      @JsonProperty("target_workload_id") String targetWorkloadId,
      @JsonProperty("source_system") String sourceSystem,
      @JsonProperty("external_objective_id") String externalObjectiveId,
      @JsonProperty("source_version") String sourceVersion, @JsonProperty("payload_digest") String payloadDigest,
      String verification, ImportDefinition definition,
      @JsonProperty("definition_fingerprint") DefinitionFingerprint definitionFingerprint) {}

  record DefinitionFingerprint(String version, String digest) {}

  record ImportDefinition(String metric, BigDecimal target, String unit, String window,
      @JsonProperty("measurement_ref") String measurementRef, String comparison,
      @JsonProperty("measurement_kind") String measurementKind, BigDecimal percentile) {}

  record ImportSummary(@JsonProperty("receipt_id") String receiptId, String status,
      @JsonProperty("target_workload_id") String targetWorkloadId,
      @JsonProperty("created_at") String createdAt) {}

  record ImportPage(List<ImportSummary> items, @JsonProperty("next_cursor") String nextCursor) {}

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
