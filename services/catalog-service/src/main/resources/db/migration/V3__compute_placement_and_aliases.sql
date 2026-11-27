-- Cloud Compute Placement, Scaling Headroom & Provider Resource Alias Mapping (FR-CAT-003)
CREATE TABLE catalog.compute_placement (
    placement_id        UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    environment_id      UUID NOT NULL,
    service_canonical_id TEXT NOT NULL,
    cloud_provider      TEXT NOT NULL CHECK (cloud_provider IN ('GCP', 'AWS', 'AZURE', 'OCI', 'K8S')),
    runtime_kind        TEXT NOT NULL CHECK (runtime_kind IN ('GKE', 'EKS', 'AKS', 'OKE', 'CLOUD_RUN', 'MIG', 'ASG', 'VMSS', 'INSTANCE_POOL')),
    region              TEXT NOT NULL,
    availability_zone   TEXT NOT NULL,
    failure_domain      TEXT NOT NULL,
    cluster_or_pool     TEXT NOT NULL,
    min_capacity        INT NOT NULL,
    max_capacity        INT NOT NULL,
    current_capacity    INT NOT NULL,
    autoscaling_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE catalog.provider_resource_alias (
    alias_id             UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL,
    environment_id       UUID NOT NULL,
    service_canonical_id TEXT NOT NULL,
    cloud_provider       TEXT NOT NULL,
    provider_resource_id TEXT NOT NULL,
    confidence           NUMERIC NOT NULL DEFAULT 1.0,
    source_system        TEXT NOT NULL,
    observed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, environment_id, cloud_provider, provider_resource_id)
);

DO $$
DECLARE
    tbl text;
BEGIN
    FOREACH tbl IN ARRAY ARRAY['catalog.compute_placement', 'catalog.provider_resource_alias']
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

INSERT INTO catalog.compute_placement (
    placement_id, tenant_id, environment_id, service_canonical_id, cloud_provider, runtime_kind,
    region, availability_zone, failure_domain, cluster_or_pool, min_capacity, max_capacity, current_capacity, autoscaling_enabled
) VALUES
    ('c0001111-1111-7111-8111-111111111101', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'urn:uacrf:svc:checkout', 'GCP', 'GKE', 'us-central1', 'us-central1-a', 'fd-gcp-us-central1', 'gke-nonprod', 2, 12, 4, TRUE),
    ('c0001111-1111-7111-8111-111111111102', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'urn:uacrf:svc:payment', 'AWS', 'EKS', 'us-east-1', 'us-east-1a', 'fd-aws-us-east-1', 'eks-payments-prod', 3, 20, 6, TRUE),
    ('c0001111-1111-7111-8111-111111111103', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'urn:uacrf:svc:settlement', 'OCI', 'OKE', 'us-ashburn-1', 'AD-1', 'fd-oci-ashburn-1', 'oke-ledger-pool', 2, 10, 3, TRUE);
