package org.uacrf.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class ConnectionsClient {
  private final RestClient restClient;

  ConnectionsClient(@Qualifier("connectionsRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  ConnectionPage list(String token, String cursor) {
    ConnectionPage page =
        restClient.get()
            .uri(uri -> {
              var request = uri.path("/v1/connections").queryParam("pageSize", 25);
              if (cursor != null && !cursor.isBlank()) {
                request.queryParam("cursor", cursor);
              }
              return request.build();
            })
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve()
            .body(ConnectionPage.class);
    if (page == null || page.items() == null || page.items().stream().anyMatch(c -> !valid(c))) {
      throw new IllegalStateException("S03 returned an incomplete connection page.");
    }
    return page;
  }

  Connection get(String token, String id) {
    Connection connection =
        restClient.get().uri("/v1/connections/{id}", id)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve().body(Connection.class);
    if (!valid(connection) || !id.equals(connection.id())) {
      throw new IllegalStateException("S03 returned an incomplete connection.");
    }
    return connection;
  }

  SampleObjectiveDefinition sampleObjective(
      String token, String id, String externalObjectiveId, String targetWorkloadId) {
    SampleObjectiveDefinition definition = restClient.get()
        .uri(uri -> uri.path("/v1/connections/{id}/sample-objective-definition")
            .queryParam("externalObjectiveId", "{external}")
            .queryParam("targetWorkloadId", "{workload}")
            .build(id, externalObjectiveId, targetWorkloadId))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve().body(SampleObjectiveDefinition.class);
    if (definition == null || !id.equals(definition.connectionId())
        || !externalObjectiveId.equals(definition.externalObjectiveId())
        || !targetWorkloadId.equals(definition.targetWorkloadId())
        || definition.connectionRevision() < 1 || definition.bindingRevision() < 1
        || !present(definition.bindingId()) || !present(definition.sourceSystem())
        || !present(definition.sourceVersion()) || !present(definition.payload())
        || definition.payload().length() > 16_384
        || !"SYNTHETIC_UNVERIFIED".equals(definition.verification())
        || !sha256(definition.payload()).equals(definition.payloadDigest())) {
      throw new IllegalStateException("S03 returned an invalid synthetic objective definition.");
    }
    return definition;
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable.", error);
    }
  }

  InventoryPage listInventory(String token, String id, String cursor, String sourceClass) {
    InventoryPage page =
        restClient.get()
            .uri(uri -> {
              var request = uri.path("/v1/connections/{id}/inventory-observations").queryParam("pageSize", 25);
              if (cursor != null && !cursor.isBlank()) {
                request.queryParam("cursor", cursor);
              }
              return request.build(id);
            })
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve().body(InventoryPage.class);
    if (page == null || page.items() == null
        || page.items().stream().anyMatch(item -> !validInventory(item, sourceClass))) {
      throw new IllegalStateException("S03 returned an incomplete inventory page.");
    }
    return page;
  }

  ObservationPage listObservations(String token, String id, String cursor) {
    ObservationPage page =
        restClient.get()
            .uri(uri -> {
              var request =
                  uri.path("/v1/connections/{id}/observations").queryParam("pageSize", 25);
              if (cursor != null && !cursor.isBlank()) {
                request.queryParam("cursor", cursor);
              }
              return request.build(id);
            })
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve()
            .body(ObservationPage.class);
    if (page == null || page.items() == null
        || page.items().stream().anyMatch(item -> !validObservation(item))) {
      throw new IllegalStateException("S03 returned an incomplete observation page.");
    }
    return page;
  }

  CollectionStatusPage listCollectionStatuses(String token, String id, String cursor) {
    CollectionStatusPage page =
        restClient.get()
            .uri(uri -> {
              var request = uri.path("/v1/connections/{id}/collection-statuses").queryParam("pageSize", 25);
              if (cursor != null && !cursor.isBlank()) {
                request.queryParam("cursor", cursor);
              }
              return request.build(id);
            })
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve().body(CollectionStatusPage.class);
    if (page == null || page.items() == null
        || page.items().stream().anyMatch(item -> !validCollectionStatus(item, id))) {
      throw new IllegalStateException("S03 returned an incomplete collection-status page.");
    }
    return page;
  }

  void registerSample(String token, String key, SampleInventory.Connection request) {
    var response = restClient.post().uri("/v1/connections")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().toEntity(Receipt.class);
    validateReceipt(response.getStatusCode().value(), response.getBody(), request.id(), "CONNECTION");
  }

  void bindSample(String token, String connectionId, String key, SampleInventory.Binding request) {
    var response = restClient.post().uri("/v1/connections/{id}/source-bindings", connectionId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().toEntity(Receipt.class);
    validateReceipt(response.getStatusCode().value(), response.getBody(), request.id(), "SOURCE_BINDING");
  }

  IngestResult submitSample(String token, String connectionId, SampleInventory.Observation request) {
    var response = restClient.post().uri("/v1/connections/{id}/observations", connectionId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().toEntity(IngestResult.class);
    IngestResult result = response.getBody();
    if (response.getStatusCode().value() != HttpStatus.ACCEPTED.value() || result == null
        || !request.id().equals(result.id()) || request.sequence() != result.sequence()
        || result.status() == null
        || !List.of("ACCEPTED", "DUPLICATE", "QUARANTINED").contains(result.status())
        || ("QUARANTINED".equals(result.status()) && !present(result.reason()))) {
      throw new IllegalStateException("S03 returned an invalid observation result.");
    }
    return result;
  }

  private static void validateReceipt(int status, Receipt receipt, String id, String kind) {
    if (status != HttpStatus.CREATED.value() || receipt == null || !id.equals(receipt.id())
        || !kind.equals(receipt.recordKind()) || receipt.revision() != 1
        || !"CREATED".equals(receipt.result()) || !"NONE".equals(receipt.runtimeEffect())) {
      throw new IllegalStateException("S03 returned an invalid mutation receipt.");
    }
  }

  private static boolean valid(Connection connection) {
    return connection != null && present(connection.id()) && present(connection.name())
        && present(connection.provider()) && present(connection.status());
  }

  private static boolean validInventory(InventoryObservation item, String sourceClass) {
    return item != null && "inventory-observation.v1".equals(item.normalizerVersion())
        && sourceClass.equals(item.sourceClass())
        && present(item.provider()) && List.of("AWS", "AZURE", "GCP").contains(item.provider())
        && ("SAMPLE_LOCAL".equals(sourceClass) || sourceClass.equals(item.provider()))
        && present(item.nativeId()) && present(item.nativeType())
        && present(item.rawObservationId()) && present(item.sourceRef()) && present(item.rawKind())
        && present(item.rawPayloadDigest()) && present(item.observedAt());
  }

  private static boolean validCollectionStatus(CollectionStatus item, String connectionId) {
    return item != null && present(item.id()) && connectionId.equals(item.connectionId())
        && present(item.sourceRef()) && item.running() != null;
  }

  private static boolean validObservation(Observation item) {
    return item != null && present(item.id()) && present(item.sourceRef())
        && item.sequence() > 0 && present(item.observedAt()) && present(item.kind())
        && item.payload() != null && item.payload().length() <= 16_384
        && present(item.payloadDigest()) && item.payloadDigest().matches("^[0-9a-f]{64}$");
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }

  record ConnectionPage(List<Connection> items, @JsonProperty("next_cursor") String nextCursor) {}

  record CollectionStatusPage(
      List<CollectionStatus> items, @JsonProperty("next_cursor") String nextCursor) {}

  record Receipt(
      String id, long revision, @JsonProperty("record_kind") String recordKind,
      String result, @JsonProperty("runtime_effect") String runtimeEffect) {}

  record IngestResult(String id, String status, String reason, long sequence) {}

  record SampleObjectiveDefinition(
      @JsonProperty("connection_id") String connectionId,
      @JsonProperty("connection_revision") long connectionRevision,
      @JsonProperty("binding_id") String bindingId,
      @JsonProperty("binding_revision") long bindingRevision,
      @JsonProperty("target_workload_id") String targetWorkloadId,
      @JsonProperty("source_system") String sourceSystem,
      @JsonProperty("external_objective_id") String externalObjectiveId,
      @JsonProperty("source_version") String sourceVersion, String payload,
      @JsonProperty("payload_digest") String payloadDigest, String verification) {}

  record CollectionStatus(
      String id,
      @JsonProperty("connection_id") String connectionId,
      @JsonProperty("source_ref") String sourceRef,
      @JsonProperty("last_status") String lastStatus,
      @JsonProperty("last_detail") String lastDetail,
      Boolean running,
      @JsonProperty("last_collected_at") String lastCollectedAt,
      @JsonProperty("next_collection_at") String nextCollectionAt) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Connection(
      String id, String name, String provider, String status,
      @JsonProperty("status_reason") String statusReason) {}

  record InventoryPage(List<InventoryObservation> items, @JsonProperty("next_cursor") String nextCursor) {}

  record ObservationPage(List<Observation> items, @JsonProperty("next_cursor") String nextCursor) {}

  record Observation(
      String id,
      @JsonProperty("source_ref") String sourceRef,
      long sequence,
      @JsonProperty("observed_at") String observedAt,
      String kind,
      String payload,
      @JsonProperty("payload_digest") String payloadDigest) {}

  record InventoryObservation(
      @JsonProperty("normalizer_version") String normalizerVersion,
      @JsonProperty("source_class") String sourceClass,
      String provider,
      @JsonProperty("native_id") String nativeId,
      @JsonProperty("native_type") String nativeType,
      String location,
      @JsonProperty("raw_observation_id") String rawObservationId,
      @JsonProperty("source_ref") String sourceRef,
      @JsonProperty("raw_kind") String rawKind,
      @JsonProperty("raw_payload_digest") String rawPayloadDigest,
      @JsonProperty("observed_at") String observedAt) {}
}
