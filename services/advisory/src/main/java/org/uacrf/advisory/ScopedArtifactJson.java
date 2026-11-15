package org.uacrf.advisory;

import java.util.Map;
import java.util.Set;

final class ScopedArtifactJson {
  private ScopedArtifactJson() {}

  static Map<?, ?> read(String tenantId, String environmentId, String expectedReference, String expectedDigest,
                        DigestBoundArtifact artifact, String protocol, String entriesField) {
    InternalJsonClient.requireId(tenantId);
    InternalJsonClient.requireId(environmentId);
    if (artifact == null || !artifact.reference().equals(expectedReference)
        || !artifact.digest().equals(expectedDigest)) {
      throw new IllegalArgumentException("Configuration does not match the requested artifact.");
    }
    var document = StrictBoundedJson.readObject(artifact.bytes(), "Configuration");
    if (!document.keySet().equals(Set.of("protocol", "tenant_id", "environment_id", entriesField))
        || !protocol.equals(document.get("protocol"))
        || !tenantId.equals(document.get("tenant_id")) || !environmentId.equals(document.get("environment_id"))) {
      throw new IllegalArgumentException("Configuration has an invalid shape or scope.");
    }
    return document;
  }
}
