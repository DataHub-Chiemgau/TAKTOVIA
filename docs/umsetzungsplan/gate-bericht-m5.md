# M5-Gate-Bericht P1

Erzeugt von `tools/nachweis_p1.py` am 2026-11-15T11:47:30+00:00. Abnahme-Build: `8e5d65b`. Das Werkzeug entscheidet nichts; die **Abnahmeentscheidung trifft der Auftraggeber** (D-12).

**Ergebnis nach den Regeln des Werkzeugs: GATE NICHT BESTANDEN** (32 Verstöße).

## 1. Nachweismatrix P1

202 P1-MUSS-Anforderungen: 190 grün, 0 rot, 3 ohne Lauf, 9 ohne ausführbaren Test, 0 mit abgewiesener Evidenz. Die vollständige Matrix steht in `docs/umsetzungsplan/nachweise/nachweismatrix-p1.csv`. Grün heißt: mindestens ein ausgeführter, grüner Test (JUnit, Python, Vitest) oder eine grüne Evidenz des Abnahme-Builds.

| Meilenstein | Anforderungen | grün | nicht grün |
|---|---:|---:|---:|
| M1 | 46 | 45 | 1 |
| M2 | 38 | 35 | 3 |
| M3 | 48 | 46 | 2 |
| M4 | 53 | 49 | 4 |
| M5 | 17 | 15 | 2 |

## 2. Gates nach Kap. 08 §8.4

### 2.1 Gate Fachmodell: teilweise

**Sperrwirkung:** Ungeklärte Geld- oder Beförderungsrolle verhindert deren produktive Aktivierung (aktiv)

- [teilweise] Review-Protokoll Fachmodell: Zustandsautomaten, Entitäten, Preis- und Kostenregeln der Detailverträge mit ausführbarem Test, Liste der offenen Geschäftsentscheidungen und gesperrten Aktivierungen (Verweise: `docs/umsetzungsplan/nachweise/fachmodell-review.md`). Grenze: Werkzeug- und Agentenprüfung, kein Fachreview durch benannte Personen (D-12); Geld- und Beförderungsrolle (D-01, D-03, D-04, D-06, D-07) nicht entschieden

### 2.2 Gate CI: teilweise

**Sperrwirkung:** Fehlgeschlagene Pflichtprüfung verhindert Release (aktiv)

- [teilweise] Unit-, Datenbankintegrations- und Contract-Tests lokal ausgeführt (Backend-Gesamtbuild, DB-Tests, API-Lint und Bündel, Contract, Web) (Verweise: `backend/build.gradle.kts`, `db/tests`, `api/tools/lint.py`, `tests/contract`). Grenze: lokaler Lauf in der Ersatzumgebung; die Workflows in .github/workflows/ci.yml sind angelegt, aber nicht auf dem Server gelaufen (Branch-Schutz offen)
- [teilweise] Release-Gate mit Pflichtprüfungen für Kernbuchung und Disposition, Migration frisch und aus freigegebenem Vorstand (Verweise: `tools/release_gate/gate.py`, `tools/release_gate/migration_check.py`, `tools/release_gate/pflichtpruefungen.json`). Grenze: signiert nur mit HMAC, keine Lieferkettensignatur; kein CI-Lauf auf dem Server

### 2.3 Gate Partnerfreigabe: teilweise

**Sperrwirkung:** Nicht geprüfte Fähigkeit bleibt in der Capability Registry deaktiviert (aktiv)

- [teilweise] Partner-Sandbox je Operation (Idempotenz, Statusabfrage, Storno) als Sandbox-Adapter der Testumgebung (Verweise: `tests/sandbox/partner/test_partner_sandbox.py`). Grenze: Sandbox-Adapter, keine realen Partner; in P1 gibt es keine buchbaren Partner
- [erbracht] Keine buchbaren Partner in P1: alle Partnerfähigkeiten sind nicht vorhanden bzw. deaktiviert (OP-20, Arbeitsannahme) (Verweise: `api/federation.yaml`, `api/contract-pending.txt`). Grenze: 8 von 8 Operationen des Föderationsvertrags sind nicht gebaut (api/contract-pending.txt); kein Hauptcode setzt die Fähigkeit EXTERNAL_BOOKING

### 2.4 Gate Sicherheit: teilweise

**Sperrwirkung:** Datenoffenlegung, Sicherheits-/Verkehrsgefährdung: keine Freigabe (aktiv)

- [teilweise] RLS-, Rollen- und Objektprüfung: Isolationssuite, Feldzugriffsmatrix, Authentisierungsangriffe, Upload- und Exportberechtigungen (Verweise: `docs/umsetzungsplan/nachweise/WP-M5-17-sicherheitsabnahme.md`, `tests/acceptance/m5_sicherheit`). Grenze: Ersatzumgebung (kein Keycloak, kein Staging)
- [fehlt] Unabhängiger Penetrationstest und Behandlung kritischer Befunde (Verweise: `tests/acceptance/m5_sicherheit/pentest/register.json`). Grenze: nicht durchgeführt; Register AUSSTEHEND, keine benannte Prüfstelle (D-12), kein Staging

### 2.5 Gate UX: teilweise

**Sperrwirkung:** Blockierte Kernreise vor Freigabe beheben (aktiv)

- [teilweise] Barrierefreiheitsprüfung Fahrer und Planer (AT-54) und übrige Kernreisen: automatisierte Prüfungen und Prüfprotokolle (Verweise: `docs/umsetzungsplan/nachweise/WP-M5-18-pruefprotokoll.md`, `docs/umsetzungsplan/nachweise/WP-M5-30-pruefprotokoll.md`). Grenze: Screenreader-Durchläufe (NVDA, VoiceOver, TalkBack), AT-19 auf dem Abnahme-Build und Zielgeräte nicht ausgeführt; AT-54 nicht in e2e.csv

### 2.6 Gate Betrieb: teilweise

**Sperrwirkung:** Fehlender Wiederanlauf- oder Kernbetriebsnachweis verhindert Pilot (aktiv)

- [teilweise] Messbares Lastprofil und p95-Ziele (Standard-API, Fahrtangebote, Planrevision) in der Ersatzumgebung (Verweise: `tests/performance/protokolle/standard-api-p95-m5-11.md`, `tests/performance/protokolle/planrevision-m5-13.md`, `tests/performance/test_referenzprofil.py`). Grenze: Referenzprofil nicht vereinbart (OP-01), Messungen auf lokaler Hardware, nicht auf der Zielumgebung (D-05)
- [teilweise] Restore, Failover und Ausfallübung mit RPO/RTO-Messung (Verweise: `tests/performance/protokolle/ausfalluebung-m5-15.json`, `tests/performance/wiederanlauf-drehbuch-m5.md`, `tests/performance/protokolle/wiederanlauf-trockenlauf-m5-14.json`). Grenze: lokale Übung mit vorläufigen Zielwerten (AT-23), keine Replikation auf getrennter Hardware
- [teilweise] Notbetrieb, Monitoring und Eskalation: Betriebshandbuch mit Runbooks je Alarm, Alarmkatalog, synthetische Proben (Verweise: `docs/betrieb/betriebshandbuch.md`, `infra/alerting/katalog.yaml`, `tests/performance/protokolle/m5-05/probenlauf-anwendung.md`). Grenze: keine Übung mit Betriebspersonal, keine reale Telemetrie-Senke, kein voller Monat Verfügbarkeitsmessung (OP-02)

### 2.7 Gate Pilot: fehlt

**Sperrwirkung:** Abbruchkriterien und Rückfallbetrieb vor Pilotbeginn festlegen (aktiv)

- [fehlt] Pilotbereitschaft: Abbruchkriterien, Entscheidungsweg, Rückfallbetrieb, Prozessverantwortliche, Generalprobe und Produktionseinspielung (Vorlage und Werkzeuge) (Verweise: `docs/pilot/pilotbereitschaft.md`, `tools/pilot_bereitschaft.py`). Grenze: nichts freigegeben: keine Schwellen, keine benannten Personen, keine Generalprobe, keine Rückfallübung, keine Produktionsumgebung (D-05, D-11)

## 3. Mängelliste

| Nr. | Klasse | Titel | Workaround (freigegeben) | Termin | Maßnahmenplan | Status |
|---|---|---|---|---|---|---|
| M-001 | C | N+1-Abfragemuster in der Fahrzeugliste des Livebetriebs (operator/vehicles) unter Referenzlast | – (nein) | durch Auftraggeber festzulegen | Vorschlag (durch Auftraggeber zu bestätigen): Abfrage der Fahrzeugliste bündeln und die Messung tests/performance/test_standard_api_p95.py auf dem Zielsystem wiederholen | offen |
| M-002 | B | Check-in, Ausstieg und No-Show haben in der Fahrer-App keinen verdrahteten Bildschirm; Ereignislogik, Outbox und Schnittstelle sind vorhanden | – (nein) | durch Auftraggeber festzulegen | – | offen |
| M-003 | C | Rollback einer Konfigurationsrevision hat im Operator-Web kein Bedienelement (nur Schnittstelle rollbackConfigRevision) | Aufruf der Schnittstelle durch Personen mit dem Recht config:publish (Administratorhandbuch, Abschnitt 7) (nein) | durch Auftraggeber festzulegen | Vorschlag (durch Auftraggeber zu bestätigen): Bedienelement Rücknahme in der Revisionsliste mit Vier-Augen-Weg ergänzen | offen |
| M-004 | C | Prüfskript tools/log_stichprobe.py erkennt Koordinaten in Diagnosedaten nicht | – (nein) | durch Auftraggeber festzulegen | Vorschlag (durch Auftraggeber zu bestätigen): Koordinatenmuster als Feldklasse in Maskierung und Prüfskript aufnehmen | offen |

## 4. Entscheidungen D-01 bis D-12

| Nr. | Gegenstand | Stand | Wirkung auf die Produktivschaltung (Arbeitspakete mit Entscheidungsbezug) |
|---|---|---|---|
| D-01 | Wer verkauft, befördert und verantwortet die Reisekette? | offen (kein Beschluss protokolliert) | WP-M1-05, WP-M1-34, WP-M1-35, WP-M5-25, WP-M5-31 |
| D-02 | Wie stark müssen Mandanten technisch isoliert werden? | offen (kein Beschluss protokolliert) | WP-M1-01, WP-M1-11, WP-M3-02, WP-M4-58, WP-M4-64, WP-M5-17 |
| D-03 | Gemeinsamer Checkout und Geldfluss | offen (kein Beschluss protokolliert) | WP-M1-37, WP-M2-01, WP-M2-29, WP-M2-30, WP-M2-31, WP-M2-42, WP-M5-15, WP-M5-23, WP-M5-24, WP-M5-28, WP-M5-31 |
| D-04 | Anschlussrisiko und unvollständige Mehrbetreiberbuchung | offen (kein Beschluss protokolliert) | kein P1-Arbeitspaket nennt sie |
| D-05 | Pilot, Peaklast, SLA und Betrieb | offen (kein Beschluss protokolliert) | WP-M1-04, WP-M1-09, WP-M1-19, WP-M1-25, WP-M1-36, WP-M1-39, WP-M1-40, WP-M1-41, WP-M1-42, WP-M2-01, WP-M2-02, WP-M2-04 |
| D-06 | Finanzumfang und ERP-Anbindung | offen (kein Beschluss protokolliert) | WP-M4-01, WP-M4-02, WP-M4-22, WP-M4-27, WP-M4-29, WP-M4-33, WP-M4-34, WP-M4-36, WP-M4-37, WP-M4-63, WP-M5-07, WP-M5-31 |
| D-07 | Einheitliche Kostenbewertung und interne Verrechnung | offen (kein Beschluss protokolliert) | WP-M4-01, WP-M4-33, WP-M4-35, WP-M4-39, WP-M4-40, WP-M4-42, WP-M4-43, WP-M4-44, WP-M4-56, WP-M4-63, WP-M5-07, WP-M5-31 |
| D-08 | Partnerstandards und tatsächliche Buchungsrechte | offen (kein Beschluss protokolliert) | kein P1-Arbeitspaket nennt sie |
| D-09 | Routing, Karten und Telematik | offen (kein Beschluss protokolliert) | WP-M2-01, WP-M2-10, WP-M2-38, WP-M2-40, WP-M2-44, WP-M3-01, WP-M3-28, WP-M3-34, WP-M3-36, WP-M3-56, WP-M4-41, WP-M4-42 |
| D-10 | Fahrerdaten, ETA und Mitbestimmung | offen (kein Beschluss protokolliert) | WP-M1-32, WP-M1-35, WP-M1-42, WP-M3-05, WP-M3-19, WP-M3-34, WP-M4-01, WP-M4-11, WP-M4-13, WP-M4-61, WP-M4-62, WP-M4-63 |
| D-11 | Aufbewahrung, Datenrechte und Notbetrieb | offen (kein Beschluss protokolliert) | WP-M1-12, WP-M1-28, WP-M1-30, WP-M1-31, WP-M1-32, WP-M1-33, WP-M1-41, WP-M1-42, WP-M1-46, WP-M2-11, WP-M2-23, WP-M2-25 |
| D-12 | Reihenfolge der Lieferung und Quellbereinigung | offen (kein Beschluss protokolliert) | WP-M1-45, WP-M2-48, WP-M3-39, WP-M5-21, WP-M5-29, WP-M5-31 |

## 5. Verstöße gegen die Gate-Regeln

Anforderungen nicht grün: 23 (Liste in der Matrix, Spalte `ergebnis`).

- Evidenz tests/acceptance/evidence/m5/AT-01-run-a6e8f5d63aadd593.json stammt von Build e16a712a7e15ffe49b06325bf29d5ea8c534233d+lokal, nicht vom Abnahme-Build 8e5d65b: abgewiesen
- Mangel M-002 (Klasse B) ohne freigegebenen Workaround oder verbindlichen Termin
- Gate Fachmodell: Nachweis teilweise (Sperrwirkung: Ungeklärte Geld- oder Beförderungsrolle verhindert deren produktive Aktivierung)
- Gate CI: Nachweis teilweise (Sperrwirkung: Fehlgeschlagene Pflichtprüfung verhindert Release)
- Gate Partnerfreigabe: Nachweis teilweise (Sperrwirkung: Nicht geprüfte Fähigkeit bleibt in der Capability Registry deaktiviert)
- Gate Sicherheit: Nachweis teilweise (Sperrwirkung: Datenoffenlegung, Sicherheits-/Verkehrsgefährdung: keine Freigabe)
- Gate UX: Nachweis teilweise (Sperrwirkung: Blockierte Kernreise vor Freigabe beheben)
- Gate Betrieb: Nachweis teilweise (Sperrwirkung: Fehlender Wiederanlauf- oder Kernbetriebsnachweis verhindert Pilot)
- Gate Pilot: Nachweis fehlt (Sperrwirkung: Abbruchkriterien und Rückfallbetrieb vor Pilotbeginn festlegen)
