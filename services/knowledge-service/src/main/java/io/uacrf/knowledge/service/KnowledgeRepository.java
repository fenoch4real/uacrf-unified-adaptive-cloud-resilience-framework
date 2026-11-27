package io.uacrf.knowledge.service;

import io.uacrf.knowledge.api.KnowledgeDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class KnowledgeRepository {

    private final JdbcTemplate jdbc;

    public KnowledgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ArticleDto> listArticles(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT article_id, tenant_id, environment_id, doc_code, doc_kind, title,
                       content_summary, sha256_digest, author_id, author_name,
                       reviewer_id, reviewer_name, status, updated_at
                FROM knowledge.knowledge_article
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY doc_code
                """, this::mapArticle, tenantId, environmentId);
    }

    public Optional<ArticleDto> findArticle(UUID tenantId, UUID environmentId, UUID articleId) {
        return jdbc.query("""
                SELECT article_id, tenant_id, environment_id, doc_code, doc_kind, title,
                       content_summary, sha256_digest, author_id, author_name,
                       reviewer_id, reviewer_name, status, updated_at
                FROM knowledge.knowledge_article
                WHERE tenant_id = ? AND environment_id = ? AND article_id = ?
                """, this::mapArticle, tenantId, environmentId, articleId).stream().findFirst();
    }

    public ArticleDto insertArticle(ArticleDto dto) {
        jdbc.update("""
                INSERT INTO knowledge.knowledge_article (
                    article_id, tenant_id, environment_id, doc_code, doc_kind, title,
                    content_summary, sha256_digest, author_id, author_name, status, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                dto.articleId(), dto.tenantId(), dto.environmentId(), dto.docCode(), dto.docKind(),
                dto.title(), dto.contentSummary(), dto.sha256Digest(), dto.authorId(),
                dto.authorName(), dto.status(), Timestamp.from(dto.updatedAt()));
        return dto;
    }

    public void updateArticleStatus(UUID articleId, UUID reviewerId, String reviewerName, String status) {
        jdbc.update("""
                UPDATE knowledge.knowledge_article
                SET reviewer_id = ?, reviewer_name = ?, status = ?, updated_at = now()
                WHERE article_id = ?
                """, reviewerId, reviewerName, status, articleId);
    }

    public List<LearningItemDto> listLearningItems(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT learning_id, tenant_id, environment_id, incident_ref, title,
                       target_artifact_type, verification_digest, status, updated_at
                FROM knowledge.learning_item
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY updated_at DESC
                """, this::mapLearning, tenantId, environmentId);
    }

    public void closeLearningItem(UUID tenantId, UUID environmentId, UUID learningId, String digest) {
        jdbc.update("""
                UPDATE knowledge.learning_item
                SET verification_digest = ?, status = 'CLOSED', updated_at = now()
                WHERE tenant_id = ? AND environment_id = ? AND learning_id = ?
                """, digest, tenantId, environmentId, learningId);
    }

    public ExperimentDto insertExperiment(ExperimentDto dto) {
        jdbc.update("""
                INSERT INTO knowledge.resilience_experiment (
                    experiment_id, tenant_id, environment_id, title, fault_type, target_canonical_id,
                    max_blast_radius, abort_slo_threshold, status, observed_sli, outcome_summary, executed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                dto.experimentId(), dto.tenantId(), dto.environmentId(), dto.title(), dto.faultType(),
                dto.targetCanonicalId(), dto.maxBlastRadius(), dto.abortSloThreshold(),
                dto.status(), dto.observedSli(), dto.outcomeSummary(), Timestamp.from(dto.executedAt()));
        return dto;
    }

    public List<ExperimentDto> listExperiments(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT experiment_id, tenant_id, environment_id, title, fault_type, target_canonical_id,
                       max_blast_radius, abort_slo_threshold, status, observed_sli, outcome_summary, executed_at
                FROM knowledge.resilience_experiment
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY executed_at DESC
                """, this::mapExperiment, tenantId, environmentId);
    }

    public void insertOutbox(UUID tenantId, UUID environmentId, String eventType, String aggregateType,
                             String aggregateId, UUID correlationId, String payloadJson) {
        jdbc.update("""
                INSERT INTO integration.outbox_event (
                    outbox_id, tenant_id, environment_id, event_type, aggregate_type, aggregate_id, correlation_id, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), tenantId, environmentId, eventType, aggregateType, aggregateId, correlationId, payloadJson);
    }

    private ArticleDto mapArticle(ResultSet rs, int rowNum) throws SQLException {
        return new ArticleDto(
                rs.getObject("article_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getString("doc_code"),
                rs.getString("doc_kind"),
                rs.getString("title"),
                rs.getString("content_summary"),
                rs.getString("sha256_digest"),
                rs.getObject("author_id", UUID.class),
                rs.getString("author_name"),
                rs.getObject("reviewer_id", UUID.class),
                rs.getString("reviewer_name"),
                rs.getString("status"),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private LearningItemDto mapLearning(ResultSet rs, int rowNum) throws SQLException {
        return new LearningItemDto(
                rs.getObject("learning_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getString("incident_ref"),
                rs.getString("title"),
                rs.getString("target_artifact_type"),
                rs.getString("verification_digest"),
                rs.getString("status"),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private ExperimentDto mapExperiment(ResultSet rs, int rowNum) throws SQLException {
        return new ExperimentDto(
                rs.getObject("experiment_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getString("title"),
                rs.getString("fault_type"),
                rs.getString("target_canonical_id"),
                rs.getInt("max_blast_radius"),
                rs.getDouble("abort_slo_threshold"),
                rs.getString("status"),
                rs.getDouble("observed_sli"),
                rs.getString("outcome_summary"),
                rs.getTimestamp("executed_at").toInstant()
        );
    }
}
