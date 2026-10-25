#!/usr/bin/env python3
"""Erzeugt die lesbare Word-Ausgabe aus den Markdown-Kapiteln.
Benoetigt python-docx, Pillow und Graphviz (dot). Keine Netzwerkzugriffe.
"""
from pathlib import Path
import re, subprocess, tempfile
from docx import Document
from docx.shared import Inches, Pt, Cm, RGBColor
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from PIL import Image
BASE=Path(__file__).resolve().parent
GRAPHS={
'01': '''digraph G {rankdir=TB; node [shape=box,style="rounded,filled",fillcolor="#e9f2fb",fontname="DejaVu Sans",fontsize=11]; edge [color="#52718b",fontname="DejaVu Sans",fontsize=9];
 clients [label="Passenger / Driver / Operator / Callcenter"];
 gateway [label="API / OIDC / Mandanten- und Objektberechtigung"];
 core [label="Modularer Fachkern\nBooking • Safety • Journey • Finanzen • Konfiguration"];
 db [shape=cylinder,label="PostgreSQL / PostGIS\nRLS • lokale Transaktionen • Outbox"];
 work [label="Dispatch / Routing / OCR / Analytics"];
 partner [label="Partneradapter\nVertrag • Capability • minimale Projektionen"];
 pay [label="PSP / ERP\nZahlung • Abgleich • Export"];
 clients->gateway->core; core->db; db->work [label="Ereignisse"]; core->partner; core->pay;
}''',
'04': '''digraph G {rankdir=LR; node [shape=box,style="rounded,filled",fillcolor="#e9f2fb",fontname="DejaVu Sans",fontsize=10]; edge [fontname="DejaVu Sans",fontsize=8,color="#52718b"];
 Tenant->Journey [label="1:n"]; Journey->JourneyLeg [label="1:n"]; Journey->Operation [label="1:n"]; Operation->SagaStep [label="1:n"];
 Tenant->EconomicEvent [label="1:n"]; EconomicEvent->Journal [label="1:0..1"]; Journal->JournalLine [label="1:n"]; EconomicEvent->AllocationSet [label="1:n"]; AllocationSet->AllocationLine [label="1:n"];
 JourneyLeg->SettlementLine [label="1:n"]; VehicleRun->CapacityHold [label="1:n"]; CapacityHold->Booking [label="1:0..1"];
 JourneyLeg->Booking [style=dashed,label="opake Partnerreferenz\nKEIN fremder SQL-FK"];
}''',
'05': '''digraph G {rankdir=TB; node [shape=box,style="rounded,filled",fillcolor="#e9f2fb",fontname="DejaVu Sans",fontsize=11]; edge [fontname="DejaVu Sans",fontsize=9,color="#52718b"];
 plan [label="Angebot: A + B\nPreis, Zeitfenster, Bedingungen"];
 hold [label="Hold A und B\n202 Operation, keine Buchungszusage"];
 auth [label="PSP autorisieren / ggf. SCA\nHoldfristen erneut pruefen"];
 confirm [label="Betreiber A und B bestaetigen\nstabile Befehlsreferenzen"];
 capture [label="PSP Capture nach freigegebener Regel"];
 done [label="Reise bestaetigt\nSegmentreferenzen und Zahlungsstatus"];
 unknown [label="Timeout: UNKNOWN / RECONCILING",fillcolor="#fff0d6"];
 failure [label="Teilfehler: Kompensation / Refund\nbei Restpflicht manueller Klaerfall",fillcolor="#fff0d6"];
 plan->hold->auth->confirm->capture->done;
 confirm->unknown [label="Ausgang unklar"]; unknown->confirm [label="Statusnachweis"]; confirm->failure [label="nachgewiesener Fehler"];
}'''}
files=[BASE.parent/'pflichtenheft.md']+sorted(BASE.glob('[0-9][0-9]-*.md'))+[BASE/'recherche-federation.md',BASE/'recherche-kosten.md']
doc=Document(); sec=doc.sections[0];sec.page_height=Cm(29.7);sec.page_width=Cm(21);sec.top_margin=Cm(1.8);sec.bottom_margin=Cm(1.8);sec.left_margin=Cm(1.8);sec.right_margin=Cm(1.8)
normal=doc.styles['Normal'];normal.font.name='Calibri';normal.font.size=Pt(10);normal.paragraph_format.space_after=Pt(5)
for name in ['Title','Heading 1','Heading 2','Heading 3','Heading 4']:
 doc.styles[name].font.name='Calibri';doc.styles[name].font.color.rgb=RGBColor.from_string('153B5B')
footer=sec.footer.paragraphs[0];footer.alignment=2
footer.add_run('TAKTOVIA · Pflichtenheft V0.1 · ')
fld=OxmlElement('w:fldSimple');fld.set(qn('w:instr'),'PAGE');footer._p.append(fld)
doc.core_properties.title='Pflichtenheft TAKTOVIA V0.1';doc.core_properties.subject='Mandantenarchitektur, Reiseketten und Kostenmodell';doc.core_properties.author='TAKTOVIA – technische Spezifikation'
def inline(p,text):
 text=re.sub(r'\[([^\]]+)\]\(([^)]+)\)',lambda m:m[1]+' ('+m[2]+')',text)
 for token in re.split(r'(\*\*.*?\*\*|`[^`]+`)',text):
  r=p.add_run(token[2:-2] if token.startswith('**') else token[1:-1] if token.startswith('`') else token)
  if token.startswith('**'):r.bold=True
  elif token.startswith('`'):r.font.name='Consolas';r.font.size=Pt(8)
with tempfile.TemporaryDirectory(prefix='taktovia-docx-') as temp:
 imgs={}
 for name,g in GRAPHS.items():
  f=Path(temp)/(name+'.png');subprocess.run(['dot','-Tpng','-Gdpi=160','-o',str(f)],input=g,text=True,check=True);imgs[name]=f
 for file in files:
  if file!=files[0]:doc.add_page_break()
  lines=file.read_text().splitlines();i=0
  while i<len(lines):
   line=lines[i]
   if line.startswith('```'):
    lang=line[3:].strip();i+=1;code=[]
    while i<len(lines) and not lines[i].startswith('```'):code.append(lines[i]);i+=1
    if lang=='mermaid' and file.name[:2] in imgs:
     f=imgs[file.name[:2]];w,h=Image.open(f).size;scale=min(6.7/w,7.5/h);doc.add_picture(str(f),width=Inches(w*scale),height=Inches(h*scale));p=doc.add_paragraph('Schematische Darstellung; die verbindlichen Detailverträge stehen im Kapitel.');p.runs[0].italic=True
    else:
     p=doc.add_paragraph();r=p.add_run('\n'.join(code));r.font.name='Consolas';r.font.size=Pt(8)
    i+=1;continue
   if line.startswith('|'):
    rows=[]
    while i<len(lines) and lines[i].startswith('|'):
     if not re.match(r'^\|[\s:|\-]+$',lines[i]):rows.append([c.strip() for c in lines[i].strip('|').split('|')])
     i+=1
    width=max(map(len,rows));table=doc.add_table(rows=0,cols=width);table.style='Light Shading Accent 1'
    for n,row in enumerate(rows):
     cells=table.add_row().cells
     for j,text in enumerate(row):
      inline(cells[j].paragraphs[0],text)
      for r in cells[j].paragraphs[0].runs:r.font.size=Pt(8)
     if n==0:
      pr=table.rows[-1]._tr.get_or_add_trPr();repeat=OxmlElement('w:tblHeader');pr.append(repeat)
    doc.add_paragraph();continue
   m=re.match(r'^(#{1,4}) (.*)',line)
   if m:inline(doc.add_heading(level=len(m[1])),m[2])
   elif line.strip():
    numbered=re.match(r'^\d+\.\s+(.*)',line)
    if line.startswith('- '):inline(doc.add_paragraph(style='List Bullet'),line[2:])
    elif numbered:inline(doc.add_paragraph(style='List Number'),numbered[1])
    else:inline(doc.add_paragraph(),line)
   i+=1
out=BASE/'Pflichtenheft_TAKTOVIA_V0_1.docx';doc.save(out)
print(f'{out}: {out.stat().st_size} bytes; {len(files)} Markdown-Quellen; {len(doc.tables)} Tabellen; {len(doc.inline_shapes)} Diagramme')
