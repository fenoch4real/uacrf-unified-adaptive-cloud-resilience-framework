package org.uacrf.advisory;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class SimulatedProviderTransport {
  enum Protocol {
    OPENAI("/v1/chat/completions"), AZURE_FOUNDRY("/openai/v1/chat/completions"), ANTHROPIC("/v1/messages"),
    GEMINI("/v1beta/models"), OLLAMA("/api/chat"), BEDROCK("/model");

    private final String path;
    Protocol(String path) { this.path = path; }
  }

  private static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration MIN_TIMEOUT = Duration.ofMillis(1);
  private final URI endpoint;
  private final String token;
  private final Protocol protocol;
  private final HttpClient http = HttpClient.newBuilder()
      .proxy(ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(2))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  private final ObjectMapper json = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .build();

  SimulatedProviderTransport(URI endpoint, String token, Protocol protocol) {
    this.protocol = Objects.requireNonNull(protocol, "protocol");
    if (endpoint == null || !"http".equalsIgnoreCase(endpoint.getScheme())
        || !("127.0.0.1".equals(endpoint.getHost()) || "[::1]".equals(endpoint.getHost())
            || "::1".equals(endpoint.getHost()))
        || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
        || !protocol.path.equals(endpoint.getPath())) {
      throw new IllegalArgumentException("Only a literal-loopback simulated provider endpoint is allowed.");
    }
    if (protocol == Protocol.OLLAMA || protocol == Protocol.BEDROCK ? token != null
        : token == null || token.length() > 32768 || !token.matches("[A-Za-z0-9._~+/-]+=*")) {
      throw new IllegalArgumentException("A valid simulated endpoint token is required.");
    }
    this.endpoint = endpoint;
    this.token = token;
  }

  AdvisoryGateway.Outcome post(AdvisoryGateway.Request request, Map<String, ?> payload,
                                Function<byte[], AdvisoryGateway.Outcome> parse) {
    Objects.requireNonNull(request, "request");
    if (request.timeout().compareTo(MIN_TIMEOUT) < 0 || request.timeout().compareTo(MAX_TIMEOUT) > 0
        || request.maxOutputTokens() > 10_000_000 || request.model().length() > 200
        || request.context().length() > InputPacket.MAX_BYTES
        || (protocol == Protocol.GEMINI && !request.model().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}"))
        || (protocol == Protocol.BEDROCK && !request.model().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}"))
        || !UnicodeText.isValid(request.model()) || !UnicodeText.isValid(request.context())) {
      throw new IllegalArgumentException("Invalid simulated model request bounds or text.");
    }
    byte[] body = json.writeValueAsBytes(payload);
    if (body.length > InputPacket.MAX_BYTES) {
      throw new IllegalArgumentException("The simulated model request exceeds the byte limit.");
    }
    URI target = switch (protocol) {
      case GEMINI -> endpoint.resolve("/v1beta/models/" + request.model() + ":generateContent");
      case BEDROCK -> endpoint.resolve("/model/" + request.model() + "/converse");
      default -> endpoint;
    };
    HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(request.timeout())
        .header("Content-Type", "application/json");
    if (protocol == Protocol.GEMINI) builder.header("x-goog-api-key", token);
    else if (protocol != Protocol.OLLAMA && protocol != Protocol.BEDROCK) {
      builder.header("Authorization", "Bearer " + token);
    }
    if (protocol == Protocol.ANTHROPIC) builder.header("anthropic-version", "2026-10-21");
    HttpRequest httpRequest = builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    var pending = http.sendAsync(httpRequest, info -> InternalJsonClient.limitedBodySubscriber());
    try {
      HttpResponse<byte[]> response = pending.get(request.timeout().toNanos(), TimeUnit.NANOSECONDS);
      if (response.statusCode() != 200) {
        return new AdvisoryGateway.Unknown("Simulated provider returned a non-success status.");
      }
      return parse.apply(response.body());
    } catch (InterruptedException error) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      return new AdvisoryGateway.Unknown("Simulated provider request was interrupted.");
    } catch (TimeoutException error) {
      pending.cancel(true);
      return new AdvisoryGateway.Unknown("Simulated provider request timed out.");
    } catch (ExecutionException error) {
      if (error.getCause() instanceof HttpTimeoutException) {
        return new AdvisoryGateway.Unknown("Simulated provider request timed out.");
      }
      return new AdvisoryGateway.Unknown("Simulated provider request failed.");
    }
  }

  Object readJson(byte[] bytes) {
    return json.readValue(bytes, Object.class);
  }
}
