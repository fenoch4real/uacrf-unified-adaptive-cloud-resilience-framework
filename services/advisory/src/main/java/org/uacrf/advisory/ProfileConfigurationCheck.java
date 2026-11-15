package org.uacrf.advisory;

import java.time.Duration;
import java.util.Objects;

final class ProfileConfigurationCheck {
  private ProfileConfigurationCheck() {}

  record VersionPins(String modelReference, String instructionsDigest,
                     int maxTokens, int maxCostUnits, int maxDurationSeconds) {
    VersionPins {
      if (modelReference == null || modelReference.isBlank() || !UnicodeText.isValid(modelReference)
          || instructionsDigest == null || !instructionsDigest.matches("[a-f0-9]{64}")
          || maxTokens < 0 || maxCostUnits < 0 || maxDurationSeconds < 1) {
        throw new IllegalArgumentException("Invalid profile version pins.");
      }
    }
  }

  record Selection(ModelConfiguration.Model model, PromptConfiguration.Prompt prompt) {}

  // Supplied pins are not proof of an activated profile, and the prompt digest is only a declaration.
  static Selection check(VersionPins pins, ModelConfiguration models, PromptConfiguration prompts,
                         AdvisoryGateway.Request request, AdvisoryBudget.Allocation allocation) {
    Objects.requireNonNull(pins, "pins");
    Objects.requireNonNull(models, "models");
    Objects.requireNonNull(prompts, "prompts");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(allocation, "allocation");
    if (!pins.modelReference().equals(request.model())
        || allocation.tokens() > pins.maxTokens()
        || allocation.costUnits() > pins.maxCostUnits()
        || request.timeout().compareTo(Duration.ofSeconds(pins.maxDurationSeconds())) > 0) {
      throw new IllegalArgumentException("Request or allocation does not match the profile version pins.");
    }
    var model = models.validateRequest(request, allocation);
    var prompt = prompts.prompts().stream()
        .filter(candidate -> candidate.declaredInstructionsDigest().equals(pins.instructionsDigest()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Profile instructions are not in the prompt configuration."));
    return new Selection(model, prompt);
  }
}
