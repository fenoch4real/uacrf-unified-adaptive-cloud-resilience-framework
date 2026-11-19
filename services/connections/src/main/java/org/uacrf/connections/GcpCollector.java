package org.uacrf.connections;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.uacrf.connections.ProviderCollector.Batch;
import org.uacrf.connections.ProviderCollector.Observation;
import org.uacrf.connections.ProviderCollector.Request;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class GcpCollector implements ProviderCollector {
  private static final Pattern SOURCE_REF =
      Pattern.compile("^gcp/[a-z0-9]+(?:-[a-z0-9]+)*/assetinventory/inventory$");
  private static final Pattern METRIC_SOURCE_REF =
      Pattern.compile("^gcp/monitoring/([A-Za-z0-9_-]{1,342})/([A-Za-z0-9_.-]{1,128})$");
  private static final Pattern PROJECT_ID =
      Pattern.compile("^[a-z][a-z0-9-]{4,28}[a-z0-9]$");
  private static final Pattern METRIC_TYPE =
      Pattern.compile("^[A-Za-z0-9._/-]{1,256}$");
  private static final Pattern RESOURCE_TYPE =
      Pattern.compile("^[A-Za-z0-9_.-]{1,128}$");
  private static final String ASSET_INVENTORY_URL = "https://cloudasset.googleapis.com/v1/projects/";
  private static final String MONITORING_URL =
      "https://monitoring.googleapis.com/v3/projects/";
  private static final String CLOUD_PLATFORM_SCOPE =
      "https://www.googleapis.com/auth/cloud-platform";
  private static final int PAGE_SIZE = 100;
  private static final int MAX_OBSERVATION_PAYLOAD_CHARS = 16_384;
  private static final String KIND = "gcp.assetinventory.assets";
  private static final String METRIC_KIND = "gcp.monitoring.timeseries";
  private static final Duration MAX_METRIC_WINDOW = Duration.ofHours(24);
  private static final int DEFAULT_METRIC_WINDOW_SECONDS = 300;

  private final InventorySessionFactory sessions;
  private final ObjectMapper objectMapper;

  @Autowired
  GcpCollector(ObjectMapper objectMapper) {
    this(() -> openSession(), objectMapper);
  }

  GcpCollector(InventorySessionFactory sessions, ObjectMapper objectMapper) {
    this.sessions = sessions;
    this.objectMapper = objectMapper;
  }

  @Override
  public ConnectionsRequests.Provider provider() {
    return ConnectionsRequests.Provider.GCP;
  }

  @Override
  public Batch collect(Request request) {
    Matcher inventorySource =
        request.sourceRef() == null ? null : SOURCE_REF.matcher(request.sourceRef());
    Matcher metricSource =
        request.sourceRef() == null ? null : METRIC_SOURCE_REF.matcher(request.sourceRef());
    boolean inventory = inventorySource != null && inventorySource.matches();
    boolean metrics = metricSource != null && metricSource.matches();
    if (!inventory && !metrics) {
      throw new IllegalArgumentException(
          "GCP source_ref must identify Asset Inventory or a scoped Monitoring metric.");
    }
    if (request.readOnlyIdentityRef() == null
        || !PROJECT_ID.matcher(request.readOnlyIdentityRef()).matches()) {
      throw new IllegalArgumentException(
          "GCP read_only_identity_ref must be a project ID.");
    }
    if (metrics) {
      return collectMetrics(request, metricSource);
    }
    if (request.to() == null) {
      throw new IllegalArgumentException("GCP inventory collection time is required.");
    }

    try (InventorySession session = sessions.open()) {
      QueryResponse response =
          session.listAssets(request.readOnlyIdentityRef(), request.checkpoint());
      if (response.statusCode() == 401 || response.statusCode() == 403) {
        return failed(
            Batch.Status.PERMISSION_DENIED,
            request.checkpoint(),
            "Google Cloud Asset Inventory denied the authenticated read-only request.");
      }
      if (response.statusCode() == 429) {
        return new Batch(
            Batch.Status.RATE_LIMITED,
            List.of(),
            request.checkpoint(),
            "Google Cloud Asset Inventory rate limit reached.",
            ProviderRetryAt.fromHeaders(response.headers(), Instant.now()));
      }
      if (response.statusCode() == 408 || response.statusCode() >= 500) {
        return failed(
            Batch.Status.SOURCE_UNAVAILABLE,
            request.checkpoint(),
            "Google Cloud Asset Inventory is unavailable.");
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Google Cloud Asset Inventory returned HTTP " + response.statusCode() + ".");
      }

      JsonNode body = objectMapper.readTree(response.body());
      if (body == null || !body.isObject()) {
        throw new IllegalStateException(
            "Google Cloud Asset Inventory returned an invalid response.");
      }
      JsonNode assets = body.get("assets");
      if (assets == null || !assets.isArray()) {
        throw new IllegalStateException(
            "Google Cloud Asset Inventory response is missing its assets array.");
      }
      String nextCheckpoint = optionalString(body.get("nextPageToken"), "nextPageToken");

      List<Observation> observations = new ArrayList<>();
      long sequence = request.lastSequence();
      for (JsonNode asset : assets) {
        if (!asset.isObject()) {
          throw new IllegalStateException(
              "Google Cloud Asset Inventory returned a non-object asset.");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", requiredString(asset.get("name"), "name"));
        fields.put("assetType", requiredString(asset.get("assetType"), "assetType"));
        String updateTime = optionalString(asset.get("updateTime"), "updateTime");
        if (updateTime != null) {
          fields.put("updateTime", updateTime);
        }
        String payload = objectMapper.writeValueAsString(fields);
        sequence = Math.addExact(sequence, 1);
        observations.add(
            new Observation(
                ProviderObservationIds.create(
                    "gcp-assetinventory-", request.sourceRef(), sequence),
                sequence,
                request.to(),
                KIND,
                payload));
      }
      return new Batch(Batch.Status.COLLECTED, observations, nextCheckpoint, null);
    } catch (GoogleAuthenticationException error) {
      return failed(Batch.Status.PERMISSION_DENIED, request.checkpoint(), error.getMessage());
    } catch (IOException error) {
      return failed(
          Batch.Status.SOURCE_UNAVAILABLE,
          request.checkpoint(),
          "Google Cloud Asset Inventory request failed.");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Google Cloud Asset Inventory request was interrupted.", error);
    } catch (JacksonException error) {
      throw new IllegalStateException(
          "Google Cloud Asset Inventory returned invalid inventory JSON.", error);
    }
  }

  private Batch collectMetrics(Request request, Matcher source) {
    if (request.to() == null) {
      throw new IllegalArgumentException("Google Cloud Monitoring collection time is required.");
    }

    String metricType = decodeMetricType(source.group(1));
    String resourceType = source.group(2);
    if (!RESOURCE_TYPE.matcher(resourceType).matches()) {
      throw new IllegalArgumentException("Google Cloud Monitoring source_ref has an invalid resource type.");
    }
    Instant from;
    Instant to;
    String pageToken;
    if (request.checkpoint() == null) {
      to = request.to();
      from =
          request.from() == null
              ? to.minusSeconds(DEFAULT_METRIC_WINDOW_SECONDS)
              : request.from();
      pageToken = null;
    } else {
      MetricCursor cursor = decodeMetricCursor(request.checkpoint());
      from = cursor.from();
      to = cursor.to();
      pageToken = cursor.pageToken();
    }
    Duration window = Duration.between(from, to);
    if (window.isNegative() || window.isZero() || window.compareTo(MAX_METRIC_WINDOW) > 0) {
      throw new IllegalArgumentException(
          "Google Cloud Monitoring window must be positive and no longer than 24 hours.");
    }

    try (InventorySession session = sessions.open()) {
      QueryResponse response =
          session.listTimeSeries(
              request.readOnlyIdentityRef(),
              metricType,
              resourceType,
              from,
              to,
              pageToken);
      if (response.statusCode() == 401 || response.statusCode() == 403) {
        return failed(
            Batch.Status.PERMISSION_DENIED,
            request.checkpoint(),
            "Google Cloud Monitoring denied the authenticated read-only request.");
      }
      if (response.statusCode() == 429) {
        return new Batch(
            Batch.Status.RATE_LIMITED,
            List.of(),
            request.checkpoint(),
            "Google Cloud Monitoring rate limit reached.",
            ProviderRetryAt.fromHeaders(response.headers(), Instant.now()));
      }
      if (response.statusCode() == 408 || response.statusCode() >= 500) {
        return failed(
            Batch.Status.SOURCE_UNAVAILABLE,
            request.checkpoint(),
            "Google Cloud Monitoring is unavailable.");
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Google Cloud Monitoring returned HTTP " + response.statusCode() + ".");
      }

      MetricsPage page =
          metricsPage(response.body(), metricType, resourceType, from, to, request);
      if (page.failureDetail() != null) {
        return failed(Batch.Status.SOURCE_UNAVAILABLE, request.checkpoint(), page.failureDetail());
      }
      String nextCheckpoint =
          page.nextPageToken() == null
              ? null
              : encodeMetricCursor(from, to, page.nextPageToken());
      return new Batch(Batch.Status.COLLECTED, page.observations(), nextCheckpoint, null);
    } catch (GoogleAuthenticationException error) {
      return failed(Batch.Status.PERMISSION_DENIED, request.checkpoint(), error.getMessage());
    } catch (IOException error) {
      return failed(
          Batch.Status.SOURCE_UNAVAILABLE,
          request.checkpoint(),
          "Google Cloud Monitoring request failed.");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Google Cloud Monitoring request was interrupted.", error);
    } catch (JacksonException error) {
      throw new IllegalStateException("Google Cloud Monitoring returned invalid time series JSON.", error);
    }
  }

  private MetricsPage metricsPage(
      String responseBody,
      String expectedMetricType,
      String expectedResourceType,
      Instant from,
      Instant to,
      Request request)
      throws JacksonException {
    JsonNode body = objectMapper.readTree(responseBody);
    if (body == null || !body.isObject()) {
      throw new IllegalStateException("Google Cloud Monitoring returned an invalid response.");
    }
    if (hasEntries(body, "executionErrors") || hasEntries(body, "unreachable")) {
      return new MetricsPage(
          List.of(),
          request.checkpoint(),
          "Google Cloud Monitoring returned partial data; the page token was not advanced.");
    }
    JsonNode timeSeries = body.get("timeSeries");
    if (timeSeries == null || timeSeries.isNull()) {
      timeSeries = objectMapper.createArrayNode();
    }
    if (!timeSeries.isArray()) {
      throw new IllegalStateException(
          "Google Cloud Monitoring response has an invalid timeSeries field.");
    }
    String nextPageToken = optionalString(body.get("nextPageToken"), "nextPageToken");
    List<Observation> observations = new ArrayList<>();
    long sequence = request.lastSequence();
    for (JsonNode series : timeSeries) {
      if (!series.isObject()) {
        throw new IllegalStateException("Google Cloud Monitoring returned an invalid time series.");
      }
      validateSeriesScope(series, expectedMetricType, expectedResourceType);
      JsonNode points = series.get("points");
      if (points == null || !points.isArray()) {
        throw new IllegalStateException("Google Cloud Monitoring time series is missing points.");
      }
      List<JsonNode> filteredPoints = new ArrayList<>();
      Instant observedAt = null;
      for (JsonNode point : points) {
        if (!point.isObject()) {
          throw new IllegalStateException("Google Cloud Monitoring returned an invalid metric point.");
        }
        JsonNode interval = point.get("interval");
        JsonNode endTime = interval == null ? null : interval.get("endTime");
        if (endTime == null || !endTime.isString()) {
          throw new IllegalStateException("Google Cloud Monitoring point is missing interval.endTime.");
        }
        Instant timestamp;
        try {
          timestamp = Instant.parse(endTime.stringValue());
        } catch (java.time.DateTimeException error) {
          throw new IllegalStateException(
              "Google Cloud Monitoring returned an invalid point timestamp.", error);
        }
        JsonNode value = point.get("value");
        if (value == null || !value.isObject() || value.size() == 0) {
          throw new IllegalStateException("Google Cloud Monitoring point is missing its value.");
        }
        if (!timestamp.isBefore(from) && timestamp.isBefore(to)) {
          filteredPoints.add(point);
          if (observedAt == null || timestamp.isAfter(observedAt)) {
            observedAt = timestamp;
          }
        }
      }
      if (filteredPoints.isEmpty()) {
        continue;
      }

      Map<String, Object> outputSeries = new LinkedHashMap<>();
      for (String field :
          List.of("metric", "resource", "metricKind", "valueType", "unit", "metadata")) {
        if (series.has(field)) {
          outputSeries.put(field, series.get(field));
        }
      }
      outputSeries.put("points", filteredPoints);
      Map<String, Object> output = new LinkedHashMap<>();
      output.put("timeSeries", List.of(outputSeries));
      if (body.has("unit")) {
        output.put("unit", body.get("unit"));
      }
      String payload = objectMapper.writeValueAsString(output);
      if (payload.length() > MAX_OBSERVATION_PAYLOAD_CHARS) {
        return new MetricsPage(
            List.of(),
            request.checkpoint(),
            "Google Cloud Monitoring observation exceeded the S03 payload limit; "
                + "the page token was not advanced.");
      }
      sequence = Math.addExact(sequence, 1);
      observations.add(
          new Observation(
              ProviderObservationIds.create("gcp-monitoring-", request.sourceRef(), sequence),
              sequence,
              observedAt,
              METRIC_KIND,
              payload));
    }
    return new MetricsPage(observations, nextPageToken, null);
  }

  private static boolean hasEntries(JsonNode body, String field) {
    JsonNode value = body.get(field);
    if (value == null) {
      return false;
    }
    if (!value.isArray()) {
      throw new IllegalStateException(
          "Google Cloud Monitoring response field " + field + " must be an array.");
    }
    return value.size() > 0;
  }

  private static void validateSeriesScope(
      JsonNode series, String expectedMetricType, String expectedResourceType) {
    JsonNode metric = series.get("metric");
    JsonNode metricType = metric == null ? null : metric.get("type");
    JsonNode resource = series.get("resource");
    JsonNode resourceType = resource == null ? null : resource.get("type");
    if (metricType == null
        || !metricType.isString()
        || !expectedMetricType.equals(metricType.stringValue())
        || resourceType == null
        || !resourceType.isString()
        || !expectedResourceType.equals(resourceType.stringValue())) {
      throw new IllegalStateException(
          "Google Cloud Monitoring returned a time series outside the requested metric scope.");
    }
  }

  private static String decodeMetricType(String encodedMetricType) {
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(encodedMetricType);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "Google Cloud Monitoring source_ref has an invalid metric type.", error);
    }
    String metricType = new String(decoded, StandardCharsets.UTF_8);
    if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encodedMetricType)
        || !METRIC_TYPE.matcher(metricType).matches()) {
      throw new IllegalArgumentException(
          "Google Cloud Monitoring source_ref has an invalid metric type.");
    }
    return metricType;
  }

  private static String encodeMetricCursor(Instant from, Instant to, String pageToken) {
    String cursor = from + "\n" + to + "\n" + pageToken;
    return "gcp-monitoring-v1/"
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(cursor.getBytes(StandardCharsets.UTF_8));
  }

  private static MetricCursor decodeMetricCursor(String checkpoint) {
    String prefix = "gcp-monitoring-v1/";
    if (!checkpoint.startsWith(prefix)) {
      throw new IllegalArgumentException("Google Cloud Monitoring checkpoint is invalid.");
    }
    String encoded = checkpoint.substring(prefix.length());
    String cursor;
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(encoded);
      if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)) {
        throw new IllegalArgumentException("Non-canonical cursor.");
      }
      cursor = new String(decoded, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("Google Cloud Monitoring checkpoint is invalid.", error);
    }
    int fromEnd = cursor.indexOf('\n');
    int toEnd = fromEnd < 0 ? -1 : cursor.indexOf('\n', fromEnd + 1);
    if (fromEnd < 0 || toEnd < 0 || toEnd == cursor.length() - 1) {
      throw new IllegalArgumentException("Google Cloud Monitoring checkpoint is invalid.");
    }
    try {
      return new MetricCursor(
          Instant.parse(cursor.substring(0, fromEnd)),
          Instant.parse(cursor.substring(fromEnd + 1, toEnd)),
          cursor.substring(toEnd + 1));
    } catch (java.time.DateTimeException error) {
      throw new IllegalArgumentException("Google Cloud Monitoring checkpoint is invalid.", error);
    }
  }

  static String metricSourceRef(String metricType, String resourceType) {
    return "gcp/monitoring/"
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(metricType.getBytes(StandardCharsets.UTF_8))
        + "/"
        + resourceType;
  }

  static URI monitoringUri(
      String projectId,
      String metricType,
      String resourceType,
      Instant from,
      Instant to,
      String pageToken) {
    String filter =
        "metric.type = \""
            + metricType
            + "\" AND resource.type = \""
            + resourceType
            + "\"";
    String url =
        MONITORING_URL
            + projectId
            + "/timeSeries?filter="
            + URLEncoder.encode(filter, StandardCharsets.UTF_8)
            + "&interval.startTime="
            + URLEncoder.encode(from.toString(), StandardCharsets.UTF_8)
            + "&interval.endTime="
            + URLEncoder.encode(to.toString(), StandardCharsets.UTF_8)
            + "&view=FULL&pageSize="
            + PAGE_SIZE;
    if (pageToken != null) {
      url += "&pageToken=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8);
    }
    return URI.create(url);
  }

  private record MetricsPage(
      List<Observation> observations, String nextPageToken, String failureDetail) {}

  private record MetricCursor(Instant from, Instant to, String pageToken) {}

  static URI requestUri(String projectId, String pageToken) {
    String url =
        ASSET_INVENTORY_URL
            + projectId
            + "/assets?pageSize="
            + PAGE_SIZE
            + "&contentType=RESOURCE";
    if (pageToken != null) {
      url += "&pageToken=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8);
    }
    return URI.create(url);
  }

  private static InventorySession openSession() {
    GoogleCredentials credentials;
    try {
      credentials =
          GoogleCredentials.getApplicationDefault().createScoped(List.of(CLOUD_PLATFORM_SCOPE));
    } catch (IOException error) {
      throw new GoogleAuthenticationException(
          "Google Application Default Credentials could not be loaded.", error);
    }
    HttpClient http =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    return new InventorySession() {
      @Override
      public QueryResponse listAssets(String projectId, String pageToken)
          throws IOException, InterruptedException {
        AccessToken token = accessToken();
        HttpRequest request =
            HttpRequest.newBuilder(requestUri(projectId, pageToken))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token.getTokenValue())
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response =
            http.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach(
            (name, values) -> {
              if (!values.isEmpty()) {
                headers.put(name, values.getFirst());
              }
            });
        return new QueryResponse(response.statusCode(), headers, response.body());
      }

      @Override
      public QueryResponse listTimeSeries(
          String projectId,
          String metricType,
          String resourceType,
          Instant from,
          Instant to,
          String pageToken)
          throws IOException, InterruptedException {
        HttpRequest request =
            HttpRequest.newBuilder(
                    monitoringUri(projectId, metricType, resourceType, from, to, pageToken))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken().getTokenValue())
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response =
            http.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach(
            (name, values) -> {
              if (!values.isEmpty()) {
                headers.put(name, values.getFirst());
              }
            });
        return new QueryResponse(response.statusCode(), headers, response.body());
      }

      private AccessToken accessToken() {
        try {
          credentials.refreshIfExpired();
          AccessToken token = credentials.getAccessToken();
          if (token == null || token.getTokenValue() == null || token.getTokenValue().isBlank()) {
            throw new GoogleAuthenticationException(
                "Google Application Default Credentials returned no access token.");
          }
          return token;
        } catch (IOException error) {
          throw new GoogleAuthenticationException(
              "Google Application Default Credentials could not obtain an access token.", error);
        }
      }

      @Override
      public void close() {}
    };
  }

  private static Batch failed(Batch.Status status, String checkpoint, String detail) {
    return new Batch(status, List.of(), checkpoint, detail);
  }

  private static String requiredString(JsonNode node, String fieldName) {
    String value = optionalString(node, fieldName);
    if (value == null) {
      throw new IllegalStateException(
          "Google Cloud Asset Inventory asset is missing " + fieldName + ".");
    }
    return value;
  }

  private static String optionalString(JsonNode node, String fieldName) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isString()) {
      throw new IllegalStateException(
          "Google Cloud Asset Inventory field " + fieldName + " must be a string.");
    }
    String value = node.stringValue();
    return value.isBlank() ? null : value;
  }

  interface InventorySessionFactory {
    InventorySession open();
  }

  interface InventorySession extends AutoCloseable {
    QueryResponse listAssets(String projectId, String pageToken)
        throws IOException, InterruptedException;

    default QueryResponse listTimeSeries(
        String projectId,
        String metricType,
        String resourceType,
        Instant from,
        Instant to,
        String pageToken)
        throws IOException, InterruptedException {
      throw new UnsupportedOperationException("Google Cloud Monitoring is not available.");
    }

    @Override
    void close();
  }

  record QueryResponse(int statusCode, Map<String, String> headers, String body) {}

  private static final class GoogleAuthenticationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    GoogleAuthenticationException(String message) {
      super(message);
    }

    GoogleAuthenticationException(String message, IOException cause) {
      super(message, cause);
    }
  }
}
