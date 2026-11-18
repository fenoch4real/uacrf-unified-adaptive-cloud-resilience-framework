package org.uacrf.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
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

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }

  record ConnectionPage(List<Connection> items, @JsonProperty("next_cursor") String nextCursor) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Connection(
      String id, String name, String provider, String status,
      @JsonProperty("status_reason") String statusReason) {}

  record InventoryPage(List<InventoryObservation> items, @JsonProperty("next_cursor") String nextCursor) {}

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
