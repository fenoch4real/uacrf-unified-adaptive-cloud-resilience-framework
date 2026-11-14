from pathlib import Path
import re,html,json
from docx import Document
from docx.shared import Inches,Pt,RGBColor
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT,WD_CELL_VERTICAL_ALIGNMENT
B=Path(__file__).resolve().parents[1]
PARTS=[('ARCHITECTURE.md','Architecture and system behavior'),('SERVICE_DESIGN.md','Detailed service specifications'),('INTERNAL_INTERFACES.md','Internal interfaces and state machines'),('IMPLEMENTATION_PLAN.md','Implementation and migration plan'),('DEPLOYMENT_AND_OPERATIONS.md','Deployment and operating model'),('ACCEPTANCE_AND_RISKS.md','Acceptance and risks'),('DECISIONS.md','Architecture decisions'),('TARGET_API_ADDITIONS.md','Proposed target API additions')]

def blocks(md):
 lines=md.splitlines();i=0
 while i<len(lines):
  line=lines[i].strip()
  if not line:i+=1;continue
  if line.startswith('```'):
   chunk=[];i+=1
   while i<len(lines) and not lines[i].startswith('```'):chunk.append(lines[i]);i+=1
   yield('code','\n'.join(chunk));i+=1;continue
  h=re.match(r'^(#{1,6}) (.+)',line)
  if h:yield('heading',(len(h[1]),h[2]));i+=1;continue
  im=re.match(r'^!\[([^]]*)\]\(([^)]+)\)$',line)
  if im:yield('image',(im[1],im[2]));i+=1;continue
  if line.startswith('|') and i+1<len(lines) and re.match(r'^\|[\s:|\-]+\|$',lines[i+1].strip()):
   rows=[[v.strip() for v in line.strip('|').split('|')]];i+=2
   while i<len(lines) and lines[i].strip().startswith('|'):rows.append([v.strip() for v in lines[i].strip().strip('|').split('|')]);i+=1
   yield('table',rows);continue
  li=re.match(r'^(?:([-*])|\d+\.) (.+)',line)
  if li:yield('list',(bool(li[1]),li[2]));i+=1;continue
  chunk=[line];i+=1
  while i<len(lines) and lines[i].strip() and not re.match(r'^(#|\||```|[-*] |\d+\. |!\[)',lines[i].strip()):chunk.append(lines[i].strip());i+=1
  yield('paragraph',' '.join(chunk))

def inline_html(s):
 s=html.escape(s)
 s=re.sub(r'`([^`]+)`',r'<code>\1</code>',s)
 s=re.sub(r'\*\*([^*]+)\*\*',r'<strong>\1</strong>',s)
 s=re.sub(r'\[([^]]+)\]\(([^)]+)\)',r'<a href="\2">\1</a>',s)
 return s

doc=Document();section=doc.sections[0];section.page_width=Inches(8.5);section.page_height=Inches(11)
section.top_margin=section.bottom_margin=Inches(.7);section.left_margin=section.right_margin=Inches(.75)
for name in ['Normal','Title','Subtitle','Heading 1','Heading 2','Heading 3','Heading 4']:
 st=doc.styles[name];st.font.name='Calibri';st.font.color.rgb=RGBColor(0,0,0)
normal=doc.styles['Normal'];normal.font.size=Pt(11);normal.paragraph_format.line_spacing=1.08;normal.paragraph_format.space_after=Pt(6)
for name,size in [('Title',26),('Subtitle',12),('Heading 1',17),('Heading 2',13.5),('Heading 3',11.5)]:
 st=doc.styles[name];st.font.size=Pt(size);st.paragraph_format.keep_with_next=True
 st.paragraph_format.space_before=Pt(14 if name.startswith('Heading') else 0);st.paragraph_format.space_after=Pt(7)
for n in ['Heading 1','Heading 2','Heading 3']:doc.styles[n].font.bold=True
footer=section.footer.paragraphs[0];footer.alignment=WD_ALIGN_PARAGRAPH.RIGHT
run=footer.add_run('UACRF  |  Version 1.0  |  MS 01  |  ');run.font.size=Pt(8)
f=OxmlElement('w:fldSimple');f.set(qn('w:instr'),'PAGE');footer._p.append(f)
doc.core_properties.title='UACRF microservices architecture and implementation design';doc.core_properties.author='UACRF';doc.core_properties.subject='Version 1.0 architecture revision MS 01';doc.core_properties.keywords='UACRF Java Spring Boot microservices architecture'

def inline_doc(p,s):
 tokens=re.split(r'(\*\*[^*]+\*\*|`[^`]+`|\[[^]]+\]\([^)]+\))',s)
 for token in tokens:
  if not token:continue
  if token.startswith('**'):r=p.add_run(token[2:-2]);r.bold=True
  elif token.startswith('`'):r=p.add_run(token[1:-1]);r.font.name='Consolas';r.font.size=Pt(9.5)
  elif token.startswith('['):
   m=re.match(r'\[([^]]+)\]\(([^)]+)\)',token);p.add_run(m[1])
  else:p.add_run(token)

def table(rows):
 t=doc.add_table(rows=1,cols=len(rows[0]));t.alignment=WD_TABLE_ALIGNMENT.CENTER;t.autofit=False
 count=len(rows[0]);widths={3:[1.0,2.1,3.9],4:[.6,1.35,2.5,2.55],2:[1.7,5.3]}.get(count,[7/count]*count)
 for c,w in zip(t.columns,widths):c.width=Inches(w)
 for i,row in enumerate(rows):
  cells=t.rows[0].cells if i==0 else t.add_row().cells
  for j,(cell,value) in enumerate(zip(cells,row)):
   cell.width=Inches(widths[j]);cell.vertical_alignment=WD_CELL_VERTICAL_ALIGNMENT.CENTER
   p=cell.paragraphs[0];p.paragraph_format.space_after=Pt(3);p.paragraph_format.space_before=Pt(3);p.paragraph_format.line_spacing=Pt(11.2)
   inline_doc(p,value)
   for r in p.runs:r.font.size=Pt(9.5)
   props=cell._tc.get_or_add_tcPr();borders=OxmlElement('w:tcBorders')
   for edge in ['top','left','bottom','right']:
    e=OxmlElement('w:'+edge);e.set(qn('w:val'),'single');e.set(qn('w:sz'),'4');e.set(qn('w:color'),'D9D9D9');borders.append(e)
   props.append(borders);margins=OxmlElement('w:tcMar')
   for edge in ['top','left','bottom','right']:
    e=OxmlElement('w:'+edge);e.set(qn('w:w'),'80');e.set(qn('w:type'),'dxa');margins.append(e)
   props.append(margins)
   fill='193651' if i==0 else 'F2F6FA' if i%2==0 else 'FFFFFF';shd=OxmlElement('w:shd');shd.set(qn('w:fill'),fill);props.append(shd)
   if i==0:
    for r in p.runs:r.bold=True;r.font.color.rgb=RGBColor(255,255,255)
  if i==0:rep=OxmlElement('w:tblHeader');t.rows[0]._tr.get_or_add_trPr().append(rep)
 doc.add_paragraph().paragraph_format.space_after=Pt(3)

doc.add_paragraph('UACRF microservices architecture',style='Title')
doc.add_paragraph('Detailed implementation design',style='Subtitle')
doc.add_paragraph('Version 1.0  |  Architecture revision MS 01  |  3 November 2026')
doc.add_paragraph('This design defines independently deployable Java Spring Boot services for the complete UACRF lifecycle. It is a fresh implementation; the existing application is not a build, runtime, regression, or acceptance dependency. The document specifies data ownership, contracts, workflows, security, deployment and acceptance for the target services.')
doc.add_paragraph('The target microservices have not been implemented by this document. Delivery acceptance requires tests executed against the target services.')
doc.add_paragraph('Reading guide',style='Heading 1')
for _,title in PARTS:doc.add_paragraph(title,style='List Bullet')
doc.add_paragraph('The accompanying HTML library, SVG and Mermaid diagrams, JSON and CSV inventories, common contract schemas and source-contract copies form part of the design package. Use those inventories for every operation, table, requirement and original backlog item.')
htmlparts=[];toc=[]
for part,(filename,title) in enumerate(PARTS):
 md=(B/filename).read_text();anchor=filename.replace('.md','').lower();toc.append(f'<a href="#{anchor}">{html.escape(title)}</a>')
 htmlparts.append(f'<section id="{anchor}"><h1>{html.escape(title)}</h1><p class="source"><a href="{filename}">Open Markdown</a></p>')

 if part in [0,1,2,3,7]:doc.add_page_break()
 doc.add_paragraph(title,style='Heading 1')
 for kind,value in blocks(md):
  if kind=='heading':
   level,text=value
   if level==1:continue
   target=min(3,level-1 if part==0 else level)
   doc.add_paragraph(text,style='Heading '+str(target));htmlparts.append(f'<h{min(4,level)}>{html.escape(text)}</h{min(4,level)}>')
  elif kind=='paragraph':inline_doc(doc.add_paragraph(),value);htmlparts.append('<p>'+inline_html(value)+'</p>')
  elif kind=='list':
   bullet,text=value;inline_doc(doc.add_paragraph(style='List Bullet' if bullet else 'List Number'),text);htmlparts.append('<p class="list">'+('• ' if bullet else '')+inline_html(text)+'</p>')
  elif kind=='code':
   p=doc.add_paragraph();p.paragraph_format.line_spacing=1
   r=p.add_run(value);r.font.name='Consolas';r.font.size=Pt(9);htmlparts.append('<pre>'+html.escape(value)+'</pre>')
  elif kind=='table':
   table(value);htmlparts.append('<div class="table-scroll"><table>'+''.join('<tr>'+''.join(('<th>' if i==0 else '<td>')+inline_html(c)+('</th>' if i==0 else '</td>') for c in row)+'</tr>' for i,row in enumerate(value))+'</table></div>')
  elif kind=='image':
   caption,link=value;image=B/link.replace('.svg','.png')
   if image.name=='execution_sequence.png':image=B/'diagrams/execution_overview.png';caption+=' summary'
   p=doc.add_paragraph();p.paragraph_format.keep_with_next=True
   r=p.add_run();pic=r.add_picture(str(image),width=Inches(6.9))
   if pic.height>Inches(7.9):ratio=Inches(7.9)/pic.height;pic.width=int(pic.width*ratio);pic.height=Inches(7.9)
   pic._inline.docPr.set('descr',caption)
   p.alignment=WD_ALIGN_PARAGRAPH.CENTER
   cap=doc.add_paragraph(caption);cap.alignment=WD_ALIGN_PARAGRAPH.CENTER
   for r in cap.runs:r.italic=True;r.font.size=Pt(9)
   htmlparts.append(f'<figure><a href="{html.escape(link)}"><img src="{html.escape(link)}" alt="{html.escape(caption)}"></a><figcaption>{html.escape(caption)}. Open to enlarge.</figcaption></figure>')
 htmlparts.append('</section>')
# Remove built-in Word title border residue from both styles and direct formatting.
for element in [doc.styles.element,doc.element]:
 for border in list(element.iter(qn('w:pBdr'))):border.getparent().remove(border)
doc.save(B/'UACRF_Microservices_Architecture_v1_0.docx')
links=[]
for f,label in [('SERVICE_CATALOG.json','Service catalogue'),('EVENT_CATALOG.json','Event catalogue'),('inventories/API_OWNERSHIP.csv','All existing API owners'),('inventories/DATA_OWNERSHIP.csv','All source table owners'),('inventories/REQUIREMENT_ALLOCATION.csv','Formal requirement allocation'),('inventories/ORIGINAL_47_DISPOSITION.json','Original 47 disposition'),('inventories/CODE_EXTRACTION.json','Java extraction map'),('inventories/ACCEPTANCE_SCENARIOS.json','Thirty nine retained scenarios'),('TARGET_API_ADDITIONS.json','Proposed target interfaces'),('qa/ARCHITECTURE_CHECKS.json','Architecture verification'),('MANIFEST.json','Package manifest')]:links.append(f'<li><a href="{f}">{label}</a></li>')
diagrams=''.join(f'<li><a href="diagrams/{p.name}">{p.stem.replace("_"," ")}</a> · <a href="diagrams/{p.stem}.mmd">Mermaid source</a></li>' for p in sorted((B/'diagrams').glob('*.svg')))
page='''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>UACRF microservices design library</title><style>body{margin:0;color:#162c43;background:#f5f8fc;font:17px/1.65 system-ui}nav{position:fixed;box-sizing:border-box;left:0;top:0;bottom:0;width:285px;overflow:auto;background:#fff;border-right:1px solid #d4dfeb;padding:24px}nav a{display:block;margin:12px 0}main{margin-left:285px;padding:40px;max-width:1100px}h1{font-size:30px;line-height:1.25}h2{margin-top:40px;font-size:24px}h3{margin-top:30px}section{border-top:1px solid #d5dfeb;padding-top:30px;margin-top:45px}a{color:#155ca0}code{font:14px ui-monospace;overflow-wrap:anywhere}pre{font:14px/1.6 ui-monospace;white-space:pre-wrap;overflow-wrap:anywhere;background:#edf2f8;padding:18px}table{border-collapse:collapse;width:100%;font-size:15px}th,td{border:1px solid #c7d4e2;padding:12px;text-align:left;vertical-align:top}th{background:#e4edf7}.table-scroll{overflow:auto}figure{margin:30px 0}figure img{max-width:100%;max-height:1100px;background:white}figcaption,.source{font-size:14px;color:#4a6075}.list{padding-left:20px}li{margin:8px 0}summary{cursor:pointer;font-weight:600}@media(max-width:900px){nav{position:relative;width:auto;border-bottom:1px solid #d4dfeb}main{margin:0;padding:24px}}</style></head><body><nav><strong>UACRF microservices</strong><p>Version 1.0<br>Architecture MS 01</p>NAV<a href="#inventories">Inventories and diagrams</a><a href="UACRF_Microservices_Architecture_v1_0.pdf">Read PDF</a><a href="UACRF_Microservices_Architecture_v1_0.docx">Open Word</a></nav><main><h1>Architecture and implementation design</h1><p>The recommended target has fourteen domain services, one unified experience API and one API edge. This package is the design for implementation; it does not claim that the target services have been built.</p><p>144 inherited API operations, 153 source table declarations and 131 formal requirements are allocated as planning inventories. Target delivery is measured through implemented contracts and tests executed against the microservices.</p>BODY<section id="inventories"><h1>Inventories and diagrams</h1><ul>LINKS</ul><h2>Graphical design</h2><ul>DIAGRAMS</ul><p>Archived source materials and selected contracts provide design traceability; target acceptance is measured against the microservices. No source credentials are included.</p></section></main></body></html>'''
page=page.replace('NAV',''.join(toc)).replace('BODY',''.join(htmlparts)).replace('LINKS',''.join(links)).replace('DIAGRAMS',diagrams)
(B/'index.html').write_text(page)
print('Created Word and self-contained HTML reading library')
