package io.uacrf.policy.api;

import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PolicyDtos {

    private PolicyDtos() {
    }

    public record ProposalDto(
            UUID proposalId,
            UUID tenantId,
            UUID environmentId,
            String serviceCanonicalId,
            String actionType,
            String summaryTitle,
            String targetVersion,
            boolean reversible,
            int blastRadiusCount,
            UUID proposerId,
            String proposerName,
            UUID approverId,
            String approverName,
            String approvalStatus,
            String policyOutcome,
            List<String> policyReasons,
            String graphRevision,
            String subgraphDigest,
            String evidenceQualification,
            String rationale,
            Instant updatedAt
    ) {
    }

    public record CreateProposalRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String serviceCanonicalId,
            @NotBlank String actionType,
            @NotBlank String summaryTitle,
            @NotBlank String targetVersion,
            boolean reversible,
            int blastRadiusCount,
            @NotBlank String environmentKind,
            @NotBlank String proposerName,
            @NotBlank String graphRevision,
            @NotBlank String subgraphDigest,
            @NotBlank String evidenceQualification,
            @NotBlank String rationale
    ) {
    }

    public record ApproveProposalRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String approverName,
            @NotBlank String decision // APPROVE | REJECT
    ) {
    }

    public record IssueAuthorizationRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String currentGraphRevision,
            @NotBlank String currentSubgraphDigest,
            int currentBlastRadiusCount,
            boolean fallbackChanged,
            @NotBlank String audience
    ) {
    }

    public record IssuedAuthorizationDto(
            UUID authorizationId,
            UUID proposalId,
            String nonce,
            String audience,
            String deltaClassification,
            boolean consumed,
            String jwsToken,
            BoundedRecoveryAuthorization envelope,
            Instant expiresAt
    ) {
    }
}
