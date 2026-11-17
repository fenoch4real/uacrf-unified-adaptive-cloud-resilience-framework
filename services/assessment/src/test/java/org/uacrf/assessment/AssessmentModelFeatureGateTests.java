package org.uacrf.assessment;

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
class AssessmentModelFeatureGateTests {
  private static final String KEY_ID = "assessment-model-gate-key";
  private static final KeyPair KEY_PAIR = keyPair();
  private static final String TOKEN = "Bearer " + new DelegationTokenIssuer(
      DelegationJwtEncoderFactory.create(KEY_PAIR, KEY_ID), Clock.systemUTC(),
      "https://uacrf.local/s01", KEY_ID).issue(
          new DelegationPrincipal("https://uacrf.local/s01|model-user", "t-model-gate",
              "env-1", "*", "https://idp.example.test", "model-user",
              List.of("assessment.model.read", "assessment.model.write",
                  "assessment.model.review", "assessment.learning.review",
                  "assessment.learning.read"), List.of(), List.of()), "S07");
  @Container private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81")
          .withDatabaseName("uacrf_assessment").withUsername("assessment_test")
          .withPassword("assessment_test");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    p.add("spring.flyway.locations", AssessmentTestMigrations::location);
    p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    p.add("spring.datasource.username", POSTGRES::getUsername);
    p.add("spring.datasource.password", POSTGRES::getPassword);
    p.add("uacrf.delegation.public-key-base64",
        () -> Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded()));
    p.add("uacrf.delegation.key-id", () -> KEY_ID);
    p.add("uacrf.assessment.models.enabled", () -> "false");
  }

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void disabledGateBlocksCommandsButKeepsReadsAvailable() throws Exception {
    HttpResponse<String> response = post("/v1/model-versions",
        "{\"model_id\":\"m\",\"version\":\"1\",\"artifact_ref\":\"a\",\"digest\":\""
            + "a".repeat(64)
            + "\",\"model_card_ref\":\"c\",\"evaluation_pack_ref\":\"e\",\"reason\":\"r\"}");
    assertEquals(409, response.statusCode());
    assertEquals("FEATURE_DISABLED", json.readTree(response.body()).get("code").asString());
    String candidate = seedVersion("candidate", "CANDIDATE");
    HttpResponse<String> declaration = post("/internal/v1/model-evaluation-declarations",
        "{\"model_version_id\":\"" + candidate + "\",\"training_data_digest\":\"" + "a".repeat(64)
            + "\",\"holdout_data_digest\":\"" + "b".repeat(64)
            + "\",\"result_ref\":\"result\",\"result_digest\":\"" + "c".repeat(64)
            + "\",\"training_through\":\"2025-01-01T00:00:00Z\","
            + "\"holdout_from\":\"2025-01-02T00:00:00Z\","
            + "\"holdout_through\":\"2025-01-03T00:00:00Z\",\"reason\":\"test\"}");
    assertEquals(409, declaration.statusCode());
    assertEquals("FEATURE_DISABLED", json.readTree(declaration.body()).get("code").asString());
    String declarationId = java.util.UUID.randomUUID().toString();
    jdbc.update("INSERT INTO uacrf_assessment.model_evaluation_declaration "
            + "(tenant_id,environment_id,id,model_version_id,model_digest,training_data_digest,"
            + "holdout_data_digest,result_ref,result_digest,training_through,holdout_from,holdout_through,"
            + "reason,status,declared_by) VALUES ('t-model-gate','env-1',?,?,?,?,?,?,?,"
            + "'2025-01-01T00:00:00Z','2025-01-02T00:00:00Z','2025-01-03T00:00:00Z',"
            + "'reason','DECLARED_UNVERIFIED','user')",
        declarationId, candidate, "a".repeat(64), "b".repeat(64), "c".repeat(64), "result", "d".repeat(64));
    HttpResponse<String> declarationRead = http.send(HttpRequest.newBuilder(
        URI.create("http://localhost:" + port + "/internal/v1/model-evaluation-declarations/" + declarationId))
        .header("Authorization", TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, declarationRead.statusCode());
    assertEquals("DECLARED_UNVERIFIED", json.readTree(declarationRead.body()).get("status").asString());
    HttpResponse<String> declarationsRead = http.send(HttpRequest.newBuilder(
        URI.create("http://localhost:" + port + "/internal/v1/model-evaluation-declarations?model_version_id="
            + candidate))
        .header("Authorization", TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, declarationsRead.statusCode());
    assertEquals(declarationId, json.readTree(declarationsRead.body()).get("items").get(0).get("id").asString());
    HttpResponse<String> review = post("/v1/model-versions/" + candidate + ":review",
        "{\"expected_revision\":1,\"decision\":\"APPROVE\",\"evaluation_result_ids\":[],"
            + "\"reason\":\"review\"}");
    assertEquals(409, review.statusCode());
    assertEquals("FEATURE_DISABLED", json.readTree(review.body()).get("code").asString());
    String approved = seedVersion("approved", "APPROVED");
    HttpResponse<String> promote = post("/v1/model-versions/" + approved + ":promote",
        "{\"expected_revision\":2,\"target_mode\":\"SHADOW\",\"reason\":\"promote\"}");
    assertEquals(409, promote.statusCode());
    assertEquals("FEATURE_DISABLED", json.readTree(promote.body()).get("code").asString());
    HttpResponse<String> read = http.send(HttpRequest.newBuilder(
        URI.create("http://localhost:" + port + "/v1/model-versions"))
        .header("Authorization", TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, read.statusCode());
    assertEquals(2, json.readTree(read.body()).size());
  }

  @Test
  void disabledLearningReviewBlocksDecisionsButKeepsExistingReviewsReadable() throws Exception {
    HttpResponse<String> denied = post("/v1/learning-records/any:review",
        "{\"expected_revision\":1,\"decision\":\"REJECT\",\"reason\":\"review\"}");
    assertEquals(409, denied.statusCode());
    assertEquals("FEATURE_DISABLED", json.readTree(denied.body()).get("code").asString());
    HttpResponse<String> deniedReceipt = post("/internal/v1/learning-records/any:review-receipt", "{}");
    assertEquals(409, deniedReceipt.statusCode());
    assertEquals("FEATURE_DISABLED", json.readTree(deniedReceipt.body()).get("code").asString());
    jdbc.update("INSERT INTO uacrf_assessment.analysis_case "
        + "(tenant_id,environment_id,id,workload_id,content,state,revision,created_by) "
        + "VALUES ('t-model-gate','env-1','case-1','w1','{}'::jsonb,'DRAFT',1,'user')");
    String incidentId = java.util.UUID.randomUUID().toString();
    jdbc.update("INSERT INTO uacrf_assessment.incident "
        + "(tenant_id,environment_id,id,workload_id,source_case_id,observed_from,description,"
        + "evidence_refs,reason,revision,created_by) VALUES ('t-model-gate','env-1',?,'w1','case-1',"
        + "'2026-01-01T00:00:00Z','incident','[\"receipt\"]'::jsonb,'reason',1,'user')", incidentId);
    String recordId = java.util.UUID.randomUUID().toString();
    jdbc.update("INSERT INTO uacrf_assessment.learning_record "
        + "(tenant_id,environment_id,id,incident_id,content,evidence_refs,content_digest,state,revision,proposed_by) "
        + "VALUES ('t-model-gate','env-1',?,?,'{\"hypotheses\":[\"h\"],\"action_items\":[{}]}'::jsonb,"
        + "'[\"receipt\"]'::jsonb,repeat('a',64),'PROPOSED',1,'proposer')", recordId, incidentId);
    jdbc.update("INSERT INTO uacrf_assessment.learning_review "
        + "(tenant_id,environment_id,learning_record_id,expected_revision,decision,reason,reviewed_by) "
        + "VALUES ('t-model-gate','env-1',?,1,'REJECT','review','reviewer')", recordId);
    HttpResponse<String> read = http.send(HttpRequest.newBuilder(URI.create(
        "http://localhost:" + port + "/v1/learning-records/" + recordId + "/review"))
        .header("Authorization", TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, read.statusCode());
    assertEquals("REJECT", json.readTree(read.body()).get("decision").asString());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Authorization", TOKEN).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private String seedVersion(String suffix, String state) {
    String id = java.util.UUID.randomUUID().toString();
    jdbc.update("INSERT INTO uacrf_assessment.model_version "
        + "(tenant_id,environment_id,id,model_id,version,artifact_ref,digest,model_card_ref,"
        + "evaluation_pack_ref,reason,state,revision,proposed_by,reviewed_by,decision,"
        + "evaluation_result_ids,review_reason,reviewed_at) VALUES "
        + "('t-model-gate','env-1',?,'m-gate',?,'artifact','" + "a".repeat(64)
        + "','card','eval','reason',?, ?, 'https://uacrf.local/s01|model-user',"
        + "CASE WHEN ?='APPROVED' THEN 'reviewer' ELSE NULL END,"
        + "CASE WHEN ?='APPROVED' THEN 'APPROVE' ELSE NULL END,"
        + "'[]'::jsonb,CASE WHEN ?='APPROVED' THEN 'review' ELSE NULL END,"
        + "CASE WHEN ?='APPROVED' THEN now() ELSE NULL END)",
        id, suffix, state, "APPROVED".equals(state) ? 2 : 1, state, state, state, state);
    return id;
  }

  private static KeyPair keyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
