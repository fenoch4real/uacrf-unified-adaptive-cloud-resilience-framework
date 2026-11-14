CREATE SCHEMA IF NOT EXISTS uacrf_objectives;

CREATE TABLE uacrf_objectives.organization_constraint (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    metric text NOT NULL,
    unit text NOT NULL,
    min_target numeric(24, 6),
    max_target numeric(24, 6),
    updated_by text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, metric),
    CHECK (min_target IS NULL OR max_target IS NULL OR min_target <= max_target)
);

CREATE TABLE uacrf_objectives.objective (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    id text NOT NULL,
    workload_id text NOT NULL,
    title text NOT NULL,
    metric text NOT NULL,
    comparator text NOT NULL CHECK (comparator IN ('GTE', 'LTE')),
    target numeric(24, 6) NOT NULL,
    unit text NOT NULL,
    window_seconds integer NOT NULL CHECK (window_seconds > 0),
    origin text NOT NULL CHECK (origin IN ('LOCAL', 'IMPORTED')),
    source_ref text,
    source_definition_id text,
    source_revision text,
    source_verified_at timestamptz,
    content_digest char(64) NOT NULL,
    state text NOT NULL
        CHECK (state IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'ACTIVE', 'SUPERSEDED')),
    revision bigint NOT NULL CHECK (revision >= 1),
    proposed_by text NOT NULL,
    decided_by text,
    decision_reason text,
    activated_by text,
    activated_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, id),
    -- Imported objectives must keep their source definition identity and verification.
    CHECK (origin = 'LOCAL'
        OR (source_ref IS NOT NULL AND source_definition_id IS NOT NULL
            AND source_revision IS NOT NULL AND source_verified_at IS NOT NULL)),
    -- Approval requires an independent decider.
    CHECK (state NOT IN ('APPROVED', 'ACTIVE', 'SUPERSEDED') OR decided_by IS NULL OR decided_by <> proposed_by)
);

-- At most one active objective per workload and metric.
CREATE UNIQUE INDEX objective_one_active_idx
    ON uacrf_objectives.objective (tenant_id, environment_id, workload_id, metric)
    WHERE state = 'ACTIVE';

CREATE INDEX objective_workload_idx
    ON uacrf_objectives.objective (tenant_id, environment_id, workload_id, id);

CREATE TABLE uacrf_objectives.outbox_event (
    event_id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    aggregate_sequence bigint NOT NULL,
    event_type text NOT NULL,
    schema_version int NOT NULL DEFAULT 1,
    payload jsonb NOT NULL,
    payload_digest char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
