ALTER TABLE uacrf_advisory.run_fence
  ADD COLUMN dispatch_plan_required boolean NOT NULL DEFAULT false;

CREATE TABLE uacrf_advisory.run_dispatch_plan (
  tenant_id text NOT NULL,
  environment_id text NOT NULL,
  run_id text NOT NULL,
  generation bigint NOT NULL CHECK (generation >= 1),
  assignment_id text NOT NULL,
  attempt_id text NOT NULL,
  role text NOT NULL CHECK (role IN ('ANALYST', 'REVIEWER', 'CRITIC')),
  profile_id text NOT NULL CHECK (profile_id ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$'),
  model_ref text NOT NULL CHECK (length(btrim(model_ref)) > 0),
  max_output_tokens integer NOT NULL CHECK (max_output_tokens > 0),
  timeout_seconds bigint NOT NULL CHECK (timeout_seconds >= 0),
  timeout_nanos integer NOT NULL CHECK (timeout_nanos BETWEEN 0 AND 999999999),
  context_digest text NOT NULL CHECK (context_digest ~ '^[0-9a-f]{64}$'),
  packet_bytes bytea NOT NULL CHECK (octet_length(packet_bytes) BETWEEN 1 AND 1048576),
  packet_bytes_digest text NOT NULL CHECK (packet_bytes_digest ~ '^[0-9a-f]{64}$'),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, environment_id, run_id, role),
  UNIQUE (tenant_id, environment_id, attempt_id),
  UNIQUE (tenant_id, environment_id, run_id, assignment_id),
  FOREIGN KEY (tenant_id, environment_id, run_id, generation, role, profile_id)
    REFERENCES uacrf_advisory.run_allocation (tenant_id, environment_id, run_id, generation, role, profile_id),
  CHECK (timeout_seconds > 0 OR timeout_nanos > 0),
  CHECK (context_digest = packet_bytes_digest)
);

CREATE FUNCTION uacrf_advisory.protect_run_dispatch_plan() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE run_state text; plan_required boolean; allocated_tokens integer;
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'admitted dispatch plans are immutable' USING ERRCODE = '23514';
  END IF;
  SELECT fence.state, fence.dispatch_plan_required, allocation.tokens
    INTO run_state, plan_required, allocated_tokens
    FROM uacrf_advisory.run_fence fence
    JOIN uacrf_advisory.run_allocation allocation
      ON allocation.tenant_id = fence.tenant_id AND allocation.environment_id = fence.environment_id
      AND allocation.run_id = fence.run_id AND allocation.generation = fence.generation
      AND allocation.role = NEW.role AND allocation.profile_id = NEW.profile_id
    WHERE fence.tenant_id = NEW.tenant_id AND fence.environment_id = NEW.environment_id
      AND fence.run_id = NEW.run_id AND fence.generation = NEW.generation
    FOR UPDATE OF fence;
  IF run_state IS DISTINCT FROM 'DRAFT' OR plan_required IS DISTINCT FROM true
    OR allocated_tokens IS NULL OR NEW.max_output_tokens > allocated_tokens THEN
    RAISE EXCEPTION 'dispatch plans require a matching draft allocation' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER protect_run_dispatch_plan
  BEFORE INSERT OR UPDATE OR DELETE ON uacrf_advisory.run_dispatch_plan FOR EACH ROW
  EXECUTE FUNCTION uacrf_advisory.protect_run_dispatch_plan();

CREATE OR REPLACE FUNCTION uacrf_advisory.protect_run_fence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE allocation_count bigint; token_total bigint; cost_total bigint; plan_count bigint;
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
    OR NEW.deadline_nano IS DISTINCT FROM OLD.deadline_nano
    OR NEW.dispatch_plan_required IS DISTINCT FROM OLD.dispatch_plan_required THEN
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
    IF OLD.dispatch_plan_required THEN
      SELECT count(*) INTO plan_count
        FROM uacrf_advisory.run_dispatch_plan
        WHERE tenant_id = OLD.tenant_id AND environment_id = OLD.environment_id
          AND run_id = OLD.run_id AND generation = OLD.generation;
      IF plan_count <> allocation_count THEN
        RAISE EXCEPTION 'run dispatch plan is incomplete' USING ERRCODE = '23514';
      END IF;
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
