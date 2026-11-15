package org.uacrf.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.uacrf.advisory.AdvisoryAssessmentProbe;
import org.uacrf.security.DelegationJwtEncoderFactory;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.DelegationTokenIssuer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AssessmentAdvisoryIntegrationTests {
  private static final String KEY_ID = "advisory-assessment-test";
  private static final KeyPair KEY_PAIR = generateKeyPair();
  private static final String MANIFEST = "bc".repeat(32);
  private static final String EXCERPT = "metrics guidance";
  private static final String EXCERPT_DIGEST = digest(EXCERPT);

  @Container
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81")
      .withDatabaseName("uacrf_assessment").withUsername("assessment_test").withPassword("assessment_test");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("spring.flyway.locations", AssessmentTestMigrations::location);
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add("uacrf.delegation.public-key-base64",
        () -> Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded()));
    properties.add("uacrf.delegation.key-id", () -> KEY_ID);
  }

  @LocalServerPort private int port;
  @Autowired private ObjectMapper json;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void advisoryClientRequiresAnAuthorizedSealedCaseAndMatchingRetrieval() throws Exception {
    String writer = token("writer", "tenant-advisory", List.of("assessment.case.write"));
    String reader = token("requester", "tenant-advisory", List.of("assessment.case.read"));
    String caseBody = """
        {"workload_id":"workload-1","purpose":"assessment","metric":"latency",
         "comparator":"LTE","threshold":10,"unit":"ms","window_seconds":3600,
         "objective_ref":{"objective_id":"o1","revision":1,"digest":"od"},
         "graph_ref":{"graph_id":"g1","revision":1,"digest":"gd"},
         "required_source_kinds":["metrics"]}
        """;
    HttpResponse<String> created = post("/v1/cases", writer, caseBody);
    assertEquals(201, created.statusCode(), created.body());
    String caseId = json.readTree(created.body()).get("id").asString();
    URI assessment = URI.create("http://127.0.0.1:" + port);
    AtomicInteger receiptCalls = new AtomicInteger();
    AtomicInteger contentCalls = new AtomicInteger();
    AtomicReference<String> caseDigest = new AtomicReference<>();
    HttpServer knowledge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    knowledge.createContext("/internal/v1/retrievals/retrieval-1:receipt", exchange -> {
      receiptCalls.incrementAndGet();
      JsonNode request = json.readTree(exchange.getRequestBody());
      boolean valid = "POST".equals(exchange.getRequestMethod())
          && "Bearer knowledge-delegation".equals(exchange.getRequestHeaders().getFirst("Authorization"))
          && caseId.equals(request.path("case_id").asString())
          && "assessment".equals(request.path("purpose").asString())
          && "INTERNAL".equals(request.path("classification").asString())
          && MANIFEST.equals(request.path("manifest_digest").asString());
      respond(exchange, valid ? 200 : 403, valid ? retrieval(caseId, caseDigest.get(), false) : "{}");
    });
    knowledge.createContext("/v1/retrievals/retrieval-1", exchange -> {
      contentCalls.incrementAndGet();
      boolean valid = "GET".equals(exchange.getRequestMethod())
          && "Bearer knowledge-delegation".equals(exchange.getRequestHeaders().getFirst("Authorization"));
      respond(exchange, valid ? 200 : 403, valid ? retrieval(caseId, caseDigest.get(), true) : "{}");
    });
    knowledge.start();
    try {
      URI knowledgeBase = URI.create("http://127.0.0.1:" + knowledge.getAddress().getPort());
      assertEquals("Internal service request failed with HTTP 409.",
          assertThrows(IOException.class,
              () -> AdvisoryAssessmentProbe.fetch(assessment, knowledgeBase, caseId, MANIFEST, reader))
              .getMessage());
      assertEquals(0, receiptCalls.get());

      HttpResponse<String> sealed = post("/v1/cases/" + caseId + ":seal", writer, "{\"expected_revision\":1}");
      assertEquals(200, sealed.statusCode(), sealed.body());
      caseDigest.set(json.readTree(sealed.body()).get("case_digest").asString());
      assertEquals(List.of(EXCERPT), AdvisoryAssessmentProbe.fetch(assessment, knowledgeBase, caseId, MANIFEST, reader));
      assertEquals(1, receiptCalls.get());
      assertEquals(1, contentCalls.get());

      assertEquals("Internal service request failed with HTTP 403.",
          assertThrows(IOException.class,
              () -> AdvisoryAssessmentProbe.fetch(assessment, knowledgeBase, caseId, MANIFEST, writer))
              .getMessage());
      String foreignReader = token("requester", "tenant-other", List.of("assessment.case.read"));
      assertEquals("Internal service request failed with HTTP 404.",
          assertThrows(IOException.class,
              () -> AdvisoryAssessmentProbe.fetch(assessment, knowledgeBase, caseId, MANIFEST, foreignReader))
              .getMessage());
      String wrongAudience = token("requester", "tenant-advisory", List.of("assessment.case.read"), "S08");
      assertEquals("Internal service request failed with HTTP 401.",
          assertThrows(IOException.class,
              () -> AdvisoryAssessmentProbe.fetch(assessment, knowledgeBase, caseId, MANIFEST, wrongAudience))
              .getMessage());
      assertEquals(1, receiptCalls.get());
      assertEquals(1, contentCalls.get());

      caseDigest.set((caseDigest.get().startsWith("0") ? "1" : "0") + caseDigest.get().substring(1));
      assertEquals("Owner receipt consistency check failed.",
          assertThrows(IOException.class,
              () -> AdvisoryAssessmentProbe.fetch(assessment, knowledgeBase, caseId, MANIFEST, reader))
              .getMessage());
      assertEquals(2, receiptCalls.get());
      assertEquals(1, contentCalls.get());
    } finally {
      knowledge.stop(0);
    }
  }

  private static String retrieval(String caseId, String caseDigest, boolean content) {
    String result = """
        {"item_id":"item-1","version":"v1","chunk_index":0,"start_offset":0,"end_offset":%d,
         "source_digest":"%s","excerpt_digest":"%s","allowed_purposes":["assessment"],
         "classification":"INTERNAL","score":1%s}
        """.formatted(EXCERPT.codePointCount(0, EXCERPT.length()), "cd".repeat(32), EXCERPT_DIGEST,
        content ? ",\"excerpt\":\"" + EXCERPT + "\"" : "");
    return """
        {"retrieval_id":"retrieval-1","requested_case_id":"%s","case_id":"%s",
         "case_binding":"VERIFIED","case_digest":"%s","workload_id":"workload-1",
         "purpose":"assessment","classification":"INTERNAL","max_classification":"INTERNAL",
         "manifest_digest":"%s","query":"metrics","created_at":"2026-01-01T00:00:00Z",
         "eligible":true,"reason":"ELIGIBLE","results":[%s]}
        """.formatted(caseId, caseId, caseDigest, MANIFEST, result);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private HttpResponse<String> post(String path, String token, String body)
      throws IOException, InterruptedException {
    return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String token(String subject, String tenant, List<String> capabilities) {
    return token(subject, tenant, capabilities, "S07");
  }

  private static String token(String subject, String tenant, List<String> capabilities, String audience) {
    var principal = new DelegationPrincipal(subject, tenant, "env-1", "*", "https://idp.example.test",
        subject, capabilities, List.of(), List.of());
    return new DelegationTokenIssuer(DelegationJwtEncoderFactory.create(KEY_PAIR, KEY_ID),
        Clock.systemUTC(), "https://uacrf.local/s01", KEY_ID).issue(principal, audience);
  }

  private static KeyPair generateKeyPair() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static String digest(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
