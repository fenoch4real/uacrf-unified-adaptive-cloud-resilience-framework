package org.uacrf.objectives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.json.JsonMapper;

class ObjectiveImportSampleSourceClientTests {
  private static final String PAYLOAD = """
      {"metric":"recovery.time","target":720,"unit":"seconds","window":"stable-30m",
       "measurement_ref":"sample:check","comparison":"AT_MOST","measurement_kind":"RECOVERY_TIME",
       "percentile":null}
      """;
  private final JsonMapper json = JsonMapper.builder().build();
  private final AtomicReference<String> mode = new AtomicReference<>("ok");
  private final AtomicReference<String> exchangeBody = new AtomicReference<>();
  private final AtomicReference<String> sourceQuery = new AtomicReference<>();
  private final AtomicReference<String> exchangeAuth = new AtomicReference<>();
  private final AtomicReference<String> sourceAuth = new AtomicReference<>();
  private final ObjectivesService.SampleSourceRequest source =
      new ObjectivesService.SampleSourceRequest("sample-connection", "sample:objective&x", "work+load", "a".repeat(64));
  private HttpServer server;
  private ObjectiveImportSampleSourceClient client;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/v1/delegations:exchange", this::exchange);
    server.createContext("/v1/connections/sample-connection/sample-objective-definition", this::sample);
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort();
    client = new ObjectiveImportSampleSourceClient(true, url, url, true);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void inspectsOnlyTheRetainedSourceWithNarrowDelegation() {
    var result = client.inspect(jwt(), source);
    assertThat(result.verification()).isEqualTo("SYNTHETIC_UNVERIFIED");
    assertThat(result.definition().target()).isEqualByComparingTo("720");
    assertThat(result.definitionFingerprint().version()).isEqualTo("OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1");
    assertThat(result.definitionFingerprint().digest())
        .isEqualTo("833ffb467927e077f96e63dd31d0682a959ba009b3628e9703d00f60aae2738e");
    assertThat(result.connectionRevision()).isEqualTo(2);
    assertThat(result.bindingRevision()).isEqualTo(3);
    assertThat(exchangeAuth.get()).isEqualTo("Bearer s05-token");
    assertThat(exchangeBody.get()).contains("\"audience\":\"S03\"")
        .contains("\"resource_type\":\"connection\"").contains("\"resource_id\":\"sample-connection\"")
        .contains("\"purpose\":\"objectives.import.synthetic-source\"");
    assertThat(sourceAuth.get()).isEqualTo("Bearer s03-token");
    assertThat(sourceQuery.get()).isEqualTo("externalObjectiveId=sample%3Aobjective%26x&targetWorkloadId=work%2Bload");
  }

  @Test
  void fingerprintsTheDefinitionIndependentlyOfSourceDecimalFormatting() {
    var original = client.inspect(jwt(), source);
    mode.set("decimal-format");
    var reformatted = client.inspect(jwt(), source);
    assertThat(reformatted.payloadDigest()).isNotEqualTo(original.payloadDigest());
    assertThat(reformatted.definitionFingerprint()).isEqualTo(original.definitionFingerprint());
  }

  @Test
  void failsClosedForDenialMismatchInvalidPayloadAndUnavailableSource() {
    for (String scenario : new String[] {"wrong-id", "bad-digest", "duplicate", "not-synthetic", "unavailable"}) {
      mode.set(scenario);
      assertStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
    mode.set("bad-payload");
    assertStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    mode.set("bad-unicode");
    assertStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    mode.set("denied");
    assertStatus(HttpStatus.FORBIDDEN);
    mode.set("missing");
    assertStatus(HttpStatus.NOT_FOUND);
    assertThatThrownBy(() -> new ObjectiveImportSampleSourceClient(false, "", "", false).inspect(jwt(), source))
        .isInstanceOfSatisfying(ObjectivesException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }

  private void assertStatus(HttpStatus status) {
    assertThatThrownBy(() -> client.inspect(jwt(), source))
        .isInstanceOfSatisfying(ObjectivesException.class, error -> assertThat(error.status()).isEqualTo(status));
  }

  private void exchange(HttpExchange request) throws IOException {
    exchangeAuth.set(request.getRequestHeaders().getFirst("Authorization"));
    exchangeBody.set(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    if ("denied".equals(mode.get())) {
      reply(request, 403, "{}");
      return;
    }
    reply(request, 200, "{\"access_token\":\"s03-token\",\"token_type\":\"Bearer\"}");
  }

  private void sample(HttpExchange request) throws IOException {
    sourceAuth.set(request.getRequestHeaders().getFirst("Authorization"));
    sourceQuery.set(request.getRequestURI().getRawQuery());
    if ("missing".equals(mode.get())) { reply(request, 404, "{}"); return; }
    if ("unavailable".equals(mode.get())) { reply(request, 503, "{}"); return; }
    String payload = switch (mode.get()) {
      case "bad-payload" -> "{}";
      case "bad-unicode" -> PAYLOAD.replace("recovery.time", "\\ud800");
      case "decimal-format" -> PAYLOAD.replace("720", "720.00");
      default -> PAYLOAD;
    };
    String digest;
    try {
      digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
    String body = json.writeValueAsString(Map.ofEntries(
        Map.entry("connection_id", "wrong-id".equals(mode.get()) ? "other" : "sample-connection"),
        Map.entry("connection_revision", 2), Map.entry("binding_id", "sample-binding"),
        Map.entry("binding_revision", 3), Map.entry("target_workload_id", "work+load"),
        Map.entry("source_system", "sample:monitoring"), Map.entry("external_objective_id", "sample:objective&x"),
        Map.entry("source_version", "sample-v1"), Map.entry("payload", payload),
        Map.entry("payload_digest", "bad-digest".equals(mode.get()) ? "a".repeat(64) : digest),
        Map.entry("verification", "not-synthetic".equals(mode.get()) ? "VERIFIED" : "SYNTHETIC_UNVERIFIED")));
    if ("duplicate".equals(mode.get())) body = body.substring(0, body.length() - 1) + ",\"binding_id\":\"other\"}";
    reply(request, 200, body);
  }

  private static void reply(HttpExchange request, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    request.sendResponseHeaders(status, bytes.length);
    try (var output = request.getResponseBody()) { output.write(bytes); }
  }

  private static Jwt jwt() {
    return Jwt.withTokenValue("s05-token").header("alg", "RS256")
        .claim("sub", "principal").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(30)).build();
  }
}
