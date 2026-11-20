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
class AssessmentRouteClient {
  private final RestClient assessmentRestClient;

  AssessmentRouteClient(@Qualifier("assessmentServiceRestClient") RestClient assessmentRestClient) {
    this.assessmentRestClient = assessmentRestClient;
  }

  ResponseEntity<String> create(String authorization, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/cases", null, authorization, requestBody);
  }

  ResponseEntity<String> createIdempotently(String authorization, String idempotencyKey, String requestBody) {
    RestClient.RequestBodySpec request = assessmentRestClient.post()
        .uri("/v1/cases:idempotent-create").contentType(MediaType.APPLICATION_JSON);
    request.header("Idempotency-Key", idempotencyKey);
    request.body(requestBody);
    return send(request, authorization);
  }

  ResponseEntity<String> transition(String authorization, String id, String action, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/cases/{id}:" + action, id, authorization, requestBody);
  }

  ResponseEntity<String> getCase(String authorization, String id) {
    return send(
        assessmentRestClient.get().uri(uriBuilder -> uriBuilder.path("/v1/cases/{id}").build(id)),
        authorization);
  }

  ResponseEntity<String> listCaseIncidents(String authorization, String id, String limit, String after) {
    return listPage(authorization, "/v1/cases/{id}/incidents", id, limit, after);
  }

  ResponseEntity<String> listCases(String authorization, String workloadId) {
    RestClient.RequestHeadersSpec<?> request =
        assessmentRestClient
            .get()
            .uri(
                uriBuilder -> {
                  uriBuilder.path("/v1/cases");
                  if (workloadId != null && !workloadId.isBlank()) {
                    uriBuilder.queryParam("workload_id", workloadId);
                  }
                  return uriBuilder.build();
                });
    return send(request, authorization);
  }

  ResponseEntity<String> evaluate(String authorization, String id, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/cases/{id}:evaluate", id, authorization, requestBody);
  }

  ResponseEntity<String> evaluateIdempotently(String authorization, String id, String key, String requestBody) {
    RestClient.RequestBodySpec request = assessmentRestClient.post()
        .uri(uri -> uri.path("/v1/cases/{id}:idempotent-evaluate").build(id))
        .contentType(MediaType.APPLICATION_JSON);
    request.header("Idempotency-Key", key);
    request.body(requestBody);
    return send(request, authorization);
  }

  ResponseEntity<String> evaluations(String authorization, String id) {
    return send(
        assessmentRestClient.get().uri(uriBuilder -> uriBuilder.path("/v1/cases/{id}/evaluations").build(id)),
        authorization);
  }

  ResponseEntity<String> latestEvaluation(String authorization, String id) {
    return send(
        assessmentRestClient
            .get()
            .uri(uriBuilder -> uriBuilder.path("/v1/cases/{id}/evaluations:latest").build(id)),
        authorization);
  }

  ResponseEntity<String> evaluation(String authorization, String id) {
    return send(
        assessmentRestClient.get().uri(uriBuilder -> uriBuilder.path("/v1/evaluations/{id}").build(id)),
        authorization);
  }

  ResponseEntity<String> recommendations(String authorization, String workloadId) {
    RestClient.RequestHeadersSpec<?> request =
        assessmentRestClient
            .get()
            .uri(
                uriBuilder ->
                    uriBuilder.path("/v1/recommendations").queryParam("workload_id", workloadId).build());
    return send(request, authorization);
  }

  ResponseEntity<String> retainModelVersion(String authorization, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/model-versions", null, authorization, requestBody);
  }

  ResponseEntity<String> modelVersions(String authorization, String modelId) {
    RestClient.RequestHeadersSpec<?> request =
        assessmentRestClient.get().uri(uriBuilder -> {
          uriBuilder.path("/v1/model-versions");
          if (modelId != null && !modelId.isBlank()) uriBuilder.queryParam("model_id", modelId);
          return uriBuilder.build();
        });
    return send(request, authorization);
  }

  ResponseEntity<String> modelVersion(String authorization, String id) {
    return send(assessmentRestClient.get()
        .uri(uriBuilder -> uriBuilder.path("/v1/model-versions/{id}").build(id)), authorization);
  }

  ResponseEntity<String> reviewModelVersion(String authorization, String id, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/model-versions/{id}:review", id, authorization, requestBody);
  }

  ResponseEntity<String> promoteModelVersion(String authorization, String id, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/model-versions/{id}:promote", id, authorization, requestBody);
  }

  ResponseEntity<String> createIncident(String authorization, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/incidents", null, authorization, requestBody);
  }

  ResponseEntity<String> createIncidentIdempotently(String authorization, String key, String requestBody) {
    RestClient.RequestBodySpec request = assessmentRestClient.post()
        .uri("/v1/incidents:idempotent-create").contentType(MediaType.APPLICATION_JSON);
    request.header("Idempotency-Key", key);
    request.body(requestBody);
    return send(request, authorization);
  }

  ResponseEntity<String> getIncident(String authorization, String id) {
    return send(assessmentRestClient.get()
        .uri(uriBuilder -> uriBuilder.path("/v1/incidents/{id}").build(id)), authorization);
  }

  ResponseEntity<String> listIncidentLearningRecords(
      String authorization, String id, String limit, String after) {
    return listPage(authorization, "/v1/incidents/{id}/learning-records", id, limit, after);
  }

  private ResponseEntity<String> listPage(
      String authorization, String path, String id, String limit, String after) {
    return send(assessmentRestClient.get().uri(uriBuilder -> {
      uriBuilder.path(path);
      if (limit != null) uriBuilder.queryParam("limit", limit);
      if (after != null) uriBuilder.queryParam("after", after);
      return uriBuilder.build(id);
    }), authorization);
  }

  ResponseEntity<String> proposeLearningRecord(String authorization, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/learning-records", null, authorization, requestBody);
  }

  ResponseEntity<String> proposeLearningRecordIdempotently(String authorization, String key, String requestBody) {
    RestClient.RequestBodySpec request = assessmentRestClient.post()
        .uri("/v1/learning-records:idempotent-propose").contentType(MediaType.APPLICATION_JSON);
    request.header("Idempotency-Key", key);
    request.body(requestBody);
    return send(request, authorization);
  }

  ResponseEntity<String> getLearningRecord(String authorization, String id) {
    return send(assessmentRestClient.get()
        .uri(uriBuilder -> uriBuilder.path("/v1/learning-records/{id}").build(id)), authorization);
  }

  ResponseEntity<String> reviewLearningRecord(String authorization, String id, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/learning-records/{id}:review", id, authorization, requestBody);
  }

  ResponseEntity<String> getLearningReview(String authorization, String id) {
    return send(assessmentRestClient.get()
        .uri(uriBuilder -> uriBuilder.path("/v1/learning-records/{id}/review").build(id)), authorization);
  }

  private ResponseEntity<String> sendBody(
      HttpMethod method, String path, String variable, String authorization, String requestBody) {
    RestClient.RequestBodySpec request =
        assessmentRestClient
            .method(method)
            .uri(
                uriBuilder -> variable == null ? uriBuilder.path(path).build() : uriBuilder.path(path).build(variable));
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
