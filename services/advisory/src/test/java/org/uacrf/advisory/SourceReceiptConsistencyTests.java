package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.uacrf.advisory.SourceReceiptConsistency.Classification.INTERNAL;
import static org.uacrf.advisory.SourceReceiptConsistency.Classification.PUBLIC;
import static org.uacrf.advisory.SourceReceiptConsistency.Classification.RESTRICTED;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceReceiptConsistencyTests {
  private static final String CASE_DIGEST = "a".repeat(64);
  private static final String MANIFEST_DIGEST = "b".repeat(64);
  private static final SourceReceiptConsistency.Expected EXPECTED =
      new SourceReceiptConsistency.Expected("case-1", "retrieval-1", MANIFEST_DIGEST, "assessment", INTERNAL);

  private static SourceReceiptConsistency.CaseReceipt sourceCase(String digest, String workload) {
    return new SourceReceiptConsistency.CaseReceipt("case-1", "assessment", digest, workload, List.of("metrics"));
  }

  private static SourceReceiptConsistency.ReceiptResult result(
      String digest, List<String> purposes, SourceReceiptConsistency.Classification classification) {
    return new SourceReceiptConsistency.ReceiptResult(
        "item-1", "v1", 0, 0, 10, digest, "d".repeat(64), purposes, classification, 1);
  }

  private static SourceReceiptConsistency.RetrievalReceipt retrieval(
      String caseId, String binding, String caseDigest, String workload, String manifest,
      boolean eligible, String reason, List<SourceReceiptConsistency.ReceiptResult> results) {
    return new SourceReceiptConsistency.RetrievalReceipt("retrieval-1", "case-1", caseId, binding,
        caseDigest, workload, "assessment", INTERNAL, manifest, "metrics", "2026-01-01T00:00:00Z",
        results, eligible, reason);
  }

  private static SourceReceiptConsistency.RetrievalReceipt goodRetrieval(
      List<SourceReceiptConsistency.ReceiptResult> results) {
    return retrieval("case-1", "VERIFIED", CASE_DIGEST, "workload one", MANIFEST_DIGEST,
        true, "ELIGIBLE", results);
  }

  private static SourceReceiptConsistency.ReceiptResult goodResult() {
    return result("c".repeat(64), List.of("assessment"), PUBLIC);
  }

  @Test
  void acceptsMatchingBoundCaseAndNonemptyManifest() {
    assertDoesNotThrow(() ->
        SourceReceiptConsistency.check(EXPECTED, sourceCase(CASE_DIGEST, "workload one"),
            goodRetrieval(List.of(goodResult()))));
  }

  @Test
  void rejectsUnverifiedOrMismatchedOwnerReceipts() {
    var source = sourceCase(CASE_DIGEST, "workload one");
    var results = List.of(goodResult());
    var rejected = List.of(
        retrieval("other", "VERIFIED", CASE_DIGEST, "workload one", MANIFEST_DIGEST, true, "ELIGIBLE", results),
        retrieval("case-1", "NOT_VERIFIED", CASE_DIGEST, "workload one", MANIFEST_DIGEST, true, "ELIGIBLE", results),
        retrieval("case-1", "VERIFIED", "e".repeat(64), "workload one", MANIFEST_DIGEST, true, "ELIGIBLE", results),
        retrieval("case-1", "VERIFIED", CASE_DIGEST, "other", MANIFEST_DIGEST, true, "ELIGIBLE", results),
        retrieval("case-1", "VERIFIED", CASE_DIGEST, "workload one", "f".repeat(64), true, "ELIGIBLE", results),
        retrieval("case-1", "VERIFIED", CASE_DIGEST, "workload one", MANIFEST_DIGEST, false, "CASE_UNVERIFIED", results),
        retrieval("case-1", "VERIFIED", CASE_DIGEST, "workload one", MANIFEST_DIGEST, true, "SOURCE_NOT_ELIGIBLE", results));
    for (var candidate : rejected) {
      assertThrows(IllegalArgumentException.class, () -> SourceReceiptConsistency.check(EXPECTED, source, candidate));
    }
    assertThrows(IllegalArgumentException.class,
        () -> SourceReceiptConsistency.check(EXPECTED, sourceCase("bad", "workload one"), goodRetrieval(results)));
    var wrongRequest = new SourceReceiptConsistency.RetrievalReceipt("retrieval-1", "other", "case-1", "VERIFIED",
        CASE_DIGEST, "workload one", "assessment", INTERNAL, MANIFEST_DIGEST, "metrics",
        "2026-01-01T00:00:00Z", results, true, "ELIGIBLE");
    assertThrows(IllegalArgumentException.class,
        () -> SourceReceiptConsistency.check(EXPECTED, source, wrongRequest));
    var wrongClassification = new SourceReceiptConsistency.Expected(
        "case-1", "retrieval-1", MANIFEST_DIGEST, "assessment", PUBLIC);
    assertThrows(IllegalArgumentException.class,
        () -> SourceReceiptConsistency.check(wrongClassification, source, goodRetrieval(results)));
    var wrongPurpose = new SourceReceiptConsistency.Expected(
        "case-1", "retrieval-1", MANIFEST_DIGEST, "other-purpose", INTERNAL);
    assertThrows(IllegalArgumentException.class,
        () -> SourceReceiptConsistency.check(wrongPurpose, source, goodRetrieval(results)));
  }

  @Test
  void rejectsEmptyOversizedAndIneligibleResults() {
    var source = sourceCase(CASE_DIGEST, "workload one");
    var candidates = List.of(
        List.<SourceReceiptConsistency.ReceiptResult>of(),
        Collections.nCopies(11, goodResult()),
        List.of(goodResult(), goodResult()),
        List.of(result("invalid", List.of("assessment"), PUBLIC)),
        List.of(result("c".repeat(64), List.of("another-purpose"), PUBLIC)),
        List.of(result("c".repeat(64), List.of("assessment"), RESTRICTED)));
    for (var results : candidates) {
      assertThrows(IllegalArgumentException.class,
          () -> SourceReceiptConsistency.check(EXPECTED, source, goodRetrieval(results)));
    }
    var invalidOffsets = new SourceReceiptConsistency.ReceiptResult("item-1", "v1", 0, 3, 3,
        "c".repeat(64), "d".repeat(64), List.of("assessment"), PUBLIC, 1);
    assertThrows(IllegalArgumentException.class,
        () -> SourceReceiptConsistency.check(EXPECTED, source, goodRetrieval(List.of(invalidOffsets))));
  }

  @Test
  void receiptListsCannotBeChangedAfterValidation() {
    var purposes = new ArrayList<>(List.of("assessment"));
    var sourceKinds = new ArrayList<>(List.of("metrics"));
    var results = new ArrayList<>(List.of(result("c".repeat(64), purposes, PUBLIC)));
    var source = new SourceReceiptConsistency.CaseReceipt("case-1", "assessment",
        CASE_DIGEST, "workload one", sourceKinds);
    var retrieval = goodRetrieval(results);
    purposes.clear();
    sourceKinds.clear();
    results.clear();
    SourceReceiptConsistency.check(EXPECTED, source, retrieval);
    assertEquals(List.of("assessment"), retrieval.results().get(0).allowedPurposes());
    assertThrows(UnsupportedOperationException.class, () -> retrieval.results().clear());
  }
}
