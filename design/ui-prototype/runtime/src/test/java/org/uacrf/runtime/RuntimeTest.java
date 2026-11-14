package org.uacrf.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RuntimeTest {
  static final String IMAGE =
      "postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81";
  static final String ISSUER = "https://identity.test.example",
      SOURCE = "https://directory.test.example";
  static String container = "uacrf-java-" + UUID.randomUUID().toString().substring(0, 8),
      url,
      adminPassword,
      apiPassword;
  static final String NEO_IMAGE =
      "neo4j@sha256:adaa077a3ab023015143b8f73f7076a7d053f12985f5b06f4c6a128c3a2f7240";
  static String neoContainer = "uacrf-graph-" + UUID.randomUUID().toString().substring(0, 8),
      neoPassword,
      workerPassword;
  static boolean neoStarted;
  static GraphStore graphStore;
  static GraphWorker graphWorker;
  static GraphConfig graphConfig;
  static String pgVersion, neoVersion;
  static int port;
  static boolean started;
  static KeyPair pair;
  static Config cfg;
  static WebServerApplicationContext context;
  static Identity.Source directory;
  static Map<String, String> assertions = new ConcurrentHashMap<>();
  static Path root = Path.of("..").toAbsolutePath().normalize();

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(Api.class)
  static class TestApp {
    @Bean
    Config config() {
      return cfg;
    }

    @Bean
    Database database() {
      var d = new Database(cfg);
      d.checkRole();
      return d;
    }

    @Bean
    Contracts contracts() throws Exception {
      var c = new Contracts(root);
      c.verifyAll();
      return c;
    }

    @Bean(destroyMethod = "close")
    GraphService graphs() {
      return new GraphService(cfg, new Database(cfg), graphStore);
    }

    @Bean
    Identity.Source source() {
      return directory;
    }
  }

  static String command(String... args) throws Exception {
    var p = new ProcessBuilder(args).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0)
      throw new IllegalStateException("Test infrastructure command failed: " + args[0]);
    return out.trim();
  }

  static Connection admin() throws SQLException {
    return DriverManager.getConnection(url, "postgres", adminPassword);
  }

  static void sql(String statement, Object... params) throws Exception {
    try (var c = admin()) {
      boolean foreign = statement.contains("'FOREIGN'");
      Admin.execute(
          c,
          "SELECT"
              + " set_config('app.tenant_id',?,false),set_config('app.environment_id','E',false),set_config('app.actor_id',?,false)",
          foreign ? "FOREIGN" : "T",
          foreign ? "foreign-owner" : "admin");
      Admin.execute(c, statement, params);
    }
  }

  static String scalar(String statement, Object... params) throws Exception {
    try (var c = admin()) {
      return Admin.scalar(c, statement, params);
    }
  }

  static String signed(
      String issuer, String aud, String sub, int lifetime, Map<String, Object> more)
      throws Exception {
    long now = Instant.now().getEpochSecond();
    ObjectNode n =
        Json.M.createObjectNode()
            .put("iss", issuer)
            .put("aud", aud)
            .put("sub", sub)
            .put("iat", now)
            .put("nbf", now)
            .put("exp", now + lifetime)
            .put("jti", UUID.randomUUID().toString());
    more.forEach((k, v) -> n.set(k, Json.M.valueToTree(v)));
    var jws =
        new JWSObject(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),
            new Payload(Json.text(n)));
    jws.sign(new RSASSASigner((RSAPrivateKey) pair.getPrivate()));
    return jws.serialize();
  }

  static String token(String sub) throws Exception {
    return signed(ISSUER, "uacrf-api", sub, 600, Map.of());
  }

  static void assertion(String kind, String ref, ObjectNode fields) throws Exception {
    ObjectNode claims =
        fields
            .deepCopy()
            .put("purpose", kind)
            .put("reference", ref)
            .put("tenant_id", "T")
            .put("environment_id", "E");
    Map<String, Object> map = Json.M.convertValue(claims, Map.class);
    assertions.put(
        kind + "/" + ref, signed(SOURCE, "uacrf-directory", "trusted-admission-source", 180, map));
  }

  static ObjectNode body(String json) {
    return (ObjectNode) Json.read(json);
  }

  static void human(String subject) throws Exception {
    assertion(
        "identity",
        subject,
        Json.M.createObjectNode()
            .put("identity_issuer", ISSUER)
            .put("identity_subject", subject)
            .put("display_name", subject)
            .put("identity_kind", "HUMAN"));
  }

  record Reply(int status, JsonNode body) {}

  static Reply request(String method, String path, JsonNode b, String token, String key)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + token);
    if (method.equals("POST"))
      builder
          .header("Content-Type", "application/json")
          .header("Idempotency-Key", key == null ? UUID.randomUUID().toString() : key);
    var response =
        HttpClient.newHttpClient()
            .send(
                builder
                    .method(
                        method,
                        b == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(Json.text(b)))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    return new Reply(response.statusCode(), Json.read(response.body()));
  }

  static Reply get(String path) throws Exception {
    return request("GET", path, null, token("alice"), null);
  }

  static Reply post(String path, String json) throws Exception {
    return request("POST", path, body(json), token("alice"), null);
  }

  static void status(int expected, Reply r) {
    assertEquals(expected, r.status, Json.text(r.body));
  }

  @BeforeAll
  static void setup() throws Exception {
    var g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    pair = g.generateKeyPair();
    adminPassword = UUID.randomUUID() + UUID.randomUUID().toString();
    apiPassword = UUID.randomUUID() + UUID.randomUUID().toString();
    command("docker", "image", "inspect", IMAGE);
    command(
        "docker",
        "run",
        "--detach",
        "--rm",
        "--name",
        container,
        "--publish",
        "127.0.0.1::5432",
        "--env",
        "POSTGRES_PASSWORD=" + adminPassword,
        IMAGE);
    started = true;
    JsonNode ports =
        Json.read(
            command("docker", "inspect", "--format", "{{json .NetworkSettings.Ports}}", container));
    url =
        "jdbc:postgresql://127.0.0.1:"
            + ports.path("5432/tcp").get(0).path("HostPort").asText()
            + "/postgres?connectTimeout=5&socketTimeout=15";
    for (int i = 0; i < 100; i++) {
      try (var c = admin()) {
        break;
      } catch (SQLException e) {
        if (i == 99) throw e;
        Thread.sleep(100);
      }
    }
    Path failing = Files.createTempDirectory("uacrf-install-rollback-");
    try {
      try (var scripts = Files.list(root.resolve("database/postgres"))) {
        for (var p : scripts.filter(p -> p.toString().endsWith(".sql")).toList()) {
          Path target = failing.resolve("database/postgres").resolve(p.getFileName());
          Files.createDirectories(target.getParent());
          Files.copy(p, target);
        }
      }
      for (String name :
          List.of(
              "relationship_design/storage.proposed.sql",
              "identity_design/storage.proposed.sql",
              "runtime/001_runtime.sql",
              "runtime/002_graph_runtime.sql",
              "runtime/003_assurance_runtime.sql")) {
        Path target = failing.resolve(name);
        Files.createDirectories(target.getParent());
        Files.copy(root.resolve(name), target);
      }
      Files.writeString(
          failing.resolve("runtime/001_runtime.sql"),
          "\nSELECT nonexistent_installation_test_function();\n",
          StandardOpenOption.APPEND);
      try (var c = admin()) {
        assertThrows(
            SQLException.class, () -> Admin.install(c, failing, "runtime_login", apiPassword));
        assertNull(Admin.scalar(c, "SELECT 1 FROM pg_namespace WHERE nspname='uacrf'"));
        assertNull(Admin.scalar(c, "SELECT 1 FROM pg_roles WHERE rolname='uacrf_runtime_owner'"));
      }
    } finally {
      try (var paths = Files.walk(failing)) {
        for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
    }
    try (var c = admin()) {
      Admin.install(c, root, "runtime_login", apiPassword);
    }
    byte[] cursor = new byte[32];
    new SecureRandom().nextBytes(cursor);
    cfg =
        new Config(
            "T",
            "E",
            ISSUER,
            "uacrf-api",
            Map.of("test", (RSAPublicKey) pair.getPublic()),
            "test",
            "Test identity provider",
            "https://directory.test.example/api",
            SOURCE,
            "uacrf-directory",
            Map.of("test", (RSAPublicKey) pair.getPublic()),
            url,
            "runtime_login",
            apiPassword,
            cursor,
            root);
    directory =
        (kind, ref) -> {
          String s = assertions.get(kind + "/" + ref);
          if (s == null) throw new Problem(503, "Synthetic signed source unavailable");
          try {
            return Identity.assertion(cfg, kind, ref, s);
          } catch (Problem p) {
            throw new Problem(503, "Source verification rejected");
          }
        };
    human("alice");
    human("witness");
    try (var c = admin()) {
      Admin.bootstrap(
          c, cfg, directory, token("alice"), "admin", "Initial test administrator", null);
    }
    sql(
        "INSERT INTO uacrf.principal(tenant_id,environment_id,id,issuer,subject,display_name,kind)"
            + " VALUES('T','E','reviewer',?,'reviewer','Independent reviewer','HUMAN')",
        ISSUER);
    sql(
        "INSERT INTO"
            + " uacrf.membership(tenant_id,environment_id,id,principal_id,capability,service_scope,expires_at)"
            + " VALUES('T','E','review-grant','reviewer','identity.grant.approve','*',now()+interval"
            + " '1 hour')");
    sql(
        "INSERT INTO uacrf.service(tenant_id,environment_id,id,name,owner_id,criticality,lifecycle)"
            + " VALUES('T','E','checkout','Checkout','admin',1,'ACTIVE')");
    neoPassword = UUID.randomUUID().toString();
    workerPassword = UUID.randomUUID() + UUID.randomUUID().toString();
    command("docker", "image", "inspect", NEO_IMAGE);
    command(
        "docker",
        "run",
        "--detach",
        "--name",
        neoContainer,
        "--publish",
        "127.0.0.1:" + freePort() + ":7687",
        "--env",
        "NEO4J_AUTH=neo4j/" + neoPassword,
        "--env",
        "NEO4J_server_memory_heap_initial__size=256m",
        "--env",
        "NEO4J_server_memory_heap_max__size=256m",
        "--env",
        "NEO4J_server_memory_pagecache_size=128m",
        NEO_IMAGE);
    neoStarted = true;
    var neoPorts =
        Json.read(
            command(
                "docker", "inspect", "--format", "{{json .NetworkSettings.Ports}}", neoContainer));
    graphConfig =
        new GraphConfig(
            "bolt://127.0.0.1:" + neoPorts.path("7687/tcp").get(0).path("HostPort").asText(),
            "neo4j",
            "neo4j",
            neoPassword);
    graphStore = new GraphStore(graphConfig, root);
    for (int i = 0; i < 120; i++) {
      try {
        graphStore.driver.verifyConnectivity();
        break;
      } catch (Exception ex) {
        if (i == 119) throw ex;
        Thread.sleep(250);
      }
    }
    graphStore.constraints(root);
    pgVersion = scalar("SHOW server_version");
    try (var session =
        graphStore.driver.session(org.neo4j.driver.SessionConfig.forDatabase("neo4j"))) {
      neoVersion =
          session
              .run("CALL dbms.components() YIELD versions RETURN versions[0] AS version")
              .single()
              .get("version")
              .asString();
    }
    try (var c = admin()) {
      String ddl =
          Admin.scalar(
              c,
              "SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD"
                  + " %L','graph_login',?::text)",
              workerPassword);
      try (var statement = c.createStatement()) {
        statement.execute(ddl);
        statement.execute("GRANT uacrf_graph_worker TO graph_login");
      }
      Admin.execute(
          c,
          "INSERT INTO uacrf_app.graph_worker_binding(login_name,tenant_id,environment_id)"
              + " VALUES('graph_login','T','E')");
    }
    graphWorker =
        new GraphWorker(url, "graph_login", workerPassword, new GraphStore(graphConfig, root));
    var app = new SpringApplication(TestApp.class);
    app.setDefaultProperties(
        Map.of(
            "server.port",
            "0",
            "server.address",
            "127.0.0.1",
            "spring.main.banner-mode",
            "off",
            "logging.level.root",
            "WARN"));
    context = (WebServerApplicationContext) app.run("--server.port=0");
    port = context.getWebServer().getPort();
  }

  @AfterAll
  static void cleanup() throws Exception {
    if (context != null)
      ((org.springframework.context.ConfigurableApplicationContext) context).close();
    if (graphWorker != null) graphWorker.close();
    if (neoStarted) {
      command("docker", "stop", neoContainer);
      command("docker", "rm", "--volumes", neoContainer);
    }
    if (started) command("docker", "stop", container);
    if (pgVersion != null && neoVersion != null) {
      Files.createDirectories(root.resolve("runtime/evidence"));
      Files.writeString(
          root.resolve("runtime/evidence/ENGINE_VERSIONS.json"),
          Json.text(
                  Map.of(
                      "postgresql",
                      pgVersion,
                      "neo4j",
                      neoVersion,
                      "postgres_image",
                      IMAGE,
                      "neo4j_image",
                      NEO_IMAGE,
                      "cleanup",
                      "REMOVED_BOTH_OWNED_CONTAINERS"))
              + "\n");
    }
  }

  @Test
  @Order(1)
  void bootstrapAndPrivileges() throws Exception {
    try (var c = admin()) {
      assertThrows(
          IllegalArgumentException.class,
          () -> Admin.bootstrap(c, cfg, directory, token("alice"), "admin", "Duplicate", null));
      assertThrows(
          IllegalArgumentException.class,
          () -> Admin.install(c, root, "runtime_login", apiPassword));
    }
    try (var c = DriverManager.getConnection(url, "runtime_login", apiPassword)) {
      assertThrows(SQLException.class, () -> Admin.scalar(c, "SELECT * FROM uacrf.principal"));
      assertThrows(SQLException.class, () -> Admin.execute(c, "SET ROLE uacrf_runtime_owner"));
    }
    assertEquals(
        "1", scalar("SELECT count(*) FROM uacrf.command WHERE operation='runtime.bootstrap'"));
  }

  @Test
  @Order(2)
  void authenticationAndStrictRequests() throws Exception {
    status(200, get("/v1/principals"));
    for (var overrides :
        List.of(
            Map.<String, Object>of("aud", "other"),
            Map.<String, Object>of("iss", "https://evil.example"),
            Map.<String, Object>of("exp", Instant.now().getEpochSecond() - 1),
            Map.<String, Object>of("nbf", Instant.now().getEpochSecond() + 300)))
      status(
          401,
          request(
              "GET",
              "/v1/principals",
              null,
              signed(ISSUER, "uacrf-api", "alice", 600, overrides),
              null));
    status(401, request("GET", "/v1/principals", null, token("unadmitted"), null));
    status(
        422,
        post(
            "/v1/customer-journeys",
            "{\"id\":\"bad\",\"name\":\"Bad\",\"owner_id\":\"admin\",\"purpose\":\"Outcome\",\"lifecycle\":\"DRAFT\",\"actor_id\":\"admin\"}"));
    status(404, post("/v1/provider-actions:execute", "{}"));
    assertThrows(Problem.class, () -> Json.read("{\"id\":1,\"id\":2}"));
    assertThrows(Problem.class, () -> Json.read("{\"value\":NaN}"));
  }

  @Test
  @Order(3)
  void admissionGrantsAndSourceReplay() throws Exception {
    human("carol");
    var b =
        body(
            "{\"id\":\"carol\",\"provider_id\":\"test\",\"directory_subject_ref\":\"carol\",\"reason\":\"Admit"
                + " operator\",\"expected_revision\":0}");
    status(201, request("POST", "/v1/principals", b, token("alice"), "admit-carol"));
    assertions.remove("identity/carol");
    status(200, request("POST", "/v1/principals", b, token("alice"), "admit-carol"));
    status(
        409,
        request(
            "POST",
            "/v1/principals",
            b.deepCopy().put("reason", "Changed"),
            token("alice"),
            "admit-carol"));
    status(403, request("GET", "/v1/principals", null, token("carol"), null));
    String expiry = Instant.now().plusSeconds(1200).toString();
    ObjectNode grant =
        body("{\"id\":\"carol-owner\",\"principal_id\":\"carol\",\"capability\":\"owner.eligible\",\"service_scope\":\"*\",\"reason\":\"Accountable"
                 + " owner\",\"expected_revision\":0,\"authorization_ref\":\"owner-review\"}")
            .put("expires_at", expiry);
    assertion("grant", "owner-review", grant.deepCopy().put("reviewed_by", "reviewer"));
    status(201, request("POST", "/v1/memberships", grant, token("alice"), null));
    assertTrue(
        get("/v1/owner-candidates?object_kind=JOURNEY")
            .body
            .path("items")
            .toString()
            .contains("carol"));
    status(
        409,
        request(
            "POST",
            "/v1/memberships",
            grant.deepCopy().put("id", "duplicate"),
            token("alice"),
            null));
  }

  @Test
  @Order(4)
  void persistedJourneyAndIdempotency() throws Exception {
    var b =
        body(
            "{\"id\":\"journey\",\"name\":\"Customer"
                + " outcome\",\"owner_id\":\"carol\",\"purpose\":\"Complete"
                + " order\",\"lifecycle\":\"DRAFT\"}");
    status(201, request("POST", "/v1/customer-journeys", b, token("alice"), "create-journey"));
    status(200, request("POST", "/v1/customer-journeys", b, token("alice"), "create-journey"));
    status(
        409,
        request(
            "POST",
            "/v1/customer-journeys",
            b.deepCopy().put("purpose", "Changed"),
            token("alice"),
            "create-journey"));
    assertEquals(
        "carol", get("/v1/customer-journeys/journey").body.at("/record/summary/owner_id").asText());
    status(
        201,
        post(
            "/v1/customer-journeys/journey/steps",
            "{\"id\":\"step\",\"position\":1,\"name\":\"Create order\",\"intended_outcome\":\"Order"
                + " accepted\"}"));
    status(
        201,
        post(
            "/v1/customer-journeys/journey/capabilities",
            "{\"id\":\"link\",\"capability_kind\":\"SERVICE\",\"capability_id\":\"checkout\",\"step_id\":\"step\",\"criticality\":\"REQUIRED\"}"));
    assertEquals(
        1, get("/v1/customer-journeys/journey").body.at("/related/capabilities/items").size());
    assertEquals(
        "1", scalar("SELECT count(*) FROM uacrf.command WHERE operation='journey-create'"));
    assertEquals(
        "1", scalar("SELECT count(*) FROM uacrf.audit_event WHERE action='journey-create'"));
    assertEquals(
        "1",
        scalar("SELECT count(*) FROM uacrf.outbox WHERE payload->>'operation'='journey-create'"));
  }

  @Test
  @Order(5)
  void boundPagination() throws Exception {
    var page = get("/v1/owner-candidates?object_kind=JOURNEY&limit=1");
    status(200, page);
    String cursor = page.body.path("next_cursor").asText();
    assertFalse(cursor.isBlank());
    status(200, get("/v1/owner-candidates?object_kind=JOURNEY&limit=1&cursor=" + cursor));
    status(409, get("/v1/owner-candidates?object_kind=RESOURCE&limit=1&cursor=" + cursor));
    status(409, get("/v1/owner-candidates?object_kind=JOURNEY&limit=1&cursor=" + cursor + "x"));
    status(400, get("/v1/customer-journeys?tenant_id=OTHER"));
    status(400, get("/v1/principals?limit=1&limit=2"));
  }

  @Test
  @Order(6)
  void concurrentTransferAndRevocation() throws Exception {
    String transfer =
        "{\"object_kind\":\"JOURNEY\",\"object_id\":\"journey\",\"owner_id\":\"admin\",\"expected_revision\":1,\"expected_anchor_revision\":null,\"reason\":\"Transfer"
            + " accountability\"}";
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> post("/v1/ownership:transfer", transfer).status);
      var b = pool.submit(() -> post("/v1/ownership:transfer", transfer).status);
      var values = new ArrayList<>(List.of(a.get(), b.get()));
      Collections.sort(values);
      assertEquals(List.of(200, 409), values);
    }
    assertEquals(2, get("/v1/customer-journeys/journey").body.at("/record/revision").asInt());
    status(
        200,
        post(
            "/v1/memberships/carol-owner:revoke",
            "{\"expected_revision\":1,\"reason\":\"Eligibility withdrawn\"}"));
  }

  @Test
  @Order(7)
  void disabledUsersAndLastAdmin() throws Exception {
    status(
        409,
        post(
            "/v1/principals/admin:set-status",
            "{\"enabled\":false,\"expected_revision\":1,\"grant_review_ref\":null,\"reason\":\"Unsafe"
                + " removal\"}"));
    status(
        200,
        post(
            "/v1/principals/carol:set-status",
            "{\"enabled\":false,\"expected_revision\":1,\"grant_review_ref\":null,\"reason\":\"Departed\"}"));
    status(401, request("GET", "/v1/principals", null, token("carol"), null));
    status(200, get("/v1/customer-journeys/journey"));
  }

  @Test
  @Order(8)
  void relationshipHistoryAndResource() throws Exception {
    status(
        200,
        post(
            "/v1/customer-journeys/journey/capabilities/link:update",
            "{\"expected_revision\":1,\"reason\":\"Optional"
                + " support\",\"step_id\":\"step\",\"criticality\":\"OPTIONAL\"}"));
    status(
        200,
        post(
            "/v1/customer-journeys/journey/capabilities/link:retire",
            "{\"expected_revision\":2,\"reason\":\"Remove link\"}"));
    assertEquals(
        0, get("/v1/customer-journeys/journey").body.at("/related/capabilities/items").size());
    assertEquals("2", scalar("SELECT count(*) FROM uacrf_relationship_design.change_history"));
    status(
        201,
        post(
            "/v1/shared-resources",
            "{\"id\":\"model\",\"name\":\"Model\",\"asset_kind\":\"MODEL\",\"owner_id\":\"admin\",\"provider\":\"LOCAL\",\"lifecycle\":\"DRAFT\",\"graph_label\":\"AIModel\",\"graph_canonical_id\":\"model\"}"));
    status(
        201,
        post(
            "/v1/shared-resources/model/consumers",
            "{\"id\":\"consumer\",\"capability_kind\":\"SERVICE\",\"capability_id\":\"checkout\",\"role\":\"MODEL\",\"effect\":\"READ\"}"));
    status(200, get("/v1/shared-resources/model"));
    status(
        200,
        post(
            "/v1/shared-resources/model/consumers/consumer:update",
            "{\"expected_revision\":1,\"reason\":\"Review"
                + " use\",\"role\":\"MODEL\",\"effect\":\"NONE\"}"));
    status(
        200,
        post(
            "/v1/shared-resources/model/consumers/consumer:retire",
            "{\"expected_revision\":2,\"reason\":\"Retire use\"}"));
    assertEquals(0, get("/v1/shared-resources/model").body.at("/related/consumers/items").size());
  }

  @Test
  @Order(9)
  void atomicRollback() throws Exception {
    String before = scalar("SELECT count(*) FROM uacrf.command");
    status(
        422,
        post(
            "/v1/customer-journeys/journey/capabilities",
            "{\"id\":\"bad-step\",\"capability_kind\":\"SERVICE\",\"capability_id\":\"checkout\",\"step_id\":\"missing\",\"criticality\":\"REQUIRED\"}"));
    assertEquals(before, scalar("SELECT count(*) FROM uacrf.command"));
    assertEquals("0", scalar("SELECT count(*) FROM uacrf.journey_capability WHERE id='bad-step'"));
  }

  @Test
  @Order(10)
  void independentlyReviewedReenable() throws Exception {
    String old =
        signed(
            ISSUER, "uacrf-api", "carol", 600, Map.of("iat", Instant.now().getEpochSecond() - 5));
    String digest = scalar("SELECT uacrf_app.grant_digest('T','E','carol')");
    assertion(
        "review",
        "restore-carol",
        Json.M.createObjectNode()
            .put("issuer", ISSUER)
            .put("subject", "carol")
            .put("grant_review_ref", "restore-carol")
            .put("reviewed_by", "reviewer")
            .put("grant_digest", digest));
    status(
        200,
        post(
            "/v1/principals/carol:set-status",
            "{\"enabled\":true,\"expected_revision\":2,\"grant_review_ref\":\"restore-carol\",\"reason\":\"Independent"
                + " review\"}"));
    status(401, request("GET", "/v1/principals", null, old, null));
  }

  @Test
  @Order(11)
  void foreignTenantAndCursorAuthority() throws Exception {
    sql(
        "INSERT INTO uacrf.environment(tenant_id,environment_id,id,name,lifecycle)"
            + " VALUES('FOREIGN','E','foreign-env','Foreign','ACTIVE')");
    sql(
        "INSERT INTO uacrf.principal(tenant_id,environment_id,id,issuer,subject,display_name,kind)"
            + " VALUES('FOREIGN','E','foreign-owner',?,'foreign','Foreign','HUMAN')",
        ISSUER);
    sql(
        "INSERT INTO"
            + " uacrf.membership(tenant_id,environment_id,id,principal_id,capability,service_scope,expires_at)"
            + " VALUES('FOREIGN','E','owner-grant','foreign-owner','owner.eligible','*',now()+interval"
            + " '1 hour')");
    sql(
        "INSERT INTO"
            + " uacrf.journey(tenant_id,environment_id,id,name,owner_id,purpose,lifecycle,created_by)"
            + " VALUES('FOREIGN','E','foreign','Foreign"
            + " journey','foreign-owner','Foreign','DRAFT','foreign-owner')");
    status(404, get("/v1/customer-journeys/foreign"));
    String cursor = get("/v1/principals?limit=1").body.path("next_cursor").asText();
    sql(
        "UPDATE uacrf.membership SET expires_at=clock_timestamp()-interval '1"
            + " second',revision=revision+1 WHERE tenant_id='T' AND principal_id='admin' AND"
            + " capability='identity.delegate.environment'");
    status(409, get("/v1/principals?limit=1&cursor=" + cursor));
  }

  @Test
  @Order(12)
  void agentAndAnchorTransfer() throws Exception {
    sql(
        "INSERT INTO uacrf.principal(tenant_id,environment_id,id,issuer,subject,display_name,kind)"
            + " VALUES('T','E','runtime',?,'runtime','Runtime','AGENT')",
        ISSUER);
    sql(
        "INSERT INTO uacrf.service(tenant_id,environment_id,id,name,owner_id,criticality,lifecycle)"
            + " VALUES('T','E','agent-anchor','Agent anchor','admin',1,'ACTIVE')");
    sql(
        "INSERT INTO"
            + " uacrf.workload_profile(tenant_id,environment_id,id,service_id,name,kind,owner_id,runtime_principal_id,purpose,created_by)"
            + " VALUES('T','E','agent','agent-anchor','Agent','SINGLE_AGENT','admin','runtime','Support','admin')");
    status(
        422,
        post(
            "/v1/ownership:transfer",
            "{\"object_kind\":\"SERVICE\",\"object_id\":\"agent-anchor\",\"owner_id\":\"admin\",\"expected_revision\":1,\"expected_anchor_revision\":null,\"reason\":\"Wrong"
                + " path\"}"));
    status(
        409,
        post(
            "/v1/ownership:transfer",
            "{\"object_kind\":\"AGENT\",\"object_id\":\"agent\",\"owner_id\":\"admin\",\"expected_revision\":1,\"expected_anchor_revision\":99,\"reason\":\"Stale"
                + " anchor\"}"));
    status(
        200,
        post(
            "/v1/ownership:transfer",
            "{\"object_kind\":\"AGENT\",\"object_id\":\"agent\",\"owner_id\":\"admin\",\"expected_revision\":1,\"expected_anchor_revision\":1,\"reason\":\"Paired"
                + " update\"}"));
    assertEquals("2", scalar("SELECT revision FROM uacrf.workload_profile WHERE id='agent'"));
    assertEquals("2", scalar("SELECT revision FROM uacrf.service WHERE id='agent-anchor'"));
    assertFalse(get("/v1/services").body.path("items").toString().contains("agent-anchor"));
    assertEquals(0, get("/v1/shared-resources?asset_kind=TOOL").body.path("items").size());
  }

  @Test
  @Order(13)
  void lastAdminMembershipAndNoDirectWrites() throws Exception {
    String id =
        scalar(
            "SELECT id FROM uacrf.membership WHERE tenant_id='T' AND principal_id='admin' AND"
                + " capability='identity.principal.manage'");
    status(
        409,
        post(
            "/v1/memberships/" + id + ":revoke",
            "{\"expected_revision\":1,\"reason\":\"Last administrator\"}"));
    try (var c = DriverManager.getConnection(url, "runtime_login", apiPassword)) {
      assertThrows(
          SQLException.class, () -> Admin.execute(c, "UPDATE uacrf.principal SET enabled=true"));
    }
  }

  @Test
  @Order(14)
  void forgedSourceAndControlledRecovery() throws Exception {
    assertions.put("identity/evil", "eyJhbGciOiJub25lIn0.eyJzdWIiOiJldmlsIn0.");
    status(
        503,
        post(
            "/v1/principals",
            "{\"id\":\"evil\",\"provider_id\":\"test\",\"directory_subject_ref\":\"evil\",\"reason\":\"Bad"
                + " source\",\"expected_revision\":0}"));
    try (var c = admin()) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              Admin.bootstrap(
                  c, cfg, directory, token("alice"), "admin", "Recovery", token("alice")));
      assertTrue(
          Admin.bootstrap(
                  c,
                  cfg,
                  directory,
                  token("alice"),
                  "admin",
                  "Controlled recovery",
                  token("witness"))
              .startsWith("bootstrap:"));
    }
  }

  @Test
  @Order(15)
  void fingerprintCanonicalBytes() throws Exception {
    for (String raw :
        List.of(
            "{\"target\":1.0,\"window\":300,\"name\":\"Δ response\"}",
            "{\"z\":[true,null,\"line\\nnext\"],\"longer\":-0.00,\"n\":1e3}",
            "{\"emoji\":\"😀\",\"ratio\":0.00100}")) {
      String material = scalar("SELECT ?::jsonb::text", raw),
          digest = scalar("SELECT encode(sha256(convert_to(?::jsonb::text,'UTF8')),'hex')", raw);
      assertEquals(digest, Json.hash(material));
      assertNotEquals(digest, Json.hash(material + " "));
      String node = System.getenv("UACRF_NODE");
      if (node == null || node.isBlank())
        throw new IllegalStateException(
            "Set UACRF_NODE to the Node.js executable for interchange verification");
      var process =
          new ProcessBuilder(node, root.resolve("runtime/fingerprint.cjs").toString()).start();
      try (var out = process.getOutputStream()) {
        out.write(
            Json.text(
                    Map.of(
                        "algorithm",
                        "SHA-256",
                        "encoding",
                        "UTF-8",
                        "canonical_text",
                        material,
                        "digest",
                        digest))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
      assertEquals(0, process.exitValue());
    }
  }

  @Test
  @Order(16)
  void rejectPrivilegedServerLogin() throws Exception {
    var privileged =
        new Config(
            cfg.tenant(),
            cfg.environment(),
            cfg.issuer(),
            cfg.audience(),
            cfg.keys(),
            cfg.providerId(),
            cfg.providerName(),
            cfg.directoryUrl(),
            cfg.directoryIssuer(),
            cfg.directoryAudience(),
            cfg.directoryKeys(),
            url,
            "postgres",
            adminPassword,
            cfg.cursorKey(),
            root);
    assertThrows(IllegalArgumentException.class, () -> new Database(privileged).checkRole());
    assertEquals(30, Routes.ALL.size());
    status(200, get("/v1/identity-providers"));
    status(200, get("/v1/memberships"));
    status(200, get("/v1/customer-journeys"));
  }

  static int freePort() throws Exception {
    try (var socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  static void makeGraph(String id) throws Exception {
    makeGraph(id, "gq");
  }

  static void makeGraph(String id, String qualification) throws Exception {
    sql(
        "INSERT INTO"
            + " uacrf.graph_revision(tenant_id,environment_id,id,service_id,digest,object_ref,source_watermark,status)"
            + " VALUES('T','E',?,'checkout',encode(sha256(convert_to(?,'UTF8')),'hex'),'internal:graph',42,'BUILDING')",
        id,
        id);
    String[][] nodes = {
      {"checkout", "Service", "SERVICE", "checkout"},
      {"agent", "Service", "AGENT", "agent"},
      {"backup", "Service", "SERVICE", "backup"},
      {"db", "DataStore", "RESOURCE", ""},
      {"dns", "DNSService", "RESOURCE", ""},
      {"spare", "DataStore", "RESOURCE", ""}
    };
    for (var n : nodes) {
      var payload =
          Json.M.createObjectNode()
              .put("name", n[0])
              .put("provider", "AWS")
              .put("workload_kind", n[2])
              .put("workload_id", n[3])
              .put("secret", "DO_NOT_DISCLOSE");
      sql(
          "INSERT INTO"
              + " uacrf.graph_node(tenant_id,environment_id,id,service_id,graph_revision_id,canonical_id,label,qualification_id,expires_at,payload)"
              + " VALUES('T','E',?,'checkout',?,?,?,?,now()+?::interval,?::jsonb)",
          id + ":" + n[0],
          id,
          n[0],
          n[1],
          qualification,
          id.equals("unqualified") ? "-1 second" : "1 hour",
          Json.text(payload));
    }
    String[][] edges = {
      {"checkout", "db", "DEPENDS_ON"},
      {"agent", "db", "READS_FROM"},
      {"backup", "db", "DEPENDS_ON"},
      {"db", "dns", "DEPENDS_ON"},
      {"dns", "db", "DEPENDS_ON"}
    };
    int i = 0;
    for (var edge : edges)
      sql(
          "INSERT INTO"
              + " uacrf.graph_edge(tenant_id,environment_id,id,service_id,graph_revision_id,source_node_id,target_node_id,relationship_type,qualification_id,confidence,valid_from,expires_at)"
              + " VALUES('T','E',?,'checkout',?,?,?,?,?,1,now()-interval '1 minute',now()+interval"
              + " '1 hour')",
          id + ":edge" + (i++),
          id,
          id + ":" + edge[0],
          id + ":" + edge[1],
          edge[2],
          qualification);
  }

  static void sealGraph(String id) throws Exception {
    sql(
        "SELECT uacrf_app.graph_seal('T','E',?,1,'Synthetic signed-source acceptance"
            + " fixture','admin')",
        id);
  }

  static ObjectNode graphClaim(String id) throws Exception {
    String material = scalar("SELECT uacrf_app.graph_snapshot('T','E',?)::text", id);
    return Json.M.createObjectNode()
        .put("canonical_text", material)
        .put("digest", Json.hash(material))
        .set("snapshot", Json.read(material));
  }

  @Test
  @Order(17)
  void graphAcceptanceAndDurableQueue() throws Exception {
    sql(
        "INSERT INTO uacrf.service(tenant_id,environment_id,id,name,owner_id,criticality,lifecycle)"
            + " VALUES('T','E','backup','Recovery service','admin',1,'ACTIVE')");
    sql(
        "INSERT INTO"
            + " uacrf.evidence_event(tenant_id,environment_id,id,service_id,source_id,occurred_at,expires_at,object_ref,digest)"
            + " VALUES('T','E','gev','checkout','synthetic-source',now(),now()+interval '1"
            + " hour','internal:synthetic',repeat('a',64))");
    sql(
        "INSERT INTO"
            + " uacrf.evidence_qualification(tenant_id,environment_id,id,service_id,evidence_event_id,verdict,evaluator_id,expires_at)"
            + " VALUES('T','E','gq','checkout','gev','QUALIFIED','admin',now()+interval '1 hour')");
    makeGraph("g1");
    sealGraph("g1");
    assertEquals(
        "PENDING", scalar("SELECT state FROM uacrf_app.graph_projection WHERE revision_id='g1'"));
    var meta = get("/v1/graphs/revisions/g1");
    status(200, meta);
    assertEquals("PARTIAL", meta.body.path("state").asText());
    status(409, get("/v1/graphs/revisions/g1/dependencies"));
    assertThrows(
        SQLException.class,
        () -> sql("UPDATE uacrf.graph_node SET payload='{}' WHERE graph_revision_id='g1'"));
    assertThrows(
        SQLException.class, () -> sql("DELETE FROM uacrf.graph_edge WHERE graph_revision_id='g1'"));
    assertThrows(
        SQLException.class,
        () -> sql("UPDATE uacrf.graph_revision SET digest=repeat('f',64) WHERE id='g1'"));
    assertEquals(
        "1",
        scalar(
            "SELECT count(*) FROM uacrf.outbox WHERE topic='graph.projection.requested' AND"
                + " aggregate_id='g1'"));
  }

  @Test
  @Order(18)
  void dualStoreProjectionAndTypedDependencies() throws Exception {
    assertTrue(graphWorker.once());
    assertFalse(graphWorker.once());
    assertEquals(
        "PROJECTED", scalar("SELECT state FROM uacrf_app.graph_projection WHERE revision_id='g1'"));
    var meta = get("/v1/graphs/revisions/g1");
    status(200, meta);
    assertEquals("AVAILABLE", meta.body.path("state").asText());
    var graph = get("/v1/graphs/revisions/g1/dependencies");
    status(200, graph);
    assertEquals(6, graph.body.path("nodes").size());
    assertEquals(5, graph.body.path("edges").size());
    assertTrue(graph.body.toString().contains("AGENT"));
    assertFalse(graph.body.toString().contains("DO_NOT_DISCLOSE"));
    assertEquals("DUPLICATE", graphStore.project(graphClaim("g1")));
  }

  @Test
  @Order(19)
  void directedImpactAndBoundedIndependence() throws Exception {
    var impact = get("/v1/graphs/revisions/g1/impact?affected_id=db");
    status(200, impact);
    assertTrue(impact.body.path("affected_nodes").toString().contains("agent"));
    assertTrue(impact.body.path("affected_nodes").toString().contains("checkout"));
    assertFalse(impact.body.path("affected_nodes").toString().contains("spare"));
    var bound = get("/v1/graphs/revisions/g1/impact?affected_id=dns&max_depth=1");
    status(200, bound);
    assertEquals("INCOMPLETE", bound.body.path("completeness").asText());
    status(400, get("/v1/graphs/revisions/g1/impact?affected_id=db&max_depth=9"));
    var shared =
        get("/v1/graphs/revisions/g1/recovery-independence?primary_id=checkout&recovery_id=backup");
    status(200, shared);
    assertTrue(shared.body.path("shared_nodes").toString().contains("db"));
    assertEquals("NOT_CERTIFIED", shared.body.path("independence").asText());
    var separate =
        get("/v1/graphs/revisions/g1/recovery-independence?primary_id=checkout&recovery_id=spare");
    status(200, separate);
    assertEquals("NO_SHARED_DEPENDENCIES_OBSERVED", separate.body.path("relationship").asText());
    status(404, get("/v1/graphs/revisions/g1/impact?affected_id=absent"));
  }

  @Test
  @Order(20)
  void crashBetweenStoresReplaysSafely() throws Exception {
    makeGraph("g2");
    sealGraph("g2");
    ObjectNode claim;
    try (var c = DriverManager.getConnection(url, "graph_login", workerPassword)) {
      claim = (ObjectNode) Json.read(Admin.scalar(c, "SELECT uacrf_app.graph_claim()"));
    }
    assertEquals("SEALED", graphStore.project(claim));
    status(409, get("/v1/graphs/revisions/g2/dependencies"));
    sql(
        "UPDATE uacrf_app.graph_projection SET lease_until=now()-interval '1 second' WHERE"
            + " revision_id='g2'");
    assertTrue(graphWorker.once());
    status(200, get("/v1/graphs/revisions/g2/dependencies"));
    try (var c = DriverManager.getConnection(url, "graph_login", workerPassword)) {
      assertThrows(
          SQLException.class,
          () ->
              Admin.execute(
                  c,
                  "SELECT uacrf_app.graph_complete('g2',?::uuid,?,true)",
                  claim.path("lease_id").asText(),
                  claim.path("digest").asText()));
    }
  }

  @Test
  @Order(21)
  void invalidNeo4jBatchRollsBack() throws Exception {
    var snapshot = (ObjectNode) graphClaim("g1").path("snapshot").deepCopy();
    snapshot.put("graph_revision", "bad-batch");
    ((ObjectNode) snapshot.path("edges").get(0)).put("type", "USES_MODEL");
    String canonical = Json.text(snapshot);
    ObjectNode claim =
        Json.M.createObjectNode()
            .put("canonical_text", canonical)
            .put("digest", Json.hash(canonical))
            .set("snapshot", snapshot);
    assertThrows(Exception.class, () -> graphStore.project(claim));
    try (var session =
        graphStore.driver.session(org.neo4j.driver.SessionConfig.forDatabase("neo4j"))) {
      assertEquals(
          0,
          session
              .run("MATCH (n {graph_revision:'bad-batch'}) RETURN count(n) AS n")
              .single()
              .get("n")
              .asInt());
    }
  }

  @Test
  @Order(22)
  void sameCountNeo4jTamperingFailsClosed() throws Exception {
    try (var session =
        graphStore.driver.session(org.neo4j.driver.SessionConfig.forDatabase("neo4j"))) {
      session
          .run(
              "MATCH (n:UACRFEntity"
                  + " {tenant_id:'T',environment_id:'E',graph_revision:'g1',canonical_id:'db'}) SET"
                  + " n.tamper=true")
          .consume();
      status(503, get("/v1/graphs/revisions/g1/dependencies"));
      session
          .run(
              "MATCH (n:UACRFEntity"
                  + " {tenant_id:'T',environment_id:'E',graph_revision:'g1',canonical_id:'db'})"
                  + " REMOVE n.tamper")
          .consume();
    }
    status(200, get("/v1/graphs/revisions/g1/dependencies"));
  }

  @Test
  @Order(23)
  void graphDisclosureRequiresEveryWorkloadScope() throws Exception {
    status(404, request("GET", "/v1/graphs/revisions/g1/dependencies", null, token("carol"), null));
    sql(
        "INSERT INTO"
            + " uacrf.membership(tenant_id,environment_id,id,principal_id,capability,service_scope,expires_at)"
            + " VALUES('T','E','carol-root-read','carol','read','checkout',now()+interval '1"
            + " hour')");
    status(404, request("GET", "/v1/graphs/revisions/g1/dependencies", null, token("carol"), null));
    for (String svc : List.of("agent-anchor", "backup"))
      sql(
          "INSERT INTO"
              + " uacrf.membership(tenant_id,environment_id,id,principal_id,capability,service_scope,expires_at)"
              + " VALUES('T','E',?,'carol','read',?,now()+interval '1 hour')",
          "carol-read-" + svc,
          svc);
    status(200, request("GET", "/v1/graphs/revisions/g1/dependencies", null, token("carol"), null));
    status(404, get("/v1/graphs/revisions/foreign-graph/dependencies"));
    status(
        401,
        request("GET", "/v1/graphs/revisions/g1/dependencies", null, token("unadmitted"), null));
  }

  @Test
  @Order(26)
  void currentQualificationRevocationBlocksProjectionReads() throws Exception {
    makeGraph("unqualified");
    assertThrows(SQLException.class, () -> sealGraph("unqualified"));
    assertEquals(
        "0",
        scalar("SELECT count(*) FROM uacrf_app.graph_projection WHERE revision_id='unqualified'"));
    sql(
        "INSERT INTO"
            + " uacrf.evidence_qualification(tenant_id,environment_id,id,service_id,evidence_event_id,verdict,evaluator_id,expires_at)"
            + " VALUES('T','E','gq-revoked','checkout','gev','REVOKED','admin',now()+interval '1"
            + " hour')");
    status(409, get("/v1/graphs/revisions/g1/dependencies"));
    assertEquals("PARTIAL", get("/v1/graphs/revisions/g1").body.path("state").asText());
  }

  @Test
  @Order(25)
  void neo4jOutageAndReconciliation() throws Exception {
    makeGraph("outage-work");
    sealGraph("outage-work");
    command("docker", "stop", neoContainer);
    try {
      status(503, get("/v1/graphs/revisions/g1/dependencies"));
      assertThrows(Exception.class, () -> graphWorker.once());
      assertEquals(
          "PENDING",
          scalar("SELECT state FROM uacrf_app.graph_projection WHERE revision_id='outage-work'"));
      assertEquals(
          "1",
          scalar(
              "SELECT count(*) FROM uacrf_app.graph_projection_event WHERE"
                  + " revision_id='outage-work' AND operation='RETRY'"));
    } finally {
      command("docker", "start", neoContainer);
      for (int i = 0; i < 120; i++) {
        try {
          graphStore.driver.verifyConnectivity();
          break;
        } catch (Exception e) {
          if (i == 119) throw e;
          Thread.sleep(250);
        }
      }
    }
    status(200, get("/v1/graphs/revisions/g1/dependencies"));
    assertTrue(graphWorker.once());
    status(200, get("/v1/graphs/revisions/outage-work/dependencies"));
    graphWorker.requeue("g1");
    status(409, get("/v1/graphs/revisions/g1/dependencies"));
    assertTrue(graphWorker.once());
    status(200, get("/v1/graphs/revisions/g1/dependencies"));
  }

  @Test
  @Order(24)
  void graphWorkerPrivilegesAndConcurrentClaims() throws Exception {
    try (var api = DriverManager.getConnection(url, "runtime_login", apiPassword)) {
      assertThrows(SQLException.class, () -> Admin.scalar(api, "SELECT uacrf_app.graph_claim()"));
      assertThrows(SQLException.class, () -> Admin.scalar(api, "SELECT * FROM uacrf.graph_node"));
    }
    try (var worker = DriverManager.getConnection(url, "graph_login", workerPassword)) {
      assertThrows(
          SQLException.class, () -> Admin.scalar(worker, "SELECT * FROM uacrf.graph_node"));
      assertThrows(
          SQLException.class,
          () ->
              Admin.execute(
                  worker, "SELECT uacrf_app.graph_seal('T','E','g1',1,'Unauthorized','admin')"));
    }
    makeGraph("g3");
    sealGraph("g3");
    makeGraph("g4");
    sealGraph("g4");
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> graphWorker.once());
      var b = pool.submit(() -> graphWorker.once());
      assertTrue(a.get());
      assertTrue(b.get());
    }
    assertEquals(
        "2",
        scalar(
            "SELECT count(*) FROM uacrf_app.graph_projection WHERE revision_id IN ('g3','g4') AND"
                + " state='PROJECTED'"));
  }

  @Test
  @Order(27)
  void offlineGraphImportAndAtomicRejection() throws Exception {
    sql(
        "INSERT INTO"
            + " uacrf.evidence_event(tenant_id,environment_id,id,service_id,source_id,occurred_at,expires_at,object_ref,digest)"
            + " VALUES('T','E','new-gev','checkout','synthetic-source',now(),now()+interval '1"
            + " hour','internal:new-source',repeat('b',64))");
    sql(
        "INSERT INTO"
            + " uacrf.evidence_qualification(tenant_id,environment_id,id,service_id,evidence_event_id,verdict,evaluator_id,expires_at)"
            + " VALUES('T','E','new-gq','checkout','new-gev','QUALIFIED','admin',now()+interval '1"
            + " hour')");
    String future = Instant.now().plusSeconds(1800).toString(),
        past = Instant.now().minusSeconds(60).toString();
    ObjectNode manifest =
        Json.M.createObjectNode()
            .put("revision_id", "imported")
            .put("service_id", "checkout")
            .put("object_ref", "internal:imported")
            .put("source_watermark", 43);
    var ns = manifest.putArray("nodes");
    ns.addObject()
        .put("canonical_id", "checkout")
        .put("label", "Service")
        .put("qualification_id", "new-gq")
        .put("expires_at", future)
        .put("workload_kind", "SERVICE")
        .put("workload_id", "checkout");
    ns.addObject()
        .put("canonical_id", "db")
        .put("label", "DataStore")
        .put("qualification_id", "new-gq")
        .put("expires_at", future);
    manifest
        .putArray("edges")
        .addObject()
        .put("relationship_id", "imported-edge")
        .put("source_id", "checkout")
        .put("target_id", "db")
        .put("type", "DEPENDS_ON")
        .put("qualification_id", "new-gq")
        .put("confidence", 1)
        .put("valid_from", past)
        .put("expires_at", future);
    try (var c = admin()) {
      var bad = manifest.deepCopy().put("revision_id", "bad-import");
      ((ObjectNode) bad.path("edges").get(0)).put("target_id", "missing");
      assertThrows(
          IllegalArgumentException.class,
          () -> GraphAdmin.load(c, cfg, bad, Json.hash(Json.text(bad)), "admin", "Bad reference"));
      assertNull(Admin.scalar(c, "SELECT id FROM uacrf.graph_revision WHERE id='bad-import'"));
      var result =
          Json.read(
              GraphAdmin.load(
                  c,
                  cfg,
                  manifest,
                  Json.hash(Json.text(manifest)),
                  "admin",
                  "Verified fixture import"));
      assertEquals("PENDING", result.path("state").asText());
      assertThrows(IllegalArgumentException.class, () -> Admin.graphInstall(c, root));
      Admin.workerLogin(c, cfg, "graph_second", UUID.randomUUID() + UUID.randomUUID().toString());
      assertEquals(
          "T",
          Admin.scalar(
              c,
              "SELECT tenant_id FROM uacrf_app.graph_worker_binding WHERE"
                  + " login_name='graph_second'"));
    }
    assertTrue(graphWorker.once());
    status(200, get("/v1/graphs/revisions/imported/dependencies"));
    assertTrue(
        Integer.parseInt(
                scalar(
                    "SELECT count(*) FROM uacrf_app.graph_projection_event WHERE"
                        + " revision_id='imported'"))
            >= 2);
    assertThrows(
        SQLException.class,
        () -> sql("DELETE FROM uacrf_app.graph_projection_event WHERE revision_id='imported'"));
  }

  static String assuranceId;

  @Test
  @Order(28)
  void assuranceBaselinePersistenceAndReplay() throws Exception {
    status(
        201,
        post(
            "/v1/customer-journeys/journey/capabilities",
            "{\"id\":\"assurance-link\",\"step_id\":null,\"capability_kind\":\"SERVICE\",\"capability_id\":\"checkout\",\"criticality\":\"REQUIRED\"}"));
    makeGraph("assure-1", "new-gq");
    sealGraph("assure-1");
    while (graphWorker.once()) {}
    var b =
        body(
            "{\"journey_id\":\"journey\",\"graph_revision_id\":\"assure-1\",\"primary_id\":\"checkout\",\"recovery_id\":\"backup\",\"scenario\":\"Database"
                + " outage\",\"reason\":\"Establish scoped diagnostic baseline\"}");
    var r = request("POST", "/v1/recovery-assessments", b, token("alice"), "assurance-baseline");
    status(200, r);
    assuranceId = r.body.path("id").asText();
    assertEquals("REVIEW_REQUIRED", r.body.path("state").asText());
    assertTrue(r.body.path("shared_nodes").toString().contains("db"));
    assertEquals("NOT_CERTIFIED", r.body.path("independence").asText());
    var replay =
        request("POST", "/v1/recovery-assessments", b, token("alice"), "assurance-baseline");
    status(200, replay);
    assertEquals(assuranceId, replay.body.path("id").asText());
    status(
        409,
        request(
            "POST",
            "/v1/recovery-assessments",
            b.deepCopy().put("reason", "Changed"),
            token("alice"),
            "assurance-baseline"));
    status(200, get("/v1/recovery-assessments/" + assuranceId));
    assertEquals("1", scalar("SELECT count(*) FROM uacrf_app.recovery_assessment"));
    assertThrows(SQLException.class, () -> sql("DELETE FROM uacrf_app.recovery_review"));
  }

  @Test
  @Order(29)
  void assuranceChangedRevisionAndOptimisticConcurrency() throws Exception {
    makeGraph("assure-2", "new-gq");
    sealGraph("assure-2");
    while (graphWorker.once()) {}
    var pending = get("/v1/recovery-assessments/" + assuranceId);
    status(200, pending);
    assertTrue(pending.body.path("pending_graph_revisions").toString().contains("assure-2"));
    var b =
        body(
            "{\"graph_revision_id\":\"assure-2\",\"expected_revision\":1,\"reason\":\"Review newer"
                + " accepted evidence\"}");
    var r =
        request(
            "POST",
            "/v1/recovery-assessments/" + assuranceId + ":reassess",
            b,
            token("alice"),
            "assurance-review");
    status(200, r);
    assertEquals(2, r.body.path("revision").asInt());
    var replay =
        request(
            "POST",
            "/v1/recovery-assessments/" + assuranceId + ":reassess",
            b,
            token("alice"),
            "assurance-review");
    status(200, replay);
    assertEquals(2, replay.body.path("revision").asInt());
    status(
        409,
        request(
            "POST",
            "/v1/recovery-assessments/" + assuranceId + ":reassess",
            b,
            token("alice"),
            "assurance-stale"));
    assertEquals("2", scalar("SELECT count(*) FROM uacrf_app.recovery_review"));
    assertEquals(
        "2",
        scalar("SELECT count(*) FROM uacrf.audit_event WHERE action='recovery.assurance.review'"));
    assertEquals(
        "2", scalar("SELECT count(*) FROM uacrf.outbox WHERE topic='recovery.assurance.reviewed'"));
  }

  @Test
  @Order(30)
  void assuranceSemanticComparisonAndLimits() {
    var old = Json.M.createObjectNode();
    var nodes = old.putArray("nodes");
    for (String id : List.of("primary", "fallback", "store", "unrelated"))
      nodes.addObject().put("canonical_id", id).put("label", "Service");
    old.putArray("edges")
        .addObject()
        .put("relationship_id", "old-row")
        .put("source_id", "primary")
        .put("target_id", "store")
        .put("type", "DEPENDS_ON")
        .putObject("properties");
    var copy = old.deepCopy();
    ((ObjectNode) copy.path("edges").get(0)).put("relationship_id", "new-row");
    ((ObjectNode) copy.path("nodes").get(3)).put("name", "unrelated change");
    assertEquals(
        "NO_RELEVANT_CHANGE_OBSERVED",
        Assurance.compare(old, copy, "primary", "fallback").path("state").asText());
    ((ArrayNode) copy.path("edges"))
        .addObject()
        .put("relationship_id", "new-dependency")
        .put("source_id", "fallback")
        .put("target_id", "store")
        .put("type", "DEPENDS_ON")
        .putObject("properties");
    var result = Assurance.compare(old, copy, "primary", "fallback");
    assertEquals("REVIEW_REQUIRED", result.path("state").asText());
    assertTrue(result.path("shared_nodes").toString().contains("store"));
  }

  @Test
  @Order(31)
  void assuranceAgentAndAuthorization() throws Exception {
    var b =
        body(
            "{\"journey_id\":\"journey\",\"graph_revision_id\":\"assure-2\",\"primary_id\":\"agent\",\"recovery_id\":\"backup\",\"scenario\":\"Agent"
                + " data source unavailable\",\"reason\":\"Review agent continuity"
                + " dependencies\"}");
    var r = request("POST", "/v1/recovery-assessments", b, token("alice"), "agent-assurance");
    status(200, r);
    assertEquals("agent", r.body.path("primary_id").asText());
    assertFalse(r.body.path("automatic_recovery").asBoolean());
    status(
        401,
        request("GET", "/v1/recovery-assessments/" + assuranceId, null, token("unadmitted"), null));
    status(404, get("/v1/recovery-assessments/unknown"));
    try (var c = DriverManager.getConnection(url, "runtime_login", apiPassword)) {
      assertThrows(
          SQLException.class,
          () -> Admin.scalar(c, "SELECT count(*) FROM uacrf_app.recovery_review"));
    }
  }

  @Test
  @Order(32)
  void assuranceEvidenceRevocationDoesNotRewriteHistory() throws Exception {
    sql(
        "INSERT INTO"
            + " uacrf.evidence_qualification(tenant_id,environment_id,id,service_id,evidence_event_id,verdict,evaluator_id,expires_at)"
            + " VALUES('T','E','assurance-revoked','checkout','new-gev','REVOKED','admin',now()+interval"
            + " '1 hour')");
    var r = get("/v1/recovery-assessments/" + assuranceId);
    status(200, r);
    assertEquals("INSUFFICIENT_EVIDENCE", r.body.path("state").asText());
    assertEquals(
        "REVIEW_REQUIRED",
        scalar(
            "SELECT result->>'state' FROM uacrf_app.recovery_review WHERE assessment_id=? AND"
                + " revision=2",
            assuranceId));
    status(
        409,
        post(
            "/v1/recovery-assessments/" + assuranceId + ":reassess",
            "{\"graph_revision_id\":\"assure-2\",\"expected_revision\":2,\"reason\":\"Cannot renew"
                + " revoked evidence\"}"));
  }
}
