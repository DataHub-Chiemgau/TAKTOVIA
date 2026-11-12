# LH-I.1-DOC-004 – Kostenzuordnung zu Firma, Kostenstelle oder Fahrzeug

- **Verantwortlicher:** Rolle Finanzen/Belege (Modul `documents-finance`)
- **Arbeitspaket:** WP-M4-29
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Jeder Beleg wird einer Firma und mindestens einer Kostenstelle oder einem Fahrzeug zugeordnet.

**Originalabnahme (unverändert):** Beleg ohne erlaubte Kostenzuordnung ist nicht freigabefähig; Cross-Tenant-Zuordnung wird zurückgewiesen.

## Vertrag

- **Tabellen / DDL:** Migration: `expense_document.legal_entity_id` (V1, NOT NULL, FK `(tenant_id, legal_entity_id) -> legal_entity`); neue Zuordnungstabelle Beleg ↔ Kostenobjekt (`tenant_id, document_id, cost_object_id`, PK über beide, zusammengesetzte FKs auf `expense_document(tenant_id,id)` und `cost_object(tenant_id,id)`), damit die Invariante „Keine lokale Cross-Tenant-Referenz“ (Kap. 04 §4.5) per Schema gilt. `cost_object` (V1): `kind`, `business_ref`, `legal_entity_id`; zusätzliche CHECK/FK, dass Kostenobjekt und Beleg denselben Rechtsträger haben. RLS auf allen Tabellen. Freigabefähig = genau ein Rechtsträger und mindestens ein Kostenobjekt.
- **API:** `api/finance.yaml` enthält bisher nur `POST /v1/expenses/{expense_id}/approve`; die Pfade `/v1/expenses/receipts` und `/v1/expenses/{id}` (Kap. 05 §5.7) sind mit dem Bau in `api/finance.yaml` zu ergänzen (Vertrag vor Code). `PATCH /v1/expenses/{id}` (Zuordnung setzen, `If-Match`); Kostenobjekte lesen unter `/v1/cost-events` (Unterpfad Vorschlag des Plans). Fremde oder unzulässige IDs ergeben `404` ohne Hinweis auf Existenz (Contract-Fall „unzulässige Fremd-ID“, Kap. 05 §5.8); fehlende Zuordnung bei Freigabe `422` mit eigenem stabilem Problem-Code.
- **Zustandsautomat:** Freigabesperre „Kostenzuordnung fehlt“ als Guard im Freigabe-Workflow (WP-M4-34).
- **JSON-Schema / Ereignisse:** Ereignis „Kostenzuordnung gesetzt“ über Outbox, `audit_event`.
- **Parameter und Kennzahlen:** keine Katalogparameter.
- **Oberfläche:** UI-14: Auswahl Rechtsträger, Kostenstelle, Fahrzeug; Fehlerzustand „Kostenstelle fehlt“; nur erlaubte Objekte sichtbar (Rolle/Objektberechtigung WP-M1-08).
- **Entscheidungen:** D-06 (Kostenstellenplan; Testkostenstellen bis zum Beschluss, blockiert nur Produktivschaltung).
- **Offene Punkte:** `cost_object.kind` weicht zwischen Kern-DDL (`VEHICLE, SERVICE, RUN, LEG, PROJECT, COST_CENTER, UNALLOCATED`) und Kap. 03 §2 ab (WP-M4 OP 14); für diesen Vertrag genügen `VEHICLE` und `COST_CENTER`. Regel „erlaubte Kostenzuordnung“ je Rolle im Bau zu präzisieren. `expense_document` hat noch keinen Kostenobjektbezug (OP 14).

## Tests

- **Positiv:** Zuordnung zu Fahrzeug, zu Kostenstelle; Beleg mit Rechtsträger und Kostenobjekt ist freigabefähig.
- **Negativ:** Beleg ohne Kostenobjekt nicht freigabefähig; fremde Fahrzeug-/Kostenstellen-/Rechtsträger-ID (anderer Mandant) wird ohne Existenzhinweis abgewiesen; Kostenobjekt eines anderen Rechtsträgers; Kostenobjekt außerhalb der Objektberechtigung des Benutzers (404 ohne Existenzhinweis); direkter SQL-Versuch einer Cross-Tenant-Zuordnung scheitert am zusammengesetzten FK.
- **Konkurrierend (Datentrennung):** Kostenobjekt wird deaktiviert, während die Zuordnung läuft; Ergebnis ist konsistent (Zuordnung abgewiesen oder Beleg danach nicht freigabefähig), nie ein Beleg mit inaktivem Kostenobjekt als freigegeben.
- **Referenztests:** E2E-064, PH-AT-012.
- Testfälle: E2E-064; PH-AT-012; db/tests/test_expense_cost_assignment.py::Kostenzuordnung::test_cross_tenant_zuordnung_scheitert_am_fk; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/CostAssignmentTest.kt::fremde_id_ergibt_404_ohne_existenzhinweis; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/CostAssignmentTest.kt::deaktiviertes_kostenobjekt_parallel_zur_zuordnung; backend/app/src/test/kotlin/de/taktovia/app/documents/CostAssignmentApiTest.kt::zuordnung_per_patch_macht_den_beleg_freigabefaehig

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
