package org.uacrf.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
  void readsCollectionStatusesWithAnIndependentCursorAndAllowsNeverRunFields() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test/internal/connections");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo(
            "http://edge.test/internal/connections/v1/connections/sample/collection-statuses?pageSize=25&cursor=status-cursor"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andRespond(withSuccess("""
            {"items":[{"id":"binding","connection_id":"sample",
            "source_ref":"aws/us-east-1/config/inventory","last_status":null,
            "last_detail":null,"running":false,"last_collected_at":null,
            "next_collection_at":null}],"next_cursor":"next-status-page"}
            """, MediaType.APPLICATION_JSON));

    ConnectionsClient.CollectionStatusPage page =
        new ConnectionsClient(builder.build())
            .listCollectionStatuses("delegated", "sample", "status-cursor");

    assertThat(page.nextCursor()).isEqualTo("next-status-page");
    assertThat(page.items()).singleElement().satisfies(status -> {
      assertThat(status.sourceRef()).isEqualTo("aws/us-east-1/config/inventory");
      assertThat(status.lastStatus()).isNull();
      assertThat(status.lastDetail()).isNull();
      assertThat(status.running()).isFalse();
      assertThat(status.lastCollectedAt()).isNull();
      assertThat(status.nextCollectionAt()).isNull();
    });
    server.verify();
  }

  @Test
  void readsRetainedObservationsWithPayloadAndPagination() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test/internal/connections");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo(
            "http://edge.test/internal/connections/v1/connections/sample/observations?pageSize=25&cursor=next"))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andRespond(withSuccess("""
            {"items":[{"id":"obs-metric","source_ref":"sample:aws/metric",
            "sequence":1,"observed_at":"2026-11-12T09:05:00Z",
            "kind":"aws.cloudwatch.metrics","payload":"{\\"MetricDataResults\\":[]}",
            "payload_digest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],
            "next_cursor":"next-page"}
            """, MediaType.APPLICATION_JSON));

    ConnectionsClient.ObservationPage page =
        new ConnectionsClient(builder.build())
            .listObservations("delegated", "sample", "next");

    assertThat(page.nextCursor()).isEqualTo("next-page");
    assertThat(page.items()).singleElement().satisfies(observation -> {
      assertThat(observation.id()).isEqualTo("obs-metric");
      assertThat(observation.kind()).isEqualTo("aws.cloudwatch.metrics");
      assertThat(observation.payload()).contains("MetricDataResults");
      assertThat(observation.payloadDigest()).hasSize(64);
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

  @Test
  void submitsSampleMutationsWithDelegationAndChecksReceipts() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://edge.test/internal/connections");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://edge.test/internal/connections/v1/connections"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated"))
        .andExpect(header("Idempotency-Key", "register-key"))
        .andExpect(jsonPath("$.provider").value("SAMPLE_LOCAL"))
        .andExpect(jsonPath("$.owner_id").value("owner"))
        .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
            .withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
            .body("{\"id\":\"sample\",\"revision\":1,\"record_kind\":\"CONNECTION\",\"result\":\"CREATED\",\"runtime_effect\":\"NONE\"}"));
    server.expect(requestTo("http://edge.test/internal/connections/v1/connections/sample/source-bindings"))
        .andExpect(header("Idempotency-Key", "binding-key"))
        .andExpect(jsonPath("$.source_ref").value("sample:aws/inventory"))
        .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
            .withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
            .body("{\"id\":\"binding\",\"revision\":1,\"record_kind\":\"SOURCE_BINDING\",\"result\":\"CREATED\",\"runtime_effect\":\"NONE\"}"));
    server.expect(requestTo("http://edge.test/internal/connections/v1/connections/sample/observations"))
        .andExpect(jsonPath("$.payload_digest").value("digest"))
        .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
            .withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
            .body("{\"id\":\"observation\",\"status\":\"DUPLICATE\",\"reason\":\"Already accepted.\",\"sequence\":1}"));
    ConnectionsClient client = new ConnectionsClient(builder.build());
    client.registerSample("delegated", "register-key",
        new SampleInventory.Connection("sample", "Sample", "SAMPLE_LOCAL", "sample/ref", "owner", 0));
    client.bindSample("delegated", "sample", "binding-key",
        new SampleInventory.Binding("binding", "sample:aws/inventory", null, 0));
    assertThat(client.submitSample("delegated", "sample",
        new SampleInventory.Observation("observation", "sample:aws/inventory", 1,
            "2026-11-12T09:05:00Z", "aws.config.resources", "{}", "digest")).status())
        .isEqualTo("DUPLICATE");
    server.verify();
  }
}
