package org.uacrf.objectives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.uacrf.security.OwnerReceiptSignature;
import tools.jackson.databind.json.JsonMapper;

class ObjectiveWorkloadAuthorityClientTests {
  private static final String KEY_ID = "s02-test-key";
  private static final String WORKLOAD_ID = "workload-1";
  private static final String PURPOSE = ObjectiveWorkloadAuthorityClient.PROPOSAL_PURPOSE;
  private final JsonMapper json = JsonMapper.builder().build();
  private final KeyPair keyPair = generateKeyPair();
  private final AtomicReference<String> mode = new AtomicReference<>("ok");
  private final AtomicReference<String> exchangeRequest = new AtomicReference<>();
  private final AtomicReference<String> exchangeAuthorization = new AtomicReference<>();
  private final AtomicReference<String> receiptRequest = new AtomicReference<>();
  private final AtomicReference<String> receiptAuthorization = new AtomicReference<>();
  private HttpServer server;
  private ObjectiveWorkloadAuthorityClient client;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/v1/delegations:exchange", this::exchange);
    server.createContext("/internal/v1/workloads/", this::receipt);
    server.start();
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    client =
        new ObjectiveWorkloadAuthorityClient(
            true,
            baseUrl,
            baseUrl,
            KEY_ID,
            Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
            true);
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void exchangesTheInboundAuthorityAndAcceptsOnlyMatchingSignedS02Receipts() {
    client.check(jwt(), WORKLOAD_ID, PURPOSE);

    assertThat(exchangeRequest.get())
        .contains("\"audience\":\"S02\"")
        .contains("\"resource_type\":\"agent_workload\"")
        .contains("\"resource_id\":\"" + WORKLOAD_ID + "\"")
        .contains("\"purpose\":\"" + PURPOSE + "\"");
    assertThat(exchangeAuthorization.get()).isEqualTo("Bearer inbound-s05-token");
    assertThat(receiptRequest.get())
        .contains("\"purpose\":\"" + PURPOSE + "\"")
        .contains("\"operation_deadline\"");
    assertThat(receiptAuthorization.get()).isEqualTo("Bearer s02-delegation");
  }

  @Test
  void rejectsReceiptContextSignatureEligibilityAndAvailabilityFailures() {
    for (String invalidMode :
        new String[] {
          "wrong-actor",
          "wrong-tenant",
          "wrong-resource",
          "wrong-purpose",
          "wrong-audience",
          "bad-signature",
          "expired",
          "malformed",
          "duplicate"
        }) {
      mode.set(invalidMode);
      assertThatThrownBy(() -> client.check(jwt(), WORKLOAD_ID, PURPOSE))
          .isInstanceOfSatisfying(
              ObjectivesException.class,
              error -> assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    mode.set("ineligible");
    assertThatThrownBy(() -> client.check(jwt(), WORKLOAD_ID, PURPOSE))
        .isInstanceOfSatisfying(
            ObjectivesException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));

    mode.set("receipt-denied");
    assertThatThrownBy(() -> client.check(jwt(), WORKLOAD_ID, PURPOSE))
        .isInstanceOfSatisfying(
            ObjectivesException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.FORBIDDEN));

    mode.set("receipt-unavailable");
    assertThatThrownBy(() -> client.check(jwt(), WORKLOAD_ID, PURPOSE))
        .isInstanceOfSatisfying(
            ObjectivesException.class,
            error ->
                assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

    mode.set("exchange-timeout");
    assertThatThrownBy(() -> client.check(jwt(), WORKLOAD_ID, PURPOSE))
        .isInstanceOfSatisfying(
            ObjectivesException.class,
            error ->
                assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }

  @Test
  void rejectsIdentityExchangeDenialAndUnavailableResponses() {
    mode.set("exchange-denied");
    assertThatThrownBy(() -> client.check(jwt(), WORKLOAD_ID, PURPOSE))
        .isInstanceOfSatisfying(
            ObjectivesException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.FORBIDDEN));

    mode.set("exchange-unavailable");
    assertThatThrownBy(() -> client.check(jwt(), WORKLOAD_ID, PURPOSE))
        .isInstanceOfSatisfying(
            ObjectivesException.class,
            error ->
                assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }

  private void exchange(HttpExchange exchange) throws IOException {
    exchangeRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    exchangeAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    if ("exchange-timeout".equals(mode.get())) {
      try {
        Thread.sleep(2500);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    int status =
        switch (mode.get()) {
          case "exchange-denied" -> HttpStatus.FORBIDDEN.value();
          case "exchange-unavailable" -> HttpStatus.SERVICE_UNAVAILABLE.value();
          default -> HttpStatus.OK.value();
        };
    respond(
        exchange,
        status,
        "{\"access_token\":\"s02-delegation\",\"token_type\":\"Bearer\",\"expires_in\":5}");
  }

  private void receipt(HttpExchange exchange) throws IOException {
    receiptRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    receiptAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    int status =
        switch (mode.get()) {
          case "receipt-denied" -> HttpStatus.FORBIDDEN.value();
          case "receipt-unavailable" -> HttpStatus.SERVICE_UNAVAILABLE.value();
          default -> HttpStatus.OK.value();
        };
    if (status != HttpStatus.OK.value()) {
      respond(exchange, status, "{}");
      return;
    }
    if ("malformed".equals(mode.get())) {
      respond(exchange, status, "{}");
      return;
    }
    Map<String, Object> claims = claims();
    switch (mode.get()) {
      case "wrong-actor" -> claims.put("actor_id", "another-actor");
      case "wrong-tenant" -> claims.put("tenant_id", "another-tenant");
      case "wrong-resource" -> claims.put("resource_id", "another-workload");
      case "wrong-purpose" -> claims.put("purpose", ObjectiveWorkloadAuthorityClient.ACTIVATION_PURPOSE);
      case "wrong-audience" -> claims.put("audience_service", "S06");
      case "expired" -> {
        Instant issuedAt = Instant.now().minusSeconds(10);
        claims.put("issued_at", issuedAt.toString());
        claims.put("valid_until", issuedAt.plusSeconds(1).toString());
      }
      case "ineligible" -> claims.put("eligible", false);
      default -> {}
    }
    String signature =
        OwnerReceiptSignature.sign(
            json.writeValueAsString(claims), (RSAPrivateKey) keyPair.getPrivate());
    if ("bad-signature".equals(mode.get())) {
      signature = "invalid-signature";
    }
    Map<String, Object> receipt = new LinkedHashMap<>(claims);
    receipt.put("signature", signature);
    String body = json.writeValueAsString(receipt);
    if ("duplicate".equals(mode.get())) {
      body = body.replace("\"eligible\":true", "\"eligible\":true,\"eligible\":true");
    }
    respond(exchange, status, body);
  }

  private Map<String, Object> claims() {
    Instant issuedAt = Instant.now();
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("protocol", "UACRF_OWNER_RECEIPT_V1");
    claims.put("key_id", KEY_ID);
    claims.put("receipt_id", "receipt-1");
    claims.put("issuer_service", "S02");
    claims.put("audience_service", "S05");
    claims.put("tenant_id", "tenant-1");
    claims.put("environment_id", "env-1");
    claims.put("actor_id", "actor-1");
    claims.put("resource_type", "agent_workload");
    claims.put("resource_id", WORKLOAD_ID);
    claims.put("resource_version", "version-7");
    claims.put("resource_revision", 7);
    claims.put("content_digest", "a".repeat(64));
    claims.put("authority_epoch", 7);
    claims.put("eligible", true);
    claims.put("purpose", PURPOSE);
    claims.put("issued_at", issuedAt.toString());
    claims.put("valid_until", issuedAt.plusSeconds(4).toString());
    return claims;
  }

  private static Jwt jwt() {
    Instant now = Instant.now();
    return Jwt.withTokenValue("inbound-s05-token")
        .header("alg", "RS256")
        .subject("principal-1")
        .issuedAt(now.minusSeconds(1))
        .expiresAt(now.plusSeconds(30))
        .claim("tenant_id", "tenant-1")
        .claim("environment_id", "env-1")
        .claim("service_scope", "*")
        .claim("actor_issuer", "https://identity.example")
        .claim("actor_subject", "actor-1")
        .claim("capabilities", java.util.List.of("objectives.objective.write"))
        .claim("eligible_owner_ids", java.util.List.of())
        .claim("runtime_principal_ids", java.util.List.of())
        .build();
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
