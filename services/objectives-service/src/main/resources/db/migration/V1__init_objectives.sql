CREATE SCHEMA IF NOT EXISTS objectives;
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE objectives.service_objective (
    objective_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    service_canonical_id VARCHAR(200) NOT NULL,
    journey_name VARCHAR(160) NOT NULL,
    sli_name VARCHAR(120) NOT NULL,
    slo_target DOUBLE PRECISION NOT NULL,
    unit VARCHAR(32) NOT NULL,
    rto_minutes INT NOT NULL,
    rpo_minutes INT NOT NULL,
    rule_pack_version VARCHAR(40) NOT NULL DEFAULT 'core-v1.0',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE objectives.risk_evaluation (
    evaluation_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    service_canonical_id VARCHAR(200) NOT NULL,
    service_name VARCHAR(160) NOT NULL,
    journey_name VARCHAR(160) NOT NULL,
    owner_name VARCHAR(120) NOT NULL,
    risk_state VARCHAR(32) NOT NULL,
    observed_sli_value DOUBLE PRECISION NOT NULL,
    delta_vs_baseline VARCHAR(64) NOT NULL,
    triggered_rules_json JSONB NOT NULL,
    explanation_summary VARCHAR(255) NOT NULL,
    timeline_json JSONB NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE integration.outbox_event (
    outbox_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id VARCHAR(120) NOT NULL,
    correlation_id UUID NOT NULL,
    payload_json JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

INSERT INTO objectives.service_objective (
    objective_id, tenant_id, environment_id, service_canonical_id, journey_name,
    sli_name, slo_target, unit, rto_minutes, rpo_minutes
) VALUES (
    'cccc1111-1111-7111-8111-111111111101',
    '11111111-1111-7111-8111-111111111111',
    '22222222-2222-7222-8222-222222222222',
    'urn:uacrf:svc:checkout', 'Place Order', 'Order success', 99.5, '%', 15, 5
);

INSERT INTO objectives.risk_evaluation (
    evaluation_id, tenant_id, environment_id, service_canonical_id, service_name,
    journey_name, owner_name, risk_state, observed_sli_value, delta_vs_baseline,
    triggered_rules_json, explanation_summary, timeline_json
) VALUES (
    'cccc2222-2222-7222-8222-222222222201',
    '11111111-1111-7111-8111-111111111111',
    '22222222-2222-7222-8222-222222222222',
    'urn:uacrf:svc:checkout', 'Checkout service', 'Place Order', 'Maya Chen',
    'ELEVATED', 81.6, '-12.4 pp vs. previous hour',
    '[{"ruleId":"APP-ERR-001","layer":"APPLICATION","severity":"ELEVATED","detail":"Order success dropped to 81.6% (18.4% error rate after deployment v43)"}]'::jsonb,
    'Order failures increased after deployment v43.',
    '[{"time":"10:14","title":"Deployment v43 detected","detail":"Checkout service v43 deployed to nonproduction cluster"},{"time":"10:15","title":"Error-rate increase confirmed","detail":"Order failure rate increased from 1.2% to 18.4%"},{"time":"10:16","title":"Rollback proposed for review","detail":"Prepared rollback to v42 with scoped impact assessment"}]'::jsonb
);
