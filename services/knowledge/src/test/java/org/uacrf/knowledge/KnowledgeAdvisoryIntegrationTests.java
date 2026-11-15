package org.uacrf.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.uacrf.advisory.AdvisoryKnowledgeProbe;
import org.uacrf.security.DelegationJwtEncoderFactory;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.DelegationTokenIssuer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(KnowledgeAdvisoryIntegrationTests.CaseFixture.class)
class KnowledgeAdvisoryIntegrationTests {
  private static final String KEY_ID = "advisory-knowledge-test";
  private static final String CASE_DIGEST = "ab".repeat(32);
  private static final String CONTENT = "metrics 😀 guidance";
  private static final KeyPair KEY_PAIR = generateKeyPair();
  private static final AtomicBoolean CASE_AVAILABLE = new AtomicBoolean(true);
  private static final AtomicReference<String> LIVE_CASE_DIGEST = new AtomicReference<>(CASE_DIGEST);
  private static final AtomicReference<Runnable> CASE_CHECK_HOOK = new AtomicReference<>();
  private static final String POSTGRES_IMAGE =
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(POSTGRES_IMAGE)
          .withDatabaseName("uacrf_knowledge")
          .withUsername("knowledge_test")
          .withPassword("knowledge_test");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("spring.flyway.locations", () -> "filesystem:"
        + java.nio.file.Path.of(URI.create(
            KnowledgeApplication.class.getProtectionDomain().getCodeSource().getLocation().toString()))
            .resolve("db/migration"));
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
  private HttpServer assessment;

  @BeforeEach
  void startAssessmentFixture() throws IOException {
    CASE_AVAILABLE.set(true);
    LIVE_CASE_DIGEST.set(CASE_DIGEST);
    CASE_CHECK_HOOK.set(null);
    assessment = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    assessment.createContext("/internal/v1/cases/case-1:receipt", exchange -> {
      int status = "POST".equals(exchange.getRequestMethod())
          && "Bearer case-delegation".equals(exchange.getRequestHeaders().getFirst("Authorization")) ? 200 : 403;
      String body = status == 200
          ? "{\"case_id\":\"case-1\",\"purpose\":\"incident\",\"case_digest\":\"" + CASE_DIGEST
              + "\",\"workload_id\":\"workload-1\",\"required_source_kinds\":[\"metrics\"]}"
          : "{}";
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
    assessment.start();
  }

  @AfterEach
  void stopAssessmentFixture() {
    CASE_CHECK_HOOK.set(null);
    assessment.stop(0);
  }

  @Test
  void advisoryClientReadsRealKnowledgeAndRejectsChangedEligibility() throws Exception {
    String writer = token("writer", "tenant-advisory",
        List.of("knowledge.item.write", "knowledge.classification.internal.read"));
    String reviewer = token("reviewer", "tenant-advisory",
        List.of("knowledge.item.review", "knowledge.classification.internal.read"));
    String runner = token("requester", "tenant-advisory",
        List.of("knowledge.retrieval.run", "knowledge.classification.internal.read"));
    String reader = token("requester", "tenant-advisory",
        List.of("knowledge.retrieval.read", "knowledge.classification.internal.read"));
    var stage = Map.of("item_id", "guidance", "version", "v1", "title", "Guidance",
        "content", CONTENT, "classification", "INTERNAL", "allowed_purposes", List.of("incident"),
        "target_workload_ids", List.of(), "review_deadline", Instant.now().plusSeconds(300).toString(),
        "reason", "Independent review required");
    assertEquals(201, post("/v1/knowledge-items", writer, json.writeValueAsString(stage)).statusCode());
    assertEquals(200, post("/v1/knowledge-items/guidance:review", reviewer,
        "{\"decision\":\"APPROVE\",\"expected_revision\":1,\"reason\":\"Approved\"}").statusCode());
    var response = post("/v1/retrievals", runner,
        "{\"case_id\":\"case-1\",\"purpose\":\"incident\",\"max_classification\":\"INTERNAL\","
            + "\"query\":\"metrics\",\"max_results\":10}");
    assertEquals(201, response.statusCode(), response.body());
    JsonNode retrieval = json.readTree(response.body());
    String id = retrieval.get("retrieval_id").asText();
    String digest = retrieval.get("manifest_digest").asText();
    URI owner = URI.create("http://127.0.0.1:" + assessment.getAddress().getPort());
    URI knowledge = URI.create("http://127.0.0.1:" + port);
    assertEquals(List.of(CONTENT), AdvisoryKnowledgeProbe.fetch(owner, knowledge, id, digest, reader));
    AdvisoryKnowledgeProbe.verifyWithSource(owner, knowledge, id, digest, reader, CONTENT);
    assertThrows(IllegalArgumentException.class,
        () -> AdvisoryKnowledgeProbe.verifyWithSource(owner, knowledge, id, digest, reader, CONTENT + " altered"));
    String unclassified = token("requester", "tenant-advisory", List.of("knowledge.retrieval.read"));
    assertEquals("Internal service request failed with HTTP 403.",
        assertThrows(IOException.class,
            () -> AdvisoryKnowledgeProbe.fetch(owner, knowledge, id, digest, unclassified)).getMessage());
    String otherTenant = token("requester", "tenant-other",
        List.of("knowledge.retrieval.read", "knowledge.classification.internal.read"));
    assertEquals("Internal service request failed with HTTP 404.",
        assertThrows(IOException.class,
            () -> AdvisoryKnowledgeProbe.fetch(owner, knowledge, id, digest, otherTenant)).getMessage());
    CASE_AVAILABLE.set(false);
    assertIneligible(id, digest, reader, "CASE_UNVERIFIED");
    assertEquals("Owner receipt consistency check failed.",
        assertThrows(IOException.class, () -> AdvisoryKnowledgeProbe.fetch(owner, knowledge, id, digest, reader))
            .getMessage());
    CASE_AVAILABLE.set(true);
    LIVE_CASE_DIGEST.set("cd".repeat(32));
    assertIneligible(id, digest, reader, "CASE_UNVERIFIED");
    assertEquals("Owner receipt consistency check failed.",
        assertThrows(IOException.class, () -> AdvisoryKnowledgeProbe.fetch(owner, knowledge, id, digest, reader))
            .getMessage());
    LIVE_CASE_DIGEST.set(CASE_DIGEST);
    assertEquals(List.of(CONTENT), AdvisoryKnowledgeProbe.fetch(owner, knowledge, id, digest, reader));
    String withdrawer = token("withdrawer", "tenant-advisory", List.of("knowledge.item.withdraw"));
    AtomicInteger withdrawalStatus = new AtomicInteger();
    HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    proxy.createContext("/", exchange -> {
      URI target = knowledge.resolve(exchange.getRequestURI().toString());
      var request = HttpRequest.newBuilder(target)
          .header("Authorization", exchange.getRequestHeaders().getFirst("Authorization"));
      if ("POST".equals(exchange.getRequestMethod())) {
        request.header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes()));
      } else {
        request.GET();
      }
      try {
        var forwarded = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        if ("GET".equals(exchange.getRequestMethod()) && forwarded.statusCode() == 200) {
          withdrawalStatus.set(post("/v1/knowledge-items/guidance:withdraw", withdrawer,
              "{\"expected_revision\":2,\"reason\":\"Source withdrawn\"}").statusCode());
        }
        exchange.sendResponseHeaders(forwarded.statusCode(), forwarded.body().length);
        try (var output = exchange.getResponseBody()) {
          output.write(forwarded.body());
        }
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IOException("Test proxy was interrupted.", failure);
      }
    });
    proxy.start();
    try {
      URI proxyBase = URI.create("http://127.0.0.1:" + proxy.getAddress().getPort());
      assertEquals("Retrieval receipt changed during content checking.",
          assertThrows(IOException.class, () -> AdvisoryKnowledgeProbe.fetch(owner, proxyBase, id, digest, reader))
              .getMessage());
      assertEquals(200, withdrawalStatus.get());
    } finally {
      proxy.stop(0);
    }
    assertIneligible(id, digest, reader, "SOURCE_NOT_ELIGIBLE");
    assertEquals("Owner receipt consistency check failed.",
        assertThrows(IOException.class, () -> AdvisoryKnowledgeProbe.fetch(owner, knowledge, id, digest, reader))
            .getMessage());
  }

  @Test
  void withdrawalDuringCaseVerificationIsVisibleToContentAndReceiptReads() throws Exception {
    String tenant = "tenant-advisory";
    String writer = token("writer", tenant,
        List.of("knowledge.item.write", "knowledge.classification.internal.read"));
    String reviewer = token("reviewer", tenant,
        List.of("knowledge.item.review", "knowledge.classification.internal.read"));
    String runner = token("requester", tenant,
        List.of("knowledge.retrieval.run", "knowledge.classification.internal.read"));
    String reader = token("requester", tenant,
        List.of("knowledge.retrieval.read", "knowledge.classification.internal.read"));
    String withdrawer = token("withdrawer", tenant, List.of("knowledge.item.withdraw"));
    for (String item : List.of("content-race", "receipt-race")) {
      var stage = Map.of("item_id", item, "version", "v1", "title", item,
          "content", item + " unique guidance", "classification", "INTERNAL",
          "allowed_purposes", List.of("incident"), "target_workload_ids", List.of(),
          "review_deadline", Instant.now().plusSeconds(300).toString(), "reason", "review");
      assertEquals(201, post("/v1/knowledge-items", writer, json.writeValueAsString(stage)).statusCode());
      assertEquals(200, post("/v1/knowledge-items/" + item + ":review", reviewer,
          "{\"decision\":\"APPROVE\",\"expected_revision\":1,\"reason\":\"Approved\"}").statusCode());
    }

    var contentRun = post("/v1/retrievals", runner,
        "{\"case_id\":\"case-1\",\"purpose\":\"incident\",\"max_classification\":\"INTERNAL\","
            + "\"query\":\"content\",\"max_results\":10}");
    var receiptRun = post("/v1/retrievals", runner,
        "{\"case_id\":\"case-1\",\"purpose\":\"incident\",\"max_classification\":\"INTERNAL\","
            + "\"query\":\"receipt\",\"max_results\":10}");
    assertEquals(201, contentRun.statusCode(), contentRun.body());
    assertEquals(201, receiptRun.statusCode(), receiptRun.body());
    JsonNode content = json.readTree(contentRun.body());
    JsonNode receipt = json.readTree(receiptRun.body());
    assertEquals(1, content.get("results").size());
    assertEquals(1, receipt.get("results").size());
    AtomicInteger withdrawalStatus = new AtomicInteger();
    CASE_CHECK_HOOK.set(() -> withdrawDuringCaseCheck("content-race", withdrawer, withdrawalStatus));
    var read = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
            + "/v1/retrievals/" + content.get("retrieval_id").asText()))
        .header("Authorization", "Bearer " + reader).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, withdrawalStatus.get());
    assertEquals(200, read.statusCode(), read.body());
    assertEquals(true, json.readTree(read.body()).get("results").get(0).get("excerpt").isNull());

    withdrawalStatus.set(0);
    CASE_CHECK_HOOK.set(() -> withdrawDuringCaseCheck("receipt-race", withdrawer, withdrawalStatus));
    assertIneligible(receipt.get("retrieval_id").asText(), receipt.get("manifest_digest").asText(),
        reader, "SOURCE_NOT_ELIGIBLE");
    assertEquals(200, withdrawalStatus.get());
  }

  private void withdrawDuringCaseCheck(String item, String token, AtomicInteger status) {
    try {
      status.set(post("/v1/knowledge-items/" + item + ":withdraw", token,
          "{\"expected_revision\":2,\"reason\":\"Source withdrawn\"}").statusCode());
    } catch (IOException failure) {
      throw new java.io.UncheckedIOException(failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Withdrawal interrupted.", failure);
    }
  }

  private void assertIneligible(String id, String digest, String token, String reason)
      throws IOException, InterruptedException {
    var response = post("/internal/v1/retrievals/" + id + ":receipt", token,
        "{\"case_id\":\"case-1\",\"purpose\":\"incident\","
            + "\"classification\":\"INTERNAL\",\"manifest_digest\":\"" + digest + "\"}");
    assertEquals(200, response.statusCode(), response.body());
    JsonNode receipt = json.readTree(response.body());
    assertEquals(false, receipt.get("eligible").asBoolean());
    assertEquals(reason, receipt.get("reason").asText());
  }

  private HttpResponse<String> post(String path, String token, String body) throws IOException, InterruptedException {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String token(String subject, String tenant, List<String> capabilities) {
    var principal = new DelegationPrincipal(subject, tenant, "env-1", "*", "https://idp.example.test",
        subject, capabilities, List.of(), List.of());
    return new DelegationTokenIssuer(DelegationJwtEncoderFactory.create(KEY_PAIR, KEY_ID),
        Clock.systemUTC(), "https://uacrf.local/s01", KEY_ID).issue(principal, "S08");
  }

  private static KeyPair generateKeyPair() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  @TestConfiguration
  static class CaseFixture {
    @Bean
    @Primary
    CaseReceiptVerifier caseReceiptVerifier() {
      return new CaseReceiptVerifier() {
        @Override public boolean enabled() { return true; }
        @Override public Verification verify(String caseId, String purpose, String tenantId, String environmentId) {
          Runnable hook = CASE_CHECK_HOOK.getAndSet(null);
          if (hook != null) hook.run();
          if (!CASE_AVAILABLE.get() || !"case-1".equals(caseId) || !"incident".equals(purpose)
              || !"tenant-advisory".equals(tenantId) || !"env-1".equals(environmentId)) {
            return Verification.failed("CASE_UNVERIFIED");
          }
          return Verification.verified(new CaseReceipt(
              "case-1", "incident", LIVE_CASE_DIGEST.get(), "workload-1", List.of("metrics")));
        }
      };
    }
  }
}
