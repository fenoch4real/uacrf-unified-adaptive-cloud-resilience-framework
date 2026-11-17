package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.uacrf.security.DelegationJwtEncoderFactory;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.DelegationTokenIssuer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class EvidenceAssessmentCrossServiceTests {
  private static final String IMAGE =
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81";
  private static final String ISSUER = "https://uacrf.local/s01";
  private static final String KEY_ID = "evidence-assessment-test";
  private static final String TENANT = "tenant-integration";
  private static final String ENVIRONMENT = "env-integration";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(2)).proxy(ProxySelector.of(null)).build();
  private final List<Process> processes = new ArrayList<>();

  @Container private static final PostgreSQLContainer EVIDENCE = database("evidence");
  @Container private static final PostgreSQLContainer ASSESSMENT = database("assessment");
  @TempDir Path temp;

  @Test
  void realEvidenceConflictInvalidatesNewAssessmentAndPreservesEarlierSnapshot() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair key = generator.generateKeyPair();
    DelegationTokenIssuer issuer = new DelegationTokenIssuer(
        DelegationJwtEncoderFactory.create(key, KEY_ID), Clock.systemUTC(), ISSUER, KEY_ID);
    String publicKey = Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
    HttpServer owners = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    owners.createContext("/token", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      if (!body.contains("grant_type=client_credentials") || !body.contains("client_id=uacrf-s07-evaluator")
          || !body.contains("client_secret=fixture-secret")) {
        respond(exchange, 403, "{}");
        return;
      }
      respond(exchange, "{\"access_token\":\"fixture-actor\",\"expires_in\":60}");
    });
    owners.createContext("/delegations", exchange -> {
      String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      JsonNode body = JSON.readTree(request);
      String audience = body.get("audience").asString();
      String capability = body.get("capability_ids").get(0).asString();
      String tenant = body.get("tenant_id").asString();
      String environment = body.get("environment_id").asString();
      String expected = switch (audience) {
        case "S04" -> "evidence.event.read";
        case "S05" -> "objectives.objective.read";
        case "S06" -> "topology.graph.read";
        default -> "";
      };
      if (!"Bearer fixture-actor".equals(exchange.getRequestHeaders().getFirst("Authorization"))
          || !expected.equals(capability) || body.get("capability_ids").size() != 1
          || !TENANT.equals(tenant) || !ENVIRONMENT.equals(environment)) {
        respond(exchange, 403, "{}");
        return;
      }
      String delegation = token(issuer, "assessment-service", tenant, environment, audience, List.of(capability));
      respond(exchange, JSON.writeValueAsString(Map.of("access_token", delegation, "expires_in", 30)));
    });
    owners.createContext("/internal/v1/workloads/", exchange -> respond(exchange,
        "{\"workload_id\":\"w1\",\"purpose\":\"assessment\",\"objectives\":["
            + "{\"id\":\"o1\",\"revision\":1,\"content_digest\":\"od\"}],\"set_digest\":\"set-od\"}"));
    owners.createContext("/internal/v1/graphs/", exchange -> respond(exchange,
        "{\"id\":\"g1\",\"scope\":\"w1\",\"revision\":1,\"digest\":\"gd\","
            + "\"status\":\"ACCEPTED\",\"projection_status\":\"PROJECTED\",\"eligibility\":true}"));
    owners.start();
    try {
      String fixture = "http://127.0.0.1:" + owners.getAddress().getPort();
      URI evidence = start("evidence", Map.of(
          "EVIDENCE_JDBC_URL", EVIDENCE.getJdbcUrl(), "EVIDENCE_DB_USER", EVIDENCE.getUsername(),
          "EVIDENCE_DB_PASSWORD", EVIDENCE.getPassword(),
          "UACRF_DELEGATION_PUBLIC_KEY_BASE64", publicKey, "UACRF_DELEGATION_KEY_ID", KEY_ID));
      URI assessment = start("assessment", Map.ofEntries(
          Map.entry("ASSESSMENT_JDBC_URL", ASSESSMENT.getJdbcUrl()),
          Map.entry("ASSESSMENT_DB_USER", ASSESSMENT.getUsername()),
          Map.entry("ASSESSMENT_DB_PASSWORD", ASSESSMENT.getPassword()),
          Map.entry("UACRF_DELEGATION_PUBLIC_KEY_BASE64", publicKey),
          Map.entry("UACRF_DELEGATION_KEY_ID", KEY_ID),
          Map.entry("UACRF_ASSESSMENT_RECEIPTS_ENABLED", "true"),
          Map.entry("UACRF_ASSESSMENT_MODELS_ENABLED", "true"),
          Map.entry("UACRF_ASSESSMENT_LEARNING_REVIEW_ENABLED", "true"),
          Map.entry("UACRF_ASSESSMENT_RECEIPTS_TOKEN_URL", fixture + "/token"),
          Map.entry("UACRF_ASSESSMENT_RECEIPTS_CLIENT_SECRET", "fixture-secret"),
          Map.entry("UACRF_ASSESSMENT_RECEIPTS_DELEGATION_URL", fixture + "/delegations"),
          Map.entry("UACRF_ASSESSMENT_RECEIPTS_EVIDENCE_BASE_URL", evidence.toString()),
          Map.entry("UACRF_ASSESSMENT_RECEIPTS_OBJECTIVES_BASE_URL", fixture),
          Map.entry("UACRF_ASSESSMENT_RECEIPTS_TOPOLOGY_BASE_URL", fixture)));

      String submit = token(issuer, "submitter", TENANT, ENVIRONMENT, "S04",
          List.of("evidence.event.write", "evidence.event.read"));
      String review = token(issuer, "reviewer", TENANT, ENVIRONMENT, "S04",
          List.of("evidence.qualification.review", "evidence.event.read"));
      String actor = token(issuer, "assessor", TENANT, ENVIRONMENT, "S07",
          List.of("assessment.case.read", "assessment.case.write", "assessment.evaluation.run",
              "assessment.evaluation.read", "assessment.recommendation.read",
              "assessment.model.read", "assessment.model.write", "assessment.incident.write",
              "assessment.incident.read", "assessment.learning.read", "assessment.learning.write"));
      String learningReviewer = token(issuer, "learning-reviewer", TENANT, ENVIRONMENT, "S07",
          List.of("assessment.learning.review"));
      String modelReviewer = token(issuer, "model-reviewer", TENANT, ENVIRONMENT, "S07",
          List.of("assessment.model.review"));
      String modelPromoter = token(issuer, "model-promoter", TENANT, ENVIRONMENT, "S07",
          List.of("assessment.model.write"));
      String other = token(issuer, "other", "other-tenant", ENVIRONMENT, "S04", List.of("evidence.event.read"));
      String observedAt = Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString();
      String payload = "{\"metric\":\"latency\",\"value\":5,\"unit\":\"ms\"}";
      String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(payload.getBytes(StandardCharsets.UTF_8)));
      String observation = JSON.writeValueAsString(Map.of("id", "e1", "source_class", "PROVIDER",
          "source_ref", "source-1", "sequence", 1, "observed_at", observedAt,
          "kind", "a", "payload", payload, "payload_digest", digest));
      assertEquals("ACCEPTED", post(evidence, "/internal/v1/observations", submit, observation, 202)
          .get("status").asString());
      post(evidence, "/v1/evidence/e1/reviews", review,
          "{\"decision\":\"QUALIFY\",\"reason\":\"independent review\",\"expected_revision\":1}", 200);
      String caseBody = "{\"workload_id\":\"w1\",\"purpose\":\"assessment\",\"metric\":\"latency\","
          + "\"comparator\":\"LTE\",\"threshold\":10,\"unit\":\"ms\",\"window_seconds\":3600,"
          + "\"objective_ref\":{\"objective_id\":\"o1\",\"revision\":1,\"digest\":\"od\"},"
          + "\"graph_ref\":{\"graph_id\":\"g1\",\"revision\":1,\"digest\":\"gd\"},"
          + "\"required_source_kinds\":[\"a\"]}";
      String caseId = post(assessment, "/v1/cases", actor, caseBody, 201).get("id").asString();
      post(assessment, "/v1/cases/" + caseId + ":seal", actor, "{\"expected_revision\":1}", 200);
      String cutoff = Instant.now().plusSeconds(5).toString();
      String evaluation = "{\"mode\":\"LIVE\",\"observation_cutoff\":\"" + cutoff
          + "\",\"measurements\":[{\"source_receipt_id\":\"e1\",\"source_kind\":\"a\","
          + "\"observed_at\":\"" + observedAt + "\",\"value\":5,\"unit\":\"ms\"}]}";
      String path = "/v1/cases/" + caseId + ":evaluate";
      JsonNode before = post(assessment, path, actor, evaluation, 201);
      assertEquals("CONFIRMED_SUCCESS", before.get("outcome").asString());
      assertEquals("VERIFIED", before.at("/dependency_vector/receipt_verification").asString());
      String incidentBody = "{\"workload_id\":\"w1\",\"source_case_id\":\"" + caseId
          + "\",\"observed_from\":\"" + cutoff + "\",\"description\":\"observed incident\","
          + "\"evidence_refs\":[\"e1\"],\"reason\":\"retain incident evidence\"}";
      JsonNode incident = post(assessment, "/v1/incidents", actor, incidentBody, 201);
      String retainedIncident = incident.get("id").asString();
      assertEquals(before.get("id").asString(),
          incident.get("source_evaluation_ids").get(0).asString());
      String learningBody = "{\"incident_id\":\"" + retainedIncident + "\","
          + "\"hypotheses\":[\"test recovery\"],\"action_items\":[{"
          + "\"description\":\"test\",\"owner_id\":\"owner-1\","
          + "\"due_at\":\"2027-01-01T00:00:00Z\",\"closure_test\":\"replay\"}],"
          + "\"evidence_refs\":[\"e1\"],\"expected_revision\":1,\"reason\":\"follow up\"}";
      String approvedLearning = post(assessment, "/v1/learning-records", actor, learningBody, 201)
          .get("id").asString();
      String pendingLearning = post(assessment, "/v1/learning-records", actor, learningBody, 201)
          .get("id").asString();
      String learningReviewBody = "{\"expected_revision\":1,\"decision\":\"APPROVE\",\"reason\":\"reviewed\"}";
      assertEquals("APPROVE", post(assessment, "/v1/learning-records/" + approvedLearning + ":review",
          learningReviewer, learningReviewBody, 200).get("decision").asString());
      String reviewReceiptPath = "/internal/v1/learning-records/" + approvedLearning + ":review-receipt";
      JsonNode reviewReceipt = post(assessment, reviewReceiptPath, actor, "{}", 200);
      assertEquals("VERIFIED", reviewReceipt.get("owner_receipts").asString());
      assertEquals(false, reviewReceipt.get("authorizes_adoption").asBoolean());
      String modelBody = "{\"model_id\":\"freshness-model\",\"version\":\"1\","
          + "\"artifact_ref\":\"artifact\",\"digest\":\"" + "b".repeat(64)
          + "\",\"model_card_ref\":\"card\",\"evaluation_pack_ref\":\"pack\",\"reason\":\"test\"}";
      String model = post(assessment, "/v1/model-versions", actor, modelBody, 201).get("id").asString();
      String reviewBody = "{\"expected_revision\":1,\"decision\":\"APPROVE\",\"evaluation_result_ids\":[\""
          + before.get("id").asString() + "\"],\"reason\":\"test\"}";
      post(assessment, "/v1/model-versions/" + model + ":review", modelReviewer, reviewBody, 200);
      JsonNode missingModelEvaluation = post(assessment, "/v1/model-versions/" + model + ":promote",
          modelPromoter, "{\"expected_revision\":2,\"target_mode\":\"SHADOW\",\"reason\":\"test\"}", 409);
      assertEquals("MODEL_EVALUATION_REQUIRED", missingModelEvaluation.get("code").asString());
      String candidate = post(assessment, "/v1/model-versions", actor,
          modelBody.replace("\"version\":\"1\"", "\"version\":\"2\""), 201).get("id").asString();

      assertEquals(404, send("POST", evidence, "/internal/v1/qualifications/e1:measurement", other,
          "{\"purpose\":\"assessment\",\"metric\":\"latency\",\"cutoff\":\"" + cutoff + "\"}").statusCode());
      assertEquals(401, send("POST", evidence, "/internal/v1/qualifications/e1:measurement", actor,
          "{\"purpose\":\"assessment\",\"metric\":\"latency\",\"cutoff\":\"" + cutoff + "\"}").statusCode());
      assertEquals("REPLAY_CONFLICT", post(evidence, "/internal/v1/observations", submit,
          observation.replace("\"kind\":\"a\"", "\"kind\":\"other\""), 202).get("reason").asString());
      JsonNode rejectedIncident = post(assessment, "/v1/incidents", actor, incidentBody, 409);
      assertEquals("OWNER_REVALIDATION_FAILED", rejectedIncident.get("code").asString());
      assertEquals("OWNER_REVALIDATION_FAILED", post(assessment,
          "/v1/learning-records/" + pendingLearning + ":review", learningReviewer, learningReviewBody, 409)
          .get("code").asString());
      assertEquals("OWNER_REVALIDATION_FAILED", post(assessment, reviewReceiptPath, actor, "{}", 409)
          .get("code").asString());
      assertEquals("APPROVE", get(assessment, "/v1/learning-records/" + approvedLearning + "/review",
          actor, 200).get("decision").asString());
      JsonNode retainedIncidentBody = get(assessment, "/v1/incidents/" + retainedIncident, actor, 200);
      assertEquals("w1", retainedIncidentBody.get("workload_id").asString());
      assertEquals(incident.get("source_evaluation_ids"), retainedIncidentBody.get("source_evaluation_ids"));
      JsonNode rejectedReview = post(assessment, "/v1/model-versions/" + candidate + ":review",
          modelReviewer, reviewBody, 409);
      assertEquals("OWNER_REVALIDATION_FAILED", rejectedReview.get("code").asString());
      JsonNode rejectedPromotion = post(assessment, "/v1/model-versions/" + model + ":promote",
          modelPromoter, "{\"expected_revision\":2,\"target_mode\":\"SHADOW\",\"reason\":\"test\"}", 409);
      assertEquals("OWNER_REVALIDATION_FAILED", rejectedPromotion.get("code").asString());
      assertEquals("APPROVED", get(assessment, "/v1/model-versions/" + model, actor, 200).get("state").asString());
      JsonNode after = post(assessment, path, actor, evaluation, 201);
      assertEquals("INSUFFICIENT_EVIDENCE", after.get("outcome").asString());
      assertEquals("SOURCE_UNVERIFIED", after.at("/dependency_vector/receipt_reason_code").asString());
      assertEquals(false, after.at("/dependency_vector/measurement_receipts/0/usable_as_evidence").asBoolean());
      assertEquals("Evidence has a retained source conflict.",
          after.at("/dependency_vector/measurement_receipts/0/reason").asString());
      JsonNode retained = get(assessment, "/v1/evaluations/" + before.get("id").asString(), actor, 200);
      assertEquals("CONFIRMED_SUCCESS", retained.get("outcome").asString());
      assertEquals(0, get(assessment, "/v1/recommendations?workload_id=w1", actor, 200).size());
    } finally {
      owners.stop(0);
      for (Process process : processes.reversed()) process.destroy();
      for (Process process : processes.reversed()) {
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
      }
    }
  }

  private static PostgreSQLContainer database(String name) {
    return new PostgreSQLContainer(IMAGE).withDatabaseName("uacrf_" + name)
        .withUsername(name + "_test").withPassword(name + "_test");
  }

  private URI start(String service, Map<String, String> environment) throws Exception {
    return CrossServiceProcess.start(service, environment, temp, processes, HTTP);
  }

  private static String token(DelegationTokenIssuer issuer, String principal, String tenant,
      String environment, String audience, List<String> capabilities) {
    return issuer.issue(new DelegationPrincipal(ISSUER + "|" + principal, tenant, environment, "*",
        "https://idp.example.test", principal, capabilities, List.of(), List.of()), audience);
  }

  private static JsonNode post(URI base, String path, String token, String body, int expected) throws Exception {
    HttpResponse<String> response = send("POST", base, path, token, body);
    assertEquals(expected, response.statusCode(), response.body());
    return JSON.readTree(response.body());
  }

  private static JsonNode get(URI base, String path, String token, int expected) throws Exception {
    HttpResponse<String> response = send("GET", base, path, token, "");
    assertEquals(expected, response.statusCode(), response.body());
    return JSON.readTree(response.body());
  }

  private static HttpResponse<String> send(String method, URI base, String path, String token, String body)
      throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
    return HTTP.send(request.method(method, "GET".equals(method) ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static void respond(HttpExchange exchange, String body) throws IOException {
    respond(exchange, 200, body);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) { output.write(bytes); }
  }
}
