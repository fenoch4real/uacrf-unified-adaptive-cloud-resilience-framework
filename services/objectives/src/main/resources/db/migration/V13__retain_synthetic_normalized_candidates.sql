CREATE UNIQUE INDEX synthetic_mapping_review_context_identity_idx
    ON uacrf_objectives.synthetic_objective_mapping_review
       (tenant_id, environment_id, command_id, capture_id, proposal_id, review_id);

CREATE TABLE uacrf_objectives.synthetic_normalized_candidate (
    candidate_id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    command_id uuid NOT NULL,
    capture_id uuid NOT NULL,
    proposal_id uuid NOT NULL,
    review_id uuid NOT NULL,
    created_by text NOT NULL,
    idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200),
    request_digest char(64) NOT NULL CHECK (request_digest ~ '^[a-f0-9]{64}$'),
    proposal_digest_version text NOT NULL CHECK (proposal_digest_version = 'SYNTHETIC_MAPPING_PROPOSAL_SHA256_V1'),
    proposal_digest char(64) NOT NULL CHECK (proposal_digest ~ '^[a-f0-9]{64}$'),
    fingerprint_version text NOT NULL CHECK (fingerprint_version = 'OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1'),
    normalized_fingerprint char(64) NOT NULL CHECK (normalized_fingerprint ~ '^[a-f0-9]{64}$'),
    normalized_definition text NOT NULL CHECK (octet_length(normalized_definition) <= 16384),
    status text NOT NULL CHECK (status = 'UNCOMPARED'),
    verification text NOT NULL CHECK (verification = 'SYNTHETIC_UNVERIFIED'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, environment_id, created_by, idempotency_key),
    UNIQUE (tenant_id, environment_id, command_id, capture_id, proposal_id, review_id),
    FOREIGN KEY (tenant_id, environment_id, command_id, capture_id, proposal_id, review_id)
        REFERENCES uacrf_objectives.synthetic_objective_mapping_review
            (tenant_id, environment_id, command_id, capture_id, proposal_id, review_id)
);

CREATE FUNCTION uacrf_objectives.reject_synthetic_normalized_candidate_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'synthetic normalized candidates are immutable and retained' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER synthetic_normalized_candidate_immutable
    BEFORE UPDATE OR DELETE ON uacrf_objectives.synthetic_normalized_candidate
    FOR EACH ROW EXECUTE FUNCTION uacrf_objectives.reject_synthetic_normalized_candidate_mutation();
