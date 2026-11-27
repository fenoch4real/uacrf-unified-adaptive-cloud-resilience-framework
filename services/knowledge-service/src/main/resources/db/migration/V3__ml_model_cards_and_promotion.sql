-- AI Model Cards & Gated Adaptive ML Promotion (Section 5.8, FR-ADP-001..002)
CREATE TABLE knowledge.ai_model_card (
    model_card_id          UUID PRIMARY KEY,
    tenant_id              UUID NOT NULL,
    environment_id         UUID NOT NULL,
    model_name             VARCHAR(120) NOT NULL,
    model_version          VARCHAR(60) NOT NULL,
    framework              VARCHAR(60) NOT NULL,
    purpose                TEXT NOT NULL,
    training_dataset_ref   VARCHAR(160) NOT NULL,
    calibration_brier      NUMERIC NOT NULL,
    fairness_gap           NUMERIC NOT NULL,
    drift_score            NUMERIC NOT NULL,
    fallback_strategy      TEXT NOT NULL,
    promotion_stage        VARCHAR(40) NOT NULL DEFAULT 'SHADOW',
    owner_id               UUID NOT NULL,
    approved_by            UUID,
    model_card_digest      VARCHAR(128) NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE knowledge.ai_model_card ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge.ai_model_card FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON knowledge.ai_model_card;
CREATE POLICY tenant_isolation_policy ON knowledge.ai_model_card
    USING (NULLIF(current_setting('app.tenant_id', true), '') IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

INSERT INTO knowledge.ai_model_card (
    model_card_id, tenant_id, environment_id, model_name, model_version, framework,
    purpose, training_dataset_ref, calibration_brier, fairness_gap, drift_score,
    fallback_strategy, promotion_stage, owner_id, model_card_digest
) VALUES (
    '88881111-1111-7111-8111-111111111101', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
    'checkout-fraud-scorer', 'v17', 'PyTorch / ONNX',
    'Score real-time Place Order risk without introducing bias across segments',
    'dataset://fraud-eval-2026-q3@sha256:abc123', 0.06, 0.02, 0.04,
    'Deterministic rule fallback when confidence < 0.7 or latency P95 > 300ms',
    'SHADOW', '33333333-3333-7333-8333-333333333301', 'sha256:seed-fraud-v17'
);
