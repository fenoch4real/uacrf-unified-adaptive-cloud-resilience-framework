CREATE UNIQUE INDEX synthetic_objective_capture_context_identity_idx
    ON uacrf_objectives.synthetic_objective_source_capture
       (tenant_id, environment_id, command_id, capture_id);

CREATE TABLE uacrf_objectives.synthetic_objective_mapping_proposal (
    proposal_id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    command_id uuid NOT NULL,
    capture_id uuid NOT NULL,
    proposed_by text NOT NULL,
    idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200),
    request_digest char(64) NOT NULL CHECK (request_digest ~ '^[a-f0-9]{64}$'),
    target_unit text NOT NULL CHECK (length(target_unit) BETWEEN 1 AND 80),
    allow_fraction_to_percent boolean NOT NULL,
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 1000),
    fingerprint_version text NOT NULL CHECK (fingerprint_version = 'OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1'),
    source_fingerprint char(64) NOT NULL CHECK (source_fingerprint ~ '^[a-f0-9]{64}$'),
    normalized_fingerprint char(64) NOT NULL CHECK (normalized_fingerprint ~ '^[a-f0-9]{64}$'),
    status text NOT NULL CHECK (status = 'UNAPPROVED'),
    verification text NOT NULL CHECK (verification = 'SYNTHETIC_UNVERIFIED'),
    proposed_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, environment_id, proposed_by, idempotency_key),
    FOREIGN KEY (tenant_id, environment_id, command_id, capture_id)
        REFERENCES uacrf_objectives.synthetic_objective_source_capture
            (tenant_id, environment_id, command_id, capture_id)
);

CREATE FUNCTION uacrf_objectives.reject_synthetic_objective_mapping_proposal_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'synthetic objective mapping proposals are immutable and retained' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER synthetic_objective_mapping_proposal_immutable
    BEFORE UPDATE OR DELETE ON uacrf_objectives.synthetic_objective_mapping_proposal
    FOR EACH ROW EXECUTE FUNCTION uacrf_objectives.reject_synthetic_objective_mapping_proposal_mutation();
