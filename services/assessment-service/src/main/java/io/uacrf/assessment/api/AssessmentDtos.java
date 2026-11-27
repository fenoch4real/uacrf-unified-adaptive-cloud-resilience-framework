package io.uacrf.assessment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AssessmentDtos {

    private AssessmentDtos() {
    }

    public record FindingDto(
            UUID findingId,
            UUID assessmentId,
            String areaName,
            String bucket,
            String status,
            String summaryTitle,
            String details,
            String ownerName,
            String dueLabel,
            boolean challenged,
            String challengeNote
    ) {
    }

    public record PathCertificationDto(
            UUID certificationId,
            UUID assessmentId,
            String serviceCanonicalId,
            String primaryPathId,
            String alternatePathId,
            String certificationStatus,
            List<String> sharedDomains,
            String witnessSummary,
            String graphRevision,
            Instant certifiedAt
    ) {
    }

    public record DecisionEnvelopeDto(
            UUID envelopeId,
            UUID assessmentId,
            String graphRevision,
            String payloadSha256,
            String jwsToken,
            String sealedBy,
            Instant sealedAt
    ) {
    }

    public record AssessmentDto(
            UUID assessmentId,
            UUID tenantId,
            UUID environmentId,
            String journeyCanonicalId,
            String journeyName,
            String failureScenario,
            List<String> scopedServices,
            String recoveryCandidate,
            UUID assessorId,
            String assessorName,
            UUID serviceOwnerId,
            String serviceOwnerName,
            String technicalReviewerName,
            String technicalReviewStatus,
            UUID independentReviewerId,
            String independentReviewerName,
            String independentReviewStatus,
            String reportStatus,
            String conclusionStatus,
            String conclusionHeadline,
            String conclusionDetail,
            String limitationsText,
            String recommendedNextStep,
            String graphRevision,
            String impactStatus,
            String impactedCustomerRate,
            List<FindingDto> findings,
            List<PathCertificationDto> pathCertifications,
            DecisionEnvelopeDto latestEnvelope,
            Instant updatedAt
    ) {
    }

    public record CertifyPathsRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            UUID assessmentId,
            @NotBlank String serviceCanonicalId,
            @NotBlank String primaryPathId,
            @NotBlank String alternatePathId,
            @NotNull Map<String, String> primaryCorrelationDomains,
            @NotNull Map<String, String> alternateCorrelationDomains,
            @NotBlank String graphRevision
    ) {
    }

    public record ReviewDecisionRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String reviewType, // TECHNICAL | INDEPENDENT
            @NotBlank String reviewerName,
            @NotBlank String outcome // APPROVED | CHANGES_REQUESTED
    ) {
    }

    public record VerifyReplayRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotNull UUID envelopeId,
            String overridePayloadSha256
    ) {
    }

    public record ReplayVerificationResult(
            UUID envelopeId,
            boolean signatureValid,
            boolean digestValid,
            String status,
            String message,
            Instant verifiedAt
    ) {
    }
}
