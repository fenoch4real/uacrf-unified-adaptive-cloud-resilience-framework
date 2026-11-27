CREATE SCHEMA IF NOT EXISTS discovery;
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE discovery.connector_connection (
    connection_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    connection_name VARCHAR(160) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL,
    access_level VARCHAR(64) NOT NULL,
    telemetry_source VARCHAR(40) NOT NULL DEFAULT 'LIVE_ADAPTER',
    affected_source VARCHAR(160),
    impact_note VARCHAR(255),
    recovery_access VARCHAR(64) NOT NULL DEFAULT 'NOT_CONFIGURED',
    last_collected_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE discovery.collection_run (
    run_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    connection_id UUID REFERENCES discovery.connector_connection(connection_id),
    provider VARCHAR(64) NOT NULL,
    telemetry_source VARCHAR(40) NOT NULL,
    status VARCHAR(40) NOT NULL,
    events_ingested INT NOT NULL DEFAULT 0,
    edges_discovered INT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE discovery.telemetry_observation (
    observation_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    service_id UUID,
    subject_canonical_id VARCHAR(200) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    signal_type VARCHAR(64) NOT NULL,
    metric_name VARCHAR(120) NOT NULL,
    metric_value DOUBLE PRECISION,
    unit VARCHAR(32),
    threshold_value DOUBLE PRECISION,
    severity VARCHAR(32) NOT NULL,
    telemetry_source VARCHAR(40) NOT NULL,
    raw_digest VARCHAR(128) NOT NULL,
    raw_reference VARCHAR(255) NOT NULL,
    payload_json JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE discovery.discovered_edge (
    edge_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    source_canonical_id VARCHAR(200) NOT NULL,
    source_label VARCHAR(80) NOT NULL,
    source_display VARCHAR(160) NOT NULL,
    relationship_type VARCHAR(80) NOT NULL,
    target_canonical_id VARCHAR(200) NOT NULL,
    target_label VARCHAR(80) NOT NULL,
    target_display VARCHAR(160) NOT NULL,
    importance VARCHAR(40) NOT NULL,
    edge_state VARCHAR(40) NOT NULL,
    observation_source VARCHAR(80) NOT NULL,
    owner_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    telemetry_source VARCHAR(40) NOT NULL,
    last_observed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE integration.outbox_event (
    outbox_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id VARCHAR(120) NOT NULL,
    correlation_id UUID NOT NULL,
    payload_json JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

-- Seed baseline connector definitions for the default nonproduction lab tenant (no production recovery access)
INSERT INTO discovery.connector_connection (
    connection_id, tenant_id, environment_id, connection_name, provider, status, access_level,
    telemetry_source, affected_source, impact_note, recovery_access, last_collected_at
) VALUES
    ('55555555-5555-7555-8555-555555555501', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'GCP inventory', 'GCP', 'CONNECTED', 'READ_ONLY', 'SAMPLE_LOCAL', NULL, NULL, 'NOT_CONFIGURED', now() - interval '30 seconds'),
    ('55555555-5555-7555-8555-555555555502', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'AWS inventory', 'AWS', 'CONNECTED', 'READ_ONLY', 'SAMPLE_LOCAL', NULL, NULL, 'NOT_CONFIGURED', now() - interval '1 minute'),
    ('55555555-5555-7555-8555-555555555503', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'Azure telemetry', 'AZURE', 'ATTENTION_NEEDED', 'READ_ONLY', 'SAMPLE_LOCAL',
     'Fraud scoring diagnostics', 'New AI/fraud evidence may be delayed or incomplete', 'NOT_CONFIGURED', now() - interval '18 minutes'),
    ('55555555-5555-7555-8555-555555555504', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'OpenTelemetry', 'OTEL', 'CONNECTED', 'RECEIVE_ONLY', 'SAMPLE_LOCAL', NULL, NULL, 'NOT_CONFIGURED', now() - interval '20 seconds'),
    ('55555555-5555-7555-8555-555555555505', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'Model and knowledge connections', 'KNOWLEDGE', 'NOT_CONFIGURED', 'NOT_CONFIGURED', 'LIVE_ADAPTER',
     'External RAG & model endpoints', 'Advisory drafting runs offline/manual until configured', 'NOT_CONFIGURED', NULL);
