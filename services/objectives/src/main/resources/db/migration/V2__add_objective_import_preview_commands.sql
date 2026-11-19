CREATE TABLE uacrf_objectives.objective_import_preview_command (
    command_id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    actor_id text NOT NULL,
    idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200),
    target_workload_id text NOT NULL,
    request_digest char(64) NOT NULL CHECK (request_digest ~ '^[a-f0-9]{64}$'),
    request_body jsonb NOT NULL,
    status text NOT NULL CHECK (status = 'PENDING'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, environment_id, actor_id, idempotency_key)
);

CREATE INDEX objective_import_preview_context_idx
    ON uacrf_objectives.objective_import_preview_command
       (tenant_id, environment_id, target_workload_id, created_at, command_id);
