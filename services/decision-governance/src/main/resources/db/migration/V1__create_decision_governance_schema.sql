CREATE SCHEMA IF NOT EXISTS uacrf_decision_governance;

CREATE TABLE uacrf_decision_governance.policy_head (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    id text NOT NULL,
    service_id text NOT NULL,
    current_revision bigint NOT NULL CHECK (current_revision >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, id)
);

CREATE TABLE uacrf_decision_governance.policy_revision (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    id text NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    service_id text NOT NULL,
    expires_at timestamptz NOT NULL,
    definition jsonb NOT NULL,
    digest char(64) NOT NULL CHECK (digest ~ '^[a-f0-9]{64}$'),
    proposed_by text NOT NULL,
    proposal_reason text NOT NULL,
    proposed_at timestamptz NOT NULL,
    effective_from timestamptz NOT NULL,
    canonical_text text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, id, revision),
    FOREIGN KEY (tenant_id, environment_id, id)
        REFERENCES uacrf_decision_governance.policy_head (tenant_id, environment_id, id),
    CHECK (expires_at > effective_from)
);

CREATE TABLE uacrf_decision_governance.idempotency_record (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    principal_id text NOT NULL,
    operation text NOT NULL,
    idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200),
    request_digest char(64) NOT NULL CHECK (request_digest ~ '^[a-f0-9]{64}$'),
    policy_id text,
    policy_revision bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, principal_id, operation, idempotency_key),
    CHECK ((policy_id IS NULL) = (policy_revision IS NULL))
);

CREATE INDEX policy_head_service_list_idx
    ON uacrf_decision_governance.policy_head (tenant_id, environment_id, service_id);
CREATE INDEX policy_revision_page_idx
    ON uacrf_decision_governance.policy_revision
        (tenant_id, environment_id, service_id, proposed_at DESC, id DESC, revision DESC);

CREATE FUNCTION uacrf_decision_governance.reject_policy_revision_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'authorization policy revisions are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER policy_revision_immutable
    BEFORE UPDATE OR DELETE ON uacrf_decision_governance.policy_revision
    FOR EACH ROW EXECUTE FUNCTION
        uacrf_decision_governance.reject_policy_revision_mutation();
