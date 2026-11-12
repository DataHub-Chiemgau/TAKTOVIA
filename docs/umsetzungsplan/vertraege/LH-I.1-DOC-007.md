# LH-I.1-DOC-007 – Unveränderliches Original, Versionen und Prüfspur

- **Verantwortlicher:** Rolle Finanzen/Belege (Modul `documents-finance`)
- **Arbeitspaket:** WP-M4-23
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Originalbelege werden unverändert archiviert; Bearbeitungen erzeugen getrennte Versionen und unveränderliche Prüfspuren.

**Originalabnahme (unverändert):** Ein korrigierter Betrag überschreibt nie das Originaldokument und ist vollständig mit Bearbeiter/Zeitpunkt nachvollziehbar.

## Vertrag

- **Tabellen / DDL:** Migration `document_revision` (`tenant_id, id, document_id, revision, field_values jsonb` mit Altwert/Neuwert, `edited_by`, `edited_at`, `reason`), PK/UNIQUE `(tenant_id, document_id, revision)`, FK auf `expense_document`; Trigger `reject_mutation()` (BEFORE UPDATE OR DELETE) analog `audit_immutable`; `expense_document.revision` (V1) zählt hoch; `audit_event` append-only (V1). Originalobjekt: Objektspeicher ohne Überschreib-/Löschrecht der Runtime (mandantengetrennte Präfixe, WP-M4-21); bearbeitete Ansicht als eigenes Objekt. Runtime-Grants in `R__runtime_grants.sql` ohne UPDATE/DELETE auf `document_revision`.
- **API:** `api/finance.yaml` enthält bisher nur `POST /v1/expenses/{expense_id}/approve`; die Pfade `/v1/expenses/receipts` und `/v1/expenses/{id}` (Kap. 05 §5.7) sind mit dem Bau in `api/finance.yaml` zu ergänzen (Vertrag vor Code). `GET /v1/expenses/{id}` (Revisionen und Original abrufen; Original über signierte, kurzlebige URL, Vorschlag des Plans); Änderung nur mit `If-Match` (428 ohne, 412 bei veraltetem ETag). Kein Pfad ändert oder löscht das Original.
- **Zustandsautomat:** Ein gebuchter Beleg (`POSTED`) erlaubt keine freie Betragsbearbeitung (Kap. 06 §6.6); Korrektur nur über Gegenereignis (WP-M4-36).
- **JSON-Schema / Ereignisse:** Ereignis „Belegrevision angelegt“ (Outbox), `audit_event` mit Bearbeiter und Zeitpunkt.
- **Parameter und Kennzahlen:** keine Katalogparameter.
- **Oberfläche:** UI-14: Revisionsverlauf (Bearbeiter, Zeitpunkt, alter/neuer Wert), Original ansehen.
- **Entscheidungen:** D-11 (Archivierung/Aufbewahrung; blockiert nur Produktivschaltung).
- **Offene Punkte:** Regel, welche Felder eine neue Revision auslösen, und Gültigkeitsdauer der signierten URL sind im Bau festzulegen. Revisionsfähiger Archivexport (DOC-023) ist M8.

## Tests

- **Positiv:** zwei Korrekturen eines Betrags, danach sind alle Versionen mit Bearbeiter, Zeitpunkt, Altwert und Neuwert abrufbar und das Original ist bytegleich (Hashvergleich).
- **Negativ:** Schreib- und Löschversuch auf das Originalobjekt unter Runtime-Rolle scheitert; UPDATE/DELETE auf `document_revision` scheitert per Trigger; Änderung ohne `If-Match` ergibt 428, veralteter ETag 412; freie Betragsbearbeitung am gebuchten Beleg abgewiesen; Original eines fremden Mandanten nicht erreichbar.
- **Konkurrierend (Geld):** zwei parallele Korrekturen derselben Revision: genau eine gewinnt, die andere erhält 412.
- **Referenztests:** E2E-064, E2E-066, PH-AT-019.
- Testfälle: E2E-064; E2E-066; PH-AT-019; db/tests/test_document_revision.py::test_revision_unveraenderlich_unter_runtime_rolle; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/DocumentRevisionTest.kt::korrektur_ueberschreibt_original_nie; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/DocumentRevisionTest.kt::parallele_korrekturen_genau_eine_gewinnt; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/DocumentRevisionTest.kt::schreib_und_loeschversuche_auf_originale_und_revisionen_scheitern; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/DocumentRevisionTest.kt::freigegebener_gebuchter_und_gesperrter_beleg_ist_nicht_frei_bearbeitbar; backend/app/src/test/kotlin/de/taktovia/app/documents/ExpenseApiTest.kt::beleg_lesen_korrigieren_mit_if_match_revisionen_und_original

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
