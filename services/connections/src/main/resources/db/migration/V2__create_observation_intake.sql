ALTER TABLE uacrf_connections.source_binding
    ADD COLUMN last_sequence bigint NOT NULL DEFAULT 0 CHECK (last_sequence >= 0);

CREATE TABLE uacrf_connections.observation (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    connection_id text NOT NULL,
    id text NOT NULL,
    source_ref text NOT NULL,
    sequence bigint NOT NULL CHECK (sequence >= 1),
    observed_at timestamptz NOT NULL,
    kind text NOT NULL,
    payload text NOT NULL,
    payload_digest char(64) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, connection_id, id),
    UNIQUE (tenant_id, environment_id, connection_id, source_ref, sequence),
    FOREIGN KEY (tenant_id, environment_id, connection_id)
        REFERENCES uacrf_connections.connection (tenant_id, environment_id, id)
);

CREATE TABLE uacrf_connections.quarantined_observation (
    quarantine_id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    connection_id text NOT NULL,
    observation_id text NOT NULL,
    source_ref text NOT NULL,
    sequence bigint NOT NULL,
    reason text NOT NULL CHECK (reason IN ('DIGEST_MISMATCH', 'STALE_SEQUENCE', 'REPLAY_CONFLICT')),
    payload text NOT NULL,
    claimed_digest text NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, environment_id, connection_id)
        REFERENCES uacrf_connections.connection (tenant_id, environment_id, id)
);

CREATE INDEX quarantined_observation_scope_idx
    ON uacrf_connections.quarantined_observation (tenant_id, environment_id, connection_id, received_at);
