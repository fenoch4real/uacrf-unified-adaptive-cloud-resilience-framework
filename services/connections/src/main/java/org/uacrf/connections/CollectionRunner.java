package org.uacrf.connections;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.uacrf.connections.CollectionCheckpointStore.LeasedSource;
import org.uacrf.connections.ProviderCollector.Batch;
import org.uacrf.connections.ProviderCollector.Request;

@Component
@ConditionalOnProperty(name = "uacrf.collection.runner.enabled", havingValue = "true")
class CollectionRunner {
  private static final Logger LOG = LoggerFactory.getLogger(CollectionRunner.class);
  private static final List<String> OBSERVATION_WRITE = List.of("connections.observation.write");
  private static final int MAX_CHECKPOINT_LENGTH = 4096;

  private final CollectionCheckpointStore checkpoints;
  private final Map<ConnectionsRequests.Provider, ProviderCollector> collectors;
  private final RestClient http;
  private final String tokenUrl;
  private final String clientId;
  private final String clientSecret;
  private final String delegationUrl;
  private final String observationBaseUrl;
  private final long intervalMs;
  private final long leaseMs;
  private final int batchSize;
  private final int maxObservations;

  CollectionRunner(
      CollectionCheckpointStore checkpoints,
      List<ProviderCollector> collectors,
      @Value("${uacrf.collection.runner.token-url}") String tokenUrl,
      @Value("${uacrf.collection.runner.client-id}") String clientId,
      @Value("${uacrf.collection.runner.client-secret}") String clientSecret,
      @Value("${uacrf.collection.runner.delegation-url}") String delegationUrl,
      @Value("${uacrf.collection.runner.observation-base-url}") String observationBaseUrl,
      @Value("${uacrf.collection.runner.interval-ms:60000}") long intervalMs,
      @Value("${uacrf.collection.runner.lease-ms:300000}") long leaseMs,
      @Value("${uacrf.collection.runner.batch-size:10}") int batchSize,
      @Value("${uacrf.collection.runner.max-observations:100}") int maxObservations) {
    if (clientSecret == null || clientSecret.isBlank()) {
      throw new IllegalArgumentException(
          "Collection runner client secret must be configured when enabled.");
    }
    if (clientId == null || clientId.isBlank()) {
      throw new IllegalArgumentException(
          "Collection runner client ID must be configured when enabled.");
    }
    ConnectionsHttpClient.validateEndpoint(tokenUrl, "Collection runner token URL");
    ConnectionsHttpClient.validateEndpoint(delegationUrl, "Collection runner delegation URL");
    ConnectionsHttpClient.validateEndpoint(
        observationBaseUrl, "Collection runner observation base URL");
    if (intervalMs < 1 || leaseMs < 1 || batchSize < 1 || maxObservations < 1) {
      throw new IllegalArgumentException("Collection runner limits must be positive.");
    }
    this.http = ConnectionsHttpClient.create();
    this.checkpoints = checkpoints;
    this.collectors =
        collectors.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    ProviderCollector::provider, Function.identity()));
    this.tokenUrl = tokenUrl;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.delegationUrl = delegationUrl;
    this.observationBaseUrl = observationBaseUrl;
    this.intervalMs = intervalMs;
    this.leaseMs = leaseMs;
    this.batchSize = batchSize;
    this.maxObservations = maxObservations;
  }

  @Scheduled(fixedDelayString = "${uacrf.collection.runner.interval-ms:60000}")
  void scheduled() {
    try {
      runDue();
    } catch (RuntimeException error) {
      LOG.warn(
          "Collection cycle aborted; active leases will expire for retry ({})",
          error.getClass().getSimpleName());
    }
  }

  int runDue() {
    Map<String, String> identityTokens = new LinkedHashMap<>();
    Map<String, String> delegations = new LinkedHashMap<>();
    int completed = 0;
    while (completed < batchSize) {
      List<LeasedSource> sources =
          checkpoints.claimDue(1, Duration.ofMillis(leaseMs));
      if (sources.isEmpty()) {
        break;
      }
      LeasedSource source = sources.getFirst();
      try {
        collect(source, identityTokens, delegations);
      } catch (RuntimeException error) {
        try {
          checkpoints.renew(source, Duration.ofMillis(leaseMs));
          complete(
              source,
              "FAILED",
              "Collection attempt failed; retry scheduled.",
              source.checkpoint(),
              source.lastCollectedAt(),
              false,
              null);
        } catch (RuntimeException recordError) {
          LOG.warn(
              "Could not record failed collection for connection {} source {} ({})",
              source.connectionId(),
              source.sourceRef(),
              recordError.getClass().getSimpleName());
        }
        LOG.warn(
            "Collection failed for connection {} source {} ({})",
            source.connectionId(),
            source.sourceRef(),
            error.getClass().getSimpleName());
      }
      completed++;
    }
    return completed;
  }

  private void collect(
      LeasedSource source,
      Map<String, String> identityTokens,
      Map<String, String> delegations) {
    if (source.pendingObservations() != null) {
      if (source.pendingCollectedAt() == null) {
        throw new IllegalStateException("Pending collection batch has no collection time.");
      }
      submitBatch(
          source,
          source.pendingObservations(),
          source.pendingNextCheckpoint(),
          source.pendingCollectedAt(),
          identityTokens,
          delegations);
      return;
    }

    ConnectionsRequests.Provider provider =
        ConnectionsRequests.Provider.valueOf(source.provider());
    ProviderCollector collector = collectors.get(provider);
    if (collector == null) {
      complete(
          source,
          "NOT_IMPLEMENTED",
          "No collector is registered for this provider.",
          source.checkpoint(),
          source.lastCollectedAt(),
          false,
          null);
      return;
    }

    Instant collectedAt = Instant.now();
    checkpoints.renew(source, Duration.ofMillis(leaseMs));
    Batch batch =
        collector.collect(
            new Request(
                source.connectionId(),
                source.sourceRef(),
                source.readOnlyIdentityRef(),
                source.awsExternalId(),
                source.checkpoint(),
                source.lastSequence(),
                source.lastCollectedAt(),
                collectedAt));
    checkpoints.renew(source, Duration.ofMillis(leaseMs));
    if (batch.status() != Batch.Status.COLLECTED) {
      complete(
          source,
          batch.status().name(),
          statusDetail(batch.status()),
          source.checkpoint(),
          source.lastCollectedAt(),
          false,
          batch.retryAt());
      return;
    }
    validateBatch(batch.observations(), batch.nextCheckpoint());
    checkpoints.storePending(source, batch.observations(), batch.nextCheckpoint(), collectedAt);
    submitBatch(
        source,
        batch.observations(),
        batch.nextCheckpoint(),
        collectedAt,
        identityTokens,
        delegations);
  }

  private void submitBatch(
      LeasedSource source,
      List<ProviderCollector.Observation> observations,
      String nextCheckpoint,
      Instant collectedAt,
      Map<String, String> identityTokens,
      Map<String, String> delegations) {
    checkpoints.renew(source, Duration.ofMillis(leaseMs));
    validateBatch(observations, nextCheckpoint);
    String scope = source.tenantId() + "/" + source.environmentId();
    for (ProviderCollector.Observation observation :
        observations.stream()
            .sorted(java.util.Comparator.comparingLong(ProviderCollector.Observation::sequence))
            .toList()) {
      String identityToken =
          identityTokens.computeIfAbsent(scope, ignored -> identityToken(source));
      String delegation =
          delegations.computeIfAbsent(
              scope,
              ignored ->
                  delegation(
                      source, identityToken, source.tenantId(), source.environmentId()));
      submit(source, observation, delegation);
    }
    complete(
        source,
        "COLLECTED",
        "Batch submitted to observation intake.",
        nextCheckpoint,
        collectedAt,
        true,
        null);
  }

  private void validateBatch(
      List<ProviderCollector.Observation> observations, String nextCheckpoint) {
    if (observations == null
        || observations.size() > maxObservations
        || (nextCheckpoint != null && nextCheckpoint.length() > MAX_CHECKPOINT_LENGTH)) {
      throw new IllegalStateException("Collector returned a batch outside configured bounds.");
    }
    observations.forEach(CollectionRunner::validateObservation);
  }

  private void submit(
      LeasedSource source, ProviderCollector.Observation observation, String delegation) {
    checkpoints.renew(source, Duration.ofMillis(leaseMs));
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("id", observation.id());
    request.put("source_ref", source.sourceRef());
    request.put("sequence", observation.sequence());
    request.put("observed_at", observation.observedAt().toString());
    request.put("kind", observation.kind());
    request.put("payload", observation.payload());
    request.put("payload_digest", sha256(observation.payload()));
    ConnectionsResponses.IngestResult result =
        http.post()
            .uri(
                observationBaseUrl
                    + "/v1/connections/{connectionId}/observations",
                source.connectionId())
            .headers(headers -> headers.setBearerAuth(delegation))
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(ConnectionsResponses.IngestResult.class);
    if (result == null
        || !observation.id().equals(result.id())
        || observation.sequence() != result.sequence()
        || !List.of("ACCEPTED", "DUPLICATE", "QUARANTINED").contains(result.status())) {
      throw new IllegalStateException("Observation intake returned an incomplete or inconsistent result.");
    }
  }

  private String identityToken(LeasedSource source) {
    checkpoints.renew(source, Duration.ofMillis(leaseMs));
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

  private String delegation(
      LeasedSource source, String identityToken, String tenant, String environment) {
    checkpoints.renew(source, Duration.ofMillis(leaseMs));
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("audience", "S03");
    request.put("capability_ids", OBSERVATION_WRITE);
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

  private void complete(
      LeasedSource source,
      String status,
      String detail,
      String nextCheckpoint,
      Instant collectedAt,
      boolean advanceCheckpoint,
      Instant retryAt) {
    Instant nextCollectionAt = Instant.now().plusMillis(intervalMs);
    if (retryAt != null && retryAt.isAfter(nextCollectionAt)) {
      nextCollectionAt = retryAt;
    }
    checkpoints.complete(
        source,
        status,
        detail,
        nextCheckpoint,
        collectedAt,
        nextCollectionAt,
        advanceCheckpoint);
  }

  private static void validateObservation(ProviderCollector.Observation observation) {
    if (observation == null
        || observation.id() == null
        || !observation.id().matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$")
        || observation.sequence() < 1
        || observation.observedAt() == null
        || observation.kind() == null
        || !observation.kind().matches("^[a-z][a-z0-9_.-]{0,99}$")
        || observation.payload() == null
        || observation.payload().isBlank()
        || observation.payload().length() > 16384) {
      throw new IllegalStateException("Collector returned an invalid observation.");
    }
  }

  private static String accessToken(Map<?, ?> response) {
    Object token = response == null ? null : response.get("access_token");
    if (!(token instanceof String value) || value.isBlank()) {
      throw new IllegalStateException("Token endpoint returned no access_token.");
    }
    return value;
  }

  private static String statusDetail(Batch.Status status) {
    return switch (status) {
      case RATE_LIMITED -> "Provider rate limit reached; retry scheduled.";
      case PERMISSION_DENIED -> "Provider denied the configured read-only request.";
      case SOURCE_UNAVAILABLE -> "Provider source is unavailable; retry scheduled.";
      case NOT_IMPLEMENTED -> "The provider collector is not implemented.";
      case COLLECTED -> "Batch submitted to observation intake.";
    };
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the runtime.", error);
    }
  }
}
