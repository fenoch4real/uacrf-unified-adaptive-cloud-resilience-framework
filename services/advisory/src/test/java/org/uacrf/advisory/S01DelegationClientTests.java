package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class S01DelegationClientTests {
  private static final String VALID = """
      {"access_token":"s01-delegation","token_type":"Bearer","expires_in":30}
      """;
  private HttpServer server;
  private URI base;
  private final AtomicReference<String> response = new AtomicReference<>(VALID);
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicInteger actorCalls = new AtomicInteger();
  private final List<String> authorizations = new CopyOnWriteArrayList<>();
  private final List<Map<?, ?>> requests = new CopyOnWriteArrayList<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/v1/delegations", exchange -> {
      calls.incrementAndGet();
      authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
      requests.add(new JsonMapper().readValue(exchange.getRequestBody().readAllBytes(), Map.class));
      byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
      if (status.get() == 302) exchange.getResponseHeaders().set("Location", "/internal/v1/delegations");
      exchange.sendResponseHeaders(status.get(), bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
    server.start();
    base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private S01DelegationClient client(boolean enabled) {
    return new S01DelegationClient(enabled, base, true, () -> {
      actorCalls.incrementAndGet();
      return "actor-token";
    });
  }

  @Test
  void requestsSeparateNarrowDelegationsWithoutCaching() throws Exception {
    var client = client(true);
    assertEquals("s01-delegation", client.delegation(
        "tenant-1", "env-1", "S07", List.of("assessment.case.read")));
    assertEquals("s01-delegation", client.delegation("tenant-1", "env-1", "S08",
        List.of("knowledge.retrieval.read", "knowledge.classification.internal.read")));
    assertEquals(2, calls.get());
    assertEquals(2, actorCalls.get());
    assertEquals(List.of("Bearer actor-token", "Bearer actor-token"), authorizations);
    assertEquals(Map.of("audience", "S07", "capability_ids", List.of("assessment.case.read"),
        "tenant_id", "tenant-1", "environment_id", "env-1"), requests.get(0));
    assertEquals(Map.of("audience", "S08",
        "capability_ids", List.of("knowledge.retrieval.read", "knowledge.classification.internal.read"),
        "tenant_id", "tenant-1", "environment_id", "env-1"), requests.get(1));
  }

  @Test
  void acceptsPublicAndRestrictedRetrievalScopes() throws Exception {
    var client = client(true);
    client.delegation("tenant-1", "env-1", "S08", List.of("knowledge.retrieval.read"));
    client.delegation("tenant-1", "env-1", "S08",
        List.of("knowledge.retrieval.read", "knowledge.classification.restricted.read"));
    assertEquals(List.of("knowledge.retrieval.read"), requests.get(0).get("capability_ids"));
    assertEquals(List.of("knowledge.retrieval.read", "knowledge.classification.restricted.read"),
        requests.get(1).get("capability_ids"));
  }

  @Test
  void disabledOrInvalidRequestsNeverAcquireActorToken() {
    assertThrows(IllegalStateException.class, () -> client(false).delegation(
        "tenant-1", "env-1", "S07", List.of("assessment.case.read")));
    var client = client(true);
    assertThrows(IllegalArgumentException.class, () -> client.delegation(
        "tenant/other", "env-1", "S07", List.of("assessment.case.read")));
    assertThrows(IllegalArgumentException.class, () -> client.delegation(
        "tenant-1", "env-1", "S08", List.of("knowledge.item.write")));
    assertThrows(IllegalArgumentException.class, () -> client.delegation(
        "tenant-1", "env-1", "S07", List.of("assessment.case.read", "assessment.case.write")));
    assertEquals(0, calls.get());
    assertEquals(0, actorCalls.get());
  }

  @Test
  void rejectsFailedOrMalformedDelegationResponses() {
    var client = client(true);
    var invalid = List.of(
        "{}", VALID.replace("\"Bearer\"", "\"Basic\""),
        VALID.replace("\"expires_in\":30", "\"expires_in\":31"),
        VALID.replace("\"expires_in\":30", "\"expires_in\":0"),
        VALID.replace("s01-delegation", "bad token"),
        VALID.replace("\"token_type\":\"Bearer\"", "\"token_type\":\"Bearer\",\"token_type\":\"Bearer\""),
        VALID + "{}");
    for (String body : invalid) {
      response.set(body);
      assertThrows(IOException.class, () -> client.delegation(
          "tenant-1", "env-1", "S07", List.of("assessment.case.read")));
    }
    status.set(403);
    response.set(VALID);
    assertThrows(IOException.class, () -> client.delegation(
        "tenant-1", "env-1", "S07", List.of("assessment.case.read")));
    status.set(302);
    int before = calls.get();
    assertThrows(IOException.class, () -> client.delegation(
        "tenant-1", "env-1", "S07", List.of("assessment.case.read")));
    assertEquals(before + 1, calls.get());
  }

  @Test
  void rejectsUnsafeIdentityEndpointAndActorToken() {
    assertThrows(IllegalArgumentException.class, () -> new S01DelegationClient(
        true, URI.create("http://example.test"), true, () -> "token"));
    var client = new S01DelegationClient(true, base, true, () -> "bad\r\ntoken");
    assertThrows(IOException.class, () -> client.delegation(
        "tenant-1", "env-1", "S07", List.of("assessment.case.read")));
    assertEquals(0, calls.get());
    assertEquals(0, actorCalls.get());
  }

  @Test
  void propagatesActorTokenFailuresWithoutContactingS01() {
    var unavailable = new S01DelegationClient(true, base, true, () -> {
      throw new IOException("Actor token unavailable.");
    });
    assertThrows(IOException.class, () -> unavailable.delegation(
        "tenant-1", "env-1", "S07", List.of("assessment.case.read")));
    var interrupted = new S01DelegationClient(true, base, true, () -> {
      throw new InterruptedException("Interrupted.");
    });
    assertThrows(InterruptedException.class, () -> interrupted.delegation(
        "tenant-1", "env-1", "S07", List.of("assessment.case.read")));
    assertEquals(0, calls.get());
  }
}
