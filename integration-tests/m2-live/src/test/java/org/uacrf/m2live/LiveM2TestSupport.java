package org.uacrf.m2live;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.uacrf.security.DelegationJwtEncoderFactory;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.DelegationTokenIssuer;
import org.uacrf.security.OwnerReceiptSignature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class LiveM2TestSupport {
  static final String ISSUER = "https://uacrf.local/s01";
  static final String IDP_ISSUER = "https://keycloak.example.test/realms/uacrf";
  static final String TENANT = "tenant-test";
  static final String ENVIRONMENT = "environment-test";
  static final List<String> TOPOLOGY_CAPABILITIES =
      List.of("topology.graph.read", "topology.proposal.write",
          "topology.proposal.review", "topology.graph.accept");
  static final List<String> OBJECTIVE_PROPOSER =
      List.of("objectives.objective.write", "objectives.objective.read");
  static final List<String> OBJECTIVE_REVIEWER =
      List.of("objectives.objective.review", "objectives.objective.read");
  static final List<String> OBJECTIVE_ACTIVATOR =
      List.of("objectives.objective.activate", "objectives.objective.read");

  private static final String POSTGRES_IMAGE =
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81";
  private static final String NEO4J_IMAGE = "neo4j:5.26.0-community";
  private static final String KEY_ID = "m2-live-key";
  private static final String S02_KEY_ID = "m2-live-s02-key";
  private static final String IDP_KEY_ID = "m2-live-idp-key";
  private static final String IDP_AUDIENCE = "uacrf-s01";
  private static final String DIRECTORY_CLIENT_ID = "m2-live-directory-client";
  private static final String DIRECTORY_CLIENT_SECRET = "m2-live-directory-secret";
  private static final String DIRECTORY_ACCESS_TOKEN = "m2-live-directory-token";
  private static final KeyPair DELEGATION_KEYS = generateKeyPair();
  private static final KeyPair S02_KEYS = generateKeyPair();
  private static final KeyPair IDP_KEYS = generateKeyPair();
  private static final JwtEncoder DELEGATION_ENCODER =
      DelegationJwtEncoderFactory.create(DELEGATION_KEYS, KEY_ID);
  private static final DelegationTokenIssuer TOKEN_ISSUER =
      new DelegationTokenIssuer(DELEGATION_ENCODER, Clock.systemUTC(), ISSUER, KEY_ID);
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private LiveM2TestSupport() {}

  static Environment environment() throws Exception {
    return environment(false);
  }

  static Environment environmentWithWorkloadOwnerChecks() throws Exception {
    return environment(true);
  }

  private static Environment environment(boolean enableWorkloadOwnerChecks) throws Exception {
    Environment created = new Environment(enableWorkloadOwnerChecks);
    try {
      created.start();
      System.out.printf(
          "M2 live services ready: S01, S02, S05, S06, S07; startup_ms=%d%n",
          created.startupMillis());
      return created;
    } catch (Exception error) {
      try {
        created.close();
      } catch (IOException cleanupError) {
        error.addSuppressed(cleanupError);
      }
      throw error;
    }
  }

  static String bearer(
      String tenantId,
      String environmentId,
      String principalId,
      String actorSubject,
      String audience,
      List<String> capabilities) {
    DelegationPrincipal principal =
        new DelegationPrincipal(
            principalId,
            tenantId,
            environmentId,
            "*",
            IDP_ISSUER,
            actorSubject,
            capabilities,
            List.of(),
            List.of());
    return "Bearer " + TOKEN_ISSUER.issue(principal, audience);
  }

  static String bearerWithEligibleOwners(
      String tenantId,
      String environmentId,
      String principalId,
      String actorSubject,
      String audience,
      List<String> capabilities,
      List<String> eligibleOwnerIds) {
    DelegationPrincipal principal =
        new DelegationPrincipal(
            principalId,
            tenantId,
            environmentId,
            "*",
            IDP_ISSUER,
            actorSubject,
            capabilities,
            eligibleOwnerIds,
            List.of());
    return "Bearer " + TOKEN_ISSUER.issue(principal, audience);
  }

  static String delegationPublicKeyBase64() {
    return Base64.getEncoder().encodeToString(DELEGATION_KEYS.getPublic().getEncoded());
  }

  static String delegationKeyId() {
    return KEY_ID;
  }

  static Map<String, Object> identityJwkSet() {
    RSAPublicKey publicKey = (RSAPublicKey) IDP_KEYS.getPublic();
    return Map.of(
        "keys",
        List.of(
            Map.of(
                "kty", "RSA",
                "use", "sig",
                "alg", "RS256",
                "kid", IDP_KEY_ID,
                "n", base64UrlUnsigned(publicKey.getModulus()),
                "e", base64UrlUnsigned(publicKey.getPublicExponent()))));
  }

  static String identityAccessToken(String subject) {
    long issuedAt = Instant.now().getEpochSecond();
    String header =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                json(Map.of("alg", "RS256", "kid", IDP_KEY_ID, "typ", "JWT"))
                    .getBytes(StandardCharsets.UTF_8));
    String payload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                json(
                        Map.of(
                            "iss", IDP_ISSUER,
                            "sub", subject,
                            "aud", List.of(IDP_AUDIENCE),
                            "iat", issuedAt,
                            "exp", issuedAt + 300))
                    .getBytes(StandardCharsets.UTF_8));
    String signingInput = header + "." + payload;
    try {
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(IDP_KEYS.getPrivate());
      signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput
          + "."
          + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    } catch (java.security.GeneralSecurityException error) {
      throw new IllegalStateException("Could not sign the M2 identity test token.", error);
    }
  }

  private static String base64UrlUnsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes.length > 1 && bytes[0] == 0) {
      bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String principalId(String label) {
    return label + "-" + UUID.randomUUID();
  }

  static String json(Map<String, ?> value) {
    return JSON.writeValueAsString(value);
  }

  static Map<String, Object> readJson(String value) {
    @SuppressWarnings("unchecked")
    Map<String, Object> result = JSON.readValue(value, Map.class);
    return result;
  }

  static String serviceDigest(Map<String, Object> snapshot) {
    String canonical = OwnerReceiptSignature.canonicalize(json(snapshot));
    try {
      return java.util.HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  record Response(int statusCode, String body, HttpResponse<String> raw) {}

  record Fixture(String serviceId, String workloadId, String caseId, String graphId, String serviceDigest) {}

  static final class Environment implements AutoCloseable {
    private final Path repoRoot;
    private final Path logDirectory;
    private final String tenantId = "tenant-" + UUID.randomUUID();
    private final String environmentId = "environment-" + UUID.randomUUID();
    private final PostgreSQLContainer identityDb =
        new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("uacrf_identity")
            .withUsername("identity_test")
            .withPassword(UUID.randomUUID().toString());
    private final PostgreSQLContainer catalogDb =
        new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("uacrf_catalog")
            .withUsername("catalog_test")
            .withPassword(UUID.randomUUID().toString());
    private final PostgreSQLContainer assessmentDb =
        new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("uacrf_assessment")
            .withUsername("assessment_test")
            .withPassword(UUID.randomUUID().toString());
    private final PostgreSQLContainer objectivesDb =
        new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("uacrf_objectives")
            .withUsername("objectives_test")
            .withPassword(UUID.randomUUID().toString());
    private final PostgreSQLContainer topologyDb =
        new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("uacrf_topology")
            .withUsername("topology_test")
            .withPassword(UUID.randomUUID().toString());
    private final String neo4jPassword = UUID.randomUUID().toString();
    private final Neo4jContainer neo4j =
        new Neo4jContainer(NEO4J_IMAGE).withAdminPassword(neo4jPassword);
    private final HttpClient http =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ConcurrentLinkedQueue<String> stubFailures = new ConcurrentLinkedQueue<>();
    private final Map<String, Map<String, Object>> directoryUsers = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger evidenceSummaryCalls =
        new java.util.concurrent.atomic.AtomicInteger();
    private final boolean enableWorkloadOwnerChecks;
    private HttpServer ownerStubs;
    private Process identityProcess;
    private Process catalogProcess;
    private Process assessmentProcess;
    private Process objectivesProcess;
    private Process topologyProcess;
    private int identityPort;
    private int catalogPort;
    private int assessmentPort;
    private int objectivesPort;
    private int topologyPort;
    private long startupMillis;
    private boolean closed;

    private Environment(boolean enableWorkloadOwnerChecks) throws IOException {
      this.enableWorkloadOwnerChecks = enableWorkloadOwnerChecks;
      repoRoot =
          Path.of(System.getProperty("uacrf.repo.root", Path.of("../..").toAbsolutePath().toString()))
              .toAbsolutePath()
              .normalize();
      if (!Files.isRegularFile(repoRoot.resolve("pom.xml"))) {
        throw new IllegalStateException("Cannot locate the repository root from " + repoRoot);
      }
      logDirectory = Files.createTempDirectory("uacrf-m2-live-");
    }

    private void start() throws Exception {
      long startedAt = System.nanoTime();
      identityDb.start();
      catalogDb.start();
      assessmentDb.start();
      objectivesDb.start();
      topologyDb.start();
      neo4j.start();
      startOwnerStubs();

      identityPort = freePort();
      identityProcess =
          startService(
              "services/identity/target/identity-service-1.0.0-SNAPSHOT.jar",
              "identity",
              identityPort,
              identityEnvironment());
      awaitHealth("S01", identityPort, identityProcess);
      seedTopologyMembership();

      catalogPort = freePort();
      catalogProcess =
          startService(
              "services/estate-catalog/target/estate-catalog-service-1.0.0-SNAPSHOT.jar",
              "catalog",
              catalogPort,
              catalogEnvironment());
      awaitHealth("S02", catalogPort, catalogProcess);

      assessmentPort = freePort();
      assessmentProcess =
          startService(
              "services/assessment/target/assessment-service-1.0.0-SNAPSHOT.jar",
              "assessment",
              assessmentPort,
              assessmentEnvironment());
      awaitHealth("S07", assessmentPort, assessmentProcess);

      objectivesPort = freePort();
      objectivesProcess =
          startService(
              "services/objectives/target/objectives-service-1.0.0-SNAPSHOT.jar",
              "objectives",
              objectivesPort,
              objectivesEnvironment());
      awaitHealth("S05", objectivesPort, objectivesProcess);

      topologyPort = freePort();
      topologyProcess =
          startService(
              "services/topology/target/topology-service-1.0.0-SNAPSHOT.jar",
              "topology",
              topologyPort,
              topologyEnvironment());
      awaitHealth("S06", topologyPort, topologyProcess);
      startupMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    long startupMillis() {
      return startupMillis;
    }

    int evidenceSummaryCalls() {
      return evidenceSummaryCalls.get();
    }

    void verifyStubHealth() {
      if (!stubFailures.isEmpty()) {
        throw new AssertionError("M2 live fixture stub failures: " + String.join("; ", stubFailures));
      }
    }

    void registerDirectoryUser(
        String subject, String username, String firstName, String lastName, boolean enabled) {
      directoryUsers.put(
          subject,
          Map.of(
              "id", subject,
              "username", username,
              "firstName", firstName,
              "lastName", lastName,
              "enabled", enabled));
    }

    private String catalogToken(String principalId, String ownerId, String runtimePrincipalId) {
      DelegationPrincipal principal =
          new DelegationPrincipal(
              principalId,
              tenantId,
              environmentId,
              "*",
              IDP_ISSUER,
              principalId,
              List.of("catalog.service.write", "catalog.agent.write"),
              List.of(ownerId),
              List.of(runtimePrincipalId));
      return "Bearer " + TOKEN_ISSUER.issue(principal, "S02");
    }

    void registerCatalogFixture(String serviceId, String workloadId, String suffix)
        throws IOException, InterruptedException {
      registerCatalogFixture(serviceId, workloadId, suffix, "owner-" + suffix);
    }

    void registerCatalogFixture(
        String serviceId, String workloadId, String suffix, String ownerId)
        throws IOException, InterruptedException {
      String runtimePrincipalId = "runtime-" + suffix;
      String token =
          catalogToken(principalId("s02-fixture-actor"), ownerId, runtimePrincipalId);
      Response service =
          request(
              catalogUrl(),
              "POST",
              "/v1/services",
              token,
              json(
                  Map.of(
                      "reason", "M2 live owner fixture",
                      "expected_revision", 0,
                      "id", serviceId,
                      "name", "M2 live test service",
                      "owner_id", ownerId,
                      "criticality", 3,
                      "lifecycle", "ACTIVE")),
              "m2-live-service-" + suffix);
      requireStatus(201, service, "S02 service registration");
      Response workload =
          request(
              catalogUrl(),
              "POST",
              "/v1/agent-workloads",
              token,
              json(
                  Map.of(
                      "id", workloadId,
                      "service_id", serviceId,
                      "name", "M2 live test workload",
                      "kind", "SINGLE_AGENT",
                      "owner_id", ownerId,
                      "runtime_principal_id", runtimePrincipalId,
                      "purpose", "M2 live topology selection fixture")),
              "m2-live-workload-" + suffix);
      requireStatus(201, workload, "S02 workload registration");

      Map<String, Object> limits = new LinkedHashMap<>();
      limits.put("steps", 10);
      limits.put("tokens", 1_000);
      limits.put("cost_units", 100);
      limits.put("duration_seconds", 300);
      Map<String, Object> manifest = new LinkedHashMap<>();
      manifest.put("instructions_digest", "a".repeat(64));
      manifest.put("model_ref", "m2-live-model");
      manifest.put("knowledge_refs", List.of());
      manifest.put("tools", List.of());
      manifest.put("memory_namespace", "m2-live-memory");
      manifest.put("evaluation_suite_ref", "m2-live-evaluation");
      manifest.put("recovery_rules_ref", "m2-live-recovery");
      manifest.put("runtime_build", "m2-live-runtime");
      manifest.put("data_policy_ref", "m2-live-data-policy");
      manifest.put("handoff_ref", "m2-live-handoff");
      manifest.put("limits", limits);
      String digest = sha256(json(manifest));
      String versionId = "version-" + suffix;
      Response version =
          request(
              catalogUrl(),
              "POST",
              "/v1/agent-workloads/" + workloadId + "/versions",
              token,
              json(
                  Map.of(
                      "id", versionId,
                      "version_number", 1,
                      "digest", digest,
                      "object_ref", "artifact:sha256:" + digest,
                      "manifest", manifest)),
              "m2-live-workload-version-" + suffix);
      requireStatus(201, version, "S02 workload version registration");

      String reviewerToken =
          catalogToken(principalId("s02-version-approver"), ownerId, runtimePrincipalId);
      Response activatedVersion =
          request(
              catalogUrl(),
              "POST",
              "/v1/agent-workloads/" + workloadId + ":activate",
              reviewerToken,
              json(Map.of("version_id", versionId, "expected_revision", 1)),
              "m2-live-workload-activate-" + suffix);
      requireStatus(200, activatedVersion, "S02 workload version activation");
    }

    private String registerAssessmentFixture(String workloadId, String graphId, String suffix)
        throws IOException, InterruptedException {
      String objectiveId = "objective-" + suffix;
      String token = assessmentToken(principalId("s07-fixture-" + suffix), suffix);
      Response created =
          request(
              assessmentUrl(),
              "POST",
              "/v1/cases",
              token,
              json(
                  Map.of(
                      "workload_id", workloadId,
                      "purpose", "assessment",
                      "metric", "latency",
                      "comparator", "LTE",
                      "threshold", 250,
                      "unit", "ms",
                      "window_seconds", 3600,
                      "objective_ref",
                          Map.of(
                              "objective_id", objectiveId,
                              "revision", 1,
                              "digest", "a".repeat(64)),
                      "graph_ref",
                          Map.of(
                              "graph_id", graphId,
                              "revision", 1,
                              "digest", "b".repeat(64)),
                      "required_source_kinds", List.of("metrics"))),
              null);
      requireStatus(201, created, "S07 case registration");
      String caseId = (String) readJson(created.body()).get("id");
      Response sealed =
          request(
              assessmentUrl(),
              "POST",
              "/v1/cases/" + caseId + ":seal",
              token,
              json(Map.of("expected_revision", 1)),
              null);
      requireStatus(200, sealed, "S07 case sealing");
      Map<String, Object> sealedCase = readJson(sealed.body());
      if (!"SEALED".equals(sealedCase.get("state"))
          || !(sealedCase.get("case_digest") instanceof String digest)
          || !digest.matches("[a-f0-9]{64}")) {
        throw new AssertionError("S07 did not return a valid sealed case: " + sealed.body());
      }
      Response persisted =
          request(assessmentUrl(), "GET", "/v1/cases/" + caseId, token, null, null);
      requireStatus(200, persisted, "S07 case read-back");
      Map<String, Object> persistedCase = readJson(persisted.body());
      if (!"SEALED".equals(persistedCase.get("state"))
          || !digest.equals(persistedCase.get("case_digest"))) {
        throw new AssertionError("S07 case read-back differs from the sealed case: " + persisted.body());
      }
      return caseId;
    }

    private String assessmentToken(String principalId, String suffix) {
      DelegationPrincipal principal =
          new DelegationPrincipal(
              principalId,
              tenantId,
              environmentId,
              "*",
              IDP_ISSUER,
              principalId,
              List.of("assessment.case.read", "assessment.case.write"),
              List.of("owner-" + suffix),
              List.of("runtime-" + suffix));
      return "Bearer " + TOKEN_ISSUER.issue(principal, "S07");
    }

    void registerActor(String principalId, String actorSubject) throws SQLException {
      registerActor(principalId, actorSubject, List.of("topology.graph.read"));
    }

    void registerActor(String principalId, String actorSubject, List<String> capabilities)
        throws SQLException {
      try (Connection connection =
              DriverManager.getConnection(
                  identityDb.getJdbcUrl(), identityDb.getUsername(), identityDb.getPassword());
          PreparedStatement tenant =
              connection.prepareStatement(
                  "INSERT INTO uacrf_identity.tenant "
                      + "(id, display_name, status, revision) VALUES (?, ?, 'ACTIVE', 1) "
                      + "ON CONFLICT (id) DO NOTHING");
          PreparedStatement environment =
              connection.prepareStatement(
                  "INSERT INTO uacrf_identity.environment "
                      + "(tenant_id, id, display_name, status, revision) "
                      + "VALUES (?, ?, ?, 'ACTIVE', 1) ON CONFLICT (tenant_id, id) DO NOTHING");
          PreparedStatement principal =
              connection.prepareStatement(
                  "INSERT INTO uacrf_identity.principal "
                      + "(id, issuer_uri, subject, display_name, kind, enabled, "
                      + "token_valid_after, revision) "
                      + "VALUES (?, ?, ?, ?, 'HUMAN', true, now() - interval '1 minute', 1)");
          PreparedStatement membership =
              connection.prepareStatement(
                  "INSERT INTO uacrf_identity.membership "
                      + "(id, tenant_id, environment_id, principal_id, capability, "
                      + "service_scope, expires_at, revision) "
                      + "VALUES (?, ?, ?, ?, ?, '*', "
                      + "'2099-12-31T23:59:59Z', 1)")) {
        tenant.setString(1, tenantId);
        tenant.setString(2, "M2 live test tenant");
        tenant.executeUpdate();
        environment.setString(1, tenantId);
        environment.setString(2, environmentId);
        environment.setString(3, "M2 live test environment");
        environment.executeUpdate();
        principal.setString(1, principalId);
        principal.setString(2, IDP_ISSUER);
        principal.setString(3, actorSubject);
        principal.setString(4, "M2 live test actor");
        principal.executeUpdate();
        for (String capability : capabilities) {
          membership.setObject(1, UUID.randomUUID());
          membership.setString(2, tenantId);
          membership.setString(3, environmentId);
          membership.setString(4, principalId);
          membership.setString(5, capability);
          membership.executeUpdate();
        }
      }
    }

    Response request(String baseUrl, String method, String path, String bearer, String body, String idempotencyKey)
        throws IOException, InterruptedException {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(baseUrl + path))
              .timeout(Duration.ofSeconds(10))
              .header("Accept", "application/json");
      if (bearer != null) {
        request.header("Authorization", bearer);
      }
      if (idempotencyKey != null) {
        request.header("Idempotency-Key", idempotencyKey);
      }
      if ("GET".equals(method)) {
        request.GET();
      } else {
        request.header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
      }
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.body(), response);
    }

    String topologyUrl() {
      return "http://127.0.0.1:" + topologyPort;
    }

    String identityUrl() {
      return "http://127.0.0.1:" + identityPort;
    }

    String tenantId() {
      return tenantId;
    }

    String environmentId() {
      return environmentId;
    }

    String objectivesUrl() {
      return "http://127.0.0.1:" + objectivesPort;
    }

    String catalogUrl() {
      return "http://127.0.0.1:" + catalogPort;
    }

    String assessmentUrl() {
      return "http://127.0.0.1:" + assessmentPort;
    }

    Map<String, Object> awaitCommand(String commandId, String bearer)
        throws IOException, InterruptedException {
      String lastBody = "";
      for (int attempt = 0; attempt < 120; attempt++) {
        Response response =
            request(topologyUrl(), "GET", "/v1/commands/" + commandId, bearer, null, null);
        if (response.statusCode() != 200) {
          throw new AssertionError(
              "S06 command read-back returned " + response.statusCode() + ": " + response.body());
        }
        lastBody = response.body();
        Map<String, Object> status = readJson(lastBody);
        String value = (String) status.get("status");
        if (List.of("SUCCEEDED", "REJECTED", "FAILED", "CANCELLED", "EXPIRED").contains(value)) {
          return status;
        }
        Thread.sleep(250);
      }
      throw new AssertionError("Timed out waiting for S06 command; last response: " + lastBody);
    }

    Map<String, Object> awaitGraphProjection(String graphId, String principalId, String actorSubject)
        throws IOException, InterruptedException {
      String bearer = topologyToken(principalId, actorSubject);
      String path = "/v1/graphs/" + graphId + "/reconciliation";
      String lastBody = "";
      Instant deadline = Instant.now().plusSeconds(20);
      while (Instant.now().isBefore(deadline)) {
        Response response = request(topologyUrl(), "GET", path, bearer, null, null);
        if (response.statusCode() != 200) {
          throw new AssertionError(
              "S06 graph reconciliation returned " + response.statusCode() + ": " + response.body());
        }
        lastBody = response.body();
        Map<String, Object> result = readJson(lastBody);
        String projectionStatus = (String) result.get("projection_status");
        if ("PROJECTED".equals(projectionStatus)) {
          return result;
        }
        if ("FAILED".equals(projectionStatus) || "DRIFTED".equals(projectionStatus)) {
          throw new AssertionError("S06 graph projection ended as " + projectionStatus + ": " + lastBody);
        }
        Thread.sleep(100);
      }
      throw new AssertionError("Timed out waiting for S06 graph projection; last response: " + lastBody);
    }

    String topologyToken(String principalId, String actorSubject) {
      return bearer(
          tenantId, environmentId, principalId, actorSubject, "S06", TOPOLOGY_CAPABILITIES);
    }

    String scopedTopologyToken(String principalId, String actorSubject, String serviceId) {
      DelegationPrincipal principal =
          new DelegationPrincipal(
              principalId, tenantId, environmentId, serviceId, IDP_ISSUER, actorSubject,
              List.of("topology.graph.read"), List.of(), List.of());
      return "Bearer " + TOKEN_ISSUER.issue(principal, "S06");
    }

    String objectiveToken(String principalId, String actorSubject, List<String> capabilities) {
      return bearer(tenantId, environmentId, principalId, actorSubject, "S05", capabilities);
    }

    Fixture createAcceptedGraph(String suffix, String workloadId, String serviceId)
        throws IOException, InterruptedException, SQLException {
      registerCatalogFixture(serviceId, workloadId, suffix);
      String caseId = registerAssessmentFixture(workloadId, "graph-" + suffix, suffix);
      Map<String, Object> serviceSnapshot = serviceSnapshot(serviceId, 2);
      String serviceDigest = serviceDigest(serviceSnapshot);
      String proposerId = principalId("proposer");
      String reviewerId = principalId("reviewer");
      String proposalId = "edge-" + suffix;
      Response proposed =
          request(
              topologyUrl(),
              "POST",
              "/v1/dependency-proposals",
              topologyToken(proposerId, proposerId),
              json(
                  Map.of(
                      "id", proposalId,
                      "source_type", "service",
                      "source_id", serviceId,
                      "relationship_type", "DEPENDS_ON",
                      "target_type", "agent_workload",
                      "target_id", workloadId,
                      "provenance_kind", "AUTHORED")),
              null);
      requireStatus(201, proposed, "S06 proposal");
      Response reviewed =
          request(
              topologyUrl(),
              "POST",
              "/v1/dependency-proposals/" + proposalId + ":review",
              topologyToken(reviewerId, reviewerId),
              json(Map.of("decision", "ACCEPT", "expected_revision", 1)),
              null);
      requireStatus(200, reviewed, "S06 proposal review");
      Response accepted =
          request(
              topologyUrl(),
              "POST",
              "/v1/graphs:accept",
              topologyToken(reviewerId, reviewerId),
              json(Map.of("scope", "all", "expected_revision_number", 0)),
              null);
      requireStatus(200, accepted, "S06 graph acceptance");
      String graphId = (String) readJson(accepted.body()).get("id");
      return new Fixture(serviceId, workloadId, caseId, graphId, serviceDigest);
    }

    Response select(Fixture fixture, String principalId, String actorSubject, String suffix)
        throws IOException, InterruptedException {
      return select(fixture, topologyToken(principalId, actorSubject), suffix);
    }

    Response select(Fixture fixture, String token, String suffix)
        throws IOException, InterruptedException {
      String body =
          json(
              Map.of(
                  "service_id", fixture.serviceId(),
                  "case_id", fixture.caseId(),
                  "graph_revision_id", fixture.graphId(),
                  "reason", "M2 live validation " + suffix,
                  "expected_revision", 0,
                  "query_id", "action-relevant-dependencies.v3",
                  "query_parameters_ref", "catalog://service/" + fixture.serviceId() + "/revision/2",
                  "query_parameters_digest", fixture.serviceDigest(),
                  "purpose", "topology.graph.subgraph-selection",
                  "maximum_nodes", 200));
      return request(
          topologyUrl(),
          "POST",
          "/v1/graphs/subgraphs:select",
          token,
          body,
          "m2-live-selection-" + suffix);
    }

    Response classify(
        Fixture fixture,
        String principalId,
        String actorSubject,
        String selectionDigest,
        String suffix)
        throws IOException, InterruptedException {
      String body =
          json(
              Map.of(
                  "service_id", fixture.serviceId(),
                  "case_id", fixture.caseId(),
                  "reason", "M2 live classification " + suffix,
                  "expected_revision", 0,
                  "authorized_graph_revision_id", fixture.graphId(),
                  "current_graph_revision_id", fixture.graphId(),
                  "selection_digest", selectionDigest));
      return request(
          topologyUrl(),
          "POST",
          "/v1/graphs/differences:classify",
          topologyToken(principalId, actorSubject),
          body,
          "m2-live-classification-" + suffix);
    }

    String createAndActivateObjective(String workloadId, String id, String target)
        throws IOException, InterruptedException {
      String proposer = principalId("objective-proposer");
      String reviewer = principalId("objective-reviewer");
      String activator = principalId("objective-activator");
      return createAndActivateObjective(
          workloadId, id, target, proposer, proposer, reviewer, reviewer, activator, activator);
    }

    String createAndActivateObjective(
        String workloadId, String id, String target, String ownerPrincipalId, String ownerActorSubject)
        throws IOException, InterruptedException {
      String reviewer = principalId("objective-reviewer");
      return createAndActivateObjective(
          workloadId,
          id,
          target,
          ownerPrincipalId,
          ownerActorSubject,
          reviewer,
          reviewer,
          ownerPrincipalId,
          ownerActorSubject);
    }

    private String createAndActivateObjective(
        String workloadId,
        String id,
        String target,
        String proposerId,
        String proposerSubject,
        String reviewerId,
        String reviewerSubject,
        String activatorId,
        String activatorSubject)
        throws IOException, InterruptedException {
      Response proposed =
          request(
              objectivesUrl(),
              "POST",
              "/v1/objectives",
              objectiveToken(proposerId, proposerSubject, OBJECTIVE_PROPOSER),
              json(
                  Map.of(
                      "id", id,
                      "workload_id", workloadId,
                      "title", "M2 live objective " + id,
                      "metric", "latency.p95",
                      "comparator", "LTE",
                      "target", new BigDecimal(target),
                      "unit", "ms",
                      "window_seconds", 300,
                      "origin", "LOCAL")),
              null);
      requireStatus(201, proposed, "S05 objective proposal");
      Response submitted =
          request(
              objectivesUrl(),
              "POST",
              "/v1/objectives/" + id + ":submit",
              objectiveToken(proposerId, proposerSubject, OBJECTIVE_PROPOSER),
              json(Map.of("expected_revision", 1)),
              null);
      requireStatus(200, submitted, "S05 objective submission");
      Response decided =
          request(
              objectivesUrl(),
              "POST",
              "/v1/objectives/" + id + ":decide",
              objectiveToken(reviewerId, reviewerSubject, OBJECTIVE_REVIEWER),
              json(
                  Map.of(
                      "decision", "APPROVE",
                      "reason", "M2 live runtime validation",
                      "expected_revision", 2)),
              null);
      requireStatus(200, decided, "S05 objective decision");
      Response activated =
          request(
              objectivesUrl(),
              "POST",
              "/v1/objectives/" + id + ":activate",
              objectiveToken(activatorId, activatorSubject, OBJECTIVE_ACTIVATOR),
              json(Map.of("expected_revision", 3)),
              null);
      requireStatus(200, activated, "S05 objective activation");
      Response readBack =
          request(
              objectivesUrl(),
              "GET",
              "/v1/objectives/" + id,
              objectiveToken(activatorId, activatorSubject, OBJECTIVE_ACTIVATOR),
              null,
              null);
      requireStatus(200, readBack, "S05 objective read-back");
      if (!readBack.body().contains("\"state\":\"ACTIVE\"")) {
        throw new AssertionError("S05 read-back did not persist an ACTIVE objective: " + readBack.body());
      }
      return id;
    }

    Map<String, Object> objective(String id) throws IOException, InterruptedException {
      String reader = principalId("objective-reader");
      Response readBack =
          request(
              objectivesUrl(),
              "GET",
              "/v1/objectives/" + id,
              objectiveToken(reader, reader, List.of("objectives.objective.read")),
              null,
              null);
      requireStatus(200, readBack, "S05 objective read-back");
      return readJson(readBack.body());
    }

    Response getCommand(String commandId, String principalId, String actorSubject)
        throws IOException, InterruptedException {
      return request(
          topologyUrl(),
          "GET",
          "/v1/commands/" + commandId,
          topologyToken(principalId, actorSubject),
          null,
          null);
    }

    private Map<String, String> identityEnvironment() {
      return Map.ofEntries(
          Map.entry("SERVER_PORT", Integer.toString(identityPort)),
          Map.entry("IDENTITY_JDBC_URL", identityDb.getJdbcUrl()),
          Map.entry("IDENTITY_DB_USER", identityDb.getUsername()),
          Map.entry("IDENTITY_DB_PASSWORD", identityDb.getPassword()),
          Map.entry("KEYCLOAK_ISSUER_URI", IDP_ISSUER),
          Map.entry(
              "KEYCLOAK_JWK_SET_URI",
              "http://127.0.0.1:" + ownerStubs.getAddress().getPort() + "/unused-jwks"),
          Map.entry("IDENTITY_DIRECTORY_ENABLED", "true"),
          Map.entry("IDENTITY_DIRECTORY_ISSUER", IDP_ISSUER),
          Map.entry(
              "IDENTITY_DIRECTORY_TOKEN_URI",
              "http://127.0.0.1:" + ownerStubs.getAddress().getPort() + "/keycloak-admin/token"),
          Map.entry(
              "IDENTITY_DIRECTORY_USERS_URI",
              "http://127.0.0.1:" + ownerStubs.getAddress().getPort() + "/keycloak-admin/users"),
          Map.entry("IDENTITY_DIRECTORY_CLIENT_ID", DIRECTORY_CLIENT_ID),
          Map.entry("IDENTITY_DIRECTORY_CLIENT_SECRET", DIRECTORY_CLIENT_SECRET),
          Map.entry("UACRF_DELEGATION_PUBLIC_KEY_BASE64", publicKeyBase64(DELEGATION_KEYS)),
          Map.entry("UACRF_DELEGATION_PRIVATE_KEY_BASE64", privateKeyBase64(DELEGATION_KEYS)),
          Map.entry("UACRF_DELEGATION_KEY_ID", KEY_ID),
          Map.entry("UACRF_DELEGATION_ISSUER", ISSUER),
          Map.entry("IDENTITY_BOOTSTRAP_ENABLED", "true"),
          Map.entry("IDENTITY_BOOTSTRAP_PROVIDER_ID", "keycloak-local"),
          Map.entry("IDENTITY_BOOTSTRAP_PROVIDER_NAME", "Local Keycloak"),
          Map.entry("IDENTITY_BOOTSTRAP_ISSUER", IDP_ISSUER),
          Map.entry("IDENTITY_BOOTSTRAP_TENANT_ID", TENANT),
          Map.entry("IDENTITY_BOOTSTRAP_TENANT_NAME", "M2 live test tenant"),
          Map.entry("IDENTITY_BOOTSTRAP_ENVIRONMENT_ID", ENVIRONMENT),
          Map.entry("IDENTITY_BOOTSTRAP_ENVIRONMENT_NAME", "M2 live test environment"),
          Map.entry("IDENTITY_BOOTSTRAP_PRINCIPAL_ID", "bootstrap-admin"),
          Map.entry("IDENTITY_BOOTSTRAP_SUBJECT", "bootstrap-admin-subject"),
          Map.entry("IDENTITY_BOOTSTRAP_DISPLAY_NAME", "M2 live bootstrap"),
          Map.entry("IDENTITY_BOOTSTRAP_ADMIN_EXPIRES_AT", "2099-12-31T23:59:59Z"),
          Map.entry("IDENTITY_BOOTSTRAP_REASON", "Initialize disposable M2 live test identity"),
          Map.entry("IDENTITY_BOOTSTRAP_APPROVER_PRINCIPAL_ID", "bootstrap-approver"),
          Map.entry("IDENTITY_BOOTSTRAP_APPROVER_SUBJECT", "bootstrap-approver-subject"),
          Map.entry("IDENTITY_BOOTSTRAP_APPROVER_DISPLAY_NAME", "M2 live test approver"));
    }

    private Map<String, String> objectivesEnvironment() {
      Map<String, String> environment = new HashMap<>();
      environment.put("SERVER_PORT", Integer.toString(objectivesPort));
      environment.put("OBJECTIVES_JDBC_URL", objectivesDb.getJdbcUrl());
      environment.put("OBJECTIVES_DB_USER", objectivesDb.getUsername());
      environment.put("OBJECTIVES_DB_PASSWORD", objectivesDb.getPassword());
      environment.put("UACRF_DELEGATION_PUBLIC_KEY_BASE64", publicKeyBase64(DELEGATION_KEYS));
      environment.put("UACRF_DELEGATION_KEY_ID", KEY_ID);
      environment.put("UACRF_DELEGATION_ISSUER", ISSUER);
      environment.put("UACRF_OBJECTIVES_REVIEWER_CHECK_ENABLED", "false");
      if (enableWorkloadOwnerChecks) {
        environment.put("UACRF_OBJECTIVES_WORKLOAD_CHECK_ENABLED", "true");
        environment.put(
            "UACRF_OBJECTIVES_WORKLOAD_CHECK_IDENTITY_BASE_URL",
            "http://127.0.0.1:" + identityPort);
        environment.put(
            "UACRF_OBJECTIVES_WORKLOAD_CHECK_CATALOG_BASE_URL",
            "http://127.0.0.1:" + catalogPort);
        environment.put(
            "UACRF_OBJECTIVES_WORKLOAD_CHECK_ALLOW_LOOPBACK_HTTP", "true");
        environment.put("UACRF_S02_OWNER_RECEIPT_KEY_ID", S02_KEY_ID);
        environment.put(
            "UACRF_S02_OWNER_RECEIPT_PUBLIC_KEY_BASE64", publicKeyBase64(S02_KEYS));
      }
      return Map.copyOf(environment);
    }

    private Map<String, String> assessmentEnvironment() {
      return Map.ofEntries(
          Map.entry("SERVER_PORT", Integer.toString(assessmentPort)),
          Map.entry("ASSESSMENT_JDBC_URL", assessmentDb.getJdbcUrl()),
          Map.entry("ASSESSMENT_DB_USER", assessmentDb.getUsername()),
          Map.entry("ASSESSMENT_DB_PASSWORD", assessmentDb.getPassword()),
          Map.entry("UACRF_DELEGATION_PUBLIC_KEY_BASE64", publicKeyBase64(DELEGATION_KEYS)),
          Map.entry("UACRF_DELEGATION_KEY_ID", KEY_ID),
          Map.entry("UACRF_DELEGATION_ISSUER", ISSUER),
          Map.entry("UACRF_ASSESSMENT_RECEIPTS_ENABLED", "false"),
          Map.entry("UACRF_ASSESSMENT_MODELS_ENABLED", "false"),
          Map.entry("UACRF_ASSESSMENT_LEARNING_REVIEW_ENABLED", "false"),
          Map.entry("UACRF_ASSESSMENT_ADAPTATION_ENABLED", "false"));
    }

    private Map<String, String> catalogEnvironment() {
      return Map.ofEntries(
          Map.entry("SERVER_PORT", Integer.toString(catalogPort)),
          Map.entry("CATALOG_JDBC_URL", catalogDb.getJdbcUrl()),
          Map.entry("CATALOG_DB_USER", catalogDb.getUsername()),
          Map.entry("CATALOG_DB_PASSWORD", catalogDb.getPassword()),
          Map.entry("UACRF_DELEGATION_PUBLIC_KEY_BASE64", publicKeyBase64(DELEGATION_KEYS)),
          Map.entry("UACRF_DELEGATION_KEY_ID", KEY_ID),
          Map.entry("UACRF_DELEGATION_ISSUER", ISSUER),
          Map.entry("UACRF_S02_OWNER_RECEIPT_PUBLIC_KEY_BASE64", publicKeyBase64(S02_KEYS)),
          Map.entry("UACRF_S02_OWNER_RECEIPT_PRIVATE_KEY_BASE64", privateKeyBase64(S02_KEYS)),
          Map.entry("UACRF_S02_OWNER_RECEIPT_KEY_ID", S02_KEY_ID));
    }

    private Map<String, String> topologyEnvironment() {
      String stubs = "http://127.0.0.1:" + ownerStubs.getAddress().getPort();
      return Map.ofEntries(
          Map.entry("SERVER_PORT", Integer.toString(topologyPort)),
          Map.entry("TOPOLOGY_JDBC_URL", topologyDb.getJdbcUrl()),
          Map.entry("TOPOLOGY_DB_USER", topologyDb.getUsername()),
          Map.entry("TOPOLOGY_DB_PASSWORD", topologyDb.getPassword()),
          Map.entry("TOPOLOGY_NEO4J_URI", neo4j.getBoltUrl()),
          Map.entry("TOPOLOGY_NEO4J_USER", "neo4j"),
          Map.entry("TOPOLOGY_NEO4J_PASSWORD", neo4jPassword),
          Map.entry("UACRF_DELEGATION_PUBLIC_KEY_BASE64", publicKeyBase64(DELEGATION_KEYS)),
          Map.entry("UACRF_DELEGATION_KEY_ID", KEY_ID),
          Map.entry("UACRF_DELEGATION_ISSUER", ISSUER),
          Map.entry("UACRF_TOPOLOGY_IDENTITY_URL", "http://127.0.0.1:" + identityPort),
          Map.entry("UACRF_TOPOLOGY_OBJECTIVES_URL", "http://127.0.0.1:" + objectivesPort),
          Map.entry("UACRF_TOPOLOGY_CATALOG_URL", catalogUrl()),
          Map.entry("UACRF_TOPOLOGY_EVIDENCE_URL", stubs),
          Map.entry("UACRF_TOPOLOGY_ASSESSMENT_URL", assessmentUrl()),
          Map.entry("UACRF_TOPOLOGY_S02_KEY_ID", S02_KEY_ID),
          Map.entry("UACRF_TOPOLOGY_S02_PUBLIC_KEY_BASE64", publicKeyBase64(S02_KEYS)),
          Map.entry("UACRF_TOPOLOGY_COMMAND_INTERVAL_MS", "100"),
          Map.entry("UACRF_TOPOLOGY_PROJECTION_ENABLED", "true"),
          Map.entry("UACRF_TOPOLOGY_PROJECTION_INTERVAL_MS", "100"),
          Map.entry("UACRF_TOPOLOGY_PROJECTION_RETRY_BASE_MS", "100"));
    }

    private void startOwnerStubs() throws IOException {
      ownerStubs =
          HttpServer.create(
              new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
      ownerStubs.createContext("/unused-jwks", exchange -> send(exchange, 200, identityJwkSet()));
      ownerStubs.createContext("/keycloak-admin/token", this::directoryTokenEndpoint);
      ownerStubs.createContext("/keycloak-admin/users/", this::directoryUserEndpoint);
      ownerStubs.createContext("/internal/v1/qualifications/", this::evidenceEndpoint);
      ownerStubs.start();
    }

    private void directoryTokenEndpoint(HttpExchange exchange) throws IOException {
      String requestBody =
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      if (!"POST".equals(exchange.getRequestMethod())
          || !requestBody.contains("grant_type=client_credentials")
          || !requestBody.contains("client_id=" + DIRECTORY_CLIENT_ID)
          || !requestBody.contains("client_secret=" + DIRECTORY_CLIENT_SECRET)) {
        stubFailures.add("Keycloak directory token request did not use the test service account.");
        send(exchange, 400, Map.of("error", "invalid directory token request"));
        return;
      }
      send(
          exchange,
          200,
          Map.of(
              "access_token", DIRECTORY_ACCESS_TOKEN,
              "token_type", "Bearer",
              "expires_in", 300));
    }

    private void directoryUserEndpoint(HttpExchange exchange) throws IOException {
      String path = exchange.getRequestURI().getPath();
      String subject = path.substring("/keycloak-admin/users/".length());
      if (!"GET".equals(exchange.getRequestMethod())
          || !("Bearer " + DIRECTORY_ACCESS_TOKEN)
              .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
        stubFailures.add("Keycloak directory user lookup did not use the test service account.");
        send(exchange, 401, Map.of("error", "unauthorized directory lookup"));
        return;
      }
      Map<String, Object> user = directoryUsers.get(subject);
      if (user == null) {
        send(exchange, 404, Map.of("error", "directory subject not found"));
        return;
      }
      send(exchange, 200, user);
    }

    private void evidenceEndpoint(HttpExchange exchange) throws IOException {
      try {
        if (!"POST".equals(exchange.getRequestMethod())) {
          throw new IllegalArgumentException("S04 summary must use POST.");
        }
        evidenceSummaryCalls.incrementAndGet();
        throw new IllegalStateException("Unexpected S04 evidence request in authored-edge M2 fixture.");
      } catch (RuntimeException error) {
        stubFailures.add("S04: " + error.getMessage());
        send(exchange, 500, Map.of("error", "S04 test stub rejected request"));
      }
    }

    private void seedTopologyMembership() throws SQLException {
      try (Connection connection =
              DriverManager.getConnection(
                  identityDb.getJdbcUrl(), identityDb.getUsername(), identityDb.getPassword());
          PreparedStatement membership =
              connection.prepareStatement(
                  "INSERT INTO uacrf_identity.membership "
                      + "(id, tenant_id, environment_id, principal_id, capability, "
                      + "service_scope, expires_at, revision) "
                      + "VALUES (?, ?, ?, 'bootstrap-admin', 'topology.graph.read', '*', "
                      + "'2099-12-31T23:59:59Z', 1)")) {
        membership.setObject(1, UUID.randomUUID());
        membership.setString(2, TENANT);
        membership.setString(3, ENVIRONMENT);
        membership.executeUpdate();
      }
    }

    private Process startService(
        String relativeJar, String name, int port, Map<String, String> environment)
        throws IOException {
      Path jar = repoRoot.resolve(relativeJar);
      if (!Files.isRegularFile(jar)) {
        throw new IllegalStateException(
            "Missing packaged " + name + " service jar: " + jar + "; run the root Maven verify command.");
      }
      ProcessBuilder builder =
          new ProcessBuilder("java", "-jar", jar.toString())
              .directory(repoRoot.toFile())
              .redirectErrorStream(true)
              .redirectOutput(logDirectory.resolve(name + ".log").toFile());
      builder.environment().putAll(environment);
      builder.environment().put("SERVER_PORT", Integer.toString(port));
      return builder.start();
    }

    private void awaitHealth(String service, int port, Process process)
        throws IOException, InterruptedException {
      Instant deadline = Instant.now().plusSeconds(120);
      URI health = URI.create("http://127.0.0.1:" + port + "/actuator/health");
      while (Instant.now().isBefore(deadline)) {
        if (!process.isAlive()) {
          throw new IllegalStateException(
              service + " exited before readiness. Recent logs:\n" + logTail(service));
        }
        try {
          HttpResponse<String> response =
              http.send(
                  HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(2)).GET().build(),
                  HttpResponse.BodyHandlers.ofString());
          if (response.statusCode() == 200) {
            return;
          }
        } catch (IOException ignored) {
          Thread.sleep(250);
          continue;
        }
        Thread.sleep(250);
      }
      throw new IllegalStateException(
          service + " did not become ready at " + health + ". Recent logs:\n" + logTail(service));
    }

    private String logTail(String service) throws IOException {
      Path log = logDirectory.resolve(service + ".log");
      if (!Files.exists(log)) {
        return "(no log file)";
      }
      List<String> lines = Files.readAllLines(log);
      int start = Math.max(0, lines.size() - 200);
      return String.join(System.lineSeparator(), lines.subList(start, lines.size()));
    }

    private void requireStatus(int expected, Response response, String action) {
      if (response.statusCode() != expected) {
        throw new AssertionError(
            action + " expected HTTP " + expected + " but got " + response.statusCode()
                + ": " + response.body());
      }
    }

    private Map<String, Object> serviceSnapshot(String serviceId, long resourceVersion)
        throws SQLException {
      try (Connection connection =
              DriverManager.getConnection(
                  catalogDb.getJdbcUrl(), catalogDb.getUsername(), catalogDb.getPassword());
          PreparedStatement snapshot =
              connection.prepareStatement(
                  "SELECT snapshot::text FROM uacrf_catalog.service_revision "
                      + "WHERE tenant_id = ? AND environment_id = ? AND service_id = ? "
                      + "AND resource_version = ?")) {
        snapshot.setString(1, tenantId);
        snapshot.setString(2, environmentId);
        snapshot.setString(3, serviceId);
        snapshot.setLong(4, resourceVersion);
        try (var rows = snapshot.executeQuery()) {
          if (!rows.next()) {
            throw new SQLException(
                "S02 did not retain service revision " + resourceVersion + " for " + serviceId);
          }
          return readJson(rows.getString(1));
        }
      }
    }

    private static String publicKeyBase64(KeyPair pair) {
      return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    private static String privateKeyBase64(KeyPair pair) {
      return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    }

    private static String sha256(String value) {
      try {
        return HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
      } catch (java.security.NoSuchAlgorithmException error) {
        throw new IllegalStateException(error);
      }
    }

    private static int freePort() throws IOException {
      try (ServerSocket socket =
          new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
        return socket.getLocalPort();
      }
    }

    private static void send(HttpExchange exchange, int status, Map<String, ?> body)
        throws IOException {
      byte[] bytes = JSON.writeValueAsBytes(body);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    }

    private void stopProcess(Process process) {
      if (process == null || !process.isAlive()) {
        return;
      }
      process.destroy();
      try {
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
      }
    }

    @Override
    public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      stopProcess(topologyProcess);
      stopProcess(objectivesProcess);
      stopProcess(assessmentProcess);
      stopProcess(catalogProcess);
      stopProcess(identityProcess);
      if (ownerStubs != null) {
        ownerStubs.stop(0);
      }
      if (neo4j.isRunning()) {
        neo4j.stop();
      }
      if (topologyDb.isRunning()) {
        topologyDb.stop();
      }
      if (objectivesDb.isRunning()) {
        objectivesDb.stop();
      }
      if (assessmentDb.isRunning()) {
        assessmentDb.stop();
      }
      if (catalogDb.isRunning()) {
        catalogDb.stop();
      }
      if (identityDb.isRunning()) {
        identityDb.stop();
      }
      try (var paths = Files.walk(logDirectory)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
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
}
