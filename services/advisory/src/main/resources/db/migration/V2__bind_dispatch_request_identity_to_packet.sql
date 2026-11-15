ALTER TABLE uacrf_advisory.dispatch_journal
  ADD COLUMN model_ref text,
  ADD COLUMN max_output_tokens integer,
  ADD COLUMN timeout_seconds bigint,
  ADD COLUMN timeout_nanos integer;

ALTER TABLE uacrf_advisory.dispatch_journal
  ADD CONSTRAINT dispatch_request_identity_valid
  CHECK (
    (model_ref IS NULL AND max_output_tokens IS NULL AND timeout_seconds IS NULL AND timeout_nanos IS NULL)
    OR (model_ref IS NOT NULL AND max_output_tokens IS NOT NULL
      AND timeout_seconds IS NOT NULL AND timeout_nanos IS NOT NULL
      AND length(btrim(model_ref)) > 0
      AND max_output_tokens > 0
      AND timeout_seconds >= 0 AND timeout_nanos BETWEEN 0 AND 999999999
      AND (timeout_seconds > 0 OR timeout_nanos > 0)
      AND context_digest = packet_bytes_digest)
  ) NOT VALID;

CREATE OR REPLACE FUNCTION uacrf_advisory.protect_dispatch_journal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' OR OLD.state <> 'DISPATCH_INTENT'
    OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
    OR NEW.environment_id IS DISTINCT FROM OLD.environment_id
    OR NEW.run_id IS DISTINCT FROM OLD.run_id
    OR NEW.assignment_id IS DISTINCT FROM OLD.assignment_id
    OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
    OR NEW.generation IS DISTINCT FROM OLD.generation
    OR NEW.role IS DISTINCT FROM OLD.role
    OR NEW.profile_id IS DISTINCT FROM OLD.profile_id
    OR NEW.model_ref IS DISTINCT FROM OLD.model_ref
    OR NEW.max_output_tokens IS DISTINCT FROM OLD.max_output_tokens
    OR NEW.timeout_seconds IS DISTINCT FROM OLD.timeout_seconds
    OR NEW.timeout_nanos IS DISTINCT FROM OLD.timeout_nanos
    OR NEW.context_digest IS DISTINCT FROM OLD.context_digest
    OR NEW.packet_bytes IS DISTINCT FROM OLD.packet_bytes
    OR NEW.packet_bytes_digest IS DISTINCT FROM OLD.packet_bytes_digest
    OR NEW.created_at IS DISTINCT FROM OLD.created_at
    OR NEW.state NOT IN ('RESPONSE', 'UNKNOWN') THEN
    RAISE EXCEPTION 'dispatch journal history is immutable' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE FUNCTION uacrf_advisory.require_dispatch_request_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.model_ref IS NULL OR NEW.max_output_tokens IS NULL
    OR NEW.timeout_seconds IS NULL OR NEW.timeout_nanos IS NULL THEN
    RAISE EXCEPTION 'new dispatch intents require a complete gateway request' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER require_dispatch_request_identity
  BEFORE INSERT ON uacrf_advisory.dispatch_journal FOR EACH ROW
  EXECUTE FUNCTION uacrf_advisory.require_dispatch_request_identity();
