CREATE SCHEMA IF NOT EXISTS assessment;
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE assessment.resilience_assessment (
    assessment_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    journey_canonical_id VARCHAR(200) NOT NULL,
    journey_name VARCHAR(160) NOT NULL,
    failure_scenario VARCHAR(200) NOT NULL,
    scoped_services_json JSONB NOT NULL,
    recovery_candidate VARCHAR(160) NOT NULL,
    assessor_id UUID NOT NULL,
    assessor_name VARCHAR(120) NOT NULL,
    service_owner_id UUID NOT NULL,
    service_owner_name VARCHAR(120) NOT NULL,
    technical_reviewer_name VARCHAR(120),
    technical_review_status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    independent_reviewer_id UUID,
    independent_reviewer_name VARCHAR(120),
    independent_review_status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    report_status VARCHAR(40) NOT NULL DEFAULT 'DRAFT_REVIEW_REQUIRED',
    conclusion_status VARCHAR(40) NOT NULL DEFAULT 'INSUFFICIENT_EVIDENCE',
    conclusion_headline VARCHAR(255) NOT NULL,
    conclusion_detail TEXT NOT NULL,
    limitations_text TEXT NOT NULL,
    recommended_next_step VARCHAR(255) NOT NULL,
    graph_revision VARCHAR(128) NOT NULL,
    impact_status VARCHAR(40) NOT NULL DEFAULT 'OBSERVED',
    impacted_customer_rate VARCHAR(80) NOT NULL DEFAULT '18.4% failure rate',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE assessment.assessment_finding (
    finding_id UUID PRIMARY KEY,
    assessment_id UUID NOT NULL REFERENCES assessment.resilience_assessment(assessment_id),
    area_name VARCHAR(120) NOT NULL,
    bucket VARCHAR(32) NOT NULL, -- SUPPORTED | OPEN
    status VARCHAR(40) NOT NULL,
    summary_title VARCHAR(160) NOT NULL,
    details VARCHAR(255) NOT NULL,
    owner_name VARCHAR(120),
    due_label VARCHAR(40),
    challenged BOOLEAN NOT NULL DEFAULT FALSE,
    challenge_note VARCHAR(255)
);

CREATE TABLE assessment.path_certification (
    certification_id UUID PRIMARY KEY,
    assessment_id UUID REFERENCES assessment.resilience_assessment(assessment_id),
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    service_canonical_id VARCHAR(200) NOT NULL,
    primary_path_id VARCHAR(120) NOT NULL,
    alternate_path_id VARCHAR(120) NOT NULL,
    certification_status VARCHAR(40) NOT NULL,
    shared_domains_json JSONB NOT NULL,
    witness_summary VARCHAR(255) NOT NULL,
    graph_revision VARCHAR(128) NOT NULL,
    certified_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE assessment.decision_envelope (
    envelope_id UUID PRIMARY KEY,
    assessment_id UUID NOT NULL REFERENCES assessment.resilience_assessment(assessment_id),
    tenant_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    graph_revision VARCHAR(128) NOT NULL,
    payload_sha256 VARCHAR(128) NOT NULL,
    jws_token TEXT NOT NULL,
    payload_json JSONB NOT NULL,
    sealed_by VARCHAR(120) NOT NULL,
    sealed_at TIMESTAMPTZ NOT NULL DEFAULT now()
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

-- Seed baseline illustrative nonproduction Place Order Defective Deployment v43 assessment
INSERT INTO assessment.resilience_assessment (
    assessment_id, tenant_id, environment_id, journey_canonical_id, journey_name,
    failure_scenario, scoped_services_json, recovery_candidate,
    assessor_id, assessor_name, service_owner_id, service_owner_name,
    report_status, conclusion_status, conclusion_headline, conclusion_detail,
    limitations_text, recommended_next_step, graph_revision, impact_status, impacted_customer_rate
) VALUES (
    'aaaa1111-1111-7111-8111-111111111101',
    '11111111-1111-7111-8111-111111111111',
    '22222222-2222-7222-8222-222222222222',
    'urn:uacrf:journey:place-order',
    'Place Order',
    'Defective deployment (v43 rollback readiness)',
    '["Checkout (GKE / GCP)", "Payment (AWS)", "Order service (AWS)"]'::jsonb,
    'Rollback v43 to v42',
    '33333333-3333-7333-8333-333333333302', 'Daniel Reed',
    '33333333-3333-7333-8333-333333333303', 'Maya Chen',
    'DRAFT_REVIEW_REQUIRED',
    'INSUFFICIENT_EVIDENCE',
    'Recovery readiness is not yet established.',
    'Additional evidence and a recovery test are required to confirm the service can be restored within the expected time and impact thresholds for the scoped journey.',
    'No recovery test has been completed for this assessment.',
    'Complete evidence review and run an approved lab test.',
    'rev-sample-local-0001',
    'OBSERVED',
    '18.4% failure rate (+17.2 pp after v43)'
);

INSERT INTO assessment.assessment_finding (
    finding_id, assessment_id, area_name, bucket, status, summary_title, details, owner_name, due_label
) VALUES
    ('aaaa1111-1111-7111-8111-111111111111', 'aaaa1111-1111-7111-8111-111111111101',
     'Service ownership', 'SUPPORTED', 'VERIFIED', 'Ownership',
     'Service owner Maya Chen and supporting contacts are identified for the Place Order journey.', 'Maya Chen', 'Completed'),
    ('aaaa1111-1111-7111-8111-111111111112', 'aaaa1111-1111-7111-8111-111111111101',
     'Deployment and telemetry', 'SUPPORTED', 'VERIFIED', 'Current deployment telemetry',
     'Order failures increased after deployment v43, with supporting telemetry available.', 'Daniel Reed', 'Completed'),
    ('aaaa1111-1111-7111-8111-111111111113', 'aaaa1111-1111-7111-8111-111111111101',
     'Fallback readiness', 'OPEN', 'NEEDS_REVIEW', 'Rollback compatibility and capacity review',
     'Confirm v42 can be deployed and has sufficient capacity to handle expected load.', 'Maya Chen', 'Today'),
    ('aaaa1111-1111-7111-8111-111111111114', 'aaaa1111-1111-7111-8111-111111111101',
     'Journey validation', 'OPEN', 'MISSING', 'Current transaction validation',
     'Validate order success rate and check dependent services after rollback.', 'Daniel Reed', 'Today');

INSERT INTO assessment.path_certification (
    certification_id, assessment_id, tenant_id, environment_id, service_canonical_id,
    primary_path_id, alternate_path_id, certification_status, shared_domains_json,
    witness_summary, graph_revision
) VALUES (
    'bbbb1111-1111-7111-8111-111111111101', 'aaaa1111-1111-7111-8111-111111111101',
    '11111111-1111-7111-8111-111111111111', '22222222-2222-7222-8222-222222222222',
    'urn:uacrf:svc:checkout', 'gke-checkout-v43', 'gke-checkout-v42-rollback',
    'CONDITIONALLY_CERTIFIED', '["CLUSTER", "IDP"]'::jsonb,
    'Primary and rollback deployments share GKE cluster & IdP correlation domain; rollback only restores code artifact, not cluster/IdP failure.',
    'rev-sample-local-0001'
);
