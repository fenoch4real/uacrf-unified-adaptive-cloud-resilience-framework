package org.uacrf.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.config.model.SelectResourceConfigResponse;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.StatusCode;
import tools.jackson.databind.ObjectMapper;

class AwsCollectorTests {
  private static final String SOURCE_REF = "aws/eu-west-1/config/inventory";
  private static final String SQS_METRIC_SOURCE_REF =
      "aws/eu-west-1/cloudwatch/sqs/orders-queue";
  private static final String ROLE_ARN = "arn:aws:iam::123456789012:role/readonly";
  private static final String EXTERNAL_ID = "uacrf-test-external-id";

  @Test
  void readsOneConfigPageAndCreatesSequencedTimestampedObservations() {
    AtomicReference<String> requestedRegion = new AtomicReference<>();
    AtomicReference<String> requestedRole = new AtomicReference<>();
    AtomicReference<String> requestedExternalId = new AtomicReference<>();
    AtomicReference<String> requestedExpression = new AtomicReference<>();
    AtomicReference<String> requestedToken = new AtomicReference<>();
    AwsCollector.InventorySession session =
        new AwsCollector.InventorySession() {
          @Override
          public SelectResourceConfigResponse select(String expression, String nextToken) {
            requestedExpression.set(expression);
            requestedToken.set(nextToken);
            return SelectResourceConfigResponse.builder()
                .results(
                    "{\"resourceId\":\"bucket-1\",\"resourceType\":\"AWS::S3::Bucket\","
                        + "\"awsRegion\":\"eu-west-1\","
                        + "\"configurationItemCaptureTime\":\"2026-11-17T17:00:00Z\"}",
                    "{\"resourceId\":\"bucket-2\",\"resourceType\":\"AWS::S3::Bucket\","
                        + "\"awsRegion\":\"eu-west-1\","
                        + "\"configurationItemCaptureTime\":\"2026-11-17T17:01:00Z\"}")
                .nextToken("next-page")
                .build();
          }

          @Override
          public void close() {}
        };
    AwsCollector collector =
        new AwsCollector(
            (region, roleArn, externalId) -> {
              requestedRegion.set(region);
              requestedRole.set(roleArn);
              requestedExternalId.set(externalId);
              return session;
            },
            new ObjectMapper());

    ProviderCollector.Batch batch =
        collector.collect(
            new ProviderCollector.Request(
                "connection-1", SOURCE_REF, ROLE_ARN, EXTERNAL_ID, "previous-page", 7, null, null));

    assertThat(requestedRegion).hasValue("eu-west-1");
    assertThat(requestedRole).hasValue(ROLE_ARN);
    assertThat(requestedExternalId).hasValue(EXTERNAL_ID);
    assertThat(requestedExpression.get())
        .contains("resourceId", "resourceType", "awsRegion", "configurationItemCaptureTime");
    assertThat(requestedToken).hasValue("previous-page");
    assertThat(batch.status()).isEqualTo(ProviderCollector.Batch.Status.COLLECTED);
    assertThat(batch.nextCheckpoint()).isEqualTo("next-page");
    assertThat(batch.observations())
        .extracting(ProviderCollector.Observation::sequence)
        .containsExactly(8L, 9L);
    assertThat(batch.observations())
        .extracting(ProviderCollector.Observation::observedAt)
        .containsExactly(
            Instant.parse("2026-11-17T17:00:00Z"), Instant.parse("2026-11-17T17:01:00Z"));
    assertThat(batch.observations())
        .extracting(ProviderCollector.Observation::kind)
        .containsOnly("aws.config.resources");
    assertThat(batch.observations())
        .extracting(ProviderCollector.Observation::id)
        .doesNotHaveDuplicates();
    ProviderCollector.Batch repeated =
        collector.collect(
            new ProviderCollector.Request(
                "connection-1", SOURCE_REF, ROLE_ARN, EXTERNAL_ID, "previous-page", 7, null, null));
    assertThat(repeated.observations())
        .extracting(ProviderCollector.Observation::id)
        .containsExactlyElementsOf(
            batch.observations().stream().map(ProviderCollector.Observation::id).toList());
  }

  @Test
  void resumesConfigPaginationWithContinuousSequencesAndStableObservationIds() {
    List<SelectResourceConfigResponse> pages =
        List.of(
            SelectResourceConfigResponse.builder()
                .results(
                    "{\"resourceId\":\"bucket-1\",\"resourceType\":\"AWS::S3::Bucket\","
                        + "\"awsRegion\":\"eu-west-1\","
                        + "\"configurationItemCaptureTime\":\"2026-11-17T17:00:00Z\"}")
                .nextToken("page-2")
                .build(),
            SelectResourceConfigResponse.builder()
                .results(
                    "{\"resourceId\":\"bucket-2\",\"resourceType\":\"AWS::S3::Bucket\","
                        + "\"awsRegion\":\"eu-west-1\","
                        + "\"configurationItemCaptureTime\":\"2026-11-17T17:01:00Z\"}")
                .build());
    AtomicInteger pageIndex = new AtomicInteger();
    List<String> requestedTokens = new ArrayList<>();
    AwsCollector collector =
        new AwsCollector(
            (region, roleArn, externalId) ->
                new AwsCollector.InventorySession() {
                  @Override
                  public SelectResourceConfigResponse select(String expression, String nextToken) {
                    requestedTokens.add(nextToken);
                    return pages.get(pageIndex.getAndIncrement());
                  }

                  @Override
                  public void close() {}
                },
            new ObjectMapper());

    ProviderCollector.Batch first =
        collector.collect(
            new ProviderCollector.Request(
                "connection-1", SOURCE_REF, ROLE_ARN, EXTERNAL_ID, null, 0, null, null));
    ProviderCollector.Batch second =
        collector.collect(
            new ProviderCollector.Request(
                "connection-1",
                SOURCE_REF,
                ROLE_ARN,
                EXTERNAL_ID,
                first.nextCheckpoint(),
                first.observations().getLast().sequence(),
                null,
                null));

    assertThat(requestedTokens).containsExactly(null, "page-2");
    assertThat(first.status()).isEqualTo(ProviderCollector.Batch.Status.COLLECTED);
    assertThat(first.nextCheckpoint()).isEqualTo("page-2");
    assertThat(first.observations())
        .extracting(ProviderCollector.Observation::sequence)
        .containsExactly(1L);
    assertThat(second.status()).isEqualTo(ProviderCollector.Batch.Status.COLLECTED);
    assertThat(second.nextCheckpoint()).isNull();
    assertThat(second.observations())
        .extracting(ProviderCollector.Observation::sequence)
        .containsExactly(2L);
    assertThat(second.observations().getFirst().id())
        .isNotEqualTo(first.observations().getFirst().id());
  }

  @Test
  void mapsAccessDenialAndThrottlingWithoutAdvancingTheCheckpoint() {
    AwsServiceException denied = mock(AwsServiceException.class);
    when(denied.statusCode()).thenReturn(403);
    when(denied.awsErrorDetails())
        .thenReturn(AwsErrorDetails.builder().errorCode("AccessDeniedException").build());
    AwsServiceException throttled = mock(AwsServiceException.class);
    when(throttled.statusCode()).thenReturn(429);
    AwsServiceException unavailable = mock(AwsServiceException.class);
    when(unavailable.statusCode()).thenReturn(503);

    assertThat(collectFailure(denied).status())
        .isEqualTo(ProviderCollector.Batch.Status.PERMISSION_DENIED);
    ProviderCollector.Batch rateLimited = collectFailure(throttled);
    assertThat(rateLimited.status()).isEqualTo(ProviderCollector.Batch.Status.RATE_LIMITED);
    assertThat(rateLimited.nextCheckpoint()).isEqualTo("previous-page");
    assertThat(rateLimited.observations()).isEmpty();
    assertThat(collectFailure(unavailable).status())
        .isEqualTo(ProviderCollector.Batch.Status.SOURCE_UNAVAILABLE);
    assertThat(collectClientFailure().status())
        .isEqualTo(ProviderCollector.Batch.Status.SOURCE_UNAVAILABLE);
  }

  @Test
  void collectsBoundedSqsMetricsAsOneSequencedProviderObservation() {
    Instant from = Instant.parse("2026-11-17T17:00:00Z");
    Instant to = Instant.parse("2026-11-17T17:10:00Z");
    AtomicReference<String> requestedQueue = new AtomicReference<>();
    AtomicReference<List<MetricDataQuery>> requestedQueries = new AtomicReference<>();
    AtomicReference<Instant> requestedFrom = new AtomicReference<>();
    AtomicReference<Instant> requestedTo = new AtomicReference<>();
    AtomicReference<Integer> requestedPeriod = new AtomicReference<>();
    AwsCollector collector =
        new AwsCollector(
            (region, roleArn, externalId) ->
                new AwsCollector.InventorySession() {
                  @Override
                  public SelectResourceConfigResponse select(String expression, String nextToken) {
                    throw new AssertionError("Inventory request is not expected.");
                  }

                  @Override
                  public GetMetricDataResponse getMetricData(
                      List<MetricDataQuery> queries, Instant start, Instant end, String nextToken) {
                    requestedQueries.set(queries);
                    requestedQueue.set(
                        queries.getFirst().metricStat().metric().dimensions().getFirst().value());
                    requestedFrom.set(start);
                    requestedTo.set(end);
                    requestedPeriod.set(queries.getFirst().metricStat().period());
                    return GetMetricDataResponse.builder()
                        .metricDataResults(
                            metricResult(
                                "oldest",
                                "ApproximateAgeOfOldestMessage",
                                List.of(
                                    Instant.parse("2026-11-17T16:55:00Z"),
                                    Instant.parse("2026-11-17T17:05:00Z"),
                                    Instant.parse("2026-11-17T17:10:00Z")),
                                List.of(99.0, 12.5, 50.0)),
                            metricResult(
                                "visible",
                                "ApproximateNumberOfMessagesVisible",
                                List.of(
                                    Instant.parse("2026-11-17T17:09:00Z"),
                                    Instant.parse("2026-11-17T17:10:00Z")),
                                List.of(7.0, 80.0)))
                        .build();
                  }

                  @Override
                  public void close() {}
                },
            new ObjectMapper());

    ProviderCollector.Batch batch =
        collector.collect(
            new ProviderCollector.Request(
                "connection-1",
                SQS_METRIC_SOURCE_REF,
                ROLE_ARN,
                EXTERNAL_ID,
                null,
                7,
                from,
                to));

    assertThat(requestedQueue).hasValue("orders-queue");
    assertThat(requestedQueries.get()).hasSize(2);
    assertThat(requestedFrom).hasValue(from);
    assertThat(requestedTo).hasValue(to);
    assertThat(requestedPeriod).hasValue(300);
    assertThat(batch.status()).isEqualTo(ProviderCollector.Batch.Status.COLLECTED);
    assertThat(batch.nextCheckpoint()).isNull();
    assertThat(batch.observations()).hasSize(1);
    ProviderCollector.Observation observation = batch.observations().getFirst();
    assertThat(observation.sequence()).isEqualTo(8);
    assertThat(observation.observedAt()).isEqualTo(Instant.parse("2026-11-17T17:09:00Z"));
    assertThat(observation.kind()).isEqualTo("aws.cloudwatch.metrics");
    assertThat(observation.payload())
        .contains("ApproximateAgeOfOldestMessage", "ApproximateNumberOfMessagesVisible")
        .contains("\"Timestamps\":[\"2026-11-17T17:05:00Z\"]")
        .contains("\"Values\":[12.5]")
        .contains("\"Values\":[7.0]")
        .doesNotContain("2026-11-17T16:55:00Z", "2026-11-17T17:10:00Z", "99.0", "80.0");
    assertThat(observation.id()).startsWith("aws-cloudwatch-");
  }

  @Test
  void collectsAnExplicitCloudWatchMetricWithItsDimensionsAndStatistic() {
    Instant from = Instant.parse("2026-11-17T17:00:00Z");
    Instant to = Instant.parse("2026-11-17T17:10:00Z");
    AtomicReference<List<MetricDataQuery>> requestedQueries = new AtomicReference<>();
    AwsCollector collector =
        new AwsCollector(
            (region, roleArn, externalId) ->
                new AwsCollector.InventorySession() {
                  @Override
                  public SelectResourceConfigResponse select(String expression, String nextToken) {
                    throw new AssertionError("Inventory request is not expected.");
                  }

                  @Override
                  public GetMetricDataResponse getMetricData(
                      List<MetricDataQuery> queries,
                      Instant start,
                      Instant end,
                      String nextToken) {
                    requestedQueries.set(queries);
                    assertThat(start).isEqualTo(from);
                    assertThat(end).isEqualTo(to);
                    return GetMetricDataResponse.builder()
                        .metricDataResults(
                            metricResult(
                                "metric",
                                "CPUUtilization",
                                List.of(from.plusSeconds(60), to),
                                List.of(24.5, 90.0)))
                        .build();
                  }

                  @Override
                  public void close() {}
                },
            new ObjectMapper());
    String selector =
        """
        {"namespace":"AWS/EC2","metricName":"CPUUtilization","statistic":"Average","dimensions":{"InstanceId":"i-0123456789abcdef0"}}
        """.strip();

    ProviderCollector.Batch batch =
        collector.collect(
            new ProviderCollector.Request(
                "connection-1",
                metricSourceRef(selector),
                ROLE_ARN,
                EXTERNAL_ID,
                null,
                7,
                from,
                to));

    MetricDataQuery query = requestedQueries.get().getFirst();
    assertThat(requestedQueries.get()).hasSize(1);
    assertThat(query.id()).isEqualTo("metric");
    assertThat(query.metricStat().metric().namespace()).isEqualTo("AWS/EC2");
    assertThat(query.metricStat().metric().metricName()).isEqualTo("CPUUtilization");
    assertThat(query.metricStat().stat()).isEqualTo("Average");
    assertThat(query.metricStat().period()).isEqualTo(300);
    assertThat(query.metricStat().metric().dimensions())
        .extracting(dimension -> dimension.name() + "=" + dimension.value())
        .containsExactly("InstanceId=i-0123456789abcdef0");
    assertThat(batch.status()).isEqualTo(ProviderCollector.Batch.Status.COLLECTED);
    assertThat(batch.observations()).hasSize(1);
    assertThat(batch.observations().getFirst().observedAt()).isEqualTo(from.plusSeconds(60));
    assertThat(batch.observations().getFirst().payload())
        .contains("\"Namespace\":\"AWS/EC2\"")
        .contains("\"MetricName\":\"CPUUtilization\"")
        .contains("\"Statistic\":\"Average\"")
        .contains("\"InstanceId\":\"i-0123456789abcdef0\"")
        .doesNotContain(to.toString());
  }

  @Test
  void rejectsInvalidCloudWatchMetricSelectorsBeforeOpeningAwsSession() {
    AtomicInteger opened = new AtomicInteger();
    AwsCollector collector =
        new AwsCollector(
            (region, roleArn, externalId) -> {
              opened.incrementAndGet();
              throw new AssertionError("Invalid source must not open an AWS session.");
            },
            new ObjectMapper());
    String selector =
        "{\"namespace\":\"AWS/EC2\",\"metricName\":\"CPUUtilization\","
            + "\"statistic\":\"Percentile95\",\"dimensions\":{}}";

    assertThatThrownBy(
            () ->
                collector.collect(
                    new ProviderCollector.Request(
                        "connection-1",
                        metricSourceRef(selector),
                        ROLE_ARN,
                        EXTERNAL_ID,
                        null,
                        0,
                        null,
                        Instant.parse("2026-11-17T17:10:00Z"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid metric selector");
    assertThat(opened).hasValue(0);
  }

  @Test
  void doesNotAcceptPartialConfiguredCloudWatchMetricResults() {
    Instant from = Instant.parse("2026-11-17T17:00:00Z");
    Instant to = Instant.parse("2026-11-17T17:05:00Z");
    AwsCollector collector =
        new AwsCollector(
            (region, roleArn, externalId) ->
                new AwsCollector.InventorySession() {
                  @Override
                  public SelectResourceConfigResponse select(String expression, String nextToken) {
                    throw new AssertionError("Inventory request is not expected.");
                  }

                  @Override
                  public GetMetricDataResponse getMetricData(
                      List<MetricDataQuery> queries,
                      Instant start,
                      Instant end,
                      String nextToken) {
                    return GetMetricDataResponse.builder()
                        .metricDataResults(
                            MetricDataResult.builder()
                                .id("metric")
                                .label("CPUUtilization")
                                .statusCode(StatusCode.PARTIAL_DATA)
                                .timestamps(from.plusSeconds(60))
                                .values(24.5)
                                .build())
                        .build();
                  }

                  @Override
                  public void close() {}
                },
            new ObjectMapper());

    ProviderCollector.Batch batch =
        collector.collect(
            new ProviderCollector.Request(
                "connection-1",
                metricSourceRef(
                    "{\"namespace\":\"AWS/EC2\",\"metricName\":\"CPUUtilization\","
                        + "\"statistic\":\"Average\",\"dimensions\":{}}"),
                ROLE_ARN,
                EXTERNAL_ID,
                null,
                0,
                from,
                to));

    assertThat(batch.status()).isEqualTo(ProviderCollector.Batch.Status.SOURCE_UNAVAILABLE);
    assertThat(batch.observations()).isEmpty();
    assertThat(batch.nextCheckpoint()).isNull();
  }

  @Test
  void rejectsCloudWatchPayloadsLargerThanTheS03Limit() {
    Instant from = Instant.parse("2026-11-17T00:00:00Z");
    Instant to = from.plus(Duration.ofHours(24));
    List<Instant> timestamps =
        java.util.stream.IntStream.range(0, 288)
            .mapToObj(index -> from.plusSeconds(index * 300L))
            .toList();
    List<Double> values = java.util.Collections.nCopies(timestamps.size(), Double.MAX_VALUE);
    AwsCollector collector =
        new AwsCollector(
            (region, roleArn, externalId) ->
                new AwsCollector.InventorySession() {
                  @Override
                  public SelectResourceConfigResponse select(String expression, String nextToken) {
                    throw new AssertionError("Inventory request is not expected.");
                  }

                  @Override
                  public GetMetricDataResponse getMetricData(
                      List<MetricDataQuery> queries,
                      Instant start,
                      Instant end,
                      String nextToken) {
                    return GetMetricDataResponse.builder()
                        .metricDataResults(
                            metricResult("oldest", "ApproximateAgeOfOldestMessage", timestamps, values),
                            metricResult(
                                "visible", "ApproximateNumberOfMessagesVisible", timestamps, values))
                        .build();
                  }

                  @Override
                  public void close() {}
                },
            new ObjectMapper());

    ProviderCollector.Batch batch =
        collector.collect(
            new ProviderCollector.Request(
                "connection-1",
                SQS_METRIC_SOURCE_REF,
                ROLE_ARN,
                EXTERNAL_ID,
                null,
                0,
                from,
                to));

    assertThat(batch.status()).isEqualTo(ProviderCollector.Batch.Status.SOURCE_UNAVAILABLE);
    assertThat(batch.observations()).isEmpty();
    assertThat(batch.nextCheckpoint()).isNull();
    assertThat(batch.detail()).contains("S03 payload limit");
  }

  @Test
  void boundsSqsMetricWindowAndDoesNotAdvanceUnexpectedContinuation() {
    AtomicInteger opened = new AtomicInteger();
    AwsCollector collector =
        new AwsCollector(
            (region, roleArn, externalId) -> {
              opened.incrementAndGet();
              return new AwsCollector.InventorySession() {
                @Override
                public SelectResourceConfigResponse select(String expression, String nextToken) {
                  throw new AssertionError("Inventory request is not expected.");
                }

                @Override
                public GetMetricDataResponse getMetricData(
                    List<MetricDataQuery> queries,
                    Instant start,
                    Instant end,
                    String nextToken) {
                  return GetMetricDataResponse.builder().nextToken("unexpected").build();
                }

                @Override
                public void close() {}
              };
            },
            new ObjectMapper());
    Instant to = Instant.parse("2026-11-17T17:10:00Z");

    ProviderCollector.Batch tooWide =
        collector.collect(
            new ProviderCollector.Request(
                "connection-1",
                SQS_METRIC_SOURCE_REF,
                ROLE_ARN,
                EXTERNAL_ID,
                null,
                0,
                to.minus(Duration.ofHours(25)),
                to));
    ProviderCollector.Batch continuation =
        collector.collect(
            new ProviderCollector.Request(
                "connection-1",
                SQS_METRIC_SOURCE_REF,
                ROLE_ARN,
                EXTERNAL_ID,
                null,
                0,
                to.minus(Duration.ofMinutes(5)),
                to));

    assertThat(tooWide.status()).isEqualTo(ProviderCollector.Batch.Status.SOURCE_UNAVAILABLE);
    assertThat(tooWide.observations()).isEmpty();
    assertThat(continuation.status()).isEqualTo(ProviderCollector.Batch.Status.SOURCE_UNAVAILABLE);
    assertThat(continuation.nextCheckpoint()).isNull();
    assertThat(continuation.observations()).isEmpty();
    assertThat(opened).hasValue(1);
  }

  @Test
  void requestsOnlyTheDocumentedSqsMetricsForTheSelectedQueue() {
    List<MetricDataQuery> queries = AwsCollector.sqsMetricQueries("orders-queue", 300);

    assertThat(queries)
        .extracting(MetricDataQuery::id)
        .containsExactly("oldest", "visible");
    assertThat(queries)
        .extracting(query -> query.metricStat().metric().namespace())
        .containsOnly("AWS/SQS");
    assertThat(queries)
        .extracting(query -> query.metricStat().metric().metricName())
        .containsExactly(
            "ApproximateAgeOfOldestMessage", "ApproximateNumberOfMessagesVisible");
    assertThat(queries)
        .extracting(query -> query.metricStat().metric().dimensions().getFirst().value())
        .containsOnly("orders-queue");
    assertThat(queries)
        .extracting(query -> query.metricStat().period())
        .containsOnly(300);
    assertThat(queries)
        .extracting(query -> query.metricStat().stat())
        .containsExactly("Maximum", "Average");
  }

  private static MetricDataResult metricResult(
      String id, String label, List<Instant> timestamps, List<Double> values) {
    return MetricDataResult.builder()
        .id(id)
        .label(label)
        .statusCode(StatusCode.COMPLETE)
        .timestamps(timestamps)
        .values(values)
        .build();
  }

  private static String metricSourceRef(String selector) {
    return "aws/eu-west-1/cloudwatch/metric/"
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(selector.getBytes(StandardCharsets.UTF_8));
  }

  private ProviderCollector.Batch collectFailure(AwsServiceException error) {
    AwsCollector collector =
        new AwsCollector(
            (region, roleArn, externalId) ->
                new AwsCollector.InventorySession() {
                  @Override
                  public SelectResourceConfigResponse select(String expression, String nextToken) {
                    throw error;
                  }

                  @Override
                  public void close() {}
                },
            new ObjectMapper());
    return collector.collect(
        new ProviderCollector.Request(
            "connection-1", SOURCE_REF, ROLE_ARN, EXTERNAL_ID, "previous-page", 7, null, null));
  }

  private ProviderCollector.Batch collectClientFailure() {
    AwsCollector collector =
        new AwsCollector(
            (region, roleArn, externalId) ->
                new AwsCollector.InventorySession() {
                  @Override
                  public SelectResourceConfigResponse select(String expression, String nextToken) {
                    throw SdkClientException.builder().message("unavailable").build();
                  }

                  @Override
                  public void close() {}
                },
            new ObjectMapper());
    return collector.collect(
        new ProviderCollector.Request(
            "connection-1", SOURCE_REF, ROLE_ARN, EXTERNAL_ID, "previous-page", 7, null, null));
  }
}
