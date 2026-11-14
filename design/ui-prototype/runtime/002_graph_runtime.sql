-- Version 1.0 runtime revision 3. Opt-in dual-store boundary; baseline migrations unchanged.
BEGIN;
CREATE ROLE uacrf_graph_worker NOLOGIN NOSUPERUSER NOBYPASSRLS;
GRANT USAGE ON SCHEMA uacrf_app TO uacrf_graph_worker;
CREATE TABLE uacrf_app.graph_worker_binding(login_name name PRIMARY KEY,tenant_id text NOT NULL,environment_id text NOT NULL);
CREATE TABLE uacrf_app.graph_projection(
 tenant_id text NOT NULL,environment_id text NOT NULL,revision_id text NOT NULL,digest text NOT NULL,
 state text NOT NULL CHECK(state IN ('PENDING','LEASED','PROJECTED')),attempts integer NOT NULL DEFAULT 0,
 lease_id uuid,lease_until timestamptz,next_attempt timestamptz NOT NULL DEFAULT clock_timestamp(),
 projected_at timestamptz,last_error text,PRIMARY KEY(tenant_id,environment_id,revision_id),
 FOREIGN KEY(tenant_id,environment_id,revision_id) REFERENCES uacrf.graph_revision(tenant_id,environment_id,id));
CREATE TABLE uacrf_app.graph_projection_event(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id text NOT NULL,environment_id text NOT NULL,revision_id text NOT NULL,
 worker_login name NOT NULL,lease_id uuid,operation text NOT NULL CHECK(operation IN ('LEASED','PROJECTED','RETRY','REQUEUED')),
 digest text NOT NULL,occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(tenant_id,environment_id,revision_id) REFERENCES uacrf.graph_revision(tenant_id,environment_id,id));
ALTER TABLE uacrf_app.graph_projection_event OWNER TO uacrf_runtime_owner;
CREATE TRIGGER graph_projection_event_immutable BEFORE UPDATE OR DELETE ON uacrf_app.graph_projection_event FOR EACH ROW EXECUTE FUNCTION uacrf.immutable();
ALTER TABLE uacrf_app.graph_worker_binding OWNER TO uacrf_runtime_owner;
ALTER TABLE uacrf_app.graph_projection OWNER TO uacrf_runtime_owner;
REVOKE ALL ON ALL TABLES IN SCHEMA uacrf_app FROM PUBLIC,uacrf_api,uacrf_graph_worker;
GRANT SELECT,UPDATE ON uacrf.graph_revision TO uacrf_runtime_owner;
GRANT SELECT ON uacrf.graph_node,uacrf.graph_edge,uacrf.evidence_qualification,uacrf.evidence_event,uacrf.graph_endpoint_role TO uacrf_runtime_owner;
DO $$ DECLARE n text; BEGIN
 FOREACH n IN ARRAY ARRAY['graph_revision','graph_node','graph_edge','evidence_qualification','evidence_event'] LOOP
  EXECUTE format('CREATE POLICY graph_runtime_scope ON uacrf.%I TO uacrf_runtime_owner USING(tenant_id=current_setting(''app.tenant_id'',true) AND environment_id=current_setting(''app.environment_id'',true)) WITH CHECK(tenant_id=current_setting(''app.tenant_id'',true) AND environment_id=current_setting(''app.environment_id'',true))',n);
 END LOOP;
END $$;

-- Locks the parent during every node/edge mutation, preventing concurrent sealing of an incomplete set.
CREATE FUNCTION uacrf_app.graph_content_guard() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,uacrf AS $$
DECLARE t text; e text; rid text; st text;
BEGIN
 t:=CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
 e:=CASE WHEN TG_OP='DELETE' THEN OLD.environment_id ELSE NEW.environment_id END;
 rid:=CASE WHEN TG_OP='DELETE' THEN OLD.graph_revision_id ELSE NEW.graph_revision_id END;
 IF current_setting('app.tenant_id',true) IS DISTINCT FROM t OR current_setting('app.environment_id',true) IS DISTINCT FROM e THEN RAISE EXCEPTION 'Graph mutation scope mismatch';END IF;
 SELECT status INTO st FROM uacrf.graph_revision WHERE tenant_id=t AND environment_id=e AND id=rid FOR UPDATE;
 IF st IS DISTINCT FROM 'BUILDING' THEN RAISE EXCEPTION 'Sealed or missing graph content is immutable';END IF;
 IF TG_OP='UPDATE' AND (OLD.tenant_id,OLD.environment_id,OLD.graph_revision_id,OLD.id) IS DISTINCT FROM (NEW.tenant_id,NEW.environment_id,NEW.graph_revision_id,NEW.id) THEN RAISE EXCEPTION 'Graph content binding is immutable';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;END IF;RETURN NEW;
END $$;
CREATE TRIGGER runtime_graph_immutable BEFORE INSERT OR UPDATE OR DELETE ON uacrf.graph_node FOR EACH ROW EXECUTE FUNCTION uacrf_app.graph_content_guard();
CREATE TRIGGER runtime_graph_immutable BEFORE INSERT OR UPDATE OR DELETE ON uacrf.graph_edge FOR EACH ROW EXECUTE FUNCTION uacrf_app.graph_content_guard();
CREATE FUNCTION uacrf_app.graph_revision_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' AND NEW.status<>'BUILDING' THEN RAISE EXCEPTION 'Create graph in BUILDING state';END IF;
 IF TG_OP='DELETE' AND OLD.status<>'BUILDING' THEN RAISE EXCEPTION 'Accepted graph revisions cannot be deleted';END IF;
 IF TG_OP='UPDATE' AND OLD.status<>'BUILDING' THEN RAISE EXCEPTION 'Accepted graph revisions are immutable';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;END IF;RETURN NEW;
END $$;
CREATE TRIGGER runtime_graph_revision_immutable BEFORE INSERT OR UPDATE OR DELETE ON uacrf.graph_revision FOR EACH ROW EXECUTE FUNCTION uacrf_app.graph_revision_guard();
REVOKE UPDATE ON uacrf.graph_revision FROM uacrf_worker;

-- Canonical snapshot protocol. Only the SQL producer serializes these bytes; Java verifies rather than reserializes.
CREATE FUNCTION uacrf_app.graph_snapshot(t text,e text,rid text) RETURNS jsonb LANGUAGE plpgsql SET search_path=pg_catalog,uacrf AS $$
DECLARE g uacrf.graph_revision; ns jsonb; es jsonb;
BEGIN
 SELECT * INTO g FROM uacrf.graph_revision WHERE tenant_id=t AND environment_id=e AND id=rid;
 IF NOT FOUND THEN PERFORM uacrf_app.deny(404,'Graph unavailable');END IF;
 SELECT coalesce(jsonb_agg(jsonb_build_object('canonical_id',n.canonical_id,'label',n.label,'properties',
  jsonb_build_object('scope_service_id',coalesce(w.service_id,CASE WHEN n.label='Service' AND n.payload->>'workload_kind'='SERVICE' THEN n.payload->>'workload_id' ELSE n.service_id END),
  'workload_kind',CASE WHEN w.id IS NOT NULL THEN 'AGENT' WHEN n.label='Service' THEN 'SERVICE' ELSE 'RESOURCE' END,
  'workload_id',CASE WHEN n.label='Service' THEN coalesce(w.id,n.payload->>'workload_id') ELSE NULL END,'qualification_id',n.qualification_id,'qualification_state','QUALIFIED',
  'expires_at',floor(extract(epoch FROM n.expires_at))::bigint,'metadata_json',jsonb_strip_nulls(jsonb_build_object('name',CASE WHEN jsonb_typeof(n.payload->'name')='string' THEN n.payload->'name' END,'provider',CASE WHEN jsonb_typeof(n.payload->'provider')='string' THEN n.payload->'provider' END,'region',CASE WHEN jsonb_typeof(n.payload->'region')='string' THEN n.payload->'region' END))::text)) ORDER BY n.canonical_id),'[]'::jsonb) INTO ns
 FROM uacrf.graph_node n LEFT JOIN uacrf.workload_profile w ON w.tenant_id=n.tenant_id AND w.environment_id=n.environment_id AND w.id=n.payload->>'workload_id' AND n.payload->>'workload_kind'='AGENT'
 WHERE n.tenant_id=t AND n.environment_id=e AND n.graph_revision_id=rid;
 SELECT coalesce(jsonb_agg(jsonb_build_object('relationship_id',x.id,'source_id',s.canonical_id,'target_id',d.canonical_id,'type',x.relationship_type,'properties',
  jsonb_build_object('qualification_id',x.qualification_id,'qualification_state','QUALIFIED','confidence',x.confidence,
  'valid_from',floor(extract(epoch FROM x.valid_from))::bigint,'valid_to',CASE WHEN x.valid_to IS NULL THEN NULL ELSE floor(extract(epoch FROM x.valid_to))::bigint END,
  'expires_at',floor(extract(epoch FROM x.expires_at))::bigint,'metadata_json','{}')) ORDER BY x.id),'[]'::jsonb) INTO es
 FROM uacrf.graph_edge x JOIN uacrf.graph_node s ON (s.tenant_id,s.environment_id,s.id)=(x.tenant_id,x.environment_id,x.source_node_id)
 JOIN uacrf.graph_node d ON (d.tenant_id,d.environment_id,d.id)=(x.tenant_id,x.environment_id,x.target_node_id)
 WHERE x.tenant_id=t AND x.environment_id=e AND x.graph_revision_id=rid;
 RETURN jsonb_build_object('protocol','UACRF_GRAPH_V1','tenant_id',t,'environment_id',e,'graph_revision',rid,'service_id',g.service_id,'source_watermark',g.source_watermark,'nodes',ns,'edges',es);
END $$;
-- The opt-in protocol finalizes a BUILDING digest only inside the controlled owner procedure.
CREATE OR REPLACE FUNCTION uacrf.guard_graph_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status<>'BUILDING' THEN RAISE EXCEPTION 'Closed graph revisions are immutable';END IF;
 IF (NEW.tenant_id,NEW.environment_id,NEW.id,NEW.service_id,NEW.object_ref,NEW.source_watermark) IS DISTINCT FROM (OLD.tenant_id,OLD.environment_id,OLD.id,OLD.service_id,OLD.object_ref,OLD.source_watermark) THEN RAISE EXCEPTION 'Graph revision identity cannot change';END IF;
 IF NEW.digest IS DISTINCT FROM OLD.digest AND NOT (current_user='uacrf_runtime_owner' AND NEW.status='SEALED' AND NEW.payload->>'graph_protocol'='UACRF_GRAPH_V1' AND NEW.digest=encode(sha256(convert_to(uacrf_app.graph_snapshot(NEW.tenant_id,NEW.environment_id,NEW.id)::text,'UTF8')),'hex')) THEN RAISE EXCEPTION 'Graph digest can only be finalized from the locked SQL snapshot';END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION uacrf_app.graph_eligible(t text,e text,rid text) RETURNS boolean LANGUAGE sql SET search_path=pg_catalog,uacrf AS $$
 SELECT EXISTS(SELECT 1 FROM uacrf.graph_node WHERE tenant_id=t AND environment_id=e AND graph_revision_id=rid)
 AND NOT EXISTS(SELECT 1 FROM uacrf.graph_node n LEFT JOIN uacrf.evidence_qualification q ON (q.tenant_id,q.environment_id,q.id)=(n.tenant_id,n.environment_id,n.qualification_id)
 LEFT JOIN uacrf.evidence_event v ON (v.tenant_id,v.environment_id,v.id)=(q.tenant_id,q.environment_id,q.evidence_event_id)
 WHERE n.tenant_id=t AND n.environment_id=e AND n.graph_revision_id=rid AND
 (n.label NOT IN (SELECT unnest(source_labels) FROM uacrf.graph_endpoint_role UNION SELECT unnest(target_labels) FROM uacrf.graph_endpoint_role) OR n.expires_at<=clock_timestamp() OR q.id IS NULL OR q.verdict<>'QUALIFIED' OR EXISTS(SELECT 1 FROM uacrf.evidence_qualification revoked WHERE revoked.tenant_id=q.tenant_id AND revoked.environment_id=q.environment_id AND revoked.evidence_event_id=q.evidence_event_id AND revoked.verdict='REVOKED') OR q.expires_at<=clock_timestamp() OR v.expires_at<=clock_timestamp() OR q.service_id<>n.service_id OR
 (n.label='Service' AND NOT EXISTS(SELECT 1 FROM uacrf.service s LEFT JOIN uacrf.workload_profile w ON w.tenant_id=s.tenant_id AND w.environment_id=s.environment_id AND w.service_id=s.id
 WHERE s.tenant_id=t AND s.environment_id=e AND ((n.payload->>'workload_kind'='SERVICE' AND s.id=n.payload->>'workload_id' AND w.id IS NULL) OR (n.payload->>'workload_kind'='AGENT' AND w.id=n.payload->>'workload_id'))))))
 AND NOT EXISTS(SELECT 1 FROM uacrf.graph_edge x LEFT JOIN uacrf.evidence_qualification q ON (q.tenant_id,q.environment_id,q.id)=(x.tenant_id,x.environment_id,x.qualification_id)
 LEFT JOIN uacrf.evidence_event v ON (v.tenant_id,v.environment_id,v.id)=(q.tenant_id,q.environment_id,q.evidence_event_id)
 JOIN uacrf.graph_node s ON (s.tenant_id,s.environment_id,s.id)=(x.tenant_id,x.environment_id,x.source_node_id)
 JOIN uacrf.graph_node d ON (d.tenant_id,d.environment_id,d.id)=(x.tenant_id,x.environment_id,x.target_node_id)
 WHERE x.tenant_id=t AND x.environment_id=e AND x.graph_revision_id=rid AND (x.expires_at<=clock_timestamp() OR x.valid_from>clock_timestamp() OR x.valid_to<=clock_timestamp() OR
 q.id IS NULL OR q.verdict<>'QUALIFIED' OR EXISTS(SELECT 1 FROM uacrf.evidence_qualification revoked WHERE revoked.tenant_id=q.tenant_id AND revoked.environment_id=q.environment_id AND revoked.evidence_event_id=q.evidence_event_id AND revoked.verdict='REVOKED') OR q.expires_at<=clock_timestamp() OR v.expires_at<=clock_timestamp() OR q.service_id<>x.service_id OR
 s.graph_revision_id<>rid OR d.graph_revision_id<>rid OR NOT EXISTS(SELECT 1 FROM uacrf.graph_endpoint_role z WHERE z.relationship_type=x.relationship_type AND s.label=ANY(z.source_labels) AND d.label=ANY(z.target_labels))));
$$;
CREATE FUNCTION uacrf_app.graph_seal(t text,e text,rid text,expected bigint,reason text,actor text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,uacrf,uacrf_app AS $$
DECLARE g uacrf.graph_revision; material jsonb; dg text;
BEGIN
 IF reason IS NULL OR length(trim(reason)) NOT BETWEEN 1 AND 2000 THEN PERFORM uacrf_app.deny(422,'Acceptance reason required');END IF;
 PERFORM set_config('app.tenant_id',t,true);PERFORM set_config('app.environment_id',e,true);
 IF NOT EXISTS(SELECT 1 FROM uacrf.principal WHERE tenant_id=t AND environment_id=e AND id=actor AND enabled AND kind='HUMAN') THEN PERFORM uacrf_app.deny(422,'Active accountable human required');END IF;
 SELECT * INTO g FROM uacrf.graph_revision WHERE tenant_id=t AND environment_id=e AND id=rid FOR UPDATE;
 IF rid !~ '^[A-Za-z0-9_.:-]{1,200}$' OR g.source_watermark<0 THEN PERFORM uacrf_app.deny(422,'Bounded graph identity and nonnegative watermark required');END IF;
 IF g.id IS NULL OR g.status<>'BUILDING' OR g.revision<>expected THEN PERFORM uacrf_app.deny(409,'Graph revision changed');END IF;
 IF EXISTS(SELECT 1 FROM uacrf.graph_node WHERE tenant_id=t AND environment_id=e AND graph_revision_id=rid AND length(canonical_id) NOT BETWEEN 1 AND 200) OR EXISTS(SELECT 1 FROM uacrf.graph_edge WHERE tenant_id=t AND environment_id=e AND graph_revision_id=rid AND length(id) NOT BETWEEN 1 AND 200) THEN PERFORM uacrf_app.deny(422,'Graph identifiers exceed bounded contract');END IF;
 IF NOT uacrf_app.graph_eligible(t,e,rid) THEN PERFORM uacrf_app.deny(422,'Graph evidence, bindings or endpoints ineligible');END IF;
 material:=uacrf_app.graph_snapshot(t,e,rid);
 IF jsonb_array_length(material->'nodes')>10000 OR jsonb_array_length(material->'edges')>75000 OR octet_length(material::text)>16777216 THEN PERFORM uacrf_app.deny(422,'Graph exceeds accepted runtime capacity');END IF;
 dg:=encode(sha256(convert_to(material::text,'UTF8')),'hex');
 UPDATE uacrf.graph_revision SET status='SEALED',digest=dg,payload=payload||jsonb_build_object('graph_protocol','UACRF_GRAPH_V1','acceptance_reason',reason),revision=revision+1,updated_at=clock_timestamp() WHERE tenant_id=t AND environment_id=e AND id=rid;
 INSERT INTO uacrf_app.graph_projection(tenant_id,environment_id,revision_id,digest,state) VALUES(t,e,rid,dg,'PENDING');
 INSERT INTO uacrf.outbox(tenant_id,environment_id,id,topic,aggregate_id,payload) VALUES(t,e,'graph:'||rid,'graph.projection.requested',rid,jsonb_build_object('digest',dg));
 INSERT INTO uacrf.audit_event(tenant_id,environment_id,id,actor_id,action,target_id,digest,payload) VALUES(t,e,'graph:'||rid,actor,'graph.accepted',rid,dg,jsonb_build_object('reason',reason,'acceptance_authority','OFFLINE_DBA'));
 RETURN jsonb_build_object('revision_id',rid,'digest',dg,'state','PENDING');
END $$;

CREATE FUNCTION uacrf_app.graph_worker_scope() RETURNS uacrf_app.graph_worker_binding LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,uacrf_app AS $$
DECLARE b uacrf_app.graph_worker_binding; BEGIN SELECT * INTO b FROM uacrf_app.graph_worker_binding WHERE login_name=session_user;IF b.login_name IS NULL THEN PERFORM uacrf_app.deny(403,'Worker binding missing');END IF;
 PERFORM set_config('app.tenant_id',b.tenant_id,true);PERFORM set_config('app.environment_id',b.environment_id,true);RETURN b;END $$;
CREATE FUNCTION uacrf_app.graph_claim() RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,uacrf,uacrf_app AS $$
DECLARE b uacrf_app.graph_worker_binding; p uacrf_app.graph_projection; material jsonb;
BEGIN
 b:=uacrf_app.graph_worker_scope();SELECT * INTO p FROM uacrf_app.graph_projection WHERE tenant_id=b.tenant_id AND environment_id=b.environment_id AND
 (state='PENDING' OR (state='LEASED' AND lease_until<clock_timestamp())) AND next_attempt<=clock_timestamp() ORDER BY revision_id FOR UPDATE SKIP LOCKED LIMIT 1;
 IF NOT FOUND THEN RETURN NULL;END IF;
 material:=uacrf_app.graph_snapshot(p.tenant_id,p.environment_id,p.revision_id);

 UPDATE uacrf_app.graph_projection SET state='LEASED',lease_id=gen_random_uuid(),lease_until=clock_timestamp()+interval '60 seconds',attempts=attempts+1 WHERE tenant_id=p.tenant_id AND environment_id=p.environment_id AND revision_id=p.revision_id RETURNING * INTO p;
 INSERT INTO uacrf_app.graph_projection_event(tenant_id,environment_id,revision_id,worker_login,lease_id,operation,digest) VALUES(p.tenant_id,p.environment_id,p.revision_id,session_user,p.lease_id,'LEASED',p.digest);
 RETURN jsonb_build_object('lease_id',p.lease_id,'digest',p.digest,'canonical_text',material::text,'snapshot',material);
END $$;
CREATE FUNCTION uacrf_app.graph_complete(rid text,lease uuid,dg text,success boolean) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,uacrf_app AS $$
DECLARE b uacrf_app.graph_worker_binding;BEGIN
 b:=uacrf_app.graph_worker_scope();UPDATE uacrf_app.graph_projection SET state=CASE WHEN success THEN 'PROJECTED' ELSE 'PENDING' END,projected_at=CASE WHEN success THEN clock_timestamp() ELSE NULL END,
 next_attempt=clock_timestamp()+CASE WHEN success THEN interval '0 seconds' ELSE interval '5 seconds' END,last_error=CASE WHEN success THEN NULL ELSE 'PROJECTION_FAILED_RETRY_REQUIRED' END,lease_id=NULL,lease_until=NULL
 WHERE tenant_id=b.tenant_id AND environment_id=b.environment_id AND revision_id=rid AND state='LEASED' AND lease_id=lease AND digest=dg AND lease_until>clock_timestamp();
 IF NOT FOUND THEN PERFORM uacrf_app.deny(409,'Worker lease lost');END IF;
 INSERT INTO uacrf_app.graph_projection_event(tenant_id,environment_id,revision_id,worker_login,lease_id,operation,digest) VALUES(b.tenant_id,b.environment_id,rid,session_user,lease,CASE WHEN success THEN 'PROJECTED' ELSE 'RETRY' END,dg);
END $$;
CREATE FUNCTION uacrf_app.graph_requeue(rid text) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,uacrf_app AS $$
DECLARE b uacrf_app.graph_worker_binding;BEGIN b:=uacrf_app.graph_worker_scope();UPDATE uacrf_app.graph_projection SET state='PENDING',next_attempt=clock_timestamp(),projected_at=NULL WHERE tenant_id=b.tenant_id AND environment_id=b.environment_id AND revision_id=rid AND state='PROJECTED';IF NOT FOUND THEN PERFORM uacrf_app.deny(409,'No completed graph receipt to reconcile');END IF;INSERT INTO uacrf_app.graph_projection_event(tenant_id,environment_id,revision_id,worker_login,operation,digest) SELECT tenant_id,environment_id,revision_id,session_user,'REQUEUED',digest FROM uacrf_app.graph_projection WHERE tenant_id=b.tenant_id AND environment_id=b.environment_id AND revision_id=rid;END $$;

CREATE FUNCTION uacrf_app.graph_context(t text,e text,issuer text,subject text,token_iat bigint,rid text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,uacrf,uacrf_app AS $$
DECLARE person uacrf.principal; g uacrf.graph_revision; material jsonb; p uacrf_app.graph_projection;
BEGIN
 PERFORM set_config('app.tenant_id',t,true);PERFORM set_config('app.environment_id',e,true);
 PERFORM pg_advisory_xact_lock(hashtextextended(jsonb_build_array(t,e,'runtime-v1')::text,0));
 SELECT * INTO person FROM uacrf.principal z WHERE z.tenant_id=t AND z.environment_id=e AND z.issuer=graph_context.issuer AND z.subject=graph_context.subject;
 IF person.id IS NULL OR NOT person.enabled OR person.kind<>'HUMAN' OR token_iat<coalesce((person.payload->>'token_valid_after')::bigint,0) THEN PERFORM uacrf_app.deny(401,'Identity unavailable');END IF;
 PERFORM set_config('app.actor_id',person.id,true);
 SELECT * INTO g FROM uacrf.graph_revision WHERE tenant_id=t AND environment_id=e AND id=rid FOR SHARE;
 IF g.id IS NULL OR NOT uacrf.catalog_allowed('read',g.service_id) THEN PERFORM uacrf_app.deny(404,'Graph unavailable');END IF;
 material:=uacrf_app.graph_snapshot(t,e,rid);
 IF EXISTS(SELECT 1 FROM jsonb_array_elements(material->'nodes') n WHERE NOT uacrf.catalog_allowed('read',n->'properties'->>'scope_service_id')) THEN PERFORM uacrf_app.deny(404,'Graph unavailable');END IF;
 SELECT * INTO p FROM uacrf_app.graph_projection WHERE tenant_id=t AND environment_id=e AND revision_id=rid;
 RETURN jsonb_build_object('id',g.id,'revision',g.revision,'service_id',g.service_id,'status',g.status,'digest',g.digest,'object_ref',g.object_ref,'source_watermark',g.source_watermark,
 'eligible',g.status='SEALED' AND g.payload->>'graph_protocol'='UACRF_GRAPH_V1' AND uacrf_app.graph_eligible(t,e,rid) AND encode(sha256(convert_to(material::text,'UTF8')),'hex')=g.digest,
 'projected',p.state='PROJECTED' AND p.digest=g.digest,'node_count',jsonb_array_length(material->'nodes'),'edge_count',jsonb_array_length(material->'edges'),'canonical_text',material::text);
END $$;

ALTER FUNCTION uacrf_app.graph_content_guard() OWNER TO uacrf_runtime_owner;
ALTER FUNCTION uacrf_app.graph_revision_guard() OWNER TO uacrf_runtime_owner;
ALTER FUNCTION uacrf_app.graph_snapshot(text,text,text) OWNER TO uacrf_runtime_owner;
ALTER FUNCTION uacrf_app.graph_eligible(text,text,text) OWNER TO uacrf_runtime_owner;
ALTER FUNCTION uacrf_app.graph_seal(text,text,text,bigint,text,text) OWNER TO uacrf_runtime_owner;
ALTER FUNCTION uacrf_app.graph_worker_scope() OWNER TO uacrf_runtime_owner;
ALTER FUNCTION uacrf_app.graph_claim() OWNER TO uacrf_runtime_owner;
ALTER FUNCTION uacrf_app.graph_complete(text,uuid,text,boolean) OWNER TO uacrf_runtime_owner;
ALTER FUNCTION uacrf_app.graph_requeue(text) OWNER TO uacrf_runtime_owner;
ALTER FUNCTION uacrf_app.graph_context(text,text,text,text,bigint,text) OWNER TO uacrf_runtime_owner;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA uacrf_app FROM PUBLIC;
GRANT EXECUTE ON FUNCTION uacrf_app.graph_claim(),uacrf_app.graph_complete(text,uuid,text,boolean),uacrf_app.graph_requeue(text) TO uacrf_graph_worker;
GRANT EXECUTE ON FUNCTION uacrf_app.graph_context(text,text,text,text,bigint,text) TO uacrf_api;
-- Existing request RPC grant is retained. No worker receives graph acceptance or direct table access.
COMMIT;
