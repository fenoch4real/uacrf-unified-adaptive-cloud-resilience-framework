-- Enterprise Phase E1: PostgreSQL Row-Level Security (RLS) Tenant Isolation
DO $$
DECLARE
    tbl text;
BEGIN
    FOREACH tbl IN ARRAY ARRAY[
        'recovery.kill_switch_state',
        'recovery.recovery_execution',
        'integration.outbox_event'
    ]
    LOOP
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', tbl);
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', tbl);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_policy ON %s', tbl);
        EXECUTE format(
            'CREATE POLICY tenant_isolation_policy ON %s USING (NULLIF(current_setting(''app.tenant_id'', true), '''') IS NULL OR tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)',
            tbl
        );
    END LOOP;
END $$;
