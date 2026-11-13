#!/usr/bin/env python3
"""Läufer der M2-Gate-Abnahmesuite (WP-M2-48).

  python tests/acceptance/m2_gate/run.py [--db taktovia_at_m2_gate] [--out tests/acceptance/evidence/m2] [--write-e2e] [--only AT-03,...]

Führt die Gate-Szenarien unter der Runtime-Rolle aus (Backend-Ergebnisse aus `./gradlew build`, Datenbank-, Web- und Browsertests werden im Szenario ausgeführt), erzeugt je Szenario einen
Evidenzdatensatz nach Kap. 08 §8.4 (ohne Fahrgastdaten) und eine Zusammenfassung. Mit --write-e2e trägt er Build, Nachweis und Entscheidung der **Katalogtests mit Zeile in e2e.csv**
(AT-01 bis AT-19 dieser Suite) ausschließlich aus diesem Lauf ein; die Entscheidung bleibt „Ausstehend“. PH-AT-004/-010 und die M2-Anteile von PH-AT-001/-011/-018, E2E-096/-098 stehen nur
in der Evidenz und der Zusammenfassung (an die M1-Gate-Suite zurückgemeldet; deren Zeilen in e2e.csv bleiben unverändert). Exit 0 nur, wenn alle Szenarien grün sind."""
import argparse
import csv
import io
import json
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ACC = HERE.parent
REPO = ACC.parents[1]
sys.path.insert(0, str(ACC))

GATE_IDS = ["PH-AT-004", "AT-01", "AT-02", "AT-03", "AT-04", "AT-05", "AT-07", "AT-13", "AT-15", "AT-19"]
M1_SHARES = ["PH-AT-001", "PH-AT-010", "PH-AT-011", "PH-AT-018", "E2E-096", "E2E-098"]    # M2-Anteile, an die M1-Gate-Suite zurückgemeldet
ALL_IDS = GATE_IDS + M1_SHARES
E2E_CSV = REPO / "docs" / "pflichtenheft" / "e2e.csv"
EVIDENCE_DIR = ACC / "evidence" / "m2"
STATUS_PASSED = {"AT-01": "Teilweise bestanden (M2-Umfang)", "AT-13": "Teilweise bestanden (M2-Umfang)", "AT-19": "Teilweise bestanden (automatisiert, manuelles Protokoll offen)"}


def build_id() -> str:
    def git(*a):
        return subprocess.run(["git", "-C", str(REPO), *a], capture_output=True, text=True).stdout.strip()
    return (git("rev-parse", "HEAD") or "unversioniert") + ("+lokal" if git("status", "--porcelain", "--untracked-files=no") else "")


def run(db_name: str, out_dir: Path, only=None, allow_missing_backend: bool = False) -> list[dict]:
    from m1_gate import junit
    from m2_gate import scenarios
    from taktovia_at import db as atdb
    from taktovia_at.scenario import execute
    os.environ["TAKTOVIA_BUILD_ID"] = build_id()
    results = junit.load_backend()
    scenarios.BACKEND_RESULTS["results"] = results or None
    if not results and not allow_missing_backend:
        print("Hinweis: keine JUnit-Ergebnisse unter backend/**/build/test-results (zuerst ./gradlew build); die Backend-Prüfungen werden rot.", file=sys.stderr)
    atdb.reset_database(db_name)
    records = []
    try:
        for tid in (only or ALL_IDS):
            record, path, error = execute(tid, db_name, out_dir=out_dir, fn=scenarios.SCENARIOS[tid])
            record["_path"] = str(path)
            records.append(record)
    finally:
        atdb.drop_database(db_name)
    return records


def summary(records: list[dict]) -> dict:
    return {"build": records[0]["software_build"]["build_id"] if records else None,
            "tests": [{"id": r["test"]["id"], "name": r["test"]["name"], "result": r["result"], "run_id": r["run_id"], "checks": len(r["soll_ist"]),
                       "failed": [c["step"] for c in r["soll_ist"] if not c["ok"]], "offen": [c["step"] for c in r["soll_ist"] if c["step"].startswith("offen (nicht geprüft)")],
                       "evidence": Path(r["_path"]).name} for r in records]}


def write_e2e(records: list[dict], csv_path: Path = E2E_CSV) -> None:
    """Trägt Build, Nachweis und Entscheidung der M2-Gate-Tests aus Läufen ein (nur AT-Zeilen dieser Suite); andere Zeilen bleiben unverändert."""
    by_id = {r["test"]["id"]: r for r in records if r["test"]["id"] in GATE_IDS}
    rows = list(csv.reader(io.StringIO(csv_path.read_text(encoding="utf-8")), delimiter=";"))
    head = rows[0]
    ix = {k: head.index(k) for k in ("test_id_original", "status", "build", "nachweis", "freigabe")}
    for row in rows[1:]:
        r = by_id.get(row[ix["test_id_original"]])
        if r is None:
            continue
        row[ix["status"]] = STATUS_PASSED.get(r["test"]["id"], "Bestanden (M2-Umfang)") if r["result"] == "PASSED" else "Fehlgeschlagen"
        row[ix["build"]] = r["software_build"]["build_id"]
        row[ix["nachweis"]] = f"{r['run_id']}; tests/acceptance/evidence/m2/{Path(r['_path']).name}"
        row[ix["freigabe"]] = "Ausstehend (Auftraggeber, D-12)"
    out = io.StringIO()
    csv.writer(out, delimiter=";", lineterminator="\n").writerows(rows)
    csv_path.write_text(out.getvalue(), encoding="utf-8")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default="taktovia_at_m2_gate")
    ap.add_argument("--out", default=str(EVIDENCE_DIR))
    ap.add_argument("--write-e2e", action="store_true")
    ap.add_argument("--only")
    ap.add_argument("--allow-missing-backend", action="store_true")
    a = ap.parse_args(argv)
    out = Path(a.out)
    if not a.only:
        for old in out.glob("*.json") if out.exists() else []:
            old.unlink()
    records = run(a.db, out, a.only.split(",") if a.only else None, a.allow_missing_backend)
    s = summary(records)
    out.mkdir(parents=True, exist_ok=True)
    if not a.only:
        (out / "zusammenfassung.json").write_text(json.dumps(s, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
    for t in s["tests"]:
        print(f"{t['id']:10} {t['result']:7} {t['checks']:3} Prüfungen, {len(t['offen'])} offen" + (f"  rot: {t['failed']}" if t["failed"] else ""))
    ok = all(t["result"] == "PASSED" for t in s["tests"])
    if a.write_e2e and not a.only:
        write_e2e(records)
        print("e2e.csv aus dem Lauf aktualisiert")
    print("M2-Gate-Suite:", "GRÜN" if ok else "ROT")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
