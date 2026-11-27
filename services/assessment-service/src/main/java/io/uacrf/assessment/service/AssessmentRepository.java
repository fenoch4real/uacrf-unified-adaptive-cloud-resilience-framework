package io.uacrf.assessment.service;

import io.uacrf.assessment.api.AssessmentDtos.*;
import io.uacrf.crypto.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AssessmentRepository {

    private final JdbcTemplate jdbc;

    public AssessmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AssessmentDto> listAssessments(UUID tenantId, UUID environmentId) {
        List<AssessmentHeader> rows = jdbc.query("""
                SELECT assessment_id, tenant_id, environment_id, journey_canonical_id, journey_name,
                       failure_scenario, scoped_services_json::text AS scoped_services_json,
                       recovery_candidate, assessor_id, assessor_name, service_owner_id, service_owner_name,
                       technical_reviewer_name, technical_review_status, independent_reviewer_id,
                       independent_reviewer_name, independent_review_status, report_status,
                       conclusion_status, conclusion_headline, conclusion_detail, limitations_text,
                       recommended_next_step, graph_revision, impact_status, impacted_customer_rate, updated_at
                FROM assessment.resilience_assessment
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY updated_at DESC
                """, this::mapHeader, tenantId, environmentId);
        return rows.stream().map(this::hydrateAssessment).toList();
    }

    public Optional<AssessmentDto> findAssessment(UUID tenantId, UUID environmentId, UUID assessmentId) {
        List<AssessmentHeader> rows = jdbc.query("""
                SELECT assessment_id, tenant_id, environment_id, journey_canonical_id, journey_name,
                       failure_scenario, scoped_services_json::text AS scoped_services_json,
                       recovery_candidate, assessor_id, assessor_name, service_owner_id, service_owner_name,
                       technical_reviewer_name, technical_review_status, independent_reviewer_id,
                       independent_reviewer_name, independent_review_status, report_status,
                       conclusion_status, conclusion_headline, conclusion_detail, limitations_text,
                       recommended_next_step, graph_revision, impact_status, impacted_customer_rate, updated_at
                FROM assessment.resilience_assessment
                WHERE tenant_id = ? AND environment_id = ? AND assessment_id = ?
                """, this::mapHeader, tenantId, environmentId, assessmentId);
        return rows.stream().findFirst().map(this::hydrateAssessment);
    }

    public List<FindingDto> listFindings(UUID assessmentId) {
        return jdbc.query("""
                SELECT finding_id, assessment_id, area_name, bucket, status, summary_title,
                       details, owner_name, due_label, challenged, challenge_note
                FROM assessment.assessment_finding
                WHERE assessment_id = ?
                ORDER BY finding_id
                """, this::mapFinding, assessmentId);
    }

    public List<PathCertificationDto> listPathCertifications(UUID assessmentId) {
        return jdbc.query("""
                SELECT certification_id, assessment_id, service_canonical_id, primary_path_id,
                       alternate_path_id, certification_status, shared_domains_json::text AS shared_domains_json,
                       witness_summary, graph_revision, certified_at
                FROM assessment.path_certification
                WHERE assessment_id = ?
                ORDER BY certified_at DESC
                """, this::mapCertification, assessmentId);
    }

    public PathCertificationDto insertCertification(UUID tenantId, UUID environmentId, UUID assessmentId,
                                                    String serviceCanonicalId, String primaryPathId,
                                                    String alternatePathId, String status,
                                                    List<String> sharedDomains, String witnessSummary,
                                                    String graphRevision) {
        UUID certId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO assessment.path_certification (
                    certification_id, assessment_id, tenant_id, environment_id, service_canonical_id,
                    primary_path_id, alternate_path_id, certification_status, shared_domains_json,
                    witness_summary, graph_revision, certified_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """,
                certId, assessmentId, tenantId, environmentId, serviceCanonicalId,
                primaryPathId, alternatePathId, status, CanonicalJson.toJson(sharedDomains),
                witnessSummary, graphRevision, Timestamp.from(now));
        return new PathCertificationDto(certId, assessmentId, serviceCanonicalId, primaryPathId,
                alternatePathId, status, sharedDomains, witnessSummary, graphRevision, now);
    }

    public void updateTechnicalReview(UUID assessmentId, String reviewerName, String status) {
        jdbc.update("""
                UPDATE assessment.resilience_assessment
                SET technical_reviewer_name = ?, technical_review_status = ?, updated_at = now()
                WHERE assessment_id = ?
                """, reviewerName, status, assessmentId);
    }

    public void updateIndependentReview(UUID assessmentId, UUID reviewerId, String reviewerName, String status) {
        jdbc.update("""
                UPDATE assessment.resilience_assessment
                SET independent_reviewer_id = ?, independent_reviewer_name = ?, independent_review_status = ?, updated_at = now()
                WHERE assessment_id = ?
                """, reviewerId, reviewerName, status, assessmentId);
    }

    public void issueReport(UUID assessmentId, String conclusionStatus, String conclusionHeadline, String reportStatus) {
        jdbc.update("""
                UPDATE assessment.resilience_assessment
                SET conclusion_status = ?, conclusion_headline = ?, report_status = ?, updated_at = now()
                WHERE assessment_id = ?
                """, conclusionStatus, conclusionHeadline, reportStatus, assessmentId);
    }

    public DecisionEnvelopeDto insertEnvelope(UUID assessmentId, UUID tenantId, UUID environmentId,
                                            String graphRevision, String payloadSha256,
                                            String jwsToken, String payloadJson, String sealedBy) {
        UUID envId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO assessment.decision_envelope (
                    envelope_id, assessment_id, tenant_id, environment_id, graph_revision,
                    payload_sha256, jws_token, payload_json, sealed_by, sealed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                envId, assessmentId, tenantId, environmentId, graphRevision,
                payloadSha256, jwsToken, payloadJson, sealedBy, Timestamp.from(now));
        return new DecisionEnvelopeDto(envId, assessmentId, graphRevision, payloadSha256, jwsToken, sealedBy, now);
    }

    public Optional<EnvelopeRow> findEnvelope(UUID tenantId, UUID environmentId, UUID envelopeId) {
        List<EnvelopeRow> list = jdbc.query("""
                SELECT envelope_id, assessment_id, graph_revision, payload_sha256, jws_token,
                       payload_json::text AS payload_json, sealed_by, sealed_at
                FROM assessment.decision_envelope
                WHERE tenant_id = ? AND environment_id = ? AND envelope_id = ?
                """, (rs, rowNum) -> new EnvelopeRow(
                rs.getObject("envelope_id", UUID.class),
                rs.getObject("assessment_id", UUID.class),
                rs.getString("graph_revision"),
                rs.getString("payload_sha256"),
                rs.getString("jws_token"),
                rs.getString("payload_json"),
                rs.getString("sealed_by"),
                rs.getTimestamp("sealed_at").toInstant()
        ), tenantId, environmentId, envelopeId);
        return list.stream().findFirst();
    }

    public Optional<DecisionEnvelopeDto> latestEnvelopeForAssessment(UUID assessmentId) {
        List<DecisionEnvelopeDto> list = jdbc.query("""
                SELECT envelope_id, assessment_id, graph_revision, payload_sha256, jws_token, sealed_by, sealed_at
                FROM assessment.decision_envelope
                WHERE assessment_id = ?
                ORDER BY sealed_at DESC
                LIMIT 1
                """, (rs, rowNum) -> new DecisionEnvelopeDto(
                rs.getObject("envelope_id", UUID.class),
                rs.getObject("assessment_id", UUID.class),
                rs.getString("graph_revision"),
                rs.getString("payload_sha256"),
                rs.getString("jws_token"),
                rs.getString("sealed_by"),
                rs.getTimestamp("sealed_at").toInstant()
        ), assessmentId);
        return list.stream().findFirst();
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
    private AssessmentDto hydrateAssessment(AssessmentHeader h) {
        List<String> services = CanonicalJson.fromJson(h.scopedServicesJson(), List.class);
        return new AssessmentDto(
                h.assessmentId(), h.tenantId(), h.environmentId(), h.journeyCanonicalId(), h.journeyName(),
                h.failureScenario(), services, h.recoveryCandidate(), h.assessorId(), h.assessorName(),
                h.serviceOwnerId(), h.serviceOwnerName(), h.technicalReviewerName(), h.technicalReviewStatus(),
                h.independentReviewerId(), h.independentReviewerName(), h.independentReviewStatus(),
                h.reportStatus(), h.conclusionStatus(), h.conclusionHeadline(), h.conclusionDetail(),
                h.limitationsText(), h.recommendedNextStep(), h.graphRevision(), h.impactStatus(),
                h.impactedCustomerRate(), listFindings(h.assessmentId()),
                listPathCertifications(h.assessmentId()),
                latestEnvelopeForAssessment(h.assessmentId()).orElse(null),
                h.updatedAt()
        );
    }

    private AssessmentHeader mapHeader(ResultSet rs, int rowNum) throws SQLException {
        return new AssessmentHeader(
                rs.getObject("assessment_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getString("journey_canonical_id"),
                rs.getString("journey_name"),
                rs.getString("failure_scenario"),
                rs.getString("scoped_services_json"),
                rs.getString("recovery_candidate"),
                rs.getObject("assessor_id", UUID.class),
                rs.getString("assessor_name"),
                rs.getObject("service_owner_id", UUID.class),
                rs.getString("service_owner_name"),
                rs.getString("technical_reviewer_name"),
                rs.getString("technical_review_status"),
                rs.getObject("independent_reviewer_id", UUID.class),
                rs.getString("independent_reviewer_name"),
                rs.getString("independent_review_status"),
                rs.getString("report_status"),
                rs.getString("conclusion_status"),
                rs.getString("conclusion_headline"),
                rs.getString("conclusion_detail"),
                rs.getString("limitations_text"),
                rs.getString("recommended_next_step"),
                rs.getString("graph_revision"),
                rs.getString("impact_status"),
                rs.getString("impacted_customer_rate"),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private FindingDto mapFinding(ResultSet rs, int rowNum) throws SQLException {
        return new FindingDto(
                rs.getObject("finding_id", UUID.class),
                rs.getObject("assessment_id", UUID.class),
                rs.getString("area_name"),
                rs.getString("bucket"),
                rs.getString("status"),
                rs.getString("summary_title"),
                rs.getString("details"),
                rs.getString("owner_name"),
                rs.getString("due_label"),
                rs.getBoolean("challenged"),
                rs.getString("challenge_note")
        );
    }

    @SuppressWarnings("unchecked")
    private PathCertificationDto mapCertification(ResultSet rs, int rowNum) throws SQLException {
        List<String> shared = CanonicalJson.fromJson(rs.getString("shared_domains_json"), List.class);
        return new PathCertificationDto(
                rs.getObject("certification_id", UUID.class),
                rs.getObject("assessment_id", UUID.class),
                rs.getString("service_canonical_id"),
                rs.getString("primary_path_id"),
                rs.getString("alternate_path_id"),
                rs.getString("certification_status"),
                shared,
                rs.getString("witness_summary"),
                rs.getString("graph_revision"),
                rs.getTimestamp("certified_at").toInstant()
        );
    }

    public record EnvelopeRow(
            UUID envelopeId, UUID assessmentId, String graphRevision,
            String payloadSha256, String jwsToken, String payloadJson,
            String sealedBy, Instant sealedAt
    ) {
    }

    private record AssessmentHeader(
            UUID assessmentId, UUID tenantId, UUID environmentId, String journeyCanonicalId,
            String journeyName, String failureScenario, String scopedServicesJson,
            String recoveryCandidate, UUID assessorId, String assessorName, UUID serviceOwnerId,
            String serviceOwnerName, String technicalReviewerName, String technicalReviewStatus,
            UUID independentReviewerId, String independentReviewerName, String independentReviewStatus,
            String reportStatus, String conclusionStatus, String conclusionHeadline,
            String conclusionDetail, String limitationsText, String recommendedNextStep,
            String graphRevision, String impactStatus, String impactedCustomerRate, Instant updatedAt
    ) {
    }
}
