# LH-I.1-DOC-006 – Belegaggregat mit einheitlicher Struktur für vier Eingangskanäle

- **Verantwortlicher:** Rolle Finanzen/Belege (Modul `documents-finance`)
- **Arbeitspaket:** WP-M4-22
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Ein Beleg kann aus Fahreranwendung, Leitstelle, E-Mail-Eingang oder Rechnungsimport angelegt werden.

**Originalabnahme (unverändert):** Vier Kanäle erzeugen dieselbe fachliche Belegstruktur mit Herkunftskanal und Eingangszeit.

## Vertrag

- **Tabellen / DDL:** Migration an `expense_document`: Spalten Herkunftskanal (Enum: Fahrer-App, Leitstelle/Web-Upload, E-Mail-Eingang, Rechnungsimport; Werte im Bau festzulegen), Belegart, Vorgangsbezug; `original_received_at` (V1, NOT NULL) ist die Eingangszeit; beide neuen Kanalfelder NOT NULL. Ein Anlagebefehl für alle Kanäle. Uploadticket in eigener Tabelle des Moduls (WP-M4-21). RLS, Mandantentrennung. `expense_document` (V1): `tenant_id, id, legal_entity_id, object_key, original_sha256, mime, state, revision, created_by, approved_by, original_received_at`; Index `expense_hash(tenant_id, original_sha256)`.
- **API:** `api/finance.yaml` enthält bisher nur `POST /v1/expenses/{expense_id}/approve`; die Pfade `/v1/expenses/receipts` und `/v1/expenses/{id}` (Kap. 05 §5.7) sind mit dem Bau in `api/finance.yaml` zu ergänzen (Vertrag vor Code). `POST /v1/expenses/receipts` (Anlage als Entwurf), `GET /v1/expenses/{id}` (mit ETag); fremder Mandant ergibt `404`. Alle Kanäle gehen über Uploadticket und Quarantäne (WP-M4-21); kein Kanal umgeht die Prüfung.
- **Zustandsautomat:** Belegzustand beginnt als Entwurf (`vertraege/zustaende/` zum Beleg, WP-M4-01).
- **JSON-Schema / Ereignisse:** Belegschema (Beleg-Payload, `vertraege/schemas/`, im Bau); Ereignis „Beleg angelegt“ (Outbox, `api/events/`).
- **Parameter und Kennzahlen:** `invoice_email_inbound` (Rechnungseingangsadresse, Domäne verifiziert).
- **Oberfläche:** UI-14 zeigt Herkunftskanal und Eingangszeit; Web-Upload für Leitstelle/Finanzen; Fahrer-App-Erfassung.
- **Entscheidungen:** D-06 (zulässige Eingangswege und Rechnungsformate; blockiert den Bau nicht, Testformat).
- **Offene Punkte:** technische Form von E-Mail-Eingang und Rechnungsimport (ohne E-Rechnung) in P1, Verfahren der Domänenverifikation (WP-M4 OP 12); Zielschema Herkunftskanal-Enum; `expense_document` hat in V1 weder Kanal noch Belegart (OP 14). E-Rechnung XRechnung/ZUGFeRD ist M8.

## Tests

- **Positiv:** je ein Beleg aus allen vier Kanälen hat dieselbe Struktur mit gesetztem Herkunftskanal und Eingangszeit.
- **Negativ:** E-Mail ohne Anhang; E-Mail von nicht verifizierter Absenderdomäne erzeugt keinen freigabefähigen Beleg; Import mit defekter Datei; `GET /v1/expenses/{id}` eines fremden Mandanten ergibt 404; Umgehen von Ticket/Quarantäne nicht möglich.
- **Konkurrierend (Geld):** dieselbe E-Mail zweimal zugestellt ergibt genau einen Beleg bzw. einen Dublettenhinweis (WP-M4-32, Inbox-Deduplikation WP-M1-14).
- **Referenztests:** E2E-064, E2E-065, PH-AT-012.
- Testfälle: E2E-064; E2E-065; PH-AT-012; geplant:backend/documents-finance/src/test/kotlin/de/taktovia/documents/ReceiptChannelsTest.kt::vier_kanaele_erzeugen_dieselbe_belegstruktur; geplant:backend/documents-finance/src/test/kotlin/de/taktovia/documents/ReceiptChannelsTest.kt::doppelte_email_zustellung_ergibt_einen_beleg; geplant:db/tests/test_expense_document_channel.py::test_kanal_und_eingangszeit_pflicht

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
