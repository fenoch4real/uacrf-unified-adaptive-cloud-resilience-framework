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

class GeminiGatewayTests {
  private static final String RESPONSE = """
      {"candidates":[{"index":0,"finishReason":"STOP",
        "content":{"role":"model","parts":[{"text":"Advice 😀"}]},
        "safetyRatings":[{"category":"HARM_CATEGORY_HARASSMENT","blocked":false}]}],
       "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3,
        "thoughtsTokenCount":2,"totalTokenCount":15}}
      """;
  private final AtomicReference<String> reply = new AtomicReference<>(RESPONSE);
  private final AtomicReference<byte[]> received = new AtomicReference<>();
  private final AtomicReference<String> key = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> path = new AtomicReference<>();
  private final AtomicReference<String> method = new AtomicReference<>();
  private final AtomicInteger calls = new AtomicInteger();
  private final ObjectMapper json = new ObjectMapper();
  private HttpServer server;
  private URI endpoint;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1beta/models/", exchange -> {
      calls.incrementAndGet();
      path.set(exchange.getRequestURI().getRawPath());
      method.set(exchange.getRequestMethod());
      key.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      received.set(exchange.getRequestBody().readAllBytes());
      byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
    server.start();
    endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/models");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void mapsRequestAndIncludesThinkingInReportedOutput() {
    var gateway = new GeminiGateway(endpoint, "fixture-token");
    assertEquals(new AdvisoryGateway.Response("Advice 😀", 10, 5), gateway.invoke(request("gemini-test")));
    assertEquals(1, calls.get());
    assertEquals("POST", method.get());
    assertEquals("/v1beta/models/gemini-test:generateContent", path.get());
    assertEquals("fixture-token", key.get());
    assertEquals(null, authorization.get());
    JsonNode body = json.readTree(received.get());
    assertEquals(2, body.size());
    assertEquals(1, body.get("contents").size());
    assertEquals("user", body.at("/contents/0/role").asString());
    assertEquals("Context 😀", body.at("/contents/0/parts/0/text").asString());
    assertEquals(20, body.at("/generationConfig/maxOutputTokens").asInt());
    assertEquals(1, body.at("/generationConfig/candidateCount").asInt());
    reply.set("""
        {"candidates":[{"finishReason":"STOP","content":{"role":"model","parts":[{"text":"Advice 😀"}]}}],
         "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3,"totalTokenCount":13}}
        """);
    assertEquals(new AdvisoryGateway.Response("Advice 😀", 10, 3), gateway.invoke(request("gemini-test")));
  }

  @Test
  void rejectsBlockedUnsupportedOrUnaccountedResponses() {
    var gateway = new GeminiGateway(endpoint, "fixture-token");
    List<String> invalid = List.of(
        RESPONSE.replace("\"STOP\"", "\"MAX_TOKENS\""),
        RESPONSE.replace("\"STOP\"", "\"SAFETY\""),
        RESPONSE.replace("\"blocked\":false", "\"blocked\":true"),
        RESPONSE.replace("\"candidates\":[", "\"promptFeedback\":{\"blockReason\":\"SAFETY\"},\"candidates\":["),
        RESPONSE.replace("\"candidates\":[",
            "\"promptFeedback\":{\"safetyRatings\":[{\"blocked\":true}]},\"candidates\":["),
        RESPONSE.replace("\"index\":0", "\"index\":1"),
        RESPONSE.replace("\"parts\":[{\"text\":\"Advice 😀\"}]", "\"parts\":[{\"functionCall\":{}}]"),
        RESPONSE.replace("\"text\":\"Advice 😀\"", "\"text\":\"\\ud800\""),
        RESPONSE.replace("\"text\":\"Advice 😀\"", "\"text\":\"Advice 😀\",\"thought\":true"),
        RESPONSE.replace("\"candidatesTokenCount\":3", "\"candidatesTokenCount\":19"),
        RESPONSE.replace("\"thoughtsTokenCount\":2", "\"thoughtsTokenCount\":-1"),
        RESPONSE.replace("\"totalTokenCount\":15", "\"totalTokenCount\":14"),
        RESPONSE.replace("\"totalTokenCount\":15", "\"totalTokenCount\":15,\"cachedContentTokenCount\":1"),
        RESPONSE.replace("\"totalTokenCount\":15", "\"totalTokenCount\":15,\"toolUsePromptTokenCount\":1"),
        RESPONSE.replace("\"promptTokenCount\":10", "\"promptTokenCount\":10,\"promptTokenCount\":10"),
        RESPONSE.replace("\"usageMetadata\":{", "\"usage_missing\":{"),
        RESPONSE + "{}",
        "not-json");
    for (String body : invalid) {
      reply.set(body);
      assertInstanceOf(AdvisoryGateway.Unknown.class, gateway.invoke(request("gemini-test")), body);
    }
    assertEquals(invalid.size(), calls.get());
  }

  @Test
  void rejectsRemoteEndpointsAndPathInjection() {
    for (String url : List.of("https://generativelanguage.googleapis.com/v1beta/models",
        "http://localhost/v1beta/models", "http://127.0.0.1/v1beta/models?key=secret",
        "http://user@127.0.0.1/v1beta/models", "http://127.0.0.1/v1/messages")) {
      assertThrows(IllegalArgumentException.class, () -> new GeminiGateway(URI.create(url), "fixture-token"));
    }
    assertThrows(IllegalArgumentException.class, () -> new GeminiGateway(endpoint, "bad\r\ntoken"));
    var gateway = new GeminiGateway(endpoint, "fixture-token");
    for (String model : List.of("../other", "models/gemini-test", "x:generateContent", "x?key=abc", "x%2fother")) {
      assertThrows(IllegalArgumentException.class, () -> gateway.invoke(request(model)));
    }
    assertEquals(0, calls.get());
  }

  private static AdvisoryGateway.Request request(String model) {
    return new AdvisoryGateway.Request("attempt-1", model, "Context 😀", 20, Duration.ofSeconds(2));
  }
}
