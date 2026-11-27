CREATE SCHEMA IF NOT EXISTS reporting;
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE reporting.metering_event (
    meter_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    meter_dimension text NOT NULL,
    cloud_provider text NOT NULL,
    quantity bigint NOT NULL,
    unit text NOT NULL,
    event_digest text NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE reporting.assurance_room (
    room_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    auditor_organization text NOT NULL,
    recipient_email text NOT NULL,
    frameworks_csv text NOT NULL,
    access_token_sha256 text NOT NULL,
    watermark_text text NOT NULL,
    bundle_sha256 text NOT NULL,
    status text NOT NULL,
    created_by text NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE integration.outbox_event (
    outbox_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    event_type text NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    correlation_id uuid NOT NULL,
    payload_json text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

INSERT INTO reporting.metering_event (
    meter_id, tenant_id, environment_id, meter_dimension, cloud_provider, quantity, unit, event_digest
) VALUES
    ('99911111-1111-7111-8111-111111111101', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'PROTECTED_SERVICE_ASSET', 'AWS', 12, 'services', 'sha256:seed-aws-meter'),
    ('99911111-1111-7111-8111-111111111102', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'PROTECTED_AGENT_WORKLOAD', 'GCP', 4, 'agents', 'sha256:seed-gcp-meter'),
    ('99911111-1111-7111-8111-111111111103', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'CONTINUOUS_ASSURANCE_EVAL', 'OCI', 18, 'evaluations', 'sha256:seed-oci-meter');
