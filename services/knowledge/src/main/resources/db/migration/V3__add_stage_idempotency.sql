CREATE TABLE uacrf_knowledge.stage_idempotency (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    actor_id text NOT NULL,
    idempotency_key text NOT NULL,
    payload_digest char(64) NOT NULL CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
    item_id text,
    version text,
    receipt jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, environment_id, actor_id, idempotency_key),
    FOREIGN KEY (tenant_id, environment_id, item_id, version)
        REFERENCES uacrf_knowledge.knowledge_version (tenant_id, environment_id, item_id, version),
    CHECK ((item_id IS NULL AND version IS NULL AND receipt IS NULL)
        OR (item_id IS NOT NULL AND version IS NOT NULL AND receipt IS NOT NULL))
);
