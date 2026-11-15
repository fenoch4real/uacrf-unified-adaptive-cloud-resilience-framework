package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenAiCompatibleGatewayTests {
  private static final String RESPONSE = """
      {"choices":[{"index":0,"finish_reason":"stop",
      "message":{"role":"assistant","content":"Advice 😀","refusal":null}}],
      "usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}
      """;
  private final AtomicReference<Reply> reply = new AtomicReference<>(new Reply(200, RESPONSE));
  private final AtomicReference<byte[]> received = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> method = new AtomicReference<>();
  private final AtomicReference<String> contentType = new AtomicReference<>();
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicInteger redirects = new AtomicInteger();
  private final AtomicBoolean block = new AtomicBoolean();
  private final CountDownLatch entered = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);
  private final ObjectMapper json = new ObjectMapper();
  private HttpServer server;
  private URI endpoint;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      calls.incrementAndGet();
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      method.set(exchange.getRequestMethod());
      contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      received.set(exchange.getRequestBody().readAllBytes());
      if (block.get()) {
        entered.countDown();
        try {
          if (!release.await(5, TimeUnit.SECONDS)) {
            exchange.close();
            return;
          }
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          exchange.close();
          return;
        }
      }
      Reply value = reply.get();
      if (value.status() == 302) exchange.getResponseHeaders().set("Location", "/redirect");
      byte[] bytes = value.body().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(value.status(), bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
    server.createContext("/redirect", exchange -> {
      redirects.incrementAndGet();
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.start();
    endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
  }

  @AfterEach
  void stop() {
    release.countDown();
    server.stop(0);
  }

  @Test
  void sendsBoundedTextRequestAndReturnsReportedUsage() {
    var gateway = new OpenAiCompatibleGateway(endpoint, "fixture-token");
    var result = gateway.invoke(request("Context 😀", 20, Duration.ofSeconds(2)));
    assertEquals(new AdvisoryGateway.Response("Advice 😀", 10, 3), result);
    assertEquals(1, calls.get());
    assertEquals("Bearer fixture-token", authorization.get());
    assertEquals("POST", method.get());
    assertEquals("application/json", contentType.get());
    JsonNode body = json.readTree(received.get());
    assertEquals(5, body.size());
    assertEquals("example-model", body.get("model").asString());
    assertEquals(20, body.get("max_completion_tokens").asInt());
    assertEquals(1, body.get("n").asInt());
    assertEquals(false, body.get("stream").asBoolean());
    assertEquals(1, body.get("messages").size());
    assertEquals("user", body.at("/messages/0/role").asString());
    assertEquals("Context 😀", body.at("/messages/0/content").asString());
  }

  @Test
  void rejectsUnsupportedOrUnverifiableResponsesWithoutInventingUsage() {
    var gateway = new OpenAiCompatibleGateway(endpoint, "fixture-token");
    var request = request("context", 20, Duration.ofSeconds(2));
    List<String> invalid = List.of(
        RESPONSE.replace("\"finish_reason\":\"stop\"", "\"finish_reason\":\"length\""),
        RESPONSE.replace("\"refusal\":null", "\"refusal\":\"declined\""),
        RESPONSE.replace("\"refusal\":null", "\"tool_calls\":[]"),
        RESPONSE.replace("\"completion_tokens\":3", "\"completion_tokens\":21"),
        RESPONSE.replace("\"total_tokens\":13", "\"total_tokens\":12"),
        RESPONSE.replace("\"prompt_tokens\":10", "\"prompt_tokens\":-1"),
        RESPONSE.replace("\"usage\":{", "\"usage_missing\":{"),
        RESPONSE.replace("\"index\":0", "\"index\":1"),
        RESPONSE.replace("\"content\":\"Advice 😀\"", "\"content\":\"\\ud800\""),
        RESPONSE.replace("\"prompt_tokens\":10", "\"prompt_tokens\":10,\"prompt_tokens\":10"),
        RESPONSE + "{}",
        "not-json");
    for (String body : invalid) {
      reply.set(new Reply(200, body));
      assertInstanceOf(AdvisoryGateway.Unknown.class, gateway.invoke(request), body);
    }
    assertEquals(invalid.size(), calls.get());
  }

  @Test
  void doesNotFollowRedirectsOrReturnOversizedAndFailedResponses() {
    var gateway = new OpenAiCompatibleGateway(endpoint, "fixture-token");
    var request = request("context", 20, Duration.ofSeconds(2));
    reply.set(new Reply(302, ""));
    assertInstanceOf(AdvisoryGateway.Unknown.class, gateway.invoke(request));
    assertEquals(0, redirects.get());
    reply.set(new Reply(500, "fixture-token private details"));
    var failure = assertInstanceOf(AdvisoryGateway.Unknown.class, gateway.invoke(request));
    assertEquals("Simulated provider returned a non-success status.", failure.reason());
    int emptyResponseBytes = RESPONSE.replace("Advice 😀", "").getBytes(StandardCharsets.UTF_8).length;
    String maximumText = "x".repeat(512 * 1024 - emptyResponseBytes);
    String maximumResponse = RESPONSE.replace("Advice 😀", maximumText);
    assertEquals(512 * 1024, maximumResponse.getBytes(StandardCharsets.UTF_8).length);
    reply.set(new Reply(200, maximumResponse));
    assertEquals(new AdvisoryGateway.Response(maximumText, 10, 3), gateway.invoke(request));
    reply.set(new Reply(200, RESPONSE.replace("Advice 😀", maximumText + "x")));
    assertInstanceOf(AdvisoryGateway.Unknown.class, gateway.invoke(request));
    assertEquals(4, calls.get());
  }

  @Test
  void enforcesTheSerializedRequestByteLimit() {
    var gateway = new OpenAiCompatibleGateway(endpoint, "fixture-token");
    int emptyRequestBytes = json.writeValueAsBytes(Map.of(
        "model", "example-model", "messages", List.of(Map.of("role", "user", "content", "")),
        "max_completion_tokens", 20, "n", 1, "stream", false)).length;
    String maximumContext = "x".repeat(InputPacket.MAX_BYTES - emptyRequestBytes);
    assertEquals(new AdvisoryGateway.Response("Advice 😀", 10, 3),
        gateway.invoke(request(maximumContext, 20, Duration.ofSeconds(2))));
    assertEquals(InputPacket.MAX_BYTES, received.get().length);
    assertThrows(IllegalArgumentException.class,
        () -> gateway.invoke(request(maximumContext + "x", 20, Duration.ofSeconds(2))));
    assertEquals(1, calls.get());
  }

  @Test
  void timeoutDoesNotRetry() throws InterruptedException {
    block.set(true);
    var gateway = new OpenAiCompatibleGateway(endpoint, "fixture-token");
    var failure = assertInstanceOf(AdvisoryGateway.Unknown.class,
        gateway.invoke(request("context", 20, Duration.ofMillis(500))));
    assertTrue(entered.await(1, TimeUnit.SECONDS));
    assertEquals("Simulated provider request timed out.", failure.reason());
    assertEquals(1, calls.get());
  }

  @Test
  void doesNotUseTheProcessDefaultProxy() throws IOException {
    HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger proxyCalls = new AtomicInteger();
    proxy.createContext("/", exchange -> {
      proxyCalls.incrementAndGet();
      exchange.sendResponseHeaders(502, -1);
      exchange.close();
    });
    proxy.start();
    ProxySelector previous = ProxySelector.getDefault();
    try {
      ProxySelector.setDefault(ProxySelector.of(new InetSocketAddress("127.0.0.1", proxy.getAddress().getPort())));
      var gateway = new OpenAiCompatibleGateway(endpoint, "fixture-token");
      assertEquals(new AdvisoryGateway.Response("Advice 😀", 10, 3),
          gateway.invoke(request("context", 20, Duration.ofSeconds(2))));
      assertEquals(1, calls.get());
      assertEquals(0, proxyCalls.get());
    } finally {
      ProxySelector.setDefault(previous);
      proxy.stop(0);
    }
  }

  @Test
  void rejectsNonLoopbackTargetsAndInvalidInputsBeforeSending() {
    for (String url : List.of("https://api.openai.com/v1/chat/completions",
        "http://localhost/v1/chat/completions", "http://127.0.0.1/v1/chat/completions?x=1",
        "http://user@127.0.0.1/v1/chat/completions", "http://127.0.0.1/other")) {
      assertThrows(IllegalArgumentException.class,
          () -> new OpenAiCompatibleGateway(URI.create(url), "fixture-token"));
    }
    assertThrows(IllegalArgumentException.class, () -> new OpenAiCompatibleGateway(endpoint, "bad\r\ntoken"));
    var gateway = new OpenAiCompatibleGateway(endpoint, "fixture-token");
    assertThrows(IllegalArgumentException.class,
        () -> gateway.invoke(request("\ud800", 20, Duration.ofSeconds(2))));
    assertThrows(IllegalArgumentException.class,
        () -> gateway.invoke(request("x".repeat(InputPacket.MAX_BYTES), 20, Duration.ofSeconds(2))));
    assertThrows(IllegalArgumentException.class,
        () -> gateway.invoke(request("context", 20, Duration.ofMinutes(6))));
    assertThrows(IllegalArgumentException.class,
        () -> gateway.invoke(request("context", 10_000_001, Duration.ofSeconds(2))));
    assertEquals(0, calls.get());
  }

  private static AdvisoryGateway.Request request(String context, int maxTokens, Duration timeout) {
    return new AdvisoryGateway.Request("attempt-1", "example-model", context, maxTokens, timeout);
  }

  private record Reply(int status, String body) {}
}
