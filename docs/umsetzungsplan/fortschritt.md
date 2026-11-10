# Umsetzungsfortschritt

Stand 10.11.2026. Ein WP gilt nur als umgesetzt, wenn die unter „Tests“ genannten Fälle grün sind. Phase 1 ist begonnen, nicht abgeschlossen.

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
| übrige M0-WPs | offen | – |

Abweichungen vom Plan: Das Gradle-Gerüst fehlt noch, deshalb läuft die Datenbankprüfung in Python. Sie wird nach WP-M0-02 in die JVM-Testsuite übernommen oder bleibt als eigene CI-Stufe.
