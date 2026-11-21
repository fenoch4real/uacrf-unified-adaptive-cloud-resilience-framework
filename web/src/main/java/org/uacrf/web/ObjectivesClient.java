package org.uacrf.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

  SampleCaptureReceipt captureImportSample(String token, String id, String idempotencyKey) {
    if (!validUuid(id) || !validUuid(idempotencyKey)) {
      throw new IllegalStateException("Invalid import receipt or capture key.");
    }
    SampleCaptureReceipt receipt = restClient.post().uri("/v1/objective-imports/{id}/sample-captures", id)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", idempotencyKey).retrieve().body(SampleCaptureReceipt.class);
    if (receipt == null || !validUuid(receipt.captureId()) || !id.equals(receipt.receiptId())
        || !present(receipt.capturedAt()) || !"SYNTHETIC_UNVERIFIED".equals(receipt.verification())) {
      throw new IllegalStateException("S05 returned an invalid synthetic capture receipt.");
    }
    try {
      java.time.Instant.parse(receipt.capturedAt());
    } catch (java.time.format.DateTimeParseException error) {
      throw new IllegalStateException("S05 returned an invalid synthetic capture time.", error);
    }
    return receipt;
  }

  RetainedSampleCapture getImportSampleCapture(String token, String id, String captureId) {
    if (!validUuid(id) || !validUuid(captureId)) throw new IllegalStateException("Invalid capture identifiers.");
    RetainedSampleCapture capture = restClient.get()
        .uri("/v1/objective-imports/{id}/sample-captures/{captureId}", id, captureId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(RetainedSampleCapture.class);
    if (capture == null || !id.equals(capture.receiptId()) || !captureId.equals(capture.captureId())
        || !present(capture.capturedAt()) || !present(capture.actorId())
        || !digest(capture.requestDigest()) || !present(capture.connectionId())
        || capture.connectionRevision() == null || capture.connectionRevision() < 1
        || !present(capture.bindingId()) || capture.bindingRevision() == null || capture.bindingRevision() < 1
        || !present(capture.targetWorkloadId()) || !present(capture.sourceSystem())
        || !capture.sourceSystem().startsWith("sample:") || !present(capture.externalObjectiveId())
        || !capture.externalObjectiveId().startsWith("sample:") || !present(capture.sourceVersion())
        || !capture.sourceVersion().startsWith("sample-") || !present(capture.payload())
        || capture.payload().getBytes(StandardCharsets.UTF_8).length > 16384
        || !digest(capture.payloadDigest()) || !digest(capture.definitionFingerprint())
        || !"OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1".equals(capture.definitionFingerprintVersion())
        || !"SYNTHETIC_UNVERIFIED".equals(capture.verification())) {
      throw new IllegalStateException("S05 returned an invalid retained synthetic capture.");
    }
    try {
      java.time.Instant.parse(capture.capturedAt());
      String calculated = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(capture.payload().getBytes(StandardCharsets.UTF_8)));
      if (!calculated.equals(capture.payloadDigest())) {
        throw new IllegalStateException("Retained capture payload digest does not match.");
      }
    } catch (java.time.format.DateTimeParseException | NoSuchAlgorithmException error) {
      throw new IllegalStateException("Invalid retained capture timestamp or digest algorithm.", error);
    }
    return capture;
  }

  private static boolean digest(String value) {
    return value != null && value.matches("[a-f0-9]{64}");
  }

  SampleCapturePage listImportSampleCaptures(String token, String id, String cursor) {
    if (!validUuid(id)) throw new IllegalStateException("Invalid import receipt ID.");
    SampleCapturePage page = restClient.get().uri(uri -> {
      var request = uri.path("/v1/objective-imports/{id}/sample-captures").queryParam("pageSize", 25);
      if (cursor != null && !cursor.isBlank()) request.queryParam("cursor", cursor);
      return request.build(id);
    }).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(SampleCapturePage.class);
    if (page == null || page.items() == null || page.items().size() > 25
        || (page.nextCursor() != null && page.nextCursor().isBlank())
        || page.items().stream().anyMatch(item -> item == null || !validUuid(item.captureId())
            || !present(item.capturedAt()) || !"SYNTHETIC_UNVERIFIED".equals(item.verification()))
        || page.items().stream().map(SampleCaptureSummary::captureId).distinct().count() != page.items().size()) {
      throw new IllegalStateException("S05 returned an invalid synthetic capture page.");
    }
    try {
      for (SampleCaptureSummary item : page.items()) java.time.Instant.parse(item.capturedAt());
    } catch (java.time.format.DateTimeParseException error) {
      throw new IllegalStateException("S05 returned an invalid synthetic capture time.", error);
    }
    return page;
  }

  NormalizationRehearsal rehearseImportNormalization(
      String token, String id, String captureId, String targetUnit, boolean allowFractionToPercent) {
    if (!validUuid(id) || !validUuid(captureId) || !present(targetUnit) || targetUnit.length() > 80) {
      throw new IllegalStateException("Invalid rehearsal parameters.");
    }
    NormalizationRehearsal result = restClient.get().uri(uri -> uri
        .path("/v1/objective-imports/{id}/sample-captures/{captureId}/normalization-rehearsal")
        .queryParam("targetUnit", targetUnit).queryParam("allowFractionToPercent", allowFractionToPercent)
        .build(id, captureId))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(NormalizationRehearsal.class);
    if (result == null || !id.equals(result.receiptId()) || !captureId.equals(result.captureId())
        || !"SYNTHETIC_UNVERIFIED".equals(result.verification()) || !"UNAPPROVED_REHEARSAL".equals(result.status())
        || !valid(result.sourceDefinition()) || !valid(result.normalizedDefinition())
        || !validFingerprint(result.sourceFingerprint()) || !validFingerprint(result.normalizedFingerprint())
        || !targetUnit.equals(result.normalizedDefinition().unit())
        || !result.sourceDefinition().metric().equals(result.normalizedDefinition().metric())
        || !result.sourceDefinition().window().equals(result.normalizedDefinition().window())
        || !result.sourceDefinition().measurementRef().equals(result.normalizedDefinition().measurementRef())
        || !result.sourceDefinition().comparison().equals(result.normalizedDefinition().comparison())
        || !result.sourceDefinition().measurementKind().equals(result.normalizedDefinition().measurementKind())
        || !java.util.Objects.equals(result.sourceDefinition().percentile(), result.normalizedDefinition().percentile())) {
      throw new IllegalStateException("S05 returned an invalid normalization rehearsal.");
    }
    return result;
  }

  SyntheticMappingProposal proposeSyntheticMapping(String token, String id, String captureId, String key,
      SyntheticMappingRequest request) {
    if (!validUuid(id) || !validUuid(captureId) || !validUuid(key)) {
      throw new IllegalStateException("Invalid synthetic mapping proposal parameters.");
    }
    SyntheticMappingProposal result = restClient.post()
        .uri("/v1/objective-imports/{id}/sample-captures/{captureId}/mapping-proposals", id, captureId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
        .body(request).retrieve().body(SyntheticMappingProposal.class);
    if (!validProposal(result, id, captureId) || !request.targetUnit().equals(result.targetUnit())
        || request.allowFractionToPercent() != result.allowFractionToPercent()
        || !request.reason().equals(result.reason())) {
      throw new IllegalStateException("S05 returned an invalid synthetic mapping proposal.");
    }
    return result;
  }

  SyntheticMappingProposal syntheticMappingProposal(String token, String id, String captureId, String proposalId) {
    if (!validUuid(id) || !validUuid(captureId) || !validUuid(proposalId)) {
      throw new IllegalStateException("Invalid synthetic mapping proposal identifiers.");
    }
    SyntheticMappingProposal result = restClient.get()
        .uri("/v1/objective-imports/{id}/sample-captures/{captureId}/mapping-proposals/{proposalId}",
            id, captureId, proposalId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(SyntheticMappingProposal.class);
    if (!validProposal(result, id, captureId) || !proposalId.equals(result.proposalId())) {
      throw new IllegalStateException("S05 returned an invalid synthetic mapping proposal.");
    }
    return result;
  }

  private static boolean validProposal(SyntheticMappingProposal value, String id, String captureId) {
    if (value == null || !validUuid(value.proposalId()) || !id.equals(value.receiptId())
        || !captureId.equals(value.captureId()) || !present(value.proposedBy()) || !present(value.targetUnit())
        || value.targetUnit().length() > 80 || value.allowFractionToPercent() == null
        || !present(value.reason()) || value.reason().length() > 1000
        || !"OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1".equals(value.fingerprintVersion())
        || !digest(value.sourceFingerprint()) || !digest(value.normalizedFingerprint())
        || !"UNAPPROVED".equals(value.status()) || !"SYNTHETIC_UNVERIFIED".equals(value.verification())
        || !present(value.proposedAt())
        || !"SYNTHETIC_MAPPING_PROPOSAL_SHA256_V1".equals(value.proposalDigestVersion())
        || !digest(value.proposalDigest())) return false;
    try {
      java.time.Instant.parse(value.proposedAt());
      return true;
    } catch (java.time.format.DateTimeParseException error) {
      return false;
    }
  }

  SyntheticMappingReviews syntheticMappingReviews(String token, SyntheticMappingProposal proposal) {
    SyntheticMappingReviews result = restClient.get()
        .uri("/v1/objective-imports/{id}/sample-captures/{captureId}/mapping-proposals/{proposalId}/reviews",
            proposal.receiptId(), proposal.captureId(), proposal.proposalId())
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(SyntheticMappingReviews.class);
    if (result == null || result.items() == null || result.items().size() > 1
        || result.items().stream().anyMatch(review -> !validSyntheticReview(review, proposal))) {
      throw new IllegalStateException("S05 returned invalid synthetic mapping reviews.");
    }
    return result;
  }

  SyntheticMappingReview reviewSyntheticMapping(String token, SyntheticMappingProposal proposal,
      String key, SyntheticMappingReviewRequest request) {
    if (!validUuid(key)) throw new IllegalStateException("Invalid synthetic review idempotency key.");
    SyntheticMappingReview result = restClient.post()
        .uri("/v1/objective-imports/{id}/sample-captures/{captureId}/mapping-proposals/{proposalId}/reviews",
            proposal.receiptId(), proposal.captureId(), proposal.proposalId())
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
        .body(request).retrieve().body(SyntheticMappingReview.class);
    if (!validSyntheticReview(result, proposal) || !request.decision().equals(result.decision())
        || !request.reason().equals(result.reason())) {
      throw new IllegalStateException("S05 returned an invalid synthetic mapping review.");
    }
    return result;
  }

  private static boolean validSyntheticReview(SyntheticMappingReview value, SyntheticMappingProposal proposal) {
    if (value == null || !validUuid(value.reviewId()) || !proposal.receiptId().equals(value.receiptId())
        || !proposal.captureId().equals(value.captureId()) || !proposal.proposalId().equals(value.proposalId())
        || !proposal.proposalDigestVersion().equals(value.proposalDigestVersion())
        || !proposal.proposalDigest().equals(value.proposalDigest())
        || !present(value.decision()) || !List.of("APPROVE", "REJECT").contains(value.decision())
        || !present(value.reviewerId())
        || !present(value.reason()) || value.reason().length() > 1000
        || !"SYNTHETIC_UNVERIFIED".equals(value.verification()) || !present(value.reviewedAt())) return false;
    try {
      java.time.Instant.parse(value.reviewedAt());
      return true;
    } catch (java.time.format.DateTimeParseException error) {
      return false;
    }
  }

  private static boolean validFingerprint(DefinitionFingerprint value) {
    return value != null && "OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1".equals(value.version()) && digest(value.digest());
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

  record SampleCaptureReceipt(@JsonProperty("capture_id") String captureId,
      @JsonProperty("receipt_id") String receiptId, @JsonProperty("captured_at") String capturedAt,
      String verification) {}

  record RetainedSampleCapture(@JsonProperty("capture_id") String captureId,
      @JsonProperty("receipt_id") String receiptId, @JsonProperty("captured_at") String capturedAt,
      @JsonProperty("actor_id") String actorId, @JsonProperty("request_digest") String requestDigest,
      @JsonProperty("connection_id") String connectionId, @JsonProperty("connection_revision") Long connectionRevision,
      @JsonProperty("binding_id") String bindingId, @JsonProperty("binding_revision") Long bindingRevision,
      @JsonProperty("target_workload_id") String targetWorkloadId, @JsonProperty("source_system") String sourceSystem,
      @JsonProperty("external_objective_id") String externalObjectiveId, @JsonProperty("source_version") String sourceVersion,
      String payload, @JsonProperty("payload_digest") String payloadDigest,
      @JsonProperty("definition_fingerprint_version") String definitionFingerprintVersion,
      @JsonProperty("definition_fingerprint") String definitionFingerprint, String verification) {}

  record SampleCaptureSummary(@JsonProperty("capture_id") String captureId,
      @JsonProperty("captured_at") String capturedAt, String verification) {}

  record SampleCapturePage(List<SampleCaptureSummary> items, @JsonProperty("next_cursor") String nextCursor) {}

  record NormalizationRehearsal(@JsonProperty("capture_id") String captureId,
      @JsonProperty("receipt_id") String receiptId,
      @JsonProperty("source_definition") ImportDefinition sourceDefinition,
      @JsonProperty("source_fingerprint") DefinitionFingerprint sourceFingerprint,
      @JsonProperty("normalized_definition") ImportDefinition normalizedDefinition,
      @JsonProperty("normalized_fingerprint") DefinitionFingerprint normalizedFingerprint,
      String verification, String status) {}

  record SyntheticMappingRequest(@JsonProperty("target_unit") String targetUnit,
      @JsonProperty("allow_fraction_to_percent") boolean allowFractionToPercent, String reason) {}

  record SyntheticMappingProposal(@JsonProperty("proposal_id") String proposalId,
      @JsonProperty("receipt_id") String receiptId, @JsonProperty("capture_id") String captureId,
      @JsonProperty("proposed_by") String proposedBy, @JsonProperty("target_unit") String targetUnit,
      @JsonProperty("allow_fraction_to_percent") Boolean allowFractionToPercent, String reason,
      @JsonProperty("fingerprint_version") String fingerprintVersion,
      @JsonProperty("source_fingerprint") String sourceFingerprint,
      @JsonProperty("normalized_fingerprint") String normalizedFingerprint,
      String status, String verification, @JsonProperty("proposed_at") String proposedAt,
      @JsonProperty("proposal_digest_version") String proposalDigestVersion,
      @JsonProperty("proposal_digest") String proposalDigest) {}

  record SyntheticMappingReviewRequest(String decision, String reason,
      @JsonProperty("expected_proposal_digest_version") String expectedProposalDigestVersion,
      @JsonProperty("expected_proposal_digest") String expectedProposalDigest) {}

  record SyntheticMappingReview(@JsonProperty("review_id") String reviewId,
      @JsonProperty("receipt_id") String receiptId, @JsonProperty("capture_id") String captureId,
      @JsonProperty("proposal_id") String proposalId, @JsonProperty("reviewer_id") String reviewerId,
      @JsonProperty("proposal_digest_version") String proposalDigestVersion,
      @JsonProperty("proposal_digest") String proposalDigest, String decision, String reason,
      String verification, @JsonProperty("reviewed_at") String reviewedAt) {}

  record SyntheticMappingReviews(List<SyntheticMappingReview> items) {}

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
