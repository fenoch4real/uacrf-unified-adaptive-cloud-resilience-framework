package org.uacrf.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.uacrf.security.DelegationJwtEncoderFactory;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.DelegationTokenIssuer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AssessmentReceiptVerificationTests {
  private static final String KEY_ID = "assessment-receipt-test-key";
  private static final KeyPair KEY_PAIR = keyPair();
  private static final DelegationTokenIssuer ISSUER = new DelegationTokenIssuer(
      DelegationJwtEncoderFactory.create(KEY_PAIR, KEY_ID), Clock.systemUTC(),
      "https://uacrf.local/s01", KEY_ID);
  private static final StubOwners OWNERS = new StubOwners();
  private static final List<String> ALL = List.of("assessment.case.read", "assessment.case.write",
      "assessment.evaluation.run", "assessment.evaluation.read", "assessment.recommendation.read");
  @Container private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81")
      .withDatabaseName("uacrf_assessment_receipts").withUsername("assessment_test").withPassword("assessment_test");

  @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    p.add("spring.datasource.username", POSTGRES::getUsername);
    p.add("spring.datasource.password", POSTGRES::getPassword);
    p.add("uacrf.delegation.public-key-base64", () -> Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded()));
    p.add("uacrf.delegation.key-id", () -> KEY_ID);
    p.add("uacrf.assessment.receipts.enabled", () -> true);
    p.add("uacrf.assessment.receipts.token-url", OWNERS::tokenUrl);
    p.add("uacrf.assessment.receipts.client-id", () -> "uacrf-s07-evaluator");
    p.add("uacrf.assessment.receipts.client-secret", () -> "test-s07-secret");
    p.add("uacrf.assessment.receipts.delegation-url", OWNERS::delegationUrl);
    p.add("uacrf.assessment.receipts.objectives-base-url", OWNERS::baseUrl);
    p.add("uacrf.assessment.receipts.topology-base-url", OWNERS::baseUrl);
  }

  @AfterAll static void stopStub() { OWNERS.stop(); }
  @BeforeEach void resetStub() { OWNERS.reset(); }
  @LocalServerPort private int port;
  @Autowired private ObjectMapper json;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test void matchingReceiptsAreRetainedAndDelegationsAreCached() throws Exception {
    String token = token("t-receipts-match");
    String id = sealed(token);
    String payload = evalJson("2026-01-01T01:00:00Z", measurement());
    JsonNode first = body(evaluate(id, token, payload), 201);
    JsonNode second = body(evaluate(id, token, payload), 201);
    assertEquals("CONFIRMED_SUCCESS", first.get("outcome").asString());
    assertEquals("VERIFIED", first.get("dependency_vector").get("receipt_verification").asString());
    assertEquals("w1", first.at("/dependency_vector/objective_receipt/workload_id").asString());
    assertEquals("set-od", first.at("/dependency_vector/objective_receipt/set_digest").asString());
    assertEquals("o1", first.at("/dependency_vector/objective_receipt/objective_id").asString());
    assertEquals("od", first.at("/dependency_vector/objective_receipt/digest").asString());
    assertEquals("g1", first.at("/dependency_vector/graph_receipt/graph_id").asString());
    assertEquals("gd", first.at("/dependency_vector/graph_receipt/digest").asString());
    assertEquals(first.get("result_digest").asString(), second.get("result_digest").asString());
    assertEquals(1, OWNERS.delegationCount("t-receipts-match", "S05"));
    assertEquals(1, OWNERS.delegationCount("t-receipts-match", "S06"));
    assertEquals(0, body(get("/v1/recommendations?workload_id=w1", token), 200).size());
  }

  @Test void staleObjectiveRevisionForcesInsufficientEvidence() throws Exception {
    OWNERS.objectiveMode = "STALE";
    String token = token("t-receipts-stale");
    JsonNode result = body(evaluate(sealed(token), token,
        evalJson("2026-01-01T01:00:00Z", measurement())), 201);
    assertEquals("INSUFFICIENT_EVIDENCE", result.get("outcome").asString());
    assertEquals("OBJECTIVE_STALE", result.at("/dependency_vector/receipt_reason_code").asString());
    assertEquals(2, result.at("/dependency_vector/objective_receipt/revision").asInt());
    assertEquals(1, OWNERS.delegationCount("t-receipts-stale", "S06"));
  }

  @Test void ineligibleGraphForcesInsufficientEvidence() throws Exception {
    OWNERS.graphMode = "INELIGIBLE";
    String token = token("t-receipts-graph");
    JsonNode result = body(evaluate(sealed(token), token,
        evalJson("2026-01-01T01:00:00Z", measurement().replace("\"value\":5", "\"value\":20"))), 201);
    assertEquals("INSUFFICIENT_EVIDENCE", result.get("outcome").asString());
    assertEquals("GRAPH_NOT_ELIGIBLE", result.at("/dependency_vector/receipt_reason_code").asString());
    assertEquals("FAILED", result.at("/dependency_vector/receipt_verification").asString());
    assertThat(result.get("result_digest").asString()).hasSize(64);
    assertEquals(0, body(get("/v1/recommendations?workload_id=w1", token), 200).size());
  }

  @Test void ownerHttpFailureIsRetainedAsUnavailable() throws Exception {
    OWNERS.objectiveMode = "ERROR";
    String token = token("t-receipts-owner-error");
    JsonNode result = body(evaluate(sealed(token), token,
        evalJson("2026-01-01T01:00:00Z", measurement().replace("\"value\":5", "\"value\":20"))), 201);
    assertEquals("INSUFFICIENT_EVIDENCE", result.get("outcome").asString());
    assertEquals("OWNER_UNAVAILABLE", result.at("/dependency_vector/receipt_reason_code").asString());
    assertEquals(1, OWNERS.delegationCount("t-receipts-owner-error", "S06"));
  }

  @Test void refusedOwnerConnectionIsUnavailable() {
    HttpReceiptVerifier verifier = new HttpReceiptVerifier(true, OWNERS.tokenUrl(), "uacrf-s07-evaluator",
        "secret", OWNERS.delegationUrl(), "http://127.0.0.1:1", OWNERS.baseUrl(),
        RestClient.create());
    AssessmentResponses.Case item = new AssessmentResponses.Case("case", "w1", "assessment", "latency",
        AssessmentRequests.Comparator.LTE, java.math.BigDecimal.TEN, "ms", 3600,
        new AssessmentRequests.ObjectiveRef("o1", 1, "od"),
        new AssessmentRequests.GraphRef("g1", 1, "gd"), List.of("a"), "SEALED", 2, "case-digest");
    ReceiptVerifier.Verification result = verifier.verify(item, "t-connection-refused", "env-1");
    assertEquals("OWNER_UNAVAILABLE", result.reasonCode());
  }

  private String token(String tenant) {
    DelegationPrincipal p = new DelegationPrincipal("https://uacrf.local/s01|user", tenant, "env-1", "*",
        "https://idp.example.test", "user", ALL, List.of(), List.of());
    return "Bearer " + ISSUER.issue(p, "S07");
  }
  private String sealed(String token) throws Exception {
    JsonNode created = body(request("/v1/cases", token,
        "{\"workload_id\":\"w1\",\"purpose\":\"assessment\",\"metric\":\"latency\","
            + "\"comparator\":\"LTE\",\"threshold\":10,\"unit\":\"ms\",\"window_seconds\":3600,"
            + "\"objective_ref\":{\"objective_id\":\"o1\",\"revision\":1,\"digest\":\"od\"},"
            + "\"graph_ref\":{\"graph_id\":\"g1\",\"revision\":1,\"digest\":\"gd\"},"
            + "\"required_source_kinds\":[\"a\"]}"), 201);
    String id = created.get("id").asString();
    body(request("/v1/cases/" + id + ":seal", token, "{\"expected_revision\":1}"), 200);
    return id;
  }
  private static String measurement() {
    return "{\"source_receipt_id\":\"source-1\",\"source_kind\":\"a\","
        + "\"observed_at\":\"2026-01-01T00:59:00Z\",\"value\":5,\"unit\":\"ms\"}";
  }
  private static String evalJson(String cutoff, String measurement) {
    return "{\"observation_cutoff\":\"" + cutoff + "\",\"mode\":\"LIVE\",\"measurements\":[" + measurement + "]}";
  }
  private HttpResponse<String> request(String path, String token, String payload) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Authorization", token).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
  }
  private HttpResponse<String> evaluate(String id, String token, String payload) throws Exception {
    return request("/v1/cases/" + id + ":evaluate", token, payload);
  }
  private HttpResponse<String> get(String path, String token) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Authorization", token).GET().build(), HttpResponse.BodyHandlers.ofString());
  }
  private JsonNode body(HttpResponse<String> response, int status) {
    assertEquals(status, response.statusCode(), response.body());
    try { return json.readTree(response.body()); } catch (Exception error) { throw new AssertionError(error); }
  }
  private static KeyPair keyPair() {
    try { KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair(); }
    catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
  }

  private static final class StubOwners {
    private final HttpServer server;
    private volatile String objectiveMode = "MATCH";
    private volatile String graphMode = "MATCH";
    private final Map<String, AtomicInteger> delegationCounts = new ConcurrentHashMap<>();
    StubOwners() {
      try {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> respond(exchange, 200,
            tokenResponse("identity-token", 300)));
        server.createContext("/delegations", exchange -> {
          String request = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
          String audience = request.contains("S05") ? "S05" : "S06";
          String tenant = extract(request, "tenant_id");
          String capability = "S05".equals(audience) ? "objectives.objective.read" : "topology.graph.read";
          if (!"Bearer identity-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))
              || !request.contains("\"capability_ids\":[\"" + capability + "\"]")) {
            respond(exchange, 403, "{}");
            return;
          }
          delegationCounts.computeIfAbsent(tenant + "/" + audience, ignored -> new AtomicInteger()).incrementAndGet();
          respond(exchange, 200, "{\"access_token\":\"delegation-" + audience + "\",\"expires_in\":30}");
        });
        server.createContext("/internal/v1/workloads/", exchange -> {
          String request = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
          if (!"Bearer delegation-S05".equals(exchange.getRequestHeaders().getFirst("Authorization"))
              || !request.contains("\"purpose\":\"assessment\"")) {
            respond(exchange, 403, "{}");
            return;
          }
          if ("ERROR".equals(objectiveMode)) { respond(exchange, 500, "{} "); return; }
          String revision = "STALE".equals(objectiveMode) ? "2" : "1";
          respond(exchange, 200, "{\"workload_id\":\"w1\",\"purpose\":\"assessment\","
              + "\"objectives\":[{\"id\":\"o1\",\"revision\":" + revision
              + ",\"content_digest\":\"od\"}],\"set_digest\":\"set-od\"}");
        });
        server.createContext("/internal/v1/graphs/", exchange -> {
          if (!"Bearer delegation-S06".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 403, "{}");
            return;
          }
          boolean eligible = !"INELIGIBLE".equals(graphMode);
          respond(exchange, 200, "{\"id\":\"g1\",\"scope\":\"w1\",\"revision\":1,"
              + "\"digest\":\"gd\",\"status\":\"ACCEPTED\","
              + "\"projection_status\":\"PROJECTED\",\"eligibility\":" + eligible + "}");
        });
        server.start();
      } catch (IOException error) { throw new IllegalStateException(error); }
    }
    String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    String tokenUrl() { return baseUrl() + "/token"; }
    String delegationUrl() { return baseUrl() + "/delegations"; }
    void stop() { server.stop(0); }
    void reset() { objectiveMode = "MATCH"; graphMode = "MATCH"; }
    int delegationCount(String tenant, String audience) {
      AtomicInteger count = delegationCounts.get(tenant + "/" + audience);
      return count == null ? 0 : count.get();
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
      byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    private static String tokenResponse(String token, int expires) {
      return "{\"access_token\":\"" + token + "\",\"expires_in\":" + expires + "}";
    }
    private static String extract(String json, String key) {
      String marker = "\"" + key + "\":\"";
      int start = json.indexOf(marker);
      if (start < 0) return "unknown";
      start += marker.length();
      int end = json.indexOf('"', start);
      return end < 0 ? "unknown" : json.substring(start, end);
    }
  }
}
