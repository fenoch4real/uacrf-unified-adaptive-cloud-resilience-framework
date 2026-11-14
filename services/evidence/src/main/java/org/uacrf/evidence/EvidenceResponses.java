package org.uacrf.evidence;

import java.util.List;

final class EvidenceResponses {
  private EvidenceResponses() {}

  record Page<T>(List<T> items, String nextCursor) {}

  record SubmitResult(String id, String status, String reason, String qualificationState) {}

  record Evidence(
      String id,
      String sourceClass,
      String sourceRef,
      long sequence,
      String observedAt,
      String kind,
      String payloadDigest,
      String submittedBy,
      String qualificationState,
      long revision,
      String reviewedBy,
      String reviewReason) {}

  record QualificationReceipt(
      String evidenceId,
      String purpose,
      String qualificationState,
      boolean usableAsEvidence,
      boolean digestMatches,
      boolean withinCutoff,
      String payloadDigest,
      long revision,
      String reason) {}

  record InboxResult(String eventId, String status, String reason) {}

  record Error(String code, String message, String requestId) {}
}
