CREATE TABLE uacrf_connections.collection_checkpoint (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    connection_id text NOT NULL,
    source_ref text NOT NULL,
    checkpoint text,
    last_collected_at timestamptz,
    pending_observations jsonb,
    pending_next_checkpoint text,
    pending_collected_at timestamptz,
    next_collection_at timestamptz NOT NULL DEFAULT now(),
    lease_token uuid,
    lease_until timestamptz,
    last_status text NOT NULL DEFAULT 'NEVER_RUN'
        CHECK (last_status IN (
            'NEVER_RUN', 'COLLECTED', 'RATE_LIMITED', 'PERMISSION_DENIED',
            'SOURCE_UNAVAILABLE', 'NOT_IMPLEMENTED', 'FAILED')),
    last_detail text NOT NULL DEFAULT 'Collection has not run yet.',
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, connection_id, source_ref),
    CHECK ((pending_observations IS NULL) = (pending_collected_at IS NULL)),
    CHECK (pending_observations IS NOT NULL OR pending_next_checkpoint IS NULL),
    FOREIGN KEY (tenant_id, environment_id, connection_id, source_ref)
        REFERENCES uacrf_connections.source_binding
            (tenant_id, environment_id, connection_id, source_ref)
);

CREATE INDEX collection_checkpoint_due_idx
    ON uacrf_connections.collection_checkpoint
        (next_collection_at, lease_until, tenant_id, environment_id);

INSERT INTO uacrf_connections.collection_checkpoint
    (tenant_id, environment_id, connection_id, source_ref)
SELECT tenant_id, environment_id, connection_id, source_ref
FROM uacrf_connections.source_binding;
