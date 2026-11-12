#!/usr/bin/env python3
"""Rückverfolgbarkeit und CI-Prüfungen der M1-Gate-Suite (WP-M1-45).

  traceability.py            Bericht: je M1-Anforderung Detailvertrag und Testfall; Verstöße gegen die Gate-Regeln
Regeln (Verstoß = Exitcode 1):
  R1  keine als `Umgesetzt`/`Abgenommen` markierte MUSS-Zeile ohne Testfall (und ohne Detailvertrag)
  R2  jede M1-Anforderung trägt Detailvertrag und Testfall in arbeitspakete.csv (Bericht; Verstoß nur mit --strict)
  R3  e2e.csv: jede Zeile außer `Nicht ausgeführt` gehört zu einem Gate-Test, trägt Build und Nachweis aus einem Lauf, und der Nachweis (Evidenzdatei) existiert, passt zur
      Lauf-ID und zum Ergebnis; Zeilen anderer Meilensteine bleiben `Nicht ausgeführt`
Ausführbarer positiver Test (R2, streng): ein Eintrag `pfad::testname` (Datei existiert, Name kommt vor) oder eine Katalog-ID; `geplant:` zählt nicht."""
import csv
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
GATE_IDS = ["PH-AT-001", "PH-AT-011", "PH-AT-017", "PH-AT-018", "AT-18", "AT-24", "AT-49", "E2E-091", "E2E-096", "E2E-097", "E2E-098"]
GREEN = {"Umgesetzt", "Abgenommen"}
CATALOG_ID = re.compile(r"^(AT-\d{2}|E2E-\d{3}|PH-AT-\d{3})$")


def rd(p: Path, delim=";"):
    with p.open(encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f, delimiter=delim))


def executable(token: str, root: Path) -> bool:
    token = token.strip()
    if token.startswith("geplant:") or not token:
        return False
    if CATALOG_ID.match(token):
        return True
    if "::" not in token:
        return False
    path, name = token.split("::", 1)
    p = root / path
    return p.is_file() and name.split("[")[0] in p.read_text(encoding="utf-8", errors="replace")


def check(root: Path = REPO, strict: bool = False) -> tuple[list[str], dict]:
    errors, stats = [], {}
    reqs = [r for r in rd(root / "docs/umsetzungsplan/arbeitspakete.csv") if r["meilenstein"] == "M1"]
    stats["anforderungen"] = len(reqs)
    no_exec = []
    for r in reqs:
        tokens = [t for t in r["testfall"].split(";") if t.strip()]
        if r["status"] in GREEN and r["prioritaet_original"] == "MUSS" and (not r["detailvertrag"].strip() or not tokens):
            errors.append(f"R1 {r['source_key']}: als {r['status']} markierte MUSS-Zeile ohne Detailvertrag oder Testfall")
        if not r["detailvertrag"].strip() or not tokens:
            no_exec.append(r["source_key"]); continue
        if not any(executable(t, root) for t in tokens):
            no_exec.append(r["source_key"])
    stats["ohne_ausfuehrbaren_positiven_test"] = sorted(no_exec)
    if strict and no_exec:
        errors += [f"R2 {k}: kein ausführbarer positiver Test eingetragen" for k in sorted(no_exec)]
    e2e = rd(root / "docs/pflichtenheft/e2e.csv")
    ev_dir = root / "tests/acceptance/evidence/m1"
    for r in e2e:
        tid, status = r["test_id_original"], r["status"]
        if status == "Nicht ausgeführt":
            if r["build"] or r["nachweis"]:
                errors.append(f"R3 {tid}: Status 'Nicht ausgeführt', aber Build/Nachweis eingetragen")
            continue
        if tid not in GATE_IDS:
            errors.append(f"R3 {tid}: Status '{status}' ohne Lauf der M1-Gate-Suite")
            continue
        m = re.match(r"(run-[0-9a-f]{16}); (tests/acceptance/evidence/m1/[\w.-]+\.json)$", r["nachweis"])
        if not r["build"] or not m:
            errors.append(f"R3 {tid}: Build oder Nachweis (Lauf-ID; Evidenzdatei) fehlt")
            continue
        f = root / m.group(2)
        if not f.is_file():
            errors.append(f"R3 {tid}: Evidenzdatei fehlt: {m.group(2)}"); continue
        ev = json.loads(f.read_text(encoding="utf-8"))
        if ev["run_id"] != m.group(1) or ev["test"]["id"] != tid or ev["software_build"]["build_id"] != r["build"]:
            errors.append(f"R3 {tid}: Nachweis passt nicht zur Evidenzdatei (Lauf-ID, Test oder Build)")
        if status.startswith("Bestanden") != (ev["result"] == "PASSED"):
            errors.append(f"R3 {tid}: Status '{status}' widerspricht dem Ergebnis der Evidenz ({ev['result']})")
        if ev["acceptance_decision"] != "PENDING" or "Ausstehend" not in r["freigabe"]:
            errors.append(f"R3 {tid}: Abnahmeentscheidung darf nicht aus dem Lauf stammen (Auftraggeber)")
    return errors, stats


def main(argv=None) -> int:
    strict = "--strict" in (argv or sys.argv[1:])
    errors, stats = check(strict=strict)
    print(f"M1-Rückverfolgbarkeit: {stats['anforderungen']} Anforderungen, {len(stats['ohne_ausfuehrbaren_positiven_test'])} noch ohne ausführbaren positiven Test")
    for e in errors:
        print("FEHLER", e)
    print("Ergebnis:", "FEHLER" if errors else "PASS")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
