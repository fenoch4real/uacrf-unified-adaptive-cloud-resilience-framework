CREATE SCHEMA IF NOT EXISTS graph;
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE graph.graph_revision (
    revision_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    revision_digest VARCHAR(128) NOT NULL,
    node_count INT NOT NULL,
    edge_count INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE graph.projected_node (
    node_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    canonical_id VARCHAR(200) NOT NULL,
    node_label VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    health_state VARCHAR(40) NOT NULL DEFAULT 'HEALTHY',
    provider_tag VARCHAR(40),
    properties_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, environment_id, canonical_id)
);

CREATE TABLE graph.projected_edge (
    edge_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    source_canonical_id VARCHAR(200) NOT NULL,
    relationship_type VARCHAR(80) NOT NULL,
    target_canonical_id VARCHAR(200) NOT NULL,
    importance VARCHAR(40) NOT NULL,
    edge_state VARCHAR(40) NOT NULL,
    observation_source VARCHAR(120) NOT NULL,
    owner_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    last_observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, environment_id, source_canonical_id, relationship_type, target_canonical_id)
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

-- Seed baseline illustrative nonproduction graph topology so graph views render immediately
INSERT INTO graph.projected_node (node_id, tenant_id, environment_id, canonical_id, node_label, display_name, health_state, provider_tag, properties_json) VALUES
    ('88888888-8888-7888-8888-888888888801', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:journey:place-order', 'CustomerJourney', 'Place Order', 'DEGRADED', 'SHARED', '{"subtitle":"Customer journey"}'::jsonb),
    ('88888888-8888-7888-8888-888888888802', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:step:start-checkout', 'JourneyStep', 'Start Checkout', 'DEGRADED', 'SHARED', '{"subtitle":"Journey step"}'::jsonb),
    ('88888888-8888-7888-8888-888888888803', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:step:create-order', 'JourneyStep', 'Create Order', 'UNKNOWN', 'SHARED', '{"subtitle":"Journey step"}'::jsonb),
    ('88888888-8888-7888-8888-888888888804', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:svc:checkout', 'Service', 'Checkout', 'DEGRADED', 'GCP', '{"subtitle":"Service · GKE GCP"}'::jsonb),
    ('88888888-8888-7888-8888-888888888805', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:svc:order-service', 'Service', 'Order service', 'UNKNOWN', 'AWS', '{"subtitle":"Service · AWS"}'::jsonb),
    ('88888888-8888-7888-8888-888888888806', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:svc:payment', 'Service', 'Payment', 'DEGRADED', 'AWS', '{"subtitle":"Service · AWS"}'::jsonb),
    ('88888888-8888-7888-8888-888888888807', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:svc:cache-service', 'Service', 'Cache service', 'HEALTHY', 'GCP', '{"subtitle":"Service · GCP"}'::jsonb),
    ('88888888-8888-7888-8888-888888888808', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:external:fraud-api', 'ExternalProvider', 'Fraud API', 'HEALTHY', 'AZURE', '{"subtitle":"External provider · Azure"}'::jsonb),
    ('88888888-8888-7888-8888-888888888809', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:external:payments-gateway', 'ExternalProvider', 'Payments gateway', 'UNKNOWN', 'EXTERNAL', '{"subtitle":"External provider"}'::jsonb),
    ('88888888-8888-7888-8888-888888888810', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:agent:fraud-endpoint', 'ModelEndpoint', 'Fraud endpoint', 'DEGRADED', 'AZURE', '{"subtitle":"AI service · Protected"}'::jsonb),
    ('88888888-8888-7888-8888-888888888811', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:model:fraud-v17', 'ModelVersion', 'Model v17', 'UNKNOWN', 'AZURE', '{"subtitle":"Fraud scoring model"}'::jsonb),
    ('88888888-8888-7888-8888-888888888812', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:feature-store:fraud-features', 'FeatureStore', 'Feature store', 'DEGRADED', 'AZURE', '{"subtitle":"Model features"}'::jsonb),
    ('88888888-8888-7888-8888-888888888813', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:fallback:rules-based', 'ModelFallback', 'Rules-based fallback', 'UNKNOWN', 'AWS', '{"subtitle":"Deterministic rules"}'::jsonb);

INSERT INTO graph.projected_edge (edge_id, tenant_id, environment_id, source_canonical_id, relationship_type, target_canonical_id, importance, edge_state, observation_source, owner_confirmed, last_observed_at) VALUES
    ('99999999-9999-7999-8999-999999999901', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:step:start-checkout', 'PART_OF_JOURNEY', 'urn:uacrf:journey:place-order', 'MANDATORY', 'CONFIRMED', 'Journey specification', TRUE, now() - interval '1 minute'),
    ('99999999-9999-7999-8999-999999999902', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:step:create-order', 'PART_OF_JOURNEY', 'urn:uacrf:journey:place-order', 'MANDATORY', 'CONFIRMED', 'Journey specification', TRUE, now() - interval '1 minute'),
    ('99999999-9999-7999-8999-999999999903', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:svc:checkout', 'SUPPORTS_STEP', 'urn:uacrf:step:start-checkout', 'MANDATORY', 'CONFIRMED', 'Distributed trace', TRUE, now() - interval '20 seconds'),
    ('99999999-9999-7999-8999-999999999904', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:svc:order-service', 'SUPPORTS_STEP', 'urn:uacrf:step:create-order', 'MANDATORY', 'CONFIRMED', 'Distributed trace', TRUE, now() - interval '20 seconds'),
    ('99999999-9999-7999-8999-999999999905', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:svc:checkout', 'DEPENDS_ON', 'urn:uacrf:svc:payment', 'MANDATORY', 'CONFIRMED', 'Distributed trace', FALSE, now() - interval '20 seconds'),
    ('99999999-9999-7999-8999-999999999906', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:svc:checkout', 'DEPENDS_ON', 'urn:uacrf:svc:cache-service', 'DEGRADABLE', 'CONFIRMED', 'GCP Service Mesh', TRUE, now() - interval '20 seconds'),
    ('99999999-9999-7999-8999-999999999907', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:svc:payment', 'DEPENDS_ON', 'urn:uacrf:external:fraud-api', 'MANDATORY', 'CONFIRMED', 'Distributed trace', TRUE, now() - interval '20 seconds'),
    ('99999999-9999-7999-8999-999999999908', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:svc:checkout', 'DEPENDS_ON', 'urn:uacrf:external:payments-gateway', 'OPTIONAL', 'UNCERTAIN', 'Observed trace spike', FALSE, now() - interval '20 seconds'),
    ('99999999-9999-7999-8999-999999999909', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:svc:payment', 'CALLS', 'urn:uacrf:agent:fraud-endpoint', 'DEGRADABLE', 'CONFIRMED', 'AI gateway trace', TRUE, now() - interval '20 seconds'),
    ('99999999-9999-7999-8999-999999999910', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:agent:fraud-endpoint', 'SERVES_MODEL', 'urn:uacrf:model:fraud-v17', 'MANDATORY', 'CONFIRMED', 'Model registry', TRUE, now() - interval '2 minutes'),
    ('99999999-9999-7999-8999-999999999911', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:agent:fraud-endpoint', 'USES_FEATURES_FROM', 'urn:uacrf:feature-store:fraud-features', 'MANDATORY', 'CONFIRMED', 'Feature client telemetry', TRUE, now() - interval '18 minutes'),
    ('99999999-9999-7999-8999-999999999912', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222', 'urn:uacrf:svc:payment', 'FALLBACK_TO', 'urn:uacrf:fallback:rules-based', 'OPTIONAL', 'UNCERTAIN', 'Architecture candidate', FALSE, now() - interval '5 minutes');

INSERT INTO graph.graph_revision (revision_id, tenant_id, environment_id, revision_digest, node_count, edge_count) VALUES (
    '90000000-0000-7000-8000-000000000001',
    '11111111-1111-7111-8111-111111111111',
    '22222222-2222-7222-8222-222222222222',
    'rev-sample-local-0001', 13, 12
);
