from pathlib import Path
from lxml import etree
import unittest,json,csv,io,re,zipfile,hashlib,sys,importlib.util

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT if (ROOT/'alignment/BUILD_COMPLETE.json').exists() else ROOT/'releases/UACRF_v1_0_aligned'
NS={'w':'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
sys.path.insert(0,str(OUT/'reference_implementation'))

def docxml(path):
    with zipfile.ZipFile(path) as z:return etree.fromstring(z.read('word/document.xml'))
def doctext(path):return '\n'.join(docxml(path).xpath('//w:t/text()',namespaces=NS))

class PackageChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.docs=[p for p in OUT.rglob('*.docx') if p.parent.name in {'00_Technical_and_Schema','01_Patent_Specifications'}]  # Nine normative technical/disclosure documents; UI addenda checked separately.
        cls.main=next(p for p in cls.docs if 'Technical_Approach' in p.name)
        cls.schema=json.loads((OUT/'00_Technical_and_Schema/data_dictionary/schema.json').read_text())
        cls.roles=json.loads((OUT/'00_Technical_and_Schema/data_dictionary/endpoint_roles.json').read_text())['roles']
        cls.rows=json.loads((OUT/'alignment/requirements_traceability.json').read_text())
    def test_A01_nine_versions(self):
        self.assertEqual(len(self.docs),9)
        for p in self.docs:
            t=doctext(p);self.assertRegex(t,r'Version\s+1\.0');self.assertNotRegex(t,r'(?i)\bversion\s+3\.[12]\b')
    def test_A02_paper_cited_all_nine(self):
        for p in self.docs:self.assertIn('nid/njbei.2026.0001',doctext(p));self.assertIn('Nahda Journal',doctext(p))
    def test_A03_no_dangling_section_77(self):
        for p in self.docs:self.assertNotRegex(doctext(p),r'\b7\.7\b')
    def test_A04_seven_patent_filenames_resolve(self):
        t=doctext(self.main);names=set(re.findall(r'\b\d{2}_[a-z_]+_v1_0\.docx',t));self.assertEqual(len(names),7)
        for n in names:self.assertTrue((OUT/'01_Patent_Specifications'/n).exists())
    def test_A05_published_bytes_unchanged(self):
        copy=next((OUT/'03_Published_Foundation').glob('*.pdf'))
        if (ROOT/'references/published').exists():
            source=next((ROOT/'references/published').glob('*.pdf'));self.assertEqual(source.read_bytes(),copy.read_bytes())
        else:
            expected=json.loads((OUT/'alignment/published_source_manifest.json').read_text())['sha256']
            self.assertEqual(hashlib.sha256(copy.read_bytes()).hexdigest(),expected)
    def test_A06_exact_formal_definition_coverage(self):
        ids=set()
        for tr in docxml(self.main).xpath('//w:tr',namespaces=NS):
            cells=[' '.join(c.xpath('.//w:t/text()',namespaces=NS)) for c in tr.findall('w:tc',NS)]
            if cells and re.fullmatch(r'(?:FR|NFR)-[A-Z]+-\d{3}|METH-\d{3}',cells[0]):ids.add(cells[0])
        paragraphs=[''.join(p.xpath('.//w:t/text()',namespaces=NS)) for p in docxml(self.main).xpath('//w:p',namespaces=NS)]
        for i,t in enumerate(paragraphs):
            if re.fullmatch(r'FR-AWL-\d{3}',t):
                ids.add(t);self.assertEqual(paragraphs[i+1],next(r['specification'] for r in self.rows if r['requirement_id']==t))
        self.assertEqual(ids,{r['requirement_id'] for r in self.rows});self.assertEqual(len(ids),131)
    def test_A07_unique_scenario_ids_and_honest_status(self):
        self.assertEqual(len({r['planned_test_id'] for r in self.rows}),131)
        for r in self.rows:self.assertEqual(r['verification_status'],'NOT_EXECUTED');self.assertEqual(r['review_status'],'Pending user/domain review')
    def test_A08_trace_artifact_links_exist(self):
        for r in self.rows:
            self.assertTrue((OUT/r['source_document']).exists())
            for link in r['artifact_links']:self.assertTrue((OUT/link).exists(),link)
    def test_A09_catalog_counts_unique(self):
        self.assertEqual(len(self.schema['nodes']),58);self.assertEqual(len({n['label'] for n in self.schema['nodes']}),58)
        self.assertEqual(len(self.schema['relationships']),55);self.assertEqual(len({r['type'] for r in self.schema['relationships']}),55)
    def test_A10_endpoint_roles_exhaustive(self):
        labels={n['label'] for n in self.schema['nodes']};abstract={r[k] for r in self.schema['relationships'] for k in ['source','target']}-labels
        self.assertEqual(set(self.roles),abstract)
        for role,values in self.roles.items():self.assertTrue(values);self.assertTrue(set(values)<=labels)
    def test_A11_csv_json_field_parity(self):
        base=OUT/'00_Technical_and_Schema/data_dictionary'
        nodes=list(csv.DictReader((base/'nodes.csv').open()));rels=list(csv.DictReader((base/'relationships.csv').open()))
        for row,n in zip(nodes,self.schema['nodes']):
            self.assertEqual(row['label'],n['label']);self.assertEqual(row['required_properties'].split(),n['properties']);self.assertEqual(row['common_properties'].split(),self.schema['common_node_properties'])
        for row,r in zip(rels,self.schema['relationships']):
            self.assertEqual(row['relationship'],r['type']);self.assertEqual(row['properties'].split(),r['properties']);self.assertEqual(row['common_properties'].split(),self.schema['common_relationship_properties'])
    def test_A12_environment_service_key(self):
        text=(OUT/'00_Technical_and_Schema/neo4j/01_constraints_and_indexes.cypher').read_text()
        self.assertIn('(n.tenant_id, n.environment_id, n.service_id) IS NODE KEY',text)
    def test_A13_no_unsafe_legacy_runtime_query(self):
        q=(OUT/'00_Technical_and_Schema/neo4j/04_core_queries.cypher').read_text()
        self.assertNotIn('apoc.',q);self.assertNotIn('*1..$',q);self.assertIn('current-state candidate extraction ONLY',q)
        self.assertIn('environment_id',q);self.assertIn('qualification_state',q)
    def test_A14_word_local_hyperlinks_exist(self):
        for p in self.docs:
            with zipfile.ZipFile(p) as z:
                x=etree.fromstring(z.read('word/_rels/document.xml.rels'))
                for rel in x:
                    if rel.get('Type','').endswith('/hyperlink'):
                        target=rel.get('Target','')
                        if target.startswith('../'):self.assertTrue((p.parent/target).exists(),(p,target))
    def test_A15_document_map_includes_later_appendices(self):
        t=doctext(self.main)
        for letter in ['R','S','T','U']:self.assertGreaterEqual(t.count('Appendix '+letter+' '),2)
    def test_A16_p4_and_p6_restrictive_branches(self):
        p4=next(p for p in self.docs if p.name.startswith('04_'));p6=next(p for p in self.docs if p.name.startswith('06_'))
        self.assertIn('incomplete mandatory window',doctext(p4));self.assertIn('duplicate, skipped, timeout, unknown',doctext(p6))
    def test_A17_canonical_API_inventory_complete(self):
        inventory=json.loads((OUT/'alignment/api_inventory.json').read_text());known={r['endpoint'] for r in inventory}
        self.assertEqual(len(known),74)
        for endpoint in known:self.assertIn(' /v1/',endpoint)
        recognized=set(known)
        for contract in OUT.rglob('openapi.proposed.json'):
            proposed=json.loads(contract.read_text())
            recognized.update(method.upper()+' '+path for path,item in proposed.get('paths',{}).items() for method in item if method.lower() in {'get','post','put','patch','delete'})
        for p in self.docs:
            for method,path in re.findall(r'\b(GET|POST|PUT|PATCH|DELETE)\s+(/[A-Za-z0-9_{}:/.-]+)',doctext(p)):
                self.assertIn(method+' '+path.rstrip('.'),recognized)
        entries=set()
        for tr in docxml(self.main).xpath('//w:tr',namespaces=NS):
            cells=[' '.join(c.xpath('.//w:t/text()',namespaces=NS)) for c in tr.findall('w:tc',NS)]
            if cells and re.match(r'^(GET|POST|PUT|PATCH|DELETE) /',cells[0]):entries.add(cells[0])
        entries.update(method+' '+path.rstrip('.') for method,path in re.findall(r'\b(GET|POST|PUT|PATCH|DELETE)\s+(/[A-Za-z0-9_{}:/.-]+)',doctext(self.main)))
        self.assertTrue(known<=entries)
    def test_A18_trace_interfaces_objects_resolve(self):
        known={r['endpoint'] for r in json.loads((OUT/'alignment/api_inventory.json').read_text())};labels={n['label'] for n in self.schema['nodes']}
        for r in self.rows:self.assertTrue(set(r['canonical_endpoints'])<=known);self.assertTrue(set(r['canonical_graph_objects'])<=labels)
    def test_A19_paper_crosswalk_requirement_targets(self):
        rows=json.loads((OUT/'alignment/paper_crosswalk.json').read_text());ids={r['requirement_id'] for r in self.rows}
        self.assertEqual(len(rows),20)
        for r in rows:
            for rid in re.findall(r'(?:FR|NFR)-[A-Z]+-\d{3}',r['requirement_ids']):self.assertIn(rid,ids)
    def test_A20_score_arithmetic(self):
        s=json.loads((OUT/'alignment/scorecard.json').read_text())
        self.assertEqual(sum(d['weight'] for d in s['dimensions']),100)
        self.assertEqual(sum(d['weight']*d['before_level']/4 for d in s['dimensions']),s['before'])
        self.assertEqual(sum(d['weight']*d['after_level']/4 for d in s['dimensions']),s['after'])
    def test_A21_no_dangling_formal_requirement_ids(self):
        ids={r['requirement_id'] for r in self.rows}
        for p in self.docs:
            mentioned=set(re.findall(r'\b(?:FR|NFR)-[A-Z]+-\d{3}\b|\bMETH-\d{3}\b',doctext(p)))
            self.assertTrue(mentioned<=ids,(p,mentioned-ids))
    def test_A22_qualification_field_is_canonical(self):
        n=next(n for n in self.schema['nodes'] if n['label']=='EvidenceQualification')
        self.assertIn('qualification_state',n['properties']);self.assertIn('expires_at',n['properties']);self.assertNotIn('state',n['properties']);self.assertNotIn('expiry',n['properties'])
    def test_A23_original_figures_retained(self):
        inventory=json.loads((OUT/'alignment/drawing_inventory.json').read_text());self.assertEqual(len(inventory),9)
        for r in inventory:
            current=len(docxml(OUT/r['document']).xpath('//w:drawing',namespaces=NS))
            self.assertGreaterEqual(current,r['source_drawings']);self.assertEqual(current,r['current_drawings']);self.assertEqual(current-r['source_drawings'],r.get('added_identity_schema_drawings',0))


    def test_A24_integration_plan_consistency(self):
        scenarios=json.loads((OUT/'verification/integration_scenarios.json').read_text())
        self.assertEqual(len(scenarios),39);self.assertEqual(len({s['scenario_id'] for s in scenarios}),39)
        ids={r['requirement_id'] for r in self.rows};known={s['scenario_id'] for s in scenarios}
        for s in scenarios:
            self.assertTrue(set(s['requirement_ids'])<=ids);self.assertEqual(s['verification_status'],'NOT_EXECUTED')
            self.assertEqual(set(s['provider_targets']),{'GCP','AWS','Azure'})
        for r in self.rows:self.assertTrue(set(r['integration_scenario_ids'])<=known)
        t=doctext(self.main);self.assertIn('18.5 Integration and full system acceptance programme',t);self.assertGreaterEqual(t.count('Appendix V '),2)
        body=docxml(self.main).find('w:body',NS)
        def location(prefix,style):
            return next(i for i,p in enumerate(body) if p.tag.endswith('}p') and ''.join(p.xpath('.//w:t/text()',namespaces=NS)).startswith(prefix) and p.xpath('./w:pPr/w:pStyle/@w:val',namespaces=NS)==[style])
        self.assertLess(location('18 Failure','Heading1'),location('18.5 Integration','Heading2'))
        self.assertLess(location('18.5 Integration','Heading2'),location('19 Metrics','Heading1'))
    def test_A25_learning_and_score_scope(self):
        capabilities=json.loads((OUT/'alignment/provider_capabilities.json').read_text());self.assertEqual(len(capabilities),8)
        for c in capabilities:
            for provider in ['GCP','AWS','Azure']:self.assertTrue(c[provider])
        score=json.loads((OUT/'alignment/scorecard.json').read_text());self.assertEqual(score['after'],87.5);self.assertEqual(score['score_change'],0)
        self.assertEqual(score['production_readiness'],'NOT_ESTABLISHED')
        for link in ['MULTICLOUD_LEARNING_PATH.md','verification/INTEGRATION_TEST_PLAN.md']:
            self.assertTrue((OUT/link).exists())

    def test_A26_knowledge_schemas_and_projection_scope(self):
        for name in ['knowledge','retrieval','orchestration']:
            schema=json.loads((OUT/('agent_contracts/'+name+'.schema.json')).read_text())
            self.assertEqual(schema["type"], "object")
            self.assertEqual(schema["additionalProperties"], False)
            self.assertEqual(set(schema["required"]), set(schema["properties"]))
        self.assertEqual(len(self.schema['nodes']),58)
        self.assertIn('Appendix W Governed knowledge',doctext(self.main))
        for p in self.docs:self.assertNotIn('remain outside UACRF Core',doctext(p))
    def test_A27_preserved_claims_and_old_requirements(self):
        baseline=ROOT.parent/'UACRF_v1_0_narrative'
        # Portable package retains invariant digests computed during revision review.
        invariants=json.loads((OUT/'alignment/REVISION_5_INVARIANTS.json').read_text())
        self.assertEqual(len(invariants['claims']),7)
        for item in invariants['claims']:
            self.assertEqual(item['before_sha256'],item['after_sha256'])
        self.assertEqual(invariants['prior_requirement_definitions_unchanged'],107)
    def test_A28_manifest_coverage_and_contract_mapping(self):
        ids={r['requirement_id'] for r in self.rows}
        self.assertEqual(len([i for i in ids if i.startswith(('FR-KNO-','FR-ORC-'))]),12)
        for p in self.docs:
            self.assertIn('alignment revision 7',doctext(p).lower())
        self.assertEqual(len(json.loads((OUT/'alignment/api_inventory.json').read_text())),74)

class CapturingResult(unittest.TextTestResult):
    def __init__(self,*args,**kwargs):super().__init__(*args,**kwargs);self.cases=[]
    def addSuccess(self,test):super().addSuccess(test);self.cases.append({'test_id':test.id(),'status':'PASS'})
    def addFailure(self,test,err):super().addFailure(test,err);self.cases.append({'test_id':test.id(),'status':'FAIL'})
    def addError(self,test,err):super().addError(test,err);self.cases.append({'test_id':test.id(),'status':'ERROR'})

if __name__=='__main__':
    import test_core_contracts, test_agent_assistance
    # Package trace checks require the reference evidence file to exist.
    v=OUT/'verification';v.mkdir(exist_ok=True)
    ref=unittest.TestSuite([unittest.defaultTestLoader.loadTestsFromModule(test_core_contracts), unittest.defaultTestLoader.loadTestsFromModule(test_agent_assistance)])
    stream=io.StringIO();rr=unittest.TextTestRunner(stream=stream,verbosity=2,resultclass=CapturingResult).run(ref)
    (v/'reference_results.json').write_text(json.dumps({'scope':'Finite-model reference functions only; not full requirement acceptance','tests_run':rr.testsRun,'passed':len(rr.cases)-len(rr.failures)-len(rr.errors),'results':rr.cases,'failures':len(rr.failures),'errors':len(rr.errors)},indent=2))
    pkg=unittest.defaultTestLoader.loadTestsFromTestCase(PackageChecks);pr=unittest.TextTestRunner(stream=stream,verbosity=2,resultclass=CapturingResult).run(pkg)
    (v/'package_results.json').write_text(json.dumps({'scope':'Package structure and documentary alignment checks','tests_run':pr.testsRun,'passed':len(pr.cases)-len(pr.failures)-len(pr.errors),'results':pr.cases,'failures':len(pr.failures),'errors':len(pr.errors)},indent=2))
    (v/'test_output.txt').write_text(stream.getvalue())
    print(stream.getvalue() if not (rr.wasSuccessful() and pr.wasSuccessful()) else f'{rr.testsRun} reference tests passed; {pr.testsRun} package checks passed. System acceptance remains NOT_EXECUTED.')
    sys.exit(0 if rr.wasSuccessful() and pr.wasSuccessful() else 1)
