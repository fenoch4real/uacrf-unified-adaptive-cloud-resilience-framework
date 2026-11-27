package io.uacrf.recovery.service;

import io.uacrf.crypto.CanonicalJson;
import io.uacrf.recovery.api.RecoveryDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Repository
public class RecoveryRepository {

    private final JdbcTemplate jdbc;

    public RecoveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public KillSwitchDto getKillSwitch(UUID tenantId, UUID environmentId) {
        List<KillSwitchDto> list = jdbc.query("""
                SELECT tenant_id, environment_id, active, reason, updated_by, updated_at
                FROM recovery.kill_switch_state
                WHERE tenant_id = ? AND environment_id = ?
                """, (rs, rowNum) -> new KillSwitchDto(
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getBoolean("active"),
                rs.getString("reason"),
                rs.getString("updated_by"),
                rs.getTimestamp("updated_at").toInstant()
        ), tenantId, environmentId);
        if (list.isEmpty()) {
            return new KillSwitchDto(tenantId, environmentId, false, "Default active", "System", Instant.now());
        }
        return list.getFirst();
    }

    public KillSwitchDto setKillSwitch(UUID tenantId, UUID environmentId, boolean active, String reason, String updatedBy) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO recovery.kill_switch_state (tenant_id, environment_id, active, reason, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, environment_id)
                DO UPDATE SET active = EXCLUDED.active, reason = EXCLUDED.reason,
                              updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                """, tenantId, environmentId, active, reason, updatedBy, Timestamp.from(now));
        return new KillSwitchDto(tenantId, environmentId, active, reason, updatedBy, now);
    }

    public boolean tryConsumeNonce(String nonce, UUID authorizationId) {
        int rows = jdbc.update("""
                INSERT INTO recovery.consumed_nonce (nonce, authorization_id)
                VALUES (?, ?)
                ON CONFLICT (nonce) DO NOTHING
                """, nonce, authorizationId);
        return rows == 1;
    }

    public RecoveryExecutionDto insertExecution(RecoveryExecutionDto dto) {
        jdbc.update("""
                INSERT INTO recovery.recovery_execution (
                    execution_id, tenant_id, environment_id, authorization_id, nonce,
                    service_canonical_id, action_type, adapter_key, target_version,
                    workflow_state, adapter_output, validation_steps_json, compensated,
                    executed_by, started_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """,
                dto.executionId(), dto.tenantId(), dto.environmentId(), dto.authorizationId(),
                dto.nonce(), dto.serviceCanonicalId(), dto.actionType(), dto.adapterKey(),
                dto.targetVersion(), dto.workflowState(), dto.adapterOutput(),
                CanonicalJson.toJson(dto.validationSteps()), dto.compensated(), dto.executedBy(),
                Timestamp.from(dto.startedAt()), Timestamp.from(dto.completedAt()));
        return dto;
    }

    public List<RecoveryExecutionDto> listExecutions(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT execution_id, tenant_id, environment_id, authorization_id, nonce,
                       service_canonical_id, action_type, adapter_key, target_version,
                       workflow_state, adapter_output, validation_steps_json::text AS validation_steps_json,
                       compensated, executed_by, started_at, completed_at
                FROM recovery.recovery_execution
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY started_at DESC
                LIMIT 20
                """, this::mapExecution, tenantId, environmentId);
    }

    public void insertOutbox(UUID tenantId, UUID environmentId, String eventType, String aggregateType,
                             String aggregateId, UUID correlationId, String payloadJson) {
        jdbc.update("""
                INSERT INTO integration.outbox_event (
                    outbox_id, tenant_id, environment_id, event_type, aggregate_type, aggregate_id, correlation_id, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), tenantId, environmentId, eventType, aggregateType, aggregateId, correlationId, payloadJson);
    }

    private RecoveryExecutionDto mapExecution(ResultSet rs, int rowNum) throws SQLException {
        ValidationStepResult[] steps = CanonicalJson.fromJson(rs.getString("validation_steps_json"), ValidationStepResult[].class);
        return new RecoveryExecutionDto(
                rs.getObject("execution_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getObject("authorization_id", UUID.class),
                rs.getString("nonce"),
                rs.getString("service_canonical_id"),
                rs.getString("action_type"),
                rs.getString("adapter_key"),
                rs.getString("target_version"),
                rs.getString("workflow_state"),
                rs.getString("adapter_output"),
                steps != null ? Arrays.asList(steps) : List.of(),
                rs.getBoolean("compensated"),
                rs.getString("executed_by"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at").toInstant()
        );
    }
}
