package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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

class BedrockGatewayTests {
  private static final String RESPONSE = """
      {"output":{"message":{"role":"assistant","content":[{"text":"Advice 😀"}]}},
       "stopReason":"end_turn","usage":{"inputTokens":10,"outputTokens":3,"totalTokens":13}}
      """;
  private final AtomicReference<String> reply = new AtomicReference<>(RESPONSE);
  private final AtomicReference<byte[]> received = new AtomicReference<>();
  private final AtomicReference<String> path = new AtomicReference<>();
  private final AtomicReference<String> method = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicInteger calls = new AtomicInteger();
  private final ObjectMapper json = new ObjectMapper();
  private HttpServer server;
  private URI endpoint;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/model/", exchange -> {
      calls.incrementAndGet();
      method.set(exchange.getRequestMethod());
      path.set(exchange.getRequestURI().getRawPath());
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      received.set(exchange.getRequestBody().readAllBytes());
      byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
    server.start();
    endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void sendsConverseRequestAndReturnsReportedUsage() {
    assertEquals(new AdvisoryGateway.Response("Advice 😀", 10, 3), new BedrockGateway(endpoint).invoke(request()));
    assertEquals(1, calls.get());
    assertEquals("POST", method.get());
    assertEquals("/model/anthropic.claude-v1:0/converse", path.get());
    assertNull(authorization.get());
    JsonNode body = json.readTree(received.get());
    assertEquals(2, body.size());
    assertEquals(20, body.at("/inferenceConfig/maxTokens").asInt());
    assertEquals("user", body.at("/messages/0/role").asString());
    assertEquals("Context 😀", body.at("/messages/0/content/0/text").asString());
    reply.set(RESPONSE.replace("\"totalTokens\":13", "\"totalTokens\":13,\"cacheReadInputTokens\":0,"
        + "\"cacheWriteInputTokens\":0,\"cacheDetails\":[]"));
    assertEquals(new AdvisoryGateway.Response("Advice 😀", 10, 3), new BedrockGateway(endpoint).invoke(request()));
  }

  @Test
  void rejectsUnsupportedContentIncompleteOutputAndUnaccountedUsage() {
    var gateway = new BedrockGateway(endpoint);
    List<String> invalid = List.of(
        RESPONSE.replace("end_turn", "guardrail_intervened"),
        RESPONSE.replace("end_turn", "content_filtered"),
        RESPONSE.replace("end_turn", "max_tokens"),
        RESPONSE.replace("end_turn", "tool_use"),
        RESPONSE.replace("\"role\":\"assistant\"", "\"role\":\"user\""),
        RESPONSE.replace("\"text\":\"Advice 😀\"", "\"toolUse\":{}"),
        RESPONSE.replace("\"text\":\"Advice 😀\"", "\"text\":\"Advice\",\"reasoningContent\":{}"),
        RESPONSE.replace("\"text\":\"Advice 😀\"", "\"text\":\"\\ud800\""),
        RESPONSE.replace("\"outputTokens\":3", "\"outputTokens\":21"),
        RESPONSE.replace("\"inputTokens\":10", "\"inputTokens\":-1"),
        RESPONSE.replace("\"inputTokens\":10", "\"inputTokens\":2147483647")
            .replace("\"totalTokens\":13", "\"totalTokens\":-2147483646"),
        RESPONSE.replace("\"totalTokens\":13", "\"totalTokens\":14"),
        RESPONSE.replace("\"totalTokens\":13", "\"totalTokens\":13,\"cacheReadInputTokens\":1"),
        RESPONSE.replace("\"totalTokens\":13", "\"totalTokens\":13,\"cacheWriteInputTokens\":1"),
        RESPONSE.replace("\"totalTokens\":13", "\"totalTokens\":13,\"cacheDetails\":[{}]"),
        RESPONSE.replace("\"totalTokens\":13", "\"totalTokens\":13,\"totalTokens\":13"),
        RESPONSE.replace("\"usage\":{", "\"usage_missing\":{"),
        RESPONSE.replace("\"stopReason\":", "\"trace\":{},\"stopReason\":"),
        RESPONSE.replace("\"stopReason\":", "\"error\":{},\"stopReason\":"),
        RESPONSE + "{}",
        "not-json");
    for (String body : invalid) {
      reply.set(body);
      assertInstanceOf(AdvisoryGateway.Unknown.class, gateway.invoke(request()), body);
    }
    assertEquals(invalid.size(), calls.get());
  }

  @Test
  void rejectsRemoteEndpointsAndPathInjectionBeforeSending() {
    for (String url : List.of("https://bedrock-runtime.us-east-1.amazonaws.com/model",
        "http://localhost/model", "http://127.0.0.1/model?x=1",
        "http://user@127.0.0.1/model", "http://127.0.0.1/model/other/converse")) {
      assertThrows(IllegalArgumentException.class, () -> new BedrockGateway(URI.create(url)));
    }
    var gateway = new BedrockGateway(endpoint);
    for (String model : List.of("../other", "model/other", "model?x=1", "model%2fother", "model#fragment")) {
      assertThrows(IllegalArgumentException.class,
          () -> gateway.invoke(new AdvisoryGateway.Request("a", model, "context", 20, Duration.ofSeconds(2))));
    }
    assertEquals(0, calls.get());
  }

  private static AdvisoryGateway.Request request() {
    return new AdvisoryGateway.Request("attempt-1", "anthropic.claude-v1:0", "Context 😀", 20, Duration.ofSeconds(2));
  }
}
