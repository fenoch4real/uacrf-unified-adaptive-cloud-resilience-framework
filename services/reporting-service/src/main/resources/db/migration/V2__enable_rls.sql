-- Enterprise Phase E2: PostgreSQL Row-Level Security (RLS) Tenant Isolation for S14
DO $$
DECLARE
    tbl text;
BEGIN
    FOREACH tbl IN ARRAY ARRAY[
        'reporting.metering_event',
        'reporting.assurance_room',
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
