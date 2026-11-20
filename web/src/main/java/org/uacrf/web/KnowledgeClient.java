package org.uacrf.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
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

  ItemVersion stage(String token, String key, StageRequest request) {
    ItemVersion receipt = restClient.post().uri("/v1/knowledge-items:idempotent")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
        .body(request).retrieve().body(ItemVersion.class);
    if (!valid(receipt, request.itemId(), null, request.classification())
        || !request.version().equals(receipt.version()) || !request.title().equals(receipt.title())
        || !Objects.equals(request.sourceRef(), receipt.sourceRef())
        || request.classification() != receipt.classification()
        || !request.allowedPurposes().equals(receipt.allowedPurposes())
        || !request.targetWorkloadIds().equals(receipt.targetWorkloadIds())
        || !request.reviewDeadline().equals(receipt.reviewDeadline())
        || !request.reason().equals(receipt.reason())
        || !sha256(request.content()).equals(receipt.sourceDigest())
        || receipt.state() != State.CANDIDATE || receipt.revision() != 1
        || receipt.reviewedBy() != null || receipt.reviewReason() != null
        || receipt.withdrawnBy() != null || receipt.withdrawalReason() != null) {
      throw new IllegalStateException("S08 returned an incomplete or mismatched staging receipt.");
    }
    return receipt;
  }

  CandidateContent candidate(String token, String itemId, String version, Classification classification) {
    CandidateContent candidate = restClient.get()
        .uri(uri -> uri.path("/v1/knowledge-items/{id}/versions/{version}:candidate-content").build(itemId, version))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve().body(CandidateContent.class);
    if (candidate == null || !valid(candidate.metadata(), itemId, null, classification)
        || !version.equals(candidate.metadata().version()) || candidate.metadata().classification() != classification
        || candidate.metadata().state() != State.CANDIDATE || candidate.content() == null
        || !sha256(candidate.content()).equals(candidate.metadata().sourceDigest())) {
      throw new IllegalStateException("S08 returned an invalid or mismatched candidate.");
    }
    return candidate;
  }

  ItemVersion review(String token, String itemId, BoundReviewRequest request, Classification classification) {
    ItemVersion receipt = restClient.post()
        .uri(uri -> uri.path("/v1/knowledge-items/{id}:review-bound").build(itemId))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body(request).retrieve().body(ItemVersion.class);
    State expectedState = request.decision().equals("APPROVE") ? State.APPROVED : State.REJECTED;
    if (!valid(receipt, itemId, null, classification)
        || !request.expectedVersion().equals(receipt.version())
        || !request.expectedSourceDigest().equals(receipt.sourceDigest())
        || receipt.classification() != classification || receipt.state() != expectedState
        || receipt.revision() != request.expectedRevision() + 1
        || !present(receipt.reviewedBy()) || receipt.reviewedBy().equals(receipt.proposedBy())
        || !request.reason().equals(receipt.reviewReason())) {
      throw new IllegalStateException("S08 returned an invalid or mismatched review receipt.");
    }
    return receipt;
  }

  ItemVersion withdraw(String token, String itemId, BoundWithdrawRequest request, Classification classification) {
    ItemVersion receipt = restClient.post()
        .uri(uri -> uri.path("/v1/knowledge-items/{id}:withdraw-bound").build(itemId))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body(request).retrieve().body(ItemVersion.class);
    if (!valid(receipt, itemId, null, classification)
        || !request.expectedVersion().equals(receipt.version())
        || !request.expectedSourceDigest().equals(receipt.sourceDigest())
        || receipt.classification() != classification || receipt.state() != State.WITHDRAWN
        || receipt.revision() != request.expectedRevision() + 1
        || !present(receipt.withdrawnBy()) || !request.reason().equals(receipt.withdrawalReason())) {
      throw new IllegalStateException("S08 returned an invalid or mismatched withdrawal receipt.");
    }
    return receipt;
  }

  Retrieval retrieve(String token, String key, RetrievalRequest request) {
    Retrieval receipt = restClient.post().uri("/v1/retrievals:idempotent")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
        .body(request).retrieve().body(Retrieval.class);
    if (!validRetrieval(receipt, request.maxClassification())
        || !Objects.equals(request.caseId(), receipt.caseId())
        || !request.purpose().equals(receipt.purpose()) || !request.query().equals(receipt.query())
        || request.maxClassification() != receipt.maxClassification()
        || receipt.results().size() > request.maxResults()) {
      throw new IllegalStateException("S08 returned an invalid or mismatched retrieval.");
    }
    return receipt;
  }

  Retrieval readRetrieval(String token, String id, Classification ceiling) {
    Retrieval receipt = restClient.get()
        .uri(uri -> uri.path("/v1/retrievals/{id}").build(id))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve().body(Retrieval.class);
    if (!validRetrieval(receipt, ceiling) || !id.equals(receipt.retrievalId())) {
      throw new IllegalStateException("S08 returned invalid or mismatched retrieval metadata.");
    }
    return receipt;
  }

  private static boolean validRetrieval(Retrieval receipt, Classification ceiling) {
    return receipt != null && receipt.retrievalId() != null
        && receipt.retrievalId().matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        && (receipt.caseId() == null || receipt.caseId().matches(IDENTIFIER))
        && receipt.purpose() != null && receipt.purpose().matches(IDENTIFIER)
        && present(receipt.query()) && receipt.query().length() <= 2000
        && receipt.maxClassification() != null && receipt.maxClassification().ordinal() <= ceiling.ordinal()
        && receipt.manifestDigest() != null && receipt.manifestDigest().matches("^[0-9a-f]{64}$")
        && receipt.createdAt() != null && receipt.results() != null && receipt.results().size() <= 10
        && validCaseBinding(receipt)
        && receipt.results().stream().allMatch(result -> validResult(result, receipt.purpose(), receipt.maxClassification()));
  }

  private static boolean validCaseBinding(Retrieval receipt) {
    if ("NOT_VERIFIED".equals(receipt.caseBinding())) return receipt.caseDigest() == null && receipt.workloadId() == null;
    return "VERIFIED".equals(receipt.caseBinding()) && receipt.caseId() != null
        && receipt.caseDigest() != null && receipt.caseDigest().matches("^[0-9a-f]{64}$")
        && receipt.workloadId() != null && receipt.workloadId().matches(IDENTIFIER);
  }

  private static boolean validResult(RetrievalResult result, String purpose, Classification ceiling) {
    return result != null && result.itemId() != null && result.itemId().matches(IDENTIFIER)
        && result.version() != null && result.version().matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}$")
        && result.chunkIndex() != null && result.chunkIndex() >= 0
        && result.startOffset() != null && result.startOffset() >= 0
        && result.endOffset() != null && result.endOffset() > result.startOffset()
        && result.endOffset() - result.startOffset() <= 1000
        && result.sourceDigest() != null && result.sourceDigest().matches("^[0-9a-f]{64}$")
        && result.excerptDigest() != null && result.excerptDigest().matches("^[0-9a-f]{64}$")
        && validIdentifiers(result.allowedPurposes(), false) && result.allowedPurposes().contains(purpose)
        && result.classification() != null && result.classification().ordinal() <= ceiling.ordinal()
        && result.score() != null && result.score() > 0
        && (result.excerpt() == null || (result.excerpt().codePointCount(0, result.excerpt().length())
            == result.endOffset() - result.startOffset() && sha256(result.excerpt()).equals(result.excerptDigest())));
  }

  private static String sha256(String content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the runtime.", error);
    }
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

  record StageRequest(@JsonProperty("item_id") String itemId, String version, String title,
      @JsonProperty("source_ref") String sourceRef, String content, Classification classification,
      @JsonProperty("allowed_purposes") List<String> allowedPurposes,
      @JsonProperty("target_workload_ids") List<String> targetWorkloadIds,
      @JsonProperty("review_deadline") Instant reviewDeadline, String reason) {}

  record CandidateContent(ItemVersion metadata, String content) {}

  record BoundReviewRequest(String decision, @JsonProperty("expected_revision") long expectedRevision,
      @JsonProperty("expected_version") String expectedVersion,
      @JsonProperty("expected_source_digest") String expectedSourceDigest, String reason) {}

  record BoundWithdrawRequest(@JsonProperty("expected_revision") long expectedRevision,
      @JsonProperty("expected_version") String expectedVersion,
      @JsonProperty("expected_source_digest") String expectedSourceDigest, String reason) {}

  record RetrievalRequest(@JsonProperty("case_id") String caseId, String purpose,
      @JsonProperty("max_classification") Classification maxClassification, String query,
      @JsonProperty("max_results") int maxResults) {}

  record Retrieval(@JsonProperty("retrieval_id") String retrievalId, @JsonProperty("case_id") String caseId,
      @JsonProperty("case_binding") String caseBinding, @JsonProperty("case_digest") String caseDigest,
      @JsonProperty("workload_id") String workloadId, String purpose,
      @JsonProperty("max_classification") Classification maxClassification, String query,
      @JsonProperty("created_at") Instant createdAt, @JsonProperty("manifest_digest") String manifestDigest,
      List<RetrievalResult> results) {}

  record RetrievalResult(@JsonProperty("item_id") String itemId, String version,
      @JsonProperty("chunk_index") Integer chunkIndex, @JsonProperty("start_offset") Integer startOffset,
      @JsonProperty("end_offset") Integer endOffset, @JsonProperty("source_digest") String sourceDigest,
      @JsonProperty("excerpt_digest") String excerptDigest, @JsonProperty("allowed_purposes") List<String> allowedPurposes,
      Classification classification, Integer score, String excerpt) {}

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
