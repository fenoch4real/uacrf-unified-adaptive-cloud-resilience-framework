package org.uacrf.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
  void allowsLoopbackHttpBootstrapIssuerButRejectsRemoteHttpIssuer() {
    IdentityBootstrap.validateIssuer(URI.create("http://localhost:8180/realms/uacrf"));
    org.assertj.core.api.Assertions.assertThatIllegalStateException()
        .isThrownBy(
            () -> IdentityBootstrap.validateIssuer(URI.create("http://keycloak:8080/realms/uacrf")));
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
    assertEquals(403, denied.statusCode());
    assertThat(denied.body()).contains("\"code\":\"PERMISSION_DENIED\"");

    HttpResponse<String> unauthenticated = get("/v1/me/access", null);
    assertEquals(401, unauthenticated.statusCode());
    assertThat(unauthenticated.body()).contains("\"code\":\"UNAUTHENTICATED\"");
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

    HttpResponse<String> unknownAudience =
        post(
            "/internal/v1/delegations",
            token("keycloak-subject-1"),
            """
            {"audience":"S06","service_id":"svc-1","capability_ids":["catalog.service.write"]}
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
    Instant now = Instant.now();
    JwtClaimsSet.Builder claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(subject)
            .audience(List.of("uacrf-s01"))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(60));
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
      throws Exception {
    return post(path, authorization, body, null);
  }

  private HttpResponse<String> post(
      String path, String authorization, String body, String idempotencyKey) throws Exception {
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
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey((RSAPublicKey) DELEGATION_KEY_PAIR.getPublic())
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();
    decoder.setJwtValidator(
        JwtValidators.createDefaultWithValidators(
            JwtValidators.createDefaultWithIssuer("https://uacrf.local/s01"),
            new JwtClaimValidator<Object>(
                "aud", value -> value instanceof List<?> audiences && audiences.contains("S02"))));
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
