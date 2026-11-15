#!/usr/bin/env python3
"""Vollständigkeitsabgleich Alarmkatalog <-> Betriebshandbuch (WP-M5-26, Betriebs-Gate Kap. 08 §8.4).

Prüft `docs/betrieb/betriebshandbuch.md` gegen `infra/alerting/katalog.yaml`:
  1. Zu jedem Alarm des Katalogs gibt es genau ein Runbook (Abschnitt `### <ALARM-ID>:` im Kapitel 7); kein Runbook ohne Alarm.
  2. Jedes Runbook hat alle Felder der Vorlage: Auslöser, Rolle, Diagnose, Sofortmaßnahme, Eskalation, Nacharbeit (nicht leer).
  3. Jeder Schritt nennt eine Rolle; kein Runbook verlangt Superuser- oder direkten Datenbankzugriff im Regelbetrieb (keine SQL-Anweisungen, kein `psql`, kein Superuser).
  4. Pflichtkapitel sind vorhanden: Systemübersicht, Umgebungen, Deployment und Migration (expand/contract, Vorwärtskorrektur), Monitoring/Dashboards, Eskalation, Backup/Restore,
     Übungen, Wartungsfenster, Klärfälle, Notbetrieb mit den vier Ausfallfällen (Kernbuchung, Driver-App-Verbindung, Leitstellenoberfläche, PSP), Erläuterung der vier Betriebsparameter.
Exit-Code 0 ohne Befund, 1 bei Befund (die Befunde stehen auf stdout), 2 bei Aufruffehler."""
import argparse
import json
import re
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[1]
FELDER = ("Auslöser", "Rolle", "Diagnose", "Sofortmaßnahme", "Eskalation", "Nacharbeit")
PFLICHT_KAPITEL = ("Systemübersicht", "Rollen im Betrieb", "Umgebungen", "Deployment und Migration", "Monitoring und Dashboards", "Alarmierung und Eskalation", "Runbooks je Alarm", "Klärfälle",
                   "Backup, Restore und Wiederanlauf", "Wiederkehrende Übungen und Wartungsfenster", "Notbetrieb", "Betriebsrelevante Parameter")
NOTBETRIEB = ("Ausfall der Kernbuchung", "Ausfall der Driver-App-Verbindung", "Ausfall der Leitstellenoberfläche", "Ausfall des PSP")
PARAMETER = ("alert_escalation_delay_min", "vehicle_unavailability_alert_min", "notification_quiet_hours", "daily_report_time")
VERBOTEN = re.compile(r"(?i)\b(superuser|psql\b|als postgres|(?:UPDATE|DELETE FROM|INSERT INTO|TRUNCATE|DROP TABLE)\s+\w)")
KEIN_BEFUND = re.compile(r"(?i)\b(kein|keine|nie|niemals|ohne|nicht)\b[^.]{0,60}(superuser|direkte[nr]? datenbankzugriff)|(superuser|direkte[nr]? datenbankzugriff)[^.]{0,60}\b(verboten|nie|nicht)\b")


def abschnitt7(text):
    m = re.search(r"^## 7\. Runbooks je Alarm\s*$(.*?)(?=^## \d+\. )", text, re.S | re.M)
    return m.group(1) if m else ""


def runbooks(text):
    """{alarm_id: abschnittstext} aus dem Kapitel 7."""
    body = abschnitt7(text)
    teile = re.split(r"^### ([A-Z][A-Z0-9_]+):.*$", body, flags=re.M)
    return {teile[i]: teile[i + 1] for i in range(1, len(teile) - 1, 2)}


def pruefen(handbuch: str, katalog: dict) -> list:
    befunde = []
    ids = [s["id"] for s in katalog["szenarien"]]
    rb = runbooks(handbuch)
    for a in ids:
        if a not in rb:
            befunde.append(f"Alarm {a} hat kein Runbook im Betriebshandbuch")
    for a in rb:
        if a not in ids:
            befunde.append(f"Runbook {a} gehört zu keinem Alarm des Katalogs")
    for a, text in rb.items():
        for f in FELDER:
            m = re.search(rf"^- \*\*{f}:\*\*[ \t]*(\S.*)$", text, re.M)
            if not m or not m.group(1).strip():
                befunde.append(f"Runbook {a}: Feld '{f}' fehlt oder ist leer")
        for zeile in text.splitlines():
            if VERBOTEN.search(zeile) and not KEIN_BEFUND.search(zeile):
                befunde.append(f"Runbook {a}: verlangt Superuser- oder direkten Datenbankzugriff: {zeile.strip()[:100]}")
    for k in PFLICHT_KAPITEL:
        if not re.search(rf"^## \d+\. {re.escape(k)}\s*$", handbuch, re.M):
            befunde.append(f"Pflichtkapitel fehlt: {k}")
    notb = re.search(r"^## \d+\. Notbetrieb\s*$(.*?)(?=^## \d+\. )", handbuch, re.S | re.M)
    for n in NOTBETRIEB:
        if not notb or not re.search(rf"^### \d+\.\d+ {re.escape(n)}\s*$", notb.group(1), re.M):
            befunde.append(f"Notbetriebsverfahren fehlt: {n}")
    for p in PARAMETER:
        if f"`{p}`" not in handbuch:
            befunde.append(f"Betriebsparameter nicht erläutert: {p}")
    for k in ("expand/contract", "Vorwärtskorrektur"):
        if k not in handbuch:
            befunde.append(f"Deployment/Migration: '{k}' nicht beschrieben")
    return befunde


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--handbuch", default=str(REPO / "docs" / "betrieb" / "betriebshandbuch.md"))
    ap.add_argument("--katalog", default=str(REPO / "infra" / "alerting" / "katalog.yaml"))
    a = ap.parse_args(argv)
    try:
        text = Path(a.handbuch).read_text(encoding="utf-8")
        katalog = yaml.safe_load(Path(a.katalog).read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as e:
        print(f"Aufruffehler: {e}", file=sys.stderr)
        return 2
    befunde = pruefen(text, katalog)
    print(json.dumps({"alarme": len(katalog["szenarien"]), "runbooks": len(runbooks(text)), "befunde": befunde, "ergebnis": "BEFUND" if befunde else "OHNE_BEFUND"}, ensure_ascii=False, indent=1))
    return 1 if befunde else 0


if __name__ == "__main__":
    sys.exit(main())
