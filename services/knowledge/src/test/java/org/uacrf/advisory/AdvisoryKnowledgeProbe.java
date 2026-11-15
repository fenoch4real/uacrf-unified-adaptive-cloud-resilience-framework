package org.uacrf.advisory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class AdvisoryKnowledgeProbe {
  private AdvisoryKnowledgeProbe() {}

  public static List<String> fetch(URI assessment, URI knowledge, String retrievalId,
                                   String manifestDigest, String knowledgeToken)
      throws IOException, InterruptedException {
    return fetchReceipts(assessment, knowledge, retrievalId, manifestDigest, knowledgeToken).excerpts();
  }

  public static void verifyWithSource(URI assessment, URI knowledge, String retrievalId,
                                      String manifestDigest, String knowledgeToken, String source)
      throws IOException, InterruptedException {
    var receipts = fetchReceipts(assessment, knowledge, retrievalId, manifestDigest, knowledgeToken);
    byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
    String digest = Sha256.hex(bytes);
    var artifact = new DigestBoundArtifact("artifact:sha256:" + digest, digest, bytes);
    KnowledgeSourceVerifier.verifyAll(expected(retrievalId, manifestDigest), receipts, Map.of(digest, artifact));
  }

  private static OwnerReceiptClient.Receipts fetchReceipts(
      URI assessment, URI knowledge, String retrievalId, String manifestDigest, String knowledgeToken)
      throws IOException, InterruptedException {
    var expected = expected(retrievalId, manifestDigest);
    var client = new OwnerReceiptClient(true, assessment, knowledge, true, (tenant, environment, audience, caps) -> {
      if (!"tenant-advisory".equals(tenant) || !"env-1".equals(environment)) {
        throw new IOException("Unexpected delegation context.");
      }
      if ("S07".equals(audience) && List.of("assessment.case.read").equals(caps)) return "case-delegation";
      if ("S08".equals(audience)
          && List.of("knowledge.retrieval.read", "knowledge.classification.internal.read").equals(caps)) {
        return knowledgeToken;
      }
      throw new IOException("Unexpected delegation scope.");
    });
    return client.fetch("tenant-advisory", "env-1", expected);
  }

  private static SourceReceiptConsistency.Expected expected(String retrievalId, String manifestDigest) {
    return new SourceReceiptConsistency.Expected(
        "case-1", retrievalId, manifestDigest, "incident", SourceReceiptConsistency.Classification.INTERNAL);
  }
}
