# Datenbank

PostgreSQL mit PostGIS, Mandantentrennung per Row Level Security (Kap. 02 §4, Kap. 04 §4.6).

| Pfad | Inhalt |
|---|---|
| `db.py` | `bootstrap` (Rollen, Datenbank, PostGIS), `migrate`, `reset` |
| `migrations/V1__kernschema.sql` | Kernschema, Zeile für Zeile der Entwurfs-DDL aus `docs/pflichtenheft/schema/` (ohne `BEGIN`, `COMMIT`, `CREATE EXTENSION`) |
| `migrations/V2__haertung_periode.sql` | Befund B-01: geschlossene Buchungsperiode unveränderlich |
| `migrations/V10100__sitzungsregister.sql` | WP-M1-10: `session_register` (Sitzungssperre) und `account_access` (Kontosperre, Abmeldezeitmarke), RLS, Trigger |
| `migrations/V1280__fahrgast_registrierung.sql`, `V1290__fahrgastprofil.sql`, `V1300__einwilligung.sql` | Fahrgastkonto (WP-M1-28 bis -30): Kontaktkanal mit Verifikationsstatus und Verifikationsaufgabe, Präferenzen, Einwilligungsrevisionen (append-only); Rechte in `R__runtime_grants_m1_31_wp28/29/30.sql` (müssen hinter `R__runtime_grants_m1_31.sql` sortieren, die `passenger_private` neu vergibt) |
| `migrations/V1350__fahrerstamm_qualifikation.sql`, `V1351__fahrerfreigabe.sql` | WP-M1-35: minimaler Fahrerstamm, Fahrerfreigabe (`driver_release`, Eigentümer) und minimierte Projektion (`driver_release_projection`, unter dem Empfängermandanten; Policies `recipient_read` mit Zeit- und Widerrufsprüfung bei jedem Zugriff, `owner_manage`); Rechte in `R__runtime_grants_m1_35.sql` |
| `migrations/V3020__m3_tabellen.sql`, `V3040__kapazitaetskonfiguration.sql`, `V3050__fahrerstamm_vollausbau.sql` | M3 (WP-M3-02/-04/-05): 24 neue Tabellen (Prüfung, Schaden, Sperre, Freigabe, Alarm, Incident, Position, Planbestätigung, Boarding-Code, `booking_change`) mit RLS und Unveränderlichkeitstriggern, additive Spalten an `vehicle` (Kennung, Typ, Ausstattung, Aktivstatus, Stammdaten-Revision, Kapazitätskonfiguration) und `driver`/`qualification_revision` (Mitgliedschaft, Freigabe, fortlaufende Nummer); Rechte in `R__runtime_grants_m3_02.sql`. Die Fremdschlüssel `staff_shift.driver_ref` und `resource_release.inspection_ref` aktiviert `db/scripts/m3_fk_aktivierung.sql` erst nach Überführung der Fixtures (siehe unten) |
| `migrations/R__runtime_grants.sql` | wiederholbare Rechtevergabe für die Runtime-Rolle |
| `role_matrix.d/*.csv` | Rolle × Tabelle × Recht; jede Tabelle braucht einen Eintrag, Test vergleicht gegen die echten Grants |
| `migrations/V1190__policy_schema.sql`, `V1200__config_revision_lebenszyklus.sql` | Parameterkatalog `policy_schema` mit Schema-Versionen und Wertprüfung, Lebenszyklus und Unveränderlichkeit von `config_revision` (WP-M1-19, -20) |
| `dumpguard.py` | Importprüfung für Dumps (WP-M1-40): Produktionsdump nie in Nicht-Produktion, `db.py import-dump <datei>` |
| `dateninventar.d/*.csv`, `dateninventar_katalog.json`, `dateninventar.py` | Dateninventar mit Zweck, Rechtsgrundlage, Schutz- und Löschklasse je Spalte (WP-M1-32); `python db/dateninventar.py check` gleicht das Schema ab, `export --write` erzeugt den Export für UI-20 |
| `role_matrix_spalten.d/*.csv` | Spaltenrechte (Rolle × Tabelle × Spalte), wo Tabellenrechte nicht genügen (WP-M1-31: `passenger_private` ohne Ciphertext-Spalten für `taktovia_runtime`, nur `taktovia_fields` liest sie); geprüft von `test_08_feldschutz.py` |
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
## Modulzugang für sensible Felder (WP-M1-31)

`db.py bootstrap` legt neben Migrator und Runtime die Anmeldung `taktovia_fields` an (kein Superuser, kein `BYPASSRLS`, ohne Eigentum). Sie darf nur die Schlüssel- und Ciphertext-Spalten von `passenger_private` lesen bzw. ändern (`R__runtime_grants_m1_31.sql`); die Runtime-Rolle hat dort Spaltenrechte ohne Ciphertext. Passwort: `TAKTOVIA_DB_FIELDS_PASSWORD`; in Staging und Produktion ausdrücklich setzen (sonst bricht ein erstes `bootstrap` ab), in Entwicklung und Test wird es aus dem Runtime-Passwort abgeleitet. Bestehende Datenbanken: vor `migrate` einmal `bootstrap` ausführen.

## M3-Tabellen und nachgezogene Fremdschlüssel (WP-M3-02)

`V3020` legt die Tabellen aus [m3-ddl-entwurf.sql](../docs/umsetzungsplan/vertraege/m3-ddl-entwurf.sql) und die Zusatztabellen (`inspection_amendment`, `known_defect_acknowledgement`, `alert_acknowledgement`, `incident_link`, `driver_plan_ack`, `boarding_code`) an. Neue Spalten an Kerntabellen sind nullbar oder haben einen Default, damit M2-Fixtures, die Referenzregion und die Spike-Daten gültig bleiben (expand/contract). **Bewusst nicht in `V3020`:** die Fremdschlüssel `staff_shift.driver_ref -> driver` und `resource_release.inspection_ref -> inspection_run`. Die Fixtures legen Schichten mit freien Fahrerkennungen und Freigaben mit freien Prüfverweisen an; ein erzwungener Fremdschlüssel würde sie ungültig machen (WP-M3-02 Kriterium 6). `db/scripts/m3_fk_aktivierung.sql` legt beide als `NOT VALID` an (prüft neue Zeilen), danach wird der Altbestand bereinigt und `VALIDATE CONSTRAINT` ausgeführt; der Test `test_m3_02_tabellen.py::FremdschluesselAktivierung` weist die Wirkung nach. `driver_plan_ack.device_id` hat keinen Datenbank-Fremdschlüssel, weil `driver_device` (V3240) hinter dieser Migration sortiert.
