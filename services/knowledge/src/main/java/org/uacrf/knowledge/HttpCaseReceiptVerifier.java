package org.uacrf.knowledge;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;
import org.uacrf.knowledge.CaseReceiptVerifier.CaseReceipt;
import org.uacrf.knowledge.CaseReceiptVerifier.Verification;

@Component
class HttpCaseReceiptVerifier implements CaseReceiptVerifier {
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration CACHE_SKEW = Duration.ofSeconds(5);
  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

  private final boolean enabled;
  private final String tokenUrl;
  private final String clientId;
  private final String clientSecret;
  private final String delegationUrl;
  private final String assessmentBaseUrl;
  private final RestClient http;
  private final Map<String, CachedToken> delegations = new HashMap<>();
  private CachedToken identityToken;

  @Autowired
  HttpCaseReceiptVerifier(
      @Value("${uacrf.knowledge.case-receipts.enabled:false}") boolean enabled,
      @Value("${uacrf.knowledge.case-receipts.token-url}") String tokenUrl,
      @Value("${uacrf.knowledge.case-receipts.client-id}") String clientId,
      @Value("${uacrf.knowledge.case-receipts.client-secret:}") String clientSecret,
      @Value("${uacrf.knowledge.case-receipts.delegation-url}") String delegationUrl,
      @Value("${uacrf.knowledge.case-receipts.assessment-base-url}") String assessmentBaseUrl) {
    this(enabled, tokenUrl, clientId, clientSecret, delegationUrl, assessmentBaseUrl, createClient());
  }

  HttpCaseReceiptVerifier(
      boolean enabled,
      String tokenUrl,
      String clientId,
      String clientSecret,
      String delegationUrl,
      String assessmentBaseUrl,
      RestClient http) {
    this.enabled = enabled;
    this.tokenUrl = tokenUrl;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.delegationUrl = delegationUrl;
    this.assessmentBaseUrl = stripTrailingSlash(assessmentBaseUrl);
    this.http = http;
  }

  @Override
  public boolean enabled() {
    return enabled;
  }

  @Override
  public Verification verify(String caseId, String purpose, String tenantId, String environmentId) {
    if (!enabled) return Verification.failed("CASE_NOT_BOUND");
    if (caseId == null || caseId.isBlank()) return Verification.failed("CASE_UNVERIFIED");
    try {
      String token = delegation(tenantId, environmentId);
      Map<?, ?> body =
          http.post()
              .uri(
                  assessmentBaseUrl
                      + "/internal/v1/cases/"
                      + UriUtils.encodePathSegment(caseId, StandardCharsets.UTF_8)
                      + ":receipt")
              .headers(headers -> headers.setBearerAuth(token))
              .contentType(MediaType.APPLICATION_JSON)
              .body(Map.of())
              .retrieve()
              .body(Map.class);
      CaseReceipt receipt = validate(body);
      if (receipt == null
          || !caseId.equals(receipt.caseId())
          || !purpose.equals(receipt.purpose())) {
        return Verification.failed("CASE_UNVERIFIED");
      }
      return Verification.verified(receipt);
    } catch (HttpClientErrorException error) {
      return Verification.failed(error.getStatusCode().is4xxClientError() ? "CASE_UNVERIFIED" : "OWNER_UNAVAILABLE");
    } catch (RestClientException | IllegalStateException error) {
      return Verification.failed("OWNER_UNAVAILABLE");
    }
  }

  private synchronized String delegation(String tenantId, String environmentId) {
    String cacheKey = tenantId + "/" + environmentId + "/S07";
    CachedToken cached = delegations.get(cacheKey);
    if (usable(cached)) return cached.value();
    String actorToken = identityToken();
    Map<?, ?> response =
        http.post()
            .uri(delegationUrl)
            .headers(headers -> headers.setBearerAuth(actorToken))
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "audience",
                    "S07",
                    "capability_ids",
                    List.of("assessment.case.read"),
                    "tenant_id",
                    tenantId,
                    "environment_id",
                    environmentId))
            .retrieve()
            .body(Map.class);
    CachedToken value = token(response, 30);
    delegations.put(cacheKey, value);
    return value.value();
  }

  private synchronized String identityToken() {
    if (usable(identityToken)) return identityToken.value();
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    Map<?, ?> response =
        http.post()
            .uri(tokenUrl)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);
    identityToken = token(response, 30);
    return identityToken.value();
  }

  private static CaseReceipt validate(Map<?, ?> body) {
    if (body == null) return null;
    Object kindsValue = body.get("required_source_kinds");
    if (!(kindsValue instanceof List<?> kinds) || kinds.isEmpty()) return null;
    List<String> requiredKinds = new java.util.ArrayList<>(kinds.size());
    for (Object kind : kinds) {
      if (!(kind instanceof String text)
          || text.isBlank()
          || text.length() > 200
          || requiredKinds.contains(text)) {
        return null;
      }
      requiredKinds.add(text);
    }
    String caseId = text(body.get("case_id"));
    String purpose = text(body.get("purpose"));
    String digest = text(body.get("case_digest"));
    String workloadId = text(body.get("workload_id"));
    if (caseId == null
        || purpose == null
        || digest == null
        || !DIGEST.matcher(digest).matches()
        || workloadId == null
        || workloadId.length() > 200) {
      return null;
    }
    return new CaseReceipt(caseId, purpose, digest, workloadId, List.copyOf(requiredKinds));
  }

  private static CachedToken token(Map<?, ?> response, long defaultLifetimeSeconds) {
    Object value = response == null ? null : response.get("access_token");
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalStateException("Token response did not contain an access token.");
    }
    Object expires = response.get("expires_in");
    long lifetime = expires instanceof Number number ? number.longValue() : defaultLifetimeSeconds;
    if (lifetime <= 0) throw new IllegalStateException("Token response has an invalid lifetime.");
    return new CachedToken(text, Instant.now().plusSeconds(lifetime));
  }

  private static boolean usable(CachedToken token) {
    return token != null && Instant.now().plus(CACHE_SKEW).isBefore(token.expiresAt());
  }

  private static String text(Object value) {
    return value instanceof String text && !text.isBlank() ? text : null;
  }

  private static String stripTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private static RestClient createClient() {
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build());
    factory.setReadTimeout(REQUEST_TIMEOUT);
    return RestClient.builder().requestFactory(factory).build();
  }

  private record CachedToken(String value, Instant expiresAt) {}
}
