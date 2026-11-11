# LH-6.1-SYS-005 – Revisionsfeste Zusage und kontrollierte Änderungsverfahren

- **Verantwortlicher:** Rolle Plattform (Modul `backend/platform`)
- **Arbeitspaket:** WP-M1-27
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Eine verbindliche Fahrtzusage enthält unveränderliche Kernkonditionen und kontrollierte Änderungsverfahren.

**Originalabnahme (unverändert):** Jede Änderung einer Zusage erzeugt ein Audit-Ereignis und eine Fahrgastinformation, soweit nötig.

## Vertrag

- **Tabellen / DDL:** In M1 Muster und Testaggregat, keine fachliche Tabelle. Muster für `booking.promise_snapshot` (jsonb NOT NULL), `booking.revision` und `journey_leg.promise_revision` (Anwendung in M2). Unveränderlichkeit analog Trigger `quote_immutable` (`BEFORE UPDATE OR DELETE`, `reject_mutation()`) auf `fare_quote`: gebundener Snapshot nicht per UPDATE/DELETE änderbar. Invariante Kap. 04 §4.5 "Gegenereignis statt Änderung": eine Änderung legt eine neue Revision an, die alte bleibt lesbar. Typ "gebundener Snapshot": Inhalt, JSON-Schema-Version, Hash (Muster `fare_quote.content_hash char(64)`), Revision, referenzierte Konfigurations-/Tarifrevision (`config_revision_id`). Die Spalten `booking.promise_snapshot`, `booking.revision` und `journey_leg.promise_revision` bestehen bereits im Kern-DDL; die Zusage-Tabelle des Testaggregats und deren Trigger sind Vorschlag dieses Vertrags (OP-33).
- **API:** Änderungsverfahren als Befehl mit Pflichtfeldern Grund, Akteur (aus Principal), alte und neue Revision; `If-Match` (ETag) – gleichzeitige zweite Änderung erhält 412; fehlender Grund 422 `application/problem+json`; unberechtigte Rolle 403; veraltete Revision 412 `REVISION_MISMATCH`, fehlender ETag 428 `PRECONDITION_REQUIRED` (Kap. 05 Fehlertabelle); fremder Mandant 404 `NOT_FOUND`. Pfade im Bau festzulegen (an Testaggregat; echter Träger `booking` in M2).
- **Zustandsautomat:** keiner eigener; Änderungsverfahren wendet den Rahmen aus WP-M1-17 an (Revision/CAS).
- **JSON-Schema / Ereignisse:** JSON-Schema des Zusage-Snapshots (`schemas/`); Ereignisschema `promise.changed` (Vorschlag, OP-33) mit alter/neuer Revision und Kennzeichen "Fahrgastinformation erforderlich"; Outbox-Ereignis mit Informationsauftrag, Übergabe an den Benachrichtigungs-Port (Zustellung M2); Auditeintrag über WP-M1-12/-46 mit Akteur, Grund, alter und neuer Revision.
- **Parameter und Kennzahlen:** keine neuen.
- **Oberfläche:** keine in M1.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-29 / OP-01 – Welche Felder die unveränderlichen "Kernkonditionen" bilden, der Katalog zulässiger Änderungsverfahren und die Regel, wann eine Fahrgastinformation "nötig" ist, sind im Pflichtenheft nicht abschließend festgelegt. Für den Bausteinnachweis am Testaggregat nicht blockierend (Testregel: Änderung eines als fahrgastrelevant markierten Feldes löst Information aus); für den Einsatz an der Buchung (`booking.promise_snapshot`) vor M2 vom Auftraggeber festzulegen. Tatsächliche Zustellung der Fahrgastinformation und Nachweis an der Buchung folgen mit M2 (Vollabnahme WP-M2-23, WP-M2-32).

## Tests

- **Positiv:** Änderung mit Informationspflicht erzeugt genau ein Audit-Ereignis (Akteur, Grund, alte/neue Revision) und genau ein Outbox-Ereignis mit Informationsauftrag; Änderung ohne Informationspflicht erzeugt genau ein Audit-Ereignis und kein Outbox-Ereignis; alte Revision bleibt lesbar.
- **Negativ:** direktes UPDATE/DELETE auf den Snapshot scheitert (Trigger); Änderung ohne Grund; Änderung durch unberechtigte Rolle (403); Änderung mit veralteter alter Revision (412) oder ohne ETag (428); Änderung durch fremden Mandanten (404); Snapshot mit nicht passendem Hash oder unbekannter Schema-Version wird abgewiesen; Konfigurationsänderung (neue `config_revision`) verändert bestehende Snapshots nicht; kein Audit-/Outbox-Eintrag bei abgewiesener Änderung.
- **Konkurrierend (Geld/Kapazität):** zwei gleichzeitige Änderungen derselben Zusage – eine erhält 412, genau ein Audit- und ein Outbox-Eintrag.
- **Referenztests:** keine Katalog-ID im WP genannt (verwandt: PH-AT-018 "Zusage", dort WP-M1-20/-45).

Testfälle: geplant:db/tests/test_zusage_snapshot.py::test_snapshot_update_und_delete_werden_abgewiesen; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/promise/PromiseChangeProcedureTest.kt::aenderung_erzeugt_audit_und_informationsauftrag; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/promise/PromiseChangeProcedureTest.kt::konfigurationsaenderung_laesst_bestehenden_snapshot_unveraendert; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/promise/PromiseChangeProcedureTest.kt::aenderung_ohne_grund_oder_unberechtigt_wird_abgewiesen; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/promise/PromiseChangeProcedureTest.kt::parallele_aenderung_liefert_412_und_genau_ein_audit_und_outbox_eintrag

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (erforderlich: Zusage/Preis/Kapazität) | | | |
