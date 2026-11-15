package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientCredentialsTokenProviderTests {
  private static final String VALID = """
      {"access_token":"service-token","token_type":"Bearer","expires_in":60}
      """;
  private HttpServer server;
  private URI endpoint;
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> response = new AtomicReference<>(VALID);
  private final AtomicReference<String> form = new AtomicReference<>();
  private final AtomicReference<String> contentType = new AtomicReference<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/oauth/token", exchange -> {
      calls.incrementAndGet();
      form.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      if (status.get() == 302) exchange.getResponseHeaders().set("Location", "/oauth/token");
      byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status.get(), bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
    server.start();
    endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oauth/token");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private ClientCredentialsTokenProvider provider(boolean enabled) {
    return new ClientCredentialsTokenProvider(enabled, endpoint, true, "client+id", "test&secret");
  }

  @Test
  void sendsFormEncodedCredentialsAndRequestsFreshTokens() throws Exception {
    var provider = provider(true);
    assertEquals("service-token", provider.token());
    assertEquals("service-token", provider.token());
    assertEquals(2, calls.get());
    assertEquals("application/x-www-form-urlencoded", contentType.get());
    Map<String, String> fields = Arrays.stream(form.get().split("&"))
        .map(part -> part.split("=", 2))
        .collect(Collectors.toMap(part -> URLDecoder.decode(part[0], StandardCharsets.UTF_8),
            part -> URLDecoder.decode(part[1], StandardCharsets.UTF_8)));
    assertEquals(Map.of("grant_type", "client_credentials",
        "client_id", "client+id", "client_secret", "test&secret"), fields);
  }

  @Test
  void disabledAndInvalidConfigurationNeverContactsIdentityProvider() {
    assertThrows(IllegalStateException.class, () -> provider(false).token());
    assertThrows(IllegalArgumentException.class, () -> new ClientCredentialsTokenProvider(
        true, URI.create("http://example.test/oauth/token"), true, "client", "secret"));
    assertThrows(IllegalArgumentException.class, () -> new ClientCredentialsTokenProvider(
        true, URI.create("https://example.test/oauth/token?secret=bad"), false, "client", "secret"));
    assertThrows(IllegalArgumentException.class, () -> new ClientCredentialsTokenProvider(
        true, endpoint, true, "client", ""));
    assertEquals(0, calls.get());
  }

  @Test
  void rejectsBadResponsesAndRedirectsWithoutExposingResponseBody() {
    var provider = provider(true);
    for (String invalid : java.util.List.of(
        "{}", VALID.replace("\"Bearer\"", "\"Basic\""),
        VALID.replace("\"expires_in\":60", "\"expires_in\":0"),
        VALID.replace("service-token", "invalid token"),
        VALID.replace("\"token_type\":\"Bearer\"", "\"token_type\":\"Bearer\",\"token_type\":\"Bearer\""),
        VALID + "{}")) {
      response.set(invalid);
      assertThrows(IOException.class, provider::token);
    }
    response.set("{\"access_token\":\"sensitive-token\"");
    IOException invalidJson = assertThrows(IOException.class, provider::token);
    assertNull(invalidJson.getCause());
    response.set("do-not-disclose");
    status.set(401);
    IOException failure = assertThrows(IOException.class, provider::token);
    org.junit.jupiter.api.Assertions.assertFalse(failure.getMessage().contains("do-not-disclose"));
    status.set(302);
    int before = calls.get();
    assertThrows(IOException.class, provider::token);
    assertEquals(before + 1, calls.get());
  }
}
