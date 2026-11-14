ALTER TABLE uacrf_identity.membership
    ADD COLUMN revocation_reason text;

CREATE TABLE uacrf_identity.membership_guard (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    PRIMARY KEY (tenant_id, environment_id),
    FOREIGN KEY (tenant_id, environment_id)
        REFERENCES uacrf_identity.environment (tenant_id, id)
);

CREATE TABLE uacrf_identity.idempotency_record (
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

CREATE TABLE uacrf_identity.audit_event (
    event_id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    actor_id text NOT NULL,
    action text NOT NULL,
    membership_id uuid NOT NULL REFERENCES uacrf_identity.membership (id),
    principal_id text NOT NULL REFERENCES uacrf_identity.principal (id),
    capability text NOT NULL,
    service_scope text NOT NULL,
    previous_revision bigint NOT NULL,
    new_revision bigint NOT NULL,
    reason text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE uacrf_identity.outbox_event (
    event_id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    aggregate_sequence bigint NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    UNIQUE (tenant_id, environment_id, aggregate_type, aggregate_id, aggregate_sequence)
);
