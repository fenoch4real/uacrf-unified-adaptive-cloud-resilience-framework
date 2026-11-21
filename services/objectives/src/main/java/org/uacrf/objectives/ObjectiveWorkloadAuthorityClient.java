package org.uacrf.objectives;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import org.uacrf.security.DelegationKeyMaterial;
import org.uacrf.security.DelegationPrincipal;
import org.uacrf.security.OwnerReceiptSignature;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
class ObjectiveWorkloadAuthorityClient {
  static final String PROPOSAL_PURPOSE = "objectives.objective.proposal";
  static final String ACTIVATION_PURPOSE = "objectives.objective.activation";
  private static final String ID = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$";
  private static final Duration TIMEOUT = Duration.ofSeconds(2);
  private static final int MAX_RESPONSE_BYTES = 16 * 1024;
  private static final Set<String> RECEIPT_FIELDS =
      Set.of(
          "protocol",
          "key_id",
          "receipt_id",
          "issuer_service",
          "audience_service",
          "tenant_id",
          "environment_id",
          "actor_id",
          "resource_type",
          "resource_id",
          "resource_version",
          "resource_revision",
          "content_digest",
          "authority_epoch",
          "eligible",
          "purpose",
          "issued_at",
          "valid_until",
          "signature");

  private final boolean enabled;
  private final URI identityUrl;
  private final URI catalogUrl;
  private final String keyId;
  private final RSAPublicKey publicKey;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(TIMEOUT)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final JsonMapper json =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  ObjectiveWorkloadAuthorityClient(
      @Value("${uacrf.objectives.workload-check.enabled:false}") boolean enabled,
      @Value("${uacrf.objectives.workload-check.identity-base-url:}") String identityBaseUrl,
      @Value("${uacrf.objectives.workload-check.catalog-base-url:}") String catalogBaseUrl,
      @Value("${uacrf.objectives.workload-check.s02-key-id:}") String keyId,
      @Value("${uacrf.objectives.workload-check.s02-public-key-base64:}") String publicKeyBase64,
      @Value("${uacrf.objectives.workload-check.allow-loopback-http:false}")
          boolean allowLoopbackHttp) {
    this.enabled = enabled;
    this.keyId = keyId;
    if (enabled) {
      if (keyId.isBlank() || publicKeyBase64.isBlank()) {
        throw new IllegalArgumentException(
            "Objective workload checking requires the S02 owner-receipt verification key.");
      }
      DelegationKeyMaterial.validateKeyId(keyId);
      this.publicKey = DelegationKeyMaterial.decodePublicKey(publicKeyBase64);
      this.identityUrl = endpoint(identityBaseUrl, allowLoopbackHttp);
      this.catalogUrl = endpoint(catalogBaseUrl, allowLoopbackHttp);
    } else {
      this.publicKey = null;
      this.identityUrl = null;
      this.catalogUrl = null;
    }
  }

  void check(Jwt jwt, String workloadId, String purpose) {
    if (!enabled) {
      return;
    }
    if (!validId(workloadId)
        || !Set.of(PROPOSAL_PURPOSE, ACTIVATION_PURPOSE).contains(purpose)) {
      throw unavailable();
    }
    try {
      DelegationPrincipal actor = DelegationPrincipal.from(jwt);
      Instant deadline = Instant.now().plusSeconds(5);
      Map<?, ?> exchange =
          post(
              identityUrl.resolve("/internal/v1/delegations:exchange"),
              jwt.getTokenValue(),
              Map.of(
                  "audience",
                  "S02",
                  "resource_type",
                  "agent_workload",
                  "resource_id",
                  workloadId,
                  "purpose",
                  purpose,
                  "operation_deadline",
                  deadline.toString()),
              deadline,
              false);
      if (!(exchange.get("access_token") instanceof String accessToken)
          || accessToken.isBlank()
          || accessToken.length() > 32768
          || !accessToken.matches("[A-Za-z0-9._~+/-]+=*")
          || !(exchange.get("token_type") instanceof String tokenType)
          || !"Bearer".equalsIgnoreCase(tokenType)) {
        throw unavailable();
      }

      String encodedWorkload = UriUtils.encodePathSegment(workloadId, StandardCharsets.UTF_8);
      Map<?, ?> receipt =
          post(
              catalogUrl.resolve(
                  "/internal/v1/workloads/" + encodedWorkload + ":current-receipt"),
              accessToken,
              Map.of("purpose", purpose, "operation_deadline", deadline.toString()),
              deadline,
              true);
      validateReceipt(receipt, actor, workloadId, purpose, deadline, Instant.now());
      if (!Instant.now().isBefore(deadline)) {
        throw unavailable();
      }
    } catch (ObjectivesException failure) {
      throw failure;
    } catch (IOException | JacksonException failure) {
      throw unavailable();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private Map<?, ?> post(
      URI uri,
      String token,
      Map<String, Object> body,
      Instant deadline,
      boolean receiptEndpoint)
      throws IOException, InterruptedException {
    Instant now = Instant.now();
    Duration remaining = Duration.between(now, deadline);
    if (remaining.isNegative() || remaining.isZero()) {
      throw unavailable();
    }
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(remaining.compareTo(TIMEOUT) < 0 ? remaining : TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
            .build();
    HttpResponse<byte[]> response =
        http.send(
            request,
            HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES));
    int status = response.statusCode();
    if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
      throw denied();
    }
    if (receiptEndpoint
        && (status == HttpStatus.NOT_FOUND.value() || status == HttpStatus.CONFLICT.value())) {
      throw notEligible();
    }
    if (status != HttpStatus.OK.value()) {
      throw unavailable();
    }
    Object result = json.readValue(response.body(), Object.class);
    if (!(result instanceof Map<?, ?> map)) {
      throw unavailable();
    }
    return map;
  }

  private void validateReceipt(
      Map<?, ?> receipt,
      DelegationPrincipal actor,
      String workloadId,
      String purpose,
      Instant deadline,
      Instant now) {
    if (!receipt.keySet().equals(RECEIPT_FIELDS)
        || !"UACRF_OWNER_RECEIPT_V1".equals(receipt.get("protocol"))
        || !keyId.equals(receipt.get("key_id"))
        || !"S02".equals(receipt.get("issuer_service"))
        || !"S05".equals(receipt.get("audience_service"))
        || !actor.tenantId().equals(receipt.get("tenant_id"))
        || !actor.environmentId().equals(receipt.get("environment_id"))
        || !actor.actorSubject().equals(receipt.get("actor_id"))
        || !"agent_workload".equals(receipt.get("resource_type"))
        || !workloadId.equals(receipt.get("resource_id"))
        || !validId(receipt.get("resource_version"))
        || !purpose.equals(receipt.get("purpose"))
        || !(receipt.get("eligible") instanceof Boolean)
        || !validId(receipt.get("receipt_id"))
        || !(receipt.get("content_digest") instanceof String digest)
        || !digest.matches("^[a-f0-9]{64}$")
        || !validRevision(receipt.get("resource_revision"))
        || !validRevision(receipt.get("authority_epoch"))
        || !receipt.get("resource_revision").equals(receipt.get("authority_epoch"))
        || !(receipt.get("signature") instanceof String signature)) {
      throw unavailable();
    }
    try {
      Instant issuedAt = Instant.parse((String) receipt.get("issued_at"));
      Instant validUntil = Instant.parse((String) receipt.get("valid_until"));
      if (issuedAt.isAfter(now)
          || !validUntil.isAfter(now)
          || !validUntil.isAfter(issuedAt)
          || validUntil.isAfter(issuedAt.plusSeconds(5))
          || validUntil.isAfter(deadline)) {
        throw unavailable();
      }
      Map<String, Object> unsigned = new LinkedHashMap<>();
      for (Map.Entry<?, ?> field : receipt.entrySet()) {
        unsigned.put((String) field.getKey(), field.getValue());
      }
      unsigned.remove("signature");
      if (!OwnerReceiptSignature.verify(
          json.writeValueAsString(unsigned), signature, publicKey)) {
        throw unavailable();
      }
    } catch (RuntimeException failure) {
      throw unavailable();
    }
    if (!Boolean.TRUE.equals(receipt.get("eligible"))) {
      throw notEligible();
    }
  }

  private static boolean validId(Object value) {
    return value instanceof String text && text.matches(ID);
  }

  private static boolean validRevision(Object value) {
    if (!(value instanceof Number number)) {
      return false;
    }
    try {
      long revision = new BigDecimal(number.toString()).longValueExact();
      return revision >= 0 && revision <= 9_007_199_254_740_991L;
    } catch (NumberFormatException | ArithmeticException failure) {
      return false;
    }
  }

  static URI endpoint(String value, boolean allowLoopbackHttp) {
    URI uri = URI.create(value);
    String host = uri.getHost();
    boolean loopback =
        "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
    if (host == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
        || !("https".equalsIgnoreCase(uri.getScheme())
            || (allowLoopbackHttp
                && loopback
                && "http".equalsIgnoreCase(uri.getScheme())))) {
      throw new IllegalArgumentException(
          "Objective authority clients require HTTPS, except for explicitly enabled loopback development.");
    }
    return URI.create(uri.toString().endsWith("/")
        ? uri.toString().substring(0, uri.toString().length() - 1)
        : uri.toString());
  }

  private static ObjectivesException denied() {
    return new ObjectivesException(
        HttpStatus.FORBIDDEN,
        "WORKLOAD_AUTHORITY_DENIED",
        "Current authority to check the workload is required.");
  }

  private static ObjectivesException notEligible() {
    return new ObjectivesException(
        HttpStatus.CONFLICT,
        "WORKLOAD_NOT_ELIGIBLE",
        "S02 did not confirm an active, integrity-checked workload version.");
  }

  private static ObjectivesException unavailable() {
    return new ObjectivesException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "WORKLOAD_AUTHORITY_UNAVAILABLE",
        "Current S02 workload authority could not be verified.");
  }
}
