package io.uacrf.objectives.service;

import io.uacrf.crypto.CanonicalJson;
import io.uacrf.objectives.api.ObjectivesDtos.*;
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
public class ObjectivesRepository {

    private final JdbcTemplate jdbc;

    public ObjectivesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ObjectiveDto> listObjectives(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT objective_id, tenant_id, environment_id, service_canonical_id, journey_name,
                       sli_name, slo_target, unit, rto_minutes, rpo_minutes, rule_pack_version
                FROM objectives.service_objective
                WHERE tenant_id = ? AND environment_id = ?
                """, this::mapObjective, tenantId, environmentId);
    }

    public List<RiskEvaluationDto> listRiskEvaluations(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT evaluation_id, tenant_id, environment_id, service_canonical_id, service_name,
                       journey_name, owner_name, risk_state, observed_sli_value, delta_vs_baseline,
                       triggered_rules_json::text AS triggered_rules_json, explanation_summary,
                       timeline_json::text AS timeline_json, evaluated_at
                FROM objectives.risk_evaluation
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY evaluated_at DESC
                LIMIT 20
                """, this::mapEvaluation, tenantId, environmentId);
    }

    public RiskEvaluationDto insertRiskEvaluation(RiskEvaluationDto dto) {
        jdbc.update("""
                INSERT INTO objectives.risk_evaluation (
                    evaluation_id, tenant_id, environment_id, service_canonical_id, service_name,
                    journey_name, owner_name, risk_state, observed_sli_value, delta_vs_baseline,
                    triggered_rules_json, explanation_summary, timeline_json, evaluated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?)
                """,
                dto.evaluationId(), dto.tenantId(), dto.environmentId(), dto.serviceCanonicalId(),
                dto.serviceName(), dto.journeyName(), dto.ownerName(), dto.riskState(),
                dto.observedSliValue(), dto.deltaVsBaseline(), CanonicalJson.toJson(dto.triggeredRules()),
                dto.explanationSummary(), CanonicalJson.toJson(dto.timeline()), Timestamp.from(dto.evaluatedAt()));
        return dto;
    }

    public void insertOutbox(UUID tenantId, UUID environmentId, String eventType, String aggregateType,
                             String aggregateId, UUID correlationId, String payloadJson) {
        jdbc.update("""
                INSERT INTO integration.outbox_event (
                    outbox_id, tenant_id, environment_id, event_type, aggregate_type, aggregate_id, correlation_id, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), tenantId, environmentId, eventType, aggregateType, aggregateId, correlationId, payloadJson);
    }

    private ObjectiveDto mapObjective(ResultSet rs, int rowNum) throws SQLException {
        return new ObjectiveDto(
                rs.getObject("objective_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getString("service_canonical_id"),
                rs.getString("journey_name"),
                rs.getString("sli_name"),
                rs.getDouble("slo_target"),
                rs.getString("unit"),
                rs.getInt("rto_minutes"),
                rs.getInt("rpo_minutes"),
                rs.getString("rule_pack_version")
        );
    }

    private RiskEvaluationDto mapEvaluation(ResultSet rs, int rowNum) throws SQLException {
        TriggeredRuleDto[] rules = CanonicalJson.fromJson(rs.getString("triggered_rules_json"), TriggeredRuleDto[].class);
        TimelineEventDto[] timeline = CanonicalJson.fromJson(rs.getString("timeline_json"), TimelineEventDto[].class);
        return new RiskEvaluationDto(
                rs.getObject("evaluation_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getString("service_canonical_id"),
                rs.getString("service_name"),
                rs.getString("journey_name"),
                rs.getString("owner_name"),
                rs.getString("risk_state"),
                rs.getDouble("observed_sli_value"),
                rs.getString("delta_vs_baseline"),
                rules != null ? Arrays.asList(rules) : List.of(),
                rs.getString("explanation_summary"),
                timeline != null ? Arrays.asList(timeline) : List.of(),
                rs.getTimestamp("evaluated_at").toInstant()
        );
    }
}
