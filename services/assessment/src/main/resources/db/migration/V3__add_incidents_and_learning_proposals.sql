CREATE TABLE uacrf_assessment.incident (
 tenant_id text NOT NULL,
 environment_id text NOT NULL,
 id text NOT NULL CHECK (id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
 workload_id text NOT NULL CHECK (length(workload_id) BETWEEN 1 AND 200),
 source_case_id text NOT NULL,
 observed_from timestamptz NOT NULL,
 description text NOT NULL CHECK (length(description) BETWEEN 1 AND 4000),
 evidence_refs jsonb NOT NULL CHECK (
   jsonb_typeof(evidence_refs) = 'array'
   AND jsonb_array_length(evidence_refs) BETWEEN 1 AND 100
 ),
 reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 2000),
 revision bigint NOT NULL DEFAULT 1 CHECK (revision = 1),
 created_by text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY (tenant_id, environment_id, id),
 FOREIGN KEY (tenant_id, environment_id, source_case_id)
   REFERENCES uacrf_assessment.analysis_case (tenant_id, environment_id, id)
);

CREATE INDEX incident_case_idx
 ON uacrf_assessment.incident (tenant_id, environment_id, source_case_id, created_at);

CREATE FUNCTION uacrf_assessment.protect_incident() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'incidents are immutable' USING ERRCODE = '23514';
END $$;

CREATE TRIGGER protect_incident BEFORE UPDATE OR DELETE ON uacrf_assessment.incident
 FOR EACH ROW EXECUTE FUNCTION uacrf_assessment.protect_incident();

CREATE TABLE uacrf_assessment.learning_record (
 tenant_id text NOT NULL,
 environment_id text NOT NULL,
 id text NOT NULL CHECK (id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
 incident_id text NOT NULL,
 content jsonb NOT NULL CHECK (
   jsonb_typeof(content) = 'object'
   AND jsonb_typeof(content->'hypotheses') = 'array'
   AND jsonb_typeof(content->'action_items') = 'array'
   AND jsonb_array_length(content->'hypotheses') BETWEEN 1 AND 100
   AND jsonb_array_length(content->'action_items') BETWEEN 1 AND 100
 ),
 evidence_refs jsonb NOT NULL CHECK (
   jsonb_typeof(evidence_refs) = 'array'
   AND jsonb_array_length(evidence_refs) BETWEEN 1 AND 100
 ),
 content_digest char(64) NOT NULL CHECK (content_digest ~ '^[0-9a-f]{64}$'),
 state text NOT NULL CHECK (state = 'PROPOSED'),
 revision bigint NOT NULL CHECK (revision = 1),
 proposed_by text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY (tenant_id, environment_id, id),
 FOREIGN KEY (tenant_id, environment_id, incident_id)
   REFERENCES uacrf_assessment.incident (tenant_id, environment_id, id)
);

CREATE INDEX learning_record_incident_idx
 ON uacrf_assessment.learning_record (tenant_id, environment_id, incident_id, created_at);

CREATE FUNCTION uacrf_assessment.protect_learning_record() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'learning records are immutable' USING ERRCODE = '23514';
END $$;

CREATE TRIGGER protect_learning_record BEFORE UPDATE OR DELETE ON uacrf_assessment.learning_record
 FOR EACH ROW EXECUTE FUNCTION uacrf_assessment.protect_learning_record();
