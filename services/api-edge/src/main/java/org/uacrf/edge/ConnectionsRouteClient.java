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
class ConnectionsRouteClient {
  private final RestClient connectionsRestClient;

  ConnectionsRouteClient(@Qualifier("connectionsServiceRestClient") RestClient connectionsRestClient) {
    this.connectionsRestClient = connectionsRestClient;
  }

  ResponseEntity<String> listConnections(String authorization, int pageSize, String cursor) {
    return get(
        "/v1/connections",
        authorization,
        uri -> {
          uri.queryParam("pageSize", pageSize);
          queryParam(uri, "cursor", cursor);
        });
  }

  ResponseEntity<String> getConnection(String authorization, String id) {
    return get("/v1/connections/{id}", authorization, uri -> {}, id);
  }

  ResponseEntity<String> registerConnection(
      String authorization, String idempotencyKey, String requestBody) {
    return post("/v1/connections", authorization, idempotencyKey, requestBody);
  }

  ResponseEntity<String> listBindings(
      String authorization, String id, int pageSize, String cursor) {
    return get(
        "/v1/connections/{id}/source-bindings",
        authorization,
        uri -> {
          uri.queryParam("pageSize", pageSize);
          queryParam(uri, "cursor", cursor);
        },
        id);
  }

  ResponseEntity<String> bindSource(
      String authorization, String id, String idempotencyKey, String requestBody) {
    return post(
        "/v1/connections/{id}/source-bindings", authorization, idempotencyKey, requestBody, id);
  }

  ResponseEntity<String> submitObservation(String authorization, String id, String requestBody) {
    RestClient.RequestBodySpec request =
        connectionsRestClient
            .post()
            .uri(uriBuilder -> uriBuilder.path("/v1/connections/{id}/observations").build(id));
    if (authorization != null) {
      request.header(HttpHeaders.AUTHORIZATION, authorization);
    }
    return request
        .contentType(MediaType.APPLICATION_JSON)
        .body(requestBody)
        .exchange((clientRequest, response) -> toResponse(response));
  }

  ResponseEntity<String> listObservations(
      String authorization, String id, int pageSize, String cursor) {
    return listChild("observations", authorization, id, pageSize, cursor);
  }

  ResponseEntity<String> listQuarantine(
      String authorization, String id, int pageSize, String cursor) {
    return listChild("quarantine", authorization, id, pageSize, cursor);
  }

  private ResponseEntity<String> listChild(
      String child, String authorization, String id, int pageSize, String cursor) {
    return get(
        "/v1/connections/{id}/" + child,
        authorization,
        uri -> {
          uri.queryParam("pageSize", pageSize);
          queryParam(uri, "cursor", cursor);
        },
        id);
  }

  private ResponseEntity<String> get(
      String path, String authorization, Consumer<UriBuilder> queryParameters, Object... variables) {
    RestClient.RequestHeadersSpec<?> request =
        connectionsRestClient
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
        connectionsRestClient
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
