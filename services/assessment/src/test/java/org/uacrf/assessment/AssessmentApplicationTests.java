package org.uacrf.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.uacrf.security.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(AssessmentApplicationTests.ModelReceiptFixture.class)
class AssessmentApplicationTests {
  @TestConfiguration
  static class ModelReceiptFixture {
    static final Set<String> UNAVAILABLE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final Set<String> UNAVAILABLE_RECEIPTS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    @Bean @Primary ReceiptVerifier receiptVerifierFixture() {
      return (item, measurements, cutoff, tenant, environment) -> {
        if (UNAVAILABLE.contains(tenant)
            || measurements.stream().anyMatch(m -> UNAVAILABLE_RECEIPTS.contains(m.sourceReceiptId()))) {
          return ReceiptVerifier.Verification.failed("OWNER_UNAVAILABLE", null, null, List.of(), Map.of());
        }
        if ((!tenant.startsWith("t-model-") && !tenant.startsWith("t-learning-")
            && !tenant.startsWith("t-incident-fresh") && !tenant.equals("t-incident-valid")
            && !tenant.equals("t-case-incidents"))
            || tenant.equals("t-model-disabled-receipts")) {
          return ReceiptVerifier.Verification.skipped();
        }
        var objective = new AssessmentResponses.ObjectiveReceipt(item.workloadId(), "set-od",
            item.objectiveRef().objectiveId(), item.objectiveRef().revision(), item.objectiveRef().digest());
        var graph = new AssessmentResponses.GraphReceipt(item.graphRef().graphId(),
            item.graphRef().revision(), item.graphRef().digest());
        var receipts = measurements.stream().map(m -> new AssessmentResponses.MeasurementReceipt(
            m.sourceReceiptId(), item.purpose(), "QUALIFIED", true, "a".repeat(64), 2,
            m.observedAt(), m.sourceKind(), item.metric(), m.value(), m.unit(), "Qualified measurement.")).toList();
        return ReceiptVerifier.Verification.verified(objective, graph, receipts);
      };
    }
  }
  private static final String KEY_ID = "assessment-test-key";
  private static final KeyPair KEY_PAIR = keyPair();
  private static final JwtEncoder ENCODER = DelegationJwtEncoderFactory.create(KEY_PAIR, KEY_ID);
  private static final List<String> ALL = List.of("assessment.case.read", "assessment.case.write",
      "assessment.evaluation.run", "assessment.evaluation.read", "assessment.recommendation.read",
      "assessment.model.read", "assessment.model.write", "assessment.model.review",
      "assessment.incident.read", "assessment.incident.write", "assessment.learning.read", "assessment.learning.write");
  @Container private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81").withDatabaseName("uacrf_assessment").withUsername("assessment_test").withPassword("assessment_test");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
    p.add("spring.flyway.locations", AssessmentTestMigrations::location);
    p.add("spring.datasource.url", POSTGRES::getJdbcUrl); p.add("spring.datasource.username", POSTGRES::getUsername); p.add("spring.datasource.password", POSTGRES::getPassword);
    p.add("uacrf.delegation.public-key-base64", () -> Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded())); p.add("uacrf.delegation.key-id", () -> KEY_ID);
    p.add("uacrf.assessment.models.enabled", () -> "true");
    p.add("uacrf.assessment.learning-review.enabled", () -> "true");
    p.add("uacrf.assessment.adaptation.enabled", () -> "true");
  }
  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;
  @Autowired private AssessmentService assessment;
  @Autowired private IncidentLearningService incidentLearning;
  @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test void keyedCaseCreationIsAtomicScopedAndReplayable() throws Exception {
    String tenant = "t-case-idempotency";
    String writer = token(tenant, "*", List.of("assessment.case.write"));
    String input = caseJson("[\"b\",\"a\"]", "LTE");
    assertEquals(400, post("/v1/cases:idempotent-create", writer, input).statusCode());
    String canonicalEquivalent = caseJson("[\"a\",\"b\"]", "LTE").replace("\"threshold\":10", "\"threshold\":10.00");
    JsonNode first = body(postCaseKey(writer, input, "key-1"), 201);
    String id = first.get("id").asString();
    assertEquals(id, body(postCaseKey(writer, canonicalEquivalent, "key-1"), 201).get("id").asString());
    assertEquals("IDEMPOTENCY_CONFLICT",
        body(postCaseKey(writer, input.replace("\"latency\"", "\"availability\""), "key-1"), 409)
            .get("code").asString());
    assertEquals(403, postCaseKey(token(tenant, "*", List.of("assessment.case.read")), input, "key-1").statusCode());
    assertEquals(400, postCaseKey(writer, input, "invalid key").statusCode());
    assertEquals(400, postCaseKey(writer, caseJson("[\"a\",\"a\"]", "LTE"), "invalid-content").statusCode());
    assertEquals(201, postCaseKey(writer, input, "invalid-content").statusCode());
    String otherActor = token(tenant, "*", List.of("assessment.case.write"), "other-user");
    assertNotEquals(id, body(postCaseKey(otherActor, input, "key-1"), 201).get("id").asString());
    assertNotEquals(id, body(postCaseKey(token("t-case-idempotency-other", "*",
        List.of("assessment.case.write")), input, "key-1"), 201).get("id").asString());
    var otherEnvironment = new DelegationPrincipal("https://uacrf.local/s01|user", tenant, "env-2", "*",
        "https://idp.example.test", "user", List.of("assessment.case.write"), List.of(), List.of());
    String otherEnvironmentToken = "Bearer "
        + new DelegationTokenIssuer(ENCODER, Clock.systemUTC(), "https://uacrf.local/s01", KEY_ID)
            .issue(otherEnvironment, "S07");
    assertNotEquals(id, body(postCaseKey(otherEnvironmentToken, input, "key-1"), 201).get("id").asString());

    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var a = executor.submit(() -> { gate.await(); return postCaseKey(writer, input, "race"); });
      var b = executor.submit(() -> { gate.await(); return postCaseKey(writer, input, "race"); });
      gate.countDown();
      assertEquals(body(a.get(), 201).get("id").asString(), body(b.get(), 201).get("id").asString());
    }
    assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.analysis_case "
            + "WHERE tenant_id=? AND environment_id='env-1'",
        Integer.class, tenant));
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var a = executor.submit(() -> { gate.await(); return postCaseKey(writer, input, "different-race"); });
      var b = executor.submit(() -> {
        gate.await(); return postCaseKey(writer, input.replace("\"latency\"", "\"availability\""), "different-race");
      });
      gate.countDown();
      assertEquals(List.of(201, 409), java.util.stream.Stream.of(a.get().statusCode(), b.get().statusCode())
          .sorted().toList());
    }
    body(post("/v1/cases/" + id + ":seal", writer, "{\"expected_revision\":1}"), 200);
    JsonNode replay = body(postCaseKey(writer, input, "key-1"), 201);
    assertEquals(id, replay.get("id").asString());
    assertEquals("SEALED", replay.get("state").asString());

    var actor = new DelegationPrincipal("https://uacrf.local/s01|user", tenant, "env-1", "*",
        "https://idp.example.test", "user", List.of("assessment.case.write"), List.of(), List.of());
    var request = json.readValue(input, AssessmentRequests.CreateCase.class);
    new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      assessment.create(actor, request, "rollback");
      status.setRollbackOnly();
    });
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.case_idempotency "
        + "WHERE tenant_id=? AND idempotency_key='rollback'", Integer.class, tenant));
    assertEquals(201, postCaseKey(writer, input, "rollback").statusCode());
  }

  @Test void sealedCaseIsImmutableAndCanonicalAcrossOrdering() throws Exception {
    String token = token("t-seal", "*", ALL);
    JsonNode first = create(token, "[\"b\",\"a\"]", "LTE");
    String id = first.get("id").asString();
    assertEquals(409, post("/internal/v1/cases/"+id+":material", token, "{}").statusCode());
    assertEquals(409, post("/v1/cases/"+id+":seal", token, "{\"expected_revision\":7}").statusCode());
    JsonNode sealed = body(post("/v1/cases/"+id+":seal", token, "{\"expected_revision\":1}"),200);
    JsonNode second = create(token, "[\"a\",\"b\"]", "LTE");
    JsonNode sealed2 = body(post("/v1/cases/"+second.get("id").asString()+":seal", token, "{\"expected_revision\":1}"),200);
    assertEquals(sealed.get("case_digest").asString(), sealed2.get("case_digest").asString());
    assertEquals(409, post("/v1/cases/"+id+":seal", token, "{\"expected_revision\":2}").statusCode());
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE uacrf_assessment.analysis_case SET workload_id='bad' WHERE tenant_id=? AND id=?", "t-seal", id));
    assertEquals(sealed.get("case_digest").asString(), body(get("/v1/cases/"+id,token),200).get("case_digest").asString());
    JsonNode receipt = body(post("/internal/v1/cases/"+id+":receipt",token,"{}"),200);
    assertThat(receipt.toString()).contains("assessment", "required_source_kinds");
    assertEquals("w1", receipt.get("workload_id").asString());
    JsonNode material = body(post("/internal/v1/cases/"+id+":material",token,"{}"),200);
    assertEquals(receipt.get("case_digest").asString(), material.get("case_digest").asString());
    assertEquals(id, material.get("case_id").asString());
  }
  @Test void keyedEvaluationsAreAtomicScopedAndReplayHistoricalResults() throws Exception {
    String tenant = "t-evaluation-idempotency";
    String writer = token(tenant, "*", ALL);
    String id = sealed(writer, "[\"a\",\"b\"]", "LTE");
    String otherCase = sealed(writer, "[\"a\",\"b\"]", "LTE");
    String a = measurement("r1", "a", "2026-01-01T00:59:00Z", "20", "ms");
    String b = measurement("r2", "b", "2026-01-01T00:59:30Z", "20", "ms");
    String input = evalJson("2026-01-01T01:00:00Z", "LIVE", a + "," + b);
    String equivalent = evalJson("2026-01-01T01:00:00.000Z", "LIVE", b + "," + a.replace("\"value\":20", "\"value\":20.00"));
    String path = "/v1/cases/" + id + ":idempotent-evaluate";
    assertEquals(400, post(path, writer, input).statusCode());
    assertEquals(400, postAdaptation(path, writer, input, "invalid key").statusCode());
    JsonNode first = body(postAdaptation(path, writer, input, "key-1"), 201);
    assertEquals("CONFIRMED_BREACH", first.get("outcome").asString());
    assertEquals(first.get("id").asString(), body(postAdaptation(path, writer, equivalent, "key-1"), 201).get("id").asString());
    ModelReceiptFixture.UNAVAILABLE.add(tenant);
    try {
      JsonNode replay = body(postAdaptation(path, writer, input, "key-1"), 201);
      assertEquals(first.get("id").asString(), replay.get("id").asString());
      assertEquals("SKIPPED", replay.at("/dependency_vector/receipt_verification").asString());
    } finally {
      ModelReceiptFixture.UNAVAILABLE.remove(tenant);
    }
    assertEquals("IDEMPOTENCY_CONFLICT", body(postAdaptation(path, writer,
        input.replace("\"LIVE\"", "\"SHADOW\""), "key-1"), 409).get("code").asString());
    assertEquals(409, postAdaptation("/v1/cases/" + otherCase + ":idempotent-evaluate", writer, input, "key-1").statusCode());
    assertEquals(403, postAdaptation(path, token(tenant, "*", List.of("assessment.evaluation.read")), input, "key-1").statusCode());
    assertEquals(403, postAdaptation(path, token(tenant, "w1", List.of("assessment.evaluation.run")), input, "key-1").statusCode());
    assertNotEquals(first.get("id").asString(), body(postAdaptation(path,
        token(tenant, "*", List.of("assessment.evaluation.run"), "other-user"), input, "key-1"), 201).get("id").asString());
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var x = executor.submit(() -> { gate.await(); return postAdaptation(path, writer, input, "race"); });
      var y = executor.submit(() -> { gate.await(); return postAdaptation(path, writer, input, "race"); });
      gate.countDown();
      assertEquals(body(x.get(), 201).get("id").asString(), body(y.get(), 201).get("id").asString());
    }
    assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.evaluation WHERE tenant_id=?", Integer.class, tenant));
    assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.recommendation WHERE tenant_id=?", Integer.class, tenant));
    assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.outbox_event WHERE tenant_id=? "
        + "AND event_type='uacrf.s07.evaluation.completed.v1'", Integer.class, tenant));
    var actor = new DelegationPrincipal("https://uacrf.local/s01|user", tenant, "env-1", "*",
        "https://idp.example.test", "user", List.of("assessment.evaluation.run"), List.of(), List.of());
    var request = json.readValue(input, AssessmentRequests.Evaluate.class);
    new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      assessment.evaluate(actor, id, request, "rollback");
      status.setRollbackOnly();
    });
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.evaluation_idempotency "
        + "WHERE tenant_id=? AND idempotency_key='rollback'", Integer.class, tenant));
    assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.evaluation WHERE tenant_id=?", Integer.class, tenant));
    assertEquals(201, postAdaptation(path, writer, input, "rollback").statusCode());
  }
  @Test void competingEvaluationContentsBindOneResult() throws Exception {
    String tenant = "t-evaluation-race-conflict";
    String writer = token(tenant, "*", ALL);
    String id = sealed(writer, "[\"a\"]", "LTE");
    String path = "/v1/cases/" + id + ":idempotent-evaluate";
    String input = evalJson("2026-01-01T01:00:00Z", "SHADOW", "");
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var first = executor.submit(() -> { gate.await(); return postAdaptation(path, writer, input, "conflict-race"); });
      var second = executor.submit(() -> { gate.await(); return postAdaptation(path, writer,
          input.replace("\"SHADOW\"", "\"DRY_RUN\""), "conflict-race"); });
      gate.countDown();
      assertEquals(List.of(201, 409), java.util.stream.Stream.of(first.get().statusCode(), second.get().statusCode())
          .sorted().toList());
    }
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.evaluation WHERE tenant_id=?", Integer.class, tenant));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.evaluation_idempotency WHERE tenant_id=?", Integer.class, tenant));
  }
  @Test void evaluationKeysAreIsolatedByTenantAndEnvironment() throws Exception {
    String tenant = "t-evaluation-scope";
    String writer = token(tenant, "*", ALL);
    String otherTenant = token("t-evaluation-other-tenant", "*", ALL);
    var principal = new DelegationPrincipal("https://uacrf.local/s01|user", tenant, "env-2", "*",
        "https://idp.example.test", "user", ALL, List.of(), List.of());
    String otherEnvironment = "Bearer " + new DelegationTokenIssuer(ENCODER, Clock.systemUTC(),
        "https://uacrf.local/s01", KEY_ID).issue(principal, "S07");
    String input = evalJson("2026-01-01T01:00:00Z", "SHADOW", "");
    Set<String> results = new java.util.HashSet<>();
    for (String actor : List.of(writer, otherTenant, otherEnvironment)) {
      String id = sealed(actor, "[\"a\"]", "LTE");
      results.add(body(postAdaptation("/v1/cases/" + id + ":idempotent-evaluate", actor, input, "shared-key"), 201)
          .get("id").asString());
    }
    assertEquals(3, results.size());
    assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.evaluation_idempotency "
        + "WHERE idempotency_key='shared-key' AND tenant_id IN (?,?)", Integer.class, tenant, "t-evaluation-other-tenant"));
  }
  @Test void caseMaterialFailsClosedWhenStoredDigestDoesNotMatch() throws Exception {
    String token = token("t-material-corrupt", "*", ALL);
    String id = create(token, "[\"a\"]", "LTE").get("id").asString();
    jdbc.update("UPDATE uacrf_assessment.analysis_case SET state='SEALED', revision=2, case_digest=? "
        + "WHERE tenant_id=? AND id=?", "0".repeat(64), "t-material-corrupt", id);
    var response = post("/internal/v1/cases/"+id+":material", token, "{}");
    assertEquals(409, response.statusCode());
    assertEquals("CASE_DIGEST_MISMATCH", json.readTree(response.body()).get("code").asString());
    assertThat(response.body()).doesNotContain("canonical_text");
  }
  @Test void inclusiveBoundariesAndEvidenceRules() throws Exception {
    String token = token("t-rules", "*", ALL); String id = sealed(token, "[\"a\",\"b\"]", "LTE");
    String a = measurement("r1","a","2026-01-01T00:59:00Z","10","ms");
    String b = measurement("r2","b","2026-01-01T00:59:30Z","10","ms");
    assertEquals("CONFIRMED_SUCCESS", eval(token,id,"2026-01-01T01:00:00Z","SHADOW",a+","+b).get("outcome").asString());
    assertEquals("INSUFFICIENT_EVIDENCE", eval(token,id,"2026-01-01T01:00:00Z","SHADOW",a).get("outcome").asString());
    assertEquals("INSUFFICIENT_EVIDENCE", eval(token,id,"2026-01-01T01:00:00Z","SHADOW",a+","+b.replace("ms", "s")).get("outcome").asString());
    JsonNode ignored = eval(token,id,"2026-01-01T01:00:00Z","SHADOW",a+","+b.replace("00:59:30", "01:00:01"));
    assertEquals("INSUFFICIENT_EVIDENCE", ignored.get("outcome").asString()); assertThat(ignored.toString()).contains("AFTER_CUTOFF");
    JsonNode outside = eval(token,id,"2026-01-01T01:00:00Z","SHADOW",a+","+b.replace("2026-01-01T00:59:30Z", "2025-12-31T23:59:59Z"));
    assertEquals("INSUFFICIENT_EVIDENCE", outside.get("outcome").asString()); assertThat(outside.toString()).contains("OUTSIDE_WINDOW");
    JsonNode precise = eval(token,id,"2026-01-01T01:00:00.0000001Z","SHADOW",a+","+b.replace("2026-01-01T00:59:30Z", "2026-01-01T01:00:00.0000009Z"));
    assertEquals("INSUFFICIENT_EVIDENCE", precise.get("outcome").asString());
    assertThat(precise.toString()).contains("AFTER_CUTOFF");
    eval(token,id,"2026-01-01T01:00:00.0000000Z","SHADOW",a+","+b);
    assertEquals(precise.get("id").asString(), body(get("/v1/cases/"+id+"/evaluations:latest",token),200).get("id").asString());
    String gte = sealed(token,"[\"a\"]","GTE");
    assertEquals("CONFIRMED_SUCCESS", eval(token,gte,"2026-01-01T01:00:00Z","SHADOW",a).get("outcome").asString());
  }
  @Test void correctionReplayAndLatestCutoffPreserveHistory() throws Exception {
    String token = token("t-history", "*", ALL); String id = sealed(token,"[\"a\"]","LTE");
    String good = measurement("receipt","a","2026-01-01T00:59:00Z","5","ms");
    JsonNode old = eval(token,id,"2026-01-01T01:00:00Z","LIVE",good);
    JsonNode replay = eval(token,id,"2026-01-01T01:00:00Z","LIVE",good);
    assertEquals(old.get("result_digest").asString(),replay.get("result_digest").asString());
    assertEquals("SKIPPED", old.get("dependency_vector").get("receipt_verification").asString());
    JsonNode corrected = eval(token,id,"2026-01-01T01:00:00Z","LIVE",good.replace("\"value\":5", "\"value\":20"));
    assertEquals("CONFIRMED_BREACH", corrected.get("outcome").asString()); assertEquals(3,corrected.get("revision").asInt());
    assertNotEquals(old.get("result_digest").asString(), corrected.get("result_digest").asString());
    assertEquals("CONFIRMED_SUCCESS",body(get("/v1/evaluations/"+old.get("id").asString(),token),200).get("outcome").asString());
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE uacrf_assessment.evaluation SET outcome='CONFIRMED_BREACH' WHERE tenant_id=? AND id=?", "t-history", old.get("id").asString()));
    eval(token,id,"2026-01-01T02:00:00Z","LIVE","");
    eval(token,id,"2026-01-01T01:00:00Z","LIVE",good);
    assertEquals("INSUFFICIENT_EVIDENCE",body(get("/v1/cases/"+id+"/evaluations:latest",token),200).get("outcome").asString());
    assertEquals(5, body(get("/v1/cases/"+id+"/evaluations",token),200).size());
  }
  @Test void modesRecommendationsAndLatestPerSource() throws Exception {
    String token = token("t-mode", "*", ALL); String id = sealed(token,"[\"a\"]","LTE");
    String older = measurement("r1","a","2026-01-01T00:58:00Z","5","ms"), newer = measurement("r2","a","2026-01-01T00:59:00Z","20","ms");
    JsonNode shadow = eval(token,id,"2026-01-01T01:00:00Z","SHADOW",older+","+newer);
    assertEquals("CONFIRMED_BREACH", shadow.get("outcome").asString()); assertThat(shadow.toString()).contains("SUPERSEDED");
    eval(token,id,"2026-01-01T01:00:00Z","DRY_RUN",newer);
    assertEquals(0,body(get("/v1/recommendations?workload_id=w1",token),200).size());
    eval(token,id,"2026-01-01T01:00:00Z","LIVE",newer);
    JsonNode recs = body(get("/v1/recommendations?workload_id=w1",token),200); assertEquals(1,recs.size()); assertFalse(recs.get(0).get("executable").asBoolean());
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE uacrf_assessment.recommendation SET executable=true WHERE tenant_id='t-mode'"));
    assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.outbox_event WHERE tenant_id='t-mode'",Integer.class));
  }
  @Test void assessmentReadsRejectServiceScopedDelegations() throws Exception {
    String tenant = "t-assessment-read-scope";
    String owner = token(tenant, "*", ALL);
    String id = sealed(owner, "[\"a\"]", "LTE");
    String evaluationId = eval(owner, id, "2026-01-01T01:00:00Z", "LIVE",
        measurement("r-scope", "a", "2026-01-01T00:59:00Z", "20", "ms"))
        .get("id").asString();
    assertEquals(200, get("/v1/cases/" + id, owner).statusCode());
    assertEquals(200, get("/v1/cases", owner).statusCode());
    assertEquals(200, post("/internal/v1/cases/" + id + ":receipt", owner, "{}").statusCode());
    assertEquals(200, post("/internal/v1/cases/" + id + ":material", owner, "{}").statusCode());
    assertEquals(200, get("/v1/cases/" + id + "/evaluations", owner).statusCode());
    assertEquals(200, get("/v1/cases/" + id + "/evaluations:latest", owner).statusCode());
    assertEquals(200, get("/v1/evaluations/" + evaluationId, owner).statusCode());
    assertEquals(200, get("/v1/recommendations?workload_id=w1", owner).statusCode());
    for (String scope : List.of("w1", "another-service")) {
      String limited = token(tenant, scope, ALL);
      assertEquals(403, get("/v1/cases/" + id, limited).statusCode());
      assertEquals(403, get("/v1/cases", limited).statusCode());
      assertEquals(403, get("/v1/cases?workload_id=w1", limited).statusCode());
      assertEquals(403, post("/internal/v1/cases/" + id + ":receipt", limited, "{}").statusCode());
      assertEquals(403, post("/internal/v1/cases/" + id + ":material", limited, "{}").statusCode());
      assertEquals(403, get("/v1/cases/" + id + "/evaluations", limited).statusCode());
      assertEquals(403, get("/v1/cases/" + id + "/evaluations:latest", limited).statusCode());
      assertEquals(403, get("/v1/evaluations/" + evaluationId, limited).statusCode());
      assertEquals(403, get("/v1/recommendations?workload_id=w1", limited).statusCode());
    }
  }
  @Test void graphOperationReceiptDelegationsBindTheCurrentSealedCase() throws Exception {
    String owner = token("t-current-case-receipt", "*", ALL);
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    String otherCaseId = sealed(owner, "[\"a\"]", "LTE");
    for (String purpose : List.of(
        "topology.graph.subgraph-selection", "topology.graph.difference-classification")) {
      String scoped = scopedCaseReceiptToken(
          "t-current-case-receipt", caseId, "assessment_case", caseId, purpose);
      JsonNode receipt = body(post("/internal/v1/cases/" + caseId + ":receipt", scoped, "{}"), 200);
      assertEquals(caseId, receipt.get("case_id").asString());
      assertEquals("assessment", receipt.get("purpose").asString());
      assertNotNull(receipt.get("case_digest").asString());
      assertEquals(403, post("/internal/v1/cases/" + otherCaseId + ":receipt", scoped, "{}").statusCode());
      assertEquals(404, post("/internal/v1/cases/" + caseId + ":receipt",
          scopedCaseReceiptToken("t-other-case-receipt", caseId, "assessment_case", caseId, purpose),
          "{}").statusCode());
    }

    for (String invalidPurpose : List.of("topology.graph.current-use", "other-purpose")) {
      assertEquals(403, post("/internal/v1/cases/" + caseId + ":receipt",
          scopedCaseReceiptToken("t-current-case-receipt", caseId, "assessment_case", caseId, invalidPurpose),
          "{}").statusCode());
    }
    assertEquals(403, post("/internal/v1/cases/" + caseId + ":receipt",
        scopedCaseReceiptToken("t-current-case-receipt", caseId, "service", caseId,
            "topology.graph.subgraph-selection"), "{}").statusCode());
    assertEquals(403, post("/internal/v1/cases/" + caseId + ":receipt",
        scopedCaseReceiptToken("t-current-case-receipt", "other-case", "assessment_case", caseId,
            "topology.graph.subgraph-selection"), "{}").statusCode());
    Instant expiredAt = Instant.now().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    assertEquals(401, post("/internal/v1/cases/" + caseId + ":receipt",
        scopedCaseReceiptToken("t-current-case-receipt", caseId, "assessment_case", caseId,
            "topology.graph.subgraph-selection", expiredAt, expiredAt.plusSeconds(30)),
        "{}").statusCode());

    JsonNode draft = create(owner, "[\"a\"]", "LTE");
    String draftId = draft.get("id").asString();
    String draftToken = scopedCaseReceiptToken(
        "t-current-case-receipt", draftId, "assessment_case", draftId,
        "topology.graph.subgraph-selection");
    assertEquals(409, post("/internal/v1/cases/" + draftId + ":receipt", draftToken, "{}").statusCode());
  }
  @Test void authorizationAndTenantIsolation() throws Exception {
    String token = token("t-auth","*",ALL); String id = sealed(token,"[\"a\"]","LTE");
    String other = token("t-other","*",ALL); assertEquals(404,get("/v1/cases/"+id,other).statusCode());
    assertEquals(404,post("/internal/v1/cases/"+id+":receipt",other,"{}").statusCode());
    assertEquals(404,post("/internal/v1/cases/"+id+":material",other,"{}").statusCode());
    assertEquals(403,post("/internal/v1/cases/"+id+":receipt",
        token("t-auth","*",List.of("assessment.case.write")),"{}").statusCode());
    assertEquals(403,post("/internal/v1/cases/"+id+":material",
        token("t-auth","*",List.of("assessment.case.write")),"{}").statusCode());
    assertEquals(403,get("/v1/cases/"+id,token("t-auth","*",List.of("assessment.case.write"))).statusCode());
    assertEquals(403,post("/v1/cases",token("t-auth","service-1",ALL),caseJson("[\"a\"]","LTE")).statusCode());
    assertEquals(403,post("/v1/cases/"+id+":evaluate",token("t-auth","service-1",ALL),evalJson("2026-01-01T01:00:00Z","LIVE","")).statusCode());
    assertEquals(403,post("/v1/cases/"+id+":evaluate",token("t-auth","*",List.of("assessment.case.read")),evalJson("2026-01-01T01:00:00Z","LIVE","")).statusCode());
  }
  @Test void modelVersionsAreImmutableUniqueAndTenantScoped() throws Exception {
    String owner = token("t-model-immutable", "*", ALL);
    JsonNode retained = modelVersion(owner, "m-immutable", "1.0");
    String id = retained.get("id").asString();
    assertEquals(403, get("/v1/model-versions/" + id,
        token("t-model-immutable", "*", List.of("assessment.model.write"))).statusCode());
    assertEquals(403, post("/v1/model-versions", token("t-model-immutable", "*",
        List.of("assessment.model.read")), modelJson("m-forbidden", "1.0")).statusCode());
    assertEquals("CANDIDATE", retained.get("state").asString());
    assertEquals(1, retained.get("revision").asInt());
    assertEquals(409, post("/v1/model-versions", owner,
        modelJson("m-immutable", "1.0")).statusCode());
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "UPDATE uacrf_assessment.model_version SET model_card_ref='changed' WHERE tenant_id=? AND id=?",
        "t-model-immutable", id));
    assertEquals(404, get("/v1/model-versions/" + id,
        token("t-model-other", "*", ALL)).statusCode());
    assertEquals(1, body(get("/v1/model-versions?model_id=m-immutable", owner), 200).size());
    assertEquals(0, body(get("/v1/model-versions", token("t-model-other", "*", ALL)), 200).size());
  }
  @Test void modelEvaluationDeclarationsRetainUnverifiedArtifactBoundLineage() throws Exception {
    String tenant = "t-model-declaration";
    String owner = token(tenant, "*", ALL);
    JsonNode model = modelVersion(owner, "m-declaration", "1");
    String modelId = model.get("id").asString();
    String path = "/internal/v1/model-evaluation-declarations";
    String payload = "{\"model_version_id\":\"" + modelId + "\","
        + "\"training_data_digest\":\"" + "a".repeat(64) + "\","
        + "\"holdout_data_digest\":\"" + "b".repeat(64) + "\","
        + "\"result_ref\":\"result://offline\",\"result_digest\":\"" + "c".repeat(64) + "\","
        + "\"training_through\":\"2026-01-01T00:00:00.000000001Z\","
        + "\"holdout_from\":\"2026-01-01T00:00:00.000000002Z\","
        + "\"holdout_through\":\"2026-01-02T00:00:00Z\",\"reason\":\"record provenance\"}";
    assertEquals(403, post(path, token(tenant, "*", List.of("assessment.model.read")), payload).statusCode());
    assertEquals(403, post(path, token(tenant, "w1", List.of("assessment.model.write")), payload).statusCode());
    assertEquals(404, post(path, token("t-model-other", "*", ALL), payload).statusCode());
    assertEquals(400, post(path, owner,
        payload.replace("00:00:00.000000002Z", "00:00:00.000000001Z")).statusCode());
    assertEquals(400, post(path, owner,
        payload.replace("2026-01-02T00:00:00Z", "2025-12-31T23:59:59Z")).statusCode());
    assertEquals(400, post(path, owner, payload.replace("c".repeat(64), "INVALID")).statusCode());
    JsonNode declared = body(post(path, owner, payload), 201);
    String id = declared.get("id").asString();
    assertEquals("DECLARED_UNVERIFIED", declared.get("status").asString());
    assertEquals(modelId, declared.get("model_version_id").asString());
    assertEquals(model.get("digest").asString(), declared.get("model_digest").asString());
    assertEquals("2026-01-01T00:00:00.000000002Z", declared.get("holdout_from").asString());
    assertEquals("c".repeat(64), declared.get("result_digest").asString());
    assertEquals(declared, body(get(path + "/" + id, owner), 200));
    assertEquals(404, get(path + "/" + id, token("t-model-other", "*", ALL)).statusCode());
    assertEquals(403, get(path + "/" + id, token(tenant, "*", List.of("assessment.model.write"))).statusCode());
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "UPDATE uacrf_assessment.model_evaluation_declaration SET status='VERIFIED' WHERE tenant_id=? AND id=?",
        tenant, id));
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "DELETE FROM uacrf_assessment.model_evaluation_declaration WHERE tenant_id=? AND id=?", tenant, id));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.outbox_event "
        + "WHERE tenant_id=? AND aggregate_id=? AND aggregate_type='MODEL_EVALUATION_DECLARATION' "
        + "AND event_type='uacrf.s07.model-evaluation.declared.v1'", Integer.class, tenant, id));
    String event = jdbc.queryForObject("SELECT payload::text FROM uacrf_assessment.outbox_event "
        + "WHERE tenant_id=? AND aggregate_id=?", String.class, tenant, id);
    assertThat(event).doesNotContain("result://offline", "a".repeat(64), "b".repeat(64), "c".repeat(64));
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    String evaluation = seedEvaluation(tenant, caseId, "VERIFIED", "CONFIRMED_SUCCESS");
    body(post(reviewPath(modelId), token(tenant, "*", ALL, "reviewer"),
        reviewJson(1, "APPROVE", "[\"" + evaluation + "\"]")), 200);
    for (String mode : List.of("SHADOW", "ADVISORY")) {
      HttpResponse<String> blocked = post("/v1/model-versions/" + modelId + ":promote",
          token(tenant, "*", ALL, "promoter"), promoteJson(2, mode));
      assertEquals(409, blocked.statusCode());
      assertThat(blocked.body()).contains("MODEL_EVALUATION_REQUIRED");
    }
  }
  @Test void modelEvaluationDeclarationListingIsBoundedAndScoped() throws Exception {
    String tenant = "t-model-declaration-list";
    String owner = token(tenant, "*", ALL);
    String model = modelVersion(owner, "list-model", "1").get("id").asString();
    String otherModel = modelVersion(owner, "other-model", "1").get("id").asString();
    String collection = "/internal/v1/model-evaluation-declarations";
    String payload = "{\"model_version_id\":\"" + model + "\","
        + "\"training_data_digest\":\"" + "a".repeat(64) + "\","
        + "\"holdout_data_digest\":\"" + "b".repeat(64) + "\","
        + "\"result_ref\":\"result\",\"result_digest\":\"" + "c".repeat(64) + "\","
        + "\"training_through\":\"2026-01-01T00:00:00Z\","
        + "\"holdout_from\":\"2026-01-02T00:00:00Z\","
        + "\"holdout_through\":\"2026-01-02T00:00:00Z\",\"reason\":\"test\"}";
    String firstId = body(post(collection, owner, payload), 201).get("id").asString();
    String secondId = body(post(collection, owner, payload), 201).get("id").asString();
    body(post(collection, owner, payload.replace(model, otherModel)), 201);
    String query = collection + "?model_version_id=" + model;
    assertEquals(403, get(query, token(tenant, "*", List.of("assessment.model.write"))).statusCode());
    assertEquals(404, get(query, token("t-model-declaration-foreign", "*", ALL)).statusCode());
    assertEquals(400, get(collection, owner).statusCode());
    assertEquals(400, get(query + "&limit=0", owner).statusCode());
    assertEquals(400, get(query + "&limit=101", owner).statusCode());
    assertEquals(400, get(query + "&after=invalid", owner).statusCode());
    JsonNode first = body(get(query + "&limit=1", owner), 200);
    assertEquals(1, first.get("items").size());
    String cursor = first.get("next_cursor").asString();
    assertEquals(first.get("items").get(0).get("id").asString(), cursor);
    JsonNode second = body(get(query + "&limit=1&after=" + cursor, owner), 200);
    assertEquals(1, second.get("items").size());
    assertTrue(second.get("next_cursor").isNull());
    assertEquals(Set.of(firstId, secondId), Set.of(cursor, second.get("items").get(0).get("id").asString()));
    assertEquals("DECLARED_UNVERIFIED", second.get("items").get(0).get("status").asString());
    assertEquals(0, body(get(query + "&after=" + second.get("items").get(0).get("id").asString(), owner),
        200).get("items").size());
    DelegationPrincipal otherEnvironment = new DelegationPrincipal(
        "https://uacrf.local/s01|user", tenant, "env-2", "*", "https://idp.example.test", "user",
        ALL, List.of(), List.of());
    String envToken = "Bearer " + new DelegationTokenIssuer(ENCODER, Clock.systemUTC(),
        "https://uacrf.local/s01", KEY_ID).issue(otherEnvironment, "S07");
    assertEquals(404, get(query, envToken).statusCode());
  }
  @Test void reviewRequiresIndependentReviewerVerifiedReceiptsAndPassingEvaluation() throws Exception {
    String proposer = token("t-model-review", "*", ALL);
    String id = modelVersion(proposer, "m-review", "1.0").get("id").asString();
    String reviewer = token("t-model-review", "*", ALL, "reviewer");
    assertEquals(409, post(reviewPath(id), proposer, reviewJson(1, "APPROVE", "[]")).statusCode());
    assertEquals(409, post(reviewPath(id), reviewer, reviewJson(2, "APPROVE", "[]")).statusCode());
    String missingId = UUID.randomUUID().toString();
    assertEquals(404, post(reviewPath(id), reviewer, reviewJson(1, "APPROVE",
        "[\"" + missingId + "\"]")).statusCode());
    String caseId = sealed(proposer, "[\"a\"]", "LTE");
    String unverified = seedEvaluation("t-model-review", caseId, "SKIPPED", "CONFIRMED_SUCCESS");
    assertEquals(409, post(reviewPath(id), reviewer, reviewJson(1, "APPROVE",
        "[\"" + unverified + "\"]")).statusCode());
    assertEquals(409, post(reviewPath(id), reviewer, reviewJson(1, "APPROVE", "[]")).statusCode());
    String passing = seedEvaluation("t-model-review", caseId, "VERIFIED", "CONFIRMED_SUCCESS");
    JsonNode approved = body(post(reviewPath(id), reviewer, reviewJson(1, "APPROVE",
        "[\"" + passing + "\"]")), 200);
    assertEquals("APPROVED", approved.get("state").asString());
    assertEquals(2, approved.get("revision").asInt());
    assertThat(approved.get("reviewed_by").asString()).endsWith("|reviewer");
    assertEquals(409, post(reviewPath(id), reviewer, reviewJson(2, "REJECT", "[]")).statusCode());
    assertEquals(409, post("/v1/model-versions/" + id + ":promote",
        token("t-model-review", "*", ALL, "reviewer"),
        promoteJson(2, "SHADOW")).statusCode());
  }
  @Test void modelReviewRejectsEvaluationWhoseRetainedResultDigestDoesNotMatch() throws Exception {
    String tenant = "t-model-corrupt-result-digest";
    String proposer = token(tenant, "*", ALL);
    String reviewer = token(tenant, "*", ALL, "reviewer");
    String caseId = sealed(proposer, "[\"a\"]", "LTE");
    String evaluation = seedEvaluation(tenant, caseId, "VERIFIED", "CONFIRMED_SUCCESS",
        "f".repeat(64));
    String model = modelVersion(proposer, "digest-check", "1").get("id").asString();

    HttpResponse<String> rejected = post(reviewPath(model), reviewer,
        reviewJson(1, "APPROVE", "[\"" + evaluation + "\"]"));

    assertEquals(409, rejected.statusCode());
    assertThat(rejected.body()).contains("EVALUATION_DIGEST_MISMATCH");
    assertEquals("CANDIDATE", body(get("/v1/model-versions/" + model, proposer), 200)
        .get("state").asString());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.outbox_event "
        + "WHERE tenant_id=? AND aggregate_id=? "
        + "AND event_type='uacrf.s07.model-version.review-decided.v1'",
        Integer.class, tenant, model));
  }
  @Test void evaluationIdsAreTenantScopedAndInsufficientEvidenceCannotBeReviewed() throws Exception {
    String proposer = token("t-model-cross", "*", ALL);
    String id = modelVersion(proposer, "m-cross", "1.0").get("id").asString();
    String reviewer = token("t-model-cross", "*", ALL, "reviewer");
    String caseId = sealed(proposer, "[\"a\"]", "LTE");
    String foreignCase = sealed(token("t-model-foreign", "*", ALL), "[\"a\"]", "LTE");
    String foreignEvaluation = seedEvaluation("t-model-foreign", foreignCase, "VERIFIED", "CONFIRMED_SUCCESS");
    assertEquals(404, post(reviewPath(id), reviewer,
        reviewJson(1, "APPROVE", "[\"" + foreignEvaluation + "\"]")).statusCode());
    String insufficient = seedEvaluation("t-model-cross", caseId, "VERIFIED", "INSUFFICIENT_EVIDENCE");
    assertEquals(409, post(reviewPath(id), reviewer,
        reviewJson(1, "REJECT", "[\"" + insufficient + "\"]")).statusCode());
  }
  @Test void disabledReceiptVerificationCannotAuthorizeModelReview() throws Exception {
    String tenant = "t-model-disabled-receipts";
    String proposer = token(tenant, "*", ALL);
    String reviewer = token(tenant, "*", ALL, "reviewer");
    String caseId = sealed(proposer, "[\"a\"]", "LTE");
    String evaluation = seedEvaluation(tenant, caseId, "VERIFIED", "CONFIRMED_SUCCESS");
    String model = modelVersion(proposer, "disabled-check", "1").get("id").asString();
    HttpResponse<String> response = post(reviewPath(model), reviewer,
        reviewJson(1, "APPROVE", "[\"" + evaluation + "\"]"));
    assertEquals(409, response.statusCode());
    assertThat(response.body()).contains("OWNER_REVALIDATION_FAILED", "SKIPPED");
  }
  @Test void workloadEvaluationCannotAuthorizeModelPromotion() throws Exception {
    String tenant = "t-model-no-lineage";
    String proposer = token(tenant, "*", ALL);
    String reviewer = token(tenant, "*", ALL, "reviewer");
    String promoter = token(tenant, "*", ALL, "promoter");
    String caseId = sealed(proposer, "[\"a\"]", "LTE");
    String evaluation = seedEvaluation(tenant, caseId, "VERIFIED", "CONFIRMED_SUCCESS");
    String model = modelVersion(proposer, "missing-model-evaluation", "1").get("id").asString();
    body(post(reviewPath(model), reviewer, reviewJson(1, "APPROVE", "[\"" + evaluation + "\"]")), 200);
    HttpResponse<String> response = post("/v1/model-versions/" + model + ":promote",
        promoter, promoteJson(2, "SHADOW"));
    assertEquals(409, response.statusCode());
    assertThat(response.body()).contains("MODEL_EVALUATION_REQUIRED");
    assertEquals("APPROVED", body(get("/v1/model-versions/" + model, proposer), 200).get("state").asString());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.outbox_event "
        + "WHERE tenant_id=? AND aggregate_id=? AND event_type='uacrf.s07.model-version.promoted.v1'",
        Integer.class, tenant, model));
  }
  @Test void promotionKeepsLegacyActiveVersionAndEnforcesIndependenceAndMode() throws Exception {
    String proposer = token("t-model-promote", "*", ALL);
    String reviewer = token("t-model-promote", "*", ALL, "reviewer");
    String promoter = token("t-model-promote", "*", ALL, "promoter");
    String caseId = sealed(proposer, "[\"a\"]", "LTE");
    String passing = seedEvaluation("t-model-promote", caseId, "VERIFIED", "CONFIRMED_SUCCESS");
    String first = modelVersion(proposer, "m-promote", "1.0").get("id").asString();
    body(post(reviewPath(first), reviewer, reviewJson(1, "APPROVE", "[\"" + passing + "\"]")), 200);
    String second = modelVersion(proposer, "m-promote", "2.0").get("id").asString();
    body(post(reviewPath(second), reviewer, reviewJson(1, "APPROVE", "[\"" + passing + "\"]")), 200);
    assertEquals(409, post("/v1/model-versions/" + first + ":promote", proposer,
        promoteJson(2, "SHADOW")).statusCode());
    assertEquals(409, post("/v1/model-versions/" + first + ":promote", promoter,
        promoteJson(3, "SHADOW")).statusCode());
    assertEquals(400, post("/v1/model-versions/" + first + ":promote", promoter,
        promoteJson(2, "LIVE")).statusCode());
    jdbc.update("INSERT INTO uacrf_assessment.model_version "
            + "(tenant_id,environment_id,id,model_id,version,artifact_ref,digest,model_card_ref,"
            + "evaluation_pack_ref,reason,state,revision,proposed_by,reviewed_by,decision,"
            + "evaluation_result_ids,review_reason,reviewed_at,promoted_by,target_mode,promotion_reason,promoted_at) "
            + "VALUES ('t-model-promote','env-1','legacy-active','m-promote','legacy','artifact',?,"
            + "'card','pack','reason','ACTIVE',3,'proposer','reviewer','APPROVE',?::jsonb,"
            + "'review',clock_timestamp(),'promoter','SHADOW','promotion',clock_timestamp())",
        "a".repeat(64), "[\"" + passing + "\"]");
    assertThat(post("/v1/model-versions/" + first + ":promote", promoter, promoteJson(2, "SHADOW")).body())
        .contains("MODEL_EVALUATION_REQUIRED");
    assertThat(post("/v1/model-versions/" + second + ":promote", promoter, promoteJson(2, "ADVISORY")).body())
        .contains("MODEL_EVALUATION_REQUIRED");
    assertEquals("APPROVED", body(get("/v1/model-versions/" + first, proposer), 200).get("state").asString());
    assertEquals("APPROVED", body(get("/v1/model-versions/" + second, proposer), 200).get("state").asString());
    assertEquals("ACTIVE", body(get("/v1/model-versions/legacy-active", proposer), 200).get("state").asString());
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.model_version "
        + "WHERE tenant_id='t-model-promote' AND model_id='m-promote' "
        + "AND target_mode='SHADOW' AND state='ACTIVE'", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.model_version "
        + "WHERE tenant_id='t-model-promote' AND model_id='m-promote' "
        + "AND target_mode='ADVISORY' AND state='ACTIVE'", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.outbox_event "
        + "WHERE tenant_id='t-model-promote' AND event_type IN "
        + "('uacrf.s07.model-version.promoted.v1','uacrf.s07.model-version.superseded.v1')", Integer.class));
  }

  @Test void incidentCreationRevalidatesCurrentOwnerEvidence() throws Exception {
    String tenant = "t-incident-fresh";
    String owner = token(tenant, "*", ALL);
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    String cutoff = "2026-01-01T01:00:00Z";
    JsonNode evaluation = eval(owner, caseId, cutoff, "LIVE",
        measurement("r-fresh", "a", "2026-01-01T00:59:00Z", "5", "ms"));
    assertEquals("VERIFIED", evaluation.at("/dependency_vector/receipt_verification").asString());
    String request = incidentJson(caseId, "w1", cutoff, "[\"r-fresh\"]");
    JsonNode incident = body(post("/v1/incidents", owner, request), 201);
    String retained = incident.get("id").asString();
    assertEquals(List.of(evaluation.get("id").asString()),
        json.convertValue(incident.get("source_evaluation_ids"), List.class));
    assertEquals(incident.get("source_evaluation_ids"),
        body(get("/v1/incidents/" + retained, owner), 200).get("source_evaluation_ids"));
    assertEquals(incident.get("source_evaluation_ids"),
        body(get("/v1/cases/" + caseId + "/incidents", owner), 200)
            .get("items").get(0).get("source_evaluation_ids"));
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "UPDATE uacrf_assessment.incident SET source_evaluation_ids='[\"different\"]'::jsonb WHERE tenant_id=? AND id=?",
        tenant, retained));
    try {
      ModelReceiptFixture.UNAVAILABLE.add(tenant);
      JsonNode rejected = body(post("/v1/incidents", owner, request), 409);
      assertEquals("OWNER_REVALIDATION_FAILED", rejected.get("code").asString());
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.incident "
          + "WHERE tenant_id=?", Integer.class, tenant));
      assertEquals(200, get("/v1/incidents/" + retained, owner).statusCode());
    } finally {
      ModelReceiptFixture.UNAVAILABLE.remove(tenant);
    }
  }

  @Test void incidentRevalidatesEveryContributingEvaluationButNotUnrelatedOnes() throws Exception {
    String tenant = "t-incident-fresh-multiple";
    String owner = token(tenant, "*", ALL);
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    String cutoff = "2026-01-01T01:00:00Z";
    Map<String, String> evaluations = new HashMap<>();
    for (String id : List.of("r-first", "r-second", "r-unrelated")) {
      evaluations.put(id, eval(owner, caseId, cutoff, "LIVE",
          measurement(id, "a", "2026-01-01T00:59:00Z", "5", "ms")).get("id").asString());
    }
    try {
      ModelReceiptFixture.UNAVAILABLE_RECEIPTS.add("r-unrelated");
      body(post("/v1/incidents", owner,
          incidentJson(caseId, "w1", cutoff, "[\"r-first\"]")), 201);
      ModelReceiptFixture.UNAVAILABLE_RECEIPTS.add("r-second");
      String both = incidentJson(caseId, "w1", cutoff, "[\"r-first\",\"r-second\"]");
      assertEquals("OWNER_REVALIDATION_FAILED",
          body(post("/v1/incidents", owner, both), 409).get("code").asString());
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.incident "
          + "WHERE tenant_id=?", Integer.class, tenant));
      ModelReceiptFixture.UNAVAILABLE_RECEIPTS.remove("r-second");
      JsonNode accepted = body(post("/v1/incidents", owner, both), 201);
      assertEquals(List.of(evaluations.get("r-second"), evaluations.get("r-first")),
          json.convertValue(accepted.get("source_evaluation_ids"), List.class));
      ModelReceiptFixture.UNAVAILABLE_RECEIPTS.remove("r-unrelated");
      eval(owner, caseId, cutoff, "LIVE",
          measurement("r-first", "a", "2026-01-01T00:58:00Z", "5", "ms") + ","
              + measurement("r-unrelated", "a", "2026-01-01T00:59:00Z", "5", "ms"));
      ModelReceiptFixture.UNAVAILABLE_RECEIPTS.add("r-unrelated");
      assertEquals("OWNER_REVALIDATION_FAILED", body(post("/v1/incidents", owner,
          incidentJson(caseId, "w1", cutoff, "[\"r-first\"]")), 409).get("code").asString());
    } finally {
      ModelReceiptFixture.UNAVAILABLE_RECEIPTS.removeAll(List.of("r-second", "r-unrelated"));
    }
  }

  @Test void incidentProvenanceMigrationPreservesUnknownLegacyLineage() {
    String database = "assessment_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url = POSTGRES.getJdbcUrl().replace("/uacrf_assessment?", "/" + database + "?");
      var source = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
      Flyway.configure().dataSource(source).locations(AssessmentTestMigrations.location())
          .target("7").load().migrate();
      var oldDb = new JdbcTemplate(source);
      oldDb.update("INSERT INTO uacrf_assessment.analysis_case "
          + "(tenant_id,environment_id,id,workload_id,content,state,revision,created_by) "
          + "VALUES ('legacy','env-1','case-1','w1','{}'::jsonb,'DRAFT',1,'user')");
      oldDb.update("INSERT INTO uacrf_assessment.incident "
          + "(tenant_id,environment_id,id,workload_id,source_case_id,observed_from,description,"
          + "evidence_refs,reason,revision,created_by) "
          + "VALUES ('legacy','env-1','00000000-0000-0000-0000-000000000001','w1','case-1',"
          + "'2026-01-01T00:00:00Z','incident','[\"receipt\"]'::jsonb,'reason',1,'user')");
      Flyway.configure().dataSource(source).locations(AssessmentTestMigrations.location())
          .target("8").load().migrate();
      oldDb.update("INSERT INTO uacrf_assessment.evaluation "
          + "(tenant_id,environment_id,id,case_id,revision,observation_cutoff,cutoff_epoch_second,"
          + "cutoff_nano,mode,outcome,dependency_vector,result_digest) VALUES "
          + "('legacy','env-1','evaluation-1','case-1',1,'2026-01-01T00:00:00Z',"
          + "1767225600,0,'LIVE','INSUFFICIENT_EVIDENCE','{}'::jsonb,repeat('a',64))");
      oldDb.update("INSERT INTO uacrf_assessment.incident "
          + "(tenant_id,environment_id,id,workload_id,source_case_id,observed_from,description,"
          + "evidence_refs,source_evaluation_ids,reason,revision,created_by) "
          + "VALUES ('legacy','env-1','00000000-0000-0000-0000-000000000002','w1','case-1',"
          + "'2026-01-01T00:00:00Z','incident','[\"receipt\"]'::jsonb,'[\"evaluation-1\"]'::jsonb,"
          + "'reason',1,'user')");
      Flyway.configure().dataSource(source).locations(AssessmentTestMigrations.location()).load().migrate();
      assertEquals(1, oldDb.queryForObject("SELECT count(*) FROM uacrf_assessment.incident "
          + "WHERE tenant_id='legacy' AND source_evaluation_ids IS NULL", Integer.class));
      assertEquals(1, oldDb.queryForObject("SELECT count(*) FROM uacrf_assessment.incident "
          + "WHERE tenant_id='legacy' AND source_evaluation_ids='[\"evaluation-1\"]'::jsonb", Integer.class));
    } finally {
      jdbc.execute("DROP DATABASE " + database);
    }
  }

  @Test void incidentProvenanceRejectsInvalidDatabaseReferences() throws Exception {
    String tenant = "t-incident-fresh-provenance";
    String owner = token(tenant, "*", ALL);
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    String otherCase = sealed(owner, "[\"a\"]", "LTE");
    String cutoff = "2026-01-01T01:00:00Z";
    String evaluation = eval(owner, caseId, cutoff, "LIVE",
        measurement("r-provenance", "a", "2026-01-01T00:59:00Z", "5", "ms")).get("id").asString();
    String wrongCase = eval(owner, otherCase, cutoff, "LIVE",
        measurement("r-wrong-case", "a", "2026-01-01T00:59:00Z", "5", "ms")).get("id").asString();
    String otherOwner = token("t-incident-fresh-provenance-foreign", "*", ALL);
    String foreignCase = sealed(otherOwner, "[\"a\"]", "LTE");
    String foreignTenant = eval(otherOwner, foreignCase, cutoff, "LIVE",
        measurement("r-foreign", "a", "2026-01-01T00:59:00Z", "5", "ms")).get("id").asString();
    String foreignEnvironment = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO uacrf_assessment.analysis_case "
        + "(tenant_id,environment_id,id,workload_id,content,state,revision,case_digest,created_by) "
        + "SELECT tenant_id,'env-2',id,workload_id,content,state,revision,case_digest,created_by "
        + "FROM uacrf_assessment.analysis_case WHERE tenant_id=? AND environment_id='env-1' AND id=?",
        tenant, caseId);
    jdbc.update("INSERT INTO uacrf_assessment.evaluation "
        + "(tenant_id,environment_id,id,case_id,revision,observation_cutoff,cutoff_epoch_second,"
        + "cutoff_nano,mode,outcome,dependency_vector,result_digest) "
        + "SELECT tenant_id,'env-2',?,case_id,revision,observation_cutoff,cutoff_epoch_second,"
        + "cutoff_nano,mode,outcome,dependency_vector,result_digest FROM uacrf_assessment.evaluation "
        + "WHERE tenant_id=? AND environment_id='env-1' AND id=?", foreignEnvironment, tenant, evaluation);
    String incidentId = body(post("/v1/incidents", owner,
        incidentJson(caseId, "w1", cutoff, "[\"r-provenance\"]")), 201).get("id").asString();
    String insert = "INSERT INTO uacrf_assessment.incident "
        + "(tenant_id,environment_id,id,workload_id,source_case_id,observed_from,description,"
        + "evidence_refs,source_evaluation_ids,reason,revision,created_by) "
        + "SELECT tenant_id,environment_id,?,workload_id,source_case_id,observed_from,description,"
        + "evidence_refs,?::jsonb,reason,revision,created_by FROM uacrf_assessment.incident "
        + "WHERE tenant_id=? AND environment_id='env-1' AND id=?";
    List<String> invalid = List.of(
        json.writeValueAsString(List.of(wrongCase)),
        json.writeValueAsString(List.of(foreignTenant)),
        json.writeValueAsString(List.of(foreignEnvironment)),
        json.writeValueAsString(List.of(UUID.randomUUID().toString())),
        json.writeValueAsString(List.of(evaluation, evaluation)),
        "[123]");
    for (String lineage : invalid) {
      assertThrows(DataIntegrityViolationException.class,
          () -> jdbc.update(insert, UUID.randomUUID().toString(), lineage, tenant, incidentId), lineage);
    }
    assertEquals(1, jdbc.update(insert, UUID.randomUUID().toString(),
        json.writeValueAsString(List.of(evaluation)), tenant, incidentId));
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.incident "
        + "WHERE tenant_id=?", Integer.class, tenant));
  }

  @Test void incidentProvenanceMigrationRejectsCorruptExistingLineage() {
    String database = "assessment_bad_lineage_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url = POSTGRES.getJdbcUrl().replace("/uacrf_assessment?", "/" + database + "?");
      var source = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
      Flyway.configure().dataSource(source).locations(AssessmentTestMigrations.location())
          .target("8").load().migrate();
      var oldDb = new JdbcTemplate(source);
      oldDb.update("INSERT INTO uacrf_assessment.analysis_case "
          + "(tenant_id,environment_id,id,workload_id,content,state,revision,created_by) "
          + "VALUES ('legacy','env-1','case-1','w1','{}'::jsonb,'DRAFT',1,'user')");
      oldDb.update("INSERT INTO uacrf_assessment.incident "
          + "(tenant_id,environment_id,id,workload_id,source_case_id,observed_from,description,"
          + "evidence_refs,source_evaluation_ids,reason,revision,created_by) "
          + "VALUES ('legacy','env-1','00000000-0000-0000-0000-000000000001','w1','case-1',"
          + "'2026-01-01T00:00:00Z','incident','[\"receipt\"]'::jsonb,'[\"missing\"]'::jsonb,"
          + "'reason',1,'user')");
      assertThrows(org.flywaydb.core.api.FlywayException.class,
          () -> Flyway.configure().dataSource(source).locations(AssessmentTestMigrations.location()).load().migrate());
      assertEquals(1, oldDb.queryForObject("SELECT count(*) FROM uacrf_assessment.incident "
          + "WHERE source_evaluation_ids='[\"missing\"]'::jsonb", Integer.class));
    } finally {
      jdbc.execute("DROP DATABASE " + database);
    }
  }

  @Test void incidentRequiresVerifiedMeasurementsAtOrBeforeObservationAndIsAttributable() throws Exception {
    String owner = token("t-incident-valid", "*", ALL);
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    eval(owner, caseId, "2026-01-01T01:00:00Z", "LIVE",
        measurement("r-incident", "a", "2026-01-01T00:59:00Z", "5", "ms"));
    String forged = incidentJson(caseId, "w1", "2026-01-01T01:00:00Z", "[\"r-incident\"]")
        .replace("\"reason\":\"retain incident evidence\"}",
            "\"reason\":\"retain incident evidence\",\"capabilities\":[\"assessment.incident.write\"]}");
    String readOnly = token("t-incident-valid", "*", List.of("assessment.incident.read"));
    assertEquals(403, post("/v1/incidents", readOnly,
        incidentJson(caseId, "w1", "2026-01-01T01:00:00Z", "[\"r-incident\"]")).statusCode());
    assertEquals(400, post("/v1/incidents", readOnly, forged).statusCode());
    String incidentWriter = token("t-incident-valid", "*",
        List.of("assessment.incident.write"), "incident-writer");
    JsonNode incident = body(post("/v1/incidents", incidentWriter,
        incidentJson(caseId, "w1", "2026-01-01T01:00:00Z", "[\"r-incident\",\"r-incident\"]")), 201);
    assertEquals(1, incident.get("revision").asInt());
    assertEquals(List.of("r-incident"), json.convertValue(incident.get("evidence_refs"), List.class));
    assertThat(incident.get("created_by").asString()).endsWith("|incident-writer");
    assertEquals(200, get("/v1/incidents/" + incident.get("id").asString(), owner).statusCode());
    assertEquals(404, get("/v1/incidents/" + incident.get("id").asString(),
        token("t-incident-other", "*", ALL)).statusCode());
    assertEquals(403, get("/v1/incidents/" + incident.get("id").asString(),
        token("t-incident-valid", "*", List.of("assessment.incident.write"))).statusCode());
  }

  @Test void keyedIncidentCreationIsAtomicScopedAndReplaysHistoricalReceipt() throws Exception {
    String tenant = "t-incident-fresh-idempotent";
    String writer = token(tenant, "*", List.of("assessment.case.write", "assessment.evaluation.run",
        "assessment.incident.write"));
    String caseId = sealed(writer, "[\"a\"]", "LTE");
    String cutoff = "2026-01-01T01:00:00Z";
    eval(writer, caseId, cutoff, "LIVE", measurement("r-key", "a", "2026-01-01T00:59:00Z", "5", "ms"));
    String path = "/v1/incidents:idempotent-create";
    String input = incidentJson(caseId, "w1", cutoff, "[\"r-key\",\"r-key\"]");
    assertEquals(400, post(path, writer, input).statusCode());
    assertEquals(400, postAdaptation(path, writer, input, "invalid key").statusCode());
    assertEquals(403, postAdaptation(path, token(tenant, "*", List.of("assessment.incident.read")), input, "key").statusCode());
    JsonNode first = body(postAdaptation(path, writer, input, "key"), 201);
    String equivalent = incidentJson(caseId, "w1", "2026-01-01T01:00:00.000Z", "[\"r-key\"]");
    assertEquals(first, body(postAdaptation(path, writer, equivalent, "key"), 201));
    assertEquals(409, postAdaptation(path, writer, input.replace("retain incident evidence", "changed"), "key").statusCode());
    ModelReceiptFixture.UNAVAILABLE.add(tenant);
    try {
      assertEquals(first, body(postAdaptation(path, writer, input, "key"), 201));
      assertEquals(409, postAdaptation(path, writer, input, "unavailable").statusCode());
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.incident_idempotency "
          + "WHERE tenant_id=? AND idempotency_key='unavailable'", Integer.class, tenant));
    } finally {
      ModelReceiptFixture.UNAVAILABLE.remove(tenant);
    }
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var one = executor.submit(() -> { gate.await(); return postAdaptation(path, writer, input, "race"); });
      var two = executor.submit(() -> { gate.await(); return postAdaptation(path, writer, equivalent, "race"); });
      gate.countDown();
      assertEquals(body(one.get(), 201).get("id").asString(), body(two.get(), 201).get("id").asString());
    }
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var one = executor.submit(() -> { gate.await(); return postAdaptation(path, writer, input, "competing"); });
      var two = executor.submit(() -> { gate.await(); return postAdaptation(path, writer,
          input.replace("retain incident evidence", "different reason"), "competing"); });
      gate.countDown();
      assertEquals(List.of(201, 409), java.util.stream.Stream.of(one.get().statusCode(), two.get().statusCode())
          .sorted().toList());
    }
    String otherActor = token(tenant, "*", List.of("assessment.incident.write"), "other-user");
    JsonNode otherResult = body(postAdaptation(path, otherActor, input, "key"), 201);
    assertNotEquals(first.get("id").asString(), otherResult.get("id").asString());
    var actor = new DelegationPrincipal("https://uacrf.local/s01|user", tenant, "env-1", "*",
        "https://idp.example.test", "user", List.of("assessment.incident.write"), List.of(), List.of());
    var request = json.readValue(input, AssessmentRequests.CreateIncident.class);
    new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      incidentLearning.createIncident(actor, request, "rollback");
      status.setRollbackOnly();
    });
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.incident_idempotency "
        + "WHERE tenant_id=? AND idempotency_key='rollback'", Integer.class, tenant));
    assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.incident WHERE tenant_id=?", Integer.class, tenant));
    assertEquals(201, postAdaptation(path, writer, input, "rollback").statusCode());
  }

  @Test void incidentRejectsUnverifiedMissingAndStaleEvaluationEvidence() throws Exception {
    String owner = token("t-incident-invalid", "*", ALL);
    String cutoff = "2026-01-01T01:00:00Z";
    String unverifiedCase = sealed(owner, "[\"a\"]", "LTE");
    seedIncidentEvaluation("t-incident-invalid", unverifiedCase, cutoff, "SKIPPED",
        measurementReceipt("r-unverified", "2026-01-01T00:59:00Z"));
    assertEquals(409, post("/v1/incidents", owner,
        incidentJson(unverifiedCase, "w1", cutoff, "[\"r-unverified\"]")).statusCode());

    String noReceiptCase = sealed(owner, "[\"a\"]", "LTE");
    seedIncidentEvaluation("t-incident-invalid", noReceiptCase, cutoff, "VERIFIED", "[]");
    assertEquals(409, post("/v1/incidents", owner,
        incidentJson(noReceiptCase, "w1", cutoff, "[\"r-missing\"]")).statusCode());

    String futureEvaluationCase = sealed(owner, "[\"a\"]", "LTE");
    seedIncidentEvaluation("t-incident-invalid", futureEvaluationCase, "2026-01-01T01:01:00Z",
        "VERIFIED", measurementReceipt("r-future", "2026-01-01T01:00:30Z"));
    assertEquals(409, post("/v1/incidents", owner,
        incidentJson(futureEvaluationCase, "w1", cutoff, "[\"r-future\"]")).statusCode());

    String futureReceiptCase = sealed(owner, "[\"a\"]", "LTE");
    seedIncidentEvaluation("t-incident-invalid", futureReceiptCase, "2026-01-01T00:59:00Z",
        "VERIFIED", measurementReceipt("r-late", "2026-01-01T01:00:30Z"));
    assertEquals(409, post("/v1/incidents", owner,
        incidentJson(futureReceiptCase, "w1", cutoff, "[\"r-late\"]")).statusCode());
  }

  @Test void incidentRejectsCrossTenantWrongWorkloadAndUnreferencedMeasurements() throws Exception {
    String owner = token("t-incident-scope", "*", ALL);
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    seedIncidentEvaluation("t-incident-scope", caseId, "2026-01-01T01:00:00Z", "VERIFIED",
        measurementReceipt("r-scoped", "2026-01-01T00:59:00Z"));
    assertEquals(404, post("/v1/incidents", token("t-incident-foreign", "*", ALL),
        incidentJson(caseId, "w1", "2026-01-01T01:00:00Z", "[\"r-scoped\"]")).statusCode());
    assertEquals(404, post("/v1/incidents", owner,
        incidentJson(caseId, "w-other", "2026-01-01T01:00:00Z", "[\"r-scoped\"]")).statusCode());
    assertEquals(409, post("/v1/incidents", owner,
        incidentJson(caseId, "w1", "2026-01-01T01:00:00Z", "[\"r-other\"]")).statusCode());
  }

  @Test void keyedLearningProposalReplaysWithoutDuplicateEventsAndRollsBack() throws Exception {
    String tenant = "t-learning-keyed";
    String owner = token(tenant, "*", ALL);
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    String cutoff = "2026-01-01T01:00:00Z";
    eval(owner, caseId, cutoff, "LIVE", measurement("r-learning-key", "a", "2026-01-01T00:59:00Z", "5", "ms"));
    String incidentId = body(post("/v1/incidents", owner,
        incidentJson(caseId, "w1", cutoff, "[\"r-learning-key\"]")), 201).get("id").asString();
    String writer = token(tenant, "*", List.of("assessment.learning.write"));
    String input = learningJson(incidentId, 1, "[\"r-learning-key\",\"r-learning-key\"]");
    String equivalent = learningJson(incidentId, 1, "[\"r-learning-key\"]");
    String path = "/v1/learning-records:idempotent-propose";
    assertEquals(400, post(path, writer, input).statusCode());
    assertEquals(400, postAdaptation(path, writer, input, "invalid key").statusCode());
    assertEquals(403, postAdaptation(path, token(tenant, "*", List.of("assessment.learning.read")), input, "key").statusCode());
    JsonNode first = body(postAdaptation(path, writer, input, "key"), 201);
    assertEquals(first, body(postAdaptation(path, writer, equivalent, "key"), 201));
    assertEquals(409, postAdaptation(path, writer, input.replace("owner-1", "other"), "key").statusCode());
    assertEquals(409, postAdaptation(path, writer, learningJson(incidentId, 2, "[\"r-learning-key\"]"), "failed").statusCode());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.learning_proposal_idempotency "
        + "WHERE tenant_id=? AND idempotency_key='failed'", Integer.class, tenant));
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var one = executor.submit(() -> { gate.await(); return postAdaptation(path, writer, input, "race"); });
      var two = executor.submit(() -> { gate.await(); return postAdaptation(path, writer, equivalent, "race"); });
      gate.countDown();
      assertEquals(body(one.get(), 201).get("id").asString(), body(two.get(), 201).get("id").asString());
    }
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var one = executor.submit(() -> { gate.await(); return postAdaptation(path, writer, input, "competing"); });
      var two = executor.submit(() -> { gate.await(); return postAdaptation(path, writer, input.replace("owner-1", "other"), "competing"); });
      gate.countDown();
      assertEquals(List.of(201, 409), java.util.stream.Stream.of(one.get().statusCode(), two.get().statusCode()).sorted().toList());
    }
    JsonNode other = body(postAdaptation(path, token(tenant, "*", List.of("assessment.learning.write"), "other"),
        input, "key"), 201);
    assertNotEquals(first.get("id").asString(), other.get("id").asString());
    var actor = new DelegationPrincipal("https://uacrf.local/s01|user", tenant, "env-1", "*",
        "https://idp.example.test", "user", List.of("assessment.learning.write"), List.of(), List.of());
    var request = json.readValue(input, AssessmentRequests.ProposeLearningRecord.class);
    new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      incidentLearning.proposeLearningRecord(actor, request, "rollback");
      status.setRollbackOnly();
    });
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.learning_proposal_idempotency "
        + "WHERE tenant_id=? AND idempotency_key='rollback'", Integer.class, tenant));
    assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.learning_record WHERE tenant_id=?", Integer.class, tenant));
    assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.outbox_event WHERE tenant_id=? "
        + "AND event_type='uacrf.s07.learning.proposed.v1'", Integer.class, tenant));
    assertEquals(201, postAdaptation(path, writer, input, "rollback").statusCode());
  }

  @Test void learningRecordsAreImmutableRevisionFencedProposalsWithTransactionalOutbox() throws Exception {
    String owner = token("t-learning-proposal", "*", ALL);
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    eval(owner, caseId, "2026-01-01T01:00:00Z", "LIVE",
        measurement("r-learning", "a", "2026-01-01T00:59:00Z", "5", "ms"));
    JsonNode incident = body(post("/v1/incidents", owner,
        incidentJson(caseId, "w1", "2026-01-01T01:00:00Z", "[\"r-learning\"]")), 201);
    String incidentId = incident.get("id").asString();
    String learningWriter = token("t-learning-proposal", "*",
        List.of("assessment.learning.write"), "learning-writer");
    String recordBody = learningJson(incidentId, 1, "[\"r-learning\",\"r-learning\"]");
    assertEquals(409, post("/v1/learning-records", learningWriter,
        learningJson(incidentId, 2, "[\"r-learning\"]")).statusCode());
    assertEquals(409, post("/v1/learning-records", learningWriter,
        learningJson(incidentId, 1, "[\"r-not-retained\"]")).statusCode());
    assertEquals(404, post("/v1/learning-records", token("t-learning-foreign", "*", ALL),
        learningJson(incidentId, 1, "[\"r-learning\"]")).statusCode());
    assertEquals(400, post("/v1/learning-records", learningWriter, recordBody.replace(
        "\"owner_id\":\"owner-1\"", "\"owner_id\":\"\"")).statusCode());

    int modelCount = jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.model_version "
        + "WHERE tenant_id='t-learning-proposal'", Integer.class);
    JsonNode proposed = body(post("/v1/learning-records", learningWriter, recordBody), 201);
    String learningPath = "/v1/learning-records/" + proposed.get("id").asString();
    String reader = token("t-learning-proposal", "*", List.of("assessment.learning.read"));
    assertEquals(proposed, body(get(learningPath, reader), 200));
    assertEquals(403, get(learningPath, learningWriter).statusCode());
    assertEquals(403, get(learningPath,
        token("t-learning-proposal", "w1", List.of("assessment.learning.read"))).statusCode());
    assertEquals(404, get(learningPath,
        token("t-learning-foreign", "*", List.of("assessment.learning.read"))).statusCode());
    assertEquals(404, get("/v1/learning-records/" + UUID.randomUUID(), reader).statusCode());
    DelegationPrincipal otherEnvironment = new DelegationPrincipal(
        "https://uacrf.local/s01|reader", "t-learning-proposal", "env-2", "*",
        "https://idp.example.test", "reader", List.of("assessment.learning.read"), List.of(), List.of());
    String envToken = "Bearer " + new DelegationTokenIssuer(ENCODER, Clock.systemUTC(),
        "https://uacrf.local/s01", KEY_ID).issue(otherEnvironment, "S07");
    assertEquals(404, get(learningPath, envToken).statusCode());
    assertEquals("PROPOSED", proposed.get("state").asString());
    assertEquals(1, proposed.get("revision").asInt());
    assertEquals(1, proposed.get("expected_revision").asInt());
    assertThat(proposed.get("proposed_by").asString()).endsWith("|learning-writer");
    assertEquals(List.of("r-learning"), json.convertValue(proposed.get("evidence_refs"), List.class));
    assertThat(proposed.toString()).contains("owner-1", "closure_test");
    assertEquals(modelCount, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.model_version "
        + "WHERE tenant_id='t-learning-proposal'", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.outbox_event "
        + "WHERE tenant_id='t-learning-proposal' AND event_type='uacrf.s07.learning.proposed.v1' "
        + "AND aggregate_id=?", Integer.class, proposed.get("id").asString()));
    assertEquals("https://uacrf.local/s01|learning-writer", jdbc.queryForObject(
        "SELECT payload->>'actor_id' FROM uacrf_assessment.outbox_event "
            + "WHERE tenant_id='t-learning-proposal' AND aggregate_id=?",
        String.class, proposed.get("id").asString()));
    assertEquals("PROPOSED", jdbc.queryForObject(
        "SELECT payload->'minimal_outcome_metadata'->>'state' "
            + "FROM uacrf_assessment.outbox_event WHERE tenant_id='t-learning-proposal' "
            + "AND aggregate_id=?",
        String.class, proposed.get("id").asString()));
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "UPDATE uacrf_assessment.learning_record SET state='APPROVED' WHERE tenant_id=? AND id=?",
        "t-learning-proposal", proposed.get("id").asString()));
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "DELETE FROM uacrf_assessment.learning_record WHERE tenant_id=? AND id=?",
        "t-learning-proposal", proposed.get("id").asString()));
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "UPDATE uacrf_assessment.incident SET reason='changed' WHERE tenant_id=? AND id=?",
        "t-learning-proposal", incidentId));
  }

  @Test void learningReviewsRequireIndependentFreshEvidenceAndDoNotAdoptChanges() throws Exception {
    String tenant = "t-learning-review";
    String owner = token(tenant, "*", ALL);
    String reviewer = token(tenant, "*", List.of("assessment.learning.review"), "reviewer");
    String reader = token(tenant, "*", List.of("assessment.learning.read"), "reader");
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    String cutoff = "2026-01-01T01:00:00Z";
    eval(owner, caseId, cutoff, "LIVE",
        measurement("r-review", "a", "2026-01-01T00:59:00Z", "5", "ms"));
    String incidentId = body(post("/v1/incidents", owner,
        incidentJson(caseId, "w1", cutoff, "[\"r-review\"]")), 201).get("id").asString();
    String recordId = body(post("/v1/learning-records", owner,
        learningJson(incidentId, 1, "[\"r-review\"]")), 201).get("id").asString();
    String reviewPath = "/v1/learning-records/" + recordId + ":review";
    String readPath = "/v1/learning-records/" + recordId + "/review";
    String approve = "{\"expected_revision\":1,\"decision\":\"APPROVE\",\"reason\":\"reviewed\"}";
    assertEquals(403, post(reviewPath, owner, approve).statusCode());
    assertEquals(409, post(reviewPath, token(tenant, "*",
        List.of("assessment.learning.review"), "user"), approve).statusCode());
    assertEquals(403, post(reviewPath, token(tenant, "w1",
        List.of("assessment.learning.review"), "reviewer"), approve).statusCode());
    assertEquals(404, post(reviewPath, token("t-learning-review-foreign", "*",
        List.of("assessment.learning.review"), "reviewer"), approve).statusCode());
    assertEquals(409, post(reviewPath, reviewer, approve.replace("\"expected_revision\":1",
        "\"expected_revision\":2")).statusCode());
    try {
      ModelReceiptFixture.UNAVAILABLE.add(tenant);
      assertEquals("OWNER_REVALIDATION_FAILED",
          body(post(reviewPath, reviewer, approve), 409).get("code").asString());
      assertEquals(404, get(readPath, reader).statusCode());
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.outbox_event "
          + "WHERE tenant_id=? AND event_type='uacrf.s07.learning.reviewed.v1'", Integer.class, tenant));
    } finally {
      ModelReceiptFixture.UNAVAILABLE.remove(tenant);
    }
    JsonNode review = body(post(reviewPath, reviewer, approve), 200);
    assertEquals("APPROVE", review.get("decision").asString());
    assertEquals(review, body(get(readPath, reader), 200));
    String receiptPath = "/internal/v1/learning-records/" + recordId + ":review-receipt";
    JsonNode receipt = body(post(receiptPath, reader, "{}"), 200);
    assertEquals(recordId, receipt.get("learning_record_id").asString());
    assertEquals(incidentId, receipt.get("incident_id").asString());
    assertEquals("VERIFIED", receipt.get("owner_receipts").asString());
    assertFalse(receipt.get("authorizes_adoption").asBoolean());
    assertEquals(jdbc.queryForObject("SELECT content_digest FROM uacrf_assessment.learning_record "
        + "WHERE tenant_id=? AND id=?", String.class, tenant, recordId),
        receipt.get("learning_record_digest").asString());
    assertEquals(jdbc.queryForObject("SELECT payload->>'owned_digest' "
        + "FROM uacrf_assessment.outbox_event WHERE tenant_id=? AND aggregate_id=? "
        + "AND event_type='uacrf.s07.learning.reviewed.v1'", String.class, tenant, recordId),
        receipt.get("review_digest").asString());
    assertEquals(403, post(receiptPath, reviewer, "{}").statusCode());
    try {
      ModelReceiptFixture.UNAVAILABLE.add(tenant);
      assertEquals("OWNER_REVALIDATION_FAILED",
          body(post(receiptPath, reader, "{}"), 409).get("code").asString());
      assertEquals(review, body(get(readPath, reader), 200));
    } finally {
      ModelReceiptFixture.UNAVAILABLE.remove(tenant);
    }
    assertEquals(403, get(readPath, reviewer).statusCode());
    assertEquals(404, get(readPath, token("t-learning-review-foreign", "*",
        List.of("assessment.learning.read"), "reader")).statusCode());
    DelegationPrincipal foreignEnvironment = new DelegationPrincipal(
        "https://uacrf.local/s01|reader", tenant, "env-2", "*", "https://idp.example.test", "reader",
        List.of("assessment.learning.read", "assessment.learning.review"), List.of(), List.of());
    String envToken = "Bearer " + new DelegationTokenIssuer(ENCODER, Clock.systemUTC(),
        "https://uacrf.local/s01", KEY_ID).issue(foreignEnvironment, "S07");
    assertEquals(404, get(readPath, envToken).statusCode());
    assertEquals(404, post(reviewPath, envToken, approve).statusCode());
    assertEquals(404, post(receiptPath, envToken, "{}").statusCode());
    assertEquals(409, post(reviewPath, reviewer, approve).statusCode());
    assertEquals("PROPOSED", body(get("/v1/learning-records/" + recordId, reader), 200)
        .get("state").asString());
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.outbox_event "
        + "WHERE tenant_id=? AND event_type='uacrf.s07.learning.reviewed.v1'", Integer.class, tenant));
    assertEquals("APPROVE", jdbc.queryForObject("SELECT payload->'minimal_outcome_metadata'->>'decision' "
        + "FROM uacrf_assessment.outbox_event WHERE tenant_id=? "
        + "AND event_type='uacrf.s07.learning.reviewed.v1'", String.class, tenant));
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "DELETE FROM uacrf_assessment.learning_review WHERE tenant_id=? AND learning_record_id=?",
        tenant, recordId));
    String forgedIncident = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO uacrf_assessment.incident "
        + "(tenant_id,environment_id,id,workload_id,source_case_id,observed_from,description,"
        + "evidence_refs,source_evaluation_ids,reason,revision,created_by) "
        + "SELECT tenant_id,environment_id,?,workload_id,source_case_id,observed_from,description,"
        + "'[\"r-forged\"]'::jsonb,source_evaluation_ids,reason,revision,created_by "
        + "FROM uacrf_assessment.incident WHERE tenant_id=? AND id=?",
        forgedIncident, tenant, incidentId);
    String forgedRecord = body(post("/v1/learning-records", owner,
        learningJson(forgedIncident, 1, "[\"r-forged\"]")), 201).get("id").asString();
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "INSERT INTO uacrf_assessment.learning_review "
            + "(tenant_id,environment_id,learning_record_id,expected_revision,decision,reason,reviewed_by) "
            + "VALUES (?,'env-1',?,1,'REJECT','review','https://uacrf.local/s01|user')",
        tenant, forgedRecord));
    assertEquals("INCIDENT_PROVENANCE_INVALID",
        body(post("/v1/learning-records/" + forgedRecord + ":review", reviewer, approve), 409)
            .get("code").asString());
    jdbc.update("UPDATE uacrf_assessment.outbox_event SET payload=jsonb_set(payload,"
        + "'{owned_digest}',to_jsonb(?::text)) WHERE tenant_id=? AND aggregate_id=? "
        + "AND event_type='uacrf.s07.learning.reviewed.v1'", "0".repeat(64), tenant, recordId);
    assertEquals("REVIEW_INTEGRITY_MISMATCH",
        body(post(receiptPath, reader, "{}"), 409).get("code").asString());
  }

  @Test void learningApprovalAndReceiptRequireIntactProposalAndEvent() throws Exception {
    String tenant = "t-learning-integrity";
    String owner = token(tenant, "*", ALL);
    String reviewer = token(tenant, "*", List.of("assessment.learning.review"), "reviewer");
    String reader = token(tenant, "*", List.of("assessment.learning.read"), "reader");
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    String cutoff = "2026-01-01T01:00:00Z";
    eval(owner, caseId, cutoff, "LIVE",
        measurement("r-integrity", "a", "2026-01-01T00:59:00Z", "5", "ms"));
    String incidentId = body(post("/v1/incidents", owner,
        incidentJson(caseId, "w1", cutoff, "[\"r-integrity\"]")), 201).get("id").asString();
    String recordId = body(post("/v1/learning-records", owner,
        learningJson(incidentId, 1, "[\"r-integrity\"]")
            .replace("2026-02-01T00:00:00Z", "2026-02-01T00:00:00.123456789Z")
            .replace("\"proposed_version_ref\":\"candidate://v2\"", "\"proposed_version_ref\":null")),
        201).get("id").asString();
    String approve = "{\"expected_revision\":1,\"decision\":\"APPROVE\",\"reason\":\"reviewed\"}";
    String reviewPath = "/v1/learning-records/" + recordId + ":review";
    String receiptPath = "/internal/v1/learning-records/" + recordId + ":review-receipt";
    String digest = jdbc.queryForObject("SELECT payload->>'owned_digest' FROM uacrf_assessment.outbox_event "
        + "WHERE tenant_id=? AND aggregate_id=? AND event_type='uacrf.s07.learning.proposed.v1'",
        String.class, tenant, recordId);

    jdbc.update("UPDATE uacrf_assessment.outbox_event SET payload=jsonb_set(payload,"
        + "'{owned_digest}',to_jsonb(?::text)) WHERE tenant_id=? AND aggregate_id=? "
        + "AND event_type='uacrf.s07.learning.proposed.v1'", "0".repeat(64), tenant, recordId);
    assertEquals("LEARNING_INTEGRITY_MISMATCH",
        body(post(reviewPath, reviewer, approve), 409).get("code").asString());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.learning_review "
        + "WHERE tenant_id=? AND learning_record_id=?", Integer.class, tenant, recordId));
    jdbc.update("UPDATE uacrf_assessment.outbox_event SET payload=jsonb_set(payload,"
        + "'{owned_digest}',to_jsonb(?::text)) WHERE tenant_id=? AND aggregate_id=? "
        + "AND event_type='uacrf.s07.learning.proposed.v1'", digest, tenant, recordId);

    for (boolean changeContent : List.of(true, false)) {
      String forgedId = UUID.randomUUID().toString();
      jdbc.update("INSERT INTO uacrf_assessment.learning_record "
              + "(tenant_id,environment_id,id,incident_id,content,evidence_refs,content_digest,"
              + "state,revision,proposed_by) SELECT tenant_id,environment_id,?,incident_id,"
              + (changeContent ? "jsonb_set(content,'{hypotheses,0}', '\"changed\"'::jsonb)" : "content")
              + "," + (changeContent ? "evidence_refs" : "'[\"r-other\"]'::jsonb")
              + ",content_digest,state,revision,proposed_by FROM uacrf_assessment.learning_record "
              + "WHERE tenant_id=? AND id=?", forgedId, tenant, recordId);
      jdbc.update("INSERT INTO uacrf_assessment.outbox_event "
          + "(event_id,tenant_id,environment_id,aggregate_type,aggregate_id,aggregate_sequence,"
          + "event_type,payload,payload_digest) SELECT ?,tenant_id,environment_id,aggregate_type,?,"
          + "aggregate_sequence,event_type,payload,payload_digest FROM uacrf_assessment.outbox_event "
          + "WHERE tenant_id=? AND aggregate_id=? AND event_type='uacrf.s07.learning.proposed.v1'",
          UUID.randomUUID(), forgedId, tenant, recordId);
      assertEquals("LEARNING_INTEGRITY_MISMATCH",
          body(post("/v1/learning-records/" + forgedId + ":review", reviewer, approve), 409)
              .get("code").asString());
      if (changeContent) {
        JsonNode rejected = body(post("/v1/learning-records/" + forgedId + ":review", reviewer,
            approve.replace("APPROVE", "REJECT")), 200);
        assertEquals("REJECT", rejected.get("decision").asString());
      }
    }

    body(post(reviewPath, reviewer, approve), 200);
    body(post(receiptPath, reader, "{}"), 200);
    jdbc.update("DELETE FROM uacrf_assessment.outbox_event WHERE tenant_id=? AND aggregate_id=? "
        + "AND event_type='uacrf.s07.learning.proposed.v1'", tenant, recordId);
    assertEquals("LEARNING_INTEGRITY_MISMATCH",
        body(post(receiptPath, reader, "{}"), 409).get("code").asString());
    assertEquals("APPROVE", body(get("/v1/learning-records/" + recordId + "/review", reader), 200)
        .get("decision").asString());
  }

  @Test void learningReviewRechecksIncidentBindingEvenWhenEvaluationsCoverTheEvidence() throws Exception {
    String tenant = "t-learning-binding";
    String owner = token(tenant, "*", ALL);
    String reviewer = token(tenant, "*", List.of("assessment.learning.review"), "reviewer");
    String reader = token(tenant, "*", List.of("assessment.learning.read"), "reader");
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    String cutoff = "2026-01-01T01:00:00Z";
    JsonNode evaluation = eval(owner, caseId, cutoff, "LIVE",
        measurement("r-binding", "a", "2026-01-01T00:58:00Z", "5", "ms") + ","
            + measurement("r-extra", "a", "2026-01-01T00:59:00Z", "5", "ms"));
    assertThat(evaluation.get("dependency_vector").get("measurement_receipts").toString())
        .contains("r-binding", "r-extra");
    String incidentId = body(post("/v1/incidents", owner,
        incidentJson(caseId, "w1", cutoff, "[\"r-binding\"]")), 201).get("id").asString();
    String originalId = body(post("/v1/learning-records", owner,
        learningJson(incidentId, 1, "[\"r-binding\"]")), 201).get("id").asString();
    String approve = "{\"expected_revision\":1,\"decision\":\"APPROVE\",\"reason\":\"reviewed\"}";
    JsonNode originalReview = body(post("/v1/learning-records/" + originalId + ":review", reviewer, approve), 200);
    AssessmentResponses.LearningReview review =
        json.treeToValue(originalReview, AssessmentResponses.LearningReview.class);
    body(post("/internal/v1/learning-records/" + originalId + ":review-receipt", reader, "{}"), 200);

    for (boolean wrongEvidence : List.of(true, false)) {
      String forgedId = UUID.randomUUID().toString();
      String evidence = wrongEvidence ? "[\"r-extra\"]" : "[\"r-binding\"]";
      var content = json.readValue(learningJson(incidentId, wrongEvidence ? 1 : 2, evidence),
          AssessmentRequests.ProposeLearningRecord.class);
      String serialized = json.writeValueAsString(content);
      String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(serialized.getBytes(StandardCharsets.UTF_8)));
      jdbc.update("INSERT INTO uacrf_assessment.learning_record "
              + "(tenant_id,environment_id,id,incident_id,content,evidence_refs,content_digest,"
              + "state,revision,proposed_by) SELECT tenant_id,environment_id,?,incident_id,?::jsonb,"
              + "?::jsonb,?,state,revision,proposed_by FROM uacrf_assessment.learning_record "
              + "WHERE tenant_id=? AND id=?",
          forgedId, serialized, evidence, digest, tenant, originalId);
      jdbc.update("INSERT INTO uacrf_assessment.outbox_event "
              + "(event_id,tenant_id,environment_id,aggregate_type,aggregate_id,aggregate_sequence,"
              + "event_type,payload,payload_digest) SELECT ?,tenant_id,environment_id,aggregate_type,?,"
              + "aggregate_sequence,event_type,jsonb_set(jsonb_set(payload,'{owned_resource_id}',"
              + "to_jsonb(?::text)),'{owned_digest}',to_jsonb(?::text)),payload_digest "
              + "FROM uacrf_assessment.outbox_event WHERE tenant_id=? AND aggregate_id=? "
              + "AND event_type='uacrf.s07.learning.proposed.v1'",
          UUID.randomUUID(), forgedId, forgedId, digest, tenant, originalId);
      assertEquals("INCIDENT_PROVENANCE_INVALID",
          body(post("/v1/learning-records/" + forgedId + ":review", reviewer, approve), 409)
              .get("code").asString());

      jdbc.update("INSERT INTO uacrf_assessment.learning_review "
              + "(tenant_id,environment_id,learning_record_id,expected_revision,decision,reason,reviewed_by,reviewed_at) "
              + "SELECT tenant_id,environment_id,?,expected_revision,decision,reason,reviewed_by,reviewed_at "
              + "FROM uacrf_assessment.learning_review WHERE tenant_id=? AND learning_record_id=?",
          forgedId, tenant, originalId);
      var copiedReview = new AssessmentResponses.LearningReview(forgedId, review.expectedRevision(),
          review.decision(), review.reason(), review.reviewedBy(), review.reviewedAt());
      String reviewDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(json.writeValueAsString(copiedReview).getBytes(StandardCharsets.UTF_8)));
      jdbc.update("INSERT INTO uacrf_assessment.outbox_event "
              + "(event_id,tenant_id,environment_id,aggregate_type,aggregate_id,aggregate_sequence,"
              + "event_type,payload,payload_digest) SELECT ?,tenant_id,environment_id,aggregate_type,?,"
              + "aggregate_sequence,event_type,jsonb_set(jsonb_set(payload,'{owned_resource_id}',"
              + "to_jsonb(?::text)),'{owned_digest}',to_jsonb(?::text)),payload_digest "
              + "FROM uacrf_assessment.outbox_event WHERE tenant_id=? AND aggregate_id=? "
              + "AND event_type='uacrf.s07.learning.reviewed.v1'",
          UUID.randomUUID(), forgedId, forgedId, reviewDigest, tenant, originalId);
      assertEquals("INCIDENT_PROVENANCE_INVALID",
          body(post("/internal/v1/learning-records/" + forgedId + ":review-receipt", reader, "{}"), 409)
              .get("code").asString());
      assertEquals("APPROVE",
          body(get("/v1/learning-records/" + forgedId + "/review", reader), 200).get("decision").asString());
    }
  }

  @Test void adaptationDraftRequiresCurrentReviewedLearningAndNeverAppliesChanges() throws Exception {
    String tenant = "t-learning-adaptation";
    String owner = token(tenant, "*", ALL);
    String reviewer = token(tenant, "*", List.of("assessment.learning.review"), "reviewer");
    String reader = token(tenant, "*", List.of("assessment.learning.read"), "reader");
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    String cutoff = "2026-01-01T01:00:00Z";
    eval(owner, caseId, cutoff, "LIVE",
        measurement("r-adaptation", "a", "2026-01-01T00:59:00Z", "5", "ms"));
    String incidentId = body(post("/v1/incidents", owner,
        incidentJson(caseId, "w1", cutoff, "[\"r-adaptation\"]")), 201).get("id").asString();
    String learningId = body(post("/v1/learning-records", owner,
        learningJson(incidentId, 1, "[\"r-adaptation\"]")), 201).get("id").asString();
    String input = "{\"learning_record_id\":\"" + learningId + "\",\"target_type\":\"OBJECTIVE\","
        + "\"target_owner\":\"S05\",\"controlled_version_ref\":\"objective:1\","
        + "\"reason\":\"investigate\",\"required_tests\":[\"replay\"],"
        + "\"proposed_reviewer\":\"reviewer-2\",\"rollback_criteria\":\"restore prior version\"}";
    String path = "/internal/v1/adaptation-proposals";
    assertEquals(400, post(path, owner, input).statusCode());
    assertEquals(400, postAdaptation(path, owner, input, "invalid key").statusCode());
    assertEquals(400, postAdaptation(path, owner, input, "a".repeat(201)).statusCode());
    assertEquals(403, postAdaptation(path, reader, input, "key-1").statusCode());
    assertEquals(403, postAdaptation(path, token(tenant, "*", List.of("assessment.learning.write")),
        input, "key-1").statusCode());
    assertEquals(403, postAdaptation(path, token(tenant, "w1", ALL), input, "key-1").statusCode());
    assertEquals(400, postAdaptation(path, owner, input.replace("[\"replay\"]", "[]"), "key-1").statusCode());
    assertEquals(404, postAdaptation(path, owner, input, "key-1").statusCode());
    String rejectedId = body(post("/v1/learning-records", owner,
        learningJson(incidentId, 1, "[\"r-adaptation\"]")), 201).get("id").asString();
    body(post("/v1/learning-records/" + rejectedId + ":review", reviewer,
        "{\"expected_revision\":1,\"decision\":\"REJECT\",\"reason\":\"not accepted\"}"), 200);
    assertEquals("LEARNING_REVIEW_NOT_APPROVED",
        body(postAdaptation(path, owner, input.replace(learningId, rejectedId), "key-1"), 409).get("code").asString());
    body(post("/v1/learning-records/" + learningId + ":review", reviewer,
        "{\"expected_revision\":1,\"decision\":\"APPROVE\",\"reason\":\"reviewed\"}"), 200);
    try {
      ModelReceiptFixture.UNAVAILABLE.add(tenant);
      assertEquals("OWNER_REVALIDATION_FAILED",
          body(postAdaptation(path, owner, input, "key-1"), 409).get("code").asString());
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.adaptation_proposal "
          + "WHERE tenant_id=?", Integer.class, tenant));
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.adaptation_idempotency "
          + "WHERE tenant_id=?", Integer.class, tenant));
    } finally {
      ModelReceiptFixture.UNAVAILABLE.remove(tenant);
    }
    JsonNode proposal = body(postAdaptation(path, owner, input, "key-1"), 201);
    String id = proposal.get("id").asString();
    assertEquals(proposal, body(postAdaptation(path, owner, input, "key-1"), 201));
    assertEquals("IDEMPOTENCY_CONFLICT",
        body(postAdaptation(path, owner, input.replace("investigate", "changed"), "key-1"), 409)
            .get("code").asString());
    assertEquals("DRAFT_UNVERIFIED", proposal.get("state").asString());
    assertEquals("OBJECTIVE", proposal.get("content").get("target_type").asString());
    assertEquals(learningId, proposal.get("content").get("learning_record_id").asString());
    assertEquals(proposal, body(get(path + "/" + id, reader), 200));
    String secondId;
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var firstRequest = executor.submit(() -> {
        gate.await();
        return postAdaptation(path, owner, input, "race-key");
      });
      var secondRequest = executor.submit(() -> {
        gate.await();
        return postAdaptation(path, owner, input, "race-key");
      });
      gate.countDown();
      JsonNode firstResult = body(firstRequest.get(), 201);
      assertEquals(firstResult, body(secondRequest.get(), 201));
      secondId = firstResult.get("id").asString();
    }
    String listing = path + "?learning_record_id=" + learningId + "&limit=1";
    JsonNode first = body(get(listing, reader), 200);
    assertEquals(1, first.get("items").size());
    String firstId = first.get("items").get(0).get("id").asString();
    JsonNode second = body(get(listing + "&after=" + first.get("next_cursor").asString(), reader), 200);
    assertEquals(1, second.get("items").size());
    assertThat(List.of(firstId, second.get("items").get(0).get("id").asString()))
        .containsExactlyInAnyOrder(id, secondId);
    assertTrue(second.get("next_cursor").isNull());
    assertEquals(400, get(path + "?learning_record_id=" + learningId + "&limit=0", reader).statusCode());
    assertEquals(404, get(listing, token("t-learning-other", "*", ALL)).statusCode());
    assertEquals(403, get(path + "/" + id, token(tenant, "w1", ALL)).statusCode());
    assertEquals(404, get(path + "/" + id, token("t-learning-other", "*", ALL)).statusCode());
    DelegationPrincipal foreignEnvironment = new DelegationPrincipal(
        "https://uacrf.local/s01|reader", tenant, "env-2", "*", "https://idp.example.test", "reader",
        List.of("assessment.learning.read"), List.of(), List.of());
    String envToken = "Bearer " + new DelegationTokenIssuer(ENCODER, Clock.systemUTC(),
        "https://uacrf.local/s01", KEY_ID).issue(foreignEnvironment, "S07");
    assertEquals(404, get(path + "/" + id, envToken).statusCode());
    DelegationPrincipal foreignEnvironmentWriter = new DelegationPrincipal(
        "https://uacrf.local/s01|user", tenant, "env-2", "*", "https://idp.example.test", "user",
        List.of("assessment.learning.read", "assessment.learning.write"), List.of(), List.of());
    String envWriterToken = "Bearer " + new DelegationTokenIssuer(ENCODER, Clock.systemUTC(),
        "https://uacrf.local/s01", KEY_ID).issue(foreignEnvironmentWriter, "S07");
    assertEquals(404, postAdaptation(path, envWriterToken, input, "key-1").statusCode());
    JsonNode otherActor = body(postAdaptation(path, token(tenant, "*", ALL, "other-user"),
        input, "key-1"), 201);
    assertNotEquals(id, otherActor.get("id").asString());
    assertThat(otherActor.get("created_by").asString()).endsWith("|other-user");
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.outbox_event "
        + "WHERE tenant_id=? AND aggregate_id=? AND event_type='uacrf.s07.adaptation.proposed.v1'",
        Integer.class, tenant, id));
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "UPDATE uacrf_assessment.adaptation_proposal SET state='DRAFT_UNVERIFIED' WHERE tenant_id=? AND id=?",
        tenant, id));
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "DELETE FROM uacrf_assessment.adaptation_proposal WHERE tenant_id=? AND id=?", tenant, id));
    assertEquals("PROPOSED", body(get("/v1/learning-records/" + learningId, reader), 200).get("state").asString());
    try {
      ModelReceiptFixture.UNAVAILABLE.add(tenant);
      assertEquals(proposal, body(get(path + "/" + id, reader), 200));
      assertEquals(proposal, body(postAdaptation(path, owner, input, "key-1"), 201));
      assertEquals("OWNER_REVALIDATION_FAILED",
          body(postAdaptation(path, owner, input, "new-key"), 409).get("code").asString());
    } finally {
      ModelReceiptFixture.UNAVAILABLE.remove(tenant);
    }
  }

  @Test void competingLearningReviewsProduceOnlyOneDecisionAndEvent() throws Exception {
    String tenant = "t-learning-review-race";
    String owner = token(tenant, "*", ALL);
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    String incidentId = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO uacrf_assessment.incident "
        + "(tenant_id,environment_id,id,workload_id,source_case_id,observed_from,description,"
        + "evidence_refs,reason,revision,created_by) VALUES (?,'env-1',?,'w1',?,"
        + "'2026-01-01T01:00:00Z','legacy','[\"receipt\"]'::jsonb,'reason',1,'user')",
        tenant, incidentId, caseId);
    String recordId = body(post("/v1/learning-records", owner,
        learningJson(incidentId, 1, "[\"receipt\"]")), 201).get("id").asString();
    String path = "/v1/learning-records/" + recordId + ":review";
    String request = "{\"expected_revision\":1,\"decision\":\"REJECT\",\"reason\":\"reviewed\"}";
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var first = executor.submit(() -> {
        gate.await();
        return post(path, token(tenant, "*", List.of("assessment.learning.review"), "reviewer-1"), request);
      });
      var second = executor.submit(() -> {
        gate.await();
        return post(path, token(tenant, "*", List.of("assessment.learning.review"), "reviewer-2"), request);
      });
      gate.countDown();
      assertEquals(Set.of(200, 409), Set.of(first.get().statusCode(), second.get().statusCode()));
    }
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.learning_review "
        + "WHERE tenant_id=? AND learning_record_id=?", Integer.class, tenant, recordId));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM uacrf_assessment.outbox_event "
        + "WHERE tenant_id=? AND aggregate_id=? AND event_type='uacrf.s07.learning.reviewed.v1'",
        Integer.class, tenant, recordId));
  }

  @Test void learningApprovalPreservesNanosecondIncidentCutoff() throws Exception {
    String tenant = "t-learning-review-precision";
    String owner = token(tenant, "*", ALL);
    String reviewer = token(tenant, "*", List.of("assessment.learning.review"), "reviewer");
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    String cutoff = "2026-01-01T01:00:00.123456001Z";
    eval(owner, caseId, cutoff, "LIVE", measurement("r-precision", "a", cutoff, "5", "ms"));
    JsonNode incident = body(post("/v1/incidents", owner,
        incidentJson(caseId, "w1", cutoff, "[\"r-precision\"]")), 201);
    assertEquals(Instant.parse(cutoff), Instant.parse(incident.get("observed_from").asString()));
    String recordId = body(post("/v1/learning-records", owner,
        learningJson(incident.get("id").asString(), 1, "[\"r-precision\"]")), 201).get("id").asString();
    assertEquals("APPROVE", body(post("/v1/learning-records/" + recordId + ":review", reviewer,
        "{\"expected_revision\":1,\"decision\":\"APPROVE\",\"reason\":\"reviewed\"}"), 200)
        .get("decision").asString());
  }

  @Test void learningReviewOfLegacyUnknownProvenanceCanRejectButCannotApprove() throws Exception {
    String tenant = "t-learning-review-legacy";
    String owner = token(tenant, "*", ALL);
    String reviewer = token(tenant, "*", List.of("assessment.learning.review"), "reviewer");
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    String incidentId = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO uacrf_assessment.incident "
        + "(tenant_id,environment_id,id,workload_id,source_case_id,observed_from,description,"
        + "evidence_refs,reason,revision,created_by) VALUES (?,'env-1',?,'w1',?,"
        + "'2026-01-01T01:00:00Z','legacy','[\"receipt\"]'::jsonb,'reason',1,'user')",
        tenant, incidentId, caseId);
    String recordId = body(post("/v1/learning-records", owner,
        learningJson(incidentId, 1, "[\"receipt\"]")), 201).get("id").asString();
    String path = "/v1/learning-records/" + recordId + ":review";
    String request = "{\"expected_revision\":1,\"decision\":\"APPROVE\",\"reason\":\"reviewed\"}";
    assertEquals("INCIDENT_PROVENANCE_REQUIRED", body(post(path, reviewer, request), 409)
        .get("code").asString());
    assertEquals("REJECT", body(post(path, reviewer, request.replace("APPROVE", "REJECT")), 200)
        .get("decision").asString());
    assertEquals("LEARNING_REVIEW_NOT_APPROVED", body(post(
        "/internal/v1/learning-records/" + recordId + ":review-receipt",
        token(tenant, "*", List.of("assessment.learning.read"), "reader"), "{}"), 409).get("code").asString());
  }

  @Test void incidentLearningRecordsArePaginatedAndRequireBothReadPermissions() throws Exception {
    String tenant = "t-learning-list";
    String owner = token(tenant, "*", ALL);
    String reader = token(tenant, "*", List.of("assessment.learning.read", "assessment.incident.read"));
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    eval(owner, caseId, "2026-01-01T01:00:00Z", "LIVE",
        measurement("r-list", "a", "2026-01-01T00:59:00Z", "5", "ms"));
    String incidentBody = incidentJson(caseId, "w1", "2026-01-01T01:00:00Z", "[\"r-list\"]");
    String incident = body(post("/v1/incidents", owner, incidentBody), 201).get("id").asString();
    String otherIncident = body(post("/v1/incidents", owner, incidentBody), 201).get("id").asString();
    String path = "/v1/incidents/" + incident + "/learning-records";
    assertEquals(0, body(get(path, reader), 200).get("items").size());
    String one = body(post("/v1/learning-records", owner,
        learningJson(incident, 1, "[\"r-list\"]")), 201).get("id").asString();
    String two = body(post("/v1/learning-records", owner,
        learningJson(incident, 1, "[\"r-list\"]")), 201).get("id").asString();
    body(post("/v1/learning-records", owner, learningJson(otherIncident, 1, "[\"r-list\"]")), 201);
    assertEquals(403, get(path, token(tenant, "*", List.of("assessment.learning.read"))).statusCode());
    assertEquals(403, get(path, token(tenant, "*", List.of("assessment.incident.read"))).statusCode());
    assertEquals(403, get(path, token(tenant, "w1",
        List.of("assessment.learning.read", "assessment.incident.read"))).statusCode());
    assertEquals(404, get(path, token("t-learning-list-foreign", "*", ALL)).statusCode());
    assertEquals(404, get("/v1/incidents/unknown/learning-records", reader).statusCode());
    assertEquals(400, get(path + "?limit=0", reader).statusCode());
    assertEquals(400, get(path + "?limit=101", reader).statusCode());
    assertEquals(400, get(path + "?after=invalid", reader).statusCode());
    assertEquals(2, body(get(path + "?limit=100", reader), 200).get("items").size());
    JsonNode first = body(get(path + "?limit=1", reader), 200);
    String cursor = first.get("next_cursor").asString();
    assertEquals(1, first.get("items").size());
    assertEquals(cursor, first.get("items").get(0).get("id").asString());
    JsonNode second = body(get(path + "?limit=1&after=" + cursor, reader), 200);
    assertEquals(1, second.get("items").size());
    assertTrue(second.get("next_cursor").isNull());
    assertEquals(Set.of(one, two), Set.of(cursor, second.get("items").get(0).get("id").asString()));
    assertEquals("PROPOSED", second.get("items").get(0).get("state").asString());
    assertEquals(0, body(get(path + "?after=" + second.get("items").get(0).get("id").asString(), reader),
        200).get("items").size());
    DelegationPrincipal foreignEnvironment = new DelegationPrincipal(
        "https://uacrf.local/s01|reader", tenant, "env-2", "*", "https://idp.example.test", "reader",
        List.of("assessment.learning.read", "assessment.incident.read"), List.of(), List.of());
    String envToken = "Bearer " + new DelegationTokenIssuer(ENCODER, Clock.systemUTC(),
        "https://uacrf.local/s01", KEY_ID).issue(foreignEnvironment, "S07");
    assertEquals(404, get(path, envToken).statusCode());
  }

  @Test void caseIncidentsArePaginatedAndRequireBothReadPermissions() throws Exception {
    String tenant = "t-case-incidents";
    String owner = token(tenant, "*", ALL);
    String reader = token(tenant, "*", List.of("assessment.case.read", "assessment.incident.read"));
    String caseId = sealed(owner, "[\"a\"]", "LTE");
    String otherCase = sealed(owner, "[\"a\"]", "LTE");
    String path = "/v1/cases/" + caseId + "/incidents";
    assertEquals(0, body(get(path, reader), 200).get("items").size());
    String cutoff = "2026-01-01T01:00:00Z";
    eval(owner, caseId, cutoff, "LIVE",
        measurement("r-case-incidents", "a", "2026-01-01T00:59:00Z", "5", "ms"));
    eval(owner, otherCase, cutoff, "LIVE",
        measurement("r-other-case", "a", "2026-01-01T00:59:00Z", "5", "ms"));
    String incidentBody = incidentJson(caseId, "w1", cutoff, "[\"r-case-incidents\"]");
    String one = body(post("/v1/incidents", owner, incidentBody), 201).get("id").asString();
    String two = body(post("/v1/incidents", owner, incidentBody), 201).get("id").asString();
    body(post("/v1/incidents", owner,
        incidentJson(otherCase, "w1", cutoff, "[\"r-other-case\"]")), 201);
    assertEquals(403, get(path, token(tenant, "*", List.of("assessment.case.read"))).statusCode());
    assertEquals(403, get(path, token(tenant, "*", List.of("assessment.incident.read"))).statusCode());
    assertEquals(403, get(path, token(tenant, "w1",
        List.of("assessment.case.read", "assessment.incident.read"))).statusCode());
    assertEquals(404, get(path, token("t-case-incidents-foreign", "*", ALL)).statusCode());
    assertEquals(404, get("/v1/cases/unknown/incidents", reader).statusCode());
    assertEquals(400, get(path + "?limit=0", reader).statusCode());
    assertEquals(400, get(path + "?limit=101", reader).statusCode());
    assertEquals(400, get(path + "?after=invalid", reader).statusCode());
    assertEquals(2, body(get(path + "?limit=100", reader), 200).get("items").size());
    JsonNode first = body(get(path + "?limit=1", reader), 200);
    String cursor = first.get("next_cursor").asString();
    assertEquals(1, first.get("items").size());
    assertEquals(cursor, first.get("items").get(0).get("id").asString());
    JsonNode second = body(get(path + "?limit=1&after=" + cursor, reader), 200);
    assertEquals(1, second.get("items").size());
    assertTrue(second.get("next_cursor").isNull());
    assertEquals(Set.of(one, two), Set.of(cursor, second.get("items").get(0).get("id").asString()));
    assertEquals(caseId, second.get("items").get(0).get("source_case_id").asString());
    DelegationPrincipal foreignEnvironment = new DelegationPrincipal(
        "https://uacrf.local/s01|reader", tenant, "env-2", "*", "https://idp.example.test", "reader",
        List.of("assessment.case.read", "assessment.incident.read"), List.of(), List.of());
    String envToken = "Bearer " + new DelegationTokenIssuer(ENCODER, Clock.systemUTC(),
        "https://uacrf.local/s01", KEY_ID).issue(foreignEnvironment, "S07");
    assertEquals(404, get(path, envToken).statusCode());
    assertEquals(404, get("/v1/incidents/" + one, envToken).statusCode());
    jdbc.update("INSERT INTO uacrf_assessment.incident "
            + "(tenant_id,environment_id,id,workload_id,source_case_id,observed_from,description,"
            + "evidence_refs,reason,revision,created_by) "
            + "SELECT tenant_id,environment_id,gen_random_uuid()::text,workload_id,source_case_id,"
            + "observed_from,description,evidence_refs,reason,revision,created_by "
            + "FROM uacrf_assessment.incident CROSS JOIN generate_series(1,49) "
            + "WHERE tenant_id=? AND environment_id='env-1' AND id=?", tenant, one);
    JsonNode defaultPage = body(get(path, reader), 200);
    assertEquals(50, defaultPage.get("items").size());
    JsonNode lastPage = body(get(path + "?after=" + defaultPage.get("next_cursor").asString(), reader), 200);
    assertEquals(1, lastPage.get("items").size());
    assertTrue(lastPage.get("next_cursor").isNull());
    JsonNode legacy = null;
    for (JsonNode item : defaultPage.get("items")) {
      if (item.get("source_evaluation_ids").isNull()) {
        legacy = item;
        break;
      }
    }
    assertNotNull(legacy);
    assertTrue(body(get("/v1/incidents/" + legacy.get("id").asString(), reader), 200)
        .get("source_evaluation_ids").isNull());
  }

  private JsonNode create(String token,String kinds,String comparator) throws Exception { return body(post("/v1/cases",token,caseJson(kinds,comparator)),201); }
  private JsonNode modelVersion(String token, String model, String version) throws Exception {
    return body(post("/v1/model-versions", token, modelJson(model, version)), 201);
  }
  private String seedEvaluation(String tenant, String caseId, String verification, String outcome) {
    return seedEvaluation(tenant, caseId, verification, outcome, null);
  }
  private String seedEvaluation(String tenant, String caseId, String verification, String outcome,
      String digestOverride) {
    String id = UUID.randomUUID().toString();
    int revision = jdbc.queryForObject("SELECT COALESCE(MAX(revision),0)+1 FROM "
        + "uacrf_assessment.evaluation WHERE tenant_id=? AND environment_id='env-1' AND case_id=?",
        Integer.class, tenant, caseId);
    String digest = jdbc.queryForObject("SELECT case_digest FROM uacrf_assessment.analysis_case "
        + "WHERE tenant_id=? AND environment_id='env-1' AND id=?", String.class, tenant, caseId);
    var objective = new AssessmentRequests.ObjectiveRef("o1", 1, "od");
    var graph = new AssessmentRequests.GraphRef("g1", 1, "gd");
    var measurement = new AssessmentRequests.Measurement("r1", "a",
        Instant.parse("2025-12-31T23:59:00Z"), new java.math.BigDecimal("5"), "ms");
    var vector = new AssessmentResponses.DependencyVector(digest, objective, graph, verification, null,
        new AssessmentResponses.ObjectiveReceipt("w1", "set-od", "o1", 1, "od"),
        new AssessmentResponses.GraphReceipt("g1", 1, "gd"),
        List.of(new AssessmentResponses.Evidence(measurement, "USED")),
        List.of(new AssessmentResponses.MeasurementReceipt("r1", "assessment", "QUALIFIED", true,
            "a".repeat(64), 2, measurement.observedAt(), "a", "latency", measurement.value(), "ms", "Qualified measurement.")));
    String material = json.writeValueAsString(new EvaluationResultMaterial(
        Instant.parse("2026-01-01T00:00:00Z"), AssessmentRequests.Mode.SHADOW, outcome, vector));
    String resultDigest = digestOverride == null ? sha256(material) : digestOverride;
    jdbc.update("INSERT INTO uacrf_assessment.evaluation "
        + "(tenant_id,environment_id,id,case_id,revision,observation_cutoff,cutoff_epoch_second,"
        + "cutoff_nano,mode,outcome,dependency_vector,result_digest) "
        + "VALUES (?, 'env-1', ?, ?, ?, '2026-01-01T00:00:00Z', 1767225600,0,'SHADOW',? ,?::jsonb,?)",
        tenant, id, caseId, revision, outcome, json.writeValueAsString(vector), resultDigest);
    return id;
  }
  private record EvaluationResultMaterial(Instant observationCutoff, AssessmentRequests.Mode mode,
      String outcome, AssessmentResponses.DependencyVector dependencyVector) {}
  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
  private static String modelJson(String model, String version) {
    return "{\"model_id\":\"" + model + "\",\"version\":\"" + version
        + "\",\"artifact_ref\":\"artifact://model\",\"digest\":\"" + "c".repeat(64)
        + "\",\"model_card_ref\":\"card://model\",\"evaluation_pack_ref\":\"eval://pack\","
        + "\"reason\":\"candidate for governance\"}";
  }
  private static String reviewPath(String id) { return "/v1/model-versions/" + id + ":review"; }
  private static String reviewJson(int revision, String decision, String evaluations) {
    return "{\"expected_revision\":" + revision + ",\"decision\":\"" + decision
        + "\",\"evaluation_result_ids\":" + evaluations + ",\"reason\":\"independent review\"}";
  }
  private static String promoteJson(int revision, String mode) {
    return "{\"expected_revision\":" + revision + ",\"target_mode\":\"" + mode
        + "\",\"reason\":\"bounded advisory activation\"}";
  }
  private String sealed(String token,String kinds,String comparator) throws Exception { String id=create(token,kinds,comparator).get("id").asString(); body(post("/v1/cases/"+id+":seal",token,"{\"expected_revision\":1}"),200); return id; }
  private static String caseJson(String kinds,String comparator) { return "{\"workload_id\":\"w1\",\"purpose\":\"assessment\",\"metric\":\"latency\",\"comparator\":\""+comparator+"\",\"threshold\":10,\"unit\":\"ms\",\"window_seconds\":3600,\"objective_ref\":{\"objective_id\":\"o1\",\"revision\":1,\"digest\":\"od\"},\"graph_ref\":{\"graph_id\":\"g1\",\"revision\":1,\"digest\":\"gd\"},\"required_source_kinds\":"+kinds+"}"; }
  private static String incidentJson(String caseId, String workloadId, String observedFrom,
      String evidenceRefs) {
    return "{\"workload_id\":\"" + workloadId + "\",\"source_case_id\":\"" + caseId
        + "\",\"observed_from\":\"" + observedFrom + "\",\"description\":\"observed incident\","
        + "\"evidence_refs\":" + evidenceRefs + ",\"reason\":\"retain incident evidence\"}";
  }
  private static String learningJson(String incidentId, int expectedRevision, String evidenceRefs) {
    return "{\"incident_id\":\"" + incidentId + "\",\"experiment_id\":\"exp-1\","
        + "\"hypotheses\":[\"recovery path may be delayed\"],\"action_items\":[{"
        + "\"description\":\"test the recovery path\",\"owner_id\":\"owner-1\","
        + "\"due_at\":\"2026-02-01T00:00:00Z\",\"proposed_version_ref\":\"candidate://v2\","
        + "\"closure_test\":\"replay verified evidence\"}],\"evidence_refs\":" + evidenceRefs
        + ",\"expected_revision\":" + expectedRevision
        + ",\"reason\":\"propose follow-up only\"}";
  }
  private static String measurementReceipt(String id, String observedAt) {
    return "{\"evidence_id\":\"" + id + "\",\"purpose\":\"assessment\","
        + "\"qualification_state\":\"QUALIFIED\",\"usable_as_evidence\":true,"
        + "\"payload_digest\":\"" + "a".repeat(64) + "\",\"revision\":1,"
        + "\"observed_at\":\"" + observedAt + "\",\"source_kind\":\"a\","
        + "\"metric\":\"latency\",\"value\":5,\"unit\":\"ms\"}";
  }
  private void seedIncidentEvaluation(String tenant, String caseId, String cutoff,
      String verification, String receipts) {
    String id = UUID.randomUUID().toString();
    int revision = jdbc.queryForObject("SELECT COALESCE(MAX(revision),0)+1 FROM "
        + "uacrf_assessment.evaluation WHERE tenant_id=? AND environment_id='env-1' AND case_id=?",
        Integer.class, tenant, caseId);
    Instant cutoffInstant = Instant.parse(cutoff);
    String receiptList = receipts.stripLeading().startsWith("{") ? "[" + receipts + "]" : receipts;
    String vector = "{\"receipt_verification\":\"" + verification
        + "\",\"objective_receipt\":{\"workload_id\":\"w1\",\"set_digest\":\""
        + "b".repeat(64) + "\",\"objective_id\":\"o1\",\"revision\":1,\"digest\":\"od\"},"
        + "\"graph_receipt\":{\"graph_id\":\"g1\",\"revision\":1,\"digest\":\"gd\"},"
        + "\"measurement_receipts\":" + receiptList + "}";
    jdbc.update("INSERT INTO uacrf_assessment.evaluation "
        + "(tenant_id,environment_id,id,case_id,revision,observation_cutoff,cutoff_epoch_second,"
        + "cutoff_nano,mode,outcome,dependency_vector,result_digest) "
        + "VALUES (?, 'env-1', ?, ?, ?, ?, ?, ?, 'SHADOW','CONFIRMED_SUCCESS',?::jsonb,?)",
        tenant, id, caseId, revision, cutoff, cutoffInstant.getEpochSecond(), cutoffInstant.getNano(),
        vector, "c".repeat(64));
  }
  private static String measurement(String receipt,String kind,String at,String value,String unit) { return "{\"source_receipt_id\":\""+receipt+"\",\"source_kind\":\""+kind+"\",\"observed_at\":\""+at+"\",\"value\":"+value+",\"unit\":\""+unit+"\"}"; }
  private static String evalJson(String cutoff,String mode,String inputs) { return "{\"observation_cutoff\":\""+cutoff+"\",\"mode\":\""+mode+"\",\"measurements\":["+inputs+"]}"; }
  private JsonNode eval(String token,String id,String cutoff,String mode,String inputs) throws Exception { return body(post("/v1/cases/"+id+":evaluate",token,evalJson(cutoff,mode,inputs)),201); }
  private JsonNode body(HttpResponse<String> response,int status) { assertEquals(status,response.statusCode(),response.body()); return json.readTree(response.body()); }
  private HttpResponse<String> post(String path,String token,String body) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization",token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString()); }
  private HttpResponse<String> postAdaptation(String path, String token, String body, String key) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Authorization", token).header("Content-Type", "application/json")
        .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }
  private HttpResponse<String> postCaseKey(String token, String body, String key) throws Exception {
    return postAdaptation("/v1/cases:idempotent-create", token, body, key);
  }
  private HttpResponse<String> get(String path,String token) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization",token).GET().build(),HttpResponse.BodyHandlers.ofString()); }
  private static String token(String tenant,String scope,List<String> capabilities) { return token(tenant, scope, capabilities, "user"); }
  private static String token(String tenant,String scope,List<String> capabilities, String subject) { DelegationPrincipal p = new DelegationPrincipal("https://uacrf.local/s01|"+subject,tenant,"env-1",scope,"https://idp.example.test",subject,capabilities,List.of(),List.of()); return "Bearer "+new DelegationTokenIssuer(ENCODER,Clock.systemUTC(),"https://uacrf.local/s01",KEY_ID).issue(p,"S07"); }
  private static String scopedCaseReceiptToken(
      String tenant, String scope, String resourceType, String resourceId, String purpose) {
    Instant issuedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    return scopedCaseReceiptToken(
        tenant, scope, resourceType, resourceId, purpose, issuedAt, issuedAt.plusSeconds(30));
  }
  private static String scopedCaseReceiptToken(
      String tenant, String scope, String resourceType, String resourceId, String purpose,
      Instant issuedAt, Instant expiresAt) {
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer("https://uacrf.local/s01")
        .subject("principal-case-receipt")
        .audience(List.of("S07"))
        .issuedAt(issuedAt)
        .expiresAt(expiresAt)
        .id(UUID.randomUUID().toString())
        .claim("tenant_id", tenant)
        .claim("environment_id", "env-1")
        .claim("actor_issuer", "https://idp.example.test")
        .claim("actor_subject", "graph-operator")
        .claim("service_scope", scope)
        .claim("capabilities", List.of("assessment.case.read"))
        .claim("eligible_owner_ids", List.of())
        .claim("runtime_principal_ids", List.of())
        .claim("resource_type", resourceType)
        .claim("resource_id", resourceId)
        .claim("purpose", purpose)
        .build();
    return "Bearer " + ENCODER.encode(JwtEncoderParameters.from(
        JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(), claims)).getTokenValue();
  }
  private static KeyPair keyPair() { try { KeyPairGenerator g=KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair(); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
}
