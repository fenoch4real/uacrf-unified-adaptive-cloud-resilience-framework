package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AzureFoundryGatewayTests {
  private static final String RESPONSE = """
      {"prompt_filter_results":[{"prompt_index":0,"content_filter_results":{
          "hate":{"filtered":false,"severity":"safe"}}}],
       "choices":[{"index":0,"finish_reason":"stop",
          "content_filter_results":{"hate":{"filtered":false,"severity":"safe"}},
          "message":{"role":"assistant","content":"Advice 😀","annotations":[]}}],
       "usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}
      """;
  private final AtomicReference<String> reply = new AtomicReference<>(RESPONSE);
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<byte[]> received = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> apiKey = new AtomicReference<>();
  private final AtomicReference<String> path = new AtomicReference<>();
  private final AtomicInteger calls = new AtomicInteger();
  private final ObjectMapper json = new ObjectMapper();
  private HttpServer server;
  private URI endpoint;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/openai/v1/chat/completions", exchange -> {
      calls.incrementAndGet();
      path.set(exchange.getRequestURI().getRawPath());
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      apiKey.set(exchange.getRequestHeaders().getFirst("api-key"));
      received.set(exchange.getRequestBody().readAllBytes());
      byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status.get(), bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
    server.start();
    endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/openai/v1/chat/completions");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void sendsDeploymentAndBearerToFoundryV1Endpoint() {
    var gateway = new AzureFoundryGateway(endpoint, "fixture-token");
    assertEquals(new AdvisoryGateway.Response("Advice 😀", 10, 3), gateway.invoke(request()));
    assertEquals(1, calls.get());
    assertEquals("/openai/v1/chat/completions", path.get());
    assertEquals("Bearer fixture-token", authorization.get());
    assertEquals(null, apiKey.get());
    JsonNode body = json.readTree(received.get());
    assertEquals("deployment-name", body.get("model").asString());
    assertEquals(20, body.get("max_completion_tokens").asInt());
    assertEquals("user", body.at("/messages/0/role").asString());
    assertEquals("Context 😀", body.at("/messages/0/content").asString());
    assertEquals(false, body.get("stream").asBoolean());
    reply.set("""
        {"choices":[{"index":0,"finish_reason":"stop",
          "message":{"role":"assistant","content":"Advice 😀"}}],
         "usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}
        """);
    assertEquals(new AdvisoryGateway.Response("Advice 😀", 10, 3), gateway.invoke(request()));
  }

  @Test
  void rejectsReportedFilteringAndMalformedFilterResults() {
    var gateway = new AzureFoundryGateway(endpoint, "fixture-token");
    List<String> invalid = List.of(
        RESPONSE.replaceFirst("\"filtered\":false", "\"filtered\":true"),
        RESPONSE.replace("\"content_filter_results\":{\"hate\":{\"filtered\":false,\"severity\":\"safe\"}}",
            "\"content_filter_results\":{\"hate\":{\"filtered\":true,\"severity\":\"safe\"}}"),
        RESPONSE.replace("\"prompt_index\":0", "\"prompt_index\":1"),
        RESPONSE.replace("\"prompt_index\":0", "\"prompt_index\":0,\"prompt_index\":0"),
        RESPONSE.replace("\"filtered\":false", "\"filtered\":\"false\""),
        RESPONSE.replace("\"severity\":\"safe\"", "\"error\":{\"code\":\"failed\"}"),
        RESPONSE.replace("\"annotations\":[]", "\"annotations\":[{\"type\":\"url_citation\"}]"),
        RESPONSE.replace("\"finish_reason\":\"stop\"", "\"finish_reason\":\"content_filter\""),
        RESPONSE.replace("\"total_tokens\":13", "\"total_tokens\":14"),
        RESPONSE.replace("\"prompt_filter_results\":[", "\"error\":{\"code\":\"failed\"},\"prompt_filter_results\":["),
        RESPONSE + "{}",
        "not-json");
    for (String body : invalid) {
      reply.set(body);
      assertInstanceOf(AdvisoryGateway.Unknown.class, gateway.invoke(request()), body);
    }
    status.set(400);
    reply.set("{\"error\":{\"code\":\"content_filter\"}}");
    assertInstanceOf(AdvisoryGateway.Unknown.class, gateway.invoke(request()));
    assertEquals(invalid.size() + 1, calls.get());
  }

  @Test
  void rejectsRemoteOrWrongProtocolEndpoints() {
    for (String url : List.of("https://example.openai.azure.com/openai/v1/chat/completions",
        "http://localhost/openai/v1/chat/completions", "http://127.0.0.1/openai/v1/chat/completions?api-version=v1",
        "http://user@127.0.0.1/openai/v1/chat/completions", "http://127.0.0.1/v1/chat/completions")) {
      assertThrows(IllegalArgumentException.class, () -> new AzureFoundryGateway(URI.create(url), "fixture-token"));
    }
    assertThrows(IllegalArgumentException.class, () -> new AzureFoundryGateway(endpoint, "bad\r\ntoken"));
    assertEquals(0, calls.get());
  }

  private static AdvisoryGateway.Request request() {
    return new AdvisoryGateway.Request("attempt-1", "deployment-name", "Context 😀", 20, Duration.ofSeconds(2));
  }
}
