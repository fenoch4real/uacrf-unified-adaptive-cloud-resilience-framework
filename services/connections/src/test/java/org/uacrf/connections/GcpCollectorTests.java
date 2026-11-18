package org.uacrf.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.uacrf.connections.ProviderCollector.Batch;
import org.uacrf.connections.ProviderCollector.Request;
import tools.jackson.databind.ObjectMapper;

class GcpCollectorTests {
  private static final String SOURCE_REF = "gcp/europe-west1/assetinventory/inventory";
  private static final String PROJECT_ID = "sample-project-123";
  private static final Instant COLLECTED_AT = Instant.parse("2026-11-18T12:00:00Z");

  @Test
  void buildsProjectAssetInventoryFirstAndContinuationRequests() {
    var firstPage = GcpCollector.requestUri(PROJECT_ID, null);
    var continuation = GcpCollector.requestUri(PROJECT_ID, "opaque+/=");

    assertThat(firstPage.getPath()).isEqualTo("/v1/projects/sample-project-123/assets");
    assertThat(firstPage.getRawQuery()).isEqualTo("pageSize=100&contentType=RESOURCE");
    assertThat(continuation.getRawQuery())
        .isEqualTo("pageSize=100&contentType=RESOURCE&pageToken=opaque%2B%2F%3D");
  }

  @Test
  void collectsMinimalProjectWideAssetMetadataAndReturnsNextPageToken() {
    AtomicInteger requested = new AtomicInteger();
    GcpCollector collector =
        new GcpCollector(
            () ->
                new GcpCollector.InventorySession() {
                  @Override
                  public GcpCollector.QueryResponse listAssets(String projectId, String pageToken) {
                    assertThat(projectId).isEqualTo(PROJECT_ID);
                    assertThat(pageToken).isNull();
                    requested.incrementAndGet();
                    return response(
                        200,
                        Map.of(),
                        """
                        {
                          "nextPageToken":"next-page",
                          "assets":[{
                            "name":"//compute.googleapis.com/projects/sample-project-123/zones/europe-west1-b/instances/vm-1",
                            "assetType":"compute.googleapis.com/Instance",
                            "updateTime":"2026-11-18T11:59:00Z",
                            "resource":{"location":"europe-west1-b","data":{"secret":"must-not-be-collected"}}
                          }]
                        }
                        """);
                  }

                  @Override
                  public void close() {}
                },
            new ObjectMapper());

    Batch batch = collector.collect(request(null, 5));

    assertThat(collector.provider()).isEqualTo(ConnectionsRequests.Provider.GCP);
    assertThat(requested).hasValue(1);
    assertThat(batch.status()).isEqualTo(Batch.Status.COLLECTED);
    assertThat(batch.nextCheckpoint()).isEqualTo("next-page");
    assertThat(batch.observations())
        .extracting(ProviderCollector.Observation::sequence)
        .containsExactly(6L);
    assertThat(batch.observations().getFirst().observedAt()).isEqualTo(COLLECTED_AT);
    assertThat(batch.observations().getFirst().kind()).isEqualTo("gcp.assetinventory.assets");
    assertThat(batch.observations().getFirst().payload())
        .contains("compute.googleapis.com/Instance", "vm-1", "2026-11-18T11:59:00Z")
        .doesNotContain("secret", "location", "resource");
  }

  @Test
  void resumesPageTokenPaginationWithContinuousSequencesAndStableObservationIds() {
    List<GcpCollector.QueryResponse> pages =
        List.of(
            response(
                200,
                Map.of(),
                """
                {"nextPageToken":"page-2","assets":[{"name":"asset-1","assetType":"type/One"}]}
                """),
            response(
                200,
                Map.of(),
                """
                {"assets":[{"name":"asset-2","assetType":"type/Two"}]}
                """));
    AtomicInteger pageIndex = new AtomicInteger();
    List<String> requestedTokens = new ArrayList<>();
    GcpCollector collector =
        new GcpCollector(
            () ->
                new GcpCollector.InventorySession() {
                  @Override
                  public GcpCollector.QueryResponse listAssets(String projectId, String pageToken) {
                    requestedTokens.add(pageToken);
                    return pages.get(pageIndex.getAndIncrement());
                  }

                  @Override
                  public void close() {}
                },
            new ObjectMapper());

    Batch first = collector.collect(request(null, 0));
    Batch second =
        collector.collect(
            new Request(
                "connection-1",
                SOURCE_REF,
                PROJECT_ID,
                null,
                first.nextCheckpoint(),
                first.observations().getFirst().sequence(),
                null,
                COLLECTED_AT));

    assertThat(requestedTokens).containsExactly(null, "page-2");
    assertThat(first.nextCheckpoint()).isEqualTo("page-2");
    assertThat(first.observations().getFirst().sequence()).isEqualTo(1);
    assertThat(second.nextCheckpoint()).isNull();
    assertThat(second.observations().getFirst().sequence()).isEqualTo(2);
    assertThat(second.observations().getFirst().id())
        .isNotEqualTo(first.observations().getFirst().id());
  }

  @Test
  void mapsPermissionRateLimitAndOutageWithoutAdvancingCheckpoint() {
    assertThat(collectFailure(response(403, Map.of(), "")).status())
        .isEqualTo(Batch.Status.PERMISSION_DENIED);
    assertThat(collectFailure(response(401, Map.of(), "")).status())
        .isEqualTo(Batch.Status.PERMISSION_DENIED);

    Instant before = Instant.now();
    Batch rateLimited = collectFailure(response(429, Map.of("Retry-After", "2"), ""));
    assertThat(rateLimited.status()).isEqualTo(Batch.Status.RATE_LIMITED);
    assertThat(rateLimited.nextCheckpoint()).isEqualTo("previous-page");
    assertThat(rateLimited.observations()).isEmpty();
    assertThat(rateLimited.retryAt()).isAfter(before);
    assertThat(collectFailure(response(503, Map.of(), "")).status())
        .isEqualTo(Batch.Status.SOURCE_UNAVAILABLE);

    GcpCollector unavailable =
        new GcpCollector(
            () ->
                new GcpCollector.InventorySession() {
                  @Override
                  public GcpCollector.QueryResponse listAssets(String projectId, String pageToken)
                      throws IOException {
                    throw new IOException("connection refused");
                  }

                  @Override
                  public void close() {}
                },
            new ObjectMapper());
    Batch failed = unavailable.collect(request("previous-page", 9));
    assertThat(failed.status()).isEqualTo(Batch.Status.SOURCE_UNAVAILABLE);
    assertThat(failed.nextCheckpoint()).isEqualTo("previous-page");
  }

  @Test
  void rejectsInvalidSourceProjectAndMissingCollectionTimeBeforeOpeningSession() {
    GcpCollector collector =
        new GcpCollector(
            () -> {
              throw new AssertionError("Invalid collection requests must not open a session.");
            },
            new ObjectMapper());

    assertThatThrownBy(() -> collector.collect(request("previous-page", 0, "gcp/inventory", PROJECT_ID, COLLECTED_AT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source_ref");
    assertThatThrownBy(() -> collector.collect(request("previous-page", 0, SOURCE_REF, "Project_ID", COLLECTED_AT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("project ID");
    assertThatThrownBy(() -> collector.collect(request("previous-page", 0, SOURCE_REF, PROJECT_ID, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("collection time");
  }

  @Test
  void rejectsAssetsMissingRequiredMetadata() {
    GcpCollector collector =
        new GcpCollector(
            () ->
                new GcpCollector.InventorySession() {
                  @Override
                  public GcpCollector.QueryResponse listAssets(String projectId, String pageToken) {
                    return response(200, Map.of(), """
                        {"assets":[{"name":"asset-1"}]}
                        """);
                  }

                  @Override
                  public void close() {}
                },
            new ObjectMapper());

    assertThatThrownBy(() -> collector.collect(request(null, 0)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("assetType");
  }

  private static Batch collectFailure(GcpCollector.QueryResponse response) {
    GcpCollector collector =
        new GcpCollector(
            () ->
                new GcpCollector.InventorySession() {
                  @Override
                  public GcpCollector.QueryResponse listAssets(String projectId, String pageToken) {
                    return response;
                  }

                  @Override
                  public void close() {}
                },
            new ObjectMapper());
    return collector.collect(request("previous-page", 9));
  }

  private static GcpCollector.QueryResponse response(
      int statusCode, Map<String, String> headers, String body) {
    return new GcpCollector.QueryResponse(statusCode, headers, body);
  }

  private static Request request(String checkpoint, long lastSequence) {
    return request(checkpoint, lastSequence, SOURCE_REF, PROJECT_ID, COLLECTED_AT);
  }

  private static Request request(
      String checkpoint,
      long lastSequence,
      String sourceRef,
      String projectId,
      Instant collectedAt) {
    return new Request(
        "connection-1",
        sourceRef,
        projectId,
        null,
        checkpoint,
        lastSequence,
        null,
        collectedAt);
  }
}
