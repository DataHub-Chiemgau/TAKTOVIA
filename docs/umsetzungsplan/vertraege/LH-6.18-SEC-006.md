# LH-6.18-SEC-006 – Audit append-only und Sicherheitsereignisse

- **Verantwortlicher:** Rolle Plattform (Audit-Port, Modul `platform`/`identity`)
- **Arbeitspaket:** WP-M1-12
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Sicherheitsrelevante Änderungen, Logins und Dispositionseingriffe werden manipulationserschwerend protokolliert.

**Originalabnahme (unverändert):** Auditabfrage weist Akteur, Zeitpunkt und Aktivität aus.

## Vertrag

- **Tabellen / DDL:** `audit_event(tenant_id, id, actor_ref, action, object_ref, occurred_at, correlation_id, redacted_changes jsonb)`, PK `(tenant_id,id)` (V1); Trigger `audit_immutable` (`reject_mutation`, ERRCODE 23514) blockiert UPDATE/DELETE; Runtime hat nur SELECT, INSERT (`R__runtime_grants.sql`); RLS `tenant_isolation`. Index `(tenant_id, occurred_at DESC)` besteht seit V1460 (WP-M1-46); WP-M1-12 ergänzt `(tenant_id, actor_ref, occurred_at DESC)` und `(tenant_id, action, occurred_at DESC)` (V1120). Tabelle, Trigger und Rechte liefert WP-M1-46; dieser Vertrag nutzt sie und wiederholt den Nachweis im Abnahmelauf. Lifecycle (Wartung/Löschung) nur über kontrollierten Prozess (Kap. 04 §4.5).
- **API:** Auditabfrage (Filter Akteur, Objekt, Zeitraum, Aktion; Cursor-Liste), rollen- und zweckgebunden, nur lesend; Pfad `GET /v1/audit-events` (`listAuditEvents`, `api/compliance.yaml`); Filter `actor_ref`, `object_ref`, `action`, `from`, `to` (beide einschließlich), Pflichtparameter `purpose` (Planungsannahme: `security_review`, `incident_response`, `support_case`, `compliance_check`; D-11 offen), Sortierung `occurred_at, id` aufsteigend. Fremdmandant 404, fehlende Berechtigung 403, fehlender Zweck 422/abgewiesen. Der Auditzugriff selbst wird auditiert.
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
- Testfälle: AT-49; E2E-091; backend/platform/src/test/kotlin/de/taktovia/platform/AuditQueryTest.kt::abfrage_weist_akteur_zeitpunkt_aktivitaet_aus; backend/platform/src/test/kotlin/de/taktovia/platform/AuditQueryTest.kt::ohne_berechtigung_ohne_zweck_und_fremdmandant_abgewiesen; backend/platform/src/test/kotlin/de/taktovia/platform/AuditQueryTest.kt::zwei_mandanten_parallel_ohne_vermischung; backend/platform/src/test/kotlin/de/taktovia/platform/AuditExportTest.kt::manipulierte_kopie_wird_erkannt; backend/platform/src/test/kotlin/de/taktovia/platform/IdpAuditImportTest.kt::doppeltes_idp_ereignis_erzeugt_einen_eintrag_ohne_passwort; backend/platform/src/test/kotlin/de/taktovia/platform/AuditExportTest.kt::export_parallel_zu_100_schreibvorgaengen_bleibt_integer; db/tests/test_09_audit.py::test_update_delete_auf_audit_event_scheitern; backend/platform/src/test/kotlin/de/taktovia/platform/SecurityEventAuditTest.kt::fremdzugriffsereignis_enthaelt_keine_inhalte_des_anderen_mandanten; backend/app/src/test/kotlin/de/taktovia/app/audit/AuditApiTest.kt::ohne_zweck_ohne_berechtigung_und_fremdmandant_abgewiesen; web/operator/src/audit/AuditList.test.tsx::auditliste_ohne_zweck_sendet_keine_anfrage_und_zeigt_den_zustand

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.

## Umsetzungsstand WP-M1-12 (Planungsannahmen zu OP-14, nicht fachlich freigegeben)

- **Ereignisse ohne Mandant:** reservierter Systemmandant `00000000-0000-0000-0000-000000000001` (Status `SUSPENDED`, vom Plattformbetrieb per `python db/db.py provision-system-tenant` bereitgestellt, nicht per Migration); `SecurityEventPort.reportWithoutTenant` und der IdP-Import schreiben dort. Fachmandanten sehen ihn nie (RLS); eine Plattform-Abfrage dafür gibt es nicht (Supportzugriff D-11).
- **IdP-Ereignisse:** `IdpAuditImporter`/`KeycloakEvents` (platform). Dedup über deterministische Zeilen-Id aus Aussteller und Ereignis-ID (`ON CONFLICT DO NOTHING`); Anmeldekennung nur als `login_ref` (SHA-256, 16 Hex), nie im Klartext; Whitelist statt Filter, Passwort/Code/IP gelangen nie in das Ereignisobjekt. Der Transportweg Keycloak zu Backend (Listener/Webhook) ist nicht gebaut und nicht gegen Keycloak ausgeführt.
- **Export:** `AuditExporter`/`AuditExportVerifier` (NDJSON, HMAC-SHA256-Kette, Schlüssel der Exportdomäne, Abschlusszeile mit Anzahl und Kopfwert; Kopfwert separat hinterlegbar gegen Rückspielen älterer Exporte). Planung/Zeitsteuerung des Workers und das Zielsystem sind offen.
- **Zeitlich begrenzter Zugriff (UI-Zustand „abgelaufener Zugriff“):** abgebildet über Sitzungs-/Mitgliedschaftsablauf (401/403); ein eigenes befristetes Auditzugriffs-Grant-Modell gibt es nicht (D-11).
