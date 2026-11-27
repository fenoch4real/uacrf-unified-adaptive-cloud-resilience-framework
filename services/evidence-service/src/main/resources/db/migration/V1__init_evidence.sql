CREATE SCHEMA IF NOT EXISTS evidence;
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE evidence.evidence_package (
    package_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    service_canonical_id VARCHAR(200) NOT NULL,
    service_name VARCHAR(160) NOT NULL,
    journey_name VARCHAR(160) NOT NULL,
    deployment_ref VARCHAR(120) NOT NULL,
    candidate_action VARCHAR(160) NOT NULL,
    owner_name VARCHAR(120) NOT NULL,
    graph_revision VARCHAR(128) NOT NULL,
    completeness_status VARCHAR(40) NOT NULL,
    recovery_readiness VARCHAR(40) NOT NULL,
    observed_state VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE evidence.evidence_item (
    item_id UUID PRIMARY KEY,
    package_id UUID NOT NULL REFERENCES evidence.evidence_package(package_id),
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    item_key VARCHAR(80) NOT NULL,
    item_label VARCHAR(160) NOT NULL,
    source_kind VARCHAR(64) NOT NULL,
    availability_status VARCHAR(40) NOT NULL,
    qualification_state VARCHAR(40) NOT NULL,
    freshness_state VARCHAR(40) NOT NULL,
    required_for_recovery BOOLEAN NOT NULL DEFAULT TRUE,
    detail_note VARCHAR(255) NOT NULL,
    source_reference VARCHAR(255),
    payload_digest VARCHAR(128),
    graph_revision VARCHAR(128) NOT NULL,
    captured_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ
);

CREATE TABLE evidence.ai_dependency_evidence (
    ai_evidence_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    service_canonical_id VARCHAR(200) NOT NULL,
    agent_canonical_id VARCHAR(200) NOT NULL,
    model_version VARCHAR(80) NOT NULL,
    aspect_key VARCHAR(80) NOT NULL,
    aspect_label VARCHAR(120) NOT NULL,
    observed_status VARCHAR(40) NOT NULL,
    current_value VARCHAR(160) NOT NULL,
    qualification_state VARCHAR(40) NOT NULL,
    verified_at TIMESTAMPTZ
);

CREATE TABLE evidence.object_blob (
    blob_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    object_key VARCHAR(255) NOT NULL UNIQUE,
    sha256_digest VARCHAR(128) NOT NULL,
    content_type VARCHAR(80) NOT NULL,
    worm_locked BOOLEAN NOT NULL DEFAULT TRUE,
    payload_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
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

-- Seed baseline illustrative nonproduction decision evidence package (3/5 items available, probe MISSING -> UNKNOWN)
INSERT INTO evidence.evidence_package (
    package_id, tenant_id, environment_id, service_canonical_id, service_name, journey_name,
    deployment_ref, candidate_action, owner_name, graph_revision, completeness_status,
    recovery_readiness, observed_state
) VALUES (
    '66666666-6666-7666-8666-666666666601',
    '11111111-1111-7111-8111-111111111111',
    '22222222-2222-7222-8222-222222222222',
    'urn:uacrf:svc:checkout', 'Checkout', 'Place Order', 'v43', 'Rollback to v42',
    'Maya Chen', 'rev-sample-local-0001', 'MISSING_REQUIRED', 'UNKNOWN', 'DEGRADED'
);

INSERT INTO evidence.evidence_item (
    item_id, package_id, tenant_id, environment_id, item_key, item_label, source_kind,
    availability_status, qualification_state, freshness_state, required_for_recovery,
    detail_note, source_reference, payload_digest, graph_revision, captured_at, expires_at
) VALUES
    ('66666666-6666-7666-8666-666666666611', '66666666-6666-7666-8666-666666666601',
     '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'deployment_change', 'Deployment event', 'LIVE_TELEMETRY', 'AVAILABLE', 'QUALIFIED', 'CURRENT', TRUE,
     'v43 deployment event captured in telemetry.', 'sample://uacrf-raw/deploy-v43', 'sha256:deploy-v43',
     'rev-sample-local-0001', now() - interval '4 minutes', now() + interval '56 minutes'),
    ('66666666-6666-7666-8666-666666666612', '66666666-6666-7666-8666-666666666601',
     '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'error_rate_telemetry', 'Error-rate telemetry', 'LIVE_TELEMETRY', 'AVAILABLE', 'QUALIFIED', 'CURRENT', TRUE,
     'Order error rate increased from 1.2% to 18.4%.', 'sample://uacrf-raw/err-v43', 'sha256:err-v43',
     'rev-sample-local-0001', now() - interval '20 seconds', now() + interval '59 minutes'),
    ('66666666-6666-7666-8666-666666666613', '66666666-6666-7666-8666-666666666601',
     '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'dependency_context', 'Dependency context', 'GRAPH_SNAPSHOT', 'AVAILABLE', 'QUALIFIED', 'CURRENT', TRUE,
     'Affected services and journey topology captured.', 'neo4j://revision/rev-sample-local-0001', 'sha256:graph-0001',
     'rev-sample-local-0001', now() - interval '20 seconds', now() + interval '59 minutes'),
    ('66666666-6666-7666-8666-666666666614', '66666666-6666-7666-8666-666666666601',
     '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'e2e_transaction_probe', 'End-to-end transaction probe', 'TRANSACTION_PROBE', 'MISSING', 'UNKNOWN', 'MISSING', TRUE,
     'No recent successful probe result found.', NULL, NULL,
     'rev-sample-local-0001', NULL, NULL),
    ('66666666-6666-7666-8666-666666666615', '66666666-6666-7666-8666-666666666601',
     '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'rollback_compatibility', 'Rollback compatibility', 'COMPATIBILITY_CHECK', 'AWAITING_REVIEW', 'UNKNOWN', 'STALE', TRUE,
     'Compatibility check requires service owner approval.', NULL, NULL,
     'rev-sample-local-0001', NULL, NULL);

INSERT INTO evidence.ai_dependency_evidence (
    ai_evidence_id, tenant_id, environment_id, service_canonical_id, agent_canonical_id,
    model_version, aspect_key, aspect_label, observed_status, current_value, qualification_state, verified_at
) VALUES
    ('77777777-7777-7777-8777-777777777701', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'urn:uacrf:svc:payment', 'urn:uacrf:agent:fraud-endpoint', 'v17', 'endpoint_availability', 'Endpoint availability',
     'OBSERVED', '99.7% over 7 days', 'QUALIFIED', now() - interval '2 minutes'),
    ('77777777-7777-7777-8777-777777777702', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'urn:uacrf:svc:payment', 'urn:uacrf:agent:fraud-endpoint', 'v17', 'latency_p95', 'Latency',
     'OBSERVED', 'P95 220 ms', 'QUALIFIED', now() - interval '2 minutes'),
    ('77777777-7777-7777-8777-777777777703', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'urn:uacrf:svc:payment', 'urn:uacrf:agent:fraud-endpoint', 'v17', 'model_quality', 'Model quality',
     'UNKNOWN', 'Labels pending', 'UNKNOWN', NULL),
    ('77777777-7777-7777-8777-777777777704', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'urn:uacrf:svc:payment', 'urn:uacrf:agent:fraud-endpoint', 'v17', 'subgroup_safety', 'Subgroup safety',
     'UNKNOWN', 'Missing evidence', 'UNKNOWN', NULL),
    ('77777777-7777-7777-8777-777777777705', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'urn:uacrf:svc:payment', 'urn:uacrf:agent:fraud-endpoint', 'v17', 'feature_freshness', 'Feature freshness',
     'NEEDS_REVIEW', 'Evidence delayed', 'PROVISIONAL', now() - interval '18 minutes'),
    ('77777777-7777-7777-8777-777777777706', '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
     'urn:uacrf:svc:payment', 'urn:uacrf:agent:fraud-endpoint', 'v17', 'fallback_capacity', 'Fallback capacity',
     'UNKNOWN', 'Not tested', 'UNKNOWN', NULL);
