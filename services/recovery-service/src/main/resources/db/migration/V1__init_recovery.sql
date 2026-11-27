CREATE SCHEMA IF NOT EXISTS recovery;
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE recovery.kill_switch_state (
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(255),
    updated_by VARCHAR(120),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id)
);

CREATE TABLE recovery.consumed_nonce (
    nonce VARCHAR(120) PRIMARY KEY,
    authorization_id UUID NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE recovery.recovery_execution (
    execution_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    authorization_id UUID NOT NULL,
    nonce VARCHAR(120) NOT NULL,
    service_canonical_id VARCHAR(200) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    adapter_key VARCHAR(64) NOT NULL,
    target_version VARCHAR(64) NOT NULL,
    workflow_state VARCHAR(40) NOT NULL,
    adapter_output TEXT NOT NULL,
    validation_steps_json JSONB NOT NULL,
    compensated BOOLEAN NOT NULL DEFAULT FALSE,
    executed_by VARCHAR(120) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
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

INSERT INTO recovery.kill_switch_state (tenant_id, environment_id, active, reason, updated_by)
VALUES (
    '11111111-1111-7111-8111-111111111111',
    '22222222-2222-7222-8222-222222222222',
    FALSE,
    'Normal nonproduction execution state',
    'System Bootstrap'
);
