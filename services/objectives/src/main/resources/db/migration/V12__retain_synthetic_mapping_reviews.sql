CREATE UNIQUE INDEX synthetic_mapping_proposal_context_identity_idx
    ON uacrf_objectives.synthetic_objective_mapping_proposal
       (tenant_id, environment_id, command_id, capture_id, proposal_id);

CREATE TABLE uacrf_objectives.synthetic_objective_mapping_review (
    review_id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    command_id uuid NOT NULL,
    capture_id uuid NOT NULL,
    proposal_id uuid NOT NULL,
    reviewer_id text NOT NULL,
    idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200),
    request_digest char(64) NOT NULL CHECK (request_digest ~ '^[a-f0-9]{64}$'),
    proposal_digest_version text NOT NULL CHECK (proposal_digest_version = 'SYNTHETIC_MAPPING_PROPOSAL_SHA256_V1'),
    proposal_digest char(64) NOT NULL CHECK (proposal_digest ~ '^[a-f0-9]{64}$'),
    decision text NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 1000),
    verification text NOT NULL CHECK (verification = 'SYNTHETIC_UNVERIFIED'),
    reviewed_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, environment_id, reviewer_id, idempotency_key),
    UNIQUE (tenant_id, environment_id, command_id, capture_id, proposal_id),
    FOREIGN KEY (tenant_id, environment_id, command_id, capture_id, proposal_id)
        REFERENCES uacrf_objectives.synthetic_objective_mapping_proposal
            (tenant_id, environment_id, command_id, capture_id, proposal_id)
);

CREATE FUNCTION uacrf_objectives.reject_synthetic_objective_mapping_review_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'synthetic objective mapping reviews are immutable and retained' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER synthetic_objective_mapping_review_immutable
    BEFORE UPDATE OR DELETE ON uacrf_objectives.synthetic_objective_mapping_review
    FOR EACH ROW EXECUTE FUNCTION uacrf_objectives.reject_synthetic_objective_mapping_review_mutation();
