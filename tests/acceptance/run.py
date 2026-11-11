#!/usr/bin/env python3
"""Runner des Akzeptanzgerüsts (WP-M0-22).

  python tests/acceptance/run.py --list                       Katalog (128 Szenarien) mit Status und Zielmeilenstein
  python tests/acceptance/run.py --scenario TECH-SMOKE-001    Szenario ausführen, Evidenz schreiben
  python tests/acceptance/run.py --all                        alle implementierten Szenarien

Optionen: --db NAME (sonst TAKTOVIA_AT_DB / TAKTOVIA_DB_NAME), --clock-start ISO, --out DIR (Standard
tests/acceptance/evidence-out), --bootstrap (Datenbank anlegen/migrieren wie in CI). Umgebungsvariablen: siehe db/db.py.
Exit-Code 0 nur, wenn alle ausgeführten Szenarien PASSED sind und nicht implementierte nicht angefordert wurden.
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from taktovia_at import AT_DIR, catalog  # noqa: E402
from taktovia_at import db as atdb  # noqa: E402
from taktovia_at import scenario as sc  # noqa: E402


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--scenario", action="append", default=[])
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--db")
    ap.add_argument("--clock-start", default=sc.DEFAULT_CLOCK_START)
    ap.add_argument("--out", default=str(AT_DIR / "evidence-out"))
    ap.add_argument("--bootstrap", action="store_true")
    a = ap.parse_args(argv)
    cat = catalog.load()
    impl = catalog.implemented_ids()
    if a.list:
        print(f"{'ID':<16}{'Art':<7}{'Status':<17}{'Ziel':<6}Name")
        for r in cat:
            print(f"{r['test_id']:<16}{r['kind']:<7}{('IMPLEMENTED' if r['test_id'] in impl else r['status']):<17}{r['target_milestone']:<6}{r['name']}")
        for t in sorted(impl - {r['test_id'] for r in cat}):
            print(f"{t:<16}{'TECH':<7}{'IMPLEMENTED':<17}{'M0':<6}{sc.REGISTRY[t].meta['name']}")
        print(f"\n{len(cat)} Katalogszenarien, {len(impl)} implementiert")
        return 0
    wanted = sorted(impl) if a.all else a.scenario
    if not wanted:
        ap.error("--list, --scenario ID oder --all angeben")
    db = a.db or atdb.target_db()
    if a.bootstrap:
        atdb.ensure_database(db)
    rc = 0
    for t in wanted:
        if t not in impl:
            print(f"{t}: NOT_IMPLEMENTED (Katalogszenario, Zielmeilenstein siehe --list)")
            rc = 2
            continue
        rec, path, err = sc.execute(t, db, a.clock_start, a.out)
        bad = [c for c in rec["soll_ist"] if not c["ok"]]
        print(f"{t}: {rec['result']} ({len(rec['soll_ist'])} Prüfungen, {len(bad)} fehlgeschlagen) -> {path}")
        for c in bad:
            print(f"   FEHLER {c['step']}: erwartet {c['expected']!r}, ist {c['actual']!r}")
        if rec["result"] != "PASSED":
            rc = 1
    return rc


if __name__ == "__main__":
    sys.exit(main())
