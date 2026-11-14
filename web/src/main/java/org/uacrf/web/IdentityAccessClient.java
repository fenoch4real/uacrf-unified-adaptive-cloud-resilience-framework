package org.uacrf.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class IdentityAccessClient {
  private final RestClient restClient;

  IdentityAccessClient(@Qualifier("experienceRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  MeAccess currentAccess(String accessToken) {
    return currentAccess(accessToken, null, null);
  }

  MeAccess currentAccess(String accessToken, String tenantId, String environmentId) {
    RestClient.RequestHeadersSpec<?> request =
        restClient
            .get()
            .uri(
                uriBuilder -> {
                  var uri =
                      uriBuilder.path("/v1/me/access");
                  if (tenantId != null && environmentId != null) {
                    uri.queryParam("tenant_id", tenantId);
                    uri.queryParam("environment_id", environmentId);
                  }
                  return uri.build();
                })
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
    MeAccess response =
        request.retrieve().body(MeAccess.class);
    if (response == null) {
      throw new IllegalStateException("UX01 returned an empty current-access response.");
    }
    return response;
  }

  CurrentContextList currentContexts(String accessToken) {
    return currentContexts(accessToken, null);
  }

  CurrentContextList currentContexts(String accessToken, String cursor) {
    CurrentContextList response =
        restClient
            .get()
            .uri(
                uriBuilder -> {
                  var uri = uriBuilder.path("/v1/me/contexts").queryParam("limit", 25);
                  if (cursor != null && !cursor.isBlank()) {
                    uri.queryParam("cursor", cursor);
                  }
                  return uri.build();
                })
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .retrieve()
            .body(CurrentContextList.class);
    if (response == null) {
      throw new IllegalStateException("UX01 returned an empty current-context response.");
    }
    return response;
  }

  OwnerPage ownerCandidates(
      String accessToken, BrowserContext context, String objectKind, String cursor) {
    return ownerCandidates(accessToken, context, objectKind, null, cursor);
  }

  OwnerPage ownerCandidates(
      String accessToken,
      BrowserContext context,
      String objectKind,
      String objectId,
      String cursor) {
    OwnerPage response =
        restClient
            .get()
            .uri(
                uriBuilder -> {
                  var uri =
                      uriBuilder
                          .path("/v1/owner-candidates")
                          .queryParam("object_kind", objectKind)
                          .queryParam("limit", 100)
                          .queryParam("tenant_id", context.tenantId())
                          .queryParam("environment_id", context.environmentId());
                  if (objectId != null && !objectId.isBlank()) {
                    uri.queryParam("object_id", objectId);
                  }
                  if (cursor != null && !cursor.isBlank()) {
                    uri.queryParam("cursor", cursor);
                  }
                  return uri.build();
                })
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .retrieve()
            .body(OwnerPage.class);
    if (response == null || response.items() == null) {
      throw new IllegalStateException("UX01 returned an empty owner-candidate page.");
    }
    return response;
  }

  PrincipalPage listPrincipals(String accessToken, BrowserContext context, String cursor) {
    PrincipalPage response =
        restClient
            .get()
            .uri(
                uriBuilder -> {
                  var uri =
                      uriBuilder
                          .path("/v1/principals")
                          .queryParam("limit", 25)
                          .queryParam("tenant_id", context.tenantId())
                          .queryParam("environment_id", context.environmentId());
                  if (cursor != null && !cursor.isBlank()) {
                    uri.queryParam("cursor", cursor);
                  }
                  return uri.build();
                })
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .retrieve()
            .body(PrincipalPage.class);
    if (response == null
        || response.items() == null
        || response.items().stream()
            .anyMatch(
                principal ->
                    principal == null
                        || principal.id() == null
                    || principal.id().isBlank()
                        || principal.displayName() == null
                    || principal.displayName().isBlank()
                    || principal.kind() == null
                        || !List.of("HUMAN", "SYSTEM", "AGENT").contains(principal.kind())
                        || principal.revision() < 1
                        || (principal.ownerEligible()
                            && (!"HUMAN".equals(principal.kind()) || !principal.enabled())))) {
      throw new IllegalStateException("UX01 returned an incomplete principal page.");
    }
    return response;
  }

  MembershipPage listMemberships(String accessToken, BrowserContext context, String cursor) {
    MembershipPage response =
        restClient
            .get()
            .uri(
                uriBuilder -> {
                  var uri =
                      uriBuilder
                          .path("/v1/memberships")
                          .queryParam("limit", 25)
                          .queryParam("tenant_id", context.tenantId())
                          .queryParam("environment_id", context.environmentId());
                  if (cursor != null && !cursor.isBlank()) {
                    uri.queryParam("cursor", cursor);
                  }
                  return uri.build();
                })
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .retrieve()
            .body(MembershipPage.class);
    if (response == null
        || response.items() == null
        || response.items().stream()
            .anyMatch(
                membership ->
                    membership == null
                        || membership.id() == null
                        || membership.principalId() == null
                        || membership.capability() == null
                        || membership.serviceScope() == null
                        || membership.expiresAt() == null
                        || membership.revision() < 1)) {
      throw new IllegalStateException("UX01 returned an incomplete membership page.");
    }
    return response;
  }

  MembershipMutationReceipt revokeMembership(
      String accessToken,
      BrowserContext context,
      String membershipId,
      long expectedRevision,
      String reason,
      String idempotencyKey) {
    MembershipMutationReceipt response =
        restClient
            .post()
            .uri(
                uriBuilder ->
                    uriBuilder
                        .path("/v1/memberships/{id}:revoke")
                        .queryParam("tenant_id", context.tenantId())
                        .queryParam("environment_id", context.environmentId())
                        .build(membershipId))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .header("Idempotency-Key", idempotencyKey)
            .body(new MembershipRevocationRequest(expectedRevision, reason))
            .retrieve()
            .body(MembershipMutationReceipt.class);
    if (response == null
        || !membershipId.equals(response.id())
        || response.revision() <= expectedRevision
        || !List.of("REVOKED", "REPLAYED").contains(response.result())
        || !"NONE".equals(response.runtimeEffect())) {
      throw new IllegalStateException("UX01 returned an invalid membership revocation receipt.");
    }
    return response;
  }

  Delegation createDelegation(
      String accessToken,
      String audience,
      String serviceId,
      List<String> capabilityIds,
      String tenantId,
      String environmentId) {
    Delegation response =
        restClient
            .post()
            .uri("/internal/v1/delegations")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .body(
                new DelegationRequest(
                    audience, serviceId, capabilityIds, tenantId, environmentId))
            .retrieve()
            .body(Delegation.class);
    if (response == null
        || response.accessToken() == null
        || response.accessToken().isBlank()
        || !"Bearer".equals(response.tokenType())
        || response.expiresIn() < 1) {
      throw new IllegalStateException("UX01 returned an empty delegation response.");
    }
    return response;
  }

  record MeAccess(
      @JsonProperty("principal_id") String principalId,
      @JsonProperty("display_name") String displayName,
      String kind,
      @JsonProperty("service_id") String serviceId,
      String scope,
      @JsonProperty("capability_ids") List<String> capabilityIds) {}

  record CurrentContextList(
      @JsonProperty("items") List<CurrentContext> items,
      @JsonProperty("next_cursor") String nextCursor) {}

  record CurrentContext(
      @JsonProperty("tenant_id") String tenantId,
      @JsonProperty("tenant_name") String tenantName,
      @JsonProperty("environment_id") String environmentId,
      @JsonProperty("environment_name") String environmentName) {}

  record OwnerPage(
      List<OwnerCandidate> items, @JsonProperty("next_cursor") String nextCursor) {}

  record OwnerCandidate(
      String id,
      @JsonProperty("display_name") String displayName,
      String kind,
      boolean enabled,
      @JsonProperty("owner_eligible") boolean ownerEligible,
      long revision) {}

  record PrincipalPage(
      @JsonProperty("items") List<Principal> items,
      @JsonProperty("next_cursor") String nextCursor) {}

  record Principal(
      String id,
      @JsonProperty("display_name") String displayName,
      String kind,
      boolean enabled,
      @JsonProperty("owner_eligible") boolean ownerEligible,
      long revision) {}

  record MembershipPage(
      @JsonProperty("items") List<Membership> items,
      @JsonProperty("next_cursor") String nextCursor) {}

  record Membership(
      String id,
      @JsonProperty("principal_id") String principalId,
      String capability,
      @JsonProperty("service_scope") String serviceScope,
      @JsonProperty("expires_at") Instant expiresAt,
      long revision) {}

  record MembershipRevocationRequest(
      @JsonProperty("expected_revision") long expectedRevision, String reason) {}

  record MembershipMutationReceipt(
      String id, long revision, String result, @JsonProperty("runtime_effect") String runtimeEffect) {}

  record DelegationRequest(
      String audience,
      @JsonProperty("service_id") String serviceId,
      @JsonProperty("capability_ids") List<String> capabilityIds,
      @JsonProperty("tenant_id") String tenantId,
      @JsonProperty("environment_id") String environmentId) {}

  record Delegation(
      @JsonProperty("access_token") String accessToken,
      @JsonProperty("token_type") String tokenType,
      @JsonProperty("expires_in") int expiresIn) {}
}
