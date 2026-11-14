package org.uacrf.edge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

@Component
class CatalogRouteClient {
  private final RestClient catalogRestClient;

  CatalogRouteClient(@Qualifier("catalogServiceRestClient") RestClient catalogRestClient) {
    this.catalogRestClient = catalogRestClient;
  }

  ResponseEntity<String> listServices(
      String authorization, int pageSize, String cursor, Boolean includeRetired) {
    return get(
        "/v1/services",
        authorization,
        uri -> {
          uri.queryParam("pageSize", pageSize);
          queryParam(uri, "cursor", cursor);
          if (includeRetired != null) {
            uri.queryParam("includeRetired", includeRetired);
          }
        });
  }

  ResponseEntity<String> registerService(
      String authorization, String idempotencyKey, String requestBody) {
    return post("/v1/services", authorization, idempotencyKey, requestBody);
  }

  ResponseEntity<String> listAgents(String authorization, int pageSize, String cursor) {
    return get(
        "/v1/agent-workloads",
        authorization,
        uri -> {
          uri.queryParam("pageSize", pageSize);
          queryParam(uri, "cursor", cursor);
        });
  }

  ResponseEntity<String> registerAgent(
      String authorization, String idempotencyKey, String requestBody) {
    return post("/v1/agent-workloads", authorization, idempotencyKey, requestBody);
  }

  ResponseEntity<String> listJourneys(
      String authorization, int pageSize, String cursor, Boolean includeRetired) {
    return get(
        "/v1/customer-journeys",
        authorization,
        uri -> {
          uri.queryParam("pageSize", pageSize);
          queryParam(uri, "cursor", cursor);
          if (includeRetired != null) {
            uri.queryParam("includeRetired", includeRetired);
          }
        });
  }

  ResponseEntity<String> getJourney(String authorization, String journeyId) {
    return get(
        "/v1/customer-journeys/{id}",
        authorization,
        uri -> {},
        journeyId);
  }

  ResponseEntity<String> registerJourney(
      String authorization, String idempotencyKey, String requestBody) {
    return post("/v1/customer-journeys", authorization, idempotencyKey, requestBody);
  }

  ResponseEntity<String> registerStep(
      String authorization, String journeyId, String idempotencyKey, String requestBody) {
    return post(
        "/v1/customer-journeys/{id}/steps",
        authorization,
        idempotencyKey,
        requestBody,
        journeyId);
  }

  ResponseEntity<String> linkCapability(
      String authorization, String journeyId, String idempotencyKey, String requestBody) {
    return post(
        "/v1/customer-journeys/{id}/capabilities",
        authorization,
        idempotencyKey,
        requestBody,
        journeyId);
  }

  private ResponseEntity<String> get(
      String path, String authorization, Consumer<UriBuilder> queryParameters, Object... variables) {
    RestClient.RequestHeadersSpec<?> request =
        catalogRestClient
            .get()
            .uri(
                uriBuilder -> {
                  UriBuilder uri = uriBuilder.path(path);
                  queryParameters.accept(uri);
                  return uri.build(variables);
                });
    if (authorization != null) {
      request.header(HttpHeaders.AUTHORIZATION, authorization);
    }
    return request.exchange((clientRequest, response) -> toResponse(response));
  }

  private ResponseEntity<String> post(
      String path,
      String authorization,
      String idempotencyKey,
      String requestBody,
      Object... variables) {
    RestClient.RequestBodySpec request =
        catalogRestClient
            .post()
            .uri(uriBuilder -> uriBuilder.path(path).build(variables));
    if (authorization != null) {
      request.header(HttpHeaders.AUTHORIZATION, authorization);
    }
    return request
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .body(requestBody)
        .exchange((clientRequest, response) -> toResponse(response));
  }

  private ResponseEntity<String> toResponse(
      org.springframework.http.client.ClientHttpResponse response) throws IOException {
    HttpHeaders headers = new HttpHeaders();
    headers.setCacheControl(CacheControl.noStore());
    copyHeader(response.getHeaders(), headers, HttpHeaders.CONTENT_TYPE);
    copyHeader(response.getHeaders(), headers, HttpHeaders.WWW_AUTHENTICATE);
    copyHeader(response.getHeaders(), headers, HttpHeaders.LOCATION);
    copyHeader(response.getHeaders(), headers, HttpHeaders.RETRY_AFTER);
    String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
    return new ResponseEntity<>(body, headers, response.getStatusCode());
  }

  private static void copyHeader(HttpHeaders source, HttpHeaders target, String name) {
    if (source.containsHeader(name)) {
      target.put(name, source.get(name));
    }
  }

  private static void queryParam(UriBuilder uri, String name, String value) {
    if (value != null && !value.isBlank()) {
      uri.queryParam(name, value);
    }
  }
}
