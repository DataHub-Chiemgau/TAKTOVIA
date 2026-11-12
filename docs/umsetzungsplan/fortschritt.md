# Umsetzungsfortschritt

Stand 11.11.2026. Ein WP gilt nur als umgesetzt, wenn die unter „Tests“ genannten Fälle grün sind. Phase 1 ist begonnen, nicht abgeschlossen.

## M0 – Fundament und Spikes

| WP | Stand | Nachweis |
|---|---|---|
| WP-M0-15 Datenbankrollen | umgesetzt | `db/db.py bootstrap`, `db/role_matrix.csv`, `db/tests/test_01_rollen.py` |
| WP-M0-16 Kernschema als Migration | umgesetzt (PostgreSQL 16; 17 über CI offen) | `db/migrations/V1__kernschema.sql`, `db/tests/test_02_migration.py` |
| WP-M0-17 CI Datenbankintegration | angelegt, noch nicht gelaufen | `.github/workflows/ci.yml` |
| WP-M0-13 CI Vertragsprüfung | angelegt, noch nicht gelaufen | `.github/workflows/ci.yml` |
| WP-M0-18 Tenantkontext-Testharness | umgesetzt (Python statt Gradle, siehe `db/README.md`) | `db/tests/helpers.py` |
| WP-M0-19 RLS-Laufzeittests | umgesetzt | `db/tests/test_03_rls.py` |
| WP-M0-20 Trigger-/Constraint-Tests | umgesetzt | `db/tests/test_04_trigger.py` |
| WP-M0-21 Befundbericht | umgesetzt | `befunde/kernschema-befunde.md`, `db/tests/test_05_haertung.py` |
| WP-M0-02 Backend-Gerüst | umgesetzt | `backend/` (20 Teilprojekte, Wrapper mit Prüfsumme, Lockfiles); `./gradlew build` auch mit `--offline` grün; `BackendStartTest` (Health UP, Runtime-Rolle, Guard weist Migrator/Admin ab) |
| WP-M0-03 Architekturtest Modulgrenzen | umgesetzt | `ArchitectureRules`/`ArchitectureTest` (grün), `ArchitectureRulesSelfTest` (5 rote Selbsttests), `TableOwnershipTest`, `db/table_owners.csv`, `leitlinien-pruefung.md` |
| WP-M0-01 Repository und Versionen | teilweise | Verzeichnisse, READMEs, CODEOWNERS, CONTRIBUTING, `check_repo.py`; Versionsmatrix mit 9 Bausteinen noch `offen` (siehe `versionen.md`); Branch-Schutz nur in den Repository-Einstellungen möglich |
| WP-M0-04 API-Verzeichnis | umgesetzt | `api/` (6 Bereiche + common), `api/tools/{bundle,lint,breaking,test_api}.py`, 18 Tests; Bündel kompatibel zum Ausgangsstand |
| WP-M0-05 Web-Gerüst | umgesetzt (Node 22; Node 24 offen) | `web/`: `npm ci && lint && test && build` grün, 8 Tests; TypeScript 6.0.3 statt 7.0.2 (typescript-eslint-Grenze) |
| WP-M0-06 Mobile-Gerüst | **nicht ausgeführt** | Dateien in `mobile/`; Flutter-SDK geladen, Start durch die Sitzung nicht erlaubt; kein analyze/test/pub get |
| WP-M0-07 Worker-Gerüst | teilweise | `workers/`: ruff, mypy strict, 19 + 5 Tests grün; Container-Build nicht ausgeführt (kein Docker) |
| WP-M0-08 bis -11, -35 Infrastruktur | teilweise, nur statisch geprüft | `infra/`: `check.py` grün; Dienste, Keycloak-Import, terraform nicht ausgeführt; MinIO/Valhalla-Images als Platzhalter |
| WP-M0-12 CI Build/Test | angelegt, nicht gelaufen | `.github/workflows/ci.yml` (repository, vertraege, datenbank, backend, web, workers, mobile) |
| WP-M0-13 CI Vertragsprüfung | umgesetzt (lokal), CI nicht gelaufen | API-Lint/Bündelung/Breaking-Prüfung, 12 Contract-Fälle als Skip-Tests |
| WP-M0-14 CI Lieferkette | angelegt, nicht ausgeführt | Job `lieferkette` (Secret-Scan, OSV); SBOM und Image-Signatur fehlen (kein Docker) |
| WP-M0-22 Akzeptanzgerüst | umgesetzt | `tests/acceptance`: 29 Tests, Rauchtest PASSED |
| WP-M0-23 PSP-Simulator | umgesetzt | `tests/sandbox/psp`: 38 Tests |
| WP-M0-24 Sandbox-Partner | umgesetzt | `tests/sandbox/partner`: 37 Tests |
| WP-M0-25 Performance-Gerüst | umgesetzt | `tests/performance`: 22 Tests |
| WP-M0-26 Spike (a) | umgesetzt (PostgreSQL 16) | `tests/performance/spikes/`: 1.000 × PH-AT-004 mit genau einer Bestätigung; 0 Deadlocks mit stabiler Sperrreihenfolge; p95 ≤ 561 ms bei 100 Parallelen; Befund B-04 bestätigt |
| WP-M0-27 Spike (b) | umgesetzt | `workers/routing/docs/spike-b-solver.md`: Referenzinstanz p95 1,64 ms, 0 Verletzungen; Matrixlatenz nicht gemessen |
| WP-M0-28 Spike (c) | **nicht ausgeführt** | Offline-Queue-Logik als Dart-Dateien, keine Tests gelaufen, keine Geräte |
| WP-M0-29 Spike (d) | nicht ausführbar | nur Simulator-Konformität (14 bestanden); kein PSP-Kandidat/Zugang |
| WP-M0-30 Spike (e) | teilweise | Probelauf gegen Referenzimplementierung; Anbietervergleich ohne zweite Variante/reale Daten nicht ausführbar |
| WP-M0-31 Detailvertragsprozess | umgesetzt | `vertragsprozess.md`, Vorlagen, 3 Probeverträge, danach alle 202 P1-Verträge |
| WP-M0-32 Nachverfolgungs-CI | umgesetzt (lokal) | `check_tracking.py` (12 Tests), `tools/transfer_evidence.py` (7 Tests inkl. Parallelität), `gate_report.py` |
| WP-M0-33 Abstimmung mit dem Auftraggeber | **offen, Beistellung** | braucht den Auftraggeber (Umfang, Testzuordnung, D-12, D-05) |
| WP-M0-34 Gate-Bericht M0 | offen | hängt an WP-M0-33, Gate-Abnahme durch den Auftraggeber |

## Phase 1, Meilenstein M1 (Plattformkern)

Stand der 202 Phase-1-Anforderungen (`arbeitspakete.csv`): 45 Umgesetzt, 28 In Umsetzung, 129 Bereit/Geplant. „Umgesetzt“ heißt: alle im Detailvertrag genannten Tests laufen grün (`tools/import_wave.py` übernimmt nur vorhandene Tests). Nicht ausführbar in dieser Umgebung (kein Docker, kein Keycloak, kein Flutter, keine CI): Browser-E2E, Container-Läufe und echte IdP-Anmeldung; die Ersatzläufe stehen im jeweiligen WP-Nachweis.

| Welle | Pakete (WP-M1-…) | Inhalt |
|---|---|---|
| 1 | 01, 02, 14, 17, 18, 32, 36, 38, 40, 43, 46, 47 | Tenantkontext, API-Grundlage, Zustellung Outbox/Inbox, Zustandsmaschinen-Rahmen, Dateninventar, Secret-Scan, Query-Lint, Web-Designsystem |
| 2 | 03, 04, 27 | OIDC-Ressourcenserver, zweiter Faktor, Umgebungstrennung, Zusage-Snapshot |
| 3 | 06, 07 | Anfragekontext, Mitgliedschaft, Mandantenwechsel, Rechtematrix |
| 4 | 05, 10, 12, 13, 15, 16, 19, 20, 24, 31, 34, 37, 44 | Mandant/Organisation/Rollen, Sitzungen und Kontosperre, Audit und Sicherheitsereignisse, Idempotenz, operation/Lease, Webhooks, Parameterkatalog und Konfigurationsrevisionen, Unternehmensprofil, Feldschutz, Zahlungsdatentrennung, Fahrzeugrollen, Web-Anmeldung |
| 5 | 08, 09, 21, 23, 26, 28, 29, 30, 41 (+ M2: 01, 02, 10, 14) | Organisationsstruktur, Dienst-/Objektberechtigung, Präzedenz und wirksame Konfiguration, Policy-Port, Wirkungsanalyse, Fahrgastregistrierung/-profil/Datenschutz, Sicherheitsvorfallprozess (Übung mit Menschen steht aus), M2-Detailverträge, Referenzfixtures, Routingadapter, Mehrpersonenbuchung |
| 6 | 11, 22, 25, 35 (+ M2: 15, 16, 29, 32, 33, 35; M3: 01, 24) | Veröffentlichung/Vier-Augen/Rollback von Konfiguration, Mandantenisolation außerhalb der Datenbank, Dienstkonfiguration und Angebotsmodelle, Fahrerfreigabe, Fahrzeugkandidatenfilter, Fahrtenplan, PSP-Adapter, Nachrichtenversand und -vorlagen, Passenger-PWA-Grundgerüst, Detailverträge M3, Driver-App-Basis (Dart, nicht ausgeführt) |
| 7 | 33, 39, 42, 45 (+ M2: 03, 04, 05, 06, 17, 18, 19; M3: 02, 03, 04, 05; M4: 64, 06, 08, 21) | Aufbewahrung/Archivierung/Löschung, verschlüsselte Backups mit Restore-Drehbuch, DSFA-Entwurf, M1-Gate-Suite; Dienste/Betriebszeiten, Bediengebiete, Haltepunkte, Zulässigkeit; Einfügeheuristik, harte Grenzen, Zeitfensterschutz; M3-Tabellen, Fahrzeug-/Fahrerstamm; M4-Modulzugänge, Abwesenheit, Ist-Arbeitszeit, Uploadticket/Quarantäne |
| 8 | M2: 07, 08, 11, 12, 13, 20, 36, 38, 39, 40; M3: 06, 07, 10, 25, 34, 43, 44, 47, 48; M4: 02, 03, 22, 23, 24, 27 | Tarifrevision und Tarifengine, Anfrageerfassung/Zulässigkeit/Ablehnungsgründe, Mehrzielbewertung, Konto/Ortssuche/Standortfreigabe/Karte der Passenger-PWA; Fahrzeugpool-Zuordnung, Schichtzuweisung und Dienstanmeldung, Positionserfassung, Checklistenvorlagen, Schadenakte mit Fotoevidenz, Statusautomat und Historie; M4-Fixtures/Leseports, Schichtmodell, Belegaggregat, Originale/Hash, OCR-Adapter |

Prüfstand nach Welle 6 (lokal, PostgreSQL 16): `./gradlew build` grün, DB-Tests 305 OK, Web (Operator 75, api-client 26 u. a.) Tests (Lint, Build grün), API-Lint/Bündel/Client/Doku PASS, Query-Lint, Secret-Scan, `check_repo`, `check_tracking` PASS.

Prüfstand nach Welle 7 (lokal, PostgreSQL 16): `./gradlew build` grün, DB-Tests 409 (ein bekannter Flaky-Test `test_vehicle_assignment` im Gesamtlauf, allein grün), Web (Operator 107, api-client u. a.) Tests/Lint/Build grün, API-Lint/Bündel/Client/Doku PASS, Routenabgleich 125 Routen PASS, Query-Lint, Secret-Scan (Baum/Verlauf), `check_repo` PASS.

Prüfstand nach Welle 8 (lokal, PostgreSQL 16): `./gradlew build` grün, DB-Tests 507 OK, Web (Operator, Passenger, api-client u. a.: 352 Tests) mit Lint und Build grün, API-Lint/Bündel/Client/Doku PASS, Routenabgleich 180 Routen PASS, Query-Lint, Secret-Scan (Baum/Verlauf), `check_repo`, DSFA-Gate (Struktur) PASS.

Abweichungen vom Plan:
- Die Datenbankprüfung (Rollen, Migration, RLS, Trigger) bleibt vorerst in Python; Flyway und Testcontainers sind im Gradle-Projekt nicht eingebunden, weil in der Entwicklungsumgebung kein Docker läuft. Die JVM-Tests verwenden eine vorhandene Datenbank (Umgebung oder CI-Service).
- Der M0-Gate-Test „Datenbankverbindung unter der Runtime-Rolle“ läuft deshalb nur mit gesetzten `TAKTOVIA_DB_*`-Variablen; ohne sie werden die vier Tests übersprungen, in CI schlagen sie dann fehl.
- Kotlin meldet für Gradle 8.14.3 eine Veraltungswarnung (ab Kotlin 2.5 wird 8.14.4 verlangt).
- Welle 4, Integration: Das Unternehmensprofil (WP-M1-24) liegt als Scope `TENANT_PROFILE` in `config_revision` (PA-11), die Revisionsverwaltung (WP-M1-20) kennt nur TENANT/REGION/DEPOT/SERVICE. `V1250` führt beides zusammen (zusätzlicher Scope, eigenes Schema `tenant-profile.v1` statt Katalogprüfung, Schema-Version-Prüfung im Trigger statt Fremdschlüssel). Die Rechtematrix steht auf Version 3 (zwei Zweige hatten unabhängig auf 2 erhöht).
- Der Zweig von WP-M1-05 hatte `docs/pflichtenheft/api/openapi.yaml` (eingefrorener Ausgangsstand) aus `api/` neu erzeugt; das ist zurückgenommen, die Kompatibilitätsprüfung (`bundle.py --check`) läuft wieder gegen den Stand vom 25.10.2026. Neue Scopes gehören in `api/common.yaml`.
- Welle 5, Integration: Der Agent von WP-M1-41 lieferte keinen gültigen Strukturbericht; Stand aus Branch und Agentenbericht: Technik, Betriebsdokument, Drehbuch, Protokollvorlage und Trockenlauf gebaut, echte Übung mit Menschen und Freigabe (OP-32, D-11, D-05) fehlen, daher „In Umsetzung“. Die Rechtematrix bleibt Version 3 (neue Zeilen, Lock erneuert). `api/contract-pending.txt` enthält die M2-Operationen aus WP-M2-01 (dokumentiert, noch nicht gebaut).
- Welle 6, Integration: Die Rechtematrix bleibt Version 3 (Zeilen für Publikation, Dienstkonfiguration, Fahrer/Leitstelle/Flotte, M2-Buchung ergänzt, Lock erneuert). Der Querschnitts-Isolationstest deckt die neuen Endpunkte ab; der Provider-Eingang `receiveProviderEvent` (Signatur statt Mandantentoken) ist begründet ausgenommen. Zwei Tests korrigiert (Ticket-Signaturtest hing von Base64-Füllbits ab; Zahlungsereignis-Parallelitätstest überschritt `max_connections`). `ResourceServerTest.jwks_ausfall_fail_closed_nach_max_stale` scheiterte im vollen Lauf einmal an der Umgebungsprüfung (Ursache nicht abschließend geklärt, vermutlich ausgeschöpfte DB-Verbindungen unter Last; die Fehlermeldung nennt jetzt die Ausnahme).
- Welle 7, Integration: Die Rechtematrix bleibt Version 3 (Zeilen für Aufbewahrung, M3-Stämme, Abwesenheit, Zeitereignisse, Belege ergänzt, Lock erneuert). Die M3-Tabellen im Besitz von `staff-time` (`driver`, `qualification_revision`, `driver_plan_ack`, `position_sample`, `vehicle_status_history`, `boarding_code`) sind aus dem M4-Katalogtest ausgenommen (DB- und Architekturtest). WP-M1-39, -42, -45 sowie WP-M3-02, -05 und WP-M4-64 bleiben „teilweise“: WAL/PITR und S3 Object Lock nicht gegen echte Infrastruktur geprüft, die DSFA ist ein Entwurf ohne Freigabe (OP-32), der prüfbare Umfang der Gate-Tests ist nicht mit dem Auftraggeber festgelegt (D-12), Fremdschlüsselaktivierung und Modulzugänge für M3 fehlen noch. Löschfristen und -matrix sind nicht beschlossen (D-11, OP-25); die Produktivschaltung personenbezogener Löschläufe bleibt blockiert.
- Welle 8, Integration: Zehn Cluster, davon überschnitten sich Cluster 2 (Schichtmodell M4, WP-M4-03) und Cluster 7 (Schichtzuweisung M3, WP-M3-07/-25) in Namen (`Shifts`, `JdbcShifts`, `ShiftContractCases`, Audit-Konstanten): das M4-Modell heißt jetzt `RosterShifts` (Audit `staff.roster_shift.*`), die M3-Zuweisung behält `Shifts`. Rechtematrix bleibt Version 3 (Lock erneuert). `FleetsafetyApi.create` trägt jetzt `store` (Standard: In-Memory-Objektspeicher für Tests) sowie `driverRelation` und `evidence`. `api/fleet-safety.yaml` wurde semantisch aus beiden Seiten zusammengeführt und neu geschrieben (Formatierung geändert, Inhalt beider Seiten erhalten). Drei Fixtures wurden an die neuen Schichtgrenzen aus WP-M3-07 angepasst (Überlappungsverbot, höchstens 24 h). Die Listenantworten von `listStops`/`listServiceAreas` sind Unions (Operator- und Passenger-Sicht); die Passenger-PWA typisiert auf die Passenger-Sicht.
- Nur teilweise (Welle 8): WP-M2-08 (Testrelationen und Steuersätze sind eigene Annahmen, OP-12/D-05), WP-M2-38 (kein echter Geocoder, D-09), WP-M2-39 und WP-M2-40 (kein Browser-E2E, Kartenquelle D-09), WP-M3-06 (OP-25/OP-14 fachlich offen), WP-M3-44 (Flutter-Erfassung nicht ausgeführt), WP-M4-27 (kein Testsatz mit erwarteten Feldtreffern, keine Bild-OCR-Engine).
- Nicht nachgewiesen (Welle 6): Driver-App (Flutter) nicht ausgeführt; WP-M1-25 und WP-M1-35 stehen auf „In Umsetzung“ (Originalabnahme erst mit M2 bzw. offene Fachentscheidungen OP-26/OP-28), WP-M1-11 teilweise (kein S3/MinIO-Adapter, Such-/Beleg-/Exportendpunkte erst mit M2/M4).
