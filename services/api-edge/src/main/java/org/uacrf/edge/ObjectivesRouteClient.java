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

  ResponseEntity<String> decisionHistory(String authorization, String id) {
    return send(objectivesRestClient.get()
        .uri(uriBuilder -> uriBuilder.path("/v1/objectives/{id}/decision-history").build(id)),
        authorization);
  }

  ResponseEntity<String> decisionRecords(String authorization, String id, int pageSize, String cursor) {
    return send(objectivesRestClient.get().uri(uriBuilder -> {
      uriBuilder.path("/v1/objectives/{id}/decision-records").queryParam("pageSize", pageSize);
      if (cursor != null && !cursor.isBlank()) uriBuilder.queryParam("cursor", cursor);
      return uriBuilder.build(id);
    }), authorization);
  }

  ResponseEntity<String> proposeObjective(String authorization, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/objectives", null, authorization, requestBody);
  }

  ResponseEntity<String> createLocalIdempotent(String authorization, String idempotencyKey, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/objectives:local-idempotent-create", null,
        authorization, requestBody, idempotencyKey);
  }

  ResponseEntity<String> previewImport(
      String authorization, String idempotencyKey, String requestBody) {
    return sendBody(
        HttpMethod.POST,
        "/v1/objective-imports:preview",
        null,
        authorization,
        requestBody,
        idempotencyKey);
  }

  ResponseEntity<String> getImportPreview(String authorization, String id) {
    return send(
        objectivesRestClient
            .get()
            .uri(uriBuilder -> uriBuilder.path("/v1/objective-imports/{id}").build(id)),
        authorization);
  }

  ResponseEntity<String> inspectImportSample(String authorization, String id) {
    return send(objectivesRestClient.get()
        .uri(uriBuilder -> uriBuilder.path("/v1/objective-imports/{id}/sample-source").build(id)),
        authorization);
  }

  ResponseEntity<String> captureImportSample(String authorization, String id, String idempotencyKey) {
    var request = objectivesRestClient.post()
        .uri(uriBuilder -> uriBuilder.path("/v1/objective-imports/{id}/sample-captures").build(id));
    if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
    return send(request, authorization);
  }

  ResponseEntity<String> getImportSampleCapture(String authorization, String id, String captureId) {
    return send(objectivesRestClient.get()
        .uri(uriBuilder -> uriBuilder.path("/v1/objective-imports/{id}/sample-captures/{captureId}")
            .build(id, captureId)), authorization);
  }

  ResponseEntity<String> rehearseImportNormalization(
      String authorization, String id, String captureId, String targetUnit, boolean allowFractionToPercent) {
    return send(objectivesRestClient.get().uri(uri -> uri
        .path("/v1/objective-imports/{id}/sample-captures/{captureId}/normalization-rehearsal")
        .queryParam("targetUnit", targetUnit).queryParam("allowFractionToPercent", allowFractionToPercent)
        .build(id, captureId)), authorization);
  }

  ResponseEntity<String> proposeSyntheticMapping(
      String authorization, String id, String captureId, String idempotencyKey, String requestBody) {
    var request = objectivesRestClient.post()
        .uri(uri -> uri.path("/v1/objective-imports/{id}/sample-captures/{captureId}/mapping-proposals")
            .build(id, captureId))
        .contentType(MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
    request.body(requestBody);
    return send(request, authorization);
  }

  ResponseEntity<String> listSyntheticMappingProposals(
      String authorization, String id, String captureId, int pageSize, String cursor) {
    return send(objectivesRestClient.get().uri(uri -> {
      uri.path("/v1/objective-imports/{id}/sample-captures/{captureId}/mapping-proposals")
          .queryParam("pageSize", pageSize);
      if (cursor != null && !cursor.isBlank()) uri.queryParam("cursor", cursor);
      return uri.build(id, captureId);
    }), authorization);
  }

  ResponseEntity<String> getSyntheticMappingProposal(
      String authorization, String id, String captureId, String proposalId) {
    return send(objectivesRestClient.get()
        .uri(uri -> uri.path("/v1/objective-imports/{id}/sample-captures/{captureId}/mapping-proposals/{proposalId}")
            .build(id, captureId, proposalId)), authorization);
  }

  ResponseEntity<String> reviewSyntheticMapping(String authorization, String id, String captureId,
      String proposalId, String idempotencyKey, String requestBody) {
    var request = objectivesRestClient.post()
        .uri(uri -> uri.path(
            "/v1/objective-imports/{id}/sample-captures/{captureId}/mapping-proposals/{proposalId}/reviews")
            .build(id, captureId, proposalId))
        .contentType(MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
    request.body(requestBody);
    return send(request, authorization);
  }

  ResponseEntity<String> listSyntheticMappingReviews(
      String authorization, String id, String captureId, String proposalId) {
    return send(objectivesRestClient.get()
        .uri(uri -> uri.path(
            "/v1/objective-imports/{id}/sample-captures/{captureId}/mapping-proposals/{proposalId}/reviews")
            .build(id, captureId, proposalId)), authorization);
  }

  ResponseEntity<String> getSyntheticMappingReview(
      String authorization, String id, String captureId, String proposalId, String reviewId) {
    return send(objectivesRestClient.get()
        .uri(uri -> uri.path(
            "/v1/objective-imports/{id}/sample-captures/{captureId}/mapping-proposals/{proposalId}/reviews/{reviewId}")
            .build(id, captureId, proposalId, reviewId)), authorization);
  }

  ResponseEntity<String> createSyntheticCandidate(String authorization, String id, String captureId,
      String proposalId, String reviewId, String idempotencyKey, String requestBody) {
    var request = objectivesRestClient.post()
        .uri(uri -> uri.path("/v1/objective-imports/{id}/sample-captures/{captureId}/mapping-proposals/"
            + "{proposalId}/reviews/{reviewId}/synthetic-candidates").build(id, captureId, proposalId, reviewId))
        .contentType(MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
    request.body(requestBody);
    return send(request, authorization);
  }

  ResponseEntity<String> getSyntheticCandidate(String authorization, String id, String captureId,
      String proposalId, String reviewId, String candidateId) {
    return send(objectivesRestClient.get()
        .uri(uri -> uri.path("/v1/objective-imports/{id}/sample-captures/{captureId}/mapping-proposals/"
            + "{proposalId}/reviews/{reviewId}/synthetic-candidates/{candidateId}")
            .build(id, captureId, proposalId, reviewId, candidateId)), authorization);
  }

  ResponseEntity<String> listSyntheticCandidates(String authorization, String id, String captureId,
      String proposalId, String reviewId) {
    return send(objectivesRestClient.get()
        .uri(uri -> uri.path("/v1/objective-imports/{id}/sample-captures/{captureId}/mapping-proposals/"
            + "{proposalId}/reviews/{reviewId}/synthetic-candidates").build(id, captureId, proposalId, reviewId)),
        authorization);
  }

  ResponseEntity<String> listImportSampleCaptures(String authorization, String id, int pageSize, String cursor) {
    return send(objectivesRestClient.get().uri(uri -> {
      uri.path("/v1/objective-imports/{id}/sample-captures").queryParam("pageSize", pageSize);
      if (cursor != null && !cursor.isBlank()) uri.queryParam("cursor", cursor);
      return uri.build(id);
    }), authorization);
  }

  ResponseEntity<String> listImportPreviews(String authorization, int pageSize, String cursor) {
    return send(objectivesRestClient.get().uri(uriBuilder -> {
      uriBuilder.path("/v1/objective-imports").queryParam("pageSize", pageSize);
      if (cursor != null && !cursor.isBlank()) uriBuilder.queryParam("cursor", cursor);
      return uriBuilder.build();
    }), authorization);
  }

  ResponseEntity<String> transition(String authorization, String id, String action, String requestBody) {
    return sendBody(HttpMethod.POST, "/v1/objectives/{id}:" + action, id, authorization, requestBody);
  }

  ResponseEntity<String> submitIdempotent(String authorization, String id, String key, String body) {
    return sendBody(HttpMethod.POST, "/v1/objectives/{id}:idempotent-submit", id, authorization, body, key);
  }

  ResponseEntity<String> decideIdempotent(String authorization, String id, String key, String body) {
    return sendBody(HttpMethod.POST, "/v1/objectives/{id}:idempotent-decide", id, authorization, body, key);
  }

  ResponseEntity<String> activateIdempotent(String authorization, String id, String key, String body) {
    return sendBody(HttpMethod.POST, "/v1/objectives/{id}:idempotent-activate", id, authorization, body, key);
  }

  ResponseEntity<String> setConstraint(String authorization, String metric, String requestBody) {
    return sendBody(HttpMethod.PUT, "/v1/organization-constraints/{id}", metric, authorization, requestBody);
  }

  private ResponseEntity<String> sendBody(
      HttpMethod method, String path, String variable, String authorization, String requestBody) {
    return sendBody(method, path, variable, authorization, requestBody, null);
  }

  private ResponseEntity<String> sendBody(
      HttpMethod method,
      String path,
      String variable,
      String authorization,
      String requestBody,
      String idempotencyKey) {
    RestClient.RequestBodySpec request =
        objectivesRestClient
            .method(method)
            .uri(
                uriBuilder ->
                    variable == null ? uriBuilder.path(path).build() : uriBuilder.path(path).build(variable));
    request.contentType(MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    request.body(requestBody);
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
