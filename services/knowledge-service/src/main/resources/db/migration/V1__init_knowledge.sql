CREATE SCHEMA IF NOT EXISTS knowledge;
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE knowledge.knowledge_article (
    article_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    doc_code VARCHAR(40) NOT NULL,
    doc_kind VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content_summary TEXT NOT NULL,
    sha256_digest VARCHAR(128) NOT NULL,
    author_id UUID NOT NULL,
    author_name VARCHAR(120) NOT NULL,
    reviewer_id UUID,
    reviewer_name VARCHAR(120),
    status VARCHAR(32) NOT NULL DEFAULT 'QUARANTINED',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE knowledge.learning_item (
    learning_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    incident_ref VARCHAR(80) NOT NULL,
    title VARCHAR(200) NOT NULL,
    target_artifact_type VARCHAR(64) NOT NULL,
    verification_digest VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE knowledge.resilience_experiment (
    experiment_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    fault_type VARCHAR(64) NOT NULL,
    target_canonical_id VARCHAR(200) NOT NULL,
    max_blast_radius INT NOT NULL,
    abort_slo_threshold DOUBLE PRECISION NOT NULL,
    status VARCHAR(32) NOT NULL,
    observed_sli DOUBLE PRECISION,
    outcome_summary VARCHAR(255) NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL DEFAULT now()
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

INSERT INTO knowledge.knowledge_article (
    article_id, tenant_id, environment_id, doc_code, doc_kind, title,
    content_summary, sha256_digest, author_id, author_name, reviewer_id, reviewer_name, status
) VALUES
    ('eeee1111-1111-7111-8111-111111111101',
     '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'OPS-2210', 'RUNBOOK', 'Rollback procedure for service v43',
     'Verify schema backwards compatibility and capacity headroom on v42 replica set before shifting traffic.',
     'sha256:ops-2210-v43', '33333333-3333-7333-8333-333333333301', 'Luis Romero',
     '33333333-3333-7333-8333-333333333303', 'Maya Chen', 'APPROVED'),
    ('eeee1111-1111-7111-8111-111111111102',
     '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'ARCH-1187', 'ADR', 'Compatibility check requirement',
     'A retained deployment artifact cannot establish runtime compatibility without a live compatibility verification.',
     'sha256:arch-1187-compat', '33333333-3333-7333-8333-333333333302', 'Daniel Reed',
     '33333333-3333-7333-8333-333333333304', 'Aisha Patel', 'APPROVED');

INSERT INTO knowledge.learning_item (
    learning_id, tenant_id, environment_id, incident_ref, title, target_artifact_type, status
) VALUES (
    'eeee2222-2222-7222-8222-222222222201',
    '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
    'INC-v43-CHECKOUT', 'Add pre-rollout synthetic Place Order canary gate to CI pipeline', 'RULE_PACK', 'OPEN'
);
