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
