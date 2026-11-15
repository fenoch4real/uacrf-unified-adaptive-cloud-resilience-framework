package org.uacrf.advisory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ModelConfiguration {
  private static final Set<String> MODEL_FIELDS =
      Set.of("model_ref", "engine", "timeout_seconds", "maximum_output_tokens", "maximum_cost_units");

  private final List<Model> models;

  private ModelConfiguration(List<Model> models) {
    this.models = List.copyOf(models);
  }

  record Model(String reference, int timeoutSeconds, int maxOutputTokens, int maxCostUnits) {}

  List<Model> models() {
    return models;
  }

  // This checks declared limits only; it does not estimate input tokens or enforce provider cost.
  Model validateRequest(AdvisoryGateway.Request request, AdvisoryBudget.Allocation allocation) {
    java.util.Objects.requireNonNull(request, "request");
    return validateLimits(request.model(), request.maxOutputTokens(), request.timeout(), allocation);
  }

  Model validateLimits(String reference, int maxOutputTokens, Duration timeout,
                       AdvisoryBudget.Allocation allocation) {
    java.util.Objects.requireNonNull(reference, "reference");
    java.util.Objects.requireNonNull(timeout, "timeout");
    java.util.Objects.requireNonNull(allocation, "allocation");
    Model model = models.stream().filter(candidate -> candidate.reference().equals(reference))
        .findFirst().orElseThrow(() -> new IllegalArgumentException("Model is not in the configuration."));
    if (maxOutputTokens < 1 || maxOutputTokens > model.maxOutputTokens()
        || maxOutputTokens > allocation.tokens()
        || timeout.isZero() || timeout.isNegative()
        || timeout.compareTo(Duration.ofSeconds(model.timeoutSeconds())) > 0
        || model.maxCostUnits() > allocation.costUnits()) {
      throw new IllegalArgumentException("Model request exceeds the declared configuration or allocation.");
    }
    return model;
  }

  // Integrity and shape only: this does not authorize or approve the artifact or model.
  static ModelConfiguration parse(String tenantId, String environmentId, String expectedReference,
                                  String expectedDigest, DigestBoundArtifact artifact) {
    Map<?, ?> document = ScopedArtifactJson.read(tenantId, environmentId, expectedReference, expectedDigest,
        artifact, "UACRF_ADVISORY_MODELS_V1", "models");
    if (!(document.get("models") instanceof List<?> entries) || entries.isEmpty() || entries.size() > 10) {
      throw new IllegalArgumentException("Model configuration has invalid entries.");
    }
    List<Model> models = new ArrayList<>();
    Set<String> references = new HashSet<>();
    for (Object entry : entries) {
      if (!(entry instanceof Map<?, ?> model) || !model.keySet().equals(MODEL_FIELDS)
          || !(model.get("model_ref") instanceof String reference) || reference.isBlank()
          || !UnicodeText.isValid(reference) || reference.codePointCount(0, reference.length()) > 500
          || !references.add(reference) || !"HTTP_JSON_ADVISORY_V1".equals(model.get("engine"))
          || !(model.get("timeout_seconds") instanceof Integer timeout) || timeout < 1 || timeout > 60
          || !(model.get("maximum_output_tokens") instanceof Integer output) || output < 1 || output > 1_000_000
          || !(model.get("maximum_cost_units") instanceof Integer cost) || cost < 0 || cost > 1_000_000_000) {
        throw new IllegalArgumentException("Model configuration contains an invalid or ambiguous model.");
      }
      models.add(new Model(reference, timeout, output, cost));
    }
    return new ModelConfiguration(models);
  }
}
