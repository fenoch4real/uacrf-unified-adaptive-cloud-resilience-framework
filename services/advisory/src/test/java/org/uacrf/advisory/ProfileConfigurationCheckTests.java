package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProfileConfigurationCheckTests {
  private static final String DIGEST = "a".repeat(64);
  private static final String MODELS = """
      {"protocol":"UACRF_ADVISORY_MODELS_V1","tenant_id":"tenant","environment_id":"env",
       "models":[{"model_ref":"model-a","engine":"HTTP_JSON_ADVISORY_V1","timeout_seconds":30,
       "maximum_output_tokens":30,"maximum_cost_units":5},
       {"model_ref":"model-b","engine":"HTTP_JSON_ADVISORY_V1","timeout_seconds":30,
       "maximum_output_tokens":30,"maximum_cost_units":5}]}""";
  private static final String PROMPTS = """
      {"protocol":"UACRF_ADVISORY_PROMPTS_V1","tenant_id":"tenant","environment_id":"env",
       "prompts":[{"instructions_digest":"%s","text":"instructions"}]}""".formatted(DIGEST);

  private final ModelConfiguration models = models();
  private final PromptConfiguration prompts = prompts();
  private final AdvisoryBudget.Allocation allocation =
      new AdvisoryBudget.Allocation(AdvisoryBudget.Role.ANALYST, "profile", 100, 5);
  private final AdvisoryGateway.Request request =
      new AdvisoryGateway.Request("attempt", "model-a", "context", 30, Duration.ofSeconds(30));
  private final ProfileConfigurationCheck.VersionPins pins = pins("model-a", DIGEST, 100, 5, 30);

  @Test
  void selectsThePinnedConfiguredModelAndDeclaredPromptAtExactLimits() {
    var selection = ProfileConfigurationCheck.check(pins, models, prompts, request, allocation);
    assertEquals("model-a", selection.model().reference());
    assertEquals("instructions", selection.prompt().text());
  }

  @Test
  void rejectsModelAndInstructionPinsEvenWhenAnotherModelIsConfigured() {
    assertThrows(IllegalArgumentException.class,
        () -> ProfileConfigurationCheck.check(pins("model-b", DIGEST, 100, 5, 30),
            models, prompts, request, allocation));
    assertThrows(IllegalArgumentException.class,
        () -> ProfileConfigurationCheck.check(pins("model-a", "b".repeat(64), 100, 5, 30),
            models, prompts, request, allocation));
    var unknown = new AdvisoryGateway.Request("attempt", "unknown", "context", 30, Duration.ofSeconds(30));
    assertThrows(IllegalArgumentException.class,
        () -> ProfileConfigurationCheck.check(pins("unknown", DIGEST, 100, 5, 30),
            models, prompts, unknown, allocation));
  }

  @Test
  void rejectsAllocationsAndTimeoutBeyondVersionLimitsAndEnforcesModelLimits() {
    assertThrows(IllegalArgumentException.class,
        () -> ProfileConfigurationCheck.check(pins("model-a", DIGEST, 99, 5, 30),
            models, prompts, request, allocation));
    assertThrows(IllegalArgumentException.class,
        () -> ProfileConfigurationCheck.check(pins("model-a", DIGEST, 100, 4, 30),
            models, prompts, request, allocation));
    assertThrows(IllegalArgumentException.class,
        () -> ProfileConfigurationCheck.check(pins("model-a", DIGEST, 100, 5, 29),
            models, prompts, request, allocation));
    var excessiveOutput = new AdvisoryGateway.Request("attempt", "model-a", "context", 31, Duration.ofSeconds(30));
    assertThrows(IllegalArgumentException.class,
        () -> ProfileConfigurationCheck.check(pins, models, prompts, excessiveOutput, allocation));
    var excessiveDuration = new AdvisoryGateway.Request(
        "attempt", "model-a", "context", 30, Duration.ofSeconds(30).plusNanos(1));
    assertThrows(IllegalArgumentException.class,
        () -> ProfileConfigurationCheck.check(pins, models, prompts, excessiveDuration, allocation));
    var lowCost = new AdvisoryBudget.Allocation(AdvisoryBudget.Role.ANALYST, "profile", 100, 4);
    assertThrows(IllegalArgumentException.class,
        () -> ProfileConfigurationCheck.check(pins, models, prompts, request, lowCost));
  }

  @Test
  void rejectsMalformedPins() {
    assertThrows(IllegalArgumentException.class, () -> pins(" ", DIGEST, 100, 5, 30));
    assertThrows(IllegalArgumentException.class, () -> pins("model-a", "A".repeat(64), 100, 5, 30));
    assertThrows(IllegalArgumentException.class, () -> pins("model-a", DIGEST, -1, 5, 30));
    assertThrows(IllegalArgumentException.class, () -> pins("model-a", DIGEST, 100, -1, 30));
    assertThrows(IllegalArgumentException.class, () -> pins("model-a", DIGEST, 100, 5, 0));
  }

  private static ProfileConfigurationCheck.VersionPins pins(
      String model, String digest, int tokens, int cost, int duration) {
    return new ProfileConfigurationCheck.VersionPins(model, digest, tokens, cost, duration);
  }

  private static DigestBoundArtifact artifact(String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    String digest = Sha256.hex(bytes);
    return new DigestBoundArtifact("artifact:sha256:" + digest, digest, bytes);
  }

  private static ModelConfiguration models() {
    var artifact = artifact(MODELS);
    return ModelConfiguration.parse("tenant", "env", artifact.reference(), artifact.digest(), artifact);
  }

  private static PromptConfiguration prompts() {
    var artifact = artifact(PROMPTS);
    return PromptConfiguration.parse("tenant", "env", artifact.reference(), artifact.digest(), artifact);
  }
}
