package org.uacrf.edge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class TopologyRouteClient {
  private final RestClient topologyRestClient;

  TopologyRouteClient(@Qualifier("topologyServiceRestClient") RestClient topologyRestClient) {
    this.topologyRestClient = topologyRestClient;
  }

  ResponseEntity<String> listProposals(String authorization, int pageSize, String cursor) {
    RestClient.RequestHeadersSpec<?> request =
        topologyRestClient
            .get()
            .uri(
                uriBuilder -> {
                  uriBuilder.path("/v1/dependency-proposals").queryParam("pageSize", pageSize);
                  if (cursor != null && !cursor.isBlank()) {
                    uriBuilder.queryParam("cursor", cursor);
                  }
                  return uriBuilder.build();
                });
    return send(request, authorization);
  }

  ResponseEntity<String> getProposal(String authorization, String id) {
    return send(
        topologyRestClient.get().uri(uriBuilder -> uriBuilder.path("/v1/dependency-proposals/{id}").build(id)),
        authorization);
  }

  ResponseEntity<String> propose(String authorization, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/dependency-proposals", null, authorization, requestBody);
  }

  ResponseEntity<String> proposeEvidence(String authorization, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/dependency-proposals:evidence-backed", null, authorization, requestBody);
  }

  ResponseEntity<String> review(String authorization, String id, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/dependency-proposals/{id}:review", id, authorization, requestBody);
  }

  ResponseEntity<String> accept(String authorization, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/graphs:accept", null, authorization, requestBody);
  }

  ResponseEntity<String> classifyDifferences(
      String authorization, String idempotencyKey, String requestBody) {
    return sendBody(
        HttpMethod.POST,
        "/v1/graphs/differences:classify",
        null,
        authorization,
        idempotencyKey,
        requestBody);
  }

  ResponseEntity<String> selectSubgraph(
      String authorization, String idempotencyKey, String requestBody) {
    return sendBody(
        HttpMethod.POST,
        "/v1/graphs/subgraphs:select",
        null,
        authorization,
        idempotencyKey,
        requestBody);
  }

  ResponseEntity<String> getCommand(String authorization, String id) {
    return send(
        topologyRestClient.get().uri(uriBuilder -> uriBuilder.path("/v1/commands/{id}").build(id)),
        authorization);
  }

  ResponseEntity<String> getGraph(String authorization, String id) {
    return send(
        topologyRestClient.get().uri(uriBuilder -> uriBuilder.path("/v1/graphs/{id}").build(id)),
        authorization);
  }

  ResponseEntity<String> reconciliation(String authorization, String id) {
    return send(
        topologyRestClient.get().uri(uriBuilder -> uriBuilder.path("/v1/graphs/{id}/reconciliation").build(id)),
        authorization);
  }

  ResponseEntity<String> subgraph(String authorization, String id, String rootType, String rootId, int depth) {
    RestClient.RequestHeadersSpec<?> request =
        topologyRestClient
            .get()
            .uri(
                uriBuilder ->
                    uriBuilder
                        .path("/v1/graphs/{id}/subgraph")
                        .queryParam("root_type", rootType)
                        .queryParam("root_id", rootId)
                        .queryParam("depth", depth)
                        .build(id));
    return send(request, authorization);
  }

  ResponseEntity<String> impact(String authorization, String id, String entityType, String entityId) {
    RestClient.RequestHeadersSpec<?> request =
        topologyRestClient
            .get()
            .uri(
                uriBuilder ->
                    uriBuilder
                        .path("/v1/graphs/{id}/impact")
                        .queryParam("entity_type", entityType)
                        .queryParam("entity_id", entityId)
                        .build(id));
    return send(request, authorization);
  }

  private ResponseEntity<String> sendBody(
      HttpMethod method, String path, String variable, String authorization, String requestBody) {
    return sendBody(method, path, variable, authorization, null, requestBody);
  }

  private ResponseEntity<String> sendBody(
      HttpMethod method,
      String path,
      String variable,
      String authorization,
      String idempotencyKey,
      String requestBody) {
    RestClient.RequestBodySpec request =
        topologyRestClient
            .method(method)
            .uri(
                uriBuilder -> variable == null ? uriBuilder.path(path).build() : uriBuilder.path(path).build(variable));
    request.contentType(MediaType.APPLICATION_JSON).body(requestBody);
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    return send(request, authorization);
  }

  private ResponseEntity<String> send(RestClient.RequestHeadersSpec<?> request, String authorization) {
    if (authorization != null) {
      request.header(HttpHeaders.AUTHORIZATION, authorization);
    }
    return request.exchange((clientRequest, response) -> toResponse(response));
  }

  private ResponseEntity<String> toResponse(
      org.springframework.http.client.ClientHttpResponse response) throws IOException {
    HttpHeaders headers = new HttpHeaders();
    headers.setCacheControl(CacheControl.noStore());
    for (String name :
        new String[] {
          HttpHeaders.CONTENT_TYPE, HttpHeaders.WWW_AUTHENTICATE, HttpHeaders.LOCATION, HttpHeaders.RETRY_AFTER
        }) {
      if (response.getHeaders().containsHeader(name)) {
        headers.put(name, response.getHeaders().get(name));
      }
    }
    String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
    return new ResponseEntity<>(body, headers, response.getStatusCode());
  }
}
