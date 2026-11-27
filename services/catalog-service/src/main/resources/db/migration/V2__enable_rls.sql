-- Enterprise Phase E1: PostgreSQL Row-Level Security (RLS) Tenant Isolation
DO $$
DECLARE
    tbl text;
BEGIN
    FOREACH tbl IN ARRAY ARRAY[
        'catalog.service',
        'catalog.agent_workload_profile',
        'catalog.agent_workload_version',
        'catalog.customer_journey',
        'catalog.journey_step',
        'catalog.journey_capability',
        'catalog.journey_impact_profile',
        'catalog.shared_resource',
        'catalog.capability_asset',
        'catalog.idempotency_record',
        'catalog.audit_event',
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
