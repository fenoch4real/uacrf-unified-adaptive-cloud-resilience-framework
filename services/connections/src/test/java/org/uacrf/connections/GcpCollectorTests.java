package org.uacrf.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
  private static final String METRIC_TYPE = "run.googleapis.com/request_latencies";
  private static final String RESOURCE_TYPE = "cloud_run_revision";

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

  @Test
  void buildsScopedTimeSeriesRequestWithIntervalAndEncodedPageToken() {
    Instant from = Instant.parse("2026-11-18T11:55:00Z");
    Instant to = COLLECTED_AT;
    URI uri =
        GcpCollector.monitoringUri(
            PROJECT_ID, METRIC_TYPE, RESOURCE_TYPE, from, to, "opaque+/=");

    assertThat(uri.getHost()).isEqualTo("monitoring.googleapis.com");
    assertThat(uri.getPath())
        .isEqualTo("/v3/projects/" + PROJECT_ID + "/timeSeries");
    assertThat(uri.getRawQuery())
        .contains(
            "filter=metric.type+%3D+%22run.googleapis.com%2Frequest_latencies%22+AND+resource.type+%3D+%22cloud_run_revision%22",
            "interval.startTime=2026-11-18T11%3A55%3A00Z",
            "interval.endTime=2026-11-18T12%3A00%3A00Z",
            "view=FULL",
            "pageSize=100",
            "pageToken=opaque%2B%2F%3D");
  }

  @Test
  void collectsScopedTimeSeriesAndFiltersPointsToTheHalfOpenWindow() {
    Instant from = Instant.parse("2026-11-18T11:55:00Z");
    AtomicInteger requested = new AtomicInteger();
    GcpCollector collector =
        new GcpCollector(
            () ->
                new GcpCollector.InventorySession() {
                  @Override
                  public GcpCollector.QueryResponse listAssets(String projectId, String pageToken) {
                    throw new AssertionError("Metric collection must not query Asset Inventory.");
                  }

                  @Override
                  public GcpCollector.QueryResponse listTimeSeries(
                      String projectId,
                      String metricType,
                      String resourceType,
                      Instant start,
                      Instant end,
                      String pageToken) {
                    assertThat(projectId).isEqualTo(PROJECT_ID);
                    assertThat(metricType).isEqualTo(METRIC_TYPE);
                    assertThat(resourceType).isEqualTo(RESOURCE_TYPE);
                    assertThat(start).isEqualTo(from);
                    assertThat(end).isEqualTo(COLLECTED_AT);
                    assertThat(pageToken).isNull();
                    requested.incrementAndGet();
                    return response(
                        200,
                        Map.of(),
                        """
                        {
                          "unit": "ms",
                          "timeSeries": [{
                            "metric": {"type":"run.googleapis.com/request_latencies","labels":{}},
                            "resource": {
                              "type":"cloud_run_revision",
                              "labels":{"project_id":"sample-project-123","location":"europe-west1","service_name":"inventory"}
                            },
                            "metricKind":"GAUGE",
                            "valueType":"DOUBLE",
                            "unit":"ms",
                            "points": [
                              {"interval":{"endTime":"2026-11-18T11:54:00Z"},"value":{"doubleValue":90.0}},
                              {"interval":{"startTime":"2026-11-18T11:50:00Z","endTime":"2026-11-18T11:55:00Z"},"value":{"doubleValue":91.0}},
                              {"interval":{"startTime":"2026-11-18T11:55:00Z","endTime":"2026-11-18T11:58:00Z"},"value":{"doubleValue":94.0}},
                              {"interval":{"endTime":"2026-11-18T12:00:00Z"},"value":{"doubleValue":99.0}}
                            ]
                          }]
                        }
                        """);
                  }

                  @Override
                  public void close() {}
                },
            new ObjectMapper());

    Batch batch = collector.collect(metricRequest(null, 4, null, COLLECTED_AT));

    assertThat(requested).hasValue(1);
    assertThat(batch.status()).isEqualTo(Batch.Status.COLLECTED);
    assertThat(batch.nextCheckpoint()).isNull();
    assertThat(batch.observations())
        .extracting(ProviderCollector.Observation::sequence)
        .containsExactly(5L);
    assertThat(batch.observations())
        .extracting(ProviderCollector.Observation::observedAt)
        .containsExactly(Instant.parse("2026-11-18T11:58:00Z"));
    assertThat(batch.observations())
        .extracting(ProviderCollector.Observation::kind)
        .containsOnly("gcp.monitoring.timeseries");
    var payload = new ObjectMapper().readTree(batch.observations().getFirst().payload());
    assertThat(payload.get("unit").stringValue()).isEqualTo("ms");
    assertThat(payload.get("timeSeries").get(0).get("unit").stringValue()).isEqualTo("ms");
    assertThat(payload.get("timeSeries").get(0).get("points")).hasSize(2);
    assertThat(
            payload
                .get("timeSeries")
                .get(0)
                .get("points")
                .get(0)
                .get("value")
                .get("doubleValue")
                .doubleValue())
        .isEqualTo(91.0);
  }

  @Test
  void resumesMonitoringPaginationAndPreservesContinuousSequences() {
    List<GcpCollector.QueryResponse> pages =
        List.of(
            response(
                200,
                Map.of(),
                """
                {"nextPageToken":"next+/=","timeSeries":[
                  {"metric":{"type":"run.googleapis.com/request_latencies"},"resource":{"type":"cloud_run_revision"},
                   "metricKind":"GAUGE","valueType":"DOUBLE","points":[
                     {"interval":{"endTime":"2026-11-18T11:58:00Z"},"value":{"doubleValue":94.0}}]}]}
                """),
            response(
                200,
                Map.of(),
                """
                {"timeSeries":[
                  {"metric":{"type":"run.googleapis.com/request_latencies"},"resource":{"type":"cloud_run_revision"},
                   "metricKind":"GAUGE","valueType":"DOUBLE","points":[
                     {"interval":{"endTime":"2026-11-18T11:59:00Z"},"value":{"doubleValue":95.0}}]}]}
                """));
    AtomicInteger pageIndex = new AtomicInteger();
    List<String> requestedTokens = new ArrayList<>();
    List<Instant> requestedFrom = new ArrayList<>();
    List<Instant> requestedTo = new ArrayList<>();
    GcpCollector collector =
        new GcpCollector(
            () ->
                new GcpCollector.InventorySession() {
                  @Override
                  public GcpCollector.QueryResponse listAssets(String projectId, String pageToken) {
                    throw new AssertionError("Metric collection must not query Asset Inventory.");
                  }

                  @Override
                  public GcpCollector.QueryResponse listTimeSeries(
                      String projectId,
                      String metricType,
                      String resourceType,
                      Instant from,
                      Instant to,
                      String pageToken) {
                    requestedTokens.add(pageToken);
                    requestedFrom.add(from);
                    requestedTo.add(to);
                    return pages.get(pageIndex.getAndIncrement());
                  }

                  @Override
                  public void close() {}
                },
            new ObjectMapper());
    Instant from = Instant.parse("2026-11-18T11:55:00Z");

    Batch first = collector.collect(metricRequest(null, 0, from, COLLECTED_AT));
    Batch second =
        collector.collect(
            metricRequest(
                first.nextCheckpoint(),
                first.observations().getFirst().sequence(),
                COLLECTED_AT,
                COLLECTED_AT.plusSeconds(300)));

    assertThat(requestedTokens).containsExactly(null, "next+/=");
    assertThat(first.nextCheckpoint()).startsWith("gcp-monitoring-v1/");
    assertThat(requestedFrom).containsExactly(from, from);
    assertThat(requestedTo).containsExactly(COLLECTED_AT, COLLECTED_AT);
    assertThat(first.observations().getFirst().sequence()).isEqualTo(1);
    assertThat(second.nextCheckpoint()).isNull();
    assertThat(second.observations().getFirst().sequence()).isEqualTo(2);
    assertThat(second.observations().getFirst().id())
        .isNotEqualTo(first.observations().getFirst().id());
  }

  @Test
  void rejectsMonitoringResponseOutsideRequestedMetricScopeAndMalformedPoints() {
    assertThatThrownBy(
            () ->
                collectMetricFailure(
                    response(
                        200,
                        Map.of(),
                        """
                        {"timeSeries":[
                          {"metric":{"type":"other.googleapis.com/metric"},"resource":{"type":"cloud_run_revision"},
                           "points":[]}]}
                        """)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outside the requested metric scope");

    assertThatThrownBy(
            () ->
                collectMetricFailure(
                    response(
                        200,
                        Map.of(),
                        """
                        {"timeSeries":[
                          {"metric":{"type":"run.googleapis.com/request_latencies"},"resource":{"type":"cloud_run_revision"},
                           "points":[{"interval":{},"value":{"doubleValue":1.0}}]}]}
                        """)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("interval.endTime");
  }

  @Test
  void mapsMonitoringPermissionRateLimitAndOutageWithoutAdvancingPageToken() {
    assertThat(collectMetricFailure(response(403, Map.of(), "")).status())
        .isEqualTo(Batch.Status.PERMISSION_DENIED);

    Instant before = Instant.now();
    Batch rateLimited =
        collectMetricFailure(response(429, Map.of("Retry-After", "2"), ""));
    assertThat(rateLimited.status()).isEqualTo(Batch.Status.RATE_LIMITED);
    assertThat(rateLimited.nextCheckpoint()).isEqualTo(previousPageCheckpoint());
    assertThat(rateLimited.observations()).isEmpty();
    assertThat(rateLimited.retryAt()).isAfter(before);
    assertThat(collectMetricFailure(response(503, Map.of(), "")).status())
        .isEqualTo(Batch.Status.SOURCE_UNAVAILABLE);
  }

  @Test
  void refusesPartialMonitoringPagesAndOversizedObservationsWithoutAdvancing() {
    Batch partial =
        collectMetricFailure(
            response(
                200,
                Map.of(),
                """
                {
                  "nextPageToken":"would-skip-data",
                  "executionErrors":[{"code":8,"message":"partial backend failure"}],
                  "timeSeries":[]
                }
                """));
    assertThat(partial.status()).isEqualTo(Batch.Status.SOURCE_UNAVAILABLE);
    assertThat(partial.nextCheckpoint()).isEqualTo(previousPageCheckpoint());
    assertThat(partial.observations()).isEmpty();
    assertThat(partial.detail()).contains("partial data");

    String largePoint =
        "{\"timeSeries\":[{\"metric\":{\"type\":\""
            + METRIC_TYPE
            + "\"},\"resource\":{\"type\":\""
            + RESOURCE_TYPE
            + "\"},\"points\":[{\"interval\":{\"endTime\":\"2026-11-18T11:58:00Z\"},"
            + "\"value\":{\"stringValue\":\""
            + "x".repeat(17_000)
            + "\"}}]}]}";
    Batch oversized = collectMetricFailure(response(200, Map.of(), largePoint));
    assertThat(oversized.status()).isEqualTo(Batch.Status.SOURCE_UNAVAILABLE);
    assertThat(oversized.nextCheckpoint()).isEqualTo(previousPageCheckpoint());
    assertThat(oversized.observations()).isEmpty();
    assertThat(oversized.detail()).contains("payload limit");
  }

  @Test
  void rejectsInvalidMetricSourceAndWindowBeforeOpeningSession() {
    GcpCollector collector =
        new GcpCollector(
            () -> {
              throw new AssertionError("Invalid metric requests must not open a session.");
            },
            new ObjectMapper());
    Request tooWide =
        metricRequest(
            null,
            0,
            COLLECTED_AT.minusSeconds(86_401),
            COLLECTED_AT);
    assertThatThrownBy(() -> collector.collect(tooWide))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no longer than 24 hours");
    assertThatThrownBy(
            () ->
                collector.collect(
                    new Request(
                        "connection-1",
                        "gcp/monitoring/not-base64/cloud_run_revision",
                        PROJECT_ID,
                        null,
                        null,
                        0,
                        null,
                        COLLECTED_AT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid metric type");
    assertThatThrownBy(
            () ->
                collector.collect(
                    metricRequest("malformed-checkpoint", 0, null, COLLECTED_AT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checkpoint is invalid");
  }

  private static Batch collectMetricFailure(GcpCollector.QueryResponse response) {
    GcpCollector collector =
        new GcpCollector(
            () ->
                new GcpCollector.InventorySession() {
                  @Override
                  public GcpCollector.QueryResponse listAssets(String projectId, String pageToken) {
                    throw new AssertionError("Metric collection must not query Asset Inventory.");
                  }

                  @Override
                  public GcpCollector.QueryResponse listTimeSeries(
                      String projectId,
                      String metricType,
                      String resourceType,
                      Instant from,
                      Instant to,
                      String pageToken) {
                    return response;
                  }

                  @Override
                  public void close() {}
                },
            new ObjectMapper());
    return collector.collect(metricRequest(previousPageCheckpoint(), 9, null, COLLECTED_AT));
  }

  private static String previousPageCheckpoint() {
    String cursor =
        COLLECTED_AT.minusSeconds(300) + "\n" + COLLECTED_AT + "\nprevious-page";
    return "gcp-monitoring-v1/"
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(cursor.getBytes(StandardCharsets.UTF_8));
  }

  private static Request metricRequest(
      String pageToken, long lastSequence, Instant from, Instant to) {
    return new Request(
        "connection-1",
        GcpCollector.metricSourceRef(METRIC_TYPE, RESOURCE_TYPE),
        PROJECT_ID,
        null,
        pageToken,
        lastSequence,
        from,
        to);
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
