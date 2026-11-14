package org.uacrf.connections;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Delivers S03 outbox rows to the S04 inbox.
 *
 * <p>S03 authenticates to S01 as a SYSTEM principal with the OAuth client-credentials grant, asks
 * S01 for a 30-second delegation token for audience S04 limited to {@code evidence.inbox.write},
 * and presents that token to S04. S03 never holds a signing key and never widens its own scope.
 */
@Component
@ConditionalOnProperty(name = "uacrf.outbox.publisher.enabled", havingValue = "true")
class OutboxPublisher {
  private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
  private static final String SCHEMA = "uacrf_connections.";

  private final JdbcTemplate jdbc;
  private final RestClient http = RestClient.create();
  private final String tokenUrl;
  private final String clientId;
  private final String clientSecret;
  private final String delegationUrl;
  private final String inboxUrl;
  private final int batchSize;

  OutboxPublisher(
      JdbcTemplate jdbc,
      @Value("${uacrf.outbox.publisher.token-url}") String tokenUrl,
      @Value("${uacrf.outbox.publisher.client-id}") String clientId,
      @Value("${uacrf.outbox.publisher.client-secret}") String clientSecret,
      @Value("${uacrf.outbox.publisher.delegation-url}") String delegationUrl,
      @Value("${uacrf.outbox.publisher.inbox-url}") String inboxUrl,
      @Value("${uacrf.outbox.publisher.batch-size:50}") int batchSize) {
    this.jdbc = jdbc;
    this.tokenUrl = tokenUrl;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.delegationUrl = delegationUrl;
    this.inboxUrl = inboxUrl;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${uacrf.outbox.publisher.interval-ms:5000}")
  void scheduled() {
    try {
      publishPending();
    } catch (RuntimeException error) {
      LOG.warn("Outbox publish cycle failed; rows stay pending: {}", error.toString());
    }
  }

  /** Returns the number of rows S04 accepted as delivered in this cycle. */
  int publishPending() {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT event_id, tenant_id, environment_id, aggregate_id, aggregate_sequence, "
                + "event_type, payload::text AS payload FROM "
                + SCHEMA
                + "outbox_event WHERE published_at IS NULL ORDER BY created_at, event_id LIMIT ?",
            batchSize);
    if (rows.isEmpty()) {
      return 0;
    }
    String identityToken = identityToken();
    Map<String, String> delegations = new LinkedHashMap<>();
    int delivered = 0;
    for (Map<String, Object> row : rows) {
      String tenant = (String) row.get("tenant_id");
      String environment = (String) row.get("environment_id");
      String delegation =
          delegations.computeIfAbsent(
              tenant + "/" + environment, key -> delegation(identityToken, tenant, environment));
      String payload = (String) row.get("payload");
      Map<String, Object> event = new LinkedHashMap<>();
      event.put("event_id", row.get("event_id").toString());
      event.put("source_service", "S03");
      event.put("event_type", row.get("event_type"));
      event.put("aggregate_id", row.get("aggregate_id"));
      event.put("aggregate_sequence", row.get("aggregate_sequence"));
      event.put("payload", payload);
      event.put("payload_digest", sha256(payload));
      Map<?, ?> result =
          http.post()
              .uri(inboxUrl)
              .headers(headers -> headers.setBearerAuth(delegation))
              .contentType(MediaType.APPLICATION_JSON)
              .body(event)
              .retrieve()
              .body(Map.class);
      String status = result == null ? null : String.valueOf(result.get("status"));
      if ("RECEIVED".equals(status) || "DUPLICATE".equals(status)) {
        markPublished(row.get("event_id"));
        delivered++;
      } else if ("QUARANTINED".equals(status)) {
        // S04 retains quarantined events durably; resending the same bytes cannot change that.
        LOG.warn("S04 quarantined event {}: {}", row.get("event_id"), result.get("reason"));
        markPublished(row.get("event_id"));
      } else {
        throw new IllegalStateException("Unexpected S04 inbox response for " + row.get("event_id"));
      }
    }
    return delivered;
  }

  private void markPublished(Object eventId) {
    jdbc.update(
        "UPDATE " + SCHEMA + "outbox_event SET published_at = now() "
            + "WHERE event_id = ? AND published_at IS NULL",
        eventId);
  }

  private String identityToken() {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    return accessToken(
        http.post()
            .uri(tokenUrl)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class));
  }

  private String delegation(String identityToken, String tenant, String environment) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("audience", "S04");
    request.put("capability_ids", List.of("evidence.inbox.write"));
    request.put("tenant_id", tenant);
    request.put("environment_id", environment);
    return accessToken(
        http.post()
            .uri(delegationUrl)
            .headers(headers -> headers.setBearerAuth(identityToken))
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(Map.class));
  }

  private static String accessToken(Map<?, ?> response) {
    Object token = response == null ? null : response.get("access_token");
    if (!(token instanceof String value) || value.isBlank()) {
      throw new IllegalStateException("Token endpoint returned no access_token.");
    }
    return value;
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the runtime.", error);
    }
  }
}
