CREATE SCHEMA IF NOT EXISTS uacrf_catalog;

CREATE TABLE uacrf_catalog.service (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    id text NOT NULL,
    name text NOT NULL,
    owner_id text NOT NULL,
    reason text NOT NULL,
    criticality integer NOT NULL CHECK (criticality BETWEEN 1 AND 5),
    lifecycle text NOT NULL CHECK (lifecycle IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
    revision bigint NOT NULL CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, id)
);

CREATE TABLE uacrf_catalog.agent_workload (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    id text NOT NULL,
    service_id text NOT NULL,
    name text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('SINGLE_AGENT', 'MULTI_AGENT')),
    owner_id text NOT NULL,
    runtime_principal_id text NOT NULL,
    purpose text NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, id),
    FOREIGN KEY (tenant_id, environment_id, service_id)
        REFERENCES uacrf_catalog.service (tenant_id, environment_id, id)
);

CREATE TABLE uacrf_catalog.customer_journey (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    id text NOT NULL,
    name text NOT NULL,
    owner_id text NOT NULL,
    purpose text NOT NULL,
    lifecycle text NOT NULL CHECK (lifecycle IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    revision bigint NOT NULL CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, id)
);

CREATE TABLE uacrf_catalog.journey_step (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    journey_id text NOT NULL,
    id text NOT NULL,
    position integer NOT NULL CHECK (position > 0),
    name text NOT NULL,
    intended_outcome text NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, journey_id, id),
    UNIQUE (tenant_id, environment_id, journey_id, position),
    FOREIGN KEY (tenant_id, environment_id, journey_id)
        REFERENCES uacrf_catalog.customer_journey (tenant_id, environment_id, id)
);

CREATE TABLE uacrf_catalog.journey_capability (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    journey_id text NOT NULL,
    id text NOT NULL,
    capability_kind text NOT NULL CHECK (capability_kind IN ('SERVICE', 'AGENT')),
    capability_id text NOT NULL,
    step_id text,
    criticality text NOT NULL CHECK (criticality IN ('REQUIRED', 'OPTIONAL', 'UNKNOWN')),
    revision bigint NOT NULL CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, journey_id, id),
    UNIQUE (tenant_id, environment_id, journey_id, capability_kind, capability_id),
    FOREIGN KEY (tenant_id, environment_id, journey_id)
        REFERENCES uacrf_catalog.customer_journey (tenant_id, environment_id, id),
    FOREIGN KEY (tenant_id, environment_id, journey_id, step_id)
        REFERENCES uacrf_catalog.journey_step (tenant_id, environment_id, journey_id, id)
);

CREATE TABLE uacrf_catalog.idempotency_record (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    actor_id text NOT NULL,
    operation text NOT NULL,
    idempotency_key text NOT NULL,
    payload_digest char(64) NOT NULL,
    response jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, actor_id, operation, idempotency_key)
);

CREATE TABLE uacrf_catalog.outbox_event (
    event_id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    aggregate_sequence bigint NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    UNIQUE (tenant_id, environment_id, aggregate_type, aggregate_id, aggregate_sequence)
);

CREATE INDEX service_scope_name_idx
    ON uacrf_catalog.service (tenant_id, environment_id, name);
CREATE INDEX journey_scope_name_idx
    ON uacrf_catalog.customer_journey (tenant_id, environment_id, name);
