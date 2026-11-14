package org.uacrf.connections;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class OutboxPublisherTests {
  private static final String POSTGRES_IMAGE =
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(POSTGRES_IMAGE)
          .withDatabaseName("uacrf_connections")
          .withUsername("connections_test")
          .withPassword("connections_test");

  private static final List<String> CALLS = new CopyOnWriteArrayList<>();
  private static final HttpServer STUB = startStub();
  private static volatile String inboxStatus = "RECEIVED";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    String base = "http://localhost:" + STUB.getAddress().getPort();
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add(
        "uacrf.delegation.public-key-base64",
        () -> java.util.Base64.getEncoder().encodeToString(publicKey()));
    properties.add("uacrf.delegation.key-id", () -> "unused");
    properties.add("uacrf.outbox.publisher.enabled", () -> "true");
    properties.add("uacrf.outbox.publisher.interval-ms", () -> "3600000");
    properties.add("uacrf.outbox.publisher.token-url", () -> base + "/token");
    properties.add("uacrf.outbox.publisher.client-id", () -> "s03-test");
    properties.add("uacrf.outbox.publisher.client-secret", () -> "s03-secret");
    properties.add("uacrf.outbox.publisher.delegation-url", () -> base + "/delegations");
    properties.add("uacrf.outbox.publisher.inbox-url", () -> base + "/inbox");
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private OutboxPublisher publisher;

  @Test
  void deliversPendingRowsThroughTheServiceIdentityPathAndMarksThemPublished() {
    CALLS.clear();
    inboxStatus = "RECEIVED";
    UUID id = insert("agg-publish");

    assertThat(publisher.publishPending()).isEqualTo(1);

    assertThat(CALLS).anyMatch(call -> call.startsWith("token:") && call.contains("client_credentials"));
    assertThat(CALLS)
        .anyMatch(
            call ->
                call.startsWith("delegation:Bearer identity-token")
                    && call.contains("\"audience\":\"S04\"")
                    && call.contains("evidence.inbox.write")
                    && call.contains("tenant-pub"));
    assertThat(CALLS)
        .anyMatch(
            call ->
                call.startsWith("inbox:Bearer delegation-token")
                    && call.contains(id.toString())
                    && call.contains("\"source_service\":\"S03\""));
    assertThat(published(id)).isTrue();
    assertThat(publisher.publishPending()).isZero();
  }

  @Test
  void leavesRowsPendingWhenS04IsUnavailable() {
    inboxStatus = "FAIL";
    UUID id = insert("agg-retry");
    try {
      publisher.publishPending();
    } catch (RuntimeException expected) {
      // The scheduled wrapper logs this; the row must remain pending.
    }
    assertThat(published(id)).isFalse();
    inboxStatus = "DUPLICATE";
    publisher.publishPending();
    assertThat(published(id)).isTrue();
  }

  private UUID insert(String aggregate) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO uacrf_connections.outbox_event (event_id, tenant_id, environment_id, "
            + "aggregate_type, aggregate_id, aggregate_sequence, event_type, payload, payload_digest) "
            + "VALUES (?, 'tenant-pub', 'env-pub', 'connection', ?, 1, 'connections.connection.registered', "
            + "'{\"a\":1}'::jsonb, repeat('a', 64))",
        id,
        aggregate);
    return id;
  }

  private boolean published(UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT published_at IS NOT NULL FROM uacrf_connections.outbox_event WHERE event_id = ?",
            Boolean.class,
            id));
  }

  private static byte[] publicKey() {
    try {
      java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair().getPublic().getEncoded();
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static HttpServer startStub() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext("/token", exchange -> respond(exchange, "token:", "{\"access_token\":\"identity-token\"}", 200));
      server.createContext(
          "/delegations",
          exchange -> respond(exchange, "delegation:", "{\"access_token\":\"delegation-token\"}", 200));
      server.createContext(
          "/inbox",
          exchange -> {
            if ("FAIL".equals(inboxStatus)) {
              respond(exchange, "inbox:", "{}", 503);
            } else {
              respond(exchange, "inbox:", "{\"status\":\"" + inboxStatus + "\"}", 200);
            }
          });
      server.start();
      return server;
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private static void respond(HttpExchange exchange, String label, String body, int status)
      throws IOException {
    String auth = exchange.getRequestHeaders().getFirst("Authorization");
    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    CALLS.add(label + (auth == null ? "" : auth) + "|" + request);
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
