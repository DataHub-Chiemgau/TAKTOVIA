#!/usr/bin/env python3
"""Überträgt Evidenzdatensätze in docs/pflichtenheft/e2e.csv (WP-M0-32).

e2e.csv wird ausschließlich hierüber befüllt: Status, Build-ID, Nachweis, Freigabe. Eine Evidenz wird abgelehnt, wenn
 - sie nicht gegen tests/acceptance/schema/evidence.schema.json validiert,
 - ihre Build-ID nicht dem Format ci-<lauf>-<sha> entspricht oder (mit --known-builds) nicht zu einem Pipeline-Lauf gehört,
 - der Test nicht im Katalog (e2e.csv bzw. PH-AT-001..022) steht oder ihre acceptance_decision nicht PENDING/ACCEPTED/REJECTED ist.
Die Entscheidung "freigegeben" trägt nur der Auftraggeber ein (acceptance_decision ACCEPTED); die Automatik setzt PENDING.
Parallele Läufe werden über eine Sperrdatei serialisiert, kein Eintrag geht verloren.

Aufruf: transfer_evidence.py <evidenz.json>... [--known-builds datei] [--csv pfad]
"""
import csv, fcntl, json, re, sys
from pathlib import Path
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / 'tests/acceptance/schema/evidence.schema.json'
CSV = ROOT / 'docs/pflichtenheft/e2e.csv'
BUILD = re.compile(r'^ci-\d+-[0-9a-f]{7,40}$')
STATUS = {'PASSED': 'Bestanden', 'FAILED': 'Fehlgeschlagen', 'NOT_EXECUTED': 'Nicht ausgeführt'}
FREIGABE = {'PENDING': '', 'ACCEPTED': 'Freigegeben', 'REJECTED': 'Abgelehnt'}


def build_id(ev):
    b = ev.get('software_build') or {}
    return b.get('build_id') or b.get('id') or ''


def validate(ev, known):
    errs = [e.message for e in Draft202012Validator(json.loads(SCHEMA.read_text())).iter_errors(ev)]
    bid = build_id(ev)
    if not BUILD.match(bid): errs.append(f'Build-ID "{bid}" entspricht nicht ci-<lauf>-<sha>')
    elif known is not None and bid not in known: errs.append(f'Build-ID {bid} gehört zu keinem Pipeline-Lauf')
    return errs


def transfer(files, csv_path=CSV, known=None):
    lock = csv_path.with_suffix('.lock')
    rejected = {}
    with lock.open('w') as lk:
        fcntl.flock(lk, fcntl.LOCK_EX)
        with csv_path.open(encoding='utf-8-sig', newline='') as f:
            rows = list(csv.DictReader(f, delimiter=';')); head = list(rows[0])
        by = {r['test_id_original']: r for r in rows}
        for p in files:
            ev = json.loads(Path(p).read_text())
            errs = validate(ev, known)
            tid = (ev.get('test') or {}).get('id')
            if not errs and str(tid).startswith('TECH-'):
                print(f'übersprungen: {tid} ist ein technisches Szenario, kein Katalogtest'); continue
            if tid not in by and not errs: errs.append(f'Test {tid} nicht im Katalog e2e.csv')
            if errs: rejected[str(p)] = errs; continue
            r = by[tid]
            r['status'] = STATUS[ev['result']]; r['build'] = build_id(ev)
            r['nachweis'] = f"{ev['run_id']}"; r['freigabe'] = FREIGABE[ev['acceptance_decision']]
        with csv_path.open('w', encoding='utf-8', newline='') as f:
            w = csv.DictWriter(f, fieldnames=head, delimiter=';', quoting=csv.QUOTE_ALL); w.writeheader(); w.writerows(rows)
        fcntl.flock(lk, fcntl.LOCK_UN)
    return rejected


if __name__ == '__main__':
    a = sys.argv[1:]; known = None; csv_path = CSV
    if '--known-builds' in a:
        i = a.index('--known-builds'); known = set(Path(a[i + 1]).read_text().split()); del a[i:i + 2]
    if '--csv' in a:
        i = a.index('--csv'); csv_path = Path(a[i + 1]); del a[i:i + 2]
    rej = transfer(a, csv_path, known)
    for f, errs in rej.items():
        for e in errs: print('ABGELEHNT', f, e)
    sys.exit(1 if rej else 0)
