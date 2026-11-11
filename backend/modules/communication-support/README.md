# communication-support – Kommunikation und Support

Paket `de.taktovia.communicationsupport` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** Case, NotificationAttempt (Tabellen mit M2/M7).

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.

## Asynchroner Nachrichtenversand (WP-M2-32) und Vorlagen (WP-M2-33)

- **Auftrag**: `NotificationService.send(jdbc, NotificationRequest)` legt in der Transaktion des Aufrufers einen Auftrag an (`notification`, Nachrichten-ID = Auftrags-ID); höchstens einer je (Ereignis, Empfänger, Kanal) über Unique-Schlüssel, aus Ereignissen zusätzlich über die Inbox (`NotificationEventConsumer`, `CommunicationsupportApi.eventConsumer`). Kontaktdaten und Inhalte werden nie gespeichert.
- **Versand**: `NotificationDispatcher.runTenant(tenant)` (Worker, ein Lauf je Mandant): Beanspruchen mit Lease (`SKIP LOCKED`), Kontakt und Sprache über `RecipientDirectory`, Vorlage auflösen und binden, **Providerruf ausserhalb jeder Transaktion** über `DeliveryGateway` (Nachrichten-ID als Idempotenzreferenz), Ergebnis mit Zustellversuch und Ereignis (`communication.notification_sent|failed`) in einer Transaktion. Timeout = `UNKNOWN`, Abgleich beim Provider vor jeder Wiederholung; erschöpfte Versuche = `FAILED`, Dead-Letter und Betreiberwarnung. Zustandsautomat: `docs/umsetzungsplan/vertraege/zustaende/nachricht.md`, in der Datenbank per Trigger erzwungen.
- **Vorlagen**: unveränderliche Revisionen je (Ereignistyp, Sprache, Dienst, Kanal); Rückfallkette Sprache+Dienst, Sprache, Deutsch+Dienst, Deutsch; Pflichtplatzhalter `{{booking_reference}}` bei buchungsbezogenen Typen (Dienst 422 und DB-CHECK); Werte werden kanalgerecht maskiert (`TemplateRenderer`). Pflege in P1: `TemplateService` und `seedDefaults` (Deutsch, Englisch), keine HTTP-Schnittstelle (OP 29).
- **Planungswerte** (OP 28, Fachentscheidung offen): `DispatchConfig` 6 Versuche, Staffel 1 s, 5 s, 30 s, 2 min, 10 min, Lease 60 s. App: `taktovia.notifications.provider=simulator` (ohne Wert kein Worker, D-05), `worker-enabled`, `poll-seconds`, `max-attempts`, `lease-seconds`.
- **Nicht ausgeführt**: Broker (RabbitMQ), realer Push-/SMS-/E-Mail-Provider. Tests nutzen `ScriptedGateway` (Modul) und den `SimulatorProvider` des Adapters `notification`.
