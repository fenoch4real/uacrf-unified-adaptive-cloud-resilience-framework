package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
}
