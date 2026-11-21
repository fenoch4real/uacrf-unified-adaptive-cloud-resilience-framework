CREATE TABLE uacrf_objectives.local_objective_semantics (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    objective_id text NOT NULL,
    objective_content_digest char(64) NOT NULL CHECK (objective_content_digest ~ '^[a-f0-9]{64}$'),
    fingerprint_version text NOT NULL CHECK (fingerprint_version = 'OBJECTIVE_DEFINITION_EXACT_DECIMAL_V1'),
    normalized_fingerprint char(64) NOT NULL CHECK (normalized_fingerprint ~ '^[a-f0-9]{64}$'),
    normalized_definition text NOT NULL CHECK (octet_length(normalized_definition) <= 16384),
    PRIMARY KEY (tenant_id, environment_id, objective_id),
    FOREIGN KEY (tenant_id, environment_id, objective_id)
        REFERENCES uacrf_objectives.objective (tenant_id, environment_id, id)
);

CREATE FUNCTION uacrf_objectives.reject_local_objective_semantics_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'local objective semantics are immutable and retained' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER local_objective_semantics_immutable
    BEFORE UPDATE OR DELETE ON uacrf_objectives.local_objective_semantics
    FOR EACH ROW EXECUTE FUNCTION uacrf_objectives.reject_local_objective_semantics_mutation();
