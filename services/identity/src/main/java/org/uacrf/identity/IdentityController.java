package org.uacrf.identity;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class IdentityController {
  private final IdentityAccessService identity;

  IdentityController(IdentityAccessService identity) {
    this.identity = identity;
  }

  @GetMapping("/v1/identity-providers")
  IdentityResponses.IdentityProviderList identityProviders(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId) {
    return identity.listIdentityProviders(jwt, tenantId, environmentId);
  }

  @GetMapping("/v1/principals")
  IdentityResponses.PrincipalPage principals(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "25") int limit,
      @RequestParam(required = false) String cursor,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId) {
    return identity.listPrincipals(jwt, limit, cursor, tenantId, environmentId);
  }

  @GetMapping("/v1/memberships")
  IdentityResponses.MembershipPage memberships(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "25") int limit,
      @RequestParam(required = false) String cursor,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId) {
    return identity.listMemberships(jwt, limit, cursor, tenantId, environmentId);
  }

  @GetMapping("/v1/me/contexts")
  IdentityResponses.CurrentContextList currentContexts(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "25") int limit,
      @RequestParam(required = false) String cursor) {
    return identity.getCurrentContexts(jwt, limit, cursor);
  }

  @PostMapping("/v1/memberships")
  ResponseEntity<IdentityResponses.MutationReceipt> createMembership(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody IdentityRequests.CreateMembership request) {
    return created(identity.createMembership(jwt, request, idempotencyKey, tenantId, environmentId));
  }

  @PostMapping("/v1/principals")
  ResponseEntity<IdentityResponses.MutationReceipt> admitPrincipal(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody IdentityRequests.AdmitPrincipal request) {
    return created(identity.admitPrincipal(jwt, request, idempotencyKey, tenantId, environmentId));
  }

  @PostMapping("/v1/grant-decisions")
  ResponseEntity<IdentityResponses.MutationReceipt> createGrantDecision(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId,
      @Valid @RequestBody IdentityRequests.CreateGrantDecision request) {
    return created(identity.createGrantDecision(jwt, request, tenantId, environmentId));
  }

  private static ResponseEntity<IdentityResponses.MutationReceipt> created(
      IdentityResponses.MutationReceipt receipt) {
    return ResponseEntity.status("REPLAYED".equals(receipt.result()) ? 200 : 201).body(receipt);
  }

  @PostMapping("/v1/memberships/{id}:revoke")
  IdentityResponses.MutationReceipt revokeMembership(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody IdentityRequests.RevokeMembership request) {
    return identity.revokeMembership(jwt, id, request, idempotencyKey, tenantId, environmentId);
  }

  @PostMapping("/v1/principals/{id}:set-status")
  IdentityResponses.MutationReceipt setPrincipalStatus(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody IdentityRequests.SetPrincipalStatus request) {
    return identity.setPrincipalStatus(jwt, id, request, idempotencyKey);
  }

  @GetMapping("/v1/me/access")
  IdentityResponses.MeAccess currentAccess(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(name = "service_id", required = false) String serviceId,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId) {
    return identity.getCurrentAccess(jwt, serviceId, tenantId, environmentId);
  }

  @GetMapping("/v1/owner-candidates")
  IdentityResponses.OwnerPage ownerCandidates(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(name = "object_kind") String objectKind,
      @RequestParam(name = "object_id", required = false) String objectId,
      @RequestParam(defaultValue = "25") int limit,
      @RequestParam(required = false) String cursor,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId) {
    return identity.listOwnerCandidates(
        jwt, objectKind, objectId, limit, cursor, tenantId, environmentId);
  }

  @PostMapping("/internal/v1/delegations")
  IdentityResponses.Delegation createDelegation(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody IdentityRequests.Delegation request) {
    return identity.createDelegation(jwt, request);
  }
}
