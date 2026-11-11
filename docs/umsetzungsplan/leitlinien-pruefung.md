# Leitlinien und prüfende Regeln

Zu Umsetzungsplan §2. Jede Leitlinie nennt die Regel, die sie heute in CI absichert, und den Meilenstein, ab dem der Rest automatisiert geprüft wird. **Aktiv** heißt: die Regel läuft in CI und hat einen roten Selbsttest.

| # | Leitlinie | Heute aktiv | Ab Meilenstein |
|---|---|---|---|
| 1 | Modularer Kern, Modulgrenzen | `ArchitectureRules.internalIsolation`, `.layering` (Zyklenfreiheit, Richtungen); `TableOwnership` (nur eigene Tabellen schreiben, jede Tabelle hat einen Besitzer). Ein Deployable: Build-Aufbau | Getrennte DB-Zugänge je Modul: M1 |
| 2 | Mandantenisolation in jeder Schicht | `db/tests/test_03_rls.py`, `test_01_rollen.py` (RLS, Rollen, Tenantkontext transaktionslokal, Poolwiederverwendung); `RuntimeRoleGuard` verweigert Start unter Migrator/Superuser | API-, Cache-, Storage-, Such- und Eventebene: M1 (PH-AT-001) |
| 3 | Einmalige Geschäftswirkung, externe Aufrufe nie in offener Transaktion | DB-Constraints (Idempotenz-, Outbox-, Inbox-Schlüssel) in `db/tests/test_04_trigger.py`; Näherungsregel `noAdapterCallInTransaction` (kein Adapteraufruf aus `@Transactional`) | Outbox/Inbox-Verhalten und vollständige Transaktionsregel: M1; Saga/PSP-Fehlerfälle: M2, M6 |
| 4 | Kapazität nur in PostgreSQL | `ArchitectureRules.redisOnlyInCachePackage` (Redis nur in `platform.cache`) | Konkurrenztests Hold/Confirm: Spike (a) WP-M0-26, M2 (PH-AT-004) |
| 5 | Geld ohne Gleitkomma | `ArchitectureRules.noFloatingPointMoney` (Felder `*Minor`, Paket `money`); `db/tests/test_02_migration.py` (alle `*_minor`-Spalten `bigint`, kein float/real/double im Schema) | Rundung, Largest Remainder: M4 (PH-AT-013) |
| 6 | Unbestimmtes bleibt unbestimmt (`UNKNOWN`/`RECONCILING`) | – | Zustandsautomaten: M2 (Buchung), M6 (Saga, PH-AT-007) |
| 7 | Vertrag vor Code | `check_wp.py` (Zuordnung, Abhängigkeiten, Katalogabdeckung) und `validate_artifacts.py` in CI | Nachverfolgung WP→Test→Evidenz: WP-M0-32 |

Leitlinie 6 hat noch keine Regel, weil es vor M2 keine Zustandsautomaten gibt.
