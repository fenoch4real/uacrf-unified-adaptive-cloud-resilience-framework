CREATE SCHEMA IF NOT EXISTS uacrf_assessment;
CREATE TABLE uacrf_assessment.analysis_case (
 tenant_id text NOT NULL, environment_id text NOT NULL, id text NOT NULL,
 workload_id text NOT NULL, content jsonb NOT NULL,
 state text NOT NULL CHECK (state IN ('DRAFT', 'SEALED')),
 revision bigint NOT NULL CHECK (revision >= 1), case_digest text,
 created_by text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY (tenant_id, environment_id, id),
 CHECK ((state = 'DRAFT' AND case_digest IS NULL) OR (state = 'SEALED' AND case_digest ~ '^[0-9a-f]{64}$'))
);
CREATE FUNCTION uacrf_assessment.protect_sealed_case() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.state = 'SEALED' THEN RAISE EXCEPTION 'sealed case is immutable' USING ERRCODE = '23514'; END IF;
 IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER protect_sealed_case BEFORE UPDATE OR DELETE ON uacrf_assessment.analysis_case
 FOR EACH ROW EXECUTE FUNCTION uacrf_assessment.protect_sealed_case();
CREATE INDEX case_workload_idx ON uacrf_assessment.analysis_case (tenant_id, environment_id, workload_id, id);
CREATE TABLE uacrf_assessment.evaluation (
 tenant_id text NOT NULL, environment_id text NOT NULL, id text NOT NULL, case_id text NOT NULL,
 revision bigint NOT NULL CHECK (revision >= 1), observation_cutoff text NOT NULL,
 cutoff_epoch_second bigint NOT NULL, cutoff_nano integer NOT NULL CHECK (cutoff_nano BETWEEN 0 AND 999999999),
 received_at timestamptz NOT NULL DEFAULT clock_timestamp(), mode text NOT NULL CHECK (mode IN ('SHADOW','DRY_RUN','LIVE')),
 outcome text NOT NULL CHECK (outcome IN ('CONFIRMED_SUCCESS','CONFIRMED_BREACH','INSUFFICIENT_EVIDENCE')),
 dependency_vector jsonb NOT NULL, result_digest text NOT NULL CHECK (result_digest ~ '^[0-9a-f]{64}$'),
 PRIMARY KEY (tenant_id, environment_id, id), UNIQUE (tenant_id, environment_id, case_id, revision),
 FOREIGN KEY (tenant_id, environment_id, case_id) REFERENCES uacrf_assessment.analysis_case (tenant_id, environment_id, id)
);
CREATE FUNCTION uacrf_assessment.protect_evaluation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'evaluation is immutable' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER protect_evaluation BEFORE UPDATE OR DELETE ON uacrf_assessment.evaluation
 FOR EACH ROW EXECUTE FUNCTION uacrf_assessment.protect_evaluation();
CREATE INDEX evaluation_latest_idx ON uacrf_assessment.evaluation (tenant_id, environment_id, case_id, cutoff_epoch_second DESC, cutoff_nano DESC, revision DESC);
CREATE TABLE uacrf_assessment.recommendation (
 tenant_id text NOT NULL, environment_id text NOT NULL, id text NOT NULL, evaluation_id text NOT NULL,
 case_id text NOT NULL, workload_id text NOT NULL, kind text NOT NULL, effect_bounds jsonb NOT NULL,
 prerequisite_refs jsonb NOT NULL, executable boolean NOT NULL DEFAULT false CHECK (executable = false),
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (tenant_id, environment_id, id),
 UNIQUE (tenant_id, environment_id, evaluation_id),
 FOREIGN KEY (tenant_id, environment_id, evaluation_id) REFERENCES uacrf_assessment.evaluation (tenant_id, environment_id, id)
);
CREATE TABLE uacrf_assessment.outbox_event (
 event_id uuid PRIMARY KEY, tenant_id text NOT NULL, environment_id text NOT NULL,
 aggregate_type text NOT NULL, aggregate_id text NOT NULL, aggregate_sequence bigint NOT NULL,
 event_type text NOT NULL, schema_version int NOT NULL DEFAULT 1, payload jsonb NOT NULL,
 payload_digest char(64) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz
);
