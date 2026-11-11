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
| `catalog/scenarios.csv` | 106 + 22 Szenarien, Zielmeilenstein vorläufig (erzeugt mit `tools/build_catalog.py`) |
| `taktovia_at/scenarios/smoke.py` | Rauchtest `TECH-SMOKE-001` |
| [`docs/testzeit.md`](docs/testzeit.md) | Steuerbare Testzeit und ihre Grenze |

## Ergebnisse und Grenzen

- Evidenz entsteht unter `tests/acceptance/evidence-out/` (nicht versioniert) und trägt `acceptance_decision: PENDING`.
- Simulator- und Sandbox-Anteile stehen in `environment.simulated_components`; sie sind kein Nachweis gegen einen realen PSP oder Partner.
- Die 128 Katalogszenarien sind benannt, aber nicht implementiert (`NOT_IMPLEMENTED`); ein Aufruf liefert Exit-Code 2.
- Nicht in CI verdrahtet (`.github/` gehört nicht zu diesem Paket): Aufruf siehe oben, Abhängigkeiten in `tests/requirements.txt`.
