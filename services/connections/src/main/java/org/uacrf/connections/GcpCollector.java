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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  private static final Pattern PROJECT_ID =
      Pattern.compile("^[a-z][a-z0-9-]{4,28}[a-z0-9]$");
  private static final String ASSET_INVENTORY_URL = "https://cloudasset.googleapis.com/v1/projects/";
  private static final String CLOUD_PLATFORM_SCOPE =
      "https://www.googleapis.com/auth/cloud-platform";
  private static final int PAGE_SIZE = 100;
  private static final String KIND = "gcp.assetinventory.assets";

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
    if (request.sourceRef() == null || !SOURCE_REF.matcher(request.sourceRef()).matches()) {
      throw new IllegalArgumentException(
          "GCP inventory source_ref must use gcp/<region>/assetinventory/inventory.");
    }
    if (request.readOnlyIdentityRef() == null
        || !PROJECT_ID.matcher(request.readOnlyIdentityRef()).matches()) {
      throw new IllegalArgumentException(
          "GCP read_only_identity_ref must be a project ID.");
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
        AccessToken token;
        try {
          credentials.refreshIfExpired();
          token = credentials.getAccessToken();
        } catch (IOException error) {
          throw new GoogleAuthenticationException(
              "Google Application Default Credentials could not obtain an access token.", error);
        }
        if (token == null || token.getTokenValue() == null || token.getTokenValue().isBlank()) {
          throw new GoogleAuthenticationException(
              "Google Application Default Credentials returned no access token.");
        }
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
