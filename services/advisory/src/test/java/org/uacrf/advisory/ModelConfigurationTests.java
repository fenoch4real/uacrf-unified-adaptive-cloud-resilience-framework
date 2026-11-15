package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ModelConfigurationTests {
  private static final String DOCUMENT = """
      {"protocol":"UACRF_ADVISORY_MODELS_V1","tenant_id":"tenant-1","environment_id":"env-1",
       "models":[{"model_ref":"model-😀","engine":"HTTP_JSON_ADVISORY_V1","timeout_seconds":60,
       "maximum_output_tokens":1000000,"maximum_cost_units":0}]}
      """;

  @Test
  void validatesScopedDigestBoundConfigurationAndCopiesModels() {
    var configuration = parse(DOCUMENT);
    assertEquals(1, configuration.models().size());
    assertEquals(new ModelConfiguration.Model("model-😀", 60, 1_000_000, 0), configuration.models().getFirst());
    assertThrows(UnsupportedOperationException.class, () -> configuration.models().clear());
  }

  @Test
  void rejectsArtifactSubstitutionAndWrongScope() {
    var artifact = artifact(DOCUMENT.getBytes(StandardCharsets.UTF_8));
    assertThrows(IllegalArgumentException.class,
        () -> ModelConfiguration.parse("tenant-1", "env-1", "artifact:sha256:" + "0".repeat(64),
            artifact.digest(), artifact));
    assertThrows(IllegalArgumentException.class,
        () -> ModelConfiguration.parse("tenant-1", "env-1", artifact.reference(), "0".repeat(64), artifact));
    assertThrows(IllegalArgumentException.class,
        () -> ModelConfiguration.parse("tenant-2", "env-1", artifact.reference(), artifact.digest(), artifact));
    assertThrows(IllegalArgumentException.class,
        () -> ModelConfiguration.parse("tenant-1", "env-2", artifact.reference(), artifact.digest(), artifact));
  }

  @Test
  void rejectsInvalidShapeAmbiguityAndOutOfBoundsValues() {
    for (String invalid : java.util.List.of(
        DOCUMENT.replace("UACRF_ADVISORY_MODELS_V1", "wrong"),
        DOCUMENT.replace("\"models\":", "\"extra\":0,\"models\":"),
        DOCUMENT.replace("\"model_ref\":", "\"extra\":0,\"model_ref\":"),
        DOCUMENT.replace("\"model-😀\"", "\"  \""),
        DOCUMENT.replace("\"model-😀\"", "\"\\ud800\""),
        DOCUMENT.replace("\"model-😀\"", "\"" + "a".repeat(501) + "\""),
        DOCUMENT.replace("HTTP_JSON_ADVISORY_V1", "other"),
        DOCUMENT.replace("\"timeout_seconds\":60", "\"timeout_seconds\":0"),
        DOCUMENT.replace("\"timeout_seconds\":60", "\"timeout_seconds\":61"),
        DOCUMENT.replace("\"maximum_output_tokens\":1000000", "\"maximum_output_tokens\":1000001"),
        DOCUMENT.replace("\"maximum_output_tokens\":1000000", "\"maximum_output_tokens\":1.0"),
        DOCUMENT.replace("\"maximum_cost_units\":0", "\"maximum_cost_units\":-1"),
        DOCUMENT.replace("\"maximum_cost_units\":0", "\"maximum_cost_units\":1000000001"),
        DOCUMENT.replace("\"models\":[", "\"models\":[] ,\"duplicate\":["),
        DOCUMENT.replace("\"tenant_id\":\"tenant-1\"", "\"tenant_id\":\"tenant-1\",\"tenant_id\":\"tenant-1\""),
        DOCUMENT + "{}",
        "invalid JSON")) {
      assertThrows(IllegalArgumentException.class, () -> parse(invalid), invalid);
    }
    String model = """
        {"model_ref":"same","engine":"HTTP_JSON_ADVISORY_V1","timeout_seconds":1,
         "maximum_output_tokens":1,"maximum_cost_units":1}""";
    String duplicate = DOCUMENT.substring(0, DOCUMENT.indexOf("\"models\":")) + "\"models\":[" + model + "," + model + "]}";
    assertThrows(IllegalArgumentException.class, () -> parse(duplicate));
  }

  @Test
  void rejectsInvalidUtf8AndOversizedDocuments() {
    var invalid = artifact(new byte[] {(byte) 0xc3, (byte) 0x28});
    assertThrows(IllegalArgumentException.class, () -> parse(invalid));
    var oversized = artifact(new byte[InputPacket.MAX_BYTES + 1]);
    assertThrows(IllegalArgumentException.class, () -> parse(oversized));
  }

  @Test
  void enforcesListBoundsAndCountsModelNamesAsUnicodeCodePoints() {
    var entries = new java.util.ArrayList<String>();
    for (int i = 0; i < 10; i++) {
      entries.add("""
          {"model_ref":"%s","engine":"HTTP_JSON_ADVISORY_V1","timeout_seconds":1,
           "maximum_output_tokens":1,"maximum_cost_units":1}""".formatted(i + "😀".repeat(499)));
    }
    String prefix = DOCUMENT.substring(0, DOCUMENT.indexOf("\"models\":")) + "\"models\":[";
    assertEquals(10, parse(prefix + String.join(",", entries) + "]}").models().size());
    entries.add(entries.getFirst().replace("\"model_ref\":\"0", "\"model_ref\":\"x"));
    assertThrows(IllegalArgumentException.class, () -> parse(prefix + String.join(",", entries) + "]}"));
    assertThrows(IllegalArgumentException.class, () -> parse(prefix + "]}"));
  }

  @Test
  void checksRequestedOutputTimeoutAndDeclaredCostAgainstSelectedModelAndAllocation() {
    var configuration = parse(DOCUMENT.replace("\"maximum_cost_units\":0", "\"maximum_cost_units\":10"));
    var allocation = new AdvisoryBudget.Allocation(AdvisoryBudget.Role.ANALYST, "analyst", 20, 10);
    var request = new AdvisoryGateway.Request("attempt", "model-😀", "context", 20, Duration.ofSeconds(60));
    assertEquals(configuration.models().getFirst(), configuration.validateRequest(request, allocation));
    assertThrows(IllegalArgumentException.class, () -> configuration.validateRequest(
        new AdvisoryGateway.Request("attempt", "other", "context", 20, Duration.ofSeconds(60)), allocation));
    assertThrows(IllegalArgumentException.class, () -> configuration.validateRequest(
        new AdvisoryGateway.Request("attempt", "model-😀", "context", 21, Duration.ofSeconds(60)), allocation));
    assertThrows(IllegalArgumentException.class, () -> configuration.validateRequest(
        new AdvisoryGateway.Request("attempt", "model-😀", "context", 20, Duration.ofSeconds(60).plusNanos(1)),
        allocation));
    assertThrows(IllegalArgumentException.class, () -> configuration.validateRequest(request,
        new AdvisoryBudget.Allocation(AdvisoryBudget.Role.ANALYST, "analyst", 20, 9)));
    var narrowModel = parse(DOCUMENT.replace("\"maximum_output_tokens\":1000000", "\"maximum_output_tokens\":19")
        .replace("\"maximum_cost_units\":0", "\"maximum_cost_units\":10"));
    assertThrows(IllegalArgumentException.class, () -> narrowModel.validateRequest(request, allocation));
  }

  private static ModelConfiguration parse(String value) {
    return parse(artifact(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static ModelConfiguration parse(DigestBoundArtifact artifact) {
    return ModelConfiguration.parse("tenant-1", "env-1", artifact.reference(), artifact.digest(), artifact);
  }

  private static DigestBoundArtifact artifact(byte[] bytes) {
    String digest = Sha256.hex(bytes);
    return new DigestBoundArtifact("artifact:sha256:" + digest, digest, bytes);
  }
}
