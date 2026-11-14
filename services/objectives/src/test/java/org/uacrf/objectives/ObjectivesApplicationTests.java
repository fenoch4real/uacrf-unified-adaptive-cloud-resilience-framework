package org.uacrf.objectives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Base64;
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

  private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
    return send(path, bearer, "POST", body);
  }

  private HttpResponse<String> put(String path, String bearer, String body) throws Exception {
    return send(path, bearer, "PUT", body);
  }

  private HttpResponse<String> send(String path, String bearer, String method, String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", bearer)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
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
        + new DelegationTokenIssuer(ENCODER, Clock.systemUTC(), "https://uacrf.local/s01", KEY_ID).issue(principal, "S05");
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
