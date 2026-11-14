from pathlib import Path
import json,re,xml.etree.ElementTree as ET
from html.parser import HTMLParser
B=Path(__file__).resolve().parents[1]
checks=[];failures=[]
def check(name,condition):
 if condition:checks.append(name)
 else:failures.append(name)
def load(p):return json.loads((B/p).read_text())
services={s['service_id'] for s in load('SERVICE_CATALOG.json')['services']}
api=load('inventories/API_OWNERSHIP.json');tables=load('inventories/DATA_OWNERSHIP.json');req=load('inventories/REQUIREMENT_ALLOCATION.json');original=load('inventories/ORIGINAL_47_DISPOSITION.json');extra=load('TARGET_API_ADDITIONS.json')
check('Fourteen domains and two edge components',len(services)==16)
check('Every existing API has one valid owner',len(api)==144 and all(a['target_owner'] in services for a in api))
check('Exactly74 canonical existing operations',sum(a['canonical'] for a in api)==74)
check('All107 inherited route records allocated',sum(a['runtime_registered'] for a in api)==107)
check('All153 inherited table records allocated',len(tables)==153 and all(t['target_owner'] in services|{'PER_SERVICE'} for t in tables))
check('Polymorphic analysis case split explicit',any(t['source_table']=='uacrf.analysis_case' and 'UACRF_AGENT_CASE_V1' in t['migration_rule'] for t in tables))
check('All131 unique formal requirement IDs allocated',len(req)==131 and len({r['requirement_id'] for r in req})==131)
check('Every formal requirement retains normative source wording',all(r.get('source_row_verbatim') or r.get('source_json_specification_verbatim') for r in req))
check('Every formal requirement has accountable owner and known target services',all(r['accountable_design_owner'] and set(r['target_services'])<=services for r in req))
check('Original47 identities preserved',len(original)==47 and len({r['endpoint'] for r in original})==47)
check('Thirty-nine inherited scenarios remain unexecuted in target',len(load('inventories/ACCEPTANCE_SCENARIOS.json'))==39 and all(x['microservice_execution_status']=='NOT_EXECUTED' for x in load('inventories/ACCEPTANCE_SCENARIOS.json')))
check('Target additions separately labelled with no duplicate existing route',all(a['status']=='PROPOSED_NOT_IMPLEMENTED' and a['method']+' '+a['path'] not in {x['endpoint'] for x in api} for a in extra))
check('All target routes have an owner',all(a['owner'] in services for a in extra))
check('All event producers and consumers are known services',all(e['producer'] in services and set(e['consumers'])<=services for e in load('EVENT_CATALOG.json')))
check('No event claims operational authorization',all(e['authority']=='NOT_AN_EXECUTION_PERMIT' for e in load('EVENT_CATALOG.json')))
for p in (B/'diagrams').glob('*.svg'):ET.parse(p)
check('All twelve diagrams have SVG PNG and editable source',len(list((B/'diagrams').glob('*.svg')))==12 and all(p.with_suffix('.mmd').exists() and p.with_suffix('.png').exists() for p in (B/'diagrams').glob('*.svg')))
broken=[]
for p in B.glob('*.md'):
 for target in re.findall(r'!?\[[^]]*\]\(([^)]+)\)',p.read_text()):
  if '://' not in target and not target.startswith('#') and not (p.parent/target.split('#')[0]).exists():broken.append((p.name,target))
check('All Markdown local links resolve',not broken)
class Links(HTMLParser):
 def __init__(self):super().__init__();self.links=[]
 def handle_starttag(self,tag,attrs):
  for k,v in attrs:
   if k in ['href','src'] and v:self.links.append(v)
links=Links();links.feed((B/'index.html').read_text())
missing=[v for v in links.links if '://' not in v and not v.startswith('#') and not (B/v.split('#')[0]).exists()]
# Document editions, manifest, and QA output are generated after rendering and packaging.
missing=[v for v in missing if v not in ['UACRF_Microservices_Architecture_v1_0.pdf','UACRF_Microservices_Architecture_v1_0.docx','MANIFEST.json','qa/ARCHITECTURE_CHECKS.json']]
check('Reading library links resolve at prepackaging stage',not missing)
refs=[]
for p in (B/'contracts/source').rglob('*.json'):
 document=json.loads(p.read_text())
 def walk(n):
  if isinstance(n,dict):
   if '$ref' in n:
    ref=n['$ref'];parts=ref.split('#',1)
    if ':' not in parts[0]:
     q=(p.parent/parts[0]).resolve() if parts[0] else p
     if not q.exists():refs.append(dict(file=str(p.relative_to(B)),reference=ref,reason='MISSING_FILE'))
     elif len(parts)==2 and parts[1]:
      try:
       obj=json.loads(q.read_text())
       for k in parts[1].strip('/').split('/'):obj=obj[k.replace('~1','/').replace('~0','~')]
      except (KeyError,TypeError):refs.append(dict(file=str(p.relative_to(B)),reference=ref,reason='MISSING_POINTER'))
   for value in n.values():walk(value)
  elif isinstance(n,list):
   for value in n:walk(value)
 walk(document)
(B/'qa/SOURCE_CONTRACT_FINDINGS.json').write_text(json.dumps(refs,indent=2)+'\n')
if failures:status='FAIL'
elif refs:status='PASS_WITH_CONTRACT_FINDINGS'
else:status='PASS'
result=dict(version='1.0',architecture_revision='MS-01',status=status,scope='Checked-in MS01 design artifact consistency; target runtime acceptance is not evaluated',checks=checks,check_count=len(checks),failed_checks=failures,source_contract_reference_findings=len(refs),target_runtime_acceptance='NOT_EXECUTED')
(B/'qa/ARCHITECTURE_CHECKS.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:v for k,v in result.items() if k!='checks'}))
if failures:raise SystemExit(1)
