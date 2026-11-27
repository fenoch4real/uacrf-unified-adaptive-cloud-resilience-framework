package io.uacrf.evidence.service;

import io.uacrf.evidence.api.EvidenceDtos.AiDependencyEvidenceDto;
import io.uacrf.evidence.api.EvidenceDtos.EvidenceItemDto;
import io.uacrf.evidence.api.EvidenceDtos.EvidencePackageDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EvidenceRepository {

    private final JdbcTemplate jdbc;

    public EvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EvidencePackageDto> listPackages(UUID tenantId, UUID environmentId) {
        List<PackageRow> rows = jdbc.query("""
                SELECT package_id, tenant_id, environment_id, service_canonical_id, service_name,
                       journey_name, deployment_ref, candidate_action, owner_name, graph_revision,
                       completeness_status, recovery_readiness, observed_state, updated_at
                FROM evidence.evidence_package
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY updated_at DESC
                """, this::mapPackageRow, tenantId, environmentId);

        return rows.stream().map(r -> hydratePackage(r)).toList();
    }

    public Optional<EvidencePackageDto> findPackage(UUID tenantId, UUID environmentId, UUID packageId) {
        List<PackageRow> rows = jdbc.query("""
                SELECT package_id, tenant_id, environment_id, service_canonical_id, service_name,
                       journey_name, deployment_ref, candidate_action, owner_name, graph_revision,
                       completeness_status, recovery_readiness, observed_state, updated_at
                FROM evidence.evidence_package
                WHERE tenant_id = ? AND environment_id = ? AND package_id = ?
                """, this::mapPackageRow, tenantId, environmentId, packageId);
        return rows.stream().findFirst().map(this::hydratePackage);
    }

    public List<EvidenceItemDto> listItems(UUID packageId) {
        return jdbc.query("""
                SELECT item_id, package_id, item_key, item_label, source_kind, availability_status,
                       qualification_state, freshness_state, required_for_recovery, detail_note,
                       source_reference, payload_digest, graph_revision, captured_at, expires_at
                FROM evidence.evidence_item
                WHERE package_id = ?
                ORDER BY item_id
                """, this::mapItem, packageId);
    }

    public void updateItem(UUID packageId, String itemKey, String sourceKind, String availabilityStatus,
                           String qualificationState, String freshnessState, String detailNote,
                           String sourceReference, String digest, String graphRevision,
                           Instant capturedAt, Instant expiresAt) {
        jdbc.update("""
                UPDATE evidence.evidence_item
                SET source_kind = ?, availability_status = ?, qualification_state = ?,
                    freshness_state = ?, detail_note = ?, source_reference = ?,
                    payload_digest = ?, graph_revision = ?, captured_at = ?, expires_at = ?
                WHERE package_id = ? AND item_key = ?
                """,
                sourceKind, availabilityStatus, qualificationState, freshnessState, detailNote,
                sourceReference, digest, graphRevision, Timestamp.from(capturedAt), Timestamp.from(expiresAt),
                packageId, itemKey);
    }

    public void updatePackageStatus(UUID packageId, String completenessStatus, String recoveryReadiness) {
        jdbc.update("""
                UPDATE evidence.evidence_package
                SET completeness_status = ?, recovery_readiness = ?, updated_at = now()
                WHERE package_id = ?
                """, completenessStatus, recoveryReadiness, packageId);
    }

    public void storeImmutableBlob(UUID tenantId, String objectKey, String sha256Digest, String payloadJson) {
        jdbc.update("""
                INSERT INTO evidence.object_blob (blob_id, tenant_id, object_key, sha256_digest, content_type, worm_locked, payload_json)
                VALUES (?, ?, ?, ?, 'application/json', TRUE, ?::jsonb)
                ON CONFLICT (object_key) DO NOTHING
                """, UUID.randomUUID(), tenantId, objectKey, sha256Digest, payloadJson);
    }

    public Optional<Map<String, Object>> findImmutableBlob(UUID tenantId, String objectKey) {
        var rows = jdbc.queryForList("""
                SELECT blob_id, object_key, sha256_digest, worm_locked, octet_length(payload_json::text) AS byte_length
                FROM evidence.object_blob
                WHERE tenant_id = ? AND object_key = ?
                """, tenantId, objectKey);
        return rows.stream().findFirst();
    }

    public List<AiDependencyEvidenceDto> listAiEvidence(UUID tenantId, UUID environmentId, String serviceCanonicalId) {
        return jdbc.query("""
                SELECT ai_evidence_id, service_canonical_id, agent_canonical_id, model_version,
                       aspect_key, aspect_label, observed_status, current_value, qualification_state, verified_at
                FROM evidence.ai_dependency_evidence
                WHERE tenant_id = ? AND environment_id = ?
                  AND (? IS NULL OR service_canonical_id = ?)
                ORDER BY ai_evidence_id
                """, this::mapAiEvidence, tenantId, environmentId, serviceCanonicalId, serviceCanonicalId);
    }

    public void updateAiAspect(UUID tenantId, UUID environmentId, String serviceCanonicalId,
                               String aspectKey, String observedStatus, String currentValue,
                               String qualificationState, Instant verifiedAt) {
        jdbc.update("""
                UPDATE evidence.ai_dependency_evidence
                SET observed_status = ?, current_value = ?, qualification_state = ?, verified_at = ?
                WHERE tenant_id = ? AND environment_id = ? AND service_canonical_id = ? AND aspect_key = ?
                """,
                observedStatus, currentValue, qualificationState, Timestamp.from(verifiedAt),
                tenantId, environmentId, serviceCanonicalId, aspectKey);
    }

    public void insertOutbox(UUID tenantId, UUID environmentId, String eventType, String aggregateType,
                             String aggregateId, UUID correlationId, String payloadJson) {
        jdbc.update("""
                INSERT INTO integration.outbox_event (
                    outbox_id, tenant_id, environment_id, event_type, aggregate_type, aggregate_id, correlation_id, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), tenantId, environmentId, eventType, aggregateType, aggregateId, correlationId, payloadJson);
    }

    private EvidencePackageDto hydratePackage(PackageRow r) {
        List<EvidenceItemDto> items = listItems(r.packageId());
        int totalRequired = (int) items.stream().filter(EvidenceItemDto::requiredForRecovery).count();
        int available = (int) items.stream().filter(i -> "AVAILABLE".equals(i.availabilityStatus())).count();
        return new EvidencePackageDto(
                r.packageId(), r.tenantId(), r.environmentId(), r.serviceCanonicalId(),
                r.serviceName(), r.journeyName(), r.deploymentRef(), r.candidateAction(),
                r.ownerName(), r.graphRevision(), r.completenessStatus(), r.recoveryReadiness(),
                r.observedState(), available, totalRequired, items, r.updatedAt()
        );
    }

    private PackageRow mapPackageRow(ResultSet rs, int rowNum) throws SQLException {
        return new PackageRow(
                rs.getObject("package_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getString("service_canonical_id"),
                rs.getString("service_name"),
                rs.getString("journey_name"),
                rs.getString("deployment_ref"),
                rs.getString("candidate_action"),
                rs.getString("owner_name"),
                rs.getString("graph_revision"),
                rs.getString("completeness_status"),
                rs.getString("recovery_readiness"),
                rs.getString("observed_state"),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private EvidenceItemDto mapItem(ResultSet rs, int rowNum) throws SQLException {
        Timestamp cap = rs.getTimestamp("captured_at");
        Timestamp exp = rs.getTimestamp("expires_at");
        return new EvidenceItemDto(
                rs.getObject("item_id", UUID.class),
                rs.getObject("package_id", UUID.class),
                rs.getString("item_key"),
                rs.getString("item_label"),
                rs.getString("source_kind"),
                rs.getString("availability_status"),
                rs.getString("qualification_state"),
                rs.getString("freshness_state"),
                rs.getBoolean("required_for_recovery"),
                rs.getString("detail_note"),
                rs.getString("source_reference"),
                rs.getString("payload_digest"),
                rs.getString("graph_revision"),
                cap != null ? cap.toInstant() : null,
                exp != null ? exp.toInstant() : null
        );
    }

    private AiDependencyEvidenceDto mapAiEvidence(ResultSet rs, int rowNum) throws SQLException {
        Timestamp ver = rs.getTimestamp("verified_at");
        return new AiDependencyEvidenceDto(
                rs.getObject("ai_evidence_id", UUID.class),
                rs.getString("service_canonical_id"),
                rs.getString("agent_canonical_id"),
                rs.getString("model_version"),
                rs.getString("aspect_key"),
                rs.getString("aspect_label"),
                rs.getString("observed_status"),
                rs.getString("current_value"),
                rs.getString("qualification_state"),
                ver != null ? ver.toInstant() : null
        );
    }

    private record PackageRow(
            UUID packageId, UUID tenantId, UUID environmentId, String serviceCanonicalId,
            String serviceName, String journeyName, String deploymentRef, String candidateAction,
            String ownerName, String graphRevision, String completenessStatus,
            String recoveryReadiness, String observedState, Instant updatedAt
    ) {
    }
}
