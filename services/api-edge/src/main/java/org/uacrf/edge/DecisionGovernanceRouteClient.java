package org.uacrf.edge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Component
class DecisionGovernanceRouteClient {
  private final RestClient decisionGovernanceRestClient;

  DecisionGovernanceRouteClient(
      @Qualifier("decisionGovernanceServiceRestClient") RestClient decisionGovernanceRestClient) {
    this.decisionGovernanceRestClient = decisionGovernanceRestClient;
  }

  ResponseEntity<String> list(
      String authorization, String idempotencyKey, MultiValueMap<String, String> query) {
    RestClient.RequestHeadersSpec<?> request =
        decisionGovernanceRestClient
            .get()
            .uri(
                uriBuilder -> {
                  uriBuilder.path("/v1/authorization-policy-bundles");
                  Map<String, Object> variables = new LinkedHashMap<>();
                  int variableIndex = 0;
                  if (query != null && !query.isEmpty()) {
                    for (Map.Entry<String, java.util.List<String>> entry : query.entrySet()) {
                      for (String value : entry.getValue()) {
                        String variable = "query" + variableIndex++;
                        uriBuilder.queryParam(entry.getKey(), "{" + variable + "}");
                        variables.put(variable, value);
                      }
                    }
                  }
                  return uriBuilder.build(variables);
                });
    return send(request, authorization, idempotencyKey);
  }

  ResponseEntity<String> propose(
      String authorization, String idempotencyKey, String requestBody) {
    RestClient.RequestBodySpec request =
        decisionGovernanceRestClient
            .post()
            .uri("/v1/authorization-policy-bundles")
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody);
    return send(request, authorization, idempotencyKey);
  }

  ResponseEntity<String> get(String authorization, String idempotencyKey, String id) {
    return send(
        decisionGovernanceRestClient
            .get()
            .uri(uriBuilder -> uriBuilder.path("/v1/authorization-policy-bundles/{id}").build(id)),
        authorization,
        idempotencyKey);
  }

  private ResponseEntity<String> send(
      RestClient.RequestHeadersSpec<?> request, String authorization, String idempotencyKey) {
    if (authorization != null) {
      request.header(HttpHeaders.AUTHORIZATION, authorization);
    }
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    return request.exchange((clientRequest, response) -> toResponse(response));
  }

  private ResponseEntity<String> toResponse(
      org.springframework.http.client.ClientHttpResponse response) throws IOException {
    HttpHeaders headers = new HttpHeaders();
    headers.setCacheControl(CacheControl.noStore());
    for (String name :
        new String[] {
          HttpHeaders.CONTENT_TYPE, HttpHeaders.WWW_AUTHENTICATE, HttpHeaders.LOCATION,
          HttpHeaders.RETRY_AFTER
        }) {
      if (response.getHeaders().containsHeader(name)) {
        headers.put(name, response.getHeaders().get(name));
      }
    }
    String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
    return new ResponseEntity<>(body, headers, response.getStatusCode());
  }
}
