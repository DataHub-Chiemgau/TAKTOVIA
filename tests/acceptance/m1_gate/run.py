#!/usr/bin/env python3
"""Läufer der M1-Gate-Abnahmesuite (WP-M1-45).

  python tests/acceptance/m1_gate/run.py [--db taktovia_at_m1_gate] [--out tests/acceptance/evidence/m1] [--write-e2e] [--allow-missing-backend]

Führt die elf Gate-Szenarien unter der Runtime-Rolle aus, erzeugt je Szenario einen Evidenzdatensatz (Kap. 08 §8.4: source_key[], Test-ID/Version, Build, Migrationsstand,
Konfigurationsrevision, Seed, Rollen/Mandanten, Soll/Ist, Trace-IDs, Ergebnis, Mangelklasse) und schreibt eine Zusammenfassung. Mit --write-e2e trägt er Build, Nachweis und
Entscheidung der Gate-Tests ausschließlich aus diesem Lauf in docs/pflichtenheft/e2e.csv ein (Entscheidung bleibt „Ausstehend“: die Abnahme trifft der Auftraggeber).
Die Backend-Ergebnisse stammen aus ./gradlew build (JUnit-XML); ohne sie sind die verknüpften Backend-Prüfungen rot, außer mit --allow-missing-backend (nur für Entwicklung).
Exit-Code 0 nur, wenn alle elf Szenarien bestanden sind."""
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

GATE_IDS = ["PH-AT-001", "PH-AT-011", "PH-AT-017", "PH-AT-018", "AT-18", "AT-24", "AT-49", "E2E-091", "E2E-096", "E2E-097", "E2E-098"]
E2E_CSV = REPO / "docs" / "pflichtenheft" / "e2e.csv"
EVIDENCE_DIR = ACC / "evidence" / "m1"


def build_id() -> str:
    """Git-Commit des geprüften Standes; `+lokal` bei nicht eingecheckten Änderungen (der Lauf prüft dann den Arbeitsstand)."""
    def git(*a):
        return subprocess.run(["git", "-C", str(REPO), *a], capture_output=True, text=True).stdout.strip()
    head = git("rev-parse", "HEAD")
    dirty = bool(git("status", "--porcelain", "--untracked-files=no"))
    return (head or "unversioniert") + ("+lokal" if dirty else "")


def run(db_name: str, out_dir: Path, allow_missing_backend: bool = False) -> list[dict]:
    from m1_gate import junit, scenarios
    from taktovia_at import db as atdb
    from taktovia_at.scenario import execute
    os.environ["TAKTOVIA_BUILD_ID"] = build_id()
    results = junit.load_backend()
    scenarios.BACKEND_RESULTS["results"] = results or None
    if not results and not allow_missing_backend:
        print("Hinweis: keine JUnit-Ergebnisse unter backend/**/build/test-results gefunden (zuerst ./gradlew build); die Backend-Prüfungen werden rot.", file=sys.stderr)
    atdb.reset_database(db_name)
    records = []
    try:
        for tid in GATE_IDS:
            record, path, error = execute(tid, db_name, out_dir=out_dir)
            record["_path"] = str(path)
            records.append(record)
    finally:
        atdb.drop_database(db_name)
    return records


def summary(records: list[dict]) -> dict:
    return {"build": records[0]["software_build"]["build_id"] if records else None,
            "tests": [{"id": r["test"]["id"], "result": r["result"], "run_id": r["run_id"], "checks": len(r["soll_ist"]),
                       "failed": [c["step"] for c in r["soll_ist"] if not c["ok"]], "evidence": Path(r["_path"]).name} for r in records]}


def write_e2e(records: list[dict], csv_path: Path = E2E_CSV) -> None:
    """Trägt Build, Nachweis und Entscheidung der Gate-Tests ausschließlich aus Läufen ein; andere Zeilen bleiben unverändert."""
    by_id = {r["test"]["id"]: r for r in records}
    text = csv_path.read_text(encoding="utf-8")
    rows = list(csv.reader(io.StringIO(text), delimiter=";"))
    head = rows[0]
    ix = {k: head.index(k) for k in ("test_id_original", "status", "build", "nachweis", "freigabe")}
    for row in rows[1:]:
        r = by_id.get(row[ix["test_id_original"]])
        if r is None:
            continue
        row[ix["status"]] = "Bestanden (M1-Umfang)" if r["result"] == "PASSED" else "Fehlgeschlagen"
        row[ix["build"]] = r["software_build"]["build_id"]
        row[ix["nachweis"]] = f"{r['run_id']}; tests/acceptance/evidence/m1/{Path(r['_path']).name}"
        row[ix["freigabe"]] = "Ausstehend (Auftraggeber, D-12)"
    out = io.StringIO()
    csv.writer(out, delimiter=";", lineterminator="\n").writerows(rows)
    csv_path.write_text(out.getvalue(), encoding="utf-8")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default="taktovia_at_m1_gate")
    ap.add_argument("--out", default=str(EVIDENCE_DIR))
    ap.add_argument("--write-e2e", action="store_true")
    ap.add_argument("--allow-missing-backend", action="store_true")
    a = ap.parse_args(argv)
    out = Path(a.out)
    for old in out.glob("*.json") if out.exists() else []:
        old.unlink()
    records = run(a.db, out, a.allow_missing_backend)
    s = summary(records)
    out.mkdir(parents=True, exist_ok=True)
    (out / "zusammenfassung.json").write_text(json.dumps(s, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
    for t in s["tests"]:
        print(f"{t['id']:10} {t['result']:7} {t['checks']:3} Prüfungen" + (f"  rot: {t['failed']}" if t["failed"] else ""))
    ok = all(t["result"] == "PASSED" for t in s["tests"])
    if a.write_e2e:
        write_e2e(records)
        print("e2e.csv aus dem Lauf aktualisiert")
    print("M1-Gate-Suite:", "GRÜN" if ok else "ROT")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
