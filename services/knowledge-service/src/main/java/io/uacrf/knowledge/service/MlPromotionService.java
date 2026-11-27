package io.uacrf.knowledge.service;

import io.uacrf.crypto.CanonicalJson;
import io.uacrf.knowledge.api.KnowledgeDomainException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AI Model Card Registry & Gated Adaptive ML Promotion Engine (Section 5.8, FR-ADP-001..002).
 */
@Service
public class MlPromotionService {

    private static final BigDecimal MAX_BRIER = new BigDecimal("0.12");
    private static final BigDecimal MAX_FAIRNESS_GAP = new BigDecimal("0.05");
    private static final BigDecimal MAX_DRIFT = new BigDecimal("0.15");

    private final JdbcTemplate jdbc;

    public MlPromotionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ModelCardDto> MAPPER = (rs, rowNum) -> new ModelCardDto(
            rs.getObject("model_card_id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("environment_id", UUID.class),
            rs.getString("model_name"),
            rs.getString("model_version"),
            rs.getString("framework"),
            rs.getString("purpose"),
            rs.getString("training_dataset_ref"),
            rs.getBigDecimal("calibration_brier"),
            rs.getBigDecimal("fairness_gap"),
            rs.getBigDecimal("drift_score"),
            rs.getString("fallback_strategy"),
            rs.getString("promotion_stage"),
            rs.getObject("owner_id", UUID.class),
            rs.getObject("approved_by", UUID.class),
            rs.getString("model_card_digest"),
            rs.getTimestamp("created_at").toInstant()
    );

    public List<ModelCardDto> listModelCards(UUID tenantId, UUID environmentId) {
        return jdbc.query("""
                SELECT * FROM knowledge.ai_model_card
                WHERE tenant_id = ? AND environment_id = ?
                ORDER BY created_at DESC
                """, MAPPER, tenantId, environmentId);
    }

    @Transactional
    public ModelCardDto registerModelCard(UUID ownerId, RegisterModelCardRequest req) {
        UUID id = UUID.randomUUID();
        String digest = CanonicalJson.digestObject(req);
        jdbc.update("""
                INSERT INTO knowledge.ai_model_card (
                    model_card_id, tenant_id, environment_id, model_name, model_version, framework,
                    purpose, training_dataset_ref, calibration_brier, fairness_gap, drift_score,
                    fallback_strategy, promotion_stage, owner_id, model_card_digest
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SHADOW', ?, ?)
                """,
                id, req.tenantId(), req.environmentId(), req.modelName(), req.modelVersion(), req.framework(),
                req.purpose(), req.trainingDatasetRef(), req.calibrationBrier(), req.fairnessGap(),
                req.driftScore(), req.fallbackStrategy(), ownerId, digest);
        return getModelCard(id);
    }

    @Transactional
    public ModelCardDto promoteModel(UUID modelCardId, UUID approverId, String targetStage) {
        ModelCardDto card = getModelCard(modelCardId);
        if (approverId.equals(card.ownerId())) {
            throw new KnowledgeDomainException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN",
                    "Model owner cannot approve promotion of their own ML model.");
        }
        if ("BOUNDED_AUTOMATION".equalsIgnoreCase(targetStage)) {
            if (card.calibrationBrier().compareTo(MAX_BRIER) > 0
                    || card.fairnessGap().compareTo(MAX_FAIRNESS_GAP) > 0
                    || card.driftScore().compareTo(MAX_DRIFT) > 0
                    || card.fallbackStrategy().isBlank()) {
                throw new KnowledgeDomainException(HttpStatus.CONFLICT, "PROMOTION_GATE_FAILED",
                        "Model fails empirical safety, calibration, fairness, or drift promotion gates.");
            }
        }
        jdbc.update("""
                UPDATE knowledge.ai_model_card
                SET promotion_stage = ?, approved_by = ?
                WHERE model_card_id = ?
                """, targetStage.toUpperCase(), approverId, modelCardId);
        return getModelCard(modelCardId);
    }

    private ModelCardDto getModelCard(UUID modelCardId) {
        var list = jdbc.query("SELECT * FROM knowledge.ai_model_card WHERE model_card_id = ?", MAPPER, modelCardId);
        if (list.isEmpty()) {
            throw new KnowledgeDomainException(HttpStatus.NOT_FOUND, "MODEL_CARD_NOT_FOUND", "Model card not found");
        }
        return list.getFirst();
    }

    public record RegisterModelCardRequest(
            @NotNull UUID tenantId, @NotNull UUID environmentId,
            @NotBlank String modelName, @NotBlank String modelVersion,
            @NotBlank String framework, @NotBlank String purpose,
            @NotBlank String trainingDatasetRef, @NotNull BigDecimal calibrationBrier,
            @NotNull BigDecimal fairnessGap, @NotNull BigDecimal driftScore,
            @NotBlank String fallbackStrategy
    ) {
    }

    public record ModelCardDto(
            UUID modelCardId, UUID tenantId, UUID environmentId,
            String modelName, String modelVersion, String framework, String purpose,
            String trainingDatasetRef, BigDecimal calibrationBrier, BigDecimal fairnessGap,
            BigDecimal driftScore, String fallbackStrategy, String promotionStage,
            UUID ownerId, UUID approvedBy, String modelCardDigest, Instant createdAt
    ) {
    }
}
