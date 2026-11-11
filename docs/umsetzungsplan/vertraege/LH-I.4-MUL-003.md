# LH-I.4-MUL-003 – Mandant, Rechtsträger, Organisation und vertragliche Rollen

- **Verantwortlicher:** Rolle Plattform (Modul `identity`)
- **Arbeitspaket:** WP-M1-05
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Die Software unterscheidet Plattformbetreiber, Verkehrsunternehmen, Aufgabenträger, Auftraggeber, Vermittler und tatsächlichen Beförderer als eigenständige Rollen.

**Originalabnahme (unverändert):** Ein Testfall mit vier unterschiedlichen Organisationen speichert vertragliche Rollen getrennt.

## Vertrag

- **Tabellen / DDL:** vorhanden: `tenant` (`status` `ACTIVE`/`SUSPENDED`/`CLOSED`), `legal_entity`, `organization` (`legal_entity_id`, `federation_ref`, `roles text[] NOT NULL`, `UNIQUE(tenant_id,federation_ref)`, FK `(tenant_id,legal_entity_id)`). Neue Migration: CHECK auf zulässige Werte in `organization.roles`; `created_at`/Urheber/Revision gemäß Kap. 04 §4.1; Erweiterung um zeitliche Gültigkeit der Rollen (Kap. 02 §2). Die sechs Lastenheftrollen (Plattformbetreiber, Verkehrsunternehmen, Aufgabenträger, Auftraggeber, Vermittler, tatsächlicher Beförderer) werden als getrennte Enum-Werte gespeichert; keine Rolle wird aus einer anderen abgeleitet; eine Organisation kann mehrere Rollen tragen. RLS (V1) gilt für alle Tabellen; gleicher Rechtsträger begründet keinen Zugriff auf andere Mandanten (Kap. 04 §4.1).
- **API:** Verwaltungsendpunkte für Organisation/Rechtsträger (nicht in Kap. 05 §5.7, OpenAPI in `api/` zu ergänzen); unbekannte Rollenwerte → 422 mit `violations[].pointer`; fremder Mandant → 404; Mandantenanlage nur durch die Plattformbetreiberrolle einschließlich Rechtsträger und erster Administrator-Mitgliedschaft; danach keine Fachdatensicht ohne eigene Mitgliedschaft (Kap. 02 §4.3); in `SUSPENDED` schlägt jeder Fachzugriff fehl; Statuswechsel auditiert.
- **Zustandsautomat:** Mandantenstatus `ACTIVE`, `SUSPENDED`, `CLOSED` (Übergangsregeln im Detail im Bau; Dokument unter `vertraege/zustaende/tenant.md` anzulegen).
- **JSON-Schema / Ereignisse:** `organization.changed` über `outbox`; jede Änderung an Organisation, Rechtsträger und Rollen erzeugt genau einen Auditeintrag und genau ein Outbox-Ereignis (Schema unter `api/events/organization.changed.v1.schema.json`).
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-18 Basis – Liste und Formular Organisationen mit Rechtsträgerrollen; Zustände „Scopekonflikt“, fehlendes Recht.
- **Entscheidungen:** D-01 (Rollen je Dienst, Rechnungsaussteller) – blockiert nur Produktivschaltung der Rollenbelegung, nicht den Bau.
- **Offene Punkte:** OP-05 – Werteliste und Abbildung der sechs Lastenheftrollen auf die Begriffe aus Kap. 02 §2 (Plattformanbieter, Beförderer, Vermittler, Fahrzeugsteller, Kostenträger) sowie Gültigkeitsintervall sind nicht festgelegt; Enum und Abbildung sind vor Codebeginn des CHECK zu beschließen (Bau der Speicherung und Tests mit den sechs Lastenheftwerten ist möglich); OP-36 – Ablauf der Mandantenanlage und Erstausstattung nicht beschrieben. Kooperationsvertrag/Grants (M6), Fahrzeugrollen (WP-M1-34) und Unternehmensprofil (WP-M1-24) sind nicht im Umfang.

## Tests

- **Positiv:** vier unterschiedliche Organisationen (Plattformbetreiber, Aufgabenträger, Vermittler, tatsächlicher Beförderer) speichern ihre vertraglichen Rollen getrennt und werden getrennt gelesen; eine Organisation mit mehreren Rollen; Plattformbetreiberrolle legt Mandant samt Rechtsträger und erster Administrator-Mitgliedschaft an.
- **Negativ:** unbekannte Rolle (422); Organisation mit Rechtsträger eines fremden Mandanten (FK-Verletzung → 404/422); Organisationen/Rechtsträger des Mandanten B aus A weder les- noch referenzierbar (404); doppelte `federation_ref`; Zugriff auf Mandant im Status `SUSPENDED` scheitert; Plattformbetreiber ohne Mitgliedschaft liest keine Fachdaten des Mandanten; Mandantenanlage durch Mandantenadministrator abgewiesen (403).
- **Zusätzlich:** Statuswechsel des Mandanten (`ACTIVE`, `SUSPENDED`, `CLOSED`) sowie jede Änderung an Organisation, Rechtsträger und Rollen erzeugt genau einen Auditeintrag und genau ein Outbox-Ereignis `organization.changed`; keine Rolle wird aus einer anderen abgeleitet.
- **Konkurrierend:** paralleles Anlegen derselben `federation_ref` – genau ein Erfolg; zwei parallele Änderungen derselben Organisation-Revision – eine erhält 412.
- **Referenztests:** keine Katalog-ID im WP genannt; Isolationsbaustein für PH-AT-001.
- Testfälle: geplant:backend/identity/src/test/kotlin/de/taktovia/identity/OrganizationRolesTest.kt::vier_organisationen_speichern_vertragliche_rollen_getrennt; geplant:backend/identity/src/test/kotlin/de/taktovia/identity/OrganizationRolesTest.kt::unbekannte_rolle_ergibt_422; geplant:backend/identity/src/test/kotlin/de/taktovia/identity/OrganizationRolesTest.kt::paralleles_anlegen_gleicher_federation_ref_genau_ein_erfolg; geplant:db/tests/test_organisation_rollen.py::test_roles_check_lehnt_unbekannte_werte_ab; geplant:backend/identity/src/test/kotlin/de/taktovia/identity/OrganizationRolesTest.kt::mehrere_rollen_je_organisation_und_keine_rolle_wird_abgeleitet; geplant:backend/identity/src/test/kotlin/de/taktovia/identity/OrganizationRolesTest.kt::organisation_des_mandanten_b_aus_a_nicht_les_oder_referenzierbar_404; geplant:backend/identity/src/test/kotlin/de/taktovia/identity/OrganizationRolesTest.kt::fremder_rechtstraeger_wird_abgewiesen; geplant:backend/identity/src/test/kotlin/de/taktovia/identity/OrganizationRolesTest.kt::mandantenanlage_nur_durch_plattformbetreiber_und_ohne_fachdatensicht; geplant:backend/identity/src/test/kotlin/de/taktovia/identity/OrganizationRolesTest.kt::suspended_mandant_verweigert_fachzugriff; geplant:backend/identity/src/test/kotlin/de/taktovia/identity/OrganizationRolesTest.kt::aenderung_erzeugt_genau_ein_audit_und_ein_outbox_ereignis; geplant:backend/identity/src/test/kotlin/de/taktovia/identity/OrganizationRolesTest.kt::zwei_parallele_aenderungen_derselben_organisation_eine_412

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
