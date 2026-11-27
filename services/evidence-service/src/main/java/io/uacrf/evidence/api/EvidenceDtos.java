package io.uacrf.evidence.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EvidenceDtos {

    private EvidenceDtos() {
    }

    public record EvidenceItemDto(
            UUID itemId,
            UUID packageId,
            String itemKey,
            String itemLabel,
            String sourceKind,
            String availabilityStatus,
            String qualificationState,
            String freshnessState,
            boolean requiredForRecovery,
            String detailNote,
            String sourceReference,
            String payloadDigest,
            String graphRevision,
            Instant capturedAt,
            Instant expiresAt
    ) {
    }

    public record EvidencePackageDto(
            UUID packageId,
            UUID tenantId,
            UUID environmentId,
            String serviceCanonicalId,
            String serviceName,
            String journeyName,
            String deploymentRef,
            String candidateAction,
            String ownerName,
            String graphRevision,
            String completenessStatus,
            String recoveryReadiness,
            String observedState,
            int availableCount,
            int totalRequiredCount,
            List<EvidenceItemDto> items,
            Instant updatedAt
    ) {
    }

    public record SubmitEvidenceRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String itemKey,
            @NotBlank String sourceKind,
            @NotBlank String detailNote,
            @NotBlank String graphRevision,
            Integer ttlSeconds,
            Map<String, Object> rawPayload
    ) {
    }

    public record AiDependencyEvidenceDto(
            UUID aiEvidenceId,
            String serviceCanonicalId,
            String agentCanonicalId,
            String modelVersion,
            String aspectKey,
            String aspectLabel,
            String observedStatus,
            String currentValue,
            String qualificationState,
            Instant verifiedAt
    ) {
    }

    public record UpdateAiEvidenceRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String serviceCanonicalId,
            @NotBlank String aspectKey,
            @NotBlank String currentValue,
            @NotBlank String sourceKind
    ) {
    }

    public record ExportedDraftBundle(
            UUID packageId,
            String exportKind,
            String completenessStatus,
            String recoveryReadiness,
            String bundleSha256,
            List<String> limitations,
            EvidencePackageDto evidencePackage,
            Instant exportedAt
    ) {
    }
}
