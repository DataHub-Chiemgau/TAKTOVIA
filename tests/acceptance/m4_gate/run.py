#!/usr/bin/env python3
"""Läufer der M4-Gate-Abnahmesuite (WP-M4-63).

  python tests/acceptance/m4_gate/run.py [--db taktovia_at_m4_gate] [--out tests/acceptance/evidence/m4] [--write-e2e] [--only AT-37,...]

Führt die Gate-Szenarien von M4 (PH-AT-012, -013, -015, -019, -021, AT-37, -39, -40, -41, -45, -47, -48, E2E-064 bis -068, -072, -073, -080 bis -084, COST-01, -02, -05, -07, -08 als `TECH-COST-xx`) sowie die
Querschnittsszenarien (Live-Dashboard, Invarianten mit parallelen Transaktionen, Contract-Fälle, Isolation, Barrierefreiheit) unter der Runtime-Rolle mit beiden Mandanten aus (Backend-Ergebnisse aus
`./gradlew build`, Datenbank- und Webtests sowie der Browserlauf werden im Szenario ausgeführt) und erzeugt je Szenario einen Evidenzdatensatz nach Kap. 08 §8.4 (ohne Fahrgastdaten) mit `source_key[]`, Build,
Migrationsstand, Konfigurationsrevision, Seed, Rollen, Soll/Ist und Ergebnis sowie eine Zusammenfassung. Mit --write-e2e trägt er Build, Nachweis und Entscheidung der **Katalogtests mit Zeile in e2e.csv**
ausschließlich aus diesem Lauf ein; die Entscheidung bleibt „Ausstehend“. Ein Szenario mit ausgewiesenen offenen Teilschritten (spätere Meilensteine, Fachentscheidungen, nicht ausführbare Läufe) gilt als
„Teilweise bestanden“. Exit 0 nur, wenn alle Szenarien grün sind (offene Teilschritte sind ausgewiesen, nie als bestanden gezählt)."""
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

# Katalogtests des Gates M4 (Kap. 08 §8.3 und WP-M4-63); die COST-Szenarien (Kap. 03 §7) laufen als technische Szenarien
CATALOG_IDS = ["PH-AT-012", "PH-AT-013", "PH-AT-015", "PH-AT-019", "PH-AT-021", "AT-37", "AT-39", "AT-40", "AT-41", "AT-45", "AT-47", "AT-48",
               "E2E-064", "E2E-065", "E2E-066", "E2E-067", "E2E-068", "E2E-072", "E2E-073", "E2E-080", "E2E-081", "E2E-082", "E2E-083", "E2E-084"]
COST_IDS = ["TECH-COST-01", "TECH-COST-02", "TECH-COST-05", "TECH-COST-07", "TECH-COST-08"]
CROSS_IDS = ["TECH-M4-LIVE", "TECH-M4-INVARIANTEN", "TECH-M4-CONTRACT", "TECH-M4-ISOLATION", "TECH-M4-A11Y"]
GATE_IDS = CATALOG_IDS + COST_IDS + CROSS_IDS
ALL_IDS = GATE_IDS
E2E_CSV = REPO / "docs" / "pflichtenheft" / "e2e.csv"
EVIDENCE_DIR = ACC / "evidence" / "m4"


def backend_root() -> Path:
    """Ablage der JUnit-Ergebnisse: `backend/` oder, mit TAKTOVIA_JUNIT_ROOT, gesicherte Ergebnisse eines vollständigen Laufs (Gradle ersetzt die Ergebnisse bei jedem Teillauf)."""
    return Path(os.environ.get("TAKTOVIA_JUNIT_ROOT", str(REPO / "backend")))


def build_id() -> str:
    def git(*a):
        return subprocess.run(["git", "-C", str(REPO), *a], capture_output=True, text=True).stdout.strip()
    return (git("rev-parse", "HEAD") or "unversioniert") + ("+lokal" if git("status", "--porcelain", "--untracked-files=no") else "")


def run(db_name: str, out_dir: Path, only=None, allow_missing_backend: bool = False) -> list[dict]:
    from m1_gate import junit
    from m2_gate import scenarios as m2
    from m4_gate import scenarios
    from taktovia_at import db as atdb
    from taktovia_at.scenario import execute
    os.environ["TAKTOVIA_BUILD_ID"] = build_id()
    results = junit.load_backend(backend_root())
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
    finally:
        atdb.drop_database(db_name)
    return records


def summary(records: list[dict]) -> dict:
    return {"build": records[0]["software_build"]["build_id"] if records else None,
            "tests": [{"id": r["test"]["id"], "name": r["test"]["name"], "result": r["result"], "run_id": r["run_id"], "checks": len(r["soll_ist"]), "source_key": r["source_key"],
                       "failed": [c["step"] for c in r["soll_ist"] if not c["ok"]], "offen": [c["step"] for c in r["soll_ist"] if c["step"].startswith("offen (nicht geprüft)")],
                       "evidence": Path(r["_path"]).name} for r in records]}


def status_of(record: dict) -> str:
    if record["result"] != "PASSED":
        return "Fehlgeschlagen"
    partial = any(c["step"].startswith("offen (nicht geprüft)") for c in record["soll_ist"])
    return "Teilweise bestanden (M4-Umfang, offene Teilschritte ausgewiesen)" if partial else "Bestanden (M4-Umfang)"


def write_e2e(records: list[dict], csv_path: Path = E2E_CSV) -> None:
    """Trägt Build, Nachweis und Entscheidung der M4-Gate-Tests aus Läufen ein (nur Zeilen dieser Suite); andere Zeilen bleiben unverändert."""
    by_id = {r["test"]["id"]: r for r in records if r["test"]["id"] in CATALOG_IDS}
    rows = list(csv.reader(io.StringIO(csv_path.read_text(encoding="utf-8")), delimiter=";"))
    head = rows[0]
    ix = {k: head.index(k) for k in ("test_id_original", "status", "build", "nachweis", "freigabe")}
    for row in rows[1:]:
        r = by_id.get(row[ix["test_id_original"]])
        if r is None:
            continue
        row[ix["status"]] = status_of(r)
        row[ix["build"]] = r["software_build"]["build_id"]
        row[ix["nachweis"]] = f"{r['run_id']}; tests/acceptance/evidence/m4/{Path(r['_path']).name}"
        row[ix["freigabe"]] = "Ausstehend (Auftraggeber, D-12)"
    out = io.StringIO()
    csv.writer(out, delimiter=";", lineterminator="\n").writerows(rows)
    csv_path.write_text(out.getvalue(), encoding="utf-8")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default="taktovia_at_m4_gate")
    ap.add_argument("--out", default=str(EVIDENCE_DIR))
    ap.add_argument("--write-e2e", action="store_true")
    ap.add_argument("--only")
    ap.add_argument("--allow-missing-backend", action="store_true")
    a = ap.parse_args(argv)
    out = Path(a.out)
    if not a.only and out.exists():
        for old in out.glob("*.json"):
            old.unlink()
    records = run(a.db, out, a.only.split(",") if a.only else None, a.allow_missing_backend)
    s = summary(records)
    out.mkdir(parents=True, exist_ok=True)
    if not a.only:
        (out / "zusammenfassung.json").write_text(json.dumps(s, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
    for t in s["tests"]:
        print(f"{t['id']:20} {t['result']:7} {t['checks']:3} Prüfungen, {len(t['offen'])} offen" + (f"  rot: {t['failed']}" if t["failed"] else ""))
    ok = all(t["result"] == "PASSED" for t in s["tests"])
    if a.write_e2e and not a.only:
        write_e2e(records)
        print("e2e.csv aus dem Lauf aktualisiert")
    print("M4-Gate-Suite:", "GRÜN" if ok else "ROT")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
