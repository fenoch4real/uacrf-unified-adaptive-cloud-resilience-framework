package io.uacrf.policy.service;

import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;
import io.uacrf.crypto.CanonicalJson;
import io.uacrf.policy.api.PolicyDtos.IssuedAuthorizationDto;
import io.uacrf.policy.api.PolicyDtos.ProposalDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PolicyRepository {

    private final JdbcTemplate jdbc;

    public PolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProposalDto> listProposals(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT proposal_id, tenant_id, environment_id, service_canonical_id, action_type,
                       summary_title, target_version, reversible, blast_radius_count,
                       proposer_id, proposer_name, approver_id, approver_name, approval_status,
                       policy_outcome, policy_reasons_json::text AS policy_reasons_json,
                       graph_revision, subgraph_digest, evidence_qualification, rationale, updated_at
                FROM policy.recovery_proposal
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY updated_at DESC
                """, this::mapProposal, tenantId, environmentId);
    }

    public Optional<ProposalDto> findProposal(UUID tenantId, UUID environmentId, UUID proposalId) {
        return jdbc.query("""
                SELECT proposal_id, tenant_id, environment_id, service_canonical_id, action_type,
                       summary_title, target_version, reversible, blast_radius_count,
                       proposer_id, proposer_name, approver_id, approver_name, approval_status,
                       policy_outcome, policy_reasons_json::text AS policy_reasons_json,
                       graph_revision, subgraph_digest, evidence_qualification, rationale, updated_at
                FROM policy.recovery_proposal
                WHERE tenant_id = ? AND environment_id = ? AND proposal_id = ?
                """, this::mapProposal, tenantId, environmentId, proposalId).stream().findFirst();
    }

    public ProposalDto insertProposal(ProposalDto dto) {
        jdbc.update("""
                INSERT INTO policy.recovery_proposal (
                    proposal_id, tenant_id, environment_id, service_canonical_id, action_type,
                    summary_title, target_version, reversible, blast_radius_count,
                    proposer_id, proposer_name, approval_status, policy_outcome, policy_reasons_json,
                    graph_revision, subgraph_digest, evidence_qualification, rationale
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """,
                dto.proposalId(), dto.tenantId(), dto.environmentId(), dto.serviceCanonicalId(),
                dto.actionType(), dto.summaryTitle(), dto.targetVersion(), dto.reversible(),
                dto.blastRadiusCount(), dto.proposerId(), dto.proposerName(), dto.approvalStatus(),
                dto.policyOutcome(), CanonicalJson.toJson(dto.policyReasons()),
                dto.graphRevision(), dto.subgraphDigest(), dto.evidenceQualification(), dto.rationale());
        return dto;
    }

    public void updateApproval(UUID proposalId, UUID approverId, String approverName, String approvalStatus) {
        jdbc.update("""
                UPDATE policy.recovery_proposal
                SET approver_id = ?, approver_name = ?, approval_status = ?, updated_at = now()
                WHERE proposal_id = ?
                """, approverId, approverName, approvalStatus, proposalId);
    }

    public void insertAuthorization(IssuedAuthorizationDto auth) {
        jdbc.update("""
                INSERT INTO policy.bounded_authorization (
                    authorization_id, proposal_id, tenant_id, environment_id, nonce, audience,
                    graph_revision, delta_classification, jws_token, envelope_json, consumed, issued_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """,
                auth.authorizationId(), auth.proposalId(),
                UUID.fromString(auth.envelope().tenantId()),
                UUID.fromString(auth.envelope().environmentId()),
                auth.nonce(), auth.audience(), auth.envelope().graphRevision(),
                auth.deltaClassification(), auth.jwsToken(), CanonicalJson.toJson(auth.envelope()),
                auth.consumed(), Timestamp.from(auth.envelope().issuedAt()), Timestamp.from(auth.expiresAt()));
    }

    public int consumeNonce(String nonce) {
        return jdbc.update("""
                UPDATE policy.bounded_authorization
                SET consumed = TRUE
                WHERE nonce = ? AND consumed = FALSE AND expires_at > now()
                """, nonce);
    }

    public List<IssuedAuthorizationDto> listAuthorizations(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT authorization_id, proposal_id, nonce, audience, delta_classification,
                       consumed, jws_token, envelope_json::text AS envelope_json, expires_at
                FROM policy.bounded_authorization
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY issued_at DESC
                """, (rs, rowNum) -> new IssuedAuthorizationDto(
                rs.getObject("authorization_id", UUID.class),
                rs.getObject("proposal_id", UUID.class),
                rs.getString("nonce"),
                rs.getString("audience"),
                rs.getString("delta_classification"),
                rs.getBoolean("consumed"),
                rs.getString("jws_token"),
                CanonicalJson.fromJson(rs.getString("envelope_json"), BoundedRecoveryAuthorization.class),
                rs.getTimestamp("expires_at").toInstant()
        ), tenantId, environmentId);
    }

    public void insertOutbox(UUID tenantId, UUID environmentId, String eventType, String aggregateType,
                             String aggregateId, UUID correlationId, String payloadJson) {
        jdbc.update("""
                INSERT INTO integration.outbox_event (
                    outbox_id, tenant_id, environment_id, event_type, aggregate_type, aggregate_id, correlation_id, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), tenantId, environmentId, eventType, aggregateType, aggregateId, correlationId, payloadJson);
    }

    @SuppressWarnings("unchecked")
    private ProposalDto mapProposal(ResultSet rs, int rowNum) throws SQLException {
        List<String> reasons = CanonicalJson.fromJson(rs.getString("policy_reasons_json"), List.class);
        return new ProposalDto(
                rs.getObject("proposal_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getString("service_canonical_id"),
                rs.getString("action_type"),
                rs.getString("summary_title"),
                rs.getString("target_version"),
                rs.getBoolean("reversible"),
                rs.getInt("blast_radius_count"),
                rs.getObject("proposer_id", UUID.class),
                rs.getString("proposer_name"),
                rs.getObject("approver_id", UUID.class),
                rs.getString("approver_name"),
                rs.getString("approval_status"),
                rs.getString("policy_outcome"),
                reasons,
                rs.getString("graph_revision"),
                rs.getString("subgraph_digest"),
                rs.getString("evidence_qualification"),
                rs.getString("rationale"),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}
