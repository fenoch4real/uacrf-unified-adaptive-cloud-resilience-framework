package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeSourceVerifierTests {
  private static DigestBoundArtifact artifact(byte[] bytes) {
    String digest = Sha256.hex(bytes);
    return new DigestBoundArtifact("artifact:sha256:" + digest, digest, bytes);
  }

  private static DigestBoundArtifact artifact(String content) {
    return artifact(content.getBytes(StandardCharsets.UTF_8));
  }

  private static SourceReceiptConsistency.ReceiptResult result(
      DigestBoundArtifact source, String excerpt, int chunk, int start, int end) {
    return new SourceReceiptConsistency.ReceiptResult(
        "item-1", "v1", chunk, start, end, source.digest(),
        Sha256.hex(excerpt.getBytes(StandardCharsets.UTF_8)), List.of("assessment"),
        SourceReceiptConsistency.Classification.PUBLIC, 1);
  }

  @Test
  void checksFullSourceAndCodePointLocatorsIncludingSupplementaryCharacters() {
    String content = "x".repeat(999) + "😀" + "tail";
    var source = artifact(content);
    String first = "x".repeat(999) + "😀";
    KnowledgeSourceVerifier.verify(result(source, first, 0, 0, 1000), first, source);
    KnowledgeSourceVerifier.verify(result(source, "tail", 1, 1000, 1004), "tail", source);
  }

  @Test
  void rejectsSourceSubstitutionAndChangedExcerptOrLocator() {
    var source = artifact("x".repeat(1000) + "tail");
    var result = result(source, "tail", 1, 1000, 1004);
    assertThrows(IllegalArgumentException.class,
        () -> KnowledgeSourceVerifier.verify(result, "tail", artifact("y".repeat(1000) + "tail")));
    assertThrows(IllegalArgumentException.class,
        () -> KnowledgeSourceVerifier.verify(result, "fail", source));
    assertThrows(IllegalArgumentException.class,
        () -> KnowledgeSourceVerifier.verify(result(source, "tail", 0, 1000, 1004), "tail", source));
    assertThrows(IllegalArgumentException.class,
        () -> KnowledgeSourceVerifier.verify(result(source, "tai", 1, 1000, 1003), "tai", source));
    assertThrows(IllegalArgumentException.class,
        () -> KnowledgeSourceVerifier.verify(result(source, "tail", 2, 2000, 2004), "tail", source));
    assertThrows(IllegalArgumentException.class,
        () -> KnowledgeSourceVerifier.verify(result(source, "tail", Integer.MAX_VALUE, 1000, 1004), "tail", source));
    var wrongDigest = new SourceReceiptConsistency.ReceiptResult(
        "item-1", "v1", 1, 1000, 1004, source.digest(), "0".repeat(64),
        List.of("assessment"), SourceReceiptConsistency.Classification.PUBLIC, 1);
    assertThrows(IllegalArgumentException.class, () -> KnowledgeSourceVerifier.verify(wrongDigest, "tail", source));
  }

  @Test
  void rejectsInvalidUtf8AndSourcesOutsideS08Bounds() {
    var invalid = artifact(new byte[] {(byte) 0xc3, (byte) 0x28});
    assertThrows(IllegalArgumentException.class,
        () -> KnowledgeSourceVerifier.verify(result(invalid, "x", 0, 0, 1), "x", invalid));
    var empty = artifact(new byte[0]);
    assertThrows(IllegalArgumentException.class,
        () -> KnowledgeSourceVerifier.verify(result(empty, "", 0, 0, 0), "", empty));
    var maximum = artifact("x".repeat(256 * 1024));
    KnowledgeSourceVerifier.verify(result(maximum, "x".repeat(1000), 0, 0, 1000),
        "x".repeat(1000), maximum);
    var oversized = artifact("x".repeat(256 * 1024 + 1));
    assertThrows(IllegalArgumentException.class,
        () -> KnowledgeSourceVerifier.verify(result(oversized, "x".repeat(1000), 0, 0, 1000),
            "x".repeat(1000), oversized));
  }

  @Test
  void verifiesEveryResultAgainstItsCompleteSourceIncludingSharedSources() {
    var first = artifact("x".repeat(1000) + "tail");
    var second = artifact("other");
    var results = List.of(result(first, "x".repeat(1000), 0, 0, 1000),
        result(first, "tail", 1, 1000, 1004),
        new SourceReceiptConsistency.ReceiptResult("item-2", "v1", 0, 0, 5, second.digest(),
            Sha256.hex("other".getBytes(StandardCharsets.UTF_8)), List.of("assessment"),
            SourceReceiptConsistency.Classification.PUBLIC, 1));
    var receipts = receipts(results, List.of("x".repeat(1000), "tail", "other"), true);
    KnowledgeSourceVerifier.verifyAll(expected(receipts), receipts, Map.of(first.digest(), first, second.digest(), second));
  }

  @Test
  void rejectsMissingExtraSubstitutedAndMismatchedSourceEvidence() {
    var first = artifact("first");
    var second = artifact("second");
    var results = List.of(result(first, "first", 0, 0, 5),
        new SourceReceiptConsistency.ReceiptResult("item-2", "v1", 0, 0, 6, second.digest(),
            Sha256.hex("second".getBytes(StandardCharsets.UTF_8)), List.of("assessment"),
            SourceReceiptConsistency.Classification.PUBLIC, 1));
    var receipts = receipts(results, List.of("first", "second"), true);
    var sources = Map.of(first.digest(), first, second.digest(), second);
    assertThrows(IllegalArgumentException.class,
        () -> KnowledgeSourceVerifier.verifyAll(expected(receipts), receipts, Map.of(first.digest(), first)));
    var extra = artifact("extra");
    assertThrows(IllegalArgumentException.class, () -> KnowledgeSourceVerifier.verifyAll(expected(receipts), receipts,
        Map.of(first.digest(), first, second.digest(), second, extra.digest(), extra)));
    assertThrows(IllegalArgumentException.class, () -> KnowledgeSourceVerifier.verifyAll(expected(receipts), receipts,
        Map.of(first.digest(), second, second.digest(), second)));
    assertThrows(IllegalArgumentException.class, () -> KnowledgeSourceVerifier.verifyAll(expected(receipts),
        receipts(results, List.of("first"), true), sources));
    assertThrows(IllegalArgumentException.class, () -> KnowledgeSourceVerifier.verifyAll(expected(receipts),
        receipts(results, List.of("second", "first"), true), sources));
    assertThrows(IllegalArgumentException.class, () -> KnowledgeSourceVerifier.verifyAll(expected(receipts),
        receipts(results, List.of("first", "second"), false), sources));
  }

  @Test
  void rejectsTamperedManifestEvenWhenSourceAndExcerptStillMatch() {
    var source = artifact("first");
    var receipts = receipts(List.of(result(source, "first", 0, 0, 5)), List.of("first"), true);
    var original = receipts.retrieval();
    var changed = new SourceReceiptConsistency.RetrievalReceipt(
        original.retrievalId(), original.requestedCaseId(), original.caseId(), original.caseBinding(),
        original.caseDigest(), original.workloadId(), original.purpose(), original.classification(),
        original.manifestDigest(), "changed query", original.createdAt(), original.results(),
        original.eligible(), original.reason());
    var tampered = new OwnerReceiptClient.Receipts(receipts.sourceCase(), changed, receipts.excerpts());
    assertThrows(IllegalArgumentException.class,
        () -> KnowledgeSourceVerifier.verifyAll(expected(receipts), tampered, Map.of(source.digest(), source)));
  }

  private static SourceReceiptConsistency.Expected expected(OwnerReceiptClient.Receipts receipts) {
    return new SourceReceiptConsistency.Expected("case-1", "retrieval-1", receipts.retrieval().manifestDigest(), "assessment",
        SourceReceiptConsistency.Classification.PUBLIC);
  }

  private static OwnerReceiptClient.Receipts receipts(
      List<SourceReceiptConsistency.ReceiptResult> results, List<String> excerpts, boolean eligible) {
    var source = new SourceReceiptConsistency.CaseReceipt(
        "case-1", "assessment", "a".repeat(64), "workload-1", List.of("metrics"));
    var entries = results.stream().map(result -> """
        {"item_id":"%s","version":"%s","chunk_index":%d,"start_offset":%d,"end_offset":%d,\
        "source_digest":"%s","excerpt_digest":"%s","allowed_purposes":["assessment"],\
        "classification":"PUBLIC","score":%d}""".formatted(result.itemId(), result.version(), result.chunkIndex(),
            result.startOffset(), result.endOffset(), result.sourceDigest(), result.excerptDigest(), result.score()))
        .toList();
    String manifest = """
        {"retrieval_id":"retrieval-1","case_id":"case-1","case_binding":"VERIFIED","case_digest":"%s",\
        "purpose":"assessment","max_classification":"PUBLIC","query":"query","workload_id":"workload-1",\
        "created_at":"2026-01-01T00:00:00Z","results":[%s]}""".formatted("a".repeat(64), String.join(",", entries));
    String digest = Sha256.hex(manifest.getBytes(StandardCharsets.UTF_8));
    var retrieval = new SourceReceiptConsistency.RetrievalReceipt(
        "retrieval-1", "case-1", "case-1", "VERIFIED", "a".repeat(64), "workload-1", "assessment",
        SourceReceiptConsistency.Classification.PUBLIC, digest, "query", "2026-01-01T00:00:00Z",
        results, eligible, eligible ? "ELIGIBLE" : "SOURCE_NOT_ELIGIBLE");
    return new OwnerReceiptClient.Receipts(source, retrieval, excerpts);
  }
}
