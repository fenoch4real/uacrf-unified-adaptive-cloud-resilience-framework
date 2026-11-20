package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OwnerReceiptClientTests {
  private static final String CASE_MATERIAL =
      "{\"workload_id\":\"workload-1\",\"purpose\":\"assessment\",\"required_source_kinds\":[\"metrics\"]}";
  private static final String CASE_DIGEST = Sha256.hex(CASE_MATERIAL.getBytes(StandardCharsets.UTF_8));
  private static final String EXCERPT = "metrics😀ab";
  private static final String EXCERPT_DIGEST =
      new InputPacket(EXCERPT.getBytes(StandardCharsets.UTF_8)).bytesDigest();
  private static final String MANIFEST_DIGEST = manifestDigest("INTERNAL", EXCERPT_DIGEST);
  private static final SourceReceiptConsistency.Expected EXPECTED =
      new SourceReceiptConsistency.Expected("case-1", "retrieval-1", MANIFEST_DIGEST, "assessment",
          SourceReceiptConsistency.Classification.INTERNAL);
  private static final String CASE_BODY = """
      {"case_id":"case-1","purpose":"assessment","case_digest":"%s",
       "workload_id":"workload-1","required_source_kinds":["metrics"]}
      """.formatted(CASE_DIGEST);
  private static final String CASE_MATERIAL_BODY = JsonMapper.builder().build().writeValueAsString(
      Map.of("case_id", "case-1", "case_digest", CASE_DIGEST, "canonical_text", CASE_MATERIAL));
  private static final String REQUESTER_ACCESS = """
      {"principal_id":"human-1","kind":"HUMAN","service_id":null,"scope":"GLOBAL_GRANTS_ONLY",
       "capability_ids":["advisory.run.create","assessment.case.read","knowledge.retrieval.read",
       "knowledge.classification.internal.read","catalog.agent.read"],
       "capability_expiry_horizon":"2099-01-01T00:00:00Z","session_expires_at":"2098-01-01T00:00:00Z"}
      """;
  private static final String WORKLOAD_VERSION_MANIFEST =
      "{\"instructions_digest\":\"" + "a".repeat(64)
          + "\",\"model_ref\":\"model-a\",\"knowledge_refs\":[],"
          + "\"tools\":[],\"memory_namespace\":\"memory\",\"evaluation_suite_ref\":\"eval\","
          + "\"recovery_rules_ref\":\"recovery\",\"runtime_build\":\"runtime\","
          + "\"data_policy_ref\":\"policy\",\"handoff_ref\":\"handoff\","
          + "\"limits\":{\"steps\":10,\"tokens\":100,\"cost_units\":5,\"duration_seconds\":30}}";
  private static final String WORKLOAD_VERSION_DIGEST = workloadVersionDigest();
  private static final String ACTIVE_VERSION_BODY =
      """
      {"profile_id":"analyst","id":"version-1","version_number":1,"digest":"%s",
       "object_ref":"artifact:sha256:version","manifest":%s}
      """
          .formatted(WORKLOAD_VERSION_DIGEST, WORKLOAD_VERSION_MANIFEST);
  private static final String RETRIEVAL_BODY = """
      {"retrieval_id":"retrieval-1","requested_case_id":"case-1","case_id":"case-1",
       "case_binding":"VERIFIED","case_digest":"%s","workload_id":"workload-1","purpose":"assessment",
       "classification":"INTERNAL","manifest_digest":"%s","query":"metrics","created_at":"2026-01-01T00:00:00Z",
       "eligible":true,"reason":"ELIGIBLE",
       "results":[{"item_id":"item-1","version":"v1","chunk_index":0,"start_offset":0,"end_offset":10,
       "source_digest":"%s","excerpt_digest":"%s","allowed_purposes":["assessment"],"classification":"PUBLIC","score":1}]}
      """.formatted(CASE_DIGEST, MANIFEST_DIGEST, EXCERPT_DIGEST, EXCERPT_DIGEST);
  private static final String CONTENT_BODY = """
      {"retrieval_id":"retrieval-1","case_id":"case-1","case_binding":"VERIFIED",
       "case_digest":"%s","workload_id":"workload-1","purpose":"assessment",
       "max_classification":"INTERNAL","manifest_digest":"%s","query":"metrics","created_at":"2026-01-01T00:00:00Z",
       "results":[{"item_id":"item-1","version":"v1","chunk_index":0,"start_offset":0,"end_offset":10,
       "source_digest":"%s","excerpt_digest":"%s","allowed_purposes":["assessment"],"classification":"PUBLIC",
       "score":1,"excerpt":"%s"}]}
      """.formatted(CASE_DIGEST, MANIFEST_DIGEST, EXCERPT_DIGEST, EXCERPT_DIGEST, EXCERPT);
  private HttpServer server;
  private URI base;
  private final AtomicReference<Reply> caseReply = new AtomicReference<>(new Reply(200, CASE_BODY));
  private final AtomicReference<Reply> caseMaterialReply = new AtomicReference<>(new Reply(200, CASE_MATERIAL_BODY));
  private final AtomicReference<Reply> retrievalReply = new AtomicReference<>(new Reply(200, RETRIEVAL_BODY));
  private final AtomicReference<Reply> contentReply = new AtomicReference<>(new Reply(200, CONTENT_BODY));
  private final AtomicReference<Reply> retrievalReplyAfterContent = new AtomicReference<>();
  private final AtomicReference<Reply> caseReplyAfterContent = new AtomicReference<>();
  private final AtomicReference<String> caseAuthorization = new AtomicReference<>();
  private final AtomicReference<String> retrievalAuthorization = new AtomicReference<>();
  private final AtomicReference<String> retrievalRequest = new AtomicReference<>();
  private final AtomicReference<String> contentAuthorization = new AtomicReference<>();
  private final AtomicInteger caseCalls = new AtomicInteger();
  private final AtomicInteger caseMaterialCalls = new AtomicInteger();
  private final AtomicInteger retrievalCalls = new AtomicInteger();
  private final AtomicInteger contentCalls = new AtomicInteger();
  private final AtomicInteger identityCalls = new AtomicInteger();
  private final AtomicInteger actorTokenCalls = new AtomicInteger();
  private final AtomicInteger requesterAccessCalls = new AtomicInteger();
  private final AtomicInteger responseDelayMillis = new AtomicInteger();
  private final AtomicReference<Reply> requesterReply = new AtomicReference<>(new Reply(200, REQUESTER_ACCESS));
  private final AtomicReference<Reply> requesterReplyAfterContent = new AtomicReference<>();
  private final AtomicReference<Reply> requesterReplyAfterCaseMaterial = new AtomicReference<>();
  private final AtomicReference<Reply> retrievalReplyAfterRequesterRefresh = new AtomicReference<>();
  private final AtomicReference<Reply> activeVersionReply =
      new AtomicReference<>(new Reply(200, ACTIVE_VERSION_BODY));
  private final AtomicReference<Reply> activeVersionReplyAfterFirstRead = new AtomicReference<>();
  private final AtomicInteger activeVersionCalls = new AtomicInteger();
  private final AtomicReference<Reply> knowledgeDelegationFailure = new AtomicReference<>();
  private final List<String> identityAuthorizations = new CopyOnWriteArrayList<>();
  private final List<String> identityRequests = new CopyOnWriteArrayList<>();
  private final AtomicReference<String> requesterAuthorization = new AtomicReference<>();
  private final AtomicReference<CountDownLatch> blockedBody = new AtomicReference<>();
  private final List<String> tokenRequests = new ArrayList<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/v1/cases/case-1:receipt", exchange -> {
      caseCalls.incrementAndGet();
      caseAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.getRequestBody().readAllBytes();
      CountDownLatch release = blockedBody.get();
      if (release != null) {
        exchange.sendResponseHeaders(200, 1);
        exchange.getResponseBody().flush();
        try {
          release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
        } finally {
          exchange.close();
        }
        return;
      }
      respond(exchange, caseReply.get());
    });
    server.createContext("/internal/v1/cases/case-1:material", exchange -> {
      caseMaterialCalls.incrementAndGet();
      if (!"POST".equals(exchange.getRequestMethod())
          || !"Bearer test-token-S07".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
        respond(exchange, new Reply(403, "{}"));
        return;
      }
      if (requesterReplyAfterCaseMaterial.get() != null) requesterReply.set(requesterReplyAfterCaseMaterial.get());
      respond(exchange, caseMaterialReply.get());
    });
    server.createContext("/internal/v1/retrievals/retrieval-1:receipt", exchange -> {
      retrievalCalls.incrementAndGet();
      retrievalAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      retrievalRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, retrievalReply.get());
    });
    server.createContext("/v1/retrievals/retrieval-1", exchange -> {
      contentCalls.incrementAndGet();
      contentAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, new Reply(405, "{}"));
        return;
      }
      if (requesterReplyAfterContent.get() != null) requesterReply.set(requesterReplyAfterContent.get());
      if (retrievalReplyAfterContent.get() != null) retrievalReply.set(retrievalReplyAfterContent.get());
      if (caseReplyAfterContent.get() != null) caseReply.set(caseReplyAfterContent.get());
      respond(exchange, contentReply.get());
    });
    server.createContext("/internal/v1/delegations", exchange -> {
      identityCalls.incrementAndGet();
      String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      String authorization = exchange.getRequestHeaders().getFirst("Authorization");
      identityAuthorizations.add(authorization);
      identityRequests.add(request);
      if (!"Bearer actor-token".equals(authorization) && !"Bearer requester-token".equals(authorization)) {
        respond(exchange, new Reply(403, "{}"));
        return;
      }
      if (request.contains("\"S08\"") && knowledgeDelegationFailure.get() != null) {
        respond(exchange, knowledgeDelegationFailure.get());
        return;
      }
      String token =
          request.contains("\"S07\"")
              ? "test-token-S07"
              : request.contains("\"S02\"") ? "test-token-S02" : "test-token-S08";
      respond(exchange, new Reply(200, """
          {"access_token":"%s","token_type":"Bearer","expires_in":30}
          """.formatted(token)));
    });
    server.createContext("/internal/v1/workload-versions/version-1", exchange -> {
      activeVersionCalls.incrementAndGet();
      if (!"GET".equals(exchange.getRequestMethod())
          || !"Bearer test-token-S02".equals(exchange.getRequestHeaders().getFirst("Authorization"))
          || !"profile_id=analyst".equals(exchange.getRequestURI().getRawQuery())) {
        respond(exchange, new Reply(403, "{}"));
        return;
      }
      respond(exchange, activeVersionReply.get());
      Reply changedReply = activeVersionReplyAfterFirstRead.getAndSet(null);
      if (changedReply != null) activeVersionReply.set(changedReply);
    });
    server.createContext("/v1/me/access", exchange -> {
      int calls = requesterAccessCalls.incrementAndGet();
      requesterAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      respond(exchange, requesterReply.get());
      if (calls == 2 && retrievalReplyAfterRequesterRefresh.get() != null) {
        retrievalReply.set(retrievalReplyAfterRequesterRefresh.get());
      }
    });
    server.createContext("/oauth/token", exchange -> {
      actorTokenCalls.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      respond(exchange, new Reply(200, """
          {"access_token":"actor-token","token_type":"Bearer","expires_in":60}
          """));
    });
    server.start();
    base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private OwnerReceiptClient client(boolean enabled) {
    return new OwnerReceiptClient(enabled, base, base, true, (tenant, environment, audience, capabilities) -> {
      tokenRequests.add(tenant + "/" + environment + "/" + audience + "/" + String.join(",", capabilities));
      return "test-token-" + audience;
    });
  }

  private void respond(HttpExchange exchange, Reply reply) throws IOException {
    try {
      Thread.sleep(responseDelayMillis.get());
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IOException("Fixture response interrupted.", failure);
    }
    byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    if (reply.status() == 302) {
      exchange.getResponseHeaders().set("Location", "/internal/v1/retrievals/retrieval-1:receipt");
    }
    exchange.sendResponseHeaders(reply.status(), bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private static String manifestDigest(String classification, String excerptDigest) {
    String manifest = """
        {"retrieval_id":"retrieval-1","case_id":"case-1","case_binding":"VERIFIED","case_digest":"%s",\
        "purpose":"assessment","max_classification":"%s","query":"metrics","workload_id":"workload-1",\
        "created_at":"2026-01-01T00:00:00Z","results":[{"item_id":"item-1","version":"v1",\
        "chunk_index":0,"start_offset":0,"end_offset":10,"source_digest":"%s","excerpt_digest":"%s",\
        "allowed_purposes":["assessment"],"classification":"PUBLIC","score":1}]}"""
        .formatted(CASE_DIGEST, classification, EXCERPT_DIGEST, excerptDigest);
    return Sha256.hex(manifest.getBytes(StandardCharsets.UTF_8));
  }

  private void setClassification(String classification) {
    String digest = manifestDigest(classification, EXCERPT_DIGEST);
    retrievalReply.set(new Reply(200, RETRIEVAL_BODY.replace("\"classification\":\"INTERNAL\"",
        "\"classification\":\"" + classification + "\"").replace(MANIFEST_DIGEST, digest)));
    contentReply.set(new Reply(200, CONTENT_BODY.replace("\"max_classification\":\"INTERNAL\"",
        "\"max_classification\":\"" + classification + "\"").replace(MANIFEST_DIGEST, digest)));
  }

  @Test
  void fetchesScopedOwnerReceiptsWithSeparateDelegations() throws Exception {
    var receipts = client(true).fetch("tenant-1", "env-1", EXPECTED);
    assertEquals("case-1", receipts.sourceCase().caseId());
    assertEquals("retrieval-1", receipts.retrieval().retrievalId());
    assertEquals(List.of(EXCERPT), receipts.excerpts());
    assertThrows(UnsupportedOperationException.class, () -> receipts.excerpts().clear());
    assertEquals(List.of(
        "tenant-1/env-1/S07/assessment.case.read",
        "tenant-1/env-1/S08/knowledge.retrieval.read,knowledge.classification.internal.read"), tokenRequests);
    assertEquals("Bearer test-token-S07", caseAuthorization.get());
    assertEquals("Bearer test-token-S08", retrievalAuthorization.get());
    assertEquals("Bearer test-token-S08", contentAuthorization.get());
    assertTrue(retrievalRequest.get().contains("\"case_id\":\"case-1\""));
    assertTrue(retrievalRequest.get().contains("\"manifest_digest\":\"" + MANIFEST_DIGEST + "\""));
    assertTrue(retrievalRequest.get().contains("\"classification\":\"INTERNAL\""));
    assertEquals(1, caseCalls.get());
    assertEquals(2, retrievalCalls.get());
    assertEquals(1, contentCalls.get());
  }

  @Test
  void usesS01DelegationClientForBothOwners() throws Exception {
    var actorTokens = new ClientCredentialsTokenProvider(true, base.resolve("/oauth/token"), true,
        "advisory-test", "secret-test");
    var delegations = new S01DelegationClient(true, base, true, actorTokens);
    var client = new OwnerReceiptClient(true, base, base, true, delegations);
    assertEquals(List.of(EXCERPT), client.fetch("tenant-1", "env-1", EXPECTED).excerpts());
    assertEquals(2, identityCalls.get());
    assertEquals(2, actorTokenCalls.get());
    assertEquals("Bearer test-token-S07", caseAuthorization.get());
    assertEquals("Bearer test-token-S08", retrievalAuthorization.get());
    assertEquals("Bearer test-token-S08", contentAuthorization.get());
  }

  @Test
  void checksRequesterAccessThenUsesThatSameRequesterForBothOwners() throws Exception {
    var verifier = new RequesterSourceCheck(true, base, base, base, true);
    var snapshot = verifier.check("requester-token", "tenant-1", "env-1", EXPECTED);
    assertEquals("human-1", snapshot.requester().id());
    assertEquals(List.of(EXCERPT), snapshot.receipts().excerpts());
    assertEquals(CASE_MATERIAL, snapshot.caseMaterial());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.requester().capabilities().clear());
    assertEquals("Bearer requester-token", requesterAuthorization.get());
    assertEquals(2, requesterAccessCalls.get());
    assertEquals(List.of(
        "Bearer requester-token", "Bearer requester-token", "Bearer requester-token",
        "Bearer requester-token", "Bearer requester-token"),
        identityAuthorizations);
    assertTrue(identityRequests.get(0).contains("\"assessment.case.read\""));
    assertTrue(identityRequests.get(1).contains("\"assessment.case.read\""));
    assertTrue(identityRequests.get(2).contains("\"knowledge.classification.internal.read\""));
    assertTrue(identityRequests.get(4).contains("\"knowledge.classification.internal.read\""));
    assertEquals(1, caseMaterialCalls.get());
    assertEquals("Bearer test-token-S07", caseAuthorization.get());
    assertEquals("Bearer test-token-S08", retrievalAuthorization.get());
    assertEquals(0, actorTokenCalls.get());
  }

  @Test
  void checksCallerSuppliedSourceBeforeRefreshingOwnerAndRequester() throws Exception {
    var verifier = new RequesterSourceCheck(true, base, base, base, true);
    byte[] bytes = EXCERPT.getBytes(StandardCharsets.UTF_8);
    var artifact = new DigestBoundArtifact("artifact:sha256:" + EXCERPT_DIGEST, EXCERPT_DIGEST, bytes);
    var sources = Map.of(EXCERPT_DIGEST, artifact);
    assertEquals(List.of(EXCERPT),
        verifier.checkWithSources("requester-token", "tenant-1", "env-1", EXPECTED, sources).receipts().excerpts());
    assertEquals(2, requesterAccessCalls.get());
    assertEquals(3, retrievalCalls.get());

    requesterReplyAfterContent.set(new Reply(200, REQUESTER_ACCESS.replace("\"advisory.run.create\",", "")));
    assertThrows(IOException.class,
        () -> verifier.checkWithSources("requester-token", "tenant-1", "env-1", EXPECTED, sources));
    assertEquals(4, requesterAccessCalls.get());
    assertEquals(5, retrievalCalls.get());
  }

  @Test
  void rejectsRetrievalEligibilityChangeDuringFinalRequesterRefresh() {
    retrievalReplyAfterRequesterRefresh.set(
        new Reply(200, RETRIEVAL_BODY.replace("\"eligible\":true", "\"eligible\":false")
            .replace("\"reason\":\"ELIGIBLE\"", "\"reason\":\"SOURCE_NOT_ELIGIBLE\"")));
    var verifier = new RequesterSourceCheck(true, base, base, base, true);
    byte[] bytes = EXCERPT.getBytes(StandardCharsets.UTF_8);
    var artifact = new DigestBoundArtifact("artifact:sha256:" + EXCERPT_DIGEST, EXCERPT_DIGEST, bytes);

    assertThrows(IOException.class,
        () -> verifier.checkWithSources("requester-token", "tenant-1", "env-1", EXPECTED,
            Map.of(EXCERPT_DIGEST, artifact)));
    assertEquals(2, requesterAccessCalls.get());
    assertEquals(3, retrievalCalls.get());
    assertEquals(1, contentCalls.get());
  }

  @Test
  void checksRequestedConfigurationAndSourcesTogetherWithoutAdmittingRun() throws Exception {
    var model = configuration("""
        {"protocol":"UACRF_ADVISORY_MODELS_V1","tenant_id":"tenant-1","environment_id":"env-1",
         "models":[{"model_ref":"model-a","engine":"HTTP_JSON_ADVISORY_V1","timeout_seconds":30,
         "maximum_output_tokens":30,"maximum_cost_units":5}]}
        """);
    var prompt = configuration("""
        {"protocol":"UACRF_ADVISORY_PROMPTS_V1","tenant_id":"tenant-1","environment_id":"env-1",
         "prompts":[{"instructions_digest":"%s","text":"instructions"}]}
        """.formatted("a".repeat(64)));
    var request = new AdvisoryRunRequest("case-1", "retrieval-1",
        AdvisoryRunRequest.WorkflowVersion.SINGLE_ANALYST_V1, model.reference(), model.digest(),
        prompt.reference(), prompt.digest(),
        new AdvisoryBudget(1, 100, 5,
            List.of(new AdvisoryBudget.Allocation(AdvisoryBudget.Role.ANALYST, "analyst", 100, 5))),
        Instant.parse("2030-01-01T00:00:00Z"), "Run assessment", List.of("proposal"));
    var bytes = EXCERPT.getBytes(StandardCharsets.UTF_8);
    var sources = Map.of(EXCERPT_DIGEST, new DigestBoundArtifact(
        "artifact:sha256:" + EXCERPT_DIGEST, EXCERPT_DIGEST, bytes));
    var check =
        new RunPreparationCheck(
            new RequesterSourceCheck(true, base, base, base, base, true));
    var pastDeadline = new RunPreparationCheck(new RequesterSourceCheck(true, base, base, base, true),
        Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC));
    assertThrows(IllegalArgumentException.class,
        () -> pastDeadline.check("requester-token", "tenant-1", "env-1", request, EXPECTED, model, prompt, sources));
    assertEquals(0, requesterAccessCalls.get());

    var wrongCase = new SourceReceiptConsistency.Expected("case-2", "retrieval-1", MANIFEST_DIGEST, "assessment",
        SourceReceiptConsistency.Classification.INTERNAL);
    assertThrows(IllegalArgumentException.class,
        () -> check.check("requester-token", "tenant-1", "env-1", request, wrongCase, model, prompt, sources));
    var wrongRetrieval = new SourceReceiptConsistency.Expected("case-1", "retrieval-2", MANIFEST_DIGEST, "assessment",
        SourceReceiptConsistency.Classification.INTERNAL);
    assertThrows(IllegalArgumentException.class,
        () -> check.check("requester-token", "tenant-1", "env-1", request, wrongRetrieval, model, prompt, sources));
    assertThrows(IllegalArgumentException.class,
        () -> check.check("requester-token", "tenant-1", "env-1", request, EXPECTED, prompt, prompt, sources));
    assertThrows(IllegalArgumentException.class,
        () -> check.check("requester-token", "tenant-2", "env-1", request, EXPECTED, model, prompt, sources));
    assertEquals(0, requesterAccessCalls.get());

    var result = check.check("requester-token", "tenant-1", "env-1", request, EXPECTED, model, prompt, sources);
    assertEquals(request, result.request());
    assertEquals(EXPECTED, result.expected());
    assertEquals("model-a", result.models().models().getFirst().reference());
    assertEquals("instructions", result.prompts().prompts().getFirst().text());
    assertEquals(List.of(EXCERPT), result.sources().receipts().excerpts());
    assertEquals(2, requesterAccessCalls.get());
    assertEquals(3, retrievalCalls.get());

    var pin = profilePins("model-a", "a".repeat(64));
    var limits = new ProfileConfigurationCheck.RequestLimits("attempt-1", 30, Duration.ofSeconds(30));
    var profiles = new HashMap<>(Map.of("analyst", pin));
    var requests = new HashMap<>(Map.of("analyst", limits));
    assertThrows(IllegalArgumentException.class,
        () -> check.checkWithProfiles("requester-token", "tenant-1", "env-1", request,
            EXPECTED, model, prompt, sources, Map.of("other", pin), requests));
    var wrongModel = profilePins("model-b", "a".repeat(64));
    assertThrows(IllegalArgumentException.class,
        () -> check.checkWithProfiles("requester-token", "tenant-1", "env-1", request,
            EXPECTED, model, prompt, sources, Map.of("analyst", wrongModel), requests));
    assertEquals(2, requesterAccessCalls.get());
    var complete = check.checkWithProfiles("requester-token", "tenant-1", "env-1", request,
        EXPECTED, model, prompt, sources, profiles, requests);
    profiles.clear();
    requests.clear();
    assertEquals(pin, complete.profiles().get("analyst"));
    assertEquals(limits, complete.limits().get("analyst"));
    assertEquals("instructions", complete.selections().get("analyst").prompt().text());
    assertThrows(UnsupportedOperationException.class, () -> complete.gatewayRequests().clear());
    var gateway = complete.gatewayRequests().get("analyst");
    assertEquals("model-a", gateway.model());
    assertEquals(limits.attemptId(), gateway.attemptId());
    assertEquals(complete.packets().get("analyst").bytesDigest(),
        Sha256.hex(gateway.context().getBytes(StandardCharsets.UTF_8)));
    var packet = StrictBoundedJson.readObject(gateway.context().getBytes(StandardCharsets.UTF_8), "Test packet");
    assertEquals("UACRF_ADVISORY_INPUT_V1", packet.get("protocol"));
    assertEquals("instructions", packet.get("instructions"));
    assertEquals(CASE_MATERIAL, packet.get("case_material"));
    assertEquals(EXCERPT, ((Map<?, ?>) ((List<?>) packet.get("sources")).getFirst()).get("excerpt"));
    assertThrows(UnsupportedOperationException.class, () -> complete.packets().clear());
    var expiredRequest = new AdvisoryRunRequest(request.agentCaseId(), request.retrievalManifestId(),
        request.workflowVersion(), request.modelConfigReference(), request.modelConfigDigest(),
        request.promptReference(), request.promptDigest(), request.budget(), Instant.EPOCH,
        request.reason(), request.proposalIds());
    var expiredPreparation = new RunPreparationCheck.Snapshot(complete.preparation().tenantId(),
        complete.preparation().environmentId(), expiredRequest, complete.preparation().expected(),
        complete.preparation().models(), complete.preparation().prompts(), complete.preparation().sources());
    var expiredSnapshot = new RunPreparationCheck.ProfileSnapshot(expiredPreparation, complete.profiles(),
        complete.limits(), complete.packets(), complete.gatewayRequests(), complete.selections(), Clock.systemUTC());
    assertThrows(IllegalArgumentException.class,
        () -> expiredSnapshot.journalIntents(new DispatchJournal.RunKey("tenant-1", "env-1", "run-expired"),
            1, Map.of("analyst", "assignment-expired")));
    var expiredByInjectedClock = new RunPreparationCheck.ProfileSnapshot(complete.preparation(),
        complete.profiles(), complete.limits(), complete.packets(), complete.gatewayRequests(),
        complete.selections(), Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC));
    assertThrows(IllegalArgumentException.class,
        () -> expiredByInjectedClock.journalIntents(new DispatchJournal.RunKey("tenant-1", "env-1", "run-clock-expired"),
            1, Map.of("analyst", "assignment-clock-expired")));
    var runKey = new DispatchJournal.RunKey("tenant-1", "env-1", "run-1");
    var journalIntent = complete.journalIntents(runKey, 1, Map.of("analyst", "assignment-1")).getFirst();
    assertEquals(new DispatchJournal.Key("tenant-1", "env-1", limits.attemptId()), journalIntent.key());
    assertEquals("run-1", journalIntent.runId());
    assertEquals("assignment-1", journalIntent.assignmentId());
    assertEquals(complete.packets().get("analyst").bytesDigest(), journalIntent.contextDigest());
    assertArrayEquals(complete.packets().get("analyst").bytes(), journalIntent.packet().bytes());
    var substitutedAttempt = new AdvisoryGateway.Request("different-attempt", gateway.model(), gateway.context(),
        gateway.maxOutputTokens(), gateway.timeout());
    var substitutedSnapshot = new RunPreparationCheck.ProfileSnapshot(complete.preparation(), complete.profiles(),
        complete.limits(), complete.packets(), Map.of("analyst", substitutedAttempt), complete.selections(),
        Clock.systemUTC());
    assertThrows(IllegalArgumentException.class,
        () -> substitutedSnapshot.journalIntents(runKey, 1, Map.of("analyst", "assignment-1")));
    var changedPacket = new InputPacket("substituted packet".getBytes(StandardCharsets.UTF_8));
    var substitutedPacketSnapshot = new RunPreparationCheck.ProfileSnapshot(complete.preparation(),
        complete.profiles(), complete.limits(), Map.of("analyst", changedPacket),
        complete.gatewayRequests(), complete.selections(), Clock.systemUTC());
    assertThrows(IllegalArgumentException.class,
        () -> substitutedPacketSnapshot.journalIntents(runKey, 1, Map.of("analyst", "assignment-1")));
    assertThrows(IllegalArgumentException.class,
        () -> complete.journalIntents(new DispatchJournal.RunKey("other-tenant", "env-1", "run-1"), 1,
            Map.of("analyst", "assignment-1")));
    assertEquals(4, requesterAccessCalls.get());
    assertEquals(6, retrievalCalls.get());

    var clockReads = new AtomicInteger();
    Clock expiringDuringCheck = new Clock() {
      @Override
      public ZoneId getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        return clockReads.incrementAndGet() < 3
            ? Instant.parse("2029-01-01T00:00:00Z") : Instant.parse("2031-01-01T00:00:00Z");
      }
    };
    var expiringCheck =
        new RunPreparationCheck(
            new RequesterSourceCheck(true, base, base, base, base, true), expiringDuringCheck);
    assertThrows(IllegalArgumentException.class,
        () -> expiringCheck.checkWithProfiles("requester-token", "tenant-1", "env-1", request,
            EXPECTED, model, prompt, sources, Map.of("analyst", pin), Map.of("analyst", limits)));
    assertEquals(6, requesterAccessCalls.get());
    assertEquals(9, retrievalCalls.get());

    requesterReplyAfterContent.set(new Reply(200, REQUESTER_ACCESS.replace("\"advisory.run.create\",", "")));
    assertThrows(IOException.class,
        () -> check.checkWithProfiles("requester-token", "tenant-1", "env-1", request,
            EXPECTED, model, prompt, sources, Map.of("analyst", pin), Map.of("analyst", limits)));
  }

  @Test
  void rejectsProfileReceiptWithManifestDigestMismatch() {
    activeVersionReply.set(
        new Reply(200, ACTIVE_VERSION_BODY.replace("\"model-a\"", "\"model-b\"")));
    var reader =
        new AgentProfileVersionClient(
            base, true, (tenant, environment, audience, capabilities) -> "test-token-S02",
            new InternalJsonClient(Duration.ofSeconds(5)));
    assertThrows(IOException.class,
        () -> reader.fetchActive("tenant-1", "env-1",
            Map.of("analyst", profilePins("model-a", "a".repeat(64)))));
  }

  @Test
  void profilePreparationRequiresCurrentCatalogReadBeforeOwnerReads() {
    requesterReply.set(new Reply(200, REQUESTER_ACCESS.replace(",\"catalog.agent.read\"", "")));
    var verifier = new RequesterSourceCheck(true, base, base, base, base, true);
    var source =
        new DigestBoundArtifact(
            "artifact:sha256:" + EXCERPT_DIGEST, EXCERPT_DIGEST,
            EXCERPT.getBytes(StandardCharsets.UTF_8));
    assertThrows(IOException.class,
        () -> verifier.checkWithProfiles("requester-token", "tenant-1", "env-1", EXPECTED,
            Map.of(EXCERPT_DIGEST, source),
            Map.of("analyst", profilePins("model-a", "a".repeat(64)))));
    assertEquals(1, requesterAccessCalls.get());
    assertEquals(0, identityCalls.get());
    assertEquals(0, caseCalls.get());
  }

  @Test
  void rejectsOutOfRangeAndNonIntegralProfileReceiptNumbers() throws Exception {
    var reader =
        new AgentProfileVersionClient(
            base, true, (tenant, environment, audience, capabilities) -> "test-token-S02",
            new InternalJsonClient(Duration.ofSeconds(5)));
    var pins = Map.of("analyst", profilePins("model-a", "a".repeat(64)));
    assertEquals(1, reader.fetchActive("tenant-1", "env-1", pins).get("analyst").versionNumber());
    activeVersionReply.set(new Reply(200,
        ACTIVE_VERSION_BODY.replace("\"version_number\":1", "\"version_number\":9223372036854775807")));
    assertEquals(Long.MAX_VALUE,
        reader.fetchActive("tenant-1", "env-1", pins).get("analyst").versionNumber());
    for (String invalid : List.of(
        "18446744073709551617", "9223372036854775808", "-18446744073709551615", "1.5", "0")) {
      activeVersionReply.set(new Reply(200,
          ACTIVE_VERSION_BODY.replace("\"version_number\":1", "\"version_number\":" + invalid)));
      assertEquals("S02 returned invalid version_number.",
          assertThrows(IOException.class, () -> reader.fetchActive("tenant-1", "env-1", pins), invalid)
              .getMessage());
    }

    String invalidManifest = WORKLOAD_VERSION_MANIFEST.replace(
        "\"tokens\":100", "\"tokens\":18446744073709551716");
    var json = JsonMapper.builder().build();
    String invalidDigest = Sha256.hex(
        json.writeValueAsBytes(json.readValue(invalidManifest, Object.class)));
    activeVersionReply.set(new Reply(200, ACTIVE_VERSION_BODY
        .replace(WORKLOAD_VERSION_MANIFEST, invalidManifest)
        .replace(WORKLOAD_VERSION_DIGEST, invalidDigest)));
    var invalidPins = new ProfileConfigurationCheck.VersionPins(
        "version-1", invalidDigest, "model-a", "a".repeat(64), 100, 5, 30);
    assertEquals("S02 returned invalid tokens.", assertThrows(IOException.class,
        () -> reader.fetchActive("tenant-1", "env-1", Map.of("analyst", invalidPins))).getMessage());
  }

  private static DigestBoundArtifact configuration(String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    String digest = Sha256.hex(bytes);
    return new DigestBoundArtifact("artifact:sha256:" + digest, digest, bytes);
  }

  private static ProfileConfigurationCheck.VersionPins profilePins(
      String model, String instructionsDigest) {
    return new ProfileConfigurationCheck.VersionPins(
        "version-1", WORKLOAD_VERSION_DIGEST, model, instructionsDigest, 100, 5, 30);
  }

  private static String workloadVersionDigest() {
    var json = JsonMapper.builder().build();
    Object manifest = json.readValue(WORKLOAD_VERSION_MANIFEST, Object.class);
    return Sha256.hex(json.writeValueAsBytes(manifest));
  }

  @Test
  void rejectsAbsentOrSubstitutedCallerSources() {
    var verifier = new RequesterSourceCheck(true, base, base, base, true);
    assertThrows(IllegalArgumentException.class,
        () -> verifier.checkWithSources("requester-token", "tenant-1", "env-1", EXPECTED, Map.of()));
    Map<String, DigestBoundArtifact> tooMany = new HashMap<>();
    for (int i = 0; i < 11; i++) {
      byte[] bytes = ("source-" + i).getBytes(StandardCharsets.UTF_8);
      String digest = Sha256.hex(bytes);
      tooMany.put(digest, new DigestBoundArtifact("artifact:sha256:" + digest, digest, bytes));
    }
    assertThrows(IllegalArgumentException.class,
        () -> verifier.checkWithSources("requester-token", "tenant-1", "env-1", EXPECTED, tooMany));
    byte[] other = "other".getBytes(StandardCharsets.UTF_8);
    String digest = Sha256.hex(other);
    var artifact = new DigestBoundArtifact("artifact:sha256:" + digest, digest, other);
    assertThrows(IllegalArgumentException.class,
        () -> verifier.checkWithSources("requester-token", "tenant-1", "env-1", EXPECTED,
            Map.of(EXCERPT_DIGEST, artifact)));
    assertEquals(0, requesterAccessCalls.get());
    assertThrows(IllegalArgumentException.class,
        () -> verifier.checkWithSources("requester-token", "tenant-1", "env-1", EXPECTED,
            Map.of(digest, artifact)));
    assertEquals(1, requesterAccessCalls.get());
    assertEquals(1, retrievalCalls.get());
    var required = new DigestBoundArtifact("artifact:sha256:" + EXCERPT_DIGEST, EXCERPT_DIGEST,
        EXCERPT.getBytes(StandardCharsets.UTF_8));
    assertThrows(IllegalArgumentException.class,
        () -> verifier.checkWithSources("requester-token", "tenant-1", "env-1", EXPECTED,
            Map.of(EXCERPT_DIGEST, required, digest, artifact)));
    assertEquals(2, requesterAccessCalls.get());
    assertEquals(2, retrievalCalls.get());
  }

  @Test
  void acceptsRestrictedGrantForInternalSourceByRequestingThatGrantFromS01() throws Exception {
    requesterReply.set(new Reply(200, REQUESTER_ACCESS.replace(
        "knowledge.classification.internal.read", "knowledge.classification.restricted.read")));
    var verifier = new RequesterSourceCheck(true, base, base, base, true);
    assertEquals(List.of(EXCERPT), verifier.check("requester-token", "tenant-1", "env-1", EXPECTED)
        .receipts().excerpts());
    assertTrue(identityRequests.get(2).contains("\"knowledge.classification.restricted.read\""));
    assertFalse(identityRequests.get(2).contains("\"knowledge.classification.internal.read\""));
  }

  @Test
  void checksPublicAndRestrictedSourcesWithOnlyTheirRequiredDelegations() throws Exception {
    var verifier = new RequesterSourceCheck(true, base, base, base, true);
    var publicExpected = new SourceReceiptConsistency.Expected(
        "case-1", "retrieval-1", manifestDigest("PUBLIC", EXCERPT_DIGEST), "assessment",
        SourceReceiptConsistency.Classification.PUBLIC);
    setClassification("PUBLIC");
    verifier.check("requester-token", "tenant-1", "env-1", publicExpected);
    assertFalse(identityRequests.get(2).contains("knowledge.classification."));

    requesterReply.set(new Reply(200, REQUESTER_ACCESS.replace(
        "knowledge.classification.internal.read", "knowledge.classification.restricted.read")));
    var restrictedExpected = new SourceReceiptConsistency.Expected(
        "case-1", "retrieval-1", manifestDigest("RESTRICTED", EXCERPT_DIGEST), "assessment",
        SourceReceiptConsistency.Classification.RESTRICTED);
    setClassification("RESTRICTED");
    verifier.check("requester-token", "tenant-1", "env-1", restrictedExpected);
    assertTrue(identityRequests.get(7).contains("\"knowledge.classification.restricted.read\""));
    assertTrue(identityRequests.get(9).contains("\"knowledge.classification.restricted.read\""));
  }

  @Test
  void requesterGrantIsRecheckedForEveryCall() throws Exception {
    var verifier = new RequesterSourceCheck(true, base, base, base, true);
    verifier.check("requester-token", "tenant-1", "env-1", EXPECTED);
    requesterReply.set(new Reply(200, REQUESTER_ACCESS.replace("\"advisory.run.create\",", "")));
    assertThrows(IOException.class, () -> verifier.check("requester-token", "tenant-1", "env-1", EXPECTED));
    assertEquals(3, requesterAccessCalls.get());
    assertEquals(5, identityCalls.get());
    assertEquals(2, caseCalls.get());
    assertEquals(3, retrievalCalls.get());
  }

  @Test
  void requesterCheckHasOneDeadlineAcrossIndividuallyResponsiveOwners() {
    responseDelayMillis.set(800);
    var verifier = new RequesterSourceCheck(true, base, base, base, true);
    long started = System.nanoTime();
    assertThrows(IOException.class, () -> verifier.check("requester-token", "tenant-1", "env-1", EXPECTED));
    long elapsed = System.nanoTime() - started;
    assertTrue(elapsed >= TimeUnit.SECONDS.toNanos(4));
    assertTrue(elapsed < TimeUnit.SECONDS.toNanos(8));
    assertEquals(1, requesterAccessCalls.get());
    assertEquals(0, contentCalls.get());
    assertTrue(identityCalls.get() >= 2);
  }

  @Test
  void requesterCheckRejectsUnavailableOrSubstitutedCaseMaterialBeforeKnowledgeReads() {
    var verifier = new RequesterSourceCheck(true, base, base, base, true);
    for (Reply failure : List.of(new Reply(503, "{}"),
        new Reply(200, CASE_MATERIAL_BODY.replace("workload-1", "changed-workload")))) {
      caseMaterialReply.set(failure);
      assertThrows(IOException.class, () -> verifier.check("requester-token", "tenant-1", "env-1", EXPECTED));
    }
    assertEquals(2, caseMaterialCalls.get());
    assertEquals(0, retrievalCalls.get());
    assertEquals(0, contentCalls.get());
    assertEquals(2, requesterAccessCalls.get());
  }

  @Test
  void rejectsChangedWithdrawnOrUnavailableRetrievalAfterContentRead() {
    var client = client(true);
    for (Reply afterContent : List.of(
        new Reply(200, RETRIEVAL_BODY.replace("\"eligible\":true", "\"eligible\":false")
            .replace("\"reason\":\"ELIGIBLE\"", "\"reason\":\"SOURCE_NOT_ELIGIBLE\"")),
        new Reply(200, RETRIEVAL_BODY.replace("\"query\":\"metrics\"", "\"query\":\"changed\"")),
        new Reply(503, "{}"))) {
      retrievalReply.set(new Reply(200, RETRIEVAL_BODY));
      retrievalReplyAfterContent.set(afterContent);
      assertThrows(IOException.class, () -> client.fetch("tenant-1", "env-1", EXPECTED));
    }
    assertEquals(6, retrievalCalls.get());
    assertEquals(3, contentCalls.get());
  }

  @Test
  void rejectsCaseReceiptChangeDuringContentRead() {
    caseReplyAfterContent.set(new Reply(200, CASE_BODY.replace(CASE_DIGEST, "b".repeat(64))));
    var verifier = new RequesterSourceCheck(true, base, base, base, true);
    assertEquals("Assessment receipt changed during source checking.",
        assertThrows(IOException.class, () -> verifier.check("requester-token", "tenant-1", "env-1", EXPECTED))
            .getMessage());
    assertEquals(2, caseCalls.get());
    assertEquals(2, retrievalCalls.get());
    assertEquals(1, contentCalls.get());
  }

  @Test
  void rejectsActiveProfileVersionChangeDuringSourceChecking() {
    activeVersionReplyAfterFirstRead.set(
        new Reply(200, ACTIVE_VERSION_BODY.replace("\"version_number\":1", "\"version_number\":2")));
    var verifier = new RequesterSourceCheck(true, base, base, base, base, true);
    var source = new DigestBoundArtifact(
        "artifact:sha256:" + EXCERPT_DIGEST, EXCERPT_DIGEST, EXCERPT.getBytes(StandardCharsets.UTF_8));
    var profiles = Map.of("analyst", profilePins("model-a", "a".repeat(64)));
    assertEquals("S02 active workload versions changed during source checking.",
        assertThrows(IOException.class,
            () -> verifier.checkWithProfiles("requester-token", "tenant-1", "env-1", EXPECTED,
                Map.of(EXCERPT_DIGEST, source), profiles)).getMessage());
    assertEquals(2, activeVersionCalls.get());
  }

  @Test
  void rejectsRevocationOrIdentityChangeDuringSourceReads() {
    var verifier = new RequesterSourceCheck(true, base, base, base, true);
    requesterReplyAfterContent.set(new Reply(200, REQUESTER_ACCESS.replace("\"advisory.run.create\",", "")));
    assertThrows(IOException.class, () -> verifier.check("requester-token", "tenant-1", "env-1", EXPECTED));
    assertEquals(2, requesterAccessCalls.get());
    assertEquals(1, contentCalls.get());

    requesterReply.set(new Reply(200, REQUESTER_ACCESS));
    requesterReplyAfterContent.set(new Reply(200, REQUESTER_ACCESS.replace("human-1", "other-human")));
    assertEquals("Requester identity changed during source checking.",
        assertThrows(IOException.class, () -> verifier.check("requester-token", "tenant-1", "env-1", EXPECTED))
            .getMessage());
    assertEquals(4, requesterAccessCalls.get());
    assertEquals(2, contentCalls.get());
  }

  @Test
  void rejectsRequesterCapabilityChangesDuringSourceReads() {
    requesterReplyAfterContent.set(
        new Reply(200, REQUESTER_ACCESS.replace("\"catalog.agent.read\"", "\"knowledge.extra.read\"")));
    var verifier = new RequesterSourceCheck(true, base, base, base, true);

    assertEquals("Requester capabilities changed during source checking.",
        assertThrows(IOException.class,
            () -> verifier.check("requester-token", "tenant-1", "env-1", EXPECTED)).getMessage());
    assertEquals(2, requesterAccessCalls.get());
    assertEquals(1, contentCalls.get());
  }

  @Test
  void rejectsRequesterCapabilityExpiryChangesDuringSourceReads() {
    requesterReplyAfterContent.set(
        new Reply(200, REQUESTER_ACCESS.replace("2099-01-01T00:00:00Z", "2098-01-01T00:00:00Z")));
    var verifier = new RequesterSourceCheck(true, base, base, base, true);

    assertEquals("Requester capability expiry horizon changed during source checking.",
        assertThrows(IOException.class,
            () -> verifier.check("requester-token", "tenant-1", "env-1", EXPECTED)).getMessage());
  }

  @Test
  void rejectsRequesterSessionExpiryChangesDuringSourceReads() {
    requesterReplyAfterContent.set(
        new Reply(200, REQUESTER_ACCESS.replace("2098-01-01T00:00:00Z", "2097-01-01T00:00:00Z")));
    var verifier = new RequesterSourceCheck(true, base, base, base, true);

    assertEquals("Requester session expiry changed during source checking.",
        assertThrows(IOException.class,
            () -> verifier.check("requester-token", "tenant-1", "env-1", EXPECTED)).getMessage());
  }

  @Test
  void refreshRejectsRequesterRevokedDuringCaseMaterialRead() {
    requesterReplyAfterCaseMaterial.set(
        new Reply(200, REQUESTER_ACCESS.replace("\"advisory.run.create\",", "")));
    var verifier = new RequesterSourceCheck(true, base, base, base, true);
    assertThrows(IOException.class, () -> verifier.check("requester-token", "tenant-1", "env-1", EXPECTED));
    assertEquals(1, caseMaterialCalls.get());
    assertEquals(2, retrievalCalls.get());
    assertEquals(2, requesterAccessCalls.get());
  }

  @Test
  void requesterCheckFailsClosedOnDisabledMissingOrRevokedAccess() {
    var disabled = new RequesterSourceCheck(false, base, base, base, true);
    assertThrows(IllegalStateException.class, () -> disabled.check("requester-token", "tenant-1", "env-1", EXPECTED));
    assertEquals(0, requesterAccessCalls.get());
    var verifier = new RequesterSourceCheck(true, base, base, base, true);
    requesterReply.set(new Reply(200, REQUESTER_ACCESS.replace("\"advisory.run.create\",", "")));
    assertThrows(IOException.class, () -> verifier.check("requester-token", "tenant-1", "env-1", EXPECTED));
    assertEquals(0, identityCalls.get());
    assertEquals(0, caseCalls.get());

    requesterReply.set(new Reply(200, REQUESTER_ACCESS));
    knowledgeDelegationFailure.set(new Reply(403, "{}"));
    assertThrows(IOException.class, () -> verifier.check("requester-token", "tenant-1", "env-1", EXPECTED));
    assertEquals(1, caseCalls.get());
    assertEquals(0, retrievalCalls.get());
    assertEquals(0, contentCalls.get());
    assertEquals(0, actorTokenCalls.get());
  }

  @Test
  void requesterCheckStopsWhenOwnerRefusesTheDelegation() {
    caseReply.set(new Reply(403, "{}"));
    var verifier = new RequesterSourceCheck(true, base, base, base, true);
    assertThrows(IOException.class, () -> verifier.check("requester-token", "tenant-1", "env-1", EXPECTED));
    assertEquals(1, identityCalls.get());
    assertEquals(1, caseCalls.get());
    assertEquals(0, retrievalCalls.get());
    assertEquals(0, contentCalls.get());
  }

  @Test
  void disabledClientDoesNotRequestTokensOrContactOwners() {
    assertThrows(IllegalStateException.class, () -> client(false).fetch("tenant-1", "env-1", EXPECTED));
    assertTrue(tokenRequests.isEmpty());
    assertEquals(0, caseCalls.get());
    assertEquals(0, retrievalCalls.get());
    assertEquals(0, contentCalls.get());
  }

  @Test
  void rejectsBadCaseBeforeCallingKnowledge() {
    caseReply.set(new Reply(200, CASE_BODY.replace("case-1", "wrong-case")));
    assertThrows(IOException.class, () -> client(true).fetch("tenant-1", "env-1", EXPECTED));
    assertEquals(1, caseCalls.get());
    assertEquals(0, retrievalCalls.get());
    assertEquals(1, tokenRequests.size());
  }

  @Test
  void rejectsUnverifiedOrMalformedRetrievalAndUnavailableOwners() {
    retrievalReply.set(new Reply(200, RETRIEVAL_BODY.replace("\"eligible\":true", "\"eligible\":false")));
    assertThrows(IOException.class, () -> client(true).fetch("tenant-1", "env-1", EXPECTED));
    retrievalReply.set(new Reply(200, RETRIEVAL_BODY.replace("\"chunk_index\":0,", "")));
    assertThrows(IOException.class, () -> client(true).fetch("tenant-1", "env-1", EXPECTED));
    caseReply.set(new Reply(503, "{}"));
    assertThrows(IOException.class, () -> client(true).fetch("tenant-1", "env-1", EXPECTED));
  }

  @Test
  void rejectsAmbiguousOrTrailingJson() {
    caseReply.set(new Reply(200, CASE_BODY.replace("\"case_id\":\"case-1\"",
        "\"case_id\":\"case-1\",\"case_id\":\"case-1\"")));
    assertThrows(IOException.class, () -> client(true).fetch("tenant-1", "env-1", EXPECTED));
    caseReply.set(new Reply(200, CASE_BODY + "{}"));
    assertThrows(IOException.class, () -> client(true).fetch("tenant-1", "env-1", EXPECTED));
    assertEquals(0, retrievalCalls.get());
  }

  @Test
  void rejectsChangedMissingOrMismatchedRetrievalContent() {
    var invalidBodies = List.of(
        CONTENT_BODY.replace(EXCERPT, "metrics😀ac"),
        CONTENT_BODY.replace("\"excerpt\":\"" + EXCERPT + "\"", "\"excerpt\":null"),
        CONTENT_BODY.replace("\"end_offset\":10", "\"end_offset\":9"),
        CONTENT_BODY.replace("\"item_id\":\"item-1\"", "\"item_id\":\"item-2\""),
        CONTENT_BODY.replace("\"score\":1", "\"score\":2"),
        CONTENT_BODY.replace("\"query\":\"metrics\"", "\"query\":\"other\""),
        CONTENT_BODY.replace("\"created_at\":\"2026-01-01T00:00:00Z\"",
            "\"created_at\":\"2026-01-02T00:00:00Z\""),
        CONTENT_BODY.replace("\"manifest_digest\":\"" + MANIFEST_DIGEST + "\"",
            "\"manifest_digest\":\"" + "e".repeat(64) + "\""),
        CONTENT_BODY.replace("\"results\":[", "\"results\":[],\"other\":["));
    for (String invalid : invalidBodies) {
      contentReply.set(new Reply(200, invalid));
      assertThrows(IOException.class, () -> client(true).fetch("tenant-1", "env-1", EXPECTED));
    }
    contentReply.set(new Reply(503, "{}"));
    assertThrows(IOException.class, () -> client(true).fetch("tenant-1", "env-1", EXPECTED));
  }

  @Test
  void rejectsInvalidManifestMetadataBeforeFetchingContent() {
    for (String invalid : List.of(
        RETRIEVAL_BODY.replace("\"query\":\"metrics\"", "\"query\":\"\""),
        RETRIEVAL_BODY.replace("\"query\":\"metrics\"", "\"query\":\"different\""),
        RETRIEVAL_BODY.replace("\"created_at\":\"2026-01-01T00:00:00Z\"", "\"created_at\":\"invalid\""),
        RETRIEVAL_BODY.replace("\"score\":1", "\"score\":0"),
        RETRIEVAL_BODY.replace("\"score\":1", "\"score\":1.5"))) {
      retrievalReply.set(new Reply(200, invalid));
      assertThrows(IOException.class, () -> client(true).fetch("tenant-1", "env-1", EXPECTED));
    }
    assertEquals(0, contentCalls.get());
  }

  @Test
  void rejectsMalformedUnicodeEvenWhenUtf8ReplacementWouldMatch() {
    String replacementDigest =
        new InputPacket("metrics\ud800ab".getBytes(StandardCharsets.UTF_8)).bytesDigest();
    String alteredManifest = manifestDigest("INTERNAL", replacementDigest);
    var expected = new SourceReceiptConsistency.Expected("case-1", "retrieval-1", alteredManifest,
        "assessment", SourceReceiptConsistency.Classification.INTERNAL);
    retrievalReply.set(new Reply(200, RETRIEVAL_BODY.replace("\"excerpt_digest\":\"" + EXCERPT_DIGEST + "\"",
        "\"excerpt_digest\":\"" + replacementDigest + "\"")
        .replace(MANIFEST_DIGEST, alteredManifest)));
    contentReply.set(new Reply(200, CONTENT_BODY.replace("\"excerpt_digest\":\"" + EXCERPT_DIGEST + "\"",
        "\"excerpt_digest\":\"" + replacementDigest + "\"")
        .replace(MANIFEST_DIGEST, alteredManifest).replace(EXCERPT, "metrics\\ud800ab")));
    assertThrows(IOException.class, () -> client(true).fetch("tenant-1", "env-1", expected));
    assertEquals(1, contentCalls.get());
  }

  @Test
  void rejectsOversizedResponsesAndDoesNotFollowRedirects() {
    caseReply.set(new Reply(200, "x".repeat(512 * 1024 + 1)));
    assertThrows(IOException.class, () -> client(true).fetch("tenant-1", "env-1", EXPECTED));
    caseReply.set(new Reply(302, "{}"));
    assertThrows(IOException.class, () -> client(true).fetch("tenant-1", "env-1", EXPECTED));
    assertEquals(2, caseCalls.get());
    assertEquals(0, retrievalCalls.get());
  }

  @Test
  void timesOutEvenAfterResponseHeadersArrive() {
    var release = new CountDownLatch(1);
    blockedBody.set(release);
    long started = System.nanoTime();
    try {
      assertThrows(IOException.class, () -> client(true).fetch("tenant-1", "env-1", EXPECTED));
      assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
      assertEquals(0, retrievalCalls.get());
    } finally {
      release.countDown();
    }
  }

  @Test
  void sharedBudgetBoundsBodyReadsAndPreventsRequestsAfterExpiry() {
    var release = new CountDownLatch(1);
    blockedBody.set(release);
    var http = new InternalJsonClient(Duration.ofMillis(500));
    try {
      assertThrows(IOException.class,
          () -> http.post(base.resolve("/internal/v1/cases/case-1:receipt"), "test-token", Map.of()));
      assertEquals(1, caseCalls.get());
      assertThrows(IOException.class,
          () -> http.post(base.resolve("/internal/v1/cases/case-1:receipt"), "test-token", Map.of()));
      assertEquals(1, caseCalls.get());
    } finally {
      release.countDown();
    }
  }

  @Test
  void restrictsDelegationsToRequestedClassificationAndRejectsBadTokens() throws Exception {
    setClassification("RESTRICTED");
    var restricted = new SourceReceiptConsistency.Expected(
        "case-1", "retrieval-1", manifestDigest("RESTRICTED", EXCERPT_DIGEST), "assessment",
        SourceReceiptConsistency.Classification.RESTRICTED);
    client(true).fetch("tenant-1", "env-1", restricted);
    assertEquals("tenant-1/env-1/S08/knowledge.retrieval.read,knowledge.classification.restricted.read",
        tokenRequests.get(1));
    var invalid = new OwnerReceiptClient(true, base, base, true,
        (tenant, env, audience, capabilities) -> "token\r\ninjected");
    assertThrows(IOException.class, () -> invalid.fetch("tenant-1", "env-1", EXPECTED));
    assertEquals(1, caseCalls.get());
  }

  @Test
  void requiresSecureOrExplicitLiteralLoopbackEndpoints() {
    var provider = (OwnerReceiptClient.DelegationProvider) (tenant, env, audience, capabilities) -> "token";
    assertThrows(IllegalArgumentException.class, () -> new OwnerReceiptClient(
        true, base, base, false, provider));
    assertThrows(IllegalArgumentException.class, () -> new OwnerReceiptClient(
        true, URI.create("http://example.test"), base, true, provider));
    assertThrows(IllegalArgumentException.class, () -> new OwnerReceiptClient(
        true, URI.create("https://user:password@example.test"), base, true, provider));
    assertThrows(IllegalArgumentException.class, () -> new OwnerReceiptClient(
        true, URI.create("https://example.test/other"), base, true, provider));
    assertFalse(tokenRequests.iterator().hasNext());
  }

  private record Reply(int status, String body) {}
}
