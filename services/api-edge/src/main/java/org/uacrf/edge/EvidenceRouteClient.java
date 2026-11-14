package org.uacrf.edge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class EvidenceRouteClient {
  private final RestClient evidenceRestClient;

  EvidenceRouteClient(@Qualifier("evidenceServiceRestClient") RestClient evidenceRestClient) {
    this.evidenceRestClient = evidenceRestClient;
  }

  ResponseEntity<String> listEvidence(String authorization, int pageSize, String cursor) {
    RestClient.RequestHeadersSpec<?> request =
        evidenceRestClient
            .get()
            .uri(
                uriBuilder -> {
                  uriBuilder.path("/v1/evidence").queryParam("pageSize", pageSize);
                  if (cursor != null && !cursor.isBlank()) {
                    uriBuilder.queryParam("cursor", cursor);
                  }
                  return uriBuilder.build();
                });
    return send(request, authorization);
  }

  ResponseEntity<String> getEvidence(String authorization, String id) {
    return send(
        evidenceRestClient.get().uri(uriBuilder -> uriBuilder.path("/v1/evidence/{id}").build(id)),
        authorization);
  }

  ResponseEntity<String> reviewEvidence(String authorization, String id, String requestBody) {
    RestClient.RequestBodySpec request =
        evidenceRestClient
            .post()
            .uri(uriBuilder -> uriBuilder.path("/v1/evidence/{id}/reviews").build(id));
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
