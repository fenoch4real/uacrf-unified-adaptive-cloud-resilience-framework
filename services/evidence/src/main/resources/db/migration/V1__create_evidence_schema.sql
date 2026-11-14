CREATE SCHEMA IF NOT EXISTS uacrf_evidence;

CREATE TABLE uacrf_evidence.evidence_event (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    id text NOT NULL,
    source_class text NOT NULL CHECK (source_class IN ('SAMPLE_LOCAL', 'PROVIDER')),
    source_ref text NOT NULL,
    sequence bigint NOT NULL CHECK (sequence >= 1),
    observed_at timestamptz NOT NULL,
    kind text NOT NULL,
    payload text NOT NULL,
    payload_digest char(64) NOT NULL,
    submitted_by text NOT NULL,
    qualification_state text NOT NULL
        CHECK (qualification_state IN ('SAMPLE_ONLY', 'UNVERIFIED', 'QUALIFIED', 'REJECTED')),
    revision bigint NOT NULL CHECK (revision >= 1),
    reviewed_by text,
    reviewed_at timestamptz,
    review_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, id),
    -- Sample data can never be qualified evidence.
    CHECK (source_class <> 'SAMPLE_LOCAL' OR qualification_state = 'SAMPLE_ONLY'),
    -- Qualification requires an independent reviewer.
    CHECK (qualification_state <> 'QUALIFIED' OR (reviewed_by IS NOT NULL AND reviewed_by <> submitted_by))
);

CREATE TABLE uacrf_evidence.source_cursor (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    source_ref text NOT NULL,
    last_sequence bigint NOT NULL,
    PRIMARY KEY (tenant_id, environment_id, source_ref)
);

CREATE TABLE uacrf_evidence.quarantined_evidence (
    quarantine_id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    evidence_id text NOT NULL,
    source_ref text NOT NULL,
    sequence bigint NOT NULL,
    reason text NOT NULL,
    payload text NOT NULL,
    claimed_digest text NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE uacrf_evidence.inbox_event (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    event_id text NOT NULL,
    source_service text NOT NULL,
    event_type text NOT NULL,
    aggregate_id text NOT NULL,
    aggregate_sequence bigint NOT NULL,
    payload text NOT NULL,
    payload_digest char(64) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, event_id),
    UNIQUE (tenant_id, environment_id, source_service, aggregate_id, aggregate_sequence)
);

CREATE TABLE uacrf_evidence.quarantined_inbox_event (
    quarantine_id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    event_id text NOT NULL,
    source_service text NOT NULL,
    aggregate_id text NOT NULL,
    aggregate_sequence bigint NOT NULL,
    reason text NOT NULL,
    payload text NOT NULL,
    claimed_digest text NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE uacrf_evidence.outbox_event (
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

CREATE INDEX evidence_scope_idx ON uacrf_evidence.evidence_event (tenant_id, environment_id, id);
