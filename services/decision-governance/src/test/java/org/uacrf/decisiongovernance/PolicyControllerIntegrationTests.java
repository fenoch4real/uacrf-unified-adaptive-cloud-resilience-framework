package org.uacrf.decisiongovernance;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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
import org.uacrf.security.DelegationJwtEncoderFactory;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.DelegationTokenIssuer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PolicyControllerIntegrationTests {
  private static final String KEY_ID = "decision-governance-test-key";
  private static final KeyPair KEY_PAIR = generateKeyPair();
  private static final String PUBLIC_KEY =
      Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded());
  private static final String POSTGRES_IMAGE =
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81";
  private static final String ROOT = "/v1/authorization-policy-bundles";
  private static final String SERVICE = "service-policy-test";
  private static final String BASE_REQUEST =
      """
      {
        "id":"policy-test",
        "service_id":"service-policy-test",
        "expected_revision":0,
        "expires_at":"2031-02-01T00:00:00Z",
        "definition":{
          "allowed_action_kinds":["READ"],
          "allowed_actor_ids":["actor-1"],
          "allowed_resource_ids":["resource-1"],
          "permitted_providers":["AZURE"],
          "permitted_regions":["eastus"],
          "required_approver_count":1,
          "eligible_approver_ids":["approver-1"],
          "maximum_cost_usd":12.345678
        },
        "reason":"Draft policy for a test",
        "effective_from":"2031-01-01T00:00:00Z"
      }
      """;
  @Container
  private static final PostgreSQLContainer DATABASE =
      new PostgreSQLContainer(POSTGRES_IMAGE)
          .withDatabaseName("decision_governance_test")
          .withUsername("decision_governance_test")
          .withPassword("decision_governance_test");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
    properties.add("spring.datasource.username", DATABASE::getUsername);
    properties.add("spring.datasource.password", DATABASE::getPassword);
    properties.add("uacrf.delegation.public-key-base64", () -> PUBLIC_KEY);
    properties.add("uacrf.delegation.key-id", () -> KEY_ID);
  }

  @LocalServerPort private int port;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void createsDigestOnlyDraftAndReadsItThroughBothContractRoutes() throws Exception {
    String writeReadToken =
        token(
            "tenant-proposal",
            "environment-proposal",
            SERVICE,
            List.of("authorization.policy.write", "authorization.policy.read"));
    HttpResponse<String> created = post(writeReadToken, "create-draft", BASE_REQUEST);
    assertThat(created.statusCode()).isEqualTo(200);
    JsonNode policy = body(created).path("policy");
    assertThat(policy.path("revision").asLong()).isEqualTo(1);
    assertThat(policy.path("proposed_by").asText()).isEqualTo("principal-governance-test");
    assertThat(policy.path("decision").asText()).isEqualTo("DRAFT");
    assertThat(policy.path("current_eligible").asBoolean()).isFalse();
    assertThat(policy.path("authority").asText())
        .isEqualTo("SIGNED_POLICY_CONFIGURATION_NOT_ACTION_AUTHORIZATION");
    assertThat(policy.path("policy_version_id").isNull()).isTrue();
    assertThat(policy.path("trust_digest").isNull()).isTrue();
    assertThat(policy.path("trust_revision").isNull()).isTrue();
    assertThat(policy.path("assertion_digest").isNull()).isTrue();
    assertThat(policy.path("signed_assertion").isNull()).isTrue();
    assertThat(body(created).path("receipt_id").isNull()).isTrue();
    String canonicalText = policy.path("material").path("canonical_text").asText();
    String digest =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(canonicalText.getBytes(StandardCharsets.UTF_8)));
    assertThat(canonicalText).doesNotContain("\n", "\": ", ", ");
    assertThat(policy.path("digest").asText()).isEqualTo(digest);
    assertThat(policy.path("material").path("digest").asText()).isEqualTo(digest);

    HttpResponse<String> listed =
        get(
            writeReadToken,
            ROOT + "?service_id=" + SERVICE + "&page_size=10");
    assertThat(listed.statusCode()).isEqualTo(200);
    assertThat(body(listed).path("coverage").asText())
        .isEqualTo("AUTHORIZED_VISIBLE_SCOPE_ONLY");
    assertThat(body(listed).path("items").get(0).path("id").asText())
        .isEqualTo("policy-test");
    assertThat(body(listed).path("items").get(0).path("decision").asText()).isEqualTo("DRAFT");
    assertThat(body(listed).path("items").get(0).has("signed_assertion")).isFalse();

    HttpResponse<String> read =
        get(writeReadToken, ROOT + "/policy-test?revision=1");
    assertThat(read.statusCode()).isEqualTo(200);
    assertThat(body(read).path("policy").path("material").path("canonical_text").asText())
        .isEqualTo(canonicalText);
    assertThat(body(read).path("policy").path("signed_assertion").isNull()).isTrue();
    HttpResponse<String> latest = get(writeReadToken, ROOT + "/policy-test");
    assertThat(latest.statusCode()).isEqualTo(200);
    assertThat(body(latest).path("policy").path("revision").asLong()).isEqualTo(1);
  }

  @Test
  void canonicalDigestIsStableAcrossRevisionAndRevisionHistoryIsImmutable() throws Exception {
    String writeToken =
        token(
            "tenant-revisions",
            "environment-revisions",
            SERVICE,
            List.of("authorization.policy.write", "authorization.policy.read"));
    JsonNode first = body(post(writeToken, "revision-one", BASE_REQUEST)).path("policy");
    String secondRequest =
        BASE_REQUEST.replace("\"expected_revision\":0", "\"expected_revision\":1")
            .replace("\"reason\":\"Draft policy for a test\"", "\"reason\":\"Second proposal\"");
    JsonNode second = body(post(writeToken, "revision-two", secondRequest)).path("policy");
    assertThat(second.path("revision").asLong()).isEqualTo(2);
    assertThat(second.path("digest").asText()).isEqualTo(first.path("digest").asText());
    assertThat(second.path("material").path("canonical_text").asText())
        .isEqualTo(first.path("material").path("canonical_text").asText());

    JsonNode historical = body(get(writeToken, ROOT + "/policy-test?revision=1")).path("policy");
    assertThat(historical.path("proposal_reason").asText()).isEqualTo("Draft policy for a test");
    assertThat(historical.path("revision").asLong()).isEqualTo(1);

    HttpResponse<String> stale =
        post(writeToken, "stale-revision", BASE_REQUEST);
    assertThat(stale.statusCode()).isEqualTo(409);
    assertThat(body(stale).path("code").asText()).isEqualTo("REVISION_CONFLICT");
  }

  @Test
  void idempotencyReplaysTheOriginalRevisionAndRejectsChangedContent() throws Exception {
    String writeToken =
        token(
            "tenant-idempotency",
            "environment-idempotency",
            SERVICE,
            List.of("authorization.policy.write", "authorization.policy.read"));
    HttpResponse<String> first = post(writeToken, "idempotent-key", BASE_REQUEST);
    HttpResponse<String> retry = post(writeToken, "idempotent-key", BASE_REQUEST);
    assertThat(retry.statusCode()).isEqualTo(200);
    assertThat(body(retry).path("policy").path("revision").asLong()).isEqualTo(1);
    assertThat(body(retry).path("policy").path("proposed_at").asText())
        .isEqualTo(body(first).path("policy").path("proposed_at").asText());

    HttpResponse<String> changed =
        post(
            writeToken,
            "idempotent-key",
            BASE_REQUEST.replace("Draft policy for a test", "Changed content"));
    assertThat(changed.statusCode()).isEqualTo(409);
    assertThat(body(changed).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
  }

  @Test
  void concurrentIdenticalProposalsReplayOneImmutableResult() throws Exception {
    String tenant = "tenant-concurrent-replay";
    String token = concurrentToken(tenant);
    assertThat(post(token, "seed", BASE_REQUEST).statusCode()).isEqualTo(200);
    String proposal = BASE_REQUEST.replace("\"expected_revision\":0", "\"expected_revision\":1");

    List<HttpResponse<String>> responses =
        concurrentProposals(tenant, token, "shared-key", proposal, "shared-key", proposal);
    assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(200));
    JsonNode original = body(responses.getFirst());
    assertThat(body(responses.getLast())).isEqualTo(original);
    assertThat(original.path("policy").path("revision").asLong()).isEqualTo(2);
    assertThat(original.path("policy").path("decision").asText()).isEqualTo("DRAFT");
    assertThat(original.path("policy").path("signed_assertion").isNull()).isTrue();
    assertPersistedCounts(tenant, 2, 2);

    String next = proposal.replace("\"expected_revision\":1", "\"expected_revision\":2");
    assertThat(post(token, "next", next).statusCode()).isEqualTo(200);
    assertThat(body(post(token, "shared-key", proposal))).isEqualTo(original);
    assertThat(body(get(token, ROOT + "/policy-test?revision=2")).path("policy"))
        .isEqualTo(original.path("policy"));
    assertPersistedCounts(tenant, 3, 3);
  }

  @Test
  void concurrentChangedContentWithSameKeyConflictsAndPreservesWinner() throws Exception {
    String tenant = "tenant-concurrent-key-conflict";
    String token = concurrentToken(tenant);
    assertThat(post(token, "seed", BASE_REQUEST).statusCode()).isEqualTo(200);
    String first = BASE_REQUEST.replace("\"expected_revision\":0", "\"expected_revision\":1");
    String changed = first.replace("Draft policy for a test", "Different concurrent proposal");

    List<HttpResponse<String>> responses =
        concurrentProposals(tenant, token, "shared-key", first, "shared-key", changed);
    assertThat(responses.stream().map(HttpResponse::statusCode).sorted().toList())
        .containsExactly(200, 409);
    int winner = responses.getFirst().statusCode() == 200 ? 0 : 1;
    JsonNode accepted = body(responses.get(winner));
    assertThat(accepted.path("policy").path("revision").asLong()).isEqualTo(2);
    assertThat(body(responses.get(1 - winner)).path("code").asText())
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(body(post(token, "shared-key", winner == 0 ? first : changed))).isEqualTo(accepted);
    HttpResponse<String> rejected = post(token, "shared-key", winner == 0 ? changed : first);
    assertThat(rejected.statusCode()).isEqualTo(409);
    assertThat(body(rejected).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
    assertPersistedCounts(tenant, 2, 2);
  }

  @Test
  void concurrentDistinctKeysCannotCreateTheSameExpectedRevision() throws Exception {
    String tenant = "tenant-concurrent-revision-conflict";
    String token = concurrentToken(tenant);
    assertThat(post(token, "seed", BASE_REQUEST).statusCode()).isEqualTo(200);
    String proposal = BASE_REQUEST.replace("\"expected_revision\":0", "\"expected_revision\":1");

    List<HttpResponse<String>> responses =
        concurrentProposals(tenant, token, "key-one", proposal, "key-two", proposal);
    assertThat(responses.stream().map(HttpResponse::statusCode).sorted().toList())
        .containsExactly(200, 409);
    int winner = responses.getFirst().statusCode() == 200 ? 0 : 1;
    JsonNode accepted = body(responses.get(winner));
    assertThat(accepted.path("policy").path("revision").asLong()).isEqualTo(2);
    assertThat(body(responses.get(1 - winner)).path("code").asText())
        .isEqualTo("REVISION_CONFLICT");
    assertThat(body(post(token, winner == 0 ? "key-one" : "key-two", proposal)))
        .isEqualTo(accepted);
    assertPersistedCounts(tenant, 2, 2);
    String next = proposal.replace("\"expected_revision\":1", "\"expected_revision\":2");
    HttpResponse<String> retried = post(token, winner == 0 ? "key-two" : "key-one", next);
    assertThat(retried.statusCode()).isEqualTo(200);
    assertThat(body(retried).path("policy").path("revision").asLong()).isEqualTo(3);
    assertPersistedCounts(tenant, 3, 3);
  }

  @Test
  void concurrentDistinctKeysCannotBothCreateTheFirstRevision() throws Exception {
    String tenant = "tenant-concurrent-first-revision";
    String token = concurrentToken(tenant);
    List<HttpResponse<String>> responses =
        concurrentProposals(tenant, token, "key-one", BASE_REQUEST, "key-two", BASE_REQUEST, true);
    assertThat(responses.stream().map(HttpResponse::statusCode).sorted().toList())
        .containsExactly(200, 409);
    int winner = responses.getFirst().statusCode() == 200 ? 0 : 1;
    assertThat(body(responses.get(winner)).path("policy").path("revision").asLong()).isEqualTo(1);
    assertThat(body(responses.get(1 - winner)).path("code").asText())
        .isEqualTo("REVISION_CONFLICT");
    assertPersistedCounts(tenant, 1, 1);
  }

  @Test
  void listPaginationAndReadsRemainTenantAndEnvironmentScoped() throws Exception {
    String tenantToken =
        token(
            "tenant-pagination",
            "environment-pagination",
            SERVICE,
            List.of("authorization.policy.write", "authorization.policy.read"));
    post(
        tenantToken,
        "page-one",
        BASE_REQUEST.replace("\"id\":\"policy-test\"", "\"id\":\"policy-alpha\""));
    post(
        tenantToken,
        "page-two",
        BASE_REQUEST.replace("\"id\":\"policy-test\"", "\"id\":\"policy-zeta\""));

    HttpResponse<String> firstPage =
        get(tenantToken, ROOT + "?service_id=" + SERVICE + "&page_size=1");
    assertThat(firstPage.statusCode()).isEqualTo(200);
    assertThat(body(firstPage).path("items").size()).isEqualTo(1);
    assertThat(body(firstPage).path("next_cursor").isNull()).isFalse();
    String cursor = body(firstPage).path("next_cursor").asText();
    HttpResponse<String> secondPage =
        get(
            tenantToken,
            ROOT + "?service_id=" + SERVICE + "&page_size=1&cursor=" + cursor);
    assertThat(secondPage.statusCode()).isEqualTo(200);
    assertThat(body(secondPage).path("items").size()).isEqualTo(1);
    assertThat(body(secondPage).path("items").get(0).path("id").asText())
        .isNotEqualTo(body(firstPage).path("items").get(0).path("id").asText());

    String otherTenant =
        token(
            "tenant-other",
            "environment-pagination",
            SERVICE,
            List.of("authorization.policy.read"));
    assertThat(get(otherTenant, ROOT + "/policy-alpha").statusCode()).isEqualTo(404);
    assertThat(body(get(otherTenant, ROOT + "?service_id=" + SERVICE))
            .path("items")
            .size())
        .isZero();
    String otherEnvironment =
        token(
            "tenant-pagination",
            "environment-other",
            SERVICE,
            List.of("authorization.policy.read"));
    assertThat(get(otherEnvironment, ROOT + "/policy-alpha").statusCode()).isEqualTo(404);
  }

  @Test
  void requiresExplicitGrantsAndRejectsGlobalWildcardInputsAndNonGovernanceRoutes()
      throws Exception {
    String noGrant =
        token("tenant-grants", "environment-grants", SERVICE, List.of());
    assertThat(post(noGrant, "no-write", BASE_REQUEST).statusCode()).isEqualTo(403);
    assertThat(get(noGrant, ROOT + "?service_id=" + SERVICE).statusCode()).isEqualTo(403);

    String writer =
        token(
            "tenant-grants",
            "environment-grants",
            SERVICE,
            List.of("authorization.policy.write"));
    assertThat(post(writer, "write-only", BASE_REQUEST).statusCode()).isEqualTo(200);
    assertThat(get(writer, ROOT + "/policy-test").statusCode()).isEqualTo(403);

    String reader =
        token(
            "tenant-reader",
            "environment-reader",
            SERVICE,
            List.of("authorization.policy.read"));
    assertThat(post(reader, "read-only", BASE_REQUEST).statusCode()).isEqualTo(403);

    String globalWildcardResource =
        BASE_REQUEST.replace("\"resource-1\"", "\"*\"");
    assertThat(post(writer, "global-wildcard-resource", globalWildcardResource).statusCode())
        .isEqualTo(400);
    String globalWildcardRegion =
        BASE_REQUEST.replace("\"eastus\"", "\"*\"");
    assertThat(post(writer, "global-wildcard-region", globalWildcardRegion).statusCode())
        .isEqualTo(400);
    String patternedResource =
        BASE_REQUEST.replace("\"id\":\"policy-test\"", "\"id\":\"policy-pattern-resource\"")
            .replace("\"resource-1\"", "\"resource-*\"");
    assertThat(post(writer, "patterned-resource", patternedResource).statusCode()).isEqualTo(200);
    String patternedRegion =
        BASE_REQUEST.replace("\"id\":\"policy-test\"", "\"id\":\"policy-pattern-region\"")
            .replace("\"eastus\"", "\"east?us\"");
    assertThat(post(writer, "patterned-region", patternedRegion).statusCode()).isEqualTo(200);

    String otherServiceScope =
        token(
            "tenant-scope",
            "environment-scope",
            "service-other",
            List.of("authorization.policy.write", "authorization.policy.read"));
    assertThat(post(otherServiceScope, "wrong-scope", BASE_REQUEST).statusCode()).isEqualTo(403);
    assertThat(
            post(
                    otherServiceScope,
                    "wrong-service",
                    BASE_REQUEST.replace("\"id\":\"policy-test\"", "\"id\":\"policy-wrong\"")
                        .replace(SERVICE, "service-other"))
                .statusCode())
        .isEqualTo(200);
    assertThat(
            get(otherServiceScope, ROOT + "?service_id=" + SERVICE)
                .statusCode())
        .isEqualTo(403);
    assertThat(get(reader, ROOT + "/policy-test:decide").statusCode()).isEqualTo(404);
  }

  private HttpResponse<String> post(String token, String key, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + ROOT))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String token, String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode body(HttpResponse<String> response) {
    return objectMapper.readTree(response.body());
  }

  private String concurrentToken(String tenant) {
    return token(
        tenant,
        "environment-concurrent",
        SERVICE,
        List.of("authorization.policy.write", "authorization.policy.read"));
  }

  private List<HttpResponse<String>> concurrentProposals(
      String tenant, String token, String firstKey, String firstBody, String secondKey,
      String secondBody) throws Exception {
    return concurrentProposals(tenant, token, firstKey, firstBody, secondKey, secondBody, false);
  }

  private List<HttpResponse<String>> concurrentProposals(
      String tenant, String token, String firstKey, String firstBody, String secondKey,
      String secondBody, boolean uncommittedHead) throws Exception {
    try (Connection connection = dataSource.getConnection();
        var executor = Executors.newFixedThreadPool(2)) {
      connection.setAutoCommit(false);
      try {
        // Force both HTTP transactions to overlap at the head or idempotency lock.
        if (uncommittedHead) {
          try (PreparedStatement insert =
              connection.prepareStatement(
                  "INSERT INTO uacrf_decision_governance.policy_head "
                      + "(tenant_id, environment_id, id, service_id, current_revision) "
                      + "VALUES (?, ?, ?, ?, 0)")) {
            insert.setString(1, tenant);
            insert.setString(2, "environment-concurrent");
            insert.setString(3, "policy-test");
            insert.setString(4, SERVICE);
            insert.executeUpdate();
          }
        }
        try (PreparedStatement lock =
            connection.prepareStatement(
                "SELECT id FROM uacrf_decision_governance.policy_head "
                    + "WHERE tenant_id = ? AND environment_id = ? AND id = ? FOR UPDATE")) {
          lock.setString(1, tenant);
          lock.setString(2, "environment-concurrent");
          lock.setString(3, "policy-test");
          try (var rows = lock.executeQuery()) {
            assertThat(rows.next()).isTrue();
          }
        }
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<HttpResponse<String>> first =
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return post(token, firstKey, firstBody);
                });
        Future<HttpResponse<String>> second =
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return post(token, secondKey, secondBody);
                });
        try {
          assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
          start.countDown();
          awaitBlockedProposals();
        } finally {
          start.countDown();
          connection.rollback();
        }
        return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
      } finally {
        connection.rollback();
      }
    }
  }

  private void awaitBlockedProposals() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    int blocked;
    do {
      blocked =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                  + "AND wait_event_type = 'Lock' AND state = 'active' "
                  + "AND (query LIKE '%uacrf_decision_governance.policy_head%' "
                  + "OR query LIKE '%uacrf_decision_governance.idempotency_record%')",
              Integer.class);
      if (blocked >= 2) {
        return;
      }
      Thread.sleep(25);
    } while (System.nanoTime() < deadline);
    assertThat(blocked).as("both proposals must be waiting on database locks").isGreaterThanOrEqualTo(2);
  }

  private void assertPersistedCounts(String tenant, int revisions, int records) {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM uacrf_decision_governance.policy_revision WHERE tenant_id = ?",
                Integer.class,
                tenant))
        .isEqualTo(revisions);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM uacrf_decision_governance.idempotency_record WHERE tenant_id = ?",
                Integer.class,
                tenant))
        .isEqualTo(records);
  }

  private static String token(
      String tenant, String environment, String serviceScope, List<String> capabilities) {
    DelegationPrincipal principal =
        new DelegationPrincipal(
            "principal-governance-test",
            tenant,
            environment,
            serviceScope,
            "https://issuer.example",
            "subject-governance-test",
            capabilities,
            List.of(),
            List.of());
    return new DelegationTokenIssuer(
            DelegationJwtEncoderFactory.create(KEY_PAIR, KEY_ID),
            Clock.systemUTC(),
            "https://uacrf.local/s01",
            KEY_ID)
        .issue(principal, "S10");
  }

  private static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("Could not create test delegation key.", error);
    }
  }
}
