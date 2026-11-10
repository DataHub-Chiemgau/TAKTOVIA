#!/usr/bin/env python3
"""Erzeugt arbeitspakete.csv: ordnet jede der 672 Anforderungen genau einem Meilenstein zu.
Regel: Quellabschnitt + Originalphase -> Meilenstein. Aufruf: python docs/umsetzungsplan/build_zuordnung.py
"""
import csv
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'docs/pflichtenheft/anforderungen.csv'
OUT = Path(__file__).with_name('arbeitspakete.csv')

P1 = {
    'M1': ['6.1', '6.2', '6.17', '6.18', 'I.4', 'I.7'],
    'M2': ['6.3', '6.4', '6.5', '6.9', '6.10', '6.12'],
    'M3': ['6.6', '6.7', '6.8', '6.21', '6.22', 'I.8'],
    'M4': ['6.14', '6.23', '6.24', 'I.1', 'I.2', 'I.3', 'I.6'],
    'M5': ['6.19', '6.20'],
}
P2 = {
    'M6': ['6.10', '6.11', '6.17', 'I.4', 'I.5', 'I.9'],
    'M7': ['6.3', '6.4', '6.5', '6.6', '6.7', '6.8', '6.9', '6.12', '6.13',
           '6.21', '6.22', '6.23', '6.24', 'I.10'],
    'M8': ['6.1', '6.2', '6.14', '6.18', '6.19', '6.20', 'I.1', 'I.2', 'I.3',
           'I.6', 'I.7', 'I.8', 'I.11'],
}
LOOKUP = {('P1', s): m for m, ss in P1.items() for s in ss}
LOOKUP |= {('P2', s): m for m, ss in P2.items() for s in ss}

with SRC.open(encoding='utf-8-sig', newline='') as f:
    rows = list(csv.DictReader(f, delimiter=';'))

out, per_ms, per_ms_prio, per_ms_sec = [], Counter(), defaultdict(Counter), defaultdict(Counter)
for r in rows:
    phase, sec = r['phase_original'], r['quellabschnitt']
    ms = 'M9' if phase == 'P3' else LOOKUP[(phase, sec)]
    prefix = r['id_original'].split('-')[0]
    out.append({'source_key': r['source_key'], 'id_original': r['id_original'],
                'quellabschnitt': sec, 'phase_original': phase,
                'prioritaet_original': r['prioritaet_original'], 'meilenstein': ms,
                'arbeitspaket': f'{ms}-{prefix}' + ('-CLR' if sec == 'I.9' else ''),
                'detailvertrag': '', 'testfall': '', 'status': 'Geplant'})
    per_ms[ms] += 1
    per_ms_prio[ms][r['prioritaet_original']] += 1
    per_ms_sec[ms][f"{prefix}{'(I.9)' if sec == 'I.9' else ''}"] += 1

assert len(out) == 672 and len({o['source_key'] for o in out}) == 672
with OUT.open('w', encoding='utf-8', newline='') as f:
    w = csv.DictWriter(f, fieldnames=list(out[0]), delimiter=';', quoting=csv.QUOTE_ALL)
    w.writeheader(); w.writerows(out)

for ms in sorted(per_ms, key=lambda m: int(m[1:])):
    print(ms, per_ms[ms], dict(per_ms_prio[ms]),
          ' '.join(f'{k}:{v}' for k, v in per_ms_sec[ms].items()))
print('Arbeitspakete:', len({o['arbeitspaket'] for o in out}))
