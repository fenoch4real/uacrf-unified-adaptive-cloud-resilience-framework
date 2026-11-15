package org.uacrf.advisory;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;

final class AnthropicGateway implements AdvisoryGateway {
  private final SimulatedProviderTransport transport;

  AnthropicGateway(URI endpoint, String token) {
    transport = new SimulatedProviderTransport(endpoint, token, SimulatedProviderTransport.Protocol.ANTHROPIC);
  }

  @Override
  public Outcome invoke(Request request) {
    Objects.requireNonNull(request, "request");
    return transport.post(request, Map.of(
        "model", request.model(),
        "messages", List.of(Map.of("role", "user", "content", request.context())),
        "max_tokens", request.maxOutputTokens(),
        "stream", false), bytes -> parse(bytes, request.maxOutputTokens()));
  }

  private Outcome parse(byte[] bytes, int maxOutputTokens) {
    try {
      Object value = transport.readJson(bytes);
      if (value instanceof Map<?, ?> body
          && "message".equals(body.get("type")) && "assistant".equals(body.get("role"))
          && "end_turn".equals(body.get("stop_reason")) && body.get("stop_details") == null
          && body.get("content") instanceof List<?> blocks && blocks.size() == 1
          && blocks.getFirst() instanceof Map<?, ?> block && "text".equals(block.get("type"))
          && block.get("text") instanceof String text && !text.isBlank() && UnicodeText.isValid(text)
          && (block.get("citations") == null
              || block.get("citations") instanceof List<?> citations && citations.isEmpty())
          && body.get("usage") instanceof Map<?, ?> usage
          && usage.get("input_tokens") instanceof Integer input && input >= 0
          && usage.get("output_tokens") instanceof Integer output && output >= 0 && output <= maxOutputTokens
          && zeroOrAbsent(usage.get("cache_creation_input_tokens"))
          && zeroOrAbsent(usage.get("cache_read_input_tokens"))
          && noAdditionalUsage(usage.get("cache_creation"))
          && noAdditionalUsage(usage.get("server_tool_use"))) {
        return new Response(text, input, output);
      }
    } catch (JacksonException error) {
      return new Unknown("Simulated provider returned invalid JSON.");
    }
    return new Unknown("Simulated provider returned an incomplete or unsupported response.");
  }

  private static boolean zeroOrAbsent(Object value) {
    return value == null || Integer.valueOf(0).equals(value);
  }

  private static boolean noAdditionalUsage(Object value) {
    return value == null
        || value instanceof Map<?, ?> tools && tools.values().stream().allMatch(Integer.valueOf(0)::equals);
  }
}
