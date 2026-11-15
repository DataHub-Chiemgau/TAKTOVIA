#!/usr/bin/env python3
"""Läufer der Gate-Szenarien AT-25, AT-53, AT-54, AT-61 (WP-M5-31): führt sie unter der Runtime-Rolle aus, schreibt Evidenz nach tests/acceptance/evidence/m5/ und trägt mit --write-e2e Build, Nachweis
und Entscheidung (Ausstehend) ausschließlich aus diesem Lauf in docs/pflichtenheft/e2e.csv ein.

  python tests/acceptance/m5_gate/run.py [--db taktovia_at_m5_gate] [--out tests/acceptance/evidence/m5] [--write-e2e] [--only AT-25,...]

Die Backend-Ergebnisse stammen aus ./gradlew build (JUnit-XML), die Webtests werden ausgeführt (npm ci zuerst). Status in e2e.csv: `Teilweise bestanden (P1-Mindestumfang, offene Teilschritte ausgewiesen)`,
solange offene Teilschritte ausgewiesen sind; `Fehlgeschlagen` bei einem roten Ergebnis. Die Abnahme trifft der Auftraggeber."""
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
IDS = ["AT-25", "AT-53", "AT-54", "AT-61"]
STATUS = "Teilweise bestanden (P1-Mindestumfang, offene Teilschritte ausgewiesen)"


def build_id() -> str:
    from m1_gate.run import build_id as b
    return b()


def run(db_name, out_dir, only=None):
    from m1_gate import junit
    from m5_gate import scenarios
    from taktovia_at import db as atdb
    from taktovia_at.scenario import execute
    os.environ["TAKTOVIA_BUILD_ID"] = build_id()
    scenarios.BACKEND_RESULTS["results"] = junit.load_backend() or None
    atdb.reset_database(db_name)
    records = []
    try:
        for tid in (only or IDS):
            record, path, _ = execute(tid, db_name, out_dir=out_dir, fn=scenarios.SCENARIOS[tid])
            record["_path"] = str(path)
            records.append(record)
    finally:
        atdb.drop_database(db_name)
    return records


def write_e2e(records, csv_path=E2E_CSV):
    rows = list(csv.reader(io.StringIO(csv_path.read_text(encoding="utf-8")), delimiter=";"))
    ix = {k: rows[0].index(k) for k in ("test_id_original", "status", "build", "nachweis", "freigabe")}
    for rec in records:
        for row in rows[1:]:
            if row[ix["test_id_original"]] == rec["test"]["id"]:
                offen = any(c["step"].startswith("offen (nicht geprüft)") for c in rec["soll_ist"])
                row[ix["status"]] = "Fehlgeschlagen" if rec["result"] != "PASSED" else (STATUS if offen else "Bestanden (P1-Mindestumfang)")
                row[ix["build"]] = rec["software_build"]["build_id"]
                row[ix["nachweis"]] = f"{rec['run_id']}; tests/acceptance/evidence/m5/{Path(rec['_path']).name}"
                row[ix["freigabe"]] = "Ausstehend (Auftraggeber, D-12)"
    out = io.StringIO()
    csv.writer(out, delimiter=";", lineterminator="\n").writerows(rows)
    csv_path.write_text(out.getvalue(), encoding="utf-8")


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default="taktovia_at_m5_gate")
    ap.add_argument("--out", default=str(EVIDENCE_DIR))
    ap.add_argument("--write-e2e", action="store_true")
    ap.add_argument("--only")
    a = ap.parse_args(argv)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    only = a.only.split(",") if a.only else None
    for tid in (only or IDS):
        for old in out.glob(f"{tid}-*.json"):
            old.unlink()
    records = run(a.db, out, only)
    ok = True
    for r in records:
        failed = [c["step"] for c in r["soll_ist"] if not c["ok"]]
        ok &= r["result"] == "PASSED"
        print(f"{r['test']['id']} {r['result']} {len(r['soll_ist'])} Prüfungen" + (f"  rot: {failed}" if failed else ""))
    if a.write_e2e:
        write_e2e(records)
        print("e2e.csv aus dem Lauf aktualisiert")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
