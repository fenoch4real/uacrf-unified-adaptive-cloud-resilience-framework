package io.uacrf.contracts.evidence;

import io.uacrf.contracts.CanonicalEnums.EvidenceQualificationState;
import java.time.Instant;
import java.util.Map;

/**
 * Canonical normalized evidence event contract (Appendix D.3).
 */
public record CanonicalEvidenceEvent(
        String schemaVersion,
        String eventId,
        String tenantId,
        String environmentId,
        Instant occurredAt,
        Instant ingestedAt,
        String sourceSystem,
        String sourceProvider,
        String serviceId,
        String subjectCanonicalId,
        String layer,
        String signalType,
        String name,
        Double value,
        String unit,
        Double threshold,
        String severity,
        String correlationId,
        Map<String, String> dimensions,
        Provenance provenance,
        EvidenceQualificationState qualificationState,
        String qualificationId,
        String graphRevision
) {
    public record Provenance(
            String connectorVersion,
            String rawReference,
            String rawDigest
    ) {
    }
}
