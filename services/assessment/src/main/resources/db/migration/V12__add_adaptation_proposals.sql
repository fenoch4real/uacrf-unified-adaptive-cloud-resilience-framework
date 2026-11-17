CREATE TABLE uacrf_assessment.adaptation_proposal (
 tenant_id text NOT NULL,
 environment_id text NOT NULL,
 id text NOT NULL CHECK (id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
 learning_record_id text NOT NULL,
 learning_record_digest char(64) NOT NULL CHECK (learning_record_digest ~ '^[0-9a-f]{64}$'),
 learning_review_digest char(64) NOT NULL CHECK (learning_review_digest ~ '^[0-9a-f]{64}$'),
 learning_checked_at timestamptz NOT NULL,
 content jsonb NOT NULL CHECK (jsonb_typeof(content) = 'object'),
 state text NOT NULL CHECK (state = 'DRAFT_UNVERIFIED'),
 created_by text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY (tenant_id, environment_id, id),
 FOREIGN KEY (tenant_id, environment_id, learning_record_id)
   REFERENCES uacrf_assessment.learning_review (tenant_id, environment_id, learning_record_id)
);

CREATE INDEX adaptation_proposal_learning_page_idx
 ON uacrf_assessment.adaptation_proposal (tenant_id, environment_id, learning_record_id, id);

CREATE FUNCTION uacrf_assessment.protect_adaptation_proposal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'adaptation proposals are immutable' USING ERRCODE = '23514';
END $$;

CREATE TRIGGER protect_adaptation_proposal BEFORE UPDATE OR DELETE ON uacrf_assessment.adaptation_proposal
 FOR EACH ROW EXECUTE FUNCTION uacrf_assessment.protect_adaptation_proposal();
