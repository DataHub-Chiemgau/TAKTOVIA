# LH-6.18-SEC-006 – Audit append-only und Sicherheitsereignisse

- **Verantwortlicher:** Rolle Plattform (Audit-Port, Modul `platform`/`identity`)
- **Arbeitspaket:** WP-M1-12
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Sicherheitsrelevante Änderungen, Logins und Dispositionseingriffe werden manipulationserschwerend protokolliert.

**Originalabnahme (unverändert):** Auditabfrage weist Akteur, Zeitpunkt und Aktivität aus.

## Vertrag

- **Tabellen / DDL:** `audit_event(tenant_id, id, actor_ref, action, object_ref, occurred_at, correlation_id, redacted_changes jsonb)`, PK `(tenant_id,id)` (V1); Trigger `audit_immutable` (`reject_mutation`, ERRCODE 23514) blockiert UPDATE/DELETE; Runtime hat nur SELECT, INSERT (`R__runtime_grants.sql`); RLS `tenant_isolation`. Neuer Index `(tenant_id, occurred_at)` für die Abfrage. Tabelle, Trigger und Rechte liefert WP-M1-46; dieser Vertrag nutzt sie und wiederholt den Nachweis im Abnahmelauf. Lifecycle (Wartung/Löschung) nur über kontrollierten Prozess (Kap. 04 §4.5).
- **API:** Auditabfrage (Filter Akteur, Objekt, Zeitraum, Aktion; Cursor-Liste), rollen- und zweckgebunden, nur lesend; Pfad ist in Kap. 05 §5.7 nicht gelistet und wird als Ergänzung in `api/` festgelegt (offen, OP-14). Fremdmandant 404, fehlende Berechtigung 403, fehlender Zweck 422/abgewiesen. Der Auditzugriff selbst wird auditiert.
- **Zustandsautomat:** keine.
- **JSON-Schema / Ereignisse:** JSON-Schema `redacted_changes` (`vertraege/schemas/audit-redacted-changes.v1.schema.json`), Format `actor_ref`/`object_ref`, Katalog auditpflichtiger Aktionen in M1 (Rollenänderung, Login erfolgreich/fehlgeschlagen, Logout, MFA-Fehlschlag, Sperre, manipulierte Mandantenangabe, veränderter Payload bei gleicher Ereignis-ID, Konfigurationspublikation, Lesezugriff auf sensible Felder; Dispositionseingriffe über denselben Port). Anmeldeereignisse des IdP (Keycloak-Ereignisschnittstelle) werden idempotent (Dedup per Ereignis-ID) in `audit_event` übernommen; Passwort/Code erscheinen nie. Sicherheitsereignisse zu Fremdzugriffen enthalten keine Inhalte des anderen Mandanten; „fehlend" vs. „verweigert" nur intern unterscheidbar.
- **Export:** Worker exportiert in eine getrennte Integritäts-/Berechtigungsdomäne mit Hashkette oder gleichwertiger Prüfung; eine nachträglich manipulierte Kopie wird erkannt.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-20 Basis – Auditliste mit Zweckangabe; Zustände „fehlender Zweck", „abgelaufener Zugriff" (Kap. 06).
- **Entscheidungen:** D-11 (Aufbewahrung, zulässiger Supportzugriff) – blockiert nur Produktivschaltung.
- **Offene Punkte:** OP-14 (kein Audit-Endpunkt in Kap. 05 §5.7; Exportziel und Integritätsverfahren offen; `audit_event.tenant_id` ist NOT NULL, fehlgeschlagene Logins vor Mandantenwahl haben keinen Mandanten; Umfang UI-20 Basis; Übernahme der IdP-Ereignisse nicht spezifiziert). Diese Punkte sind vor Codebeginn der Abfrage-API und des Exports zu entscheiden; Vorschlag: reservierter Systemmandant für mandantenlose Ereignisse. Sie blockieren den Bau der Port-Nutzung und der Abfrage nicht, wohl aber die Festlegung des Exportverfahrens. OP-01 (Dispositionseingriffe → M3: Vollabnahme mit WP-M3-40); der Port ist hier geliefert.

## Tests

- **Positiv:** Abfrage nach Rollenänderung, Login und Konfigurationspublikation weist je Akteur (`actor_ref`), Zeitpunkt (`occurred_at`) und Aktivität (`action`, `object_ref`) aus; erfolgreicher und fehlgeschlagener IdP-Login zusätzlich mit Anmeldekennung und Ergebnis; Filter nach Akteur, Objekt, Zeitraum, Aktion; erfolgreicher und fehlgeschlagener IdP-Login erscheinen als Eintrag; von WP-M1-04, -06, -10, -14 gemeldete Sicherheitsereignisse sind auffindbar; Export erkennt unverändertes Ergebnis als gültig.
- **Negativ:** UPDATE und DELETE auf `audit_event` scheitern (auch unter Runtime-Rolle); manipulierte Exportkopie wird erkannt; Auditabfrage ohne Berechtigung oder ohne Zweck wird abgewiesen; Fremdmandant liest keine Auditzeilen (A liest nichts von B); fehlgeschlagener Login ohne Mandantenkontext wird protokolliert; doppelt geliefertes IdP-Ereignis erzeugt keinen zweiten Eintrag; Passwort/Code nie im Eintrag; Fremdzugriffs-Ereignis enthält keine Inhalte des anderen Mandanten.
- **Konkurrierend:** Export läuft parallel zu 100 schreibenden Änderungen – Integritätsprüfung bleibt gültig und kein Eintrag fehlt; paralleler Zugriff zweier Mandanten auf die Auditabfrage ohne Vermischung.
- **Referenztests:** AT-49, E2E-091.
- Testfälle: AT-49; E2E-091; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/AuditQueryTest.kt::abfrage_weist_akteur_zeitpunkt_aktivitaet_aus; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/AuditQueryTest.kt::ohne_berechtigung_ohne_zweck_und_fremdmandant_abgewiesen; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/AuditQueryTest.kt::zwei_mandanten_parallel_ohne_vermischung; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/AuditExportTest.kt::manipulierte_kopie_wird_erkannt; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/IdpAuditImportTest.kt::doppeltes_idp_ereignis_erzeugt_einen_eintrag_ohne_passwort; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/AuditExportTest.kt::export_parallel_zu_100_schreibvorgaengen_bleibt_integer; geplant:db/tests/test_09_audit.py::test_update_delete_auf_audit_event_scheitern

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
