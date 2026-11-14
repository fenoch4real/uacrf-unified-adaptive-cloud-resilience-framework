package org.uacrf.knowledge;

import java.util.List;
import org.uacrf.knowledge.KnowledgeRequests.Classification;

final class KnowledgeResponses {
  private KnowledgeResponses() {}

  record ItemVersion(
      String itemId,
      String version,
      String title,
      String sourceRef,
      Classification classification,
      List<String> allowedPurposes,
      List<String> targetWorkloadIds,
      String reviewDeadline,
      String reason,
      String sourceDigest,
      String state,
      long revision,
      String proposedBy,
      String reviewedBy,
      String reviewReason,
      String withdrawnBy,
      String withdrawalReason,
      String createdAt) {}

  record RetrievalResult(
      String itemId,
      String version,
      int chunkIndex,
      int startOffset,
      int endOffset,
      String sourceDigest,
      String excerptDigest,
      List<String> allowedPurposes,
      Classification classification,
      int score,
      String excerpt) {}

  record Retrieval(
      String retrievalId,
      String caseId,
      String caseBinding,
      String caseDigest,
      String workloadId,
      String purpose,
      Classification maxClassification,
      String query,
      String createdAt,
      String manifestDigest,
      List<RetrievalResult> results) {}

  record ReceiptResult(
      String itemId,
      String version,
      int chunkIndex,
      int startOffset,
      int endOffset,
      String sourceDigest,
      String excerptDigest,
      List<String> allowedPurposes,
      Classification classification,
      int score) {}

  record RetrievalReceipt(
      String retrievalId,
      String requestedCaseId,
      String caseId,
      String caseBinding,
      String caseDigest,
      String workloadId,
      String purpose,
      Classification classification,
      String manifestDigest,
      String query,
      String createdAt,
      List<ReceiptResult> results,
      boolean eligible,
      String reason) {}

  record Error(String code, String message, String requestId) {}
}
