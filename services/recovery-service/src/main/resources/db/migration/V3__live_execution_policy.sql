-- Toggle-gated Live Recovery Execution Policy (disabled by default: safe DRY_RUN simulation)
CREATE TABLE recovery.live_execution_policy (
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    live_execution_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    allowed_providers VARCHAR(120) NOT NULL DEFAULT '',
    reason VARCHAR(255) NOT NULL DEFAULT 'Disabled by default (safe simulation mode)',
    updated_by VARCHAR(120) NOT NULL DEFAULT 'System Bootstrap',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id)
);

ALTER TABLE recovery.live_execution_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE recovery.live_execution_policy FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON recovery.live_execution_policy;
CREATE POLICY tenant_isolation_policy ON recovery.live_execution_policy
    USING (NULLIF(current_setting('app.tenant_id', true), '') IS NULL
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

INSERT INTO recovery.live_execution_policy (tenant_id, environment_id, live_execution_enabled, allowed_providers, reason, updated_by)
VALUES (
    '11111111-1111-7111-8111-111111111111',
    '22222222-2222-7222-8222-222222222222',
    FALSE,
    '',
    'Disabled by default (safe simulation mode)',
    'System Bootstrap'
);
