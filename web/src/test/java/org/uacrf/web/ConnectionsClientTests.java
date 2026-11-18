package org.uacrf.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ConnectionsClientTests {
  @Test
  void readsConnectionAndInventoryThroughEdgeWithPaginationAndProvenance() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test/internal/connections");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://edge.test/internal/connections/v1/connections?pageSize=25&cursor=start"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andRespond(withSuccess("""
            {"items":[{"id":"sample","name":"Sample estate","provider":"SAMPLE_LOCAL",
            "status":"ACTIVE","status_reason":null,"read_only_identity_ref":"fixture"}],"next_cursor":"next"}
            """, MediaType.APPLICATION_JSON));
    server.expect(requestTo("http://edge.test/internal/connections/v1/connections/sample"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andRespond(withSuccess("""
            {"id":"sample","name":"Sample estate","provider":"SAMPLE_LOCAL",
            "status":"ACTIVE","status_reason":null,"aws_external_id":null}
            """, MediaType.APPLICATION_JSON));
    server.expect(requestTo("http://edge.test/internal/connections/v1/connections/sample/inventory-observations?pageSize=25&cursor=next"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andRespond(withSuccess("""
            {"items":[{"normalizer_version":"inventory-observation.v1","source_class":"SAMPLE_LOCAL",
            "provider":"GCP","native_id":"asset","native_type":"type","location":null,
            "raw_observation_id":"obs","source_ref":"sample:gcp/source",
            "raw_kind":"gcp.assetinventory.assets","raw_payload_digest":"digest",
            "observed_at":"2026-11-12T09:05:40Z"}],"next_cursor":null}
            """, MediaType.APPLICATION_JSON));

    ConnectionsClient client = new ConnectionsClient(builder.build());
    assertThat(client.list("delegated", "start").nextCursor()).isEqualTo("next");
    ConnectionsClient.Connection connection = client.get("delegated", "sample");
    assertThat(connection.provider()).isEqualTo("SAMPLE_LOCAL");
    assertThat(client.listInventory("delegated", "sample", "next", connection.provider()).items())
        .singleElement().satisfies(item -> {
          assertThat(item.provider()).isEqualTo("GCP");
          assertThat(item.location()).isNull();
          assertThat(item.rawObservationId()).isEqualTo("obs");
        });
    server.verify();
  }

  @Test
  void rejectsInventoryWithConflictingSourceClass() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://edge.test/v1/connections/sample/inventory-observations?pageSize=25"))
        .andRespond(withSuccess("""
            {"items":[{"normalizer_version":"inventory-observation.v1","source_class":"AWS",
            "provider":"AWS","native_id":"asset","native_type":"type","location":null,
            "raw_observation_id":"obs","source_ref":"source","raw_kind":"aws.config.resources",
            "raw_payload_digest":"digest","observed_at":"2026-11-12T09:05:40Z"}],"next_cursor":null}
            """, MediaType.APPLICATION_JSON));
    ConnectionsClient client = new ConnectionsClient(builder.build());
    assertThatThrownBy(() -> client.listInventory("delegated", "sample", null, "SAMPLE_LOCAL"))
        .isInstanceOf(IllegalStateException.class);
    server.verify();
  }
}
