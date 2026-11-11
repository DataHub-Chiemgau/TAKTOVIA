# backend

Kotlin (Java 21) und Spring Boot, ein Gradle-Multiprojekt mit **einem** Deployable (`app`). Python-Worker liegen unter `workers/` und gehören nicht zu diesem Build.

```bash
cd backend
./gradlew build            # alle 20 Teilprojekte, Tests, Architekturregeln
./gradlew :app:bootRun     # Start gegen die lokale Datenbank (Runtime-Rolle)
./gradlew resolveAndLockAll --write-locks   # Lockfiles nach Abhängigkeitsänderung erneuern
```

Datenbanktests brauchen `TAKTOVIA_DB_HOST/PORT/NAME` und `TAKTOVIA_DB_RUNTIME_PASSWORD` (optional `TAKTOVIA_DB_ADMIN_*`, `TAKTOVIA_DB_MIGRATOR_PASSWORD` für den Rollen-Negativtest). Ohne Zugangsdaten werden sie übersprungen; mit `TAKTOVIA_REQUIRE_DB=true` (CI) schlagen sie stattdessen fehl. Schema und Rollen: [db/README.md](../db/README.md).

## Struktur und erlaubte Abhängigkeiten

| Projekt | Inhalt | darf abhängen von |
|---|---|---|
| `platform` | Tenantkontext, RLS, Outbox/Inbox, Idempotenz, Audit, Geld/Zeit, Cache (Platzhalter bis M1) | nichts Fachliches |
| `modules/*` (11) | `identity`, `config`, `service-geo`, `booking-dispatch`, `journey-federation`, `fare-payment`, `fleet-safety`, `staff-time`, `documents-finance`, `communication-support`, `analytics`; je `api` und `internal` | `platform`; andere Module nur über deren `api` |
| `adapters/*` (7) | `psp`, `partner-tomp`, `partner-osdm`, `timetable-gtfs`, `erp-export`, `ocr`, `notification` | `platform`, `api` von Modulen |
| `app` | Zusammenbau, Health, Rollenprüfung beim Start | alles |

Durchgesetzt von [ArchitectureRules](app/src/test/kotlin/de/taktovia/architecture/ArchitectureRules.kt) (ArchUnit) und [TableOwnership](app/src/test/kotlin/de/taktovia/architecture/TableOwnership.kt). Jede Regel hat einen roten Selbsttest gegen absichtlich fehlerhafte Fixtures in `archfixtures/`. Tabellenbesitz: [db/table_owners.d/](../db/table_owners.d/).

## Versionen

Zentral in `gradle/libs.versions.toml`; Gradle-Wrapper mit Prüfsumme; `gradle.lockfile` je Teilprojekt (Dependency-Locking für alle Konfigurationen). Plugins (Kotlin, Spring Boot) sind über den Katalog festgelegt, aber nicht gelockt. Details: [versionen.md](../docs/umsetzungsplan/versionen.md).
