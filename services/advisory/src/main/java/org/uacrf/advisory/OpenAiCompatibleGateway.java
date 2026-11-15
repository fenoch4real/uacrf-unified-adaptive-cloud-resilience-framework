package org.uacrf.advisory;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;

final class OpenAiCompatibleGateway implements AdvisoryGateway {
  private final SimulatedProviderTransport transport;
  private final boolean azureFoundry;

  OpenAiCompatibleGateway(URI endpoint, String token) {
    this(endpoint, token, false);
  }

  OpenAiCompatibleGateway(URI endpoint, String token, boolean azureFoundry) {
    this.azureFoundry = azureFoundry;
    transport = new SimulatedProviderTransport(endpoint, token, azureFoundry
        ? SimulatedProviderTransport.Protocol.AZURE_FOUNDRY : SimulatedProviderTransport.Protocol.OPENAI);
  }

  @Override
  public Outcome invoke(Request request) {
    Objects.requireNonNull(request, "request");
    return transport.post(request, Map.of(
        "model", request.model(),
        "messages", List.of(Map.of("role", "user", "content", request.context())),
        "max_completion_tokens", request.maxOutputTokens(),
        "n", 1,
        "stream", false), bytes -> parse(bytes, request.maxOutputTokens()));
  }

  private Outcome parse(byte[] bytes, int maxOutputTokens) {
    try {
      Object value = transport.readJson(bytes);
      if (value instanceof Map<?, ?> body
          && body.get("error") == null
          && body.get("choices") instanceof List<?> choices && choices.size() == 1
          && choices.getFirst() instanceof Map<?, ?> choice
          && Integer.valueOf(0).equals(choice.get("index"))
          && "stop".equals(choice.get("finish_reason"))
          && choice.get("message") instanceof Map<?, ?> message
          && "assistant".equals(message.get("role"))
          && message.get("content") instanceof String text && !text.isBlank() && UnicodeText.isValid(text)
          && message.get("refusal") == null && message.get("tool_calls") == null
          && message.get("function_call") == null
          && (!azureFoundry || azureResponseAccepted(body, choice, message))
          && body.get("usage") instanceof Map<?, ?> usage
          && usage.get("prompt_tokens") instanceof Integer input && input >= 0
          && usage.get("completion_tokens") instanceof Integer output && output >= 0 && output <= maxOutputTokens
          && usage.get("total_tokens") instanceof Integer total && (long) input + output == total) {
        return new Response(text, input, output);
      }
    } catch (JacksonException error) {
      return new Unknown("Simulated provider returned invalid JSON.");
    }
    return new Unknown("Simulated provider returned an incomplete or unsupported response.");
  }

  private static boolean azureResponseAccepted(Map<?, ?> body, Map<?, ?> choice, Map<?, ?> message) {
    Object annotations = message.get("annotations");
    if (annotations != null && (!(annotations instanceof List<?> list) || !list.isEmpty())) return false;
    if (choice.containsKey("content_filter_results") && !unfiltered(choice.get("content_filter_results"))) {
      return false;
    }
    if (!body.containsKey("prompt_filter_results")) return true;
    if (!(body.get("prompt_filter_results") instanceof List<?> prompts) || prompts.size() != 1
        || !(prompts.getFirst() instanceof Map<?, ?> prompt)
        || !Integer.valueOf(0).equals(prompt.get("prompt_index"))) return false;
    return unfiltered(prompt.get("content_filter_results"));
  }

  private static boolean unfiltered(Object value) {
    if (!(value instanceof Map<?, ?> categories) || categories.isEmpty()) return false;
    for (Object result : categories.values()) {
      if (!(result instanceof Map<?, ?> category) || !Boolean.FALSE.equals(category.get("filtered"))
          || category.get("error") != null) return false;
    }
    return true;
  }
}
