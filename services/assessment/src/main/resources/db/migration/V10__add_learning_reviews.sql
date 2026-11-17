CREATE TABLE uacrf_assessment.learning_review (
 tenant_id text NOT NULL,
 environment_id text NOT NULL,
 learning_record_id text NOT NULL,
 expected_revision bigint NOT NULL CHECK (expected_revision = 1),
 decision text NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
 reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 2000),
 reviewed_by text NOT NULL,
 reviewed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY (tenant_id, environment_id, learning_record_id),
 FOREIGN KEY (tenant_id, environment_id, learning_record_id)
   REFERENCES uacrf_assessment.learning_record (tenant_id, environment_id, id)
);

CREATE FUNCTION uacrf_assessment.check_learning_reviewer() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS (
   SELECT 1 FROM uacrf_assessment.learning_record l
   WHERE l.tenant_id = NEW.tenant_id AND l.environment_id = NEW.environment_id
     AND l.id = NEW.learning_record_id AND l.revision = NEW.expected_revision
     AND l.proposed_by <> NEW.reviewed_by
 ) THEN
   RAISE EXCEPTION 'independent learning reviewer and matching revision required' USING ERRCODE = '23514';
 END IF;
 RETURN NEW;
END $$;

CREATE TRIGGER check_learning_reviewer BEFORE INSERT ON uacrf_assessment.learning_review
 FOR EACH ROW EXECUTE FUNCTION uacrf_assessment.check_learning_reviewer();

CREATE FUNCTION uacrf_assessment.protect_learning_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'learning reviews are immutable' USING ERRCODE = '23514';
END $$;

CREATE TRIGGER protect_learning_review BEFORE UPDATE OR DELETE ON uacrf_assessment.learning_review
 FOR EACH ROW EXECUTE FUNCTION uacrf_assessment.protect_learning_review();
