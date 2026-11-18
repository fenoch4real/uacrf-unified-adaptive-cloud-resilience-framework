package org.uacrf.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.uacrf.connections.ProviderCollector.Batch;
import org.uacrf.connections.ProviderCollector.Request;
import tools.jackson.databind.ObjectMapper;

class AzureCollectorTests {
  private static final String SOURCE_REF = "azure/westeurope/resourcegraph/inventory";
  private static final String SUBSCRIPTION_ID = "00000000-0000-0000-0000-000000000001";
  private static final Instant COLLECTED_AT = Instant.parse("2026-11-18T12:00:00Z");

  @Test
  void buildsResourceGraphFirstPageAndContinuationRequests() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    String query = "Resources | project id | order by id asc";
    var firstPage =
        objectMapper.readTree(
            objectMapper.writeValueAsString(
                AzureCollector.requestBody(SUBSCRIPTION_ID, query, null)));
    var continuation =
        objectMapper.readTree(
            objectMapper.writeValueAsString(
                AzureCollector.requestBody(SUBSCRIPTION_ID, query, "opaque-token")));

    assertThat(firstPage.get("subscriptions").get(0).stringValue())
        .isEqualTo(SUBSCRIPTION_ID);
    assertThat(firstPage.get("query").stringValue()).isEqualTo(query);
    assertThat(firstPage.get("options").get("$top").intValue()).isEqualTo(100);
    assertThat(firstPage.get("options").get("$skip").intValue()).isZero();
    assertThat(continuation.get("options").get("$skipToken").stringValue())
        .isEqualTo("opaque-token");
    assertThat(continuation.get("options").has("$top")).isFalse();
  }

  @Test
  void queriesOneRegionalPageAndCreatesSequencedTimestampedObservations() {
    AtomicReference<String> requestedSubscription = new AtomicReference<>();
    AtomicReference<String> requestedQuery = new AtomicReference<>();
    AtomicReference<String> requestedToken = new AtomicReference<>();
    AtomicBoolean closed = new AtomicBoolean();
    AzureCollector.InventorySession session =
        new AzureCollector.InventorySession() {
          @Override
          public AzureCollector.QueryResponse query(
              String subscriptionId, String query, String skipToken) {
            requestedSubscription.set(subscriptionId);
            requestedQuery.set(query);
            requestedToken.set(skipToken);
            return response(
                200,
                Map.of(),
                """
                {
                  "resultTruncated": "false",
                  "$skipToken": "next-page",
                  "data": [
                    {"id":"/subscriptions/sub/resourceGroups/rg/providers/Microsoft.Compute/virtualMachines/vm-1","name":"vm-1","type":"microsoft.compute/virtualmachines","location":"westeurope","subscriptionId":"sub","resourceGroup":"rg"},
                    {"id":"/subscriptions/sub/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/store1","name":"store1","type":"microsoft.storage/storageaccounts","location":"westeurope","subscriptionId":"sub","resourceGroup":"rg"}
                  ]
                }
                """);
          }

          @Override
          public void close() {
            closed.set(true);
          }
        };
    AzureCollector collector = new AzureCollector(() -> session, new ObjectMapper());

    Batch batch = collector.collect(request(null, 7));

    assertThat(collector.provider()).isEqualTo(ConnectionsRequests.Provider.AZURE);
    assertThat(requestedSubscription).hasValue(SUBSCRIPTION_ID);
    assertThat(requestedQuery.get())
        .contains("location =~ 'westeurope'", "project id, name, type, location")
        .contains("subscriptionId", "resourceGroup", "order by id asc")
        .doesNotContain("properties", "tags");
    assertThat(requestedToken).hasValue(null);
    assertThat(batch.status()).isEqualTo(Batch.Status.COLLECTED);
    assertThat(batch.nextCheckpoint()).isEqualTo("next-page");
    assertThat(batch.observations())
        .extracting(ProviderCollector.Observation::sequence)
        .containsExactly(8L, 9L);
    assertThat(batch.observations())
        .extracting(ProviderCollector.Observation::observedAt)
        .containsExactly(COLLECTED_AT, COLLECTED_AT);
    assertThat(batch.observations())
        .extracting(ProviderCollector.Observation::kind)
        .containsOnly("azure.resourcegraph.rows");
    assertThat(batch.observations())
        .extracting(ProviderCollector.Observation::id)
        .doesNotHaveDuplicates();
    assertThat(closed).isTrue();
  }

  @Test
  void resumesSkipTokenPaginationWithContinuousSequencesAndStableObservationIds() {
    List<AzureCollector.QueryResponse> pages =
        List.of(
            response(
                200,
                Map.of(),
                """
                {"resultTruncated":false,"$skipToken":"page-2","data":[{"id":"resource-1"}]}
                """),
            response(
                200,
                Map.of(),
                """
                {"resultTruncated":false,"data":[{"id":"resource-2"}]}
                """));
    AtomicInteger pageIndex = new AtomicInteger();
    List<String> requestedTokens = new ArrayList<>();
    AzureCollector collector =
        new AzureCollector(
            () ->
                new AzureCollector.InventorySession() {
                  @Override
                  public AzureCollector.QueryResponse query(
                      String subscriptionId, String query, String skipToken) {
                    requestedTokens.add(skipToken);
                    return pages.get(pageIndex.getAndIncrement());
                  }

                  @Override
                  public void close() {}
                },
            new ObjectMapper());

    Batch first = collector.collect(request(null, 0));
    Batch second = collector.collect(request(first.nextCheckpoint(), first.observations().getFirst().sequence()));

    assertThat(requestedTokens).containsExactly(null, "page-2");
    assertThat(first.status()).isEqualTo(Batch.Status.COLLECTED);
    assertThat(first.observations())
        .extracting(ProviderCollector.Observation::sequence)
        .containsExactly(1L);
    assertThat(second.status()).isEqualTo(Batch.Status.COLLECTED);
    assertThat(second.nextCheckpoint()).isNull();
    assertThat(second.observations())
        .extracting(ProviderCollector.Observation::sequence)
        .containsExactly(2L);
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
    Batch rateLimited =
        collectFailure(response(429, Map.of("Retry-After", "2"), ""));
    assertThat(rateLimited.status()).isEqualTo(Batch.Status.RATE_LIMITED);
    assertThat(rateLimited.nextCheckpoint()).isEqualTo("previous-page");
    assertThat(rateLimited.observations()).isEmpty();
    assertThat(rateLimited.retryAt()).isAfter(before);
    assertThat(collectFailure(response(503, Map.of(), "")).status())
        .isEqualTo(Batch.Status.SOURCE_UNAVAILABLE);

    AzureCollector unavailable =
        new AzureCollector(
            () ->
                new AzureCollector.InventorySession() {
                  @Override
                  public AzureCollector.QueryResponse query(
                      String subscriptionId, String query, String skipToken) throws IOException {
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
  void refusesTruncatedResponseWithoutContinuationToken() {
    Batch batch =
        collectFailure(
            response(
                200,
                Map.of(),
                """
                {"resultTruncated":true,"data":[{"id":"resource-1"}]}
                """));

    assertThat(batch.status()).isEqualTo(Batch.Status.SOURCE_UNAVAILABLE);
    assertThat(batch.observations()).isEmpty();
    assertThat(batch.nextCheckpoint()).isEqualTo("previous-page");
    assertThat(batch.detail()).contains("without a continuation token");
  }

  @Test
  void rejectsResponseWithoutTruncationMetadata() {
    assertThatThrownBy(
            () ->
                collectFailure(
                    response(200, Map.of(), """
                        {"data":[{"id":"resource-1"}]}
                        """)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("resultTruncated");
  }

  @Test
  void rejectsInvalidSourceSubscriptionAndMissingCollectionTimeBeforeOpeningSession() {
    AzureCollector collector =
        new AzureCollector(
            () -> {
              throw new AssertionError("Invalid collection requests must not open a session.");
            },
            new ObjectMapper());

    assertThatThrownBy(() -> collector.collect(request("previous-page", 0, "azure/inventory", SUBSCRIPTION_ID)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source_ref");
    assertThatThrownBy(() -> collector.collect(request("previous-page", 0, SOURCE_REF, "subscription-placeholder")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("subscription ID");
    assertThatThrownBy(
            () ->
                collector.collect(
                    new Request(
                        "connection-1",
                        SOURCE_REF,
                        SUBSCRIPTION_ID,
                        null,
                        null,
                        0,
                        null,
                        null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("collection time");
  }

  private static Batch collectFailure(AzureCollector.QueryResponse response) {
    AzureCollector collector =
        new AzureCollector(
            () ->
                new AzureCollector.InventorySession() {
                  @Override
                  public AzureCollector.QueryResponse query(
                      String subscriptionId, String query, String skipToken) {
                    return response;
                  }

                  @Override
                  public void close() {}
                },
            new ObjectMapper());
    return collector.collect(request("previous-page", 9));
  }

  private static AzureCollector.QueryResponse response(
      int statusCode, Map<String, String> headers, String body) {
    return new AzureCollector.QueryResponse(statusCode, headers, body);
  }

  private static Request request(String checkpoint, long lastSequence) {
    return request(checkpoint, lastSequence, SOURCE_REF, SUBSCRIPTION_ID);
  }

  private static Request request(
      String checkpoint, long lastSequence, String sourceRef, String subscriptionId) {
    return new Request(
        "connection-1",
        sourceRef,
        subscriptionId,
        null,
        checkpoint,
        lastSequence,
        null,
        COLLECTED_AT);
  }
}
