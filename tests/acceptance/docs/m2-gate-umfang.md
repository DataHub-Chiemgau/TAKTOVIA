# Umfang der M2-Gate-Abnahmesuite (WP-M2-48)

Stand 13.11.2026. Die Suite `tests/acceptance/m2_gate/` führt 16 Szenarien (10 Gate-Tests von M2 und 6 M2-Anteile von M1-Referenztests) unter der Runtime-Rolle aus und schreibt Evidenz nach `tests/acceptance/evidence/m2/`.
Nachweisarten: JUnit-Ergebnisse des Backends (`./gradlew build`), **im Lauf ausgeführte** Datenbank-, Web- und Browsertests, SQL-Prüfungen unter der Runtime-Rolle. Die Abnahmeentscheidung bleibt `PENDING` (Auftraggeber, D-12).
Zuordnung `source_key[]` je Test: in den Szenarien (`scenarios.py`, Dekorator) und in der Evidenz.

| Test | Geprüfter Umfang in M2 | Nicht geprüft (Offene Punkte) | Zielmeilenstein |
|---|---|---|---|
| PH-AT-004 | 100 parallele Confirms und Holds auf den letzten Platz, Rollstuhlplatz, veränderte Routenrevision, gegenläufige Abschnittsreihenfolge, Abbruch mitten in der Transaktion; Datenbank-Hold-Automat | Lastvariante unter Referenzlast liegt in `tests/performance/test_referenzprofil.py` (eigener Lauf mit bootJar, nicht Teil dieser Suite) | M2 (Last: M5) |
| AT-01 | Anfrage, Angebot, bestätigte Buchung, Planzuweisung, Status/ETA aus dem Plan (Standardfahrt über HTTP, Anzeige = Zusage, Detail mit ETA) | Fahrerzuweisung in der Driver-App, Check-in, Fahrtende (Offener Punkt 1) | M3 (WP-M3-25, -29, -30) |
| AT-02 | Zusagefenster bleiben bei Einfügung erhalten, harte Grenzen, Planrevisionskonflikt, Einfügeheuristik im Worker | Produktionsverdrahtung der Einfügeheuristik an `/v1/ride-offers` (Tor `PASS_THROUGH`) | M3/M5 |
| AT-03 | zwei Clients auf den letzten Platz: höchstens eine bestätigte Reservierung; Varianten Rollstuhlplatz und veränderte Routenrevision | – | M2 |
| AT-04 | Fahrzeug mit/ohne Ausstattung, ungeeigneter Haltepunkt, Sitz/Rollstuhl-Vektor, parallele Rollstuhlbuchungen | – | M2 |
| AT-05 | Zeit-/Regelprüfung der Vorausbuchung (Vorlauf, Horizont, DST, Betriebszeitraum), UI-01 | – | M2 |
| AT-07 | Storno gibt Kapazität frei, neue Planrevision, Zusagen bleiben, Frist, Void/Erstattung, UI-04 | Fahrerinformation über die Driver-App | M2 (Driver-Teil M3) |
| AT-13 | Regelverbot am Plattformbaustein: verbotene Relation (Zonenregel) ohne Direktangebot, Grund „Regelverbot“, Eintrag in `travel_attempt` | Sperrregel gegen bestehende ÖPNV-Alternative, angebotene Alternative, Ausnahme (Offene Punkte 2 und 49) | M6/M7 (WP-M6a-26, -29, WP-M7a-30) |
| AT-15 | PSP-Timeout, späteres identisches Ereignis, 100 identische Webhooks, Abgleich, Retry ohne Doppelbelastung | echter PSP (Simulator, D-03) | M2 (PSP: Produktivschaltung) |
| AT-19 | automatisierte Kernreise-Regression (axe WCAG 2.2 AA, Tastatur, Live-Region, ohne Karte) und Browserlauf (320 px, 200 % Text, Touchflächen, Fokus, Kontrast) für die Passenger-PWA | manuelles Prüfprotokoll mit Screenreader (NVDA + Firefox, VoiceOver + Safari): keine Hilfstechnik in der Umgebung | M2 (Protokoll offen), M5 (alle Rollen) |
| PH-AT-001 | M2-Anteil: RLS-Abdeckung aller M2-Tabellen, Isolationslauf über die M2-Endpunkte mit zwei Mandanten, Invarianten mit mindestens zwei parallelen Transaktionen (Kapazität, Tarif-/Dienstversion, Gegenereignis, Bestätigung/PSP-Retry) | – | M2 |
| PH-AT-010 | Hold läuft während des Checkouts ab, SCA länger als Hold, Ablauf parallel zu Bestätigung (Einbetreiberzuschnitt) | Föderationsanteil (Partner-Holds) | P2, M6 |
| PH-AT-011 | M2-Anteil: Buchungs- und Zahlungsbefehle mit abweichendem Request bei gleichem Idempotenzschlüssel | – | M2 |
| PH-AT-018 | M2-Anteil: DST-Anfrage, Zusage und Tarif nach Konfigurationswechsel unverändert | – | M2 |
| E2E-096 | M2-Anteil: geänderte Vorlaufzeit wirkt erst ab Wirksamkeit, frühere Buchungen unverändert | – | M2 |
| E2E-098 | M2-Anteil: fehlerhafte Tarifversion, Zusage unverändert; Rollback als neue Revision (Konfiguration) | Tarif-Rollback als eigener Befehl (die Plattform kennt Rollback für Konfigurationsrevisionen; Tarifrevisionen sind unveränderlich, eine Korrektur ist eine neue Revision) | M2/M4 |

Vorbereitung von PH-AT-021, E2E-080 und E2E-081 (Auswertung in M4): die Datenbasis (Ereignisse zu Versuch, Angebot, Hold, Buchung, Storno, Zahlung) entsteht in den Backend-Tests der Szenarien; die Auswertung liegt bei M4.

## Lauf

```bash
cd backend && ./gradlew build --continue            # JUnit-Ergebnisse
cd web && npm ci                                      # Web- und Browserläufe
python tests/acceptance/m2_gate/run.py [--write-e2e]  # Evidenz unter tests/acceptance/evidence/m2/
```
