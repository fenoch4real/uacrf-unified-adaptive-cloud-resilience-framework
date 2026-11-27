package io.uacrf.catalog.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record CreateServiceRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String name,
            @NotBlank String domain,
            String description,
            @NotBlank String criticality,
            @NotNull UUID ownerId
    ) {
    }

    public record ServiceView(
            UUID serviceId,
            UUID tenantId,
            UUID environmentId,
            String canonicalId,
            String name,
            String domain,
            String description,
            String serviceKind,
            String criticality,
            String lifecycle,
            UUID ownerId,
            long version
    ) {
    }

    public record RegisterAgentWorkloadRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            UUID parentServiceId,
            @NotBlank String name,
            @NotBlank String workloadKind,
            @NotBlank String intendedPurpose,
            @NotNull UUID ownerId,
            @NotNull UUID runtimePrincipalId,
            @NotBlank String criticality
    ) {
    }

    public record AgentWorkloadView(
            UUID workloadProfileId,
            UUID tenantId,
            UUID environmentId,
            UUID serviceAnchorId,
            UUID parentServiceId,
            String name,
            String workloadKind,
            String intendedPurpose,
            UUID ownerId,
            UUID runtimePrincipalId,
            String criticality,
            Long activeVersion,
            String activeDigest,
            String lifecycle,
            long version
    ) {
    }

    public record RegisterWorkloadVersionRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotNull Map<String, Object> manifest
    ) {
    }

    public record WorkloadVersionView(
            UUID workloadVersionId,
            UUID workloadProfileId,
            long versionNumber,
            String manifestDigest,
            UUID authorId,
            String status,
            UUID activatedBy,
            Instant activatedAt
    ) {
    }

    public record ActivateWorkloadVersionRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            long versionNumber,
            long expectedProfileVersion
    ) {
    }

    public record CreateJourneyRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String name,
            @NotBlank String purpose,
            @NotNull UUID ownerId,
            String operatingWindow
    ) {
    }

    public record JourneyView(
            UUID journeyId,
            UUID tenantId,
            UUID environmentId,
            String name,
            String purpose,
            UUID ownerId,
            String operatingWindow,
            String lifecycle,
            long version
    ) {
    }

    public record AddJourneyStepRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @Min(1) int sequenceNum,
            @NotBlank String name,
            @NotBlank String successCondition,
            int timeoutSeconds,
            String failureBehavior
    ) {
    }

    public record JourneyStepView(
            UUID stepId,
            UUID journeyId,
            int sequenceNum,
            String name,
            String successCondition,
            int timeoutSeconds,
            String failureBehavior
    ) {
    }

    public record LinkJourneyCapabilityRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            UUID stepId,
            @NotBlank String capabilityKind,
            @NotNull UUID targetId,
            @NotBlank String participationCriticality,
            @NotBlank String rationale,
            String evidenceRef
    ) {
    }

    public record JourneyCapabilityView(
            UUID linkId,
            UUID journeyId,
            UUID stepId,
            String capabilityKind,
            UUID targetId,
            String participationCriticality,
            String rationale,
            String evidenceRef
    ) {
    }

    public record ImpactDimensionInput(
            @NotBlank String dimensionType,
            @NotBlank String description,
            @NotBlank String severity
    ) {
    }

    public record UpsertImpactProfileRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String impactIfUnavailable,
            @NotBlank String affectedPopulation,
            @NotBlank String criticalityAssessment,
            BigDecimal businessToleranceValue,
            String businessToleranceUnit,
            String businessToleranceBasis,
            @NotBlank String rationale,
            @NotNull List<ImpactDimensionInput> dimensions
    ) {
    }

    public record ReviewImpactProfileRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String decision,
            @NotBlank String reason
    ) {
    }

    public record ImpactProfileView(
            UUID profileId,
            UUID journeyId,
            long revision,
            String impactIfUnavailable,
            String affectedPopulation,
            String criticalityAssessment,
            String assessmentStatus,
            BigDecimal businessToleranceValue,
            String businessToleranceUnit,
            String businessToleranceBasis,
            String rationale,
            String contentDigest,
            UUID authorId,
            UUID reviewerId,
            String reviewReason,
            List<ImpactDimensionInput> dimensions
    ) {
    }

    public record JourneyDetailView(
            JourneyView journey,
            List<JourneyStepView> steps,
            List<JourneyCapabilityView> capabilities,
            ImpactProfileView latestImpactProfile
    ) {
    }

    public record CreateSharedResourceRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String name,
            @NotBlank String assetKind,
            @NotBlank String classification,
            @NotNull UUID ownerId
    ) {
    }

    public record SharedResourceView(
            UUID sharedAssetId,
            UUID tenantId,
            UUID environmentId,
            String name,
            String assetKind,
            String classification,
            UUID ownerId,
            String lifecycle,
            long version
    ) {
    }

    public record LinkSharedResourceConsumerRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String consumerKind,
            @NotNull UUID consumerId,
            @NotBlank String usageRole,
            @NotBlank String rationale
    ) {
    }
}
