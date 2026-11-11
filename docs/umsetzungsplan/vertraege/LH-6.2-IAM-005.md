# LH-6.2-IAM-005 – Dienst- und Objektberechtigung

- **Verantwortlicher:** Rolle Plattform (Modul `identity`, Port in `platform`)
- **Arbeitspaket:** WP-M1-08
- **Status:** Bereit
- **Phase / Priorität:** P1 / MUSS

## Originaltext (unverändert)

> Berechtigungen sind zusätzlich auf Mandant, Dienst und konkretes Objekt begrenzt.

**Originalabnahme:** Ein Disponent kann nicht auf fremde regionale Buchungen zugreifen.

## Vertrag

- **Tabellen / DDL:** `membership.service_scope uuid[]`, `service.id`; Index auf `(tenant_id, …)` für dienstgefilterte Listen. Mandantenisolation bleibt RLS (V1), Dienstscope ist eine zweite Schicht in der Anwendung. Die Semantik von `service_scope = NULL` ist im Pflichtenheft nicht festgelegt (OP-04) und muss vor Codebeginn entschieden werden; Vorschlag: NULL = kein Dienstzugriff (fail-closed).
- **API:** einheitlich 404 für nicht sichtbare Objekte, auch innerhalb desselben Mandanten bei fremdem Dienst; Listenfilter dürfen den Scope nur einschränken (Kap. 05 §5.1).
- **Port:** `ObjectAuthorizer.canRead/canWrite(ctx, ScopedObject, purpose)` in `backend/platform` (Paket `platform.authz`; Umsetzung `ServiceScopeAuthorizer`, Markierung `@ServiceScoped`; Entscheidung OP-04 im Bau: `service_scope = NULL` und leere Liste = kein Dienstzugriff, Objekte ohne Dienstbezug nur Mandantengrenze, verwaltende Rollen mit `services:write` sehen die Dienstköpfe mandantenweit; ein Cursor einer anderen Sicht wird mit 400 `INVALID_CURSOR` und ohne Daten abgewiesen); Pflichtaufruf für jedes Repository mit Dienstbezug per Architekturtest.
- **Zustandsautomat / Schema / Ereignisse:** keine.
- **Parameter und Kennzahlen:** keine.
- **Entscheidungen:** keine. **Offene Punkte:** OP-04 (Scope-Semantik), Region ↔ Dienst (WP-M1-09).

## Tests

- **Positiv:** Disponent bearbeitet Objekt der eigenen Region.
- **Negativ:** fremde Region per UUID (Detail- und Änderungszugriff), per Listenfilter, per Sortierung/Cursor einer anderen Sicht; Filter auf fremden Dienst → leere Liste; fremder Mandant bleibt 404 unabhängig vom Scope; `service_scope = NULL` verweigert (Vorschlag, OP-04); Repository mit Dienstbezug ohne Port-Aufruf lässt den Architekturtest scheitern.
- **Konkurrierend:** Scope-Entzug parallel zu einer Änderung → Änderung nach Commit des Entzugs abgewiesen.
- **Referenztests:** AT-18, Baustein für PH-AT-001. Nachweis an echter Buchung mit WP-M2-23.
- Testfälle: AT-18; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/authz/ObjectAuthorizationTest.kt::disponent_fremde_region_ergibt_404; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/authz/ObjectAuthorizationTest.kt::disponent_eigene_region_bearbeitet; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/authz/ObjectAuthorizationTest.kt::fremder_dienst_per_listenfilter_leere_liste; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/authz/ObjectAuthorizationTest.kt::sortierung_cursor_fremde_sicht_wird_abgewiesen_ohne_daten; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/authz/ObjectAuthorizationTest.kt::fremder_mandant_404_unabhaengig_vom_scope; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/authz/ObjectAuthorizationTest.kt::service_scope_null_verweigert; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/authz/ObjectAuthorizationTest.kt::scope_entzug_parallel_zu_aenderung; geplant:backend/app/src/test/kotlin/de/taktovia/architecture/ObjectAuthorizationArchitectureTest.kt::repository_mit_dienstbezug_ruft_port_auf

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung).
