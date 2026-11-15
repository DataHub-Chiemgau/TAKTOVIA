#!/usr/bin/env python3
"""Statische Prüfung der Produktionsumgebung und des Rollouts (WP-M5-28): Übernahmen existieren, Plan und Betriebszugriff sind vollständig, Production ist von den übrigen Umgebungen getrennt.

Aufruf: python3 infra/check_rollout.py  (Exit 0 = ohne Befund). Prüft NICHT: eine laufende Produktionsumgebung (kein Anbieter, D-05), terraform plan, reale Netze."""
import json
import pathlib
import sys

INFRA = pathlib.Path(__file__).resolve().parent
REPO = INFRA.parent
sys.path.insert(0, str(INFRA / "rollout"))


def check(infra=INFRA, repo=REPO):
    from taktovia_rollout import stufen, zugriff
    errs = []
    prod = json.loads((infra / "rollout" / "produktion.json").read_text(encoding="utf-8"))
    if "D-05" not in prod["status"]:
        errs.append("produktion.json: Status nennt D-05 nicht")
    for name, u in prod["uebernahmen"].items():
        if not u["dateien"]:
            errs.append(f"Übernahme {name}: keine Dateien")
        for f in u["dateien"]:
            if not (repo / f).is_file():
                errs.append(f"Übernahme {name} ({u['wp']}): Datei {f} fehlt")
    for pflicht in ("telemetrie", "alarmregeln", "synthetische_proben", "wartungskalender", "maskierung", "wal_archivierung_pitr", "release_gate", "iac", "identitaet", "bereitstellung"):
        if pflicht not in prod["uebernahmen"]:
            errs.append(f"Übernahme {pflicht} fehlt")
    try:
        plan = stufen.lade_plan(infra / "rollout" / "rollout-plan.json")
    except (ValueError, KeyError) as e:
        return errs + [f"rollout-plan.json: {e}"]
    if "OP-26" not in plan["status"]:
        errs.append("rollout-plan.json: Status nennt OP-26 nicht")
    if not plan["regeln"].get("nur_signierte_artefakte"):
        errs.append("rollout-plan.json: signierte Artefakte nicht verlangt")
    if "Vorwärtskorrektur" not in plan["regeln"]["fehlerbehandlung"]:
        errs.append("rollout-plan.json: keine Vorwärtskorrektur")
    for k in ("fehlerrate_5xx_pct_max", "outbox_alter_s_max", "unbestimmte_zahlungen_max", "rauchprobe_fehlgeschlagen", "verfuegbarkeitsprobe_rot"):
        if k not in plan["stoppkriterien"]:
            errs.append(f"rollout-plan.json: Stoppkriterium {k} fehlt")
    if "alarmkette" not in plan["stufen"][-1]["rauchprobe"] or "synthetische_probe" not in plan["stufen"][-1]["rauchprobe"] or "archivierungsverzug" not in plan["stufen"][-1]["rauchprobe"]:
        errs.append("letzte Stufe prüft nicht Probe, Alarmkette und Archivierungsverzug (AK 4)")
    k = zugriff.lade_konzept(infra / "rollout" / "betriebszugriff.json")
    for f in ("personengebunden", "mfa", "protokoll", "notfallzugang", "verbotene_kontonamen"):
        if f not in k:
            errs.append(f"betriebszugriff.json: {f} fehlt")
    m = json.loads((infra / "umgebungsmatrix.json").read_text(encoding="utf-8"))["umgebungen"]
    p = m["production"]
    if p["datenbank"]["erlaubte_quellnetze"] != [p["netz"]]:
        errs.append("Production-Datenbank nimmt Verkehr aus fremden Netzen an")
    for n, e in m.items():
        if n != "production" and (e["datenbank"]["host"] == p["datenbank"]["host"] or p["netz"] in e["datenbank"]["erlaubte_quellnetze"] or e["psp"]["domaene"] == p["psp"]["domaene"]):
            errs.append(f"{n} teilt Datenbank, Netz oder PSP mit Production")
    if p["psp"]["modus"] != "live":
        errs.append("Production nutzt kein Live-PSP-Konto")
    realm = json.loads((infra / "keycloak" / "realm-taktovia-production.json").read_text(encoding="utf-8"))
    if realm.get("realm") != p["identitaet"]["realm"]:
        errs.append("Realm-Export von Production passt nicht zur Matrix")
    return errs


def main():
    errs = check()
    for e in errs:
        print("FEHLER:", e)
    print("rollout: " + ("OK (statisch; Produktionsumgebung beim Anbieter NICHT ausgeführt, D-05)" if not errs else f"{len(errs)} Fehler"))
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
