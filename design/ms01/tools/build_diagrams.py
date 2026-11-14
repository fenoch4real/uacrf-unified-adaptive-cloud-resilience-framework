from pathlib import Path
import json,html,textwrap
B=Path(__file__).resolve().parents[1];D=B/'diagrams'
def esc(v):return html.escape(str(v),quote=True)
def start(w,h,title):return [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" role="img" aria-label="{esc(title)}"><defs><marker id="arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L0,6 L9,3 z" fill="#45617d"/></marker></defs><rect width="100%" height="100%" fill="#f7f9fc"/><g font-family="Arial, sans-serif" fill="#152b43">',f'<text x="35" y="44" font-size="28" font-weight="700">{esc(title)}</text>']
def text(s,x,y,label,width=42,size=19,color='#152b43'):
 for i,line in enumerate(textwrap.wrap(label,width=width)):
  s.append(f'<text x="{x}" y="{y+i*(size+5)}" font-size="{size}" fill="{color}">{esc(line)}</text>')
def box(s,x,y,w,h,title,body='',fill='#fff'):
 s.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{fill}" stroke="#bbcad9"/>');text(s,x+15,y+28,title,max(15,int((w-30)/10)),20)
 if body:text(s,x+15,y+57+max(0,len(textwrap.wrap(title,width=max(15,int((w-30)/10))))-1)*25,body,max(15,int((w-30)/9.5)),18)
def line(s,x,y,x2,y2):s.append(f'<path d="M{x},{y} L{x2},{y2}" fill="none" stroke="#45617d" stroke-width="2" marker-end="url(#arrow)"/>')
def end(s,name):s.append('</g></svg>');(D/(name+'.svg')).write_text('\n'.join(s))
services=json.loads((B/'SERVICE_CATALOG.json').read_text())['services']
s=start(1000,1000,'UACRF service architecture')
box(s,35,78,440,105,'One browser application','UX01 behind the edge composes domain APIs.','#e8f2ff');box(s,525,78,440,105,'One public API edge','EDGE01 accepts browser and API transport.','#e8f2ff')
line(s,525,130,477,130);text(s,35,223,'Fourteen domain services with exclusive data ownership',70,22)
for i,svc in enumerate(services[:14]):
 x=35+(i%3)*320;y=250+(i//3)*110
 box(s,x,y,295,95,svc['service_id']+' '+svc['title'])
text(s,35,825,'Isolated workers',70,23)
for i,(title,body) in enumerate([('Collection','Read-only source credentials'),('Advisory runtimes','Bounded model context and journal'),('Execution cells','Action-specific permits and credentials')]):box(s,35+i*320,855,295,112,title,body,'#e8f2ff')
end(s,'system_context')
(D/'system_context.mmd').write_text('flowchart TD\n  Customer[Customer] --> EDGE01[API edge]\n  EDGE01 --> UX01[Unified experience API]\n'+''.join(f'  EDGE01 --> {v["service_id"]}["{v["service_id"]} {v["title"]}"]\n' for v in services[:14])+'  S03 --> Collection[Read only collection workers]\n  S09 --> Agents[Bounded advisory runtimes]\n  S11 --> Executors[Isolated execution cells]\n')
s=start(1000,920,'Owned data and verified projections')
text(s,35,84,'Every owner has its own database, credentials and migrations.',80,21)
for i,(ids,title,body) in enumerate([('S01 S02 S03 S04 S05','Identity inventory and source records','Owned PostgreSQL databases'),('S06 S07 S08 S09 S10','Graph assessment knowledge and decisions','Owned PostgreSQL databases'),('S11 S12 S13 S14 UX01','Execution assurance money audit and UI preferences','Owned PostgreSQL databases')]):box(s,35,125+i*130,930,108,title,ids+' | '+body)
box(s,35,550,440,140,'Neo4j is owned by S06','Accepted PostgreSQL graph material -> leased projection -> verified content.','#e8f2ff')
box(s,525,550,440,140,'Artifacts have domain owners','Immutable bytes and digests; every read needs current owner disclosure.','#e8f2ff')
box(s,35,735,930,125,'No cross service SQL or distributed foreign keys','Use scoped references, owner receipts, revisions and digests. Commands, inbox, outbox and local audit exist per service.')
end(s,'data_ownership')
(D/'data_ownership.mmd').write_text('flowchart LR\n  Owners[Domain services] --> PG[Separate owned PostgreSQL databases]\n  S06[Topology service] --> GraphPG[Accepted graph material in PostgreSQL]\n  GraphPG --> Projection[Leased verified projection]\n  Projection --> Neo4j[Private Neo4j]\n  Owners --> Objects[Domain scoped immutable artifacts]\n  Owners --> Outbox[Local outbox]\n  Outbox --> Kafka[Kafka]\n  Kafka --> Inbox[Consumer local inbox and projection]\n')
flows={
'execution_sequence':('Authorized execution and independent validation',['User and S11','S10 governance','Owner APIs','Executor','Provider','S12 validation'],[(0,1,'1 Request exact plan and context'),(1,2,'2 Read current source and access receipts'),(2,1,'3 Return bounded current owner facts'),(1,0,'4 Reserve one bound attempt'),(0,3,'5 Deliver plan and reservation'),(3,1,'6 Current permit and fence check'),(3,4,'7 Check provider preconditions'),(3,3,'8 Persist DISPATCHING attempt'),(3,4,'9 Dispatch conditional request'),(4,3,'10 Return observed effect or uncertainty'),(3,0,'11 Retain outcome and request identity'),(0,5,'12 Request independent mandatory probes'),(5,0,'13 Return observed validation evidence'),(0,1,'14 Retain final justified disposition')],'Denied, stale or unavailable current authority stops new dispatch. A lost provider response stays Unknown until reconciliation.'),
'event_delivery':('Transactional event delivery',['Producer','Owner DB','Kafka','Consumer','Consumer DB'],[(0,1,'1 Commit business change audit and outbox'),(1,0,'2 Return durable owner result'),(0,2,'3 Publish leased outbox event'),(2,0,'4 Acknowledge publication'),(2,3,'5 Deliver event at least once'),(3,4,'6 Commit inbox key and local projection'),(4,3,'7 Return committed consumer result'),(3,2,'8 Acknowledge after local commit')],'Crash after publish can duplicate delivery. Inbox deduplication handles it; conflicts are quarantined, not silently overwritten.'),
'onboarding':('Customer onboarding and objectives',['Customer UI','S01 and S02','S03 and S04','S05','S06 and S07'],[(0,1,'1 Admit people owners and workloads'),(0,2,'2 Enroll read-only source connection'),(2,1,'3 Propose discovered inventory links'),(0,1,'4 Link services agents and shared resources'),(0,3,'5 Propose or resolve imported objectives'),(3,3,'6 Independent review and activation'),(2,4,'7 Supply qualified source observations'),(1,4,'8 Accept and project typed dependencies'),(4,0,'9 Show actual setup and evaluation results')],'An unresolved import, missing owner, stale source or unavailable prerequisite remains visible. Screen visits do not complete setup.'),
'observe_detect':('Observe detect and retain findings',['Source S03','Evidence S04','Objectives S05','Topology S06','Assessment S07'],[(0,1,'1 Submit observation with source time'),(1,1,'2 Verify bytes and qualify under exact policy'),(1,4,'3 Publish qualified observation trigger'),(4,2,'4 Read current approved target set'),(4,3,'5 Read exact accepted graph receipt'),(4,4,'6 Reverify required artifacts and seal case'),(4,4,'7 Compare exact measurements and targets'),(4,4,'8 Retain findings and advisory recommendations')],'Late arrival cannot refresh old measurements. Missing or stale input remains Unknown; a risk classifier is not a forecast.'),
'advisory_sequence':('Bounded advisory agent workflow',['Owner UI','S09','S02 S07 S08','Runtime','Independent reviewer'],[(0,1,'1 Choose fixed case guidance and roles'),(1,2,'2 Verify active profiles and same-purpose sources'),(1,1,'3 Reserve one attempt per required role'),(3,1,'4 Claim only assigned input once'),(1,3,'5 Issue exact verified bounded packet'),(3,3,'6 Journal intent before model invocation'),(3,1,'7 Submit output and every dissent record'),(4,1,'8 Review exact revision and complete outputs'),(1,2,'9 Reverify current source and actual files'),(1,4,'10 Retain advisory review and rationale')],'Unknown model usage stays null. Missing or abstaining roles block; dissent is preserved. Cancellation retains late ineligible output.'),
'recovery_validation':('Recovery evidence and certification',['Execution S11','Assurance S12','Probe worker','Evidence S04','Decision S10'],[(0,1,'1 Request exact validation after effect'),(1,3,'2 Verify current approved probe policies'),(1,2,'3 Lease bounded independent probes'),(2,3,'4 Retain actual observations and provenance'),(2,1,'5 Submit fenced probe results'),(1,1,'6 Check every mandatory recovery criterion'),(1,4,'7 Publish justified validation and certification'),(4,0,'8 Use current outcome in subsequent decisions')],'Provider success alone is not recovery. Missing, stale or failed mandatory evidence prevents certification.'),
'billing_sequence':('Usage to invoice and reconciliation',['Domain owner','S13 admission','S13 rating','Payment provider','Customer UI'],[(0,1,'1 Submit immutable measured usage receipt'),(1,1,'2 Verify source scope metric unit and identity'),(1,2,'3 Admit once under frozen contract'),(2,2,'4 Apply exact price allowance and currency rules'),(2,2,'5 Post immutable invoice'),(2,3,'6 Request collection with stable payment identity'),(3,1,'7 Deliver deduplicated provider event'),(1,2,'8 Reconcile or adjust payment'),(2,4,'9 Show attributable invoice and economics')],'Unknown completion is not invented usage. A reservation is not a charge. Credits and corrections retain the original posted facts.'),
'migration':('One owner migration procedure',['Old owner','Migration','New owner','Route edge'],[(0,1,'1 Freeze scope and retain baseline hashes'),(1,2,'2 Deploy read-only and compare responses'),(1,0,'3 Stop new writes and fence old workers'),(0,2,'4 Snapshot and replay to accepted watermark'),(1,2,'5 Reconcile rows digests artifacts and events'),(1,3,'6 Switch one route ownership epoch'),(3,2,'7 Allow only new owner writes'),(1,2,'8 Verify UI and repair or finalize cutover')],'After accepted new writes, rollback needs reconciled reverse migration or forward repair. Routing alone cannot restore lost facts.')}
flows['execution_overview']=('Execution admission and outcome review',['S11 workflow','S10 authority','Source owners','Executor','Provider','S12 validation'],[(0,1,'1 Request bound authorization'),(1,2,'2 Verify current owner receipts'),(1,0,'3 Reserve one exact attempt'),(3,1,'4 Revalidate permit and fence'),(3,3,'5 Retain dispatch intent'),(3,4,'6 Apply conditional provider action'),(0,5,'7 Reconcile effect and request probes'),(5,1,'8 Retain justified validation outcome')],'Denial stops dispatch. An uncertain provider effect stays Unknown. Only observed independent validation can establish recovery.')
for name,(title,participants,steps,note) in flows.items():
 rowheight=90 if len(participants)>5 else 77
 height=150+len(steps)*rowheight+150;s=start(1000,height,title);n=len(participants);xs=[85+i*(830/(n-1)) for i in range(n)]
 for x,label in zip(xs,participants):
  text(s,x-75,85,label,15,19);s.append(f'<line x1="{x}" y1="120" x2="{x}" y2="{height-140}" stroke="#c2cfdb" stroke-dasharray="5 5"/>')
 for i,(a,b,label) in enumerate(steps):
  y=150+i*rowheight
  if a==b:
   x=min(675,max(25,xs[a]-140));s.append(f'<rect x="{x}" y="{y-20}" width="300" height="70" rx="8" fill="#e8f2ff" stroke="#bbcad9"/>');text(s,x+12,y+1,label,29,19)
  else:
   x1=xs[a];x2=xs[b];line(s,x1,y+70 if n>5 else y+60,x2,y+70 if n>5 else y+60);labelx=max(20,min(x1,x2)+8);text(s,labelx,y,label,max(17,int(abs(x2-x1)/10)),19)
 text(s,35,height-100,note,87,19)
 end(s,name)
 m=['sequenceDiagram']+[f'  participant P{i} as {p}' for i,p in enumerate(participants)]
 m+=['  alt Required current prerequisites hold']+[f'    P{a}->>P{b}: {label}' for a,b,label in steps]
 m+=['  else A prerequisite fails or an external outcome is uncertain',f'    Note over P0,P{n-1}: {note}','  end']
 (D/(name+'.mmd')).write_text('\n'.join(m)+'\n')
# Deployment view deliberately avoids suggesting a shared domain database.
s=start(1000,830,'Deployment and privilege boundaries')
for y,title,body in [(90,'Ingress and experience','Browser -> EDGE01 -> UX01 -> authenticated domain APIs'),(230,'Control plane service pools','S01 through S10 and S12 through S14; separate identities and databases'),(370,'Collection and model worker pools','S03 read-only source adapters; S09 bounded advisory model clients'),(510,'Tenant isolated execution cells','S11 uses action-specific permits and short-lived target credentials'),(650,'Managed state and independent operations','Owned PostgreSQL databases, private Neo4j, Kafka, object storage, secret store and telemetry')]:box(s,35,y,930,112,title,body,fill='#e8f2ff' if y in [90,510] else '#fff')
end(s,'deployment')
(D/'deployment.mmd').write_text('flowchart TD\n Browser --> Edge[API edge]\n Edge --> UX[Experience API]\n UX --> Control[Authenticated domain services]\n Edge --> Control\n Control --> DB[Owned logical databases]\n Control --> Bus[Kafka]\n Control --> Storage[Owner scoped object storage]\n S06[Topology] --> Neo4j[Private Neo4j]\n Control --> Collection[Read only collection workers]\n Control --> Advisory[Bounded model workers]\n Control --> Execution[Isolated privileged execution cells]\n')
print('Created',len(list(D.glob('*.svg'))),'SVG diagrams with editable Mermaid companions')
