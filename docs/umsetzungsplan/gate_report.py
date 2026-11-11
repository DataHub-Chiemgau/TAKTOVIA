#!/usr/bin/env python3
"""Gate-Bericht je Meilenstein (WP-M0-32): MUSS-Anforderungen ohne grünen Test, Referenztests ohne Evidenz.

Aufruf: python docs/umsetzungsplan/gate_report.py M1 [--csv-out pfad]
Quellen: arbeitspakete.csv (Status je Anforderung), tests/acceptance/catalog/scenarios.csv (Zielmeilenstein je Test),
docs/pflichtenheft/e2e.csv (Evidenz: Status mit Build-ID). Der Bericht entscheidet nichts; die Abnahme trifft der Auftraggeber.
"""
import csv, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GREEN = {'Umgesetzt', 'Abgenommen'}


def rd(p, delim):
    with p.open(encoding='utf-8-sig', newline='') as f:
        return list(csv.DictReader(f, delimiter=delim))


def report(ms: str, root: Path = ROOT) -> dict:
    reqs = [r for r in rd(root / 'docs/umsetzungsplan/arbeitspakete.csv', ';') if r['meilenstein'] == ms]
    must = [r for r in reqs if r['prioritaet_original'] == 'MUSS']
    open_must = [r['source_key'] + ' (' + r['status'] + ')' for r in must if r['status'] not in GREEN]
    cat = [r for r in rd(root / 'tests/acceptance/catalog/scenarios.csv', ',') if r['target_milestone'] == ms]
    ev = {r['test_id_original']: r for r in rd(root / 'docs/pflichtenheft/e2e.csv', ';')}
    no_ev = [r['test_id'] for r in cat if not (ev.get(r['test_id']) and ev[r['test_id']]['status'] == 'Bestanden' and ev[r['test_id']]['build'])]
    return {'meilenstein': ms, 'anforderungen': len(reqs), 'muss': len(must), 'muss_ohne_gruenen_test': open_must,
            'referenztests': len(cat), 'referenztests_ohne_evidenz': no_ev}


if __name__ == '__main__':
    r = report(sys.argv[1])
    print(f"Gate-Bericht {r['meilenstein']}: {r['anforderungen']} Anforderungen ({r['muss']} MUSS), "
          f"{len(r['muss_ohne_gruenen_test'])} MUSS ohne grünen Test, {len(r['referenztests_ohne_evidenz'])} von {r['referenztests']} Referenztests ohne Evidenz")
    for k in r['muss_ohne_gruenen_test']: print('  offen:', k)
    for k in r['referenztests_ohne_evidenz']: print('  ohne Evidenz:', k)
