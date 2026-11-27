package io.uacrf.discovery.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DiscoveryDtos {

    private DiscoveryDtos() {
    }

    public record ConnectionDto(
            UUID connectionId,
            UUID tenantId,
            UUID environmentId,
            String connectionName,
            String provider,
            String status,
            String accessLevel,
            String telemetrySource,
            String affectedSource,
            String impactNote,
            String recoveryAccess,
            Instant lastCollectedAt
    ) {
    }

    public record CreateConnectionRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String connectionName,
            @NotBlank String provider,
            @NotBlank String accessLevel,
            String telemetrySource,
            String affectedSource,
            String impactNote
    ) {
    }

    public record TelemetryIngestRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            UUID serviceId,
            @NotBlank String subjectCanonicalId,
            @NotBlank String provider,
            @NotBlank String signalType,
            @NotBlank String metricName,
            Double value,
            String unit,
            Double threshold,
            @NotBlank String severity,
            String telemetrySource,
            Map<String, String> dimensions
    ) {
    }

    public record ObservationDto(
            UUID observationId,
            UUID tenantId,
            UUID environmentId,
            UUID serviceId,
            String subjectCanonicalId,
            String provider,
            String signalType,
            String metricName,
            Double metricValue,
            String unit,
            Double thresholdValue,
            String severity,
            String telemetrySource,
            String rawDigest,
            String rawReference,
            Instant occurredAt,
            Instant ingestedAt
    ) {
    }

    public record DiscoveredEdgeDto(
            UUID edgeId,
            UUID tenantId,
            UUID environmentId,
            String sourceCanonicalId,
            String sourceLabel,
            String sourceDisplay,
            String relationshipType,
            String targetCanonicalId,
            String targetLabel,
            String targetDisplay,
            String importance,
            String edgeState,
            String observationSource,
            boolean ownerConfirmed,
            String telemetrySource,
            Instant lastObservedAt
    ) {
    }

    public record SampleScenarioRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            String scenarioKey
    ) {
    }

    public record SampleScenarioResult(
            String scenarioKey,
            String telemetrySource,
            String provenanceClass,
            int observationsCreated,
            int edgesDiscovered,
            List<ObservationDto> observations,
            List<DiscoveredEdgeDto> edges,
            String note
    ) {
    }

    public record CloudInventoryProfile(
            String provider,
            boolean enabled,
            String authMode,
            String endpointUrl,
            String sourceName,
            Map<String, String> scopeMetadata
    ) {
    }

    public record ResourceFamilyDescriptor(
            String ruleFamily,
            String domainName,
            String canonicalNodeLabel,
            String awsServices,
            String azureServices,
            String gcpServices,
            String ociServices
    ) {
    }

    public record RawCloudAssetRecord(
            @NotBlank String resourceId,
            @NotBlank String nativeType,
            String displayName,
            String serviceCanonicalId,
            String region,
            String availabilityZone,
            String failureDomain,
            String runtimeKind,
            Integer minCapacity,
            Integer maxCapacity,
            Integer currentCapacity,
            Map<String, String> tags
    ) {
    }

    public record InventoryNormalizeRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String provider,
            String telemetrySource,
            boolean persist,
            @NotNull List<RawCloudAssetRecord> assets
    ) {
    }

    public record InventoryPollRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @NotBlank String provider,
            String scopeFilter
    ) {
    }

    public record NormalizedComputePlacementDto(
            String serviceCanonicalId,
            String cloudProvider,
            String runtimeKind,
            String region,
            String availabilityZone,
            String failureDomain,
            String clusterOrPool,
            int minCapacity,
            int maxCapacity,
            int currentCapacity,
            boolean autoscalingEnabled
    ) {
    }

    public record NormalizedAliasDto(
            String serviceCanonicalId,
            String cloudProvider,
            String providerResourceId,
            double confidence,
            String sourceSystem,
            Instant observedAt
    ) {
    }

    public record InventoryNormalizationResult(
            String provider,
            String telemetrySource,
            int assetsNormalized,
            List<ObservationDto> observations,
            List<DiscoveredEdgeDto> edges,
            List<NormalizedComputePlacementDto> computePlacements,
            List<NormalizedAliasDto> aliases
    ) {
    }
}
