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
| übrige M0-WPs | offen | – |

Abweichungen vom Plan:
- Die Datenbankprüfung (Rollen, Migration, RLS, Trigger) bleibt vorerst in Python; Flyway und Testcontainers sind im Gradle-Projekt nicht eingebunden, weil in der Entwicklungsumgebung kein Docker läuft. Die JVM-Tests verwenden eine vorhandene Datenbank (Umgebung oder CI-Service).
- Der M0-Gate-Test „Datenbankverbindung unter der Runtime-Rolle“ läuft deshalb nur mit gesetzten `TAKTOVIA_DB_*`-Variablen; ohne sie werden die vier Tests übersprungen, in CI schlagen sie dann fehl.
- Kotlin meldet für Gradle 8.14.3 eine Veraltungswarnung (ab Kotlin 2.5 wird 8.14.4 verlangt).
