# LH-I.1-DOC-008 – Hash und Integritätsprüfung der Originale

- **Verantwortlicher:** Rolle Finanzen/Belege (Modul `documents-finance`)
- **Arbeitspaket:** WP-M4-24
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Ein Hashwert und technische Integritätsprüfung sichern hochgeladene Belegoriginale gegen unbemerkte Änderungen ab.

**Originalabnahme (unverändert):** Manipulierter Originalinhalt wird beim Integritätstest erkannt und als Vorfall protokolliert.

## Vertrag

- **Tabellen / DDL:** `expense_document.original_sha256 char(64)` und Index `expense_hash(tenant_id, original_sha256)` (V1); Vorfall als Sicherheitsereignis und `audit_event` (append-only). Neue Spalte oder Zustand für Integritätsverletzung am Beleg (Freigabesperre; im Bau, Migration). Hash wird serverseitig berechnet (WP-M4-21).
- **API:** Integritätsprüfung bei Auslieferung des Originals, vor Freigabe (`POST /v1/expenses/{expense_id}/approve`, Kap. 05 §5.5 prüft den Originalhash) und als periodischer Job; Freigabe eines verletzten Belegs wird mit eigenem stabilem Problem-Code abgewiesen. `api/finance.yaml` enthält bisher nur `POST /v1/expenses/{expense_id}/approve`; die Pfade `/v1/expenses/receipts` und `/v1/expenses/{id}` (Kap. 05 §5.7) sind mit dem Bau in `api/finance.yaml` zu ergänzen (Vertrag vor Code).
- **Zustandsautomat:** Beleg mit Integritätsverletzung: Freigabesperre, kein Posting (`vertraege/zustaende/` zum Beleg).
- **JSON-Schema / Ereignisse:** Ereignis „Integritätsverletzung erkannt“ (Outbox, Sicherheitsereignis, WP-M1-12); Vorfallschema (Beleg-ID, erwarteter/tatsächlicher Hash, Zeitpunkt, Prüfanlass) im Bau.
- **Parameter und Kennzahlen:** Prüfintervall (im Pflichtenheft nicht beziffert, kein Katalogparameter).
- **Oberfläche:** UI-14: Anzeige „Integritätsverletzung“ am Beleg; Sicherheitsvorfall für Sicherheitsrolle.
- **Entscheidungen:** keine.
- **Offene Punkte:** Prüfintervall nicht beziffert (WP-M4 OP 13); Vorfallschema und Wirkung auf den Belegstatus sind im Bau festzulegen. Blockiert den Bau nicht. Langzeitarchiv/Legal Hold ist M8 (D-11).

## Tests

- **Positiv:** wiederholte Prüfung unveränderter Originale ohne Vorfall.
- **Negativ:** im Objektspeicher manipuliertes Original wird beim Integritätstest erkannt und als Vorfall protokolliert; fehlendes Objekt wird erkannt; Freigabe auf verletztem Original abgewiesen; Prüfjob läuft nur im Tenantkontext (kein Zugriff auf fremde Objekte).
- **Konkurrierend (Sicherheit):** Freigabe parallel zur Feststellung einer Integritätsverletzung: die Freigabe scheitert oder der Vorfall sperrt vor dem Posting; kein Posting auf verletztem Original.
- **Referenztests:** PH-AT-019.
- Testfälle: PH-AT-019; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/IntegrityCheckTest.kt::manipuliertes_original_wird_erkannt_und_protokolliert; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/IntegrityCheckTest.kt::freigabe_parallel_zur_verletzung_bucht_nicht; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/IntegrityCheckTest.kt::fehlendes_objekt_wird_erkannt_und_die_auslieferung_loest_den_vorfall_aus; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/IntegrityCheckTest.kt::pruefung_laeuft_nur_im_tenantkontext_und_beruehrt_fremde_objekte_nicht; db/tests/test_expense_integrity.py::test_vorfall_ist_append_only; backend/app/src/test/kotlin/de/taktovia/app/documents/ExpenseApiTest.kt::manipuliertes_original_ergibt_409_mit_stabilem_code_und_sperrt_den_beleg

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
