from pathlib import Path
import json,copy,warnings
from datetime import datetime
warnings.filterwarnings('ignore',category=DeprecationWarning)
from jsonschema import Draft202012Validator,RefResolver,FormatChecker
warnings.filterwarnings('ignore',category=DeprecationWarning)
d=Path(__file__).resolve().parent;r=d.parent;schemas={p.resolve().as_uri():json.loads(p.read_text()) for base in [d,r/'objective_design'] for p in base.glob('*.schema.json')};checker=FormatChecker();checks=0
@checker.checks('date-time',raises=ValueError)
def timestamp(v):
 if not isinstance(v,str):return True
 return datetime.fromisoformat(v.replace('Z','+00:00')).tzinfo is not None

def validator(name):
 p=d/(name+'.schema.json');s=json.loads(p.read_text());return Draft202012Validator(s,resolver=RefResolver(base_uri=p.resolve().as_uri(),referrer=s,store=schemas),format_checker=checker)
def check(v):
 global checks
 assert v
 checks+=1
fixtures=json.loads((d/'SCHEMA_FIXTURES.json').read_text())['fixtures']
check(len(list(d.glob('*.schema.json')))==9)
for name,fixture in fixtures.items():
 s=json.loads((d/(name+'.schema.json')).read_text());Draft202012Validator.check_schema(s);v=validator(name);check(v.is_valid(fixture))
 for key in s['required']:
  bad=copy.deepcopy(fixture);bad.pop(key);check(not v.is_valid(bad))
 bad={**fixture,'actor_id':'self-appointed-admin'};check(not v.is_valid(bad))
policy=fixtures['collection-policy'];check(not validator('collection-policy').is_valid({**policy,'delivery':'EVENTS','poll_seconds':30}));check(not validator('collection-policy').is_valid({**policy,'signal_kind':'OBJECTIVE_DEFINITION','freshness_basis':'OBSERVED_AT'}));check(not validator('collection-policy').is_valid({**policy,'urgent_bypasses_debounce':False}))
check(not validator('collector-signal-envelope').is_valid({**fixtures['collector-signal-envelope'],'quality':'QUALIFIED'}))
check(not validator('normalized-objective-definition').is_valid({**fixtures['normalized-objective-definition'],'minimum':0,'maximum':1000}))
check(not validator('normalized-objective-definition').is_valid({**fixtures['normalized-objective-definition'],'measurement_kind':'PERCENTILE','percentile':None}))
check(not validator('objective-import-propose').is_valid({**fixtures['objective-import-propose'],'choice':'KEEP_LOCAL'}))
check(not validator('external-objective-origin').is_valid({**fixtures['external-objective-origin'],'source_checked_at':'tomorrow'}))
for p in json.loads((d/'TIMING_EXAMPLES.json').read_text())['policies']:check(validator('collection-policy').is_valid(p))
check(not validator('collection-policy').is_valid({**policy,'pre_dispatch_checks_required':False}))
check(not validator('objective-import-candidate').is_valid({**fixtures['objective-import-candidate'],'normalized_definition':None}))
check(not validator('objective-import-candidate').is_valid({**fixtures['objective-import-candidate'],'scope_complete':False,'status':'WITHDRAWN'}))
check(not validator('objective-import-candidate').is_valid({**fixtures['objective-import-candidate'],'status':'WITHDRAWN'}))
api=json.loads((d/'openapi.proposed.json').read_text());check(sum(len(x) for x in api['paths'].values())==8)
for path,methods in api['paths'].items():
 for method,op in methods.items():
  check('PROPOSED' in op['description']);check(bool(api['security']))
  if method!='get':check(any(p['name']=='Idempotency-Key' and p['required'] for p in op['parameters']))
# Every local API/schema reference resolves inside the current release.
def refs(value):
 if isinstance(value,dict):
  for k,v in value.items():
   if k=='$ref':yield v
   else:yield from refs(v)
 elif isinstance(value,list):
  for v in value:yield from refs(v)
for p in list(d.glob('*.schema.json'))+[d/'openapi.proposed.json',r/'objective_design/objective-revision.schema.json']:
 for value in refs(json.loads(p.read_text())):
  if not value.startswith('#'):check((p.parent/value.split('#')[0]).resolve().is_file())
result={'version':'1.0','status':'PASS_FINITE_SCHEMA_AND_CONTRACT_CHECKS','checks':checks,'schemas':9,'proposed_operations':8,'storage_record_families':7,'planned_acceptance_scenarios':22,'extension_promoted':False,'engine_and_live_acceptance':'NOT_EXECUTED','browser_acceptance':'NOT_EXECUTED','score_change':0};(r/'verification/IMPORT_SIGNAL_SCHEMA_RESULTS.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
