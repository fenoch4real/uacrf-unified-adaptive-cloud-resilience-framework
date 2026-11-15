package org.uacrf.advisory;

final class DigestBoundArtifact {
  private static final String PREFIX = "artifact:sha256:";
  private final String reference;
  private final String digest;
  private final byte[] bytes;

  DigestBoundArtifact(String reference, String digest, byte[] bytes) {
    if (digest == null || !digest.matches("[0-9a-f]{64}")
        || !(PREFIX + digest).equals(reference) || bytes == null) {
      throw new IllegalArgumentException("Invalid content-addressed artifact.");
    }
    this.bytes = bytes.clone();
    if (!digest.equals(Sha256.hex(this.bytes))) {
      throw new IllegalArgumentException("Artifact bytes do not match the declared digest.");
    }
    this.reference = reference;
    this.digest = digest;
  }

  String reference() {
    return reference;
  }

  String digest() {
    return digest;
  }

  byte[] bytes() {
    return bytes.clone();
  }
}
