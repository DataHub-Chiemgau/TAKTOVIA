# Umfang der M3-Gate-Abnahmesuite (WP-M3-56)

Die Suite `tests/acceptance/m3_gate/` führt 19 Szenarien (PH-AT-020, AT-08 bis AT-11, AT-26 bis AT-36, AT-50, AT-51, AT-60) unter der Runtime-Rolle mit **beiden** Mandanten der Fixture aus, danach die Regression der M2-Gate-Tests
(PH-AT-004, AT-01 bis AT-05, Akzeptanzkriterium 9) und schreibt Evidenz nach `tests/acceptance/evidence/m3/` (Regression: `evidence/m3/regression-m2/`). Die Abnahmeentscheidung bleibt `PENDING` (Auftraggeber, D-12).
Nachweisarten: JUnit-Ergebnisse des Backends (`./gradlew build`; die Zwei-Mandanten-Fixtures, die Gerätesimulation für Offline-/Sync-Fälle und die Parallelitätsharness sind Teil dieser Backend-Tests), **im Lauf ausgeführte**
Datenbank- und Webtests (Vitest), SQL-Prüfungen unter der Runtime-Rolle. Zuordnung `source_key[]` je Test: in den Szenarien (`scenarios.py`, Dekorator) und in der Evidenz.

Ein Szenario mit **ausgewiesenen offenen Teilschritten** (Schritt mit Soll `offen`, Ist `offen (nicht geprüft)`) gilt in `e2e.csv` als „Teilweise bestanden (M3-Umfang, offene Teilschritte ausgewiesen)“; das gilt auch für alle Teile, die in
Flutter laufen (Driver-App UI-09/UI-10) und in dieser Umgebung nicht ausführbar sind (die Dart-Tests liegen unter `mobile/driver/test/`).

| Test | Geprüfter Umfang in M3 | Nicht geprüft (Offene Punkte) | Zielmeilenstein |
|---|---|---|---|
| PH-AT-020 | Freigabe nur bei erfüllten Bedingungen; Sperre, Qualifikationsentzug, Fahrzeugwechsel, Schichtüberlappung und Umplanung je gegen die Zusage mit mindestens zwei parallelen Transaktionen; **Parallelitätsharness** `PhAt020SafetyHarnessTest` mit 120 Durchläufen (40 Sperre zuerst, 40 Zusage zuerst, 40 echter Wettlauf) ohne Fehlzusage und **Negativkontrolle** (Zusagelogik ohne Safety-Check macht die Harness rot); Vertragsprüfungen je Endpunkt | Lastvariante unter Referenzlast (eigener Lauf, `tests/performance`) | M3 (Last: M7b) |
| AT-08 | Wartefrist, No-Show-Grund, gemessene Wartezeit, Regelstand, Klärfall ohne Ankunft, kein Gebühreneinzug; Ursache und Verursachungskategorie (WP-M4-55), No-Show-Rate | **Abrechnung regelkonform**: die Gebühren- und Erstattungsregel bei No-Show ist im Pflichtenheft nicht festgelegt (OP-12) | M4/M5 (nach Entscheidung) |
| AT-09 | Sperre, betroffene Buchungen (Störungsablauf WP-M3-54: Vorschläge je Fahrt, Umbuchung, Betreiberstorno mit Ursache Fahrzeugausfall, eingestiegene Fahrgäste gesondert, zwei Disponenten, keine doppelte Erstattung), Incident und Ablaufprotokoll, UI-07 | Inhalt der Fahrgastnachricht zur Umbuchung (Vorlagen, OP-27) | M3/M4 |
| AT-10 | unterbrochener Positionsstrom: veraltet gekennzeichnet mit letztem Stand und Messzeit, keine Extrapolation, nachgelieferte Positionen ersetzen nie die neuere, Live-Kanal, UI-07 Standortalter | – | M3 |
| AT-11 | Gerätesimulation: Netzverlust und Wiederverbindung, 100 identische Wiederholungen, alte Planrevision als Klärfall, Offline-Code, bedingter Abruf (ETag), Offline-Dienststatus | Anzeige der letzten gültigen Route in der Flutter-App (nicht ausführbar) | M3 (Flutter-Lauf in der CI der App) |
| AT-26 | Bereitmeldung ohne bestandene Prüfung serverseitig abgewiesen, fehlende Bedingungen benannt, Pflichtvorlagen (Fahrzeug, Fahrgastraum), UI-11 Prüfstatus | Anzeige im Driver-Prüfpfad (Flutter) | M3 |
| AT-27 | bestandene Prüfung: unveränderlicher Nachweis, Freigabe mit Qualifikationsnummer, Schichtstartwerte, Nachtrag ändert Freigabe nicht | Driver-Prüfpfad (Flutter) | M3 |
| AT-28 | kritisches „Nein“: synchrone Sperre, Alarm, bestehende Zuweisungen im Störungsablauf, Ersatzdisposition mit Vorschlägen | – | M3 |
| AT-29 | Bekanntschaden: Anzeige, Quittierung vor Abschluss, kein unbegründeter neuer Fall (Hinweis auf bestehenden), idempotent | Quittierung in der Flutter-App | M3 |
| AT-30 | Fahrzeugwechsel: Ersatzfahrzeug gesperrt bis zur neuen fahrzeugspezifischen Prüfung, alte Prüfung gilt nicht, Audit, parallele Wechsel | – | M3 |
| AT-31 | Offline-Prüfung: Geräte- und Empfangszeit getrennt, Sync einmalig, Sperre zwischenzeitlich verhindert Freigabe, zu alter Abschluss, Fahrzeugwechsel vor Sync | Offline-Speicherung in der Flutter-App | M3 |
| AT-32 | Historie zeigt ursprüngliche Fragen und Antworten, Vorlagenrevisionen unveränderlich, Nachträge | – | M3 |
| AT-33 | Schadenakte mit Fallnummer, Foto erst nach bestandener Prüfung sichtbar, Typ-/Größen-/Hash-Prüfung, signierter Abruf, Eindeutigkeit der Fallnummern unter Parallelität | **Medienprüfung im vollen Umfang** (Schadsoftware, Quarantäne): hängt von WP-M4-21 ab (OP-13); Flutter-Meldung mit Foto | M4 (WP-M4-21) |
| AT-34 | Klassifikation mit Regelversion, Sperre im selben Commit, Zuweisung, Statushistorie mit Akteur und Zeit, Alarm nach Regelmatrix | **Frist auslösen**: Fristen der Schadensbearbeitung sind nicht festgelegt (OP-12) | M4/M5 (nach Entscheidung) |
| AT-35 | Werkstattabschluss ohne technische Freigabe: Sperre bleibt; jede fehlende Bedingung hält sie; Freigabe nur berechtigt | – | M3 |
| AT-36 | Hinweis auf offene Fälle bei Neuanlage, Eindeutigkeit bei doppelter Übermittlung, Integritätsprüfung erkennt veränderte Beweise, Metadaten ohne Standort | **Dublettenhinweis über den Hinweis auf offene Fälle hinaus** (Ähnlichkeitserkennung, Zusammenführung): Regel offen (OP-13, OP-21) | M4/M5 |
| AT-50 | kritischer Schaden im Dienst, Ersatzfahrzeug mit neuer Prüfung, Umbuchung und Storno über den Störungsablauf, genau eine Mitteilung je Änderung, UI-07 | Inhalt der Umbuchungsnachricht (Vorlagen, OP-27) | M3/M4 |
| AT-51 | Leitstelle kann kritische Sperre weder operativ noch ohne Werkstattnachweis entfernen (serverseitig), Fehlversuch im Audit | – | M3 |
| AT-60 | Disposition erst nach Reparaturnachweis, technischer Freigabe und neuer Checkliste | – | M3 |

**Regression der M2-Gate-Tests** (Akzeptanzkriterium 9): PH-AT-004, AT-01 bis AT-05 laufen nach dem Ablösen der M2-Fixtures durch die M3-Module erneut (Szenarien der M2-Suite, eigene Evidenz unter `evidence/m3/regression-m2/`).

**Vertragsprüfungen je M3-Endpunkt** (Kap. 05 §5.8): Manipulation von `tenant_id`, fremde Kennung (404), fehlendes `If-Match` (428), veraltete Revision (412), gleicher Idempotenzschlüssel mit anderem Inhalt (409 `IDEMPOTENCY_CONFLICT`),
100 identische Wiederholungen, Offline-Check-in auf alter Planrevision: `CrossTenantIsolationTest` (alle gebauten Endpunkte), `ContractCoverageTest` (je Operation positiver Fall und Fehlerfall), `DriverApiTest`, `InspectionSubmitApiTest`,
`PlanRevisionDeliveryHttpTest`, `OperationalBlockApiTest`, `DriverSyncTest`, `DriverEventEffectsHttpTest` (siehe `scenarios.py: endpoint_contract`).

**UI-Abnahmefälle Kap. 06 §6.9:** UI-07 (Störungsansicht, Vorfälle, Fahrzeugliste, Zeitleiste) und UI-11 (Flotte: Schadenakte, Prüfstatus, Prüfhistorie) als prüfbare Fachzustände (Vitest: Leerzustand, Ladefehler, Rechtezustand, 412-Konflikt ohne stilles
Überschreiben); UI-09 und UI-10 (Driver-App: Prüfpfad mit kritischem Nein und Offline-Sync, Fahrerplan) laufen in Flutter und sind hier nicht ausführbar (Abweichung „Frist überschritten“: OP-12).

**Messprotokoll „Commit bis dargestellte Clientrevision“ und Standortalter:** die Messungen liegen in `PlanRevisionDeliveryHttpTest.neue_route_innerhalb_p95_dargestellt` (Plan-Commit bis zur Darstellung beim Fahrer) und `PositionCaptureTest`
(Standortalter, Schwelle); sie laufen als Backend-Tests im Szenario AT-10/AT-11. Ein eigener Messlauf unter Referenzlast gehört zu `tests/performance`.

## Lauf

```bash
cd backend && ./gradlew build --continue            # JUnit-Ergebnisse
cd web && npm ci                                      # Webtests
python tests/acceptance/m3_gate/run.py [--write-e2e]  # Evidenz unter tests/acceptance/evidence/m3/
```
