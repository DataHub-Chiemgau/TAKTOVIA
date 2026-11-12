"""Statische Prüfung der Staging- und Performance-Umgebung (WP-M5-02): Größenprotokoll, Referenzbestand, Aufbauskript. Startet nichts (kein Docker, kein Terraform)."""
import json
import pathlib

MIN_REFERENCE = {"mandanten": 2, "fahrzeuge_je_mandant": 250, "buchungen_pro_tag_je_mandant": 30000}
REQUIRED_COMPONENTS = ("app", "datenbank", "broker", "cache", "routing", "lastgenerator")


def check(root, errs, notes):
    d = pathlib.Path(root) / "umgebungen"
    g = json.loads((d / "groessen.json").read_text(encoding="utf-8"))
    matrix = json.loads((pathlib.Path(root) / "umgebungsmatrix.json").read_text(encoding="utf-8"))["umgebungen"]
    if "D-05" not in g.get("status", ""):
        errs.append("umgebungen: groessen.json nennt den Status 'vorläufig, D-05 offen' nicht")
    ref = g.get("referenzbestand", {})
    for k, v in MIN_REFERENCE.items():
        if ref.get(k, 0) < v:
            errs.append(f"umgebungen: Referenzbestand {k} < {v}")
    envs = g.get("umgebungen", {})
    if set(envs) != {"staging", "performance"}:
        errs.append("umgebungen: genau staging und performance erwartet")
    for name, e in envs.items():
        if name not in matrix:
            errs.append(f"umgebungen: {name} fehlt in umgebungsmatrix.json")
            continue
        if e.get("psp") not in ("simulator", "sandbox") or matrix[name]["psp"]["modus"] not in ("simulator", "sandbox"):
            errs.append(f"umgebungen: {name} nutzt kein PSP-Simulator-/Sandbox-Konto")
        comps = e.get("komponenten", {})
        for c in REQUIRED_COMPONENTS:
            if c not in comps:
                errs.append(f"umgebungen: {name}: Komponente {c} fehlt im Größenprotokoll")
            elif comps[c] is not None and not all(isinstance(comps[c].get(k), int) and comps[c][k] > 0 for k in ("instanzen", "vcpu", "ram_gb")):
                errs.append(f"umgebungen: {name}: {c} ohne positive Größen")
        if name == "performance" and not comps.get("lastgenerator"):
            errs.append("umgebungen: performance braucht einen Lastgenerator")
        for k, v in MIN_REFERENCE.items():
            key = "mandanten" if k == "mandanten" else k
            if e.get("datenbestand", {}).get(key, 0) < v:
                errs.append(f"umgebungen: {name}: Datenbestand {key} < Referenz {v}")
    for a, b in (("staging", "performance"),):
        for k in ("secrets", "psp", "identitaet", "konto", "netz"):
            if matrix[a].get(k) == matrix[b].get(k):
                errs.append(f"umgebungen: {a} und {b} teilen {k}")
    script = (d / "aufbau.sh").read_text(encoding="utf-8")
    for needle in ("db.py\" bootstrap", "db.py\" migrate", "migrationsstand.py", "fill.py", "--provision"):
        if needle not in script:
            errs.append(f"umgebungen: aufbau.sh ohne Schritt {needle}")
    if "staging|performance" not in script:
        errs.append("umgebungen: aufbau.sh schließt Produktion nicht aus")
    notes.append("umgebungen: Größenprotokoll, Referenzbestand und Aufbauskript statisch geprüft (Aufbau in realer Umgebung NICHT ausgeführt, D-05)")
