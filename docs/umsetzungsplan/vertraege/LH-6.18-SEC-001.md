# LH-6.18-SEC-001 – Tenantkontext, RLS-Laufzeitbasis und Isolations-Testharness

- **Verantwortlicher:** Rolle Plattform (Module `platform`, Datenbank)
- **Arbeitspaket:** WP-M1-01
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Mandanten- und rollenbasierte Isolation wird serverseitig durchgesetzt und automatisiert getestet.

**Originalabnahme (unverändert):** Rechte-Tests liefern keine Cross-Tenant-Leaks.

## Vertrag

- **Tabellen / DDL:** Flyway-Migration auf Basis `kernschema.sql` (V1; Härtung V2/V3 vorhanden): Policy `tenant_isolation` (USING und WITH CHECK) und `FORCE ROW LEVEL SECURITY` für alle Tabellen mit `tenant_id`, Policy `own_tenant` auf `tenant`; Funktion `current_tenant()`; Trigger verweigert Änderung von `tenant_id` (V3); Migrationsprüfung "jede neue Tabelle hat `tenant_id`, PK `(tenant_id,id)`, zusammengesetzte FKs, RLS". Runtime-Rolle NOSUPERUSER, NOBYPASSRLS, kein Tabelleneigentümer, weder TRUNCATE noch DDL (`R__runtime_grants.sql`). Views mit invokergerechten Rechten; Definerfunktionen vermeiden oder mit festem `search_path`, minimalen Rechten und eigenem Test. Invarianten §4.5: "Keine lokale Cross-Tenant-Referenz", "Kein Mandantenkontext -> keine Zeile". Partitionierung ist keine Sicherheitsgrenze. Stand: V1 bis V3 (36 Tabellen mit `tenant_isolation`, `FORCE ROW LEVEL SECURITY`, Trigger `tenant_id_immutable`) und `R__runtime_grants.sql` sind vorhanden und durch bestehende Tests in `db/tests/` belegt; dieser Vertrag verlangt zusätzlich den generischen Scan als CI-Pflichtschritt, den Zwei-Mandanten-Harness und die Laufzeitbausteine. Aktuell enthält `taktovia` weder Views noch `SECURITY DEFINER`-Funktionen (Test vorhanden); jede künftige View/Definerfunktion braucht Test und Begründung. Jede neue Tabelle braucht Eintrag in `db/table_owners.csv` und `db/role_matrix.csv`.
- **Backend:** `backend/platform` – `TenantContext`, Transaktionsinterceptor mit parametrisiertem `set_config('app.tenant_id', tenant, true)` innerhalb `BEGIN`; sessionweite GUCs verboten; Worker-Variante mit explizitem Tenantkontext, Batchjobs iterieren je Mandant und hinterlassen nach Abbruch keinen Kontext. Architekturtest: Modul schreibt nur eigene Tabellen.
- **Testharness:** `tests/acceptance` (Katalog `tests/acceptance/catalog/scenarios.csv`, dort PH-AT-001 und E2E-091 als `NOT_IMPLEMENTED` geführt) – Fixture Mandant A/B (je Fahrgast, Fahrzeug, Beleg, identisch benannte Kostenstelle gemäß PH-AT-001), generischer Tabellen-Scan über Schema `taktovia` unter der Runtime-Rolle; CI-Pflichtschritt, schlägt fehl bei Tabelle mit `tenant_id` ohne erzwungene Policy.
- **API:** keine (API-Autorisierung: WP-M1-06 bis WP-M1-08).
- **Zustandsautomat / Schema / Ereignisse:** keine.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** keine.
- **Entscheidungen:** D-02 (RLS ist Planungsdefault; blockiert den Bau nicht; spätere Entscheidung für Datenbank je Mandant wäre Umbau).
- **Offene Punkte:** OP-08 (Plattformmandant für globale Katalogobjekte, Detailvertrag vorab); OP-02 (Gate-Tests PH-AT-001/E2E-091/AT-49 in M1 nur teilweise ausführbar, Objekte kommen mit M2/M3).

## Tests

- **Positiv:** Kontext A liest und schreibt eigene Zeilen; Rechte-Testlauf über alle Rollen und beide Mandanten meldet null Cross-Tenant-Leaks.
- **Negativ:** die Rechte-Tests laufen unter der Runtime-Rolle über alle Rollen der `db/role_matrix.csv`; A liest/ändert/löscht B über bekannte UUID (`booking`, `expense_document`, `journal`, `settlement_line`); fremder FK und fremde `tenant_id` werden durch FK bzw. WITH CHECK abgewiesen; `tenant_id`-Update verweigert; leerer Kontext liefert keine Zeile, auch nach Pool-Wiederverwendung und nach Fehler/Rollback; Lesezugriff über View und Definerfunktion liefert keine Fremdzeile; TRUNCATE/DDL unter Runtime-Rolle scheitert; Tabelle mit `tenant_id` ohne erzwungene Policy lässt den Scan fehlschlagen (roter Selbsttest mit absichtlich ungeschützter Testtabelle); neue Tabelle ohne `tenant_id`/PK `(tenant_id,id)`/zusammengesetzten FK/RLS lässt die Migrationsprüfung fehlschlagen; sessionweiter Tenant-GUC im Code wird vom Regeltest gefunden; Runtime-Rolle ist NOSUPERUSER, NOBYPASSRLS, kein Eigentümer, ohne TRUNCATE/DDL/`SET ROLE`; ein Modul schreibt keine fremde Tabelle (Architekturtest).
- **Konkurrierend:** zwei parallele Transaktionen mit Kontext A und B auf demselben Pool, 1.000 Wechsel, keine Kontextvermischung; Batchjob über mehrere Mandanten setzt Kontext je Mandant neu und hinterlässt nach Abbruch keinen Kontext; Rollback und Fehler in einer Transaktion hinterlassen keinen Kontext auf der wiederverwendeten Verbindung.
- **Referenztests:** ermöglicht PH-AT-001 (Datenbankteil), E2E-091, AT-49.
- Testfälle: PH-AT-001; E2E-091; AT-49; db/tests/test_03_rls.py::test_ohne_kontext_keine_zeile_und_kein_schreiben; db/tests/test_03_rls.py::test_kontext_ist_transaktionslokal_nach_poolwiederverwendung; db/tests/test_03_rls.py::test_alle_36_tabellen_ohne_kontext_leer; db/tests/test_06_konventionen.py::test_policies_erzwingen_using_und_with_check_mit_current_tenant; db/tests/test_01_rollen.py::test_ddl_truncate_setrole_scheitern; geplant:db/tests/test_isolation_alle_tabellen.py::test_keine_cross_tenant_leaks_alle_tabellen; geplant:db/tests/test_isolation_alle_tabellen.py::test_pool_wiederverwendung_ohne_kontextrest; geplant:db/tests/test_isolation_alle_tabellen.py::test_parallele_kontexte_a_b_1000_wechsel; geplant:tests/acceptance/isolation/test_zwei_mandanten_harness.py::test_scan_schlaegt_fehl_bei_tabelle_ohne_erzwungene_policy; geplant:db/tests/test_isolation_alle_tabellen.py::test_neue_tabelle_ohne_rls_faellt_migrationspruefung_durch

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Sicherheit und Datentrennung).
