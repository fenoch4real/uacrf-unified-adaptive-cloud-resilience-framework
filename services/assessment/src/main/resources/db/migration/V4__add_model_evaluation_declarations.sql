CREATE TABLE uacrf_assessment.model_evaluation_declaration (
 tenant_id text NOT NULL,
 environment_id text NOT NULL,
 id text NOT NULL,
 model_version_id text NOT NULL,
 model_digest text NOT NULL CHECK (model_digest ~ '^[0-9a-f]{64}$'),
 training_data_digest text NOT NULL CHECK (training_data_digest ~ '^[0-9a-f]{64}$'),
 holdout_data_digest text NOT NULL CHECK (holdout_data_digest ~ '^[0-9a-f]{64}$'),
 result_ref text NOT NULL CHECK (length(result_ref) BETWEEN 1 AND 1000),
 result_digest text NOT NULL CHECK (result_digest ~ '^[0-9a-f]{64}$'),
 training_through text NOT NULL,
 holdout_from text NOT NULL,
 holdout_through text NOT NULL,
 reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 2000),
 status text NOT NULL CHECK (status = 'DECLARED_UNVERIFIED'),
 declared_by text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY (tenant_id, environment_id, id),
 FOREIGN KEY (tenant_id, environment_id, model_version_id)
   REFERENCES uacrf_assessment.model_version (tenant_id, environment_id, id)
);

CREATE INDEX model_evaluation_declaration_model_idx
 ON uacrf_assessment.model_evaluation_declaration (tenant_id, environment_id, model_version_id);

CREATE FUNCTION uacrf_assessment.protect_model_evaluation_declaration() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'model evaluation declarations are immutable' USING ERRCODE = '23514';
END $$;

CREATE TRIGGER protect_model_evaluation_declaration
 BEFORE UPDATE OR DELETE ON uacrf_assessment.model_evaluation_declaration
 FOR EACH ROW EXECUTE FUNCTION uacrf_assessment.protect_model_evaluation_declaration();
