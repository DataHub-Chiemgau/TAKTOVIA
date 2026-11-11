# LH-I.7-CFG-003 – Änderungsprotokoll und Rekonstruktion von Konfigurationen

- **Verantwortlicher:** Rolle Plattform (Modul `config`)
- **Arbeitspaket:** WP-M1-20
- **Status:** Bereit
- **Phase / Priorität:** P1 / MUSS

## Originaltext (unverändert)

> Konfigurationsänderungen werden mit Benutzer, Zeitpunkt, Altwert, Neuwert, Grund und Gültigkeitsbeginn protokolliert.

**Originalabnahme:** Eine frühere Einstellung ist für einen historischen Dispositionsfall rekonstruierbar.

## Vertrag

- **Tabellen / DDL:** `config_revision` (V1: `scope_kind`, `scope_id`, `revision`, `schema_version`, `values_json`, `effective_from`, `effective_to`, `created_by`, `approved_by`, `published_at`) wie bei SYS-002; Migration ergänzt `created_at` (Zeitpunkt der Änderung), Statusspalte, Änderungsgrund (Grund ist Pflichtfeld bei Anlage), CHECK der `scope_kind`-Werte (WP-M1-20, OP-18; Spaltennamen im Bau festzulegen); Invarianten `UNIQUE(tenant_id,scope_kind,scope_id,revision)` und `effective_to > effective_from` (V1); Diff Altwert/Neuwert gegen die Vorgängerrevision wird berechnet, nicht gespeichert. Publizierte Zeilen sind per Trigger unveränderlich (analog `reject_mutation()`), auch für die Runtime-Rolle per Recht.
- **API:** `GET /v1/config/revisions` (Liste), `GET /v1/config/revisions/{id}`; Parameter `as_of` liefert die zum Zeitpunkt wirksame Revision samt Revisions-ID. Jede Ansicht eines Konfigurationswerts nennt die verwendete Revision.
- **Zustandsautomat:** siehe SYS-002. **Ereignisse:** `config.revision_created`; Audit über WP-M1-46.
- **Parameter und Kennzahlen:** keine neuen. **Oberfläche:** UI-17 Basis mit Diff Alt/Neu und Grund.
- **Entscheidungen:** keine. **Offene Punkte:** OP-18 (Spalte Änderungsgrund, Statusspalte, `created_at`, zulässige `scope_kind`-Werte); Nachweis an echtem Dispositionsfall mit gebundener `config_revision_id` erst mit M2 (WP-M2-04, -07, -21, -23).

## Tests

- **Positiv:** drei Revisionen, As-of-Abfrage auf jede liefert den damals wirksamen Stand.
- **Negativ:** Änderung ohne Grund (422); fehlendes `effective_from` (422); `effective_to` vor `effective_from`; Rolle ohne Recht (403); veralteter ETag (412); Update oder Löschen einer publizierten Revision per API und per SQL unter der Runtime-Rolle; fremder Scope.
- **Konkurrierend:** zwei parallele Entwürfe mit derselben Revisionsnummer.
- **Referenztests:** E2E-096, E2E-098, E2E-103 (KPI-Definition geändert), PH-AT-018.
- Testfälle: E2E-096; E2E-098; E2E-103; PH-AT-018; geplant:tests/acceptance/test_config_revision_m1.py::test_aenderung_ohne_grund_oder_effective_from_abgewiesen; geplant:backend/config/src/test/kotlin/de/taktovia/config/ConfigRevisionTest.kt::diff_altwert_neuwert_zeigt_benutzer_zeitpunkt_grund_gueltigkeitsbeginn; geplant:backend/config/src/test/kotlin/de/taktovia/config/ConfigRevisionTest.kt::parallele_entwuerfe_gleiche_revisionsnummer_genau_einer; geplant:db/tests/test_config_revision.py::test_publizierte_revision_unveraenderlich_unter_runtime_rolle; geplant:backend/config/src/test/kotlin/de/taktovia/config/ConfigRevisionTest.kt::as_of_liefert_damals_wirksame_revision

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
