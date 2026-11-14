from pathlib import Path
import json,re,csv,hashlib
BASE=Path(__file__).resolve().parents[1]; ROOT=BASE.parents[1]; SRC=ROOT/'development/UACRF_v1_0_application'
def save(name,value):(BASE/name).write_text(json.dumps(value,indent=2)+'\n')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
services=[
('S01','identity-access','Identity and access','Tenant and environment enrollment, principal admission, owners, scoped grants, revocation and service identity','Principal and membership lifecycle; authoritative current access receipts','Identity.java Database.java','Identity'),
('S02','estate-catalog','Estate catalog','Customer journeys, services, protected agent profiles and versions, shared resources and authored relationships','Inventory identity, accountable ownership and independently activated agent versions','ApplicationCommands.java UiController.java','Catalog'),
('S03','integration','Connections and ingestion','Connections, discovery, provider inventory receipts, signal intake, collection policies and scheduler leases','Provider source bindings and collection checkpoints; never computes health from arrival time','runtime/008_collection_policy_runtime.sql','Observe'),
('S04','evidence','Evidence and qualification','Immutable observations, source attestations, classification, qualification policies, reviews and waivers','Observation provenance and quality; stale or missing data remains explicitly unknown','EvidenceProcessor.java QualificationProcessor.java WaiverTrust.java','Observe and qualify'),
('S05','objectives','Objectives and imports','Versioned objective templates, mandatory rules, proposals, independent activation and imported objective resolution','Approved desired outcomes and source-definition provenance; never fabricates measurements','UiController.java','Define'),
('S06','topology','Topology and dependencies','Accepted graph revisions, scoped Neo4j projection, service and agent graphs, impact, differences and reconciliation','PostgreSQL owns accepted graph material; Neo4j is a verified query projection','GraphStore.java GraphService.java GraphWorker.java','Detect and analyze'),
('S07','assessment','Assessment and learning','Fixed analysis cases, deterministic evaluations, observed risk, recommendations, forecasts and learning proposals','Observed facts remain distinct from future prediction and proposed adaptations','AnalysisCases.java EvaluationWorker.java ProposalProcessor.java','Detect Predict Learn'),
('S08','knowledge','Knowledge and retrieval','Knowledge source/version review, purpose and classification rules, bounded retrieval and immutable citations','Approved supplied knowledge and exact excerpt provenance','KnowledgeRetrieval.java RetrievalService.java','Inform'),
('S09','agent-orchestration','Advisory agent orchestration','Attributable agent-case seals, bounded multi-agent runs, one-time assignments, outputs, dissent and human arbitration','A0 A1 A2 advisory assistance; no cloud execution authority','AgentCases.java AdvisoryRuns.java AdvisoryWorkflow.java','Assist'),
('S10','decision-governance','Decision governance','Signed authorization-policy lifecycle, durable policy evaluation, approvals, one-time authorizations, fences, decision envelopes and replay','Authoritative action permission, cancellation and bounded decision-context receipts','ActionPolicyEngine.java PolicyTrust.java PolicyAttestor.java','Prevent and adapt governance'),
('S11','execution','Execution and workload control','Provider plan translation, durable workflows, protected agent execution, control generations, checkpoints, compensation and isolated action dispatch','Exactly attributable attempts; ambiguous external effects remain unknown until reconciled','runtime/005_agent_runtime.sql','Withstand and recover'),
('S12','recovery-assurance','Recovery assurance and validation','Validation plan/probe lifecycle, resilience experiments, recovery paths, path certification and independence baselines','Independent observed recovery validation; topology alone never certifies recovery','ValidationPlanProcessor.java Assurance.java','Withstand Recover Learn'),
('S13','commercial-billing','Commercial and billing','Customer qualifications, pilots, approved product claims, launch gates, subscriptions, metering, rating, invoices, payment reconciliation and value review','Immutable admitted usage and money ledger; entitlements never increase operational authority','database/billing/README.md','Commercial'),
('S14','audit-reporting','Audit reporting and notifications','Domain audit receipt aggregation, evidence-bundle export, recent activity, findings routing and notification delivery','Read models and sealed exports preserve source ownership and current disclosure','runtime/001_runtime.sql','Explain'),
('UX01','experience-api','Unified experience API','One browser application, backend for frontend, journey composition, saved views and personal preferences','Uses domain APIs; no cross-service SQL or independent action permissions','UiGateway.java UiSecurity.java UiController.java','Experience'),
('EDGE01','edge-gateway','API edge','External route compatibility, limits, TLS ingress and command routing','Routes and authenticates transport; each owner still authorizes its operation','Api.java Routes.java','Edge')]
rows=[]
for sid,slug,title,scope,authority,classes,loop in services:rows.append(dict(service_id=sid,name=slug,title=title,scope=scope,authority=authority,existing_code_examples=classes.split(),loop=loop,design_status='PROPOSED',implementation_status='NOT_IMPLEMENTED_AS_MICROSERVICE',database=('none' if sid=='EDGE01' else 'uacrf_'+slug.replace('-','_')),port=8080))
save('SERVICE_CATALOG.json',{'version':'1.0','architecture_revision':'MS-01','domain_services':14,'edge_components':2,'services':rows})
# Every source table is assigned explicitly, including polymorphic migration patterns.
owner_lists={
'S01':'environment principal membership',
'S02':'workload_profile workload_version workload_dependency journey journey_step journey_capability shared_asset capability_asset service resource service_resource',
'S03':'connection discovery_run onboarding source_binding source_checkpoint collection_policy signal_receipt',
'S04':'evidence_event evidence_qualification evidence_receipt qualification_policy qualification_policy_review qualification_input qualification_result waiver_trust_configuration qualification_waiver qualification_waiver_review qualification_waiver_revocation',
'S05':'template_version objective_revision review_decision activation objective_import import_resolution',
'S06':'graph_endpoint_role graph_revision graph_node graph_edge impact_result graph_difference subgraph_selection reconciliation graph_worker_binding graph_projection graph_projection_event',
'S07':'analysis_case evaluation proposal analysis_context evaluation_input evaluation_result proposal_input proposal_record evaluation_rulepack evaluation_rulepack_review evaluation_schedule',
'S08':'knowledge_item knowledge_version knowledge_review retrieval_manifest retrieval_citation retrieval_record',
'S09':'orchestration_run budget_reservation agent_assignment agent_output agent_dissent arbitration agent_case_record advisory_run advisory_event advisory_assignment advisory_output advisory_arbitration advisory_delivery',
'S10':'policy_version policy_decision approval authorization decision_envelope decision_replay policy_trust_configuration authorization_policy authorization_policy_review',
'S11':'workload_execution workload_event workload_checkpoint provider_plan workflow execution_attempt',
'S12':'workload_evaluation workload_recovery experiment recovery_path path_certification validation_plan validation_result recovery_assessment recovery_review validation_plan_input validation_plan_record',
'S13':'customer customer_qualification pilot claim metering_event value_review gate_decision',
'S14':'evidence_bundle notification',
'UX01':'saved_view ui_preference',
'PER_SERVICE':'schema_migration command outbox audit_event application_worker_binding application_job application_job_event'}
table_owner={n:sid for sid,names in owner_lists.items() for n in names.split()}
admin=(SRC/'runtime/src/main/java/org/uacrf/runtime/Admin.java').read_text()
files=sorted((SRC/'database/postgres').glob('*.sql'))+[SRC/p for p in re.findall(r'scripts\.add\(root\.resolve\("([^"]+)"\)\)',admin)]
tables={}
for file in files:
 for name in re.findall(r'CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([\w.]+)',file.read_text(),re.I):tables.setdefault(name,[]).append(str(file.relative_to(SRC)))
tablemap=[]
for name,definitions in sorted(tables.items()):
 schema,local=name.split('.')
 sid='S13' if schema=='uacrf_billing' else 'S02' if schema=='uacrf_relationship_design' else table_owner[local]
 rule='Copy to owner database; retain original IDs/digests and replace foreign-service FKs with scoped references and receipts.'
 if sid=='PER_SERVICE':rule='Split by emitting domain/operation into independent per-service technical tables; do not create a shared runtime database.'
 if local=='analysis_case':rule='Split by context_protocol: UACRF_ANALYSIS_CONTEXT_V1 belongs to S07; UACRF_AGENT_CASE_V1 anchors move to S09. Preserve original IDs and signed source bytes.'
 tablemap.append(dict(source_table=name,target_owner=sid,source_definitions=definitions,migration_rule=rule,status='SOURCE_EXTRACTED_NOT_DATABASE_INTROSPECTED'))
save('inventories/DATA_OWNERSHIP.json',tablemap)
# Exact routes are reflected from the last verified jar by the inventory helper.
routes={}
for line in (BASE/'inventories/REGISTERED_ROUTES.tsv').read_text().splitlines():
 method,path,operation,contract=line.split('\t');routes[method+' '+path]=dict(operation=operation,contract=contract)
assert len(routes)==107
canonical=json.loads((SRC/'api_reference/openapi.json').read_text()); operations={}
for path,value in canonical['paths'].items():
 for method,op in value.items():
  if method.upper() in ['GET','POST','PUT','PATCH','DELETE']:operations[method.upper()+' '+path]=dict(canonical=True,source_contract='api_reference/openapi.json',summary=op.get('summary',''))
assert len(operations)==74
progress=json.loads((SRC/'IMPLEMENTATION_PROGRESS.json').read_text()); recorded={o['endpoint']:o for o in progress['operations']+progress['supporting_operations']}
for file in SRC.rglob('openapi*.json'):
 if 'target' in file.parts:continue
 j=json.loads(file.read_text())
 for path,value in j.get('paths',{}).items():
  for method,op in value.items():
   if method.upper() in ['GET','POST','PUT','PATCH','DELETE']:
    key=method.upper()+' '+path
    operations.setdefault(key,dict(canonical=False,source_contract=str(file.relative_to(SRC)),summary=op.get('summary','')))
for key,route in routes.items():operations.setdefault(key,dict(canonical=False,source_contract=route['contract'],summary='Registered runtime supporting operation'))
def api_owner(key):
 p=key.split(' ',1)[1]
 if re.search(r'identity|principals|memberships|owner-candidates|ownership|/me/',p):return 'S01'
 if '/agent-workload-executions/' in p:return 'S12' if p.endswith('/evaluations') else 'S11'
 if re.search(r'/agent-workloads/[^/]+/executions',p):return 'S11'
 if re.search(r'customer-journeys|/services|shared-resources|agent-workloads|^/v1/dependencies',p):return 'S02'
 if re.search(r'connections|discovery|onboarding',p):return 'S03'
 if re.search(r'evidence/bundles',p):return 'S14'
 if re.search(r'evidence|qualification',p) and not re.search(r'customers/qualifications',p):return 'S04'
 if re.search(r'objective|/workloads/',p):return 'S05'
 if re.search(r'graphs|impact:|reconciliations',p):return 'S06'
 if re.search(r'knowledge|retrieval',p):return 'S08'
 if re.search(r'orchestration|agent-cases|agent-outputs|agent-arbitrations|advisory-',p):return 'S09'
 if re.search(r'authorizations|authorization-polic|policies:evaluate|approvals|decision-envelope|decision-replay',p):return 'S10'
 if re.search(r'provider-|workflows',p):return 'S11'
 if re.search(r'validation|recovery-|path-certifications|experiments',p):return 'S12'
 if re.search(r'customers|economics|claims|pilots|metering|value-reviews|/gates/',p):return 'S13'
 if re.search(r'analysis-cases|evaluations|evaluation-rulepacks|evaluation-rule-packs|actions/proposals|/states/',p):return 'S07'
 if '/commands/' in p:return 'EDGE01'
 raise ValueError('Unassigned API '+key)
api=[]
for key,info in sorted(operations.items()):
 owner=api_owner(key)
 api.append(dict(endpoint=key,target_owner=owner,canonical=info['canonical'],summary=info['summary'],source_contract=info['source_contract'],effective_wire_contract=routes.get(key,{}).get('contract',info['source_contract']),runtime_registered=key in routes,current_recorded_status=recorded.get(key,{}).get('status','INHERITED_RELEASE_OR_SOURCE_ONLY'),source_operation=routes.get(key,{}).get('operation'),target_microservice_status='NOT_IMPLEMENTED',migration_wave={'S01':1,'S02':1,'S03':2,'S04':2,'S05':2,'S06':2,'S07':3,'S08':3,'S09':3,'S10':4,'S11':5,'S12':5,'S13':6,'S14':2,'EDGE01':1}[owner]))
save('inventories/API_OWNERSHIP.json',api)
reqs=json.loads((SRC/'database/REQUIREMENT_DATABASE_MAP.json').read_text());reqmap=[]
for req in reqs:
 owners=set()
 for endpoint in req.get('canonical_endpoints',[]):
  if endpoint in operations:owners.add(api_owner(endpoint))
 for table in req.get('persistence_tables',[]):
  match=next((t for t in tablemap if t['source_table']==table or t['source_table'].split('.')[-1]==table),None)
  if match and match['target_owner']!='PER_SERVICE':owners.add(match['target_owner'])
 # Nonfunctional requirements apply to the platform even where their current SQL map is empty.
 if req['requirement_id'].startswith(('NFR-','METH-')): owners.update(s[0] for s in services)
 if not owners:
  family=req['requirement_id'].split('-')[1]
  fallback={'LRN':['S07','S12','S14'],'QNT':['S07','S04'],'ADP':['S07','S09','S10']}
  owners.update(fallback[family])
 reqmap.append(dict(requirement_id=req['requirement_id'],source_document=req['source_document'],source_section=req['source_section'],target_services=sorted(owners),coverage='DESIGN_ALLOCATION_NOT_ACCEPTANCE',source_acceptance=req.get('full_requirement_status','NOT_EXECUTED')))
assert len(reqmap)==131 and len({r['requirement_id'] for r in reqmap})==131
# Copy exact normative wording from the controlled source, without rewriting it.
from docx import Document
normative={}
source_doc=SRC/'00_Technical_and_Schema/UACRF_Technical_Approach_Design_v1_0_with_Complete_Graph_Schema.docx'
for table in Document(source_doc).tables:
 for row in table.rows:
  values=[c.text for c in row.cells]
  if values and values[0] in {r['requirement_id'] for r in reqmap}:
   normative[values[0]]=values
awl={x['requirement_id']:x['specification'] for x in json.loads((SRC/'workload_contracts/REQUIREMENTS.json').read_text())}
primary={'CAT':'S02','GPH':'S06','ING':'S03','RUL':'S07','RSK':'S07','POL':'S10','APR':'S10','WFL':'S11','VAL':'S12','EVD':'S04','LRN':'S07','ONB':'S03','PRD':'S13','TST':'S12','UI':'UX01','QNT':'S07','ADP':'S07','AGT':'S09','IP':'S13','KNO':'S08','ORC':'S09'}
awl_primary=['S02','S02','S06','S11','S11','S11','S11','S10','S11','S12','S10','S13']
for req in reqmap:
 rid=req['requirement_id']
 req['primary_service']=awl_primary[int(rid[-3:])-1] if rid.startswith('FR-AWL-') else primary[rid.split('-')[1]] if rid.startswith('FR-') else 'CROSS_CUTTING'
 req['accountable_design_owner']='Owning domain team' if rid.startswith('FR-') else 'Platform engineering and assurance'
 if req['primary_service']!='CROSS_CUTTING':req['target_services']=sorted(set(req['target_services']+[req['primary_service']]))
 req['source_row_verbatim']=normative.get(rid,[])
 if rid in awl:
  req['source_json_specification_verbatim']=awl[rid]
  req['normative_source']='workload_contracts/REQUIREMENTS.json'

 req['normative_text_status']='COPIED_SOURCE_ROW' if req['source_row_verbatim'] else 'COPIED_JSON_SPECIFICATION' if rid in awl else 'REFER_TO_CONTROLLED_SOURCE'
save('inventories/REQUIREMENT_ALLOCATION.json',reqmap)
scenarios=[]
for scenario in json.loads((SRC/'verification/integration_scenarios.json').read_text()):
 row=dict(scenario);row['target_services']=sorted({o for req in reqmap if req['requirement_id'] in scenario['requirement_ids'] for o in req['target_services']});row['microservice_execution_status']='NOT_EXECUTED';scenarios.append(row)
save('inventories/ACCEPTANCE_SCENARIOS.json',scenarios)
# Preserve the original historical 47 identities, not an inferred list of the present44.
original=json.loads((SRC/'IMPLEMENTATION_PROGRESS.json').read_text())
remaining=[r for r in original['operations'] if r['status'] in ['NOT_IMPLEMENTED','PARTIALLY_IMPLEMENTED']]
assert len(remaining)==44
for row in api: row['current_recorded_status']=recorded.get(row['endpoint'],{}).get('status','INHERITED_RELEASE_OR_SOURCE_ONLY')
original47=re.findall(r'\d+\. \*\*((?:GET|POST|PUT|DELETE) /v1/[^*]+)\*\*', (ROOT/'exports/REMAINING_47_OPERATIONS.md').read_text())
assert len(original47)==47
save('inventories/ORIGINAL_47_DISPOSITION.json',[dict(original_number=i+1,endpoint=ep,target_owner=api_owner(ep),recorded_status=recorded.get(ep,{}).get('status'),runtime_registered=ep in routes,microservice_status='NOT_IMPLEMENTED') for i,ep in enumerate(original47)])
source_files={str(p.relative_to(SRC)):sha(p) for p in files+[SRC/'runtime/pom.xml',SRC/'runtime/src/main/java/org/uacrf/runtime/Routes.java',SRC/'IMPLEMENTATION_PROGRESS.json',SRC/'runtime/evidence/development/advisory-pending/STATUS.json']}
save('inventories/SOURCE_BASELINE.json',dict(version='1.0',architecture_revision='MS-01',source='development/UACRF_v1_0_application',source_jar_sha256=sha(SRC/'runtime/target/uacrf-runtime-1.0.0.jar'),runtime_registered_routes=107,recorded_routes=99,recorded_full_operations=97,recorded_unclosed_original_operations=44,canonical_operations=74,unique_discovered_operations=len(api),source_declared_tables=len(tablemap),source_classes=len(list(SRC.glob('runtime/src/main/java/**/*.java'))),requirements=131,planned_full_system_scenarios=39,source_hashes=source_files,limitations=['Source extraction is not live database introspection','Published paper and historical releases preserved']))
for filename,data in [('API_OWNERSHIP',api),('DATA_OWNERSHIP',tablemap),('REQUIREMENT_ALLOCATION',reqmap)]:
 keys=list(dict.fromkeys(k for row in data for k in row));
 with (BASE/'inventories'/f'{filename}.csv').open('w',newline='') as f:
  w=csv.DictWriter(f,fieldnames=keys);w.writeheader();w.writerows({k:json.dumps(v) if isinstance(v,(dict,list)) else v for k,v in row.items()} for row in data)
print(json.dumps({'services':len(rows),'api_operations':len(api),'tables':len(tablemap),'requirements':len(reqmap),'scenarios':len(scenarios)}))
