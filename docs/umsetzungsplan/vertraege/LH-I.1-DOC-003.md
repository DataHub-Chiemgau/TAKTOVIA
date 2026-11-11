# LH-I.1-DOC-003 – OCR als unbestätigter Vorschlag mit Bestätigungspflicht

- **Verantwortlicher:** Rolle Finanzen/Belege (Modul `documents-finance`)
- **Arbeitspaket:** WP-M4-28
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Die Software behandelt OCR-Ergebnisse als unbestätigte Vorschläge und fordert bei wesentlichen Unsicherheiten eine menschliche Freigabe.

**Originalabnahme (unverändert):** Niedrige Erkennungsqualität führt zu Markierung; ohne Bestätigung gelangt kein Vorschlag als gebuchter Aufwand in die Kostenrechnung.

## Vertrag

- **Tabellen / DDL:** Neue Migration (V-Nummer mit dem Bau): `extracted_field` (`tenant_id, id, document_id, revision, field_name, value_text, confidence numeric(5,4), status` mit CHECK `status IN ('PROPOSED','CONFIRMED','CORRECTED')`, `suggested_value` (unveränderlicher OCR-Vorschlag), `confirmed_by`, `confirmed_at`, `flag_reason` NULL|`LOW_CONFIDENCE`|`SUM_MISMATCH`) und `document_revision` (siehe DOC-007). Zusammengesetzte FKs `(tenant_id, document_id) -> expense_document(tenant_id, id)`, RLS wie alle Mandantentabellen. Der OCR-Vorschlag (`suggested_value`) wird nie überschrieben; Korrekturen stehen als eigener Wert mit Status `CORRECTED`. `expense_document` (V1): `tenant_id, id, legal_entity_id, object_key, original_sha256, mime, state, revision, created_by, approved_by, original_received_at`; Index `expense_hash(tenant_id, original_sha256)`.
- **API:** `api/finance.yaml` enthält bisher nur `POST /v1/expenses/{expense_id}/approve`; die Pfade `/v1/expenses/receipts` und `/v1/expenses/{id}` (Kap. 05 §5.7) sind mit dem Bau in `api/finance.yaml` zu ergänzen (Vertrag vor Code). `PATCH /v1/expenses/{id}` (Feld bestätigen/korrigieren, `If-Match` Pflicht: 428 ohne, 412 bei veraltetem ETag). `POST /v1/expenses/{expense_id}/approve` weist mit `422` und stabilem Problem-Code (Name im Bau festzulegen, z. B. `UNCONFIRMED_FIELDS`) ab, solange ein Pflichtfeld (`receipt_mandatory_fields`) nicht `CONFIRMED`/`CORRECTED` ist.
- **Zustandsautomat:** Feldstatus `PROPOSED -> CONFIRMED | CORRECTED`; kein Rückweg ohne neue Revision. Belegzustand gemäß `vertraege/zustaende/` (Freigabe-Workflow, WP-M4-34).
- **JSON-Schema / Ereignisse:** Ereignis „Feld bestätigt/korrigiert“ über Outbox und `audit_event` (WP-M1-46).
- **Parameter und Kennzahlen:** `receipt_mandatory_fields` (Feldmenge je Mandant/Belegart; steuerlich notwendige Felder geschützt), `receipt_ocr_enabled` (nur Aktivierung, kein Schwellenparameter).
- **Oberfläche:** UI-14 (Belege/OCR, Prüfen/Freigeben): markierte Felder (niedrige Konfidenz, Summenabweichung Netto + Steuer ≠ Brutto), Bestätigen/Korrigieren, Anzeige des ursprünglichen OCR-Vorschlags neben dem korrigierten Wert; Rechte: nur Rollen mit Belegprüfung.
- **Entscheidungen:** keine.
- **Offene Punkte:** Schwelle „wesentliche Unsicherheit“ ist kein Katalogparameter (WP-M4 OP 11); der Bau startet mit einer konfigurierbaren, im Vertrag zu benennenden Testschwelle (Konfidenzwert), die Fachanalyse bestätigen muss. OCR-Engine und Datenschutz bei externem Dienst offen (OP 11). Blockiert den Bau nicht.

## Tests

- **Positiv:** Alle Pflichtfelder bestätigt, danach ist die Freigabe zulässig; niedrige Konfidenz setzt `flag_reason = LOW_CONFIDENCE`.
- **Negativ:** Freigabe mit unbestätigtem Betrag wird abgewiesen (kein Kostenereignis, keine `journal`-Zeile); Summenabweichung (Netto + Steuer ≠ Brutto) markiert und blockiert; OCR liest 89,00 statt 39,00, Korrektur auf 39,00 möglich, `suggested_value` 89,00 bleibt abrufbar (E2E-066); Änderung ohne `If-Match` (428); Bestätigen/Korrigieren durch Benutzer ohne Belegprüfungsrecht (403); Beleg eines anderen Mandanten (404).
- **Konkurrierend (Geld):** Feldkorrektur parallel zur Freigabe: die Freigabe auf der alten Dokumentrevision scheitert mit 412, es wird kein unbestätigter Wert gebucht.
- **Referenztests:** E2E-064, E2E-066, PH-AT-012.
- Testfälle: E2E-064; E2E-066; PH-AT-012; geplant:backend/documents-finance/src/test/kotlin/de/taktovia/documents/OcrConfirmationTest.kt::freigabe_mit_unbestaetigtem_betrag_wird_abgewiesen; geplant:backend/documents-finance/src/test/kotlin/de/taktovia/documents/OcrConfirmationTest.kt::korrektur_parallel_zur_freigabe_ergibt_412; geplant:db/tests/test_extracted_field.py::test_ocr_vorschlag_bleibt_unveraendert

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
