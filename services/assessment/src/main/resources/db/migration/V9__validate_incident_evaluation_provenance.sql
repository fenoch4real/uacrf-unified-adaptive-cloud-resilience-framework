CREATE FUNCTION uacrf_assessment.valid_incident_evaluations(
 p_tenant text, p_environment text, p_case text, p_evaluations jsonb
) RETURNS boolean LANGUAGE plpgsql STABLE AS $$
BEGIN
 IF p_evaluations IS NULL THEN RETURN true; END IF;
 IF jsonb_typeof(p_evaluations) IS DISTINCT FROM 'array' THEN RETURN false; END IF;
 IF jsonb_array_length(p_evaluations) NOT BETWEEN 1 AND 100 THEN RETURN false; END IF;
 IF EXISTS (
   SELECT 1 FROM jsonb_array_elements(p_evaluations) AS item(value)
   WHERE jsonb_typeof(item.value) IS DISTINCT FROM 'string'
      OR NOT EXISTS (
        SELECT 1 FROM uacrf_assessment.evaluation e
        WHERE e.tenant_id = p_tenant AND e.environment_id = p_environment
          AND e.case_id = p_case AND e.id = item.value #>> '{}'
      )
 ) OR (
   SELECT count(*) <> count(DISTINCT item.value)
   FROM jsonb_array_elements(p_evaluations) AS item(value)
 ) THEN RETURN false; END IF;
 RETURN true;
END $$;

LOCK TABLE uacrf_assessment.incident IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
 IF EXISTS (
   SELECT 1 FROM uacrf_assessment.incident i
   WHERE NOT uacrf_assessment.valid_incident_evaluations(
     i.tenant_id, i.environment_id, i.source_case_id, i.source_evaluation_ids)
 ) THEN
   RAISE EXCEPTION 'existing incident evaluation provenance is invalid' USING ERRCODE = '23514';
 END IF;
END $$;

CREATE FUNCTION uacrf_assessment.check_incident_evaluations() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT uacrf_assessment.valid_incident_evaluations(
   NEW.tenant_id, NEW.environment_id, NEW.source_case_id, NEW.source_evaluation_ids
 ) THEN
   RAISE EXCEPTION 'invalid incident evaluation provenance' USING ERRCODE = '23514';
 END IF;
 RETURN NEW;
END $$;

CREATE TRIGGER check_incident_evaluations BEFORE INSERT ON uacrf_assessment.incident
 FOR EACH ROW EXECUTE FUNCTION uacrf_assessment.check_incident_evaluations();
