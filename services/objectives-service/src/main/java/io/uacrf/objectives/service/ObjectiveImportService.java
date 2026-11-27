package io.uacrf.objectives.service;

import io.uacrf.crypto.CanonicalJson;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S05 Objective Import Preview & Upstream Conflict Resolution (AB.3):
 * Compares imported cloud/provider SLO targets against governed local targets without overwriting silently.
 */
@Service
public class ObjectiveImportService {

    private final JdbcTemplate jdbc;

    public ObjectiveImportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ImportConflictDto> MAPPER = (rs, rowNum) -> new ImportConflictDto(
            rs.getObject("conflict_id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("environment_id", UUID.class),
            rs.getString("service_canonical_id"),
            rs.getString("sli_name"),
            rs.getBigDecimal("local_slo_target"),
            rs.getBigDecimal("incoming_slo_target"),
            rs.getString("resolution_strategy"),
            rs.getString("resolved_by"),
            rs.getTimestamp("created_at").toInstant()
    );

    public List<ImportConflictDto> listConflicts(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT * FROM objectives.import_conflict
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY created_at DESC
                """, MAPPER, tenantId, environmentId);
    }

    @Transactional
    public ImportConflictDto previewImport(ImportPreviewRequest req) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        boolean differs = req.localSloTarget().compareTo(req.incomingSloTarget()) != 0;
        String status = differs ? "CONFLICT_DETECTED" : "AUTO_MATCHED";
        jdbc.update("""
                INSERT INTO objectives.import_conflict (
                    conflict_id, tenant_id, environment_id, service_canonical_id, sli_name,
                    local_slo_target, incoming_slo_target, resolution_strategy
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, req.tenantId(), req.environmentId(), req.serviceCanonicalId(), req.sliName(),
                req.localSloTarget(), req.incomingSloTarget(), status);
        return new ImportConflictDto(id, req.tenantId(), req.environmentId(), req.serviceCanonicalId(),
                req.sliName(), req.localSloTarget(), req.incomingSloTarget(), status, null, now);
    }

    @Transactional
    public ImportConflictDto resolveConflict(UUID conflictId, ResolveConflictRequest req) {
        jdbc.update("""
                UPDATE objectives.import_conflict
                SET resolution_strategy = ?, resolved_by = ?
                WHERE conflict_id = ?
                """, req.strategy(), req.resolvedBy(), conflictId);
        jdbc.update("""
                INSERT INTO integration.outbox_event (
                    outbox_id, tenant_id, environment_id, event_type, aggregate_type, aggregate_id, correlation_id, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), req.tenantId(), req.environmentId(), "objectives.import_conflict.resolved",
                "ImportConflict", conflictId.toString(), UUID.randomUUID(), CanonicalJson.toJson(req));
        return jdbc.query("SELECT * FROM objectives.import_conflict WHERE conflict_id = ?", MAPPER, conflictId).getFirst();
    }

    public record ImportPreviewRequest(
            @NotNull UUID tenantId, @NotNull UUID environmentId,
            @NotBlank String serviceCanonicalId, @NotBlank String sliName,
            @NotNull BigDecimal localSloTarget, @NotNull BigDecimal incomingSloTarget
    ) {
    }

    public record ResolveConflictRequest(
            @NotNull UUID tenantId, @NotNull UUID environmentId,
            @NotBlank String strategy, @NotBlank String resolvedBy
    ) {
    }

    public record ImportConflictDto(
            UUID conflictId, UUID tenantId, UUID environmentId,
            String serviceCanonicalId, String sliName,
            BigDecimal localSloTarget, BigDecimal incomingSloTarget,
            String resolutionStrategy, String resolvedBy, Instant createdAt
    ) {
    }
}
