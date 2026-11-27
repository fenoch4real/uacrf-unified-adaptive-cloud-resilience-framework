package io.uacrf.identity.api;

import io.uacrf.identity.api.IdentityDtos.*;
import io.uacrf.identity.service.IdentityAuthorityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class IdentityController {

    private final IdentityAuthorityService service;

    public IdentityController(IdentityAuthorityService service) {
        this.service = service;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return service.jwks();
    }

    @GetMapping("/v1/tenants/{tenantId}/environments")
    public List<EnvironmentView> listEnvironments(@PathVariable UUID tenantId) {
        return service.listEnvironments(tenantId);
    }

    @GetMapping("/v1/principals")
    public List<PrincipalView> listPrincipals(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listPrincipals(tenantId, environmentId);
    }

    @PostMapping("/v1/principals")
    public ResponseEntity<PrincipalView> admitPrincipal(
            @RequestHeader("X-UACRF-Actor-Id") UUID actorId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AdmitPrincipalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.admitPrincipal(actorId, idempotencyKey, request));
    }

    @PostMapping("/v1/principals/{principalId}:disable")
    public PrincipalView disablePrincipal(
            @RequestHeader("X-UACRF-Actor-Id") UUID actorId,
            @PathVariable UUID principalId,
            @Valid @RequestBody DisablePrincipalRequest request) {
        return service.disablePrincipal(actorId, principalId, request);
    }

    @GetMapping("/v1/owner-candidates")
    public List<PrincipalView> listOwnerCandidates(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listEligibleOwners(tenantId, environmentId);
    }

    @PostMapping("/v1/grants/decisions")
    public ResponseEntity<GrantDecisionView> createGrantDecision(
            @RequestHeader("X-UACRF-Actor-Id") UUID approverId,
            @Valid @RequestBody CreateGrantDecisionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createGrantDecision(approverId, request));
    }

    @GetMapping("/v1/memberships")
    public List<MembershipView> listMemberships(@RequestParam UUID tenantId, @RequestParam UUID environmentId) {
        return service.listMemberships(tenantId, environmentId);
    }

    @PostMapping("/v1/memberships")
    public ResponseEntity<MembershipView> createMembership(
            @RequestHeader("X-UACRF-Actor-Id") UUID managerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateMembershipRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createMembership(managerId, idempotencyKey, request));
    }

    @PostMapping("/v1/memberships/{membershipId}:revoke")
    public ResponseEntity<Void> revokeMembership(
            @RequestHeader("X-UACRF-Actor-Id") UUID actorId,
            @RequestParam UUID tenantId,
            @RequestParam UUID environmentId,
            @PathVariable UUID membershipId,
            @RequestParam(defaultValue = "Administrative revocation") String reason) {
        service.revokeMembership(actorId, tenantId, environmentId, membershipId, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/delegations/exchange")
    public DelegationExchangeResponse exchangeDelegation(@Valid @RequestBody DelegationExchangeRequest request) {
        return service.exchangeDelegation(request);
    }
}
