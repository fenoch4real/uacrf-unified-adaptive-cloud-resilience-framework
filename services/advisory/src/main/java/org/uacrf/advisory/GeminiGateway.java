package org.uacrf.advisory;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;

final class GeminiGateway implements AdvisoryGateway {
  private final SimulatedProviderTransport transport;

  GeminiGateway(URI endpoint, String token) {
    transport = new SimulatedProviderTransport(endpoint, token, SimulatedProviderTransport.Protocol.GEMINI);
  }

  @Override
  public Outcome invoke(Request request) {
    Objects.requireNonNull(request, "request");
    return transport.post(request, Map.of(
        "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", request.context())))),
        "generationConfig", Map.of("maxOutputTokens", request.maxOutputTokens(), "candidateCount", 1)),
        bytes -> parse(bytes, request.maxOutputTokens()));
  }

  private Outcome parse(byte[] bytes, int maxOutputTokens) {
    try {
      Object value = transport.readJson(bytes);
      if (value instanceof Map<?, ?> body
          && promptAllowed(body.get("promptFeedback"))
          && body.get("candidates") instanceof List<?> candidates && candidates.size() == 1
          && candidates.getFirst() instanceof Map<?, ?> candidate
          && (!candidate.containsKey("index") || Integer.valueOf(0).equals(candidate.get("index")))
          && "STOP".equals(candidate.get("finishReason"))
          && candidate.get("citationMetadata") == null && candidate.get("groundingMetadata") == null
          && safetyAllowed(candidate.get("safetyRatings"))
          && candidate.get("content") instanceof Map<?, ?> content && "model".equals(content.get("role"))
          && content.get("parts") instanceof List<?> parts && parts.size() == 1
          && parts.getFirst() instanceof Map<?, ?> part && part.size() == 1
          && part.get("text") instanceof String text && !text.isBlank() && UnicodeText.isValid(text)
          && body.get("usageMetadata") instanceof Map<?, ?> usage
          && usage.get("promptTokenCount") instanceof Integer input && input >= 0
          && usage.get("candidatesTokenCount") instanceof Integer output && output >= 0
          && optionalCount(usage, "thoughtsTokenCount") instanceof Integer thoughts && thoughts >= 0
          && (long) output + thoughts <= maxOutputTokens
          && usage.get("totalTokenCount") instanceof Integer total && (long) input + output + thoughts == total
          && Integer.valueOf(0).equals(optionalCount(usage, "cachedContentTokenCount"))
          && Integer.valueOf(0).equals(optionalCount(usage, "toolUsePromptTokenCount"))) {
        return new Response(text, input, output + thoughts);
      }
    } catch (JacksonException error) {
      return new Unknown("Simulated provider returned invalid JSON.");
    }
    return new Unknown("Simulated provider returned an incomplete or unsupported response.");
  }

  private static Object optionalCount(Map<?, ?> usage, String key) {
    return usage.containsKey(key) ? usage.get(key) : Integer.valueOf(0);
  }

  private static boolean promptAllowed(Object value) {
    return value == null || value instanceof Map<?, ?> feedback && feedback.get("blockReason") == null
        && safetyAllowed(feedback.get("safetyRatings"));
  }

  private static boolean safetyAllowed(Object value) {
    if (value == null) return true;
    if (!(value instanceof List<?> ratings)) return false;
    for (Object rating : ratings) {
      if (!(rating instanceof Map<?, ?> item)
          || item.containsKey("blocked") && !Boolean.FALSE.equals(item.get("blocked"))) return false;
    }
    return true;
  }
}
