package org.uacrf.experience;

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
class IdentityProxyClient {
  private final RestClient restClient;
  private final RestClient identityDelegationRestClient;

  IdentityProxyClient(
      @Qualifier("identityServiceRestClient") RestClient restClient,
      @Qualifier("identityDelegationRestClient") RestClient identityDelegationRestClient) {
    this.restClient = restClient;
    this.identityDelegationRestClient = identityDelegationRestClient;
  }

  ResponseEntity<String> currentAccess(
      String authorization, String tenantId, String environmentId) {
    return get(
        "/v1/me/access",
        authorization,
        uri -> context(uri, tenantId, environmentId));
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

  ResponseEntity<String> revokeMembership(
      String authorization,
      String membershipId,
      String tenantId,
      String environmentId,
      String idempotencyKey,
      String requestBody) {
    return restClient
        .post()
        .uri(
            uriBuilder -> {
              var uri = uriBuilder.path("/v1/memberships/{id}:revoke");
              context(uri, tenantId, environmentId);
              return uri.build(membershipId);
            })
        .header(HttpHeaders.AUTHORIZATION, authorization)
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .body(requestBody)
        .exchange((request, response) -> toResponse(response));
  }

  ResponseEntity<String> setPrincipalStatus(
      String authorization, String principalId, String idempotencyKey, String requestBody) {
    return restClient
        .post()
        .uri(uriBuilder -> uriBuilder.path("/v1/principals/{id}:set-status").build(principalId))
        .header(HttpHeaders.AUTHORIZATION, authorization)
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .body(requestBody)
        .exchange((request, response) -> toResponse(response));
  }

  ResponseEntity<String> createDelegation(String authorization, String requestBody) {
    return identityDelegationRestClient
        .post()
        .uri("/internal/v1/delegations")
        .header(HttpHeaders.AUTHORIZATION, authorization)
        .contentType(MediaType.APPLICATION_JSON)
        .body(requestBody)
        .exchange((request, response) -> toResponse(response));
  }

  private ResponseEntity<String> get(
      String path, String authorization, Consumer<UriBuilder> queryParameters) {
    return restClient
        .get()
        .uri(
            uriBuilder -> {
              UriBuilder uri = uriBuilder.path(path);
              queryParameters.accept(uri);
              return uri.build();
            })
        .header(HttpHeaders.AUTHORIZATION, authorization)
            .exchange((request, response) -> toResponse(response));
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
