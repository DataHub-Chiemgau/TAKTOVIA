# Datenbank

PostgreSQL mit PostGIS, Mandantentrennung per Row Level Security (Kap. 02 §4, Kap. 04 §4.6).

| Pfad | Inhalt |
|---|---|
| `db.py` | `bootstrap` (Rollen, Datenbank, PostGIS), `migrate`, `reset` |
| `migrations/V1__kernschema.sql` | Kernschema, Zeile für Zeile der Entwurfs-DDL aus `docs/pflichtenheft/schema/` (ohne `BEGIN`, `COMMIT`, `CREATE EXTENSION`) |
| `migrations/V2__haertung_periode.sql` | Befund B-01: geschlossene Buchungsperiode unveränderlich |
| `migrations/V10100__sitzungsregister.sql` | WP-M1-10: `session_register` (Sitzungssperre) und `account_access` (Kontosperre, Abmeldezeitmarke), RLS, Trigger |
| `migrations/R__runtime_grants.sql` | wiederholbare Rechtevergabe für die Runtime-Rolle |
| `role_matrix.d/*.csv` | Rolle × Tabelle × Recht; jede Tabelle braucht einen Eintrag, Test vergleicht gegen die echten Grants |
| `dumpguard.py` | Importprüfung für Dumps (WP-M1-40): Produktionsdump nie in Nicht-Produktion, `db.py import-dump <datei>` |
| `dateninventar.d/*.csv`, `dateninventar_katalog.json`, `dateninventar.py` | Dateninventar mit Zweck, Rechtsgrundlage, Schutz- und Löschklasse je Spalte (WP-M1-32); `python db/dateninventar.py check` gleicht das Schema ab, `export --write` erzeugt den Export für UI-20 |
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

## Parallele Arbeit ohne Merge-Konflikte

- **Migrationsnummern:** Arbeitspaket `WP-M<m>-<nn>` verwendet Versionen ab `V<m>0<nn>0` (z. B. WP-M1-02 → `V1020`, `V1021`, …; WP-M2-05 → `V2050`). V1–V3 gehören dem Kernschema.
- **Rechte:** je Arbeitspaket eine eigene wiederholbare Datei statt Änderungen an `R__runtime_grants.sql`. **Achtung Reihenfolge:** `db.py` führt `R__*.sql` alphabetisch aus und `R__runtime_grants.sql` beginnt mit `REVOKE ALL`; der Dateiname muss dahinter sortieren (`R__runtime_grants_<wp>.sql`, nicht `R__grants_<wp>.sql`), sonst gehen die Rechte auf frischen Datenbanken verloren.
- **Rollenmatrix und Tabellenbesitz:** je Arbeitspaket eine eigene Datei `db/role_matrix.d/<wp>.csv` und `db/table_owners.d/<wp>.csv`; Tabellennamen dürfen nicht doppelt vorkommen.

## Umgebungskennung und Dump-Import (WP-M1-40)

`db.py migrate` setzt einmalig die unveränderliche Umgebungskennung (`taktovia_meta.environment_marker`) aus `TAKTOVIA_ENVIRONMENT` (Vorgabe `development`; Produktion: `TAKTOVIA_ENVIRONMENT=production`). Das Backend startet nur, wenn seine `taktovia.environment` zur Kennung passt. Dumps werden nur über `db.py import-dump` eingespielt; Produktionsdumps in Nicht-Produktion werden abgewiesen. Details: [umgebungstrennung.md](../docs/umsetzungsplan/vertraege/umgebungstrennung.md).
## Dateninventar (WP-M1-32)

- **Jede neue Tabelle und Spalte braucht einen Inventareintrag im selben Pull Request**, sonst scheitert `dateninventar.py check` (CI) und `test_07_dateninventar.py`. Eine Datei je Arbeitspaket: `db/dateninventar.d/<wp>.csv` (`tabelle;spalte;personenbezogen;zweck;rechtsgrundlage;schutzklasse;loeschklasse;bemerkung`) und `<wp>.tabellen.csv` (`tabelle;aggregat;loeschklasse;bemerkung`).
- Personenbezogene Felder (`ja`) brauchen Rechtsgrundlage, Schutzklasse und Löschklasse; Zweck und Werte müssen im Katalog `dateninventar_katalog.json` stehen. Der Katalog ist **vorläufig und nicht freigegeben** (OP-24, D-11).
- Nach Änderungen am Inventar den UI-Export erneuern: `python db/dateninventar.py export --write` (Test `test_ui_export_entspricht_dem_inventar` hält ihn synchron).
- Felder ohne nachgewiesenen Zweck werden nicht angelegt (z. B. keine feinere Herkunft als `travel_attempt.coarse_origin`).

## Erweiterung `btree_gist` (WP-M1-34)

`V1340__fahrzeugrollen.sql` legt `btree_gist` an (`CREATE EXTENSION IF NOT EXISTS`, vertrauenswürdige Erweiterung, der Migrator ist Datenbankeigentümer). Sie wird für den Exclusion Constraint `vehicle_assignment_no_overlap` (Überlappungsverbot je Fahrzeug und Rolle) gebraucht; das PostgreSQL-Abbild muss die contrib-Module enthalten (das PostGIS-Abbild und die lokale Installation tun das).
