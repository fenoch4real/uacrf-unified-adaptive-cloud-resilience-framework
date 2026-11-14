-- Version 1.0. Optional assurance extension; baseline migrations remain unchanged.
BEGIN;
CREATE TABLE uacrf_app.recovery_assessment (
 tenant_id text NOT NULL, environment_id text NOT NULL, id text NOT NULL,
 journey_id text NOT NULL, baseline_graph_revision_id text NOT NULL,
 primary_id text NOT NULL,recovery_id text NOT NULL,scenario text NOT NULL,
 created_by text NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,environment_id,id),
 FOREIGN KEY(tenant_id,environment_id,journey_id) REFERENCES uacrf.journey(tenant_id,environment_id,id),
 FOREIGN KEY(tenant_id,environment_id,baseline_graph_revision_id) REFERENCES uacrf.graph_revision(tenant_id,environment_id,id),
 CHECK(primary_id<>recovery_id));
CREATE TABLE uacrf_app.recovery_review (
 tenant_id text NOT NULL,environment_id text NOT NULL,assessment_id text NOT NULL,
 revision bigint NOT NULL CHECK(revision>0),graph_revision_id text NOT NULL,
 actor_id text NOT NULL,idempotency_key text NOT NULL,request_digest text NOT NULL,
 result jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,environment_id,assessment_id,revision),
 UNIQUE(tenant_id,environment_id,actor_id,idempotency_key),
 FOREIGN KEY(tenant_id,environment_id,assessment_id) REFERENCES uacrf_app.recovery_assessment(tenant_id,environment_id,id),
 FOREIGN KEY(tenant_id,environment_id,graph_revision_id) REFERENCES uacrf.graph_revision(tenant_id,environment_id,id));
ALTER TABLE uacrf_app.recovery_assessment OWNER TO uacrf_runtime_owner;
ALTER TABLE uacrf_app.recovery_review OWNER TO uacrf_runtime_owner;
CREATE FUNCTION uacrf_app.assurance_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Assurance history is append-only';END $$;
CREATE TRIGGER assurance_immutable BEFORE UPDATE OR DELETE ON uacrf_app.recovery_assessment FOR EACH ROW EXECUTE FUNCTION uacrf_app.assurance_immutable();
CREATE TRIGGER assurance_immutable BEFORE UPDATE OR DELETE ON uacrf_app.recovery_review FOR EACH ROW EXECUTE FUNCTION uacrf_app.assurance_immutable();
-- graph_context establishes verified identity, transaction lock, and current all-workload authorization.
CREATE FUNCTION uacrf_app.assurance_context(t text,e text,iss text,sub text,iat bigint,aid text,jid text,rid text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,uacrf,uacrf_app AS $$
DECLARE a uacrf_app.recovery_assessment;ctx jsonb; j uacrf.journey;v jsonb;BEGIN
 IF aid IS NOT NULL THEN
  SELECT * INTO a FROM uacrf_app.recovery_assessment WHERE tenant_id=t AND environment_id=e AND id=aid;
  IF a.id IS NULL THEN PERFORM uacrf_app.deny(404,'Assessment unavailable');END IF;
  jid:=a.journey_id;rid:=a.baseline_graph_revision_id;
 END IF;
 ctx:=uacrf_app.graph_context(t,e,iss,sub,iat,rid);
 SELECT * INTO j FROM uacrf.journey WHERE tenant_id=t AND environment_id=e AND id=jid;
 IF j.id IS NULL OR NOT uacrf.catalog_allowed('catalog.journey.read',NULL) THEN PERFORM uacrf_app.deny(404,'Journey unavailable');END IF;
 -- The journey must actually contain the graph root; a submitted journey label is insufficient.
 IF NOT EXISTS(SELECT 1 FROM uacrf.journey_capability c WHERE c.tenant_id=t AND c.environment_id=e AND c.journey_id=jid AND c.service_id=ctx->>'service_id' AND c.link_lifecycle='ACTIVE') THEN PERFORM uacrf_app.deny(409,'Journey no longer includes graph workload');END IF;
 IF aid IS NULL THEN RETURN jsonb_build_object('context',ctx);END IF;
 SELECT result INTO v FROM uacrf_app.recovery_review WHERE tenant_id=t AND environment_id=e AND assessment_id=aid ORDER BY revision DESC LIMIT 1;
 RETURN jsonb_build_object('context',ctx,'assessment',to_jsonb(a),'result',v,'reviews',coalesce((SELECT jsonb_agg(x.result ORDER BY x.revision) FROM (SELECT result,revision FROM uacrf_app.recovery_review WHERE tenant_id=t AND environment_id=e AND assessment_id=aid ORDER BY revision DESC LIMIT 101) x),'[]'::jsonb),'pending',coalesce((SELECT jsonb_agg(x.id) FROM (SELECT g.id FROM uacrf.graph_revision g WHERE g.tenant_id=t AND g.environment_id=e AND g.service_id=ctx->>'service_id' AND g.status='SEALED' AND g.created_at>=a.created_at AND NOT EXISTS(SELECT 1 FROM uacrf_app.recovery_review r WHERE r.tenant_id=t AND r.environment_id=e AND r.assessment_id=aid AND r.graph_revision_id=g.id) ORDER BY g.created_at,g.id LIMIT 101)x),'[]'::jsonb));
END $$;
CREATE FUNCTION uacrf_app.assurance_save(t text,e text,iss text,sub text,iat bigint,aid text,jid text,rid text,expected bigint,k text,dg text,v jsonb) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,uacrf,uacrf_app AS $$
DECLARE ctx jsonb; old uacrf_app.recovery_review;actor text;rev bigint;root_service text;BEGIN
 ctx:=uacrf_app.assurance_context(t,e,iss,sub,iat,CASE WHEN expected=0 THEN NULL ELSE aid END,jid,rid);
 actor:=current_setting('app.actor_id');root_service:=ctx->'context'->>'service_id';
 PERFORM uacrf_app.cap('catalog.journey.write',NULL);
 SELECT * INTO old FROM uacrf_app.recovery_review WHERE tenant_id=t AND environment_id=e AND actor_id=actor AND idempotency_key=k;
 IF FOUND THEN
  IF old.request_digest<>dg THEN PERFORM uacrf_app.deny(409,'Idempotency conflict');END IF;
  RETURN old.result;
 END IF;
 ctx:=uacrf_app.graph_context(t,e,iss,sub,iat,rid);
 IF ctx->>'service_id' IS DISTINCT FROM root_service THEN PERFORM uacrf_app.deny(409,'Graph root changed');END IF;
 IF NOT (ctx->>'eligible')::boolean OR NOT (ctx->>'projected')::boolean THEN PERFORM uacrf_app.deny(409,'Current graph evidence unavailable');END IF;
 IF expected=0 THEN
  INSERT INTO uacrf_app.recovery_assessment(tenant_id,environment_id,id,journey_id,baseline_graph_revision_id,primary_id,recovery_id,scenario,created_by)
  VALUES(t,e,aid,jid,rid,v->>'primary_id',v->>'recovery_id',v->>'scenario',actor);rev:=1;
 ELSE
  SELECT max(revision) INTO rev FROM uacrf_app.recovery_review WHERE tenant_id=t AND environment_id=e AND assessment_id=aid;
  IF rev IS DISTINCT FROM expected THEN PERFORM uacrf_app.deny(409,'Stale assessment revision');END IF;rev:=rev+1;
 END IF;
 v:=v||jsonb_build_object('id',aid,'revision',rev,'observed_at',clock_timestamp()::text);
 INSERT INTO uacrf_app.recovery_review(tenant_id,environment_id,assessment_id,revision,graph_revision_id,actor_id,idempotency_key,request_digest,result) VALUES(t,e,aid,rev,rid,actor,k,dg,v);
 INSERT INTO uacrf.audit_event(tenant_id,environment_id,id,actor_id,action,target_id,digest,payload) VALUES(t,e,gen_random_uuid()::text,actor,'recovery.assurance.review',aid,dg,jsonb_build_object('revision',rev,'graph_revision_id',rid,'state',v->>'state'));
 INSERT INTO uacrf.outbox(tenant_id,environment_id,id,topic,aggregate_id,payload) VALUES(t,e,gen_random_uuid()::text,'recovery.assurance.reviewed',aid,jsonb_build_object('revision',rev,'state',v->>'state'));
 RETURN v;
END $$;
ALTER FUNCTION uacrf_app.assurance_immutable() OWNER TO uacrf_runtime_owner;
ALTER FUNCTION uacrf_app.assurance_context(text,text,text,text,bigint,text,text,text) OWNER TO uacrf_runtime_owner;
ALTER FUNCTION uacrf_app.assurance_save(text,text,text,text,bigint,text,text,text,bigint,text,text,jsonb) OWNER TO uacrf_runtime_owner;
REVOKE ALL ON FUNCTION uacrf_app.assurance_context(text,text,text,text,bigint,text,text,text),uacrf_app.assurance_save(text,text,text,text,bigint,text,text,text,bigint,text,text,jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION uacrf_app.assurance_context(text,text,text,text,bigint,text,text,text),uacrf_app.assurance_save(text,text,text,text,bigint,text,text,text,bigint,text,text,jsonb) TO uacrf_api;
COMMIT;
