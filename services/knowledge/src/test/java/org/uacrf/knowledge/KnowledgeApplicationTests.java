package org.uacrf.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.uacrf.security.DelegationJwtEncoderFactory;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.DelegationTokenIssuer;
import org.uacrf.knowledge.KnowledgeRequests.Classification;
import org.uacrf.knowledge.KnowledgeRequests.ReceiptRequest;
import org.uacrf.knowledge.KnowledgeRequests.RunRetrieval;
import org.uacrf.knowledge.KnowledgeResponses.ReceiptResult;
import org.uacrf.knowledge.KnowledgeResponses.Retrieval;
import org.uacrf.knowledge.KnowledgeResponses.RetrievalReceipt;
import org.uacrf.knowledge.KnowledgeResponses.RetrievalResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class KnowledgeApplicationTests {
  private static final String KEY_ID = "knowledge-test-key";
  private static final KeyPair KEY_PAIR = generateKeyPair();
  private static final String POSTGRES_IMAGE =
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81";
  private static final List<String> WRITER =
      List.of("knowledge.item.write", "knowledge.item.read", "knowledge.classification.internal.read");
  private static final List<String> REVIEWER =
      List.of("knowledge.item.review", "knowledge.item.read", "knowledge.classification.internal.read");
  private static final List<String> RETRIEVER =
      List.of(
          "knowledge.retrieval.run",
          "knowledge.retrieval.read",
          "knowledge.classification.internal.read",
          "knowledge.classification.restricted.read");
  private static final List<String> READER = List.of("knowledge.retrieval.read");
  private static final List<String> INTERNAL_READER =
      List.of("knowledge.retrieval.read", "knowledge.classification.internal.read");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(POSTGRES_IMAGE)
          .withDatabaseName("uacrf_knowledge")
          .withUsername("knowledge_test")
          .withPassword("knowledge_test");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    // The test-only advisory dependency also contains Flyway migrations.
    properties.add("spring.flyway.locations", () -> "filesystem:"
        + java.nio.file.Path.of(URI.create(
            KnowledgeApplication.class.getProtectionDomain().getCodeSource().getLocation().toString()))
            .resolve("db/migration"));
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
  @Autowired private ObjectMapper objectMapper;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void candidateVersionsAreMetadataOnlyAndNeverSearchableUntilApproved() throws Exception {
    String writer = bearer("proposer", "t-candidate", WRITER);
    assertEquals(201, post("/v1/knowledge-items", writer, stage("candidate", "v1", "secret draft guidance")).statusCode());
    HttpResponse<String> metadata = get("/v1/knowledge-items?item_id=candidate&purpose=incident", writer);
    assertThat(metadata.body()).contains("\"state\":\"CANDIDATE\"").doesNotContain("secret draft guidance");
    JsonNode retrieval = run(bearer("search", "t-candidate", RETRIEVER), "incident", "secret");
    assertEquals(0, retrieval.get("results").size());
  }

  @Test
  void candidateDoesNotShadowApprovedVersionUntilIndependentApprovalCommits() throws Exception {
    String writer = bearer("proposer", "t-successor", WRITER);
    String reviewer = bearer("reviewer", "t-successor", REVIEWER);
    stageAndApprove("successor", "v1", "old exact phrase", writer, reviewer);

    assertEquals(201, post("/v1/knowledge-items", writer, stage("successor", "v2", "new exact phrase")).statusCode());
    JsonNode beforeReview = run(bearer("search", "t-successor", RETRIEVER), "incident", "exact");
    assertThat(beforeReview.toString()).contains("\"version\":\"v1\"");
    assertThat(post("/v1/knowledge-items/successor:review", reviewer, review("APPROVE", 1)).body())
        .contains("\"state\":\"APPROVED\"");
    JsonNode afterReview = run(bearer("search", "t-successor", RETRIEVER), "incident", "exact");
    assertThat(afterReview.toString()).contains("\"version\":\"v2\"").doesNotContain("\"version\":\"v1\"");
    assertThat(get("/v1/knowledge-items?item_id=successor", writer).body())
        .contains("\"version\":\"v1\"")
        .contains("\"state\":\"SUPERSEDED\"");
  }

  @Test
  void reviewRequiresCapabilityIndependentActorExpectedRevisionAndUnexpiredDeadline() throws Exception {
    String writer = bearer("review-proposer", "t-review", WRITER);
    String reviewer = bearer("reviewer-a", "t-review", REVIEWER);
    assertEquals(201, post("/v1/knowledge-items", writer, stage("reviewable", "v1", "review body")).statusCode());
    assertThat(
            post(
                "/v1/knowledge-items/reviewable:review",
                bearer(
                    "review-proposer",
                    "t-review",
                    List.of("knowledge.item.review", "knowledge.item.read")),
                review("APPROVE", 1))
                .body())
        .contains("REVIEWER_NOT_INDEPENDENT");
    assertThat(post(
            "/v1/knowledge-items/reviewable:review",
            bearer("reader", "t-review", List.of("knowledge.item.read")),
            review("APPROVE", 1))
        .body()).contains("PERMISSION_DENIED");
    assertThat(post("/v1/knowledge-items/reviewable:review", reviewer, review("APPROVE", 2)).body())
        .contains("REVISION_MISMATCH");

    String expiring =
        stage("deadline", "v1", "deadline body")
            .replaceFirst(
                "\"review_deadline\":\"[^\"]+\"",
                "\"review_deadline\":\"" + Instant.now().plusMillis(1500) + "\"");
    assertEquals(201, post("/v1/knowledge-items", writer, expiring).statusCode());
    Thread.sleep(1700);
    assertThat(post("/v1/knowledge-items/deadline:review", reviewer, review("APPROVE", 1)).body())
        .contains("REVIEW_DEADLINE_EXPIRED");
  }

  @Test
  void expiredReviewExcludesNewRetrievalsAndConcealsRetainedContentAndReceipts() throws Exception {
    String tenant = "t-expired-source";
    String writer = bearer("proposer", tenant, WRITER);
    String reviewer = bearer("reviewer", tenant, REVIEWER);
    Instant deadline = Instant.now().plusSeconds(8);
    String expiring =
        stage("expiring", "v1", "expiring unique guidance")
            .replaceFirst("\"review_deadline\":\"[^\"]+\"",
                "\"review_deadline\":\"" + deadline + "\"");
    stageRequestAndApprove("expiring", expiring, writer, reviewer);

    var verifier = new CaseReceiptVerifier() {
      @Override public boolean enabled() { return true; }
      @Override public Verification verify(String caseId, String purpose, String tenantId, String environmentId) {
        return Verification.verified(new CaseReceipt(caseId, purpose, "ab".repeat(32),
            "workload-a", List.of("telemetry")));
      }
    };
    var service = new KnowledgeService(jdbc, objectMapper, verifier);
    var actor = retrievalPrincipal(tenant, RETRIEVER);
    var request = new RunRetrieval("case-1", "incident", Classification.INTERNAL, "unique", 10, null);
    Retrieval retained = service.run(actor, request);
    assertThat(retained.results()).hasSize(1);
    var receiptRequest = new ReceiptRequest("case-1", "incident", Classification.INTERNAL,
        retained.manifestDigest());
    assertThat(service.receipt(actor, retained.retrievalId(), receiptRequest).eligible()).isTrue();
    assertThat(service.getRetrieval(actor, retained.retrievalId()).results().getFirst().excerpt())
        .isEqualTo("expiring unique guidance");

    long remainingMillis = java.time.Duration.between(Instant.now(), deadline).toMillis();
    if (remainingMillis > 0) Thread.sleep(remainingMillis + 50);

    assertThat(service.run(actor, request).results()).isEmpty();
    assertThat(service.getRetrieval(actor, retained.retrievalId()).results())
        .allMatch(result -> result.excerpt() == null);
    RetrievalReceipt expired = service.receipt(actor, retained.retrievalId(), receiptRequest);
    assertThat(expired.eligible()).isFalse();
    assertThat(expired.reason()).isEqualTo("SOURCE_NOT_ELIGIBLE");
  }

  @Test
  void retrievalFiltersByPurposeClassificationAndExplicitWorkloadTargets() throws Exception {
    String writer = bearer("proposer", "t-filter", WRITER);
    String reviewer = bearer("reviewer", "t-filter", REVIEWER);
    stageRequestAndApprove(
        "public",
        stage("public", "v1", "shared guidance")
            .replace("\"classification\":\"INTERNAL\"", "\"classification\":\"PUBLIC\""),
        writer,
        reviewer);
    stageRequestAndApprove(
        "restricted",
        stage("restricted", "v1", "shared guidance")
            .replace("\"classification\":\"INTERNAL\"", "\"classification\":\"RESTRICTED\"")
            .replace("\"target_workload_ids\":[]", "\"target_workload_ids\":[\"workload-a\"]"),
        writer,
        reviewer);

    JsonNode limited = run(bearer("search", "t-filter", RETRIEVER), "incident", "guidance", "INTERNAL", null);
    assertThat(limited.toString()).contains("\"item_id\":\"public\"").doesNotContain("\"item_id\":\"restricted\"");
    JsonNode noTarget = run(bearer("search", "t-filter", RETRIEVER), "incident", "guidance", "RESTRICTED", null);
    assertThat(noTarget.toString()).contains("\"item_id\":\"public\"").doesNotContain("\"item_id\":\"restricted\"");
    JsonNode wrongTarget =
        run(bearer("search", "t-filter", RETRIEVER), "incident", "guidance", "RESTRICTED", "workload-b");
    assertThat(wrongTarget.toString()).doesNotContain("\"item_id\":\"restricted\"");
    JsonNode matchingTarget =
        run(bearer("search", "t-filter", RETRIEVER), "incident", "guidance", "RESTRICTED", "workload-a");
    assertThat(matchingTarget.toString()).contains("WORKLOAD_SCOPE_UNVERIFIED");
    JsonNode wrongPurpose = run(bearer("search", "t-filter", RETRIEVER), "recovery", "guidance");
    assertEquals(0, wrongPurpose.get("results").size());
  }

  @Test
  void classificationComesFromCapabilitiesAndCannotBeRaisedByRequest() throws Exception {
    String writer = bearer("proposer", "t-classification", WRITER);
    String reviewer = bearer("reviewer", "t-classification", REVIEWER);
    stageRequestAndApprove(
        "public-only",
        stage("public-only", "v1", "classification guidance")
            .replace("\"classification\":\"INTERNAL\"", "\"classification\":\"PUBLIC\""),
        writer,
        reviewer);
    stageAndApprove("internal-only", "v1", "classification guidance", writer, reviewer);
    stageRequestAndApprove(
        "restricted-only",
        stage("restricted-only", "v1", "classification guidance")
            .replace("\"classification\":\"INTERNAL\"", "\"classification\":\"RESTRICTED\""),
        writer,
        reviewer);

    String publicReader =
        bearer(
            "public-reader",
            "t-classification",
            List.of("knowledge.item.read", "knowledge.retrieval.run", "knowledge.retrieval.read"));
    JsonNode publicResults = run(publicReader, "incident", "classification", "PUBLIC", null);
    assertThat(publicResults.toString()).contains("\"item_id\":\"public-only\"");
    assertThat(publicResults.toString())
        .doesNotContain("\"item_id\":\"internal-only\"")
        .doesNotContain("\"item_id\":\"restricted-only\"");
    assertThat(
            post(
                    "/v1/retrievals",
                    publicReader,
                    retrievalRequest("classification", "incident", "INTERNAL", null, null))
                .statusCode())
        .isEqualTo(403);

    String internalReader =
        bearer(
            "internal-reader",
            "t-classification",
            List.of(
                "knowledge.retrieval.run",
                "knowledge.retrieval.read",
                "knowledge.classification.internal.read"));
    JsonNode internalResults = run(internalReader, "incident", "classification", "INTERNAL", null);
    assertThat(internalResults.toString())
        .contains("\"item_id\":\"public-only\"")
        .contains("\"item_id\":\"internal-only\"")
        .doesNotContain("\"item_id\":\"restricted-only\"");

    String restrictedReader =
        bearer(
            "restricted-reader",
            "t-classification",
            List.of(
                "knowledge.retrieval.run",
                "knowledge.retrieval.read",
                "knowledge.classification.restricted.read"));
    JsonNode restrictedResults = run(restrictedReader, "incident", "classification", "RESTRICTED", null);
    String retrievalId = restrictedResults.get("retrieval_id").asText();
    assertThat(restrictedResults.toString()).contains("\"item_id\":\"restricted-only\"");
    JsonNode downgradedRead =
        getJson("/v1/retrievals/" + retrievalId, publicReader, "GET", null);
    assertThat(downgradedRead.toString()).contains("\"excerpt\":null");

    assertThat(
            getJson(
                    "/v1/knowledge-items?purpose=incident",
                    publicReader)
                .toString())
        .doesNotContain("\"item_id\":\"internal-only\"")
        .doesNotContain("\"item_id\":\"restricted-only\"");
  }

  @Test
  void exactTermRankingAndChunkOffsetsAreDeterministicAndDigestBound() throws Exception {
    String writer = bearer("proposer", "t-ranking", WRITER);
    String reviewer = bearer("reviewer", "t-ranking", REVIEWER);
    stageAndApprove("b-item", "v1", "alpha beta", writer, reviewer);
    stageAndApprove("a-item", "v1", "alpha gamma", writer, reviewer);
    stageAndApprove(
        "chunked",
        "v1",
        "alpha " + "x".repeat(995) + " beta",
        writer,
        reviewer);

    JsonNode results =
        run(bearer("search", "t-ranking", RETRIEVER), "incident", "alpha beta", "RESTRICTED", null)
            .get("results");
    assertThat(results.get(0).get("item_id").asText()).isEqualTo("b-item");
    assertThat(results.get(0).get("score").asInt()).isEqualTo(2);
    assertThat(results.get(1).get("item_id").asText()).isEqualTo("a-item");
    assertThat(results.size()).isEqualTo(4);
    JsonNode firstChunk = results.get(2);
    assertThat(firstChunk.get("item_id").asText()).isEqualTo("chunked");
    assertThat(firstChunk.get("chunk_index").asInt()).isEqualTo(0);
    assertThat(firstChunk.get("start_offset").asInt()).isZero();
    assertThat(firstChunk.get("end_offset").asInt()).isEqualTo(1000);
    JsonNode chunk = results.get(3);
    assertThat(chunk.get("item_id").asText()).isEqualTo("chunked");
    assertThat(chunk.get("chunk_index").asInt()).isEqualTo(1);
    assertThat(chunk.get("start_offset").asInt()).isEqualTo(1000);
    assertThat(chunk.get("end_offset").asInt()).isEqualTo(1006);
    assertThat(chunk.get("excerpt").asText()).endsWith("beta");
    String chunkedContent = "alpha " + "x".repeat(995) + " beta";
    assertThat(chunk.get("source_digest").asText()).isEqualTo(sha256(chunkedContent));
    assertThat(chunk.get("excerpt_digest").asText()).isEqualTo(sha256(chunk.get("excerpt").asText()));

    String repeatedChunk = "repeat " + "x".repeat(993);
    stageAndApprove("duplicates", "v1", repeatedChunk + repeatedChunk, writer, reviewer);
    JsonNode duplicateResults =
        run(bearer("search", "t-ranking", RETRIEVER), "incident", "repeat").get("results");
    assertThat(duplicateResults.size()).isEqualTo(2);
    assertThat(duplicateResults.get(0).get("chunk_index").asInt()).isZero();
    assertThat(duplicateResults.get(1).get("chunk_index").asInt()).isEqualTo(1);
    assertThat(duplicateResults.get(0).get("excerpt_digest").asText())
        .isEqualTo(duplicateResults.get(1).get("excerpt_digest").asText());

    stageRequestAndApprove(
        "unicode",
        stage("unicode", "v1", "🙂".repeat(999) + " beta")
            .replace("[\"incident\"]", "[\"unicode\"]"),
        writer,
        reviewer);
    JsonNode unicode =
        run(bearer("search", "t-ranking", RETRIEVER), "unicode", "beta").get("results").get(0);
    assertThat(unicode.get("start_offset").asInt()).isEqualTo(1000);
    assertThat(unicode.get("end_offset").asInt()).isEqualTo(1004);
    assertThat(unicode.get("excerpt").asText()).isEqualTo("beta");

    stageAndApprove("localized", "v1", "Résilience café 東京", writer, reviewer);
    JsonNode localized =
        run(bearer("search", "t-ranking", RETRIEVER), "incident", "CAFÉ 東京").get("results");
    assertThat(localized.size()).isEqualTo(1);
    assertThat(localized.get(0).get("score").asInt()).isEqualTo(2);
  }

  @Test
  void databaseRejectsSourceAndManifestSubstitution() throws Exception {
    String writer = bearer("proposer", "t-immutable", WRITER);
    String reviewer = bearer("reviewer", "t-immutable", REVIEWER);
    stageAndApprove("immutable", "v1", "immutable guidance", writer, reviewer);
    JsonNode retrieval = run(bearer("search", "t-immutable", RETRIEVER), "incident", "guidance");
    String retrievalId = retrieval.get("retrieval_id").asText();

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE uacrf_knowledge.knowledge_version SET content = 'substituted' "
                    + "WHERE tenant_id = 't-immutable' AND item_id = 'immutable' AND version = 'v1'"));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE uacrf_knowledge.retrieval_result SET excerpt = 'substituted' "
                    + "WHERE tenant_id = 't-immutable' AND retrieval_id = ?",
                retrievalId));
  }

  @Test
  void withdrawalStopsNewSearchAndConcealsPreviouslyRetainedExcerpt() throws Exception {
    String writer = bearer("proposer", "t-withdraw", WRITER);
    String reviewer = bearer("reviewer", "t-withdraw", REVIEWER);
    String withdrawer = bearer("withdrawer", "t-withdraw", List.of("knowledge.item.withdraw"));
    stageAndApprove("withdrawn", "v1", "withdrawn protected text", writer, reviewer);
    JsonNode before = run(bearer("search", "t-withdraw", RETRIEVER), "incident", "protected");
    String retrievalId = before.get("retrieval_id").asText();
    assertThat(before.toString()).contains("withdrawn protected text");

    assertThat(post(
            "/v1/knowledge-items/withdrawn:withdraw",
            withdrawer,
            "{\"expected_revision\":2,\"reason\":\"source no longer approved\"}")
        .body()).contains("\"state\":\"WITHDRAWN\"");
    JsonNode after = getJson("/v1/retrievals/" + retrievalId, bearer("reader", "t-withdraw", READER));
    assertThat(after.toString()).contains("\"excerpt\":null").contains("\"source_digest\"");
    JsonNode newSearch = run(bearer("search", "t-withdraw", RETRIEVER), "incident", "protected");
    assertEquals(0, newSearch.get("results").size());
    assertThat(
            get(
                    "/v1/knowledge-items?item_id=withdrawn",
                    bearer(
                        "reader",
                        "t-withdraw",
                        List.of("knowledge.item.read", "knowledge.classification.internal.read")))
                .body())
        .contains("\"state\":\"WITHDRAWN\"")
        .doesNotContain("withdrawn protected text");
    assertThat(post("/v1/knowledge-items", writer, stage("withdrawn", "v2", "new guidance")).body())
        .contains("ITEM_WITHDRAWN");
  }

  @Test
  void retrievalAndMetadataAreTenantScopedAndRequireCapabilitiesAndGlobalWriteScope() throws Exception {
    String writer = bearer("proposer", "t-isolation", WRITER);
    assertEquals(201, post("/v1/knowledge-items", writer, stage("isolated", "v1", "isolation text")).statusCode());
    assertEquals(
        403,
        post(
                "/v1/knowledge-items",
                token("scoped", "t-isolation", "workload-a", WRITER),
                stage("scoped-item", "v1", "scoped text"))
            .statusCode());
    assertEquals(
        403,
        post(
                "/v1/knowledge-items",
                bearer("reader", "t-isolation", List.of("knowledge.item.read")),
                stage("denied", "v1", "denied text"))
            .statusCode());
    assertEquals(
        0,
        getJson(
                "/v1/knowledge-items?item_id=isolated",
                bearer("reader", "t-other-tenant", List.of("knowledge.item.read")))
            .size());
    JsonNode retrieval = run(bearer("search", "t-isolation", RETRIEVER), "incident", "isolation");
    String retrievalId = retrieval.get("retrieval_id").asText();
    assertEquals(
        404,
        get(
                "/v1/retrievals/" + retrievalId,
                bearer("reader", "t-other-tenant", READER))
            .statusCode());
    assertEquals(
        403,
        get(
                "/v1/retrievals/" + retrievalId,
                bearer("writer", "t-isolation", WRITER))
            .statusCode());
  }

  @Test
  void internalReceiptReturnsManifestFactsButNeverClaimsCaseEligibility() throws Exception {
    String writer = bearer("proposer", "t-receipt", WRITER);
    String reviewer = bearer("reviewer", "t-receipt", REVIEWER);
    stageAndApprove("receiptable", "v1", "receipt material", writer, reviewer);
    JsonNode retrieval = run(bearer("search", "t-receipt", RETRIEVER), "incident", "receipt", "INTERNAL", null, "case-7");
    String retrievalId = retrieval.get("retrieval_id").asText();
    String digest = retrieval.get("manifest_digest").asText();
    JsonNode receipt =
        getJson(
            "/internal/v1/retrievals/" + retrievalId + ":receipt",
            bearer("reader", "t-receipt", INTERNAL_READER),
            "POST",
            "{\"case_id\":\"case-7\",\"purpose\":\"incident\","
                + "\"classification\":\"INTERNAL\",\"manifest_digest\":\"" + digest + "\"}");
    assertThat(receipt.get("eligible").asBoolean()).isFalse();
    assertThat(receipt.get("reason").asText()).isEqualTo("CASE_NOT_BOUND");
    assertThat(receipt.get("case_binding").asText()).isEqualTo("NOT_VERIFIED");
    assertThat(receipt.get("manifest_digest").asText()).isEqualTo(digest);
    assertEquals(retrieval.get("results").size(), receipt.get("results").size());
    assertEquals(
        403,
        post(
                "/internal/v1/retrievals/" + retrievalId + ":receipt",
                bearer("writer", "t-receipt", WRITER),
                "{\"case_id\":\"case-7\",\"purpose\":\"incident\","
                    + "\"classification\":\"INTERNAL\",\"manifest_digest\":\"" + digest + "\"}")
            .statusCode());
    assertEquals(
        404,
        post(
                "/internal/v1/retrievals/" + retrievalId + ":receipt",
                bearer("reader", "t-other-receipt", INTERNAL_READER),
                "{\"case_id\":\"case-7\",\"purpose\":\"incident\","
                    + "\"classification\":\"INTERNAL\",\"manifest_digest\":\"" + digest + "\"}")
            .statusCode());
  }

  @Test
  void verifiedCaseReceiptBindsTargetScopeAndEligibilityAndWithdrawalInvalidatesIt() throws Exception {
    String tenant = "t-verified-case";
    String writer = bearer("proposer", tenant, WRITER);
    String reviewer = bearer("reviewer", tenant, REVIEWER);
    stageRequestAndApprove(
        "target-a",
        stage("target-a", "v1", "verified retrieval material")
            .replace("\"target_workload_ids\":[]", "\"target_workload_ids\":[\"workload-a\"]"),
        writer,
        reviewer);
    stageRequestAndApprove(
        "target-b",
        stage("target-b", "v1", "verified retrieval material")
            .replace("\"target_workload_ids\":[]", "\"target_workload_ids\":[\"workload-b\"]"),
        writer,
        reviewer);
    stageAndApprove("generic", "v1", "verified retrieval material", writer, reviewer);

    String caseDigest = "ab".repeat(32);
    AtomicInteger tokenCalls = new AtomicInteger();
    AtomicInteger delegationCalls = new AtomicInteger();
    AtomicReference<String> delegationRequest = new AtomicReference<>();
    AtomicReference<String> receiptAuthorization = new AtomicReference<>();
    AtomicReference<StubResponse> liveReceipt =
        new AtomicReference<>(
            new StubResponse(
                200,
                "{\"case_id\":\"case-verified\",\"purpose\":\"incident\","
                    + "\"case_digest\":\"" + caseDigest + "\",\"workload_id\":\"workload-a\","
                    + "\"required_source_kinds\":[\"telemetry\"]}"));
    HttpServer server = newCaseReceiptServer(
        tokenCalls,
        delegationCalls,
        delegationRequest,
        receiptAuthorization,
        liveReceipt);
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      HttpCaseReceiptVerifier verifier =
          new HttpCaseReceiptVerifier(
              true,
              base + "/token",
              "uacrf-s08-retriever",
              "test-secret",
              base + "/delegations",
              base,
              RestClient.builder().build());
      KnowledgeService service = new KnowledgeService(jdbc, objectMapper, verifier);
      DelegationPrincipal actor = retrievalPrincipal(tenant, RETRIEVER);
      Retrieval retrieval =
          service.run(actor, new RunRetrieval("case-verified", "incident", Classification.RESTRICTED,
              "verified", 10, null));
      assertEquals("VERIFIED", retrieval.caseBinding());
      assertEquals(caseDigest, retrieval.caseDigest());
      assertEquals("workload-a", retrieval.workloadId());
      assertThat(retrieval.results()).extracting(RetrievalResult::itemId)
          .contains("target-a", "generic")
          .doesNotContain("target-b");
      String retrievalId = retrieval.retrievalId();
      RetrievalReceipt eligible =
          service.receipt(
              actor,
              retrievalId,
              new ReceiptRequest(
                  "case-verified", "incident", Classification.RESTRICTED, retrieval.manifestDigest()));
      assertThat(eligible.eligible()).isTrue();
      assertEquals("ELIGIBLE", eligible.reason());
      assertEquals(caseDigest, eligible.caseDigest());
      assertEquals("workload-a", eligible.workloadId());
      Retrieval currentContent = service.getRetrieval(actor, retrievalId);
      assertEquals(eligible.query(), currentContent.query());
      assertEquals(eligible.createdAt(), currentContent.createdAt());
      assertThat(eligible.results()).extracting(ReceiptResult::score)
          .containsExactlyElementsOf(currentContent.results().stream().map(RetrievalResult::score).toList());
      assertEquals(1, tokenCalls.get());
      assertEquals(1, delegationCalls.get());
      assertThat(delegationRequest.get())
          .contains("\"audience\":\"S07\"")
          .contains("\"capability_ids\":[\"assessment.case.read\"]")
          .contains("\"tenant_id\":\"" + tenant + "\"");
      assertEquals("Bearer delegated", receiptAuthorization.get());

      liveReceipt.set(
          new StubResponse(
              200,
              "{\"case_id\":\"case-verified\",\"purpose\":\"incident\","
                  + "\"case_digest\":\"" + caseDigest + "\",\"workload_id\":\"workload-b\","
                  + "\"required_source_kinds\":[\"telemetry\"]}"));
      RetrievalReceipt changedCase =
          service.receipt(
              actor,
              retrievalId,
              new ReceiptRequest(
                  "case-verified", "incident", Classification.RESTRICTED, retrieval.manifestDigest()));
      assertThat(changedCase.eligible()).isFalse();
      assertEquals("CASE_UNVERIFIED", changedCase.reason());
      assertThat(service.getRetrieval(actor, retrievalId).results())
          .allMatch(result -> result.excerpt() == null);
      liveReceipt.set(
          new StubResponse(
              200,
              "{\"case_id\":\"case-verified\",\"purpose\":\"incident\","
                  + "\"case_digest\":\"" + caseDigest + "\",\"workload_id\":\"workload-a\","
                  + "\"required_source_kinds\":[\"telemetry\"]}"));

      String withdrawer = bearer("withdrawer", tenant, List.of("knowledge.item.withdraw"));
      assertThat(
              post(
                      "/v1/knowledge-items/target-a:withdraw",
                      withdrawer,
                      "{\"expected_revision\":2,\"reason\":\"source withdrawn\"}")
                  .body())
          .contains("\"state\":\"WITHDRAWN\"");
      RetrievalReceipt afterWithdrawal =
          service.receipt(
              actor,
              retrievalId,
              new ReceiptRequest(
                  "case-verified", "incident", Classification.RESTRICTED, retrieval.manifestDigest()));
      assertThat(afterWithdrawal.eligible()).isFalse();
      assertEquals("SOURCE_NOT_ELIGIBLE", afterWithdrawal.reason());
      Retrieval concealed = service.getRetrieval(actor, retrievalId);
      assertThat(concealed.results())
          .filteredOn(result -> result.itemId().equals("target-a"))
          .allMatch(result -> result.excerpt() == null);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void caseReceiptMismatchAndOwnerOutageCreateNoRetrievalManifest() throws Exception {
    String tenant = "t-case-failure";
    String caseDigest = "cd".repeat(32);
    AtomicInteger tokenCalls = new AtomicInteger();
    AtomicInteger delegationCalls = new AtomicInteger();
    AtomicReference<String> delegationRequest = new AtomicReference<>();
    AtomicReference<String> receiptAuthorization = new AtomicReference<>();
    AtomicReference<StubResponse> response =
        new AtomicReference<>(
            new StubResponse(
                200,
                "{\"case_id\":\"different-case\",\"purpose\":\"incident\","
                    + "\"case_digest\":\"" + caseDigest + "\",\"workload_id\":\"workload-a\","
                    + "\"required_source_kinds\":[\"telemetry\"]}"));
    HttpServer server =
        newCaseReceiptServer(
            tokenCalls, delegationCalls, delegationRequest, receiptAuthorization, response);
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      HttpCaseReceiptVerifier verifier =
          new HttpCaseReceiptVerifier(
              true,
              base + "/token",
              "uacrf-s08-retriever",
              "test-secret",
              base + "/delegations",
              base,
              RestClient.builder().build());
      KnowledgeService service = new KnowledgeService(jdbc, objectMapper, verifier);
      DelegationPrincipal actor = retrievalPrincipal(tenant, RETRIEVER);
      RunRetrieval request =
          new RunRetrieval("expected-case", "incident", Classification.RESTRICTED, "nothing", 10, null);
      int initial =
          jdbc.queryForObject(
              "SELECT count(*) FROM uacrf_knowledge.retrieval WHERE tenant_id = ?",
              Integer.class,
              tenant);
      assertEquals(
          "CASE_UNVERIFIED",
          assertThrows(
                  KnowledgeException.class,
                  () ->
                      service.run(
                          actor,
                          new RunRetrieval(
                              null, "incident", Classification.RESTRICTED, "nothing", 10, null)))
              .code());
      assertEquals(
          "WORKLOAD_SCOPE_UNVERIFIED",
          assertThrows(
                  KnowledgeException.class,
                  () ->
                      service.run(
                          actor,
                          new RunRetrieval(
                              "expected-case", "incident", Classification.RESTRICTED, "nothing", 10,
                              "workload-a")))
              .code());
      KnowledgeException mismatch =
          assertThrows(KnowledgeException.class, () -> service.run(actor, request));
      assertEquals("CASE_UNVERIFIED", mismatch.code());
      assertEquals(
          initial,
          jdbc.queryForObject(
              "SELECT count(*) FROM uacrf_knowledge.retrieval WHERE tenant_id = ?",
              Integer.class,
              tenant));

      response.set(
          new StubResponse(
              200,
              "{\"case_id\":\"expected-case\",\"purpose\":\"other-purpose\","
                  + "\"case_digest\":\"" + caseDigest + "\",\"workload_id\":\"workload-a\","
                  + "\"required_source_kinds\":[\"telemetry\"]}"));
      assertEquals(
          "CASE_UNVERIFIED",
          assertThrows(KnowledgeException.class, () -> service.run(actor, request)).code());
      response.set(
          new StubResponse(
              200,
              "{\"case_id\":\"expected-case\",\"purpose\":\"incident\","
                  + "\"case_digest\":\"" + caseDigest + "\",\"workload_id\":\"workload-a\","
                  + "\"required_source_kinds\":[]}"));
      assertEquals(
          "CASE_UNVERIFIED",
          assertThrows(KnowledgeException.class, () -> service.run(actor, request)).code());
      response.set(
          new StubResponse(
              200,
              "{\"case_id\":\"expected-case\",\"purpose\":\"incident\","
                  + "\"case_digest\":\"" + caseDigest + "\",\"required_source_kinds\":[\"telemetry\"]}"));
      assertEquals(
          "CASE_UNVERIFIED",
          assertThrows(KnowledgeException.class, () -> service.run(actor, request)).code());
      response.set(new StubResponse(503, "{}"));
      KnowledgeException unavailable =
          assertThrows(KnowledgeException.class, () -> service.run(actor, request));
      assertEquals("OWNER_UNAVAILABLE", unavailable.code());
      assertEquals(
          initial,
          jdbc.queryForObject(
              "SELECT count(*) FROM uacrf_knowledge.retrieval WHERE tenant_id = ?",
              Integer.class,
              tenant));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void plaintextByteLimitAndQueryTermRequirementAreEnforced() throws Exception {
    String writer = bearer("proposer", "t-bounds", WRITER);
    String tooLarge = stage("large", "v1", "é".repeat(131073));
    assertThat(post("/v1/knowledge-items", writer, tooLarge).body()).contains("CONTENT_TOO_LARGE");
    assertThat(post("/v1/knowledge-items", writer, stage("blank", "v1", "   ")).body())
        .contains("INVALID_CONTENT");
    assertThat(
            post(
                    "/v1/knowledge-items",
                    writer,
                    stage("binary", "v1", "placeholder")
                        .replace("placeholder", "pdf\\u0000payload"))
                .body())
        .contains("UNSUPPORTED_CONTENT");
    assertThat(
            post(
                    "/v1/retrievals",
                    bearer("search", "t-bounds", RETRIEVER),
                    retrievalRequest("!!!", "incident", "PUBLIC", null, null))
                .body())
        .contains("QUERY_HAS_NO_TERMS");
    assertEquals(
        400,
        post(
                "/v1/retrievals",
                bearer("search", "t-bounds", RETRIEVER),
                retrievalRequest("query", "incident", "PUBLIC", null, null, 11))
            .statusCode());
  }

  @Test
  void retrievalEventsAreTransactionalAndUseCatalogEventNames() throws Exception {
    String writer = bearer("proposer", "t-events", WRITER);
    String reviewer = bearer("reviewer", "t-events", REVIEWER);
    stageAndApprove("events", "v1", "event material", writer, reviewer);
    run(bearer("search", "t-events", RETRIEVER), "incident", "event");
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_knowledge.outbox_event WHERE tenant_id = 't-events'",
            Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_knowledge.outbox_event "
                + "WHERE tenant_id = 't-events' AND event_type = 'uacrf.s08.knowledge.review.changed.v1'",
            Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_knowledge.outbox_event "
                + "WHERE tenant_id = 't-events' AND event_type = 'uacrf.s08.retrieval.retained.v1'",
            Integer.class));
  }

  private void stageAndApprove(
      String itemId, String version, String content, String writer, String reviewer) throws Exception {
    assertEquals(
        201,
        post("/v1/knowledge-items", writer, stage(itemId, version, content)).statusCode());
    assertEquals(
        200,
        post("/v1/knowledge-items/" + itemId + ":review", reviewer, review("APPROVE", 1))
            .statusCode());
  }

  private void stageRequestAndApprove(
      String itemId, String request, String writer, String reviewer) throws Exception {
    assertEquals(201, post("/v1/knowledge-items", writer, request).statusCode());
    assertEquals(
        200,
        post("/v1/knowledge-items/" + itemId + ":review", reviewer, review("APPROVE", 1))
            .statusCode());
  }

  private String stage(String itemId, String version, String content) {
    return "{\"item_id\":\"%s\",\"version\":\"%s\",\"title\":\"Guidance %s\","
        .formatted(itemId, version, itemId)
        + "\"source_ref\":\"metadata-only://source\",\"content\":\"%s\","
            .formatted(content)
        + "\"classification\":\"INTERNAL\",\"allowed_purposes\":[\"incident\"],"
        + "\"target_workload_ids\":[],\"review_deadline\":\""
        + Instant.now().plusSeconds(300)
        + "\",\"reason\":\"staged for independent review\"}";
  }

  private static String review(String decision, long revision) {
    return "{\"decision\":\"%s\",\"expected_revision\":%d,\"reason\":\"independent review\"}"
        .formatted(decision, revision);
  }

  private static String retrievalRequest(String query, String purpose, String max, String workloadId, String caseId) {
    return retrievalRequest(query, purpose, max, workloadId, caseId, 10);
  }

  private static String retrievalRequest(
      String query, String purpose, String max, String workloadId, String caseId, int maxResults) {
    return "{\"query\":\"%s\",\"purpose\":\"%s\",\"max_classification\":\"%s\","
        .formatted(query, purpose, max)
        + "\"max_results\":" + maxResults
        + (workloadId == null ? "" : ",\"workload_id\":\"" + workloadId + "\"")
        + (caseId == null ? "" : ",\"case_id\":\"" + caseId + "\"")
        + "}";
  }

  private JsonNode run(String bearer, String purpose, String query) throws Exception {
    return run(bearer, purpose, query, "RESTRICTED", null, null);
  }

  private JsonNode run(String bearer, String purpose, String query, String max, String workloadId)
      throws Exception {
    return run(bearer, purpose, query, max, workloadId, null);
  }

  private JsonNode run(
      String bearer, String purpose, String query, String max, String workloadId, String caseId)
      throws Exception {
    return getJson(
        "/v1/retrievals",
        bearer,
        "POST",
        retrievalRequest(query, purpose, max, workloadId, caseId));
  }

  private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
    return send(path, bearer, "POST", body);
  }

  private HttpResponse<String> get(String path, String bearer) throws Exception {
    return send(path, bearer, "GET", null);
  }

  private JsonNode getJson(String path, String bearer) throws Exception {
    return objectMapper.readTree(get(path, bearer).body());
  }

  private JsonNode getJson(String path, String bearer, String method, String body) throws Exception {
    return objectMapper.readTree(send(path, bearer, method, body).body());
  }

  private HttpResponse<String> send(String path, String bearer, String method, String body) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", bearer)
            .header("Content-Type", "application/json");
    if ("GET".equals(method)) {
      request.GET();
    } else {
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
        + new DelegationTokenIssuer(
                DelegationJwtEncoderFactory.create(KEY_PAIR, KEY_ID),
                Clock.systemUTC(),
                "https://uacrf.local/s01",
                KEY_ID)
            .issue(principal, "S08");
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

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static DelegationPrincipal retrievalPrincipal(String tenant, List<String> capabilities) {
    return new DelegationPrincipal(
        "https://uacrf.local/s01|retriever",
        tenant,
        "env-1",
        "*",
        "https://idp.example.test",
        "retriever",
        capabilities,
        List.of(),
        List.of());
  }

  private static HttpServer newCaseReceiptServer(
      AtomicInteger tokenCalls,
      AtomicInteger delegationCalls,
      AtomicReference<String> delegationRequest,
      AtomicReference<String> receiptAuthorization,
      AtomicReference<StubResponse> receiptResponse)
      throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          tokenCalls.incrementAndGet();
          respond(exchange, 200, "{\"access_token\":\"identity\",\"expires_in\":60}");
        });
    server.createContext(
        "/delegations",
        exchange -> {
          delegationCalls.incrementAndGet();
          delegationRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          assertEquals("Bearer identity", exchange.getRequestHeaders().getFirst("Authorization"));
          respond(exchange, 200, "{\"access_token\":\"delegated\",\"expires_in\":30}");
        });
    server.createContext(
        "/internal/v1/cases/",
        exchange -> {
          receiptAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          StubResponse configured = receiptResponse.get();
          respond(exchange, configured.status(), configured.body());
        });
    server.start();
    return server;
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private record StubResponse(int status, String body) {}
}
