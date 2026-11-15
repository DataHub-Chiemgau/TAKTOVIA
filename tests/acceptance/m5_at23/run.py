#!/usr/bin/env python3
"""Läufer für AT-23 (WP-M5-15): führt die Ausfallübung als Szenario aus, schreibt den Evidenzdatensatz nach tests/acceptance/evidence/m5/ und trägt mit --write-e2e Build, Nachweis und Entscheidung
(Ausstehend) ausschließlich aus diesem Lauf in docs/pflichtenheft/e2e.csv ein. Zusätzlich wird das Übungsprotokoll nach tests/performance/protokolle/ausfalluebung-m5-15.json geschrieben.

  python tests/acceptance/m5_at23/run.py [--db taktovia_at_m5_at23] [--out tests/acceptance/evidence/m5] [--mit-anwendung] [--write-e2e]

Status in e2e.csv: `Bestanden (Übung lokal, vorläufige Zielwerte)`; die Abnahme trifft der Auftraggeber (D-12), die Zielwerte sind vorläufig (D-05)."""
import argparse
import csv
import io
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ACC = HERE.parent
REPO = ACC.parents[1]
sys.path.insert(0, str(ACC))
E2E_CSV = REPO / "docs" / "pflichtenheft" / "e2e.csv"
EVIDENCE_DIR = ACC / "evidence" / "m5"
PROTOKOLL = REPO / "tests" / "performance" / "protokolle" / "ausfalluebung-m5-15.json"
TEST_ID = "AT-23"
STATUS_OK = "Bestanden (Übung lokal, vorläufige Zielwerte)"


def run(db_name, out_dir, mit_anwendung=False, protokoll=PROTOKOLL):
    from m1_gate.run import build_id
    from m5_at23 import scenarios
    from taktovia_at import db as atdb
    from taktovia_at.scenario import execute
    os.environ["TAKTOVIA_BUILD_ID"] = build_id()
    scenarios.CONFIG.update({"mit_anwendung": mit_anwendung, "ergebnis": None, "protokoll_out": str(protokoll) if protokoll else None})
    atdb.reset_database(db_name)
    try:
        record, path, _ = execute(TEST_ID, db_name, out_dir=out_dir)
        record["_path"] = str(path)
    finally:
        atdb.drop_database(db_name)
    return record


def write_e2e(record, csv_path=E2E_CSV):
    rows = list(csv.reader(io.StringIO(csv_path.read_text(encoding="utf-8")), delimiter=";"))
    ix = {k: rows[0].index(k) for k in ("test_id_original", "status", "build", "nachweis", "freigabe")}
    for row in rows[1:]:
        if row[ix["test_id_original"]] == TEST_ID:
            row[ix["status"]] = STATUS_OK if record["result"] == "PASSED" else "Fehlgeschlagen"
            row[ix["build"]] = record["software_build"]["build_id"]
            row[ix["nachweis"]] = f"{record['run_id']}; tests/acceptance/evidence/m5/{Path(record['_path']).name}"
            row[ix["freigabe"]] = "Ausstehend (Auftraggeber, D-12)"
    out = io.StringIO(); csv.writer(out, delimiter=";", lineterminator="\n").writerows(rows)
    csv_path.write_text(out.getvalue(), encoding="utf-8")


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default="taktovia_at_m5_at23")
    ap.add_argument("--out", default=str(EVIDENCE_DIR))
    ap.add_argument("--mit-anwendung", action="store_true")
    ap.add_argument("--write-e2e", action="store_true")
    a = ap.parse_args(argv)
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    for old in out.glob("AT-23-*.json"):
        old.unlink()
    record = run(a.db, out, a.mit_anwendung)
    failed = [c["step"] for c in record["soll_ist"] if not c["ok"]]
    print(f"AT-23 {record['result']} {len(record['soll_ist'])} Prüfungen" + (f"  rot: {failed}" if failed else ""))
    if a.write_e2e:
        write_e2e(record); print("e2e.csv aus dem Lauf aktualisiert")
    return 0 if record["result"] == "PASSED" else 1


if __name__ == "__main__":
    sys.exit(main())
