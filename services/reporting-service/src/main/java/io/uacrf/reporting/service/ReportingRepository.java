package io.uacrf.reporting.service;

import io.uacrf.reporting.api.ReportingDtos.AssuranceRoomSummaryDto;
import io.uacrf.reporting.api.ReportingDtos.MeteringEventDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReportingRepository {

    private final JdbcTemplate jdbc;

    public ReportingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<MeteringEventDto> METERING_MAPPER = (rs, rowNum) -> new MeteringEventDto(
            UUID.fromString(rs.getString("meter_id")),
            UUID.fromString(rs.getString("tenant_id")),
            UUID.fromString(rs.getString("environment_id")),
            rs.getString("meter_dimension"),
            rs.getString("cloud_provider"),
            rs.getLong("quantity"),
            rs.getString("unit"),
            rs.getString("event_digest"),
            rs.getTimestamp("recorded_at").toInstant()
    );

    public record StoredRoom(
            UUID roomId,
            UUID tenantId,
            UUID environmentId,
            String auditorOrganization,
            String recipientEmail,
            List<String> frameworks,
            String accessTokenSha256,
            String watermarkText,
            String bundleSha256,
            String status,
            String createdBy,
            Instant expiresAt,
            Instant createdAt
    ) {
        public AssuranceRoomSummaryDto toSummary() {
            return new AssuranceRoomSummaryDto(roomId, tenantId, environmentId, auditorOrganization, recipientEmail,
                    frameworks, watermarkText, bundleSha256, status, createdBy, expiresAt, createdAt);
        }
    }

    private static final RowMapper<StoredRoom> ROOM_MAPPER = (rs, rowNum) -> new StoredRoom(
            UUID.fromString(rs.getString("room_id")),
            UUID.fromString(rs.getString("tenant_id")),
            UUID.fromString(rs.getString("environment_id")),
            rs.getString("auditor_organization"),
            rs.getString("recipient_email"),
            Arrays.asList(rs.getString("frameworks_csv").split(",")),
            rs.getString("access_token_sha256"),
            rs.getString("watermark_text"),
            rs.getString("bundle_sha256"),
            rs.getString("status"),
            rs.getString("created_by"),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("created_at").toInstant()
    );

    public List<MeteringEventDto> listMetering(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT * FROM reporting.metering_event
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY recorded_at DESC
                """, METERING_MAPPER, tenantId, environmentId);
    }

    public MeteringEventDto insertMetering(MeteringEventDto dto) {
        jdbc.update("""
                INSERT INTO reporting.metering_event (
                    meter_id, tenant_id, environment_id, meter_dimension, cloud_provider, quantity, unit, event_digest, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                dto.meterId(), dto.tenantId(), dto.environmentId(), dto.meterDimension(),
                dto.cloudProvider(), dto.quantity(), dto.unit(), dto.eventDigest(), Timestamp.from(dto.recordedAt()));
        return dto;
    }

    public StoredRoom insertAssuranceRoom(StoredRoom room) {
        jdbc.update("""
                INSERT INTO reporting.assurance_room (
                    room_id, tenant_id, environment_id, auditor_organization, recipient_email,
                    frameworks_csv, access_token_sha256, watermark_text, bundle_sha256, status,
                    created_by, expires_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                room.roomId(), room.tenantId(), room.environmentId(), room.auditorOrganization(),
                room.recipientEmail(), String.join(",", room.frameworks()), room.accessTokenSha256(),
                room.watermarkText(), room.bundleSha256(), room.status(), room.createdBy(),
                Timestamp.from(room.expiresAt()), Timestamp.from(room.createdAt()));
        return room;
    }

    public List<AssuranceRoomSummaryDto> listAssuranceRooms(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT * FROM reporting.assurance_room
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY created_at DESC
                """, ROOM_MAPPER, tenantId, environmentId)
                .stream().map(StoredRoom::toSummary).toList();
    }

    public Optional<StoredRoom> findRoom(UUID roomId) {
        return jdbc.query("SELECT * FROM reporting.assurance_room WHERE room_id = ?", ROOM_MAPPER, roomId)
                .stream().findFirst();
    }

    public void insertOutbox(UUID tenantId, UUID environmentId, String eventType,
                             String aggregateType, String aggregateId, UUID correlationId, String payloadJson) {
        jdbc.update("""
                INSERT INTO integration.outbox_event (
                    outbox_id, tenant_id, environment_id, event_type, aggregate_type, aggregate_id, correlation_id, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), tenantId, environmentId, eventType, aggregateType, aggregateId, correlationId, payloadJson);
    }
}
