# LH-6.12-COM-003 – Asynchroner Nachrichtenversand mit Zustellversuchen

- **Verantwortlicher:** Rolle Kommunikation (Modul `communication-support`)
- **Arbeitspaket:** WP-M2-32
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Versand ist asynchron und hat Wiederholungs-/Fehlerbehandlung sowie eindeutige Nachrichten-ID.

**Originalabnahme (unverändert):** Provider-Timeout erzeugt keine unkontrollierte Mehrfachbenachrichtigung.

## Vertrag

- **Tabellen / DDL:** neue Tabellen (Migration nach V3, nächste freie Versionsnummer, Kap. 04 §4.4: ein fachlicher Auftrag, n Zustellversuche):
  - `notification`: `tenant_id`, `id` (= Nachrichten-ID, uuid, eindeutig), `event_id` (Quelle aus `outbox`), `recipient_ref` (Referenz auf `passenger_private.id`, keine Klartextkontakte), `channel` (push|sms|email), `event_type`, `template_revision_id`, `state`, `attempt_count`, `failure_reason`, `created_at`; Primärschlüssel `(tenant_id, id)`, Unique `(tenant_id, event_id, recipient_ref, channel)` als fachlicher Schlüssel.
  - `notification_attempt`: `tenant_id`, `notification_id`, `attempt_no`, `started_at`, `outcome` (success|failed|unknown), `provider_ref`; Primärschlüssel `(tenant_id, notification_id, attempt_no)`, lokaler FK `(tenant_id, notification_id)` auf `notification(tenant_id, id)`; nur anfügen (kein UPDATE/DELETE für die Runtime-Rolle). Neue Tabellen sind in `db/table_owners.csv` und `db/role_matrix.csv` einzutragen (Modul `communication-support`).
  - RLS `tenant_isolation` wie V1; Inbox `inbox(tenant_id, consumer, event_id)` für Einmaligkeit des Auftrags je Ereignis.
- **Verfahren:** Auftrag entsteht aus Outbox-Ereignis über die Inbox; Versand-Worker außerhalb der Fachtransaktion (RabbitMQ); begrenztes Backoff, Höchstzahl Versuche, Dead-Letter und Betreiberwarnung. Je Versuch wird die Nachrichten-ID als Idempotenzreferenz an den Provider übergeben (`backend/adapters/notification`, Kanalschnittstelle Push/SMS/E-Mail, Simulator-Provider mit steuerbarem Timeout/Fehler). Timeout setzt den Versuch auf `unknown`; Wiederholung nutzt dieselbe Nachrichten-ID; ein später Erfolg eines früheren Versuchs erzeugt keine zweite fachliche Nachricht. Retry und Zustellstatus ändern keine Buchungszusage (Kap. 01 §1.3). Interne Fachoperation "Betriebsnachricht senden" für andere Module (u. a. SMS für Kontaktverifikation, WP-M1-28).
- **API:** `GET /v1/notifications` und `GET /v1/notifications/{id}`: Auftrag und Zustellstatus nur für berechtigte Rollen des eigenen Mandanten; fremde ID -> 404; Fehler als `problem+json` (api/common.yaml). Keine Schreibaktionen für Endkunden. Datei `api/` für Kommunikation ist mit WP-M2-01 anzulegen.
- **Zustandsautomat:** `vertraege/zustaende/notification.md` (anzulegen): Auftrag `angelegt -> versendet | wiederholt | endgültig fehlgeschlagen`; Versuch `gestartet -> erfolgreich | fehlgeschlagen | unbekannt (Timeout)`; unbekannt führt nur zu Wiederholung mit gleicher Nachrichten-ID.
- **JSON-Schema / Ereignisse:** Ausgehend: "Nachricht versendet", "Nachricht endgültig fehlgeschlagen" (Ereignisschemas nach `api/events/README.md`).
- **Parameter und Kennzahlen:** keine Katalogparameter; Retry-Intervalle und Höchstzahl sind nicht festgelegt (OP 28). Protokollierung: Nachrichteninhalt und Kontaktdaten weder in Logs noch in Metriklabels.
- **Oberfläche:** keine in P1; Betreiberwarnung bei endgültigem Fehlschlag über die Betriebssicht (Darstellung außerhalb dieses Vertrags).
- **Entscheidungen:** D-05 (Provider für Push, SMS, E-Mail, Bau gegen Simulator, blockiert nur Produktivschaltung); D-11 (Protokollierungs- und Löschfrist der Nachrichten, blockiert nur Produktivschaltung).
- **Offene Punkte:** OP 28 (WP-Dokument): Provider, Web-Push-Fähigkeit der PWA als P1-Kanal, Wiederholintervalle und Höchstzahl Versuche sind im Pflichtenheft nicht festgelegt; der Bau parametrisiert sie über Konfiguration mit Testwerten, Produktivwerte erfordern eine Fachentscheidung (blockiert den Bau nicht). Die Zustandsübergänge bei `unknown` ohne Provider-Idempotenz sind mit dem realen Provider zu bestätigen.

## Tests

- **Positiv:** Versand je Kanal (Push, SMS, E-Mail) über Simulator; Nachrichten-ID in allen Zustellversuchen und beim Provider identisch; Versand läuft außerhalb der Fachtransaktion, Buchung wird auch bei Providerausfall nicht verzögert.
- **Negativ:** dauerhafter Providerfehler -> nach Höchstzahl Versuche dokumentierter Fehlschlag, Dead-Letter und Betreiberwarnung, kein stiller Verlust; ungültiger Kontakt -> dokumentierter Fehler; Inhalt und Kontaktdaten nicht in Logs/Metriklabels; fremde Nachrichten-ID -> 404.
- **Konkurrierend:** dasselbe Ereignis parallel an zwei Worker -> genau ein Auftrag (Unique und Inbox); Provider-Timeout mit spätem Erfolg -> keine zweite Nachricht (höchstens eine fachliche Nachricht je Ereignis, Empfänger, Kanal); Mandantenkontext der Worker: Nachrichten fremder Mandanten nicht lesbar.
- **Referenztests:** keine Katalog-ID im WP genannt; Nachweis der Abnahme über COM-001-Tests (AT-07, AT-01).
- Testfälle: geplant:backend/communication-support/src/test/kotlin/de/taktovia/communication/NotificationDispatchTest.kt::provider_timeout_erzeugt_keine_mehrfachnachricht; geplant:backend/communication-support/src/test/kotlin/de/taktovia/communication/NotificationDispatchTest.kt::dasselbe_ereignis_parallel_ergibt_ein_auftrag; geplant:backend/communication-support/src/test/kotlin/de/taktovia/communication/NotificationApiTest.kt::fremde_nachricht_ergibt_404; geplant:backend/communication-support/src/test/kotlin/de/taktovia/communication/NotificationDispatchTest.kt::dauerhafter_fehler_nach_hoechstzahl_dead_letter_und_warnung; geplant:backend/communication-support/src/test/kotlin/de/taktovia/communication/NotificationDispatchTest.kt::ungueltiger_kontakt_dokumentierter_fehler; geplant:backend/communication-support/src/test/kotlin/de/taktovia/communication/NotificationDispatchTest.kt::nachrichten_id_in_allen_versuchen_und_beim_provider_identisch; geplant:backend/communication-support/src/test/kotlin/de/taktovia/communication/NotificationDispatchTest.kt::providerausfall_verzoegert_keine_buchung; geplant:backend/communication-support/src/test/kotlin/de/taktovia/communication/NotificationDispatchTest.kt::keine_inhalte_oder_kontaktdaten_in_logs_und_metriklabels; geplant:backend/communication-support/src/test/kotlin/de/taktovia/communication/NotificationApiTest.kt::lesen_nur_berechtigte_rolle_eigener_mandant; geplant:db/tests/test_notification.py::test_unique_fachlicher_schluessel_und_rls

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.

