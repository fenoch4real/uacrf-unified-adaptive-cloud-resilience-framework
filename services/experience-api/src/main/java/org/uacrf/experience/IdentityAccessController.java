package org.uacrf.experience;

import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;

@RestController
class IdentityAccessController {
  private final IdentityProxyClient identity;

  IdentityAccessController(IdentityProxyClient identity) {
    this.identity = identity;
  }

  @GetMapping("/v1/me/access")
  ResponseEntity<String> currentAccess(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId) {
    return forward(
        authorization, () -> identity.currentAccess(authorization, tenantId, environmentId));
  }

  @GetMapping("/v1/me/contexts")
  ResponseEntity<String> currentContexts(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(defaultValue = "25") int limit,
      @RequestParam(required = false) String cursor) {
    return forward(authorization, () -> identity.currentContexts(authorization, limit, cursor));
  }

  @GetMapping("/v1/identity-providers")
  ResponseEntity<String> identityProviders(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId) {
    return forward(
        authorization, () -> identity.identityProviders(authorization, tenantId, environmentId));
  }

  @GetMapping("/v1/principals")
  ResponseEntity<String> principals(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(defaultValue = "25") int limit,
      @RequestParam(required = false) String cursor,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId) {
    return forward(
        authorization, () -> identity.principals(authorization, limit, cursor, tenantId, environmentId));
  }

  @GetMapping("/v1/memberships")
  ResponseEntity<String> memberships(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(defaultValue = "25") int limit,
      @RequestParam(required = false) String cursor,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId) {
    return forward(
        authorization, () -> identity.memberships(authorization, limit, cursor, tenantId, environmentId));
  }

  @GetMapping("/v1/owner-candidates")
  ResponseEntity<String> ownerCandidates(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(name = "object_kind") String objectKind,
      @RequestParam(name = "object_id", required = false) String objectId,
      @RequestParam(defaultValue = "25") int limit,
      @RequestParam(required = false) String cursor,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId) {
    return forward(
        authorization,
        () ->
            identity.ownerCandidates(
                authorization, objectKind, objectId, limit, cursor, tenantId, environmentId));
  }

  @PostMapping("/v1/memberships/{id}:revoke")
  ResponseEntity<String> revokeMembership(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody String requestBody) {
    return forward(
        authorization,
        () ->
            identity.revokeMembership(
                authorization,
                id,
                tenantId,
                environmentId,
                idempotencyKey,
                requestBody));
  }

  @PostMapping("/v1/principals/{id}:set-status")
  ResponseEntity<String> setPrincipalStatus(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody String requestBody) {
    return forward(
        authorization,
        () ->
            identity.setPrincipalStatus(
                authorization, id, idempotencyKey, requestBody));
  }

  @PostMapping("/internal/v1/delegations")
  ResponseEntity<String> createDelegation(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody String requestBody) {
    return forward(authorization, () -> identity.createDelegation(authorization, requestBody));
  }

  private ResponseEntity<String> forward(
      String authorization, Supplier<ResponseEntity<String>> operation) {
    if (!isBearer(authorization)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .cacheControl(CacheControl.noStore())
          .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
          .build();
    }

    try {
      return operation.get();
    } catch (ResourceAccessException error) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .cacheControl(CacheControl.noStore())
          .contentType(MediaType.APPLICATION_JSON)
          .body(
              "{\"code\":\"IDENTITY_UNAVAILABLE\","
                  + "\"message\":\"Identity service is unavailable.\"}");
    }
  }

  private static boolean isBearer(String authorization) {
    return authorization != null
        && authorization.length() > 7
        && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
        && !authorization.substring(7).isBlank();
  }
}
