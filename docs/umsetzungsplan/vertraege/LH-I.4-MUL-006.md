# LH-I.4-MUL-006 – Mitgliedschaft, RequestContext, serverseitiger Tenantwechsel, `/v1/me`

- **Verantwortlicher:** Rolle Plattform (Modul `identity`)
- **Arbeitspaket:** WP-M1-06
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Alle APIs prüfen Mandantenkontext serverseitig unabhängig von übergebenen Kennungen.

**Originalabnahme (unverändert):** Ein manipuliertes tenant_id-Feld gibt keine fremden Ressourcen preis.

## Vertrag

- **Tabellen / DDL:** `membership` (V1: `tenant_id`, `id`, `issuer`, `subject`, `role_code`, `service_scope uuid[]`, `revoked_at`, `UNIQUE(tenant_id,issuer,subject,role_code)`); Migration für zeitliche Begrenzung (z. B. `valid_from`, `valid_to`; Kap. 02 §2 „zeitlich begrenzt"). Mandantenisolation bleibt RLS (V1). Kontrollierter Lookup „Mandanten eines Subjects" vor gesetztem Tenantkontext ohne Allzweck-`SECURITY DEFINER` (Kap. 04 §4.6), liefert ausschließlich Mandanten des angemeldeten `(issuer, subject)`.
- **API:** `GET /v1/me` (Akteur, aktiver Mandant, Rollen, Dienste, wählbare Mandanten); Tenantwechsel-Operation (Detailvertrag, mit dem Bau in `api/openapi.yaml`); Verwaltung Mitgliedschaft erteilen/entziehen. Statuscodes: ohne Token 401; Token ohne aktive Mitgliedschaft im gewählten Mandanten 403; fehlender Funktionsscope 403 ohne vertrauliche Daten im Fehlertext; manipulierte `tenant_id` in Body, Pfad oder Header: 404, identisch zu „nicht vorhanden", plus Sicherheitsereignis über den Port aus WP-M1-46. Der Mandant ergibt sich ausschließlich aus dem unveränderlichen `RequestContext` (Akteur, aktiver Tenant, Scope, zulässige Dienste, Zweck, Vertragsrevision – in P1 leer); deny-by-default ohne Kontext. Architekturtest: kein Lesen von `tenant_id` aus Request-DTOs.
- **Zustandsautomat:** keine (Mitgliedschaft aktiv/widerrufen über `revoked_at`). **JSON-Schema / Ereignisse:** `membership.granted`, `membership.revoked`; Audit über WP-M1-46; Logs ohne Token und personenbezogene Kontextdaten (automatisierter Log-Scan).
- **Parameter und Kennzahlen:** keine. **Oberfläche:** UI-18 Basis, Mitgliedschaften und Rollen eines Mandanten verwalten.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-04 (Gültigkeitszeitraum/Statusfeld, Semantik `service_scope = NULL`; Vorschlag fail-closed), OP-06 (Mechanismus des Mandanten-Lookups vor Tenantkontext) – beide vor Codebeginn zu entscheiden; Detail Tenantwechsel-Operation und Token-/Sitzungsbindung (Kap. 05 §5.1) mit dem Bau.

## Tests

- **Positiv:** Nutzer mit Mitgliedschaften in A und B wechselt und sieht jeweils nur den aktiven Mandanten; `/v1/me` listet nur eigene wählbare Mandanten.
- **Negativ:** manipuliertes `tenant_id` in Body, Pfad und Header gibt keine fremde Ressource preis (404, gleichartig zu nicht vorhanden, Sicherheitsereignis); Wechsel in Mandant ohne Mitgliedschaft; fremde UUID; widerrufene Mitgliedschaft; Lookup der Mandantenliste mit fremdem Subject; ohne Token 401; fehlender Scope 403 ohne Details; Log-Scan ohne Token/PII.
- **Konkurrierend (Sicherheit/Datentrennung):** Widerruf während laufender Anfragen – keine Anfrage nach Commit des Widerrufs erfolgreich; zwei Tabs mit unterschiedlichem Kontext unabhängig.
- **Referenztests:** AT-18 (Teil Mandant), E2E-091, Baustein für PH-AT-001 (API-Teil); semantischer Contract-Fall „Manipulation tenant_id" und „unzulässige Fremd-ID" (Kap. 05 §5.8).
- Testfälle: AT-18; E2E-091; PH-AT-001; geplant:backend/identity/src/test/kotlin/de/taktovia/identity/ArchitectureTest.kt::kein_tenant_id_aus_request_dtos; geplant:backend/identity/src/test/kotlin/de/taktovia/identity/RequestContextTest.kt::ohne_token_401_ohne_mitgliedschaft_403_fehlender_scope_403_ohne_details; geplant:backend/identity/src/test/kotlin/de/taktovia/identity/TenantSwitchTest.kt::lookup_liefert_nur_mandanten_des_angemeldeten_subjects; geplant:backend/identity/src/test/kotlin/de/taktovia/identity/TenantSwitchTest.kt::zwei_kontexte_parallel_unabhaengig; geplant:backend/identity/src/test/kotlin/de/taktovia/identity/LogScanTest.kt::logs_ohne_token_und_personenbezogene_kontextdaten; geplant:backend/identity/src/test/kotlin/de/taktovia/identity/MembershipAdminTest.kt::erteilen_entziehen_wechsel_auditiert_und_outbox_ereignis; geplant:backend/identity/src/test/kotlin/de/taktovia/identity/RequestContextTest.kt::manipuliertes_tenant_id_ergibt_404_und_sicherheitsereignis; geplant:backend/identity/src/test/kotlin/de/taktovia/identity/TenantSwitchTest.kt::widerruf_waehrend_anfragen_keine_erfolgreiche_anfrage_nach_commit

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Sicherheit und Datentrennung).
