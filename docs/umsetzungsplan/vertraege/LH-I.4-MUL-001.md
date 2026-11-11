# LH-I.4-MUL-001 – Tenantkontext, RLS-Laufzeitbasis und Isolations-Testharness

- **Verantwortlicher:** Rolle Plattform (Modul `platform`, Datenbank)
- **Arbeitspaket:** WP-M1-01
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Jede Entität verfügt über eindeutige Tenant-ID und rechtlich getrennten Sichtbarkeitskontext.

**Originalabnahme (unverändert):** Ein automatisierter Mandantentest kann weder fremde Buchungen noch fremde Rechnungen lesen.

## Vertrag

- **Tabellen / DDL:** `taktovia`-Schema aus `kernschema.sql` (V1) und `db/migrations/V2__haertung_periode.sql` und `db/migrations/V3__tenant_id_unveraenderlich.sql` (Trigger `tenant_id_immutable`, Funktion `reject_tenant_change()`): jede Entität mit `tenant_id uuid NOT NULL` als erstem Bestandteil von PK, UNIQUE und Fremdschlüsseln (Konvention: PK `(tenant_id,id)`, zusammengesetzte FKs); Policy `tenant_isolation` (USING und WITH CHECK mit `current_tenant()`) und `FORCE ROW LEVEL SECURITY` für alle Tabellen mit `tenant_id`, Policy `own_tenant` auf `tenant` (Ausnahme der Konvention: `tenant` und `currency` tragen keine `tenant_id`, `tenant.id` ist die Mandantenkennung; die übrigen 36 Tabellen tragen `tenant_id`); Trigger, der eine Änderung von `tenant_id` verweigert; Migrationsprüfung „jede neue Tabelle hat `tenant_id`, PK `(tenant_id,id)`, zusammengesetzte FKs, RLS“; Views nur mit invokergerechten Rechten, keine Definerfunktionen ohne festen `search_path`. Invarianten Kap. 04 §4.5: „Keine lokale Cross-Tenant-Referenz“, „Kein Mandantenkontext → keine Zeile“. Runtime-Rolle NOSUPERUSER, NOBYPASSRLS, kein Tabelleneigentümer, kein TRUNCATE/DDL. Der Datenbankteil ist bereits umgesetzt (vorhandene Tests unten).
- **Laufzeit (`backend/platform`):** `TenantContext`; Transaktionsinterceptor mit parametrisiertem `set_config('app.tenant_id', tenant, true)` innerhalb `BEGIN`; sessionweite GUCs verboten; Worker-Variante mit explizitem Tenantkontext, Batchjobs iterieren begrenzt je Mandant und hinterlassen nach Abbruch keinen Kontext. Architekturtest: ein Modul schreibt nur eigene Tabellen.
- **Testharness (`tests/acceptance`):** Fixture Mandant A/B (je Fahrgast, Fahrzeug, Beleg, identisch benannte Kostenstelle gemäß PH-AT-001) und generischer Tabellen-Scan unter der Runtime-Rolle über alle Tabellen des Schemas `taktovia`; schlägt fehl, wenn eine Tabelle mit `tenant_id` keine erzwungene RLS-Policy hat. Lesen von `booking`, `expense_document`, `journal`, `settlement_line` des Mandanten B mit Kontext A ergibt keine Zeile (das Kernschema führt keine Tabelle `invoice`; „Rechnungen“ der Originalabnahme werden gemäß WP-M1-01 durch `expense_document`, `journal` und `settlement_line` abgedeckt).
- **API / Zustandsautomat / Schema / Ereignisse / Parameter / Oberfläche:** keine.
- **Entscheidungen:** D-02 (RLS ist Planungsdefault; eine spätere Entscheidung für Datenbank je Mandant wäre Umbau) – blockiert den Bau nicht.
- **Offene Punkte:** OP-08 (Plattformmandant für globale Katalogobjekte unter RLS); OP-02 (in M1 nur Datenbankteil von PH-AT-001, E2E-091 und AT-49 prüfbar; Umfang mit Auftraggeber festlegen, WP-M1-45). Der Mandantentest an `booking` setzt voraus, dass die Tabelle im Schema existiert (V1); Fachdatenpfade folgen in M2.

## Tests

- **Positiv:** Kontext A liest und schreibt Zeilen von A (`test_a_sieht_nur_a`); alle Entitäten haben `tenant_id` NOT NULL als PK-Anfang.
- **Negativ:** A liest/ändert/löscht B über bekannte UUID ohne Wirkung; INSERT mit fremdem `tenant_id` (WITH CHECK) und fremdem FK scheitert; `tenant_id`-Update wird auch für Administrator verweigert; leerer oder ungültiger Kontext liefert keine Zeile (fail-closed); Kontext nach Poolwiederverwendung und Rollback leer; Zugriff über View/Definerfunktion liefert nichts (keine solchen Objekte); TRUNCATE/DDL unter der Runtime-Rolle scheitert; Tabelle mit `tenant_id` ohne erzwungene Policy lässt den Scan scheitern.
- **Konkurrierend:** zwei parallele Transaktionen mit Kontext A und B auf demselben Pool, 1.000 Wechsel, keine Kontextvermischung; Batchjob über mehrere Mandanten setzt den Kontext je Mandant neu.
- **Referenztests:** AT-49, E2E-091; ermöglicht PH-AT-001 (Datenbankteil).
- Testfälle: AT-49; E2E-091; db/tests/test_06_konventionen.py::test_a_sieht_nur_a; db/tests/test_06_konventionen.py::test_pk_beginnt_mit_tenant_id_und_tenant_id_ist_not_null; db/tests/test_03_rls.py::test_alle_36_tabellen_ohne_kontext_leer; db/tests/test_03_rls.py::test_zwei_gleichzeitige_verbindungen_beeinflussen_sich_nicht; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/TenantContextTest.kt::tausend_kontextwechsel_ohne_vermischung; db/tests/test_06_konventionen.py::test_a_aendert_und_loescht_b_nicht; db/tests/test_06_konventionen.py::test_a_schreibt_nicht_fuer_b; db/tests/test_06_konventionen.py::test_tenant_id_aenderung_wird_auch_fuer_admin_verweigert; db/tests/test_06_konventionen.py::test_keine_view_und_keine_definerfunktion; db/tests/test_03_rls.py::test_ungueltiger_kontext_ist_fail_closed; db/tests/test_03_rls.py::test_kontext_ist_transaktionslokal_nach_poolwiederverwendung; db/tests/test_03_rls.py::test_tenantfremde_referenz_scheitert_per_fk; db/tests/test_01_rollen.py::test_ddl_truncate_setrole_scheitern; geplant:tests/acceptance/test_mandantentest.py::test_kontext_a_liest_nichts_aus_booking_expense_document_journal_settlement_line_von_b; geplant:tests/acceptance/test_mandantentest.py::test_tabellenscan_schlaegt_fehl_bei_tabelle_ohne_erzwungene_policy; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/TenantContextTest.kt::batchjob_setzt_kontext_je_mandant_und_hinterlaesst_nach_abbruch_keinen

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
