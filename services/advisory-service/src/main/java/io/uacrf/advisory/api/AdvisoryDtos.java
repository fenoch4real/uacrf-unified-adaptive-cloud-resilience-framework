package io.uacrf.advisory.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AdvisoryDtos {

    private AdvisoryDtos() {
    }

    public record CitationDto(String docCode, String docKind, String title) {
    }

    public record AdvisoryConfigDto(UUID tenantId, UUID environmentId, boolean advisoryEnabled,
                                    int maxTokenBudget, int tokensUsed) {
    }

    public record AdvisoryFindingDto(
            UUID findingId, UUID tenantId, UUID environmentId,
            String caseCode, String contextRef, String producerRole,
            String modelVersion, String promptVersion, String headline, String findingBody,
            List<CitationDto> citations,
            String criticDissentHeadline, String criticDissentBody,
            String criticSeverity, String criticStatus,
            String humanReviewer, String reviewDecision, Instant updatedAt
    ) {
    }

    public record RunAdvisoryRequest(
            @NotNull UUID tenantId, @NotNull UUID environmentId,
            @NotBlank String caseCode, @NotBlank String contextRef,
            @NotBlank String queryPrompt, @NotBlank String humanReviewer
    ) {
    }

    public record ReviewFindingRequest(
            @NotNull UUID tenantId, @NotNull UUID environmentId,
            @NotBlank String decision, // REJECT_DRAFT | EDIT_DRAFT | KEEP_FOR_REVIEW
            String editedBody
    ) {
    }

    public record AgentProposalInput(String agentId, String role, String actionRecommendation, double confidence, boolean hasBlockingDissent) {
    }

    public record ArbitratedOutcome(
            String selectedRecommendation,
            boolean humanReviewRequired,
            boolean suppressedAnyDissent,
            List<String> preservedDissentAgentIds,
            String deterministicManifestSha256
    ) {
    }
}
