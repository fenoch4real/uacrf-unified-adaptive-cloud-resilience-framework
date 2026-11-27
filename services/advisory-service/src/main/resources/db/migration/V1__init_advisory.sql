CREATE SCHEMA IF NOT EXISTS advisory;
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE advisory.advisory_config (
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    advisory_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    max_token_budget INT NOT NULL DEFAULT 8000,
    tokens_used INT NOT NULL DEFAULT 1240,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id)
);

CREATE TABLE advisory.advisory_finding (
    finding_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    case_code VARCHAR(40) NOT NULL,
    context_ref VARCHAR(120) NOT NULL,
    producer_role VARCHAR(80) NOT NULL,
    model_version VARCHAR(40) NOT NULL,
    prompt_version VARCHAR(40) NOT NULL,
    headline VARCHAR(200) NOT NULL,
    finding_body TEXT NOT NULL,
    citations_json JSONB NOT NULL,
    critic_dissent_headline VARCHAR(200) NOT NULL,
    critic_dissent_body TEXT NOT NULL,
    critic_severity VARCHAR(40) NOT NULL DEFAULT 'BLOCKING_CONCERN',
    critic_status VARCHAR(40) NOT NULL DEFAULT 'UNRESOLVED',
    human_reviewer VARCHAR(120) NOT NULL,
    review_decision VARCHAR(40) NOT NULL DEFAULT 'PENDING_REVIEW',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
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

INSERT INTO advisory.advisory_config (tenant_id, environment_id, advisory_enabled, max_token_budget, tokens_used)
VALUES ('11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', TRUE, 8000, 1240);

INSERT INTO advisory.advisory_finding (
    finding_id, tenant_id, environment_id, case_code, context_ref,
    producer_role, model_version, prompt_version, headline, finding_body, citations_json,
    critic_dissent_headline, critic_dissent_body, critic_severity, critic_status,
    human_reviewer, review_decision
) VALUES (
    'ffff1111-1111-7111-8111-111111111101',
    '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
    'AR-104', 'GR-1044 / P-12 / K-08',
    'Evidence assistant', 'EA-1.3', 'PA-0.7',
    'Rollback compatibility needs evidence',
    'The procedure requires a compatibility check; no current result is linked.',
    '[{"docCode":"OPS-2210","docKind":"Runbook","title":"Rollback procedure for service v43"},{"docCode":"ARCH-1187","docKind":"Architecture decision record","title":"Compatibility check requirement"}]'::jsonb,
    'Do not infer v42 compatibility from a retained package',
    'A retained v42 package does not establish compatibility with current data and dependencies. A compatibility check must be performed and linked before this claim can be accepted.',
    'BLOCKING_CONCERN',
    'UNRESOLVED',
    'Daniel Reed',
    'PENDING_REVIEW'
);
