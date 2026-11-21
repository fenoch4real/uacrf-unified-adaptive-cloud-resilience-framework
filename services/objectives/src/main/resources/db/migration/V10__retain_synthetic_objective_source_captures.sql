CREATE UNIQUE INDEX objective_import_preview_context_identity_idx
    ON uacrf_objectives.objective_import_preview_command (tenant_id, environment_id, command_id);

CREATE TABLE uacrf_objectives.synthetic_objective_source_capture (
    capture_id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    command_id uuid NOT NULL,
    actor_id text NOT NULL,
    idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200),
    request_digest char(64) NOT NULL CHECK (request_digest ~ '^[a-f0-9]{64}$'),
    connection_id text NOT NULL,
    connection_revision bigint NOT NULL CHECK (connection_revision > 0),
    binding_id text NOT NULL,
    binding_revision bigint NOT NULL CHECK (binding_revision > 0),
    target_workload_id text NOT NULL,
    source_system text NOT NULL CHECK (source_system LIKE 'sample:%'),
    external_objective_id text NOT NULL CHECK (external_objective_id LIKE 'sample:%'),
    source_version text NOT NULL CHECK (source_version LIKE 'sample-%'),
    payload text NOT NULL CHECK (octet_length(payload) <= 16384),
    payload_digest char(64) NOT NULL CHECK (payload_digest ~ '^[a-f0-9]{64}$'),
    definition_fingerprint_version text NOT NULL
        CHECK (definition_fingerprint_version = 'OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1'),
    definition_fingerprint char(64) NOT NULL CHECK (definition_fingerprint ~ '^[a-f0-9]{64}$'),
    verification text NOT NULL CHECK (verification = 'SYNTHETIC_UNVERIFIED'),
    captured_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, environment_id, actor_id, idempotency_key),
    FOREIGN KEY (tenant_id, environment_id, command_id)
        REFERENCES uacrf_objectives.objective_import_preview_command (tenant_id, environment_id, command_id)
);

CREATE FUNCTION uacrf_objectives.reject_synthetic_objective_source_capture_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'synthetic objective source captures are immutable and retained' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER synthetic_objective_source_capture_immutable
    BEFORE UPDATE OR DELETE ON uacrf_objectives.synthetic_objective_source_capture
    FOR EACH ROW EXECUTE FUNCTION uacrf_objectives.reject_synthetic_objective_source_capture_mutation();
