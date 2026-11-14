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
class ObjectivesRouteClient {
  private final RestClient objectivesRestClient;

  ObjectivesRouteClient(@Qualifier("objectivesServiceRestClient") RestClient objectivesRestClient) {
    this.objectivesRestClient = objectivesRestClient;
  }

  ResponseEntity<String> listObjectives(String authorization, int pageSize, String workloadId, String cursor) {
    RestClient.RequestHeadersSpec<?> request =
        objectivesRestClient
            .get()
            .uri(
                uriBuilder -> {
                  uriBuilder.path("/v1/objectives").queryParam("pageSize", pageSize);
                  if (workloadId != null && !workloadId.isBlank()) {
                    uriBuilder.queryParam("workloadId", workloadId);
                  }
                  if (cursor != null && !cursor.isBlank()) {
                    uriBuilder.queryParam("cursor", cursor);
                  }
                  return uriBuilder.build();
                });
    return send(request, authorization);
  }

  ResponseEntity<String> getObjective(String authorization, String id) {
    return send(
        objectivesRestClient.get().uri(uriBuilder -> uriBuilder.path("/v1/objectives/{id}").build(id)),
        authorization);
  }

  ResponseEntity<String> proposeObjective(String authorization, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/objectives", null, authorization, requestBody);
  }

  ResponseEntity<String> transition(String authorization, String id, String action, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/objectives/{id}:" + action, id, authorization, requestBody);
  }

  ResponseEntity<String> setConstraint(String authorization, String metric, String requestBody) {
    return sendBody(HttpMethod.PUT, "/v1/organization-constraints/{id}", metric, authorization, requestBody);
  }

  private ResponseEntity<String> sendBody(
      HttpMethod method, String path, String variable, String authorization, String requestBody) {
    RestClient.RequestBodySpec request =
        objectivesRestClient
            .method(method)
            .uri(
                uriBuilder ->
                    variable == null ? uriBuilder.path(path).build() : uriBuilder.path(path).build(variable));
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
