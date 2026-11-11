# LH-I.1-DOC-002 – OCR-Adapter und Feldextraktion

- **Verantwortlicher:** Rolle Finanzen/Belege (Modul `documents-finance`, Worker `workers/documents`)
- **Arbeitspaket:** WP-M4-27
- **Status:** Vertrag in Arbeit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> OCR erkennt Belegdatum, Betrag, Währung, Händler, Belegnummer und Mehrwertsteuerbeträge, soweit sichtbar.

**Originalabnahme (unverändert):** Ein Testsatz unterschiedlicher Kassenbelege wird extrahiert; jedes erkannte Feld ist einzeln prüfbar und korrigierbar.

## Vertrag

- **Tabellen / DDL:** `extraction_run` (Dokumentrevision, OCR-Version, Zeit, Status) und `extracted_field` (Feld, Rohwert, normalisierter Wert, Konfidenz, Quellenkoordinate, Korrekturbezug) laut WP-M4-27 und Kap. 04 §4.5 Familie DOC; im V1-Schema nicht enthalten, Migration ab V4 mit RLS nach `tenant_id`. Ursprünglicher OCR-Vorschlag bleibt unverändert erhalten; Korrektur erzeugt eine neue Revision mit Korrekturbezug (Kap. 03 §3 Nr. 2). Rohbeleg (`expense_document.original_sha256`) bleibt unabhängig vom OCR-Ergebnis archiviert. Betrag als `amount_minor`, Datum, Währung normalisiert.
- **API:** `/extract` zu einem Beleg unter `/v1/expenses/receipts` (Extraktion anstoßen, asynchrone `operation` mit Lease/Fencing, WP-M1-15); Feldkorrektur über `/v1/expenses/{id}`; je Feld bestätigen/korrigieren. Statuscodes 202, 400, 401, 403, 404, 409, 422. Pfade noch nicht in `api/finance.yaml`.
- **Zustandsautomat:** `extraction_run`: angestoßen, läuft, abgeschlossen, fehlgeschlagen (Wiederholung ohne Doppellauf); Feld: Vorschlag, bestätigt, korrigiert. Datei unter `vertraege/zustaende/` wird mit dem Bau angelegt.
- **JSON-Schema / Ereignisse:** Outbox-Ereignisse „Extraktion abgeschlossen“ und „Extraktion fehlgeschlagen“ (Namen nach `api/events/`-Schema mit dem Bau). Adapter `ocr` hinter providerunabhängiger Schnittstelle in `workers/documents`.
- **Parameter und Kennzahlen:** `receipt_ocr_enabled` (Bool, Mandant, Katalog LH-J.5; Rohbeleg bleibt archiviert); bei `false` keine Extraktion, manuelle Erfassung bleibt möglich.
- **Oberfläche:** Prüfansicht je Feld (Belegdatum, Betrag, Währung, Händler, Belegnummer, Mehrwertsteuerbeträge) mit Konfidenz und Fundstelle; je Feld bestätigen/korrigieren; erkannter Text wird neutralisiert dargestellt (keine Anweisungen, Formeln oder Markup wirksam); nicht sichtbare Felder bleiben leer.
- **Entscheidungen:** D-06 (zulässige OCR-/Verarbeitungsdienste nicht ausdrücklich geregelt; blockiert nur Produktivschaltung).
- **Offene Punkte:** OCR-Engine, Unsicherheitsschwelle und Datenschutz bei externem Dienst sind nicht festgelegt (WP-M4 Offener Punkt 11, D-06); Feldkatalog, Normalisierung und Adaptervertrag OCR sind laut WP vorab zu definieren. Der Testsatz unterschiedlicher Kassenbelege und die erwartete Feldtrefferliste sind nicht vorhanden und vom Auftraggeber bzw. QA bereitzustellen; ohne Testsatz ist die Originalabnahme nicht prüfbar. Der Vertrag bleibt deshalb „Vertrag in Arbeit“; der Bau von Adapter-Schnittstelle und Datenmodell ist nicht blockiert, die Engine-Auswahl nur für die Produktivschaltung.

## Tests

- **Positiv:** Testsatz unterschiedlicher Kassenbelege mit erwarteter Feldtrefferliste: die sechs Feldarten liegen je Feld einzeln mit Konfidenz und Fundstelle vor; jedes Feld einzeln bestätig- und korrigierbar, der ursprüngliche OCR-Vorschlag bleibt erhalten (E2E-066, falscher Betrag 89,00 statt 39,00).
- **Negativ:** unlesbares Bild: Felder bleiben leer, nichts wird geraten; Beleg mit eingebettetem Anweisungs-/Formeltext löst keine Aktion aus und wird neutralisiert dargestellt; OCR-Timeout: Job wiederholbar ohne Doppellauf; Beleg in Quarantäne wird nicht extrahiert; `receipt_ocr_enabled = false`: keine Extraktion.
- **Konkurrierend:** zwei parallele Extraktionsläufe zum selben Beleg ergeben genau einen wirksamen Lauf (Lease/Fencing); Extraktion läuft im Tenantkontext des Belegs, kein Zugriff auf Belege anderer Mandanten (Datentrennung).
- **Referenztests:** E2E-064 (Teil), E2E-066.
- Testfälle: E2E-064; E2E-066; geplant:workers/documents/tests/test_ocr_feldextraktion.py::test_testsatz_liefert_sechs_feldarten_mit_konfidenz_und_fundstelle; geplant:backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/ExtractedFieldKorrekturTest.kt::korrektur_erhaelt_ocr_vorschlag; geplant:backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/ExtractedFieldKorrekturTest.kt::betrag_89_statt_39_korrigierbar_vorschlag_nachvollziehbar; geplant:backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/ExtractionRunTest.kt::ocr_deaktiviert_keine_extraktion; geplant:backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/ExtractionRunTest.kt::quarantaene_beleg_nicht_extrahiert; geplant:backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/ExtractionRunTest.kt::paralleler_lauf_genau_ein_wirksamer_lauf; geplant:backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/ExtractionRunTest.kt::fremder_mandant_kein_zugriff_auf_extraktion; geplant:workers/documents/tests/test_ocr_feldextraktion.py::test_unlesbares_bild_felder_leer_nichts_geraten; geplant:workers/documents/tests/test_ocr_feldextraktion.py::test_eingebetteter_anweisungstext_loest_keine_aktion_aus

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung, Verarbeitung von Belegbildern bei externem Dienst).
