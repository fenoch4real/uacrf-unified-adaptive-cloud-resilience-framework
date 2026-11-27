package io.uacrf.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class IdentityDtos {

    private IdentityDtos() {
    }

    public record EnvironmentView(
            UUID environmentId,
            UUID tenantId,
            String slug,
            String name,
            String envClass,
            boolean production,
            String authorityTier,
            String status
    ) {
    }

    public record AdmitPrincipalRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String principalType,
            @NotBlank String issuer,
            @NotBlank String externalSubject,
            @NotBlank String displayName,
            String email
    ) {
    }

    public record PrincipalView(
            UUID principalId,
            UUID tenantId,
            UUID environmentId,
            String principalType,
            String issuer,
            String externalSubject,
            String displayName,
            String email,
            String status,
            long version,
            String runtimeEffect
    ) {
    }

    public record DisablePrincipalRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            long expectedVersion,
            @NotBlank String reason
    ) {
    }

    public record CreateGrantDecisionRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotNull UUID targetPrincipalId,
            @NotBlank String capability,
            @NotBlank String serviceScope,
            @NotBlank String decision,
            @NotBlank String rationale,
            @NotNull Instant expiresAt
    ) {
    }

    public record GrantDecisionView(
            UUID grantDecisionId,
            UUID tenantId,
            UUID environmentId,
            UUID targetPrincipalId,
            String capability,
            String serviceScope,
            UUID approverPrincipalId,
            String decision,
            String rationale,
            Instant expiresAt,
            Instant consumedAt
    ) {
    }

    public record CreateMembershipRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotNull UUID principalId,
            @NotBlank String capability,
            @NotBlank String serviceScope,
            @NotNull UUID grantDecisionId,
            @NotNull Instant expiresAt
    ) {
    }

    public record MembershipView(
            UUID membershipId,
            UUID tenantId,
            UUID environmentId,
            UUID principalId,
            String capability,
            String serviceScope,
            UUID grantDecisionId,
            UUID grantedByPrincipalId,
            Instant expiresAt,
            Instant revokedAt,
            long version
    ) {
    }

    public record DelegationExchangeRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotNull UUID actorPrincipalId,
            @NotBlank String audience,
            @NotBlank String serviceScope,
            @NotNull List<String> requestedCapabilities
    ) {
    }

    public record DelegationExchangeResponse(
            String token,
            String audience,
            String keyId,
            Instant expiresAt,
            List<String> grantedCapabilities
    ) {
    }
}
