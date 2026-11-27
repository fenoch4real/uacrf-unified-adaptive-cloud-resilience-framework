package io.uacrf.graph.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class GraphDtos {

    private GraphDtos() {
    }

    public record GraphNodeDto(
            UUID nodeId,
            String canonicalId,
            String nodeLabel,
            String displayName,
            String healthState,
            String providerTag,
            String subtitle
    ) {
    }

    public record GraphEdgeDto(
            UUID edgeId,
            String sourceCanonicalId,
            String relationshipType,
            String targetCanonicalId,
            String importance,
            String edgeState,
            String observationSource,
            boolean ownerConfirmed,
            Instant lastObservedAt
    ) {
    }

    public record DependencyGraphResponse(
            UUID tenantId,
            UUID environmentId,
            String focusCanonicalId,
            int depth,
            boolean includeUncertain,
            String revisionDigest,
            String nonCausationNotice,
            List<GraphNodeDto> nodes,
            List<GraphEdgeDto> edges
    ) {
    }

    public record ProjectEdgeInput(
            @NotBlank String sourceCanonicalId,
            @NotBlank String sourceLabel,
            @NotBlank String sourceDisplay,
            @NotBlank String relationshipType,
            @NotBlank String targetCanonicalId,
            @NotBlank String targetLabel,
            @NotBlank String targetDisplay,
            @NotBlank String importance,
            @NotBlank String edgeState,
            @NotBlank String observationSource,
            boolean ownerConfirmed
    ) {
    }

    public record ProjectEdgesRequest(
            @NotNull UUID tenantId,
            @NotNull UUID environmentId,
            @Valid @NotNull List<ProjectEdgeInput> edges
    ) {
    }

    public record GraphRevisionDto(
            UUID revisionId,
            UUID tenantId,
            UUID environmentId,
            String revisionDigest,
            int nodeCount,
            int edgeCount,
            Instant createdAt
    ) {
    }
}
