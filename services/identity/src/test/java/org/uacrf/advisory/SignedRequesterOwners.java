package org.uacrf.advisory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

public final class SignedRequesterOwners implements AutoCloseable {
  private static final String CASE_PATH = "/internal/v1/cases/case-1:receipt";
  private static final String CASE_MATERIAL_PATH = "/internal/v1/cases/case-1:material";
  private static final String RECEIPT_PATH = "/internal/v1/retrievals/retrieval-1:receipt";
  private static final String CONTENT_PATH = "/v1/retrievals/retrieval-1";
  private static final String CASE_BODY = """
      {"case_id":"case-1","purpose":"assessment","case_digest":"%s",
       "workload_id":"workload-1","required_source_kinds":["metrics"]}
      """.formatted(RequesterIdentityProbe.CASE_DIGEST);
  private static final String CASE_MATERIAL_BODY = JsonMapper.builder().build().writeValueAsString(Map.of(
      "case_id", "case-1", "case_digest", RequesterIdentityProbe.CASE_DIGEST,
      "canonical_text", RequesterIdentityProbe.CASE_MATERIAL));
  private static final String RESULT = """
      {"item_id":"item-1","version":"v1","chunk_index":0,"start_offset":0,"end_offset":7,
       "source_digest":"%s","excerpt_digest":"%s","allowed_purposes":["assessment"],
       "classification":"PUBLIC","score":1}
      """.formatted(RequesterIdentityProbe.SOURCE_DIGEST, RequesterIdentityProbe.EXCERPT_DIGEST).strip();
  private static final String RECEIPT_BODY = """
      {"retrieval_id":"retrieval-1","requested_case_id":"case-1","case_id":"case-1",
       "case_binding":"VERIFIED","case_digest":"%s","workload_id":"workload-1","purpose":"assessment",
       "classification":"INTERNAL","manifest_digest":"%s","query":"metrics",
       "created_at":"2026-01-01T00:00:00Z","eligible":true,"reason":"ELIGIBLE","results":[%s]}
      """.formatted(RequesterIdentityProbe.CASE_DIGEST, RequesterIdentityProbe.MANIFEST_DIGEST, RESULT);
  private static final String CONTENT_BODY = """
      {"retrieval_id":"retrieval-1","case_id":"case-1","case_binding":"VERIFIED",
       "case_digest":"%s","workload_id":"workload-1","purpose":"assessment",
       "max_classification":"INTERNAL","manifest_digest":"%s","query":"metrics",
       "created_at":"2026-01-01T00:00:00Z","results":[%s]}
      """.formatted(RequesterIdentityProbe.CASE_DIGEST, RequesterIdentityProbe.MANIFEST_DIGEST,
          RESULT.substring(0, RESULT.length() - 1) + ",\"excerpt\":\"metrics\"}");

  private final HttpServer server;
  private final NimbusJwtDecoder decoder;
  private final String keyId;
  private final AtomicInteger caseCalls = new AtomicInteger();
  private final AtomicInteger caseMaterialCalls = new AtomicInteger();
  private final AtomicInteger receiptCalls = new AtomicInteger();
  private final AtomicInteger contentCalls = new AtomicInteger();
  private final AtomicReference<Runnable> contentHook = new AtomicReference<>(() -> {});

  public SignedRequesterOwners(RSAPublicKey publicKey, String keyId) throws IOException {
    this.keyId = keyId;
    decoder = NimbusJwtDecoder.withPublicKey(publicKey).signatureAlgorithm(SignatureAlgorithm.RS256).build();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(CASE_PATH, exchange -> {
      caseCalls.incrementAndGet();
      handle(exchange, CASE_PATH, "POST", "S07", List.of("assessment.case.read"), CASE_BODY);
    });
    server.createContext(CASE_MATERIAL_PATH, exchange -> {
      caseMaterialCalls.incrementAndGet();
      handle(exchange, CASE_MATERIAL_PATH, "POST", "S07", List.of("assessment.case.read"), CASE_MATERIAL_BODY);
    });
    server.createContext(RECEIPT_PATH, exchange -> {
      receiptCalls.incrementAndGet();
      handle(exchange, RECEIPT_PATH, "POST", "S08",
          List.of("knowledge.retrieval.read", "knowledge.classification.restricted.read"), RECEIPT_BODY);
    });
    server.createContext(CONTENT_PATH, exchange -> {
      contentCalls.incrementAndGet();
      handle(exchange, CONTENT_PATH, "GET", "S08",
          List.of("knowledge.retrieval.read", "knowledge.classification.restricted.read"), CONTENT_BODY);
    });
    server.start();
  }

  public URI base() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  public int caseCalls() { return caseCalls.get(); }
  public int caseMaterialCalls() { return caseMaterialCalls.get(); }
  public int receiptCalls() { return receiptCalls.get(); }
  public int contentCalls() { return contentCalls.get(); }
  public void onContent(Runnable hook) { contentHook.set(java.util.Objects.requireNonNull(hook)); }

  private void handle(HttpExchange exchange, String path, String method, String audience,
                      List<String> capabilities, String body) throws IOException {
    if (!path.equals(exchange.getRequestURI().getPath())
        || !method.equals(exchange.getRequestMethod())
        || !authorized(exchange.getRequestHeaders().getFirst("Authorization"), audience, capabilities)) {
      reply(exchange, 403, "{}");
      return;
    }
    if (RECEIPT_PATH.equals(path)) {
      Map<?, ?> request;
      try {
        request = new JsonMapper().readValue(exchange.getRequestBody().readAllBytes(), Map.class);
      } catch (JacksonException error) {
        reply(exchange, 400, "{}");
        return;
      }
      if (!Map.of("case_id", "case-1", "purpose", "assessment", "classification", "INTERNAL",
          "manifest_digest", RequesterIdentityProbe.MANIFEST_DIGEST).equals(request)) {
        reply(exchange, 400, "{}");
        return;
      }
    }
    if (CONTENT_PATH.equals(path)) contentHook.get().run();
    reply(exchange, 200, body);
  }

  private boolean authorized(String authorization, String audience, List<String> capabilities) {
    if (authorization == null || !authorization.startsWith("Bearer ")) return false;
    try {
      Jwt jwt = decoder.decode(authorization.substring("Bearer ".length()));
      return jwt.getAudience().equals(List.of(audience))
          && "https://uacrf.local/s01".equals(jwt.getClaimAsString("iss"))
          && keyId.equals(jwt.getHeaders().get("kid"))
          && "advisory-test-human".equals(jwt.getSubject())
          && "tenant-test".equals(jwt.getClaimAsString("tenant_id"))
          && "environment-test".equals(jwt.getClaimAsString("environment_id"))
          && "*".equals(jwt.getClaimAsString("service_scope"))
          && capabilities.equals(jwt.getClaimAsStringList("capabilities"));
    } catch (JwtException error) {
      return false;
    }
  }

  private static void reply(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
