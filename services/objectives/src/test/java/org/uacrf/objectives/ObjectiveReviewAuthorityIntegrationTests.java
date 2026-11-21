package org.uacrf.objectives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.uacrf.security.DelegationJwtEncoderFactory;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.DelegationTokenIssuer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ObjectiveReviewAuthorityIntegrationTests {
  private static final String KEY_ID = "objective-authority-test-key";
  private static final KeyPair KEY_PAIR = generateKeyPair();
  private static final JwtEncoder ENCODER = DelegationJwtEncoderFactory.create(KEY_PAIR, KEY_ID);
  private static final AtomicReference<String> MODE = new AtomicReference<>("ok");
  private static final AtomicReference<String> REQUEST = new AtomicReference<>();
  private static HttpServer identity;

  @Container
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81")
      .withDatabaseName("uacrf_objectives").withUsername("objectives_test").withPassword("objectives_test");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) throws IOException {
    identity = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    identity.createContext("/token", exchange -> {
      String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      int status = form.contains("client_id=s05") && form.contains("client_secret=test-secret")
          && form.contains("grant_type=client_credentials") && !"token-denied".equals(MODE.get()) ? 200 : 403;
      String token = "bad-token".equals(MODE.get()) ? "invalid token" : "authority-test-token";
      byte[] body = ("{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\"}")
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, body.length);
      try (var output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    identity.createContext("/internal/v1/objective-review-authority:check", exchange -> {
      REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      String mode = MODE.get();
      int status = "unavailable".equals(mode) ? 503
          : "Bearer authority-test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))
              && !"denied".equals(mode) ? 200 : 403;
      String reviewer = "wrong".equals(mode) ? "other-reviewer" : "reviewer";
      Instant checkedAt = "stale".equals(mode) ? Instant.now().minusSeconds(30)
          : "future".equals(mode) ? Instant.now().plusSeconds(30) : Instant.now();
      String result = "malformed".equals(mode) ? "{}" : """
          {"tenant_id":"tenant-check","environment_id":"env-1","reviewer_id":"%s",
           "status":"AUTHORIZED","checked_at":"%s"}
          """.formatted(reviewer, checkedAt);
      if ("duplicate".equals(mode)) {
        result = result.replace("\"status\":\"AUTHORIZED\"", "\"status\":\"DENIED\",\"status\":\"AUTHORIZED\"");
      } else if ("oversized".equals(mode)) {
        result = "{\"padding\":\"" + "x".repeat(20000) + "\"}";
      }
      byte[] body = result.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, body.length);
      try (var output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    identity.start();
    String base = "http://127.0.0.1:" + identity.getAddress().getPort();
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add("uacrf.delegation.public-key-base64",
        () -> Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded()));
    properties.add("uacrf.delegation.key-id", () -> KEY_ID);
    properties.add("uacrf.objectives.reviewer-check.enabled", () -> true);
    properties.add("uacrf.objectives.reviewer-check.token-url", () -> base + "/token");
    properties.add("uacrf.objectives.reviewer-check.identity-base-url", () -> base);
    properties.add("uacrf.objectives.reviewer-check.client-id", () -> "s05");
    properties.add("uacrf.objectives.reviewer-check.client-secret", () -> "test-secret");
    properties.add("uacrf.objectives.reviewer-check.allow-loopback-http", () -> true);
  }

  @AfterAll
  static void stopIdentity() {
    if (identity != null) {
      identity.stop(0);
    }
  }

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void checksCurrentReviewerBeforeActivationAndPreservesPreviousObjectiveOnFailure() throws Exception {
    String proposer = token("proposer", "objectives.objective.write");
    String reviewer = token("reviewer", "objectives.objective.review");
    String activator = token("activator", "objectives.objective.activate");
    prepare("first", proposer, reviewer);
    assertEquals(200, post("/v1/objectives/first:activate", activator, "{\"expected_revision\":3}").statusCode());
    prepare("second", proposer, reviewer);
    String digest = jdbc.queryForObject("SELECT trim(content_digest) FROM uacrf_objectives.objective WHERE id='second'", String.class);
    String activationBody = "{\"expected_revision\":3,\"expected_content_digest\":\"" + digest + "\"}";
    int events = jdbc.queryForObject("SELECT count(*) FROM uacrf_objectives.outbox_event", Integer.class);
    REQUEST.set(null);
    assertEquals(409, post("/v1/objectives/second:activate", activator, "{\"expected_revision\":99}").statusCode());
    assertThat(REQUEST.get()).isNull();
    for (String mode : List.of("denied", "unavailable", "wrong", "stale", "future", "malformed", "duplicate",
        "oversized", "token-denied", "bad-token")) {
      MODE.set(mode);
      HttpResponse<String> denied = postKeyed("/v1/objectives/second:idempotent-activate", activator, activationBody);
      assertEquals(503, denied.statusCode(), denied.body());
      assertThat(denied.body()).contains("REVIEWER_AUTHORITY_UNAVAILABLE");
      assertEquals("ACTIVE", state("first"));
      assertEquals("APPROVED", state("second"));
      assertEquals(events, jdbc.queryForObject("SELECT count(*) FROM uacrf_objectives.outbox_event", Integer.class));
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_objectives.objective_activation", Integer.class));
    }
    assertThat(REQUEST.get()).contains("\"tenant_id\":\"tenant-check\"",
        "\"environment_id\":\"env-1\"", "\"reviewer_id\":\"reviewer\"");
    MODE.set("ok");
    assertEquals(200, postKeyed("/v1/objectives/second:idempotent-activate", activator, activationBody).statusCode());
    assertEquals("SUPERSEDED", state("first"));
    assertEquals("ACTIVE", state("second"));
  }

  @Test
  void enabledCheckRejectsInsecureOrMissingConfiguration() {
    assertThrows(IllegalArgumentException.class, () -> new ObjectiveReviewAuthorityClient(
        true, "http://example.test/token", "https://identity.example.test", "s05", "secret", false));
    assertThrows(IllegalArgumentException.class, () -> new ObjectiveReviewAuthorityClient(
        true, "https://identity.example.test/token", "http://127.0.0.1:8082", "s05", "secret", false));
    assertThrows(IllegalArgumentException.class, () -> new ObjectiveReviewAuthorityClient(
        true, "https://identity.example.test/token", "https://identity.example.test", "s05", "", false));
  }

  private void prepare(String id, String proposer, String reviewer) throws Exception {
    String body = """
        {"id":"%s","workload_id":"w1","title":"Objective","metric":"latency",
         "comparator":"LTE","target":250,"unit":"ms","window_seconds":3600,"origin":"LOCAL"}
        """.formatted(id);
    assertEquals(201, post("/v1/objectives", proposer, body).statusCode());
    assertEquals(200, post("/v1/objectives/" + id + ":submit", proposer, "{\"expected_revision\":1}").statusCode());
    assertEquals(200, post("/v1/objectives/" + id + ":decide", reviewer,
        "{\"decision\":\"APPROVE\",\"reason\":\"reviewed\",\"expected_revision\":2}").statusCode());
  }

  private String state(String id) {
    return jdbc.queryForObject("SELECT state FROM uacrf_objectives.objective WHERE id=?", String.class, id);
  }

  private HttpResponse<String> post(String path, String token, String body) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .header("Authorization", token).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> postKeyed(String path, String token, String body) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .header("Authorization", token).header("Content-Type", "application/json")
        .header("Idempotency-Key", "activation-key")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String token(String actor, String capability) {
    DelegationPrincipal principal = new DelegationPrincipal(actor, "tenant-check", "env-1", "*",
        "https://idp.example.test", actor, List.of(capability), List.of(), List.of());
    return "Bearer " + new DelegationTokenIssuer(ENCODER, Clock.systemUTC(),
        "https://uacrf.local/s01", KEY_ID).issue(principal, "S05");
  }

  private static KeyPair generateKeyPair() {
    try {
      return KeyPairGenerator.getInstance("RSA").generateKeyPair();
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
