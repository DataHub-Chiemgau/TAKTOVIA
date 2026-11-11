# Mitarbeit

## Leitlinien (Umsetzungsplan §2)

1. **Modularer Kern**: ein Deployable, Module sprechen nur über `api`-Pakete, schreiben nur eigene Tabellen.
2. **Mandantenisolation in jeder Schicht**: Primärschlüssel `(tenant_id,id)`, RLS, Tenantkontext nur transaktionslokal, Runtime-Rolle ohne Superuser/BYPASSRLS/Eigentum.
3. **Einmalige Geschäftswirkung**: Aggregat, Revision und Outbox in einer Transaktion; PSP- und Partneraufrufe nie innerhalb offener SQL-Transaktionen.
4. **Kapazität nur in PostgreSQL**; Redis nur Cache und Rate-Limit.
5. **Geld ohne Gleitkomma**: `bigint` in kleinster Einheit, Korrektur nur per Gegenereignis.
6. **Unbestimmtes bleibt unbestimmt**: `UNKNOWN`/`RECONCILING` wird nie zu Erfolg oder Fehler verkürzt.
7. **Vertrag vor Code**: Detailvertrag und mindestens ein positiver Test je MUSS-Anforderung vor der Umsetzung.

Welche Regel jede Leitlinie in CI absichert: [leitlinien-pruefung.md](docs/umsetzungsplan/leitlinien-pruefung.md).

## Arbeitsweise

- Arbeitspakete: [docs/umsetzungsplan/wp/](docs/umsetzungsplan/wp/README.md). Ein WP gilt als umgesetzt, wenn die dort genannten Tests grün sind; Stand in [fortschritt.md](docs/umsetzungsplan/fortschritt.md).
- Änderungen nur per Pull Request mit Review durch die Zuständigen aus `.github/CODEOWNERS`. **Einzurichten in den Repository-Einstellungen** (nicht per Datei erzwingbar): geschützter Standardzweig, mindestens eine Freigabe, Code-Owner-Review, erforderliche Statusprüfungen `Vertragsprüfung`, `Datenbankintegration`, `Backend` und `Repository`.
- Migrationen: nie ausgelieferte Dateien ändern, siehe [db/README.md](db/README.md).
- Keine Secrets im Repository. Zugangsdaten kommen aus der Umgebung.
- Abhängigkeiten nur mit festen Versionen im Versionskatalog; danach Lockfiles erneuern (`backend/README.md`).
