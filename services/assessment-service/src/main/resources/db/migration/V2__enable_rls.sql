-- Enterprise Phase E1: PostgreSQL Row-Level Security (RLS) Tenant Isolation
ALTER TABLE assessment.resilience_assessment ENABLE ROW LEVEL SECURITY;
ALTER TABLE assessment.resilience_assessment FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_resilience_assessment ON assessment.resilience_assessment;
CREATE POLICY tenant_isolation_resilience_assessment ON assessment.resilience_assessment
    USING (NULLIF(current_setting('app.tenant_id', true), '') IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE assessment.path_certification ENABLE ROW LEVEL SECURITY;
ALTER TABLE assessment.path_certification FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_path_certification ON assessment.path_certification;
CREATE POLICY tenant_isolation_path_certification ON assessment.path_certification
    USING (NULLIF(current_setting('app.tenant_id', true), '') IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE assessment.decision_envelope ENABLE ROW LEVEL SECURITY;
ALTER TABLE assessment.decision_envelope FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_decision_envelope ON assessment.decision_envelope;
CREATE POLICY tenant_isolation_decision_envelope ON assessment.decision_envelope
    USING (NULLIF(current_setting('app.tenant_id', true), '') IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE integration.outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.outbox_event FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_outbox_event ON integration.outbox_event;
CREATE POLICY tenant_isolation_outbox_event ON integration.outbox_event
    USING (NULLIF(current_setting('app.tenant_id', true), '') IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
