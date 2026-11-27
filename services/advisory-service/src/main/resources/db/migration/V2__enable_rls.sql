-- Enterprise Phase E1: PostgreSQL Row-Level Security (RLS) Tenant Isolation
ALTER TABLE advisory.advisory_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE advisory.advisory_config FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_advisory_config ON advisory.advisory_config;
CREATE POLICY tenant_isolation_advisory_config ON advisory.advisory_config
    USING (NULLIF(current_setting('app.tenant_id', true), '') IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE advisory.advisory_finding ENABLE ROW LEVEL SECURITY;
ALTER TABLE advisory.advisory_finding FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_advisory_finding ON advisory.advisory_finding;
CREATE POLICY tenant_isolation_advisory_finding ON advisory.advisory_finding
    USING (NULLIF(current_setting('app.tenant_id', true), '') IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE integration.outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.outbox_event FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_outbox_event ON integration.outbox_event;
CREATE POLICY tenant_isolation_outbox_event ON integration.outbox_event
    USING (NULLIF(current_setting('app.tenant_id', true), '') IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
