---
dokument_version: 0.1
abnahme_build: ENTWURF-W18-9d035b7
sprache: de
stand: 2026-11-15
arbeitspaket: WP-M5-29
---

# Pilotbereitschaft TAKTOVIA

**Zweck:** Vor Pilotbeginn sind Abbruchkriterien und Rückfallbetrieb festgelegt, und das Pilot-Gate aus Kap. 08 §8.4 ist nachgewiesen: Betrieb von Anfang bis Ende mit freigegebenem Gebiet, Personal, Tarif, Flotte und Prozessverantwortlichen.
**Status: VORLAGE, NICHT FREIGEGEBEN.** Dieses Dokument legt Struktur, Messbarkeit und Ablauf fest. Alle Schwellen, Personen, Vertretungen und der Rückfallprozess des Betreibers sind **durch Auftraggeber festzulegen** (D-05 Pilotbetreiber und Region, D-11 Notbetrieb, D-12 Termin). Der Pilot beginnt erst, wenn die offenen Punkte in Abschnitt 8 geschlossen sind; `python3 tools/pilot_bereitschaft.py bereitschaft` zeigt sie und endet mit Exit-Code 1, solange eine offen ist.
**Quellen:** `docs/pilot/abbruchkriterien.json`, `docs/pilot/prozessverantwortliche.json`, `docs/pilot/rueckfallbetrieb.json`, Alarmkatalog `infra/alerting/katalog.yaml` (WP-M5-04), Messpunktvertrag `infra/availability/messpunktvertrag.json` (WP-M5-05), Migrationsprotokoll des Einspielwerkzeugs `tools/stammdaten_import` (WP-M5-25), Betriebshandbuch ([Notbetrieb](../betrieb/betriebshandbuch.md#11-notbetrieb)), Rollout `infra/rollout` (WP-M5-28).

## Inhalt

1. [Freigabeumfang des Pilots](#1-freigabeumfang-des-pilots)
2. [Prozessverantwortliche des Pilot-Gates](#2-prozessverantwortliche-des-pilot-gates)
3. [Abbruchkriterien](#3-abbruchkriterien)
4. [Entscheidungsweg](#4-entscheidungsweg)
5. [Rückfallbetrieb](#5-rückfallbetrieb)
6. [Wiederaufnahme](#6-wiederaufnahme)
7. [Generalprobe und Produktionseinspielung](#7-generalprobe-und-produktionseinspielung)
8. [Nachweisstand und offene Punkte](#8-nachweisstand-und-offene-punkte)

## 1. Freigabeumfang des Pilots

Der Umfang folgt dem Migrationsprotokoll der Einspielung (WP-M5-25): Dort stehen je Objektart Soll- und Ist-Bestand. Das Pilotbereitschaftsdokument verweist auf dieses Protokoll, es wiederholt die Daten nicht und enthält keine Personendaten.

| Gegenstand | Freigabe | Bezug im Migrationsprotokoll | Stand |
|---|---|---|---|
| Bediengebiet | durch Auftraggeber festzulegen | `bestaende.bediengebiete` | offen |
| Tarif | durch Auftraggeber festzulegen | `bestaende.tarife` | offen |
| Flotte (Fahrzeuge, Betreiber) | durch Auftraggeber festzulegen | `bestaende.fahrzeuge`, `bestaende.betreiber` | offen |
| Personal (Benutzer, Fahrer) | durch Auftraggeber festzulegen | `bestaende.benutzer`, `bestaende.fahrer` | offen |

Pilotbetreiber und Region sind nicht entschieden (D-05). Ohne freigegebenes Protokoll gilt der Umfang als nicht freigegeben.

## 2. Prozessverantwortliche des Pilot-Gates

Für jede Prozessverantwortung ist eine Person mit Vertretung zu benennen und in der Plattform mit der passenden Rolle anzulegen (`docs/pilot/prozessverantwortliche.json`).

| ID | Prozess | Plattform- oder Betriebsrolle | Person | Vertretung |
|---|---|---|---|---|
| PV-01 | Leitstelle | `dispatcher` | nicht benannt | nicht benannt |
| PV-02 | Werkstatt und Fahrzeugfreigabe | `workshop` | nicht benannt | nicht benannt |
| PV-03 | Zahlungsklärung | `finance` | nicht benannt | nicht benannt |
| PV-04 | Datenschutz | `tenant_admin` (ohne automatischen Zugriff auf PII, HR und FINANZ) | nicht benannt | nicht benannt |
| PV-05 | Betrieb und Rufbereitschaft | Betriebsrolle `betrieb-bereitschaft` (Betriebswerkzeuge, keine Plattformrolle) | nicht benannt | nicht benannt |

Namen stehen nicht in diesem Repository; die Zuordnung führt der Betreiber in seinem Personalverzeichnis. Das Werkzeug prüft, dass die Plattformrollen im Rollenkatalog existieren, dass Person und Vertretung verschieden sind und meldet jede unbesetzte Stelle als offenen Punkt.

## 3. Abbruchkriterien

Jedes Kriterium hat Messgröße, Vergleich, Schwelle, Messquelle, Mangelklasse und Runbook und ist aus dem Monitoring messbar (Alarmkatalog WP-M5-04, Verfügbarkeitsmessung WP-M5-05). Die Vorlage für ein weiteres Kriterium ist ein Eintrag in `docs/pilot/abbruchkriterien.json` mit denselben Feldern (Messgröße, Schwelle, Messquelle, Entscheider).

| ID | Kriterium | Messgröße | Messquelle | Mangelklasse | Schwelle |
|---|---|---|---|---|---|
| PK-01 | Ausfall der Kernbuchung | Dauer des Alarms `KERNBUCHUNG_AUSFALL` (Minuten) | Alarmkatalog | A | durch Auftraggeber festzulegen (Arbeitsannahme 10) |
| PK-02 | Verfügbarkeit der Kernbuchung | Verfügbarkeit im Messfenster (Prozent) | Monatsbericht der synthetischen Proben, Bereich `kernbuchung` | B | durch Auftraggeber festzulegen (Arbeitsannahme 99,0) |
| PK-03 | Ungeklärte Zahlungen | Anzahl unbestimmter Zahlungsvorgänge, Metrik `taktovia_payment_intent_indeterminate` | Alarm `PSP_UNBESTIMMT` | B | durch Auftraggeber festzulegen (Arbeitsannahme 5) |
| PK-04 | Gestaute Ereignisauslieferung | Dauer des Alarms `OUTBOX_STAU` (Minuten) | Alarmkatalog | B | durch Auftraggeber festzulegen (Arbeitsannahme 30) |
| PK-05 | Archivierungsverzug (RPO) | Dauer des Alarms `ARCHIVIERUNGS_REPLIKATIONSVERZUG` (Minuten) | Alarmkatalog | A | durch Auftraggeber festzulegen (Arbeitsannahme 15) |

Die Arbeitsannahmen sind keine Vorgabe des Pflichtenhefts. Mängel der Klasse A blockieren die Produktion (Kap. 08 §8.4, `tests/acceptance/docs/mangelklassen.md`). **Eine offene Schwelle gilt nie als eingehalten:** `tools/pilot_bereitschaft.py bewerten` führt sie als „nicht bewertbar“.

## 4. Entscheidungsweg

Erreicht ein Messwert die Schwelle, löst das Werkzeug den Entscheidungsweg aus und legt einen Eintrag im Entscheidungsjournal an (nur anfügbar, Hash-Kette, ein offener Eintrag je Kriterium):

1. **Stufe 1, Bereitschaft Betrieb (15 Minuten):** Messwert und Kriterium bestätigen, Betriebsleitung informieren, Vorfall im Leitstellenprotokoll vermerken.
2. **Stufe 2, Betriebsleitung (60 Minuten):** Entscheidung `PILOT_UNTERBRECHEN` oder `MIT_AUFLAGE_FORTSETZEN` mit Begründung. Fortsetzen verlangt eine Auflage und ist bei einem Mangel der Klasse A nicht zulässig. Genau eine Entscheidung je Auslösung, auch bei gleichzeitigen Versuchen; nur die Entscheiderrolle (Betriebsleitung oder Vertretung) darf entscheiden.
3. **Stufe 3, Auftraggeber (240 Minuten):** wird informiert; bei Unterbrechung entscheidet er über die Wiederaufnahme mit.

Frist und Rollen sind Arbeitsannahmen und vom Auftraggeber freizugeben. Das Werkzeug überwacht die Fristen nicht selbst; die Alarmkette des Betriebs (Eskalation nach Katalog) bleibt maßgeblich.

## 5. Rückfallbetrieb

Wie Fahrten ohne Plattform disponiert und Fahrgäste informiert werden, **liefert der Betreiber** (D-11). Arbeitsannahme (`docs/pilot/rueckfallbetrieb.json`, abgeleitet aus dem Notbetrieb des Betriebshandbuchs):

1. Entscheidung zur Unterbrechung im Journal erfassen.
2. Notbetrieb erklären und Leitstelle informieren.
3. Fahrgäste mit bestätigten Buchungen informieren.
4. Fahrten ohne Plattform telefonisch disponieren, Notbetriebsblatt führen (keine verbindlichen Zusagen).
5. Fahrer anweisen: Stoppliste weiterfahren, keine Freigabe ohne Weisung.
6. Auftraggeber informieren.
7. Rückkehr: Notizen nachbuchen (Idempotenzschlüssel verhindert Doppelungen) und das Journal abschließen.

Der Rückfallbetrieb ist **einmal zu üben** (Tischübung oder Teilübung) und zu protokollieren (Vorlage `docs/pilot/vorlagen/rueckfalluebung-protokoll.json`, Prüfung mit `tools/pilot_bereitschaft.py`, Funktion `rueckfalluebung_pruefen`). Stand: **nicht geübt**.

## 6. Wiederaufnahme

Nach einer Unterbrechung nimmt der Pilot nur auf, wenn alle Kriterien im Messfenster wieder innerhalb der Schwelle liegen (kein Kriterium verletzt, keines unbewertbar, keines ohne Messwert) und zwei verschiedene Personen, Betriebsleitung und Auftraggeber, zustimmen. Die Wiederaufnahme steht als eigener Eintrag im Journal.

## 7. Generalprobe und Produktionseinspielung

**Generalprobe:** ein Betriebstag in Staging mit Pilotstammdaten und Betreiberpersonal (alle fünf Prozesse besetzt). Befunde werden mit Mangelklasse erfasst; die Generalprobe gilt nur ohne offenen Mangel der Klasse A, und nach der Behebung ist auf dem neuen Stand zu wiederholen. Protokollvorlage: `docs/pilot/vorlagen/generalprobe-protokoll.json`, Prüfung `generalprobe_pruefen`. Stand: **nicht durchgeführt** (kein Staging, kein Betreiberpersonal).

**Produktionseinspielung:** Die freigegebenen Pilotstammdaten werden mit dem Werkzeug `tools/stammdaten_import` in die Produktionsumgebung (WP-M5-28) eingespielt, mit eigenem Migrationsprotokoll. Das Protokoll muss Soll gleich Ist für Bediengebiet, Tarif, Betreiber, Fahrzeuge und Benutzer bestätigen, fehlerfrei und im Lauf-Modus, für den Zielmandanten und den Abnahme-Build (`migrationsprotokoll_pruefen`). Danach darf die Produktionsdatenbank außer den freigegebenen Pilotmandanten und dem gekennzeichneten Probemandanten keine Mandanten enthalten (`produktionsdaten_pruefen`). Ein Einspielversuch mit einer Rolle ohne Rechte am Zielmandanten wird abgewiesen (Vorabprüfung des Werkzeugs, Protokoll `NICHT_GESTARTET`). Zum Abschluss gleicht die Betriebsleitung das Betriebshandbuch-Kapitel „Deployment und Migration“ gegen die Produktionsumgebung ab. Stand: **nicht durchgeführt**, es gibt keine Produktionsumgebung und keine freigegebenen Pilotdaten; die Mechanik ist in einer Ersatzumgebung nachgewiesen (`tests/acceptance/test_pilotbereitschaft.py`).

## 8. Nachweisstand und offene Punkte

| Nr. | Punkt | Stand |
|---|---|---|
| 1 | Abbruchkriterien messbar aus dem Monitoring, Entscheidungsweg wird ausgelöst | belegt (Test) |
| 2 | Schwellen und Freigabe der Abbruchkriterien und des Rückfallbetriebs durch den Auftraggeber | **offen**, durch Auftraggeber festzulegen |
| 3 | Prozessverantwortliche mit Vertretung benannt und in der Plattform angelegt | **offen**, Betreiber (D-05) |
| 4 | Generalprobe ohne Mangel der Klasse A | **nicht durchgeführt** |
| 5 | Rückfallbetrieb einmal geübt und protokolliert | **nicht durchgeführt** |
| 6 | Produktionseinspielung mit Probebuchung des Probemandanten | **nicht durchgeführt**; die Ersatzumgebung belegt die Mechanik (Einspielung, Soll gleich Ist, Probebuchung im eingespielten Gebiet des Pilotmandanten, Prüfung auf Testdaten); die Probebuchung des Probemandanten selbst ist Gegenstand von WP-M5-05 |
| 7 | Abgleich Betriebshandbuch „Deployment und Migration“ gegen die Produktionsumgebung | **nicht durchgeführt** (keine Produktionsumgebung) |
