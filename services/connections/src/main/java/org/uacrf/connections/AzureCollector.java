package org.uacrf.connections;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.core.exception.ClientAuthenticationException;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
  private static final Pattern SUBSCRIPTION_ID =
      Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final String RESOURCE_GRAPH_URL =
      "https://management.azure.com/providers/Microsoft.ResourceGraph/resources"
          + "?api-version=2022-10-01";
  private static final String MANAGEMENT_SCOPE = "https://management.azure.com/.default";
  private static final String INVENTORY_QUERY =
      "Resources | where location =~ '%s' | project id, name, type, location, subscriptionId, "
          + "resourceGroup | order by id asc";
  private static final int PAGE_SIZE = 100;
  private static final String KIND = "azure.resourcegraph.rows";

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
    if (!source.matches()) {
      throw new IllegalArgumentException(
          "Azure inventory source_ref must use azure/<region>/resourcegraph/inventory.");
    }
    if (request.readOnlyIdentityRef() == null
        || !SUBSCRIPTION_ID.matcher(request.readOnlyIdentityRef()).matches()) {
      throw new IllegalArgumentException(
          "Azure read_only_identity_ref must be a subscription ID.");
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

  private static InventorySession openSession(ObjectMapper objectMapper) {
    DefaultAzureCredential credential = new DefaultAzureCredentialBuilder().build();
    HttpClient http =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    return new InventorySession() {
      @Override
      public QueryResponse query(String subscriptionId, String query, String skipToken)
          throws IOException, InterruptedException {
        AccessToken token =
            credential
                .getToken(new TokenRequestContext().addScopes(MANAGEMENT_SCOPE))
                .block();
        if (token == null) {
          throw new IllegalStateException("Azure credential did not return an access token.");
        }
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

    @Override
    void close();
  }

  record QueryResponse(int statusCode, Map<String, String> headers, String body) {}
}
