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
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
    properties.add("uacrf.qualification-waiver.trust-set-json", EvidenceApplicationTests::waiverTrustSet);
  }

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void providerObservationsAreValidatedAgainstTheSharedVersionedSchema() throws Exception {
    String submit = bearer("schema-submitter", "tenant-schema", SUBMITTER);
    String metricData =
        "{\"metric\":\"latency\",\"value\":1.25,\"unit\":\"ms\","
            + "\"window_start\":\"2025-12-31T23:00:00Z\","
            + "\"window_end\":\"2026-01-01T00:00:00Z\"}";
    HttpResponse<String> accepted =
        post(
            "/internal/v1/observations",
            submit,
            schemaObservation(
                "schema-valid",
                "schema-source",
                1,
                "measurement.latency",
                "svc-schema",
                "UACRF_OBSERVATION_V1",
                metricData));
    assertEquals(202, accepted.statusCode());
    assertThat(accepted.body()).contains("\"status\":\"ACCEPTED\"");
    assertThat(get("/v1/evidence/schema-valid", submit).body())
        .contains("\"service_id\":\"svc-schema\"")
        .contains("\"schema_version\":\"UACRF_OBSERVATION_V1\"");

    String configurationData =
        "{\"resource_id\":\"r1\",\"configuration_digest\":\""
            + "a".repeat(64)
            + "\",\"source_version\":\"1\",\"source_checked_at\":\"2026-01-01T00:00:00Z\","
            + "\"change_type\":\"SNAPSHOT\"}";
    HttpResponse<String> rejected =
        post(
            "/internal/v1/observations",
            submit,
            schemaObservation(
                "schema-invalid",
                "schema-source",
                2,
                "configuration.snapshot",
                "svc-schema",
                "UACRF_OBSERVATION_V2",
                configurationData));
    assertEquals(202, rejected.statusCode());
    assertThat(rejected.body()).contains("QUARANTINED").contains("SOURCE_SCHEMA_INVALID");
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_evidence.quarantined_evidence "
                + "WHERE tenant_id='tenant-schema' AND evidence_id='schema-invalid'",
            Integer.class));

    String v2Data =
        "{\"metric\":\"latency\",\"value\":1.25,\"unit\":\"ms\","
            + "\"window_start\":\"2025-12-31T23:00:00Z\","
            + "\"window_end\":\"2026-01-01T00:00:00Z\","
            + "\"measurement_kind\":\"THRESHOLD\",\"measurement_ref\":\"latency-slo\","
            + "\"percentile\":null}";
    HttpResponse<String> v2Accepted =
        post(
            "/internal/v1/observations",
            submit,
            schemaObservation(
                "schema-valid-v2",
                "schema-source",
                3,
                "measurement.latency",
                "svc-schema",
                "UACRF_OBSERVATION_V2",
                v2Data));
    assertEquals(202, v2Accepted.statusCode());
    String reviewer = bearer("schema-reviewer", "tenant-schema", REVIEWER);
    assertEquals(
        200,
        post(
                "/v1/evidence/schema-valid-v2/reviews",
                reviewer,
                "{\"decision\":\"QUALIFY\",\"reason\":\"verified\",\"expected_revision\":1}")
            .statusCode());
    HttpResponse<String> v2Measurement =
        post(
            "/internal/v1/qualifications/schema-valid-v2:measurement",
            reviewer,
            measurementRequest("latency", "2999-01-01T00:00:00Z"));
    assertEquals(200, v2Measurement.statusCode());
    assertThat(v2Measurement.body()).contains("\"usable_as_evidence\":true", "\"metric\":\"latency\"");
  }

  @Test
  void observationPayloadMetadataTracksUtf8BytesForAcceptedAndQuarantinedPayloads() throws Exception {
    String submit = bearer("raw-byte-submitter", "tenant-byte-metadata", SUBMITTER);
    String payload = "{\"message\":\"é☃\"}";
    long byteLength = payload.getBytes(StandardCharsets.UTF_8).length;
    String actualDigest = sha(payload);

    HttpResponse<String> accepted =
        post(
            "/internal/v1/observations",
            submit,
            evidence("byte-meta-accepted", "SAMPLE_LOCAL", "byte-source", 1, payload, actualDigest));
    assertEquals(202, accepted.statusCode());
    var acceptedResult = json.readTree(accepted.body());
    assertEquals(actualDigest, acceptedResult.get("actual_payload_digest").asString());
    assertEquals(actualDigest, acceptedResult.get("claimed_payload_digest").asString());
    assertEquals(byteLength, acceptedResult.get("payload_byte_length").asLong());
    var retained =
        json.readTree(get("/v1/evidence/byte-meta-accepted", submit).body());
    assertEquals(actualDigest, retained.get("payload_digest").asString());
    assertEquals(byteLength, retained.get("payload_byte_length").asLong());

    String claimedDigest = "0".repeat(64);
    HttpResponse<String> quarantined =
        post(
            "/internal/v1/observations",
            submit,
            evidence("byte-meta-quarantined", "SAMPLE_LOCAL", "byte-source", 2, payload, claimedDigest));
    assertEquals(202, quarantined.statusCode());
    var quarantineResult = json.readTree(quarantined.body());
    assertEquals("QUARANTINED", quarantineResult.get("status").asString());
    assertEquals(actualDigest, quarantineResult.get("actual_payload_digest").asString());
    assertEquals(claimedDigest, quarantineResult.get("claimed_payload_digest").asString());
    assertEquals(byteLength, quarantineResult.get("payload_byte_length").asLong());
    Map<String, Object> quarantineRow =
        jdbc.queryForMap(
            "SELECT payload_byte_length, actual_payload_digest, claimed_digest "
                + "FROM uacrf_evidence.quarantined_evidence "
                + "WHERE tenant_id=? AND evidence_id=?",
            "tenant-byte-metadata",
            "byte-meta-quarantined");
    assertEquals(byteLength, ((Number) quarantineRow.get("payload_byte_length")).longValue());
    assertEquals(actualDigest, quarantineRow.get("actual_payload_digest").toString().trim());
    assertEquals(claimedDigest, quarantineRow.get("claimed_digest").toString().trim());
  }

  @Test
  void qualificationPolicyLifecycleIsScopedVersionedAndMetadataOnly() throws Exception {
    String tenant = "tenant-policy";
    String service = "svc-policy";
    String writer =
        bearer(
            "policy-writer",
            tenant,
            List.of(
                "evidence.event.write",
                "evidence.qualification.policy.read",
                "evidence.qualification.policy.write"));
    String reviewer =
        token(
            "policy-reviewer",
            tenant,
            service,
            List.of("evidence.qualification.policy.read", "evidence.qualification.policy.review"));
    String request =
        """
        {"id":"policy-1","service_id":"svc-policy","purpose":"assessment",
         "expected_revision":0,"expires_at":"2999-01-01T00:00:00Z",
         "definition":{"requirements":[{"id":"configuration","mandatory":true,
           "max_age_seconds":3600,"trusted_source_ids":["source-1"],
           "selector":{"kind":"CONFIGURATION","resource_id":"resource-1"}}]},
         "reason":"Initial qualification criteria"}
        """;
    HttpResponse<String> proposed =
        postWithIdempotencyKey("/v1/qualification-policies", writer, request, "policy-create-1");
    assertEquals(200, proposed.statusCode(), proposed.body());
    assertThat(proposed.body()).contains("\"decision\":\"DRAFT\"").contains("\"current_eligible\":false");
    String receiptId = json.readTree(proposed.body()).get("receipt_id").asString();
    HttpResponse<String> replay =
        postWithIdempotencyKey("/v1/qualification-policies", writer, request, "policy-create-1");
    assertEquals(200, replay.statusCode(), replay.body());
    assertEquals(receiptId, json.readTree(replay.body()).get("receipt_id").asString());
    assertEquals(
        409,
        postWithIdempotencyKey(
                "/v1/qualification-policies",
                writer,
                request.replace("Initial qualification criteria", "Changed qualification criteria"),
                "policy-create-1")
            .statusCode());

    assertEquals(
        400,
        postWithIdempotencyKey(
                "/v1/qualification-policies",
                writer,
                request.replace("\"max_age_seconds\":3600", "\"max_age_seconds\":0"),
                "policy-invalid")
            .statusCode());
    assertEquals(
        403,
        postWithIdempotencyKey(
                "/v1/qualification-policies/policy-1:decide",
                writer,
                "{\"expected_revision\":1,\"decision\":\"APPROVE\",\"reason\":\"reviewed\"}",
                "policy-decide-ungranted")
            .statusCode());

    HttpResponse<String> approved =
        postWithIdempotencyKey(
            "/v1/qualification-policies/policy-1:decide",
            reviewer,
            "{\"expected_revision\":1,\"decision\":\"APPROVE\",\"reason\":\"Independently reviewed\"}",
            "policy-decide-1");
    assertEquals(200, approved.statusCode(), approved.body());
    assertThat(approved.body()).contains("\"decision\":\"APPROVED\"").contains("\"current_eligible\":true");
    HttpResponse<String> decisionReplay =
        postWithIdempotencyKey(
            "/v1/qualification-policies/policy-1:decide",
            reviewer,
            "{\"expected_revision\":1,\"decision\":\"APPROVE\",\"reason\":\"Independently reviewed\"}",
            "policy-decide-1");
    assertEquals(200, decisionReplay.statusCode(), decisionReplay.body());
    assertEquals(
        json.readTree(approved.body()).get("receipt_id").asString(),
        json.readTree(decisionReplay.body()).get("receipt_id").asString());
    assertEquals(
        200,
        get("/v1/qualification-policies?service_id=svc-policy", reviewer).statusCode());
    assertThat(get("/v1/qualification-policies?service_id=svc-other", reviewer).body())
        .contains("PERMISSION_DENIED");
    String revisionTwo =
        request
            .replace("\"expected_revision\":0", "\"expected_revision\":1")
            .replace("Initial qualification criteria", "Revised qualification criteria");
    HttpResponse<String> revised =
        postWithIdempotencyKey(
            "/v1/qualification-policies", writer, revisionTwo, "policy-revision-2");
    assertEquals(200, revised.statusCode(), revised.body());
    assertThat(revised.body())
        .contains("\"revision\":2")
        .contains("\"decision\":\"DRAFT\"")
        .contains("\"current_eligible\":false");
    assertThat(get("/v1/qualification-policies/policy-1?revision=1", reviewer).body())
        .contains("\"decision\":\"APPROVED\"")
        .contains("\"current_eligible\":false");
    assertThat(get("/v1/qualification-policies/policy-1/history", reviewer).body())
        .contains("AUTHORIZED_VISIBLE_SCOPE_ONLY")
        .contains("\"revision\":1")
        .contains("\"revision\":2")
        .contains("\"decision\":\"APPROVED\"");
    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE uacrf_evidence.qualification_policy_revision SET proposal_reason=? "
                    + "WHERE tenant_id=? AND id=? AND revision=1",
                "mutated",
                tenant,
                "policy-1"));

    String observation =
        schemaObservation(
            "policy-does-not-qualify",
            "source-1",
            1,
            "measurement.latency",
            service,
            "UACRF_OBSERVATION_V1",
            "{\"metric\":\"latency\",\"value\":1,\"unit\":\"ms\","
                + "\"window_start\":\"2025-12-31T23:00:00Z\","
                + "\"window_end\":\"2026-01-01T00:00:00Z\"}");
    assertEquals(202, post("/internal/v1/observations", writer, observation).statusCode());
    assertEquals(
        "UNVERIFIED",
        jdbc.queryForObject(
            "SELECT qualification_state FROM uacrf_evidence.evidence_event "
                + "WHERE tenant_id=? AND id=?",
            String.class,
            tenant,
            "policy-does-not-qualify"));
  }

  @Test
  void qualificationWaiverRequiresScopedCurrentSignedReviewAndSupportsRevocation() throws Exception {
    String tenant = "tenant-waiver";
    String service = "svc-waiver";
    String proposerId = "waiver-writer";
    String reviewerId = "waiver-reviewer";
    String proposer =
        tokenWithPrincipalId(
            proposerId,
            tenant,
            service,
            List.of(
                "evidence.event.write",
                "evidence.qualification.policy.read",
                "evidence.qualification.policy.write",
                "evidence.qualification.policy.review",
                "evidence.qualification.waiver.read",
                "evidence.qualification.waiver.write",
                "evidence.qualification.waiver.review",
                "evidence.qualification.waiver.revoke"));
    String reviewer =
        tokenWithPrincipalId(
            reviewerId,
            tenant,
            service,
            List.of(
                "evidence.qualification.policy.review",
                "evidence.qualification.waiver.read",
                "evidence.qualification.waiver.review",
                "evidence.qualification.waiver.revoke"));
    String policyRequest =
        """
        {"id":"waiver-policy","service_id":"svc-waiver","purpose":"assessment",
         "expected_revision":0,"expires_at":"2999-01-01T00:00:00Z",
         "definition":{"requirements":[{"id":"availability","mandatory":true,
           "max_age_seconds":3600,"trusted_source_ids":["source-1"],
           "selector":{"kind":"CONFIGURATION","resource_id":"resource-1"},"waivable":true},
           {"id":"nonwaivable","mandatory":true,"max_age_seconds":3600,
            "trusted_source_ids":["source-1"],
            "selector":{"kind":"CONFIGURATION","resource_id":"resource-2"},"waivable":false}]},
         "reason":"Approved qualification criteria"}
        """;
    assertEquals(
        200,
        postWithIdempotencyKey(
                "/v1/qualification-policies", proposer, policyRequest, "waiver-policy-propose")
            .statusCode());
    assertEquals(
        200,
        postWithIdempotencyKey(
                "/v1/qualification-policies/waiver-policy:decide",
                reviewer,
                "{\"expected_revision\":1,\"decision\":\"APPROVE\",\"reason\":\"Reviewed\"}",
                "waiver-policy-approve")
            .statusCode());

    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    String waiverRequest =
        """
        {"id":"waiver-1","policy_id":"waiver-policy","policy_revision":1,
         "requirement_id":"availability","observation_window_start":"%s",
         "observation_window_end":"%s","expires_at":"%s","gap_types":["MISSING"],
         "reason":"Provider outage documented","expected_revision":0}
        """
            .formatted(now.minusSeconds(120), now, now.plusSeconds(900));
    HttpResponse<String> deniedWaiver =
        postWithIdempotencyKey(
            "/v1/qualification-waivers",
            proposer,
            waiverRequest
                .replace("\"waiver-1\"", "\"waiver-denied\"")
                .replace("\"availability\"", "\"nonwaivable\""),
            "waiver-propose-not-waivable");
    assertEquals(409, deniedWaiver.statusCode(), deniedWaiver.body());
    assertThat(deniedWaiver.body()).contains("REQUIREMENT_NOT_WAIVABLE");
    HttpResponse<String> draft =
        postWithIdempotencyKey(
            "/v1/qualification-waivers", proposer, waiverRequest, "waiver-propose");
    assertEquals(200, draft.statusCode(), draft.body());
    assertThat(draft.body())
        .contains("\"state\":\"DRAFT\"")
        .contains("\"current_eligible\":false")
        .contains("\"signing_material\"");
    String draftReceiptId = json.readTree(draft.body()).get("receipt_id").asString();
    HttpResponse<String> replay =
        postWithIdempotencyKey(
            "/v1/qualification-waivers", proposer, waiverRequest, "waiver-propose");
    assertEquals(draftReceiptId, json.readTree(replay.body()).get("receipt_id").asString());
    assertEquals(
        409,
        postWithIdempotencyKey(
                "/v1/qualification-waivers",
                proposer,
                waiverRequest.replace("Provider outage documented", "Changed content"),
                "waiver-propose")
            .statusCode());
    assertEquals(
        403,
        get(
                "/v1/qualification-waivers/waiver-1",
                tokenWithPrincipalId(
                    "waiver-other-service",
                    tenant,
                    "svc-other",
                    List.of("evidence.qualification.waiver.read")))
            .statusCode());

    String proposerReview =
        """
        {"expected_revision":1,"decision":"APPROVE","reason":"Self review",
         "signed_assertion":"not-a-valid-assertion"}
        """;
    assertEquals(
        409,
        postWithIdempotencyKey(
                "/v1/qualification-waivers/waiver-1:decide",
                proposer,
                proposerReview,
                "waiver-self-review")
            .statusCode());
    String digest =
        json.readTree(draft.body())
            .get("waiver")
            .get("signing_material")
            .get("digest")
            .asString();
    String invalidAssertion =
        signedWaiverAssertion(
            tenant,
            reviewerId,
            "waiver-1",
            digest.charAt(0) == '0' ? "1" + digest.substring(1) : "0" + digest.substring(1));
    String decisionRequest =
        "{\"expected_revision\":1,\"decision\":\"APPROVE\",\"reason\":\"Independent review\","
            + "\"signed_assertion\":\""
            + invalidAssertion
            + "\"}";
    assertEquals(
        422,
        postWithIdempotencyKey(
                "/v1/qualification-waivers/waiver-1:decide",
                reviewer,
                decisionRequest,
                "waiver-approval-invalid")
            .statusCode());

    String overflowingLifetimeAssertion =
        signedWaiverAssertion(
            tenant,
            reviewerId,
            "waiver-1",
            digest,
            Long.MIN_VALUE,
            Long.MIN_VALUE,
            Long.MAX_VALUE);
    String overflowingDecision =
        "{\"expected_revision\":1,\"decision\":\"APPROVE\",\"reason\":\"Invalid time claims\","
            + "\"signed_assertion\":\""
            + overflowingLifetimeAssertion
            + "\"}";
    assertEquals(
        422,
        postWithIdempotencyKey(
                "/v1/qualification-waivers/waiver-1:decide",
                reviewer,
                overflowingDecision,
                "waiver-approval-overflow")
            .statusCode());

    String signedAssertion = signedWaiverAssertion(tenant, reviewerId, "waiver-1", digest);
    decisionRequest =
        "{\"expected_revision\":1,\"decision\":\"APPROVE\",\"reason\":\"Independent review\","
            + "\"signed_assertion\":\""
            + signedAssertion
            + "\"}";
    HttpResponse<String> approved =
        postWithIdempotencyKey(
            "/v1/qualification-waivers/waiver-1:decide",
            reviewer,
            decisionRequest,
            "waiver-approval");
    assertEquals(200, approved.statusCode(), approved.body());
    assertThat(approved.body())
        .contains("\"state\":\"APPROVED\"")
        .contains("\"current_eligible\":true")
        .contains("\"reviewer_id\":\"waiver-reviewer\"");
    HttpResponse<String> approvalReplay =
        postWithIdempotencyKey(
            "/v1/qualification-waivers/waiver-1:decide",
            reviewer,
            decisionRequest,
            "waiver-approval");
    assertEquals(
        json.readTree(approved.body()).get("receipt_id").asString(),
        json.readTree(approvalReplay.body()).get("receipt_id").asString());
    assertThat(
            get(
                    "/v1/qualification-waivers?service_id=svc-waiver&policy_id=waiver-policy",
                    reviewer)
                .body())
        .contains("\"coverage\":\"AUTHORIZED_VISIBLE_SCOPE_ONLY\"")
        .contains("\"id\":\"waiver-1\"");

    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE uacrf_evidence.qualification_waiver SET reason=? "
                    + "WHERE tenant_id=? AND environment_id='env-1' AND id=?",
                "mutated",
                tenant,
                "waiver-1"));

    HttpResponse<String> revoked =
        postWithIdempotencyKey(
            "/v1/qualification-waivers/waiver-1:revoke",
            proposer,
            "{\"expected_revision\":2,\"reason\":\"Evidence gap resolved\"}",
            "waiver-revoke");
    assertEquals(200, revoked.statusCode(), revoked.body());
    assertThat(revoked.body()).contains("\"state\":\"REVOKED\"", "\"current_eligible\":false");
    HttpResponse<String> revokeReplay =
        postWithIdempotencyKey(
            "/v1/qualification-waivers/waiver-1:revoke",
            proposer,
            "{\"expected_revision\":2,\"reason\":\"Evidence gap resolved\"}",
            "waiver-revoke");
    assertEquals(
        json.readTree(revoked.body()).get("receipt_id").asString(),
        json.readTree(revokeReplay.body()).get("receipt_id").asString());
  }

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
    String payload = providerMetricPayload("p2");
    assertThat(post("/internal/v1/observations", submit, evidence("e2", "PROVIDER", "src-2", 1, payload, sha(payload))).body())
        .contains("UNVERIFIED");

    String decision = "{\"decision\":\"QUALIFY\",\"reason\":\"verified\",\"expected_revision\":1}";
    String selfReview = bearer("submitter", "tenant-p", List.of("evidence.qualification.review"));
    assertThat(post("/v1/evidence/e2/reviews", selfReview, decision).body()).contains("REVIEWER_NOT_INDEPENDENT");
    assertEquals(409, post("/v1/evidence/e2/reviews", review, decision.replace("\"expected_revision\":1", "\"expected_revision\":9")).statusCode());
    assertEquals(200, post("/v1/evidence/e2/reviews", review, decision).statusCode());
    assertThat(post("/v1/evidence/e2/reviews", review, decision.replace("1}", "2}")).body()).contains("ALREADY_REVIEWED");

    assertThat(post("/internal/v1/qualifications/e2:receipt", review, receiptBody(sha(payload))).body())
        .contains("\"usable_as_evidence\":true");
    assertThat(post("/internal/v1/qualifications/e2:receipt", review, receiptBody(sha("other"))).body())
        .contains("\"usable_as_evidence\":false").contains("\"digest_matches\":false");
    assertThat(
            post(
                    "/internal/v1/qualifications/e2:receipt",
                    review,
                    receiptBody(sha(payload)).replace("2999-01-01", "2000-01-01"))
                .body())
        .contains("\"within_cutoff\":false");
  }

  @Test
  void measurementReceiptBindsQualifiedPayloadAndHidesOtherTenants() throws Exception {
    String submit = bearer("submitter", "tenant-measurement", SUBMITTER);
    String reviewer = bearer("reviewer", "tenant-measurement", REVIEWER);
    String payload =
        "{\"metric\":\"latency\",\"value\":1.250,\"unit\":\"ms\","
            + "\"window_start\":\"2025-12-31T23:00:00Z\","
            + "\"window_end\":\"2026-01-01T00:00:00Z\"}";
    String request = measurementRequest("latency", "2999-01-01T00:00:00Z");
    String path = "/internal/v1/qualifications/measurement-1:measurement";
    assertEquals(202, post("/internal/v1/observations", submit,
        evidence("measurement-1", "PROVIDER", "measurement-source", 1, payload, sha(payload))).statusCode());
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
    String alteredPayload = "{\"metric\":\"latency\",\"value\":999,\"unit\":\"ms\"}";
    jdbc.update(
        "UPDATE uacrf_evidence.evidence_event "
            + "SET payload=?, payload_byte_length=octet_length(convert_to(?, 'UTF8')) "
            + "WHERE tenant_id=? AND id=?",
        alteredPayload,
        alteredPayload,
        "tenant-measurement",
        "measurement-1");
    assertThat(post(path, reviewer, request).body()).contains("\"usable_as_evidence\":false").contains("digest");
  }

  @Test
  void measurementReceiptRejectsEvidenceWithANonMeasurementKind() throws Exception {
    String submit = bearer("submitter", "tenant-measurement-kind", SUBMITTER);
    String reviewer = bearer("reviewer", "tenant-measurement-kind", REVIEWER);
    String payload =
        "{\"resource_id\":\"r1\",\"configuration_digest\":\""
            + "a".repeat(64)
            + "\",\"source_version\":\"1\",\"source_checked_at\":\"2026-01-01T00:00:00Z\","
            + "\"change_type\":\"SNAPSHOT\"}";
    String evidence = evidence(
        "configuration-as-measurement",
        "PROVIDER",
        "configuration-source",
        1,
        payload,
        sha(payload))
        .replace("measurement.latency", "configuration.snapshot");

    assertEquals(202, post("/internal/v1/observations", submit, evidence).statusCode());
    assertEquals(
        200,
        post(
                "/v1/evidence/configuration-as-measurement/reviews",
                reviewer,
                "{\"decision\":\"QUALIFY\",\"reason\":\"verified\",\"expected_revision\":1}")
            .statusCode());

    var receipt =
        json.readTree(
            post(
                    "/internal/v1/qualifications/configuration-as-measurement:measurement",
                    reviewer,
                    measurementRequest("latency", "2999-01-01T00:00:00Z"))
                .body());
    assertThat(receipt.get("usable_as_evidence").asBoolean()).isFalse();
    assertThat(receipt.get("reason").asString()).contains("not a measurement observation");
  }

  @Test
  void receiptsRejectObservationsAfterCutoffAndAcceptTheInclusiveBoundary() throws Exception {
    String submit = bearer("submitter", "tenant-cutoff", SUBMITTER);
    String reviewer = bearer("reviewer", "tenant-cutoff", REVIEWER);
    String payload =
        "{\"metric\":\"latency\",\"value\":1,\"unit\":\"ms\","
            + "\"window_start\":\"2025-12-31T23:00:00Z\","
            + "\"window_end\":\"2026-01-01T00:00:00Z\"}";
    java.time.Instant observed = java.time.Instant.now().plusSeconds(120)
        .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    String original =
        withObservedAt(
            evidence("future-observation", "PROVIDER", "future-source", 1, payload, sha(payload)),
            observed.toString());
    assertEquals(202, post("/internal/v1/observations", submit, original).statusCode());
    assertEquals(200, post("/v1/evidence/future-observation/reviews", reviewer,
        "{\"decision\":\"QUALIFY\",\"reason\":\"verified\",\"expected_revision\":1}").statusCode());

    String qualification = "/internal/v1/qualifications/future-observation:receipt";
    String measurement = "/internal/v1/qualifications/future-observation:measurement";
    String before = observed.minusNanos(1).toString();
    String beforeReceipt = receiptBody(sha(payload)).replace("2999-01-01T00:00:00Z", before);
    assertThat(post(qualification, reviewer, beforeReceipt).body())
        .contains("\"usable_as_evidence\":false", "\"within_cutoff\":false", "Observation happened after the cutoff.");
    assertThat(post(measurement, reviewer, measurementRequest("latency", before)).body())
        .contains("\"usable_as_evidence\":false", "Observation happened after the cutoff.");

    String atReceipt = receiptBody(sha(payload)).replace("2999-01-01T00:00:00Z", observed.toString());
    assertThat(post(qualification, reviewer, atReceipt).body())
        .contains("\"usable_as_evidence\":true", "\"within_cutoff\":true");
    assertThat(post(measurement, reviewer, measurementRequest("latency", observed.toString())).body())
        .contains("\"usable_as_evidence\":true");
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
      String id = "malformed-" + i;
      HttpResponse<String> quarantined =
          post(
              "/internal/v1/observations",
              submit,
              evidence(id, "PROVIDER", "malformed-source", i + 1, payload, sha(payload)));
      assertEquals(202, quarantined.statusCode());
      assertThat(quarantined.body()).contains("QUARANTINED", "SOURCE_SCHEMA_INVALID");
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
    String payload =
        "{\"metric\":\"latency\",\"value\":1.25,\"unit\":\"ms\","
            + "\"window_start\":\"2025-12-31T23:00:00Z\","
            + "\"window_end\":\"2026-01-01T00:00:00Z\"}";
    String original =
        withObservedAt(
            evidence("e-conflict", "PROVIDER", "conflict-source", 1, payload, sha(payload)),
            "2026-01-01T00:00:00.123456789Z");
    String receipt = "/internal/v1/qualifications/e-conflict:receipt";
    String measurement = "/internal/v1/qualifications/e-conflict:measurement";
    assertThat(post("/internal/v1/observations", submit, original).body()).contains("ACCEPTED");
    assertEquals(200, post("/v1/evidence/e-conflict/reviews", reviewer,
        "{\"decision\":\"QUALIFY\",\"reason\":\"verified\",\"expected_revision\":1}").statusCode());
    assertThat(post(receipt, reviewer, receiptBody(sha(payload))).body()).contains("\"usable_as_evidence\":true");
    assertThat(post(measurement, reviewer, measurementRequest("latency", "2999-01-01T00:00:00Z")).body())
        .contains("\"usable_as_evidence\":true");
    assertThat(post("/internal/v1/observations", submit, original).body()).contains("DUPLICATE");

    String changedTime = withObservedAt(original, "2026-01-02T00:00:00Z");
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
    String payloadDigest = sha(providerMetricPayload("payload"));
    assertThat(post(receipt, reviewer, receiptBody(payloadDigest)).body()).contains("\"usable_as_evidence\":true");
    assertThat(post("/internal/v1/observations", submit,
        evidence("different-id", "PROVIDER", "sequence-source", 2, "different", null)).body())
        .contains("STALE_SEQUENCE");
    assertThat(post(receipt, reviewer, receiptBody(payloadDigest)).body())
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
      String legacyPayload = "legacy naïve ☃";
      oldDb.update(
          "INSERT INTO uacrf_evidence.evidence_event "
              + "(tenant_id, environment_id, id, source_class, source_ref, sequence, observed_at, "
              + "kind, payload, payload_digest, submitted_by, qualification_state, revision) "
              + "VALUES (?, ?, ?, 'SAMPLE_LOCAL', ?, 1, '2026-01-01T00:00:00Z', ?, ?, ?, ?, "
              + "'SAMPLE_ONLY', 1)",
          "tenant-legacy",
          "env-1",
          "legacy-evidence",
          "legacy-source",
          "measurement.latency",
          legacyPayload,
          sha(legacyPayload),
          "legacy-submitter");
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
      oldDb.update(
          "INSERT INTO uacrf_evidence.quarantined_evidence "
              + "(quarantine_id, tenant_id, environment_id, evidence_id, source_ref, sequence, "
              + "reason, payload, claimed_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
          UUID.randomUUID(),
          "tenant-legacy",
          "env-1",
          "legacy-digest-mismatch",
          "old-source",
          2,
          "DIGEST_MISMATCH",
          legacyPayload,
          "0".repeat(64));
      Flyway.configure().dataSource(source).load().migrate();
      assertEquals(
          legacyPayload.getBytes(StandardCharsets.UTF_8).length,
          oldDb.queryForObject(
              "SELECT payload_byte_length FROM uacrf_evidence.evidence_event "
                  + "WHERE tenant_id='tenant-legacy' AND id='legacy-evidence'",
              Long.class));
      assertEquals(
          "old-payload".getBytes(StandardCharsets.UTF_8).length,
          oldDb.queryForObject(
              "SELECT payload_byte_length FROM uacrf_evidence.quarantined_evidence "
                  + "WHERE tenant_id='tenant-legacy' AND evidence_id='old-evidence'",
              Long.class));
      assertEquals(
          sha("old-payload"),
          oldDb.queryForObject(
              "SELECT trim(actual_payload_digest) FROM uacrf_evidence.quarantined_evidence "
                  + "WHERE tenant_id='tenant-legacy' AND evidence_id='old-evidence'",
              String.class));
      assertThat(
              oldDb.queryForObject(
                  "SELECT actual_payload_digest FROM uacrf_evidence.quarantined_evidence "
                      + "WHERE tenant_id='tenant-legacy' AND evidence_id='legacy-digest-mismatch'",
                  String.class))
          .isNull();
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

  private String evidence(String id, String sourceClass, String source, long sequence, String payload, String digest)
      throws Exception {
    String storedPayload = payload;
    if ("PROVIDER".equals(sourceClass) && digest == null) {
      storedPayload = providerMetricPayload(payload);
    }
    return ("{\"id\":\"%s\",\"source_class\":\"%s\",\"source_ref\":\"%s\","
            + "\"sequence\":%d,\"observed_at\":\"2026-01-01T00:00:00Z\","
            + "\"kind\":\"measurement.latency\",\"service_id\":%s,\"schema_version\":%s,"
            + "\"payload\":%s,\"payload_digest\":\"%s\"}")
        .formatted(
            id,
            sourceClass,
            source,
            sequence,
            "PROVIDER".equals(sourceClass) ? "\"svc-test\"" : "null",
            "PROVIDER".equals(sourceClass) ? "\"UACRF_OBSERVATION_V1\"" : "null",
            json.writeValueAsString(storedPayload),
            digest == null ? sha(storedPayload) : digest);
  }

  private static String providerMetricPayload(String seed) {
    long value = Math.abs((long) seed.hashCode());
    return "{\"metric\":\"latency\",\"value\":" + value + ",\"unit\":\"ms\","
        + "\"window_start\":\"2025-12-31T23:00:00Z\","
        + "\"window_end\":\"2026-01-01T00:00:00Z\"}";
  }

  private static String withObservedAt(String requestBody, String observedAt) {
    String field = "\"observed_at\":\"";
    int start = requestBody.indexOf(field);
    if (start < 0) {
      throw new IllegalArgumentException("The observation request is missing observed_at.");
    }
    start += field.length();
    int end = requestBody.indexOf('"', start);
    if (end < 0) {
      throw new IllegalArgumentException("The observation request has a malformed observed_at.");
    }
    return requestBody.substring(0, start) + observedAt + requestBody.substring(end);
  }

  private String schemaObservation(
      String id,
      String source,
      long sequence,
      String kind,
      String serviceId,
      String schemaVersion,
      String payload)
      throws Exception {
    return ("{\"id\":\"%s\",\"source_class\":\"PROVIDER\",\"source_ref\":\"%s\","
            + "\"sequence\":%d,\"observed_at\":\"2026-01-01T00:00:00Z\",\"kind\":\"%s\","
            + "\"service_id\":\"%s\",\"schema_version\":\"%s\",\"payload\":%s,"
            + "\"payload_digest\":\"%s\"}")
        .formatted(
            id,
            source,
            sequence,
            kind,
            serviceId,
            schemaVersion,
            json.writeValueAsString(payload),
            sha(payload));
  }

  private static String event(String id, long sequence, String payload, String digest) {
    return "{\"event_id\":\"%s\",\"source_service\":\"S03\",\"event_type\":\"connections.observation.accepted\",\"aggregate_id\":\"obs-1\",\"aggregate_sequence\":%d,\"payload\":\"%s\",\"payload_digest\":\"%s\"}"
        .formatted(id, sequence, payload, digest == null ? sha(payload) : digest);
  }

  private static String receiptBody(String digest) {
    return "{\"purpose\":\"assessment\",\"expected_digest\":\"%s\",\"cutoff\":\"2999-01-01T00:00:00Z\"}".formatted(digest);
  }

  private static String waiverTrustSet() {
    return """
        {"revision":1,"issuer":"https://waiver-authority.example.test","audience":"uacrf-evidence",
         "keys":[{"kid":"waiver-test-key","algorithm":"RS256","public_key_base64":"%s",
           "tenant_id":"tenant-waiver","environment_id":"env-1","service_id":"svc-waiver",
           "reviewer_id":"waiver-reviewer"}]}
        """
        .formatted(Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded()));
  }

  private String signedWaiverAssertion(
      String tenant, String reviewerId, String waiverId, String waiverDigest) throws Exception {
    Instant issuedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    return signedWaiverAssertion(
        tenant,
        reviewerId,
        waiverId,
        waiverDigest,
        issuedAt.getEpochSecond(),
        issuedAt.getEpochSecond(),
        issuedAt.plusSeconds(300).getEpochSecond());
  }

  private String signedWaiverAssertion(
      String tenant,
      String reviewerId,
      String waiverId,
      String waiverDigest,
      long issuedAt,
      long notBefore,
      long expiresAt)
      throws Exception {
    Map<String, Object> claims = new TreeMap<>();
    claims.put("iss", "https://waiver-authority.example.test");
    claims.put("aud", "uacrf-evidence");
    claims.put("sub", reviewerId);
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("tenant_id", tenant);
    claims.put("environment_id", "env-1");
    claims.put("iat", issuedAt);
    claims.put("nbf", notBefore);
    claims.put("exp", expiresAt);
    claims.put("purpose", "qualification-waiver");
    claims.put("waiver_id", waiverId);
    claims.put("waiver_digest", waiverDigest);
    claims.put("approved_by", reviewerId);
    String header =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                "{\"alg\":\"RS256\",\"kid\":\"waiver-test-key\","
                        .concat("\"typ\":\"uacrf.qualification-waiver+jws\"}")
                    .getBytes(StandardCharsets.UTF_8));
    String payload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                org.uacrf.security.OwnerReceiptSignature
                    .canonicalize(json.writeValueAsString(claims))
                    .getBytes(StandardCharsets.UTF_8));
    String signingInput = header + "." + payload;
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign((java.security.PrivateKey) KEY_PAIR.getPrivate());
    signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    return signingInput
        + "."
        + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
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

  private HttpResponse<String> postWithIdempotencyKey(
      String path, String bearer, String body, String idempotencyKey) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", bearer)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idempotencyKey)
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

  private static String tokenWithPrincipalId(
      String principalId, String tenant, String scope, List<String> capabilities) {
    DelegationPrincipal principal =
        new DelegationPrincipal(
            principalId,
            tenant,
            "env-1",
            scope,
            "https://idp.example.test",
            principalId,
            capabilities,
            List.of(),
            List.of());
    return "Bearer "
        + new DelegationTokenIssuer(ENCODER, Clock.systemUTC(), "https://uacrf.local/s01", KEY_ID)
            .issue(principal, "S04");
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
