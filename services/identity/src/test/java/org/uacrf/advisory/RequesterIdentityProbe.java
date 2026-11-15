package org.uacrf.advisory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class RequesterIdentityProbe {
  static final String CASE_DIGEST = "a".repeat(64);
  static final String MANIFEST_DIGEST = "b".repeat(64);
  static final String SOURCE_DIGEST = "c".repeat(64);
  static final String EXCERPT = "metrics";
  static final String EXCERPT_DIGEST = Sha256.hex(EXCERPT.getBytes(StandardCharsets.UTF_8));

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
    if (!snapshot.receipts().excerpts().equals(List.of(EXCERPT))) {
      throw new IllegalStateException("The requester source check returned unexpected excerpts.");
    }
    return snapshot.requester().id();
  }
}
