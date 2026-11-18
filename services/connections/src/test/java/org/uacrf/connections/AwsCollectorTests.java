package org.uacrf.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.config.model.SelectResourceConfigResponse;
import tools.jackson.databind.ObjectMapper;

class AwsCollectorTests {
  private static final String SOURCE_REF = "aws/eu-west-1/config/inventory";
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
