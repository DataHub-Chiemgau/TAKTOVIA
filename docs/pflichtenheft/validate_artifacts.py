#!/usr/bin/env python3
"""Dokument-/Vertragsprüfung; keine laufende Anwendung oder Datenbank erforderlich.
Abhängigkeiten: PyYAML, jsonschema, openapi-spec-validator, pglast.
Aufruf aus beliebigem Verzeichnis: python docs/pflichtenheft/validate_artifacts.py
"""
from pathlib import Path
from collections import Counter
from decimal import Decimal
import csv, hashlib, json, re, zipfile, xml.etree.ElementTree as ET
import yaml
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate_spec
from pglast import parse_sql

ROOT=Path(__file__).resolve().parents[2]
DOC=ROOT/'docs/pflichtenheft'
def read_csv(name):
    with (DOC/name).open(encoding='utf-8-sig',newline='') as f:
        return list(csv.DictReader(f,delimiter=';'))
def norm(s): return re.sub(r'\s+','',s)
source=ROOT/'docs/lastenheft/Lastenheft_On_Demand_Mobilitaetsplattform_V1_2.docx'
sha=hashlib.sha256(source.read_bytes()).hexdigest()
assert sha=='9f799a0617b5e9a487041c9fe4475957be48fafacf216196bb088f555ea49165'
with zipfile.ZipFile(source) as z: xml=ET.fromstring(z.read('word/document.xml'))
ns={'w':'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
original=[]
for row in xml.findall('.//w:tr',ns):
    cells=[''.join(t.text or '' for t in c.findall('.//w:t',ns)) for c in row.findall('w:tc',ns)]
    if not cells: continue
    m=re.fullmatch(r'([A-Z]+-\d{3})\s*(P[123])\s*(MUSS|SOLL|KANN)',cells[0])
    if m:
        body,accept=cells[1].split('Abnahme:',1)
        original.append((*m.groups(),norm(body),norm(accept)))
rows=read_csv('anforderungen.csv')
export=[(r['id_original'],r['phase_original'],r['prioritaet_original'],norm(r['beschreibung_original']),norm(r['akzeptanz_original'])) for r in rows]
assert original==export, 'Original-/Matrixinhalt oder Reihenfolge weicht ab'
assert len(rows)==672 and len({r['source_key'] for r in rows})==672
assert len({r['id_original'] for r in rows})==660
for file in ['anforderungen.csv','kataloge.csv']:
    for row in read_csv(file):
        for artifact in row['pflichtenheft_artifact'].split(';'):
            assert (DOC/artifact).exists(),artifact
assert len(read_csv('e2e.csv'))==106
catalog=Counter(r['katalogtyp'] for r in read_csv('kataloge.csv'))
assert sorted(catalog.values())==[48,80]
links=0
for md in [ROOT/'README.md',ROOT/'docs/pflichtenheft.md',*DOC.glob('*.md')]:
    for target in re.findall(r'\]\(([^)]+)\)',md.read_text()):
        if '://' in target or target.startswith('#'): continue
        dest=target.split('#')[0]
        assert (md.parent/dest).exists(),f'{md.name}: {target}'
        links+=1
api=yaml.safe_load((DOC/'api/openapi.yaml').read_text());validate_spec(api)
schemas=api['components']['schemas'];examples=0
for name,schema in schemas.items():
    v=Draft202012Validator({**schema,'components':api['components']},format_checker=FormatChecker())
    for ex in schema.get('examples',[]):
        v.validate(ex);examples+=1
ops=[v['operationId'] for p in api['paths'].values() for k,v in p.items() if k in ['get','post','put','patch','delete']]
assert len(ops)==len(set(ops))
assert schemas['CostAllocationLine']['properties']['amount']['$ref'].endswith('/SignedMoney')
assert schemas['AllocationResult']['properties']['basis']['$ref'].endswith('/SignedMoney')
bs=Draft202012Validator({**schemas['BookingSubject'],'components':api['components']})
assert not bs.is_valid({'mode':'DELEGATED','passenger_ref':None,'delegation_ref':None})
assert bs.is_valid({'mode':'SELF','passenger_ref':None,'delegation_ref':None})
sql=parse_sql((DOC/'schema/kernschema.sql').read_text())
tables=[x.stmt.relation.relname for x in sql if type(x.stmt).__name__=='CreateStmt']
D=Decimal
assert D('32.10')==D('9.51')+D('19.02')+D('3.57')
assert D('21.40')==D('19.02')+D('2.38')
assert D('32.10')-D('21.40')==D('10.70')
assert sum(map(D,['800','180','600','100']))==D('1680')
assert D('1680')*D('.6')==D('1008') and D('1680')*D('.4')==D('672')
assert sum(map(D,['400','180','600','100']))==D('1280')
print(json.dumps({'source_sha256':sha,'requirements':len(rows),'distinct_original_ids':660,'phases':dict(Counter(r['phase_original'] for r in rows)),'catalogs':dict(catalog),'original_tests':106,'local_links_checked':links,'openapi_paths':len(api['paths']),'schemas':len(schemas),'examples_validated':examples,'sql_statements_parsed':len(sql),'core_tables':len(tables),'result':'PASS','limits':'No application, PostgreSQL runtime/PLpgSQL execution, partner integration or load test executed.'},ensure_ascii=False,indent=2))
