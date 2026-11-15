package org.uacrf.advisory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class KnowledgeManifestVerifier {
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private KnowledgeManifestVerifier() {}

  // S08's existing digest uses its ordered, snake-case Jackson representation, not RFC 8785.
  static void verify(SourceReceiptConsistency.Expected expected, SourceReceiptConsistency.CaseReceipt sourceCase,
                     SourceReceiptConsistency.RetrievalReceipt retrieval) {
    SourceReceiptConsistency.check(expected, sourceCase, retrieval);
    var entries = retrieval.results().stream()
        .map(result -> new Entry(result.itemId(), result.version(), result.chunkIndex(),
            result.startOffset(), result.endOffset(), result.sourceDigest(), result.excerptDigest(),
            result.allowedPurposes(), result.classification().name(), result.score()))
        .toList();
    var manifest = new Manifest(retrieval.retrievalId(), retrieval.caseId(), retrieval.caseBinding(),
        retrieval.caseDigest(), retrieval.purpose(), retrieval.classification().name(), retrieval.query(),
        retrieval.workloadId(), retrieval.createdAt(), entries);
    String serialized = JSON.writeValueAsString(manifest);
    if (!retrieval.manifestDigest().equals(Sha256.hex(serialized.getBytes(StandardCharsets.UTF_8)))) {
      throw new IllegalArgumentException("Knowledge manifest fields do not match the retained digest.");
    }
  }

  private record Entry(String item_id, String version, int chunk_index, int start_offset, int end_offset,
                       String source_digest, String excerpt_digest, List<String> allowed_purposes,
                       String classification, int score) {}

  private record Manifest(String retrieval_id, String case_id, String case_binding, String case_digest,
                          String purpose, String max_classification, String query, String workload_id,
                          String created_at, List<Entry> results) {}
}
