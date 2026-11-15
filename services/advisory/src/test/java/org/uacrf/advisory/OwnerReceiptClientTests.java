package org.uacrf.advisory;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OwnerReceiptClientTests {
  private static final String CASE_DIGEST = "a".repeat(64);
  private static final String MANIFEST_DIGEST = "b".repeat(64);
  private static final String EXCERPT = "metrics😀ab";
  private static final String EXCERPT_DIGEST =
      new InputPacket(EXCERPT.getBytes(StandardCharsets.UTF_8)).bytesDigest();
  private static final SourceReceiptConsistency.Expected EXPECTED =
      new SourceReceiptConsistency.Expected("case-1", "retrieval-1", MANIFEST_DIGEST, "assessment",
          SourceReceiptConsistency.Classification.INTERNAL);
  private static final String CASE_BODY = """
      {"case_id":"case-1","purpose":"assessment","case_digest":"%s",
       "workload_id":"workload-1","required_source_kinds":["metrics"]}
      """.formatted(CASE_DIGEST);
  private static final String RETRIEVAL_BODY = """
      {"retrieval_id":"retrieval-1","requested_case_id":"case-1","case_id":"case-1",
       "case_binding":"VERIFIED","case_digest":"%s","workload_id":"workload-1","purpose":"assessment",
       "classification":"INTERNAL","manifest_digest":"%s","query":"metrics","created_at":"2026-01-01T00:00:00Z",
       "eligible":true,"reason":"ELIGIBLE",
       "results":[{"item_id":"item-1","version":"v1","chunk_index":0,"start_offset":0,"end_offset":10,
       "source_digest":"%s","excerpt_digest":"%s","allowed_purposes":["assessment"],"classification":"PUBLIC","score":1}]}
      """.formatted(CASE_DIGEST, MANIFEST_DIGEST, "c".repeat(64), EXCERPT_DIGEST);
  private static final String CONTENT_BODY = """
      {"retrieval_id":"retrieval-1","case_id":"case-1","case_binding":"VERIFIED",
       "case_digest":"%s","workload_id":"workload-1","purpose":"assessment",
       "max_classification":"INTERNAL","manifest_digest":"%s","query":"metrics","created_at":"2026-01-01T00:00:00Z",
       "results":[{"item_id":"item-1","version":"v1","chunk_index":0,"start_offset":0,"end_offset":10,
       "source_digest":"%s","excerpt_digest":"%s","allowed_purposes":["assessment"],"classification":"PUBLIC",
       "score":1,"excerpt":"%s"}]}
      """.formatted(CASE_DIGEST, MANIFEST_DIGEST, "c".repeat(64), EXCERPT_DIGEST, EXCERPT);
  private HttpServer server;
  private URI base;
  private final AtomicReference<Reply> caseReply = new AtomicReference<>(new Reply(200, CASE_BODY));
  private final AtomicReference<Reply> retrievalReply = new AtomicReference<>(new Reply(200, RETRIEVAL_BODY));
  private final AtomicReference<Reply> contentReply = new AtomicReference<>(new Reply(200, CONTENT_BODY));
  private final AtomicReference<String> caseAuthorization = new AtomicReference<>();
  private final AtomicReference<String> retrievalAuthorization = new AtomicReference<>();
  private final AtomicReference<String> retrievalRequest = new AtomicReference<>();
  private final AtomicReference<String> contentAuthorization = new AtomicReference<>();
  private final AtomicInteger caseCalls = new AtomicInteger();
  private final AtomicInteger retrievalCalls = new AtomicInteger();
  private final AtomicInteger contentCalls = new AtomicInteger();
  private final AtomicInteger identityCalls = new AtomicInteger();
  private final AtomicInteger actorTokenCalls = new AtomicInteger();
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
      respond(exchange, contentReply.get());
    });
    server.createContext("/internal/v1/delegations", exchange -> {
      identityCalls.incrementAndGet();
      String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      if (!"Bearer actor-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
        respond(exchange, new Reply(403, "{}"));
        return;
      }
      String token = request.contains("\"S07\"") ? "test-token-S07" : "test-token-S08";
      respond(exchange, new Reply(200, """
          {"access_token":"%s","token_type":"Bearer","expires_in":30}
          """.formatted(token)));
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

  private static void respond(HttpExchange exchange, Reply reply) throws IOException {
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
    assertEquals(1, retrievalCalls.get());
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
    retrievalReply.set(new Reply(200, RETRIEVAL_BODY.replace(EXCERPT_DIGEST, replacementDigest)));
    contentReply.set(new Reply(200, CONTENT_BODY.replace(EXCERPT_DIGEST, replacementDigest)
        .replace(EXCERPT, "metrics\\ud800ab")));
    assertThrows(IOException.class, () -> client(true).fetch("tenant-1", "env-1", EXPECTED));
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
  void restrictsDelegationsToRequestedClassificationAndRejectsBadTokens() throws Exception {
    retrievalReply.set(new Reply(200, RETRIEVAL_BODY.replace("\"classification\":\"INTERNAL\"",
        "\"classification\":\"RESTRICTED\"")));
    contentReply.set(new Reply(200, CONTENT_BODY.replace("\"max_classification\":\"INTERNAL\"",
        "\"max_classification\":\"RESTRICTED\"")));
    var restricted = new SourceReceiptConsistency.Expected(
        "case-1", "retrieval-1", MANIFEST_DIGEST, "assessment", SourceReceiptConsistency.Classification.RESTRICTED);
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
