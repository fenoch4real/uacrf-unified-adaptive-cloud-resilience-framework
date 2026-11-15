package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptConfigurationTests {
  private static final String DIGEST = "a".repeat(64);
  private static final String DOCUMENT = """
      {"protocol":"UACRF_ADVISORY_PROMPTS_V1","tenant_id":"tenant-1","environment_id":"env-1",
       "prompts":[{"instructions_digest":"%s","text":"Instructions 😀"}]}
      """.formatted(DIGEST);

  @Test
  void checksArtifactScopeAndShapeWithoutTreatingDeclaredDigestAsVerifiedText() {
    var configuration = parse(DOCUMENT);
    assertEquals(List.of(new PromptConfiguration.Prompt(DIGEST, "Instructions 😀")), configuration.prompts());
    assertThrows(UnsupportedOperationException.class, () -> configuration.prompts().clear());
    var artifact = artifact(DOCUMENT.getBytes(StandardCharsets.UTF_8));
    assertThrows(IllegalArgumentException.class,
        () -> PromptConfiguration.parse("other", "env-1", artifact.reference(), artifact.digest(), artifact));
    assertThrows(IllegalArgumentException.class,
        () -> PromptConfiguration.parse("tenant-1", "other", artifact.reference(), artifact.digest(), artifact));
    assertThrows(IllegalArgumentException.class,
        () -> PromptConfiguration.parse("tenant-1", "env-1", "artifact:sha256:" + "0".repeat(64),
            artifact.digest(), artifact));
    assertThrows(IllegalArgumentException.class,
        () -> PromptConfiguration.parse("tenant-1", "env-1", artifact.reference(), "0".repeat(64), artifact));
  }

  @Test
  void rejectsMalformedAmbiguousAndInvalidEntries() {
    String prompt = "{\"instructions_digest\":\"" + DIGEST + "\",\"text\":\"Instructions 😀\"}";
    for (String invalid : List.of(
        DOCUMENT.replace("UACRF_ADVISORY_PROMPTS_V1", "wrong"),
        DOCUMENT.replace("\"prompts\":", "\"extra\":0,\"prompts\":"),
        DOCUMENT.replace("\"text\":", "\"extra\":0,\"text\":"),
        DOCUMENT.replace(DIGEST, "not-a-digest"),
        DOCUMENT.replace("\"Instructions 😀\"", "\"  \""),
        DOCUMENT.replace("\"Instructions 😀\"", "\"\\ud800\""),
        DOCUMENT.replace("\"Instructions 😀\"", "\"" + "a".repeat(20_001) + "\""),
        DOCUMENT.replace("[" + prompt + "]", "[" + prompt + "," + prompt + "]"),
        DOCUMENT.replace("[" + prompt + "]", "[]"),
        DOCUMENT.replace("\"tenant_id\":\"tenant-1\"", "\"tenant_id\":\"tenant-1\",\"tenant_id\":\"tenant-1\""),
        DOCUMENT + "{}",
        "invalid JSON")) {
      assertThrows(IllegalArgumentException.class, () -> parse(invalid), invalid);
    }
    assertThrows(IllegalArgumentException.class, () -> parse(artifact(new byte[] {(byte) 0xc3, (byte) 0x28})));
    assertThrows(IllegalArgumentException.class, () -> parse(artifact(new byte[InputPacket.MAX_BYTES + 1])));
  }

  @Test
  void enforcesListBoundsAndUnicodeCodePointLength() {
    List<String> entries = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      entries.add("{\"instructions_digest\":\"" + String.format("%064x", i)
          + "\",\"text\":\"" + "😀".repeat(20_000) + "\"}");
    }
    String prefix = DOCUMENT.substring(0, DOCUMENT.indexOf("\"prompts\":")) + "\"prompts\":[";
    assertEquals(10, parse(prefix + String.join(",", entries) + "]}").prompts().size());
    entries.add("{\"instructions_digest\":\"" + String.format("%064x", 10) + "\",\"text\":\"x\"}");
    assertThrows(IllegalArgumentException.class, () -> parse(prefix + String.join(",", entries) + "]}"));
  }

  private static PromptConfiguration parse(String value) {
    return parse(artifact(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static PromptConfiguration parse(DigestBoundArtifact artifact) {
    return PromptConfiguration.parse("tenant-1", "env-1", artifact.reference(), artifact.digest(), artifact);
  }

  private static DigestBoundArtifact artifact(byte[] bytes) {
    String digest = Sha256.hex(bytes);
    return new DigestBoundArtifact("artifact:sha256:" + digest, digest, bytes);
  }
}
