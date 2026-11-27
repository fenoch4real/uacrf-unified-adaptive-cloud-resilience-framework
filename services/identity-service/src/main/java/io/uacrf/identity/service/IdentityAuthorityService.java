package io.uacrf.identity.service;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.uacrf.crypto.CanonicalJson;
import io.uacrf.crypto.EnvelopeSigner;
import io.uacrf.identity.api.IdentityDomainException;
import io.uacrf.identity.api.IdentityDtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Enforces S01 identity admission, two-person grant separation of duties, last-admin safety,
 * and 30-second audience-bound RS256 delegation tokens.
 */
@Service
public class IdentityAuthorityService {

    private static final Set<String> HUMAN_ONLY_GLOBAL_CAPABILITIES = Set.of(
            "owner.eligible",
            "identity.owner.select",
            "advisory.run.create",
            "objectives.objective.review",
            "assessment.learning.review"
    );

    private final IdentityRepository repository;
    private final RSAKey signingKey;
    private final Clock clock;

    public IdentityAuthorityService(IdentityRepository repository) {
        this.repository = repository;
        this.signingKey = EnvelopeSigner.generateEphemeralRsaKey("uacrf-s01-k1");
        this.clock = Clock.systemUTC();
    }

    public Map<String, Object> jwks() {
        return new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }

    public List<EnvironmentView> listEnvironments(UUID tenantId) {
        return repository.listEnvironments(tenantId);
    }

    public List<PrincipalView> listPrincipals(UUID tenantId, UUID environmentId) {
        return repository.listPrincipals(tenantId, environmentId);
    }

    public List<PrincipalView> listEligibleOwners(UUID tenantId, UUID environmentId) {
        return repository.listEligibleOwners(tenantId, environmentId, clock.instant());
    }

    public List<MembershipView> listMemberships(UUID tenantId, UUID environmentId) {
        return repository.listMemberships(tenantId, environmentId);
    }

    @Transactional
    public PrincipalView admitPrincipal(UUID actorId, String idempotencyKey, AdmitPrincipalRequest req) {
        String digest = CanonicalJson.digestObject(req);
        var existing = repository.findIdempotency(req.tenantId(), req.environmentId(), actorId, "ADMIT_PRINCIPAL", idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), digest, PrincipalView.class);
        }
        PrincipalView created = repository.insertPrincipal(req, actorId);
        repository.saveIdempotency(req.tenantId(), req.environmentId(), actorId, "ADMIT_PRINCIPAL",
                idempotencyKey, digest, HttpStatus.CREATED.value(), created);
        return created;
    }

    @Transactional
    public PrincipalView disablePrincipal(UUID actorId, UUID principalId, DisablePrincipalRequest req) {
        PrincipalView target = repository.findPrincipalForUpdate(req.tenantId(), req.environmentId(), principalId)
                .orElseThrow(() -> new IdentityDomainException(HttpStatus.NOT_FOUND, "PRINCIPAL_NOT_FOUND", "Principal not found"));

        if (target.version() != req.expectedVersion()) {
            throw new IdentityDomainException(HttpStatus.CONFLICT, "STALE_REVISION", "Expected principal revision mismatch");
        }
        long remainingAdmins = repository.countActiveCapabilityHolders(
                req.tenantId(), req.environmentId(), "identity.membership.manage", principalId, clock.instant());
        List<MembershipView> targetMemberships = repository.findActiveMembershipsForPrincipal(
                req.tenantId(), req.environmentId(), principalId, "GLOBAL", clock.instant());
        boolean isMembershipAdmin = targetMemberships.stream()
                .anyMatch(m -> "identity.membership.manage".equals(m.capability()));
        if (isMembershipAdmin && remainingAdmins == 0) {
            throw new IdentityDomainException(HttpStatus.CONFLICT, "LAST_ACTIVE_ADMIN",
                    "Cannot disable the last active membership administrator in this context");
        }
        return repository.disablePrincipal(target, actorId, req.reason());
    }

    @Transactional
    public GrantDecisionView createGrantDecision(UUID approverId, CreateGrantDecisionRequest req) {
        if (approverId.equals(req.targetPrincipalId())) {
            throw new IdentityDomainException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_PROHIBITED",
                    "Approver cannot decide a grant for themselves");
        }
        PrincipalView approver = repository.findPrincipal(req.tenantId(), req.environmentId(), approverId)
                .orElseThrow(() -> new IdentityDomainException(HttpStatus.FORBIDDEN, "APPROVER_NOT_FOUND", "Approver not found"));
        if (!"HUMAN".equals(approver.principalType()) || !"ENABLED".equals(approver.status())) {
            throw new IdentityDomainException(HttpStatus.FORBIDDEN, "APPROVER_INELIGIBLE",
                    "Only an enabled HUMAN principal may approve capability grants");
        }
        PrincipalView target = repository.findPrincipal(req.tenantId(), req.environmentId(), req.targetPrincipalId())
                .orElseThrow(() -> new IdentityDomainException(HttpStatus.NOT_FOUND, "TARGET_NOT_FOUND", "Target principal not found"));
        if (!"ENABLED".equals(target.status())) {
            throw new IdentityDomainException(HttpStatus.CONFLICT, "TARGET_DISABLED", "Disabled principal cannot receive grants");
        }
        if (HUMAN_ONLY_GLOBAL_CAPABILITIES.contains(req.capability())) {
            if (!"HUMAN".equals(target.principalType()) || !"GLOBAL".equals(req.serviceScope())) {
                throw new IdentityDomainException(HttpStatus.BAD_REQUEST, "HUMAN_GLOBAL_ONLY",
                        req.capability() + " is restricted to HUMAN principals at GLOBAL scope");
            }
        }
        if (!req.expiresAt().isAfter(clock.instant())) {
            throw new IdentityDomainException(HttpStatus.BAD_REQUEST, "INVALID_EXPIRY", "Decision expiry must be in the future");
        }
        return repository.insertGrantDecision(req, approverId);
    }

    @Transactional
    public MembershipView createMembership(UUID managerId, String idempotencyKey, CreateMembershipRequest req) {
        if (managerId.equals(req.principalId())) {
            throw new IdentityDomainException(HttpStatus.FORBIDDEN, "SELF_GRANT_PROHIBITED",
                    "Membership manager cannot grant capabilities to themselves");
        }
        String digest = CanonicalJson.digestObject(req);
        var existing = repository.findIdempotency(req.tenantId(), req.environmentId(), managerId, "CREATE_MEMBERSHIP", idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), digest, MembershipView.class);
        }

        PrincipalView recipient = repository.findPrincipalForUpdate(req.tenantId(), req.environmentId(), req.principalId())
                .orElseThrow(() -> new IdentityDomainException(HttpStatus.NOT_FOUND, "PRINCIPAL_NOT_FOUND", "Recipient principal not found"));
        if (!"ENABLED".equals(recipient.status())) {
            throw new IdentityDomainException(HttpStatus.CONFLICT, "PRINCIPAL_DISABLED", "Cannot grant membership to disabled principal");
        }

        GrantDecisionView decision = repository.lockGrantDecision(req.tenantId(), req.environmentId(), req.grantDecisionId())
                .orElseThrow(() -> new IdentityDomainException(HttpStatus.NOT_FOUND, "GRANT_DECISION_NOT_FOUND", "Grant decision not found"));

        Instant now = clock.instant();
        if (!"APPROVE".equals(decision.decision())) {
            throw new IdentityDomainException(HttpStatus.FORBIDDEN, "DECISION_NOT_APPROVED", "Grant decision was rejected");
        }
        if (decision.consumedAt() != null) {
            throw new IdentityDomainException(HttpStatus.CONFLICT, "GRANT_DECISION_CONSUMED", "Grant decision already consumed");
        }
        if (!decision.expiresAt().isAfter(now)) {
            throw new IdentityDomainException(HttpStatus.CONFLICT, "GRANT_DECISION_EXPIRED", "Grant decision has expired");
        }
        if (managerId.equals(decision.approverPrincipalId())) {
            throw new IdentityDomainException(HttpStatus.FORBIDDEN, "SEPARATION_OF_DUTIES",
                    "Membership manager must be distinct from the grant approver");
        }
        if (!decision.targetPrincipalId().equals(req.principalId())
                || !decision.capability().equals(req.capability())
                || !decision.serviceScope().equals(req.serviceScope())) {
            throw new IdentityDomainException(HttpStatus.BAD_REQUEST, "GRANT_DECISION_MISMATCH",
                    "Membership request does not match approved grant decision");
        }

        MembershipView created = repository.consumeDecisionAndInsertMembership(req, managerId, now);
        repository.saveIdempotency(req.tenantId(), req.environmentId(), managerId, "CREATE_MEMBERSHIP",
                idempotencyKey, digest, HttpStatus.CREATED.value(), created);
        return created;
    }

    @Transactional
    public void revokeMembership(UUID actorId, UUID tenantId, UUID environmentId, UUID membershipId, String reason) {
        repository.revokeMembership(tenantId, environmentId, membershipId, actorId, reason, clock.instant());
    }

    public DelegationExchangeResponse exchangeDelegation(DelegationExchangeRequest req) {
        Instant now = clock.instant();
        PrincipalView actor = repository.findPrincipal(req.tenantId(), req.environmentId(), req.actorPrincipalId())
                .orElseThrow(() -> new IdentityDomainException(HttpStatus.FORBIDDEN, "ACTOR_NOT_FOUND", "Principal not found"));
        if (!"ENABLED".equals(actor.status())) {
            throw new IdentityDomainException(HttpStatus.FORBIDDEN, "ACTOR_DISABLED", "Principal is disabled");
        }

        List<MembershipView> active = repository.findActiveMembershipsForPrincipal(
                req.tenantId(), req.environmentId(), req.actorPrincipalId(), req.serviceScope(), now);
        Set<String> activeCapabilities = active.stream().map(MembershipView::capability).collect(Collectors.toSet());

        List<String> missing = req.requestedCapabilities().stream()
                .filter(cap -> !activeCapabilities.contains(cap))
                .toList();
        if (!missing.isEmpty()) {
            throw new IdentityDomainException(HttpStatus.FORBIDDEN, "MISSING_CAPABILITIES",
                    "Principal lacks required capabilities in scope: " + String.join(", ", missing));
        }

        Instant maxMembershipExpiry = active.stream()
                .filter(m -> req.requestedCapabilities().contains(m.capability()))
                .map(MembershipView::expiresAt)
                .min(Instant::compareTo)
                .orElse(now.plusSeconds(30));

        Instant expiresAt = now.plusSeconds(30).isBefore(maxMembershipExpiry)
                ? now.plusSeconds(30)
                : maxMembershipExpiry;

        EnvelopeSigner signer = new EnvelopeSigner(signingKey, clock);
        String token = signer.sign(
                "uacrf-s01",
                req.audience(),
                req.actorPrincipalId().toString(),
                UUID.randomUUID().toString(),
                expiresAt,
                Map.of(
                        "tenantId", req.tenantId().toString(),
                        "environmentId", req.environmentId().toString(),
                        "actorType", actor.principalType(),
                        "serviceScope", req.serviceScope(),
                        "capabilities", req.requestedCapabilities()
                )
        );
        return new DelegationExchangeResponse(token, req.audience(), signingKey.getKeyID(), expiresAt, req.requestedCapabilities());
    }

    private <T> T replayOrConflict(IdentityRepository.StoredIdempotency stored, String requestDigest, Class<T> type) {
        if (!stored.requestDigest().equals(requestDigest)) {
            throw new IdentityDomainException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "Idempotency key reused with a different request payload");
        }
        return CanonicalJson.fromJson(stored.responseJson(), type);
    }
}
