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

class OllamaGatewayTests {
  private static final String RESPONSE = """
      {"model":"example:8b","message":{"role":"assistant","content":"Advice 😀"},
       "done":true,"done_reason":"stop","prompt_eval_count":10,"eval_count":3}
      """;
  private final AtomicReference<String> reply = new AtomicReference<>(RESPONSE);
  private final AtomicReference<byte[]> received = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> method = new AtomicReference<>();
  private final AtomicInteger calls = new AtomicInteger();
  private final ObjectMapper json = new ObjectMapper();
  private HttpServer server;
  private URI endpoint;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/chat", exchange -> {
      calls.incrementAndGet();
      method.set(exchange.getRequestMethod());
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      received.set(exchange.getRequestBody().readAllBytes());
      byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
    server.start();
    endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void sendsUncredentialedBoundedChatRequestAndReturnsReportedUsage() {
    var gateway = new OllamaGateway(endpoint);
    assertEquals(new AdvisoryGateway.Response("Advice 😀", 10, 3), gateway.invoke(request()));
    assertEquals(1, calls.get());
    assertEquals("POST", method.get());
    assertEquals(null, authorization.get());
    JsonNode body = json.readTree(received.get());
    assertEquals(5, body.size());
    assertEquals("example:8b", body.get("model").asString());
    assertEquals(1, body.get("messages").size());
    assertEquals("user", body.at("/messages/0/role").asString());
    assertEquals("Context 😀", body.at("/messages/0/content").asString());
    assertEquals(20, body.at("/options/num_predict").asInt());
    assertEquals(false, body.get("think").asBoolean());
    assertEquals(false, body.get("stream").asBoolean());
    reply.set(RESPONSE.replace("\"eval_count\":3", "\"eval_count\":3,\"prompt_eval_cached_count\":0"));
    assertEquals(new AdvisoryGateway.Response("Advice 😀", 10, 3), gateway.invoke(request()));
  }

  @Test
  void rejectsIncompleteOrUnverifiableResponses() {
    var gateway = new OllamaGateway(endpoint);
    List<String> invalid = List.of(
        RESPONSE.replace("\"done\":true", "\"done\":false"),
        RESPONSE.replace("\"stop\"", "\"length\""),
        RESPONSE.replace("\"example:8b\"", "\"other:8b\""),
        RESPONSE.replace("\"role\":\"assistant\"", "\"role\":\"user\""),
        RESPONSE.replace("\"content\":\"Advice 😀\"", "\"content\":\"\\ud800\""),
        RESPONSE.replace("\"content\":\"Advice 😀\"", "\"content\":\"Advice 😀\",\"thinking\":\"reasoning\""),
        RESPONSE.replace("\"content\":\"Advice 😀\"", "\"content\":\"Advice 😀\",\"tool_calls\":[]"),
        RESPONSE.replace("\"content\":\"Advice 😀\"", "\"content\":\"Advice 😀\",\"images\":[]"),
        RESPONSE.replace("\"eval_count\":3", "\"eval_count\":21"),
        RESPONSE.replace("\"eval_count\":3", "\"eval_count\":3,\"prompt_eval_cached_count\":1"),
        RESPONSE.replace("\"eval_count\":3", "\"eval_count\":3,\"prompt_eval_cached_count\":null"),
        RESPONSE.replace("\"prompt_eval_count\":10", "\"prompt_eval_count\":-1"),
        RESPONSE.replace("\"prompt_eval_count\":10", "\"prompt_eval_count\":10,\"prompt_eval_count\":10"),
        RESPONSE.replace("\"eval_count\":3", "\"eval_missing\":3"),
        RESPONSE.replace("\"model\":\"example:8b\"", "\"model\":\"example:8b\",\"error\":\"failed\""),
        RESPONSE + "{}",
        "not-json");
    for (String body : invalid) {
      reply.set(body);
      assertInstanceOf(AdvisoryGateway.Unknown.class, gateway.invoke(request()), body);
    }
    assertEquals(invalid.size(), calls.get());
  }

  @Test
  void rejectsRemoteEndpointsAndInvalidTextBeforeSending() {
    for (String url : List.of("https://ollama.com/api/chat",
        "http://localhost/api/chat", "http://127.0.0.1/api/chat?key=secret",
        "http://user@127.0.0.1/api/chat", "http://127.0.0.1/v1/chat/completions")) {
      assertThrows(IllegalArgumentException.class, () -> new OllamaGateway(URI.create(url)));
    }
    var gateway = new OllamaGateway(endpoint);
    assertThrows(IllegalArgumentException.class,
        () -> gateway.invoke(new AdvisoryGateway.Request("a", "m", "\ud800", 20, Duration.ofSeconds(2))));
    assertEquals(0, calls.get());
  }

  private static AdvisoryGateway.Request request() {
    return new AdvisoryGateway.Request("attempt-1", "example:8b", "Context 😀", 20, Duration.ofSeconds(2));
  }
}
