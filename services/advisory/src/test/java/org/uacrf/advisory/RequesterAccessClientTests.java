package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.uacrf.advisory.SourceReceiptConsistency.Classification.INTERNAL;
import static org.uacrf.advisory.SourceReceiptConsistency.Classification.PUBLIC;
import static org.uacrf.advisory.SourceReceiptConsistency.Classification.RESTRICTED;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequesterAccessClientTests {
  private static final String PUBLIC_ACCESS = """
      {"principal_id":"human-1","display_name":"User","kind":"HUMAN","service_id":null,
       "scope":"GLOBAL_GRANTS_ONLY","capability_ids":["advisory.run.create","assessment.case.read","knowledge.retrieval.read"],
       "capability_expiry_horizon":"2099-01-01T00:00:00Z","session_expires_at":"2098-01-01T00:00:00Z",
       "membership_snapshot_digest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
      """;
  private HttpServer server;
  private URI base;
  private final AtomicReference<String> body = new AtomicReference<>(PUBLIC_ACCESS);
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> query = new AtomicReference<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/me/access", exchange -> {
      calls.incrementAndGet();
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      query.set(exchange.getRequestURI().getRawQuery());
      byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
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

  private RequesterAccessClient client(boolean enabled) {
    return new RequesterAccessClient(enabled, base, true);
  }

  @Test
  void checksCurrentGlobalSourceGrantsForRequesterTokenAndSelectedContext() throws Exception {
    var principal = client(true).check("requester-token", "tenant:1", "env-1", PUBLIC);
    assertEquals("human-1", principal.id());
    assertEquals("HUMAN", principal.kind());
    assertEquals("Bearer requester-token", authorization.get());
    assertEquals("tenant_id=tenant%3A1&environment_id=env-1", query.get());
  }

  @Test
  void enforcesClassificationAndFetchesAgainAfterGrantChange() throws Exception {
    var client = client(true);
    assertThrows(IOException.class, () -> client.check("requester-token", "tenant-1", "env-1", INTERNAL));
    body.set(PUBLIC_ACCESS.replace("\"knowledge.retrieval.read\"",
        "\"knowledge.retrieval.read\",\"knowledge.classification.internal.read\""));
    client.check("requester-token", "tenant-1", "env-1", INTERNAL);
    assertThrows(IOException.class, () -> client.check("requester-token", "tenant-1", "env-1", RESTRICTED));
    body.set(PUBLIC_ACCESS.replace("\"knowledge.retrieval.read\"",
        "\"knowledge.retrieval.read\",\"knowledge.classification.restricted.read\""));
    client.check("requester-token", "tenant-1", "env-1", INTERNAL);
    client.check("requester-token", "tenant-1", "env-1", RESTRICTED);
    body.set(PUBLIC_ACCESS.replace("\"assessment.case.read\",", ""));
    assertThrows(IOException.class, () -> client.check("requester-token", "tenant-1", "env-1", PUBLIC));
    assertEquals(6, calls.get());
  }

  @Test
  void rejectsMissingRunGrantAndNonHumanRequesters() {
    var client = client(true);
    body.set(PUBLIC_ACCESS.replace("\"advisory.run.create\",", ""));
    assertThrows(IOException.class, () -> client.check("requester-token", "tenant-1", "env-1", PUBLIC));
    body.set(PUBLIC_ACCESS.replace("\"kind\":\"HUMAN\"", "\"kind\":\"SYSTEM\""));
    assertThrows(IOException.class, () -> client.check("requester-token", "tenant-1", "env-1", PUBLIC));
  }

  @Test
  void rejectsMalformedAccessAndS01Failures() {
    var client = client(true);
    for (String invalid : java.util.List.of(
        PUBLIC_ACCESS.replace("\"principal_id\":\"human-1\"", "\"principal_id\":\"\""),
        PUBLIC_ACCESS.replace("\"kind\":\"HUMAN\"", "\"kind\":\"AGENT\""),
        PUBLIC_ACCESS.replace("\"scope\":\"GLOBAL_GRANTS_ONLY\"", "\"scope\":\"GLOBAL_AND_SELECTED_SERVICE_GRANTS\""),
        PUBLIC_ACCESS.replace("\"service_id\":null", "\"service_id\":\"svc-1\""),
        PUBLIC_ACCESS.replace("\"assessment.case.read\"", "\"assessment.case.read\",\"assessment.case.read\""),
        PUBLIC_ACCESS.replace("\"capability_expiry_horizon\":\"2099-01-01T00:00:00Z\"", "\"capability_expiry_horizon\":null"),
        PUBLIC_ACCESS.replace("\"capability_expiry_horizon\":\"2099-01-01T00:00:00Z\",", ""),
        PUBLIC_ACCESS.replace("2099-01-01T00:00:00Z", "not-an-instant"),
        PUBLIC_ACCESS.replace("2099-01-01T00:00:00Z", "2000-01-01T00:00:00Z"),
        PUBLIC_ACCESS.replace("\"session_expires_at\":\"2098-01-01T00:00:00Z\"", "\"session_expires_at\":null"),
        PUBLIC_ACCESS.replace(",\"session_expires_at\":\"2098-01-01T00:00:00Z\"", ""),
        PUBLIC_ACCESS.replace("2098-01-01T00:00:00Z", "not-an-instant"),
        PUBLIC_ACCESS.replace("2098-01-01T00:00:00Z", "2000-01-01T00:00:00Z"),
        PUBLIC_ACCESS.replace("\"membership_snapshot_digest\":\"" + "a".repeat(64) + "\"",
            "\"membership_snapshot_digest\":null"),
        PUBLIC_ACCESS.replace(",\n \"membership_snapshot_digest\":\"" + "a".repeat(64) + "\"", ""),
        PUBLIC_ACCESS.replace("a".repeat(64), "A".repeat(64)),
        PUBLIC_ACCESS + "{}")) {
      body.set(invalid);
      assertThrows(IOException.class, () -> client.check("requester-token", "tenant-1", "env-1", PUBLIC));
    }
    status.set(403);
    assertThrows(IOException.class, () -> client.check("requester-token", "tenant-1", "env-1", PUBLIC));
  }

  @Test
  void disabledOrInvalidRequestsDoNotContactIdentity() {
    assertThrows(IllegalStateException.class, () -> client(false).check("token", "tenant-1", "env-1", PUBLIC));
    assertThrows(IllegalArgumentException.class, () -> client(true).check("token", "tenant/1", "env-1", PUBLIC));
    assertThrows(IOException.class, () -> client(true).check("bad token", "tenant-1", "env-1", PUBLIC));
    assertEquals(0, calls.get());
  }
}
