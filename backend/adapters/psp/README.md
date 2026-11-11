# adapter psp

Zahlungsdienstleister (Authorize/Capture/Void/Refund/Status). Paket `de.taktovia.adapters.psp`. Externe Aufrufe nie innerhalb einer offenen SQL-Transaktion (Leitlinie 3).
Capability-Nachweis und Sandbox-Tests je Operation vor Aktivierung (Kap. 08 §8.4).

## Stand (WP-M2-29)

Port `PspAdapter` (Authorize/Capture/Void/Refund/Status) mit stabiler Kommandoreferenz je Aufruf (`PspCommandRef`), Fehlerklassen und Abgleich (`reconcile*`); HTTP/JSON-Adapter gegen den Simulator des Testnetzes (`tests/sandbox/psp`), Adapter für den gewählten PSP folgt mit D-03. Kein Aufruf in einer offenen Transaktion (`ExternalCallGuard`). Detailvertrag: [psp-adapter.md](../../../docs/umsetzungsplan/vertraege/psp-adapter.md).
