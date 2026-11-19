package org.uacrf.connections;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.uacrf.connections.ProviderCollector.Batch;
import org.uacrf.connections.ProviderCollector.Observation;
import org.uacrf.connections.ProviderCollector.Request;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;
import software.amazon.awssdk.services.config.ConfigClient;
import software.amazon.awssdk.services.config.model.SelectResourceConfigRequest;
import software.amazon.awssdk.services.config.model.SelectResourceConfigResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class AwsCollector implements ProviderCollector {
  private static final Pattern SOURCE_REF =
      Pattern.compile("^aws/([a-z]{2}(?:-[a-z0-9]+)+-[0-9]+)/config/inventory$");
  private static final Pattern SQS_METRIC_SOURCE_REF =
      Pattern.compile(
          "^aws/([a-z]{2}(?:-[a-z0-9]+)+-[0-9]+)/cloudwatch/sqs/([A-Za-z0-9_.-]{1,80})$");
  private static final Pattern CLOUDWATCH_METRIC_SOURCE_REF =
      Pattern.compile(
          "^aws/([a-z]{2}(?:-[a-z0-9]+)+-[0-9]+)/cloudwatch/metric/([A-Za-z0-9_-]{1,450})$");
  private static final String CONFIG_QUERY =
      "SELECT resourceId, resourceType, awsRegion, configurationItemCaptureTime";
  private static final int PAGE_SIZE = 100;
  private static final String KIND = "aws.config.resources";
  private static final String METRIC_KIND = "aws.cloudwatch.metrics";
  private static final Duration MAX_METRIC_WINDOW = Duration.ofHours(24);
  private static final int METRIC_PERIOD_SECONDS = 300;
  private static final int MAX_METRIC_PAYLOAD_CHARS = 16_384;
  private static final int MAX_METRIC_SELECTOR_BYTES = 337;
  private static final int MAX_METRIC_DIMENSIONS = 30;
  private static final Set<String> CLOUDWATCH_STATISTICS =
      Set.of("Average", "Sum", "Minimum", "Maximum", "SampleCount");

  private final InventorySessionFactory sessions;
  private final ObjectMapper objectMapper;

  @Autowired
  AwsCollector(ObjectMapper objectMapper) {
    this(AwsCollector::openSession, objectMapper);
  }

  AwsCollector(InventorySessionFactory sessions, ObjectMapper objectMapper) {
    this.sessions = sessions;
    this.objectMapper = objectMapper;
  }

  @Override
  public ConnectionsRequests.Provider provider() {
    return ConnectionsRequests.Provider.AWS;
  }

  @Override
  public Batch collect(Request request) {
    Matcher metricSource = SQS_METRIC_SOURCE_REF.matcher(request.sourceRef());
    if (metricSource.matches()) {
      return collectSqsMetrics(request, metricSource);
    }
    Matcher configuredMetricSource = CLOUDWATCH_METRIC_SOURCE_REF.matcher(request.sourceRef());
    if (configuredMetricSource.matches()) {
      return collectConfiguredMetric(request, configuredMetricSource);
    }
    Matcher source = SOURCE_REF.matcher(request.sourceRef());
    if (!source.matches()) {
      throw new IllegalArgumentException(
          "AWS source_ref must use aws/<region>/config/inventory or a supported "
              + "CloudWatch metric source.");
    }
    if (request.readOnlyIdentityRef() == null
        || request.readOnlyExternalId() == null
        || request.readOnlyExternalId().isBlank()) {
      throw new IllegalArgumentException("AWS role ARN and ExternalId are required.");
    }
    String region = source.group(1);
    try (InventorySession session =
        sessions.open(region, request.readOnlyIdentityRef(), request.readOnlyExternalId())) {
      SelectResourceConfigResponse response = session.select(CONFIG_QUERY, request.checkpoint());
      List<Observation> observations = new ArrayList<>();
      long sequence = request.lastSequence();
      for (String result : response.results()) {
        JsonNode item = objectMapper.readTree(result);
        JsonNode capturedAt = item.get("configurationItemCaptureTime");
        if (capturedAt == null || !capturedAt.isString()) {
          throw new IllegalStateException(
              "AWS Config inventory result is missing configurationItemCaptureTime.");
        }
        String payload = objectMapper.writeValueAsString(item);
        sequence = Math.addExact(sequence, 1);
        observations.add(
            new Observation(
                ProviderObservationIds.create("aws-config-", request.sourceRef(), sequence),
                sequence,
                Instant.parse(capturedAt.stringValue()),
                KIND,
                payload));
      }
      return new Batch(
          Batch.Status.COLLECTED, observations, response.nextToken(), null);
    } catch (AwsServiceException error) {
      int statusCode = error.statusCode();
      if (error.isThrottlingException() || statusCode == 429) {
        return new Batch(Batch.Status.RATE_LIMITED, List.of(), request.checkpoint(), null);
      }
      String errorCode =
          error.awsErrorDetails() == null ? "" : error.awsErrorDetails().errorCode();
      if (errorCode != null && errorCode.toLowerCase(java.util.Locale.ROOT).contains("accessdenied")) {
        return new Batch(Batch.Status.PERMISSION_DENIED, List.of(), request.checkpoint(), null);
      }
      if (statusCode >= 500) {
        return new Batch(Batch.Status.SOURCE_UNAVAILABLE, List.of(), request.checkpoint(), null);
      }
      throw error;
    } catch (SdkClientException error) {
      return new Batch(Batch.Status.SOURCE_UNAVAILABLE, List.of(), request.checkpoint(), null);
    } catch (JacksonException error) {
      throw new IllegalStateException("AWS Config returned invalid inventory JSON.", error);
    }
  }

  private Batch collectSqsMetrics(Request request, Matcher source) {
    return collectCloudWatchMetrics(
        request,
        source.group(1),
        sqsMetricQueries(source.group(2), METRIC_PERIOD_SECONDS),
        Set.of("oldest", "visible"),
        null);
  }

  private Batch collectConfiguredMetric(Request request, Matcher source) {
    MetricSelector selector = decodeMetricSelector(source.group(2));
    Map<String, Object> metricConfiguration = new LinkedHashMap<>();
    metricConfiguration.put("Namespace", selector.namespace());
    metricConfiguration.put("MetricName", selector.metricName());
    metricConfiguration.put("Statistic", selector.statistic());
    metricConfiguration.put("Dimensions", selector.dimensions());
    return collectCloudWatchMetrics(
        request,
        source.group(1),
        List.of(
            metricQuery(
                "metric",
                selector.namespace(),
                selector.metricName(),
                selector.statistic(),
                selector.dimensions(),
                METRIC_PERIOD_SECONDS)),
        Set.of("metric"),
        metricConfiguration);
  }

  private Batch collectCloudWatchMetrics(
      Request request,
      String region,
      List<MetricDataQuery> queries,
      Set<String> expectedResultIds,
      Map<String, Object> metricConfiguration) {
    if (request.readOnlyIdentityRef() == null
        || request.readOnlyExternalId() == null
        || request.readOnlyExternalId().isBlank()) {
      throw new IllegalArgumentException("AWS role ARN and ExternalId are required.");
    }
    if (request.to() == null) {
      throw new IllegalArgumentException("AWS CloudWatch collection end time is required.");
    }
    if (request.checkpoint() != null) {
      return new Batch(
          Batch.Status.SOURCE_UNAVAILABLE,
          List.of(),
          request.checkpoint(),
          "Unexpected CloudWatch continuation state; the checkpoint was not advanced.");
    }
    Instant end = request.to();
    Instant start = request.from() == null ? end.minusSeconds(METRIC_PERIOD_SECONDS) : request.from();
    if (!end.isAfter(start) || Duration.between(start, end).compareTo(MAX_METRIC_WINDOW) > 0) {
      return new Batch(
          Batch.Status.SOURCE_UNAVAILABLE,
          List.of(),
          request.checkpoint(),
          "CloudWatch collection window must be positive and no longer than 24 hours.");
    }

    try (InventorySession session =
        sessions.open(region, request.readOnlyIdentityRef(), request.readOnlyExternalId())) {
      GetMetricDataResponse response =
          session.getMetricData(
              queries, start, end, request.checkpoint());
      if (response.nextToken() != null) {
        return new Batch(
            Batch.Status.SOURCE_UNAVAILABLE,
            List.of(),
            request.checkpoint(),
            "CloudWatch returned an unexpected continuation token; the checkpoint was not advanced.");
      }
      List<MetricDataResult> results = response.metricDataResults();
      Set<String> resultIds =
          results.stream().map(MetricDataResult::id).collect(java.util.stream.Collectors.toSet());
      boolean invalidMetricData =
          results.stream()
              .anyMatch(
                  result ->
                      !"Complete".equals(result.statusCodeAsString())
                          || result.timestamps().size() != result.values().size());
      if (results.size() != expectedResultIds.size()
          || !resultIds.equals(expectedResultIds)
          || invalidMetricData) {
        return new Batch(
            Batch.Status.SOURCE_UNAVAILABLE,
            List.of(),
            request.checkpoint(),
            "CloudWatch returned incomplete or invalid metric data.");
      }
      List<Map<String, Object>> payloadResults = new ArrayList<>();
      Instant observedAt = null;
      for (MetricDataResult result : results) {
        List<String> timestamps = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        // Keep CloudWatch's half-open query interval to avoid replaying adjacent windows.
        for (int index = 0; index < result.timestamps().size(); index++) {
          Instant timestamp = result.timestamps().get(index);
          if (!timestamp.isBefore(start) && timestamp.isBefore(end)) {
            timestamps.add(timestamp.toString());
            values.add(result.values().get(index));
            if (observedAt == null || timestamp.isAfter(observedAt)) {
              observedAt = timestamp;
            }
          }
        }
        Map<String, Object> payloadResult = new LinkedHashMap<>();
        payloadResult.put("Id", result.id());
        payloadResult.put("Label", result.label());
        payloadResult.put("StatusCode", result.statusCodeAsString());
        payloadResult.put("Timestamps", timestamps);
        payloadResult.put("Values", values);
        payloadResults.add(payloadResult);
      }
      if (observedAt == null) {
        observedAt = end;
      }
      Map<String, Object> payloadBody = new LinkedHashMap<>();
      if (metricConfiguration != null) {
        payloadBody.put("MetricConfiguration", metricConfiguration);
      }
      payloadBody.put("MetricDataResults", payloadResults);
      payloadBody.put("Messages", List.of());
      String payload = objectMapper.writeValueAsString(payloadBody);
      if (payload.length() > MAX_METRIC_PAYLOAD_CHARS) {
        return new Batch(
            Batch.Status.SOURCE_UNAVAILABLE,
            List.of(),
            request.checkpoint(),
            "CloudWatch observation exceeded the S03 payload limit; the checkpoint was not advanced.");
      }
      long sequence = Math.addExact(request.lastSequence(), 1);
      Observation observation =
          new Observation(
              ProviderObservationIds.create("aws-cloudwatch-", request.sourceRef(), sequence),
              sequence,
              observedAt,
              METRIC_KIND,
              payload);
      return new Batch(Batch.Status.COLLECTED, List.of(observation), null, null);
    } catch (AwsServiceException error) {
      int statusCode = error.statusCode();
      if (error.isThrottlingException() || statusCode == 429) {
        return new Batch(Batch.Status.RATE_LIMITED, List.of(), request.checkpoint(), null);
      }
      String errorCode =
          error.awsErrorDetails() == null ? "" : error.awsErrorDetails().errorCode();
      if (errorCode != null
          && errorCode.toLowerCase(java.util.Locale.ROOT).contains("accessdenied")) {
        return new Batch(Batch.Status.PERMISSION_DENIED, List.of(), request.checkpoint(), null);
      }
      if (statusCode >= 500) {
        return new Batch(Batch.Status.SOURCE_UNAVAILABLE, List.of(), request.checkpoint(), null);
      }
      throw error;
    } catch (SdkClientException error) {
      return new Batch(Batch.Status.SOURCE_UNAVAILABLE, List.of(), request.checkpoint(), null);
    } catch (JacksonException error) {
      throw new IllegalStateException("CloudWatch returned invalid metric data.", error);
    }
  }

  private MetricSelector decodeMetricSelector(String encodedSelector) {
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(encodedSelector);
    } catch (IllegalArgumentException error) {
      throw invalidMetricSelector(error);
    }
    if (decoded.length > MAX_METRIC_SELECTOR_BYTES
        || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encodedSelector)) {
      throw invalidMetricSelector(null);
    }

    JsonNode selector;
    try {
      selector = objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));
    } catch (JacksonException error) {
      throw invalidMetricSelector(error);
    }
    JsonNode dimensionsNode = selector == null ? null : selector.get("dimensions");
    if (selector == null
        || !selector.isObject()
        || selector.size() != 4
        || !isSelectorText(selector.get("namespace"), 255)
        || !isSelectorText(selector.get("metricName"), 255)
        || !isSelectorText(selector.get("statistic"), 32)
        || dimensionsNode == null
        || !dimensionsNode.isObject()
        || dimensionsNode.size() > MAX_METRIC_DIMENSIONS) {
      throw invalidMetricSelector(null);
    }

    String statistic = selector.get("statistic").stringValue();
    if (!CLOUDWATCH_STATISTICS.contains(statistic)) {
      throw invalidMetricSelector(null);
    }
    Map<String, String> dimensions = new TreeMap<>();
    for (var entry : dimensionsNode.properties()) {
      JsonNode value = entry.getValue();
      if (!isSelectorText(entry.getKey(), 255) || !isSelectorText(value, 1024)) {
        throw invalidMetricSelector(null);
      }
      dimensions.put(entry.getKey(), value.stringValue());
    }
    return new MetricSelector(
        selector.get("namespace").stringValue(),
        selector.get("metricName").stringValue(),
        statistic,
        dimensions);
  }

  private static boolean isSelectorText(JsonNode value, int maxLength) {
    return value != null && value.isString() && isSelectorText(value.stringValue(), maxLength);
  }

  private static boolean isSelectorText(String text, int maxLength) {
    return !text.isBlank()
        && text.length() <= maxLength
        && text.chars().noneMatch(Character::isISOControl);
  }

  private static IllegalArgumentException invalidMetricSelector(Throwable cause) {
    return new IllegalArgumentException(
        "AWS CloudWatch source_ref has an invalid metric selector.", cause);
  }

  private static InventorySession openSession(String regionName, String roleArn, String externalId) {
    Region region = Region.of(regionName);
    StsClient sts = StsClient.builder().region(region).build();
    StsAssumeRoleCredentialsProvider credentials = null;
    ConfigClient config = null;
    try {
      credentials =
          StsAssumeRoleCredentialsProvider.builder()
              .stsClient(sts)
              .refreshRequest(
                  AssumeRoleRequest.builder()
                      .roleArn(roleArn)
                      .roleSessionName("uacrf-s03-collection")
                      .externalId(externalId)
                      .durationSeconds(900)
                      .build())
              .build();
      config =
          ConfigClient.builder()
              .region(region)
              .credentialsProvider(credentials)
              .build();
      StsAssumeRoleCredentialsProvider assumedCredentials = credentials;
      ConfigClient configClient = config;
      java.util.concurrent.atomic.AtomicReference<CloudWatchClient> cloudWatch =
          new java.util.concurrent.atomic.AtomicReference<>();
      return new InventorySession() {
        @Override
        public SelectResourceConfigResponse select(String expression, String nextToken) {
          SelectResourceConfigRequest.Builder request =
              SelectResourceConfigRequest.builder().expression(expression).limit(PAGE_SIZE);
          if (nextToken != null) {
            request.nextToken(nextToken);
          }
          return configClient.selectResourceConfig(request.build());
        }

        @Override
        public GetMetricDataResponse getMetricData(
            List<MetricDataQuery> queries, Instant start, Instant end, String nextToken) {
          CloudWatchClient client =
              cloudWatch.updateAndGet(
                  existing ->
                      existing == null
                          ? CloudWatchClient.builder()
                              .region(region)
                              .credentialsProvider(assumedCredentials)
                              .build()
                          : existing);
          GetMetricDataRequest.Builder request =
              GetMetricDataRequest.builder()
              .metricDataQueries(queries)
                  .startTime(start)
                  .endTime(end)
                  .maxDatapoints(100_800);
          if (nextToken != null) {
            request.nextToken(nextToken);
          }
          return client.getMetricData(request.build());
        }

        @Override
        public void close() {
          CloudWatchClient cloudWatchClient = cloudWatch.get();
          if (cloudWatchClient != null) {
            cloudWatchClient.close();
          }
          configClient.close();
          assumedCredentials.close();
          sts.close();
        }
      };
    } catch (RuntimeException error) {
      if (config != null) {
        config.close();
      }
      if (credentials != null) {
        credentials.close();
      }
      sts.close();
      throw error;
    }
  }

  static List<MetricDataQuery> sqsMetricQueries(String queueName, int periodSeconds) {
    return List.of(
        metricQuery(
            "oldest",
            "AWS/SQS",
            "ApproximateAgeOfOldestMessage",
            "Maximum",
            Map.of("QueueName", queueName),
            periodSeconds),
        metricQuery(
            "visible",
            "AWS/SQS",
            "ApproximateNumberOfMessagesVisible",
            "Average",
            Map.of("QueueName", queueName),
            periodSeconds));
  }

  private static MetricDataQuery metricQuery(
      String id,
      String namespace,
      String metricName,
      String statistic,
      Map<String, String> dimensions,
      int periodSeconds) {
    Metric.Builder metric = Metric.builder().namespace(namespace).metricName(metricName);
    if (!dimensions.isEmpty()) {
      metric.dimensions(
          dimensions.entrySet().stream()
              .map(
                  dimension ->
                      Dimension.builder()
                          .name(dimension.getKey())
                          .value(dimension.getValue())
                          .build())
              .toList());
    }
    return MetricDataQuery.builder()
        .id(id)
        .label(metricName)
        .metricStat(
            MetricStat.builder()
                .metric(metric.build())
                .period(periodSeconds)
                .stat(statistic)
                .build())
        .returnData(true)
        .build();
  }

  interface InventorySessionFactory {
    InventorySession open(String region, String roleArn, String externalId);
  }

  interface InventorySession extends AutoCloseable {
    SelectResourceConfigResponse select(String expression, String nextToken);

    default GetMetricDataResponse getMetricData(
        List<MetricDataQuery> queries, Instant start, Instant end, String nextToken) {
      throw new UnsupportedOperationException(
          "CloudWatch metrics are not available in this session.");
    }

    @Override
    void close();
  }

  private record MetricSelector(
      String namespace, String metricName, String statistic, Map<String, String> dimensions) {}
}
