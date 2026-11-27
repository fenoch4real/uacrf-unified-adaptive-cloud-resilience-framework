package io.uacrf.discovery.service;

import io.uacrf.discovery.api.DiscoveryDtos.ConnectionDto;
import io.uacrf.discovery.api.DiscoveryDtos.DiscoveredEdgeDto;
import io.uacrf.discovery.api.DiscoveryDtos.ObservationDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class DiscoveryRepository {

    private final JdbcTemplate jdbc;

    public DiscoveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ConnectionDto> listConnections(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT connection_id, tenant_id, environment_id, connection_name, provider, status,
                       access_level, telemetry_source, affected_source, impact_note, recovery_access, last_collected_at
                FROM discovery.connector_connection
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY created_at, connection_name
                """, this::mapConnection, tenantId, environmentId);
    }

    public ConnectionDto insertConnection(ConnectionDto dto) {
        jdbc.update("""
                INSERT INTO discovery.connector_connection (
                    connection_id, tenant_id, environment_id, connection_name, provider, status,
                    access_level, telemetry_source, affected_source, impact_note, recovery_access, last_collected_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                dto.connectionId(), dto.tenantId(), dto.environmentId(), dto.connectionName(),
                dto.provider(), dto.status(), dto.accessLevel(), dto.telemetrySource(),
                dto.affectedSource(), dto.impactNote(), dto.recoveryAccess(),
                dto.lastCollectedAt() != null ? Timestamp.from(dto.lastCollectedAt()) : null);
        return dto;
    }

    public void updateConnectionCollection(UUID tenantId, UUID environmentId, String provider, Instant collectedAt) {
        jdbc.update("""
                UPDATE discovery.connector_connection
                SET last_collected_at = ?, status = CASE WHEN status = 'NOT_CONFIGURED' THEN 'CONNECTED' ELSE status END
                WHERE tenant_id = ? AND environment_id = ? AND provider = ?
                """, Timestamp.from(collectedAt), tenantId, environmentId, provider);
    }

    public void insertObservation(ObservationDto o, String payloadJson) {
        jdbc.update("""
                INSERT INTO discovery.telemetry_observation (
                    observation_id, tenant_id, environment_id, service_id, subject_canonical_id,
                    provider, signal_type, metric_name, metric_value, unit, threshold_value, severity,
                    telemetry_source, raw_digest, raw_reference, payload_json, occurred_at, ingested_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                o.observationId(), o.tenantId(), o.environmentId(), o.serviceId(), o.subjectCanonicalId(),
                o.provider(), o.signalType(), o.metricName(), o.metricValue(), o.unit(), o.thresholdValue(),
                o.severity(), o.telemetrySource(), o.rawDigest(), o.rawReference(), payloadJson,
                Timestamp.from(o.occurredAt()), Timestamp.from(o.ingestedAt()));
    }

    public List<ObservationDto> listObservations(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT observation_id, tenant_id, environment_id, service_id, subject_canonical_id,
                       provider, signal_type, metric_name, metric_value, unit, threshold_value, severity,
                       telemetry_source, raw_digest, raw_reference, occurred_at, ingested_at
                FROM discovery.telemetry_observation
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY occurred_at DESC
                LIMIT 100
                """, this::mapObservation, tenantId, environmentId);
    }

    public void upsertDiscoveredEdge(DiscoveredEdgeDto e) {
        jdbc.update("""
                DELETE FROM discovery.discovered_edge
                WHERE tenant_id = ? AND environment_id = ?
                  AND source_canonical_id = ? AND relationship_type = ? AND target_canonical_id = ?
                """, e.tenantId(), e.environmentId(), e.sourceCanonicalId(), e.relationshipType(), e.targetCanonicalId());

        jdbc.update("""
                INSERT INTO discovery.discovered_edge (
                    edge_id, tenant_id, environment_id, source_canonical_id, source_label, source_display,
                    relationship_type, target_canonical_id, target_label, target_display, importance,
                    edge_state, observation_source, owner_confirmed, telemetry_source, last_observed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                e.edgeId(), e.tenantId(), e.environmentId(), e.sourceCanonicalId(), e.sourceLabel(),
                e.sourceDisplay(), e.relationshipType(), e.targetCanonicalId(), e.targetLabel(),
                e.targetDisplay(), e.importance(), e.edgeState(), e.observationSource(),
                e.ownerConfirmed(), e.telemetrySource(), Timestamp.from(e.lastObservedAt()));
    }

    public List<DiscoveredEdgeDto> listDiscoveredEdges(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT edge_id, tenant_id, environment_id, source_canonical_id, source_label, source_display,
                       relationship_type, target_canonical_id, target_label, target_display, importance,
                       edge_state, observation_source, owner_confirmed, telemetry_source, last_observed_at
                FROM discovery.discovered_edge
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY source_display, target_display
                """, this::mapEdge, tenantId, environmentId);
    }

    public void insertOutbox(UUID tenantId, UUID environmentId, String eventType, String aggregateType,
                             String aggregateId, UUID correlationId, String payloadJson) {
        jdbc.update("""
                INSERT INTO integration.outbox_event (
                    outbox_id, tenant_id, environment_id, event_type, aggregate_type, aggregate_id, correlation_id, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), tenantId, environmentId, eventType, aggregateType, aggregateId, correlationId, payloadJson);
    }

    private ConnectionDto mapConnection(ResultSet rs, int rowNum) throws SQLException {
        Timestamp ts = rs.getTimestamp("last_collected_at");
        return new ConnectionDto(
                rs.getObject("connection_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getString("connection_name"),
                rs.getString("provider"),
                rs.getString("status"),
                rs.getString("access_level"),
                rs.getString("telemetry_source"),
                rs.getString("affected_source"),
                rs.getString("impact_note"),
                rs.getString("recovery_access"),
                ts != null ? ts.toInstant() : null
        );
    }

    private ObservationDto mapObservation(ResultSet rs, int rowNum) throws SQLException {
        return new ObservationDto(
                rs.getObject("observation_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getObject("service_id", UUID.class),
                rs.getString("subject_canonical_id"),
                rs.getString("provider"),
                rs.getString("signal_type"),
                rs.getString("metric_name"),
                rs.getObject("metric_value") != null ? rs.getDouble("metric_value") : null,
                rs.getString("unit"),
                rs.getObject("threshold_value") != null ? rs.getDouble("threshold_value") : null,
                rs.getString("severity"),
                rs.getString("telemetry_source"),
                rs.getString("raw_digest"),
                rs.getString("raw_reference"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("ingested_at").toInstant()
        );
    }

    private DiscoveredEdgeDto mapEdge(ResultSet rs, int rowNum) throws SQLException {
        return new DiscoveredEdgeDto(
                rs.getObject("edge_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getString("source_canonical_id"),
                rs.getString("source_label"),
                rs.getString("source_display"),
                rs.getString("relationship_type"),
                rs.getString("target_canonical_id"),
                rs.getString("target_label"),
                rs.getString("target_display"),
                rs.getString("importance"),
                rs.getString("edge_state"),
                rs.getString("observation_source"),
                rs.getBoolean("owner_confirmed"),
                rs.getString("telemetry_source"),
                rs.getTimestamp("last_observed_at").toInstant()
        );
    }
}
