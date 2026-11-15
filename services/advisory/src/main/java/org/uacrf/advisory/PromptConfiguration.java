package org.uacrf.advisory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PromptConfiguration {
  private static final Set<String> PROMPT_FIELDS = Set.of("instructions_digest", "text");
  private final List<Prompt> prompts;

  private PromptConfiguration(List<Prompt> prompts) {
    this.prompts = List.copyOf(prompts);
  }

  record Prompt(String declaredInstructionsDigest, String text) {}

  List<Prompt> prompts() {
    return prompts;
  }

  // The contract does not specify how instructions_digest is calculated from text.
  static PromptConfiguration parse(String tenantId, String environmentId, String expectedReference,
                                   String expectedDigest, DigestBoundArtifact artifact) {
    Map<?, ?> document = ScopedArtifactJson.read(tenantId, environmentId, expectedReference, expectedDigest,
        artifact, "UACRF_ADVISORY_PROMPTS_V1", "prompts");
    if (!(document.get("prompts") instanceof List<?> entries) || entries.isEmpty() || entries.size() > 10) {
      throw new IllegalArgumentException("Prompt configuration has invalid entries.");
    }
    List<Prompt> prompts = new ArrayList<>();
    Set<String> digests = new HashSet<>();
    for (Object entry : entries) {
      if (!(entry instanceof Map<?, ?> prompt) || !prompt.keySet().equals(PROMPT_FIELDS)
          || !(prompt.get("instructions_digest") instanceof String digest) || !digest.matches("[a-f0-9]{64}")
          || !digests.add(digest) || !(prompt.get("text") instanceof String text) || text.isBlank()
          || !UnicodeText.isValid(text) || text.codePointCount(0, text.length()) > 20_000) {
        throw new IllegalArgumentException("Prompt configuration contains an invalid or ambiguous prompt.");
      }
      prompts.add(new Prompt(digest, text));
    }
    return new PromptConfiguration(prompts);
  }
}
