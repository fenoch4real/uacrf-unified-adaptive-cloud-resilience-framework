-- Enterprise Phase E1.1: PostgreSQL Row-Level Security (RLS) Tenant Isolation
ALTER TABLE foundation.principal ENABLE ROW LEVEL SECURITY;
ALTER TABLE foundation.principal FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation_principal ON foundation.principal;
CREATE POLICY tenant_isolation_principal ON foundation.principal
    USING (
        NULLIF(current_setting('app.tenant_id', true), '') IS NULL
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    );

ALTER TABLE foundation.membership ENABLE ROW LEVEL SECURITY;
ALTER TABLE foundation.membership FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation_membership ON foundation.membership;
CREATE POLICY tenant_isolation_membership ON foundation.membership
    USING (
        NULLIF(current_setting('app.tenant_id', true), '') IS NULL
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    );
