ALTER TABLE uacrf_identity.membership
    ADD COLUMN grant_reason text,
    ADD COLUMN grant_decision_id text;

CREATE TABLE uacrf_identity.grant_decision (
    id text PRIMARY KEY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    approver_id text NOT NULL REFERENCES uacrf_identity.principal (id),
    principal_id text NOT NULL REFERENCES uacrf_identity.principal (id),
    capability text NOT NULL,
    service_scope text NOT NULL,
    membership_expires_at timestamptz NOT NULL,
    reason text NOT NULL,
    decision_expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    consumed_membership_id uuid REFERENCES uacrf_identity.membership (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, environment_id)
        REFERENCES uacrf_identity.environment (tenant_id, id),
    CHECK (approver_id <> principal_id),
    CHECK ((consumed_at IS NULL) = (consumed_membership_id IS NULL))
);

CREATE UNIQUE INDEX grant_decision_single_use_idx
    ON uacrf_identity.grant_decision (consumed_membership_id)
    WHERE consumed_membership_id IS NOT NULL;

ALTER TABLE uacrf_identity.membership
    ADD CONSTRAINT membership_grant_decision_fk
    FOREIGN KEY (grant_decision_id) REFERENCES uacrf_identity.grant_decision (id);
