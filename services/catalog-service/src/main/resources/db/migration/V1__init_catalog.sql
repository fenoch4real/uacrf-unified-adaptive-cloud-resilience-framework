CREATE SCHEMA IF NOT EXISTS catalog;
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE catalog.service (
    service_id       uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL,
    environment_id   uuid NOT NULL,
    canonical_id     text NOT NULL,
    name             text NOT NULL,
    domain           text NOT NULL,
    description      text,
    service_kind     text NOT NULL CHECK (service_kind IN ('CONVENTIONAL', 'AGENT_ANCHOR')),
    criticality      text NOT NULL CHECK (criticality IN ('P0', 'P1', 'P2', 'P3', 'UNKNOWN')),
    lifecycle        text NOT NULL CHECK (lifecycle IN ('PROPOSED', 'ACTIVE', 'DEPRECATED', 'RETIRED')),
    owner_id         uuid NOT NULL,
    version          bigint NOT NULL DEFAULT 1,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, environment_id, domain, name)
);

CREATE TABLE catalog.agent_workload_profile (
    workload_profile_id uuid PRIMARY KEY,
    tenant_id           uuid NOT NULL,
    environment_id      uuid NOT NULL,
    service_anchor_id   uuid NOT NULL UNIQUE REFERENCES catalog.service(service_id),
    parent_service_id   uuid REFERENCES catalog.service(service_id),
    name                text NOT NULL,
    workload_kind       text NOT NULL CHECK (workload_kind IN ('SINGLE_AGENT', 'MULTI_AGENT')),
    intended_purpose    text NOT NULL,
    owner_id            uuid NOT NULL,
    runtime_principal_id uuid NOT NULL,
    criticality         text NOT NULL CHECK (criticality IN ('P0', 'P1', 'P2', 'P3', 'UNKNOWN')),
    active_version      bigint,
    active_digest       text,
    lifecycle           text NOT NULL CHECK (lifecycle IN ('PROPOSED', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
    version             bigint NOT NULL DEFAULT 1,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, environment_id, name)
);

CREATE TABLE catalog.agent_workload_version (
    workload_version_id uuid PRIMARY KEY,
    workload_profile_id uuid NOT NULL REFERENCES catalog.agent_workload_profile(workload_profile_id),
    tenant_id           uuid NOT NULL,
    environment_id      uuid NOT NULL,
    version_number      bigint NOT NULL,
    manifest            jsonb NOT NULL,
    manifest_digest     text NOT NULL,
    author_id           uuid NOT NULL,
    status              text NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'SUPERSEDED', 'RETIRED')),
    activated_by        uuid,
    activated_at        timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workload_profile_id, version_number),
    CHECK (activated_by IS NULL OR activated_by <> author_id)
);

CREATE TABLE catalog.customer_journey (
    journey_id          uuid PRIMARY KEY,
    tenant_id           uuid NOT NULL,
    environment_id      uuid NOT NULL,
    name                text NOT NULL,
    purpose             text NOT NULL,
    owner_id            uuid NOT NULL,
    operating_window    text NOT NULL DEFAULT '24x7',
    lifecycle           text NOT NULL CHECK (lifecycle IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    version             bigint NOT NULL DEFAULT 1,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, environment_id, name)
);

CREATE TABLE catalog.journey_step (
    step_id             uuid PRIMARY KEY,
    journey_id          uuid NOT NULL REFERENCES catalog.customer_journey(journey_id),
    tenant_id           uuid NOT NULL,
    environment_id      uuid NOT NULL,
    sequence_num        integer NOT NULL CHECK (sequence_num > 0),
    name                text NOT NULL,
    success_condition   text NOT NULL,
    timeout_seconds     integer NOT NULL DEFAULT 30,
    failure_behavior    text NOT NULL DEFAULT 'DEGRADE',
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (journey_id, sequence_num)
);

CREATE TABLE catalog.journey_capability (
    link_id                   uuid PRIMARY KEY,
    journey_id                uuid NOT NULL REFERENCES catalog.customer_journey(journey_id),
    step_id                   uuid REFERENCES catalog.journey_step(step_id),
    tenant_id                 uuid NOT NULL,
    environment_id            uuid NOT NULL,
    capability_kind           text NOT NULL CHECK (capability_kind IN ('SERVICE', 'AGENT')),
    target_id                 uuid NOT NULL,
    participation_criticality text NOT NULL CHECK (participation_criticality IN ('REQUIRED', 'OPTIONAL', 'UNKNOWN')),
    rationale                 text NOT NULL,
    evidence_ref              text,
    linked_by                 uuid NOT NULL,
    retired_at                timestamptz,
    created_at                timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE catalog.journey_impact_profile (
    profile_id                uuid PRIMARY KEY,
    journey_id                uuid NOT NULL REFERENCES catalog.customer_journey(journey_id),
    tenant_id                 uuid NOT NULL,
    environment_id            uuid NOT NULL,
    revision                  bigint NOT NULL,
    impact_if_unavailable     text NOT NULL,
    affected_population       text NOT NULL,
    criticality_assessment    text NOT NULL CHECK (criticality_assessment IN ('P0', 'P1', 'P2', 'P3', 'UNKNOWN')),
    assessment_status         text NOT NULL CHECK (assessment_status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'UNKNOWN')),
    business_tolerance_value  numeric,
    business_tolerance_unit   text,
    business_tolerance_basis  text,
    rationale                 text NOT NULL,
    content_digest            text NOT NULL,
    author_id                 uuid NOT NULL,
    reviewer_id               uuid,
    review_reason             text,
    reviewed_at               timestamptz,
    created_at                timestamptz NOT NULL DEFAULT now(),
    UNIQUE (journey_id, revision),
    CHECK (reviewer_id IS NULL OR reviewer_id <> author_id)
);

CREATE TABLE catalog.journey_impact_dimension (
    dimension_id    uuid PRIMARY KEY,
    profile_id      uuid NOT NULL REFERENCES catalog.journey_impact_profile(profile_id),
    dimension_type  text NOT NULL CHECK (dimension_type IN ('CUSTOMER', 'FINANCIAL', 'REGULATORY', 'SAFETY', 'REPUTATIONAL', 'OPERATIONAL')),
    description     text NOT NULL,
    severity        text NOT NULL CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'))
);

CREATE TABLE catalog.shared_resource (
    shared_asset_id uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL,
    environment_id  uuid NOT NULL,
    name            text NOT NULL,
    asset_kind      text NOT NULL CHECK (asset_kind IN ('MODEL', 'KNOWLEDGE_BASE', 'TOOL', 'DATA_STORE', 'IDENTITY', 'RUNTIME')),
    classification  text NOT NULL DEFAULT 'INTERNAL',
    owner_id        uuid NOT NULL,
    lifecycle       text NOT NULL CHECK (lifecycle IN ('ACTIVE', 'DEPRECATED', 'RETIRED')),
    version         bigint NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, environment_id, name)
);

CREATE TABLE catalog.capability_asset (
    link_id          uuid PRIMARY KEY,
    shared_asset_id  uuid NOT NULL REFERENCES catalog.shared_resource(shared_asset_id),
    tenant_id        uuid NOT NULL,
    environment_id   uuid NOT NULL,
    consumer_kind    text NOT NULL CHECK (consumer_kind IN ('SERVICE', 'AGENT')),
    consumer_id      uuid NOT NULL,
    usage_role       text NOT NULL CHECK (usage_role IN ('MODEL', 'TOOL', 'KNOWLEDGE', 'MEMORY', 'IDENTITY', 'RUNTIME')),
    rationale        text NOT NULL,
    linked_by        uuid NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE catalog.idempotency_record (
    tenant_id       uuid NOT NULL,
    environment_id  uuid NOT NULL,
    actor_id        uuid NOT NULL,
    operation       text NOT NULL,
    idempotency_key text NOT NULL,
    request_digest  text NOT NULL,
    response_status integer NOT NULL,
    response_body   jsonb NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, actor_id, operation, idempotency_key)
);

CREATE TABLE catalog.audit_event (
    audit_event_id uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL,
    environment_id uuid NOT NULL,
    actor_id       uuid NOT NULL,
    action         text NOT NULL,
    entity_type    text NOT NULL,
    entity_id      uuid NOT NULL,
    reason         text,
    after_digest   text,
    occurred_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE integration.outbox_event (
    outbox_event_id   uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL,
    aggregate_type    text NOT NULL,
    aggregate_id      uuid NOT NULL,
    aggregate_version bigint NOT NULL,
    event_type        text NOT NULL,
    payload           jsonb NOT NULL,
    payload_digest    text NOT NULL,
    occurred_at       timestamptz NOT NULL DEFAULT now(),
    publish_status    text NOT NULL DEFAULT 'pending',
    attempt_count     integer NOT NULL DEFAULT 0,
    next_attempt_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type)
);
