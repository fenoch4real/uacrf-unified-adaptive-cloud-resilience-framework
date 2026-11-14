CREATE TABLE uacrf_identity.principal_status_audit (
    event_id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    actor_id text NOT NULL REFERENCES uacrf_identity.principal (id),
    principal_id text NOT NULL REFERENCES uacrf_identity.principal (id),
    previous_enabled boolean NOT NULL,
    new_enabled boolean NOT NULL,
    previous_revision bigint NOT NULL,
    new_revision bigint NOT NULL,
    reason text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, environment_id)
        REFERENCES uacrf_identity.environment (tenant_id, id)
);
