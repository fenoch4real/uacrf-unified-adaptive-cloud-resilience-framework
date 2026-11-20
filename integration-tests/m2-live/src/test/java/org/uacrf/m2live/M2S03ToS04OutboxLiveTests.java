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
  private static final String SOURCE_BINDING_EVENT = "connections.source-binding.created";
  private static final String OBSERVATION_EVENT = "connections.observation.accepted";
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
  void retriesWhenS04PersistsTheEventButItsAcknowledgementFails() throws Exception {
    Path repoRoot =
        Path.of(System.getProperty("uacrf.repo.root", Path.of("../..").toAbsolutePath().toString()))
            .toAbsolutePath()
            .normalize();
    Path logDirectory = Files.createTempDirectory("uacrf-s03-s04-live-");
    ConcurrentLinkedQueue<String> stubFailures = new ConcurrentLinkedQueue<>();
    AtomicInteger tokenCalls = new AtomicInteger();
    IdentityStub identityStub = null;
    S04InboxProxy inboxProxy = null;
    LiveM2TestSupport.Environment identityEnvironment = null;
    Process evidenceProcess = null;
    Process connectionsProcess = null;
    try {
      if (!Files.isRegularFile(repoRoot.resolve("pom.xml"))) {
        throw new IllegalStateException("Cannot locate the repository root from " + repoRoot);
      }

      identityEnvironment = LiveM2TestSupport.environment();
      identityEnvironment.registerDirectoryUser(
          PUBLISHER_SUBJECT,
          "service-account-" + PUBLISHER_CLIENT_ID,
          "M2 live",
          "S03 publisher",
          true);
      String suffix = UUID.randomUUID().toString();
      String publisherPrincipalId = "uacrf.s03.publisher";
      String grantDecisionId = "m2.live.grant." + suffix.replace("-", "");
      String grantExpiry = Instant.now().plus(Duration.ofDays(7)).toString();
      HttpResponse<String> admitted =
          postIdentity(
              identityEnvironment.identityUrl(),
              "/v1/principals",
              "bootstrap-admin-subject",
              Map.of(
                  "id", publisherPrincipalId,
                  "provider_id", "keycloak-local",
                  "directory_subject_ref", PUBLISHER_SUBJECT,
                  "reason", "Admit the S03 publisher for the M2 live fixture.",
                  "expected_revision", 0),
              "m2-live-admit-" + suffix);
      assertEquals(201, admitted.statusCode(), admitted.body());

      HttpResponse<String> grantDecision =
          postIdentity(
              identityEnvironment.identityUrl(),
              "/v1/grant-decisions",
              "bootstrap-approver-subject",
              Map.of(
                  "id", grantDecisionId,
                  "principal_id", publisherPrincipalId,
                  "capability", "evidence.inbox.write",
                  "service_scope", "*",
                  "expires_at", grantExpiry,
                  "reason", "Authorize S03 outbox delivery in the M2 live fixture."),
              null);
      assertEquals(201, grantDecision.statusCode(), grantDecision.body());

      HttpResponse<String> membership =
          postIdentity(
              identityEnvironment.identityUrl(),
              "/v1/memberships",
              "bootstrap-admin-subject",
              Map.of(
                  "id", UUID.randomUUID().toString(),
                  "principal_id", publisherPrincipalId,
                  "capability", "evidence.inbox.write",
                  "service_scope", "*",
                  "expires_at", grantExpiry,
                  "reason", "Enable the admitted S03 publisher in the M2 live fixture.",
                  "expected_revision", 0,
                  "authorization_ref", grantDecisionId),
              "m2-live-membership-" + suffix);
      assertEquals(201, membership.statusCode(), membership.body());

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
      inboxProxy =
          new S04InboxProxy(
              "http://127.0.0.1:"
                  + evidencePort
                  + "/internal/v1/inbox/events");

      connectionsProcess =
          startService(
              repoRoot,
              logDirectory,
              "services/connections/target/connections-service-1.0.0-SNAPSHOT.jar",
              "connections",
              connectionsPort,
              connectionsEnvironment(
                  tokenBaseUrl,
                  identityEnvironment.identityUrl(),
                  connectionsPort,
                  inboxProxy.inboxUrl()));
      awaitHealth("S03", connectionsPort, connectionsProcess, logDirectory);

      String connectionId = "connection-" + suffix;
      String ownerId = "owner-" + suffix;
      String bearer =
          LiveM2TestSupport.bearerWithEligibleOwners(
              LiveM2TestSupport.TENANT,
              LiveM2TestSupport.ENVIRONMENT,
              "m2-live-connection-owner",
              "m2-live-connection-owner-subject",
              "S03",
              List.of("connections.connection.write", "connections.observation.write"),
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

      HttpResponse<String> bound =
          HTTP.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://127.0.0.1:"
                              + connectionsPort
                              + "/v1/connections/"
                              + connectionId
                              + "/source-bindings"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Authorization", bearer)
                  .header("Content-Type", "application/json")
                  .header("Idempotency-Key", "m2-live-binding-" + suffix)
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          LiveM2TestSupport.json(
                              Map.of(
                                  "id", connectionId,
                                  "source_ref", "source/" + suffix,
                                  "expected_revision", 0))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(201, bound.statusCode(), bound.body());

      String observationPayload = "{\"observation\":\"M2 live aggregate collision\"}";
      HttpResponse<String> observation =
          HTTP.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://127.0.0.1:"
                              + connectionsPort
                              + "/v1/connections/"
                              + connectionId
                              + "/observations"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Authorization", bearer)
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          LiveM2TestSupport.json(
                              Map.of(
                                  "id", connectionId,
                                  "source_ref", "source/" + suffix,
                                  "sequence", 1,
                                  "observed_at", Instant.now().toString(),
                                  "kind", "sample.inventory",
                                  "payload", observationPayload,
                                  "payload_digest", sha256(observationPayload)))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(202, observation.statusCode(), observation.body());
      assertTrue(observation.body().contains("\"status\":\"ACCEPTED\""), observation.body());

      Delivery delivery =
          awaitDelivery(
              connectionId,
              CONNECTION_EVENT,
              LiveM2TestSupport.TENANT,
              LiveM2TestSupport.ENVIRONMENT,
              logDirectory);
      assertTrue(delivery.published(), "S03 did not mark the outbox event delivered");
      assertNotNull(delivery.inboxEvent(), "S04 did not retain the event");
      assertEquals("S03", delivery.inboxEvent().sourceService());
      assertEquals(CONNECTION_EVENT, delivery.inboxEvent().eventType());
      assertEquals("CONNECTION", delivery.inboxEvent().aggregateType());
      assertEquals(connectionId, delivery.inboxEvent().aggregateId());
      assertEquals(1, delivery.inboxEvent().aggregateSequence());
      assertEquals(sha256(delivery.inboxEvent().payload()), delivery.inboxEvent().payloadDigest());

      Delivery bindingDelivery =
          awaitDelivery(
              connectionId,
              SOURCE_BINDING_EVENT,
              LiveM2TestSupport.TENANT,
              LiveM2TestSupport.ENVIRONMENT,
              logDirectory);
      assertTrue(bindingDelivery.published(), "S03 did not mark the source-binding event delivered");
      assertNotNull(bindingDelivery.inboxEvent(), "S04 did not retain the source-binding event");
      assertEquals(SOURCE_BINDING_EVENT, bindingDelivery.inboxEvent().eventType());
      assertEquals("SOURCE_BINDING", bindingDelivery.inboxEvent().aggregateType());
      assertEquals(connectionId, bindingDelivery.inboxEvent().aggregateId());
      assertEquals(1, bindingDelivery.inboxEvent().aggregateSequence());

      Delivery observationDelivery =
          awaitDelivery(
              connectionId,
              OBSERVATION_EVENT,
              LiveM2TestSupport.TENANT,
              LiveM2TestSupport.ENVIRONMENT,
              logDirectory);
      assertTrue(
          observationDelivery.published(), "S03 did not mark the observation event delivered");
      assertNotNull(
          observationDelivery.inboxEvent(), "S04 did not retain the observation event");
      assertEquals(OBSERVATION_EVENT, observationDelivery.inboxEvent().eventType());
      assertEquals("OBSERVATION", observationDelivery.inboxEvent().aggregateType());
      assertEquals(connectionId, observationDelivery.inboxEvent().aggregateId());
      assertEquals(1, observationDelivery.inboxEvent().aggregateSequence());
      assertEquals(1, inboxProxy.acknowledgementFailures());
      assertEquals(3, inboxProxy.receivedResponses());
      assertTrue(inboxProxy.duplicateResponses() > 0, "S04 did not identify the retried event");
      assertTrue(inboxProxy.requestCount() > 1, "S03 did not retry after the failed acknowledgement");
      assertTrue(inboxProxy.failures().isEmpty(), String.join("; ", inboxProxy.failures()));
      assertTrue(tokenCalls.get() > 0, "S03 did not request a client-credentials token");
      assertTrue(stubFailures.isEmpty(), String.join("; ", stubFailures));
      identityEnvironment.verifyStubHealth();

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
                                  "tenant_id", LiveM2TestSupport.TENANT,
                                  "environment_id", LiveM2TestSupport.ENVIRONMENT))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(403, overbroadDelegation.statusCode(), overbroadDelegation.body());
    } finally {
      stopProcess(connectionsProcess);
      stopProcess(evidenceProcess);
      if (inboxProxy != null) {
        inboxProxy.close();
      }
      if (identityStub != null) {
        identityStub.server().stop(0);
      }
      if (identityEnvironment != null) {
        identityEnvironment.close();
      }
      deleteDirectory(logDirectory);
    }
  }

  private static HttpResponse<String> postIdentity(
      String identityUrl,
      String path,
      String subject,
      Map<String, Object> body,
      String idempotencyKey)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(
                URI.create(
                    identityUrl
                        + path
                        + "?tenant_id="
                        + LiveM2TestSupport.TENANT
                        + "&environment_id="
                        + LiveM2TestSupport.ENVIRONMENT))
            .timeout(Duration.ofSeconds(5))
            .header(
                "Authorization",
                "Bearer " + LiveM2TestSupport.identityAccessToken(subject))
            .header("Content-Type", "application/json");
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    return HTTP.send(
        request
            .POST(HttpRequest.BodyPublishers.ofString(LiveM2TestSupport.json(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
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
      String tokenBaseUrl, String identityBaseUrl, int connectionsPort, String inboxUrl) {
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
            inboxUrl));
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
    respondBody(exchange, status, bytes);
  }

  private static void respondBody(HttpExchange exchange, int status, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private static Delivery awaitDelivery(
      String aggregateId,
      String eventType,
      String tenantId,
      String environmentId,
      Path logDirectory)
      throws Exception {
    Instant deadline = Instant.now().plusSeconds(30);
    while (Instant.now().isBefore(deadline)) {
      OutboxEvent outboxEvent = findOutboxEvent(aggregateId, eventType);
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

  private static OutboxEvent findOutboxEvent(String aggregateId, String eventType)
      throws SQLException {
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
      query.setString(1, aggregateId);
      query.setString(2, eventType);
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
                "SELECT source_service, event_type, aggregate_type, aggregate_id, aggregate_sequence, "
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
            rows.getString(4),
            rows.getLong(5),
            rows.getString(6),
            rows.getString(7).trim());
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

  private static final class S04InboxProxy implements AutoCloseable {
    private static final String PATH = "/internal/v1/inbox/events";
    private final String targetInboxUrl;
    private final HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger acknowledgementFailures = new AtomicInteger();
    private final AtomicInteger receivedResponses = new AtomicInteger();
    private final AtomicInteger duplicateResponses = new AtomicInteger();
    private final ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

    private S04InboxProxy(String targetInboxUrl) throws IOException {
      this.targetInboxUrl = targetInboxUrl;
      server =
          HttpServer.create(
              new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
      server.createContext(PATH, this::forward);
      server.start();
    }

    String inboxUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + PATH;
    }

    int requestCount() {
      return requestCount.get();
    }

    int acknowledgementFailures() {
      return acknowledgementFailures.get();
    }

    int receivedResponses() {
      return receivedResponses.get();
    }

    int duplicateResponses() {
      return duplicateResponses.get();
    }

    ConcurrentLinkedQueue<String> failures() {
      return failures;
    }

    private void forward(HttpExchange exchange) throws IOException {
      int requestNumber = requestCount.incrementAndGet();
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(targetInboxUrl))
              .timeout(Duration.ofSeconds(5))
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofByteArray(
                      exchange.getRequestBody().readAllBytes()));
      String authorization = exchange.getRequestHeaders().getFirst("Authorization");
      if (authorization != null) {
        request.header("Authorization", authorization);
      }
      try {
        HttpResponse<String> response =
            HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.body().contains("\"RECEIVED\"")) {
          receivedResponses.incrementAndGet();
        }
        if (response.body().contains("\"DUPLICATE\"")) {
          duplicateResponses.incrementAndGet();
        }
        if (requestNumber == 1 && response.statusCode() >= 200 && response.statusCode() < 300) {
          acknowledgementFailures.incrementAndGet();
          respond(
              exchange,
              503,
              Map.of("error", "simulated acknowledgement failure after S04 persistence"));
          return;
        }
        respondBody(
            exchange,
            response.statusCode(),
            response.body().getBytes(StandardCharsets.UTF_8));
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        failures.add("Interrupted while forwarding the S03 inbox request.");
        respond(exchange, 502, Map.of("error", "interrupted while forwarding"));
      } catch (IOException error) {
        failures.add("S04 inbox forwarding failed: " + error.getMessage());
        respond(exchange, 502, Map.of("error", "S04 inbox forwarding failed"));
      }
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  private record OutboxEvent(UUID eventId, boolean published) {}

  private record InboxEvent(
      String sourceService,
      String eventType,
      String aggregateType,
      String aggregateId,
      long aggregateSequence,
      String payload,
      String payloadDigest) {}

  private record Delivery(boolean published, InboxEvent inboxEvent) {}
}
