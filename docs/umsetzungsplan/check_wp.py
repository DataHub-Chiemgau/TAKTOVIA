#!/usr/bin/env python3
"""Vollständigkeitsprüfung der Arbeitspakete und Erzeugung von arbeitspakete.csv.

Liest docs/umsetzungsplan/wp/*.md und prüft gegen die Artefakte des Pflichtenhefts:
- jede der 672 Anforderungen steht in genau einem WP
- jede WP-Abhängigkeit zeigt auf ein existierendes WP, keine Zyklen, keine Vorwärtsabhängigkeit
- alle 106 Originaltests, 22 PH-AT-Tests, 80 Parameter, 48 Kennzahlen, 38 Kerntabellen,
  Ergänzungstabellen (Kap. 04 §4.4), OpenAPI-Pfade, Endpunktkatalog (Kap. 05 §5.7) und UI-01..20
  werden in mindestens einem WP genannt
Aufruf: python docs/umsetzungsplan/check_wp.py [--write]   (--write schreibt arbeitspakete.csv)
Exitcode 1 bei harten Fehlern (Anforderungen, Abhängigkeiten); Kataloglücken werden berichtet.
"""
import csv, re, sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PH = ROOT / 'docs/pflichtenheft'
WPDIR = Path(__file__).with_name('wp')
OUT = Path(__file__).with_name('arbeitspakete.csv')

def read_csv(p):
    with p.open(encoding='utf-8-sig', newline='') as f:
        return list(csv.DictReader(f, delimiter=';'))

reqs = {r['source_key']: r for r in read_csv(PH / 'anforderungen.csv')}
WP_RE = re.compile(r'WP-M\d[a-z]?-\d{2}')
KEY_RE = re.compile(r'LH-[A-Z0-9.]+-[A-Z]+-\d{3}')
ORDER = {'M0': 0, 'M1': 1, 'M2': 2, 'M3': 2, 'M4': 2, 'M5': 3, 'M6': 4, 'M7': 4, 'M8': 4, 'M9': 5}

wps, errors, alltext = {}, [], ''
for f in sorted(WPDIR.glob('M*.md')):
    text = f.read_text(encoding='utf-8'); alltext += '\n' + text
    parts = re.split(r'^### (WP-M\d[a-z]?-\d{2}) [–-] (.+)$', text, flags=re.M)
    for i in range(1, len(parts), 3):
        wid, title, body = parts[i], parts[i + 1].strip(), parts[i + 2].split('\n## ')[0]
        if wid in wps: errors.append(f'WP-ID doppelt: {wid}')
        def field(name):
            m = re.search(rf'^- \*\*{name}:\*\*(.*?)(?=^- \*\*|\Z)', body, re.S | re.M)
            return m.group(1) if m else None
        req, dep, size = field('Anforderungen'), field('Abhängigkeiten'), field('Größe')
        for need in ['Ziel', 'Anforderungen', 'Liefergegenstände', 'Akzeptanzkriterien', 'Tests', 'Abhängigkeiten', 'Größe']:
            if field(need) is None: errors.append(f'{wid}: Feld "{need}" fehlt')
        sm = re.search(r'\b([SML])\b', size or '')
        wps[wid] = {'title': title, 'file': f.name, 'keys': KEY_RE.findall(req or ''),
                    'deps': sorted(set(WP_RE.findall(dep or '')) - {wid}),
                    'size': sm.group(1) if sm else '', 'dep_raw': dep or ''}
        if 'EXTERN[' in (dep or ''): errors.append(f'{wid}: unaufgelöste EXTERN-Abhängigkeit')
        if 'UNGEDECKT' in (dep or ''): errors.append(f'{wid}: ungedeckte Abhängigkeit')
        if not sm: errors.append(f'{wid}: Größe nicht S/M/L')

assign = defaultdict(list)
for wid, w in wps.items():
    for k in w['keys']: assign[k].append(wid)
for k in reqs:
    if len(assign[k]) != 1: errors.append(f'{k}: zugeordnet zu {assign[k] or "keinem WP"}')
for k in assign:
    if k not in reqs: errors.append(f'Unbekannter source_key {k} in {assign[k]}')

ms = lambda wid: wid.split('-')[1][:2]
for wid, w in wps.items():
    for d in w['deps']:
        if d not in wps: errors.append(f'{wid}: Abhängigkeit {d} existiert nicht')
        elif ORDER[ms(d)] > ORDER[ms(wid)]: errors.append(f'{wid}: hängt von späterem {d} ab')
state = {}
def visit(n, path):
    if state.get(n) == 2: return
    if state.get(n) == 1:
        errors.append('Zyklus: ' + ' -> '.join(path[path.index(n):] + [n])); return
    state[n] = 1
    for d in wps[n]['deps']:
        if d in wps: visit(d, path + [n])
    state[n] = 2
sys.setrecursionlimit(10000)
for n in wps: visit(n, [])

# Katalogabdeckung
gaps = {}
tests = [r['test_id_original'] for r in read_csv(PH / 'e2e.csv')] + [f'PH-AT-{i:03d}' for i in range(1, 23)]
def mentioned(t):
    return re.search(rf'(?<![A-Z-]){re.escape(t)}(?!\d)', alltext) is not None
gaps['Referenztests'] = [t for t in tests if not mentioned(t)]
cat = read_csv(PH / 'kataloge.csv')
gaps['Parameter'] = [r['name_original'] for r in cat if r['katalogtyp'] == 'Parameter' and r['name_original'] not in alltext]
gaps['Kennzahlen'] = [r['name_original'] for r in cat if r['katalogtyp'] != 'Parameter' and r['name_original'] not in alltext]
sql = (PH / 'schema/kernschema.sql').read_text()
gaps['Kerntabellen'] = [t for t in re.findall(r'^CREATE TABLE (\w+)', sql, re.M) if not re.search(rf'\b{t}\b', alltext)]
ch4 = (PH / '04-datenmodell.md').read_text().split('## 4.4')[1].split('## 4.5')[0]
ext = [t.strip() for row in ch4.splitlines() if row.startswith('| ') and '---' not in row and 'Tabellen (' not in row
       for t in row.split('|')[2].split(',')]
gaps['Ergänzungstabellen'] = [t for t in ext if t and not re.search(rf'\b{t}\b', alltext)]
api = (PH / 'api/openapi.yaml').read_text()
gaps['OpenAPI-Pfade'] = [p for p in re.findall(r'^  (/v1/\S+):', api, re.M) if p not in alltext]
ch5 = (PH / '05-api-vertraege.md').read_text().split('## 5.7')[1].split('## 5.8')[0]
eps = sorted({e for row in ch5.splitlines() if row.startswith('| ') for e in re.findall(r'`(/v1/[^`]+)`', row.split('|')[2])})
gaps['Endpunktkatalog'] = [e for e in eps if e not in alltext]
gaps['Bildschirme'] = [u for u in (f'UI-{i:02d}' for i in range(1, 21)) if u not in alltext]

sizes = Counter(w['size'] for w in wps.values())
per_unit = Counter(w['file'].split('-')[0] for w in wps.values())
print(f'WPs: {len(wps)}  Größen: {dict(sizes)}')
print('je Einheit:', ' '.join(f'{k}:{v}' for k, v in sorted(per_unit.items(), key=lambda x: (int(x[0][1]), x[0]))))
print(f'Anforderungen zugeordnet: {sum(1 for k in reqs if len(assign[k]) == 1)}/{len(reqs)}')
for name, g in gaps.items():
    print(f'Katalog {name}: {"vollständig" if not g else f"{len(g)} ohne WP-Bezug: " + ", ".join(g)}')
for e in errors: print('FEHLER', e)

if '--write' in sys.argv and not errors:
    with OUT.open('w', encoding='utf-8', newline='') as f:
        w = csv.writer(f, delimiter=';', quoting=csv.QUOTE_ALL)
        w.writerow(['source_key', 'id_original', 'quellabschnitt', 'phase_original', 'prioritaet_original',
                    'meilenstein', 'arbeitspaket', 'wp_titel', 'wp_groesse', 'wp_datei', 'detailvertrag', 'testfall', 'status'])
        for k, r in reqs.items():
            wid = assign[k][0]; x = wps[wid]
            w.writerow([k, r['id_original'], r['quellabschnitt'], r['phase_original'], r['prioritaet_original'],
                        ms(wid), wid, x['title'], x['size'], 'wp/' + x['file'], '', '', 'Geplant'])
    print('arbeitspakete.csv geschrieben')
sys.exit(1 if errors else 0)
