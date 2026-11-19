package org.uacrf.m2live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class M2S03ToS04OutboxLiveTests {
  private static final String POSTGRES_IMAGE =
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81";
  private static final String PUBLISHER_CLIENT_ID = "uacrf-s03-publisher";
  private static final String PUBLISHER_CLIENT_SECRET = "m2-live-publisher-secret";
  private static final String PUBLISHER_SUBJECT = "m2-live-publisher-subject";
  private static final String CONNECTION_EVENT = "connections.connection.registered";
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @Container
  private static final PostgreSQLContainer CONNECTIONS_DB =
      new PostgreSQLContainer(POSTGRES_IMAGE)
          .withDatabaseName("uacrf_connections")
          .withUsername("connections_live")
          .withPassword("connections_live");

  @Container
  private static final PostgreSQLContainer EVIDENCE_DB =
      new PostgreSQLContainer(POSTGRES_IMAGE)
          .withDatabaseName("uacrf_evidence")
          .withUsername("evidence_live")
          .withPassword("evidence_live");

  @Test
  void publishesS03OutboxEventToTheRealS04Inbox() throws Exception {
    Path repoRoot =
        Path.of(System.getProperty("uacrf.repo.root", Path.of("../..").toAbsolutePath().toString()))
            .toAbsolutePath()
            .normalize();
    Path logDirectory = Files.createTempDirectory("uacrf-s03-s04-live-");
    ConcurrentLinkedQueue<String> stubFailures = new ConcurrentLinkedQueue<>();
    AtomicInteger tokenCalls = new AtomicInteger();
    IdentityStub identityStub = null;
    LiveM2TestSupport.Environment identityEnvironment = null;
    Process evidenceProcess = null;
    Process connectionsProcess = null;
    try {
      if (!Files.isRegularFile(repoRoot.resolve("pom.xml"))) {
        throw new IllegalStateException("Cannot locate the repository root from " + repoRoot);
      }

      identityEnvironment = LiveM2TestSupport.environment();
      identityEnvironment.registerSystemPrincipal(
          PUBLISHER_CLIENT_ID, PUBLISHER_SUBJECT, List.of("evidence.inbox.write"));
      identityStub = startIdentityStub(stubFailures, tokenCalls);
      int evidencePort = freePort();
      int connectionsPort = freePort();
      String tokenBaseUrl =
          "http://127.0.0.1:" + identityStub.server().getAddress().getPort();

      evidenceProcess =
          startService(
              repoRoot,
              logDirectory,
              "services/evidence/target/evidence-service-1.0.0-SNAPSHOT.jar",
              "evidence",
              evidencePort,
              evidenceEnvironment());
      awaitHealth("S04", evidencePort, evidenceProcess, logDirectory);

      connectionsProcess =
          startService(
              repoRoot,
              logDirectory,
              "services/connections/target/connections-service-1.0.0-SNAPSHOT.jar",
              "connections",
              connectionsPort,
              connectionsEnvironment(
                  tokenBaseUrl, identityEnvironment.identityUrl(), evidencePort, connectionsPort));
      awaitHealth("S03", connectionsPort, connectionsProcess, logDirectory);

      String suffix = UUID.randomUUID().toString();
      String connectionId = "connection-" + suffix;
      String ownerId = "owner-" + suffix;
      String bearer =
          LiveM2TestSupport.bearerWithEligibleOwners(
              identityEnvironment.tenantId(),
              identityEnvironment.environmentId(),
              "m2-live-connection-owner",
              "m2-live-connection-owner-subject",
              "S03",
              List.of("connections.connection.write"),
              List.of(ownerId));
      Map<String, Object> request =
          Map.of(
              "id", connectionId,
              "name", "M2 S03 outbox source",
              "provider", "SAMPLE_LOCAL",
              "read_only_identity_ref", "role/m2-live",
              "owner_id", ownerId,
              "expected_revision", 0);
      HttpResponse<String> created =
          HTTP.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + connectionsPort + "/v1/connections"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Authorization", bearer)
                  .header("Content-Type", "application/json")
                  .header("Idempotency-Key", "m2-live-" + suffix)
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          LiveM2TestSupport.json(request)))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(201, created.statusCode(), created.body());

      Delivery delivery =
          awaitDelivery(
              connectionId,
              identityEnvironment.tenantId(),
              identityEnvironment.environmentId(),
              logDirectory);
      assertTrue(delivery.published(), "S03 did not mark the outbox event delivered");
      assertNotNull(delivery.inboxEvent(), "S04 did not retain the event");
      assertEquals("S03", delivery.inboxEvent().sourceService());
      assertEquals(CONNECTION_EVENT, delivery.inboxEvent().eventType());
      assertEquals(connectionId, delivery.inboxEvent().aggregateId());
      assertEquals(1, delivery.inboxEvent().aggregateSequence());
      assertEquals(sha256(delivery.inboxEvent().payload()), delivery.inboxEvent().payloadDigest());
      assertTrue(tokenCalls.get() > 0, "S03 did not request a client-credentials token");
      assertTrue(stubFailures.isEmpty(), String.join("; ", stubFailures));

      HttpResponse<String> overbroadDelegation =
          HTTP.send(
              HttpRequest.newBuilder(
                      URI.create(
                          identityEnvironment.identityUrl() + "/internal/v1/delegations"))
                  .timeout(Duration.ofSeconds(5))
                  .header(
                      "Authorization",
                      "Bearer "
                          + LiveM2TestSupport.identityAccessToken(PUBLISHER_SUBJECT))
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          LiveM2TestSupport.json(
                              Map.of(
                                  "audience", "S04",
                                  "capability_ids", List.of("connections.connection.write"),
                                  "tenant_id", identityEnvironment.tenantId(),
                                  "environment_id", identityEnvironment.environmentId()))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(403, overbroadDelegation.statusCode(), overbroadDelegation.body());
    } finally {
      stopProcess(connectionsProcess);
      stopProcess(evidenceProcess);
      if (identityStub != null) {
        identityStub.server().stop(0);
      }
      if (identityEnvironment != null) {
        identityEnvironment.close();
      }
      deleteDirectory(logDirectory);
    }
  }

  private static Map<String, String> evidenceEnvironment() {
    return Map.of(
        "EVIDENCE_JDBC_URL", EVIDENCE_DB.getJdbcUrl(),
        "EVIDENCE_DB_USER", EVIDENCE_DB.getUsername(),
        "EVIDENCE_DB_PASSWORD", EVIDENCE_DB.getPassword(),
        "UACRF_DELEGATION_PUBLIC_KEY_BASE64",
            LiveM2TestSupport.delegationPublicKeyBase64(),
        "UACRF_DELEGATION_KEY_ID", LiveM2TestSupport.delegationKeyId(),
        "UACRF_DELEGATION_ISSUER", LiveM2TestSupport.ISSUER);
  }

  private static Map<String, String> connectionsEnvironment(
      String tokenBaseUrl, String identityBaseUrl, int evidencePort, int connectionsPort) {
    return Map.ofEntries(
        Map.entry("SERVER_PORT", Integer.toString(connectionsPort)),
        Map.entry("CONNECTIONS_JDBC_URL", CONNECTIONS_DB.getJdbcUrl()),
        Map.entry("CONNECTIONS_DB_USER", CONNECTIONS_DB.getUsername()),
        Map.entry("CONNECTIONS_DB_PASSWORD", CONNECTIONS_DB.getPassword()),
        Map.entry(
            "UACRF_DELEGATION_PUBLIC_KEY_BASE64",
            LiveM2TestSupport.delegationPublicKeyBase64()),
        Map.entry("UACRF_DELEGATION_KEY_ID", LiveM2TestSupport.delegationKeyId()),
        Map.entry("UACRF_DELEGATION_ISSUER", LiveM2TestSupport.ISSUER),
        Map.entry("UACRF_OUTBOX_PUBLISHER_ENABLED", "true"),
        Map.entry("UACRF_OUTBOX_PUBLISHER_INTERVAL_MS", "200"),
        Map.entry("UACRF_S03_IDENTITY_TOKEN_URL", tokenBaseUrl + "/token"),
        Map.entry("UACRF_S03_CLIENT_ID", PUBLISHER_CLIENT_ID),
        Map.entry("UACRF_S03_CLIENT_SECRET", PUBLISHER_CLIENT_SECRET),
        Map.entry(
            "UACRF_S03_S01_DELEGATION_URL",
            identityBaseUrl + "/internal/v1/delegations"),
        Map.entry(
            "UACRF_S03_S04_INBOX_URL",
            "http://127.0.0.1:" + evidencePort + "/internal/v1/inbox/events"));
  }

  private static IdentityStub startIdentityStub(
      ConcurrentLinkedQueue<String> failures, AtomicInteger tokenCalls)
      throws IOException {
    HttpServer server =
        HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          tokenCalls.incrementAndGet();
          String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          if (!"POST".equals(exchange.getRequestMethod())
              || !form.contains("grant_type=client_credentials")
              || !form.contains("client_id=" + PUBLISHER_CLIENT_ID)
              || !form.contains("client_secret=" + PUBLISHER_CLIENT_SECRET)) {
            failures.add("Unexpected client-credentials token request.");
            respond(exchange, 400, Map.of("error", "invalid token request"));
            return;
          }
          respond(
              exchange,
              200,
              Map.of(
                  "access_token",
                      LiveM2TestSupport.identityAccessToken(PUBLISHER_SUBJECT),
                  "token_type", "Bearer",
                  "expires_in", 300));
        });
    server.start();
    return new IdentityStub(server);
  }

  private static void respond(HttpExchange exchange, int status, Map<String, ?> body)
      throws IOException {
    byte[] bytes = LiveM2TestSupport.json(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static Delivery awaitDelivery(
      String connectionId, String tenantId, String environmentId, Path logDirectory)
      throws Exception {
    Instant deadline = Instant.now().plusSeconds(30);
    while (Instant.now().isBefore(deadline)) {
      OutboxEvent outboxEvent = findOutboxEvent(connectionId);
      if (outboxEvent != null) {
        InboxEvent inboxEvent = findInboxEvent(tenantId, environmentId, outboxEvent.eventId());
        if (outboxEvent.published() && inboxEvent != null) {
          return new Delivery(outboxEvent.published(), inboxEvent);
        }
      }
      Thread.sleep(100);
    }
    throw new IllegalStateException(
        "S03 outbox event did not reach the S04 inbox. Recent logs:\n"
            + logTail("connections", logDirectory)
            + "\n"
            + logTail("evidence", logDirectory));
  }

  private static OutboxEvent findOutboxEvent(String connectionId) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                CONNECTIONS_DB.getJdbcUrl(),
                CONNECTIONS_DB.getUsername(),
                CONNECTIONS_DB.getPassword());
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT event_id, published_at IS NOT NULL FROM "
                    + "uacrf_connections.outbox_event WHERE aggregate_id = ? "
                    + "AND event_type = ?")) {
      query.setString(1, connectionId);
      query.setString(2, CONNECTION_EVENT);
      try (var rows = query.executeQuery()) {
        if (!rows.next()) {
          return null;
        }
        return new OutboxEvent(UUID.fromString(rows.getString(1)), rows.getBoolean(2));
      }
    }
  }

  private static InboxEvent findInboxEvent(String tenantId, String environmentId, UUID eventId)
      throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                EVIDENCE_DB.getJdbcUrl(), EVIDENCE_DB.getUsername(), EVIDENCE_DB.getPassword());
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT source_service, event_type, aggregate_id, aggregate_sequence, "
                    + "payload, payload_digest "
                    + "FROM uacrf_evidence.inbox_event WHERE tenant_id = ? "
                    + "AND environment_id = ? AND event_id = ?")) {
      query.setString(1, tenantId);
      query.setString(2, environmentId);
      query.setString(3, eventId.toString());
      try (var rows = query.executeQuery()) {
        if (!rows.next()) {
          return null;
        }
        return new InboxEvent(
            rows.getString(1),
            rows.getString(2),
            rows.getString(3),
            rows.getLong(4),
            rows.getString(5),
            rows.getString(6).trim());
      }
    }
  }

  private static Process startService(
      Path repoRoot,
      Path logDirectory,
      String relativeJar,
      String name,
      int port,
      Map<String, String> environment)
      throws IOException {
    Path jar = repoRoot.resolve(relativeJar);
    if (!Files.isRegularFile(jar)) {
      throw new IllegalStateException(
          "Missing packaged " + name + " service jar: " + jar + "; run the root Maven verify command.");
    }
    ProcessBuilder builder =
        new ProcessBuilder("java", "-jar", jar.toString())
            .directory(repoRoot.toFile())
            .redirectErrorStream(true)
            .redirectOutput(logDirectory.resolve(name + ".log").toFile());
    builder.environment().putAll(environment);
    builder.environment().put("SERVER_PORT", Integer.toString(port));
    return builder.start();
  }

  private static void awaitHealth(
      String service, int port, Process process, Path logDirectory)
      throws IOException, InterruptedException {
    Instant deadline = Instant.now().plusSeconds(120);
    URI health = URI.create("http://127.0.0.1:" + port + "/actuator/health");
    while (Instant.now().isBefore(deadline)) {
      if (!process.isAlive()) {
        throw new IllegalStateException(
            service + " exited before readiness. Recent logs:\n" + logTail(service, logDirectory));
      }
      try {
        HttpResponse<String> response =
            HTTP.send(
                HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
          return;
        }
      } catch (IOException ignored) {
        Thread.sleep(250);
        continue;
      }
      Thread.sleep(250);
    }
    throw new IllegalStateException(
        service
            + " did not become ready at "
            + health
            + ". Recent logs:\n"
            + logTail(service, logDirectory));
  }

  private static String logTail(String service, Path logDirectory) throws IOException {
    Path log = logDirectory.resolve(service + ".log");
    if (!Files.exists(log)) {
      return "(no log file)";
    }
    List<String> lines = Files.readAllLines(log);
    int start = Math.max(0, lines.size() - 150);
    return String.join(System.lineSeparator(), lines.subList(start, lines.size()));
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket =
        new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    }
  }

  private static void stopProcess(Process process) {
    if (process == null || !process.isAlive()) {
      return;
    }
    process.destroy();
    try {
      if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  private static void deleteDirectory(Path directory) throws IOException {
    try (var paths = Files.walk(directory)) {
      for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private record IdentityStub(HttpServer server) {}

  private record OutboxEvent(UUID eventId, boolean published) {}

  private record InboxEvent(
      String sourceService,
      String eventType,
      String aggregateId,
      long aggregateSequence,
      String payload,
      String payloadDigest) {}

  private record Delivery(boolean published, InboxEvent inboxEvent) {}
}
