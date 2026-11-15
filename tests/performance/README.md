# tests/performance

Gerüst für Lastprofile, Messbericht und Restore-Drehbuch gegen die Referenzlast (Kap. 08 §8.6), WP-M0-25.
Die Spike-Berichte (WP-M0-26 bis -30) liegen unter [`spikes/`](spikes) und verwenden dieselben Kopffelder.

- **Verantwortlich:** Plattform (QA) (Rolle laut D-12, noch keine Personen benannt)
- **Voraussetzungen:** Python 3.13, `pip install -r tests/requirements.txt` (nur `jsonschema` wird für Profile und Berichte gebraucht).
- **CI (manuell auslösbar):** Beispiel [`ci-manuell.yml.example`](ci-manuell.yml.example) (nicht aktiv, nicht ausgeführt; `.github/` gehört nicht zu diesem Paket).
- **Beispiellauf:** `python tests/performance/run.py --profile tests/performance/profiles/health-example.json --stub-target`
  (gegen ein laufendes Backend stattdessen `--target http://127.0.0.1:8080`). Ausgabe unter `tests/performance/out/` (nicht versioniert): `bericht.md`, `bericht.json`, `rohdaten.csv`.
- **Test:** `python -m unittest discover -s tests/performance -p 'test_*.py'`
- **Regeln:** [CONTRIBUTING.md](../../CONTRIBUTING.md)

## Werkzeugwahl (festgeschrieben, Revisionsvorbehalt)

Festgeschrieben ist der eigene Lastgenerator `perfkit/runner.py` (nur Python-Standardbibliothek): geschlossene Last mit fester
Parallelität, gewichteter Endpointmix, Aufwärmphase, Rohdaten je Anfrage, Perzentile nach Nearest-Rank. Gründe: In der
Entwicklungsumgebung waren keine externen Lastwerkzeuge installierbar (GitHub-Releases nicht erreichbar; andere Werkzeuge wurden
nicht bewertet), und der Generator ist in CI ohne Zusatzinstallation lauffähig. Grenze: Ein Python-Generator belastet eine Maschine nur begrenzt
und kennt nur HTTP/1.1. Zu M2, wenn Partner-Fanout und WebSocket-Last hinzukommen, ist zu prüfen, ob ein spezialisiertes Werkzeug (z. B. k6, Gatling)
nötig ist; das Profilformat und das Berichtsformat bleiben davon unberührt. Das ist ein Vorschlag, den das Team bestätigen muss.

## Inhalt

| Pfad | Zweck |
|---|---|
| `schema/profile.schema.json`, `profiles/health-example.json` | Lastprofil mit allen Größen aus Kap. 08 §8.6; Referenzziele als Parameter (vorläufig) |
| `perfkit/runner.py`, `run.py` | Runner mit tatsächlich gemessenen Perzentilen, Rohdaten, Bericht |
| `schema/report.schema.json`, `perfkit/report.py` | Berichtsformat (Perzentile, Stichprobengröße, Hardware, Datenstand, Rohdatenablage) |
| `templates/messbericht-vorlage.md` | Berichtsvorlage für Messläufe und Spikes |
| `schema/restore-drehbuch.schema.json`, `templates/restore-drehbuch-vorlage.json`, `perfkit/restore.py` | Vorlage „Restore-Drehbuch“ mit allen Protokollfeldern, RPO/RTO-Auswertung |

## Offene Pflichtparameter

Spitzen-Requests/s und Anzahl aktiver Mandanten sind im Profil als `open_parameters` mit Wert `null` und Status „OFFEN (D-05)“ ausgewiesen.
Ein Profil im Modus `measurement` wird ohne diese Werte abgewiesen; ein Beispielprofil läuft, der Bericht ist dann `valid_for_acceptance: false`.
Das Restore-Drehbuch ist nur eine Vorlage; das Wiederanlaufdrehbuch (WP-M5-14) steht in `wiederanlauf-drehbuch-m5.md` (Tests `test_pitr.py`, `test_wiederanlauf_drehbuch.py`, Trockenlaufprotokoll in `protokolle/`); die Durchführung der Ausfallübung mit RPO-/RTO-Messung und PSP-Abgleich ist WP-M5-15 (`ausfalluebung/`, Plan `ausfalluebung-plan-m5.md`, Test `test_ausfalluebung.py`, Protokoll `protokolle/ausfalluebung-m5-15.json`).

## Referenzdatenbestand (WP-M5-02)

`datagen/generator.py`, `datagen/fill.py`: deterministischer synthetischer Bestand je Mandant (250 Fahrzeuge, Fahrer, Dienstplan, Schichten, Freigaben, Umläufe, Fahrgäste, 30.000 Buchungen/Tag über mehrere Betriebstage, Positionen), befüllt unter der Runtime-Rolle; `python tests/performance/datagen/fill.py --scale reference --provision` (Zugang wie `db/db.py`), Protokoll mit Zeilenzahlen je Tabelle. Tests: `test_datagen.py`, `test_umgebungsaufbau.py` (kleine Skala, Datenbank erforderlich).


## Referenzlast gegen die echte Anwendung (WP-M2-48, WP-M5-10, WP-M5-12)

`refload/` startet das Spring-Boot-Jar (`backend/app/build/libs/app.jar`, **produktive Verdrahtung, Runtime-Rolle, RLS**) gegen die Datenbank und einen lokalen JWT-Test-Issuer (JWKS, RS256; Ersatz für Keycloak, nicht ausgeführt: echter IdP),
legt je Mandant Dienst, Gebiet, Haltepunkte, veröffentlichten Tarif (Vier-Augen), Testflotte und Fahrersitzungen über die API bzw. SQL an und belastet sie mit einem asynchronen Lastgenerator (Fahrgast-Buchungssitzungen mit offener
Ankunftsrate, Lesesitzungen mit Think-Time, Leitstelle, Fahrer-Positionen und Planabruf, Überlastakteure). Berichte (Format wie oben, dazu Stufen, Mandanten, Sättigung, Invarianten, Isolation) entstehen unter `tests/performance/out/`.

| Datei | Zweck |
|---|---|
| `profiles/m2-buchung.json`, `test_m2_lastprofil.py` | kleines Regressionsprofil, fortlaufender Lauf ab M2 (WP-M2-48), Beispiel für CI: [`ci-m2-lasttest.yml.example`](ci-m2-lasttest.yml.example) |
| `profiles/referenz-skalierung.json`, `test_referenzprofil.py` | Referenzprofil: 2 Mandanten, 250 Fahrzeuge und Fahrersitzungen je Mandant, 5.000 Lesesitzungen je Mandant (Annahme), 30.000 Buchungen/Tag mit Peakfaktor, Stufen 50 %, 100 %, Überlast (WP-M5-10) |
| `test_angebot_p95.py`, `refload/offerchain.py` | Angebotskette: Routingmatrix kalt/warm, Einfügeheuristik auf 250 Fahrzeugen, Worker-Warteschlange, Timeout-Verhalten, Konkurrenz ohne Hold (WP-M5-12) |
| `refload/invariants.py` | Invariantenabfrage nach dem Lauf (keine Überbelegung, keine Doppelbestätigung, kein Fremdmandant) |

Aufruf (mit Opt-in, weil Minuten Laufzeit): `cd backend && ./gradlew :app:bootJar`, dann `TAKTOVIA_PERF_RUN=1 python -m unittest tests/performance/test_m2_lastprofil.py` (Datenbankumgebung wie `db/db.py`, auch `TAKTOVIA_DB_ADMIN_PASSWORD`).
`TAKTOVIA_PERF_STAGE_SCALE` skaliert die Stufendauer des Referenzlaufs (Standard 0,4; der dokumentierte Messlauf nutzt 1,0). **Grenzen:** Generator, Anwendung, PostgreSQL und Issuer teilen sich die CPUs der Maschine; die Werte
gelten für diese Umgebung und nicht als Abnahme (D-05 offen). Die Produktionsverdrahtung von `/v1/ride-offers` bindet Angebote noch nicht an Kandidatensuche und Solver (`CandidateGate.PASS_THROUGH`); Hold und Confirm laufen mit einer
im Testaufbau gesetzten Planbindung (SQL). Beides steht in jedem Bericht unter „Grenzen“.

## Standard-API je Endpunktklasse (WP-M5-11)

`test_standard_api_p95.py`, `refload/standardapi.py`: Messung der nicht-optimierenden Standard-API (`/v1/me`, Buchungen Lesen/Confirm/Storno, Dienste, Gebiete, Haltepunkte, Fahrzeuge, Fahrerereignisse und Umlauf, Schäden, Dienstpläne, Zeitereignisse, Konfiguration, Kennzahlen, 404-Pfade für Beleg, Operation und Prüfung) gegen die echte Anwendung (Runtime-Rolle, RLS) mit zwei Mandanten und dem Datenbestand aus WP-M5-02 (Skala `TAKTOVIA_PERF_STD_SCALE=mittel|small|reference`, Standard mittel: 250 Fahrzeuge, 4.500 Buchungen je Mandant). Dazu der Zeitseitenkanal (fremde gegen eigene unbekannte UUID) und die Cache-Probe (identische Pfade beider Mandanten parallel). Protokoll mit p50/p95/p99 je Klasse, Hardware, Datenvolumen, Build, Migrationsstand und Seed unter `out/standardapi/` (geprüfte Fassung und Befunde: `protokolle/standard-api-p95-m5-11*.md`). Aufruf: `TAKTOVIA_PERF_RUN=1 python -m unittest test_standard_api_p95` (Datenbankumgebung wie `db/db.py`, bootJar gebaut; eine eigene Datenbank empfohlen). Messpunkt ist die Clientzeit auf derselben Maschine, Zielwert vorläufig (D-05, OP-04).
## Fahrer-Planrevision: Commit bis Darstellung (WP-M5-13)

`refload/planrevision.py` (Auswertung, Gerätesimulation, Planschreiber), `refload/planrevision_run.py` (Lauf, Protokoll), `test_planrevision_p95.py` (Messlauf gegen die echte Anwendung, Opt-in `TAKTOVIA_PERF_RUN=1`),
`test_planrevision_logik.py` (Auswertungslogik, läuft immer). 500 verbundene Geräte (2 Mandanten x 250, Live-Kanal offen) unter Grundlast der Positionsmeldungen; Phasen: Serie (p95-Nachweis), Konkurrenz (zwei Revisionen
kurz nacheinander, Bestätigung mit alter Revision parallel zur Planänderung), kurze Verbindungsunterbrechung (getrennt ausgewiesen), angehaltener Verteilweg (Ausreißer). `TAKTOVIA_PERF_PLANREV_SCALE` skaliert die Serie
(Standard 1,0). Messpunkt „dargestellt“ im Gerät: `mobile/driver/lib/run/display_probe.dart` (nicht ausgeführt, Flutter fehlt); die Simulation ersetzt das Zielgerät (OP-27). Der Relay ist in der Anwendung nicht
verdrahtet, der Planschreiber bildet `JdbcRunPlans.appendIn` nach: beides und weitere Grenzen stehen im Protokoll `protokolle/planrevision-m5-13.md`. Zielwert 10 s vorläufig, D-05 offen: keine Abnahme.

## Verfügbarkeitsmessung (WP-M5-05)

`availability/`: Tests der synthetischen Proben, des Monatsberichts und des Probemandanten (Werkzeug: `infra/availability`); Nachweis gegen die Anwendung in `protokolle/m5-05/`.
