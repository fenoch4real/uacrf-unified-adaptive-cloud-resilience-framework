-- Version 1.0 runtime revision 1. Separate opt-in installation after baseline and identity/relationship patches.
-- Run as DBA in a fresh isolated database first. API credentials are server-only.
BEGIN;
CREATE ROLE uacrf_runtime_owner NOLOGIN NOSUPERUSER NOBYPASSRLS;
CREATE ROLE uacrf_api NOLOGIN NOSUPERUSER NOBYPASSRLS;
CREATE SCHEMA uacrf_app AUTHORIZATION uacrf_runtime_owner;
REVOKE ALL ON SCHEMA uacrf_app FROM PUBLIC;
GRANT USAGE ON SCHEMA uacrf_app TO uacrf_api;
GRANT USAGE ON SCHEMA uacrf,uacrf_identity_design,uacrf_relationship_design TO uacrf_runtime_owner;
GRANT SELECT,INSERT,UPDATE ON uacrf.principal,uacrf.membership,uacrf.journey,uacrf.journey_step,uacrf.journey_capability,uacrf.service,uacrf.workload_profile,uacrf.shared_asset,uacrf.capability_asset,uacrf.onboarding,uacrf.knowledge_item,uacrf.command,uacrf.outbox TO uacrf_runtime_owner;
GRANT SELECT,INSERT ON uacrf.audit_event,uacrf_relationship_design.change_history TO uacrf_runtime_owner;
GRANT EXECUTE ON FUNCTION uacrf.allowed(text,text),uacrf.catalog_allowed(text,text),uacrf.workload_allowed(text,text),uacrf.catalog_agent_anchor(text) TO uacrf_runtime_owner;
GRANT EXECUTE ON FUNCTION uacrf_identity_design.eligible_owner(text,text,text) TO uacrf_runtime_owner;
-- Procedure owner has scoped policies; API has no direct table privileges or membership in this role.
DO $$ DECLARE n text; BEGIN
 FOREACH n IN ARRAY ARRAY['principal','membership','journey','journey_step','journey_capability','service','workload_profile','shared_asset','capability_asset','onboarding','knowledge_item','command','outbox','audit_event'] LOOP
  EXECUTE format('CREATE POLICY runtime_scope ON uacrf.%I TO uacrf_runtime_owner USING (tenant_id=current_setting(''app.tenant_id'',true) AND environment_id=current_setting(''app.environment_id'',true)) WITH CHECK (tenant_id=current_setting(''app.tenant_id'',true) AND environment_id=current_setting(''app.environment_id'',true))',n);
 END LOOP;
END $$;
CREATE POLICY runtime_history ON uacrf_relationship_design.change_history TO uacrf_runtime_owner USING(tenant_id=current_setting('app.tenant_id',true) AND environment_id=current_setting('app.environment_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true) AND environment_id=current_setting('app.environment_id',true));
REVOKE INSERT,UPDATE ON uacrf.journey,uacrf.journey_step,uacrf.journey_capability,uacrf.shared_asset,uacrf.capability_asset FROM uacrf_worker;
CREATE FUNCTION uacrf_app.deny(code integer,msg text) RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog AS $$ BEGIN RAISE EXCEPTION USING ERRCODE='U'||lpad(code::text,4,'0'),MESSAGE=msg; END $$;
CREATE FUNCTION uacrf_app.cap(cap text,svc text DEFAULT NULL) RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog,uacrf AS $$ BEGIN IF NOT uacrf.catalog_allowed(cap,svc) THEN PERFORM uacrf_app.deny(403,'Current capability denied'); END IF; END $$;
CREATE FUNCTION uacrf_app.owner(p text) RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog,uacrf AS $$ BEGIN IF NOT uacrf_identity_design.eligible_owner(current_setting('app.tenant_id'),current_setting('app.environment_id'),p) THEN PERFORM uacrf_app.deny(422,'Eligible enabled human owner required'); END IF; END $$;
CREATE FUNCTION uacrf_app.record(v jsonb,k text) RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$ SELECT jsonb_build_object('id',v->>'id','kind',k,'revision',(v->>'revision')::bigint,'summary',v-ARRAY['tenant_id','environment_id','payload','created_by']) || CASE WHEN v?'name' THEN jsonb_build_object('name',v->>'name') ELSE '{}'::jsonb END $$;
CREATE FUNCTION uacrf_app.page(v jsonb,c text DEFAULT NULL) RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$ SELECT jsonb_build_object('items',coalesce(v,'[]'::jsonb),'next_cursor',c,'coverage','AUTHORIZED_VISIBLE_SCOPE_ONLY','runtime_verification','NOT_ESTABLISHED') $$;
CREATE FUNCTION uacrf_app.request(t text,e text,issuer text,subject text,token_iat bigint,op text,b jsonb,params jsonb,key text,source jsonb DEFAULT NULL) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,uacrf,uacrf_app AS $$
DECLARE actor text; person uacrf.principal; target uacrf.principal; cm uacrf.command; membership uacrf.membership;
 result jsonb; rowval jsonb; rows jsonb; oldval jsonb; currentrow jsonb; status integer:=200; objectid text; table_name text; kind text; scope text; capname text; command_id text; dg text; after_id text:=coalesce(params->>'after',''); lim integer:=coalesce((params->>'limit')::integer,25); next_id text; svc text; profile text; ownerid text; rev bigint; eventid text; effect text;
BEGIN
 IF t IS NULL OR e IS NULL OR issuer IS NULL OR subject IS NULL OR lim NOT BETWEEN 1 AND 200 THEN PERFORM uacrf_app.deny(400,'Invalid authenticated context or page'); END IF;
 PERFORM set_config('app.tenant_id',t,true);PERFORM set_config('app.environment_id',e,true);
 -- One environment lock serializes admission, grant/status changes, transfer and consistent reads.
 -- This deliberately favors correctness over per-environment throughput in this first runtime.
 PERFORM pg_advisory_xact_lock(hashtextextended(jsonb_build_array(t,e,'runtime-v1')::text,0));
 SELECT * INTO person FROM uacrf.principal p WHERE p.tenant_id=t AND p.environment_id=e AND p.issuer=request.issuer AND p.subject=request.subject;
 IF person.id IS NULL OR NOT person.enabled OR person.kind<>'HUMAN' OR token_iat<coalesce((person.payload->>'token_valid_after')::bigint,0) THEN PERFORM uacrf_app.deny(401,'Identity unavailable or session invalidated'); END IF;
 actor:=person.id;PERFORM set_config('app.actor_id',actor,true);
 -- Explicit permissions are checked even for idempotent replays and preflight source reads.
 capname:=CASE
 WHEN op IN ('identity-providers','principals') THEN 'identity.directory.read'
 WHEN op IN ('principal-create','principal-status') THEN 'identity.principal.manage'
 WHEN op='owner-candidates' THEN 'identity.owner.select'
 WHEN op='ownership-transfer' THEN 'ownership.transfer'
 WHEN op='memberships' THEN 'identity.membership.read'
 WHEN op IN ('membership-create','membership-revoke') THEN 'identity.membership.manage'
 WHEN op IN ('journeys','journey') THEN 'catalog.journey.read'
 WHEN op IN ('journey-create','step-create','journey-link-create','journey-link-update','journey-link-retire') THEN 'catalog.journey.write'
 WHEN op IN ('resources','resource') THEN 'catalog.asset.read'
 WHEN op IN ('resource-create','consumer-create','consumer-update','consumer-retire') THEN 'catalog.asset.write'
 WHEN op='services' THEN 'read'
 ELSE NULL END;
 IF capname IS NULL THEN PERFORM uacrf_app.deny(404,'Route not implemented'); END IF;
 PERFORM uacrf_app.cap(capname,NULL);
 IF params?'authority_digest' AND params->>'authority_digest' IS DISTINCT FROM uacrf_app.authority_digest(t,e,actor) THEN PERFORM uacrf_app.deny(409,'Cursor authority changed');END IF;
 IF key IS NOT NULL THEN
  IF length(key) NOT BETWEEN 1 AND 200 THEN PERFORM uacrf_app.deny(400,'Invalid idempotency key'); END IF;
  dg:=encode(sha256(convert_to(jsonb_build_array(op,b,params-ARRAY['preflight','provider_id','provider_name'])::text,'UTF8')),'hex');
  SELECT * INTO cm FROM uacrf.command c WHERE c.tenant_id=t AND c.environment_id=e AND c.actor_id=actor AND c.operation=op AND c.request_key=key;
  IF FOUND THEN
   IF cm.request_digest<>dg THEN PERFORM uacrf_app.deny(409,'Changed-content idempotency replay'); END IF;
   RETURN jsonb_build_object('status',200,'body',cm.payload->'response' || jsonb_build_object('result','REPLAYED'),'replayed',true,'authority_digest',uacrf_app.authority_digest(t,e,actor));
  END IF;
  command_id:='runtime:'||gen_random_uuid()::text;
 ELSIF op NOT IN ('identity-providers','principals','owner-candidates','memberships','journeys','journey','resources','resource','services') THEN PERFORM uacrf_app.deny(400,'Idempotency key required');
 END IF;
 IF params->>'preflight'='true' THEN RETURN jsonb_build_object('status',200,'body','{}'::jsonb); END IF;
 IF op='identity-providers' THEN
  result:=jsonb_build_object('items',jsonb_build_array(jsonb_build_object('id',params->>'provider_id','display_name',params->>'provider_name','status','TRUSTED')));
 ELSIF op IN ('principals','owner-candidates') THEN
  IF op='owner-candidates' THEN
   kind:=params->>'object_kind';
   capname:=CASE kind WHEN 'JOURNEY' THEN 'catalog.journey.write' WHEN 'RESOURCE' THEN 'catalog.asset.write' WHEN 'AGENT' THEN 'workload.register' ELSE 'write' END;
   PERFORM uacrf_app.cap(capname,NULL);
   IF params?'object_id' THEN
    table_name:=CASE kind WHEN 'JOURNEY' THEN 'journey' WHEN 'RESOURCE' THEN 'shared_asset' WHEN 'AGENT' THEN 'workload_profile' WHEN 'SERVICE' THEN 'service' WHEN 'ONBOARDING' THEN 'onboarding' WHEN 'KNOWLEDGE' THEN 'knowledge_item' END;
    EXECUTE format('SELECT to_jsonb(x) FROM uacrf.%I x WHERE tenant_id=$1 AND environment_id=$2 AND id=$3',table_name) INTO rowval USING t,e,params->>'object_id';
    IF rowval IS NULL THEN PERFORM uacrf_app.deny(404,'Target unavailable'); END IF;
    IF rowval?'service_id' THEN PERFORM uacrf_app.cap('write',rowval->>'service_id');END IF;
   END IF;
  END IF;
  SELECT jsonb_agg(v ORDER BY v->>'id') INTO rows FROM (SELECT jsonb_build_object('id',p.id,'display_name',p.display_name,'kind',p.kind,'enabled',p.enabled,'revision',p.revision,'owner_eligible',uacrf_identity_design.eligible_owner(t,e,p.id)) v FROM uacrf.principal p WHERE p.tenant_id=t AND p.environment_id=e AND p.id>after_id AND (op='principals' OR uacrf_identity_design.eligible_owner(t,e,p.id)) ORDER BY p.id LIMIT lim+1) q;
  IF jsonb_array_length(rows)>lim THEN next_id:=rows->(lim-1)->>'id';rows:=rows-lim;END IF;
  result:=jsonb_build_object('items',coalesce(rows,'[]'::jsonb),'next_cursor',next_id);
 ELSIF op='principal-create' THEN
  IF source IS NULL OR source->>'provider_id' IS DISTINCT FROM b->>'provider_id' OR source->>'subject' IS DISTINCT FROM b->>'directory_subject_ref' OR (source->>'exp')::numeric<=extract(epoch FROM clock_timestamp()) THEN PERFORM uacrf_app.deny(503,'Current verified source identity required'); END IF;
  INSERT INTO uacrf.principal(tenant_id,environment_id,id,issuer,subject,display_name,kind,payload) VALUES(t,e,b->>'id',source->>'issuer',source->>'subject',source->>'display_name',source->>'kind',jsonb_build_object('source_verified_at',clock_timestamp(),'source_jti',source->>'jti','source_digest',source->>'assertion_digest')) RETURNING to_jsonb(principal.*) INTO rowval;
  status:=201;objectid:=b->>'id';kind:='principal';
 ELSIF op='principal-status' THEN
  SELECT * INTO target FROM uacrf.principal p WHERE p.tenant_id=t AND p.environment_id=e AND p.id=params->>'id' FOR UPDATE;
  IF target.id IS NULL THEN PERFORM uacrf_app.deny(404,'Target unavailable'); END IF;
  IF target.revision<>(b->>'expected_revision')::bigint THEN PERFORM uacrf_app.deny(409,'Stale principal revision'); END IF;
  IF (b->>'enabled')::boolean THEN
   IF source IS NULL OR source->>'issuer' IS DISTINCT FROM target.issuer OR source->>'subject' IS DISTINCT FROM target.subject OR (source->>'exp')::numeric<=extract(epoch FROM clock_timestamp()) THEN PERFORM uacrf_app.deny(503,'Source reverification required'); END IF;
   -- A review is a signed independent source assertion, not a caller-controlled string.
   IF source->>'grant_review_ref' IS DISTINCT FROM b->>'grant_review_ref' OR source->>'reviewed_by' IS NULL OR source->>'reviewed_by'=actor OR source->>'reviewed_by'=target.id OR NOT uacrf_app.independent_reviewer(t,e,source->>'reviewed_by') OR source->>'grant_digest' IS DISTINCT FROM uacrf_app.grant_digest(t,e,target.id) THEN PERFORM uacrf_app.deny(403,'Current independent grant review required'); END IF;
  ELSE
   IF EXISTS(SELECT 1 FROM uacrf.membership m WHERE m.tenant_id=t AND m.environment_id=e AND m.principal_id=target.id AND m.capability='identity.principal.manage' AND m.expires_at>clock_timestamp()) AND (SELECT count(DISTINCT p.id) FROM uacrf.principal p JOIN uacrf.membership m ON (p.tenant_id,p.environment_id,p.id)=(m.tenant_id,m.environment_id,m.principal_id) WHERE p.tenant_id=t AND p.environment_id=e AND p.enabled AND p.kind='HUMAN' AND m.capability='identity.principal.manage' AND m.expires_at>clock_timestamp())<=1 THEN PERFORM uacrf_app.deny(409,'Final active administrator requires controlled recovery'); END IF;
  END IF;
  UPDATE uacrf.principal p SET enabled=(b->>'enabled')::boolean,revision=p.revision+1,payload=p.payload||jsonb_build_object('token_valid_after',floor(extract(epoch FROM clock_timestamp()))::bigint+1),updated_at=clock_timestamp() WHERE p.tenant_id=t AND p.environment_id=e AND p.id=target.id RETURNING to_jsonb(p.*) INTO rowval;
  objectid:=target.id;kind:='principal';oldval:=to_jsonb(target);
 ELSIF op='memberships' THEN
  SELECT jsonb_agg(v ORDER BY v->>'id') INTO rows FROM (SELECT jsonb_build_object('id',m.id,'principal_id',m.principal_id,'capability',m.capability,'service_scope',m.service_scope,'expires_at',m.expires_at,'revision',m.revision) v FROM uacrf.membership m WHERE m.tenant_id=t AND m.environment_id=e AND m.id>after_id ORDER BY m.id LIMIT lim+1) q;
  IF jsonb_array_length(rows)>lim THEN next_id:=rows->(lim-1)->>'id';rows:=rows-lim;END IF;
  result:=jsonb_build_object('items',coalesce(rows,'[]'::jsonb),'next_cursor',next_id);
 ELSIF op='membership-create' THEN
  IF EXISTS(SELECT 1 FROM uacrf.command WHERE tenant_id=t AND environment_id=e AND operation='membership-create' AND payload->>'authorization_ref'=b->>'authorization_ref') THEN PERFORM uacrf_app.deny(409,'Grant authorization already consumed');END IF;
  SELECT * INTO target FROM uacrf.principal p WHERE p.tenant_id=t AND p.environment_id=e AND p.id=b->>'principal_id' FOR UPDATE;
  IF target.id IS NULL OR NOT target.enabled THEN PERFORM uacrf_app.deny(422,'Enabled target required'); END IF;
  IF source IS NULL OR source->>'authorization_ref' IS DISTINCT FROM b->>'authorization_ref' OR source->>'reviewed_by' IS NULL OR source->>'reviewed_by' IN (actor,target.id) OR NOT uacrf_app.independent_reviewer(t,e,source->>'reviewed_by') OR source->>'principal_id' IS DISTINCT FROM target.id OR source->>'capability' IS DISTINCT FROM b->>'capability' OR source->>'service_scope' IS DISTINCT FROM b->>'service_scope' OR (source->>'expires_at')::timestamptz IS DISTINCT FROM (b->>'expires_at')::timestamptz OR (source->>'exp')::numeric<=extract(epoch FROM clock_timestamp()) THEN PERFORM uacrf_app.deny(403,'Exact independently authorized grant required'); END IF;
  IF NOT EXISTS(SELECT 1 FROM uacrf.membership m WHERE m.tenant_id=t AND m.environment_id=e AND m.principal_id=actor AND m.capability=b->>'capability' AND (m.service_scope='*' OR m.service_scope=b->>'service_scope') AND m.expires_at>=(b->>'expires_at')::timestamptz) OR (b->>'expires_at')::timestamptz<=clock_timestamp() OR (b->>'expires_at')::timestamptz>clock_timestamp()+interval '24 hours' THEN PERFORM uacrf_app.deny(403,'Delegation ceiling or expiry denied'); END IF;
  IF (b->>'capability') NOT IN (SELECT jsonb_array_elements_text(uacrf_app.capabilities())) OR (b->>'service_scope'='*' AND NOT uacrf.allowed('identity.delegate.environment',NULL)) THEN PERFORM uacrf_app.deny(403,'Unknown capability or wildcard delegation denied'); END IF;
  IF b->>'capability'='owner.eligible' AND (target.kind<>'HUMAN' OR b->>'service_scope'<>'*') THEN PERFORM uacrf_app.deny(422,'Human environment-local owner eligibility required'); END IF;
  IF b->>'service_scope'<>'*' AND NOT EXISTS(SELECT 1 FROM uacrf.service WHERE tenant_id=t AND environment_id=e AND id=b->>'service_scope') THEN PERFORM uacrf_app.deny(404,'Service scope unavailable'); END IF;
  IF EXISTS(SELECT 1 FROM uacrf.membership WHERE tenant_id=t AND environment_id=e AND principal_id=target.id AND capability=b->>'capability' AND service_scope=b->>'service_scope' AND expires_at>clock_timestamp()) THEN PERFORM uacrf_app.deny(409,'Current capability membership already exists'); END IF;
  INSERT INTO uacrf.membership AS inserted(tenant_id,environment_id,id,principal_id,capability,service_scope,expires_at,payload) VALUES(t,e,b->>'id',target.id,b->>'capability',b->>'service_scope',(b->>'expires_at')::timestamptz,jsonb_build_object('authorization_ref',b->>'authorization_ref','reason',b->>'reason')) RETURNING to_jsonb(inserted.*) INTO rowval;
  objectid:=b->>'id';kind:='membership';status:=201;
 ELSIF op='membership-revoke' THEN
  SELECT * INTO membership FROM uacrf.membership m WHERE m.tenant_id=t AND m.environment_id=e AND m.id=params->>'id' FOR UPDATE;
  IF membership.id IS NULL THEN PERFORM uacrf_app.deny(404,'Target unavailable'); END IF;
  IF membership.revision<>(b->>'expected_revision')::bigint THEN PERFORM uacrf_app.deny(409,'Stale membership revision'); END IF;
  IF membership.capability='identity.principal.manage' AND membership.expires_at>clock_timestamp() AND (SELECT count(DISTINCT p.id) FROM uacrf.principal p JOIN uacrf.membership m ON (p.tenant_id,p.environment_id,p.id)=(m.tenant_id,m.environment_id,m.principal_id) WHERE p.tenant_id=t AND p.environment_id=e AND p.enabled AND p.kind='HUMAN' AND m.capability='identity.principal.manage' AND m.expires_at>clock_timestamp() AND m.id<>membership.id)=0 THEN PERFORM uacrf_app.deny(409,'Final administrator grant cannot be revoked'); END IF;
  UPDATE uacrf.membership m SET expires_at=least(m.expires_at,clock_timestamp()),revision=m.revision+1,payload=m.payload||jsonb_build_object('revoked_by',actor,'reason',b->>'reason') WHERE m.tenant_id=t AND m.environment_id=e AND m.id=membership.id RETURNING to_jsonb(m.*) INTO rowval;
  oldval:=to_jsonb(membership);objectid:=membership.id;kind:='membership';
 ELSIF op='ownership-transfer' THEN
  kind:=b->>'object_kind';objectid:=b->>'object_id';ownerid:=b->>'owner_id';PERFORM uacrf_app.owner(ownerid);
  table_name:=CASE kind WHEN 'JOURNEY' THEN 'journey' WHEN 'SERVICE' THEN 'service' WHEN 'AGENT' THEN 'workload_profile' WHEN 'RESOURCE' THEN 'shared_asset' WHEN 'ONBOARDING' THEN 'onboarding' WHEN 'KNOWLEDGE' THEN 'knowledge_item' END;
  IF table_name IS NULL THEN PERFORM uacrf_app.deny(422,'Invalid owner object kind'); END IF;
  EXECUTE format('SELECT to_jsonb(x) FROM uacrf.%I x WHERE tenant_id=$1 AND environment_id=$2 AND id=$3 FOR UPDATE',table_name) INTO oldval USING t,e,objectid;
  IF oldval IS NULL THEN PERFORM uacrf_app.deny(404,'Target unavailable'); END IF;
  IF (oldval->>'revision')::bigint<>(b->>'expected_revision')::bigint THEN PERFORM uacrf_app.deny(409,'Stale object revision'); END IF;
  capname:=CASE kind WHEN 'JOURNEY' THEN 'catalog.journey.write' WHEN 'RESOURCE' THEN 'catalog.asset.write' WHEN 'AGENT' THEN 'workload.register' ELSE 'write' END;PERFORM uacrf_app.cap(capname,coalesce(oldval->>'service_id',CASE WHEN kind='SERVICE' THEN objectid END));
  IF kind='SERVICE' AND uacrf.catalog_agent_anchor(objectid) THEN PERFORM uacrf_app.deny(422,'Transfer an agent through its protected profile'); END IF;
  IF kind='AGENT' THEN
   SELECT to_jsonb(s.*) INTO currentrow FROM uacrf.service s WHERE tenant_id=t AND environment_id=e AND id=oldval->>'service_id' FOR UPDATE;
   IF (currentrow->>'revision')::bigint IS DISTINCT FROM (b->>'expected_anchor_revision')::bigint THEN PERFORM uacrf_app.deny(409,'Stale agent anchor revision'); END IF;
   UPDATE uacrf.service s SET owner_id=ownerid,revision=s.revision+1,updated_at=clock_timestamp() WHERE tenant_id=t AND environment_id=e AND id=oldval->>'service_id';
  END IF;
  EXECUTE format('UPDATE uacrf.%I x SET owner_id=$4,revision=revision+1,updated_at=clock_timestamp() WHERE tenant_id=$1 AND environment_id=$2 AND id=$3 RETURNING to_jsonb(x)',table_name) INTO rowval USING t,e,objectid,ownerid;
 ELSIF op='journey-create' THEN
  PERFORM uacrf_app.owner(b->>'owner_id');
  INSERT INTO uacrf.journey(tenant_id,environment_id,id,name,owner_id,purpose,lifecycle,created_by) VALUES(t,e,b->>'id',b->>'name',b->>'owner_id',b->>'purpose',b->>'lifecycle',actor) RETURNING to_jsonb(journey.*) INTO rowval;
  objectid:=b->>'id';kind:='journey';status:=201;
 ELSIF op='step-create' THEN
  IF NOT EXISTS(SELECT 1 FROM uacrf.journey WHERE tenant_id=t AND environment_id=e AND id=params->>'id' AND lifecycle<>'RETIRED') THEN PERFORM uacrf_app.deny(404,'Active catalogue target unavailable'); END IF;
  INSERT INTO uacrf.journey_step(tenant_id,environment_id,id,journey_id,position,name,intended_outcome,created_by) VALUES(t,e,b->>'id',params->>'id',(b->>'position')::integer,b->>'name',b->>'intended_outcome',actor) RETURNING to_jsonb(journey_step.*) INTO rowval;
  objectid:=b->>'id';kind:='journey_step';status:=201;
 ELSIF op IN ('journey-link-create','consumer-create') THEN
  IF b->>'capability_kind'='AGENT' THEN
   SELECT service_id,id INTO svc,profile FROM uacrf.workload_profile WHERE tenant_id=t AND environment_id=e AND id=b->>'capability_id';PERFORM uacrf_app.cap('workload.read',svc);
  ELSE svc:=b->>'capability_id';profile:=NULL;IF uacrf.catalog_agent_anchor(svc) THEN PERFORM uacrf_app.deny(422,'Agent anchors are not conventional services');END IF;END IF;
  IF svc IS NULL OR NOT EXISTS(SELECT 1 FROM uacrf.service WHERE tenant_id=t AND environment_id=e AND id=svc) THEN PERFORM uacrf_app.deny(404,'Capability unavailable'); END IF;
  PERFORM uacrf_app.cap('write',svc);
  IF op='journey-link-create' THEN
   INSERT INTO uacrf.journey_capability(tenant_id,environment_id,id,journey_id,step_id,service_id,capability_kind,profile_id,criticality,created_by) VALUES(t,e,b->>'id',params->>'id',b->>'step_id',svc,b->>'capability_kind',profile,b->>'criticality',actor) RETURNING to_jsonb(journey_capability.*) INTO rowval;
   kind:='journey_capability';
  ELSE
   INSERT INTO uacrf.capability_asset(tenant_id,environment_id,id,service_id,asset_id,role,effect,created_by) VALUES(t,e,b->>'id',svc,params->>'id',b->>'role',b->>'effect',actor) RETURNING to_jsonb(capability_asset.*) INTO rowval;
   kind:='capability_asset';
  END IF;objectid:=b->>'id';status:=201;
 ELSIF op='resource-create' THEN
  PERFORM uacrf_app.owner(b->>'owner_id');
  INSERT INTO uacrf.shared_asset(tenant_id,environment_id,id,name,asset_kind,owner_id,provider,lifecycle,graph_label,graph_canonical_id,created_by) VALUES(t,e,b->>'id',b->>'name',b->>'asset_kind',b->>'owner_id',b->>'provider',b->>'lifecycle',b->>'graph_label',b->>'graph_canonical_id',actor) RETURNING to_jsonb(shared_asset.*) INTO rowval;objectid:=b->>'id';kind:='shared_asset';status:=201;
 ELSIF op IN ('journey-link-update','journey-link-retire','consumer-update','consumer-retire') THEN
  table_name:=CASE WHEN op LIKE 'journey-%' THEN 'journey_capability' ELSE 'capability_asset' END;
  EXECUTE format('SELECT to_jsonb(x) FROM uacrf.%I x WHERE tenant_id=$1 AND environment_id=$2 AND id=$3 AND %I=$4 FOR UPDATE',table_name,CASE WHEN table_name='journey_capability' THEN 'journey_id' ELSE 'asset_id' END) INTO oldval USING t,e,params->>'link_id',params->>'id';
  IF oldval IS NULL THEN PERFORM uacrf_app.deny(404,'Relationship unavailable');END IF;
  PERFORM uacrf_app.cap('write',oldval->>'service_id');
  IF oldval->>'link_lifecycle'<>'ACTIVE' OR (oldval->>'revision')::bigint<>(b->>'expected_revision')::bigint THEN PERFORM uacrf_app.deny(409,'Stale or retired relationship');END IF;
  IF op LIKE '%retire' THEN
   EXECUTE format('UPDATE uacrf.%I x SET link_lifecycle=''RETIRED'',revision=revision+1 WHERE tenant_id=$1 AND environment_id=$2 AND id=$3 RETURNING to_jsonb(x)',table_name) INTO rowval USING t,e,params->>'link_id';
  ELSIF table_name='journey_capability' THEN
   UPDATE uacrf.journey_capability x SET step_id=b->>'step_id',criticality=b->>'criticality',revision=x.revision+1 WHERE tenant_id=t AND environment_id=e AND id=params->>'link_id' RETURNING to_jsonb(x.*) INTO rowval;
  ELSE
   UPDATE uacrf.capability_asset x SET role=b->>'role',effect=b->>'effect',revision=x.revision+1 WHERE tenant_id=t AND environment_id=e AND id=params->>'link_id' RETURNING to_jsonb(x.*) INTO rowval;
  END IF;
  objectid:=params->>'link_id';kind:=table_name;
  INSERT INTO uacrf_relationship_design.change_history(tenant_id,environment_id,id,relationship_kind,relationship_id,journey_link_id,resource_link_id,revision,actor_id,reason,before_record,after_record) VALUES(t,e,command_id,CASE WHEN table_name='journey_capability' THEN 'JOURNEY_CAPABILITY' ELSE 'RESOURCE_CONSUMER' END,objectid,CASE WHEN table_name='journey_capability' THEN objectid END,CASE WHEN table_name='capability_asset' THEN objectid END,(rowval->>'revision')::bigint,actor,b->>'reason',oldval,rowval);
 ELSIF op IN ('journeys','resources','services') THEN
  table_name:=CASE op WHEN 'journeys' THEN 'journey' WHEN 'resources' THEN 'shared_asset' ELSE 'service' END;
  EXECUTE format('SELECT jsonb_agg(uacrf_app.record(v,$5) ORDER BY v->>''id'') FROM (SELECT to_jsonb(x) v FROM uacrf.%I x WHERE tenant_id=$1 AND environment_id=$2 AND id>$3 AND ($6 OR lifecycle<>''RETIRED'') AND ($5<>''shared_asset'' OR $7 IS NULL OR to_jsonb(x)->>''asset_kind''=$7) AND ($5<>''service'' OR NOT uacrf.catalog_agent_anchor(id)) AND ($5<>''service'' OR uacrf.allowed(''read'',id)) ORDER BY id LIMIT $4) q',table_name) INTO rows USING t,e,after_id,lim+1,table_name,coalesce((params->>'include_retired')::boolean,false),params->>'asset_kind';
  IF jsonb_array_length(rows)>lim THEN next_id:=rows->(lim-1)->>'id';rows:=rows-lim;END IF;result:=uacrf_app.page(rows,next_id);
 ELSIF op IN ('journey','resource') THEN
  table_name:=CASE op WHEN 'journey' THEN 'journey' ELSE 'shared_asset' END;
  EXECUTE format('SELECT to_jsonb(x) FROM uacrf.%I x WHERE tenant_id=$1 AND environment_id=$2 AND id=$3',table_name) INTO rowval USING t,e,params->>'id';
  IF rowval IS NULL THEN PERFORM uacrf_app.deny(404,'Target unavailable');END IF;
  result:=jsonb_build_object('record',uacrf_app.record(rowval,table_name),'related','{}'::jsonb,'runtime_verification','NOT_ESTABLISHED');
  IF op='journey' THEN
   SELECT jsonb_agg(uacrf_app.record(to_jsonb(x.*),'journey_step') ORDER BY id) INTO rows FROM (SELECT * FROM uacrf.journey_step WHERE tenant_id=t AND environment_id=e AND journey_id=params->>'id' AND id>coalesce(params->>'step_after','') ORDER BY id LIMIT lim+1) x;
   next_id:=NULL;IF jsonb_array_length(rows)>lim THEN next_id:=rows->(lim-1)->>'id';rows:=rows-lim;END IF;result:=jsonb_set(result,'{related,steps}',uacrf_app.page(rows,next_id));
   SELECT jsonb_agg(uacrf_app.record(to_jsonb(x.*),'journey_capability') ORDER BY id) INTO rows FROM (SELECT * FROM uacrf.journey_capability WHERE tenant_id=t AND environment_id=e AND journey_id=params->>'id' AND link_lifecycle='ACTIVE' AND id>coalesce(params->>'capability_after',params->>'consumer_after','') AND uacrf.allowed('read',service_id) AND (capability_kind='SERVICE' OR uacrf.workload_allowed('workload.read',service_id)) ORDER BY id LIMIT lim+1) x;
   next_id:=NULL;IF jsonb_array_length(rows)>lim THEN next_id:=rows->(lim-1)->>'id';rows:=rows-lim;END IF;result:=jsonb_set(result,'{related,capabilities}',uacrf_app.page(rows,next_id));
  ELSE
   SELECT jsonb_agg(uacrf_app.record(to_jsonb(x.*),'capability_asset') ORDER BY id) INTO rows FROM (SELECT * FROM uacrf.capability_asset WHERE tenant_id=t AND environment_id=e AND asset_id=params->>'id' AND link_lifecycle='ACTIVE' AND id>coalesce(params->>'capability_after',params->>'consumer_after','') AND uacrf.allowed('read',service_id) ORDER BY id LIMIT lim+1) x;
   next_id:=NULL;IF jsonb_array_length(rows)>lim THEN next_id:=rows->(lim-1)->>'id';rows:=rows-lim;END IF;result:=jsonb_set(result,'{related,consumers}',uacrf_app.page(rows,next_id));
  END IF;
 END IF;
 IF key IS NOT NULL THEN
  IF result IS NULL THEN
   result:=jsonb_build_object('id',objectid,'revision',(rowval->>'revision')::bigint,'result',CASE WHEN status=201 THEN 'CREATED' WHEN op='membership-revoke' THEN 'REVOKED' ELSE 'UPDATED' END,'runtime_effect','NONE');
   IF kind IN ('journey','journey_step','journey_capability','shared_asset','capability_asset') AND status=201 THEN result:=result||jsonb_build_object('record_kind',kind);END IF;
  END IF;
  INSERT INTO uacrf.command(tenant_id,environment_id,id,operation,actor_id,request_key,request_digest,status,payload) VALUES(t,e,command_id,op,actor,key,dg,'SUCCEEDED',jsonb_build_object('response',result,'http_status',status,'authorization_ref',CASE WHEN op='membership-create' THEN b->>'authorization_ref' END,'source_jti',source->>'jti','source_digest',source->>'assertion_digest'));
  eventid:=command_id;
  INSERT INTO uacrf.audit_event(tenant_id,environment_id,id,actor_id,action,target_id,digest,payload) VALUES(t,e,eventid,actor,op,objectid,dg,jsonb_build_object('reason',b->>'reason','before',oldval,'after',rowval,'command_id',command_id));
  INSERT INTO uacrf.outbox(tenant_id,environment_id,id,topic,aggregate_id,payload) VALUES(t,e,eventid,'runtime.metadata.changed',objectid,jsonb_build_object('command_id',command_id,'operation',op));
 END IF;
 RETURN jsonb_build_object('status',status,'body',result,'authority_digest',uacrf_app.authority_digest(t,e,actor));
END $$;
CREATE FUNCTION uacrf_app.capabilities() RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$ SELECT '["identity.directory.read","identity.principal.manage","identity.owner.select","identity.membership.read","identity.membership.manage","identity.delegate.environment","identity.grant.approve","ownership.transfer","owner.eligible","catalog.journey.read","catalog.journey.write","catalog.asset.read","catalog.asset.write","read","write","workload.read","workload.register"]'::jsonb $$;
CREATE FUNCTION uacrf_app.grant_digest(t text,e text,p text) RETURNS text LANGUAGE sql STABLE SET search_path=pg_catalog,uacrf AS $$ SELECT encode(sha256(convert_to(coalesce(jsonb_agg(jsonb_build_array(id,revision,capability,service_scope,expires_at) ORDER BY id),'[]'::jsonb)::text,'UTF8')),'hex') FROM uacrf.membership WHERE tenant_id=t AND environment_id=e AND principal_id=p $$;
CREATE FUNCTION uacrf_app.authority_digest(t text,e text,p text) RETURNS text LANGUAGE sql STABLE SET search_path=pg_catalog,uacrf AS $$ SELECT encode(sha256(convert_to(jsonb_build_array((SELECT revision FROM uacrf.principal WHERE tenant_id=t AND environment_id=e AND id=p),(SELECT coalesce(jsonb_agg(jsonb_build_array(id,revision,capability,service_scope,expires_at) ORDER BY id),'[]'::jsonb) FROM uacrf.membership WHERE tenant_id=t AND environment_id=e AND principal_id=p AND expires_at>statement_timestamp()))::text,'UTF8')),'hex') $$;
CREATE FUNCTION uacrf_app.independent_reviewer(t text,e text,p text) RETURNS boolean LANGUAGE sql STABLE SET search_path=pg_catalog,uacrf AS $$ SELECT EXISTS(SELECT 1 FROM uacrf.principal x JOIN uacrf.membership m ON (x.tenant_id,x.environment_id,x.id)=(m.tenant_id,m.environment_id,m.principal_id) WHERE x.tenant_id=t AND x.environment_id=e AND x.id=p AND x.enabled AND x.kind='HUMAN' AND m.capability='identity.grant.approve' AND m.service_scope='*' AND m.expires_at>statement_timestamp()) $$;
DO $$ DECLARE f record; BEGIN FOR f IN SELECT p.oid::regprocedure name FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='uacrf_app' LOOP EXECUTE format('ALTER FUNCTION %s OWNER TO uacrf_runtime_owner',f.name);EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',f.name); END LOOP; END $$;
GRANT EXECUTE ON FUNCTION uacrf_app.request(text,text,text,text,bigint,text,jsonb,jsonb,text,jsonb) TO uacrf_api;
COMMIT;
