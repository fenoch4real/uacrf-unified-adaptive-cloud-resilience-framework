package org.uacrf.connections;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
  @Autowired private ObjectMapper mapper;
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
    assertEquals(201, post("/v1/connections", bearer, "k-aws", connection("conn-aws", "AWS", 0)).statusCode());
    assertThat(get("/v1/connections/conn-aws", bearer).body()).contains("\"status\":\"UNACCEPTED\"");

    HttpResponse<String> bind =
        post(
            "/v1/connections/conn-aws/source-bindings",
            bearer,
            "k-bind-aws",
            "{\"id\":\"b-1\",\"source_ref\":\"acct-1\",\"expected_revision\":0}");
    assertEquals(409, bind.statusCode());
    assertThat(bind.body()).contains("CONNECTION_NOT_ACCEPTED");
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

  private static String connection(String id, String provider, long revision) {
    return "{\"id\":\"%s\",\"name\":\"Sample source\",\"provider\":\"%s\",\"read_only_identity_ref\":\"role/readonly\",\"owner_id\":\"owner-1\",\"expected_revision\":%d}"
        .formatted(id, provider, revision);
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
