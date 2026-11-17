package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.uacrf.security.DelegationJwtEncoderFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class AdvisoryCrossServiceTests {
  private static final String IMAGE =
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81";
  private static final String ISSUER = "https://identity.example.test/realms/uacrf";
  private static final String TENANT = "tenant-integration";
  private static final String ENVIRONMENT = "env-integration";
  private static final String KEY_ID = "integration-delegation";
  private static final String CONTENT = "metrics guidance for testing";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(2)).proxy(java.net.ProxySelector.of(null)).build();
  private final List<Process> processes = new ArrayList<>();

  @Container private static final PostgreSQLContainer IDENTITY = database("identity");
  @Container private static final PostgreSQLContainer CATALOG = database("catalog");
  @Container private static final PostgreSQLContainer ASSESSMENT = database("assessment");
  @Container private static final PostgreSQLContainer KNOWLEDGE = database("knowledge");
  @TempDir Path temp;

  @Test
  void checksRequesterAgainstRealIdentityAssessmentAndKnowledge() throws Exception {
    KeyPair oidcKey = keyPair();
    KeyPair delegationKey = keyPair();
    String publicKey = Base64.getEncoder().encodeToString(delegationKey.getPublic().getEncoded());
    String privateKey = Base64.getEncoder().encodeToString(delegationKey.getPrivate().getEncoded());
    HttpServer oidc = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String jwks = "{\"keys\":[" + new RSAKey.Builder((RSAPublicKey) oidcKey.getPublic())
        .keyID("integration-oidc").build().toPublicJWK().toJSONString() + "]}";
    oidc.createContext("/jwks", exchange -> {
      byte[] body = jwks.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
    });
    oidc.createContext("/token", exchange -> {
      String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      boolean valid = "POST".equals(exchange.getRequestMethod())
          && request.contains("grant_type=client_credentials")
          && request.contains("client_id=s08-test")
          && request.contains("client_secret=fixture-secret");
      String body = valid ? JSON.writeValueAsString(Map.of("access_token", oidcToken(oidcKey, "s08-subject"),
          "token_type", "Bearer", "expires_in", 60)) : "{}";
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(valid ? 200 : 403, bytes.length);
      try (var output = exchange.getResponseBody()) { output.write(bytes); }
    });
    oidc.start();
    try {
      String oidcBase = "http://127.0.0.1:" + oidc.getAddress().getPort();
      URI identity = start("identity", Map.ofEntries(
          Map.entry("IDENTITY_JDBC_URL", IDENTITY.getJdbcUrl()),
          Map.entry("IDENTITY_DB_USER", IDENTITY.getUsername()),
          Map.entry("IDENTITY_DB_PASSWORD", IDENTITY.getPassword()),
          Map.entry("KEYCLOAK_ISSUER_URI", ISSUER),
          Map.entry("KEYCLOAK_JWK_SET_URI", oidcBase + "/jwks"),
          Map.entry("UACRF_DELEGATION_PUBLIC_KEY_BASE64", publicKey),
          Map.entry("UACRF_DELEGATION_PRIVATE_KEY_BASE64", privateKey),
          Map.entry("UACRF_DELEGATION_KEY_ID", KEY_ID),
          Map.entry("IDENTITY_BOOTSTRAP_ENABLED", "true"),
          Map.entry("IDENTITY_BOOTSTRAP_PROVIDER_ID", "integration-idp"),
          Map.entry("IDENTITY_BOOTSTRAP_PROVIDER_NAME", "Integration identity provider"),
          Map.entry("IDENTITY_BOOTSTRAP_ISSUER", ISSUER),
          Map.entry("IDENTITY_BOOTSTRAP_TENANT_ID", TENANT),
          Map.entry("IDENTITY_BOOTSTRAP_TENANT_NAME", "Integration tenant"),
          Map.entry("IDENTITY_BOOTSTRAP_ENVIRONMENT_ID", ENVIRONMENT),
          Map.entry("IDENTITY_BOOTSTRAP_ENVIRONMENT_NAME", "Integration environment"),
          Map.entry("IDENTITY_BOOTSTRAP_PRINCIPAL_ID", "admin"),
          Map.entry("IDENTITY_BOOTSTRAP_SUBJECT", "admin-subject"),
          Map.entry("IDENTITY_BOOTSTRAP_DISPLAY_NAME", "Integration admin"),
          Map.entry("IDENTITY_BOOTSTRAP_ADMIN_EXPIRES_AT", "2099-12-31T23:59:59Z"),
          Map.entry("IDENTITY_BOOTSTRAP_REASON", "Integration test")));
      UUID advisoryMembership = seedIdentity();
      URI catalog = start("estate-catalog", Map.of(
          "CATALOG_JDBC_URL", CATALOG.getJdbcUrl(),
          "CATALOG_DB_USER", CATALOG.getUsername(),
          "CATALOG_DB_PASSWORD", CATALOG.getPassword(),
          "UACRF_DELEGATION_PUBLIC_KEY_BASE64", publicKey,
          "UACRF_DELEGATION_KEY_ID", KEY_ID));
      String versionDigest = seedActiveProfileVersion();
      URI assessment = start("assessment", Map.of(
          "ASSESSMENT_JDBC_URL", ASSESSMENT.getJdbcUrl(), "ASSESSMENT_DB_USER", ASSESSMENT.getUsername(),
          "ASSESSMENT_DB_PASSWORD", ASSESSMENT.getPassword(), "UACRF_DELEGATION_PUBLIC_KEY_BASE64", publicKey,
          "UACRF_DELEGATION_KEY_ID", KEY_ID));
      URI knowledge = start("knowledge", Map.ofEntries(
          Map.entry("KNOWLEDGE_JDBC_URL", KNOWLEDGE.getJdbcUrl()),
          Map.entry("KNOWLEDGE_DB_USER", KNOWLEDGE.getUsername()),
          Map.entry("KNOWLEDGE_DB_PASSWORD", KNOWLEDGE.getPassword()),
          Map.entry("UACRF_DELEGATION_PUBLIC_KEY_BASE64", publicKey),
          Map.entry("UACRF_DELEGATION_KEY_ID", KEY_ID),
          Map.entry("UACRF_KNOWLEDGE_CASE_RECEIPTS_ENABLED", "true"),
          Map.entry("UACRF_KNOWLEDGE_CASE_RECEIPTS_TOKEN_URL", oidcBase + "/token"),
          Map.entry("UACRF_KNOWLEDGE_CASE_RECEIPTS_CLIENT_ID", "s08-test"),
          Map.entry("UACRF_KNOWLEDGE_CASE_RECEIPTS_CLIENT_SECRET", "fixture-secret"),
          Map.entry("UACRF_KNOWLEDGE_CASE_RECEIPTS_DELEGATION_URL",
              identity.resolve("/internal/v1/delegations").toString()),
          Map.entry("UACRF_KNOWLEDGE_CASE_RECEIPTS_ASSESSMENT_BASE_URL", assessment.toString())));
      String requester = oidcToken(oidcKey, "requester-subject");
      String reviewer = oidcToken(oidcKey, "reviewer-subject");
      String caseWriter = delegate(identity, requester, "S07", List.of("assessment.case.write"));
      JsonNode created = post(assessment, "/v1/cases", caseWriter, """
          {"workload_id":"workload-1","purpose":"assessment","metric":"latency",
           "comparator":"LTE","threshold":10,"unit":"ms","window_seconds":3600,
           "objective_ref":{"objective_id":"o1","revision":1,"digest":"od"},
           "graph_ref":{"graph_id":"g1","revision":1,"digest":"gd"},
           "required_source_kinds":["metrics"]}
          """, 201);
      String caseId = created.get("id").asString();
      JsonNode sealed = post(assessment, "/v1/cases/" + caseId + ":seal", caseWriter,
          "{\"expected_revision\":1}", 200);
      String writer = delegate(identity, requester, "S08",
          List.of("knowledge.item.write", "knowledge.classification.internal.read"));
      String review = delegate(identity, reviewer, "S08",
          List.of("knowledge.item.review", "knowledge.classification.internal.read"));
      post(knowledge, "/v1/knowledge-items", writer, JSON.writeValueAsString(Map.of(
          "item_id", "guidance", "version", "v1", "title", "Guidance", "content", CONTENT,
          "classification", "INTERNAL", "allowed_purposes", List.of("assessment"),
          "target_workload_ids", List.of(), "review_deadline", Instant.now().plusSeconds(600).toString(),
          "reason", "Integration test")), 201);
      post(knowledge, "/v1/knowledge-items/guidance:review", review,
          "{\"decision\":\"APPROVE\",\"expected_revision\":1,\"reason\":\"Approved\"}", 200);
      String runner = delegate(identity, requester, "S08",
          List.of("knowledge.retrieval.run", "knowledge.classification.internal.read"));
      JsonNode retrieval = post(knowledge, "/v1/retrievals", runner,
          JSON.writeValueAsString(Map.of("case_id", caseId, "purpose", "assessment",
              "max_classification", "INTERNAL", "query", "metrics", "max_results", 10)), 201);
      var expected = new SourceReceiptConsistency.Expected(caseId, retrieval.get("retrieval_id").asString(),
          retrieval.get("manifest_digest").asString(), "assessment", SourceReceiptConsistency.Classification.INTERNAL);
      var checker = new RequesterSourceCheck(true, identity, assessment, knowledge, catalog, true);
      byte[] sourceBytes = CONTENT.getBytes(StandardCharsets.UTF_8);
      String sourceDigest = Sha256.hex(sourceBytes);
      var sources = Map.of(sourceDigest,
          new DigestBoundArtifact("artifact:sha256:" + sourceDigest, sourceDigest, sourceBytes));
      var models = artifact("""
          {"protocol":"UACRF_ADVISORY_MODELS_V1","tenant_id":"%s","environment_id":"%s",
           "models":[{"model_ref":"model-a","engine":"HTTP_JSON_ADVISORY_V1","timeout_seconds":30,
           "maximum_output_tokens":30,"maximum_cost_units":5}]}
          """.formatted(TENANT, ENVIRONMENT));
      var prompts = artifact("""
          {"protocol":"UACRF_ADVISORY_PROMPTS_V1","tenant_id":"%s","environment_id":"%s",
           "prompts":[{"instructions_digest":"%s","text":"instructions"}]}
          """.formatted(TENANT, ENVIRONMENT, "a".repeat(64)));
      byte[] requestBytes = JSON.writeValueAsBytes(Map.ofEntries(
          Map.entry("agent_case_id", caseId),
          Map.entry("retrieval_manifest_id", expected.retrievalId()),
          Map.entry("workflow_version", "SINGLE_ANALYST_V1"),
          Map.entry("model_config_ref", models.reference()), Map.entry("model_config_digest", models.digest()),
          Map.entry("prompt_ref", prompts.reference()), Map.entry("prompt_digest", prompts.digest()),
          Map.entry("max_calls", 1), Map.entry("max_tokens", 100), Map.entry("max_cost_units", 5),
          Map.entry("deadline", "2099-01-01T00:00:00Z"), Map.entry("reason", "Integration test"),
          Map.entry("allocations", List.of(Map.of("role", "ANALYST", "agent_profile_id", "analyst",
              "tokens", 100, "cost_units", 5))),
          Map.entry("proposal_ids", List.of("proposal-1"))));
      var profiles = Map.of("analyst",
          new ProfileConfigurationCheck.VersionPins(
              "version-1", versionDigest, "model-a", "a".repeat(64), 100, 5, 30));
      var gatewayRequests = Map.of("analyst",
          new ProfileConfigurationCheck.RequestLimits("attempt-1", 30, Duration.ofSeconds(30)));
      var profilePrepared = new RunPreparationCheck(checker).checkWithProfiles(requester, TENANT, ENVIRONMENT,
          AdvisoryRunRequest.parse(requestBytes), expected, models, prompts, sources, profiles, gatewayRequests);
      assertEquals(profiles, profilePrepared.profiles());
      assertEquals("instructions", profilePrepared.selections().get("analyst").prompt().text());
      assertEquals(gatewayRequests, profilePrepared.limits());
      assertEquals(profilePrepared.packets().get("analyst").bytesDigest(),
          Sha256.hex(profilePrepared.gatewayRequests().get("analyst").context().getBytes(StandardCharsets.UTF_8)));
      var journalIntent = profilePrepared.journalIntents(
          new DispatchJournal.RunKey(TENANT, ENVIRONMENT, "run-1"), 1, Map.of("analyst", "assignment-1")).getFirst();
      assertEquals("attempt-1", journalIntent.key().attemptId());
      assertEquals("run-1", journalIntent.runId());
      assertEquals("assignment-1", journalIntent.assignmentId());
      assertEquals(profilePrepared.packets().get("analyst").bytesDigest(), journalIntent.contextDigest());
      var prepared = profilePrepared.preparation();
      assertEquals("model-a", prepared.models().models().getFirst().reference());
      assertEquals("instructions", prepared.prompts().prompts().getFirst().text());
      var snapshot = prepared.sources();
      assertEquals("requester", snapshot.requester().id());
      assertEquals(sealed.get("case_digest").asString(), snapshot.receipts().sourceCase().caseDigest());
      assertEquals("latency", JSON.readTree(snapshot.caseMaterial()).get("metric").asString());
      assertEquals(List.of(CONTENT), snapshot.receipts().excerpts());
      byte[] changedBytes = (CONTENT + " altered").getBytes(StandardCharsets.UTF_8);
      String changedDigest = Sha256.hex(changedBytes);
      var changedSources = Map.of(changedDigest,
          new DigestBoundArtifact("artifact:sha256:" + changedDigest, changedDigest, changedBytes));
      assertThrows(IllegalArgumentException.class,
          () -> checker.checkWithSources(requester, TENANT, ENVIRONMENT, expected, Map.of()));
      assertThrows(IllegalArgumentException.class,
          () -> checker.checkWithSources(requester, TENANT, ENVIRONMENT, expected, changedSources));
      assertThrows(IllegalArgumentException.class,
          () -> checker.checkWithSources(requester, TENANT, ENVIRONMENT, expected,
              Map.of(sourceDigest, sources.get(sourceDigest), changedDigest, changedSources.get(changedDigest))));

      String admin = oidcToken(oidcKey, "admin-subject");
      AtomicReference<HttpResponse<String>> revocation = new AtomicReference<>();
      try (var proxy = new ContentProxy(knowledge, expected.retrievalId(), () ->
          revocation.set(sendPost(identity, "/v1/memberships/" + advisoryMembership + ":revoke?tenant_id=" + TENANT
                  + "&environment_id=" + ENVIRONMENT, admin,
              "{\"expected_revision\":1,\"reason\":\"Revoke integration grant\"}")))) {
        var intercepted = new RequesterSourceCheck(true, identity, assessment, proxy.base(), true);
        assertEquals("Requester lacks required advisory or source access capabilities.",
            assertThrows(IOException.class,
                () -> intercepted.checkWithSources(requester, TENANT, ENVIRONMENT, expected, sources)).getMessage());
        assertEquals(1, proxy.contentReads());
        assertNull(proxy.failure());
        assertNotNull(revocation.get());
        assertEquals(200, revocation.get().statusCode(), revocation.get().body());
      }

      String secondRequester = oidcToken(oidcKey, "requester-2-subject");
      assertEquals("requester-2", checker.check(secondRequester, TENANT, ENVIRONMENT, expected).requester().id());
      String withdrawer = delegate(identity, requester, "S08", List.of("knowledge.item.withdraw"));
      AtomicReference<HttpResponse<String>> withdrawal = new AtomicReference<>();
      try (var proxy = new ContentProxy(knowledge, expected.retrievalId(), () ->
          withdrawal.set(sendPost(knowledge, "/v1/knowledge-items/guidance:withdraw", withdrawer,
              "{\"expected_revision\":2,\"reason\":\"Withdraw test source\"}")))) {
        var intercepted = new RequesterSourceCheck(true, identity, assessment, proxy.base(), true);
        assertEquals("Retrieval receipt changed during content checking.",
            assertThrows(IOException.class,
                () -> intercepted.checkWithSources(secondRequester, TENANT, ENVIRONMENT, expected, sources))
                .getMessage());
        assertEquals(1, proxy.contentReads());
        assertNull(proxy.failure());
        assertNotNull(withdrawal.get());
        assertEquals(200, withdrawal.get().statusCode(), withdrawal.get().body());
      }
      assertEquals("Owner receipt consistency check failed.", assertThrows(IOException.class,
          () -> checker.check(secondRequester, TENANT, ENVIRONMENT, expected)).getMessage());
    } finally {
      try {
        for (Process process : processes.reversed()) process.destroy();
        for (Process process : processes.reversed()) {
          if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
          }
        }
      } finally {
        for (Process process : processes) if (process.isAlive()) process.destroyForcibly();
        oidc.stop(0);
      }
    }
  }

  private static DigestBoundArtifact artifact(String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    String digest = Sha256.hex(bytes);
    return new DigestBoundArtifact("artifact:sha256:" + digest, digest, bytes);
  }

  private static PostgreSQLContainer database(String name) {
    return new PostgreSQLContainer(IMAGE).withDatabaseName("uacrf_" + name)
        .withUsername(name + "_test").withPassword(name + "_test");
  }

  private URI start(String service, Map<String, String> environment) throws Exception {
    return CrossServiceProcess.start(service, environment, temp, processes, HTTP);
  }

  private UUID seedIdentity() throws Exception {
    UUID advisory = UUID.randomUUID();
    try (var connection = DriverManager.getConnection(IDENTITY.getJdbcUrl(), IDENTITY.getUsername(),
        IDENTITY.getPassword())) {
      try (var principal = connection.prepareStatement(
          "INSERT INTO uacrf_identity.principal "
              + "(id,issuer_uri,subject,display_name,kind,enabled,token_valid_after,revision) "
              + "VALUES (?,?,?,?,?,true,now()-interval '1 minute',1)")) {
        for (String[] row : List.of(new String[] {"requester", "requester-subject", "HUMAN"},
            new String[] {"requester-2", "requester-2-subject", "HUMAN"},
            new String[] {"reviewer", "reviewer-subject", "HUMAN"},
            new String[] {"s08", "s08-subject", "SYSTEM"})) {
          principal.setString(1, row[0]); principal.setString(2, ISSUER); principal.setString(3, row[1]);
          principal.setString(4, row[0]); principal.setString(5, row[2]); principal.executeUpdate();
        }
      }
      try (var membership = connection.prepareStatement(
          "INSERT INTO uacrf_identity.membership "
              + "(id,tenant_id,environment_id,principal_id,capability,service_scope,expires_at,revision) "
              + "VALUES (?,?,?,?,?,'*','2099-12-31T23:59:59Z',1)")) {
        for (var entry : Map.of(
            "requester", List.of("advisory.run.create", "assessment.case.read", "assessment.case.write",
                "knowledge.retrieval.read", "knowledge.retrieval.run", "knowledge.item.write",
                "knowledge.item.withdraw", "knowledge.classification.internal.read", "catalog.agent.read"),
            "requester-2", List.of("advisory.run.create", "assessment.case.read",
                "knowledge.retrieval.read", "knowledge.classification.internal.read"),
            "reviewer", List.of("knowledge.item.review", "knowledge.classification.internal.read"),
            "s08", List.of("assessment.case.read")).entrySet()) {
          for (String capability : entry.getValue()) {
            membership.setObject(1, "requester".equals(entry.getKey()) && "advisory.run.create".equals(capability)
                ? advisory : UUID.randomUUID());
            membership.setString(2, TENANT); membership.setString(3, ENVIRONMENT);
            membership.setString(4, entry.getKey()); membership.setString(5, capability);
            membership.executeUpdate();
          }
        }
      }
    }
    return advisory;
  }

  private String seedActiveProfileVersion() throws Exception {
    String manifest = """
        {"instructions_digest":"%s","model_ref":"model-a","knowledge_refs":[],"tools":[],
         "memory_namespace":"memory","evaluation_suite_ref":"eval","recovery_rules_ref":"recovery",
         "runtime_build":"runtime","data_policy_ref":"policy","handoff_ref":"handoff",
         "limits":{"steps":10,"tokens":100,"cost_units":5,"duration_seconds":30}}
        """
        .formatted("a".repeat(64))
        .replaceAll("\\s+", "");
    String digest = Sha256.hex(JSON.writeValueAsBytes(JSON.readValue(manifest, Object.class)));
    try (var connection =
        DriverManager.getConnection(CATALOG.getJdbcUrl(), CATALOG.getUsername(), CATALOG.getPassword())) {
      try (var profile =
          connection.prepareStatement(
              "INSERT INTO uacrf_catalog.agent_workload "
                  + "(tenant_id,environment_id,id,service_id,name,kind,owner_id,runtime_principal_id,"
                  + "purpose,revision) VALUES (?,?,?,NULL,?,'SINGLE_AGENT',?,?,?,1)")) {
        profile.setString(1, TENANT);
        profile.setString(2, ENVIRONMENT);
        profile.setString(3, "analyst");
        profile.setString(4, "Analyst profile");
        profile.setString(5, "owner-1");
        profile.setString(6, "runtime-1");
        profile.setString(7, "Advisory integration fixture");
        profile.executeUpdate();
      }
      try (var version =
          connection.prepareStatement(
              "INSERT INTO uacrf_catalog.agent_workload_version "
                  + "(tenant_id,environment_id,workload_id,id,version_number,digest,object_ref,manifest,"
                  + "created_by,state,revision,activated_by,activated_at) "
                  + "VALUES (?,?,?,?,1,?,?,?::jsonb,'requester','ACTIVE',2,'reviewer',now())")) {
        version.setString(1, TENANT);
        version.setString(2, ENVIRONMENT);
        version.setString(3, "analyst");
        version.setString(4, "version-1");
        version.setString(5, digest);
        version.setString(6, "artifact:sha256:" + digest);
        version.setString(7, manifest);
        version.executeUpdate();
      }
      try (var activate =
          connection.prepareStatement(
              "UPDATE uacrf_catalog.agent_workload SET active_version_id='version-1',revision=2 "
                  + "WHERE tenant_id=? AND environment_id=? AND id='analyst'")) {
        activate.setString(1, TENANT);
        activate.setString(2, ENVIRONMENT);
        activate.executeUpdate();
      }
    }
    return digest;
  }

  private static String delegate(URI identity, String requester, String audience, List<String> capabilities)
      throws Exception {
    return post(identity, "/internal/v1/delegations", requester, JSON.writeValueAsString(Map.of(
        "audience", audience, "capability_ids", capabilities, "tenant_id", TENANT,
        "environment_id", ENVIRONMENT)), 200).get("access_token").asString();
  }

  private static JsonNode post(URI base, String path, String token, String body, int expected) throws Exception {
    var response = sendPost(base, path, token, body);
    assertEquals(expected, response.statusCode(), response.body());
    return JSON.readTree(response.body());
  }

  private static HttpResponse<String> sendPost(URI base, String path, String token, String body)
      throws IOException, InterruptedException {
    return HTTP.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @FunctionalInterface
  private interface ContentAction {
    void run() throws IOException, InterruptedException;
  }

  private static final class ContentProxy implements AutoCloseable {
    private final HttpServer server;
    private final AtomicInteger contentReads = new AtomicInteger();
    private final AtomicReference<Exception> failure = new AtomicReference<>();

    ContentProxy(URI knowledge, String retrievalId, ContentAction afterContent) throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      String contentPath = "/v1/retrievals/" + retrievalId;
      String receiptPath = "/internal/v1/retrievals/" + retrievalId + ":receipt";
      server.createContext("/", exchange -> {
        String path = exchange.getRequestURI().getPath();
        boolean content = "GET".equals(exchange.getRequestMethod()) && contentPath.equals(path);
        boolean receipt = "POST".equals(exchange.getRequestMethod()) && receiptPath.equals(path);
        if (!content && !receipt) {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
          return;
        }
        try {
          var request = HttpRequest.newBuilder(knowledge.resolve(path))
              .timeout(Duration.ofSeconds(5))
              .header("Authorization", exchange.getRequestHeaders().getFirst("Authorization"));
          if (receipt) {
            request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes()));
          } else {
            request.GET();
          }
          var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
          if (content && response.statusCode() == 200) {
            contentReads.incrementAndGet();
            afterContent.run();
          }
          exchange.sendResponseHeaders(response.statusCode(), response.body().length);
          try (var output = exchange.getResponseBody()) { output.write(response.body()); }
        } catch (IOException error) {
          failure.set(error);
          exchange.sendResponseHeaders(502, -1);
          exchange.close();
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          failure.set(error);
          exchange.sendResponseHeaders(502, -1);
          exchange.close();
        }
      });
      server.start();
    }

    URI base() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    int contentReads() { return contentReads.get(); }
    Exception failure() { return failure.get(); }
    @Override public void close() { server.stop(0); }
  }

  private static String oidcToken(KeyPair key, String subject) {
    Instant now = Instant.now();
    var claims = JwtClaimsSet.builder().issuer(ISSUER).subject(subject).audience(List.of("uacrf-s01"))
        .issuedAt(now).expiresAt(now.plusSeconds(300)).build();
    return DelegationJwtEncoderFactory.create(key, "integration-oidc")
        .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256)
            .keyId("integration-oidc").build(), claims)).getTokenValue();
  }

  private static KeyPair keyPair() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }
}
