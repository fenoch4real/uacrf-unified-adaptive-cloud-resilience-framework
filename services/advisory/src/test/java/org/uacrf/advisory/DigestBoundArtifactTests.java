package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DigestBoundArtifactTests {
  private static final String ABC_DIGEST =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
  private static final String ABC_REFERENCE = "artifact:sha256:" + ABC_DIGEST;

  @Test
  void checksAndDefensivelyRetainsExactBytes() {
    byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
    var artifact = new DigestBoundArtifact(ABC_REFERENCE, ABC_DIGEST, bytes);
    bytes[0] = 0;
    artifact.bytes()[1] = 0;
    assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), artifact.bytes());
    assertEquals(ABC_REFERENCE, artifact.reference());
    assertEquals(ABC_DIGEST, artifact.digest());
  }

  @Test
  void rejectsChangedBytesAndReferenceOrDigestSubstitution() {
    byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
    assertThrows(IllegalArgumentException.class,
        () -> new DigestBoundArtifact(ABC_REFERENCE, ABC_DIGEST, "abd".getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalArgumentException.class,
        () -> new DigestBoundArtifact("artifact:sha256:" + "0".repeat(64), ABC_DIGEST, bytes));
    assertThrows(IllegalArgumentException.class,
        () -> new DigestBoundArtifact(ABC_REFERENCE, "bad", bytes));
    assertThrows(IllegalArgumentException.class,
        () -> new DigestBoundArtifact("https://example.test/abc", ABC_DIGEST, bytes));
    assertThrows(IllegalArgumentException.class,
        () -> new DigestBoundArtifact(ABC_REFERENCE, ABC_DIGEST, null));
  }

  @Test
  void permitsEmptyBytesWhenTheirDigestMatches() {
    String emptyDigest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    var artifact = new DigestBoundArtifact("artifact:sha256:" + emptyDigest, emptyDigest, new byte[0]);
    assertArrayEquals(new byte[0], artifact.bytes());
  }
}
