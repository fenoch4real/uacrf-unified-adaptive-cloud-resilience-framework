package org.uacrf.advisory;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

  // Caller-supplied profiles and requests are not verified owner records or dispatch authority.
  static Map<String, Selection> checkAll(AdvisoryBudget budget, ModelConfiguration models,
                                         PromptConfiguration prompts, Map<String, VersionPins> profiles,
                                         Map<String, AdvisoryGateway.Request> requests) {
    Objects.requireNonNull(budget, "budget");
    var profileSnapshot = snapshot(profiles);
    var requestSnapshot = snapshot(requests);
    Set<String> required = new HashSet<>();
    for (var allocation : budget.allocations()) {
      required.add(allocation.agentProfileId());
    }
    if (!profileSnapshot.keySet().equals(required) || !requestSnapshot.keySet().equals(required)) {
      throw new IllegalArgumentException("Profile pins and requests must match the budget allocations exactly.");
    }
    Set<String> attemptIds = new HashSet<>();
    Map<String, Selection> selections = new HashMap<>();
    for (var allocation : budget.allocations()) {
      String profile = allocation.agentProfileId();
      var request = requestSnapshot.get(profile);
      if (!attemptIds.add(request.attemptId())) {
        throw new IllegalArgumentException("Each allocation requires a distinct attempt ID.");
      }
      selections.put(profile, check(profileSnapshot.get(profile), models, prompts, request, allocation));
    }
    return Map.copyOf(selections);
  }

  private static <T> Map<String, T> snapshot(Map<String, T> values) {
    if (values == null || values.isEmpty() || values.size() > 3) {
      throw new IllegalArgumentException("One to three profile entries are required.");
    }
    Map<String, T> copy = new HashMap<>();
    for (var entry : values.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null || copy.size() >= 3) {
        throw new IllegalArgumentException("Invalid profile entries.");
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Map.copyOf(copy);
  }

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
