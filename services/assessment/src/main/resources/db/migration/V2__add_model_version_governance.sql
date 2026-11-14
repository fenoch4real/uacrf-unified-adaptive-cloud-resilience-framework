CREATE TABLE uacrf_assessment.model_version (
 tenant_id text NOT NULL, environment_id text NOT NULL, id text NOT NULL,
 model_id text NOT NULL, version text NOT NULL, artifact_ref text NOT NULL,
 digest text NOT NULL CHECK (digest ~ '^[0-9a-f]{64}$'),
 model_card_ref text NOT NULL, evaluation_pack_ref text NOT NULL, reason text NOT NULL,
 state text NOT NULL CHECK (state IN ('CANDIDATE','APPROVED','REJECTED','ACTIVE','SUPERSEDED')),
 revision bigint NOT NULL CHECK (revision >= 1),
 proposed_by text NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 reviewed_by text, decision text CHECK (decision IN ('APPROVE','REJECT')),
 evaluation_result_ids jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(evaluation_result_ids) = 'array'),
 review_reason text, reviewed_at timestamptz,
 promoted_by text, target_mode text CHECK (target_mode IN ('SHADOW','ADVISORY')),
 promotion_reason text, promoted_at timestamptz,
 PRIMARY KEY (tenant_id, environment_id, id),
 UNIQUE (tenant_id, environment_id, model_id, version),
 CHECK (
   (state = 'CANDIDATE' AND revision = 1 AND reviewed_by IS NULL AND decision IS NULL
     AND review_reason IS NULL AND reviewed_at IS NULL AND promoted_by IS NULL AND target_mode IS NULL
     AND promotion_reason IS NULL AND promoted_at IS NULL)
   OR (state IN ('APPROVED','REJECTED') AND revision = 2 AND reviewed_by IS NOT NULL
     AND decision = CASE state WHEN 'APPROVED' THEN 'APPROVE' ELSE 'REJECT' END
     AND review_reason IS NOT NULL AND reviewed_at IS NOT NULL AND promoted_by IS NULL
     AND target_mode IS NULL AND promotion_reason IS NULL AND promoted_at IS NULL)
   OR (state IN ('ACTIVE','SUPERSEDED') AND revision >= 3 AND reviewed_by IS NOT NULL
     AND decision = 'APPROVE' AND review_reason IS NOT NULL AND reviewed_at IS NOT NULL
     AND promoted_by IS NOT NULL AND target_mode IS NOT NULL AND promotion_reason IS NOT NULL
     AND promoted_at IS NOT NULL)
 )
);

CREATE UNIQUE INDEX one_active_model_version_per_mode
 ON uacrf_assessment.model_version (tenant_id, environment_id, model_id, target_mode)
 WHERE state = 'ACTIVE';

CREATE INDEX model_version_list_idx
 ON uacrf_assessment.model_version (tenant_id, environment_id, model_id, version, id);

CREATE FUNCTION uacrf_assessment.protect_model_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP = 'DELETE' THEN
   RAISE EXCEPTION 'model versions are immutable' USING ERRCODE = '23514';
 END IF;
 IF ROW(NEW.tenant_id, NEW.environment_id, NEW.id, NEW.model_id, NEW.version, NEW.artifact_ref,
        NEW.digest, NEW.model_card_ref, NEW.evaluation_pack_ref, NEW.reason, NEW.proposed_by, NEW.created_at)
    IS DISTINCT FROM
    ROW(OLD.tenant_id, OLD.environment_id, OLD.id, OLD.model_id, OLD.version, OLD.artifact_ref,
        OLD.digest, OLD.model_card_ref, OLD.evaluation_pack_ref, OLD.reason, OLD.proposed_by, OLD.created_at) THEN
   RAISE EXCEPTION 'model version metadata is immutable' USING ERRCODE = '23514';
 END IF;
 IF NEW.revision <> OLD.revision + 1 THEN
   RAISE EXCEPTION 'model version revision must increase by one' USING ERRCODE = '23514';
 END IF;
 IF NOT ((OLD.state = 'CANDIDATE' AND NEW.state IN ('APPROVED','REJECTED'))
      OR (OLD.state = 'APPROVED' AND NEW.state = 'ACTIVE')
      OR (OLD.state = 'ACTIVE' AND NEW.state = 'SUPERSEDED')) THEN
   RAISE EXCEPTION 'invalid model version state transition' USING ERRCODE = '23514';
 END IF;
 IF OLD.state = 'CANDIDATE' AND NEW.state IN ('APPROVED','REJECTED')
    AND (NEW.reviewed_by IS NULL OR NEW.decision IS DISTINCT FROM
      CASE NEW.state WHEN 'APPROVED' THEN 'APPROVE' ELSE 'REJECT' END
      OR NEW.review_reason IS NULL OR NEW.reviewed_at IS NULL
      OR NEW.evaluation_result_ids IS NULL) THEN
   RAISE EXCEPTION 'review decision metadata is required' USING ERRCODE = '23514';
 END IF;
 IF OLD.state = 'APPROVED' AND NEW.state = 'ACTIVE'
    AND (NEW.promoted_by IS NULL OR NEW.target_mode IS NULL
      OR NEW.promotion_reason IS NULL OR NEW.promoted_at IS NULL) THEN
   RAISE EXCEPTION 'promotion metadata is required' USING ERRCODE = '23514';
 END IF;
 RETURN NEW;
END $$;

CREATE TRIGGER protect_model_version
 BEFORE UPDATE OR DELETE ON uacrf_assessment.model_version
 FOR EACH ROW EXECUTE FUNCTION uacrf_assessment.protect_model_version();
