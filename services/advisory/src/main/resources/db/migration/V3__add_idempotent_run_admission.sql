CREATE TABLE uacrf_advisory.run_idempotency (
  tenant_id text NOT NULL,
  environment_id text NOT NULL,
  principal_id text NOT NULL CHECK (principal_id ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$'),
  operation text NOT NULL CHECK (operation ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$'),
  idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200
    AND idempotency_key ~ '[^[:space:]]'),
  request_digest text NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
  run_id text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, environment_id, principal_id, operation, idempotency_key),
  UNIQUE (tenant_id, environment_id, run_id),
  FOREIGN KEY (tenant_id, environment_id, run_id)
    REFERENCES uacrf_advisory.run_fence (tenant_id, environment_id, run_id)
);

CREATE FUNCTION uacrf_advisory.protect_run_idempotency() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'run idempotency records are immutable' USING ERRCODE = '23514';
END $$;

CREATE TRIGGER protect_run_idempotency
  BEFORE UPDATE OR DELETE ON uacrf_advisory.run_idempotency FOR EACH ROW
  EXECUTE FUNCTION uacrf_advisory.protect_run_idempotency();

CREATE TABLE uacrf_advisory.outbox_event (
  event_id text PRIMARY KEY CHECK (event_id ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$'),
  tenant_id text NOT NULL,
  environment_id text NOT NULL,
  run_id text NOT NULL,
  generation bigint NOT NULL CHECK (generation >= 1),
  event_type text NOT NULL CHECK (event_type = 'uacrf.s09.run.admitted.v1'),
  payload jsonb NOT NULL CHECK (
    jsonb_typeof(payload) = 'object'
    AND payload ? 'run_id'
    AND payload ? 'generation'
    AND payload ? 'request_digest'
    AND payload->>'run_id' = run_id
    AND payload->>'generation' = generation::text
    AND payload->>'request_digest' ~ '^[0-9a-f]{64}$'
  ),
  state text NOT NULL CHECK (state IN ('PENDING', 'PUBLISHED')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  published_at timestamptz,
  UNIQUE (tenant_id, environment_id, run_id, event_type),
  FOREIGN KEY (tenant_id, environment_id, run_id, generation)
    REFERENCES uacrf_advisory.run_fence (tenant_id, environment_id, run_id, generation),
  CHECK (
    (state = 'PENDING' AND published_at IS NULL)
    OR (state = 'PUBLISHED' AND published_at IS NOT NULL AND published_at >= created_at)
  )
);

CREATE INDEX outbox_event_pending_order
  ON uacrf_advisory.outbox_event (created_at, event_id)
  WHERE state = 'PENDING';

CREATE FUNCTION uacrf_advisory.protect_outbox_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE'
    OR NEW.event_id IS DISTINCT FROM OLD.event_id
    OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
    OR NEW.environment_id IS DISTINCT FROM OLD.environment_id
    OR NEW.run_id IS DISTINCT FROM OLD.run_id
    OR NEW.generation IS DISTINCT FROM OLD.generation
    OR NEW.event_type IS DISTINCT FROM OLD.event_type
    OR NEW.payload IS DISTINCT FROM OLD.payload
    OR NEW.created_at IS DISTINCT FROM OLD.created_at
    OR OLD.state <> 'PENDING'
    OR NEW.state <> 'PUBLISHED'
    OR NEW.published_at IS NULL THEN
    RAISE EXCEPTION 'outbox event history is immutable' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER protect_outbox_event
  BEFORE UPDATE OR DELETE ON uacrf_advisory.outbox_event FOR EACH ROW
  EXECUTE FUNCTION uacrf_advisory.protect_outbox_event();
