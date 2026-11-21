package org.uacrf.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.uacrf.advisory.RequesterIdentityProbe;
import org.uacrf.advisory.SignedRequesterOwners;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(IdentityApplicationTests.TestJwtConfiguration.class)
class IdentityApplicationTests {
  private static final String ISSUER = "https://keycloak.example.test/realms/uacrf";
  private static final String OIDC_TEST_SECRET =
      "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
  private static final String DELEGATION_KEY_ID = "identity-test-key";
  private static final KeyPair DELEGATION_KEY_PAIR = generateDelegationKeyPair();
  private static final String DELEGATION_PUBLIC_KEY_BASE64 =
      Base64.getEncoder().encodeToString(DELEGATION_KEY_PAIR.getPublic().getEncoded());
  private static final String DELEGATION_PRIVATE_KEY_BASE64 =
      Base64.getEncoder().encodeToString(DELEGATION_KEY_PAIR.getPrivate().getEncoded());
  private static final String POSTGRES_IMAGE =
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(POSTGRES_IMAGE)
          .withDatabaseName("uacrf_identity")
          .withUsername("identity_test")
          .withPassword("identity_test");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry properties) {
    // The test-only advisory dependency also contains Flyway migrations.
    properties.add("spring.flyway.locations", () -> "filesystem:"
        + java.nio.file.Path.of(URI.create(
            IdentityApplication.class.getProtectionDomain().getCodeSource().getLocation().toString()))
            .resolve("db/migration"));
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add(
        "uacrf.delegation.public-key-base64", () -> DELEGATION_PUBLIC_KEY_BASE64);
    properties.add(
        "uacrf.delegation.private-key-base64", () -> DELEGATION_PRIVATE_KEY_BASE64);
    properties.add("uacrf.delegation.key-id", () -> DELEGATION_KEY_ID);
    properties.add("uacrf.identity.oidc.issuer-uri", () -> ISSUER);
    properties.add(
        "uacrf.identity.oidc.jwk-set-uri",
        () -> "https://keycloak.example.test/realms/uacrf/protocol/openid-connect/certs");
    properties.add("uacrf.identity.oidc.audience", () -> "uacrf-s01");
    properties.add("uacrf.identity.bootstrap.enabled", () -> true);
    properties.add("uacrf.identity.bootstrap.provider-id", () -> "keycloak-local");
    properties.add("uacrf.identity.bootstrap.provider-name", () -> "Local Keycloak");
    properties.add("uacrf.identity.bootstrap.issuer", () -> "https://keycloak.example.test/realms/uacrf");
    properties.add("uacrf.identity.bootstrap.tenant-id", () -> "tenant-test");
    properties.add("uacrf.identity.bootstrap.tenant-name", () -> "Test tenant");
    properties.add("uacrf.identity.bootstrap.environment-id", () -> "environment-test");
    properties.add("uacrf.identity.bootstrap.environment-name", () -> "Test environment");
    properties.add("uacrf.identity.bootstrap.principal-id", () -> "admin-test");
    properties.add("uacrf.identity.bootstrap.subject", () -> "keycloak-subject-1");
    properties.add("uacrf.identity.bootstrap.display-name", () -> "Initial administrator");
    properties.add(
        "uacrf.identity.bootstrap.admin-expires-at", () -> "2099-12-31T23:59:59Z");
    properties.add("uacrf.identity.bootstrap.reason", () -> "Initialize test identity authority");
    properties.add("uacrf.identity.bootstrap.approver-principal-id", () -> "bootstrap-approver");
    properties.add("uacrf.identity.bootstrap.approver-subject", () -> "keycloak-subject-approver");
    properties.add("uacrf.identity.bootstrap.approver-display-name", () -> "Initial approver");
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private IdentityBootstrap bootstrap;
  @Autowired private StubDirectory directory;
  @Autowired
  @Qualifier("testJwtEncoder")
  private JwtEncoder jwtEncoder;
  @LocalServerPort private int port;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void migratesSchemaAndCreatesOneAuditedExpiringBootstrapAdministrator() {
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_identity.identity_provider WHERE status = 'TRUSTED'",
            Integer.class));
    assertThat(jdbc.queryForObject(
            "SELECT issuer_uri || '|' || subject FROM uacrf_identity.principal "
                + "WHERE id = 'admin-test'",
            String.class))
        .isEqualTo("https://keycloak.example.test/realms/uacrf|keycloak-subject-1");
    assertEquals(
        7,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_identity.membership "
                + "WHERE principal_id = 'admin-test' AND revoked_at IS NULL "
                + "AND capability IN ('identity.directory.read', 'identity.principal.manage', "
                + "'identity.membership.read', 'identity.membership.manage', "
                + "'identity.owner.select', 'ownership.transfer', 'identity.provider.manage')",
            Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_identity.membership "
                + "WHERE principal_id = 'admin-test' AND capability = 'owner.eligible'",
            Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_identity.bootstrap_audit "
                + "WHERE singleton = true AND admin_expires_at = '2099-12-31T23:59:59Z' "
                + "AND bootstrap_reason = 'Initialize test identity authority' "
                + "AND granted_capabilities LIKE '%identity.membership.manage%'",
            Integer.class));

    bootstrap.run(new DefaultApplicationArguments(new String[0]));

    assertEquals(
        7,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_identity.membership "
                + "WHERE principal_id = 'admin-test' AND capability IN "
                + "('identity.directory.read', 'identity.principal.manage', "
                + "'identity.membership.read', 'identity.membership.manage', "
                + "'identity.owner.select', 'ownership.transfer', 'identity.provider.manage')",
            Integer.class));

    assertEquals(
        7,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_identity.membership "
                + "WHERE principal_id = 'admin-test' AND capability IN "
                + "('identity.directory.read', 'identity.principal.manage', "
                + "'identity.membership.read', 'identity.membership.manage', "
                + "'identity.owner.select', 'ownership.transfer', 'identity.provider.manage')",
            Integer.class));
  }

  @Test
  void membershipGenerationMigrationBackfillsExistingRows() throws Exception {
    String database = "uacrf_identity_generation_migration";
    jdbc.execute("CREATE DATABASE " + database);
    String url = POSTGRES.getJdbcUrl().replace("/uacrf_identity", "/" + database);
    String migrations = "filesystem:"
        + java.nio.file.Path.of(URI.create(
            IdentityApplication.class.getProtectionDomain().getCodeSource().getLocation().toString()))
            .resolve("db/migration");
    try {
      org.flywaydb.core.Flyway.configure()
          .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
          .locations(migrations).target("6").load().migrate();
      try (var connection = java.sql.DriverManager.getConnection(
          url, POSTGRES.getUsername(), POSTGRES.getPassword());
          var statement = connection.createStatement()) {
        statement.executeUpdate("INSERT INTO uacrf_identity.tenant "
            + "(id, display_name, status, revision) VALUES ('tenant', 'Tenant', 'ACTIVE', 1)");
        statement.executeUpdate("INSERT INTO uacrf_identity.environment "
            + "(tenant_id, id, display_name, status, revision) "
            + "VALUES ('tenant', 'env', 'Environment', 'ACTIVE', 1)");
        statement.executeUpdate("INSERT INTO uacrf_identity.identity_provider "
            + "(id, issuer_uri, display_name, status, revision) "
            + "VALUES ('provider', 'https://example.test', 'Provider', 'TRUSTED', 1)");
        statement.executeUpdate("INSERT INTO uacrf_identity.principal "
            + "(id, issuer_uri, subject, display_name, kind, enabled, token_valid_after, revision) "
            + "VALUES ('principal', 'https://example.test', 'subject', 'Principal', 'HUMAN', true, now(), 1)");
        statement.executeUpdate("INSERT INTO uacrf_identity.membership "
            + "(id, tenant_id, environment_id, principal_id, capability, service_scope, expires_at, revision) "
            + "VALUES ('00000000-0000-0000-0000-000000000001', 'tenant', 'env', 'principal', "
            + "'test.alpha', '*', '2099-01-01', 1), "
            + "('00000000-0000-0000-0000-000000000002', 'tenant', 'env', 'principal', "
            + "'test.beta', 'svc-1', '2099-01-01', 1)");
      }
      org.flywaydb.core.Flyway.configure()
          .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
          .locations(migrations).load().migrate();
      try (var connection = java.sql.DriverManager.getConnection(
          url, POSTGRES.getUsername(), POSTGRES.getPassword());
          var statement = connection.createStatement()) {
        try (var rows = statement.executeQuery(
            "SELECT service_scope, generation FROM uacrf_identity.membership_generation ORDER BY service_scope")) {
          assertThat(rows.next()).isTrue();
          assertEquals("*", rows.getString(1));
          assertEquals(1, rows.getLong(2));
          assertThat(rows.next()).isTrue();
          assertEquals("svc-1", rows.getString(1));
          assertEquals(1, rows.getLong(2));
          assertThat(rows.next()).isFalse();
        }
        statement.executeUpdate("DELETE FROM uacrf_identity.membership WHERE service_scope = '*'");
        try (var rows = statement.executeQuery(
            "SELECT generation FROM uacrf_identity.membership_generation WHERE service_scope = '*'")) {
          assertThat(rows.next()).isTrue();
          assertEquals(2, rows.getLong(1));
        }
      }
    } finally {
      jdbc.execute("DROP DATABASE " + database);
    }
  }

  @Test
  void allowsLoopbackHttpBootstrapIssuerButRejectsRemoteHttpIssuer() {
    IdentityBootstrap.validateIssuer(URI.create("http://localhost:8180/realms/uacrf"));
    org.assertj.core.api.Assertions.assertThatIllegalStateException()
        .isThrownBy(
            () -> IdentityBootstrap.validateIssuer(URI.create("http://keycloak:8080/realms/uacrf")));
  }

  @Test
  void currentGlobalAccessReflectsSourceGrantRevocation() throws Exception {
    UUID grant = UUID.randomUUID();
    try {
      jdbc.update(
          "INSERT INTO uacrf_identity.membership "
              + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
              + "expires_at, revision) VALUES (?, 'tenant-test', 'environment-test', "
              + "'admin-test', 'assessment.case.read', '*', '2099-12-31T23:59:59Z', 1)",
          grant);
      String path = "/v1/me/access?tenant_id=tenant-test&environment_id=environment-test";
      String actor = tokenWithoutContext("keycloak-subject-1");
      HttpResponse<String> access = get(path, actor);
      assertEquals(200, access.statusCode());
      assertThat(access.body()).contains(
          "\"principal_id\":\"admin-test\"", "\"kind\":\"HUMAN\"", "\"service_id\":null",
          "\"scope\":\"GLOBAL_GRANTS_ONLY\"", "\"assessment.case.read\"");

      jdbc.update("UPDATE uacrf_identity.membership SET revoked_at = now() WHERE id = ?", grant);
      HttpResponse<String> revoked = get(path, actor);
      assertEquals(200, revoked.statusCode());
      assertThat(revoked.body()).doesNotContain("\"assessment.case.read\"");
    } finally {
      jdbc.update("DELETE FROM uacrf_identity.membership WHERE id = ?", grant);
    }
  }

  @Test
  void currentAccessReportsEarliestEffectiveCapabilityExpiryForItsScope() throws Exception {
    String principal = "expiry-horizon-test";
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    UUID laterGlobal = UUID.randomUUID();
    UUID scopedBeta = UUID.randomUUID();
    try {
      seedGrantParticipant(principal);
      for (Object[] grant :
          List.of(
              new Object[] {UUID.randomUUID(), "test.alpha", "*", now.plusSeconds(3600), null},
              new Object[] {laterGlobal, "test.alpha", "*", now.plusSeconds(7200), null},
              new Object[] {UUID.randomUUID(), "test.alpha", "svc-1", now.plusSeconds(10800), null},
              new Object[] {scopedBeta, "test.beta", "svc-1", now.plusSeconds(1800), null},
              new Object[] {UUID.randomUUID(), "test.other", "svc-2", now.plusSeconds(600), null},
              new Object[] {UUID.randomUUID(), "test.revoked", "*", now.plusSeconds(300), now},
              new Object[] {UUID.randomUUID(), "test.expired", "*", now.minusSeconds(300), null})) {
        jdbc.update(
            "INSERT INTO uacrf_identity.membership "
                + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
                + "expires_at, revoked_at, revision) VALUES (?, 'tenant-test', 'environment-test', "
                + "?, ?, ?, ?, ?, 1)",
            grant[0], principal, grant[1], grant[2], java.sql.Timestamp.from((Instant) grant[3]),
            grant[4] == null ? null : java.sql.Timestamp.from((Instant) grant[4]));
      }
      Instant sessionExpiry = now.plusSeconds(60);
      String actor = token(principal + "-subject", true, sessionExpiry);
      String path = "/v1/me/access?tenant_id=tenant-test&environment_id=environment-test";
      HttpResponse<String> global = get(path, actor);
      assertEquals(200, global.statusCode());
      assertThat(global.body())
          .contains("\"capability_expiry_horizon\":\"" + now.plusSeconds(7200) + "\"")
          .contains("\"session_expires_at\":\"" + sessionExpiry + "\"")
          .doesNotContain("test.beta", "test.other", "test.revoked", "test.expired");
      var json = tools.jackson.databind.json.JsonMapper.builder().build();
      String digest = json.readTree(global.body()).path("membership_snapshot_digest").asString();
      long generation = Long.parseLong(json.readTree(global.body()).path("membership_generation").asString());
      assertThat(digest).matches("[a-f0-9]{64}");
      assertEquals(digest, json.readTree(get(path, actor).body())
          .path("membership_snapshot_digest").asString());
      UUID redundant = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO uacrf_identity.membership "
              + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
              + "expires_at, revision) VALUES (?, 'tenant-test', 'environment-test', ?, "
              + "'test.alpha', '*', ?, 1)",
          redundant, principal, java.sql.Timestamp.from(now.plusSeconds(1800)));
      HttpResponse<String> withRedundantGrant = get(path, actor);
      assertThat(withRedundantGrant.body())
          .contains("\"capability_expiry_horizon\":\"" + now.plusSeconds(7200) + "\"");
      assertEquals(json.readTree(global.body()).path("capability_ids"),
          json.readTree(withRedundantGrant.body()).path("capability_ids"));
      assertThat(json.readTree(withRedundantGrant.body()).path("membership_snapshot_digest").asString())
          .isNotEqualTo(digest);
      long withRedundantGeneration =
          Long.parseLong(json.readTree(withRedundantGrant.body()).path("membership_generation").asString());
      assertThat(withRedundantGeneration).isGreaterThan(generation);
      jdbc.update("UPDATE uacrf_identity.membership SET revoked_at = now() WHERE id = ?", redundant);
      var restored = json.readTree(get(path, actor).body());
      assertEquals(digest, restored.path("membership_snapshot_digest").asString());
      assertThat(Long.parseLong(restored.path("membership_generation").asString()))
          .isGreaterThan(withRedundantGeneration);
      HttpResponse<String> scoped = get(path + "&service_id=svc-1", actor);
      assertEquals(200, scoped.statusCode());
      assertThat(scoped.body())
          .contains("\"capability_expiry_horizon\":\"" + now.plusSeconds(1800) + "\"")
          .contains("test.alpha", "test.beta")
          .doesNotContain("test.other", "test.revoked", "test.expired");
      String scopedDigest = json.readTree(scoped.body()).path("membership_snapshot_digest").asString();
      String scopedGeneration = json.readTree(scoped.body()).path("membership_generation").asString();
      String globalGeneration = restored.path("membership_generation").asString();
      assertThat(scopedDigest).isNotEqualTo(digest);
      jdbc.update(
          "INSERT INTO uacrf_identity.membership "
              + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
              + "expires_at, revision) VALUES (?, 'tenant-test', 'environment-test', ?, "
              + "'test.other', 'svc-2', ?, 1)",
          UUID.randomUUID(), principal, java.sql.Timestamp.from(now.plusSeconds(500)));
      assertEquals(digest, json.readTree(get(path, actor).body())
          .path("membership_snapshot_digest").asString());
      assertEquals(scopedDigest, json.readTree(get(path + "&service_id=svc-1", actor).body())
          .path("membership_snapshot_digest").asString());
      assertEquals(globalGeneration, json.readTree(get(path, actor).body())
          .path("membership_generation").asString());
      assertEquals(scopedGeneration, json.readTree(get(path + "&service_id=svc-1", actor).body())
          .path("membership_generation").asString());

      try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement(
            "INSERT INTO uacrf_identity.membership "
                + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
                + "expires_at, revision) VALUES (?, 'tenant-test', 'environment-test', ?, "
                + "'test.alpha', '*', ?, 1)")) {
          statement.setObject(1, UUID.randomUUID());
          statement.setString(2, principal);
          statement.setTimestamp(3, java.sql.Timestamp.from(now.plusSeconds(1800)));
          statement.executeUpdate();
        }
        assertEquals(globalGeneration, json.readTree(get(path, actor).body())
            .path("membership_generation").asString());
        connection.rollback();
      }
      assertEquals(globalGeneration, json.readTree(get(path, actor).body())
          .path("membership_generation").asString());

      String otherGeneration = json.readTree(get(path + "&service_id=svc-2", actor).body())
          .path("membership_generation").asString();
      jdbc.update("UPDATE uacrf_identity.membership SET service_scope = 'svc-2' WHERE id = ?", scopedBeta);
      assertThat(json.readTree(get(path + "&service_id=svc-1", actor).body())
          .path("membership_generation").asString()).isNotEqualTo(scopedGeneration);
      assertThat(json.readTree(get(path + "&service_id=svc-2", actor).body())
          .path("membership_generation").asString()).isNotEqualTo(otherGeneration);
      assertEquals(globalGeneration, json.readTree(get(path, actor).body())
          .path("membership_generation").asString());
      jdbc.update("UPDATE uacrf_identity.membership SET service_scope = 'svc-1' WHERE id = ?", scopedBeta);

      var firstInsert = CompletableFuture.runAsync(() -> jdbc.update(
          "INSERT INTO uacrf_identity.membership "
              + "(id, tenant_id, environment_id, principal_id, capability, service_scope, expires_at, revision) "
              + "VALUES (?, 'tenant-test', 'environment-test', ?, 'test.alpha', '*', ?, 1)",
          UUID.randomUUID(), principal, java.sql.Timestamp.from(now.plusSeconds(1800))));
      var secondInsert = CompletableFuture.runAsync(() -> jdbc.update(
          "INSERT INTO uacrf_identity.membership "
              + "(id, tenant_id, environment_id, principal_id, capability, service_scope, expires_at, revision) "
              + "VALUES (?, 'tenant-test', 'environment-test', ?, 'test.alpha', '*', ?, 1)",
          UUID.randomUUID(), principal, java.sql.Timestamp.from(now.plusSeconds(1800))));
      CompletableFuture.allOf(firstInsert, secondInsert).join();
      assertEquals(Long.parseLong(globalGeneration) + 2,
          Long.parseLong(json.readTree(get(path, actor).body()).path("membership_generation").asString()));

      Instant naturalExpiry = Instant.now().plusSeconds(5);
      jdbc.update(
          "INSERT INTO uacrf_identity.membership "
              + "(id, tenant_id, environment_id, principal_id, capability, service_scope, expires_at, revision) "
              + "VALUES (?, 'tenant-test', 'environment-test', ?, 'test.transient', '*', ?, 1)",
          UUID.randomUUID(), principal, java.sql.Timestamp.from(naturalExpiry));
      var beforeExpiry = json.readTree(get(path, actor).body());
      assertThat(beforeExpiry.path("capability_ids").toString()).contains("test.transient");
      Thread.sleep(Math.max(0, java.time.Duration.between(Instant.now(), naturalExpiry).toMillis()) + 100);
      var afterExpiry = json.readTree(get(path, actor).body());
      assertThat(afterExpiry.path("capability_ids").toString()).doesNotContain("test.transient");
      assertEquals(beforeExpiry.path("membership_generation"), afterExpiry.path("membership_generation"));
      assertThat(afterExpiry.path("membership_snapshot_digest"))
          .isNotEqualTo(beforeExpiry.path("membership_snapshot_digest"));

      jdbc.update("UPDATE uacrf_identity.membership SET revoked_at = now() WHERE id = ?", laterGlobal);
      assertThat(get(path, actor).body())
          .contains("\"capability_expiry_horizon\":\"" + now.plusSeconds(3600) + "\"");
      jdbc.update("UPDATE uacrf_identity.membership SET revoked_at = now() WHERE id = ?", scopedBeta);
      assertThat(get(path + "&service_id=svc-1", actor).body())
          .contains("\"capability_expiry_horizon\":\"" + now.plusSeconds(10800) + "\"");
      jdbc.update(
          "UPDATE uacrf_identity.membership SET revoked_at = now() "
              + "WHERE principal_id = ? AND service_scope = '*' AND revoked_at IS NULL",
          principal);
      assertThat(get(path, actor).body())
          .contains("\"capability_ids\":[]", "\"capability_expiry_horizon\":null");
    } finally {
      jdbc.update("DELETE FROM uacrf_identity.membership WHERE principal_id = ?", principal);
      jdbc.update("DELETE FROM uacrf_identity.principal WHERE id = ?", principal);
    }
  }

  @Test
  void advisoryRunGrantRequiresIndependentApprovalHumanAndGlobalScope() throws Exception {
    String context = "?tenant_id=tenant-test&environment_id=environment-test";
    String expiresAt = Instant.now().plusSeconds(30L * 86400)
        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    String membershipId = "00000000-0000-4000-8000-000000000221";
    String decisionId = "advisory-test-decision";
    try {
      seedGrantParticipant("advisory-test-human", "assessment.case.read", "knowledge.retrieval.read",
          "knowledge.classification.restricted.read");
      jdbc.update(
          "INSERT INTO uacrf_identity.principal "
              + "(id, issuer_uri, subject, display_name, kind, enabled, token_valid_after, revision) "
              + "VALUES ('advisory-test-system', ?, 'advisory-test-system-subject', "
              + "'Advisory test system', 'SYSTEM', true, now() - interval '1 minute', 1), "
              + "('advisory-test-agent', ?, 'advisory-test-agent-subject', "
              + "'Advisory test agent', 'AGENT', true, now() - interval '1 minute', 1)",
          ISSUER, ISSUER);
      String approver = token("keycloak-subject-approver");
      String admin = token("keycloak-subject-1");
      String actor = token("advisory-test-human-subject");
      String actorToken = actor.substring("Bearer ".length());
      URI identity = URI.create("http://127.0.0.1:" + port);
      String path = "/v1/me/access" + context;
      assertThat(get(path, actor).body()).doesNotContain("\"advisory.run.create\"");
      assertThat(get(path, admin).body()).doesNotContain("\"advisory.run.create\"");
      assertThrows(java.io.IOException.class, () -> RequesterIdentityProbe.check(identity, actorToken, "PUBLIC"));

      String scoped = decisionBody("advisory-test-scoped", "advisory-test-human",
          "advisory.run.create", expiresAt).replace("\"service_scope\":\"*\"", "\"service_scope\":\"svc-1\"");
      assertEquals(422, post("/v1/grant-decisions" + context, approver, scoped).statusCode());
      assertEquals(422, post("/v1/grant-decisions" + context, approver,
          decisionBody("advisory-test-system-decision", "advisory-test-system",
              "advisory.run.create", expiresAt)).statusCode());
      assertEquals(422, post("/v1/grant-decisions" + context, approver,
          decisionBody("advisory-test-agent-decision", "advisory-test-agent",
              "advisory.run.create", expiresAt)).statusCode());
      assertEquals(403, post("/v1/grant-decisions" + context, approver,
          decisionBody("advisory-test-self", "bootstrap-approver",
              "advisory.run.create", expiresAt)).statusCode());
      String create = membershipBody(membershipId, "advisory-test-human",
          "advisory.run.create", expiresAt, decisionId);
      assertEquals(403, post("/v1/memberships" + context, admin, create, "advisory-grant-forged").statusCode());
      assertEquals(201, post("/v1/grant-decisions" + context, approver,
          decisionBody(decisionId, "advisory-test-human", "advisory.run.create", expiresAt)).statusCode());
      assertEquals(403, post("/v1/memberships" + context, approver, create, "advisory-grant-approver").statusCode());
      assertEquals(201, post("/v1/memberships" + context, admin, create, "advisory-grant-create").statusCode());
      assertThat(get(path, actor).body()).contains("\"advisory.run.create\"");
      for (String classification : List.of("PUBLIC", "INTERNAL", "RESTRICTED")) {
        assertEquals("advisory-test-human", RequesterIdentityProbe.check(identity, actorToken, classification));
      }
      String delegation = RequesterIdentityProbe.delegate(identity, actorToken, "S08",
          List.of("knowledge.retrieval.read", "knowledge.classification.restricted.read"));
      NimbusJwtDecoder delegationDecoder =
          NimbusJwtDecoder.withPublicKey((RSAPublicKey) DELEGATION_KEY_PAIR.getPublic())
              .signatureAlgorithm(SignatureAlgorithm.RS256).build();
      Jwt delegated = delegationDecoder.decode(delegation);
      assertThat(delegated.getIssuer().toString()).isEqualTo("https://uacrf.local/s01");
      assertThat(delegated.getHeaders().get("kid")).isEqualTo(DELEGATION_KEY_ID);
      assertThat(delegated.getAudience()).containsExactly("S08");
      assertThat(delegated.getSubject()).isEqualTo("advisory-test-human");
      assertThat(delegated.getClaimAsString("tenant_id")).isEqualTo("tenant-test");
      assertThat(delegated.getClaimAsString("environment_id")).isEqualTo("environment-test");
      assertThat(delegated.getClaimAsString("service_scope")).isEqualTo("*");
      assertThat(delegated.getClaimAsStringList("capabilities"))
          .containsExactly("knowledge.retrieval.read", "knowledge.classification.restricted.read");
      Jwt caseDelegation = delegationDecoder.decode(RequesterIdentityProbe.delegate(
          identity, actorToken, "S07", List.of("assessment.case.read")));
      assertThat(caseDelegation.getAudience()).containsExactly("S07");
      assertThat(caseDelegation.getSubject()).isEqualTo("advisory-test-human");
      assertThat(caseDelegation.getClaimAsStringList("capabilities")).containsExactly("assessment.case.read");
      assertThrows(java.io.IOException.class, () -> RequesterIdentityProbe.delegate(identity, actorToken, "S08",
          List.of("knowledge.retrieval.read", "knowledge.classification.internal.read")));
      try (var owners = new SignedRequesterOwners((RSAPublicKey) DELEGATION_KEY_PAIR.getPublic(), DELEGATION_KEY_ID)) {
        URI caseReceipt = owners.base().resolve("/internal/v1/cases/case-1:receipt");
        for (String invalidAuthorization : List.of("Bearer " + delegation, actor)) {
          HttpRequest invalidRequest = HttpRequest.newBuilder(caseReceipt)
              .header("Authorization", invalidAuthorization)
              .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
          assertEquals(403, http.send(invalidRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertEquals("advisory-test-human", RequesterIdentityProbe.checkSources(identity, owners.base(), actorToken));
        assertEquals(4, owners.caseCalls());
        assertEquals(1, owners.caseMaterialCalls());
        assertEquals(3, owners.receiptCalls());
        assertEquals(1, owners.contentCalls());
        var revokedDuringContent = new java.util.concurrent.atomic.AtomicInteger();
        owners.onContent(() -> {
          try {
            revokedDuringContent.set(post("/v1/memberships/" + membershipId + ":revoke" + context, admin,
                "{\"expected_revision\":1,\"reason\":\"End advisory access\"}", "advisory-grant-revoke")
                .statusCode());
          } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Revocation was interrupted.", failure);
          }
        });
        assertThrows(java.io.IOException.class,
            () -> RequesterIdentityProbe.checkSources(identity, owners.base(), actorToken));
        assertEquals(200, revokedDuringContent.get());
        assertThat(get(path, actor).body()).doesNotContain("\"advisory.run.create\"");
        assertThrows(java.io.IOException.class, () -> RequesterIdentityProbe.check(identity, actorToken, "PUBLIC"));
        assertThrows(java.io.IOException.class,
            () -> RequesterIdentityProbe.checkSources(identity, owners.base(), actorToken));
        assertEquals(6, owners.caseCalls());
        assertEquals(2, owners.caseMaterialCalls());
        assertEquals(5, owners.receiptCalls());
        assertEquals(2, owners.contentCalls());
      }
      UUID retrievalGrant = jdbc.queryForObject(
          "SELECT id FROM uacrf_identity.membership WHERE principal_id = 'advisory-test-human' "
              + "AND capability = 'knowledge.retrieval.read'", UUID.class);
      assertEquals(200, post("/v1/memberships/" + retrievalGrant + ":revoke" + context, admin,
          "{\"expected_revision\":1,\"reason\":\"End retrieval access\"}", "advisory-grant-source-revoke").statusCode());
      assertThrows(java.io.IOException.class, () -> RequesterIdentityProbe.check(identity, actorToken, "PUBLIC"));
      assertThrows(java.io.IOException.class, () -> RequesterIdentityProbe.delegate(identity, actorToken, "S08",
          List.of("knowledge.retrieval.read", "knowledge.classification.restricted.read")));
    } finally {
      jdbc.update("UPDATE uacrf_identity.grant_decision SET consumed_at = NULL, consumed_membership_id = NULL "
          + "WHERE id = ?", decisionId);
      jdbc.update("DELETE FROM uacrf_identity.audit_event WHERE principal_id = 'advisory-test-human'");
      jdbc.update("DELETE FROM uacrf_identity.outbox_event WHERE aggregate_id IN "
          + "(SELECT id::text FROM uacrf_identity.membership WHERE principal_id = 'advisory-test-human')");
      jdbc.update("DELETE FROM uacrf_identity.idempotency_record WHERE idempotency_key IN "
          + "('advisory-grant-forged', 'advisory-grant-approver', 'advisory-grant-create', "
          + "'advisory-grant-source-revoke', 'advisory-grant-revoke')");
      jdbc.update("DELETE FROM uacrf_identity.membership WHERE principal_id = 'advisory-test-human'");
      jdbc.update("DELETE FROM uacrf_identity.grant_decision WHERE id = ?", decisionId);
      jdbc.update("DELETE FROM uacrf_identity.principal WHERE id IN "
          + "('advisory-test-human', 'advisory-test-system', 'advisory-test-agent')");
    }
  }

  @Test
  void returnsOnlyCurrentScopedAccessAndEligibleOwnerCandidates() throws Exception {
    seedScopedMemberships();
    HttpResponse<String> access =
        get("/v1/me/access?service_id=svc-1", token("keycloak-subject-1"));

    assertEquals(200, access.statusCode());
    assertThat(access.body())
        .contains(
            "\"principal_id\":\"admin-test\"",
            "\"service_id\":\"svc-1\"",
            "\"scope\":\"GLOBAL_AND_SELECTED_SERVICE_GRANTS\"",
            "\"catalog.service.write\"");

    HttpResponse<String> firstPage =
        get(
            "/v1/owner-candidates?object_kind=SERVICE&object_id=svc-1&limit=1"
                + "&tenant_id=tenant-test&environment_id=environment-test",
            tokenWithoutContext("keycloak-subject-1"));
    assertEquals(200, firstPage.statusCode());
    assertThat(firstPage.body())
        .contains("\"id\":\"owner-a\"", "\"owner_eligible\":true")
        .doesNotContain("owner-expired", "owner-disabled");

    String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString("owner-a".getBytes());
    HttpResponse<String> secondPage =
        get(
            "/v1/owner-candidates?object_kind=SERVICE&object_id=svc-1&limit=1&cursor="
                + cursor
                + "&tenant_id=tenant-test&environment_id=environment-test",
            tokenWithoutContext("keycloak-subject-1"));
    assertEquals(200, secondPage.statusCode());
    assertThat(secondPage.body()).contains("\"id\":\"owner-b\"");

    HttpResponse<String> denied =
        get(
            "/v1/owner-candidates?object_kind=SERVICE&object_id=svc-other"
                + "&tenant_id=tenant-test&environment_id=environment-test",
            tokenWithoutContext("keycloak-subject-1"));
    assertEquals(200, denied.statusCode());
    assertThat(denied.body()).contains("\"id\":\"owner-a\"", "\"id\":\"owner-b\"");

    HttpResponse<String> unauthenticated = get("/v1/me/access", null);
    assertEquals(401, unauthenticated.statusCode());
    assertThat(unauthenticated.body()).contains("\"code\":\"UNAUTHENTICATED\"");
  }

  @Test
  void ownershipTransferOperatorCanReadTargetsAndSelectOwnersWithoutCatalogWrite()
      throws Exception {
    seedScopedMemberships();
    String principalId = "transfer-operator-test";
    UUID ownerSelection = UUID.randomUUID();
    UUID ownershipTransfer = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO uacrf_identity.principal "
            + "(id, issuer_uri, subject, display_name, kind, enabled, token_valid_after, revision) "
            + "VALUES (?, ?, ?, 'Transfer Operator', 'HUMAN', true, now() - interval '1 minute', 1)",
        principalId,
        ISSUER,
        principalId + "-subject");
    for (Object[] membership :
        List.of(
            new Object[] {ownerSelection, "identity.owner.select"},
            new Object[] {ownershipTransfer, "ownership.transfer"})) {
      jdbc.update(
          "INSERT INTO uacrf_identity.membership "
              + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
              + "expires_at, revision) VALUES (?, 'tenant-test', 'environment-test', ?, ?, "
              + "'*', '2099-12-31T23:59:59Z', 1)",
          membership[0],
          principalId,
          membership[1]);
    }

    String operator = tokenWithoutContext(principalId + "-subject");
    String context = "&tenant_id=tenant-test&environment_id=environment-test";
    try {
      for (String objectKind : List.of("JOURNEY", "SERVICE")) {
        String objectId = "JOURNEY".equals(objectKind) ? "journey-1" : "svc-1";
        HttpResponse<String> owners =
            get(
                "/v1/owner-candidates?object_kind="
                    + objectKind
                    + "&object_id="
                    + objectId
                    + context,
                operator);
        assertEquals(200, owners.statusCode());
        assertThat(owners.body()).contains("\"id\":\"owner-a\"", "\"id\":\"owner-b\"");
      }

      HttpResponse<String> journeyRead =
          post(
              "/internal/v1/delegations",
              operator,
              """
              {"audience":"S02","service_id":null,"capability_ids":["catalog.journey.read"],
               "tenant_id":"tenant-test","environment_id":"environment-test"}
              """);
      assertEquals(200, journeyRead.statusCode(), journeyRead.body());
      Jwt journeyDelegation =
          decodeDelegation(
              journeyRead.body().replaceFirst("^.*\"access_token\":\"([^\"]+)\".*$", "$1"),
              "S02");
      assertThat(journeyDelegation.getClaimAsString("service_scope")).isEqualTo("*");
      assertThat(journeyDelegation.getClaimAsStringList("capabilities"))
          .containsExactly("catalog.journey.read");

      HttpResponse<String> serviceRead =
          post(
              "/internal/v1/delegations",
              operator,
              """
              {"audience":"S02","service_id":"svc-1","capability_ids":["catalog.service.read"],
               "tenant_id":"tenant-test","environment_id":"environment-test"}
              """);
      assertEquals(200, serviceRead.statusCode(), serviceRead.body());
      Jwt serviceDelegation =
          decodeDelegation(
              serviceRead.body().replaceFirst("^.*\"access_token\":\"([^\"]+)\".*$", "$1"),
              "S02");
      assertThat(serviceDelegation.getClaimAsString("service_scope")).isEqualTo("svc-1");
      assertThat(serviceDelegation.getClaimAsStringList("capabilities"))
          .containsExactly("catalog.service.read");

      jdbc.update(
          "UPDATE uacrf_identity.membership SET service_scope = 'svc-1' WHERE id = ?",
          ownershipTransfer);
      assertEquals(
          200,
          get(
                  "/v1/owner-candidates?object_kind=SERVICE&object_id=svc-1" + context,
                  operator)
              .statusCode());
      assertEquals(
          200,
          post(
                  "/internal/v1/delegations",
                  operator,
                  """
                  {"audience":"S02","service_id":"svc-1","capability_ids":["catalog.service.read"],
                   "tenant_id":"tenant-test","environment_id":"environment-test"}
                  """)
              .statusCode());
      assertEquals(
          403,
          get(
                  "/v1/owner-candidates?object_kind=SERVICE&object_id=svc-other" + context,
                  operator)
              .statusCode());
      assertEquals(
          403,
          get(
                  "/v1/owner-candidates?object_kind=JOURNEY&object_id=journey-1" + context,
                  operator)
              .statusCode());
      assertEquals(
          403,
          post(
                  "/internal/v1/delegations",
                  operator,
                  """
                  {"audience":"S02","service_id":"svc-other","capability_ids":["catalog.service.read"],
                   "tenant_id":"tenant-test","environment_id":"environment-test"}
                  """)
              .statusCode());
      assertEquals(
          403,
          post(
                  "/internal/v1/delegations",
                  operator,
                  """
                  {"audience":"S02","service_id":null,"capability_ids":["catalog.journey.read"],
                   "tenant_id":"tenant-test","environment_id":"environment-test"}
                  """)
              .statusCode());
      assertEquals(
          403,
          post(
                  "/internal/v1/delegations",
                  operator,
                  """
                  {"audience":"S02","service_id":null,"capability_ids":["catalog.journey.write"],
                   "tenant_id":"tenant-test","environment_id":"environment-test"}
                  """)
              .statusCode());
      jdbc.update(
          "UPDATE uacrf_identity.membership SET revoked_at = now() WHERE id = ?", ownerSelection);
      assertEquals(
          403,
          post(
                  "/internal/v1/delegations",
                  operator,
                  """
                  {"audience":"S02","service_id":null,"capability_ids":["catalog.journey.read"],
                   "tenant_id":"tenant-test","environment_id":"environment-test"}
                  """)
              .statusCode());
    } finally {
      jdbc.update("DELETE FROM uacrf_identity.membership WHERE principal_id = ?", principalId);
      jdbc.update("DELETE FROM uacrf_identity.principal WHERE id = ?", principalId);
    }
  }

  @Test
  void connectionOwnerCandidatesRequireGlobalWriteAndOwnerSelection() throws Exception {
    seedScopedMemberships();
    UUID scopedGrant = UUID.randomUUID();
    UUID globalGrant = UUID.randomUUID();
    UUID noSelectionGrant = UUID.randomUUID();
    String path = "/v1/owner-candidates?object_kind=CONNECTION"
        + "&object_id=sample-connection&tenant_id=tenant-test&environment_id=environment-test";
    String admin = tokenWithoutContext("keycloak-subject-1");
    try {
      jdbc.update(
          "INSERT INTO uacrf_identity.principal "
              + "(id, issuer_uri, subject, display_name, kind, enabled, token_valid_after, revision) "
              + "VALUES ('connection-writer-test', ?, 'connection-writer-test', "
              + "'Connection writer', 'HUMAN', true, '2020-01-01T00:00:00Z', 1)",
          ISSUER);
      for (Object[] membership : List.of(
          new Object[] {scopedGrant, "admin-test", "sample-connection"},
          new Object[] {noSelectionGrant, "connection-writer-test", "*"})) {
        jdbc.update(
            "INSERT INTO uacrf_identity.membership "
                + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
                + "expires_at, revision) VALUES (?, 'tenant-test', 'environment-test', ?, "
                + "'connections.connection.write', ?, '2099-12-31T23:59:59Z', 1)",
            membership);
      }

      assertEquals(403, get(path, admin).statusCode());
      assertEquals(403, get(path, tokenWithoutContext("connection-writer-test")).statusCode());
      assertEquals(401, get(path, null).statusCode());

      jdbc.update(
          "INSERT INTO uacrf_identity.membership "
              + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
              + "expires_at, revision) VALUES (?, 'tenant-test', 'environment-test', "
              + "'admin-test', 'connections.connection.write', '*', '2099-12-31T23:59:59Z', 1)",
          globalGrant);
      HttpResponse<String> candidates = get(path, admin);
      assertEquals(200, candidates.statusCode());
      assertThat(candidates.body())
          .contains("\"id\":\"owner-a\"", "\"id\":\"owner-b\"")
          .doesNotContain("owner-expired", "owner-disabled");
      assertEquals(200, get(path.replace("&object_id=sample-connection", ""), admin).statusCode());
      HttpResponse<String> issued = post(
          "/internal/v1/delegations", token("keycloak-subject-1"),
          """
          {"audience":"S03","capability_ids":["connections.connection.write"]}
          """);
      assertEquals(200, issued.statusCode());
      String accessToken = issued.body().replaceFirst("^.*\"access_token\":\"([^\"]+)\".*$", "$1");
      Jwt delegation = decodeDelegation(accessToken, "S03");
      assertThat(delegation.getClaimAsString("service_scope")).isEqualTo("*");
      assertThat(delegation.getClaimAsStringList("eligible_owner_ids"))
          .contains("owner-a", "owner-b")
          .doesNotContain("owner-expired", "owner-disabled");

      jdbc.update("UPDATE uacrf_identity.membership SET revoked_at = now() WHERE id = ?", globalGrant);
      assertEquals(403, get(path, admin).statusCode());
    } finally {
      jdbc.update("DELETE FROM uacrf_identity.membership WHERE id IN (?, ?, ?)",
          scopedGrant, globalGrant, noSelectionGrant);
      jdbc.update("DELETE FROM uacrf_identity.principal WHERE id = 'connection-writer-test'");
    }
  }

  @Test
  void listsOnlyAdmittedContextsAndRechecksMembershipForSelectedAccess() throws Exception {
    seedScopedMemberships();
    jdbc.update(
        "INSERT INTO uacrf_identity.tenant (id, display_name, status, revision) "
            + "VALUES ('tenant-secondary', 'Secondary tenant', 'ACTIVE', 1)");
    jdbc.update(
        "INSERT INTO uacrf_identity.environment "
            + "(tenant_id, id, display_name, status, revision) "
            + "VALUES ('tenant-secondary', 'environment-secondary', 'Secondary environment', "
            + "'ACTIVE', 1)");
    jdbc.update(
        "INSERT INTO uacrf_identity.membership "
            + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
            + "expires_at, revision) VALUES (?, 'tenant-secondary', 'environment-secondary', "
            + "'admin-test', 'catalog.journey.write', '*', '2099-12-31T23:59:59Z', 1)",
        UUID.randomUUID());

    HttpResponse<String> firstContextPage =
        get("/v1/me/contexts?limit=1", tokenWithoutContext("keycloak-subject-1"));
    assertEquals(200, firstContextPage.statusCode(), firstContextPage.body());
    assertThat(firstContextPage.body())
        .contains("\"tenant_id\":\"tenant-secondary\"", "\"environment_id\":\"environment-secondary\"")
        .contains("\"next_cursor\"");
    String cursor =
        firstContextPage.body().replaceAll(".*\"next_cursor\":\"([^\"]+)\".*", "$1");
    HttpResponse<String> secondContextPage =
        get(
            "/v1/me/contexts?limit=1&cursor=" + cursor,
            tokenWithoutContext("keycloak-subject-1"));
    assertEquals(200, secondContextPage.statusCode(), secondContextPage.body());
    assertThat(secondContextPage.body())
        .contains("\"tenant_id\":\"tenant-test\"", "\"environment_id\":\"environment-test\"")
        .contains("\"next_cursor\":null");

    HttpResponse<String> selectedAccess =
        get(
            "/v1/me/access?tenant_id=tenant-secondary&environment_id=environment-secondary",
            tokenWithoutContext("keycloak-subject-1"));
    assertEquals(200, selectedAccess.statusCode(), selectedAccess.body());
    assertThat(selectedAccess.body())
        .contains("\"catalog.journey.write\"")
        .doesNotContain("\"identity.membership.read\"");

    HttpResponse<String> unauthorizedContext =
        get(
            "/v1/me/access?tenant_id=tenant-unknown&environment_id=environment-unknown",
            tokenWithoutContext("keycloak-subject-1"));
    assertEquals(403, unauthorizedContext.statusCode());

    HttpResponse<String> incompleteContext =
        get(
            "/v1/me/access?tenant_id=tenant-secondary",
            tokenWithoutContext("keycloak-subject-1"));
    assertEquals(400, incompleteContext.statusCode());
  }

  @Test
  void listsTenantScopedProvidersPrincipalsAndCurrentMemberships() throws Exception {
    seedScopedMemberships();
    String context = "tenant_id=tenant-test&environment_id=environment-test";

    HttpResponse<String> providers =
        get("/v1/identity-providers?" + context, tokenWithoutContext("keycloak-subject-1"));
    assertEquals(200, providers.statusCode());
    assertThat(providers.body())
        .contains("\"id\":\"keycloak-local\"", "\"status\":\"TRUSTED\"")
        .doesNotContain("keycloak.example.test");

    HttpResponse<String> firstPrincipals =
        get(
            "/v1/principals?limit=1&" + context,
            tokenWithoutContext("keycloak-subject-1"));
    assertEquals(200, firstPrincipals.statusCode());
    assertThat(firstPrincipals.body())
        .contains("\"id\":\"admin-test\"", "\"owner_eligible\":false");
    String principalCursor =
        Base64.getUrlEncoder().withoutPadding().encodeToString("bootstrap-approver".getBytes());
    HttpResponse<String> nextPrincipals =
        get(
            "/v1/principals?limit=1&cursor="
                + principalCursor
                + "&"
                + context,
            tokenWithoutContext("keycloak-subject-1"));
    assertEquals(200, nextPrincipals.statusCode());
    assertThat(nextPrincipals.body())
        .contains("\"id\":\"owner-a\"", "\"owner_eligible\":true");

    HttpResponse<String> allMemberships =
        get(
            "/v1/memberships?limit=100&" + context,
            tokenWithoutContext("keycloak-subject-1"));
    assertEquals(200, allMemberships.statusCode());
    assertThat(allMemberships.body())
        .contains("\"capability\":\"identity.membership.read\"")
        .contains("\"capability\":\"catalog.service.write\"")
        .contains("\"service_scope\":\"svc-1\"");

    HttpResponse<String> firstMembership =
        get(
            "/v1/memberships?limit=1&" + context,
            tokenWithoutContext("keycloak-subject-1"));
    assertEquals(200, firstMembership.statusCode());
    assertThat(firstMembership.body()).contains("\"next_cursor\":\"");
    String membershipCursor =
        firstMembership.body().replaceFirst("^.*\"next_cursor\":\"([^\"]+)\".*$", "$1");
    String firstMembershipId =
        firstMembership.body().replaceFirst("^.*\"id\":\"([^\"]+)\".*$", "$1");
    HttpResponse<String> nextMembership =
        get(        "/v1/memberships?limit=1&cursor=" + membershipCursor + "&" + context,
        tokenWithoutContext("keycloak-subject-1"));
    assertEquals(200, nextMembership.statusCode());
    assertThat(nextMembership.body()).doesNotContain("\"id\":\"" + firstMembershipId + "\"");

    HttpResponse<String> denied =
        get("/v1/principals", token("owner-a-subject"));
    assertEquals(403, denied.statusCode());
    assertThat(denied.body()).contains("\"code\":\"PERMISSION_DENIED\"");
  }

  @Test
  void revokesMembershipWithAuditOutboxAndIdempotentReplay() throws Exception {
    seedScopedMemberships();
    seedRevocableMembership();
    String membershipId = "00000000-0000-4000-8000-000000000101";
    String request = """
        {"expected_revision":1,"reason":"Remove temporary catalog access"}
        """;
    String endpoint =
        "/v1/memberships/"
            + membershipId
            + ":revoke?tenant_id=tenant-test&environment_id=environment-test";

    HttpResponse<String> revoked =
        post(endpoint, tokenWithoutContext("keycloak-subject-1"), request, "revoke-key-1");
    assertEquals(200, revoked.statusCode(), revoked.body());
    assertThat(revoked.body())
        .contains(
            "\"id\":\"" + membershipId + "\"",
            "\"revision\":2",
            "\"result\":\"REVOKED\"",
            "\"runtime_effect\":\"NONE\"");
    HttpResponse<String> revokedDelegation =
        post(
            "/internal/v1/delegations",
            tokenWithoutContext("revocable-user-subject"),
            """
            {
              "audience":"S02",
              "service_id":"svc-1",
              "capability_ids":["catalog.service.read"],
              "tenant_id":"tenant-test",
              "environment_id":"environment-test"
            }
            """);
    assertEquals(403, revokedDelegation.statusCode(), revokedDelegation.body());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_identity.audit_event "
                + "WHERE membership_id = ? AND action = 'MEMBERSHIP_REVOKED' "
                + "AND reason = 'Remove temporary catalog access'",
            Integer.class,
            UUID.fromString(membershipId)));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_identity.outbox_event "
                + "WHERE aggregate_id = ? AND event_type = 'identity.membership.revoked' "
                + "AND aggregate_sequence = 2",
            Integer.class,
            membershipId));

    HttpResponse<String> replay =
        post(endpoint, tokenWithoutContext("keycloak-subject-1"), request, "revoke-key-1");
    assertEquals(200, replay.statusCode(), replay.body());
    assertThat(replay.body())
        .contains("\"revision\":2", "\"result\":\"REPLAYED\"", "\"runtime_effect\":\"NONE\"");
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_identity.audit_event WHERE membership_id = ?",
            Integer.class,
            UUID.fromString(membershipId)));

    HttpResponse<String> reusedKey =
        post(
            endpoint,
            token("keycloak-subject-1"),
            """
            {"expected_revision":1,"reason":"Different reason"}
            """,
            "revoke-key-1");
    assertEquals(409, reusedKey.statusCode());

    HttpResponse<String> staleRevision =
        post(endpoint, token("keycloak-subject-1"), request, "revoke-key-2");
    assertEquals(409, staleRevision.statusCode());

    String administratorMembership =
        jdbc.queryForObject(
            "SELECT id::text FROM uacrf_identity.membership "
                + "WHERE principal_id = 'admin-test' "
                + "AND capability = 'identity.membership.manage' AND revoked_at IS NULL",
            String.class);
    String administratorEndpoint = "/v1/memberships/" + administratorMembership + ":revoke";
    HttpResponse<String> unauthorized =
        post(
            administratorEndpoint,
            token("owner-a-subject"),
            request,
            "revoke-key-unauthorized");
    assertEquals(403, unauthorized.statusCode());

    HttpResponse<String> missingIdempotencyKey =
        post(administratorEndpoint, token("keycloak-subject-1"), request);
    assertEquals(400, missingIdempotencyKey.statusCode());

    HttpResponse<String> lastAdministrator =
        post(
            administratorEndpoint,
            token("keycloak-subject-1"),
            """
            {"expected_revision":1,"reason":"Must preserve identity administration"}
            """,
            "revoke-key-last-admin");
    assertEquals(409, lastAdministrator.statusCode());
    assertThat(lastAdministrator.body()).contains("\"code\":\"LAST_IDENTITY_ADMIN\"");
  }

  @Test
  void serializesConcurrentMembershipRevocations() {
    seedScopedMemberships();
    String membershipId = "00000000-0000-4000-8000-000000000102";
    seedRevocableMembership(membershipId, "owner-a");
    String endpoint = "/v1/memberships/" + membershipId + ":revoke";
    String request = """
        {"expected_revision":1,"reason":"Concurrent revocation"}
        """;

    CompletableFuture<HttpResponse<String>> first =
        CompletableFuture.supplyAsync(
            () ->
                postUnchecked(
                    endpoint, token("keycloak-subject-1"), request, "revoke-race-key-1"));
    CompletableFuture<HttpResponse<String>> second =
        CompletableFuture.supplyAsync(
            () ->
                postUnchecked(
                    endpoint, token("keycloak-subject-1"), request, "revoke-race-key-2"));
    HttpResponse<String> firstResponse = first.join();
    HttpResponse<String> secondResponse = second.join();

    assertThat(List.of(firstResponse.statusCode(), secondResponse.statusCode()))
        .containsExactlyInAnyOrder(200, 409);
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT revision FROM uacrf_identity.membership WHERE id = ?",
            Integer.class,
            UUID.fromString(membershipId)));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_identity.audit_event WHERE membership_id = ?",
            Integer.class,
            UUID.fromString(membershipId)));
  }

  @Test
  void disablesPrincipalWithAuditOutboxAndIdempotentReplay() throws Exception {
    seedScopedMemberships();
    jdbc.update(
        "INSERT INTO uacrf_identity.principal "
            + "(id, issuer_uri, subject, display_name, kind, enabled, token_valid_after, revision) "
            + "VALUES ('status-target', ?, 'status-target-subject', 'Status target', "
            + "'HUMAN', true, now(), 1) ON CONFLICT (id) DO NOTHING",
        ISSUER);
    jdbc.update(
        "INSERT INTO uacrf_identity.membership "
            + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
            + "expires_at, revision) VALUES (?, 'tenant-test', 'environment-test', "
            + "'status-target', 'owner.eligible', '*', '2099-12-31T23:59:59Z', 1) "
            + "ON CONFLICT (id) DO NOTHING",
        UUID.randomUUID());

    String endpoint = "/v1/principals/status-target:set-status";
    String request = """
        {"enabled":false,"expected_revision":1,"reason":"Disable compromised principal","grant_review_ref":null}
        """;
    HttpResponse<String> disabled =
        post(endpoint, token("keycloak-subject-1"), request, "principal-status-key-1");

    assertEquals(200, disabled.statusCode(), disabled.body());
    assertThat(disabled.body())
        .contains(
            "\"id\":\"status-target\"",
            "\"revision\":2",
            "\"result\":\"UPDATED\"",
            "\"runtime_effect\":\"NONE\"");
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_identity.principal_status_audit "
                + "WHERE principal_id = 'status-target' AND actor_id = 'admin-test' "
                + "AND previous_enabled = true AND new_enabled = false "
                + "AND reason = 'Disable compromised principal'",
            Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_identity.outbox_event "
                + "WHERE aggregate_id = 'status-target' "
                + "AND event_type = 'identity.principal.disabled' "
                + "AND aggregate_sequence = 2",
            Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_identity.membership "
                + "WHERE principal_id = 'status-target' AND revoked_at IS NOT NULL",
            Integer.class));

    HttpResponse<String> replay =
        post(endpoint, token("keycloak-subject-1"), request, "principal-status-key-1");
    assertEquals(200, replay.statusCode(), replay.body());
    assertThat(replay.body())
        .contains("\"revision\":2", "\"result\":\"REPLAYED\"", "\"runtime_effect\":\"NONE\"");
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_identity.principal_status_audit "
                + "WHERE principal_id = 'status-target'",
            Integer.class));

    HttpResponse<String> reusedKey =
        post(
            endpoint,
            token("keycloak-subject-1"),
            """
            {"enabled":false,"expected_revision":1,"reason":"Different reason","grant_review_ref":null}
            """,
            "principal-status-key-1");
    assertEquals(409, reusedKey.statusCode());

    HttpResponse<String> staleRevision =
        post(
            endpoint,
            token("keycloak-subject-1"),
            """
            {"enabled":false,"expected_revision":1,"reason":"Stale status request","grant_review_ref":null}
            """,
            "principal-status-key-2");
    assertEquals(409, staleRevision.statusCode());

    HttpResponse<String> unauthorized =
        post(
            endpoint,
            token("owner-a-subject"),
            request,
            "principal-status-key-unauthorized");
    assertEquals(403, unauthorized.statusCode());

    HttpResponse<String> disabledAccess =
        get("/v1/me/access", token("status-target-subject"));
    assertEquals(403, disabledAccess.statusCode());

    HttpResponse<String> lastAdministrator =
        post(
            "/v1/principals/admin-test:set-status",
            token("keycloak-subject-1"),
            """
            {"enabled":false,"expected_revision":1,"reason":"Preserve final administrator","grant_review_ref":null}
            """,
            "principal-status-last-admin");
    assertEquals(409, lastAdministrator.statusCode());
    assertThat(lastAdministrator.body()).contains("\"code\":\"LAST_IDENTITY_ADMIN\"");

    HttpResponse<String> unverifiedReenable =
        post(
            endpoint,
            token("keycloak-subject-1"),
            """
            {"enabled":true,"expected_revision":2,"reason":"Re-enable after review","grant_review_ref":"review-123"}
            """,
            "principal-status-reenable");
    assertEquals(503, unverifiedReenable.statusCode());
    assertThat(unverifiedReenable.body()).contains("\"code\":\"TRUSTED_SOURCE_UNAVAILABLE\"");
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT revision FROM uacrf_identity.principal WHERE id = 'status-target'",
            Integer.class));
  }

  @Test
  void issuesOnlyMembershipBoundShortLivedS02Delegations() throws Exception {
    seedScopedMemberships();
    HttpResponse<String> response =
        post(
            "/internal/v1/delegations",
            token("keycloak-subject-1"),
            """
            {"audience":"S02","service_id":"svc-1","capability_ids":["catalog.service.write"]}
            """);

    assertEquals(200, response.statusCode(), response.body());
    assertThat(response.body()).contains("\"token_type\":\"Bearer\"", "\"expires_in\":30");
    String accessToken =
        response.body().replaceFirst("^.*\"access_token\":\"([^\"]+)\".*$", "$1");
    Jwt delegation = decodeDelegation(accessToken);
    assertThat(delegation.getIssuer().toString()).isEqualTo("https://uacrf.local/s01");
    assertThat(delegation.getAudience()).containsExactly("S02");
    assertThat(delegation.getSubject()).isEqualTo("admin-test");
    assertThat(delegation.getClaimAsStringList("capabilities"))
        .containsExactly("catalog.service.write");
    assertThat(delegation.getClaimAsString("service_scope")).isEqualTo("svc-1");
    assertThat(delegation.getClaimAsStringList("eligible_owner_ids"))
        .containsExactly("owner-a", "owner-b");
    assertThat(delegation.getClaimAsStringList("runtime_principal_ids"))
        .containsExactly("runtime-1");
    assertEquals(
        30,
        java.time.Duration.between(delegation.getIssuedAt(), delegation.getExpiresAt())
            .getSeconds());

    HttpResponse<String> selectedContext =
        post(
            "/internal/v1/delegations",
            tokenWithoutContext("keycloak-subject-1"),
            """
            {
              "audience":"S02",
              "service_id":"svc-1",
              "capability_ids":["catalog.service.write"],
              "tenant_id":"tenant-test",
              "environment_id":"environment-test"
            }
            """);
    assertEquals(200, selectedContext.statusCode(), selectedContext.body());
    String selectedAccessToken =
        selectedContext.body().replaceFirst("^.*\"access_token\":\"([^\"]+)\".*$", "$1");
    Jwt selectedDelegation = decodeDelegation(selectedAccessToken);
    assertThat(selectedDelegation.getClaimAsString("tenant_id")).isEqualTo("tenant-test");
    assertThat(selectedDelegation.getClaimAsString("environment_id"))
        .isEqualTo("environment-test");

    HttpResponse<String> unauthorizedContext =
        post(
            "/internal/v1/delegations",
            tokenWithoutContext("keycloak-subject-1"),
            """
            {
              "audience":"S02",
              "service_id":"svc-1",
              "capability_ids":["catalog.service.write"],
              "tenant_id":"tenant-other",
              "environment_id":"environment-other"
            }
            """);
    assertEquals(403, unauthorizedContext.statusCode(), unauthorizedContext.body());

    HttpResponse<String> incompleteContext =
        post(
            "/internal/v1/delegations",
            tokenWithoutContext("keycloak-subject-1"),
            """
            {
              "audience":"S02",
              "service_id":"svc-1",
              "capability_ids":["catalog.service.write"],
              "tenant_id":"tenant-test"
            }
            """);
    assertEquals(400, incompleteContext.statusCode(), incompleteContext.body());

    HttpResponse<String> widened =
        post(
            "/internal/v1/delegations",
            token("keycloak-subject-1"),
            """
            {"audience":"S02","service_id":"svc-1","capability_ids":["catalog.agent.write"]}
            """);
    assertEquals(403, widened.statusCode());

    HttpResponse<String> wrongAudience =
        post(
            "/internal/v1/delegations",
            token("keycloak-subject-1"),
            """
            {"audience":"S01","service_id":"svc-1","capability_ids":["catalog.service.write"]}
            """);
    assertEquals(400, wrongAudience.statusCode());

    HttpResponse<String> topologyAudience =
        post(
            "/internal/v1/delegations",
            token("keycloak-subject-1"),
            """
            {"audience":"S06","service_id":"svc-1","capability_ids":["catalog.service.write"]}
            """);
    assertEquals(200, topologyAudience.statusCode(), topologyAudience.body());

    HttpResponse<String> unmemberedTopology =
        post(
            "/internal/v1/delegations",
            token("keycloak-subject-1"),
            """
            {"audience":"S06","capability_ids":["topology.graph.accept"]}
            """);
    assertEquals(403, unmemberedTopology.statusCode(), unmemberedTopology.body());

    HttpResponse<String> assessmentAudience =
        post(
            "/internal/v1/delegations",
            token("keycloak-subject-1"),
            """
            {"audience":"S07","service_id":"svc-1","capability_ids":["catalog.service.write"]}
            """);
    assertEquals(200, assessmentAudience.statusCode(), assessmentAudience.body());

    HttpResponse<String> unmemberedAssessment =
        post(
            "/internal/v1/delegations",
            token("keycloak-subject-1"),
            """
            {"audience":"S07","capability_ids":["assessment.evaluation.run"]}
            """);
    assertEquals(403, unmemberedAssessment.statusCode(), unmemberedAssessment.body());

    HttpResponse<String> unknownAudience =
        post(
            "/internal/v1/delegations",
            token("keycloak-subject-1"),
            """
            {"audience":"S09","service_id":"svc-1","capability_ids":["catalog.service.write"]}
            """);
    assertEquals(400, unknownAudience.statusCode());

    HttpResponse<String> unmemberedConnections =
        post(
            "/internal/v1/delegations",
            token("keycloak-subject-1"),
            """
            {"audience":"S03","service_id":"svc-1","capability_ids":["connections.connection.write"]}
            """);
    assertEquals(403, unmemberedConnections.statusCode(), unmemberedConnections.body());

    HttpResponse<String> humanInboxWriter =
        post(
            "/internal/v1/delegations",
            token("keycloak-subject-1"),
            """
            {"audience":"S04","capability_ids":["evidence.inbox.write"]}
            """);
    assertEquals(403, humanInboxWriter.statusCode(), humanInboxWriter.body());
  }

  @Test
  void modelVersionCapabilitiesAreDelegableOnlyWhenGranted() throws Exception {
    seedScopedMemberships();
    List<String> modelCapabilities =
        List.of("assessment.model.read", "assessment.model.write", "assessment.model.review",
            "assessment.incident.read", "assessment.incident.write", "assessment.learning.write",
            "assessment.learning.read");
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_identity.membership WHERE principal_id='admin-test' "
                + "AND capability IN ('assessment.model.read','assessment.model.write',"
                + "'assessment.model.review','assessment.incident.read',"
                + "'assessment.incident.write','assessment.learning.write','assessment.learning.read') AND revoked_at IS NULL",
            Integer.class));
    try {
      String decision = "learning-read-test-decision";
      try {
        String expiresAt = Instant.now().plusSeconds(86400)
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        HttpResponse<String> approved = post("/v1/grant-decisions?tenant_id=tenant-test&environment_id=environment-test",
            token("keycloak-subject-approver"),
            decisionBody(decision, "admin-test", "assessment.learning.read", expiresAt));
        assertEquals(201, approved.statusCode(), approved.body());
      } finally {
        jdbc.update("DELETE FROM uacrf_identity.grant_decision WHERE id=?", decision);
      }
      for (String capability : modelCapabilities) {
        jdbc.update(
            "INSERT INTO uacrf_identity.membership "
                + "(id,tenant_id,environment_id,principal_id,capability,service_scope,expires_at,revision) "
                + "VALUES (?,'tenant-test','environment-test','admin-test',?,?,"
                + "'2099-12-31T23:59:59Z',1)",
            java.util.UUID.randomUUID(), capability, "assessment.learning.read".equals(capability) ? "*" : "svc-1");
        HttpResponse<String> response =
            post(
                "/internal/v1/delegations",
                token("keycloak-subject-1"),
                "assessment.learning.read".equals(capability)
                    ? "{\"audience\":\"S07\",\"capability_ids\":[\"assessment.learning.read\"]}"
                    : """
                {"audience":"S07","service_id":"svc-1","capability_ids":["%s"]}
                """.formatted(capability));
        assertEquals(200, response.statusCode(), response.body());
        String accessToken =
            response.body().replaceFirst("^.*\"access_token\":\"([^\"]+)\".*$", "$1");
        String claims = new String(
            java.util.Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]),
            java.nio.charset.StandardCharsets.UTF_8);
        assertThat(claims).contains("\"capabilities\":[\"" + capability + "\"]");
        if ("assessment.learning.read".equals(capability)) {
          assertThat(claims).contains("\"service_scope\":\"*\"");
        }
      }

    } finally {
      jdbc.update(
          "DELETE FROM uacrf_identity.membership WHERE principal_id='admin-test' "
              + "AND capability IN ('assessment.model.read','assessment.model.write',"
              + "'assessment.model.review','assessment.incident.read',"
              + "'assessment.incident.write','assessment.learning.write','assessment.learning.read')");
    }
  }

  @Test
  void authorizationPolicyCapabilitiesRequireTheExistingGrantFlow() throws Exception {
    List<String> policyCapabilities =
        List.of("authorization.policy.read", "authorization.policy.write");
    String context = "?tenant_id=tenant-test&environment_id=environment-test";
    String expiresAt =
        Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    String membershipIdRead = UUID.randomUUID().toString();
    String membershipIdWrite = UUID.randomUUID().toString();
    seedScopedMemberships();
    seedGrantParticipant("decision-policy-target");
    try {
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT count(*) FROM uacrf_identity.membership "
                  + "WHERE principal_id='admin-test' AND capability IN (?,?) AND revoked_at IS NULL",
              Integer.class,
              policyCapabilities.get(0),
              policyCapabilities.get(1)));
      bootstrap.run(new DefaultApplicationArguments(new String[0]));
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT count(*) FROM uacrf_identity.membership "
                  + "WHERE principal_id='admin-test' AND capability IN (?,?) AND revoked_at IS NULL",
              Integer.class,
              policyCapabilities.get(0),
              policyCapabilities.get(1)));

      for (int index = 0; index < policyCapabilities.size(); index++) {
        String capability = policyCapabilities.get(index);
        String decisionId = "decision-policy-grant-" + index;
        String membershipId = index == 0 ? membershipIdRead : membershipIdWrite;
        String body =
            membershipBody(
                membershipId, "decision-policy-target", capability, expiresAt, decisionId);
        assertEquals(
            403,
            post("/v1/memberships" + context, token("keycloak-subject-1"), body,
                    "decision-policy-unapproved-" + index)
                .statusCode());
        assertEquals(
            201,
            post(
                    "/v1/grant-decisions" + context,
                    token("keycloak-subject-approver"),
                    decisionBody(decisionId, "decision-policy-target", capability, expiresAt))
                .statusCode());
        assertEquals(
            201,
            post(
                    "/v1/memberships" + context,
                    token("keycloak-subject-1"),
                    body,
                    "decision-policy-approved-" + index)
                .statusCode());
      }

      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT count(*) FROM uacrf_identity.membership "
                  + "WHERE principal_id='decision-policy-target' "
                  + "AND capability IN (?,?) AND revoked_at IS NULL",
              Integer.class,
              policyCapabilities.get(0),
              policyCapabilities.get(1)));
      HttpResponse<String> delegation =
          post(
              "/internal/v1/delegations",
              token("decision-policy-target-subject"),
              """
              {"audience":"S10","capability_ids":[
                "authorization.policy.read","authorization.policy.write"]}
              """);
      assertEquals(200, delegation.statusCode(), delegation.body());
      String accessToken =
          delegation.body().replaceFirst("^.*\"access_token\":\"([^\"]+)\".*$", "$1");
      assertThat(decodeDelegation(accessToken, "S10").getAudience()).containsExactly("S10");
      assertThat(decodeDelegation(accessToken, "S10").getClaimAsStringList("capabilities"))
          .containsExactlyInAnyOrderElementsOf(policyCapabilities);
    } finally {
      jdbc.update("DELETE FROM uacrf_identity.audit_event WHERE principal_id='decision-policy-target'");
      jdbc.update(
          "DELETE FROM uacrf_identity.outbox_event WHERE aggregate_id IN "
              + "(SELECT id::text FROM uacrf_identity.membership "
              + "WHERE principal_id='decision-policy-target')");
      jdbc.update(
          "DELETE FROM uacrf_identity.idempotency_record "
              + "WHERE idempotency_key LIKE 'decision-policy-%'");
      jdbc.update(
          "UPDATE uacrf_identity.grant_decision SET consumed_at=NULL, consumed_membership_id=NULL "
              + "WHERE id IN ('decision-policy-grant-0','decision-policy-grant-1')");
      jdbc.update(
          "DELETE FROM uacrf_identity.membership WHERE principal_id='decision-policy-target'");
      jdbc.update(
          "DELETE FROM uacrf_identity.grant_decision "
              + "WHERE id IN ('decision-policy-grant-0','decision-policy-grant-1')");
      jdbc.update("DELETE FROM uacrf_identity.principal WHERE id='decision-policy-target'");
    }
  }

  @Test
  void learningReviewCapabilityRequiresHumanAndGlobalScope() throws Exception {
    String capability = "assessment.learning.review";
    String context = "?tenant_id=tenant-test&environment_id=environment-test";
    String expiresAt = Instant.now().plusSeconds(86400)
        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    String decision = "learning-review-test-decision";
    try {
      jdbc.update("INSERT INTO uacrf_identity.principal "
          + "(id,issuer_uri,subject,display_name,kind,enabled,token_valid_after,revision) "
          + "VALUES ('learning-review-system',?,'learning-review-system-subject','System',"
          + "'SYSTEM',true,now() - interval '1 minute',1),"
          + "('learning-review-agent',?,'learning-review-agent-subject','Agent',"
          + "'AGENT',true,now() - interval '1 minute',1)", ISSUER, ISSUER);
      String approver = token("keycloak-subject-approver");
      String scoped = decisionBody("learning-review-scoped", "admin-test", capability, expiresAt)
          .replace("\"service_scope\":\"*\"", "\"service_scope\":\"svc-1\"");
      assertEquals(422, post("/v1/grant-decisions" + context, approver, scoped).statusCode());
      assertEquals(422, post("/v1/grant-decisions" + context, approver,
          decisionBody("learning-review-system-decision", "learning-review-system",
              capability, expiresAt)).statusCode());
      assertEquals(422, post("/v1/grant-decisions" + context, approver,
          decisionBody("learning-review-agent-decision", "learning-review-agent",
              capability, expiresAt)).statusCode());
      assertEquals(201, post("/v1/grant-decisions" + context, approver,
          decisionBody(decision, "admin-test", capability, expiresAt)).statusCode());
      for (String principal : List.of("admin-test", "learning-review-system", "learning-review-agent")) {
        jdbc.update("INSERT INTO uacrf_identity.membership "
            + "(id,tenant_id,environment_id,principal_id,capability,service_scope,expires_at,revision) "
            + "VALUES (?,'tenant-test','environment-test',?,?,'*','2099-12-31T23:59:59Z',1)",
            UUID.randomUUID(), principal, capability);
      }
      String request = "{\"audience\":\"S07\",\"capability_ids\":[\"assessment.learning.review\"]}";
      assertEquals(200, post("/internal/v1/delegations", token("keycloak-subject-1"), request).statusCode());
      assertEquals(403, post("/internal/v1/delegations",
          token("learning-review-system-subject"), request).statusCode());
      assertEquals(403, post("/internal/v1/delegations",
          token("learning-review-agent-subject"), request).statusCode());
    } finally {
      jdbc.update("DELETE FROM uacrf_identity.membership WHERE capability=? "
          + "AND principal_id IN ('admin-test','learning-review-system','learning-review-agent')", capability);
      jdbc.update("DELETE FROM uacrf_identity.grant_decision WHERE id=?", decision);
      jdbc.update("DELETE FROM uacrf_identity.principal "
          + "WHERE id IN ('learning-review-system','learning-review-agent')");
    }
  }

  @Test
  void policyReviewCapabilityRequiresHumanAndExactServiceScope() throws Exception {
    String capability = "authorization.policy.review";
    String context = "?tenant_id=tenant-test&environment_id=environment-test";
    String expiresAt = Instant.now().plusSeconds(86400)
        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    String decision = "policy-review-test-decision";
    try {
      seedGrantParticipant("policy-review-human");
      jdbc.update("INSERT INTO uacrf_identity.principal "
          + "(id,issuer_uri,subject,display_name,kind,enabled,token_valid_after,revision) "
          + "VALUES ('policy-review-system',?,'policy-review-system-subject','System',"
          + "'SYSTEM',true,now() - interval '1 minute',1)", ISSUER);
      String approver = token("keycloak-subject-approver");
      assertEquals(422, post("/v1/grant-decisions" + context, approver,
          decisionBody("policy-review-global", "policy-review-human", capability, expiresAt)).statusCode());
      String scoped = decisionBody(decision, "policy-review-human", capability, expiresAt)
          .replace("\"service_scope\":\"*\"", "\"service_scope\":\"svc-1\"");
      assertEquals(422, post("/v1/grant-decisions" + context, approver,
          decisionBody("policy-review-system-decision", "policy-review-system", capability, expiresAt)
              .replace("\"service_scope\":\"*\"", "\"service_scope\":\"svc-1\"")).statusCode());
      assertEquals(201, post("/v1/grant-decisions" + context, approver, scoped).statusCode());
      String membershipId = "00000000-0000-4000-8000-000000000333";
      String body = membershipBody(membershipId, "policy-review-human", capability, expiresAt, decision)
          .replace("\"service_scope\":\"*\"", "\"service_scope\":\"svc-1\"");
      assertEquals(201, post("/v1/memberships" + context, token("keycloak-subject-1"), body,
          "policy-review-key").statusCode());
      jdbc.update("INSERT INTO uacrf_identity.membership "
          + "(id,tenant_id,environment_id,principal_id,capability,service_scope,expires_at,revision) "
          + "VALUES (?,'tenant-test','environment-test','policy-review-system',?,'svc-1',"
          + "'2099-12-31T23:59:59Z',1)", UUID.randomUUID(), capability);
      String request = "{\"audience\":\"S10\",\"service_id\":\"svc-1\","
          + "\"capability_ids\":[\"authorization.policy.review\"]}";
      HttpResponse<String> allowed =
          post("/internal/v1/delegations", token("policy-review-human-subject"), request);
      assertEquals(200, allowed.statusCode(), allowed.body());
      String accessToken =
          allowed.body().replaceFirst("^.*\"access_token\":\"([^\"]+)\".*$", "$1");
      assertThat(decodeDelegation(accessToken, "S10").getClaimAsStringList("capabilities"))
          .containsExactly(capability);
      assertEquals(403, post("/internal/v1/delegations", token("policy-review-human-subject"),
          request.replace("svc-1", "svc-2")).statusCode());
      assertEquals(403, post("/internal/v1/delegations",
          token("policy-review-system-subject"), request).statusCode());
    } finally {
      jdbc.update("DELETE FROM uacrf_identity.audit_event WHERE principal_id IN ('policy-review-human','policy-review-system')");
      jdbc.update("DELETE FROM uacrf_identity.outbox_event WHERE aggregate_id IN "
          + "(SELECT id::text FROM uacrf_identity.membership WHERE principal_id IN ('policy-review-human','policy-review-system'))");
      jdbc.update("DELETE FROM uacrf_identity.idempotency_record WHERE idempotency_key='policy-review-key'");
      jdbc.update("UPDATE uacrf_identity.grant_decision SET consumed_at=NULL, consumed_membership_id=NULL "
          + "WHERE id=?", decision);
      jdbc.update("DELETE FROM uacrf_identity.membership "
          + "WHERE principal_id IN ('policy-review-human','policy-review-system')");
      jdbc.update("DELETE FROM uacrf_identity.grant_decision WHERE id=?", decision);
      jdbc.update("DELETE FROM uacrf_identity.principal WHERE id IN ('policy-review-human','policy-review-system')");
    }
  }

  @Test
  void qualificationPolicyReviewCapabilityRequiresHumanAndExactServiceScope() throws Exception {
    String capability = "evidence.qualification.policy.review";
    String context = "?tenant_id=tenant-test&environment_id=environment-test";
    String expiresAt =
        Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    String decision = "qualification-policy-review-test-decision";
    try {
      seedGrantParticipant("qualification-policy-review-human");
      jdbc.update(
          "INSERT INTO uacrf_identity.principal "
              + "(id,issuer_uri,subject,display_name,kind,enabled,token_valid_after,revision) "
              + "VALUES ('qualification-policy-review-system',?,'qualification-policy-review-system-subject',"
              + "'System','SYSTEM',true,now() - interval '1 minute',1)",
          ISSUER);
      String approver = token("keycloak-subject-approver");
      assertEquals(
          422,
          post(
                  "/v1/grant-decisions" + context,
                  approver,
                  decisionBody(
                      "qualification-policy-review-global",
                      "qualification-policy-review-human",
                      capability,
                      expiresAt))
              .statusCode());
      assertEquals(
          422,
          post(
                  "/v1/grant-decisions" + context,
                  approver,
                  decisionBody(
                          "qualification-policy-review-system-decision",
                          "qualification-policy-review-system",
                          capability,
                          expiresAt)
                      .replace("\"service_scope\":\"*\"", "\"service_scope\":\"svc-1\""))
              .statusCode());
      String scopedDecision =
          decisionBody(decision, "qualification-policy-review-human", capability, expiresAt)
              .replace("\"service_scope\":\"*\"", "\"service_scope\":\"svc-1\"");
      assertEquals(
          201,
          post("/v1/grant-decisions" + context, approver, scopedDecision).statusCode());
      String membershipId = "00000000-0000-4000-8000-000000000334";
      String membership =
          membershipBody(
                  membershipId,
                  "qualification-policy-review-human",
                  capability,
                  expiresAt,
                  decision)
              .replace("\"service_scope\":\"*\"", "\"service_scope\":\"svc-1\"");
      assertEquals(
          201,
          post(
                  "/v1/memberships" + context,
                  token("keycloak-subject-1"),
                  membership,
                  "qualification-policy-review-key")
              .statusCode());
      String request =
          "{\"audience\":\"S04\",\"service_id\":\"svc-1\","
              + "\"capability_ids\":[\"evidence.qualification.policy.review\"]}";
      HttpResponse<String> allowed =
          post(
              "/internal/v1/delegations",
              token("qualification-policy-review-human-subject"),
              request);
      assertEquals(200, allowed.statusCode(), allowed.body());
      String accessToken =
          allowed.body().replaceFirst("^.*\"access_token\":\"([^\"]+)\".*$", "$1");
      assertThat(decodeDelegation(accessToken, "S04").getClaimAsStringList("capabilities"))
          .containsExactly(capability);
      assertEquals(
          403,
          post(
                  "/internal/v1/delegations",
                  token("qualification-policy-review-human-subject"),
                  request.replace("svc-1", "svc-2"))
              .statusCode());
      assertEquals(
          403,
          post(
                  "/internal/v1/delegations",
                  token("qualification-policy-review-system-subject"),
                  request)
              .statusCode());
    } finally {
      jdbc.update(
          "DELETE FROM uacrf_identity.audit_event WHERE principal_id IN "
              + "('qualification-policy-review-human','qualification-policy-review-system')");
      jdbc.update(
          "DELETE FROM uacrf_identity.outbox_event WHERE aggregate_id IN "
              + "(SELECT id::text FROM uacrf_identity.membership WHERE principal_id "
              + "IN ('qualification-policy-review-human','qualification-policy-review-system'))");
      jdbc.update(
          "DELETE FROM uacrf_identity.idempotency_record "
              + "WHERE idempotency_key='qualification-policy-review-key'");
      jdbc.update(
          "UPDATE uacrf_identity.grant_decision SET consumed_at=NULL, consumed_membership_id=NULL "
              + "WHERE id=?",
          decision);
      jdbc.update(
          "DELETE FROM uacrf_identity.membership WHERE principal_id IN "
              + "('qualification-policy-review-human','qualification-policy-review-system')");
      jdbc.update("DELETE FROM uacrf_identity.grant_decision WHERE id=?", decision);
      jdbc.update(
          "DELETE FROM uacrf_identity.principal WHERE id IN "
              + "('qualification-policy-review-human','qualification-policy-review-system')");
    }
  }

  @Test
  void qualificationWaiverCapabilitiesRequireExactServiceScopeAndHumanReviewers() throws Exception {
    String context = "?tenant_id=tenant-test&environment_id=environment-test";
    String expiresAt =
        Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    String humanId = "waiver-capability-human";
    String systemId = "waiver-capability-system";
    List<String> capabilities =
        List.of(
            "evidence.qualification.waiver.read",
            "evidence.qualification.waiver.write",
            "evidence.qualification.waiver.review",
            "evidence.qualification.waiver.revoke");
    try {
      seedGrantParticipant(humanId);
      jdbc.update(
          "INSERT INTO uacrf_identity.principal "
              + "(id,issuer_uri,subject,display_name,kind,enabled,token_valid_after,revision) "
              + "VALUES (?,?,'waiver-capability-system-subject','System','SYSTEM',true,"
              + "now() - interval '1 minute',1)",
          systemId,
          ISSUER);
      String approver = token("keycloak-subject-approver");
      for (int index = 0; index < capabilities.size(); index++) {
        String capability = capabilities.get(index);
        assertEquals(
            422,
            post(
                    "/v1/grant-decisions" + context,
                    approver,
                    decisionBody(
                        "waiver-capability-global-" + index, humanId, capability, expiresAt))
                .statusCode(),
            capability);

        String decisionId = "waiver-capability-decision-" + index;
        String scopedDecision =
            decisionBody(decisionId, humanId, capability, expiresAt)
                .replace("\"service_scope\":\"*\"", "\"service_scope\":\"svc-1\"");
        HttpResponse<String> decision =
            post("/v1/grant-decisions" + context, approver, scopedDecision);
        assertEquals(201, decision.statusCode(), decision.body());
        String membershipId = UUID.randomUUID().toString();
        String membership =
            membershipBody(membershipId, humanId, capability, expiresAt, decisionId)
                .replace("\"service_scope\":\"*\"", "\"service_scope\":\"svc-1\"");
        HttpResponse<String> granted =
            post(
                "/v1/memberships" + context,
                token("keycloak-subject-1"),
                membership,
                "waiver-capability-key-" + index);
        assertEquals(201, granted.statusCode(), granted.body());
      }

      String wrongKindDecision =
          decisionBody(
                  "waiver-capability-system-decision",
                  systemId,
                  "evidence.qualification.waiver.review",
                  expiresAt)
              .replace("\"service_scope\":\"*\"", "\"service_scope\":\"svc-1\"");
      assertEquals(
          422,
          post("/v1/grant-decisions" + context, approver, wrongKindDecision).statusCode());

      for (String capability : capabilities) {
        String request =
            "{\"audience\":\"S04\",\"service_id\":\"svc-1\","
                + "\"capability_ids\":[\""
                + capability
                + "\"]}";
        HttpResponse<String> allowed =
            post("/internal/v1/delegations", token(humanId + "-subject"), request);
        assertEquals(200, allowed.statusCode(), allowed.body());
        String accessToken =
            allowed.body().replaceFirst("^.*\"access_token\":\"([^\"]+)\".*$", "$1");
        assertThat(decodeDelegation(accessToken, "S04").getClaimAsStringList("capabilities"))
            .containsExactly(capability);
        assertEquals(
            403,
            post(
                    "/internal/v1/delegations",
                    token(humanId + "-subject"),
                    request.replace("svc-1", "svc-2"))
                .statusCode(),
            capability);
      }
    } finally {
      jdbc.update(
          "DELETE FROM uacrf_identity.audit_event WHERE principal_id IN (?, ?)", humanId, systemId);
      jdbc.update(
          "DELETE FROM uacrf_identity.outbox_event WHERE aggregate_id IN "
              + "(SELECT id::text FROM uacrf_identity.membership WHERE principal_id IN (?, ?))",
          humanId,
          systemId);
      jdbc.update(
          "DELETE FROM uacrf_identity.idempotency_record "
              + "WHERE idempotency_key LIKE 'waiver-capability-key-%'");
      jdbc.update(
          "UPDATE uacrf_identity.grant_decision SET consumed_at=NULL, consumed_membership_id=NULL "
              + "WHERE id LIKE 'waiver-capability-decision-%'");
      jdbc.update(
          "DELETE FROM uacrf_identity.membership WHERE principal_id IN (?, ?)", humanId, systemId);
      jdbc.update(
          "DELETE FROM uacrf_identity.grant_decision WHERE id LIKE 'waiver-capability-%'");
      jdbc.update(
          "DELETE FROM uacrf_identity.principal WHERE id IN (?, ?)", humanId, systemId);
    }
  }

  @Test
  void objectiveReviewCapabilityRequiresHumanAndGlobalScope() throws Exception {
    String capability = "objectives.objective.review";
    String context = "?tenant_id=tenant-test&environment_id=environment-test";
    String expiresAt = Instant.now().plusSeconds(86400)
        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    String decision = "objective-review-test-decision";
    String membershipId = "00000000-0000-4000-8000-000000000222";
    try {
      jdbc.update("INSERT INTO uacrf_identity.principal "
          + "(id,issuer_uri,subject,display_name,kind,enabled,token_valid_after,revision) "
          + "VALUES ('objective-review-human',?,'objective-review-human-subject','Human',"
          + "'HUMAN',true,now() - interval '1 minute',1),"
          + "('objective-review-system',?,'objective-review-system-subject','System',"
          + "'SYSTEM',true,now() - interval '1 minute',1),"
          + "('objective-review-agent',?,'objective-review-agent-subject','Agent',"
          + "'AGENT',true,now() - interval '1 minute',1)", ISSUER, ISSUER, ISSUER);
      String approver = token("keycloak-subject-approver");
      String scoped = decisionBody("objective-review-scoped", "objective-review-human", capability, expiresAt)
          .replace("\"service_scope\":\"*\"", "\"service_scope\":\"svc-1\"");
      assertEquals(422, post("/v1/grant-decisions" + context, approver, scoped).statusCode());
      assertEquals(422, post("/v1/grant-decisions" + context, approver,
          decisionBody("objective-review-system-decision", "objective-review-system",
              capability, expiresAt)).statusCode());
      assertEquals(422, post("/v1/grant-decisions" + context, approver,
          decisionBody("objective-review-agent-decision", "objective-review-agent",
              capability, expiresAt)).statusCode());
      String request = "{\"audience\":\"S05\",\"capability_ids\":[\"objectives.objective.review\"]}";
      jdbc.update("INSERT INTO uacrf_identity.membership "
          + "(id,tenant_id,environment_id,principal_id,capability,service_scope,expires_at,revision) "
          + "VALUES (?,'tenant-test','environment-test','objective-review-human',?,'svc-1',"
          + "'2099-12-31T23:59:59Z',1)", UUID.randomUUID(), capability);
      assertEquals(403, post("/internal/v1/delegations",
          token("objective-review-human-subject"), request).statusCode());
      assertEquals(201, post("/v1/grant-decisions" + context, approver,
          decisionBody(decision, "objective-review-human", capability, expiresAt)).statusCode());
      assertEquals(201, post("/v1/memberships" + context, token("keycloak-subject-1"),
          membershipBody(membershipId, "objective-review-human", capability, expiresAt, decision),
          "objective-review-grant").statusCode());
      for (String principal : List.of("objective-review-system", "objective-review-agent")) {
        jdbc.update("INSERT INTO uacrf_identity.membership "
            + "(id,tenant_id,environment_id,principal_id,capability,service_scope,expires_at,revision) "
            + "VALUES (?,'tenant-test','environment-test',?,?,'*','2099-12-31T23:59:59Z',1)",
            UUID.randomUUID(), principal, capability);
      }
      assertEquals(200, post("/internal/v1/delegations",
          token("objective-review-human-subject"), request).statusCode());
      assertEquals(403, post("/internal/v1/delegations",
          token("objective-review-system-subject"), request).statusCode());
      assertEquals(403, post("/internal/v1/delegations",
          token("objective-review-agent-subject"), request).statusCode());
    } finally {
      jdbc.update("UPDATE uacrf_identity.grant_decision SET consumed_at = NULL, consumed_membership_id = NULL "
          + "WHERE id = ?", decision);
      jdbc.update("DELETE FROM uacrf_identity.audit_event WHERE principal_id = 'objective-review-human'");
      jdbc.update("DELETE FROM uacrf_identity.outbox_event WHERE aggregate_id IN "
          + "(SELECT id::text FROM uacrf_identity.membership WHERE principal_id = 'objective-review-human')");
      jdbc.update("DELETE FROM uacrf_identity.idempotency_record WHERE idempotency_key = 'objective-review-grant'");
      jdbc.update("DELETE FROM uacrf_identity.membership WHERE principal_id IN "
          + "('objective-review-human','objective-review-system','objective-review-agent')");
      jdbc.update("DELETE FROM uacrf_identity.grant_decision WHERE id IN (?,?,?,?)", decision,
          "objective-review-scoped", "objective-review-system-decision", "objective-review-agent-decision");
      jdbc.update("DELETE FROM uacrf_identity.principal "
          + "WHERE id IN ('objective-review-human','objective-review-system','objective-review-agent')");
    }
  }

  @Test
  void objectiveReviewAuthorityCheckRequiresCurrentSystemAndHumanAuthority() throws Exception {
    String path = "/internal/v1/objective-review-authority:check";
    String body = """
        {"tenant_id":"tenant-test","environment_id":"environment-test","reviewer_id":"authority-human"}
        """;
    String capability = "objectives.review-authority.check";
    String expiresAt = Instant.now().plusSeconds(86400)
        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    String membershipId = "00000000-0000-4000-8000-000000000223";
    try {
      jdbc.update("INSERT INTO uacrf_identity.principal "
          + "(id,issuer_uri,subject,display_name,kind,enabled,token_valid_after,revision) "
          + "VALUES ('authority-system',?,'authority-system-subject','System',"
          + "'SYSTEM',true,now() - interval '1 minute',1),"
          + "('authority-human',?,'authority-human-subject','Human',"
          + "'HUMAN',true,now() - interval '1 minute',1),"
          + "('authority-agent',?,'authority-agent-subject','Agent',"
          + "'AGENT',true,now() - interval '1 minute',1)",
          ISSUER, ISSUER, ISSUER);
      for (String principal : List.of("authority-system", "authority-human")) {
        jdbc.update("INSERT INTO uacrf_identity.membership "
            + "(id,tenant_id,environment_id,principal_id,capability,service_scope,expires_at,revision) "
            + "VALUES (?,'tenant-test','environment-test',?,'catalog.service.read','*',"
            + "'2099-12-31T23:59:59Z',1)", UUID.randomUUID(), principal);
      }
      String system = token("authority-system-subject");
      String human = token("authority-human-subject");
      assertEquals(401, post(path, "Bearer invalid", body).statusCode());
      assertEquals(403, post(path, system, body).statusCode());
      String approver = token("keycloak-subject-approver");
      String context = "?tenant_id=tenant-test&environment_id=environment-test";
      assertEquals(422, post("/v1/grant-decisions" + context, approver,
          decisionBody("authority-human-grant", "authority-human", capability, expiresAt)).statusCode());
      assertEquals(422, post("/v1/grant-decisions" + context, approver,
          decisionBody("authority-agent-grant", "authority-agent", capability, expiresAt)).statusCode());
      String scoped = decisionBody("authority-scoped-grant", "authority-system", capability, expiresAt)
          .replace("\"service_scope\":\"*\"", "\"service_scope\":\"svc-1\"");
      assertEquals(422, post("/v1/grant-decisions" + context, approver, scoped).statusCode());
      assertEquals(201, post("/v1/grant-decisions" + context, approver,
          decisionBody("authority-system-grant", "authority-system", capability, expiresAt)).statusCode());
      UUID checkGrant = UUID.randomUUID();
      jdbc.update("INSERT INTO uacrf_identity.membership "
          + "(id,tenant_id,environment_id,principal_id,capability,service_scope,expires_at,revision) "
          + "VALUES (?,'tenant-test','environment-test','authority-system',?,'svc-1',"
          + "'2099-12-31T23:59:59Z',1)", checkGrant, capability);
      assertEquals(403, post(path, system, body).statusCode());
      jdbc.update("DELETE FROM uacrf_identity.membership WHERE id=?", checkGrant);
      assertEquals(201, post("/v1/memberships" + context, token("keycloak-subject-1"),
          membershipBody(membershipId, "authority-system", capability, expiresAt, "authority-system-grant"),
          "objective-authority-grant").statusCode());
      assertEquals(403, post("/internal/v1/delegations", system,
          "{\"audience\":\"S05\",\"capability_ids\":[\"objectives.review-authority.check\"]}").statusCode());
      jdbc.update("INSERT INTO uacrf_identity.membership "
          + "(id,tenant_id,environment_id,principal_id,capability,service_scope,expires_at,revision) "
          + "VALUES (?,'tenant-test','environment-test','authority-human',?,'*',"
          + "'2099-12-31T23:59:59Z',1)", UUID.randomUUID(), capability);
      assertEquals(403, post(path, human, body).statusCode());
      assertEquals(403, post(path, system, body).statusCode());
      UUID reviewerGrant = UUID.randomUUID();
      jdbc.update("INSERT INTO uacrf_identity.membership "
          + "(id,tenant_id,environment_id,principal_id,capability,service_scope,expires_at,revision) "
          + "VALUES (?,'tenant-test','environment-test','authority-human','objectives.objective.review','*',"
          + "'2099-12-31T23:59:59Z',1)", reviewerGrant);
      HttpResponse<String> success = post(path, system, body);
      assertEquals(200, success.statusCode(), success.body());
      assertThat(success.body()).contains("\"reviewer_id\":\"authority-human\"",
          "\"tenant_id\":\"tenant-test\"", "\"environment_id\":\"environment-test\"",
          "\"status\":\"AUTHORIZED\"", "\"checked_at\":\"");
      String checkedAt = success.body().replaceFirst("^.*\"checked_at\":\"([^\"]+)\".*$", "$1");
      assertThat(Instant.parse(checkedAt)).isBeforeOrEqualTo(Instant.now().plusSeconds(5));
      assertEquals(403, post(path, system, body.replace("authority-human", "authority-system")).statusCode());
      assertEquals(403, post(path, system, body.replace("environment-test", "environment-other")).statusCode());
      assertEquals(400, post(path, system, body.replace("authority-human", "../invalid")).statusCode());
      jdbc.update("INSERT INTO uacrf_identity.tenant (id,display_name,status,revision) "
          + "VALUES ('authority-other-tenant','Other tenant','ACTIVE',1)");
      jdbc.update("INSERT INTO uacrf_identity.environment (tenant_id,id,display_name,status,revision) "
          + "VALUES ('authority-other-tenant','authority-other-env','Other environment','ACTIVE',1)");
      jdbc.update("INSERT INTO uacrf_identity.membership "
          + "(id,tenant_id,environment_id,principal_id,capability,service_scope,expires_at,revision) "
          + "VALUES (?,'authority-other-tenant','authority-other-env','authority-system',?,'*',"
          + "'2099-12-31T23:59:59Z',1)", UUID.randomUUID(), capability);
      String otherContext = body.replace("tenant-test", "authority-other-tenant")
          .replace("environment-test", "authority-other-env");
      assertEquals(403, post(path, system, otherContext).statusCode());
      jdbc.update("UPDATE uacrf_identity.membership SET service_scope='svc-1' WHERE id=?", reviewerGrant);
      assertEquals(403, post(path, system, body).statusCode());
      jdbc.update("UPDATE uacrf_identity.membership SET service_scope='*' WHERE id=?", reviewerGrant);
      jdbc.update("UPDATE uacrf_identity.membership SET revoked_at=now() WHERE id=?", reviewerGrant);
      assertEquals(403, post(path, system, body).statusCode());
      jdbc.update("UPDATE uacrf_identity.membership SET revoked_at=NULL, expires_at=now() - interval '1 second' "
          + "WHERE id=?", reviewerGrant);
      assertEquals(403, post(path, system, body).statusCode());
      jdbc.update("UPDATE uacrf_identity.membership SET expires_at='2099-12-31T23:59:59Z' WHERE id=?", reviewerGrant);
      jdbc.update("UPDATE uacrf_identity.principal SET enabled=false WHERE id='authority-human'");
      assertEquals(403, post(path, system, body).statusCode());
      jdbc.update("UPDATE uacrf_identity.principal SET enabled=true WHERE id='authority-human'");
      jdbc.update("UPDATE uacrf_identity.membership SET revoked_at=now() WHERE id=?", UUID.fromString(membershipId));
      assertEquals(403, post(path, system, body).statusCode());
    } finally {
      jdbc.update("UPDATE uacrf_identity.grant_decision SET consumed_at = NULL, consumed_membership_id = NULL "
          + "WHERE id = 'authority-system-grant'");
      jdbc.update("DELETE FROM uacrf_identity.audit_event WHERE principal_id = 'authority-system'");
      jdbc.update("DELETE FROM uacrf_identity.outbox_event WHERE aggregate_id IN "
          + "(SELECT id::text FROM uacrf_identity.membership WHERE principal_id = 'authority-system')");
      jdbc.update("DELETE FROM uacrf_identity.idempotency_record WHERE idempotency_key = 'objective-authority-grant'");
      jdbc.update("DELETE FROM uacrf_identity.membership WHERE principal_id IN ('authority-system','authority-human')");
      jdbc.update("DELETE FROM uacrf_identity.grant_decision WHERE id IN "
          + "('authority-human-grant','authority-agent-grant','authority-scoped-grant','authority-system-grant')");
      jdbc.update("DELETE FROM uacrf_identity.principal "
          + "WHERE id IN ('authority-system','authority-human','authority-agent')");
      jdbc.update("DELETE FROM uacrf_identity.environment WHERE tenant_id='authority-other-tenant'");
      jdbc.update("DELETE FROM uacrf_identity.tenant WHERE id='authority-other-tenant'");
    }
  }

  @Test
  void knowledgeCapabilitiesAreDelegableToS08OnlyForHumanMemberships() throws Exception {
    List<String> knowledgeCapabilities =
        List.of(
            "knowledge.item.read",
            "knowledge.item.write",
            "knowledge.item.review",
            "knowledge.retrieval.run",
            "knowledge.retrieval.read",
            "knowledge.item.withdraw");
    try {
      for (String capability : knowledgeCapabilities) {
        jdbc.update(
            "INSERT INTO uacrf_identity.membership "
                + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
                + "expires_at, revision) VALUES (?, 'tenant-test', 'environment-test', "
                + "'admin-test', ?, '*', '2099-12-31T23:59:59Z', 1)",
            UUID.randomUUID(),
            capability);
      }

      HttpResponse<String> delegation =
          post(
              "/internal/v1/delegations",
              token("keycloak-subject-1"),
              """
              {"audience":"S08","capability_ids":[
                "knowledge.item.read","knowledge.item.write","knowledge.item.review",
                "knowledge.retrieval.run","knowledge.retrieval.read","knowledge.item.withdraw"]}
              """);
      assertEquals(200, delegation.statusCode(), delegation.body());
      assertThat(delegation.body()).contains("\"expires_in\":30");

      jdbc.update(
          "INSERT INTO uacrf_identity.principal "
              + "(id, issuer_uri, subject, display_name, kind, enabled, token_valid_after, revision) "
              + "VALUES ('knowledge-system', ?, 'knowledge-system-subject', 'Knowledge system', "
              + "'SYSTEM', true, now(), 1) ON CONFLICT (id) DO NOTHING",
          ISSUER);
      jdbc.update(
          "INSERT INTO uacrf_identity.membership "
              + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
              + "expires_at, revision) VALUES (?, 'tenant-test', 'environment-test', "
              + "'knowledge-system', 'knowledge.item.review', '*', '2099-12-31T23:59:59Z', 1)",
          UUID.randomUUID());
      HttpResponse<String> systemReviewer =
          post(
              "/internal/v1/delegations",
              token("knowledge-system-subject"),
              """
              {"audience":"S08","capability_ids":["knowledge.item.review"]}
              """);
      assertEquals(403, systemReviewer.statusCode(), systemReviewer.body());
    } finally {
      jdbc.update(
          "DELETE FROM uacrf_identity.membership WHERE principal_id = 'admin-test' "
              + "AND capability IN ('knowledge.item.read','knowledge.item.write',"
              + "'knowledge.item.review','knowledge.retrieval.run',"
              + "'knowledge.retrieval.read','knowledge.item.withdraw')");
      jdbc.update("DELETE FROM uacrf_identity.membership WHERE principal_id = 'knowledge-system'");
      jdbc.update("DELETE FROM uacrf_identity.principal WHERE id = 'knowledge-system'");
    }
  }

  @Test
  void bootstrapsASeparateApproverWhoseOnlyPowerIsApprovingGrants() throws Exception {
    assertThat(jdbc.queryForList(
            "SELECT capability FROM uacrf_identity.membership "
                + "WHERE principal_id = 'bootstrap-approver' ORDER BY capability",
            String.class))
        .containsExactly("identity.directory.read", "identity.grant.approve");
    assertThat(jdbc.queryForObject(
            "SELECT approver_principal_id FROM uacrf_identity.bootstrap_audit",
            String.class))
        .isEqualTo("bootstrap-approver");

    String context = "?tenant_id=tenant-test&environment_id=environment-test";
    seedGrantParticipant("bootstrap-target");
    try {
      HttpResponse<String> decision =
          post("/v1/grant-decisions" + context, token("keycloak-subject-approver"),
              decisionBody("bootstrap-decision-1", "bootstrap-target", "catalog.service.read",
                  Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()));
      assertEquals(201, decision.statusCode(), decision.body());
      assertEquals(
          403,
          post("/v1/memberships" + context, token("keycloak-subject-approver"),
                  membershipBody("00000000-0000-4000-8000-000000000301", "bootstrap-target",
                      "catalog.service.read", Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(),
                      "bootstrap-decision-1"),
                  "bootstrap-key-1")
              .statusCode());
    } finally {
      jdbc.update("DELETE FROM uacrf_identity.grant_decision WHERE id = 'bootstrap-decision-1'");
      jdbc.update("DELETE FROM uacrf_identity.idempotency_record WHERE idempotency_key = 'bootstrap-key-1'");
      jdbc.update("DELETE FROM uacrf_identity.membership WHERE principal_id = 'bootstrap-target'");
      jdbc.update("DELETE FROM uacrf_identity.principal WHERE id = 'bootstrap-target'");
    }
  }

  @Test
  void admitsPrincipalsOnlyFromVerifiedTrustedDirectoryEntries() throws Exception {
    String path = "/v1/principals?tenant_id=tenant-test&environment_id=environment-test";
    String admin = token("keycloak-subject-1");
    directory.entries.put(
        "dir-user-1", new TrustedDirectory.DirectoryPrincipal("dir-user-1", "Ada Admitted", "HUMAN", true));
    directory.entries.put(
        "dir-disabled", new TrustedDirectory.DirectoryPrincipal("dir-disabled", "Off", "HUMAN", false));
    String body =
        """
        {"id":"admitted-1","provider_id":"keycloak-local","directory_subject_ref":"dir-user-1",
         "reason":"Admit reviewer","expected_revision":0}
        """;
    try {
      assertEquals(
          403, post(path, token("not-a-principal"), body, "admit-key-0").statusCode());
      assertEquals(503, withDirectoryDown(path, admin, body).statusCode());

      HttpResponse<String> created = post(path, admin, body, "admit-key-1");
      assertEquals(201, created.statusCode(), created.body());
      assertThat(jdbc.queryForObject(
              "SELECT issuer_uri || '|' || subject || '|' || display_name || '|' || kind "
                  + "FROM uacrf_identity.principal WHERE id = 'admitted-1'",
              String.class))
          .isEqualTo(ISSUER + "|dir-user-1|Ada Admitted|HUMAN");
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT count(*) FROM uacrf_identity.membership WHERE principal_id = 'admitted-1'",
              Integer.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM uacrf_identity.principal_admission_audit "
                  + "WHERE principal_id = 'admitted-1'",
              Integer.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM uacrf_identity.outbox_event "
                  + "WHERE aggregate_id = 'admitted-1' AND event_type = 'identity.principal.admitted'",
              Integer.class));

      assertEquals(200, post(path, admin, body, "admit-key-1").statusCode());
      assertEquals(409, post(path, admin, body, "admit-key-2").statusCode());
      assertEquals(
          404,
          post(path, admin, body.replace("dir-user-1", "dir-missing").replace("admitted-1", "admitted-2"),
                  "admit-key-3")
              .statusCode());
      assertEquals(
          422,
          post(path, admin, body.replace("dir-user-1", "dir-disabled").replace("admitted-1", "admitted-3"),
                  "admit-key-4")
              .statusCode());
      assertEquals(
          404,
          post(path, admin, body.replace("keycloak-local", "unknown-provider").replace("admitted-1", "admitted-4"),
                  "admit-key-5")
              .statusCode());
    } finally {
      directory.entries.clear();
      directory.unavailable = false;
      jdbc.update("DELETE FROM uacrf_identity.principal_admission_audit WHERE principal_id LIKE 'admitted-%'");
      jdbc.update("DELETE FROM uacrf_identity.outbox_event WHERE aggregate_id LIKE 'admitted-%'");
      jdbc.update("DELETE FROM uacrf_identity.idempotency_record WHERE idempotency_key LIKE 'admit-key-%'");
      jdbc.update("DELETE FROM uacrf_identity.principal WHERE id LIKE 'admitted-%'");
    }
  }

  private HttpResponse<String> withDirectoryDown(String path, String admin, String body)
      throws Exception {
    directory.unavailable = true;
    try {
      return post(path, admin, body, "admit-key-down");
    } finally {
      directory.unavailable = false;
    }
  }

  @Test
  void createsMembershipsOnlyFromIndependentSingleUseGrantDecisions() throws Exception {
    seedScopedMemberships();
    seedGrantParticipant("approver-a", "identity.grant.approve");
    seedGrantParticipant("approver-b", "identity.grant.approve", "identity.membership.manage");
    seedGrantParticipant("grant-target");
    try {
      verifyIndependentGrantDecisions();
    } finally {
      removeGrantFixtures();
    }
  }

  private void verifyIndependentGrantDecisions() throws Exception {
    String context = "?tenant_id=tenant-test&environment_id=environment-test";
    String expiresAt = Instant.now().plusSeconds(30L * 86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    String membershipId = "00000000-0000-4000-8000-000000000201";

    HttpResponse<String> forged =
        post("/v1/memberships" + context, token("keycloak-subject-1"),
            membershipBody(membershipId, "grant-target", "catalog.service.read", expiresAt, "forged-ref"),
            "grant-key-forged");
    assertEquals(403, forged.statusCode(), forged.body());

    String decisionBody =
        decisionBody("grant-decision-1", "grant-target", "catalog.service.read", expiresAt);
    HttpResponse<String> notApprover =
        post("/v1/grant-decisions" + context, token("keycloak-subject-1"), decisionBody);
    assertEquals(403, notApprover.statusCode(), notApprover.body());
    HttpResponse<String> wildcard =
        post("/v1/grant-decisions" + context, token("approver-a-subject"),
            decisionBody("grant-decision-wild", "grant-target", "catalog.*", expiresAt));
    assertEquals(422, wildcard.statusCode(), wildcard.body());
    HttpResponse<String> unbounded =
        post("/v1/grant-decisions" + context, token("approver-a-subject"),
            decisionBody("grant-decision-long", "grant-target", "catalog.service.read",
                "2099-12-31T23:59:59Z"));
    assertEquals(422, unbounded.statusCode(), unbounded.body());
    HttpResponse<String> selfGrant =
        post("/v1/grant-decisions" + context, token("approver-a-subject"),
            decisionBody("grant-decision-self", "approver-a", "catalog.service.read", expiresAt));
    assertEquals(403, selfGrant.statusCode(), selfGrant.body());

    String ownerDecision =
        decisionBody("grant-decision-owner", "grant-target", "identity.owner.select", expiresAt);
    HttpResponse<String> scopedOwner =
        post("/v1/grant-decisions" + context, token("approver-a-subject"),
            ownerDecision.replace("\"service_scope\":\"*\"", "\"service_scope\":\"svc-1\""));
    assertEquals(422, scopedOwner.statusCode(), scopedOwner.body());
    assertThat(scopedOwner.body()).contains("INVALID_SCOPE");
    jdbc.update("UPDATE uacrf_identity.principal SET kind = 'SYSTEM' WHERE id = 'grant-target'");
    HttpResponse<String> systemOwner =
        post("/v1/grant-decisions" + context, token("approver-a-subject"), ownerDecision);
    assertEquals(422, systemOwner.statusCode(), systemOwner.body());
    assertThat(systemOwner.body()).contains("INVALID_PRINCIPAL_KIND");
    jdbc.update("UPDATE uacrf_identity.principal SET kind = 'AGENT' WHERE id = 'grant-target'");
    HttpResponse<String> agentOwner =
        post("/v1/grant-decisions" + context, token("approver-a-subject"), ownerDecision);
    assertEquals(422, agentOwner.statusCode(), agentOwner.body());
    assertThat(agentOwner.body()).contains("INVALID_PRINCIPAL_KIND");
    jdbc.update("UPDATE uacrf_identity.principal SET kind = 'HUMAN' WHERE id = 'grant-target'");
    HttpResponse<String> selfOwnerApproval =
        post("/v1/grant-decisions" + context, token("approver-a-subject"),
            decisionBody("grant-decision-owner-self", "approver-a", "identity.owner.select", expiresAt));
    assertEquals(403, selfOwnerApproval.statusCode(), selfOwnerApproval.body());
    HttpResponse<String> managerOwnerApproval =
        post("/v1/grant-decisions" + context, token("approver-a-subject"),
            decisionBody("grant-decision-owner-manager", "admin-test", "identity.owner.select", expiresAt));
    assertEquals(201, managerOwnerApproval.statusCode(), managerOwnerApproval.body());
    HttpResponse<String> managerSelfGrant =
        post("/v1/memberships" + context, token("keycloak-subject-1"),
            membershipBody("00000000-0000-4000-8000-000000000207", "admin-test",
                "identity.owner.select", expiresAt, "grant-decision-owner-manager"),
            "grant-key-owner-self");
    assertEquals(403, managerSelfGrant.statusCode(), managerSelfGrant.body());
    HttpResponse<String> ownerApproved =
        post("/v1/grant-decisions" + context, token("approver-a-subject"), ownerDecision);
    assertEquals(201, ownerApproved.statusCode(), ownerApproved.body());
    String ownerMembership =
        membershipBody("00000000-0000-4000-8000-000000000205", "grant-target",
            "identity.owner.select", expiresAt, "grant-decision-owner");
    HttpResponse<String> ownerScopedMembership =
        post("/v1/memberships" + context, token("keycloak-subject-1"),
            ownerMembership.replace("\"service_scope\":\"*\"", "\"service_scope\":\"svc-1\""),
            "grant-key-owner-scoped");
    assertEquals(422, ownerScopedMembership.statusCode(), ownerScopedMembership.body());
    HttpResponse<String> ownerCreated =
        post("/v1/memberships" + context, token("keycloak-subject-1"),
            ownerMembership, "grant-key-owner");
    assertEquals(201, ownerCreated.statusCode(), ownerCreated.body());
    HttpResponse<String> ownerReused =
        post("/v1/memberships" + context, token("keycloak-subject-1"),
            membershipBody("00000000-0000-4000-8000-000000000206", "grant-target",
                "identity.owner.select", expiresAt, "grant-decision-owner"),
            "grant-key-owner-reused");
    assertEquals(403, ownerReused.statusCode(), ownerReused.body());

    HttpResponse<String> approved =
        post("/v1/grant-decisions" + context, token("approver-a-subject"), decisionBody);
    assertEquals(201, approved.statusCode(), approved.body());
    HttpResponse<String> approvedReplay =
        post("/v1/grant-decisions" + context, token("approver-a-subject"), decisionBody);
    assertEquals(200, approvedReplay.statusCode(), approvedReplay.body());
    assertThat(approvedReplay.body()).contains("\"result\":\"REPLAYED\"");

    HttpResponse<String> mismatch =
        post("/v1/memberships" + context, token("keycloak-subject-1"),
            membershipBody(membershipId, "grant-target", "catalog.service.write", expiresAt,
                "grant-decision-1"),
            "grant-key-mismatch");
    assertEquals(403, mismatch.statusCode(), mismatch.body());

    String createBody =
        membershipBody(membershipId, "grant-target", "catalog.service.read", expiresAt,
            "grant-decision-1");
    HttpResponse<String> created =
        post("/v1/memberships" + context, token("keycloak-subject-1"), createBody, "grant-key-1");
    assertEquals(201, created.statusCode(), created.body());
    assertThat(created.body()).contains("\"result\":\"CREATED\"", "\"revision\":1");
    HttpResponse<String> replay =
        post("/v1/memberships" + context, token("keycloak-subject-1"), createBody, "grant-key-1");
    assertEquals(200, replay.statusCode(), replay.body());
    assertThat(replay.body()).contains("\"result\":\"REPLAYED\"");
    HttpResponse<String> reused =
        post("/v1/memberships" + context, token("keycloak-subject-1"),
            membershipBody("00000000-0000-4000-8000-000000000202", "grant-target",
                "catalog.service.read", expiresAt, "grant-decision-1"),
            "grant-key-2");
    assertEquals(403, reused.statusCode(), reused.body());

    assertEquals(1, jdbc.queryForObject(
        "SELECT count(*) FROM uacrf_identity.membership WHERE id = ? AND grant_decision_id = ?",
        Integer.class, UUID.fromString(membershipId), "grant-decision-1"));
    assertEquals(1, jdbc.queryForObject(
        "SELECT count(*) FROM uacrf_identity.grant_decision WHERE id = 'grant-decision-1' "
            + "AND consumed_membership_id = ?", Integer.class, UUID.fromString(membershipId)));
    assertEquals(1, jdbc.queryForObject(
        "SELECT count(*) FROM uacrf_identity.audit_event WHERE membership_id = ? "
            + "AND action = 'MEMBERSHIP_CREATED'", Integer.class, UUID.fromString(membershipId)));
    assertEquals(1, jdbc.queryForObject(
        "SELECT count(*) FROM uacrf_identity.outbox_event WHERE aggregate_id = ? "
            + "AND event_type = 'identity.membership.created'", Integer.class, membershipId));

    HttpResponse<String> selfApproved =
        post("/v1/grant-decisions" + context, token("approver-b-subject"),
            decisionBody("grant-decision-2", "grant-target", "catalog.journey.read", expiresAt));
    assertEquals(201, selfApproved.statusCode(), selfApproved.body());
    HttpResponse<String> sameActor =
        post("/v1/memberships" + context, token("approver-b-subject"),
            membershipBody("00000000-0000-4000-8000-000000000203", "grant-target",
                "catalog.journey.read", expiresAt, "grant-decision-2"),
            "grant-key-3");
    assertEquals(403, sameActor.statusCode(), sameActor.body());

    HttpResponse<String> staleApprover =
        post("/v1/grant-decisions" + context, token("approver-a-subject"),
            decisionBody("grant-decision-3", "grant-target", "catalog.journey.read", expiresAt));
    assertEquals(201, staleApprover.statusCode(), staleApprover.body());
    jdbc.update(
        "UPDATE uacrf_identity.membership SET revoked_at = now() "
            + "WHERE principal_id = 'approver-a' AND capability = 'identity.grant.approve'");
    HttpResponse<String> revokedApprover =
        post("/v1/memberships" + context, token("keycloak-subject-1"),
            membershipBody("00000000-0000-4000-8000-000000000204", "grant-target",
                "catalog.journey.read", expiresAt, "grant-decision-3"),
            "grant-key-4");
    assertEquals(403, revokedApprover.statusCode(), revokedApprover.body());
  }

  private void removeGrantFixtures() {
    String principals = "('approver-a', 'approver-b', 'grant-target')";
    jdbc.update(
        "UPDATE uacrf_identity.grant_decision SET consumed_at = NULL, "
            + "consumed_membership_id = NULL");
    jdbc.update("DELETE FROM uacrf_identity.audit_event WHERE principal_id IN " + principals);
    jdbc.update(
        "DELETE FROM uacrf_identity.outbox_event WHERE aggregate_id IN (SELECT id::text "
            + "FROM uacrf_identity.membership WHERE principal_id IN " + principals + ")");
    jdbc.update("DELETE FROM uacrf_identity.idempotency_record WHERE idempotency_key LIKE 'grant-key-%'");
    jdbc.update("DELETE FROM uacrf_identity.membership WHERE principal_id IN " + principals);
    jdbc.update("DELETE FROM uacrf_identity.grant_decision");
    jdbc.update("DELETE FROM uacrf_identity.principal WHERE id IN " + principals);
  }

  private static String decisionBody(
      String id, String principalId, String capability, String expiresAt) {
    return """
        {"id":"%s","principal_id":"%s","capability":"%s","service_scope":"*",
         "expires_at":"%s","reason":"Approved test grant"}
        """.formatted(id, principalId, capability, expiresAt);
  }

  private static String membershipBody(
      String id, String principalId, String capability, String expiresAt, String ref) {
    return """
        {"id":"%s","principal_id":"%s","capability":"%s","service_scope":"*",
         "expires_at":"%s","reason":"Granted test access","expected_revision":0,
         "authorization_ref":"%s"}
        """.formatted(id, principalId, capability, expiresAt, ref);
  }

  @Test
  void syntheticSourceExchangeRequiresBothCurrentGlobalGrants() throws Exception {
    String principal = "objective-sample-exchange-test";
    var json = tools.jackson.databind.json.JsonMapper.builder().build();
    UUID scopedGrant = UUID.randomUUID();
    UUID globalGrant = UUID.randomUUID();
    try {
      seedGrantParticipant(principal, "objectives.objective.read");
      String oidc = token(principal + "-subject");
      var delegated = post("/internal/v1/delegations", oidc,
          "{\"audience\":\"S05\",\"capability_ids\":[\"objectives.objective.read\"]}");
      assertEquals(200, delegated.statusCode());
      String assertion = "Bearer " + json.readTree(delegated.body()).path("access_token").asString();
      jdbc.update("INSERT INTO uacrf_identity.membership "
          + "(id,tenant_id,environment_id,principal_id,capability,service_scope,expires_at,revision) "
          + "VALUES (?,'tenant-test','environment-test',?,'connections.connection.read','sample-connection',"
          + "'2099-12-31T23:59:59Z',1)", scopedGrant, principal);
      assertEquals(403, post("/internal/v1/delegations:exchange", assertion, sampleExchangeBody()).statusCode());
      jdbc.update("INSERT INTO uacrf_identity.membership "
          + "(id,tenant_id,environment_id,principal_id,capability,service_scope,expires_at,revision) "
          + "VALUES (?,'tenant-test','environment-test',?,'connections.connection.read','*',"
          + "'2099-12-31T23:59:59Z',1)", globalGrant, principal);
      var accepted = post("/internal/v1/delegations:exchange", assertion, sampleExchangeBody());
      assertEquals(200, accepted.statusCode());
      Jwt result = NimbusJwtDecoder.withPublicKey((RSAPublicKey) DELEGATION_KEY_PAIR.getPublic()).build()
          .decode(json.readTree(accepted.body()).path("access_token").asString());
      assertThat(result.getSubject()).isEqualTo(principal);
      assertThat(result.getAudience()).containsExactly("S03");
      assertThat(result.getClaimAsStringList("capabilities")).containsExactly("connections.sample-objective.read");
      assertThat(result.getClaimAsString("resource_id")).isEqualTo("sample-connection");
      jdbc.update("UPDATE uacrf_identity.membership SET revoked_at = now() WHERE id = ?", globalGrant);
      assertEquals(403, post("/internal/v1/delegations:exchange", assertion, sampleExchangeBody()).statusCode());
      jdbc.update("INSERT INTO uacrf_identity.membership "
          + "(id,tenant_id,environment_id,principal_id,capability,service_scope,expires_at,revision) "
          + "VALUES (?,'tenant-test','environment-test',?,'connections.connection.read','*',"
          + "'2099-12-31T23:59:59Z',1)", UUID.randomUUID(), principal);
      jdbc.update("UPDATE uacrf_identity.membership SET revoked_at = now() "
          + "WHERE principal_id = ? AND capability = 'objectives.objective.read'", principal);
      assertEquals(403, post("/internal/v1/delegations:exchange", assertion, sampleExchangeBody()).statusCode());
    } finally {
      jdbc.update("DELETE FROM uacrf_identity.membership WHERE principal_id = ?", principal);
      jdbc.update("DELETE FROM uacrf_identity.principal WHERE id = ?", principal);
    }
  }

  private static String sampleExchangeBody() {
    return """
        {"audience":"S03","resource_type":"connection","resource_id":"sample-connection",
         "purpose":"objectives.import.synthetic-source","operation_deadline":"%s"}
        """.formatted(Instant.now().plusSeconds(4));
  }

  private void seedGrantParticipant(String principalId, String... capabilities) {
    jdbc.update(
        "INSERT INTO uacrf_identity.principal "
            + "(id, issuer_uri, subject, display_name, kind, enabled, token_valid_after, revision) "
            + "VALUES (?, ?, ?, ?, 'HUMAN', true, now() - interval '1 minute', 1) "
            + "ON CONFLICT (id) DO NOTHING",
        principalId, ISSUER, principalId + "-subject", principalId);
    // Every participant needs one active membership to be admitted to the environment.
    jdbc.update(
        "INSERT INTO uacrf_identity.membership "
            + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
            + "expires_at, revision) VALUES (?, 'tenant-test', 'environment-test', ?, "
            + "'identity.directory.read', '*', '2099-12-31T23:59:59Z', 1)",
        UUID.randomUUID(), principalId);
    for (String capability : capabilities) {
      jdbc.update(
          "INSERT INTO uacrf_identity.membership "
              + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
              + "expires_at, revision) VALUES (?, 'tenant-test', 'environment-test', ?, ?, '*', "
              + "'2099-12-31T23:59:59Z', 1)",
          UUID.randomUUID(), principalId, capability);
    }
  }

  private void seedScopedMemberships() {
    jdbc.update(
        "INSERT INTO uacrf_identity.membership "
            + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
            + "expires_at, revision) SELECT ?, 'tenant-test', 'environment-test', 'admin-test', "
            + "'catalog.service.write', 'svc-1', '2099-12-31T23:59:59Z', 1 "
            + "WHERE NOT EXISTS (SELECT 1 FROM uacrf_identity.membership "
            + "WHERE tenant_id = 'tenant-test' AND environment_id = 'environment-test' "
            + "AND principal_id = 'admin-test' AND capability = 'catalog.service.write' "
            + "AND service_scope = 'svc-1' AND revoked_at IS NULL)",
        UUID.randomUUID());
    for (String id : List.of("owner-a", "owner-b", "owner-expired", "owner-disabled")) {
      boolean enabled = !id.equals("owner-disabled");
      jdbc.update(
          "INSERT INTO uacrf_identity.principal "
              + "(id, issuer_uri, subject, display_name, kind, enabled, token_valid_after, revision) "
              + "VALUES (?, ?, ?, ?, 'HUMAN', ?, now(), 1) ON CONFLICT (id) DO NOTHING",
          id,
          ISSUER,
          id + "-subject",
          id,
          enabled);
      jdbc.update(
          "INSERT INTO uacrf_identity.membership "
              + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
              + "expires_at, revision) SELECT ?, 'tenant-test', 'environment-test', ?, "
              + "'owner.eligible', '*', ?, 1 WHERE NOT EXISTS ("
              + "SELECT 1 FROM uacrf_identity.membership WHERE tenant_id = 'tenant-test' "
              + "AND environment_id = 'environment-test' AND principal_id = ? "
              + "AND capability = 'owner.eligible' AND revoked_at IS NULL)",
          UUID.randomUUID(),
          id,
          id.equals("owner-expired")
              ? java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z"))
              : java.sql.Timestamp.from(Instant.parse("2099-12-31T23:59:59Z")),
          id);
    }
    jdbc.update(
        "INSERT INTO uacrf_identity.principal "
            + "(id, issuer_uri, subject, display_name, kind, enabled, token_valid_after, revision) "
            + "VALUES ('runtime-1', ?, 'runtime-subject', 'Runtime workload', 'SYSTEM', "
            + "true, now(), 1) ON CONFLICT (id) DO NOTHING",
        ISSUER);
    jdbc.update(
        "INSERT INTO uacrf_identity.membership "
            + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
            + "expires_at, revision) SELECT ?, 'tenant-test', 'environment-test', 'runtime-1', "
            + "'runtime.principal.use', 'svc-1', '2099-12-31T23:59:59Z', 1 "
            + "WHERE NOT EXISTS (SELECT 1 FROM uacrf_identity.membership "
            + "WHERE tenant_id = 'tenant-test' AND environment_id = 'environment-test' "
            + "AND principal_id = 'runtime-1' AND capability = 'runtime.principal.use' "
            + "AND service_scope = 'svc-1' AND revoked_at IS NULL)",
        UUID.randomUUID());
  }

  private void seedRevocableMembership() {
    seedRevocableMembership("00000000-0000-4000-8000-000000000101", "revocable-user");
  }

  private void seedRevocableMembership(String membershipId, String principalId) {
    jdbc.update(
        "INSERT INTO uacrf_identity.principal "
            + "(id, issuer_uri, subject, display_name, kind, enabled, token_valid_after, revision) "
            + "VALUES (?, ?, ?, ?, 'HUMAN', true, now(), 1) ON CONFLICT (id) DO NOTHING",
        principalId,
        ISSUER,
        principalId + "-subject",
        principalId);
    jdbc.update(
        "INSERT INTO uacrf_identity.membership "
            + "(id, tenant_id, environment_id, principal_id, capability, service_scope, "
            + "expires_at, revision) VALUES (?, 'tenant-test', 'environment-test', ?, "
            + "'catalog.service.read', '*', '2099-12-31T23:59:59Z', 1) ON CONFLICT (id) DO NOTHING",
        UUID.fromString(membershipId),
        principalId);
  }

  private String token(String subject) {
    return token(subject, true);
  }

  private String tokenWithoutContext(String subject) {
    return token(subject, false);
  }

  private String token(String subject, boolean includeContext) {
    return token(subject, includeContext, Instant.now().plusSeconds(60));
  }

  private String token(String subject, boolean includeContext, Instant expiresAt) {
    Instant now = Instant.now();
    JwtClaimsSet.Builder claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(subject)
            .audience(List.of("uacrf-s01"))
            .issuedAt(now)
            .expiresAt(expiresAt);
    if (includeContext) {
      claims
          .claim("tenant_id", "tenant-test")
          .claim("environment_id", "environment-test");
    }
    return "Bearer "
        + jwtEncoder
            .encode(
                JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
            .getTokenValue();
  }

  private HttpResponse<String> get(String path, String authorization) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String authorization, String body)
      throws java.io.IOException, InterruptedException {
    return post(path, authorization, body, null);
  }

  private HttpResponse<String> post(
      String path, String authorization, String body, String idempotencyKey)
      throws java.io.IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", authorization)
            .header("Content-Type", "application/json");
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    return http.send(
        request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> postUnchecked(
      String path, String authorization, String body, String idempotencyKey) {
    try {
      return post(path, authorization, body, idempotencyKey);
    } catch (Exception error) {
      throw new IllegalStateException("Concurrent HTTP request failed.", error);
    }
  }

  private Jwt decodeDelegation(String token) {
    return decodeDelegation(token, "S02");
  }

  private Jwt decodeDelegation(String token, String audience) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey((RSAPublicKey) DELEGATION_KEY_PAIR.getPublic())
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();
    decoder.setJwtValidator(
        JwtValidators.createDefaultWithValidators(
            JwtValidators.createDefaultWithIssuer("https://uacrf.local/s01"),
            new JwtClaimValidator<Object>(
                "aud", value -> value instanceof List<?> audiences && audiences.contains(audience))));
    Jwt jwt = decoder.decode(token);
    assertThat(jwt.getHeaders().get("kid")).isEqualTo(DELEGATION_KEY_ID);
    return jwt;
  }

  static final class StubDirectory implements TrustedDirectory {
    final java.util.Map<String, DirectoryPrincipal> entries = new java.util.concurrent.ConcurrentHashMap<>();
    volatile boolean unavailable;

    @Override
    public java.util.Optional<DirectoryPrincipal> find(String issuerUri, String subjectRef) {
      if (unavailable) {
        throw new UnavailableException("down", null);
      }
      return java.util.Optional.ofNullable(entries.get(subjectRef));
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestJwtConfiguration {
    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
      SecretKey secretKey = testSecretKey();
      NimbusJwtDecoder decoder =
          NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
      decoder.setJwtValidator(
          JwtValidators.createDefaultWithValidators(
              JwtValidators.createDefaultWithIssuer(ISSUER),
              new JwtClaimValidator<Object>(
                  "aud",
                  value -> value instanceof List<?> audiences && audiences.contains("uacrf-s01"))));
      return decoder;
    }

    @Bean
    StubDirectory stubDirectory() {
      return new StubDirectory();
    }

    @Bean
    JwtEncoder testJwtEncoder() {
      return new NimbusJwtEncoder(new ImmutableSecret<>(testSecretKey()));
    }

    private static SecretKey testSecretKey() {
      return new SecretKeySpec(Base64.getDecoder().decode(OIDC_TEST_SECRET), "HmacSHA256");
    }
  }

  private static KeyPair generateDelegationKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("Unable to create test delegation key pair", error);
    }
  }
}
