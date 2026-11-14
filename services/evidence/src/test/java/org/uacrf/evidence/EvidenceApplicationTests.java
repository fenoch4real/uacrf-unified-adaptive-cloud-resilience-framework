package org.uacrf.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
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
class EvidenceApplicationTests {
  private static final String KEY_ID = "evidence-test-key";
  private static final KeyPair KEY_PAIR = generateKeyPair();
  private static final JwtEncoder ENCODER = DelegationJwtEncoderFactory.create(KEY_PAIR, KEY_ID);
  private static final String POSTGRES_IMAGE =
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81";
  private static final List<String> SUBMITTER =
      List.of("evidence.event.write", "evidence.event.read", "evidence.inbox.write");
  private static final List<String> REVIEWER =
      List.of("evidence.qualification.review", "evidence.event.read");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(POSTGRES_IMAGE)
          .withDatabaseName("uacrf_evidence")
          .withUsername("evidence_test")
          .withPassword("evidence_test");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add(
        "uacrf.delegation.public-key-base64",
        () -> Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded()));
    properties.add("uacrf.delegation.key-id", () -> KEY_ID);
  }

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void sampleEvidenceStaysSampleOnlyAndCannotBeQualified() throws Exception {
    String submit = bearer("submitter", "tenant-s", SUBMITTER);
    String review = bearer("reviewer", "tenant-s", REVIEWER);
    HttpResponse<String> accepted = post("/internal/v1/observations", submit, evidence("e1", "SAMPLE_LOCAL", "src-1", 1, "p1", null));
    assertEquals(202, accepted.statusCode());
    assertThat(accepted.body()).contains("\"status\":\"ACCEPTED\"").contains("SAMPLE_ONLY");

    HttpResponse<String> blocked = post("/v1/evidence/e1/reviews", review, "{\"decision\":\"QUALIFY\",\"reason\":\"ok\",\"expected_revision\":1}");
    assertEquals(409, blocked.statusCode());
    assertThat(blocked.body()).contains("SAMPLE_NOT_QUALIFIABLE");

    HttpResponse<String> receipt = post("/internal/v1/qualifications/e1:receipt", review, receiptBody(sha("p1")));
    assertThat(receipt.body()).contains("\"usable_as_evidence\":false").contains("never accepted evidence");
  }

  @Test
  void providerEvidenceNeedsIndependentReviewAndReceiptTracksDigestAndCutoff() throws Exception {
    String submit = bearer("submitter", "tenant-p", SUBMITTER);
    String review = bearer("reviewer", "tenant-p", REVIEWER);
    assertThat(post("/internal/v1/observations", submit, evidence("e2", "PROVIDER", "src-2", 1, "p2", null)).body())
        .contains("UNVERIFIED");

    String decision = "{\"decision\":\"QUALIFY\",\"reason\":\"verified\",\"expected_revision\":1}";
    String selfReview = bearer("submitter", "tenant-p", List.of("evidence.qualification.review"));
    assertThat(post("/v1/evidence/e2/reviews", selfReview, decision).body()).contains("REVIEWER_NOT_INDEPENDENT");
    assertEquals(409, post("/v1/evidence/e2/reviews", review, decision.replace("\"expected_revision\":1", "\"expected_revision\":9")).statusCode());
    assertEquals(200, post("/v1/evidence/e2/reviews", review, decision).statusCode());
    assertThat(post("/v1/evidence/e2/reviews", review, decision.replace("1}", "2}")).body()).contains("ALREADY_REVIEWED");

    assertThat(post("/internal/v1/qualifications/e2:receipt", review, receiptBody(sha("p2"))).body())
        .contains("\"usable_as_evidence\":true");
    assertThat(post("/internal/v1/qualifications/e2:receipt", review, receiptBody(sha("other"))).body())
        .contains("\"usable_as_evidence\":false").contains("\"digest_matches\":false");
    assertThat(
            post(
                    "/internal/v1/qualifications/e2:receipt",
                    review,
                    receiptBody(sha("p2")).replace("2999-01-01", "2000-01-01"))
                .body())
        .contains("\"within_cutoff\":false");
  }

  @Test
  void quarantinesDigestMismatchReplayAndStaleSequence() throws Exception {
    String submit = bearer("submitter", "tenant-q", SUBMITTER);
    assertThat(post("/internal/v1/observations", submit, evidence("e3", "PROVIDER", "src-3", 5, "p3", null)).body()).contains("ACCEPTED");
    assertThat(post("/internal/v1/observations", submit, evidence("e3", "PROVIDER", "src-3", 5, "p3", null)).body()).contains("DUPLICATE");
    assertThat(post("/internal/v1/observations", submit, evidence("e3", "PROVIDER", "src-3", 5, "changed", null)).body()).contains("REPLAY_CONFLICT");
    assertThat(post("/internal/v1/observations", submit, evidence("e4", "PROVIDER", "src-3", 4, "p4", null)).body()).contains("STALE_SEQUENCE");
    assertThat(post("/internal/v1/observations", submit, evidence("e5", "PROVIDER", "src-3", 6, "p5", "0".repeat(64))).body()).contains("DIGEST_MISMATCH");
    assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM uacrf_evidence.quarantined_evidence WHERE tenant_id = 'tenant-q'", Integer.class));
  }

  @Test
  void inboxChecksDigestDuplicatesAndAggregateSequence() throws Exception {
    String submit = bearer("submitter", "tenant-i", SUBMITTER);
    assertThat(post("/internal/v1/inbox/events", submit, event("ev1", 2, "x", null)).body()).contains("RECEIVED");
    assertThat(post("/internal/v1/inbox/events", submit, event("ev1", 2, "x", null)).body()).contains("DUPLICATE");
    assertThat(post("/internal/v1/inbox/events", submit, event("ev1", 2, "y", null)).body()).contains("EVENT_CONFLICT");
    assertThat(post("/internal/v1/inbox/events", submit, event("ev2", 1, "z", null)).body()).contains("SEQUENCE_REGRESSION");
    assertThat(post("/internal/v1/inbox/events", submit, event("ev3", 3, "z", "0".repeat(64))).body()).contains("DIGEST_MISMATCH");
    assertThat(post("/internal/v1/inbox/events", submit, event("ev4", 3, "w", null)).body()).contains("RECEIVED");
  }

  @Test
  void enforcesCapabilitiesScopeAndTenantIsolation() throws Exception {
    String submit = bearer("submitter", "tenant-x", SUBMITTER);
    String other = bearer("submitter", "tenant-y", SUBMITTER);
    assertEquals(202, post("/internal/v1/observations", submit, evidence("e6", "PROVIDER", "src-6", 1, "p6", null)).statusCode());
    assertEquals(404, get("/v1/evidence/e6", other).statusCode());
    assertEquals(200, get("/v1/evidence/e6", submit).statusCode());
    String readOnly = bearer("reader", "tenant-x", List.of("evidence.event.read"));
    assertEquals(403, post("/internal/v1/observations", readOnly, evidence("e7", "PROVIDER", "src-6", 2, "p7", null)).statusCode());
    String scoped = token("submitter", "tenant-x", "svc-1", SUBMITTER);
    assertEquals(403, post("/internal/v1/observations", scoped, evidence("e8", "PROVIDER", "src-6", 3, "p8", null)).statusCode());
    assertEquals(403, post("/internal/v1/inbox/events", readOnly, event("ev9", 1, "q", null)).statusCode());
  }

  private static String evidence(String id, String sourceClass, String source, long sequence, String payload, String digest) {
    return "{\"id\":\"%s\",\"source_class\":\"%s\",\"source_ref\":\"%s\",\"sequence\":%d,\"observed_at\":\"2026-01-01T00:00:00Z\",\"kind\":\"measurement.latency\",\"payload\":\"%s\",\"payload_digest\":\"%s\"}"
        .formatted(id, sourceClass, source, sequence, payload, digest == null ? sha(payload) : digest);
  }

  private static String event(String id, long sequence, String payload, String digest) {
    return "{\"event_id\":\"%s\",\"source_service\":\"S03\",\"event_type\":\"connections.observation.accepted\",\"aggregate_id\":\"obs-1\",\"aggregate_sequence\":%d,\"payload\":\"%s\",\"payload_digest\":\"%s\"}"
        .formatted(id, sequence, payload, digest == null ? sha(payload) : digest);
  }

  private static String receiptBody(String digest) {
    return "{\"purpose\":\"assessment\",\"expected_digest\":\"%s\",\"cutoff\":\"2999-01-01T00:00:00Z\"}".formatted(digest);
  }

  private static String sha(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", bearer)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path, String bearer) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Authorization", bearer).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String bearer(String user, String tenant, List<String> capabilities) {
    return token(user, tenant, "*", capabilities);
  }

  private static String token(String user, String tenant, String scope, List<String> capabilities) {
    DelegationPrincipal principal =
        new DelegationPrincipal(
            "https://uacrf.local/s01|" + user,
            tenant,
            "env-1",
            scope,
            "https://idp.example.test",
            user,
            capabilities,
            List.of(),
            List.of());
    return "Bearer "
        + new DelegationTokenIssuer(ENCODER, Clock.systemUTC(), "https://uacrf.local/s01", KEY_ID).issue(principal, "S04");
  }

  private static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
