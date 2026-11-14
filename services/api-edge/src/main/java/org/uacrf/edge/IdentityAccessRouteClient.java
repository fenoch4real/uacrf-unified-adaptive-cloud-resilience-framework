package org.uacrf.edge;

import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.util.UriBuilder;

@Component
class IdentityAccessRouteClient {
  private final RestClient identityRestClient;

  IdentityAccessRouteClient(@Qualifier("identityServiceRestClient") RestClient identityRestClient) {
    this.identityRestClient = identityRestClient;
  }

  ResponseEntity<String> currentAccess(
      String authorization, String serviceId, String tenantId, String environmentId) {
    return get(
        "/v1/me/access",
        authorization,
        uri -> {
          queryParam(uri, "service_id", serviceId);
          context(uri, tenantId, environmentId);
        });
  }

  ResponseEntity<String> identityProviders(
      String authorization, String tenantId, String environmentId) {
    return get(
        "/v1/identity-providers",
        authorization,
        uri -> context(uri, tenantId, environmentId));
  }

  ResponseEntity<String> principals(
      String authorization, int limit, String cursor, String tenantId, String environmentId) {
    return get(
        "/v1/principals",
        authorization,
        uri -> {
          uri.queryParam("limit", limit);
          queryParam(uri, "cursor", cursor);
          context(uri, tenantId, environmentId);
        });
  }

  ResponseEntity<String> memberships(
      String authorization, int limit, String cursor, String tenantId, String environmentId) {
    return get(
        "/v1/memberships",
        authorization,
        uri -> {
          uri.queryParam("limit", limit);
          queryParam(uri, "cursor", cursor);
          context(uri, tenantId, environmentId);
        });
  }

  ResponseEntity<String> currentContexts(String authorization, int limit, String cursor) {
    return get(
        "/v1/me/contexts",
        authorization,
        uri -> {
          uri.queryParam("limit", limit);
          queryParam(uri, "cursor", cursor);
        });
  }

  ResponseEntity<String> ownerCandidates(
      String authorization,
      String objectKind,
      String objectId,
      int limit,
      String cursor,
      String tenantId,
      String environmentId) {
    return get(
        "/v1/owner-candidates",
        authorization,
        uri -> {
          uri.queryParam("object_kind", objectKind);
          queryParam(uri, "object_id", objectId);
          uri.queryParam("limit", limit);
          queryParam(uri, "cursor", cursor);
          context(uri, tenantId, environmentId);
        });
  }

  ResponseEntity<String> createMembership(
      String authorization,
      String tenantId,
      String environmentId,
      String idempotencyKey,
      String requestBody) {
    return post("/v1/memberships", authorization, tenantId, environmentId, idempotencyKey, requestBody);
  }

  ResponseEntity<String> admitPrincipal(
      String authorization,
      String tenantId,
      String environmentId,
      String idempotencyKey,
      String requestBody) {
    return post("/v1/principals", authorization, tenantId, environmentId, idempotencyKey, requestBody);
  }

  ResponseEntity<String> createGrantDecision(
      String authorization, String tenantId, String environmentId, String requestBody) {
    return post("/v1/grant-decisions", authorization, tenantId, environmentId, null, requestBody);
  }

  private ResponseEntity<String> post(
      String path,
      String authorization,
      String tenantId,
      String environmentId,
      String idempotencyKey,
      String requestBody) {
    RestClient.RequestBodySpec request =
        identityRestClient
            .post()
            .uri(
                uriBuilder -> {
                  UriBuilder uri = uriBuilder.path(path);
                  context(uri, tenantId, environmentId);
                  return uri.build();
                });
    if (authorization != null) {
      request.header(HttpHeaders.AUTHORIZATION, authorization);
    }
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }

    return request
        .contentType(MediaType.APPLICATION_JSON)
        .body(requestBody)
        .exchange((clientRequest, response) -> toResponse(response));
  }

  ResponseEntity<String> revokeMembership(
      String authorization,
      String membershipId,
      String tenantId,
      String environmentId,
      String idempotencyKey,
      String requestBody) {
    RestClient.RequestBodySpec request =
        identityRestClient
            .post()
            .uri(
                uriBuilder -> {
                  UriBuilder uri = uriBuilder.path("/v1/memberships/{id}:revoke");
                  context(uri, tenantId, environmentId);
                  return uri.build(membershipId);
                });
    if (authorization != null) {
      request.header(HttpHeaders.AUTHORIZATION, authorization);
    }

    return request
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .body(requestBody)
        .exchange((clientRequest, response) -> toResponse(response));
  }

  ResponseEntity<String> setPrincipalStatus(
      String authorization, String principalId, String idempotencyKey, String requestBody) {
    RestClient.RequestBodySpec request =
        identityRestClient
            .post()
            .uri(
                uriBuilder ->
                    uriBuilder.path("/v1/principals/{id}:set-status").build(principalId));
    if (authorization != null) {
      request.header(HttpHeaders.AUTHORIZATION, authorization);
    }

    return request
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .body(requestBody)
        .exchange((clientRequest, response) -> toResponse(response));
  }

  private ResponseEntity<String> get(
      String path, String authorization, Consumer<UriBuilder> queryParameters) {
    RestClient.RequestHeadersSpec<?> request =
        identityRestClient
            .get()
            .uri(
                uriBuilder -> {
                  UriBuilder uri = uriBuilder.path(path);
                  queryParameters.accept(uri);
                  return uri.build();
                });
    if (authorization != null) {
      request.header(HttpHeaders.AUTHORIZATION, authorization);
    }
    return request.exchange((clientRequest, response) -> toResponse(response));
  }

  private ResponseEntity<String> toResponse(ConvertibleClientHttpResponse response)
      throws java.io.IOException {
    HttpHeaders headers = new HttpHeaders();
    headers.setCacheControl(CacheControl.noStore());
    if (response.getHeaders().getContentType() != null) {
      headers.setContentType(response.getHeaders().getContentType());
    }
    if (response.getHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE)) {
      headers.put(
          HttpHeaders.WWW_AUTHENTICATE,
          response.getHeaders().get(HttpHeaders.WWW_AUTHENTICATE));
    }
    return new ResponseEntity<>(response.bodyTo(String.class), headers, response.getStatusCode());
  }

  private static void context(UriBuilder uri, String tenantId, String environmentId) {
    if (tenantId != null && environmentId != null) {
      uri.queryParam("tenant_id", tenantId);
      uri.queryParam("environment_id", environmentId);
    }
  }

  private static void queryParam(UriBuilder uri, String name, String value) {
    if (value != null && !value.isBlank()) {
      uri.queryParam(name, value);
    }
  }
}
