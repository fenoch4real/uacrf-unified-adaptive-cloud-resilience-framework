package org.uacrf.objectives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
class ObjectivesApplicationTests {
  private static final String KEY_ID = "objectives-test-key";
  private static final KeyPair KEY_PAIR = generateKeyPair();
  private static final JwtEncoder ENCODER = DelegationJwtEncoderFactory.create(KEY_PAIR, KEY_ID);
  private static final String POSTGRES_IMAGE =
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81";
  private static final List<String> PROPOSER =
      List.of("objectives.objective.write", "objectives.objective.read");
  private static final List<String> DECIDER =
      List.of("objectives.objective.review", "objectives.objective.read");
  private static final List<String> ACTIVATOR =
      List.of("objectives.objective.activate", "objectives.objective.read");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(POSTGRES_IMAGE)
          .withDatabaseName("uacrf_objectives")
          .withUsername("objectives_test")
          .withPassword("objectives_test");

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
  @Autowired private ObjectivesService objectives;
  @MockitoBean private ObjectiveWorkloadAuthorityClient workloadAuthority;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void objectiveMovesThroughSeparateTransitionsWithIndependentDecision() throws Exception {
    String propose = bearer("proposer", "t-life", PROPOSER);
    String decide = bearer("decider", "t-life", DECIDER);
    String activate = bearer("activator", "t-life", ACTIVATOR);
    assertEquals(201, post("/v1/objectives", propose, objective("o1", "w1", "latency.p95", "LTE", "250", "ms")).statusCode());
    assertThat(post("/v1/objectives/o1:decide", decide, decision("APPROVE", 1)).body()).contains("INVALID_STATE");
    assertThat(post("/v1/objectives/o1:submit", propose, "{\"expected_revision\":9}").body()).contains("REVISION_MISMATCH");
    assertThat(post("/v1/objectives/o1:submit", propose, "{\"expected_revision\":1}").body()).contains("\"state\":\"SUBMITTED\"");

    String selfDecide = bearer("proposer", "t-life", DECIDER);
    assertThat(post("/v1/objectives/o1:decide", selfDecide, decision("APPROVE", 2)).body()).contains("REVIEWER_NOT_INDEPENDENT");
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_objectives.objective_decision WHERE tenant_id = 't-life'", Integer.class));
    assertThat(post("/v1/objectives/o1:activate", activate, "{\"expected_revision\":2}").body()).contains("INVALID_STATE");
    assertThat(post("/v1/objectives/o1:decide", decide, decision("APPROVE", 2)).body()).contains("\"state\":\"APPROVED\"");
    assertThat(jdbc.queryForMap("SELECT revision, decision, reason, decided_by FROM uacrf_objectives.objective_decision WHERE tenant_id = 't-life'"))
        .containsEntry("revision", 3L).containsEntry("decision", "APPROVE")
        .containsEntry("decided_by", "https://uacrf.local/s01|decider");
    assertThatThrownBy(() -> jdbc.update("DELETE FROM uacrf_objectives.objective_decision WHERE tenant_id = 't-life'"))
        .hasMessageContaining("objective decisions are immutable and retained");
    assertThatThrownBy(() -> jdbc.update("UPDATE uacrf_objectives.objective_decision SET reason = 'changed' WHERE tenant_id = 't-life'"))
        .hasMessageContaining("objective decisions are immutable and retained");
    HttpResponse<String> active = post("/v1/objectives/o1:activate", activate, "{\"expected_revision\":3}");
    assertThat(active.body()).contains("\"state\":\"ACTIVE\"").contains("\"activated_by\":\"https://uacrf.local/s01|activator\"");
    assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM uacrf_objectives.outbox_event WHERE tenant_id = 't-life'", Integer.class));
    verify(workloadAuthority)
        .check(
            any(Jwt.class),
            eq("w1"),
            eq(ObjectiveWorkloadAuthorityClient.PROPOSAL_PURPOSE));
    verify(workloadAuthority)
        .check(
            any(Jwt.class),
            eq("w1"),
            eq(ObjectiveWorkloadAuthorityClient.ACTIVATION_PURPOSE));
  }

  @Test
  void workloadAuthorityDenialPreventsObjectiveProposal() throws Exception {
    String propose = bearer("proposer", "t-owner-proposal", PROPOSER);
    doThrow(
            new ObjectivesException(
                HttpStatus.CONFLICT,
                "WORKLOAD_NOT_ELIGIBLE",
                "S02 did not confirm an active workload."))
        .when(workloadAuthority)
        .check(
            any(Jwt.class),
            eq("w-owner-proposal"),
            eq(ObjectiveWorkloadAuthorityClient.PROPOSAL_PURPOSE));

    HttpResponse<String> denied =
        post(
            "/v1/objectives",
            propose,
            objective(
                "owner-proposal",
                "w-owner-proposal",
                "availability",
                "GTE",
                "99.9",
                "%"));

    assertEquals(409, denied.statusCode());
    assertThat(denied.body()).contains("WORKLOAD_NOT_ELIGIBLE");
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_objectives.objective "
                + "WHERE tenant_id = 't-owner-proposal'",
            Integer.class));
  }

  @Test
  void workloadAuthorityDenialAtActivationPreservesTheExistingActiveObjective()
      throws Exception {
    String propose = bearer("proposer", "t-owner-activation", PROPOSER);
    String decide = bearer("decider", "t-owner-activation", DECIDER);
    String activate = bearer("activator", "t-owner-activation", ACTIVATOR);
    approveAndActivate("owner-first", "250", propose, decide, activate);
    assertEquals(
        201,
        post(
                "/v1/objectives",
                propose,
                objective(
                    "owner-next",
                    "w2",
                    "latency.p95",
                    "LTE",
                    "200",
                    "ms"))
            .statusCode());
    post("/v1/objectives/owner-next:submit", propose, "{\"expected_revision\":1}");
    post(
        "/v1/objectives/owner-next:decide",
        decide,
        decision("APPROVE", 2));
    Integer eventsBefore =
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_objectives.outbox_event "
                + "WHERE tenant_id = 't-owner-activation'",
            Integer.class);
    doThrow(
            new ObjectivesException(
                HttpStatus.CONFLICT,
                "WORKLOAD_NOT_ELIGIBLE",
                "S02 did not confirm an active workload."))
        .when(workloadAuthority)
        .check(
            any(Jwt.class),
            eq("w2"),
            eq(ObjectiveWorkloadAuthorityClient.ACTIVATION_PURPOSE));

    HttpResponse<String> denied =
        post(
            "/v1/objectives/owner-next:activate",
            activate,
            "{\"expected_revision\":3}");

    assertEquals(409, denied.statusCode());
    assertThat(get("/v1/objectives/owner-first", activate).body())
        .contains("\"state\":\"ACTIVE\"");
    assertThat(get("/v1/objectives/owner-next", activate).body())
        .contains("\"state\":\"APPROVED\"");
    assertEquals(
        eventsBefore,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_objectives.outbox_event "
                + "WHERE tenant_id = 't-owner-activation'",
            Integer.class));
  }

  @Test
  void activationSupersedesPreviousAndReceiptReflectsActiveSet() throws Exception {
    String propose = bearer("proposer", "t-sup", PROPOSER);
    String decide = bearer("decider", "t-sup", DECIDER);
    String activate = bearer("activator", "t-sup", ACTIVATOR);
    approveAndActivate("a1", "250", propose, decide, activate);
    String first = post("/internal/v1/workloads/w2/objectives:receipt", activate, "{\"purpose\":\"assessment\"}").body();
    assertThat(first).contains("\"id\":\"a1\"");
    approveAndActivate("a2", "200", propose, decide, activate);
    assertThat(get("/v1/objectives/a1", activate).body()).contains("\"state\":\"SUPERSEDED\"");
    String second = post("/internal/v1/workloads/w2/objectives:receipt", activate, "{\"purpose\":\"assessment\"}").body();
    assertThat(second).contains("\"id\":\"a2\"").doesNotContain("\"id\":\"a1\"");
    assertThat(digest(first)).isNotEqualTo(digest(second));
    assertThat(post("/internal/v1/workloads/none/objectives:receipt", activate, "{\"purpose\":\"x\"}").body()).contains("\"objectives\":[]");
  }

  @Test
  void mandatoryOrganizationConstraintBlocksOutOfRangeTargets() throws Exception {
    String admin = bearer("admin", "t-con", List.of("objectives.constraint.write"));
    String propose = bearer("proposer", "t-con", PROPOSER);
    assertEquals(200, put("/v1/organization-constraints/latency.p95", admin, "{\"unit\":\"ms\",\"min_target\":\"100\",\"max_target\":\"500\"}").statusCode());
    post("/v1/objectives", propose, objective("c1", "w3", "latency.p95", "LTE", "900", "ms"));
    assertThat(post("/v1/objectives/c1:submit", propose, "{\"expected_revision\":1}").body()).contains("CONSTRAINT_VIOLATION");
    post("/v1/objectives", propose, objective("c2", "w3", "latency.p95", "LTE", "300", "s"));
    assertThat(post("/v1/objectives/c2:submit", propose, "{\"expected_revision\":1}").body()).contains("CONSTRAINT_VIOLATION");
    post("/v1/objectives", propose, objective("c3", "w3", "latency.p95", "LTE", "300", "ms"));
    assertThat(post("/v1/objectives/c3:submit", propose, "{\"expected_revision\":1}").body()).contains("SUBMITTED");
    assertEquals(403, put("/v1/organization-constraints/latency.p95", propose, "{\"unit\":\"ms\"}").statusCode());
  }

  @Test
  void importedObjectivesKeepProvenanceAndLocalOnesRejectIt() throws Exception {
    String propose = bearer("proposer", "t-imp", PROPOSER);
    String imported = objective("i1", "w4", "availability", "GTE", "99.9", "%")
        .replace("\"origin\":\"LOCAL\"", "\"origin\":\"IMPORTED\"");
    assertThat(post("/v1/objectives", propose, imported).body()).contains("IMPORT_PROVENANCE_REQUIRED");
    String full = imported.replace("}", ",\"source_ref\":\"conn-1\",\"source_definition_id\":\"slo-7\",\"source_revision\":\"r12\",\"source_verified_at\":\"2026-01-01T00:00:00Z\"}");
    assertThat(post("/v1/objectives", propose, full).body()).contains("\"source_definition_id\":\"slo-7\"").contains("\"source_revision\":\"r12\"");
    String local = objective("i2", "w4", "availability", "GTE", "99.9", "%").replace("}", ",\"source_ref\":\"x\"}");
    assertEquals(400, post("/v1/objectives", propose, local).statusCode());
  }

  @Test
  void objectiveImportPreviewAdmissionIsDurableIdempotentAndExplicitlyPending() throws Exception {
    String propose = bearer("importer", "t-preview", PROPOSER);
    String body = importPreview("slo-7", "w-preview", 2, 4);

    HttpResponse<String> accepted =
        postWithIdempotency("/v1/objective-imports:preview", propose, body, "preview-key-1");
    assertEquals(202, accepted.statusCode());
    assertThat(accepted.body())
        .contains("\"status\":\"PENDING\"")
        .contains("\"runtime_verification\":\"NOT_ESTABLISHED\"");
    String receiptId = extractString(accepted.body(), "receipt_id");
    assertThat(accepted.body())
        .contains("\"read_resource\":\"/v1/objective-imports/" + receiptId + "\"");

    HttpResponse<String> replay =
        postWithIdempotency("/v1/objective-imports:preview", propose, body, "preview-key-1");
    assertEquals(200, replay.statusCode());
    assertThat(replay.body()).contains("\"receipt_id\":\"" + receiptId + "\"");
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_objectives.objective_import_preview_command "
                + "WHERE tenant_id = 't-preview'",
            Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_objectives.outbox_event WHERE tenant_id = 't-preview' "
                + "AND event_type = 'uacrf.s05.objective-import.preview.requested.v1'",
            Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_objectives.objective WHERE tenant_id = 't-preview'",
            Integer.class));

    HttpResponse<String> pending = get("/v1/objective-imports/" + receiptId, propose);
    assertEquals(202, pending.statusCode());
    assertThat(pending.body()).contains("\"status\":\"PENDING\"");
    assertEquals(
        403,
        get(
                "/v1/objective-imports/" + receiptId,
                token(
                    "scoped-reader",
                    "t-preview",
                    "w-preview",
                    List.of("objectives.objective.read")))
            .statusCode());
    assertEquals(
        403,
        get(
                "/v1/objective-imports/" + receiptId,
                token(
                    "other-workload-reader",
                    "t-preview",
                    "w-other",
                    List.of("objectives.objective.read")))
            .statusCode());
    assertEquals(
        403,
        get(
                "/v1/objective-imports/" + receiptId,
                token(
                    "no-read-capability",
                    "t-preview",
                    "*",
                    List.of("objectives.objective.write")))
            .statusCode());
    assertEquals(
        404,
        get("/v1/objective-imports/" + receiptId, bearer("reader", "t-other", PROPOSER))
            .statusCode());
  }

  @Test
  void pendingImportListingIsPaginatedAndContextScoped() throws Exception {
    String writer = bearer("importer", "t-list-imports", PROPOSER);
    String reader = bearer("reader", "t-list-imports", List.of("objectives.objective.read"));
    String firstId = extractString(postWithIdempotency("/v1/objective-imports:preview", writer,
        importPreview("slo-1", "w1", 1, 0), "list-1").body(), "receipt_id");
    String secondId = extractString(postWithIdempotency("/v1/objective-imports:preview", writer,
        importPreview("slo-2", "w2", 1, 0), "list-2").body(), "receipt_id");
    String otherId = extractString(postWithIdempotency("/v1/objective-imports:preview",
        bearer("importer", "t-list-other", PROPOSER),
        importPreview("other", "other-workload", 1, 0), "list-other").body(), "receipt_id");

    HttpResponse<String> first = get("/v1/objective-imports?pageSize=1", reader);
    assertEquals(200, first.statusCode());
    var firstPage = json.readTree(first.body());
    String item1 = firstPage.path("items").get(0).path("receipt_id").asText();
    String cursor = firstPage.path("next_cursor").asText();
    assertThat(cursor).isNotBlank();
    HttpResponse<String> second = get("/v1/objective-imports?pageSize=1&cursor=" + cursor, reader);
    assertEquals(200, second.statusCode());
    var secondPage = json.readTree(second.body());
    String item2 = secondPage.path("items").get(0).path("receipt_id").asText();
    assertThat(List.of(item1, item2)).containsExactlyInAnyOrder(firstId, secondId).doesNotContain(otherId);
    assertThat(secondPage.path("next_cursor").isNull()).isTrue();
    HttpResponse<String> otherEnvironment = get("/v1/objective-imports",
        tokenForEnvironment("reader", "t-list-imports", "env-other", "*", List.of("objectives.objective.read")));
    assertEquals(200, otherEnvironment.statusCode());
    assertThat(json.readTree(otherEnvironment.body()).path("items").isEmpty()).isTrue();
    assertEquals(400, get("/v1/objective-imports?cursor=not-a-cursor", reader).statusCode());
    assertEquals(400, get("/v1/objective-imports?pageSize=101", reader).statusCode());
    assertEquals(403, get("/v1/objective-imports",
        token("scoped", "t-list-imports", "w1", List.of("objectives.objective.read"))).statusCode());
    assertEquals(403, get("/v1/objective-imports",
        bearer("writer-only", "t-list-imports", List.of("objectives.objective.write"))).statusCode());
  }

  @Test
  void sampleInspectionIsRequestScopedAndDisabledByDefault() throws Exception {
    String tenant = "t-sample-inspection";
    String writer = bearer("importer", tenant, PROPOSER);
    String reader = bearer("reader", tenant, List.of("objectives.objective.read"));
    var admission = postWithIdempotency("/v1/objective-imports:preview", writer,
        importPreview("sample:checkout-recovery", "w1", 1, 0), "sample-inspect");
    assertEquals(202, admission.statusCode());
    String id = extractString(admission.body(), "receipt_id");
    String path = "/v1/objective-imports/" + id + "/sample-source";
    assertEquals(503, get(path, reader).statusCode());
    assertEquals(403, get(path, bearer("write-only", tenant, List.of("objectives.objective.write"))).statusCode());
    assertEquals(403, get(path, token("scoped", tenant, "w1", List.of("objectives.objective.read"))).statusCode());
    assertEquals(404, get(path, bearer("reader", "other-tenant", List.of("objectives.objective.read"))).statusCode());
    assertEquals(404, get(path, tokenForEnvironment("reader", tenant, "other-environment", "*",
        List.of("objectives.objective.read"))).statusCode());
    assertEquals(200, get("/v1/objective-imports?pageSize=25", reader).statusCode());
    assertThat(get("/v1/objective-imports/" + id, reader).body()).contains("PENDING");
    String capture = "/v1/objective-imports/" + id + "/sample-captures";
    assertEquals(503, postWithIdempotency(capture, writer, "", "capture-key").statusCode());
    assertEquals(403, postWithIdempotency(capture, reader, "", "capture-key").statusCode());
    assertEquals(400, post(capture, writer, "").statusCode());
  }

  @Test
  void syntheticCapturesAreScopedImmutableAndRetrySafe() throws Exception {
    String tenant = "t-synthetic-capture";
    var admission = postWithIdempotency("/v1/objective-imports:preview",
        bearer("importer", tenant, PROPOSER),
        importPreview("sample:checkout-recovery", "w1", 1, 0), "capture-preview");
    String id = extractString(admission.body(), "receipt_id");
    var actor = new DelegationPrincipal("importer", tenant, "env-1", "*", "https://idp.example.test",
        "importer", PROPOSER, List.of(), List.of());
    var request = objectives.getSampleCaptureRequest(actor, id, "capture-key");
    String payload = """
        {"metric":"recovery.time","target":720,"unit":"seconds","window":"stable-30m",
         "measurement_ref":"sample:check","comparison":"AT_MOST","measurement_kind":"RECOVERY_TIME",
         "percentile":null}
        """;
    var definition = ObjectiveImportSampleParser.parse(payload);
    String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    var inspection = new ObjectiveImportSampleSourceClient.Inspection(request.connectionId(), 1,
        "binding", 2, request.targetWorkloadId(), "sample:monitoring", request.externalObjectiveId(),
        "sample-v1", digest, "SYNTHETIC_UNVERIFIED", definition, ObjectiveImportFingerprint.fingerprint(definition));
    var fetched = new ObjectiveImportSampleSourceClient.Fetched(inspection, payload);
    var created = objectives.captureSample(actor, id, "capture-key", request, fetched);
    var replay = objectives.captureSample(actor, id, "capture-key", request, fetched);
    assertThat(created.newlyCreated()).isTrue();
    assertThat(replay.newlyCreated()).isFalse();
    assertThat(replay.captureId()).isEqualTo(created.captureId());
    assertThat(replay.capturedAt()).isEqualTo(created.capturedAt());
    String historyPath = "/v1/objective-imports/" + id + "/sample-captures/" + created.captureId();
    String reader = bearer("reader", tenant, List.of("objectives.objective.read"));
    var captureRead = get(historyPath, reader);
    assertEquals(200, captureRead.statusCode());
    assertThat(captureRead.headers().firstValue("cache-control")).hasValue("no-store");
    var readBody = json.readTree(captureRead.body());
    assertThat(readBody.path("payload").asText()).isEqualTo(payload);
    assertThat(readBody.path("receipt_id").asText()).isEqualTo(id);
    assertThat(readBody.path("capture_id").asText()).isEqualTo(created.captureId());
    assertThat(readBody.path("actor_id").asText()).isEqualTo("importer");
    assertThat(readBody.path("request_digest").asText()).isEqualTo(request.requestDigest());
    assertThat(readBody.path("payload_digest").asText()).isEqualTo(digest);
    assertThat(readBody.path("connection_revision").asLong()).isEqualTo(1);
    assertThat(readBody.path("binding_revision").asLong()).isEqualTo(2);
    assertThat(readBody.path("definition_fingerprint").asText()).isEqualTo(inspection.definitionFingerprint().digest());
    assertThat(readBody.path("definition_fingerprint_version").asText()).isEqualTo("OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1");
    assertThat(readBody.path("verification").asText()).isEqualTo("SYNTHETIC_UNVERIFIED");
    assertEquals(403, get(historyPath, bearer("writer-only", tenant, List.of("objectives.objective.write"))).statusCode());
    assertEquals(403, get(historyPath, token("scoped", tenant, "w1", List.of("objectives.objective.read"))).statusCode());
    assertEquals(404, get(historyPath, bearer("reader", "other-tenant", List.of("objectives.objective.read"))).statusCode());
    assertEquals(404, get(historyPath, tokenForEnvironment("reader", tenant, "other-environment", "*",
        List.of("objectives.objective.read"))).statusCode());
    assertEquals(404, get("/v1/objective-imports/" + java.util.UUID.randomUUID() + "/sample-captures/"
        + created.captureId(), reader).statusCode());
    assertEquals(404, get("/v1/objective-imports/" + id + "/sample-captures/not-a-uuid", reader).statusCode());
    String rehearsalPath = historyPath + "/normalization-rehearsal";
    var rehearsal = get(rehearsalPath + "?targetUnit=minutes", reader);
    assertEquals(200, rehearsal.statusCode());
    assertThat(rehearsal.headers().firstValue("cache-control")).hasValue("no-store");
    var rehearsalBody = json.readTree(rehearsal.body());
    assertThat(rehearsalBody.path("status").asText()).isEqualTo("UNAPPROVED_REHEARSAL");
    assertThat(rehearsalBody.path("verification").asText()).isEqualTo("SYNTHETIC_UNVERIFIED");
    assertThat(rehearsalBody.path("source_definition").path("target").decimalValue())
        .isEqualByComparingTo("720");
    assertThat(rehearsalBody.path("normalized_definition").path("target").decimalValue())
        .isEqualByComparingTo("12");
    assertThat(rehearsalBody.path("normalized_definition").path("unit").asText()).isEqualTo("minutes");
    assertThat(rehearsalBody.path("normalized_definition").path("window").asText()).isEqualTo("stable-30m");
    assertThat(rehearsalBody.path("source_fingerprint").path("digest").asText())
        .isEqualTo(inspection.definitionFingerprint().digest());
    assertThat(rehearsalBody.path("normalized_fingerprint").path("version").asText())
        .isEqualTo("OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1");
    assertThat(rehearsalBody.path("normalized_fingerprint").path("digest").asText())
        .isEqualTo(ObjectiveImportFingerprint.fingerprint(definition.convert("minutes", definition.window(), false)).digest());
    assertThat(rehearsalBody.path("normalized_definition").path("measurement_ref").asText()).isEqualTo("sample:check");
    assertThat(rehearsalBody.path("normalized_definition").path("comparison").asText()).isEqualTo("AT_MOST");
    assertThat(rehearsalBody.path("normalized_definition").path("measurement_kind").asText()).isEqualTo("RECOVERY_TIME");
    assertEquals(400, get(rehearsalPath + "?targetUnit=invalid", reader).statusCode());
    assertEquals(400, get(rehearsalPath + "?targetUnit=", reader).statusCode());
    assertEquals(403, get(rehearsalPath + "?targetUnit=minutes",
        token("scoped", tenant, "w1", List.of("objectives.objective.read"))).statusCode());
    assertEquals(403, get(rehearsalPath + "?targetUnit=minutes",
        bearer("writer-only", tenant, List.of("objectives.objective.write"))).statusCode());
    assertEquals(404, get(rehearsalPath + "?targetUnit=minutes",
        bearer("reader", "other-tenant", List.of("objectives.objective.read"))).statusCode());
    String proposals = historyPath + "/mapping-proposals";
    String proposalBody = """
        {"target_unit":"minutes","allow_fraction_to_percent":false,"reason":"Review this conversion"}
        """;
    String proposer = bearer("importer", tenant, PROPOSER);
    var proposed = postWithIdempotency(proposals, proposer, proposalBody, "mapping-key");
    assertEquals(201, proposed.statusCode());
    assertThat(proposed.headers().firstValue("cache-control")).hasValue("no-store");
    var proposedBody = json.readTree(proposed.body());
    assertThat(proposedBody.path("status").asText()).isEqualTo("UNAPPROVED");
    assertThat(proposedBody.path("verification").asText()).isEqualTo("SYNTHETIC_UNVERIFIED");
    assertThat(proposedBody.path("proposed_by").asText()).isEqualTo("https://uacrf.local/s01|importer");
    assertThat(proposedBody.path("reason").asText()).isEqualTo("Review this conversion");
    assertThat(proposedBody.path("target_unit").asText()).isEqualTo("minutes");
    assertThat(proposedBody.path("source_fingerprint").asText()).isEqualTo(inspection.definitionFingerprint().digest());
    assertThat(proposedBody.path("normalized_fingerprint").asText())
        .isEqualTo(rehearsalBody.path("normalized_fingerprint").path("digest").asText());
    assertThat(proposedBody.path("fingerprint_version").asText()).isEqualTo("OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1");
    String proposalId = proposedBody.path("proposal_id").asText();
    String disabledReview = """
        {"decision":"APPROVE","reason":"review",
         "expected_proposal_digest_version":"SYNTHETIC_MAPPING_PROPOSAL_SHA256_V1","expected_proposal_digest":"%s"}
        """.formatted(proposedBody.path("proposal_digest").asText());
    assertEquals(503, postWithIdempotency(proposals + "/" + proposalId + "/reviews",
        bearer("reviewer", tenant, List.of("objectives.objective.read", "objectives.objective.review")),
        disabledReview, "disabled-review").statusCode());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_objectives.synthetic_objective_mapping_review "
        + "WHERE tenant_id = ?", Integer.class, tenant));
    var replayProposal = postWithIdempotency(proposals, proposer, proposalBody, "mapping-key");
    assertEquals(200, replayProposal.statusCode());
    assertThat(json.readTree(replayProposal.body())).isEqualTo(proposedBody);
    assertEquals(409, postWithIdempotency(proposals, proposer,
        proposalBody.replace("Review this conversion", "Different reason"), "mapping-key").statusCode());
    assertEquals(400, postWithIdempotency(proposals, proposer,
        proposalBody.replace("minutes", "invalid"), "invalid-mapping").statusCode());
    assertEquals(400, postWithIdempotency(proposals, proposer,
        proposalBody.replace("Review this conversion", " "), "empty-reason").statusCode());
    assertEquals(400, post(proposals, proposer, proposalBody).statusCode());
    assertEquals(403, postWithIdempotency(proposals, reader, proposalBody, "read-only").statusCode());
    assertEquals(403, postWithIdempotency(proposals,
        bearer("writer-only", tenant, List.of("objectives.objective.write")), proposalBody, "write-only").statusCode());
    assertEquals(403, postWithIdempotency(proposals,
        token("scoped", tenant, "w1", PROPOSER), proposalBody, "scoped").statusCode());
    String proposalPath = proposals + "/" + proposalId;
    var proposalRead = get(proposalPath, reader);
    assertEquals(200, proposalRead.statusCode());
    assertThat(proposalRead.headers().firstValue("cache-control")).hasValue("no-store");
    assertThat(json.readTree(proposalRead.body())).isEqualTo(proposedBody);
    assertEquals(404, get(proposalPath, bearer("reader", "other-tenant",
        List.of("objectives.objective.read"))).statusCode());
    assertEquals(404, get(proposalPath, tokenForEnvironment("reader", tenant, "other-environment", "*",
        List.of("objectives.objective.read"))).statusCode());
    assertEquals(404, get(proposals + "/" + java.util.UUID.randomUUID(), reader).statusCode());
    assertThatThrownBy(() -> jdbc.update("UPDATE uacrf_objectives.synthetic_objective_mapping_proposal "
        + "SET reason = 'changed' WHERE proposal_id = ?::uuid", proposalId))
        .hasMessageContaining("proposals are immutable");
    assertThatThrownBy(() -> jdbc.update("DELETE FROM uacrf_objectives.synthetic_objective_mapping_proposal "
        + "WHERE proposal_id = ?::uuid", proposalId)).hasMessageContaining("proposals are immutable");
    var proposalRequest = new ObjectivesRequests.SyntheticMappingProposalRequest("minutes", false, "Concurrent review");
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var attempts = java.util.stream.IntStream.range(0, 8)
          .mapToObj(index -> pool.submit(() -> objectives.proposeSyntheticMapping(
              actor, id, created.captureId(), "concurrent-mapping", proposalRequest)))
          .toList();
      var results = new java.util.ArrayList<ObjectivesService.SyntheticMappingAdmission>();
      for (var attempt : attempts) results.add(attempt.get());
      assertThat(results.stream().filter(ObjectivesService.SyntheticMappingAdmission::newlyCreated)).hasSize(1);
      assertThat(results.stream().map(result -> result.proposal().proposalId()).distinct()).hasSize(1);
    }
    var row = jdbc.queryForMap("SELECT payload, payload_digest, definition_fingerprint_version, "
        + "definition_fingerprint, verification, request_digest FROM "
        + "uacrf_objectives.synthetic_objective_source_capture WHERE capture_id = ?::uuid", created.captureId());
    assertThat(row).containsEntry("payload", payload).containsEntry("payload_digest", digest)
        .containsEntry("definition_fingerprint_version", "OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1")
        .containsEntry("definition_fingerprint", inspection.definitionFingerprint().digest())
        .containsEntry("verification", "SYNTHETIC_UNVERIFIED").containsEntry("request_digest", request.requestDigest());
    assertThatThrownBy(() -> jdbc.update("UPDATE uacrf_objectives.synthetic_objective_source_capture "
        + "SET payload = '{}' WHERE capture_id = ?::uuid", created.captureId()))
        .hasMessageContaining("captures are immutable");
    assertThatThrownBy(() -> jdbc.update("DELETE FROM uacrf_objectives.synthetic_objective_source_capture "
        + "WHERE capture_id = ?::uuid", created.captureId())).hasMessageContaining("captures are immutable");
    var stale = new ObjectivesService.SampleSourceRequest(request.connectionId(), request.externalObjectiveId(),
        request.targetWorkloadId(), "a".repeat(64));
    assertThatThrownBy(() -> objectives.captureSample(actor, id, "stale-key", stale, fetched))
        .isInstanceOfSatisfying(ObjectivesException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));
    var other = postWithIdempotency("/v1/objective-imports:preview",
        bearer("importer", tenant, PROPOSER),
        importPreview("sample:checkout-recovery", "w1", 2, 0), "second-preview");
    String otherId = extractString(other.body(), "receipt_id");
    assertEquals(404, get("/v1/objective-imports/" + otherId + "/sample-captures/" + created.captureId(),
        reader).statusCode());
    var otherRequest = objectives.getSampleCaptureRequest(actor, otherId, "capture-key");
    assertThatThrownBy(() -> objectives.captureSample(actor, otherId, "capture-key", otherRequest, fetched))
        .isInstanceOfSatisfying(ObjectivesException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));
    jdbc.update("UPDATE uacrf_objectives.objective_import_preview_command "
        + "SET request_body = (SELECT request_body FROM uacrf_objectives.objective_import_preview_command "
        + "WHERE command_id = ?::uuid), request_digest = (SELECT request_digest FROM "
        + "uacrf_objectives.objective_import_preview_command WHERE command_id = ?::uuid) "
        + "WHERE command_id = ?::uuid", otherId, otherId, id);
    var changedRequest = objectives.getSampleCaptureRequest(actor, id, "capture-key");
    assertThatThrownBy(() -> objectives.captureSample(actor, id, "capture-key", changedRequest, fetched))
        .isInstanceOfSatisfying(ObjectivesException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));
    jdbc.update("UPDATE uacrf_objectives.objective_import_preview_command "
        + "SET request_body = jsonb_set(request_body, '{mapping_revision}', '1'::jsonb), request_digest = ? "
        + "WHERE command_id = ?::uuid", request.requestDigest(), id);
    var otherTenant = new DelegationPrincipal("importer", "other-tenant", "env-1", "*", "https://idp.example.test",
        "importer", PROPOSER, List.of(), List.of());
    assertThatThrownBy(() -> objectives.getSampleCaptureRequest(otherTenant, id, "capture-key"))
        .isInstanceOfSatisfying(ObjectivesException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var attempts = java.util.stream.IntStream.range(0, 8)
          .mapToObj(index -> pool.submit(() -> objectives.captureSample(actor, id, "concurrent-key", request, fetched)))
          .toList();
      var results = new java.util.ArrayList<ObjectivesService.SampleCapture>();
      for (var attempt : attempts) results.add(attempt.get());
      assertThat(results.stream().filter(ObjectivesService.SampleCapture::newlyCreated)).hasSize(1);
      assertThat(results.stream().map(ObjectivesService.SampleCapture::captureId).distinct()).hasSize(1);
    }
    String secondCapture = objectives.captureSample(actor, id, "concurrent-key", request, fetched).captureId();
    String otherProposals = "/v1/objective-imports/" + id + "/sample-captures/" + secondCapture
        + "/mapping-proposals";
    assertEquals(409, postWithIdempotency(otherProposals, proposer, proposalBody, "mapping-key").statusCode());
    assertEquals(404, get(otherProposals + "/" + proposalId, reader).statusCode());
    var proposalFirst = get(proposals + "?pageSize=1", reader);
    assertEquals(200, proposalFirst.statusCode());
    assertThat(proposalFirst.headers().firstValue("cache-control")).hasValue("no-store");
    var proposalFirstBody = json.readTree(proposalFirst.body());
    assertThat(proposalFirstBody.path("items")).hasSize(1);
    assertThat(proposalFirstBody.path("items").get(0).path("status").asText()).isEqualTo("UNAPPROVED");
    assertThat(proposalFirstBody.path("items").get(0).path("verification").asText()).isEqualTo("SYNTHETIC_UNVERIFIED");
    String proposalCursor = proposalFirstBody.path("next_cursor").asText();
    var proposalSecond = get(proposals + "?pageSize=1&cursor=" + proposalCursor, reader);
    assertEquals(200, proposalSecond.statusCode());
    var proposalSecondBody = json.readTree(proposalSecond.body());
    assertThat(proposalSecondBody.path("items")).hasSize(1);
    assertThat(proposalSecondBody.path("items").get(0).path("proposal_id").asText())
        .isNotEqualTo(proposalFirstBody.path("items").get(0).path("proposal_id").asText());
    assertThat(proposalSecondBody.path("next_cursor").isNull()).isTrue();
    assertEquals(400, get(otherProposals + "?cursor=" + proposalCursor, reader).statusCode());
    assertEquals(400, get(proposals + "?cursor=invalid", reader).statusCode());
    assertEquals(400, get(proposals + "?pageSize=101", reader).statusCode());
    assertEquals(403, get(proposals, bearer("writer-only", tenant, List.of("objectives.objective.write"))).statusCode());
    assertEquals(403, get(proposals, token("scoped", tenant, "w1", List.of("objectives.objective.read"))).statusCode());
    assertEquals(404, get(proposals, bearer("reader", "other-tenant", List.of("objectives.objective.read"))).statusCode());
    assertEquals(404, get(proposals, tokenForEnvironment("reader", tenant, "other-environment", "*",
        List.of("objectives.objective.read"))).statusCode());
    var emptyProposals = get(otherProposals, reader);
    assertEquals(200, emptyProposals.statusCode());
    assertThat(json.readTree(emptyProposals.body()).path("items")).isEmpty();
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM uacrf_objectives.synthetic_objective_mapping_proposal "
        + "WHERE tenant_id = ?", Integer.class, tenant));
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM uacrf_objectives.synthetic_objective_source_capture "
        + "WHERE tenant_id = ?", Integer.class, tenant));
    String listing = "/v1/objective-imports/" + id + "/sample-captures";
    var firstPage = get(listing + "?pageSize=1", reader);
    assertEquals(200, firstPage.statusCode());
    assertThat(firstPage.headers().firstValue("cache-control")).hasValue("no-store");
    var firstBody = json.readTree(firstPage.body());
    assertThat(firstBody.path("items")).hasSize(1);
    assertThat(firstBody.path("items").get(0).path("verification").asText()).isEqualTo("SYNTHETIC_UNVERIFIED");
    var secondPage = get(listing + "?pageSize=1&cursor=" + firstBody.path("next_cursor").asText(), reader);
    assertEquals(200, secondPage.statusCode());
    var secondBody = json.readTree(secondPage.body());
    assertThat(secondBody.path("items")).hasSize(1);
    assertThat(secondBody.path("items").get(0).path("capture_id").asText())
        .isNotEqualTo(firstBody.path("items").get(0).path("capture_id").asText());
    assertThat(secondBody.path("next_cursor").isNull()).isTrue();
    assertEquals(400, get(listing + "?cursor=invalid", reader).statusCode());
    assertEquals(400, get(listing + "?pageSize=101", reader).statusCode());
    assertEquals(403, get(listing, bearer("writer-only", tenant, List.of("objectives.objective.write"))).statusCode());
    assertEquals(403, get(listing, token("scoped", tenant, "w1", List.of("objectives.objective.read"))).statusCode());
    assertEquals(404, get(listing, bearer("reader", "other-tenant", List.of("objectives.objective.read"))).statusCode());
    assertEquals(404, get(listing, tokenForEnvironment("reader", tenant, "other-environment", "*",
        List.of("objectives.objective.read"))).statusCode());
    var empty = get("/v1/objective-imports/" + otherId + "/sample-captures", reader);
    assertEquals(200, empty.statusCode());
    assertThat(json.readTree(empty.body()).path("items").isEmpty()).isTrue();
    assertEquals(400, get("/v1/objective-imports/" + otherId + "/sample-captures?cursor="
        + firstBody.path("next_cursor").asText(), reader).statusCode());
    String legacyCursor = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(created.captureId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(400, get(listing + "?cursor=" + legacyCursor, reader).statusCode());
    String encoded = firstBody.path("next_cursor").asText();
    String decoded = new String(java.util.Base64.getUrlDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
    String malformedCursor = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
        (decoded.substring(0, decoded.lastIndexOf(':') + 1) + "not-a-uuid")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(400, get(listing + "?cursor=" + malformedCursor, reader).statusCode());
    assertThat(get("/v1/objective-imports/" + id, bearer("importer", tenant, PROPOSER)).body())
        .contains("PENDING");
  }

  @Test
  void sampleInspectionRejectsCorruptRetainedRequests() throws Exception {
    String tenant = "t-sample-integrity";
    var admission = postWithIdempotency("/v1/objective-imports:preview",
        bearer("importer", tenant, PROPOSER),
        importPreview("sample:checkout-recovery", "w1", 1, 0), "integrity");
    assertEquals(202, admission.statusCode());
    String id = extractString(admission.body(), "receipt_id");
    DelegationPrincipal reader = new DelegationPrincipal(
        "reader", tenant, "env-1", "*", "https://idp.example.test", "reader",
        List.of("objectives.objective.read"), List.of(), List.of());
    assertThat(objectives.getSampleSourceRequest(reader, id).targetWorkloadId()).isEqualTo("w1");
    String original = jdbc.queryForObject(
        "SELECT request_body::text FROM uacrf_objectives.objective_import_preview_command "
            + "WHERE command_id = ?::uuid", String.class, id);
    for (String expression : List.of(
        "jsonb_set(request_body, '{target_workload_id}', '\"other\"'::jsonb)",
        "jsonb_set(request_body, '{external_objective_id}', '\"sample:other\"'::jsonb)",
        "request_body - 'mapping_revision'",
        "jsonb_set(request_body, '{mapping_revision}', '\"1\"'::jsonb)")) {
      jdbc.update("UPDATE uacrf_objectives.objective_import_preview_command SET request_body = "
          + expression + " WHERE command_id = ?::uuid", id);
      assertThatThrownBy(() -> objectives.getSampleSourceRequest(reader, id))
          .isInstanceOf(IllegalStateException.class);
      jdbc.update("UPDATE uacrf_objectives.objective_import_preview_command "
          + "SET request_body = ?::jsonb WHERE command_id = ?::uuid", original, id);
    }
  }

  @Test
  void objectiveImportPreviewRejectsIdempotencyReuseAndMissingKeys() throws Exception {
    String propose = bearer("importer", "t-preview-conflict", PROPOSER);
    String body = importPreview("slo-8", "w-preview", 1, 0);
    assertEquals(
        403,
        postWithIdempotency(
                "/v1/objective-imports:preview",
                token("scoped-writer", "t-preview-conflict", "w-preview", PROPOSER),
                body,
                "scoped-preview-key")
            .statusCode());
    assertEquals(
        202,
        postWithIdempotency(
                "/v1/objective-imports:preview", propose, body, "preview-key-2")
            .statusCode());
    HttpResponse<String> conflict =
        postWithIdempotency(
            "/v1/objective-imports:preview",
            propose,
            importPreview("slo-9", "w-preview", 1, 0),
            "preview-key-2");
    assertEquals(409, conflict.statusCode());
    assertThat(conflict.body()).contains("IDEMPOTENCY_CONFLICT");
    assertEquals(400, post("/v1/objective-imports:preview", propose, body).statusCode());
  }

  @Test
  void idempotentSubmissionSerializesConcurrentRequestsAndReplaysCurrentState() throws Exception {
    String propose = bearer("creator", "t-submit-idempotent", PROPOSER);
    String created = post("/v1/objectives", propose,
        objective("submit-one", "w-submit", "latency.p95", "LTE", "250", "ms")).body();
    String digest = extractString(created, "content_digest");
    String body = "{\"expected_revision\":1,\"expected_content_digest\":\"" + digest + "\"}";
    String path = "/v1/objectives/submit-one:idempotent-submit";
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Authorization", propose).header("Content-Type", "application/json")
        .header("Idempotency-Key", "submit-key").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    var first = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    var second = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, first.get().statusCode());
    assertEquals(200, second.get().statusCode());
    assertEquals(1, jdbc.queryForObject(
        "SELECT count(*) FROM uacrf_objectives.outbox_event WHERE tenant_id = 't-submit-idempotent' AND event_type = 'objectives.objective.submitted'",
        Integer.class));
    assertThat(postWithIdempotency(path, propose, body.replace(":1,", ":2,"), "submit-key").body())
        .contains("IDEMPOTENCY_CONFLICT");
    assertEquals(403, postWithIdempotency(path,
        token("creator", "t-submit-idempotent", "w-submit", PROPOSER), body, "submit-key").statusCode());
    assertEquals(200, post("/v1/objectives/submit-one:decide",
        bearer("reviewer", "t-submit-idempotent", DECIDER), decision("APPROVE", 2)).statusCode());
    assertThat(postWithIdempotency(path, propose, body, "submit-key").body())
        .contains("\"state\":\"APPROVED\"").contains("\"revision\":3");
  }

  @Test
  void idempotentSubmissionRejectsMismatchAndRollsBackLedger() throws Exception {
    String propose = bearer("creator", "t-submit-invalid", PROPOSER);
    String created = post("/v1/objectives", propose,
        objective("submit-two", "w-submit-two", "latency.p95", "LTE", "250", "ms")).body();
    String digest = extractString(created, "content_digest");
    String body = "{\"expected_revision\":1,\"expected_content_digest\":\"" + digest + "\"}";
    String path = "/v1/objectives/submit-two:idempotent-submit";
    assertEquals(400, post(path, propose, body).statusCode());
    assertThat(postWithIdempotency(path, propose, body.replace(digest, "a".repeat(64)), "retry-key").body())
        .contains("CONTENT_MISMATCH");
    assertThat(postWithIdempotency(path, propose, body.replace(":1,", ":2,"), "retry-key").body())
        .contains("REVISION_MISMATCH");
    String admin = bearer("admin", "t-submit-invalid", List.of("objectives.constraint.write"));
    assertEquals(200, put("/v1/organization-constraints/latency.p95", admin,
        "{\"unit\":\"ms\",\"max_target\":\"100\"}").statusCode());
    assertThat(postWithIdempotency(path, propose, body, "retry-key").body()).contains("CONSTRAINT_VIOLATION");
    assertEquals(0, jdbc.queryForObject(
        "SELECT count(*) FROM uacrf_objectives.objective_submission WHERE tenant_id = 't-submit-invalid'",
        Integer.class));
    assertEquals(200, put("/v1/organization-constraints/latency.p95", admin,
        "{\"unit\":\"ms\",\"max_target\":\"300\"}").statusCode());
    assertEquals(200, postWithIdempotency(path, propose, body, "retry-key").statusCode());
    assertThat(postWithIdempotency(path, propose, body, "different-key").body()).contains("REVISION_MISMATCH");
    assertEquals(1, jdbc.queryForObject(
        "SELECT count(*) FROM uacrf_objectives.objective_submission WHERE tenant_id = 't-submit-invalid'",
        Integer.class));
  }

  @Test
  void idempotentActivationBindsContentSerializesAndReplaysAfterSupersession() throws Exception {
    String creator = bearer("creator", "t-activation-retry", PROPOSER);
    String reviewer = bearer("reviewer", "t-activation-retry", DECIDER);
    String activator = bearer("activator", "t-activation-retry", ACTIVATOR);
    String created = post("/v1/objectives", creator,
        objective("activation-one", "w2", "latency.p95", "LTE", "250", "ms")).body();
    String digest = extractString(created, "content_digest");
    post("/v1/objectives/activation-one:submit", creator, "{\"expected_revision\":1}");
    post("/v1/objectives/activation-one:decide", reviewer, decision("APPROVE", 2));
    String path = "/v1/objectives/activation-one:idempotent-activate";
    String body = "{\"expected_revision\":3,\"expected_content_digest\":\"" + digest + "\"}";
    assertEquals(400, post(path, activator, body).statusCode());
    assertEquals(403, postWithIdempotency(path, token("activator", "t-activation-retry", "w2", ACTIVATOR), body, "k").statusCode());
    assertEquals(403, postWithIdempotency(path, creator, body, "k").statusCode());
    assertThat(postWithIdempotency(path, activator, body.replace(digest, "b".repeat(64)), "k").body())
        .contains("CONTENT_MISMATCH");
    assertThat(postWithIdempotency(path, activator, body.replace(":3,", ":2,"), "k").body())
        .contains("REVISION_MISMATCH");
    String admin = bearer("admin", "t-activation-retry", List.of("objectives.constraint.write"));
    assertEquals(200, put("/v1/organization-constraints/latency.p95", admin,
        "{\"unit\":\"ms\",\"max_target\":\"100\"}").statusCode());
    assertThat(postWithIdempotency(path, activator, body, "k").body()).contains("CONSTRAINT_VIOLATION");
    assertEquals(200, put("/v1/organization-constraints/latency.p95", admin,
        "{\"unit\":\"ms\",\"max_target\":\"300\"}").statusCode());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_objectives.objective_activation WHERE tenant_id = 't-activation-retry'", Integer.class));
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Authorization", activator).header("Content-Type", "application/json")
        .header("Idempotency-Key", "k").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    var first = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    var second = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, first.get().statusCode());
    assertEquals(200, second.get().statusCode());
    assertThat(first.get().body()).contains("\"state\":\"ACTIVE\"").contains("\"activated_by\":\"https://uacrf.local/s01|activator\"");
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_objectives.outbox_event WHERE tenant_id = 't-activation-retry' AND event_type = 'objectives.objective.activated'", Integer.class));
    assertThat(postWithIdempotency(path, activator, body.replace(digest, "b".repeat(64)), "k").body()).contains("IDEMPOTENCY_CONFLICT");
    assertThat(postWithIdempotency("/v1/objectives/other:idempotent-activate", activator, body, "k").body()).contains("IDEMPOTENCY_CONFLICT");
    approveAndActivate("activation-two", "200", creator, reviewer, activator);
    assertThat(postWithIdempotency(path, activator, body, "k").body()).contains("\"state\":\"SUPERSEDED\"");
  }

  @Test
  void idempotentActivationRollsBackReservationOnOwnerDenial() throws Exception {
    String creator = bearer("creator", "t-activation-owner", PROPOSER);
    String reviewer = bearer("reviewer", "t-activation-owner", DECIDER);
    String activator = bearer("activator", "t-activation-owner", ACTIVATOR);
    approveAndActivate("old-owner", "250", creator, reviewer, activator);
    String created = post("/v1/objectives", creator,
        objective("new-owner", "w2", "latency.p95", "LTE", "200", "ms")).body();
    post("/v1/objectives/new-owner:submit", creator, "{\"expected_revision\":1}");
    post("/v1/objectives/new-owner:decide", reviewer, decision("APPROVE", 2));
    doThrow(new ObjectivesException(HttpStatus.CONFLICT, "WORKLOAD_NOT_ELIGIBLE", "Denied"))
        .when(workloadAuthority).check(any(Jwt.class), eq("w2"),
            eq(ObjectiveWorkloadAuthorityClient.ACTIVATION_PURPOSE));
    String body = "{\"expected_revision\":3,\"expected_content_digest\":\""
        + extractString(created, "content_digest") + "\"}";
    assertThat(postWithIdempotency("/v1/objectives/new-owner:idempotent-activate", activator, body, "k").body())
        .contains("WORKLOAD_NOT_ELIGIBLE");
    assertThat(get("/v1/objectives/old-owner", activator).body()).contains("\"state\":\"ACTIVE\"");
    assertThat(get("/v1/objectives/new-owner", activator).body()).contains("\"state\":\"APPROVED\"");
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_objectives.objective_activation WHERE tenant_id = 't-activation-owner'", Integer.class));
  }

  @Test
  void idempotentReviewSerializesAndReplaysAfterActivation() throws Exception {
    String creator = bearer("creator", "t-review-replay", PROPOSER);
    String reviewer = bearer("reviewer", "t-review-replay", DECIDER);
    String created = post("/v1/objectives", creator,
        objective("review-one", "w-review", "latency.p95", "LTE", "250", "ms")).body();
    String digest = extractString(created, "content_digest");
    assertEquals(200, post("/v1/objectives/review-one:submit", creator, "{\"expected_revision\":1}").statusCode());
    String path = "/v1/objectives/review-one:idempotent-decide";
    String body = "{\"decision\":\"APPROVE\",\"reason\":\"reviewed exactly\",\"expected_revision\":2,"
        + "\"expected_content_digest\":\"" + digest + "\"}";
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Authorization", reviewer).header("Content-Type", "application/json")
        .header("Idempotency-Key", "review-key").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    var first = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    var second = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, first.get().statusCode());
    assertEquals(200, second.get().statusCode());
    assertThat(first.get().body()).contains("\"state\":\"APPROVED\"")
        .contains("\"decided_by\":\"https://uacrf.local/s01|reviewer\"")
        .contains("\"decision_reason\":\"reviewed exactly\"");
    assertEquals(1, jdbc.queryForObject(
        "SELECT count(*) FROM uacrf_objectives.outbox_event WHERE tenant_id = 't-review-replay' AND event_type = 'objectives.objective.decided'",
        Integer.class));
    assertThat(jdbc.queryForMap(
        "SELECT revision, content_digest, decision, reason, decided_by FROM uacrf_objectives.objective_decision WHERE tenant_id = 't-review-replay'"))
        .containsEntry("revision", 3L).containsEntry("content_digest", digest)
        .containsEntry("decision", "APPROVE").containsEntry("reason", "reviewed exactly")
        .containsEntry("decided_by", "https://uacrf.local/s01|reviewer");
    assertThat(postWithIdempotency(path, reviewer, body.replace("reviewed exactly", "changed"), "review-key").body())
        .contains("IDEMPOTENCY_CONFLICT");
    assertThat(postWithIdempotency(path, reviewer, body.replace("APPROVE", "REJECT"), "review-key").body())
        .contains("IDEMPOTENCY_CONFLICT");
    assertThat(postWithIdempotency(path, reviewer, body.replace(digest, "a".repeat(64)), "review-key").body())
        .contains("IDEMPOTENCY_CONFLICT");
    assertThat(postWithIdempotency(path, reviewer, body.replace(":2,", ":1,"), "review-key").body())
        .contains("IDEMPOTENCY_CONFLICT");
    assertThat(postWithIdempotency("/v1/objectives/other:idempotent-decide", reviewer, body, "review-key").body())
        .contains("IDEMPOTENCY_CONFLICT");
    assertEquals(200, post("/v1/objectives/review-one:activate",
        bearer("activator", "t-review-replay", ACTIVATOR), "{\"expected_revision\":3}").statusCode());
    assertThat(postWithIdempotency(path, reviewer, body, "review-key").body())
        .contains("\"state\":\"ACTIVE\"").contains("\"decision_reason\":\"reviewed exactly\"");
  }

  @Test
  void idempotentReviewRejectsInvalidRequestsAndRollsBackReservation() throws Exception {
    String creator = bearer("creator", "t-review-invalid", PROPOSER);
    String reviewer = bearer("reviewer", "t-review-invalid", DECIDER);
    String created = post("/v1/objectives", creator,
        objective("review-two", "w-review", "latency.p95", "LTE", "250", "ms")).body();
    String digest = extractString(created, "content_digest");
    assertEquals(200, post("/v1/objectives/review-two:submit", creator, "{\"expected_revision\":1}").statusCode());
    String path = "/v1/objectives/review-two:idempotent-decide";
    String body = "{\"decision\":\"REJECT\",\"reason\":\"not acceptable\",\"expected_revision\":2,"
        + "\"expected_content_digest\":\"" + digest + "\"}";
    assertEquals(400, post(path, reviewer, body).statusCode());
    assertEquals(403, postWithIdempotency(path, token("reviewer", "t-review-invalid", "w-review", DECIDER),
        body, "review-key").statusCode());
    assertEquals(403, postWithIdempotency(path, creator, body, "review-key").statusCode());
    assertThat(postWithIdempotency(path, bearer("creator", "t-review-invalid", DECIDER), body, "self-key").body())
        .contains("REVIEWER_NOT_INDEPENDENT");
    assertThat(postWithIdempotency(path, reviewer, body.replace(":2,", ":1,"), "review-key").body())
        .contains("REVISION_MISMATCH");
    assertThat(postWithIdempotency(path, reviewer, body.replace(digest, "a".repeat(64)), "review-key").body())
        .contains("CONTENT_MISMATCH");
    assertEquals(0, jdbc.queryForObject(
        "SELECT count(*) FROM uacrf_objectives.objective_review WHERE tenant_id = 't-review-invalid'",
        Integer.class));
    assertThat(postWithIdempotency(path, reviewer, body, "review-key").body())
        .contains("\"state\":\"REJECTED\"").contains("\"decision_reason\":\"not acceptable\"");
    assertThat(postWithIdempotency(path, reviewer, body, "review-key").body()).contains("\"state\":\"REJECTED\"");
    assertThat(jdbc.queryForMap("SELECT decision, reason FROM uacrf_objectives.objective_decision WHERE tenant_id = 't-review-invalid'"))
        .containsEntry("decision", "REJECT").containsEntry("reason", "not acceptable");
  }

  @Test
  void decisionRecordFailureRollsBackReviewAndOutbox() throws Exception {
    String creator = bearer("creator", "t-review-record-failure", PROPOSER);
    String reviewer = bearer("reviewer", "t-review-record-failure", DECIDER);
    String created = post("/v1/objectives", creator,
        objective("review-failure", "w-review", "latency.p95", "LTE", "250", "ms")).body();
    String digest = extractString(created, "content_digest");
    assertEquals(200, post("/v1/objectives/review-failure:submit", creator, "{\"expected_revision\":1}").statusCode());
    jdbc.update("INSERT INTO uacrf_objectives.objective_decision "
            + "(tenant_id, environment_id, objective_id, revision, content_digest, decision, reason, decided_by) "
            + "VALUES ('t-review-record-failure', 'env-1', 'review-failure', 3, ?, 'REJECT', 'existing', 'existing')",
        digest);
    String body = "{\"decision\":\"APPROVE\",\"reason\":\"reviewed\",\"expected_revision\":2,"
        + "\"expected_content_digest\":\"" + digest + "\"}";
    HttpResponse<String> failed = postWithIdempotency("/v1/objectives/review-failure:idempotent-decide",
        reviewer, body, "review-key");
    assertEquals(409, failed.statusCode());
    assertThat(failed.body()).contains("OBJECTIVE_CONFLICT");
    assertThat(get("/v1/objectives/review-failure", creator).body())
        .contains("\"state\":\"SUBMITTED\"").contains("\"revision\":2");
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_objectives.objective_review "
        + "WHERE tenant_id = 't-review-record-failure'", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_objectives.outbox_event "
        + "WHERE tenant_id = 't-review-record-failure' AND event_type = 'objectives.objective.decided'", Integer.class));
  }

  @Test
  void decisionRecordsPageWithinGlobalAuthorizedContextAndDisclosePossibleGaps() throws Exception {
    String tenant = "t-decision-pages";
    String creator = bearer("creator", tenant, PROPOSER);
    String reviewer = bearer("reviewer", tenant, DECIDER);
    String created = post("/v1/objectives", creator,
        objective("page-objective", "w-page", "latency.p95", "LTE", "250", "ms")).body();
    String digest = extractString(created, "content_digest");
    assertEquals(200, post("/v1/objectives/page-objective:submit", creator, "{\"expected_revision\":1}").statusCode());
    assertEquals(200, post("/v1/objectives/page-objective:decide", reviewer, decision("REJECT", 2)).statusCode());
    for (int revision : List.of(5, 7)) {
      jdbc.update("INSERT INTO uacrf_objectives.objective_decision "
              + "(tenant_id, environment_id, objective_id, revision, content_digest, decision, reason, decided_by) "
              + "VALUES (?, 'env-1', 'page-objective', ?, ?, 'APPROVE', ?, 'fixture')",
          tenant, revision, digest, "reason-" + revision);
    }
    String path = "/v1/objectives/page-objective/decision-records";
    var first = json.readTree(get(path + "?pageSize=2", creator).body());
    assertEquals(2, first.path("items").size());
    assertEquals(7, first.path("items").get(0).path("revision").asInt());
    assertEquals("reason-5", first.path("items").get(1).path("reason").asText());
    assertThat(first.path("history_may_be_incomplete").asBoolean()).isTrue();
    String cursor = first.path("next_cursor").asText();
    var second = json.readTree(get(path + "?pageSize=2&cursor=" + cursor, creator).body());
    assertEquals(1, second.path("items").size());
    assertEquals(3, second.path("items").get(0).path("revision").asInt());
    assertThat(second.path("next_cursor").isNull()).isTrue();
    assertEquals(400, get(path + "?cursor=bad!", creator).statusCode());
    assertEquals(400, get(path + "?pageSize=0", creator).statusCode());
    assertEquals(403, get(path, token("reader", tenant, "w-page", List.of("objectives.objective.read"))).statusCode());
    assertEquals(403, get(path, bearer("writer", tenant, List.of("objectives.objective.write"))).statusCode());
    assertEquals(404, get(path, bearer("reader", "t-other-decision-pages", List.of("objectives.objective.read"))).statusCode());
  }

  @Test
  void localObjectiveCreationSerializesReplayAndReturnsCurrentState() throws Exception {
    String path = "/v1/objectives:local-idempotent-create";
    String propose = bearer("creator", "t-local-idempotent", PROPOSER);
    String body = objective("local-one", "w-local-one", "latency.p95", "LTE", "250.000000", "ms");
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Authorization", propose).header("Content-Type", "application/json")
        .header("Idempotency-Key", "local-key")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    var first = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    var second = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> one = first.get();
    HttpResponse<String> two = second.get();
    assertThat(List.of(one.statusCode(), two.statusCode())).containsExactlyInAnyOrder(201, 200);
    assertThat(one.body()).contains("\"id\":\"local-one\"").contains("\"state\":\"DRAFT\"");
    assertThat(two.body()).contains("\"id\":\"local-one\"").contains("\"state\":\"DRAFT\"");
    assertEquals(1, jdbc.queryForObject(
        "SELECT count(*) FROM uacrf_objectives.objective WHERE tenant_id = 't-local-idempotent'", Integer.class));
    assertEquals(1, jdbc.queryForObject(
        "SELECT count(*) FROM uacrf_objectives.outbox_event WHERE tenant_id = 't-local-idempotent'", Integer.class));
    HttpResponse<String> conflict = postWithIdempotency(path, propose,
        body.replace("Objective local-one", "Changed title"), "local-key");
    assertEquals(409, conflict.statusCode());
    assertThat(conflict.body()).contains("IDEMPOTENCY_CONFLICT");
    assertEquals(200, postWithIdempotency(path, propose,
        body.replace("250.000000", "250"), "local-key").statusCode());
    assertEquals(200, post("/v1/objectives/local-one:submit", propose, "{\"expected_revision\":1}").statusCode());
    HttpResponse<String> later = postWithIdempotency(path, propose, body, "local-key");
    assertEquals(200, later.statusCode());
    assertThat(later.body()).contains("\"state\":\"SUBMITTED\"").contains("\"revision\":2");
    assertEquals(403, postWithIdempotency(path,
        token("creator", "t-local-idempotent", "w-local-one", PROPOSER), body, "local-key").statusCode());
    assertEquals(201, postWithIdempotency(path,
        bearer("creator", "t-local-other", PROPOSER), body, "local-key").statusCode());
  }

  @Test
  void localObjectiveCreationRejectsUnrepresentableTargetsAndRollsBackOnOwnerFailure() throws Exception {
    String path = "/v1/objectives:local-idempotent-create";
    String propose = bearer("creator", "t-local-invalid", PROPOSER);
    String body = objective("local-invalid", "w-idempotent-denied", "latency.p95", "LTE", "1", "ms");
    assertEquals(400, post(path, propose, body).statusCode());
    assertEquals(400, postWithIdempotency(path, propose,
        body.replace("\"target\":1", "\"target\":0.0000001"), "precision-key").statusCode());
    assertEquals(400, postWithIdempotency(path, propose,
        body.replace("\"target\":1", "\"target\":1000000000000000000"), "range-key").statusCode());
    assertEquals(400, postWithIdempotency(path, propose,
        body.replace("\"origin\":\"LOCAL\"", "\"origin\":\"IMPORTED\""), "import-key").statusCode());
    assertEquals(400, postWithIdempotency(path, propose,
        body.replace("\"origin\":\"LOCAL\"", "\"origin\":\"LOCAL\",\"source_ref\":\"source\""), "source-key").statusCode());
    doThrow(new ObjectivesException(HttpStatus.CONFLICT, "WORKLOAD_NOT_ELIGIBLE", "Unavailable."))
        .when(workloadAuthority)
        .check(any(Jwt.class), eq("w-idempotent-denied"), eq(ObjectiveWorkloadAuthorityClient.PROPOSAL_PURPOSE));
    assertEquals(409, postWithIdempotency(path, propose, body, "rollback-key").statusCode());
    assertEquals(0, jdbc.queryForObject(
        "SELECT count(*) FROM uacrf_objectives.local_objective_creation WHERE tenant_id = 't-local-invalid'",
        Integer.class));
    assertEquals(201, postWithIdempotency(path, propose,
        body.replace("w-idempotent-denied", "w-idempotent-valid"), "rollback-key").statusCode());
    assertEquals(201, postWithIdempotency(path, propose,
        objective("maximum", "w-valid", "latency.p95", "LTE", "999999999999999999.999999", "ms"),
        "maximum-key").statusCode());
    assertEquals(400, postWithIdempotency(path, propose,
        objective("outside", "w-valid", "latency.p95", "LTE", "-1000000000000000000", "ms"),
        "outside-key").statusCode());
  }

  @Test
  void decisionHistoryShowsOnlyRetainedBoundedEventsWithinAuthorizedContext() throws Exception {
    String tenant = "t-history";
    String creator = bearer("creator", tenant, PROPOSER);
    String reviewer = bearer("reviewer", tenant, DECIDER);
    String path = "/v1/objectives/history-one/decision-history";
    assertEquals(201, post("/v1/objectives", creator,
        objective("history-one", "w-history", "m.history", "GTE", "1", "u")).statusCode());
    assertThat(get(path, creator).body()).contains("\"items\":[]", "\"more_recorded\":false");
    assertEquals(200, post("/v1/objectives/history-one:submit", creator, "{\"expected_revision\":1}").statusCode());
    assertEquals(200, post("/v1/objectives/history-one:decide", reviewer, decision("REJECT", 2)).statusCode());
    String history = get(path, creator).body();
    assertThat(history).contains("\"objective_id\":\"history-one\"", "\"revision\":3",
        "\"state\":\"REJECTED\"", "\"actor_id\":\"https://uacrf.local/s01|reviewer\"", "\"recorded_at\":")
        .doesNotContain("reviewed", "decision_reason");
    assertEquals(403, get(path, token("reader", tenant, "w-history", List.of("objectives.objective.read"))).statusCode());
    assertEquals(403, get(path, bearer("writer", tenant, List.of("objectives.objective.write"))).statusCode());
    assertEquals(404, get(path, bearer("reader", "t-other-history", List.of("objectives.objective.read"))).statusCode());
    assertEquals(404, get("/v1/objectives/missing/decision-history", creator).statusCode());
    jdbc.update("INSERT INTO uacrf_objectives.outbox_event "
            + "(event_id, tenant_id, environment_id, aggregate_type, aggregate_id, aggregate_sequence, "
            + "event_type, payload, payload_digest) VALUES (?, ?, 'other-env', 'OBJECTIVE', 'history-one', 99, "
            + "'objectives.objective.decided', '{\"actor_id\":\"other-environment\"}'::jsonb, ?)",
        java.util.UUID.randomUUID(), tenant, "0".repeat(64));
    assertThat(get(path, creator).body()).doesNotContain("other-environment");
    for (int revision = 4; revision <= 28; revision++) {
      jdbc.update("INSERT INTO uacrf_objectives.outbox_event "
              + "(event_id, tenant_id, environment_id, aggregate_type, aggregate_id, aggregate_sequence, "
              + "event_type, payload, payload_digest) VALUES (?, ?, 'env-1', 'OBJECTIVE', 'history-one', ?, "
              + "'objectives.objective.decided', '{}'::jsonb, ?)",
          java.util.UUID.randomUUID(), tenant, revision, "0".repeat(64));
    }
    var bounded = json.readTree(get(path, creator).body());
    assertEquals(25, bounded.path("items").size());
    assertEquals(28, bounded.path("items").get(0).path("revision").asInt());
    assertEquals(4, bounded.path("items").get(24).path("revision").asInt());
    assertThat(bounded.path("items").get(0).path("state").isNull()).isTrue();
    assertThat(bounded.path("more_recorded").asBoolean()).isTrue();
    jdbc.update("DELETE FROM uacrf_objectives.outbox_event WHERE tenant_id = ? "
        + "AND event_type = 'objectives.objective.decided'", tenant);
    assertThat(get(path, creator).body()).contains("\"items\":[]", "\"more_recorded\":false");
  }

  @Test
  void enforcesCapabilitiesScopeTenantIsolationAndPaging() throws Exception {
    String propose = bearer("proposer", "t-x", PROPOSER);
    String other = bearer("proposer", "t-y", PROPOSER);
    assertEquals(201, post("/v1/objectives", propose, objective("x1", "w5", "m.one", "GTE", "1", "u")).statusCode());
    assertEquals(201, post("/v1/objectives", propose, objective("x2", "w5", "m.two", "GTE", "1", "u")).statusCode());
    assertEquals(404, get("/v1/objectives/x1", other).statusCode());
    assertEquals(403, post("/v1/objectives", bearer("reader", "t-x", List.of("objectives.objective.read")), objective("x3", "w5", "m.three", "GTE", "1", "u")).statusCode());
    assertEquals(403, post("/v1/objectives", token("proposer", "t-x", "svc-1", PROPOSER), objective("x4", "w5", "m.four", "GTE", "1", "u")).statusCode());
    assertEquals(409, post("/v1/objectives", propose, objective("x1", "w5", "m.one", "GTE", "1", "u")).statusCode());
    HttpResponse<String> page = get("/v1/objectives?pageSize=1&workloadId=w5", propose);
    assertThat(page.body()).contains("\"id\":\"x1\"").doesNotContain("\"id\":\"x2\"").contains("next_cursor\":\"");
    assertEquals(400, get("/v1/objectives?pageSize=500", propose).statusCode());
  }

  @Test
  void ordinaryServiceScopesCannotAuthorizeWorkloadReads() throws Exception {
    String tenant = "t-objective-read-scope";
    String global = bearer("proposer", tenant, PROPOSER);
    String scoped = token("reader", tenant, "workload-a", List.of("objectives.objective.read"));
    assertEquals(201, post("/v1/objectives", global,
        objective("a", "workload-a", "m.one", "GTE", "1", "u")).statusCode());
    assertEquals(201, post("/v1/objectives", global,
        objective("b", "workload-b", "m.two", "GTE", "1", "u")).statusCode());
    assertEquals(201, post("/v1/objectives", global,
        objective("c", "workload-a", "m.three", "GTE", "1", "u")).statusCode());

    assertEquals(403, get("/v1/objectives", scoped).statusCode());
    assertEquals(403, get("/v1/objectives?workloadId=workload-a", scoped).statusCode());
    assertEquals(403, get("/v1/objectives/a", scoped).statusCode());
    assertEquals(404, post("/internal/v1/workloads/workload-a/objectives:receipt", scoped,
        "{\"purpose\":\"topology.graph.subgraph-selection\"}").statusCode());
    String exchanged = workloadReceiptToken(tenant, "workload-a", "agent_workload",
        "topology.graph.subgraph-selection");
    assertEquals(200, post("/internal/v1/workloads/workload-a/objectives:receipt", exchanged,
        "{\"purpose\":\"topology.graph.subgraph-selection\"}").statusCode());
    assertEquals(404, post("/internal/v1/workloads/workload-b/objectives:receipt", exchanged,
        "{\"purpose\":\"topology.graph.subgraph-selection\"}").statusCode());
    assertEquals(404, post("/internal/v1/workloads/workload-a/objectives:receipt", exchanged,
        "{\"purpose\":\"topology.graph.difference-classification\"}").statusCode());
    String wrongType = workloadReceiptToken(tenant, "workload-a", "service",
        "topology.graph.subgraph-selection");
    assertEquals(404, post("/internal/v1/workloads/workload-a/objectives:receipt", wrongType,
        "{\"purpose\":\"topology.graph.subgraph-selection\"}").statusCode());
    assertEquals(403, get("/v1/objectives?workloadId=workload-a", exchanged).statusCode());

    HttpResponse<String> first = get("/v1/objectives?pageSize=1&workloadId=workload-a", global);
    assertEquals(200, first.statusCode());
    assertThat(first.body()).contains("\"id\":\"a\"").doesNotContain("\"id\":\"b\"", "\"id\":\"c\"");
    String cursor = json.readTree(first.body()).path("next_cursor").asString();
    HttpResponse<String> second = get("/v1/objectives?pageSize=1&workloadId=workload-a&cursor=" + cursor, global);
    assertEquals(200, second.statusCode());
    assertThat(second.body()).contains("\"id\":\"c\"").doesNotContain("\"id\":\"a\"", "\"id\":\"b\"");
    assertThat(json.readTree(second.body()).path("next_cursor").isNull()).isTrue();
    assertThat(get("/v1/objectives", global).body())
        .contains("\"id\":\"a\"", "\"id\":\"b\"", "\"id\":\"c\"");
  }

  private void approveAndActivate(String id, String target, String propose, String decide, String activate) throws Exception {
    assertEquals(201, post("/v1/objectives", propose, objective(id, "w2", "latency.p95", "LTE", target, "ms")).statusCode());
    post("/v1/objectives/" + id + ":submit", propose, "{\"expected_revision\":1}");
    post("/v1/objectives/" + id + ":decide", decide, decision("APPROVE", 2));
    assertEquals(200, post("/v1/objectives/" + id + ":activate", activate, "{\"expected_revision\":3}").statusCode());
  }

  private static String digest(String body) {
    int at = body.indexOf("set_digest");
    return body.substring(at);
  }

  private static String objective(String id, String workload, String metric, String comparator, String target, String unit) {
    return "{\"id\":\"%s\",\"workload_id\":\"%s\",\"title\":\"Objective %s\",\"metric\":\"%s\",\"comparator\":\"%s\",\"target\":%s,\"unit\":\"%s\",\"window_seconds\":3600,\"origin\":\"LOCAL\"}"
        .formatted(id, workload, id, metric, comparator, target, unit);
  }

  private static String decision(String decision, long revision) {
    return "{\"decision\":\"%s\",\"reason\":\"reviewed\",\"expected_revision\":%d}".formatted(decision, revision);
  }

  private static String importPreview(
      String externalId, String workloadId, long mappingRevision, long localRevision) {
    return "{\"connection_id\":\"connection-1\",\"external_objective_id\":\"%s\","
        .formatted(externalId)
        + "\"target_workload_id\":\"%s\",\"mapping_revision\":%d,\"expected_local_revision\":%d}"
            .formatted(workloadId, mappingRevision, localRevision);
  }

  private static String extractString(String body, String property) {
    String marker = "\"" + property + "\":\"";
    int start = body.indexOf(marker);
    if (start < 0) {
      throw new AssertionError("Missing response property " + property);
    }
    start += marker.length();
    int end = body.indexOf('"', start);
    if (end < 0) {
      throw new AssertionError("Malformed response property " + property);
    }
    return body.substring(start, end);
  }

  private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
    return send(path, bearer, "POST", body);
  }

  private HttpResponse<String> postWithIdempotency(
      String path, String bearer, String body, String idempotencyKey) throws Exception {
    return send(path, bearer, "POST", body, idempotencyKey);
  }

  private HttpResponse<String> put(String path, String bearer, String body) throws Exception {
    return send(path, bearer, "PUT", body);
  }

  private HttpResponse<String> send(String path, String bearer, String method, String body) throws Exception {
    return send(path, bearer, method, body, null);
  }

  private HttpResponse<String> send(
      String path, String bearer, String method, String body, String idempotencyKey) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", bearer)
            .header("Content-Type", "application/json");
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    return http.send(
        request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
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
    return tokenForEnvironment(user, tenant, "env-1", scope, capabilities);
  }

  private static String tokenForEnvironment(
      String user, String tenant, String environment, String scope, List<String> capabilities) {
    DelegationPrincipal principal =
        new DelegationPrincipal(
            "https://uacrf.local/s01|" + user,
            tenant,
            environment,
            scope,
            "https://idp.example.test",
            user,
            capabilities,
            List.of(),
            List.of());
    return "Bearer "
        + new DelegationTokenIssuer(ENCODER, Clock.systemUTC(), "https://uacrf.local/s01", KEY_ID).issue(principal, "S05");
  }

  private static String workloadReceiptToken(
      String tenant, String workloadId, String resourceType, String purpose) {
    Instant now = Instant.now();
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer("https://uacrf.local/s01").subject("topology-reader").audience(List.of("S05"))
        .issuedAt(now).expiresAt(now.plusSeconds(5))
        .claim("tenant_id", tenant).claim("environment_id", "env-1")
        .claim("service_scope", workloadId).claim("actor_issuer", "https://idp.example.test")
        .claim("actor_subject", "reader").claim("capabilities", List.of("objectives.objective.read"))
        .claim("eligible_owner_ids", List.of()).claim("runtime_principal_ids", List.of())
        .claim("resource_type", resourceType).claim("resource_id", workloadId)
        .claim("purpose", purpose).build();
    return "Bearer " + ENCODER.encode(JwtEncoderParameters.from(
        JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(), claims)).getTokenValue();
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
