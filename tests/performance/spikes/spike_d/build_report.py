#!/usr/bin/env python3
"""Erzeugt spike-d-psp-sandbox.md aus spike-d-data/conformance-simulator.json (Zahlen nur aus der Datei)."""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATA = HERE.parent / "spike-d-data"


def main():
    c = json.loads((DATA / "conformance-simulator.json").read_text())
    rows = "\n".join(f"| {r['id']} | {r['description']} | {'bestanden' if r['ok'] else ('nicht ausgeführt' if r['ok'] is None else 'FEHLER')} |" for r in c["results"])
    lat = "\n".join(f"| {op} | {v['n']} | {v['p50_ms']} | {v['p95_ms']} | {v['max_ms']} |" for op, v in c["latency_ms"].items())
    s = c["summary"]
    md = f"""# Spike (d) WP-M0-29: PSP-Sandbox

Stand 11.11.2026. **Status: gegen eine reale PSP-Sandbox nicht ausführbar. Port-Entwurf, Konformitätsprüfung (läuft gegen den Simulator), Testplan, Fähigkeitsmatrix- und Messvorlage sowie
Bewertungsraster liegen vor.** Alle Messwerte unten sind **Simulator-Ergebnisse (Loopback), kein Nachweis gegen einen PSP**.

## 1. Akzeptanzkriterien

| Nr. | Kriterium | Status | Grund |
|---|---|---|---|
| 1 | Alle fünf Operationen gegen mindestens eine reale Sandbox ausgeführt und mit Anbieterreferenzen protokolliert | nicht ausführbar | Kein PSP-Kandidat benannt und kein Sandbox-Zugang (Beistellung des Auftraggebers, D-03); externe Hosts außer Paketregistern sind in der Entwicklungsumgebung nicht erreichbar. |
| 2 | Timeout je Operation über Statusabfrage aufgelöst, Wiederholung ohne zweite Belastung/Erstattung | nur gegen Simulator ausgeführt | Simulator: Prüfungen C4 (je Operation Timeout und verlorene Antwort) bestanden. Reale Sandbox: offen. |
| 3 | Fähigkeitsmatrix je Kandidat vollständig, nicht unterstützte Fähigkeiten mit Auswirkung benannt | nicht ausführbar | Vorlage mit vorbelegten Auswirkungen steht in Abschnitt 4; Zellen je Kandidat offen. |
| 4 | Abweichungen Sandbox gegen Simulator nachgezogen oder als bewusste Differenz dokumentiert | teilweise | Bewusste Differenzen des Simulators stehen in Abschnitt 6; Abweichungen realer Sandboxen können erst nach der Messung eingetragen werden. |
| 5 | Nur Testkonten und Testzahlungsmittel, keine Zugangsdaten im Repository | erfüllt für das Geleistete | Der Kandidatenadapter ist eine Vorlage ohne Zugangsdaten und liest sie aus Umgebungsvariablen; ein Test sucht nach Schlüsselmustern. |

## 2. Port (Entwurf des internen PSP-Ports)

`spike_d/psp_port.py`: `authorize`, `capture`, `void`, `refund`, `status_by_merchant_ref`, `status`. Regeln: Beträge ganzzahlig in kleinster Einheit; jede schreibende Operation trägt einen Idempotenzschlüssel
vom Aufrufer; ein Timeout oder Verbindungsabbruch ist `Unknown` (nie Misserfolg) und wird nur über `resolve_unknown` mit der ursprünglichen Referenz aufgelöst; ist die Statusabfrage nicht erreichbar, bleibt es ein
manueller Klärfall (Kap. 03 §4.2, „Kein zweiter Charge auf Verdacht“). Der PSP-Simulator (WP-M0-23) implementiert diese Schnittstelle über `SimulatorAdapter`; Kandidaten brauchen einen eigenen Adapter hinter demselben Port
(`candidate_adapter_template.py`).

## 3. Konformitätsprüfung gegen den Simulator (ausgeführt)

Befehl: `python tests/performance/spikes/spike_d/conformance.py`. Ergebnis: **{s['ok']} bestanden, {s['failed']} fehlgeschlagen, {s['not_run']} nicht ausgeführt** (Simulator-Ergebnis).

| ID | Prüfung | Ergebnis |
|---|---|---|
{rows}

Latenz je Operation (sequentiell, Loopback, Simulator, keine Aussage über einen PSP):

| Operation | n | p50 ms | p95 ms | max ms |
|---|---:|---:|---:|---:|
{lat}

Gegenprobe (Test `test_gegenprobe_adapter_ohne_stabilen_schluessel_faellt_durch`): Ein Adapter, der je Aufruf einen neuen Idempotenzschlüssel vergibt, fällt in C4 und C5a durch. Die Prüfung erkennt also einen Adapter, der „auf Verdacht“ wiederholt.

## 4. Fähigkeitsmatrix je Kandidat (Vorlage)

Zellen je Kandidat bleiben bis zur Messung „offen“. Die Spalte „Auswirkung bei nicht unterstützt“ folgt aus Kap. 03 §4.2.

| Fähigkeit | Simulator | Kandidat 1 | Kandidat 2 | Auswirkung auf den Zahlungsautomaten, wenn nicht unterstützt |
|---|---|---|---|---|
| Idempotenzschlüssel und Gültigkeitsdauer | ja, 24 h (Testuhr) | offen | offen | Wiederholung nach Timeout ist riskant; es bleibt nur die Statusabfrage, sonst Klärfall |
| Statusabfrage nach eigener (Händler-)Referenz | ja | offen | offen | `CAPTURE_UNKNOWN` ist nicht automatisch auflösbar: dauerhaft `RECONCILING`, manueller Klärfall |
| Teil-Capture | ja (ein Capture je Autorisierung) | offen | offen | Preisanpassung nach Teilleistung nur über Void und neue Autorisierung (neue Kundenzustimmung) |
| Mehrfach-Capture | nein | offen | offen | Keine Teilzahlungen je Segment |
| Teil-Refund (mehrfach) | ja | offen | offen | Teilstorno nur als Vollerstattung mit neuer Belastung |
| Void nach Teil-Capture | nein | offen | offen | Rest der Autorisierung bleibt bis zum Ablauf gebunden |
| Asynchrone Benachrichtigung: Signatur | ja (`t=...,v1=HMAC-SHA256`) | offen | offen | ohne Signatur kein vertrauenswürdiger Eingang `/v1/payments/provider-events/{{provider}}` |
| Wiederholung und Reihenfolge der Benachrichtigungen | Wiederholung auf Abruf, Reihenfolge per `sequence` | offen | offen | Deduplikation und Statusabgleich müssen den Anbieterfall abdecken |
| SCA/3DS-Rückkehr | ja (`REQUIRES_ACTION`, Ablauf nach TTL) | offen | offen | ohne SCA keine zulässige Kartenzahlung in der EU |
| Split-/Marktplatzfähigkeit (nur Ausblick D-03) | nein | offen | offen | Gemeinsamer Checkout (M6) braucht einen anderen Geldfluss |
| Sandbox-Grenzen (Rate Limits, Testkarten, Dauer von Statuswechseln) | – | offen | offen | Bestimmt die Reichweite der Tests |

## 5. Zustandsabgleich (Vorlage)

| Zustand Zahlungsautomat (Kap. 03 §4.2) | Simulator | Kandidat 1 | Kandidat 2 |
|---|---|---|---|
| `CREATED`, `REQUIRES_ACTION`, `AUTHORIZED`, `CAPTURE_PENDING`, `CAPTURED`, `AUTHORIZATION_FAILED`, `VOID_PENDING`, `VOIDED`, `REFUND_PENDING`, `PARTIALLY_REFUNDED`, `REFUNDED` | gleichnamig | offen | offen |
| `DISPUTED` | getrenntes Feld `dispute_state` | offen | offen |
| `CAPTURE_UNKNOWN`, `RECONCILING` | entstehen beim Aufrufer (Timeout, verlorene Antwort) | entstehen beim Aufrufer | entstehen beim Aufrufer |

## 6. Bewusste Differenzen des Simulators (bekannt, nicht gegen reale PSP geprüft)

Keine Mehrfach-Captures, kein Void nach Teil-Capture, kein Transfer/Payout/Split (M6), Webhook-Wiederholung nur auf Abruf ohne Retry-Zeitplan, Testzahlungsmittel `pm_ok`/`pm_decline`/`pm_sca`,
Idempotenzschlüssel gelten global statt je Operationsart. Ob reale Anbieter in diesen Punkten abweichen, ist **nicht geprüft**; die Punkte sind Prüfaufträge für die Messung.

## 7. Testplan für die reale Sandbox (nach Benennung eines Kandidaten)

1. Voraussetzungen: Testkonto, Sandbox-Schlüssel über das Secret-Verfahren des Auftraggebers (nie im Repository), Testzahlungsmittel des Anbieters (Erfolg, Ablehnung, SCA-Pflicht), Notiz der Rate Limits.
2. Adapter ausfüllen (`candidate_adapter_template.py`), Zustandsabbildung in Abschnitt 5 eintragen.
3. `conformance.py --adapter candidate_adapter_template:CandidateAdapter --label <Kandidat>`: C1 bis C3 und C6 laufen ohne Fehlerinjektion.
4. Timeout je Operation (C4) manuell oder per Adapterhaken `abort_after_send` (Anfrage senden, Verbindung vor der Antwort schließen), dann Statusabfrage über die Händlerreferenz und Wiederholung mit gleichem Schlüssel; je Operation und
   Kandidat mindestens 10 Wiederholungen.
5. Gleichzeitige Captures mit gleichem Schlüssel (C5a) und doppelt zugestellte Benachrichtigung (C5b, über den Webhook-Zugang der Sandbox) prüfen; Wirkungen zählen.
6. Fähigkeitsmatrix (Abschnitt 4) und Zustandsabgleich (Abschnitt 5) aus den Läufen und der Dokumentation des Anbieters füllen; jede nicht unterstützte Fähigkeit mit Auswirkung benennen.
7. Latenz je Operation: mindestens 30 Messwerte je Operation und Tageszeit, Rohdaten und Hardware nach `../templates/messbericht-vorlage.md`.
8. Abweichungen zum Simulator: nachziehen (Test im Simulator) oder als Differenz in Abschnitt 6 eintragen.

Messvorlage je Lauf (CSV): `kandidat, operation, referenz, idempotenzschluessel, start_utc, dauer_ms, http_status, anbieterzustand, ergebnis (OK|UNKNOWN|ABGELEHNT), aufgeloest_ueber_status (ja|nein), wirkungen_beim_anbieter`.

## 8. Bewertungsraster (vor der Messung festgelegt)

| Kriterium | Gewicht |
|---|---:|
| Statusabfrage nach eigener Referenz, Timeout eindeutig auflösbar | 25 |
| Idempotenz (Schlüsselgültigkeit, keine zweite Wirkung) | 20 |
| Teil-Capture, Teil-Refund, Void-Verhalten | 15 |
| Benachrichtigungen (Signatur, Wiederholung, Reihenfolge) | 10 |
| SCA/3DS-Ablauf und Rückkehr | 10 |
| Latenz je Operation (p95) | 5 |
| Split-/Marktplatzfähigkeit (Ausblick D-03) | 10 |
| Sandbox-Qualität und Dokumentation | 5 |

Skala 0 bis 4 wie in Spike (e). K.O.: keine Statusabfrage nach eigener Referenz und kein Idempotenzschlüssel (dann ist ein Timeout nie sicher auflösbar). Empfehlung nur ohne K.O. und ab 60 %.

## 9. Entscheidungsvorlage „PSP-Kandidat“ (für WP-M0-34; Messwerte offen)

| Punkt | Inhalt |
|---|---|
| Entscheidung | D-03 Teil PSP-Auswahl (Einbetreiberzahlung); blockiert nur die Produktivschaltung der Zahlung in M2 |
| Kandidaten | vom Auftraggeber zu benennen (Beistellung) |
| Messgrundlage | Konformitätsprüfung, Fähigkeitsmatrix, Raster nach Abschnitt 8 |
| Empfehlung | offen; bis dahin gilt „Nur PSP-Simulator“ (Umsetzungsplan §5) |
| Unsicherheiten | Verhalten realer Sandboxen bei Timeout und Wiederholung, Idempotenzdauer, Webhook-Reihenfolge, Split-Fähigkeit |
"""
    out = HERE.parent / "spike-d-psp-sandbox.md"
    out.write_text(md)
    print("->", out)


if __name__ == "__main__":
    sys.exit(main())
