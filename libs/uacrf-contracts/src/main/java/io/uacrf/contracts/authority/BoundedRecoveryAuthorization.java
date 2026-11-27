package io.uacrf.contracts.authority;

import java.time.Instant;
import java.util.List;

/**
 * Provider-neutral, short-lived, single-use Bounded Recovery Authorization Envelope (Patent 3, Appendix D.5).
 */
public record BoundedRecoveryAuthorization(
        String schemaVersion,
        String authorizationId,
        String candidateActionId,
        String actionType,
        List<String> targetCanonicalIds,
        String tenantId,
        String environmentId,
        String graphRevision,
        String subgraphDigest,
        int maximumTargets,
        int maximumTrafficPercentage,
        List<String> prohibitedOperations,
        String validationPlanId,
        String compensationPlanId,
        String policyDecisionId,
        List<String> approvalDecisionIds,
        String audience,
        String nonce,
        Instant issuedAt,
        Instant expiresAt
) {
}
