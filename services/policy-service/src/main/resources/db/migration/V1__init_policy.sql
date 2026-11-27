CREATE SCHEMA IF NOT EXISTS policy;
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE policy.recovery_proposal (
    proposal_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    service_canonical_id VARCHAR(200) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    summary_title VARCHAR(200) NOT NULL,
    target_version VARCHAR(64) NOT NULL,
    reversible BOOLEAN NOT NULL DEFAULT TRUE,
    blast_radius_count INT NOT NULL DEFAULT 1,
    proposer_id UUID NOT NULL,
    proposer_name VARCHAR(120) NOT NULL,
    approver_id UUID,
    approver_name VARCHAR(120),
    approval_status VARCHAR(40) NOT NULL DEFAULT 'PENDING_APPROVAL',
    policy_outcome VARCHAR(40) NOT NULL,
    policy_reasons_json JSONB NOT NULL,
    graph_revision VARCHAR(128) NOT NULL,
    subgraph_digest VARCHAR(128) NOT NULL,
    evidence_qualification VARCHAR(40) NOT NULL,
    rationale TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE policy.bounded_authorization (
    authorization_id UUID PRIMARY KEY,
    proposal_id UUID NOT NULL REFERENCES policy.recovery_proposal(proposal_id),
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    nonce VARCHAR(120) NOT NULL UNIQUE,
    audience VARCHAR(120) NOT NULL,
    graph_revision VARCHAR(128) NOT NULL,
    delta_classification VARCHAR(40) NOT NULL,
    jws_token TEXT NOT NULL,
    envelope_json JSONB NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
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

-- Seed baseline illustrative rollback proposal from SRE Luis Romero awaiting Service Owner Maya Chen approval
INSERT INTO policy.recovery_proposal (
    proposal_id, tenant_id, environment_id, service_canonical_id, action_type,
    summary_title, target_version, reversible, blast_radius_count,
    proposer_id, proposer_name, approval_status, policy_outcome, policy_reasons_json,
    graph_revision, subgraph_digest, evidence_qualification, rationale
) VALUES (
    'dddd1111-1111-7111-8111-111111111101',
    '11111111-1111-7111-8111-111111111111',
    '22222222-2222-7222-8222-222222222222',
    'urn:uacrf:svc:checkout',
    'K8S_ROLLBACK_DEPLOYMENT',
    'Roll back Checkout deployment from v43 to v42',
    'v42',
    TRUE,
    1,
    '33333333-3333-7333-8333-333333333301',
    'Luis Romero',
    'PENDING_APPROVAL',
    'APPROVAL_REQUIRED',
    '["SCOPED_SINGLE_DEPLOYMENT", "REQUIRES_DISTINCT_SERVICE_OWNER_APPROVAL"]'::jsonb,
    'rev-sample-local-0001',
    'sha256:sub-checkout-v43',
    'QUALIFIED',
    'Order error rate spiked from 1.2% to 18.4% immediately after v43 rollout; reversible rollback to v42 scoped to 1 deployment.'
);
