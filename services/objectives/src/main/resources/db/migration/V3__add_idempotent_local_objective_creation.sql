CREATE TABLE uacrf_objectives.local_objective_creation (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    actor_id text NOT NULL,
    idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200),
    request_digest char(64) NOT NULL CHECK (request_digest ~ '^[a-f0-9]{64}$'),
    objective_id text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, actor_id, idempotency_key),
    FOREIGN KEY (tenant_id, environment_id, objective_id)
        REFERENCES uacrf_objectives.objective (tenant_id, environment_id, id)
        DEFERRABLE INITIALLY DEFERRED
);
