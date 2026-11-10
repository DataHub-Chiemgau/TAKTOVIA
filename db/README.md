# Datenbank

PostgreSQL mit PostGIS, Mandantentrennung per Row Level Security (Kap. 02 §4, Kap. 04 §4.6).

| Pfad | Inhalt |
|---|---|
| `db.py` | `bootstrap` (Rollen, Datenbank, PostGIS), `migrate`, `reset` |
| `migrations/V1__kernschema.sql` | Kernschema, Zeile für Zeile der Entwurfs-DDL aus `docs/pflichtenheft/schema/` (ohne `BEGIN`, `COMMIT`, `CREATE EXTENSION`) |
| `migrations/V2__haertung_periode.sql` | Befund B-01: geschlossene Buchungsperiode unveränderlich |
| `migrations/R__runtime_grants.sql` | wiederholbare Rechtevergabe für die Runtime-Rolle |
| `role_matrix.csv` | Rolle × Tabelle × Recht; jede Tabelle braucht einen Eintrag, Test vergleicht gegen die echten Grants |
| `tests/` | Laufzeittests unter der echten Runtime-Rolle (49 Fälle: Rollen, Migration, RLS, Trigger, Härtung) |

## Lokal

```bash
python -m venv .venv && .venv/bin/pip install "psycopg[binary]==3.2.*"
export TAKTOVIA_DB_NAME=taktovia_test TAKTOVIA_DB_ADMIN_USER=postgres TAKTOVIA_DB_ADMIN_PASSWORD=... \
       TAKTOVIA_DB_MIGRATOR_PASSWORD=... TAKTOVIA_DB_RUNTIME_PASSWORD=...
.venv/bin/python db/db.py reset && .venv/bin/python db/db.py migrate
.venv/bin/python -m unittest discover -s db/tests -p 'test_*.py'
```

## Konventionen (WP-M0-16)

- Eine fachliche Änderung je Migration, `V<n>__beschreibung.sql`, aufsteigend; ausgelieferte Migrationen werden nie geändert (Prüfsummenfehler). Fachliche Korrekturen sind Vorwärtsmigrationen, kein Löschen bestätigter Daten.
- **Führend ist `db/migrations/`.** `docs/pflichtenheft/schema/kernschema.sql` bleibt der Entwurf, auf den V1 sich bezieht; Abweichungen stehen in V2+ und im [Befundbericht](../docs/umsetzungsplan/befunde/kernschema-befunde.md). Ein Test stellt sicher, dass V1 und Entwurf nicht auseinanderlaufen.
- `expand/contract` für Änderungen an bestehenden Spalten (Kap. 04 §4.7).
- Tenantkontext nur transaktionslokal: `SELECT set_config('app.tenant_id', $1, true)`; sessionweite Kontexte sind verboten.
- Die Runtime-Rolle ist weder Superuser noch `BYPASSRLS` noch Eigentümer. Die Migrator-Zugangsdaten gehören nicht in die Laufzeitkonfiguration der Anwendung.

## Abweichungen vom Plan (Stand M0)

- `db.py` ersetzt bis zum Gradle-Gerüst (WP-M0-02) Flyway; Dateinamen sind Flyway-kompatibel, die Historientabelle liegt in `taktovia_meta.schema_history`.
- Getestet wurde mit PostgreSQL 16.15 und PostGIS 3.4 (Entwicklungsumgebung ohne Docker); die CI nutzt PostgreSQL 17 mit PostGIS 3.5. Das 17er-Ergebnis steht bis zum ersten CI-Lauf aus.
