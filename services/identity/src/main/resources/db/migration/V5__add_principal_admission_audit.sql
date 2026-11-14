CREATE TABLE uacrf_identity.principal_admission_audit (
    event_id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    actor_id text NOT NULL REFERENCES uacrf_identity.principal (id),
    principal_id text NOT NULL REFERENCES uacrf_identity.principal (id),
    provider_id text NOT NULL REFERENCES uacrf_identity.identity_provider (id),
    directory_subject_ref text NOT NULL,
    reason text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, environment_id)
        REFERENCES uacrf_identity.environment (tenant_id, id)
);
