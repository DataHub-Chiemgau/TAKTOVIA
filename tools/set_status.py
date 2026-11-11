#!/usr/bin/env python3
"""Pflegt Status, Detailvertrag und Testfälle einer Anforderung in docs/umsetzungsplan/arbeitspakete.csv.

  set_status.py <source_key> <Status> [--test TOKEN]... [--contract PFAD]...

Der Vertragspfad ergibt sich standardmäßig aus docs/umsetzungsplan/vertraege/<source_key>.md. Neue Testfälle werden den
vorhandenen hinzugefügt. Anschließend prüft docs/umsetzungsplan/check_tracking.py die Regeln des Vertragsprozesses.
"""
import csv, sys
from pathlib import Path

CSV = Path(__file__).resolve().parents[1] / 'docs/umsetzungsplan/arbeitspakete.csv'


def main(argv):
    key, status = argv[0], argv[1]
    tests, contracts = [], []
    it = iter(argv[2:])
    for a in it:
        if a == '--test': tests.append(next(it))
        elif a == '--contract': contracts.append(next(it))
    with CSV.open(encoding='utf-8', newline='') as f:
        rows = list(csv.reader(f, delimiter=';'))
    h = rows[0]; i = {n: h.index(n) for n in ('source_key', 'detailvertrag', 'testfall', 'status')}
    row = next(r for r in rows[1:] if r[i['source_key']] == key)
    row[i['status']] = status
    cs = [c for c in row[i['detailvertrag']].split(';') if c] or [f'docs/umsetzungsplan/vertraege/{key}.md']
    for c in contracts:
        if c not in cs: cs.append(c)
    row[i['detailvertrag']] = ';'.join(cs)
    ts = [t for t in row[i['testfall']].split(';') if t]
    for t in tests:
        if t not in ts: ts.append(t)
    row[i['testfall']] = ';'.join(ts)
    with CSV.open('w', encoding='utf-8', newline='') as f:
        csv.writer(f, delimiter=';', quoting=csv.QUOTE_ALL).writerows(rows)


if __name__ == '__main__':
    main(sys.argv[1:])
