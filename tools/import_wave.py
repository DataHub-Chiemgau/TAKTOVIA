#!/usr/bin/env python3
"""Übernimmt das Ergebnis einer Umsetzungswelle (Workflow-Ausgabe) in docs/umsetzungsplan/arbeitspakete.csv.

Je Anforderung: complete=true -> Status „Umgesetzt“, sonst „In Umsetzung“ (nur wenn mindestens ein Test existiert).
Testtokens werden nur übernommen, wenn Datei und Testname im Repository gefunden werden. Nichts wird herabgestuft:
„Umgesetzt“/„Abgenommen“ bleiben erhalten. Aufruf: import_wave.py <workflow-ausgabe.json>
"""
import csv, json, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CSV = ROOT / 'docs/umsetzungsplan/arbeitspakete.csv'


def resolves(tok):
    if tok.startswith(('AT-', 'E2E-', 'PH-AT-')): return True
    if '::' not in tok: return False
    path, name = tok.split('::', 1)
    f = ROOT / path
    return f.is_file() and name in f.read_text(encoding='utf-8', errors='replace')


def main(out):
    data = json.loads(Path(out).read_text())['result']
    with CSV.open(encoding='utf-8', newline='') as f:
        rows = list(csv.reader(f, delimiter=';'))
    h = rows[0]; ix = {n: h.index(n) for n in ('source_key', 'detailvertrag', 'testfall', 'status')}
    by = {r[ix['source_key']]: r for r in rows[1:]}
    report = []
    for cl in data:
        for wp in cl.get('wps', []):
            for rq in wp.get('requirements', []) or []:
                r = by.get(rq['source_key'])
                if not r: report.append(f"unbekannt: {rq['source_key']}"); continue
                good = [t.strip() for t in rq['tests'] if resolves(t.strip())]
                bad = [t for t in rq['tests'] if t.strip() not in good]
                old = [t for t in r[ix['testfall']].split(';') if t and not t.startswith('geplant:')]
                tests = old + [t for t in good if t not in old]
                real = [t for t in tests if '::' in t]
                if r[ix['status']] in ('Umgesetzt', 'Abgenommen'): new = r[ix['status']]
                elif rq['complete'] and real: new = 'Umgesetzt'
                elif real: new = 'In Umsetzung'
                else: new = r[ix['status']]
                r[ix['testfall']] = ';'.join(tests) or r[ix['testfall']]
                r[ix['status']] = new
                report.append(f"{rq['source_key']}: {new}, {len(real)} Tests" + (f", {len(bad)} Tokens nicht gefunden" if bad else ''))
    with CSV.open('w', encoding='utf-8', newline='') as f:
        csv.writer(f, delimiter=';', quoting=csv.QUOTE_ALL).writerows(rows)
    print('\n'.join(report))


if __name__ == '__main__':
    main(sys.argv[1])
