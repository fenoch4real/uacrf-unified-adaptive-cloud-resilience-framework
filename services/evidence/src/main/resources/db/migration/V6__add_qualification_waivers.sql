CREATE TABLE uacrf_evidence.qualification_waiver (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    id text NOT NULL,
    policy_id text NOT NULL,
    policy_revision bigint NOT NULL CHECK (policy_revision > 0),
    requirement_id text NOT NULL,
    observation_window_start timestamptz NOT NULL,
    observation_window_end timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    gap_types text[] NOT NULL CHECK (
        cardinality(gap_types) BETWEEN 1 AND 3
        AND gap_types <@ ARRAY['MISSING', 'STALE', 'INCOMPLETE_WINDOW']::text[]
    ),
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 2000),
    service_id text NOT NULL,
    purpose text NOT NULL,
    requested_by text NOT NULL,
    digest char(64) NOT NULL CHECK (digest ~ '^[a-f0-9]{64}$'),
    signing_material text NOT NULL,
    proposed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, id),
    FOREIGN KEY (tenant_id, environment_id, policy_id, policy_revision)
        REFERENCES uacrf_evidence.qualification_policy_revision
            (tenant_id, environment_id, id, revision),
    CHECK (observation_window_start < observation_window_end)
);

CREATE TABLE uacrf_evidence.qualification_waiver_decision (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    waiver_id text NOT NULL,
    decision text NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    reviewer_id text NOT NULL,
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 2000),
    decided_at timestamptz NOT NULL,
    trust_revision bigint,
    trust_digest char(64) CHECK (trust_digest IS NULL OR trust_digest ~ '^[a-f0-9]{64}$'),
    trust_key_id text,
    trust_key_digest char(64) CHECK (trust_key_digest IS NULL OR trust_key_digest ~ '^[a-f0-9]{64}$'),
    assertion_digest char(64) CHECK (assertion_digest IS NULL OR assertion_digest ~ '^[a-f0-9]{64}$'),
    assertion_jti text,
    PRIMARY KEY (tenant_id, environment_id, waiver_id),
    FOREIGN KEY (tenant_id, environment_id, waiver_id)
        REFERENCES uacrf_evidence.qualification_waiver (tenant_id, environment_id, id),
    CHECK (
        (decision = 'APPROVED'
            AND trust_revision > 0
            AND trust_digest IS NOT NULL
            AND trust_key_id IS NOT NULL
            AND trust_key_digest IS NOT NULL
            AND assertion_digest IS NOT NULL
            AND assertion_jti IS NOT NULL)
        OR
        (decision = 'REJECTED'
            AND trust_revision IS NULL
            AND trust_digest IS NULL
            AND trust_key_id IS NULL
            AND trust_key_digest IS NULL
            AND assertion_digest IS NULL
            AND assertion_jti IS NULL)
    ),
    UNIQUE (tenant_id, environment_id, assertion_jti)
);

CREATE TABLE uacrf_evidence.qualification_waiver_revocation (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    waiver_id text NOT NULL,
    revoked_by text NOT NULL,
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 2000),
    revoked_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, environment_id, waiver_id),
    FOREIGN KEY (tenant_id, environment_id, waiver_id)
        REFERENCES uacrf_evidence.qualification_waiver (tenant_id, environment_id, id)
);

CREATE TABLE uacrf_evidence.qualification_waiver_idempotency (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    principal_id text NOT NULL,
    operation text NOT NULL CHECK (operation IN ('PROPOSE', 'DECIDE', 'REVOKE')),
    idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200),
    request_digest char(64) NOT NULL CHECK (request_digest ~ '^[a-f0-9]{64}$'),
    waiver_id text NOT NULL,
    receipt_id text NOT NULL,
    response jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, principal_id, operation, idempotency_key),
    FOREIGN KEY (tenant_id, environment_id, waiver_id)
        REFERENCES uacrf_evidence.qualification_waiver (tenant_id, environment_id, id)
);

CREATE TABLE uacrf_evidence.qualification_waiver_trust_head (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    service_id text NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    digest char(64) NOT NULL CHECK (digest ~ '^[a-f0-9]{64}$'),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, service_id)
);

CREATE INDEX qualification_waiver_service_page_idx
    ON uacrf_evidence.qualification_waiver
        (tenant_id, environment_id, service_id, id);

CREATE INDEX qualification_waiver_policy_idx
    ON uacrf_evidence.qualification_waiver
        (tenant_id, environment_id, policy_id, policy_revision, requirement_id);

CREATE FUNCTION uacrf_evidence.reject_immutable_qualification_waiver_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'qualification waiver records are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER qualification_waiver_immutable
    BEFORE UPDATE OR DELETE ON uacrf_evidence.qualification_waiver
    FOR EACH ROW EXECUTE FUNCTION
        uacrf_evidence.reject_immutable_qualification_waiver_mutation();

CREATE TRIGGER qualification_waiver_decision_immutable
    BEFORE UPDATE OR DELETE ON uacrf_evidence.qualification_waiver_decision
    FOR EACH ROW EXECUTE FUNCTION
        uacrf_evidence.reject_immutable_qualification_waiver_mutation();

CREATE TRIGGER qualification_waiver_revocation_immutable
    BEFORE UPDATE OR DELETE ON uacrf_evidence.qualification_waiver_revocation
    FOR EACH ROW EXECUTE FUNCTION
        uacrf_evidence.reject_immutable_qualification_waiver_mutation();

CREATE TRIGGER qualification_waiver_idempotency_immutable
    BEFORE UPDATE OR DELETE ON uacrf_evidence.qualification_waiver_idempotency
    FOR EACH ROW EXECUTE FUNCTION
        uacrf_evidence.reject_immutable_qualification_waiver_mutation();
