# documents-finance – Belege und Finanzen

Paket `de.taktovia.documentsfinance` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** Expense, EconomicEvent, Journal, Allocation, Settlement; Tabellen expense_document, cost_object, economic_event, allocation_set, allocation_line, fiscal_period, journal, journal_line, settlement_line.

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.

## Uploadticket, Quarantäne und Malware-Prüfung (WP-M4-21)

`DocumentUploads.create(…)` liefert den Port `Uploads` (Ticket anfordern, abschließen, lesen, erneut prüfen, Original öffnen); Ablauf, Zustände und Betrieb: [m4-cluster-c5.md](../../../docs/umsetzungsplan/vertraege/m4-cluster-c5.md). Objektspeicher (`TenantObjectStore`) und Prüfer (`MalwareScanner`: `ClamdScanner`, `EicarSignatureScanner` nur lokal, `UnavailableScanner` fail-closed) liefert die Anwendung (`app.documents.DocumentsConfiguration`).

## Belegaggregat, Revisionen, Integrität, OCR (WP-M4-22, -23, -24, -27)

`DocumentUploads.services(…)` liefert `DocumentServices`: `uploads` (Ticket, Quarantäne, gemeinsamer Anlagebefehl `ingest`), `expenses` (lesen, Revisionen, Korrektur mit `If-Match`, Original), `intake` (E-Mail-Eingang, Rechnungsimport), `settings` (`receipt_ocr_enabled`, `invoice_email_inbound`), `integrity` (Hashprüfung, Prüfjob, Freigabe- und Postingsperre), `extraction` (OCR-Lauf mit Lease und Fencing). Alle Bausteine arbeiten über `ProtectedObjectStore` (Originale schreibgeschützt). Vertrag, Abweichungen und offene Punkte: [m4-cluster-c10.md](../../../docs/umsetzungsplan/vertraege/m4-cluster-c10.md). Der Beleg-Zustandsautomat: [beleg.md](../../../docs/umsetzungsplan/vertraege/zustaende/beleg.md).

## Bestätigungspflicht für OCR-Vorschläge (WP-M4-28)

`DocumentServices.review` (`ReceiptReview`): OCR-Vorschläge werden bei niedriger Konfidenz oder Summenabweichung markiert, nur Bestätigtes oder Korrigiertes ist Belegstand, Freigabevoraussetzung ist "alle Pflichtfelder bestätigt" (422 `UNCONFIRMED_FIELDS`). Vertrag und offene Punkte: [m4-cluster-wave9-c8.md](../../../docs/umsetzungsplan/vertraege/m4-cluster-wave9-c8.md).

## Kostenzuordnung, Kostenereignis, Posting (WP-M4-29, -33)

`DocumentServices.costs` (`CostAssignments`: Beleg einem oder mehreren Kostenobjekten — Kostenstelle oder Fahrzeug — desselben Rechtsträgers zuordnen, zuordenbare Kostenobjekte lesen, Kostenobjekt deaktivieren), `costEvents` (`CostEvents`: einmaliges Posting eines freigegebenen Belegs zu Ereignis und ausgeglichenem Journal in einer Transaktion, Ist-Bericht nur mit `POSTED`, Salden als Projektion) und `accounting` (`AccountingMappings`: Kontierung Kostenart → Konten, Test-Kontenrahmen). Vertrag, Annahmen und offene Punkte: [m4-cluster-c9.md](../../../docs/umsetzungsplan/vertraege/m4-cluster-c9.md).

## Tankbeleg, Plausibilität, Dubletten, Freigabe-Workflow (WP-M4-30, -31, -32, -34)

`DocumentServices.fuel` (Tankwerte je Beleg), `plausibility` (Tankplausibilität mit quittierbaren Hinweisen), `duplicates` (Dublettenerkennung und Evidenzverknüpfung), `approvals` (Erfassung, fachliche Prüfung, Budgetfreigabe, zweite Freigabe, Ablehnung, Exportstatus) und `policies` (Fachparameter je Mandant und Fahrzeug). Die Freigabe prüft alle Blockaden in fester Reihenfolge (`ApprovalGate`) und bucht atomar (WP-M4-33). Vertrag, Annahmen und offene Punkte: [m4-cluster-wave10-c5.md](../../../docs/umsetzungsplan/vertraege/m4-cluster-wave10-c5.md).
