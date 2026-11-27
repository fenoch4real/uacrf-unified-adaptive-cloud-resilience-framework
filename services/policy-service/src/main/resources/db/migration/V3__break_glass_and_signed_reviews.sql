-- Break-Glass Emergency Escalation & Signed Review Audit Ledger (FR-APR-002, AB.3)
CREATE TABLE policy.break_glass_grant (
    break_glass_id              UUID PRIMARY KEY,
    tenant_id                   UUID NOT NULL,
    environment_id              UUID NOT NULL,
    service_canonical_id        VARCHAR(160) NOT NULL,
    action_type                 VARCHAR(80) NOT NULL,
    requested_by                VARCHAR(120) NOT NULL,
    justification               TEXT NOT NULL,
    mfa_step_up_verified        BOOLEAN NOT NULL,
    signed_jws_grant            TEXT NOT NULL,
    post_incident_review_status VARCHAR(40) NOT NULL DEFAULT 'REQUIRED_OPEN',
    expires_at                  TIMESTAMPTZ NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE policy.break_glass_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE policy.break_glass_grant FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON policy.break_glass_grant;
CREATE POLICY tenant_isolation_policy ON policy.break_glass_grant
    USING (NULLIF(current_setting('app.tenant_id', true), '') IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
