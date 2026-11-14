package org.uacrf.edge;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;

@RestController
class IdentityAccessRouteController {
  private final IdentityAccessRouteClient identity;

  IdentityAccessRouteController(IdentityAccessRouteClient identity) {
    this.identity = identity;
  }

  @GetMapping("/v1/me/access")
  ResponseEntity<String> currentAccess(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(name = "service_id", required = false) String serviceId,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId) {
    try {
      return identity.currentAccess(authorization, serviceId, tenantId, environmentId);
    } catch (ResourceAccessException error) {
      return unavailableIdentity();
    }
  }

  @GetMapping("/v1/identity-providers")
  ResponseEntity<String> identityProviders(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId) {
    try {
      return identity.identityProviders(authorization, tenantId, environmentId);
    } catch (ResourceAccessException error) {
      return unavailableIdentity();
    }
  }

  @GetMapping("/v1/principals")
  ResponseEntity<String> principals(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(defaultValue = "25") int limit,
      @RequestParam(required = false) String cursor,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId) {
    try {
      return identity.principals(authorization, limit, cursor, tenantId, environmentId);
    } catch (ResourceAccessException error) {
      return unavailableIdentity();
    }
  }

  @GetMapping("/v1/memberships")
  ResponseEntity<String> memberships(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(defaultValue = "25") int limit,
      @RequestParam(required = false) String cursor,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId) {
    try {
      return identity.memberships(authorization, limit, cursor, tenantId, environmentId);
    } catch (ResourceAccessException error) {
      return unavailableIdentity();
    }
  }

  @GetMapping("/v1/me/contexts")
  ResponseEntity<String> currentContexts(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(defaultValue = "25") int limit,
      @RequestParam(required = false) String cursor) {
    try {
      return identity.currentContexts(authorization, limit, cursor);
    } catch (ResourceAccessException error) {
      return unavailableIdentity();
    }
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
    try {
      return identity.ownerCandidates(
          authorization, objectKind, objectId, limit, cursor, tenantId, environmentId);
    } catch (ResourceAccessException error) {
      return unavailableIdentity();
    }
  }

  @PostMapping("/v1/memberships")
  ResponseEntity<String> createMembership(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody String requestBody) {
    try {
      return identity.createMembership(
          authorization, tenantId, environmentId, idempotencyKey, requestBody);
    } catch (ResourceAccessException error) {
      return unavailableIdentity();
    }
  }

  @PostMapping("/v1/principals")
  ResponseEntity<String> admitPrincipal(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody String requestBody) {
    try {
      return identity.admitPrincipal(
          authorization, tenantId, environmentId, idempotencyKey, requestBody);
    } catch (ResourceAccessException error) {
      return unavailableIdentity();
    }
  }

  @PostMapping("/v1/grant-decisions")
  ResponseEntity<String> createGrantDecision(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId,
      @RequestBody String requestBody) {
    try {
      return identity.createGrantDecision(authorization, tenantId, environmentId, requestBody);
    } catch (ResourceAccessException error) {
      return unavailableIdentity();
    }
  }

  @PostMapping("/v1/memberships/{id}:revoke")
  ResponseEntity<String> revokeMembership(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestParam(name = "tenant_id", required = false) String tenantId,
      @RequestParam(name = "environment_id", required = false) String environmentId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody String requestBody) {
    try {
      return identity.revokeMembership(
          authorization, id, tenantId, environmentId, idempotencyKey, requestBody);
    } catch (ResourceAccessException error) {
      return unavailableIdentity();
    }
  }

  @PostMapping("/v1/principals/{id}:set-status")
  ResponseEntity<String> setPrincipalStatus(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody String requestBody) {
    try {
      return identity.setPrincipalStatus(authorization, id, idempotencyKey, requestBody);
    } catch (ResourceAccessException error) {
      return unavailableIdentity();
    }
  }

  private static ResponseEntity<String> unavailableIdentity() {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            "{\"code\":\"IDENTITY_UNAVAILABLE\","
                + "\"message\":\"Identity service is unavailable.\"}");
  }
}
