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
class KnowledgeRouteClient {
  private final RestClient knowledgeRestClient;

  KnowledgeRouteClient(@Qualifier("knowledgeServiceRestClient") RestClient knowledgeRestClient) {
    this.knowledgeRestClient = knowledgeRestClient;
  }

  ResponseEntity<String> listItems(String authorization, String itemId, String purpose) {
    RestClient.RequestHeadersSpec<?> request =
        knowledgeRestClient
            .get()
            .uri(
                uriBuilder -> {
                  uriBuilder.path("/v1/knowledge-items");
                  if (itemId != null && !itemId.isBlank()) {
                    uriBuilder.queryParam("item_id", itemId);
                  }
                  if (purpose != null && !purpose.isBlank()) {
                    uriBuilder.queryParam("purpose", purpose);
                  }
                  return uriBuilder.build();
                });
    return send(request, authorization);
  }

  ResponseEntity<String> stageItem(String authorization, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/knowledge-items", null, authorization, requestBody);
  }

  ResponseEntity<String> reviewItem(
      String authorization, String id, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/knowledge-items/{id}:review", id, authorization, requestBody);
  }

  ResponseEntity<String> withdrawItem(
      String authorization, String id, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/knowledge-items/{id}:withdraw", id, authorization, requestBody);
  }

  ResponseEntity<String> runRetrieval(String authorization, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/retrievals", null, authorization, requestBody);
  }

  ResponseEntity<String> getRetrieval(String authorization, String id) {
    return send(
        knowledgeRestClient.get().uri(uriBuilder -> uriBuilder.path("/v1/retrievals/{id}").build(id)),
        authorization);
  }

  private ResponseEntity<String> sendBody(
      HttpMethod method,
      String path,
      String pathVariable,
      String authorization,
      String requestBody) {
    RestClient.RequestBodySpec request =
        knowledgeRestClient
            .method(method)
            .uri(
                uriBuilder ->
                    pathVariable == null
                        ? uriBuilder.path(path).build()
                        : uriBuilder.path(path).build(pathVariable));
    request.contentType(MediaType.APPLICATION_JSON).body(requestBody);
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
