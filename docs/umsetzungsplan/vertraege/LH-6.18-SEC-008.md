# LH-6.18-SEC-008 – Zahlungsdatentrennung (keine Kartendaten im System)

- **Verantwortlicher:** Rolle Plattform (Security) mit Zahlungsbezug
- **Arbeitspaket:** WP-M1-37
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Zahlungen verarbeiten keine rohen Kartendaten in eigenem System; Payment-Token werden geschützt.

**Originalabnahme (unverändert):** Sicherheitsprüfung bestätigt PSP-konforme Trennung.

## Vertrag

- **Tabellen / DDL:** `payment_intent.provider_ref text NOT NULL` (V1, UNIQUE `(tenant_id,provider,provider_ref)`) ist die einzige PSP-Referenz; klassifiziert im Dateninventar (SEC-004); Feldschutzklasse „Payment-Token" im Feldschutzbaustein (WP-M1-31); die Zuordnung zu den fünf Schutzklassen aus Kap. 01 §1.7 ist offen (siehe Offene Punkte). Schema-Lint: keine Spalte für PAN, CVC, Ablaufdatum oder Karteninhaber (Namensmuster); Migration mit solcher Spalte scheitert. PSP-Referenzen nur für Rollen mit Zahlungsbezug lesbar (Feldschutzbaustein aus WP-M1-31).
- **API:** OpenAPI-Lint: kein Schema in `api/*.yaml` mit Kartendatenfeldern; Kap. 05 §5.3: „PSP-Token bleibt ein PSP-Referenzwert; rohe Zahlungsdaten erreichen diese API nie". Eingangsfilter erkennt kartennummernähnliche Muster (Body, Query, Header, Freitext) und weist ab (4xx) oder maskiert.
- **Port:** Eingangs- und Log-Filter in `backend/platform` für Requests, Logs, Audit und Ereignisse; Maskierung auch in `audit_event.redacted_changes`.
- **Zustandsautomat / Schema / Ereignisse:** keine (Zahlungsautomat ist M2). Nachweisdokument „PSP-konforme Trennung" (Datenfluss, Systemgrenzen) unter `docs/`.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** Vorgabe an das Designsystem: Zahlungsdateneingabe ausschließlich in PSP-gehosteten Komponenten.
- **Entscheidungen:** D-03 (Teil: PSP-Auswahl) – blockiert nur Produktivschaltung.
- **Offene Punkte:** Erkennungsmuster und Maskierungsregel (Luhn-Prüfung, Formate) sind im Pflichtenheft nicht festgelegt und mit dem Bau zu bestimmen; Schutzklasse der Token (Vorschlag: wie sensible Felder; Zuordnung zu Kap. 01 §1.7 offen). OP-01: abschließende Bestätigung am echten PSP-Adapter erst mit M2 (WP-M2-29); in M1 Nachweis über Lints, Log-/Audit-Scan und Datenflussdokument. Zahlungsautomat, Authorize/Capture/Refund und PCI-Nachweis des PSP sind nicht Umfang.

## Tests

- **Positiv:** Request mit PSP-Referenz (`provider_ref`) wird verarbeitet; Rolle mit Zahlungsbezug liest die Referenz; Schema-Lint, OpenAPI-Lint, Log-/Audit-Scan der Testläufe und Datenflussdokument bestätigen die PSP-konforme Trennung.
- **Negativ:** Testkartennummer in Body, Query und Header sowie im Freitextfeld wird abgewiesen oder maskiert und erscheint weder in Logs noch in `audit_event.redacted_changes`; Migration mit Kartendatenspalte scheitert; OpenAPI-Schema mit Kartendatenfeld scheitert; Rolle ohne Zahlungsbezug erhält die PSP-Referenz nicht; Fremdmandant 404.
- **Konkurrierend:** 100 parallele Requests mit Testkartennummer unter Last – jeder wird abgewiesen oder maskiert, kein Treffer gelangt über asynchrones Logging in Logs oder Audit.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:backend/platform/src/test/kotlin/de/taktovia/platform/CardDataFilterTest.kt::kartennummer_in_freitext_wird_abgewiesen_oder_maskiert; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/CardDataFilterTest.kt::kartennummer_in_body_query_header_wird_abgewiesen_oder_maskiert; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/CardDataFilterTest.kt::psp_referenz_nur_fuer_rolle_mit_zahlungsbezug; geplant:tests/acceptance/security/test_zahlungsdatentrennung.py::test_openapi_ohne_kartendatenfeld; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/CardDataFilterTest.kt::hundert_parallele_requests_ohne_treffer_in_logs_und_audit; geplant:db/tests/test_11_zahlungsdaten.py::test_keine_kartendatenspalte_im_schema

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
