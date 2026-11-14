package org.uacrf.assessment;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;
import org.uacrf.assessment.AssessmentRequests.GraphRef;
import org.uacrf.assessment.AssessmentRequests.ObjectiveRef;
import org.uacrf.assessment.AssessmentResponses.GraphReceipt;
import org.uacrf.assessment.AssessmentResponses.ObjectiveReceipt;

@Component
class HttpReceiptVerifier implements ReceiptVerifier {
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration CACHE_SKEW = Duration.ofSeconds(5);
  private final boolean enabled;
  private final String tokenUrl;
  private final String clientId;
  private final String clientSecret;
  private final String delegationUrl;
  private final String objectivesBaseUrl;
  private final String topologyBaseUrl;
  private final RestClient http;
  private final Map<String, CachedToken> delegations = new HashMap<>();
  private CachedToken identityToken;

  @Autowired
  HttpReceiptVerifier(
      @Value("${uacrf.assessment.receipts.enabled:false}") boolean enabled,
      @Value("${uacrf.assessment.receipts.token-url}") String tokenUrl,
      @Value("${uacrf.assessment.receipts.client-id}") String clientId,
      @Value("${uacrf.assessment.receipts.client-secret:}") String clientSecret,
      @Value("${uacrf.assessment.receipts.delegation-url}") String delegationUrl,
      @Value("${uacrf.assessment.receipts.objectives-base-url}") String objectivesBaseUrl,
      @Value("${uacrf.assessment.receipts.topology-base-url}") String topologyBaseUrl) {
    this(enabled, tokenUrl, clientId, clientSecret, delegationUrl, objectivesBaseUrl,
        topologyBaseUrl, createClient());
  }

  HttpReceiptVerifier(boolean enabled, String tokenUrl, String clientId, String clientSecret,
      String delegationUrl, String objectivesBaseUrl, String topologyBaseUrl, RestClient http) {
    this.enabled = enabled;
    this.tokenUrl = tokenUrl;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.delegationUrl = delegationUrl;
    this.objectivesBaseUrl = stripTrailingSlash(objectivesBaseUrl);
    this.topologyBaseUrl = stripTrailingSlash(topologyBaseUrl);
    this.http = http;
  }

  @Override
  public Verification verify(AssessmentResponses.Case item, String tenantId, String environmentId) {
    if (!enabled) return Verification.skipped();
    ObjectiveReceipt objectiveReceipt = null;
    GraphReceipt graphReceipt = null;
    String failureReason = null;
    try {
      String objectiveToken = delegation("S05", "objectives.objective.read", tenantId, environmentId);
      Map<?, ?> objectiveBody = http.post()
          .uri(objectivesBaseUrl + "/internal/v1/workloads/"
              + UriUtils.encodePathSegment(item.workloadId(), StandardCharsets.UTF_8)
              + "/objectives:receipt")
          .headers(headers -> headers.setBearerAuth(objectiveToken))
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("purpose", item.purpose()))
          .retrieve().body(Map.class);
      ObjectiveCheck objective = objectiveReceipt(objectiveBody, item.workloadId(), item.purpose(), item.objectiveRef());
      objectiveReceipt = objective.receipt();
      failureReason = objective.reasonCode();
    } catch (RestClientException | IllegalStateException error) {
      failureReason = "OWNER_UNAVAILABLE";
    }

    try {
      String graphToken = delegation("S06", "topology.graph.read", tenantId, environmentId);
      Map<?, ?> graphBody = http.post()
          .uri(topologyBaseUrl + "/internal/v1/graphs/"
              + UriUtils.encodePathSegment(item.graphRef().graphId(), StandardCharsets.UTF_8) + ":receipt")
          .headers(headers -> headers.setBearerAuth(graphToken))
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of())
          .retrieve().body(Map.class);
      GraphCheck graph = graphReceipt(graphBody, item.graphRef());
      graphReceipt = graph.receipt();
      if (failureReason == null) failureReason = graph.reasonCode();
    } catch (RestClientException | IllegalStateException error) {
      if (failureReason == null) failureReason = "OWNER_UNAVAILABLE";
    }

    return failureReason == null
        ? Verification.verified(objectiveReceipt, graphReceipt)
        : Verification.failed(failureReason, objectiveReceipt, graphReceipt);
  }

  private synchronized String delegation(String audience, String capability, String tenantId,
      String environmentId) {
    String cacheKey = tenantId + "/" + environmentId + "/" + audience;
    CachedToken cached = delegations.get(cacheKey);
    if (usable(cached)) return cached.value();
    String actorToken = identityToken();
    Map<?, ?> response = http.post().uri(delegationUrl)
        .headers(headers -> headers.setBearerAuth(actorToken))
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("audience", audience, "capability_ids", List.of(capability),
            "tenant_id", tenantId, "environment_id", environmentId))
        .retrieve().body(Map.class);
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
    Map<?, ?> response = http.post().uri(tokenUrl)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
        .retrieve().body(Map.class);
    identityToken = token(response, 30);
    return identityToken.value();
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

  private static ObjectiveCheck objectiveReceipt(Map<?, ?> body, String workloadId, String purpose,
      ObjectiveRef expected) {
    if (!string(body, "workload_id", workloadId) || !string(body, "purpose", purpose)
        || !(body.get("objectives") instanceof List<?> objectives)) {
      return new ObjectiveCheck("OBJECTIVE_UNVERIFIED", null);
    }
    Map<?, ?> found = null;
    for (Object value : objectives) {
      if (!(value instanceof Map<?, ?> objective) || !(objective.get("id") instanceof String)) {
        return new ObjectiveCheck("OBJECTIVE_UNVERIFIED", null);
      }
      if (expected.objectiveId().equals(objective.get("id"))) {
        if (found != null) return new ObjectiveCheck("OBJECTIVE_UNVERIFIED", null);
        found = objective;
      }
    }
    if (found == null) return new ObjectiveCheck("OBJECTIVE_STALE", null);
    Long revision = number(found.get("revision"));
    String digest = text(found.get("content_digest"));
    if (revision == null || digest == null || text(body.get("set_digest")) == null) {
      return new ObjectiveCheck("OBJECTIVE_UNVERIFIED", null);
    }
    ObjectiveReceipt receipt = new ObjectiveReceipt(workloadId, text(body.get("set_digest")),
        expected.objectiveId(), revision, digest);
    if (revision != expected.revision() || !digest.equals(expected.digest())) {
      return new ObjectiveCheck("OBJECTIVE_STALE", receipt);
    }
    return new ObjectiveCheck(null, receipt);
  }

  private static GraphCheck graphReceipt(Map<?, ?> body, GraphRef expected) {
    if (body == null) return new GraphCheck("GRAPH_UNVERIFIED", null);
    Object eligibility = body.get("eligibility");
    String projection = text(body.get("projection_status"));
    String status = text(body.get("status"));
    if (Boolean.FALSE.equals(eligibility) || (projection != null && !"PROJECTED".equals(projection))
        || (status != null && !"ACCEPTED".equals(status))) {
      return new GraphCheck("GRAPH_NOT_ELIGIBLE", receiptIfPresent(body));
    }
    if (!Boolean.TRUE.equals(eligibility) || !"PROJECTED".equals(projection) || !"ACCEPTED".equals(status)) {
      return new GraphCheck("GRAPH_UNVERIFIED", receiptIfPresent(body));
    }
    Long revision = number(body.get("revision"));
    String id = text(body.get("id"));
    String digest = text(body.get("digest"));
    if (id == null || revision == null || digest == null) {
      return new GraphCheck("GRAPH_UNVERIFIED", receiptIfPresent(body));
    }
    GraphReceipt receipt = new GraphReceipt(id, revision, digest);
    if (!expected.graphId().equals(id) || expected.revision() != revision
        || !expected.digest().equals(digest)) {
      return new GraphCheck("GRAPH_UNVERIFIED", receipt);
    }
    return new GraphCheck(null, receipt);
  }

  private static GraphReceipt receiptIfPresent(Map<?, ?> body) {
    Long revision = number(body.get("revision"));
    String id = text(body.get("id"));
    String digest = text(body.get("digest"));
    return id == null || revision == null || digest == null ? null : new GraphReceipt(id, revision, digest);
  }

  private static boolean string(Map<?, ?> map, String key, String expected) {
    return map != null && expected.equals(map.get(key));
  }

  private static String text(Object value) {
    return value instanceof String text && !text.isBlank() ? text : null;
  }

  private static Long number(Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }

  private static String stripTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private static RestClient createClient() {
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
        HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build());
    factory.setReadTimeout(REQUEST_TIMEOUT);
    return RestClient.builder().requestFactory(factory).build();
  }

  private record CachedToken(String value, Instant expiresAt) {}
  private record ObjectiveCheck(String reasonCode, ObjectiveReceipt receipt) {}
  private record GraphCheck(String reasonCode, GraphReceipt receipt) {}
}
