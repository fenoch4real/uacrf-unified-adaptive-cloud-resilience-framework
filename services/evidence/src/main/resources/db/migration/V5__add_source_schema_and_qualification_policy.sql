ALTER TABLE uacrf_evidence.evidence_event
    ADD COLUMN service_id text,
    ADD COLUMN schema_version text
        CHECK (schema_version IS NULL OR schema_version IN ('UACRF_OBSERVATION_V1', 'UACRF_OBSERVATION_V2')),
    ADD CONSTRAINT provider_evidence_requires_schema
        CHECK (source_class <> 'PROVIDER' OR (service_id IS NOT NULL AND schema_version IS NOT NULL));

ALTER TABLE uacrf_evidence.quarantined_evidence
    ADD COLUMN service_id text,
    ADD COLUMN schema_version text
        CHECK (schema_version IS NULL OR schema_version IN ('UACRF_OBSERVATION_V1', 'UACRF_OBSERVATION_V2'));

CREATE TABLE uacrf_evidence.qualification_policy_head (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    id text NOT NULL,
    service_id text NOT NULL,
    purpose text NOT NULL,
    current_revision bigint NOT NULL CHECK (current_revision >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, id)
);

CREATE TABLE uacrf_evidence.qualification_policy_revision (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    id text NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    service_id text NOT NULL,
    purpose text NOT NULL,
    expires_at timestamptz NOT NULL,
    definition jsonb NOT NULL,
    digest char(64) NOT NULL CHECK (digest ~ '^[a-f0-9]{64}$'),
    proposed_by text NOT NULL,
    proposal_reason text NOT NULL CHECK (length(proposal_reason) BETWEEN 1 AND 2000),
    proposed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, id, revision),
    FOREIGN KEY (tenant_id, environment_id, id)
        REFERENCES uacrf_evidence.qualification_policy_head (tenant_id, environment_id, id)
);

CREATE TABLE uacrf_evidence.qualification_policy_decision (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    id text NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    decision text NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    reviewer_id text NOT NULL,
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 2000),
    decided_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, environment_id, id, revision),
    FOREIGN KEY (tenant_id, environment_id, id, revision)
        REFERENCES uacrf_evidence.qualification_policy_revision (tenant_id, environment_id, id, revision)
);

CREATE TABLE uacrf_evidence.qualification_policy_idempotency (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    principal_id text NOT NULL,
    operation text NOT NULL,
    idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200),
    request_digest char(64) NOT NULL CHECK (request_digest ~ '^[a-f0-9]{64}$'),
    policy_id text,
    policy_revision bigint,
    receipt_id text NOT NULL,
    response jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, principal_id, operation, idempotency_key),
    CHECK ((policy_id IS NULL) = (policy_revision IS NULL)),
    FOREIGN KEY (tenant_id, environment_id, policy_id, policy_revision)
        REFERENCES uacrf_evidence.qualification_policy_revision
            (tenant_id, environment_id, id, revision)
);

CREATE INDEX qualification_policy_service_idx
    ON uacrf_evidence.qualification_policy_head (tenant_id, environment_id, service_id, id);

CREATE INDEX qualification_policy_revision_page_idx
    ON uacrf_evidence.qualification_policy_revision
        (tenant_id, environment_id, service_id, proposed_at DESC, id DESC, revision DESC);

CREATE FUNCTION uacrf_evidence.reject_immutable_qualification_policy_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'qualification policy revisions and decisions are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER qualification_policy_revision_immutable
    BEFORE UPDATE OR DELETE ON uacrf_evidence.qualification_policy_revision
    FOR EACH ROW EXECUTE FUNCTION
        uacrf_evidence.reject_immutable_qualification_policy_mutation();

CREATE TRIGGER qualification_policy_decision_immutable
    BEFORE UPDATE OR DELETE ON uacrf_evidence.qualification_policy_decision
    FOR EACH ROW EXECUTE FUNCTION
        uacrf_evidence.reject_immutable_qualification_policy_mutation();

CREATE TRIGGER qualification_policy_idempotency_immutable
    BEFORE UPDATE OR DELETE ON uacrf_evidence.qualification_policy_idempotency
    FOR EACH ROW EXECUTE FUNCTION
        uacrf_evidence.reject_immutable_qualification_policy_mutation();
