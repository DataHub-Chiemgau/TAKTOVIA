# LH-I.1-DOC-005 – Tankbeleg-Fachdaten

- **Verantwortlicher:** Rolle Finanzen/Belege (Modul `documents-finance`)
- **Arbeitspaket:** WP-M4-30
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Für Tankquittungen werden Kraftstoffart, getankte Liter, Preis pro Liter, Bruttobetrag, Kilometerstand und Fahrzeug erfasst.

**Originalabnahme (unverändert):** Ein plausibler Tankvorgang ist als Kostenereignis auswertbar; fehlende Pflichtwerte werden angezeigt.

## Vertrag

- **Tabellen / DDL:** Migration `fuel_transaction`: `tenant_id, id, document_id` (FK `expense_document`), `vehicle_id` (zusammengesetzter FK auf Fahrzeugstamm, WP-M3-03), `fuel_type` (Kraftstoffart aus Fahrzeugstamm), `quantity numeric(20,8)` mit `unit` (Liter; kWh getrennt, Kap. 03 §2.1), `unit_price` (Preis je Einheit, als Dezimal ohne Gleitkomma), `gross_minor bigint`, `currency char(3)`, `odometer_km`, `fueled_at timestamptz`, `card_id` optional; CHECK `quantity > 0`. Kein `float`/`double`. RLS, Mandanten-FKs. `expense_document` (V1): `tenant_id, id, legal_entity_id, object_key, original_sha256, mime, state, revision, created_by, approved_by, original_received_at`; Index `expense_hash(tenant_id, original_sha256)`.
- **API:** `api/finance.yaml` enthält bisher nur `POST /v1/expenses/{expense_id}/approve`; die Pfade `/v1/expenses/receipts` und `/v1/expenses/{id}` (Kap. 05 §5.7) sind mit dem Bau in `api/finance.yaml` zu ergänzen (Vertrag vor Code). Tankfelder in `/v1/expenses/{id}` und beim Anlegen über `/v1/expenses/receipts`; Mengen und Preise als Dezimalstrings. Fehlende Pflichtwerte werden einzeln als Feldfehler (`422`, je Feld ein Eintrag) geliefert.
- **Zustandsautomat:** Freigabesperre „Pflichtwert fehlt“ im Freigabe-Workflow (WP-M4-34); Übergabe als Tankvorgang an das Kostenereignis (WP-M4-33).
- **JSON-Schema / Ereignisse:** Ereignis „Tankvorgang erfasst“ (Outbox), Konsum durch Kilometerstandsregister (WP-M4-41).
- **Parameter und Kennzahlen:** `receipt_mandatory_fields`, `fuel_receipt_required` (Wirkung offen).
- **Oberfläche:** UI-14 und Fahrer-App-Erfassung: Pflichtfelder Kraftstoffart, Liter, Preis je Liter, Brutto, Kilometerstand, Fahrzeug; fehlende Werte einzeln markiert.
- **Entscheidungen:** keine.
- **Offene Punkte:** Wirkung von `fuel_receipt_required` (WP-M4-30, Offener Punkt 39 des Plans) folgt dem Detailvertrag, im Pflichtenheft nicht festgelegt; blockiert den Bau nicht. Die Plausibilitätshinweise zum Tankvorgang liefert WP-M4-31 (nicht Teil dieses Vertrags). Die Erfassung durch den Fahrer ersetzt nie die Finanzprüfung (Katalogvalidierung). Konfliktregel Brutto gegen Liter x Preis (Rundung) ist nicht festgelegt und im Bau mit der Fachanalyse zu klären.

## Tests

- **Positiv:** Vollständiger, plausibler Tankbeleg wird nach Freigabe als Kostenereignis ausgewertet (genau ein `economic_event`).
- **Negativ:** fehlende Liter, fehlender Kilometerstand, fehlendes Fahrzeug werden einzeln angezeigt und blockieren die Freigabe; negatives oder nulles Volumen abgewiesen; fremdes Fahrzeug (anderer Mandant) abgewiesen; Gleitkommaeingabe in JSON-Zahl abgelehnt bzw. Dezimalstring erzwungen.
- **Konkurrierend (Geld):** zwei parallele Freigaben desselben Tankbelegs erzeugen genau ein Kostenereignis.
- **Referenztests:** E2E-064, PH-AT-012.
- Testfälle: E2E-064; PH-AT-012; geplant:db/tests/test_fuel_transaction.py::test_negative_menge_und_fremdes_fahrzeug_abgewiesen; geplant:backend/documents-finance/src/test/kotlin/de/taktovia/documents/FuelReceiptTest.kt::fehlende_pflichtwerte_werden_einzeln_angezeigt; geplant:backend/documents-finance/src/test/kotlin/de/taktovia/documents/FuelReceiptTest.kt::parallele_freigabe_ergibt_ein_kostenereignis

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
