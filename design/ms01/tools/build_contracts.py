from pathlib import Path
import json,shutil
B=Path(__file__).resolve().parents[1]; S=B.parents[1]/'development/UACRF_v1_0_application'
def save(n,v):(B/n).write_text(json.dumps(v,indent=2)+'\n')
id={'type':'string','pattern':'^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$'}
hash={'type':'string','pattern':'^[a-f0-9]{64}$'}
time={'type':'string','format':'date-time'}
integer={'type':'integer','minimum':0,'maximum':9007199254740991}
service={'enum':['S%02d'%i for i in range(1,15)]+['UX01','EDGE01']}
def obj(props):return {'type':'object','additionalProperties':False,'properties':props,'required':list(props)}
def schema(props):return {'$schema':'https://json-schema.org/draft/2020-12/schema',**obj(props)}
base={'tenant_id':id,'environment_id':id}
receipt={'protocol':{'const':'UACRF_OWNER_RECEIPT_V1'},'receipt_id':id,'issuer_service':service,'audience_service':service,**base,'actor_id':id,'resource_type':id,'resource_id':id,'resource_revision':integer,'content_digest':hash,'authority_epoch':integer,'eligible':{'type':'boolean'},'purpose':id,'issued_at':time,'valid_until':time,'signature':{'type':'string','minLength':1,'maxLength':8192}}
save('contracts/owner-receipt.schema.json',schema(receipt))
envelope={'protocol':{'const':'UACRF_DOMAIN_EVENT_V1'},'event_id':id,'event_type':{'type':'string','pattern':'^uacrf\\.[a-z0-9_.-]+\\.v[1-9][0-9]*$'},'schema_version':{'const':1},'producer':service,**base,'aggregate_type':id,'aggregate_id':id,'aggregate_revision':integer,'aggregate_sequence':integer,'observed_at':{'anyOf':[time,{'type':'null'}]},'published_at':time,'correlation_id':id,'causation_id':{'anyOf':[id,{'type':'null'}]},'traceparent':{'anyOf':[{'type':'string','pattern':'^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'},{'type':'null'}]},'data_schema':id,'payload_digest':hash,'canonicalization':{'const':'RFC8785_DECIMAL_STRINGS_V1'},'data':{'type':'object','maxProperties':64}}
save('contracts/event-envelope.schema.json',schema(envelope))
permit={'protocol':{'const':'UACRF_EXECUTION_RESERVATION_V1'},'reservation_id':id,'authorization_id':id,'attempt_id':id,'issuer_service':{'const':'S10'},'executor_id':id,**base,'workload_id':id,'plan_digest':hash,'context_digest':hash,'resource_scope_digest':hash,'control_generation':integer,'state':{'enum':['RESERVED','CONSUMED','REVOKED','EXPIRED']},'issued_at':time,'expires_at':time,'signature':{'type':'string','minLength':1,'maxLength':8192}}
save('contracts/execution-reservation.schema.json',schema(permit))
usage={'protocol':{'const':'UACRF_USAGE_RECEIPT_V1'},'source_event_id':id,'producer':service,**base,'account_id':id,'metric':id,'unit':id,'quantity':{'type':'string','pattern':'^(0|[1-9][0-9]{0,24})(\\.[0-9]{1,12})?$'},'observed_from':time,'observed_until':time,'source_digest':hash,'outcome':{'enum':['MEASURED','CORRECTION','UNKNOWN']},'correction_of':{'anyOf':[id,{'type':'null'}]},'signature':{'type':'string','minLength':1,'maxLength':8192}}
# Unknown usage has no invented quantity.
usage['quantity']={'anyOf':[usage['quantity'],{'type':'null'}]}
u=schema(usage);u['allOf']=[{'if':{'properties':{'outcome':{'const':'UNKNOWN'}}},'then':{'properties':{'quantity':{'type':'null'}}},'else':{'properties':{'quantity':{'type':'string'}}}}]
save('contracts/usage-receipt.schema.json',u)
events=[('S01','identity.changed',['S02','S09','S10','S11','S14']),('S02','workload.version.activated',['S06','S07','S09','S10','S11','S12','S14']),('S02','relationship.proposed',['S06','S14']),('S03','source.batch.received',['S04','S14']),('S03','connection.state.changed',['S02','S14']),('S04','observation.retained',['S07','S14']),('S04','qualification.changed',['S07','S10','S12','S14']),('S04','waiver.revoked',['S07','S10','S14']),('S05','objective.activated',['S07','S10','S14']),('S05','objective.import.resolved',['S14']),('S06','graph.accepted',['S14']),('S06','graph.projected',['S07','S10','S12','S14']),('S06','graph.conflict',['S10','S14']),('S07','case.sealed',['S09','S10','S14']),('S07','evaluation.completed',['S10','S14']),('S07','recommendation.retained',['S09','S10','S14']),('S07','forecast.retained',['S14']),('S07','learning.proposed',['S02','S05','S10','S14']),('S08','knowledge.review.changed',['S09','S10','S14']),('S08','retrieval.retained',['S09','S14']),('S09','run.changed',['S14']),('S09','output.retained',['S14']),('S09','arbitration.retained',['S14']),('S10','decision.retained',['S11','S14']),('S10','authorization.revoked',['S11','S12','S14']),('S10','control.fenced',['S11','S14']),('S11','attempt.outcome.changed',['S12','S13','S14']),('S11','agent.checkpoint.retained',['S12','S14']),('S12','validation.completed',['S07','S10','S11','S14']),('S12','certification.changed',['S07','S10','S14']),('S13','usage.admitted',['S14']),('S13','invoice.posted',['S14']),('S13','entitlement.changed',['S01','S14']),('S14','notification.delivery.changed',[])]
save('EVENT_CATALOG.json',[dict(event_type='uacrf.'+s.lower()+'.'+n+'.v1',producer=s,consumers=c,topic='uacrf.'+s.lower()+'.domain.v1',schema_version=1,partition_key='tenant|environment|aggregate_type|aggregate_id',payload_requirements=['owned_resource_id','owned_revision','owned_digest','minimal_outcome_metadata'],delivery='AT_LEAST_ONCE_WITH_INBOX_DEDUPLICATION',authority='NOT_AN_EXECUTION_PERMIT',status='PROPOSED') for s,n,c in events])
# Bundle legacy wire schemas with their local references, without editing their bytes.
initial=list(S.rglob('openapi*.json'))+list(S.rglob('*.schema.json'));queue=[p for p in initial if 'target' not in p.parts];seen=set()
while queue:
 p=queue.pop()
 if p in seen:continue
 seen.add(p);j=json.loads(p.read_text())
 def walk(n):
  if isinstance(n,dict):
   if '$ref' in n:
    f=n['$ref'].split('#')[0]
    if f and ':' not in f:
     q=(p.parent/f).resolve()
     if q.is_relative_to(S.resolve()) and q.exists():queue.append(q)
   for v in n.values():walk(v)
  elif isinstance(n,list):
   for v in n:walk(v)
 walk(j)
 target=B/'contracts/source'/p.relative_to(S);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,target)
save('contracts/SOURCE_CONTRACT_INDEX.json',[str(p.relative_to(S)) for p in sorted(seen)])
print('Generated four target common schemas,',len(events),'event types and',len(seen),'unaltered source contract files')
