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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.uacrf.security.DelegationJwtEncoderFactory;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.DelegationTokenIssuer;
import tools.jackson.databind.ObjectMapper;

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
  @Autowired private ObjectMapper json;
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
  void measurementReceiptBindsQualifiedPayloadAndHidesOtherTenants() throws Exception {
    String submit = bearer("submitter", "tenant-measurement", SUBMITTER);
    String reviewer = bearer("reviewer", "tenant-measurement", REVIEWER);
    String payload = "{\"metric\":\"latency\",\"value\":1.250,\"unit\":\"ms\"}";
    String escapedPayload = json.writeValueAsString(payload);
    String request = measurementRequest("latency", "2999-01-01T00:00:00Z");
    String path = "/internal/v1/qualifications/measurement-1:measurement";
    assertEquals(202, post("/internal/v1/observations", submit,
        evidence("measurement-1", "PROVIDER", "measurement-source", 1, escapedPayload.substring(1, escapedPayload.length() - 1), sha(payload))).statusCode());
    assertThat(post(path, reviewer, request).body()).contains("\"usable_as_evidence\":false");
    assertEquals(200, post("/v1/evidence/measurement-1/reviews", reviewer,
        "{\"decision\":\"QUALIFY\",\"reason\":\"verified\",\"expected_revision\":1}").statusCode());
    HttpResponse<String> receipt = post(path, reviewer, request);
    assertEquals(200, receipt.statusCode());
    var result = json.readTree(receipt.body());
    assertThat(result.get("usable_as_evidence").asBoolean()).isTrue();
    assertEquals("measurement-1", result.get("evidence_id").asString());
    assertEquals(2, result.get("revision").asInt());
    assertEquals(sha(payload), result.get("payload_digest").asString());
    assertEquals("2026-01-01T00:00:00Z", result.get("observed_at").asString());
    assertEquals("measurement.latency", result.get("source_kind").asString());
    assertEquals("latency", result.get("metric").asString());
    assertEquals(0, result.get("value").decimalValue().compareTo(new java.math.BigDecimal("1.250")));
    assertEquals("ms", result.get("unit").asString());
    assertThat(receipt.body()).doesNotContain("\"payload\":");
    assertThat(post(path, reviewer, measurementRequest("other", "2999-01-01T00:00:00Z")).body())
        .contains("\"usable_as_evidence\":false").contains("metric");
    assertThat(post(path, reviewer, measurementRequest("latency", "2000-01-01T00:00:00Z")).body())
        .contains("\"usable_as_evidence\":false").contains("cutoff");
    assertEquals(404, post(path, bearer("reader", "other-tenant", REVIEWER), request).statusCode());
    assertEquals(403, post(path, bearer("reader", "tenant-measurement", List.of()), request).statusCode());
    jdbc.update("UPDATE uacrf_evidence.evidence_event SET payload=? WHERE tenant_id=? AND id=?",
        "{\"metric\":\"latency\",\"value\":999,\"unit\":\"ms\"}", "tenant-measurement", "measurement-1");
    assertThat(post(path, reviewer, request).body()).contains("\"usable_as_evidence\":false").contains("digest");
  }

  @Test
  void malformedAndAmbiguousPayloadsAreIneligible() throws Exception {
    String submit = bearer("submitter", "tenant-malformed", SUBMITTER);
    String reviewer = bearer("reviewer", "tenant-malformed", REVIEWER);
    List<String> payloads = List.of("{", "{\"metric\":\"latency\",\"value\":1,\"unit\":\"ms\"} true",
        "{\"metric\":\"latency\",\"metric\":\"latency\",\"value\":1,\"unit\":\"ms\"}",
        "{\"metric\":\"latency\",\"value\":\"1\",\"unit\":\"ms\"}",
        "{\"metric\":\"latency\",\"value\":1e9999999999,\"unit\":\"ms\"}",
        "{\"metric\":\"latency\",\"value\":NaN,\"unit\":\"ms\"}",
        "{\"metric\":\"latency\",\"value\":1,\"unit\":\" \"}",
        "{\"metric\":\"latency\",\"value\":1,\"unit\":\"ms\",\"extra\":1}");
    for (int i = 0; i < payloads.size(); i++) {
      String payload = payloads.get(i);
      String escaped = json.writeValueAsString(payload);
      String id = "malformed-" + i;
      assertEquals(202, post("/internal/v1/observations", submit,
          evidence(id, "PROVIDER", "malformed-source", i + 1, escaped.substring(1, escaped.length() - 1), sha(payload))).statusCode());
      assertEquals(200, post("/v1/evidence/" + id + "/reviews", reviewer,
          "{\"decision\":\"QUALIFY\",\"reason\":\"verified\",\"expected_revision\":1}").statusCode());
      assertThat(post("/internal/v1/qualifications/" + id + ":measurement", reviewer,
          measurementRequest("latency", "2999-01-01T00:00:00Z")).body())
          .contains("\"usable_as_evidence\":false").contains("valid measurement");
    }
  }

  private static String measurementRequest(String metric, String cutoff) {
    return "{\"purpose\":\"assessment\",\"metric\":\"" + metric + "\",\"cutoff\":\"" + cutoff + "\"}";
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
  void conflictingEvidenceReplayBlocksQualificationAndMeasurementReceipts() throws Exception {
    String submit = bearer("submitter", "tenant-evidence-conflict", SUBMITTER);
    String reviewer = bearer("reviewer", "tenant-evidence-conflict", REVIEWER);
    String payload = "{\"metric\":\"latency\",\"value\":1.25,\"unit\":\"ms\"}";
    String escaped = json.writeValueAsString(payload);
    String original = evidence("e-conflict", "PROVIDER", "conflict-source", 1,
        escaped.substring(1, escaped.length() - 1), sha(payload))
        .replace("2026-01-01T00:00:00Z", "2026-01-01T00:00:00.123456789Z");
    String receipt = "/internal/v1/qualifications/e-conflict:receipt";
    String measurement = "/internal/v1/qualifications/e-conflict:measurement";
    assertThat(post("/internal/v1/observations", submit, original).body()).contains("ACCEPTED");
    assertEquals(200, post("/v1/evidence/e-conflict/reviews", reviewer,
        "{\"decision\":\"QUALIFY\",\"reason\":\"verified\",\"expected_revision\":1}").statusCode());
    assertThat(post(receipt, reviewer, receiptBody(sha(payload))).body()).contains("\"usable_as_evidence\":true");
    assertThat(post(measurement, reviewer, measurementRequest("latency", "2999-01-01T00:00:00Z")).body())
        .contains("\"usable_as_evidence\":true");
    assertThat(post("/internal/v1/observations", submit, original).body()).contains("DUPLICATE");

    String changedTime = original.replace("2026-01-01T00:00:00.123456789Z", "2026-01-02T00:00:00Z");
    String changedKind = original.replace("measurement.latency", "measurement.other");
    assertThat(post("/internal/v1/observations", submit, changedTime).body()).contains("REPLAY_CONFLICT");
    assertThat(post("/internal/v1/observations", submit, changedKind).body()).contains("REPLAY_CONFLICT");
    assertThat(post(receipt, reviewer, receiptBody(sha(payload))).body())
        .contains("\"usable_as_evidence\":false", "conflict");
    assertThat(post(measurement, reviewer, measurementRequest("latency", "2999-01-01T00:00:00Z")).body())
        .contains("\"usable_as_evidence\":false", "conflict");
    assertThat(post("/internal/v1/observations", submit, original).body()).contains("DUPLICATE");
    assertThat(post(receipt, reviewer, receiptBody(sha(payload))).body()).contains("\"usable_as_evidence\":false");
    assertEquals(1, jdbc.queryForObject(
        "SELECT count(*) FROM uacrf_evidence.quarantined_evidence WHERE tenant_id = 'tenant-evidence-conflict' "
            + "AND evidence_id = 'e-conflict' AND source_class = 'PROVIDER' "
            + "AND kind = 'measurement.other' AND observed_at = '2026-01-01T00:00:00.123457Z'::timestamptz "
            + "AND submitted_by = 'https://uacrf.local/s01|submitter'", Integer.class));
    String otherSubmit = bearer("submitter", "tenant-evidence-other", SUBMITTER);
    String otherReviewer = bearer("reviewer", "tenant-evidence-other", REVIEWER);
    assertThat(post("/internal/v1/observations", otherSubmit, original).body()).contains("ACCEPTED");
    assertEquals(200, post("/v1/evidence/e-conflict/reviews", otherReviewer,
        "{\"decision\":\"QUALIFY\",\"reason\":\"verified\",\"expected_revision\":1}").statusCode());
    assertThat(post(receipt, otherReviewer, receiptBody(sha(payload))).body()).contains("\"usable_as_evidence\":true");
    assertThat(post(measurement, otherReviewer, measurementRequest("latency", "2999-01-01T00:00:00Z")).body())
        .contains("\"usable_as_evidence\":true");
  }

  @Test
  void conflictingSourceSequenceBlocksPreviouslyQualifiedEvidence() throws Exception {
    String submit = bearer("submitter", "tenant-sequence-conflict", SUBMITTER);
    String reviewer = bearer("reviewer", "tenant-sequence-conflict", REVIEWER);
    String original = evidence("sequence-original", "PROVIDER", "sequence-source", 2, "payload", null);
    assertThat(post("/internal/v1/observations", submit, original).body()).contains("ACCEPTED");
    assertEquals(200, post("/v1/evidence/sequence-original/reviews", reviewer,
        "{\"decision\":\"QUALIFY\",\"reason\":\"verified\",\"expected_revision\":1}").statusCode());
    String receipt = "/internal/v1/qualifications/sequence-original:receipt";
    assertThat(post("/internal/v1/observations", submit,
        evidence("older", "PROVIDER", "sequence-source", 1, "old", null)).body()).contains("STALE_SEQUENCE");
    assertThat(post("/internal/v1/observations", submit,
        evidence("bad-digest", "PROVIDER", "sequence-source", 2, "bad", "0".repeat(64))).body())
        .contains("DIGEST_MISMATCH");
    assertThat(post(receipt, reviewer, receiptBody(sha("payload"))).body()).contains("\"usable_as_evidence\":true");
    assertThat(post("/internal/v1/observations", submit,
        evidence("different-id", "PROVIDER", "sequence-source", 2, "different", null)).body())
        .contains("STALE_SEQUENCE");
    assertThat(post(receipt, reviewer, receiptBody(sha("payload"))).body())
        .contains("\"usable_as_evidence\":false", "conflict");
  }

  @Test
  void evidenceSerializesConflictingIdsAcrossSources() throws Exception {
    String submit = bearer("submitter", "tenant-evidence-race", SUBMITTER);
    String first = evidence("same-evidence-id", "PROVIDER", "source-one", 1, "payload", null);
    String second = first.replace("source-one", "source-two");
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var firstResult = executor.submit(() -> {
        start.await();
        return post("/internal/v1/observations", submit, first);
      });
      var secondResult = executor.submit(() -> {
        start.await();
        return post("/internal/v1/observations", submit, second);
      });
      start.countDown();
      HttpResponse<String> a = firstResult.get(15, TimeUnit.SECONDS);
      HttpResponse<String> b = secondResult.get(15, TimeUnit.SECONDS);
      assertEquals(202, a.statusCode());
      assertEquals(202, b.statusCode());
      assertThat(List.of(json.readTree(a.body()).get("status").asString(),
              json.readTree(b.body()).get("status").asString()))
          .containsExactlyInAnyOrder("ACCEPTED", "QUARANTINED");
      assertThat(a.body() + b.body()).contains("REPLAY_CONFLICT");
    }
    assertThat(post("/internal/v1/qualifications/same-evidence-id:receipt", submit,
        receiptBody(sha("payload"))).body()).contains("\"usable_as_evidence\":false", "conflict");
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
  void inboxQuarantinesReplayWithChangedEnvelopeEvenWhenPayloadMatches() throws Exception {
    String submit = bearer("submitter", "tenant-envelope", SUBMITTER);
    String original = event("envelope-1", 1, "same-payload", null);
    assertThat(post("/internal/v1/inbox/events", submit, original).body()).contains("RECEIVED");
    assertThat(post("/internal/v1/inbox/events", submit, original).body()).contains("DUPLICATE");

    for (String changed :
        List.of(
            original.replace("\"source_service\":\"S03\"", "\"source_service\":\"S05\""),
            original.replace("connections.observation.accepted", "connections.observation.changed"),
            original.replace("\"aggregate_id\":\"obs-1\"", "\"aggregate_id\":\"obs-2\""),
            original.replace("\"aggregate_sequence\":1", "\"aggregate_sequence\":2"))) {
      HttpResponse<String> response = post("/internal/v1/inbox/events", submit, changed);
      assertEquals(202, response.statusCode());
      assertThat(response.body()).contains("QUARANTINED", "EVENT_CONFLICT");
    }
    assertThat(post("/internal/v1/inbox/events", submit, original).body()).contains("DUPLICATE");
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_evidence.inbox_event WHERE tenant_id = 'tenant-envelope'", Integer.class));
    assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM uacrf_evidence.quarantined_inbox_event WHERE tenant_id = 'tenant-envelope' AND reason = 'EVENT_CONFLICT'", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_evidence.quarantined_inbox_event WHERE tenant_id = 'tenant-envelope' AND event_type = 'connections.observation.changed'", Integer.class));
    String otherTenant = bearer("submitter", "tenant-envelope-other", SUBMITTER);
    assertThat(post("/internal/v1/inbox/events", otherTenant, original).body()).contains("RECEIVED");
  }

  @Test
  void inboxSerializesConcurrentConflictingEventIdsAcrossAggregates() throws Exception {
    String submit = bearer("submitter", "tenant-inbox-race", SUBMITTER);
    String first = event("same-id", 1, "same-payload", null);
    String second = first.replace("\"aggregate_id\":\"obs-1\"", "\"aggregate_id\":\"obs-2\"");
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var firstResult = executor.submit(() -> {
        start.await();
        return post("/internal/v1/inbox/events", submit, first);
      });
      var secondResult = executor.submit(() -> {
        start.await();
        return post("/internal/v1/inbox/events", submit, second);
      });
      start.countDown();
      HttpResponse<String> a = firstResult.get(15, TimeUnit.SECONDS);
      HttpResponse<String> b = secondResult.get(15, TimeUnit.SECONDS);
      assertEquals(202, a.statusCode());
      assertEquals(202, b.statusCode());
      assertThat(List.of(json.readTree(a.body()).get("status").asString(),
              json.readTree(b.body()).get("status").asString()))
          .containsExactlyInAnyOrder("RECEIVED", "QUARANTINED");
      assertThat(a.body() + b.body()).contains("EVENT_CONFLICT");
    }
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_evidence.inbox_event WHERE tenant_id = 'tenant-inbox-race'", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_evidence.quarantined_inbox_event WHERE tenant_id = 'tenant-inbox-race' AND reason = 'EVENT_CONFLICT'", Integer.class));
  }

  @Test
  void inboxIntegrityViewFlagsConflictingIdsAndSequencesWithoutFlaggingUnrelatedEvents()
      throws Exception {
    String submit = bearer("submitter", "tenant-integrity", SUBMITTER);
    String first = event("integrity-1", 5, "payload-1", null);
    String second =
        event("integrity-2", 1, "payload-2", null)
            .replace("\"aggregate_id\":\"obs-1\"", "\"aggregate_id\":\"obs-2\"");
    assertThat(post("/internal/v1/inbox/events", submit, first).body()).contains("RECEIVED");
    assertThat(post("/internal/v1/inbox/events", submit, second).body()).contains("RECEIVED");
    assertThat(post("/internal/v1/inbox/events", submit, event("stale", 4, "old", null)).body())
        .contains("SEQUENCE_REGRESSION");
    assertThat(post("/internal/v1/inbox/events", submit, event("bad-digest", 5, "bad", "0".repeat(64))).body())
        .contains("DIGEST_MISMATCH");
    assertEquals("NO_KNOWN_CONFLICT", inboxIntegrity("tenant-integrity", "integrity-1"));

    String changed = first.replace("connections.observation.accepted", "connections.observation.changed");
    assertThat(post("/internal/v1/inbox/events", submit, changed).body()).contains("EVENT_CONFLICT");
    assertEquals("CONFLICT", inboxIntegrity("tenant-integrity", "integrity-1"));
    assertEquals("NO_KNOWN_CONFLICT", inboxIntegrity("tenant-integrity", "integrity-2"));

    String sameSequence = second.replace("\"event_id\":\"integrity-2\"", "\"event_id\":\"other-id\"");
    assertThat(post("/internal/v1/inbox/events", submit, sameSequence).body()).contains("SEQUENCE_REGRESSION");
    assertEquals("CONFLICT", inboxIntegrity("tenant-integrity", "integrity-2"));

    String otherTenant = bearer("submitter", "tenant-integrity-other", SUBMITTER);
    assertThat(post("/internal/v1/inbox/events", otherTenant, first).body()).contains("RECEIVED");
    assertEquals("NO_KNOWN_CONFLICT", inboxIntegrity("tenant-integrity-other", "integrity-1"));
  }

  @Test
  void inboxMigrationPreservesLegacyQuarantineWithoutInventingEventType() {
    String database = "evidence_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url = POSTGRES.getJdbcUrl().replace("/uacrf_evidence?", "/" + database + "?");
      var source = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
      Flyway.configure().dataSource(source).target("1").load().migrate();
      var oldDb = new JdbcTemplate(source);
      oldDb.update(
          "INSERT INTO uacrf_evidence.inbox_event "
              + "(tenant_id, environment_id, event_id, source_service, event_type, aggregate_id, "
              + "aggregate_sequence, payload, payload_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
          "tenant-legacy", "env-1", "legacy", "S03", "connections.observation.accepted",
          "obs-1", 1, "original-payload", sha("original-payload"));
      oldDb.update(
          "INSERT INTO uacrf_evidence.quarantined_inbox_event "
              + "(quarantine_id, tenant_id, environment_id, event_id, source_service, aggregate_id, "
              + "aggregate_sequence, reason, payload, claimed_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          UUID.randomUUID(), "tenant-legacy", "env-1", "legacy", "S03", "obs-1", 1,
          "EVENT_CONFLICT", "old-payload", sha("old-payload"));
      oldDb.update(
          "INSERT INTO uacrf_evidence.quarantined_evidence "
              + "(quarantine_id, tenant_id, environment_id, evidence_id, source_ref, sequence, "
              + "reason, payload, claimed_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
          UUID.randomUUID(), "tenant-legacy", "env-1", "old-evidence", "old-source", 1,
          "REPLAY_CONFLICT", "old-payload", sha("old-payload"));
      Flyway.configure().dataSource(source).load().migrate();
      assertEquals(
          1,
          oldDb.queryForObject(
              "SELECT count(*) FROM uacrf_evidence.quarantined_inbox_event "
                  + "WHERE event_id = 'legacy' AND payload = 'old-payload' AND event_type IS NULL",
              Integer.class));
      assertEquals(
          "CONFLICT",
          oldDb.queryForObject(
              "SELECT integrity_status FROM uacrf_evidence.inbox_event_integrity "
                  + "WHERE tenant_id = 'tenant-legacy' AND event_id = 'legacy'",
              String.class));
      assertEquals(
          1,
          oldDb.queryForObject(
              "SELECT count(*) FROM uacrf_evidence.quarantined_evidence "
                  + "WHERE evidence_id = 'old-evidence' AND payload = 'old-payload' "
                  + "AND reason = 'REPLAY_CONFLICT' AND source_class IS NULL "
                  + "AND observed_at IS NULL AND kind IS NULL AND submitted_by IS NULL",
              Integer.class));
    } finally {
      jdbc.execute("DROP DATABASE " + database);
    }
  }

  private String inboxIntegrity(String tenant, String eventId) {
    return jdbc.queryForObject(
        "SELECT integrity_status FROM uacrf_evidence.inbox_event_integrity "
            + "WHERE tenant_id = ? AND environment_id = 'env-1' AND event_id = ?",
        String.class,
        tenant,
        eventId);
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
