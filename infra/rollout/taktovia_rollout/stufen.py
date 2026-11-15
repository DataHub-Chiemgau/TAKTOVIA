"""Gestaffeltes Rollout mit Rauchprobe je Stufe und Stoppkriterien (WP-M5-28 AK 5; Plan: rollout-plan.json, vorläufig OP-26).

Die Stufen werden nacheinander gestartet. Nach jeder Stufe laufen die Rauchproben des Plans und die Stoppkriterien gegen die Metriken. Schlägt eine Rauchprobe fehl oder wird ein
Stoppkriterium verletzt, wird NUR der Verkehrsanteil der neuen Version zurückgezogen und das Rollout endet als GESTOPPT: die Datenbank wird nicht angefasst (Vorwärtskorrektur statt
Löschen bestätigter Buchungen, Kap. 01 §1.8). Die Zielplattform ist hinter `Ziel` verborgen."""
from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Protocol

PLAN = Path(__file__).resolve().parents[1] / "rollout-plan.json"


class Ziel(Protocol):
    def stufe_starten(self, stufe: dict, build_id: str) -> None: ...
    def rauchprobe(self, stufe: dict, name: str) -> tuple[bool, str]: ...
    def metriken(self, stufe: dict) -> dict: ...
    def stufe_zurueckziehen(self, stufe: dict) -> None: ...
    def stufe_abschliessen(self, stufe: dict) -> None: ...


def lade_plan(path: Path = PLAN) -> dict:
    plan = json.loads(path.read_text(encoding="utf-8"))
    anteile = [s["verkehrsanteil_pct"] for s in plan["stufen"]]
    if anteile != sorted(anteile) or len(set(anteile)) != len(anteile) or anteile[-1] != 100 or len(anteile) < 2:
        raise ValueError("Stufen müssen streng aufsteigend sein, mindestens zwei, die letzte 100 %")
    for s in plan["stufen"]:
        if not s["rauchprobe"]:
            raise ValueError(f"Stufe {s['id']} ohne Rauchprobe")
        unbekannt = set(s["rauchprobe"]) - set(plan["rauchproben"])
        if unbekannt:
            raise ValueError(f"Stufe {s['id']}: unbekannte Rauchprobe {sorted(unbekannt)}")
    return plan


def stoppgruende(kriterien: dict, metriken: dict) -> list[str]:
    """Verletzte Stoppkriterien (leer = weiter). Fehlt eine Metrik, gilt das als Verstoß: ohne Messwert wird nicht weiter ausgerollt."""
    gruende = []
    for schluessel, grenze, text in (("fehlerrate_5xx_pct", kriterien["fehlerrate_5xx_pct_max"], "Fehlerrate 5xx"), ("outbox_alter_s", kriterien["outbox_alter_s_max"], "Alter der Outbox"),
                                     ("unbestimmte_zahlungen", kriterien["unbestimmte_zahlungen_max"], "unbestimmte Zahlungen")):
        wert = metriken.get(schluessel)
        if wert is None:
            gruende.append(f"{text}: kein Messwert")
        elif wert > grenze:
            gruende.append(f"{text} {wert} über {grenze}")
    if kriterien.get("verfuegbarkeitsprobe_rot") and metriken.get("verfuegbarkeitsprobe_rot"):
        gruende.append("Verfügbarkeitsprobe rot")
    return gruende


def gestaffelt_ausrollen(plan: dict, ziel: Ziel, build_id: str, warte=time.sleep, mindestdauer_beruecksichtigen: bool = False) -> dict:
    """Führt die Stufen aus. Rückgabe: Protokoll mit Ergebnis ABGESCHLOSSEN oder GESTOPPT, je Stufe Rauchprobenergebnis, Metriken und Zeiten."""
    protokoll = {"build_id": build_id, "plan_status": plan["status"], "stufen": [], "ergebnis": "ABGESCHLOSSEN", "grund": None, "datenbank": "unverändert (Vorwärtskorrektur, nichts gelöscht)"}
    for stufe in plan["stufen"]:
        eintrag = {"id": stufe["id"], "verkehrsanteil_pct": stufe["verkehrsanteil_pct"], "gestartet": time.time(), "rauchprobe": {}, "metriken": None}
        protokoll["stufen"].append(eintrag)
        ziel.stufe_starten(stufe, build_id)
        if mindestdauer_beruecksichtigen:
            warte(stufe["mindestdauer_s"])
        gruende = []
        for name in stufe["rauchprobe"]:
            try:
                ok, detail = ziel.rauchprobe(stufe, name)
            except Exception as e:  # noqa: BLE001 - eine nicht ausführbare Rauchprobe zählt als fehlgeschlagen
                ok, detail = False, f"Ausnahme: {e}"
            eintrag["rauchprobe"][name] = {"ok": ok, "detail": detail}
            if not ok:
                gruende.append(f"Rauchprobe {name} fehlgeschlagen: {detail}")
        eintrag["metriken"] = ziel.metriken(stufe)
        gruende += stoppgruende(plan["stoppkriterien"], eintrag["metriken"])
        if gruende:
            ziel.stufe_zurueckziehen(stufe)
            eintrag["ergebnis"] = "ZURUECKGEZOGEN"
            protokoll.update(ergebnis="GESTOPPT", grund="; ".join(gruende))
            return protokoll
        ziel.stufe_abschliessen(stufe)
        eintrag["ergebnis"] = "BESTANDEN"
    return protokoll
