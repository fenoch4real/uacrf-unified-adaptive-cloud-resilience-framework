package org.uacrf.objectives;

import static org.assertj.core.api.Assertions.assertThat;
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
    assertThat(post("/v1/objectives/o1:activate", activate, "{\"expected_revision\":2}").body()).contains("INVALID_STATE");
    assertThat(post("/v1/objectives/o1:decide", decide, decision("APPROVE", 2)).body()).contains("\"state\":\"APPROVED\"");
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
