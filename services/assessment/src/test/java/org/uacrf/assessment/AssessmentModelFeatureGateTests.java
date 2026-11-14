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
                  "assessment.model.review"), List.of(), List.of()), "S07");
  @Container private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81")
          .withDatabaseName("uacrf_assessment").withUsername("assessment_test")
          .withPassword("assessment_test");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
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
