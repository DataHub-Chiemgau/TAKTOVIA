# LH-6.2-IAM-004 – Rollenmodell und Rechtematrix

- **Verantwortlicher:** Rolle Plattform (Modul `identity`)
- **Arbeitspaket:** WP-M1-07
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Rollen umfassen mindestens Fahrgast, Fahrer, Callcenter, Support, Disponent, Verkehrsplaner und Administrator.

**Originalabnahme (unverändert):** Je Rolle sind erlaubte und verweigerte Tätigkeiten in einem Rechtematrixtest bestätigt.

## Vertrag

- **Tabellen / DDL:** `membership.role_code` (V1: `text NOT NULL`, `UNIQUE(tenant_id,issuer,subject,role_code)`); Migration ergänzt CHECK/Referenz auf den Rollenkatalog. Werteliste: die sieben Rollen Fahrgast, Fahrer, Callcenter, Support, Disponent, Verkehrsplaner, Administrator sowie Platzhalter für weitere Fachrollen aus Kap. 06 (Finanzen, Personalplanung, Werkstatt/Freigabe; Bedeutung für M3/M4). Konkrete Codes werden im Rollenkatalog festgelegt.
- **Rechtematrix:** versioniertes Artefakt im Repository (Rolle × Tätigkeit für alle M1-Funktionen), Abbildung Rolle -> Funktionsscopes (z. B. `config:publish`, `operations:read`; Export als eigener Funktionsscope). Der Matrixtest wird aus der Matrixdatei generiert (7 Rollen × M1-Tätigkeiten, erlaubt = Erfolg, verweigert = 403/404). Änderung der Matrix ist nachvollziehbar und wird beim Ausrollen auditiert.
- **API:** Scope-Deklaration (`security`) an jedem M1-Endpunkt in OpenAPI; eine Route ohne Matrixeintrag lässt den Build scheitern. Zentrale Autorisierungskomponente im Modul `identity`.
- **Invarianten:** Tenantadmin erhält nicht automatisch HR-, Gesundheits- oder Finanzzugriff (Kap. 01 §1.7); Administratorrolle hat ohne gesonderte Zuweisung keinen Zugriff auf Felder der Klassen PII/HR/Finanzen.
- **Zustandsautomat / Schema / Ereignisse / Parameter:** keine.
- **Oberfläche:** rollenbezogene Navigation im Operator-Web (nur erlaubte Bereiche sichtbar); Anzeige ist keine Rechtequelle (Kap. 06 §6.1).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-10 (Pflichtenheft enthält keine Matrix Rolle × Tätigkeit und keine Werteliste für `role_code`; Kap. 06-Fachrollen nicht in IAM-004 genannt); OP-36 (Mandantenanlage und erste Administrator-Mitgliedschaft). Blockiert den Bau der Matrixmechanik nicht; der Inhalt der Matrix ist vor Codebeginn von Fachanalyse festzulegen und freizugeben.

## Tests

- **Positiv:** generierter Matrixlauf: jede als erlaubt markierte M1-Tätigkeit gelingt je Rolle.
- **Negativ:** jede als verweigert markierte Tätigkeit ergibt 403/404; neue Route ohne Matrixeintrag lässt den Build scheitern; Administrator ohne gesonderte Zuweisung kann PII/HR/Finanzfelder nicht lesen; unbekannter `role_code` wird per CHECK abgewiesen; Rollenwechsel/Matrixänderung ist im Repository nachvollziehbar (Versionierung der Matrixdatei) und wird beim Ausrollen auditiert; Operator-Web zeigt nur erlaubte Bereiche (Anzeige ersetzt keine serverseitige Prüfung); Fremdmandant-Zugriff bleibt verweigert (AT-18).
- **Konkurrierend:** Rollenentzug während aktiver Sitzung – Folgeanfrage wird verweigert.
- **Referenztests:** AT-18 (Teil Rolle).
- Testfälle: AT-18; backend/modules/identity/src/test/kotlin/de/taktovia/identity/RechtematrixTest.kt::matrixdatei_ist_strukturell_gueltig_und_deckt_sieben_pflichtrollen_ab; backend/modules/identity/src/test/kotlin/de/taktovia/identity/RechtematrixTest.kt::administrator_ohne_zuweisung_kein_pii_hr_finanzzugriff; backend/modules/identity/src/test/kotlin/de/taktovia/identity/RechtematrixTest.kt::matrixaenderung_nachvollziehbar_und_auditiert; backend/modules/identity/src/test/kotlin/de/taktovia/identity/RechtematrixTest.kt::rollenentzug_waehrend_sitzung_verweigert; backend/modules/identity/src/test/kotlin/de/taktovia/identity/RechtematrixTest.kt::zentrale_autorisierung_deny_by_default; backend/app/src/test/kotlin/de/taktovia/app/identity/RechtematrixApiTest.kt::matrix_alle_rollen_erlaubt_und_verweigert; backend/app/src/test/kotlin/de/taktovia/app/identity/RechtematrixApiTest.kt::route_ohne_matrixeintrag_scheitert; backend/app/src/test/kotlin/de/taktovia/app/identity/RechtematrixApiTest.kt::administrator_ohne_zuweisung_hat_keinen_zugriff_auf_pii_hr_finanzen_ueber_http; backend/app/src/test/kotlin/de/taktovia/app/identity/RechtematrixApiTest.kt::rollenentzug_waehrend_sitzung_verweigert; backend/app/src/test/kotlin/de/taktovia/app/identity/RechtematrixApiTest.kt::rollenkatalog_matrix_openapi_und_datenbank_sind_synchron; backend/app/src/test/kotlin/de/taktovia/app/identity/RechtematrixApiTest.kt::ausrollen_der_matrix_wird_beim_start_protokolliert; db/tests/test_m1_07_rollenkatalog.py::test_unbekannter_role_code_per_check_abgewiesen; web/operator/src/navigation/rollennavigation.test.tsx::nur_erlaubte_bereiche_sichtbar

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (falls gefordert) | | | |
