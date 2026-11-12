# Ereigniskatalog M2 (WP-M2-01)

Outbox-Ereignisse der Module `booking-dispatch`, `fare-payment` und `communication-support`. Hülle: [platform.eventenvelope.v1](../../../api/events/platform.eventenvelope.v1.schema.json) (Aggregat-ID, Revision, Empfängermandant, Korrelation). Payload-Schemas: `api/events/<event_type>.v1.schema.json`; Zustandsübergänge nutzen das gemeinsame Schema [platform.statechanged.v1](../../../api/events/platform.statechanged.v1.schema.json). **Keine exakten Adressen oder Kontaktdaten im Payload** (Kap. 05 §5.1); Orte nur als Vergröberung (`coarse_*`, OP-13), Personen nur als Zahl.

`schema_version` aller Ereignisse: `1.0`. Empfängerkreis: `eigener Mandant` (recipient = owner), solange keine Föderation besteht (M6).

| Ereignis (`event_type`) | Aggregat / Revision | Auslöser | Verbraucher | Schema |
|---|---|---|---|---|
| `booking.attempt_recorded` | `travel_attempt` / 1 | jede fachlich neue Anfrage, auch abgelehnte (Primärgrund) | M4 Nachfragetrichter, Kennzahlen (Ablehnungen, `Ausstattungsablehnungen`) | ja |
| `booking.offer_created` | Angebot (`ride_offer`) / 1 | Angebot mit Preis, Planrevision, `expires_at` | M4 Trichter | ja |
| `booking.hold_placed` | `capacity_hold` / 1 | Hold gesetzt | M4 Trichter, Betreiberansicht | ja |
| `booking.hold_released` | `capacity_hold` / 2 | Client-Freigabe, Kompensation | M4 Trichter | ja |
| `booking.hold_expired` | `capacity_hold` / 2 | Ablauf (`now >= expires_at`) | M4 Trichter | ja |
| `booking.confirmed` | `booking` / 1 | Confirm, Hold nach `CONSUMED` | M3 (Fahrtenplan, Boarding-Code), M4 (Trichter, Umsatz), Nachrichten (COM-001) | ja |
| `booking.state_changed` | `booking` / n | jeder Zustandsübergang nach [buchung-lokal.md](zustaende/buchung-lokal.md) | M3, M4 | `platform.statechanged` |
| `booking.cancelled` | `booking` / n | Storno mit Grund und Initiator | M4 (Stornoquote), Zahlung (Erstattung), Nachrichten | ja |
| `dispatch.run_revised` | `vehicle_run` / n | jede Planrevision (Einfügung, Entnahme, manuelle Änderung, Neuplanung) | M3 (Fahrtenplan, Fahrerhinweise), Nachrichten (Zusageänderung) | ja |
| `payment.state_changed` | `payment_intent` / n | Übergang nach [zahlung-ablauf.md](zustaende/zahlung-ablauf.md) | Buchung (Zahlungsvoraussetzung), M4 | `platform.statechanged` |
| `payment.authorized` | `payment_intent` / n | Autorisierung bestätigt (Zustand `AUTHORIZED`, Voraussetzung der Buchungsbestätigung, WP-M2-30) | Buchung (Kopplung), M4 | ja |
| `payment.authorization_failed` | `payment_intent` / n | Autorisierung abgelehnt oder aufgehoben (zu spät, Betrag abweichend, Buchung freigegeben, WP-M2-30) | Buchung (Freigabe), Nachrichten | ja |
| `payment.captured` | `payment_intent` / n | Einzug vom PSP bestätigt (WP-M2-30) | M4 (Umsatz), Nachrichten | ja |
| `payment.outcome_unknown` | `payment_intent` / n | PSP-Aufruf ohne bestimmtes Ergebnis oder Einzug als Klärfall (WP-M2-30) | Betreiberwarnung, M4 | ja |
| `payment.refund_requested` | `refund_case` / n | Erstattung beauftragt, noch nicht vom PSP bestätigt (WP-M2-31) | Nachrichten (Erstattungshinweis), M4 | ja |
| `payment.refund_failed` | `refund_case` / n | Erstattung vom PSP definitiv abgelehnt; Buchung bleibt storniert, Betrag bleibt offen (WP-M2-31) | Betreiberwarnung | ja |
| `payment.refund_recorded` | `payment_intent` / n | Erstattung bestätigt | M4 (Umsatz), Nachrichten | ja |
| `payment.provider_event_received` | `payment_event` / 1 | signiertes PSP-Ereignis dauerhaft gespeichert (WP-M2-29), je Ereignis-ID des Providers genau einmal; kein Zahlstatus-Übergang | Zahlungsautomat (WP-M2-30, Abgleich per Statusabfrage) | ja |
| `communication.notification_sent` | `notification` / n | Zustellung bestätigt | Betreiberansicht | ja |
| `communication.notification_failed` | `notification` / n | endgültiger Fehlschlag | Betreiberwarnung | ja |
| `promise.changed` (M1, WP-M1-27) | `booking` / n | fahrgastrelevante Änderung der Zusage | Nachrichten (COM-001) | vorhanden |

**Abstimmung mit M3/M4 (Akzeptanzkriterium 6):** M4 liest ausschließlich `booking.attempt_recorded`, `booking.offer_created`, `booking.hold_*`, `booking.confirmed`, `booking.cancelled`, `payment.*` für den Nachfragetrichter (Versuch, Angebot, Hold, Buchung, Storno, Zahlung); M3 liest `booking.confirmed`, `booking.cancelled` und `dispatch.run_revised`. Die Abstimmung mit den Planungsdokumenten M3/M4 ist als Annahme dokumentiert (Verantwortlicher: Rolle Buchung/Disposition) und mit deren WP-01 zu bestätigen; die Ereignisnamen sind Arbeitsnamen dieses Plans.

**Regeln:** Ereignis und Aggregatänderung in derselben Transaktion; mindestens einmal zugestellt, Verbraucher deduplizieren über `event_id` (Inbox). Ein Ereignis zu einer älteren Aggregatrevision überschreibt nie einen neueren Zustand.
