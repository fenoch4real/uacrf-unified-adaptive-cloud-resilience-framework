CREATE SCHEMA IF NOT EXISTS uacrf_connections;

CREATE TABLE uacrf_connections.connection (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    id text NOT NULL,
    name text NOT NULL,
    provider text NOT NULL CHECK (provider IN ('SAMPLE_LOCAL', 'AWS', 'AZURE', 'GCP')),
    read_only_identity_ref text NOT NULL,
    owner_id text NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'UNACCEPTED')),
    status_reason text NOT NULL,
    revision bigint NOT NULL CHECK (revision >= 1),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, id)
);

CREATE TABLE uacrf_connections.source_binding (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    id text NOT NULL,
    connection_id text NOT NULL,
    source_ref text NOT NULL,
    service_ref text,
    revision bigint NOT NULL CHECK (revision >= 1),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, id),
    UNIQUE (tenant_id, environment_id, connection_id, source_ref),
    FOREIGN KEY (tenant_id, environment_id, connection_id)
        REFERENCES uacrf_connections.connection (tenant_id, environment_id, id)
);

CREATE TABLE uacrf_connections.idempotency_record (
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

CREATE TABLE uacrf_connections.outbox_event (
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
    published_at timestamptz,
    UNIQUE (tenant_id, environment_id, aggregate_type, aggregate_id, aggregate_sequence)
);

CREATE INDEX connection_scope_name_idx
    ON uacrf_connections.connection (tenant_id, environment_id, name);
