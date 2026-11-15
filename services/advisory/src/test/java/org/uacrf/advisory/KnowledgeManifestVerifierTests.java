package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeManifestVerifierTests {
  private static final String CASE_DIGEST = "a".repeat(64);
  private static final String SOURCE_DIGEST = "b".repeat(64);
  private static final String EXCERPT_DIGEST = "c".repeat(64);
  private static final String QUERY = "metrics \"😀\" \\";
  private static final String MANIFEST = """
      {"retrieval_id":"retrieval-1","case_id":"case-1","case_binding":"VERIFIED","case_digest":"%s",\
      "purpose":"incident","max_classification":"INTERNAL","query":"metrics \\"😀\\" \\\\",\
      "workload_id":"workload-1","created_at":"2026-01-01T00:00:00Z","results":[\
      {"item_id":"item-1","version":"v1","chunk_index":0,"start_offset":0,"end_offset":4,\
      "source_digest":"%s","excerpt_digest":"%s","allowed_purposes":["incident"],\
      "classification":"PUBLIC","score":1}]}""".formatted(CASE_DIGEST, SOURCE_DIGEST, EXCERPT_DIGEST);
  private static final String DIGEST = Sha256.hex(MANIFEST.getBytes(StandardCharsets.UTF_8));

  @Test
  void checksTheExistingOrderedManifestEncodingIncludingUnicodeAndEscapes() {
    verify(receipts(DIGEST, QUERY, 1));
  }

  @Test
  void rejectsChangedManifestFieldsEvenWhenTheReceiptClaimsTheOriginalDigest() {
    assertThrows(IllegalArgumentException.class,
        () -> verify(receipts(DIGEST, QUERY + " changed", 1)));
    assertThrows(IllegalArgumentException.class,
        () -> verify(receipts(DIGEST, QUERY, 2)));
    String wrongDigest = "0".repeat(64);
    assertThrows(IllegalArgumentException.class,
        () -> verify(receipts(wrongDigest, QUERY, 1)));
  }

  private static void verify(OwnerReceiptClient.Receipts receipts) {
    KnowledgeManifestVerifier.verify(expected(receipts.retrieval().manifestDigest()),
        receipts.sourceCase(), receipts.retrieval());
  }

  private static SourceReceiptConsistency.Expected expected(String digest) {
    return new SourceReceiptConsistency.Expected("case-1", "retrieval-1", digest, "incident",
        SourceReceiptConsistency.Classification.INTERNAL);
  }

  private static OwnerReceiptClient.Receipts receipts(String digest, String query, int score) {
    var source = new SourceReceiptConsistency.CaseReceipt(
        "case-1", "incident", CASE_DIGEST, "workload-1", List.of("metrics"));
    var result = new SourceReceiptConsistency.ReceiptResult(
        "item-1", "v1", 0, 0, 4, SOURCE_DIGEST, EXCERPT_DIGEST, List.of("incident"),
        SourceReceiptConsistency.Classification.PUBLIC, score);
    var retrieval = new SourceReceiptConsistency.RetrievalReceipt(
        "retrieval-1", "case-1", "case-1", "VERIFIED", CASE_DIGEST, "workload-1", "incident",
        SourceReceiptConsistency.Classification.INTERNAL, digest, query, "2026-01-01T00:00:00Z",
        List.of(result), true, "ELIGIBLE");
    return new OwnerReceiptClient.Receipts(source, retrieval, List.of("text"));
  }
}
