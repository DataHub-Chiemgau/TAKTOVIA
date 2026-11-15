# tests/acceptance

Akzeptanzgerüst (WP-M0-22): Szenarien mit AT-/E2E-/PH-AT-Kennung, Zwei-Mandanten-Seed, Evidenzdatensätze nach Kap. 08 §8.4.
Der PSP-Simulator (WP-M0-23) und die Sandbox-Partner (WP-M0-24) liegen unter [`../sandbox/`](../sandbox), die gemeinsame
Testbibliothek unter [`../lib/`](../lib).

- **Verantwortlich:** Plattform (QA) (Rolle laut D-12, noch keine Personen benannt)
- **Voraussetzungen:** Python 3.13, `pip install -r tests/requirements.txt`, PostgreSQL mit PostGIS und die Umgebungsvariablen aus `db/db.py`.
- **Test des Gerüsts:** `python -m unittest discover -s tests/acceptance -p 'test_*.py'` (legt eigene Datenbanken `taktovia_at_selftest_*` an und verwirft sie).
- **Runner:** `python tests/acceptance/run.py --list | --scenario TECH-SMOKE-001 | --all` (siehe `--help`; `--bootstrap` legt die Datenbank wie in CI an).
- **Regeln:** [CONTRIBUTING.md](../../CONTRIBUTING.md)

## Inhalt

| Pfad | Zweck |
|---|---|
| `taktovia_at/scenario.py` | `@scenario(test_id, version, source_keys, name)`, Ausführungskontext, Evidenzerzeugung |
| `taktovia_at/seed.py` | Seed „Mandanten A/B“ (PH-AT-001), [Seed-Verfahren](docs/seed-verfahren.md) |
| `taktovia_at/evidence.py`, `schema/evidence.schema.json` | Evidenzschema, Personendatenprüfung, Ablage |
| `taktovia_at/defects.py` | [Mangelklassen A/B/C](docs/mangelklassen.md) |
| `m1_gate/` | **M1-Gate-Abnahmesuite (WP-M1-45):** die elf Gate-Tests (PH-AT-001/-011/-017/-018, AT-18/-24/-49, E2E-091/-096/-097/-098) als Szenarien unter der Runtime-Rolle; `run.py` (Lauf, Evidenz, `--write-e2e`), `traceability.py` (Gate-Regeln R1 bis R3), Umfang je Test: [docs/m1-gate-umfang.md](docs/m1-gate-umfang.md); Evidenz unter `evidence/m1/` |
| `test_dsfa_gate.py`, `test_loeschjournal_restore.py` (in `security/`) | Release-Gate der DSFA (`tools/check_dsfa.py`, WP-M1-42) und Restore belebt Gelöschtes nicht wieder (WP-M1-33/-39) |
| `catalog/scenarios.csv` | 106 + 22 Szenarien, Zielmeilenstein vorläufig (erzeugt mit `tools/build_catalog.py`) |
| `taktovia_at/scenarios/smoke.py` | Rauchtest `TECH-SMOKE-001` |
| [`docs/testzeit.md`](docs/testzeit.md) | Steuerbare Testzeit und ihre Grenze |

## Ergebnisse und Grenzen

- Evidenz entsteht unter `tests/acceptance/evidence-out/` (nicht versioniert) und trägt `acceptance_decision: PENDING`.
- Simulator- und Sandbox-Anteile stehen in `environment.simulated_components`; sie sind kein Nachweis gegen einen realen PSP oder Partner.
- Die 128 Katalogszenarien sind benannt, aber nicht implementiert (`NOT_IMPLEMENTED`); ein Aufruf liefert Exit-Code 2.
- Nicht in CI verdrahtet (`.github/` gehört nicht zu diesem Paket): Aufruf siehe oben, Abhängigkeiten in `tests/requirements.txt`.

## M1-Gate-Suite (WP-M1-45)

```bash
cd backend && ./gradlew build              # erzeugt die JUnit-Ergebnisse, auf die die Szenarien verweisen
python tests/acceptance/m1_gate/run.py     # elf Szenarien, Evidenz nach tests/acceptance/evidence/m1/, Exit 0 nur bei allen grün
python tests/acceptance/m1_gate/run.py --write-e2e   # trägt Build, Nachweis, Entscheidung (Ausstehend) aus dem Lauf in docs/pflichtenheft/e2e.csv ein
python tests/acceptance/m1_gate/traceability.py [--strict]   # CI-Regeln: keine umgesetzte MUSS-Zeile ohne Testfall, e2e.csv nur aus Läufen
```

Die Szenarien prüfen den in M1 prüfbaren Umfang ([m1-gate-umfang.md](docs/m1-gate-umfang.md)); nicht prüfbare Teilschritte stehen dort mit Zielmeilenstein (OP-02). Die Abnahmeentscheidung bleibt `PENDING`/`Ausstehend`.

- `test_m4_referenz.py` (WP-M4-02): Referenzdaten M4 (zwei Mandanten, Musterfahrt, Sollwerte PH-AT-012/-013/-015/-021, DST-Stichtage) laden reproduzierbar und mandantengetrennt; Beschreibung in `fixtures/m4_referenz/README.md`.

## M2-Gate-Suite (WP-M2-48)

```bash
cd backend && ./gradlew build --continue        # JUnit-Ergebnisse
cd web && npm ci                                  # Web- und Browserläufe (Chrome)
python tests/acceptance/m2_gate/run.py [--write-e2e]   # 16 Szenarien, Evidenz nach tests/acceptance/evidence/m2/
```

PH-AT-004, AT-01 bis AT-19 (soweit M2) und die M2-Anteile von PH-AT-001/-010/-011/-018, E2E-096/-098. Umfang je Test und offene Teilschritte (AT-01 bis M3, AT-13 bis M6/M7, AT-19 manuelles Protokoll): [docs/m2-gate-umfang.md](docs/m2-gate-umfang.md).
Datenbank-, Web- und Browsertests werden im Szenario **ausgeführt**; Backend-Tests über ihre JUnit-Ergebnisse. Last- und Skalierungsläufe liegen in `tests/performance` (eigener Lauf). Die RLS-Abdeckungsprüfung aller M2-Tabellen: `db/tests/test_m2_48_rls_abdeckung.py`.

- `test_stammdaten_migration.py` (WP-M5-25, LH-6.20-QAT-005): Einspielwerkzeug `tools/stammdaten_import` gegen die **echte Anwendung** (Spring-Boot-Jar als Prozess, lokaler JWT-Test-Issuer statt Keycloak, Testwelt `stammdaten_testwelt/`, Referenzdatensatz `fixtures/stammdaten_referenz/`); braucht `cd backend && ./gradlew :app:bootJar`, sonst werden die Tests übersprungen (mit `TAKTOVIA_REQUIRE_APP_JAR=true` schlagen sie fehl).

## M3-Gate-Suite (WP-M3-56)

```bash
cd backend && ./gradlew build --continue        # JUnit-Ergebnisse
cd web && npm ci                                  # Webtests (Vitest)
python tests/acceptance/m3_gate/run.py [--write-e2e]   # 19 Szenarien + M2-Regression, Evidenz nach tests/acceptance/evidence/m3/
```

PH-AT-020, AT-08 bis AT-11, AT-26 bis AT-36, AT-50, AT-51, AT-60 unter der Runtime-Rolle mit beiden Mandanten, dazu die Regression PH-AT-004 und AT-01 bis AT-05 nach Ablösung der M2-Fixtures. Die Parallelitätsharness von PH-AT-020 ist
`backend/app/src/test/kotlin/de/taktovia/app/fleet/PhAt020SafetyHarnessTest.kt` (120 Durchläufe, beide Reihenfolgen und Wettlauf, mit Negativkontrolle). Umfang, offene Teilschritte (OP-12, OP-13, OP-21, Flutter) und Zielmeilenstein
je Test: [docs/m3-gate-umfang.md](docs/m3-gate-umfang.md). Die Abnahmeentscheidung bleibt `PENDING`/`Ausstehend`.

## E2E-Referenzfall, negative Fälle, Fault-Injection (WP-M5-23, WP-M5-24, WP-M5-16)

**Ersatzumgebung, kein Staging.** Die Szenarien laufen gegen die ECHTE Anwendung (Spring-Boot-Jar als Prozess, Runtime-Rolle, PostgreSQL mit RLS), einen lokalen JWT-Test-Issuer (statt Keycloak), den PSP-Simulator
(statt PSP-Sandbox, D-03) und ohne Messaging-Broker und Redis (der Outbox-Relay stellt im Prozess zu, `taktovia.events.local-loopback`). Ein Lauf gegen Staging ist in dieser Umgebung nicht ausführbar.
Voraussetzung: `cd backend && ./gradlew :app:bootJar` und die Datenbankumgebung aus `db/db.py`; ohne Jar werden die Tests übersprungen (`TAKTOVIA_REQUIRE_APP_JAR=true`: schlagen fehl).

| Datei | Inhalt |
|---|---|
| `e2e/referenzwelt.py`, `e2e/referenzfall.py` | Welt (Anwendung, Issuer, PSP-Simulator, vier Mandanten mit eingespielten Stammdaten) und Bausteine des Referenzfalls (Angebot, Hold, Buchung, Zahlung, Plan, Dienststart, Fahrerereignisse, Kennzahlen) |
| `e2e/test_referenzfall_staging.py` | WP-M5-23 (LH-6.20-QAT-003): Referenzfall mit und ohne Zahlung, Kennzahlen mit Datenstand, zweiter Lauf, zwei Mandanten, Wächter, Evidenz und Log-Stichprobe |
| `e2e/waechter.py` | Wächter „keine manuelle DB-Korrektur“: Anweisungs-Trigger je Tabelle, wertet die Schreibzugriffe nach Rolle aus (Runtime erlaubt, Testaufbau `e2e-testaufbau` ausgewiesen, alles andere Befund) |
| `e2e/aufraeumen.py` | Aufräumprüfung (verwaiste Holds, überfällige Leases, unbestimmte Zahlungen ohne Abgleich, unzugestellte Outbox, Stopps stornierter Buchungen); Klärfälle werden getrennt ausgewiesen |
| `test_negative_e2e.py` | WP-M5-24 (LH-6.20-QAT-004): Doppelbuchung (100 parallel), No-Show, Storno nach Disposition, Hold-Ablauf, PSP-Timeout, Solver-Timeout, Fahrzeugausfall, Idempotenzkonflikt, zwei Mandanten; Tabelle Negativfall - Endstatus - Aufräumprozess - Frist im Modulkopf |
| `test_fault_injection_m5.py` | WP-M5-16: Datenbank (Abriss, Ausfall, Failover unter 40 parallelen Bestätigungen), Worker-Absturz zwischen PSP-Aufruf und Commit, PSP, Messaging, Redis-Zustand, Kartenquelle; Erwartungstabelle im Modulkopf |
| `test_betriebshandbuch.py` | WP-M5-26: Vollständigkeitsabgleich Alarmkatalog <-> Runbooks (`tools/check_betriebshandbuch.py`) |

Der Testaufbau (Planbindung des Angebots, Fahrzeugfreigabe, Schicht, Planstopps, Rückdatieren eines Holds, Fahrgastprofil) schreibt per Administratorzugang, weil die Einfügeheuristik, die Freigabeketten und die Registrierung in den
Anwendungspaketen nicht an diese Pfade verdrahtet sind; der Wächter weist diese Schreibzugriffe getrennt aus. Backend-seitig gehören dazu die zuschaltbaren Zeitgeber ohne Betriebswerkzeug: `taktovia.workers.tenant-file`
(Mandantenliste des Betriebs), `taktovia.events.local-loopback` und `taktovia.events.loopback-tenant-file` (Ereigniszustellung im Prozess).

- `test_dokumentationspaket.py` (WP-M5-27, LH-6.20-QAT-006): Dokumentationspaket `docs/dokumentation/` gegen den Build (`tools/check_dokumentationspaket.py`: Versionierung, Oberflächen- und Schnittstellenabgleich, Personendaten, Platzhalter, Zugänglichkeit, Fahrerschulung, Standardbetrieb mit Protokollprüfung). Die Demonstration durch den Abnehmer ist **nicht** ausgeführt.
- `test_pilotbereitschaft.py` (WP-M5-29): Abbruchkriterien, Entscheidungsjournal, Prozessverantwortliche, Protokollprüfung (synthetisch) und Produktionseinspielung in einer Ersatzumgebung (echte Anwendung und Werkzeug; braucht `./gradlew :app:bootJar`, sonst übersprungen). Generalprobe, Rückfallübung und echte Produktionseinspielung sind **nicht** ausgeführt; siehe `docs/pilot/pilotbereitschaft.md`.

## M5-Gate und Nachweisführung P1 (WP-M5-31)

```bash
python tests/acceptance/m5_gate/regression.py --build <Commit> [--write-e2e]   # alle Gate-Suiten auf EINEM Build (M1 bis M4, AT-23, AT-59, AT-25/-53/-54/-61)
python tools/nachweis_p1.py lauf --python db/tests --python tests/acceptance ... --vitest-json web.json   # Testergebnisse sammeln
python tools/nachweis_p1.py bericht --write       # Nachweismatrix (docs/umsetzungsplan/nachweise/nachweismatrix-p1.csv) und Gate-Bericht (docs/umsetzungsplan/gate-bericht-m5.md); Exit 1 bei Verstößen
python tools/nachweis_p1.py fachmodell --write    # Review-Protokoll Fachmodell
```

`test_nachweis_p1.py` prüft das Werkzeug mit synthetischen Mini-Repositories (entfernte Evidenz, fremder Build, Mängelregeln, Gates). Der Bericht über das echte Repository weist offene Punkte aus; er entscheidet nichts (Abnahme: Auftraggeber, D-12).
