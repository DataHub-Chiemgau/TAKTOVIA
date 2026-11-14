#!/usr/bin/env python3
"""Läufer der M3-Gate-Abnahmesuite (WP-M3-56).

  python tests/acceptance/m3_gate/run.py [--db taktovia_at_m3_gate] [--out tests/acceptance/evidence/m3] [--write-e2e] [--only AT-09,...] [--no-regression]

Führt die 19 Gate-Szenarien (PH-AT-020, AT-08 bis AT-11, AT-26 bis AT-36, AT-50, AT-51, AT-60) unter der Runtime-Rolle mit beiden Mandanten aus (Backend-Ergebnisse aus `./gradlew build`, Datenbank- und
Webtests werden im Szenario ausgeführt), danach die Regression der M2-Gate-Tests (PH-AT-004, AT-01 bis AT-05, Akzeptanzkriterium 9), erzeugt je Szenario einen Evidenzdatensatz nach Kap. 08 §8.4 (ohne
Fahrgastdaten) und eine Zusammenfassung. Mit --write-e2e trägt er Build, Nachweis und Entscheidung der **Katalogtests mit Zeile in e2e.csv** ausschließlich aus diesem Lauf ein; die Entscheidung bleibt
„Ausstehend“. Ein Szenario mit ausgewiesenen offenen Teilschritten (spätere Meilensteine, Fachentscheidungen, nicht ausführbare Flutter-Läufe) gilt als „Teilweise bestanden“. Exit 0 nur, wenn alle Szenarien grün sind."""
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

GATE_IDS = ["PH-AT-020", "AT-08", "AT-09", "AT-10", "AT-11", "AT-26", "AT-27", "AT-28", "AT-29", "AT-30", "AT-31", "AT-32", "AT-33", "AT-34", "AT-35", "AT-36", "AT-50", "AT-51", "AT-60"]
REGRESSION_IDS = ["PH-AT-004", "AT-01", "AT-02", "AT-03", "AT-04", "AT-05"]    # M2-Gate-Regression nach Ablösung der M2-Fixtures durch die M3-Module (Akzeptanzkriterium 9)
ALL_IDS = GATE_IDS
E2E_CSV = REPO / "docs" / "pflichtenheft" / "e2e.csv"
EVIDENCE_DIR = ACC / "evidence" / "m3"


def build_id() -> str:
    def git(*a):
        return subprocess.run(["git", "-C", str(REPO), *a], capture_output=True, text=True).stdout.strip()
    return (git("rev-parse", "HEAD") or "unversioniert") + ("+lokal" if git("status", "--porcelain", "--untracked-files=no") else "")


def run(db_name: str, out_dir: Path, only=None, allow_missing_backend: bool = False, regression: bool = True) -> list[dict]:
    from m1_gate import junit
    from m2_gate import scenarios as m2
    from m3_gate import scenarios
    from taktovia_at import db as atdb
    from taktovia_at.scenario import execute
    os.environ["TAKTOVIA_BUILD_ID"] = build_id()
    results = junit.load_backend(Path(os.environ.get("TAKTOVIA_JUNIT_ROOT", str(REPO / "backend"))))   # TAKTOVIA_JUNIT_ROOT: gesicherte Ergebnisse eines vollständigen Laufs
    m2.BACKEND_RESULTS["results"] = results or None
    if not results and not allow_missing_backend:
        print("Hinweis: keine JUnit-Ergebnisse unter backend/**/build/test-results (zuerst ./gradlew build); die Backend-Prüfungen werden rot.", file=sys.stderr)
    atdb.reset_database(db_name)
    records = []
    try:
        for tid in (only or ALL_IDS):
            record, path, error = execute(tid, db_name, out_dir=out_dir, fn=scenarios.SCENARIOS[tid])
            record["_path"] = str(path)
            records.append(record)
        if regression and not only:
            for tid in REGRESSION_IDS:
                record, path, error = execute(tid, db_name, out_dir=out_dir / "regression-m2", fn=m2.SCENARIOS[tid])
                record["_path"] = str(path)
                record["_regression"] = True
                records.append(record)
    finally:
        atdb.drop_database(db_name)
    return records


def summary(records: list[dict]) -> dict:
    return {"build": records[0]["software_build"]["build_id"] if records else None,
            "tests": [{"id": r["test"]["id"], "name": r["test"]["name"], "result": r["result"], "run_id": r["run_id"], "checks": len(r["soll_ist"]), "regression_m2": bool(r.get("_regression")),
                       "failed": [c["step"] for c in r["soll_ist"] if not c["ok"]], "offen": [c["step"] for c in r["soll_ist"] if c["step"].startswith("offen (nicht geprüft)")],
                       "evidence": (("regression-m2/" if r.get("_regression") else "") + Path(r["_path"]).name)} for r in records]}


def status_of(record: dict) -> str:
    if record["result"] != "PASSED":
        return "Fehlgeschlagen"
    partial = any(c["step"].startswith("offen (nicht geprüft)") for c in record["soll_ist"])
    return "Teilweise bestanden (M3-Umfang, offene Teilschritte ausgewiesen)" if partial else "Bestanden (M3-Umfang)"


def write_e2e(records: list[dict], csv_path: Path = E2E_CSV) -> None:
    """Trägt Build, Nachweis und Entscheidung der M3-Gate-Tests aus Läufen ein (nur Zeilen dieser Suite, Regressionsläufe nie); andere Zeilen bleiben unverändert."""
    by_id = {r["test"]["id"]: r for r in records if r["test"]["id"] in GATE_IDS and not r.get("_regression")}
    rows = list(csv.reader(io.StringIO(csv_path.read_text(encoding="utf-8")), delimiter=";"))
    head = rows[0]
    ix = {k: head.index(k) for k in ("test_id_original", "status", "build", "nachweis", "freigabe")}
    for row in rows[1:]:
        r = by_id.get(row[ix["test_id_original"]])
        if r is None:
            continue
        row[ix["status"]] = status_of(r)
        row[ix["build"]] = r["software_build"]["build_id"]
        row[ix["nachweis"]] = f"{r['run_id']}; tests/acceptance/evidence/m3/{Path(r['_path']).name}"
        row[ix["freigabe"]] = "Ausstehend (Auftraggeber, D-12)"
    out = io.StringIO()
    csv.writer(out, delimiter=";", lineterminator="\n").writerows(rows)
    csv_path.write_text(out.getvalue(), encoding="utf-8")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default="taktovia_at_m3_gate")
    ap.add_argument("--out", default=str(EVIDENCE_DIR))
    ap.add_argument("--write-e2e", action="store_true")
    ap.add_argument("--only")
    ap.add_argument("--no-regression", action="store_true")
    ap.add_argument("--allow-missing-backend", action="store_true")
    a = ap.parse_args(argv)
    out = Path(a.out)
    if not a.only:
        for old in list(out.glob("*.json")) + list((out / "regression-m2").glob("*.json")) if out.exists() else []:
            old.unlink()
    records = run(a.db, out, a.only.split(",") if a.only else None, a.allow_missing_backend, regression=not a.no_regression)
    s = summary(records)
    out.mkdir(parents=True, exist_ok=True)
    if not a.only:
        (out / "zusammenfassung.json").write_text(json.dumps(s, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
    for t in s["tests"]:
        print(f"{t['id']:10} {t['result']:7} {t['checks']:3} Prüfungen, {len(t['offen'])} offen" + ("  (M2-Regression)" if t["regression_m2"] else "") + (f"  rot: {t['failed']}" if t["failed"] else ""))
    ok = all(t["result"] == "PASSED" for t in s["tests"])
    if a.write_e2e and not a.only:
        write_e2e(records)
        print("e2e.csv aus dem Lauf aktualisiert")
    print("M3-Gate-Suite:", "GRÜN" if ok else "ROT")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
