ALTER TABLE uacrf_advisory.run_fence
  ALTER COLUMN dispatch_plan_required SET DEFAULT true;

CREATE FUNCTION uacrf_advisory.require_dispatch_plan_for_new_run() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.dispatch_plan_required IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'new runs require an admitted dispatch plan' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER require_dispatch_plan_for_new_run
  BEFORE INSERT ON uacrf_advisory.run_fence FOR EACH ROW
  EXECUTE FUNCTION uacrf_advisory.require_dispatch_plan_for_new_run();
