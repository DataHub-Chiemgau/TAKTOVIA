# documents-finance – Belege und Finanzen

Paket `de.taktovia.documentsfinance` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** Expense, EconomicEvent, Journal, Allocation, Settlement; Tabellen expense_document, cost_object, economic_event, allocation_set, allocation_line, fiscal_period, journal, journal_line, settlement_line.

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.

## Uploadticket, Quarantäne und Malware-Prüfung (WP-M4-21)

`DocumentUploads.create(…)` liefert den Port `Uploads` (Ticket anfordern, abschließen, lesen, erneut prüfen, Original öffnen); Ablauf, Zustände und Betrieb: [m4-cluster-c5.md](../../../docs/umsetzungsplan/vertraege/m4-cluster-c5.md). Objektspeicher (`TenantObjectStore`) und Prüfer (`MalwareScanner`: `ClamdScanner`, `EicarSignatureScanner` nur lokal, `UnavailableScanner` fail-closed) liefert die Anwendung (`app.documents.DocumentsConfiguration`).

## Belegaggregat, Revisionen, Integrität, OCR (WP-M4-22, -23, -24, -27)

`DocumentUploads.services(…)` liefert `DocumentServices`: `uploads` (Ticket, Quarantäne, gemeinsamer Anlagebefehl `ingest`), `expenses` (lesen, Revisionen, Korrektur mit `If-Match`, Original), `intake` (E-Mail-Eingang, Rechnungsimport), `settings` (`receipt_ocr_enabled`, `invoice_email_inbound`), `integrity` (Hashprüfung, Prüfjob, Freigabe- und Postingsperre), `extraction` (OCR-Lauf mit Lease und Fencing). Alle Bausteine arbeiten über `ProtectedObjectStore` (Originale schreibgeschützt). Vertrag, Abweichungen und offene Punkte: [m4-cluster-c10.md](../../../docs/umsetzungsplan/vertraege/m4-cluster-c10.md). Der Beleg-Zustandsautomat: [beleg.md](../../../docs/umsetzungsplan/vertraege/zustaende/beleg.md).
