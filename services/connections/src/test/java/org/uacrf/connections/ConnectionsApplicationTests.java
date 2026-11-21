package org.uacrf.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
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
class ConnectionsApplicationTests {
  private static final String KEY_ID = "connections-test-key";
  private static final KeyPair KEY_PAIR = generateKeyPair();
  private static final JwtEncoder ENCODER = DelegationJwtEncoderFactory.create(KEY_PAIR, KEY_ID);
  private static final String POSTGRES_IMAGE =
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81";
  private static final List<String> RW =
      List.of("connections.connection.write", "connections.connection.read");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(POSTGRES_IMAGE)
          .withDatabaseName("uacrf_connections")
          .withUsername("connections_test")
          .withPassword("connections_test");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
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
  @Autowired private DataSource dataSource;
  @Autowired private ObjectMapper mapper;
  @Autowired private CollectionCheckpointStore collectionCheckpoints;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void registersListsAndReplaysConnectionsWithOutboxDigest() throws Exception {
    String body = connection("conn-sample", "SAMPLE_LOCAL", 0);
    String bearer = bearer(actor("tenant-a", "env-a", RW, List.of("owner-1")), "S03");

    HttpResponse<String> created = post("/v1/connections", bearer, "k1", body);
    assertEquals(201, created.statusCode());
    assertEquals(201, post("/v1/connections", bearer, "k1", body).statusCode());
    HttpResponse<String> changed =
        post("/v1/connections", bearer, "k1", body.replace("Sample source", "Other"));
    assertEquals(409, changed.statusCode());
    assertThat(changed.body()).contains("IDEMPOTENCY_CONFLICT");

    HttpResponse<String> fetched = get("/v1/connections/conn-sample", bearer);
    assertThat(fetched.body()).contains("\"status\":\"ACTIVE\"");
    assertThat(get("/v1/connections", bearer).body()).contains("conn-sample");
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_connections.outbox_event WHERE aggregate_id = 'conn-sample' "
                + "AND schema_version = 1 AND length(payload_digest) = 64",
            Integer.class));
  }

  @Test
  void cloudProvidersStayVisiblyUnacceptedAndCannotBeBound() throws Exception {
    String bearer = bearer(actor("tenant-a", "env-a", RW, List.of("owner-1")), "S03");
    String awsConnection = connection("conn-aws", "AWS", 0);
    assertEquals(201, post("/v1/connections", bearer, "k-aws", awsConnection).statusCode());
    String awsDetails = get("/v1/connections/conn-aws", bearer).body();
    assertThat(awsDetails)
        .contains("\"status\":\"UNACCEPTED\"")
        .contains("\"aws_external_id\":\"uacrf-");
    String externalIdPrefix = "\"aws_external_id\":\"";
    int externalIdStart = awsDetails.indexOf(externalIdPrefix) + externalIdPrefix.length();
    String externalId = awsDetails.substring(externalIdStart, awsDetails.indexOf('"', externalIdStart));
    assertEquals(201, post("/v1/connections", bearer, "k-aws", awsConnection).statusCode());
    assertThat(get("/v1/connections/conn-aws", bearer).body()).contains(externalId);

    String invalidRole =
        connection("conn-aws-invalid", "AWS", 0).replace(
            "arn:aws:iam::123456789012:role/readonly", "role/readonly");
    assertEquals(400, post("/v1/connections", bearer, "k-aws-invalid", invalidRole).statusCode());

    HttpResponse<String> bind =
        post(
            "/v1/connections/conn-aws/source-bindings",
            bearer,
            "k-bind-aws",
            "{\"id\":\"b-1\",\"source_ref\":\"acct-1\",\"expected_revision\":0}");
    assertEquals(409, bind.statusCode());
    assertThat(bind.body()).contains("CONNECTION_NOT_ACCEPTED");

    String azureConnection = connection("conn-azure", "AZURE", 0);
    assertEquals(201, post("/v1/connections", bearer, "k-azure", azureConnection).statusCode());
    assertThat(get("/v1/connections/conn-azure", bearer).body())
        .contains("\"status\":\"UNACCEPTED\"")
        .contains("\"read_only_identity_ref\":\"00000000-0000-0000-0000-000000000001\"");
    String invalidSubscription =
        azureConnection.replace(
            "00000000-0000-0000-0000-000000000001", "<AZURE_SUBSCRIPTION_ID>");
    assertEquals(
        400, post("/v1/connections", bearer, "k-azure-invalid", invalidSubscription).statusCode());

    String gcpConnection = connection("conn-gcp", "GCP", 0);
    assertEquals(201, post("/v1/connections", bearer, "k-gcp", gcpConnection).statusCode());
    assertThat(get("/v1/connections/conn-gcp", bearer).body())
        .contains("\"status\":\"UNACCEPTED\"")
        .contains("\"read_only_identity_ref\":\"sample-project-123\"");
    String invalidProjectId = gcpConnection.replace("sample-project-123", "Project_ID");
    assertEquals(
        400, post("/v1/connections", bearer, "k-gcp-invalid", invalidProjectId).statusCode());
  }

  @Test
  void readsOnlyBoundSyntheticObjectiveDefinitionsWithoutClaimingVerification() throws Exception {
    String tenant = "tenant-sample-objective";
    String environment = "env-sample-objective";
    String writer = bearer(actor(tenant, environment, RW, List.of("owner-1")), "S03");
    String id = "sample:checkout-recovery";
    String path = "/v1/connections/sample-conn/sample-objective-definition"
        + "?externalObjectiveId=sample:checkout-recovery&targetWorkloadId=workload-a";
    assertEquals(201, post("/v1/connections", writer, "create-sample-objective",
        connection("sample-conn", "SAMPLE_LOCAL", 0)).statusCode());
    assertEquals(201, post("/v1/connections/sample-conn/source-bindings", writer, "bind-sample-objective",
        "{\"id\":\"sample-binding\",\"source_ref\":\"" + id
            + "\",\"service_ref\":\"workload-a\",\"expected_revision\":0}").statusCode());
    Integer events = jdbc.queryForObject(
        "SELECT count(*) FROM uacrf_connections.outbox_event WHERE tenant_id = ?", Integer.class, tenant);

    HttpResponse<String> response = get(path, writer);
    assertEquals(200, response.statusCode());
    JsonNode definition = mapper.readTree(response.body());
    assertThat(definition.path("verification").asString()).isEqualTo("SYNTHETIC_UNVERIFIED");
    assertThat(definition.path("source_system").asString()).isEqualTo("sample:monitoring");
    assertThat(definition.path("external_objective_id").asString()).isEqualTo(id);
    assertThat(definition.path("connection_revision").asLong()).isEqualTo(1);
    assertThat(definition.path("binding_revision").asLong()).isEqualTo(1);
    String payload = definition.path("payload").asString();
    String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(definition.path("payload_digest").asString()).isEqualTo(digest);
    assertThat(mapper.readTree(payload).path("target").asInt()).isEqualTo(720);
    assertEquals(events, jdbc.queryForObject(
        "SELECT count(*) FROM uacrf_connections.outbox_event WHERE tenant_id = ?", Integer.class, tenant));

    String exchanged = sampleExchange(tenant, environment, "sample-conn", "sample-conn",
        "objectives.import.synthetic-source", "connection", 4);
    assertEquals(200, get(path, exchanged).statusCode());
    assertEquals(403, get("/v1/connections/sample-conn", exchanged).statusCode());
    assertEquals(403, get("/v1/connections/sample-conn/source-bindings", exchanged).statusCode());
    assertEquals(403, get(path, sampleExchange(tenant, environment, "other-conn", "other-conn",
        "objectives.import.synthetic-source", "connection", 4)).statusCode());
    assertEquals(403, get(path, sampleExchange(tenant, environment, "sample-conn", "other-conn",
        "objectives.import.synthetic-source", "connection", 4)).statusCode());
    assertEquals(403, get(path, sampleExchange(tenant, environment, "sample-conn", "sample-conn",
        "other-purpose", "connection", 4)).statusCode());
    assertEquals(403, get(path, sampleExchange(tenant, environment, "sample-conn", "sample-conn",
        "objectives.import.synthetic-source", "service", 4)).statusCode());
    assertEquals(403, get(path, sampleExchange(tenant, environment, "sample-conn", "sample-conn",
        "objectives.import.synthetic-source", "connection", 30)).statusCode());
    assertEquals(404, get(path, sampleExchange("other-tenant", environment, "sample-conn", "sample-conn",
        "objectives.import.synthetic-source", "connection", 4)).statusCode());
    assertEquals(404, get(path.replace("workload-a", "other-workload"), exchanged).statusCode());

    assertEquals(404, get(path.replace("workload-a", "other-workload"), writer).statusCode());
    assertEquals(404, get(path, bearer(actor(tenant, "other-env", RW, List.of()), "S03")).statusCode());
    assertEquals(404, get(path, bearer(actor("other-tenant", environment, RW, List.of()), "S03")).statusCode());
    assertEquals(403, get(path, bearer(actor(tenant, environment,
        List.of("connections.connection.write"), List.of()), "S03")).statusCode());
    DelegationPrincipal scoped = new DelegationPrincipal(
        "https://uacrf.local/s01|scoped", tenant, environment, "workload-a",
        "https://idp.example.test", "scoped", List.of("connections.connection.read"), List.of(), List.of());
    assertEquals(403, get(path, bearer(scoped, "S03")).statusCode());
    assertEquals(201, post("/v1/connections", writer, "create-cloud-objective",
        connection("cloud-conn", "AWS", 0)).statusCode());
    assertEquals(404, get(path.replace("sample-conn", "cloud-conn"), writer).statusCode());
    jdbc.update("UPDATE uacrf_connections.connection SET status = 'ACTIVE' "
        + "WHERE tenant_id = ? AND environment_id = ? AND id = 'cloud-conn'", tenant, environment);
    jdbc.update("INSERT INTO uacrf_connections.source_binding "
        + "(tenant_id, environment_id, id, connection_id, source_ref, service_ref, revision) "
        + "VALUES (?, ?, 'cloud-binding', 'cloud-conn', ?, 'workload-a', 1)", tenant, environment, id);
    assertEquals(404, get(path.replace("sample-conn", "cloud-conn"), writer).statusCode());
    assertEquals(201, post("/v1/connections/sample-conn/source-bindings", writer, "bind-unknown-objective",
        "{\"id\":\"unknown-binding\",\"source_ref\":\"sample:missing\","
            + "\"service_ref\":\"workload-a\",\"expected_revision\":0}").statusCode());
    assertEquals(404, get(path.replace(id, "sample:missing"), writer).statusCode());
  }

  @Test
  void bindsSourcesOncePerConnectionAndPagesThem() throws Exception {
    String bearer = bearer(actor("tenant-a", "env-a", RW, List.of("owner-1")), "S03");
    assertEquals(201, post("/v1/connections", bearer, "k-b", connection("conn-bind", "SAMPLE_LOCAL", 0)).statusCode());
    String bind = "{\"id\":\"b-%s\",\"source_ref\":\"%s\",\"service_ref\":\"svc-1\",\"expected_revision\":0}";
    assertEquals(201, post("/v1/connections/conn-bind/source-bindings", bearer, "kb1", bind.formatted("1", "src-1")).statusCode());
    assertEquals(201, post("/v1/connections/conn-bind/source-bindings", bearer, "kb2", bind.formatted("2", "src-2")).statusCode());
    HttpResponse<String> duplicate =
        post("/v1/connections/conn-bind/source-bindings", bearer, "kb3", bind.formatted("3", "src-1"));
    assertEquals(409, duplicate.statusCode());

    HttpResponse<String> page = get("/v1/connections/conn-bind/source-bindings?pageSize=1", bearer);
    assertThat(page.body()).contains("\"id\":\"b-1\"").doesNotContain("b-2").doesNotContain("\"next_cursor\":null");
  }

  @Test
  void reportsScopedCollectionOutcomesWithoutExposingCheckpoints() throws Exception {
    String tenant = "tenant-collection-report";
    String environment = "env-collection-report";
    String bearer = bearer(actor(tenant, environment, RW, List.of("owner-1")), "S03");
    assertEquals(
        201,
        post(
                "/v1/connections",
                bearer,
                "report-connection",
                connection("conn-report", "SAMPLE_LOCAL", 0))
            .statusCode());
    String binding =
        "{\"id\":\"binding-%s\",\"source_ref\":\"source-%s\",\"expected_revision\":0}";
    assertEquals(
        201,
        post(
                "/v1/connections/conn-report/source-bindings",
                bearer,
                "report-binding-1",
                binding.formatted("1", "1"))
            .statusCode());
    assertEquals(
        201,
        post(
                "/v1/connections/conn-report/source-bindings",
                bearer,
                "report-binding-2",
                binding.formatted("2", "2"))
            .statusCode());

    String endpoint = "/v1/connections/conn-report/collection-statuses";
    JsonNode firstPage = mapper.readTree(get(endpoint + "?pageSize=1", bearer).body());
    JsonNode first = firstPage.get("items").get(0);
    assertThat(first.get("id").asString()).isEqualTo("binding-1");
    assertThat(first.get("last_status").asString()).isEqualTo("NEVER_RUN");
    assertThat(first.get("last_detail").asString()).contains("not run");
    assertThat(first.get("running").asBoolean()).isFalse();
    assertThat(first.get("last_collected_at").isNull()).isTrue();
    assertThat(first.get("next_collection_at").isString()).isTrue();
    assertThat(firstPage.get("next_cursor").isString()).isTrue();

    Instant lastCollected = Instant.parse("2026-11-18T08:00:00Z");
    jdbc.update(
        "UPDATE uacrf_connections.collection_checkpoint SET last_status = ?, last_detail = ?, "
            + "last_collected_at = ?, next_collection_at = ?, lease_until = now() + interval '1 minute', "
            + "checkpoint = ? WHERE tenant_id = ? AND environment_id = ? AND connection_id = ? "
            + "AND source_ref = ?",
        "SOURCE_UNAVAILABLE",
        "Provider source is unavailable; retry scheduled.",
        java.sql.Timestamp.from(lastCollected),
        java.sql.Timestamp.from(Instant.parse("2026-11-18T08:35:00Z")),
        "private-page-token",
        tenant,
        environment,
        "conn-report",
        "source-1");
    JsonNode reported = mapper.readTree(get(endpoint + "?pageSize=1", bearer).body());
    JsonNode status = reported.get("items").get(0);
    assertThat(status.get("last_status").asString()).isEqualTo("SOURCE_UNAVAILABLE");
    assertThat(status.get("running").asBoolean()).isTrue();
    assertThat(Instant.parse(status.get("last_collected_at").asString())).isEqualTo(lastCollected);
    assertThat(status.toString())
        .doesNotContain("checkpoint", "private-page-token")
        .contains("next_collection_at");

    String cursor = firstPage.get("next_cursor").asString();
    JsonNode secondPage =
        mapper.readTree(get(endpoint + "?pageSize=1&cursor=" + cursor, bearer).body());
    assertThat(secondPage.get("items").get(0).get("id").asString()).isEqualTo("binding-2");
    assertThat(secondPage.get("items").get(0).get("last_status").asString()).isEqualTo("NEVER_RUN");

    String otherTenant =
        bearer(actor("tenant-other", environment, RW, List.of("owner-1")), "S03");
    assertEquals(404, get(endpoint, otherTenant).statusCode());
    String noRead =
        bearer(
            actor(
                tenant,
                environment,
                List.of("connections.connection.write"),
                List.of("owner-1")),
            "S03");
    assertEquals(403, get(endpoint, noRead).statusCode());
  }

  @Test
  void collectionLeasesAreExclusiveAndRetainTheCheckpointForRetry() throws Exception {
    String tenant = "tenant-collection-lease";
    String bearer = bearer(actor(tenant, "env-lease", RW, List.of("owner-1")), "S03");
    assertEquals(
        201,
        post(
                "/v1/connections",
                bearer,
                "lease-connection",
                connection("conn-lease", "SAMPLE_LOCAL", 0))
            .statusCode());
    assertEquals(
        201,
        post(
                "/v1/connections/conn-lease/source-bindings",
                bearer,
                "lease-binding",
                "{\"id\":\"binding-lease\",\"source_ref\":\"source-lease\",\"expected_revision\":0}")
            .statusCode());

    List<CollectionCheckpointStore.LeasedSource> firstClaims =
        collectionCheckpoints.claimDue(1000, Duration.ofMillis(100));
    CollectionCheckpointStore.LeasedSource lease =
        firstClaims.stream()
            .filter(
                source ->
                    source.tenantId().equals(tenant)
                        && source.connectionId().equals("conn-lease"))
            .findFirst()
            .orElseThrow();
    assertThat(lease.lastSequence()).isZero();
    collectionCheckpoints.renew(lease, Duration.ofMinutes(2));
    Thread.sleep(150);
    assertThat(
            collectionCheckpoints.claimDue(1000, Duration.ofMinutes(2)))
        .noneMatch(
            source ->
                source.tenantId().equals(tenant)
                    && source.connectionId().equals("conn-lease"));

    Instant collectedAt = Instant.parse("2026-11-17T17:00:00Z");
    ProviderCollector.Observation observation =
        new ProviderCollector.Observation(
            "pending-obs",
            1,
            collectedAt,
            "aws.config.resources",
            "{\"resource\":\"fixture\"}");
    collectionCheckpoints.storePending(lease, List.of(observation), "next-page", collectedAt);
    collectionCheckpoints.complete(
        lease,
        "FAILED",
        "Collection attempt failed; retry scheduled.",
        lease.checkpoint(),
        lease.lastCollectedAt(),
        Instant.now().minusMillis(1),
        false);
    CollectionCheckpointStore.LeasedSource retry =
        collectionCheckpoints.claimDue(1000, Duration.ofMinutes(2)).stream()
            .filter(
                source ->
                    source.tenantId().equals(tenant)
                        && source.connectionId().equals("conn-lease"))
            .findFirst()
            .orElseThrow();
    assertThat(retry.leaseToken()).isNotEqualTo(lease.leaseToken());
    assertThrows(
        IllegalStateException.class,
        () -> collectionCheckpoints.renew(lease, Duration.ofMinutes(2)));
    assertThat(retry.checkpoint()).isNull();
    assertThat(retry.lastSequence()).isZero();
    assertThat(retry.pendingObservations()).containsExactly(observation);
    assertThat(retry.pendingNextCheckpoint()).isEqualTo("next-page");
    assertThat(retry.pendingCollectedAt()).isEqualTo(collectedAt);

    collectionCheckpoints.complete(
        retry,
        "COLLECTED",
        "Batch submitted to observation intake.",
        retry.pendingNextCheckpoint(),
        retry.pendingCollectedAt(),
        Instant.now().plusSeconds(60),
        true);
    assertThat(
            jdbc.queryForObject(
                "SELECT pending_observations IS NULL AND pending_collected_at IS NULL "
                    + "AND checkpoint = 'next-page' FROM uacrf_connections.collection_checkpoint "
                    + "WHERE tenant_id = ? AND connection_id = 'conn-lease'",
                Boolean.class,
                tenant))
        .isTrue();
  }

  @Test
  void enforcesAudienceCapabilityOwnerAndTenantBoundaries() throws Exception {
    String wrongAudience = bearer(actor("tenant-a", "env-a", RW, List.of("owner-1")), "S02");
    assertEquals(401, get("/v1/connections", wrongAudience).statusCode());

    String readOnly =
        bearer(actor("tenant-a", "env-a", List.of("connections.connection.read"), List.of("owner-1")), "S03");
    assertEquals(403, post("/v1/connections", readOnly, "k-ro", connection("conn-ro", "SAMPLE_LOCAL", 0)).statusCode());

    String noOwner = bearer(actor("tenant-a", "env-a", RW, List.of()), "S03");
    assertEquals(403, post("/v1/connections", noOwner, "k-no", connection("conn-no", "SAMPLE_LOCAL", 0)).statusCode());

    String tenantA = bearer(actor("tenant-a", "env-a", RW, List.of("owner-1")), "S03");
    assertEquals(201, post("/v1/connections", tenantA, "k-t", connection("conn-tenant", "SAMPLE_LOCAL", 0)).statusCode());
    String tenantB = bearer(actor("tenant-b", "env-a", RW, List.of("owner-1")), "S03");
    assertEquals(404, get("/v1/connections/conn-tenant", tenantB).statusCode());
    assertThat(get("/v1/connections", tenantB).body()).doesNotContain("conn-tenant");
    assertEquals(401, http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/connections")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  private record Ingested(String connectionId, String bearer, int accepted, int quarantined) {}

  private Ingested ingest(String fixture, String tenant, String environment) throws Exception {
    JsonNode sample = mapper.readTree(Files.readString(Path.of("../../sample-data/connections/" + fixture)));
    String connectionId = sample.get("connection").get("id").asString();
    String bearer =
        bearer(
            actor(
                tenant,
                environment,
                List.of(
                    "connections.connection.write",
                    "connections.connection.read",
                    "connections.observation.write"),
                List.of("owner-platform")),
            "S03");
    assertEquals(
        201, post("/v1/connections", bearer, "sample-conn", sample.get("connection").toString()).statusCode());
    for (JsonNode binding : sample.get("bindings")) {
      assertEquals(
          201,
          post(
                  "/v1/connections/" + connectionId + "/source-bindings",
                  bearer,
                  "sample-" + binding.get("id").asString(),
                  binding.toString())
              .statusCode());
    }
    int accepted = 0;
    int quarantined = 0;
    for (JsonNode delivery : sample.get("deliveries")) {
      String expect = delivery.get("expect").asString();
      HttpResponse<String> response =
          post("/v1/connections/" + connectionId + "/observations", bearer, null, delivery.get("observation").toString());
      assertEquals(202, response.statusCode(), response.body());
      JsonNode result = mapper.readTree(response.body());
      String actual =
          "QUARANTINED".equals(result.get("status").asString())
              ? "QUARANTINED:" + result.get("reason").asString()
              : result.get("status").asString();
      assertEquals(expect, actual, delivery.get("observation").get("id").asString());
      accepted += "ACCEPTED".equals(actual) ? 1 : 0;
      quarantined += actual.startsWith("QUARANTINED") ? 1 : 0;
    }
    return new Ingested(connectionId, bearer, accepted, quarantined);
  }

  @Test
  void ingestsTheMultiCloudSampleEstateWithProviderShapedFeedsAndFaults() throws Exception {
    Ingested run = ingest("sample-multicloud-estate.json", "tenant-sample-mc", "env-sample-mc");

    assertEquals(
        run.accepted(),
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_connections.observation WHERE connection_id = ?",
            Integer.class,
            run.connectionId()));
    assertEquals(4, run.quarantined());
    assertThat(get("/v1/connections/" + run.connectionId() + "/quarantine", run.bearer()).body())
        .contains("STALE_SEQUENCE", "DIGEST_MISMATCH", "REPLAY_CONFLICT");
    for (String kind :
        List.of(
            "aws.cloudwatch.metrics", "azure.monitor.metrics", "gcp.monitoring.timeseries",
            "aws.config.resources", "azure.resourcegraph.rows", "gcp.assetinventory.assets",
            "aws.error", "azure.error", "gcp.error")) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM uacrf_connections.observation WHERE connection_id = ? AND kind = ?",
                  Integer.class,
                  run.connectionId(),
                  kind))
          .as(kind)
          .isPositive();
    }
  }

  @Test
  void projectsSampleInventoryWithRawProvenanceAndScopedPagination() throws Exception {
    Ingested run = ingest("sample-multicloud-estate.json", "tenant-inventory", "env-inventory");
    String endpoint = "/v1/connections/" + run.connectionId() + "/inventory-observations";
    HttpResponse<String> response = get(endpoint + "?pageSize=100", run.bearer());
    assertEquals(200, response.statusCode(), response.body());
    JsonNode items = mapper.readTree(response.body()).get("items");
    assertEquals(42, items.size());
    assertThat(response.body()).contains("\"provider\":\"AWS\"", "\"provider\":\"AZURE\"", "\"provider\":\"GCP\"");
    for (JsonNode item : items) {
      assertEquals("SAMPLE_LOCAL", item.get("source_class").asString());
      assertEquals("inventory-observation.v1", item.get("normalizer_version").asString());
      String id = item.get("raw_observation_id").asString();
      var retained =
          jdbc.queryForMap(
              "SELECT source_ref, kind, payload, payload_digest FROM uacrf_connections.observation "
                  + "WHERE tenant_id = ? AND environment_id = ? AND connection_id = ? AND id = ?",
              "tenant-inventory", "env-inventory", run.connectionId(), id);
      assertEquals(retained.get("source_ref"), item.get("source_ref").asString());
      assertEquals(retained.get("kind"), item.get("raw_kind").asString());
      assertEquals(retained.get("payload_digest"), item.get("raw_payload_digest").asString());
      JsonNode raw = mapper.readTree((String) retained.get("payload"));
      String provider = item.get("provider").asString();
      assertEquals(raw.get(provider.equals("AWS") ? "resourceId" : provider.equals("AZURE") ? "id" : "name").asString(), item.get("native_id").asString());
      if (provider.equals("GCP")) {
        assertThat(item.get("location").isNull()).isTrue();
      }
    }

    JsonNode first = mapper.readTree(get(endpoint + "?pageSize=1", run.bearer()).body());
    String cursor = first.get("next_cursor").asString();
    JsonNode second = mapper.readTree(get(endpoint + "?pageSize=1&cursor=" + cursor, run.bearer()).body());
    assertThat(first.get("items").get(0).get("raw_observation_id").asString())
        .isNotEqualTo(second.get("items").get(0).get("raw_observation_id").asString());
    String otherTenant =
        bearer(actor("tenant-other", "env-inventory", RW, List.of("owner-platform")), "S03");
    assertEquals(404, get(endpoint, otherTenant).statusCode());
    String noRead =
        bearer(actor("tenant-inventory", "env-inventory", List.of("connections.observation.write"), List.of("owner-platform")), "S03");
    assertEquals(403, get(endpoint, noRead).statusCode());
    jdbc.update(
        "UPDATE uacrf_connections.observation SET payload = ? WHERE tenant_id = ? "
            + "AND environment_id = ? AND connection_id = ? AND id = ?",
        "{}", "tenant-inventory", "env-inventory", run.connectionId(),
        first.get("items").get(0).get("raw_observation_id").asString());
    assertEquals(500, get(endpoint, run.bearer()).statusCode());
  }

  @Test
  void ingestsTheSampleEstateAndQuarantinesItsDeliberateFaults() throws Exception {
    Ingested run = ingest("sample-local-estate.json", "tenant-sample", "env-sample");
    String connectionId = run.connectionId();
    String bearer = run.bearer();
    int accepted = run.accepted();

    assertEquals(
        accepted,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_connections.observation WHERE connection_id = ?",
            Integer.class,
            connectionId));
    assertEquals(3, run.quarantined());
    assertThat(get("/v1/connections/" + connectionId + "/quarantine", bearer).body())
        .contains("STALE_SEQUENCE", "DIGEST_MISMATCH", "REPLAY_CONFLICT");
    assertThat(get("/v1/connections/" + connectionId + "/observations?pageSize=2", bearer).body())
        .contains("\"next_cursor\":\"");
    assertEquals(
        accepted,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_connections.outbox_event WHERE event_type = "
                + "'connections.observation.accepted' AND tenant_id = 'tenant-sample'",
            Integer.class));

    String unbound =
        "{\"id\":\"x\",\"source_ref\":\"sample:unbound\",\"sequence\":1,"
            + "\"observed_at\":\"2026-11-12T09:00:00Z\",\"kind\":\"service.health\","
            + "\"payload\":\"{}\",\"payload_digest\":\""
            + "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a\"}";
    HttpResponse<String> notBound =
        post("/v1/connections/" + connectionId + "/observations", bearer, null, unbound);
    assertEquals(409, notBound.statusCode());
    assertThat(notBound.body()).contains("SOURCE_NOT_BOUND");
    String readOnly =
        bearer(
            actor("tenant-sample", "env-sample", List.of("connections.connection.read"), List.of()),
            "S03");
    assertEquals(
        403,
        post("/v1/connections/" + connectionId + "/observations", readOnly, null, unbound)
            .statusCode());
  }

  @Test
  void onlyExactObservationReplaysAreDuplicatesAndSequenceConflictsAreRetained() throws Exception {
    String bearer =
        bearer(
            actor(
                "tenant-replay",
                "env-replay",
                List.of(
                    "connections.connection.write",
                    "connections.connection.read",
                    "connections.observation.write"),
                List.of("owner-1")),
            "S03");
    assertEquals(
        201,
        post(
                "/v1/connections",
                bearer,
                "replay-connection",
                connection("conn-replay", "SAMPLE_LOCAL", 0))
            .statusCode());
    assertEquals(
        201,
        post(
                "/v1/connections/conn-replay/source-bindings",
                bearer,
                "replay-binding",
                "{\"id\":\"binding-replay\",\"source_ref\":\"source-replay\","
                    + "\"expected_revision\":0}")
            .statusCode());

    String original =
        observation(
            "obs-1",
            "source-replay",
            "2026-11-17T10:00:00Z",
            "service.health",
            "{}",
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
    HttpResponse<String> accepted =
        post("/v1/connections/conn-replay/observations", bearer, null, original);
    assertThat(accepted.body()).contains("\"status\":\"ACCEPTED\"");
    HttpResponse<String> duplicate =
        post("/v1/connections/conn-replay/observations", bearer, null, original);
    assertThat(duplicate.body()).contains("\"status\":\"DUPLICATE\"");

    HttpResponse<String> changedKind =
        post(
            "/v1/connections/conn-replay/observations",
            bearer,
            null,
            observation(
                "obs-1",
                "source-replay",
                "2026-11-17T10:00:00Z",
                "service.config",
                "{}",
                "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"));
    HttpResponse<String> changedTime =
        post(
            "/v1/connections/conn-replay/observations",
            bearer,
            null,
            observation(
                "obs-1",
                "source-replay",
                "2026-11-17T10:00:01Z",
                "service.health",
                "{}",
                "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"));
    HttpResponse<String> changedPayload =
        post(
            "/v1/connections/conn-replay/observations",
            bearer,
            null,
            observation(
                "obs-1",
                "source-replay",
                "2026-11-17T10:00:00Z",
                "service.health",
                "[]",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945"));
    HttpResponse<String> changedId =
        post(
            "/v1/connections/conn-replay/observations",
            bearer,
            null,
            observation(
                "obs-2",
                "source-replay",
                "2026-11-17T10:00:00Z",
                "service.health",
                "{}",
                "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"));

    for (HttpResponse<String> conflict :
        List.of(changedKind, changedTime, changedPayload, changedId)) {
      assertEquals(202, conflict.statusCode());
      assertThat(conflict.body()).contains("\"status\":\"QUARANTINED\"", "REPLAY_CONFLICT");
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_connections.observation WHERE tenant_id = 'tenant-replay' "
                + "AND connection_id = 'conn-replay'",
            Integer.class));
    assertEquals(
        4,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_connections.quarantined_observation "
                + "WHERE tenant_id = 'tenant-replay' AND connection_id = 'conn-replay' "
                + "AND reason = 'REPLAY_CONFLICT'",
            Integer.class));
  }

  @Test
  void preservesNanosecondInstantsAndFailsClosedForLegacyReplay() throws Exception {
    String bearer = intakeBearer("tenant-time");
    setupIntake(bearer, "conn-time", "source-time");
    String path = "/v1/connections/conn-time/observations";
    String original = sampleObservation("nano", "source-time", "2026-11-17T10:00:00.123456101Z");
    String changed = sampleObservation("nano", "source-time", "2026-11-17T10:00:00.123456102Z");

    assertThat(post(path, bearer, null, original).body()).contains("\"status\":\"ACCEPTED\"");
    assertThat(post(path, bearer, null, original).body()).contains("\"status\":\"DUPLICATE\"");
    assertThat(post(path, bearer, null, changed).body()).contains("REPLAY_CONFLICT");
    assertThat(get(path, bearer).body()).contains("2026-11-17T10:00:00.123456101Z");
    assertEquals(
        Boolean.TRUE,
        jdbc.queryForObject(
            "SELECT observed_at = ? FROM uacrf_connections.observation "
                + "WHERE tenant_id = 'tenant-time' AND id = 'nano'",
            Boolean.class,
            java.sql.Timestamp.from(java.time.Instant.parse("2026-11-17T10:00:00.123456102Z"))));

    jdbc.update(
        "UPDATE uacrf_connections.observation SET observed_at_epoch_second = NULL, "
            + "observed_at_nano = NULL WHERE tenant_id = 'tenant-time' AND id = 'nano'");
    assertThat(post(path, bearer, null, original).body()).contains("REPLAY_CONFLICT");
    assertThat(post(path, bearer, null, changed).body()).contains("REPLAY_CONFLICT");
    assertThat(get(path, bearer).body()).contains("2026-11-17T10:00:00.123456Z");

    setupIntake(bearer, "conn-round", "source-round");
    String roundPath = "/v1/connections/conn-round/observations";
    String nearBoundary =
        sampleObservation("nano-round", "source-round", "2026-11-17T10:00:00.999999800Z");
    String changedBoundary =
        sampleObservation("nano-round", "source-round", "2026-11-17T10:00:00.999999900Z");
    assertThat(post(roundPath, bearer, null, nearBoundary).body()).contains("\"status\":\"ACCEPTED\"");
    assertThat(post(roundPath, bearer, null, nearBoundary).body()).contains("\"status\":\"DUPLICATE\"");
    assertThat(post(roundPath, bearer, null, changedBoundary).body()).contains("REPLAY_CONFLICT");
    assertThat(get(roundPath, bearer).body()).contains("2026-11-17T10:00:00.999999800Z");
  }

  @Test
  void serializesSameObservationIdAcrossDifferentBindings() throws Exception {
    String bearer = intakeBearer("tenant-race");
    setupIntake(bearer, "conn-race", "source-a");
    assertEquals(
        201,
        post(
                "/v1/connections/conn-race/source-bindings",
                bearer,
                "bind-b",
                "{\"id\":\"binding-b\",\"source_ref\":\"source-b\",\"expected_revision\":0}")
            .statusCode());
    String path = "/v1/connections/conn-race/observations";
    CompletableFuture<HttpResponse<String>> first;
    CompletableFuture<HttpResponse<String>> second;
    try (java.sql.Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (var statement =
          lock.prepareStatement(
              "SELECT id FROM uacrf_connections.source_binding WHERE tenant_id = ? "
                  + "AND environment_id = ? AND connection_id = ? AND source_ref = ? FOR UPDATE")) {
        statement.setString(1, "tenant-race");
        statement.setString(2, "env-test");
        statement.setString(3, "conn-race");
        statement.setString(4, "source-a");
        statement.executeQuery();
      }
      first =
          postAsync(
              path, bearer, sampleObservation("race-id", "source-a", "2026-11-17T10:00:00Z"));
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline
            && jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                        + "AND wait_event_type = 'Lock' AND query LIKE '%last_sequence%source_binding%FOR UPDATE%'",
                    Integer.class)
                == 0) {
          Thread.sleep(25);
        }
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                        + "AND wait_event_type = 'Lock' AND query LIKE '%last_sequence%source_binding%FOR UPDATE%'",
                    Integer.class))
            .isPositive();
        second =
            postAsync(
                path, bearer, sampleObservation("race-id", "source-b", "2026-11-17T10:00:00Z"));
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline
            && jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                        + "AND wait_event_type = 'Lock' AND query LIKE '%pg_advisory_xact_lock%'",
                    Integer.class)
                == 0
            && !second.isDone()) {
          Thread.sleep(25);
        }
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                        + "AND wait_event_type = 'Lock' AND query LIKE '%pg_advisory_xact_lock%'",
                    Integer.class))
            .isPositive();
        assertThrows(TimeoutException.class, () -> second.get(500, TimeUnit.MILLISECONDS));
      } finally {
        lock.rollback();
      }
    }
    assertThat(first.get(10, TimeUnit.SECONDS).body()).contains("\"status\":\"ACCEPTED\"");
    HttpResponse<String> rejected = second.get(10, TimeUnit.SECONDS);
    assertEquals(202, rejected.statusCode());
    assertThat(rejected.body()).contains("REPLAY_CONFLICT");
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_connections.observation WHERE tenant_id = 'tenant-race'",
            Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM uacrf_connections.outbox_event WHERE tenant_id = 'tenant-race' "
                + "AND event_type = 'connections.observation.accepted'",
            Integer.class));
  }

  private String intakeBearer(String tenant) {
    return bearer(
        actor(
            tenant,
            "env-test",
            List.of(
                "connections.connection.write",
                "connections.connection.read",
                "connections.observation.write"),
            List.of("owner-1")),
        "S03");
  }

  private void setupIntake(String bearer, String connectionId, String sourceRef) throws Exception {
    assertEquals(
        201,
        post("/v1/connections", bearer, "create-" + connectionId, connection(connectionId, "SAMPLE_LOCAL", 0))
            .statusCode());
    assertEquals(
        201,
        post(
                "/v1/connections/" + connectionId + "/source-bindings",
                bearer,
                "bind",
                "{\"id\":\"binding-" + connectionId + "\",\"source_ref\":\"" + sourceRef
                    + "\",\"expected_revision\":0}")
            .statusCode());
  }

  private static String sampleObservation(String id, String sourceRef, String observedAt) {
    return observation(
        id,
        sourceRef,
        observedAt,
        "service.health",
        "{}",
        "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
  }

  private CompletableFuture<HttpResponse<String>> postAsync(
      String path, String bearer, String body) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", bearer)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String observation(
      String id, String sourceRef, String observedAt, String kind, String payload, String digest) {
    return (
            "{\"id\":\"%s\",\"source_ref\":\"%s\",\"sequence\":1,"
                + "\"observed_at\":\"%s\",\"kind\":\"%s\",\"payload\":\"%s\","
                + "\"payload_digest\":\"%s\"}")
        .formatted(id, sourceRef, observedAt, kind, payload, digest);
  }

  private static String connection(String id, String provider, long revision) {
    String identityRef =
        provider.equals("AWS")
            ? "arn:aws:iam::123456789012:role/readonly"
            : provider.equals("AZURE")
                ? "00000000-0000-0000-0000-000000000001"
                : provider.equals("GCP") ? "sample-project-123" : "role/readonly";
    return "{\"id\":\"%s\",\"name\":\"Sample source\",\"provider\":\"%s\",\"read_only_identity_ref\":\"%s\",\"owner_id\":\"owner-1\",\"expected_revision\":%d}"
        .formatted(id, provider, identityRef, revision);
  }

  private HttpResponse<String> post(String path, String bearer, String key, String body) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", bearer)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path, String bearer) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", bearer)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String bearer(DelegationPrincipal principal, String audience) {
    return "Bearer "
        + new DelegationTokenIssuer(ENCODER, Clock.systemUTC(), "https://uacrf.local/s01", KEY_ID)
            .issue(principal, audience);
  }

  private String sampleExchange(String tenant, String environment, String scope, String resourceId,
      String purpose, String resourceType, int lifetimeSeconds) {
    Instant issued = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
        .issuer("https://uacrf.local/s01").subject("exchange-user").audience(List.of("S03"))
        .issuedAt(issued).expiresAt(issued.plusSeconds(lifetimeSeconds))
        .claim("tenant_id", tenant).claim("environment_id", environment)
        .claim("actor_issuer", "https://idp.example.test").claim("actor_subject", "user-1")
        .claim("service_scope", scope).claim("capabilities", List.of("connections.sample-objective.read"))
        .claim("resource_id", resourceId).claim("resource_type", resourceType).claim("purpose", purpose)
        .build();
    return "Bearer " + ENCODER.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
        org.springframework.security.oauth2.jwt.JwsHeader.with(
            org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).keyId(KEY_ID).build(),
        claims)).getTokenValue();
  }

  private static DelegationPrincipal actor(
      String tenant, String environment, List<String> capabilities, List<String> owners) {
    return new DelegationPrincipal(
        "https://uacrf.local/s01|user-1",
        tenant,
        environment,
        "*",
        "https://idp.example.test",
        "user-1",
        capabilities,
        owners,
        List.of());
  }

  private static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
