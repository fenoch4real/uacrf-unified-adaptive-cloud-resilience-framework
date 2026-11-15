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

class AnthropicGatewayTests {
  private static final String RESPONSE = """
      {"type":"message","role":"assistant","stop_reason":"end_turn","stop_details":null,
       "content":[{"type":"text","text":"Advice 😀","citations":null}],
       "usage":{"input_tokens":10,"output_tokens":3,"cache_creation_input_tokens":0,
       "cache_read_input_tokens":0,"server_tool_use":{"web_search_requests":0}}}
      """;
  private final AtomicReference<String> reply = new AtomicReference<>(RESPONSE);
  private final AtomicReference<byte[]> received = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> version = new AtomicReference<>();
  private final AtomicReference<String> method = new AtomicReference<>();
  private final AtomicInteger calls = new AtomicInteger();
  private final ObjectMapper json = new ObjectMapper();
  private HttpServer server;
  private URI endpoint;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/messages", exchange -> {
      calls.incrementAndGet();
      method.set(exchange.getRequestMethod());
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      version.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
      received.set(exchange.getRequestBody().readAllBytes());
      byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
    server.start();
    endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void sendsMessagesRequestWithVersionAndReturnsUsage() {
    var gateway = new AnthropicGateway(endpoint, "fixture-token");
    assertEquals(new AdvisoryGateway.Response("Advice 😀", 10, 3), gateway.invoke(request()));
    assertEquals(1, calls.get());
    assertEquals("POST", method.get());
    assertEquals("Bearer fixture-token", authorization.get());
    assertEquals("2026-10-21", version.get());
    JsonNode body = json.readTree(received.get());
    assertEquals(4, body.size());
    assertEquals("example-model", body.get("model").asString());
    assertEquals(20, body.get("max_tokens").asInt());
    assertEquals(false, body.get("stream").asBoolean());
    assertEquals(1, body.get("messages").size());
    assertEquals("user", body.at("/messages/0/role").asString());
    assertEquals("Context 😀", body.at("/messages/0/content").asString());
    reply.set("""
        {"type":"message","role":"assistant","stop_reason":"end_turn",
         "content":[{"type":"text","text":"Advice 😀"}],"usage":{"input_tokens":10,"output_tokens":3}}
        """);
    assertEquals(new AdvisoryGateway.Response("Advice 😀", 10, 3), gateway.invoke(request()));
  }

  @Test
  void rejectsIncompleteOrUnsupportedContentAndUnaccountedUsage() {
    var gateway = new AnthropicGateway(endpoint, "fixture-token");
    List<String> invalid = List.of(
        RESPONSE.replace("\"end_turn\"", "\"max_tokens\""),
        RESPONSE.replace("\"end_turn\"", "\"refusal\""),
        RESPONSE.replace("\"stop_details\":null", "\"stop_details\":{\"type\":\"refusal\"}"),
        RESPONSE.replace("\"type\":\"text\"", "\"type\":\"tool_use\""),
        RESPONSE.replace("\"citations\":null", "\"citations\":[{}]"),
        RESPONSE.replace("\"text\":\"Advice 😀\"", "\"text\":\"\\ud800\""),
        RESPONSE.replace("\"output_tokens\":3", "\"output_tokens\":21"),
        RESPONSE.replace("\"input_tokens\":10", "\"input_tokens\":-1"),
        RESPONSE.replace("\"input_tokens\":10", "\"input_tokens\":10,\"input_tokens\":10"),
        RESPONSE.replace("\"cache_creation_input_tokens\":0", "\"cache_creation_input_tokens\":4"),
        RESPONSE.replace("\"cache_read_input_tokens\":0", "\"cache_read_input_tokens\":4"),
        RESPONSE.replace("\"cache_read_input_tokens\":0", "\"cache_read_input_tokens\":\"0\""),
        RESPONSE.replace("\"cache_creation_input_tokens\":0", "\"cache_creation_input_tokens\":0,"
            + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":1}"),
        RESPONSE.replace("\"web_search_requests\":0", "\"web_search_requests\":1"),
        RESPONSE.replace("\"usage\":{", "\"usage_missing\":{"),
        RESPONSE + "{}",
        "not-json");
    for (String body : invalid) {
      reply.set(body);
      assertInstanceOf(AdvisoryGateway.Unknown.class, gateway.invoke(request()), body);
    }
    assertEquals(invalid.size(), calls.get());
  }

  @Test
  void rejectsRemoteOrWrongProtocolEndpointsBeforeSending() {
    for (String url : List.of("https://api.anthropic.com/v1/messages",
        "http://localhost/v1/messages", "http://127.0.0.1/v1/messages?x=1",
        "http://user@127.0.0.1/v1/messages", "http://127.0.0.1/v1/chat/completions")) {
      assertThrows(IllegalArgumentException.class, () -> new AnthropicGateway(URI.create(url), "fixture-token"));
    }
    assertThrows(IllegalArgumentException.class, () -> new AnthropicGateway(endpoint, "bad\r\ntoken"));
    var gateway = new AnthropicGateway(endpoint, "fixture-token");
    assertThrows(IllegalArgumentException.class,
        () -> gateway.invoke(new AdvisoryGateway.Request("a", "m", "\ud800", 20, Duration.ofSeconds(2))));
    assertEquals(0, calls.get());
  }

  private static AdvisoryGateway.Request request() {
    return new AdvisoryGateway.Request("attempt-1", "example-model", "Context 😀", 20, Duration.ofSeconds(2));
  }
}
