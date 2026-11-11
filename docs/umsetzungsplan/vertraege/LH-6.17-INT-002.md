# LH-6.17-INT-002 – Mitgliedschaft, RequestContext, serverseitiger Tenantwechsel, `/v1/me`

- **Verantwortlicher:** Rolle Plattform (Modul `identity`, Port in `platform`)
- **Arbeitspaket:** WP-M1-06
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> API nutzt geeignete standardisierte Authentifizierung, Berechtigungsprüfung und Mandantenfilterung.

**Originalabnahme (unverändert):** Unautorisierter oder mandantenfremder Zugriff liefert keine vertraulichen Daten.

## Vertrag

- **Tabellen / DDL:** `membership` aus `kernschema.sql` (`tenant_id`, `id`, `issuer`, `subject`, `role_code`, `service_scope uuid[]`, `revoked_at`; UNIQUE `(tenant_id,issuer,subject,role_code)`); Migration für zeitliche Begrenzung (Kap. 02 §2, OP-04) und kontrollierten Lookup "Mandanten eines Subjects" vor gesetztem Tenantkontext ohne Allzweck-`SECURITY DEFINER` (Kap. 04 §4.6; OP-06). Mandantenfilterung bleibt RLS (V1) unter der Runtime-Rolle.
- **API:** `/v1/me` (Akteur, aktiver Mandant, Rollen, Dienste, wählbare Mandanten); Tenantwechsel-Operation (Detailvertrag zu ergänzen); Verwaltungsendpunkte Mitgliedschaft erteilen/entziehen. Statuscodes: ohne Token 401; Token ohne aktive Mitgliedschaft im gewählten Mandanten 403; fehlender Funktionsscope 403 ohne vertrauliche Daten im Fehlertext; nicht sichtbare oder fremde Ressource und manipulierte `tenant_id` in Body/Pfad/Header einheitlich 404 `NOT_FOUND` (identisch zu "nicht vorhanden"), dazu Sicherheitsereignis über den Audit-Port WP-M1-46. Standardisierte Authentisierung über Sicherheitsschema `oidc` (`api/common.yaml`; Menschen: Authorization Code + PKCE, Adapter: Client Credentials, Kap. 05 §5.1); die Tokenprüfung selbst (Signatur, Issuer, Audience, Ablauf, Sitzungsstatus) liefert WP-M1-04 und wird hier an M1-Endpunkten mitgeprüft. Alle Fehler als `Problem` (LH-6.17-INT-003).
- **RequestContext:** unveränderlich aus Akteur, aktivem Tenant, Scope, zulässigen Diensten, Zweck, Vertragsrevision (in P1 leer); Tenant ausschließlich aus aktiver Mitgliedschaft, nie aus Request-DTOs (Architekturtest); deny-by-default ohne Kontext; keine Token/personenbezogenen Kontexte in Logs (automatisierter Log-Scan).
- **Zustandsautomat:** keine.
- **JSON-Schema / Ereignisse:** `membership.granted`, `membership.revoked` (Outbox); Schemas als `api/events/membership.granted.v1.schema.json` und `api/events/membership.revoked.v1.schema.json` (Namensschema der `api/events/README.md`) anzulegen; Outbox-Eintrag je Wirkung (UNIQUE `(tenant_id,aggregate_id,aggregate_revision,event_type)`).
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-18 Basis (Kooperation/Rollen, Kap. 06: Grant erteilen/entziehen) – Mitgliedschaften und Rollen eines Mandanten verwalten, in `web/operator`; Zustände aus Kap. 06: Vertragsablauf, offene Reisen, Scopekonflikt; zusätzlich Rechte-Zustand (ohne Berechtigung nicht sichtbar bzw. 403) und Fehlerzustand.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-06 (Mechanismus der Mandantenliste vor Tenantkontext, vor Codebeginn des Lookups festzulegen; blockiert diesen Teil), OP-04 (Gültigkeitszeitraum, Statusfeld, `service_scope`-Semantik); Tenantwechsel-Operation und Token-/Sitzungsbindung sind Detailverträge vorab.

## Tests

- **Positiv:** Nutzer mit Mitgliedschaften in A und B wechselt serverseitig und sieht jeweils nur den aktiven Mandanten; `/v1/me` liefert nur Mandanten des angemeldeten Subjects.
- **Negativ:** ohne Token 401; abgelaufenes Token, falscher Issuer oder falsche Audience wird abgewiesen (401; Statuscode für ungültige Tokens im Fehlercode-Katalog festzulegen);  Wechsel in Mandant ohne aktive Mitgliedschaft 403; fehlender Funktionsscope 403 ohne Datenpreisgabe; manipulierter Mandantenheader bzw. `tenant_id` in Body/Pfad -> 404 plus Sicherheitsereignis; fremde UUID 404; widerrufene Mitgliedschaft (`revoked_at`) beendet Zugriff mit der nächsten Anfrage; Mandantenlisten-Lookup mit fremdem Subject liefert nichts; Architekturtest verbietet `tenant_id` aus Request-DTOs; Log-Scan ohne Token/PII; Mitgliedschaft erteilen/entziehen und Tenantwechsel erzeugen je einen Auditeintrag (Port WP-M1-46); `membership.granted`/`membership.revoked` genau einmal in `outbox` und gültig gegen das Ereignisschema; Erteilen/Entziehen ohne Berechtigung 403, für fremden Mandanten 404; doppelte Mitgliedschaft (UNIQUE `(tenant_id,issuer,subject,role_code)`) wird abgewiesen (Code im Katalog); Tenantwechsel in einen Dienst ohne Freigabe (`service_scope`) wird abgewiesen; Fehlerantworten enthalten keine Daten fremder Mandanten; UI-18 zeigt nur Mitgliedschaften des aktiven Mandanten.
- **Konkurrierend:** Widerruf während laufender Anfragen – keine Anfrage nach Commit des Widerrufs erfolgreich; zwei Tabs mit unterschiedlichem aktivem Mandanten bleiben unabhängig; parallele Anfragen A/B ohne Kontextvermischung; paralleles Erteilen derselben Mitgliedschaft erzeugt genau eine Zeile und ein Ereignis.
- **Referenztests:** AT-18 (Teil Mandant), PH-AT-001 (API-Teil), E2E-091; semantische Contract-Fälle "Manipulation tenant_id" und "unzulässige Fremd-ID" (Kap. 05 §5.8).
- Testfälle: AT-18; E2E-091; PH-AT-001; geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/TenantSwitchTest.kt::fremde_mitgliedschaft_ergibt_403_und_manipulierter_tenant_404; geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/TenantSwitchTest.kt::widerruf_waehrend_anfragen_beendet_zugriff; geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/TenantSwitchTest.kt::zwei_tabs_unterschiedlicher_mandant_unabhaengig; geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/MeApiTest.kt::me_liefert_nur_mandanten_des_subjects_ohne_token_401; geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/MembershipLookupTest.kt::lookup_fremdes_subject_liefert_nichts; geplant:backend/modules/identity/src/test/kotlin/de/taktovia/identity/MembershipEventsTest.kt::erteilen_und_entziehen_auditiert_und_ereignis_genau_einmal; geplant:backend/app/src/test/kotlin/de/taktovia/architecture/RequestContextRulesTest.kt::tenant_id_nicht_aus_request_dtos; geplant:backend/app/src/test/kotlin/de/taktovia/app/LogScanTest.kt::keine_token_oder_pii_in_logs; geplant:web/operator/src/identity/MembershipAdmin.test.tsx::ui18_zeigt_nur_aktiven_mandanten_und_rechtezustand

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Sicherheit und Datentrennung).
