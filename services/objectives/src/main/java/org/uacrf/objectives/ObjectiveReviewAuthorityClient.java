package org.uacrf.objectives;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
class ObjectiveReviewAuthorityClient {
  private static final Duration TIMEOUT = Duration.ofSeconds(2);
  private static final int MAX_RESPONSE_BYTES = 16 * 1024;
  private final boolean enabled;
  private final URI tokenUrl;
  private final URI checkUrl;
  private final String clientId;
  private final String clientSecret;
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
  private final JsonMapper json = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .build();

  ObjectiveReviewAuthorityClient(
      @Value("${uacrf.objectives.reviewer-check.enabled:false}") boolean enabled,
      @Value("${uacrf.objectives.reviewer-check.token-url:}") String tokenUrl,
      @Value("${uacrf.objectives.reviewer-check.identity-base-url:}") String identityBaseUrl,
      @Value("${uacrf.objectives.reviewer-check.client-id:}") String clientId,
      @Value("${uacrf.objectives.reviewer-check.client-secret:}") String clientSecret,
      @Value("${uacrf.objectives.reviewer-check.allow-loopback-http:false}") boolean allowLoopbackHttp) {
    this.enabled = enabled;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    if (enabled) {
      if (clientId.isBlank() || clientSecret.isBlank()) {
        throw new IllegalArgumentException("Objective reviewer checking requires client credentials.");
      }
      this.tokenUrl = endpoint(tokenUrl, allowLoopbackHttp, false);
      this.checkUrl = endpoint(identityBaseUrl, allowLoopbackHttp, true)
          .resolve("/internal/v1/objective-review-authority:check");
    } else {
      this.tokenUrl = null;
      this.checkUrl = null;
    }
  }

  void check(String tenantId, String environmentId, String reviewerId) {
    if (!enabled) {
      return;
    }
    if (reviewerId == null || !reviewerId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}")) {
      throw unavailable();
    }
    try {
      String form = "grant_type=client_credentials&client_id=" + encode(clientId)
          + "&client_secret=" + encode(clientSecret);
      Map<?, ?> token = send(HttpRequest.newBuilder(tokenUrl).timeout(TIMEOUT)
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(form)).build());
      if (!(token.get("access_token") instanceof String accessToken)
          || accessToken.length() > 32768 || !accessToken.matches("[A-Za-z0-9._~+/-]+=*")
          || !(token.get("token_type") instanceof String tokenType)
          || !"Bearer".equalsIgnoreCase(tokenType)) {
        throw unavailable();
      }
      Instant started = Instant.now();
      Map<?, ?> response = send(HttpRequest.newBuilder(checkUrl).timeout(TIMEOUT)
          .header("Authorization", "Bearer " + accessToken)
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of(
              "tenant_id", tenantId, "environment_id", environmentId, "reviewer_id", reviewerId))))
          .build());
      if (!tenantId.equals(response.get("tenant_id"))
          || !environmentId.equals(response.get("environment_id"))
          || !reviewerId.equals(response.get("reviewer_id"))
          || !"AUTHORIZED".equals(response.get("status"))
          || !(response.get("checked_at") instanceof String time)) {
        throw unavailable();
      }
      Instant checkedAt = Instant.parse(time);
      if (checkedAt.isBefore(started.minusSeconds(5)) || checkedAt.isAfter(Instant.now().plusSeconds(5))) {
        throw unavailable();
      }
    } catch (IOException | JacksonException | DateTimeParseException failure) {
      throw unavailable();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw unavailable();
    }
  }

  private Map<?, ?> send(HttpRequest request) throws IOException, InterruptedException {
    HttpResponse<byte[]> response = http.send(request,
        HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES));
    if (response.statusCode() != 200) {
      throw unavailable();
    }
    Object body = json.readValue(response.body(), Object.class);
    if (!(body instanceof Map<?, ?> result)) {
      throw unavailable();
    }
    return result;
  }

  private static URI endpoint(String value, boolean allowLoopbackHttp, boolean base) {
    URI uri = URI.create(value);
    String host = uri.getHost();
    boolean loopback = "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
    if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
        || (!"https".equalsIgnoreCase(uri.getScheme())
            && !(allowLoopbackHttp && loopback && "http".equalsIgnoreCase(uri.getScheme())))
        || (base && uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
      throw new IllegalArgumentException("Objective reviewer checking requires a secure service URI.");
    }
    return uri;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static ObjectivesException unavailable() {
    return new ObjectivesException(HttpStatus.SERVICE_UNAVAILABLE, "REVIEWER_AUTHORITY_UNAVAILABLE",
        "Current objective reviewer authority could not be verified.");
  }
}
