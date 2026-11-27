package io.uacrf.knowledge.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class KnowledgeDtos {

    private KnowledgeDtos() {
    }

    public record ArticleDto(
            UUID articleId, UUID tenantId, UUID environmentId,
            String docCode, String docKind, String title, String contentSummary,
            String sha256Digest, UUID authorId, String authorName,
            UUID reviewerId, String reviewerName, String status, Instant updatedAt
    ) {
    }

    public record CreateArticleRequest(
            @NotNull UUID tenantId, @NotNull UUID environmentId,
            @NotBlank String docCode, @NotBlank String docKind,
            @NotBlank String title, @NotBlank String contentSummary,
            @NotBlank String authorName
    ) {
    }

    public record ReviewArticleRequest(
            @NotNull UUID tenantId, @NotNull UUID environmentId,
            @NotBlank String reviewerName, @NotBlank String decision
    ) {
    }

    public record LearningItemDto(
            UUID learningId, UUID tenantId, UUID environmentId,
            String incidentRef, String title, String targetArtifactType,
            String verificationDigest, String status, Instant updatedAt
    ) {
    }

    public record CloseLearningRequest(
            @NotNull UUID tenantId, @NotNull UUID environmentId,
            String verificationDigest
    ) {
    }

    public record ExperimentDto(
            UUID experimentId, UUID tenantId, UUID environmentId,
            String title, String faultType, String targetCanonicalId,
            int maxBlastRadius, double abortSloThreshold, String status,
            double observedSli, String outcomeSummary, Instant executedAt
    ) {
    }

    public record RunExperimentRequest(
            @NotNull UUID tenantId, @NotNull UUID environmentId,
            @NotBlank String title, @NotBlank String faultType,
            @NotBlank String targetCanonicalId, int maxBlastRadius,
            double abortSloThreshold, double simulatedObservedSli
    ) {
    }

    public record QuantitativeSnapshot(
            double[] nextLayerRisk,
            double systemicRiskScore,
            double resilienceCapabilityIndex,
            double effectiveResilience,
            String mode
    ) {
    }

    public record AblationBenchmarkRow(
            String variant,
            double mttdSeconds,
            int cascadeSizeNodes,
            double mttrMinutes,
            double effectiveResilience,
            double harmfulActionRate
    ) {
    }

    public record PaperBenchmarkReport(
            String citation,
            QuantitativeSnapshot sampleShadowState,
            List<AblationBenchmarkRow> benchmarks
    ) {
    }
}
