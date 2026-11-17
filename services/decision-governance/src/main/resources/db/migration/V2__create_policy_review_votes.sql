CREATE TABLE uacrf_decision_governance.policy_review_vote (
    vote_seq bigint GENERATED ALWAYS AS IDENTITY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    id text NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    reviewer_id text NOT NULL,
    decision text NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 2000),
    reviewed_at timestamptz NOT NULL,
    policy_digest char(64) NOT NULL CHECK (policy_digest ~ '^[a-f0-9]{64}$'),
    key_id text,
    key_digest char(64) CHECK (key_digest ~ '^[a-f0-9]{64}$'),
    trust_revision bigint CHECK (trust_revision > 0),
    trust_digest char(64) CHECK (trust_digest ~ '^[a-f0-9]{64}$'),
    signed_assertion text,
    assertion_digest char(64) CHECK (assertion_digest ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, id, revision, reviewer_id),
    UNIQUE (vote_seq),
    FOREIGN KEY (tenant_id, environment_id, id, revision)
        REFERENCES uacrf_decision_governance.policy_revision (tenant_id, environment_id, id, revision),
    CHECK (
        (decision = 'APPROVE'
            AND key_id IS NOT NULL AND key_digest IS NOT NULL AND trust_revision IS NOT NULL
            AND trust_digest IS NOT NULL AND signed_assertion IS NOT NULL
            AND assertion_digest IS NOT NULL)
        OR (decision = 'REJECT'
            AND key_id IS NULL AND key_digest IS NULL AND trust_revision IS NULL
            AND trust_digest IS NULL AND signed_assertion IS NULL AND assertion_digest IS NULL)
    )
);

CREATE INDEX policy_review_vote_revision_idx
    ON uacrf_decision_governance.policy_review_vote (tenant_id, environment_id, id, revision, vote_seq);

CREATE TABLE uacrf_decision_governance.policy_trust_revision (
    revision bigint PRIMARY KEY CHECK (revision > 0),
    trust_digest char(64) NOT NULL CHECK (trust_digest ~ '^[a-f0-9]{64}$'),
    recorded_at timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION uacrf_decision_governance.reject_immutable_review_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'authorization policy review records are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER policy_review_vote_immutable
    BEFORE UPDATE OR DELETE ON uacrf_decision_governance.policy_review_vote
    FOR EACH ROW EXECUTE FUNCTION
        uacrf_decision_governance.reject_immutable_review_mutation();

CREATE TRIGGER policy_trust_revision_immutable
    BEFORE UPDATE OR DELETE ON uacrf_decision_governance.policy_trust_revision
    FOR EACH ROW EXECUTE FUNCTION
        uacrf_decision_governance.reject_immutable_review_mutation();
