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
  @Autowired private ObjectivesService objectives;
  @Autowired private tools.jackson.databind.ObjectMapper json;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void reviewsExactSyntheticProposalWithIndependentCurrentAuthority() throws Exception {
    MODE.set("ok");
    String importer = token("importer", List.of("objectives.objective.read", "objectives.objective.write"));
    var preview = postKeyed("/v1/objective-imports:preview", importer, """
        {"connection_id":"sample-connection","external_objective_id":"sample:objective",
         "target_workload_id":"workload-1","mapping_revision":1,"expected_local_revision":0}
        """);
    assertEquals(202, preview.statusCode(), preview.body());
    String id = json.readTree(preview.body()).path("receipt_id").asText();
    var actor = new DelegationPrincipal("importer", "tenant-check", "env-1", "*", "https://idp.example.test",
        "importer", List.of("objectives.objective.read", "objectives.objective.write"), List.of(), List.of());
    var sourceRequest = objectives.getSampleCaptureRequest(actor, id, "capture-key");
    String payload = """
        {"metric":"recovery.time","target":720,"unit":"seconds","window":"stable-30m",
         "measurement_ref":"sample:check","comparison":"AT_MOST","measurement_kind":"RECOVERY_TIME",
         "percentile":null}
        """;
    var definition = ObjectiveImportSampleParser.parse(payload);
    String payloadDigest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(payload.getBytes(StandardCharsets.UTF_8)));
    var inspection = new ObjectiveImportSampleSourceClient.Inspection(sourceRequest.connectionId(), 1, "binding", 2,
        sourceRequest.targetWorkloadId(), "sample:monitoring", sourceRequest.externalObjectiveId(),
        "sample-v1", payloadDigest, "SYNTHETIC_UNVERIFIED", definition, ObjectiveImportFingerprint.fingerprint(definition));
    var captured = objectives.captureSample(actor, id, "capture-key", sourceRequest,
        new ObjectiveImportSampleSourceClient.Fetched(inspection, payload));
    String proposals = "/v1/objective-imports/" + id + "/sample-captures/" + captured.captureId()
        + "/mapping-proposals";
    var proposed = postKeyed(proposals, importer,
        "{\"target_unit\":\"minutes\",\"allow_fraction_to_percent\":false,\"reason\":\"inspect conversion\"}");
    assertEquals(201, proposed.statusCode(), proposed.body());
    var proposal = json.readTree(proposed.body());
    String proposalId = proposal.path("proposal_id").asText();
    String digest = proposal.path("proposal_digest").asText();
    assertThat(digest).matches("[a-f0-9]{64}");
    assertThat(proposal.path("proposal_digest_version").asText()).isEqualTo("SYNTHETIC_MAPPING_PROPOSAL_SHA256_V1");
    String canonical = """
        {"allow_fraction_to_percent":false,"capture_id":"%s","environment_id":"env-1","fingerprint_version":"OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1","normalized_fingerprint":"%s","proposal_id":"%s","proposed_at":"%s","proposed_by":"importer","reason":"inspect conversion","receipt_id":"%s","source_fingerprint":"%s","status":"UNAPPROVED","target_unit":"minutes","tenant_id":"tenant-check","verification":"SYNTHETIC_UNVERIFIED"}\
        """.formatted(captured.captureId(), proposal.path("normalized_fingerprint").asText(), proposalId,
        proposal.path("proposed_at").asText(), id, proposal.path("source_fingerprint").asText());
    String expectedDigest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    assertThat(digest).isEqualTo(expectedDigest);
    String reviews = proposals + "/" + proposalId + "/reviews";
    String body = """
        {"decision":"APPROVE","reason":"independent review",
         "expected_proposal_digest_version":"SYNTHETIC_MAPPING_PROPOSAL_SHA256_V1","expected_proposal_digest":"%s"}
        """.formatted(digest);
    String reviewer = token("reviewer", List.of("objectives.objective.read", "objectives.objective.review"));
    String self = token("importer", List.of("objectives.objective.read", "objectives.objective.review"));
    String reader = token("reader", "objectives.objective.read");
    var empty = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + reviews))
        .header("Authorization", reader).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, empty.statusCode());
    assertThat(empty.headers().firstValue("cache-control")).hasValue("no-store");
    assertThat(json.readTree(empty.body()).path("items").size()).isZero();
    var forbidden = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + reviews))
        .header("Authorization", token("writer", "objectives.objective.write")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(403, forbidden.statusCode());
    REQUEST.set(null);
    assertEquals(409, postKeyed(reviews, self, body).statusCode());
    assertThat(REQUEST.get()).isNull();
    assertEquals(409, postKeyed(reviews, reviewer, body.replace(digest, "0".repeat(64))).statusCode());
    assertThat(REQUEST.get()).isNull();
    assertEquals(403, postKeyed(reviews, token("reader", "objectives.objective.read"), body).statusCode());
    var scopedReviewer = new DelegationPrincipal("reviewer", "tenant-check", "env-1", "workload-1",
        "https://idp.example.test", "reviewer", List.of("objectives.objective.read", "objectives.objective.review"),
        List.of(), List.of());
    String scopedToken = "Bearer " + new DelegationTokenIssuer(ENCODER, Clock.systemUTC(),
        "https://uacrf.local/s01", KEY_ID).issue(scopedReviewer, "S05");
    assertEquals(403, postKeyed(reviews, scopedToken, body).statusCode());
    MODE.set("denied");
    assertEquals(503, postKeyed(reviews, reviewer, body).statusCode());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_objectives.synthetic_objective_mapping_review",
        Integer.class));
    MODE.set("ok");
    var created = postKeyed(reviews, reviewer, body);
    assertEquals(201, created.statusCode(), created.body());
    assertThat(created.headers().firstValue("cache-control")).hasValue("no-store");
    var review = json.readTree(created.body());
    assertThat(review.path("decision").asText()).isEqualTo("APPROVE");
    assertThat(review.path("verification").asText()).isEqualTo("SYNTHETIC_UNVERIFIED");
    assertThat(review.path("proposal_digest").asText()).isEqualTo(digest);
    assertThat(REQUEST.get()).contains("\"reviewer_id\":\"reviewer\"");
    String candidates = reviews + "/" + review.path("review_id").asText() + "/synthetic-candidates";
    String candidateBody = """
        {"expected_proposal_digest_version":"SYNTHETIC_MAPPING_PROPOSAL_SHA256_V1","expected_proposal_digest":"%s"}
        """.formatted(digest);
    var emptyCandidates = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + candidates))
        .header("Authorization", reader).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, emptyCandidates.statusCode());
    assertThat(emptyCandidates.headers().firstValue("cache-control")).hasValue("no-store");
    assertThat(json.readTree(emptyCandidates.body()).path("items").size()).isZero();
    assertEquals(403, postWithKey(candidates, reader, candidateBody, "candidate-key").statusCode());
    assertEquals(409, postWithKey(candidates, importer, candidateBody.replace(digest, "0".repeat(64)),
        "candidate-key").statusCode());
    String missingReview = reviews + "/" + java.util.UUID.randomUUID() + "/synthetic-candidates";
    assertEquals(404, postWithKey(missingReview, importer, candidateBody, "candidate-key").statusCode());
    java.util.List<HttpResponse<String>> candidateResults = new java.util.ArrayList<>();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var attempts = java.util.stream.IntStream.range(0, 8)
          .mapToObj(index -> pool.submit(() -> postWithKey(candidates, importer, candidateBody, "candidate-key")))
          .toList();
      for (var attempt : attempts) candidateResults.add(attempt.get());
    }
    assertThat(candidateResults.stream().filter(result -> result.statusCode() == 201)).hasSize(1);
    assertThat(candidateResults.stream().filter(result -> result.statusCode() == 200)).hasSize(7);
    var candidate = json.readTree(candidateResults.getFirst().body());
    assertThat(candidateResults.stream().map(result -> json.readTree(result.body()).path("candidate_id").asText())
        .distinct()).hasSize(1);
    assertThat(candidate.path("normalized_definition").path("target").decimalValue())
        .isEqualByComparingTo("12");
    assertThat(candidate.path("normalized_definition").path("unit").asText()).isEqualTo("minutes");
    assertThat(candidate.path("normalized_fingerprint").path("digest").asText())
        .isEqualTo(proposal.path("normalized_fingerprint").asText());
    assertThat(candidate.path("status").asText()).isEqualTo("UNCOMPARED");
    assertThat(candidate.path("verification").asText()).isEqualTo("SYNTHETIC_UNVERIFIED");
    assertEquals(409, postWithKey(candidates, importer, candidateBody, "different-key").statusCode());
    String candidateReadPath = candidates + "/" + candidate.path("candidate_id").asText();
    var candidateRead = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + candidateReadPath))
        .header("Authorization", reader).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, candidateRead.statusCode());
    assertThat(candidateRead.headers().firstValue("cache-control")).hasValue("no-store");
    assertThat(json.readTree(candidateRead.body())).isEqualTo(candidate);
    var discovered = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + candidates))
        .header("Authorization", reader).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, discovered.statusCode());
    assertThat(discovered.headers().firstValue("cache-control")).hasValue("no-store");
    assertThat(json.readTree(discovered.body()).path("items").size()).isEqualTo(1);
    assertThat(json.readTree(discovered.body()).path("items").get(0)).isEqualTo(candidate);
    assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
        "UPDATE uacrf_objectives.synthetic_normalized_candidate SET status='COMPARED' WHERE candidate_id=?::uuid",
        candidate.path("candidate_id").asText()));
    assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
        "DELETE FROM uacrf_objectives.synthetic_normalized_candidate WHERE candidate_id=?::uuid",
        candidate.path("candidate_id").asText()));
    var listed = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + reviews))
        .header("Authorization", reader).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, listed.statusCode());
    assertThat(listed.headers().firstValue("cache-control")).hasValue("no-store");
    assertThat(json.readTree(listed.body()).path("items").size()).isEqualTo(1);
    assertThat(json.readTree(listed.body()).path("items").get(0)).isEqualTo(review);
    var replay = postKeyed(reviews, reviewer, body);
    assertEquals(200, replay.statusCode(), replay.body());
    assertThat(json.readTree(replay.body())).isEqualTo(review);
    MODE.set("denied");
    assertEquals(503, postKeyed(reviews, reviewer, body).statusCode());
    MODE.set("ok");
    assertEquals(409, postKeyed(reviews, reviewer, body.replace("independent review", "changed")).statusCode());
    assertEquals(409, postWithKey(reviews, reviewer, body, "another-key").statusCode());
    String readPath = reviews + "/" + review.path("review_id").asText();
    var read = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + readPath))
        .header("Authorization", token("reader", "objectives.objective.read")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(200, read.statusCode());
    assertThat(read.headers().firstValue("cache-control")).hasValue("no-store");
    assertThat(json.readTree(read.body())).isEqualTo(review);
    var otherTenant = new DelegationPrincipal("reader", "other-tenant", "env-1", "*", "https://idp.example.test",
        "reader", List.of("objectives.objective.read"), List.of(), List.of());
    String otherToken = "Bearer " + new DelegationTokenIssuer(ENCODER, Clock.systemUTC(),
        "https://uacrf.local/s01", KEY_ID).issue(otherTenant, "S05");
    var isolated = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + readPath))
        .header("Authorization", otherToken).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(404, isolated.statusCode());
    var isolatedList = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + reviews))
        .header("Authorization", otherToken).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(404, isolatedList.statusCode());
    var isolatedCandidate = http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + candidateReadPath))
            .header("Authorization", otherToken).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(404, isolatedCandidate.statusCode());
    var isolatedDiscovery = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + candidates))
        .header("Authorization", otherToken).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(404, isolatedDiscovery.statusCode());
    var scopedList = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + reviews))
        .header("Authorization", scopedToken).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(403, scopedList.statusCode());
    var scopedCandidate = http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + candidateReadPath))
            .header("Authorization", scopedToken).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(403, scopedCandidate.statusCode());
    var scopedDiscovery = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + candidates))
        .header("Authorization", scopedToken).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(403, scopedDiscovery.statusCode());
    var missingDiscovery = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + missingReview))
        .header("Authorization", reader).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(404, missingDiscovery.statusCode());
    String wrongProposal = reviews.replace(proposalId, java.util.UUID.randomUUID().toString());
    var missingList = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + wrongProposal))
        .header("Authorization", reader).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(404, missingList.statusCode());
    assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
        "UPDATE uacrf_objectives.synthetic_objective_mapping_review SET reason='changed' WHERE review_id=?::uuid",
        review.path("review_id").asText()));
    assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
        "DELETE FROM uacrf_objectives.synthetic_objective_mapping_review WHERE review_id=?::uuid",
        review.path("review_id").asText()));
    var second = postWithKey(proposals, importer,
        "{\"target_unit\":\"seconds\",\"allow_fraction_to_percent\":false,\"reason\":\"second proposal\"}",
        "second-proposal-key");
    assertEquals(201, second.statusCode(), second.body());
    var secondBody = json.readTree(second.body());
    String secondPath = proposals + "/" + secondBody.path("proposal_id").asText() + "/reviews";
    String rejection = body.replace("APPROVE", "REJECT")
        .replace(digest, secondBody.path("proposal_digest").asText());
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var attempts = java.util.stream.IntStream.range(0, 8)
          .mapToObj(index -> pool.submit(() -> postWithKey(secondPath, reviewer, rejection, "concurrent-review")))
          .toList();
      var results = new java.util.ArrayList<HttpResponse<String>>();
      for (var attempt : attempts) results.add(attempt.get());
      assertThat(results.stream().filter(result -> result.statusCode() == 201)).hasSize(1);
      assertThat(results.stream().filter(result -> result.statusCode() == 200)).hasSize(7);
      assertThat(results.stream().map(result -> json.readTree(result.body()).path("review_id").asText()).distinct())
          .hasSize(1);
      assertThat(json.readTree(results.getFirst().body()).path("decision").asText()).isEqualTo("REJECT");
      String rejectedReview = json.readTree(results.getFirst().body()).path("review_id").asText();
      assertEquals(409, postWithKey(secondPath + "/" + rejectedReview + "/synthetic-candidates", importer,
          candidateBody.replace(digest, secondBody.path("proposal_digest").asText()), "rejected-key").statusCode());
    }
    var third = postWithKey(proposals, importer,
        "{\"target_unit\":\"seconds\",\"allow_fraction_to_percent\":false,\"reason\":\"third proposal\"}",
        "third-proposal-key");
    assertEquals(201, third.statusCode(), third.body());
    var thirdBody = json.readTree(third.body());
    String thirdDigest = thirdBody.path("proposal_digest").asText();
    String thirdReviews = proposals + "/" + thirdBody.path("proposal_id").asText() + "/reviews";
    var thirdReview = postWithKey(thirdReviews, reviewer, body.replace(digest, thirdDigest), "third-review-key");
    assertEquals(201, thirdReview.statusCode(), thirdReview.body());
    String thirdCandidates = thirdReviews + "/" + json.readTree(thirdReview.body()).path("review_id").asText()
        + "/synthetic-candidates";
    assertEquals(409, postWithKey(thirdCandidates, importer, candidateBody.replace(digest, thirdDigest),
        "candidate-key").statusCode());
    assertEquals(201, postWithKey(thirdCandidates, importer, candidateBody.replace(digest, thirdDigest),
        "third-candidate-key").statusCode());
    assertThat(proposal.path("status").asText()).isEqualTo("UNAPPROVED");
  }

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
    var disabled = new ObjectiveReviewAuthorityClient(false, "", "", "", "", false);
    assertThrows(ObjectivesException.class, () -> disabled.checkRequired("tenant", "environment", "reviewer"));
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
    return postWithKey(path, token, body, "activation-key");
  }

  private HttpResponse<String> postWithKey(String path, String token, String body, String key) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .header("Authorization", token).header("Content-Type", "application/json")
        .header("Idempotency-Key", key)
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String token(String actor, String capability) {
    return token(actor, List.of(capability));
  }

  private static String token(String actor, List<String> capabilities) {
    DelegationPrincipal principal = new DelegationPrincipal(actor, "tenant-check", "env-1", "*",
        "https://idp.example.test", actor, capabilities, List.of(), List.of());
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
