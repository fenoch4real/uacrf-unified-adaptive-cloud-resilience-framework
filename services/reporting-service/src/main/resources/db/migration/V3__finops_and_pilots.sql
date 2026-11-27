-- FinOps Attribution, Pilot Boundary Locking & Onboarding Analytics (FR-PRD-001..010, NFR-CST-001, NFR-IMP-001)
CREATE TABLE reporting.finops_allocation (
    allocation_id      UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL,
    environment_id     UUID NOT NULL,
    cloud_provider     TEXT NOT NULL,
    cost_category      TEXT NOT NULL CHECK (cost_category IN ('INGESTION', 'STORAGE', 'GRAPH', 'WORKFLOW', 'MODEL')),
    estimated_usd      NUMERIC NOT NULL,
    monthly_quota_usd  NUMERIC NOT NULL,
    quota_exceeded     BOOLEAN NOT NULL,
    recorded_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reporting.commercial_pilot (
    pilot_id                UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL,
    environment_id          UUID NOT NULL,
    pilot_name              TEXT NOT NULL,
    target_segment          TEXT NOT NULL,
    champion_name           TEXT NOT NULL,
    locked_provider_scope   TEXT NOT NULL,
    onboarding_lead_hours   NUMERIC NOT NULL,
    time_to_first_outcome_h NUMERIC NOT NULL,
    status                  TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

DO $$
DECLARE
    tbl text;
BEGIN
    FOREACH tbl IN ARRAY ARRAY['reporting.finops_allocation', 'reporting.commercial_pilot']
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

INSERT INTO reporting.finops_allocation (
    allocation_id, tenant_id, environment_id, cloud_provider, cost_category, estimated_usd, monthly_quota_usd, quota_exceeded
) VALUES
    ('99921111-1111-7111-8111-111111111101', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'GCP', 'GRAPH', 142.50, 500.00, FALSE),
    ('99921111-1111-7111-8111-111111111102', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'AWS', 'STORAGE', 88.20, 400.00, FALSE),
    ('99921111-1111-7111-8111-111111111103', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'OCI', 'WORKFLOW', 64.00, 350.00, FALSE);

INSERT INTO reporting.commercial_pilot (
    pilot_id, tenant_id, environment_id, pilot_name, target_segment, champion_name,
    locked_provider_scope, onboarding_lead_hours, time_to_first_outcome_h, status
) VALUES (
    '99931111-1111-7111-8111-111111111101', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
    'Place Order Multi-Cloud Resilience Pilot', 'Regulated Financial & Commerce', 'Maya Chen',
    'GCP,AWS,AZURE,OCI', 6.5, 2.0, 'ACTIVE'
);
