package org.uacrf.advisory;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;

final class OllamaGateway implements AdvisoryGateway {
  private final SimulatedProviderTransport transport;

  OllamaGateway(URI endpoint) {
    transport = new SimulatedProviderTransport(endpoint, null, SimulatedProviderTransport.Protocol.OLLAMA);
  }

  @Override
  public Outcome invoke(Request request) {
    Objects.requireNonNull(request, "request");
    return transport.post(request, Map.of(
        "model", request.model(),
        "messages", List.of(Map.of("role", "user", "content", request.context())),
        "options", Map.of("num_predict", request.maxOutputTokens()),
        "think", false,
        "stream", false), bytes -> parse(bytes, request));
  }

  private Outcome parse(byte[] bytes, Request request) {
    try {
      Object value = transport.readJson(bytes);
      if (value instanceof Map<?, ?> body
          && body.get("error") == null
          && request.model().equals(body.get("model"))
          && Boolean.TRUE.equals(body.get("done")) && "stop".equals(body.get("done_reason"))
          && body.get("message") instanceof Map<?, ?> message && "assistant".equals(message.get("role"))
          && message.get("content") instanceof String text && !text.isBlank() && UnicodeText.isValid(text)
          && message.get("thinking") == null && message.get("tool_calls") == null
          && message.get("images") == null
          && body.get("prompt_eval_count") instanceof Integer input && input >= 0
          && body.get("eval_count") instanceof Integer output && output >= 0 && output <= request.maxOutputTokens()
          && (!body.containsKey("prompt_eval_cached_count")
              || Integer.valueOf(0).equals(body.get("prompt_eval_cached_count")))) {
        return new Response(text, input, output);
      }
    } catch (JacksonException error) {
      return new Unknown("Simulated provider returned invalid JSON.");
    }
    return new Unknown("Simulated provider returned an incomplete or unsupported response.");
  }
}
