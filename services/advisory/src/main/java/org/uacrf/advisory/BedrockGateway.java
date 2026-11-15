package org.uacrf.advisory;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;

final class BedrockGateway implements AdvisoryGateway {
  private final SimulatedProviderTransport transport;

  BedrockGateway(URI endpoint) {
    transport = new SimulatedProviderTransport(endpoint, null, SimulatedProviderTransport.Protocol.BEDROCK);
  }

  @Override
  public Outcome invoke(Request request) {
    Objects.requireNonNull(request, "request");
    return transport.post(request, Map.of(
        "messages", List.of(Map.of("role", "user", "content", List.of(Map.of("text", request.context())))),
        "inferenceConfig", Map.of("maxTokens", request.maxOutputTokens())),
        bytes -> parse(bytes, request.maxOutputTokens()));
  }

  private Outcome parse(byte[] bytes, int maxOutputTokens) {
    try {
      Object value = transport.readJson(bytes);
      if (value instanceof Map<?, ?> body && body.get("error") == null
          && "end_turn".equals(body.get("stopReason"))
          && body.get("trace") == null && body.get("additionalModelResponseFields") == null
          && body.get("output") instanceof Map<?, ?> output && output.size() == 1
          && output.get("message") instanceof Map<?, ?> message && "assistant".equals(message.get("role"))
          && message.get("content") instanceof List<?> blocks && blocks.size() == 1
          && blocks.getFirst() instanceof Map<?, ?> block && block.size() == 1
          && block.get("text") instanceof String text && !text.isBlank() && UnicodeText.isValid(text)
          && body.get("usage") instanceof Map<?, ?> usage
          && usage.get("inputTokens") instanceof Integer input && input >= 0
          && usage.get("outputTokens") instanceof Integer generated && generated >= 0 && generated <= maxOutputTokens
          && usage.get("totalTokens") instanceof Integer total && (long) input + generated == total
          && zeroOrAbsent(usage.get("cacheReadInputTokens"))
          && zeroOrAbsent(usage.get("cacheWriteInputTokens"))
          && (usage.get("cacheDetails") == null
              || usage.get("cacheDetails") instanceof List<?> details && details.isEmpty())) {
        return new Response(text, input, generated);
      }
    } catch (JacksonException error) {
      return new Unknown("Simulated provider returned invalid JSON.");
    }
    return new Unknown("Simulated provider returned an incomplete or unsupported response.");
  }

  private static boolean zeroOrAbsent(Object value) {
    return value == null || Integer.valueOf(0).equals(value);
  }
}
