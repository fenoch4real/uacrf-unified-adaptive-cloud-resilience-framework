package org.uacrf.advisory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class RequesterIdentityProbe {
  static final String CASE_MATERIAL =
      "{\"workload_id\":\"workload-1\",\"purpose\":\"assessment\",\"required_source_kinds\":[\"metrics\"]}";
  static final String CASE_DIGEST = Sha256.hex(CASE_MATERIAL.getBytes(StandardCharsets.UTF_8));
  static final String SOURCE_DIGEST = "c".repeat(64);
  static final String EXCERPT = "metrics";
  static final String EXCERPT_DIGEST = Sha256.hex(EXCERPT.getBytes(StandardCharsets.UTF_8));
  static final String MANIFEST_DIGEST = Sha256.hex(("""
      {"retrieval_id":"retrieval-1","case_id":"case-1","case_binding":"VERIFIED","case_digest":"%s",\
      "purpose":"assessment","max_classification":"INTERNAL","query":"metrics","workload_id":"workload-1",\
      "created_at":"2026-01-01T00:00:00Z","results":[{"item_id":"item-1","version":"v1",\
      "chunk_index":0,"start_offset":0,"end_offset":7,"source_digest":"%s","excerpt_digest":"%s",\
      "allowed_purposes":["assessment"],"classification":"PUBLIC","score":1}]}"""
      .formatted(CASE_DIGEST, SOURCE_DIGEST, EXCERPT_DIGEST)).getBytes(StandardCharsets.UTF_8));

  private RequesterIdentityProbe() {}

  public static String check(URI identity, String token, String classification)
      throws IOException, InterruptedException {
    var client = new RequesterAccessClient(true, identity, true);
    return client.check(token, "tenant-test", "environment-test",
        SourceReceiptConsistency.Classification.valueOf(classification)).id();
  }

  public static String delegate(URI identity, String token, String audience, List<String> capabilities)
      throws IOException, InterruptedException {
    var client = new S01DelegationClient(true, identity, true, () -> token);
    return client.delegation("tenant-test", "environment-test", audience, capabilities);
  }

  public static String checkSources(URI identity, URI owners, String token)
      throws IOException, InterruptedException {
    var expected = new SourceReceiptConsistency.Expected(
        "case-1", "retrieval-1", MANIFEST_DIGEST, "assessment",
        SourceReceiptConsistency.Classification.INTERNAL);
    var client = new RequesterSourceCheck(true, identity, owners, owners, true);
    var snapshot = client.check(token, "tenant-test", "environment-test", expected);
    if (!snapshot.receipts().excerpts().equals(List.of(EXCERPT))
        || !CASE_MATERIAL.equals(snapshot.caseMaterial())) {
      throw new IllegalStateException("The requester source check returned unexpected material.");
    }
    return snapshot.requester().id();
  }
}
