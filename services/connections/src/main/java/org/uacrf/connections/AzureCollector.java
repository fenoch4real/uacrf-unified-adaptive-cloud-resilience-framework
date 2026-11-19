package org.uacrf.connections;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.core.exception.ClientAuthenticationException;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
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
final class AzureCollector implements ProviderCollector {
  private static final Pattern SOURCE_REF =
      Pattern.compile("^azure/([a-z0-9]+(?:-[a-z0-9]+)*)/resourcegraph/inventory$");
  private static final Pattern METRIC_SOURCE_REF =
      Pattern.compile("^azure/monitor/([A-Za-z0-9_-]{1,357})/([A-Za-z][A-Za-z0-9._-]{0,127})$");
  private static final Pattern SUBSCRIPTION_ID =
      Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern RESOURCE_ID =
      Pattern.compile(
          "^/subscriptions/([0-9a-fA-F-]{36})/resourceGroups/[^/]+/providers/[^/]+/.+$",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern RESOURCE_ID_SEGMENT =
      Pattern.compile("^[A-Za-z0-9._~!$&'()*+,;=:@-]+$");
  private static final String RESOURCE_GRAPH_URL =
      "https://management.azure.com/providers/Microsoft.ResourceGraph/resources"
          + "?api-version=2022-10-01";
  private static final String MANAGEMENT_SCOPE = "https://management.azure.com/.default";
  private static final String INVENTORY_QUERY =
      "Resources | where location =~ '%s' | project id, name, type, location, subscriptionId, "
          + "resourceGroup | order by id asc";
  private static final int PAGE_SIZE = 100;
  private static final String KIND = "azure.resourcegraph.rows";
  private static final String METRIC_KIND = "azure.monitor.metrics";
  private static final Duration MAX_METRIC_WINDOW = Duration.ofHours(24);
  private static final int METRIC_PERIOD_SECONDS = 300;

  private final InventorySessionFactory sessions;
  private final ObjectMapper objectMapper;

  @Autowired
  AzureCollector(ObjectMapper objectMapper) {
    this(() -> openSession(objectMapper), objectMapper);
  }

  AzureCollector(InventorySessionFactory sessions, ObjectMapper objectMapper) {
    this.sessions = sessions;
    this.objectMapper = objectMapper;
  }

  @Override
  public ConnectionsRequests.Provider provider() {
    return ConnectionsRequests.Provider.AZURE;
  }

  @Override
  public Batch collect(Request request) {
    Matcher source = SOURCE_REF.matcher(request.sourceRef());
    Matcher metricSource = METRIC_SOURCE_REF.matcher(request.sourceRef());
    boolean inventorySource = source.matches();
    boolean metricsSource = metricSource.matches();
    if (!inventorySource && !metricsSource) {
      throw new IllegalArgumentException(
          "Azure source_ref must identify Resource Graph inventory or a scoped Monitor metric.");
    }
    if (request.readOnlyIdentityRef() == null
        || !SUBSCRIPTION_ID.matcher(request.readOnlyIdentityRef()).matches()) {
      throw new IllegalArgumentException(
          "Azure read_only_identity_ref must be a subscription ID.");
    }
    if (metricsSource) {
      return collectMetric(request, metricSource);
    }
    if (request.to() == null) {
      throw new IllegalArgumentException("Azure inventory collection time is required.");
    }

    String query = INVENTORY_QUERY.formatted(source.group(1));
    try (InventorySession session = sessions.open()) {
      QueryResponse response =
          session.query(request.readOnlyIdentityRef(), query, request.checkpoint());
      if (response.statusCode() == 401 || response.statusCode() == 403) {
        return failed(
            Batch.Status.PERMISSION_DENIED,
            request.checkpoint(),
            "Azure Resource Graph denied the authenticated read-only request.");
      }
      if (response.statusCode() == 429) {
        return new Batch(
            Batch.Status.RATE_LIMITED,
            List.of(),
            request.checkpoint(),
            "Azure Resource Graph rate limit reached.",
            ProviderRetryAt.fromHeaders(response.headers(), Instant.now()));
      }
      if (response.statusCode() == 408 || response.statusCode() >= 500) {
        return failed(
            Batch.Status.SOURCE_UNAVAILABLE,
            request.checkpoint(),
            "Azure Resource Graph is unavailable.");
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Azure Resource Graph returned HTTP " + response.statusCode() + ".");
      }

      JsonNode body = objectMapper.readTree(response.body());
      if (body == null || !body.isObject()) {
        throw new IllegalStateException("Azure Resource Graph returned an invalid response.");
      }
      JsonNode data = body.get("data");
      if (data == null || !data.isArray()) {
        throw new IllegalStateException("Azure Resource Graph response is missing its data array.");
      }
      String nextCheckpoint = optionalString(body.get("$skipToken"), "$skipToken");
      if (isTruncated(body.get("resultTruncated")) && nextCheckpoint == null) {
        return failed(
            Batch.Status.SOURCE_UNAVAILABLE,
            request.checkpoint(),
            "Azure Resource Graph truncated the result without a continuation token.");
      }

      List<Observation> observations = new ArrayList<>();
      long sequence = request.lastSequence();
      for (JsonNode item : data) {
        if (!item.isObject()) {
          throw new IllegalStateException(
              "Azure Resource Graph returned a non-object inventory row.");
        }
        String payload = objectMapper.writeValueAsString(item);
        sequence = Math.addExact(sequence, 1);
        observations.add(
            new Observation(
                ProviderObservationIds.create("azure-resourcegraph-", request.sourceRef(), sequence),
                sequence,
                request.to(),
                KIND,
                payload));
      }
      return new Batch(Batch.Status.COLLECTED, observations, nextCheckpoint, null);
    } catch (ClientAuthenticationException error) {
      return failed(
          Batch.Status.PERMISSION_DENIED,
          request.checkpoint(),
          "Azure DefaultAzureCredential could not authenticate.");
    } catch (IOException error) {
      return failed(
          Batch.Status.SOURCE_UNAVAILABLE,
          request.checkpoint(),
          "Azure Resource Graph request failed.");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Azure Resource Graph request was interrupted.", error);
    } catch (JacksonException error) {
      throw new IllegalStateException("Azure Resource Graph returned invalid inventory JSON.", error);
    }
  }

  private Batch collectMetric(Request request, Matcher source) {
    if (request.to() == null) {
      throw new IllegalArgumentException("Azure Monitor collection time is required.");
    }
    if (request.checkpoint() != null) {
      return failed(
          Batch.Status.SOURCE_UNAVAILABLE,
          request.checkpoint(),
          "Azure Monitor metrics do not support continuation checkpoints.");
    }

    String resourceId = decodeResourceId(source.group(1), request.readOnlyIdentityRef());
    String metricName = source.group(2);
    Instant from = request.from();
    Instant to = request.to();
    if (from == null) {
      from = to.minusSeconds(METRIC_PERIOD_SECONDS);
    }
    Duration window = Duration.between(from, to);
    if (window.isNegative() || window.isZero() || window.compareTo(MAX_METRIC_WINDOW) > 0) {
      throw new IllegalArgumentException(
          "Azure Monitor collection window must be positive and no longer than 24 hours.");
    }

    try (InventorySession session = sessions.open()) {
      QueryResponse response = session.metrics(resourceId, metricName, from, to);
      if (response.statusCode() == 401 || response.statusCode() == 403) {
        return failed(
            Batch.Status.PERMISSION_DENIED,
            request.checkpoint(),
            "Azure Monitor denied the authenticated read-only request.");
      }
      if (response.statusCode() == 429) {
        return new Batch(
            Batch.Status.RATE_LIMITED,
            List.of(),
            request.checkpoint(),
            "Azure Monitor rate limit reached.",
            ProviderRetryAt.fromHeaders(response.headers(), Instant.now()));
      }
      if (response.statusCode() == 408 || response.statusCode() >= 500) {
        return failed(
            Batch.Status.SOURCE_UNAVAILABLE,
            request.checkpoint(),
            "Azure Monitor is unavailable.");
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Azure Monitor returned HTTP " + response.statusCode() + ".");
      }

      MetricResult result = metricResult(response.body(), metricName, from, to);
      long sequence = Math.addExact(request.lastSequence(), 1);
      Observation observation =
          new Observation(
              ProviderObservationIds.create(
                  "azure-monitor-", request.sourceRef(), sequence),
              sequence,
              result.observedAt(),
              METRIC_KIND,
              result.payload());
      return new Batch(Batch.Status.COLLECTED, List.of(observation), null, null);
    } catch (ClientAuthenticationException error) {
      return failed(
          Batch.Status.PERMISSION_DENIED,
          request.checkpoint(),
          "Azure DefaultAzureCredential could not authenticate.");
    } catch (IOException error) {
      return failed(
          Batch.Status.SOURCE_UNAVAILABLE,
          request.checkpoint(),
          "Azure Monitor request failed.");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Azure Monitor request was interrupted.", error);
    } catch (JacksonException error) {
      throw new IllegalStateException("Azure Monitor returned invalid metrics JSON.", error);
    }
  }

  private MetricResult metricResult(
      String responseBody, String expectedMetric, Instant from, Instant to)
      throws JacksonException {
    JsonNode body = objectMapper.readTree(responseBody);
    if (body == null || !body.isObject()) {
      throw new IllegalStateException("Azure Monitor returned an invalid response.");
    }
    JsonNode values = body.get("value");
    if (values == null || !values.isArray() || values.size() != 1) {
      throw new IllegalStateException(
          "Azure Monitor response must contain exactly one requested metric.");
    }
    JsonNode metric = values.get(0);
    if (metric == null || !metric.isObject()) {
      throw new IllegalStateException("Azure Monitor returned an invalid metric.");
    }
    JsonNode name = metric.get("name");
    JsonNode nameValue = name == null ? null : name.get("value");
    if (nameValue == null
        || !nameValue.isString()
        || !expectedMetric.equalsIgnoreCase(nameValue.stringValue())) {
      throw new IllegalStateException("Azure Monitor returned an unexpected metric.");
    }
    if (metric.get("id") == null
        || !metric.get("id").isString()
        || metric.get("unit") == null
        || !metric.get("unit").isString()) {
      throw new IllegalStateException("Azure Monitor metric response is missing its ID or unit.");
    }
    JsonNode timeseries = metric.get("timeseries");
    if (timeseries == null || !timeseries.isArray()) {
      throw new IllegalStateException("Azure Monitor metric response is missing time series.");
    }

    List<Object> filteredSeries = new ArrayList<>();
    Instant observedAt = null;
    for (JsonNode series : timeseries) {
      if (!series.isObject()) {
        throw new IllegalStateException("Azure Monitor returned an invalid time series.");
      }
      JsonNode data = series.get("data");
      if (data == null || !data.isArray()) {
        throw new IllegalStateException("Azure Monitor time series is missing data.");
      }
      List<JsonNode> filteredData = new ArrayList<>();
      for (JsonNode point : data) {
        JsonNode timestampNode = point.get("timeStamp");
        if (timestampNode == null || !timestampNode.isString()) {
          throw new IllegalStateException("Azure Monitor returned a metric point without a timestamp.");
        }
        Instant timestamp;
        try {
          timestamp = Instant.parse(timestampNode.stringValue());
        } catch (java.time.DateTimeException error) {
          throw new IllegalStateException("Azure Monitor returned an invalid metric timestamp.", error);
        }
        if (!timestamp.isBefore(from) && timestamp.isBefore(to)) {
          JsonNode average = point.get("average");
          if (average == null || !average.isNumber()) {
            throw new IllegalStateException(
                "Azure Monitor returned a metric point without the requested average.");
          }
          filteredData.add(point);
          if (observedAt == null || timestamp.isAfter(observedAt)) {
            observedAt = timestamp;
          }
        }
      }
      Map<String, Object> outputSeries = new LinkedHashMap<>();
      if (series.has("metadatavalues")) {
        outputSeries.put("metadatavalues", series.get("metadatavalues"));
      }
      outputSeries.put("data", filteredData);
      filteredSeries.add(outputSeries);
    }
    if (observedAt == null) {
      observedAt = to;
    }

    Map<String, Object> outputMetric = new LinkedHashMap<>();
    for (String field : List.of("id", "type", "displayDescription", "name", "unit")) {
      if (metric.has(field)) {
        outputMetric.put(field, metric.get(field));
      }
    }
    outputMetric.put("timeseries", filteredSeries);
    Map<String, Object> output = new LinkedHashMap<>();
    for (String field : List.of("cost", "timespan", "interval", "namespace", "resourceregion")) {
      if (body.has(field)) {
        output.put(field, body.get(field));
      }
    }
    output.put("value", List.of(outputMetric));
    return new MetricResult(objectMapper.writeValueAsString(output), observedAt);
  }

  private static String decodeResourceId(String encodedResourceId, String subscriptionId) {
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(encodedResourceId);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("Azure Monitor source_ref has an invalid resource ID.", error);
    }
    String resourceId = new String(decoded, StandardCharsets.UTF_8);
    if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encodedResourceId)
        || !RESOURCE_ID.matcher(resourceId).matches()) {
      throw new IllegalArgumentException("Azure Monitor source_ref has an invalid resource ID.");
    }
    String[] segments = resourceId.split("/", -1);
    for (int index = 1; index < segments.length; index++) {
      if (!RESOURCE_ID_SEGMENT.matcher(segments[index]).matches()) {
        throw new IllegalArgumentException("Azure Monitor source_ref has an invalid resource ID.");
      }
    }
    if (!subscriptionId.equalsIgnoreCase(segments[2])) {
      throw new IllegalArgumentException(
          "Azure Monitor resource ID must belong to the configured subscription.");
    }
    return resourceId;
  }

  static String metricSourceRef(String resourceId, String metricName) {
    return "azure/monitor/"
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(resourceId.getBytes(StandardCharsets.UTF_8))
        + "/"
        + metricName;
  }

  static URI metricsUri(String resourceId, String metricName, Instant from, Instant to) {
    String query =
        "api-version=2018-01-01"
            + "&timespan="
            + URLEncoder.encode(from + "/" + to, StandardCharsets.UTF_8)
            + "&interval=PT5M&metricnames="
            + URLEncoder.encode(metricName, StandardCharsets.UTF_8)
            + "&aggregation=Average";
    return URI.create(
        "https://management.azure.com"
            + resourceId
            + "/providers/Microsoft.Insights/metrics?"
            + query);
  }

  private record MetricResult(String payload, Instant observedAt) {}

  private static InventorySession openSession(ObjectMapper objectMapper) {
    DefaultAzureCredential credential = new DefaultAzureCredentialBuilder().build();
    HttpClient http =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    return new InventorySession() {
      @Override
      public QueryResponse query(String subscriptionId, String query, String skipToken)
          throws IOException, InterruptedException {
        AccessToken token = accessToken();
        String body;
        try {
          body =
              objectMapper.writeValueAsString(
                  requestBody(subscriptionId, query, skipToken));
        } catch (JacksonException error) {
          throw new IllegalStateException("Could not encode the Azure Resource Graph request.", error);
        }
        HttpRequest request =
            HttpRequest.newBuilder(URI.create(RESOURCE_GRAPH_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token.getToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
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
      public QueryResponse metrics(
          String resourceId, String metricName, Instant from, Instant to)
          throws IOException, InterruptedException {
        HttpRequest request =
            HttpRequest.newBuilder(metricsUri(resourceId, metricName, from, to))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken().getToken())
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
        AccessToken token =
            credential
                .getToken(new TokenRequestContext().addScopes(MANAGEMENT_SCOPE))
                .block();
        if (token == null) {
          throw new IllegalStateException("Azure credential did not return an access token.");
        }
        return token;
      }

      @Override
      public void close() {}
    };
  }

  private static Batch failed(Batch.Status status, String checkpoint, String detail) {
    return new Batch(status, List.of(), checkpoint, detail);
  }

  private static String optionalString(JsonNode node, String fieldName) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isString()) {
      throw new IllegalStateException(
          "Azure Resource Graph response field " + fieldName + " must be a string.");
    }
    String value = node.stringValue();
    return value.isBlank() ? null : value;
  }

  private static boolean isTruncated(JsonNode node) {
    if (node == null || node.isNull()) {
      throw new IllegalStateException(
          "Azure Resource Graph response is missing resultTruncated.");
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isString()) {
      String value = node.stringValue();
      if ("true".equalsIgnoreCase(value)) {
        return true;
      }
      if ("false".equalsIgnoreCase(value)) {
        return false;
      }
    }
    throw new IllegalStateException(
        "Azure Resource Graph response field resultTruncated must be a boolean.");
  }

  static Map<String, Object> requestBody(
      String subscriptionId, String query, String skipToken) {
    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("subscriptions", List.of(subscriptionId));
    requestBody.put("query", query);
    Map<String, Object> options = new LinkedHashMap<>();
    if (skipToken == null) {
      options.put("$top", PAGE_SIZE);
      options.put("$skip", 0);
    } else {
      options.put("$skipToken", skipToken);
    }
    requestBody.put("options", options);
    return requestBody;
  }

  interface InventorySessionFactory {
    InventorySession open();
  }

  interface InventorySession extends AutoCloseable {
    QueryResponse query(String subscriptionId, String query, String skipToken)
        throws IOException, InterruptedException;

    default QueryResponse metrics(
        String resourceId, String metricName, Instant from, Instant to)
        throws IOException, InterruptedException {
      throw new UnsupportedOperationException("Azure Monitor metrics are not available.");
    }

    @Override
    void close();
  }

  record QueryResponse(int statusCode, Map<String, String> headers, String body) {}
}
