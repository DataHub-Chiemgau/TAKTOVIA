# fare-payment – Tarif und Zahlung

Paket `de.taktovia.farepayment` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** QuoteSnapshot, PaymentIntent, RefundCase; Tabellen fare_quote, payment_intent.

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.

## Stand (WP-M2-29)

Eingang signierter PSP-Ereignisse (`ProviderEvents`, `POST /v1/payments/provider-events/{provider}`): Signatur und Zeitfenster, Deduplikation in `payment_event`, internes Ereignis `payment.provider_event_received`. Detailvertrag: [psp-adapter.md](../../../docs/umsetzungsplan/vertraege/psp-adapter.md). Der Zahlungsautomat folgt mit WP-M2-30.

## Stand (WP-M2-07)

Tarifrevisionen mit Preisregeln und Gültigkeit (`Tariffs`, `/v1/tariffs`): Entwurf, Prüfung und Veröffentlichung durch eine zweite Person, nicht überlappende veröffentlichte Revision je Dienst und Tarifklasse (Exclusion Constraint), nach der Veröffentlichung unveränderlich, wirksame Revision zum Preisstichtag (`effective`, „kein Tarif“ = `null`), Ereignis `tariff.revision_published`. Detailvertrag: [LH-6.10-PAY-003](../../../docs/umsetzungsplan/vertraege/LH-6.10-PAY-003.md), Zustände: [tarifrevision.md](../../../docs/umsetzungsplan/vertraege/zustaende/tarifrevision.md).
