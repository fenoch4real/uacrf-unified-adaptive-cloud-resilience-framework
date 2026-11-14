package org.uacrf.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.uacrf.security.DelegationJwtEncoderFactory;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.DelegationTokenIssuer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EstateCatalogApplicationTests {
  private static final String DELEGATION_KEY_ID = "catalog-test-key";
  private static final KeyPair DELEGATION_KEY_PAIR = generateDelegationKeyPair();
  private static final String DELEGATION_PUBLIC_KEY_BASE64 =
      Base64.getEncoder().encodeToString(DELEGATION_KEY_PAIR.getPublic().getEncoded());
  private static final JwtEncoder TEST_DELEGATION_ENCODER =
      DelegationJwtEncoderFactory.create(DELEGATION_KEY_PAIR, DELEGATION_KEY_ID);
  private static final String POSTGRES_IMAGE =
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(POSTGRES_IMAGE)
          .withDatabaseName("uacrf_catalog")
          .withUsername("catalog_test")
          .withPassword("catalog_test");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add(
        "uacrf.delegation.public-key-base64", () -> DELEGATION_PUBLIC_KEY_BASE64);
    properties.add("uacrf.delegation.key-id", () -> DELEGATION_KEY_ID);
  }

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void migratesSchemaAndSupportsCreationListingAndIdempotentReplay() throws Exception {
    String request =
        """
        {"reason":"Initial registration","expected_revision":0,"id":"svc-replay","name":"Payments","owner_id":"owner-1","criticality":3,"lifecycle":"ACTIVE"}
        """;
    String bearer =
        bearer(
            actor(
                "tenant-a",
                "environment-a",
                List.of("catalog.service.write", "catalog.service.read"),
                List.of("owner-1"),
                List.of()));

    HttpResponse<String> created =
        post("/v1/services", bearer, "service-create-1", request);
    assertEquals(201, created.statusCode());
    assertThat(created.body()).contains("\"id\":\"svc-replay\"", "\"revision\":1");

    HttpResponse<String> replay =
        post("/v1/services", bearer, "service-create-1", request);
    assertEquals(201, replay.statusCode());
    assertThat(replay.body()).contains("\"id\":\"svc-replay\"");

    HttpResponse<String> changedReplay =
        post(
            "/v1/services",
            bearer,
            "service-create-1",
            request.replace("Payments", "Changed name"));
    assertEquals(409, changedReplay.statusCode());
    assertThat(changedReplay.body()).contains("\"code\":\"IDEMPOTENCY_CONFLICT\"");

    HttpResponse<String> listing = get("/v1/services", bearer);
    assertEquals(200, listing.statusCode());
    assertThat(listing.body()).contains("\"name\":\"Payments\"");
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_catalog.outbox_event WHERE aggregate_id = 'svc-replay'",
            Integer.class));
  }

  @Test
  void enforcesTenantCapabilityAndOwnerBoundaries() throws Exception {
    String create =
        """
        {"reason":"Initial registration","expected_revision":0,"id":"svc-tenant-a","name":"Tenant A","owner_id":"owner-1","criticality":3,"lifecycle":"ACTIVE"}
        """;

    HttpResponse<String> deniedOwner =
        post(
            "/v1/services",
            bearer(
                actor(
                    "tenant-a",
                    "environment-a",
                    List.of("catalog.service.write"),
                    List.of(),
                    List.of())),
            "owner-denied",
            create);
    assertEquals(403, deniedOwner.statusCode());

    HttpResponse<String> created =
        post(
            "/v1/services",
            bearer(
                actor(
                    "tenant-a",
                    "environment-a",
                    List.of("catalog.service.write"),
                    List.of("owner-1"),
                    List.of())),
            "tenant-a-create",
            create);
    assertEquals(201, created.statusCode());

    HttpResponse<String> otherTenantList =
        get(
            "/v1/services",
            bearer(
                actor(
                    "tenant-b",
                    "environment-a",
                    List.of("catalog.service.read"),
                    List.of(),
                    List.of())));
    assertEquals(200, otherTenantList.statusCode());
    assertThat(otherTenantList.body()).contains("\"items\":[]");

    HttpResponse<String> deniedCapability =
        post(
            "/v1/services",
            bearer(
                actor(
                    "tenant-b",
                    "environment-a",
                    List.of(),
                    List.of("owner-1"),
                    List.of())),
            "capability-denied",
            create);
    assertEquals(403, deniedCapability.statusCode());
    assertThat(deniedCapability.body()).contains("\"code\":\"PERMISSION_DENIED\"");
  }

  @Test
  void bindsServiceRegistrationToItsDelegatedServiceId() throws Exception {
    String scopedToken =
        bearer(
            actor(
                "tenant-service-scope",
                "environment-a",
                List.of("catalog.service.write"),
                List.of("owner-1"),
                List.of(),
                "svc-authorized"));
    String registration =
        """
        {"reason":"Scoped registration","expected_revision":0,"id":"%s","name":"Scoped service","owner_id":"owner-1","criticality":2,"lifecycle":"ACTIVE"}
        """;

    assertEquals(
        403,
        post(
                "/v1/services",
                scopedToken,
                "wrong-scoped-service",
                registration.formatted("svc-other"))
            .statusCode());
    assertEquals(
        201,
        post(
                "/v1/services",
                scopedToken,
                "authorized-scoped-service",
                registration.formatted("svc-authorized"))
            .statusCode());
  }

  @Test
  void createsJourneyGraphAndRejectsMissingReferences() throws Exception {
    String writeToken =
        bearer(
            actor(
                "tenant-graph",
                "environment-a",
                List.of("catalog.service.write", "catalog.journey.write", "catalog.journey.read"),
                List.of("owner-1"),
                List.of()));

    assertEquals(
        201,
        post(
                "/v1/customer-journeys",
                writeToken,
                "journey-create",
                """
                {"id":"journey-1","name":"Checkout","owner_id":"owner-1","purpose":"Purchase flow","lifecycle":"DRAFT"}
                """)
            .statusCode());
    assertEquals(
        201,
        post(
                "/v1/customer-journeys/journey-1/steps",
                writeToken,
                "step-create",
                """
                {"id":"step-1","position":1,"name":"Authorize","intended_outcome":"Payment is authorized"}
                """)
            .statusCode());

    HttpResponse<String> missingCapability =
        post(
            "/v1/customer-journeys/journey-1/capabilities",
            writeToken,
            "missing-capability",
            """
            {"id":"link-missing","capability_kind":"SERVICE","capability_id":"missing","step_id":"step-1","criticality":"REQUIRED"}
            """);
    assertEquals(404, missingCapability.statusCode());

    assertEquals(
        201,
        post(
                "/v1/services",
                writeToken,
                "graph-service",
                """
                {"reason":"Journey capability","expected_revision":0,"id":"svc-checkout","name":"Checkout service","owner_id":"owner-1","criticality":2,"lifecycle":"ACTIVE"}
                """)
            .statusCode());

    assertEquals(
        201,
        post(
                "/v1/customer-journeys/journey-1/capabilities",
                writeToken,
                "valid-capability",
                """
                {"id":"link-1","capability_kind":"SERVICE","capability_id":"svc-checkout","step_id":"step-1","criticality":"REQUIRED"}
                """)
            .statusCode());

    HttpResponse<String> journey = get("/v1/customer-journeys/journey-1", writeToken);
    assertEquals(200, journey.statusCode());
    assertThat(journey.body())
        .contains("\"id\":\"step-1\"", "\"capability_id\":\"svc-checkout\"");
  }

  @Test
  void enforcesServiceScopeForCatalogReadsAndRequiresGlobalJourneyScope() throws Exception {
    String globalToken =
        bearer(
            actor(
                "tenant-scope",
                "environment-a",
                List.of(
                    "catalog.service.write",
                    "catalog.service.read",
                    "catalog.agent.write",
                    "catalog.agent.read",
                    "catalog.journey.write",
                    "catalog.journey.read"),
                List.of("owner-1"),
                List.of("runtime-1")));
    for (String serviceId : List.of("svc-scope-a", "svc-scope-b")) {
      assertEquals(
          201,
          post(
                  "/v1/services",
                  globalToken,
                  "scope-service-" + serviceId,
                  """
                  {"reason":"Scope fixture","expected_revision":0,"id":"%s","name":"Scoped service","owner_id":"owner-1","criticality":2,"lifecycle":"ACTIVE"}
                  """
                      .formatted(serviceId))
              .statusCode());
      assertEquals(
          201,
          post(
                  "/v1/agent-workloads",
                  globalToken,
                  "scope-agent-" + serviceId,
                  """
                  {"id":"agent-%s","service_id":"%s","name":"Scoped agent","kind":"SINGLE_AGENT","owner_id":"owner-1","runtime_principal_id":"runtime-1","purpose":"Scope fixture"}
                  """
                      .formatted(serviceId, serviceId))
              .statusCode());
    }
    assertEquals(
        201,
        post(
                "/v1/agent-workloads",
                globalToken,
                "scope-standalone-agent",
                """
                {"id":"agent-scope-standalone","name":"Standalone agent","kind":"SINGLE_AGENT","owner_id":"owner-1","runtime_principal_id":"runtime-1","purpose":"Scope fixture"}
                """)
            .statusCode());
    assertEquals(
        201,
        post(
                "/v1/customer-journeys",
                globalToken,
                "scope-journey",
                """
                {"id":"journey-scope","name":"Scope journey","owner_id":"owner-1","purpose":"Scope fixture","lifecycle":"DRAFT"}
                """)
            .statusCode());

    String scopedToken =
        bearer(
            actor(
                "tenant-scope",
                "environment-a",
                List.of(
                    "catalog.service.read",
                    "catalog.agent.read",
                    "catalog.journey.write",
                    "catalog.journey.read"),
                List.of("owner-1"),
                List.of(),
                "svc-scope-a"));
    HttpResponse<String> services = get("/v1/services", scopedToken);
    assertEquals(200, services.statusCode());
    assertThat(services.body())
        .contains("\"id\":\"svc-scope-a\"")
        .doesNotContain("\"id\":\"svc-scope-b\"");

    HttpResponse<String> agents = get("/v1/agent-workloads", scopedToken);
    assertEquals(200, agents.statusCode());
    assertThat(agents.body())
        .contains("\"id\":\"agent-svc-scope-a\"")
        .doesNotContain("\"id\":\"agent-svc-scope-b\"")
        .doesNotContain("\"id\":\"agent-scope-standalone\"");

    assertEquals(
        403,
        post(
                "/v1/customer-journeys",
                scopedToken,
                "scoped-journey-create",
                """
                {"id":"journey-scoped-denied","name":"Scoped journey","owner_id":"owner-1","purpose":"Should be denied","lifecycle":"DRAFT"}
                """)
            .statusCode());
    assertEquals(403, get("/v1/customer-journeys/journey-scope", scopedToken).statusCode());
    assertEquals(
        403,
        post(
                "/v1/customer-journeys/journey-scope/capabilities",
                scopedToken,
                "scoped-link-denied",
                """
                {"id":"link-scoped-denied","capability_kind":"SERVICE","capability_id":"svc-scope-a","criticality":"REQUIRED"}
                """)
            .statusCode());
  }

  @Test
  void registersStandaloneAndServiceBoundAgentsAndLinksTheStandaloneWorkload()
      throws Exception {
    String token =
        bearer(
            actor(
                "tenant-agent",
                "environment-a",
                List.of(
                    "catalog.service.write", "catalog.agent.write", "catalog.journey.write"),
                List.of("owner-1"),
                List.of("runtime-1")));
    String agent =
        """
        {"id":"agent-1","service_id":"missing-service","name":"Worker","kind":"SINGLE_AGENT","owner_id":"owner-1","runtime_principal_id":"runtime-1","purpose":"Process tasks"}
        """;

    HttpResponse<String> missingService =
        post("/v1/agent-workloads", token, "missing-service-agent", agent);
    assertEquals(404, missingService.statusCode());

    assertEquals(
        201,
        post(
                "/v1/services",
                token,
                "agent-service",
                """
                {"reason":"Agent parent","expected_revision":0,"id":"svc-agent","name":"Agent parent","owner_id":"owner-1","criticality":3,"lifecycle":"ACTIVE"}
                """)
            .statusCode());

    HttpResponse<String> serviceBound =
        post(
            "/v1/agent-workloads",
            bearer(
                actor(
                    "tenant-agent",
                    "environment-a",
                    List.of("catalog.agent.write"),
                    List.of("owner-1"),
                    List.of("runtime-1"),
                    "svc-agent")),
            "service-bound-agent",
            agent.replace("missing-service", "svc-agent"));
    assertEquals(201, serviceBound.statusCode());

    HttpResponse<String> mismatchedServiceScope =
        post(
            "/v1/agent-workloads",
            bearer(
                actor(
                    "tenant-agent",
                    "environment-a",
                    List.of("catalog.agent.write"),
                    List.of("owner-1"),
                    List.of("runtime-1"),
                    "other-service")),
            "mismatched-service-scope",
            agent.replace("missing-service", "svc-agent"));
    assertEquals(403, mismatchedServiceScope.statusCode());

    HttpResponse<String> scopedStandalone =
        post(
            "/v1/agent-workloads",
            bearer(
                actor(
                    "tenant-agent",
                    "environment-a",
                    List.of("catalog.agent.write"),
                    List.of("owner-1"),
                    List.of("runtime-1"),
                    "svc-agent")),
            "scoped-standalone-agent",
            """
            {"id":"agent-scoped-standalone","name":"Worker","kind":"SINGLE_AGENT","owner_id":"owner-1","runtime_principal_id":"runtime-1","purpose":"Process tasks"}
            """);
    assertEquals(403, scopedStandalone.statusCode());

    HttpResponse<String> standalone =
        post(
            "/v1/agent-workloads",
            token,
            "standalone-agent",
            """
            {"id":"agent-standalone","name":"Standalone worker","kind":"SINGLE_AGENT","owner_id":"owner-1","runtime_principal_id":"runtime-1","purpose":"Process tasks"}
            """);
    assertEquals(201, standalone.statusCode());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_catalog.agent_workload "
                + "WHERE tenant_id = 'tenant-agent' AND environment_id = 'environment-a' "
                + "AND id = 'agent-standalone' AND service_id IS NULL",
            Integer.class));

    assertEquals(
        201,
        post(
                "/v1/customer-journeys",
                token,
                "standalone-agent-journey",
                """
                {"id":"journey-agent-link","name":"Agent link","owner_id":"owner-1","purpose":"Link a standalone agent","lifecycle":"DRAFT"}
                """)
            .statusCode());
    assertEquals(
        201,
        post(
                "/v1/customer-journeys/journey-agent-link/capabilities",
                token,
                "standalone-agent-link",
                """
                {"id":"link-agent-standalone","capability_kind":"AGENT","capability_id":"agent-standalone","criticality":"REQUIRED"}
                """)
            .statusCode());

    HttpResponse<String> deniedRuntime =
        post(
            "/v1/agent-workloads",
            bearer(
                actor(
                    "tenant-agent",
                    "environment-a",
                    List.of("catalog.agent.write"),
                    List.of("owner-1"),
                    List.of("different-runtime"))),
            "runtime-denied",
            agent.replace("missing-service", "svc-agent").replace("agent-1", "agent-runtime-denied"));
    assertEquals(403, deniedRuntime.statusCode());
  }

  private HttpResponse<String> post(String path, String bearer, String key, String body)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", bearer)
            .header("Idempotency-Key", key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path, String bearer) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", bearer)
            .GET()
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String bearer(DelegationPrincipal principal) {
    return "Bearer "
        + new DelegationTokenIssuer(
                TEST_DELEGATION_ENCODER,
                Clock.systemUTC(),
                "https://uacrf.local/s01",
                DELEGATION_KEY_ID)
            .issue(principal, "S02");
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

  private static DelegationPrincipal actor(
      String tenant,
      String environment,
      List<String> capabilities,
      List<String> owners,
      List<String> runtimePrincipals) {
    return actor(tenant, environment, capabilities, owners, runtimePrincipals, "*");
  }

  private static DelegationPrincipal actor(
      String tenant,
      String environment,
      List<String> capabilities,
      List<String> owners,
      List<String> runtimePrincipals,
      String serviceScope) {
    return new DelegationPrincipal(
        "https://uacrf.local/s01|user-1",
        tenant,
        environment,
        serviceScope,
        "https://idp.example.test",
        "user-1",
        capabilities,
        owners,
        runtimePrincipals);
  }
}
