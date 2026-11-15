package org.uacrf.advisory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SourceReceiptConsistency {
  private SourceReceiptConsistency() {}

  enum Classification {
    PUBLIC, INTERNAL, RESTRICTED
  }

  record Expected(String caseId, String retrievalId, String manifestDigest, String purpose,
                  Classification classification) {}

  record CaseReceipt(String caseId, String purpose, String caseDigest, String workloadId,
                     List<String> requiredSourceKinds) {
    CaseReceipt {
      requiredSourceKinds = copyOrNull(requiredSourceKinds);
    }
  }

  record ReceiptResult(String itemId, String version, int chunkIndex, int startOffset, int endOffset,
                       String sourceDigest, String excerptDigest, List<String> allowedPurposes,
                       Classification classification, int score) {
    ReceiptResult {
      allowedPurposes = copyOrNull(allowedPurposes);
    }
  }

  record RetrievalReceipt(String retrievalId, String requestedCaseId, String caseId, String caseBinding,
                          String caseDigest, String workloadId, String purpose, Classification classification,
                          String manifestDigest, String query, String createdAt,
                          List<ReceiptResult> results, boolean eligible, String reason) {
    RetrievalReceipt {
      results = copyOrNull(results);
    }
  }

  // Consistency does not authenticate receipts or establish current requester authority.
  static void check(Expected expected, CaseReceipt sourceCase, RetrievalReceipt retrieval) {
    checkCase(expected, sourceCase);
    if (retrieval == null) {
      throw new IllegalArgumentException("Invalid expected advisory sources or missing receipts.");
    }
    if (!expected.retrievalId().equals(retrieval.retrievalId())
        || !expected.caseId().equals(retrieval.requestedCaseId())
        || !expected.caseId().equals(retrieval.caseId())
        || !"VERIFIED".equals(retrieval.caseBinding())
        || !sourceCase.caseDigest().equals(retrieval.caseDigest())
        || !sourceCase.workloadId().equals(retrieval.workloadId())
        || !expected.purpose().equals(retrieval.purpose())
        || expected.classification() != retrieval.classification()
        || !expected.manifestDigest().equals(retrieval.manifestDigest())
        || !text(retrieval.query(), 2000) || !validInstant(retrieval.createdAt())
        || !retrieval.eligible() || !"ELIGIBLE".equals(retrieval.reason())) {
      throw new IllegalArgumentException("Retrieval receipt does not match the expected case and manifest.");
    }
    if (retrieval.results() == null || retrieval.results().isEmpty() || retrieval.results().size() > 10) {
      throw new IllegalArgumentException("An advisory retrieval requires one to ten receipt results.");
    }
    Set<ChunkKey> chunks = new HashSet<>();
    for (ReceiptResult result : retrieval.results()) {
      if (result == null || !id(result.itemId(), 200) || !id(result.version(), 100)
          || result.chunkIndex() < 0 || result.startOffset() < 0
          || result.endOffset() <= result.startOffset()
          || (long) result.endOffset() - result.startOffset() > 1000
          || !digest(result.sourceDigest()) || !digest(result.excerptDigest())
          || !validTexts(result.allowedPurposes(), 100)
          || !result.allowedPurposes().contains(expected.purpose())
          || result.classification() == null
          || result.classification().ordinal() > expected.classification().ordinal()
          || result.score() < 1
          || !chunks.add(new ChunkKey(result.itemId(), result.version(), result.chunkIndex()))) {
        throw new IllegalArgumentException("Retrieval receipt contains invalid or ineligible source metadata.");
      }
    }
  }

  static void checkCase(Expected expected, CaseReceipt sourceCase) {
    validateExpected(expected);
    if (sourceCase == null || !expected.caseId().equals(sourceCase.caseId())
        || !expected.purpose().equals(sourceCase.purpose())
        || !digest(sourceCase.caseDigest()) || !text(sourceCase.workloadId(), 200)
        || !validTexts(sourceCase.requiredSourceKinds(), 100)) {
      throw new IllegalArgumentException("Assessment receipt does not match the expected case.");
    }
  }

  static void validateExpected(Expected expected) {
    if (expected == null || !id(expected.caseId(), 200) || !id(expected.retrievalId(), 200)
        || !digest(expected.manifestDigest()) || !text(expected.purpose(), 200)
        || expected.classification() == null) {
      throw new IllegalArgumentException("Invalid expected advisory sources.");
    }
  }

  private static boolean validTexts(List<String> values, int maxSize) {
    if (values == null || values.isEmpty() || values.size() > maxSize) {
      return false;
    }
    Set<String> seen = new HashSet<>();
    for (String value : values) {
      if (!text(value, 200) || !seen.add(value)) {
        return false;
      }
    }
    return true;
  }

  private static boolean id(String value, int maxLength) {
    return value != null && value.length() <= maxLength
        && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*");
  }

  private static boolean text(String value, int maxLength) {
    return value != null && !value.isBlank() && value.length() <= maxLength;
  }

  private static boolean digest(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  private static boolean validInstant(String value) {
    if (value == null || value.length() > 40) return false;
    try {
      return java.time.Instant.parse(value).toString().equals(value);
    } catch (java.time.format.DateTimeParseException failure) {
      return false;
    }
  }

  private static <T> List<T> copyOrNull(List<T> values) {
    return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
  }

  private record ChunkKey(String itemId, String version, int chunkIndex) {}
}
