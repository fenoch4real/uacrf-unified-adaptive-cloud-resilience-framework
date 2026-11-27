package io.uacrf.advisory.service;

import io.uacrf.advisory.api.AdvisoryDtos.*;
import io.uacrf.crypto.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Repository
public class AdvisoryRepository {

    private final JdbcTemplate jdbc;

    public AdvisoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AdvisoryConfigDto getConfig(UUID tenantId, UUID environmentId) {
        List<AdvisoryConfigDto> list = jdbc.query("""
                SELECT tenant_id, environment_id, advisory_enabled, max_token_budget, tokens_used
                FROM advisory.advisory_config
                WHERE tenant_id = ? AND environment_id = ?
                """, (rs, rowNum) -> new AdvisoryConfigDto(
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getBoolean("advisory_enabled"),
                rs.getInt("max_token_budget"),
                rs.getInt("tokens_used")
        ), tenantId, environmentId);
        return list.isEmpty()
                ? new AdvisoryConfigDto(tenantId, environmentId, true, 8000, 0)
                : list.getFirst();
    }

    public void setEnabled(UUID tenantId, UUID environmentId, boolean enabled) {
        jdbc.update("""
                INSERT INTO advisory.advisory_config (tenant_id, environment_id, advisory_enabled, max_token_budget, tokens_used)
                VALUES (?, ?, ?, 8000, 0)
                ON CONFLICT (tenant_id, environment_id)
                DO UPDATE SET advisory_enabled = EXCLUDED.advisory_enabled, updated_at = now()
                """, tenantId, environmentId, enabled);
    }

    public void addTokensUsed(UUID tenantId, UUID environmentId, int delta) {
        jdbc.update("""
                UPDATE advisory.advisory_config
                SET tokens_used = tokens_used + ?, updated_at = now()
                WHERE tenant_id = ? AND environment_id = ?
                """, delta, tenantId, environmentId);
    }

    public List<AdvisoryFindingDto> listFindings(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT finding_id, tenant_id, environment_id, case_code, context_ref, producer_role,
                       model_version, prompt_version, headline, finding_body, citations_json::text AS citations_json,
                       critic_dissent_headline, critic_dissent_body, critic_severity, critic_status,
                       human_reviewer, review_decision, updated_at
                FROM advisory.advisory_finding
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY updated_at DESC
                """, this::mapFinding, tenantId, environmentId);
    }

    public AdvisoryFindingDto insertFinding(AdvisoryFindingDto dto) {
        jdbc.update("""
                INSERT INTO advisory.advisory_finding (
                    finding_id, tenant_id, environment_id, case_code, context_ref, producer_role,
                    model_version, prompt_version, headline, finding_body, citations_json,
                    critic_dissent_headline, critic_dissent_body, critic_severity, critic_status,
                    human_reviewer, review_decision, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
                """,
                dto.findingId(), dto.tenantId(), dto.environmentId(), dto.caseCode(), dto.contextRef(),
                dto.producerRole(), dto.modelVersion(), dto.promptVersion(), dto.headline(),
                dto.findingBody(), CanonicalJson.toJson(dto.citations()), dto.criticDissentHeadline(),
                dto.criticDissentBody(), dto.criticSeverity(), dto.criticStatus(),
                dto.humanReviewer(), dto.reviewDecision(), Timestamp.from(dto.updatedAt()));
        return dto;
    }

    public int updateReviewDecision(UUID tenantId, UUID environmentId, UUID findingId, String decision, String editedBody) {
        if (editedBody != null && !editedBody.isBlank()) {
            return jdbc.update("""
                    UPDATE advisory.advisory_finding
                    SET review_decision = ?, finding_body = ?, updated_at = now()
                    WHERE tenant_id = ? AND environment_id = ? AND finding_id = ?
                    """, decision, editedBody, tenantId, environmentId, findingId);
        }
        return jdbc.update("""
                UPDATE advisory.advisory_finding
                SET review_decision = ?, updated_at = now()
                WHERE tenant_id = ? AND environment_id = ? AND finding_id = ?
                """, decision, tenantId, environmentId, findingId);
    }

    public void insertOutbox(UUID tenantId, UUID environmentId, String eventType, String aggregateType,
                             String aggregateId, UUID correlationId, String payloadJson) {
        jdbc.update("""
                INSERT INTO integration.outbox_event (
                    outbox_id, tenant_id, environment_id, event_type, aggregate_type, aggregate_id, correlation_id, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), tenantId, environmentId, eventType, aggregateType, aggregateId, correlationId, payloadJson);
    }

    private AdvisoryFindingDto mapFinding(ResultSet rs, int rowNum) throws SQLException {
        CitationDto[] citations = CanonicalJson.fromJson(rs.getString("citations_json"), CitationDto[].class);
        return new AdvisoryFindingDto(
                rs.getObject("finding_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getString("case_code"),
                rs.getString("context_ref"),
                rs.getString("producer_role"),
                rs.getString("model_version"),
                rs.getString("prompt_version"),
                rs.getString("headline"),
                rs.getString("finding_body"),
                citations != null ? Arrays.asList(citations) : List.of(),
                rs.getString("critic_dissent_headline"),
                rs.getString("critic_dissent_body"),
                rs.getString("critic_severity"),
                rs.getString("critic_status"),
                rs.getString("human_reviewer"),
                rs.getString("review_decision"),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}
