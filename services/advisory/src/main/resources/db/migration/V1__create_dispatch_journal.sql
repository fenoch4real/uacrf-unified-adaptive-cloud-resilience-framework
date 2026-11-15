CREATE SCHEMA IF NOT EXISTS uacrf_advisory;

CREATE TABLE uacrf_advisory.run_fence (
  tenant_id text NOT NULL,
  environment_id text NOT NULL,
  run_id text NOT NULL,
  generation bigint NOT NULL CHECK (generation >= 1),
  max_calls integer NOT NULL CHECK (max_calls BETWEEN 1 AND 100),
  max_tokens integer NOT NULL CHECK (max_tokens BETWEEN 1 AND 10000000),
  max_cost_units integer NOT NULL CHECK (max_cost_units BETWEEN 0 AND 1000000000),
  deadline_epoch_second bigint NOT NULL,
  deadline_nano integer NOT NULL CHECK (deadline_nano BETWEEN 0 AND 999999999),
  state text NOT NULL CHECK (state IN ('DRAFT', 'ACTIVE', 'CANCELLED')),
  revision bigint NOT NULL CHECK (revision IN (1, 2)),
  cancelled_by text,
  cancellation_reason text,
  cancelled_at timestamptz,
  PRIMARY KEY (tenant_id, environment_id, run_id),
  UNIQUE (tenant_id, environment_id, run_id, generation),
  CHECK (
    (state IN ('DRAFT', 'ACTIVE') AND revision = 1 AND cancelled_by IS NULL
      AND cancellation_reason IS NULL AND cancelled_at IS NULL)
    OR (state = 'CANCELLED' AND revision = 2 AND cancelled_by IS NOT NULL
      AND cancellation_reason IS NOT NULL AND length(btrim(cancellation_reason)) BETWEEN 1 AND 2000
      AND cancelled_at IS NOT NULL)
  )
);

CREATE TABLE uacrf_advisory.run_allocation (
  tenant_id text NOT NULL,
  environment_id text NOT NULL,
  run_id text NOT NULL,
  generation bigint NOT NULL,
  role text NOT NULL CHECK (role IN ('ANALYST', 'REVIEWER', 'CRITIC')),
  profile_id text NOT NULL CHECK (profile_id ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$'),
  tokens integer NOT NULL CHECK (tokens BETWEEN 1 AND 10000000),
  cost_units integer NOT NULL CHECK (cost_units BETWEEN 0 AND 1000000000),
  PRIMARY KEY (tenant_id, environment_id, run_id, role),
  UNIQUE (tenant_id, environment_id, run_id, profile_id),
  UNIQUE (tenant_id, environment_id, run_id, generation, role, profile_id),
  FOREIGN KEY (tenant_id, environment_id, run_id, generation)
    REFERENCES uacrf_advisory.run_fence (tenant_id, environment_id, run_id, generation)
);

CREATE FUNCTION uacrf_advisory.protect_run_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE run_state text;
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'run allocations are immutable' USING ERRCODE = '23514';
  END IF;
  SELECT state INTO run_state FROM uacrf_advisory.run_fence
    WHERE tenant_id = NEW.tenant_id AND environment_id = NEW.environment_id
      AND run_id = NEW.run_id AND generation = NEW.generation FOR UPDATE;
  IF run_state IS DISTINCT FROM 'DRAFT' THEN
    RAISE EXCEPTION 'run allocations require a draft run' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER protect_run_allocation BEFORE INSERT OR UPDATE OR DELETE
  ON uacrf_advisory.run_allocation FOR EACH ROW
  EXECUTE FUNCTION uacrf_advisory.protect_run_allocation();

CREATE FUNCTION uacrf_advisory.protect_run_fence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE allocation_count bigint; token_total bigint; cost_total bigint;
BEGIN
  IF TG_OP = 'DELETE'
    OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
    OR NEW.environment_id IS DISTINCT FROM OLD.environment_id
    OR NEW.run_id IS DISTINCT FROM OLD.run_id
    OR NEW.generation IS DISTINCT FROM OLD.generation
    OR NEW.max_calls IS DISTINCT FROM OLD.max_calls
    OR NEW.max_tokens IS DISTINCT FROM OLD.max_tokens
    OR NEW.max_cost_units IS DISTINCT FROM OLD.max_cost_units
    OR NEW.deadline_epoch_second IS DISTINCT FROM OLD.deadline_epoch_second
    OR NEW.deadline_nano IS DISTINCT FROM OLD.deadline_nano THEN
    RAISE EXCEPTION 'run fence history is immutable' USING ERRCODE = '23514';
  END IF;
  IF OLD.state = 'DRAFT' AND NEW.state = 'ACTIVE' THEN
    SELECT count(*), coalesce(sum(tokens), 0), coalesce(sum(cost_units), 0)
      INTO allocation_count, token_total, cost_total
      FROM uacrf_advisory.run_allocation
      WHERE tenant_id = OLD.tenant_id AND environment_id = OLD.environment_id AND run_id = OLD.run_id;
    IF allocation_count < 1 OR allocation_count > 3 OR allocation_count > OLD.max_calls
      OR token_total > OLD.max_tokens OR cost_total > OLD.max_cost_units THEN
      RAISE EXCEPTION 'run allocations exceed budget' USING ERRCODE = '23514';
    END IF;
    IF extract(epoch FROM clock_timestamp()) * 1000000000
      >= OLD.deadline_epoch_second::numeric * 1000000000 + OLD.deadline_nano THEN
      RAISE EXCEPTION 'run deadline has passed' USING ERRCODE = '23514';
    END IF;
  ELSIF OLD.state <> 'ACTIVE' OR NEW.state <> 'CANCELLED' THEN
    RAISE EXCEPTION 'invalid run fence transition' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER protect_run_fence BEFORE UPDATE OR DELETE
  ON uacrf_advisory.run_fence FOR EACH ROW
  EXECUTE FUNCTION uacrf_advisory.protect_run_fence();

CREATE TABLE uacrf_advisory.dispatch_journal (
  tenant_id text NOT NULL,
  environment_id text NOT NULL,
  run_id text NOT NULL,
  assignment_id text NOT NULL,
  attempt_id text NOT NULL,
  generation bigint NOT NULL CHECK (generation >= 1),
  role text NOT NULL,
  profile_id text NOT NULL,
  context_digest text NOT NULL CHECK (context_digest ~ '^[0-9a-f]{64}$'),
  packet_bytes bytea NOT NULL CHECK (octet_length(packet_bytes) BETWEEN 1 AND 1048576),
  packet_bytes_digest text NOT NULL CHECK (packet_bytes_digest ~ '^[0-9a-f]{64}$'),
  state text NOT NULL CHECK (state IN ('DISPATCH_INTENT', 'RESPONSE', 'UNKNOWN')),
  response_text text,
  input_tokens integer,
  output_tokens integer,
  unknown_reason text,
  recording_fence text CHECK (recording_fence IN ('ACTIVE', 'CANCELLED', 'EXPIRED')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz,
  PRIMARY KEY (tenant_id, environment_id, attempt_id),
  UNIQUE (tenant_id, environment_id, run_id, assignment_id),
  UNIQUE (tenant_id, environment_id, run_id, role),
  FOREIGN KEY (tenant_id, environment_id, run_id, generation, role, profile_id)
    REFERENCES uacrf_advisory.run_allocation (tenant_id, environment_id, run_id, generation, role, profile_id),
  CHECK (
    (state = 'DISPATCH_INTENT' AND response_text IS NULL AND input_tokens IS NULL
      AND output_tokens IS NULL AND unknown_reason IS NULL AND recording_fence IS NULL AND completed_at IS NULL)
    OR (state = 'RESPONSE' AND response_text IS NOT NULL AND length(btrim(response_text)) > 0
      AND input_tokens IS NOT NULL AND input_tokens >= 0
      AND output_tokens IS NOT NULL AND output_tokens >= 0
      AND unknown_reason IS NULL AND recording_fence IS NOT NULL AND completed_at IS NOT NULL)
    OR (state = 'UNKNOWN' AND response_text IS NULL AND input_tokens IS NULL
      AND output_tokens IS NULL AND unknown_reason IS NOT NULL
      AND length(btrim(unknown_reason)) > 0 AND recording_fence IS NOT NULL AND completed_at IS NOT NULL)
  )
);

CREATE FUNCTION uacrf_advisory.protect_dispatch_journal() RETURNS trigger LANGUAGE plpgsql AS $$
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
    OR NEW.context_digest IS DISTINCT FROM OLD.context_digest
    OR NEW.packet_bytes IS DISTINCT FROM OLD.packet_bytes
    OR NEW.packet_bytes_digest IS DISTINCT FROM OLD.packet_bytes_digest
    OR NEW.created_at IS DISTINCT FROM OLD.created_at
    OR NEW.state NOT IN ('RESPONSE', 'UNKNOWN') THEN
    RAISE EXCEPTION 'dispatch journal history is immutable' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER protect_dispatch_journal BEFORE UPDATE OR DELETE
  ON uacrf_advisory.dispatch_journal FOR EACH ROW
  EXECUTE FUNCTION uacrf_advisory.protect_dispatch_journal();
