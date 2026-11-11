# PSP-Simulator (WP-M0-23)

Deterministischer Zahlungsanbieter-Simulator als lokaler HTTP-Server (nur Python-Standardbibliothek). **Nur Testinfrastruktur**; Ergebnisse daraus sind
Simulator-Ergebnisse, kein Nachweis gegen einen realen PSP (das leistet WP-M0-29 mit einer echten Sandbox).

- **Start:** `python tests/sandbox/psp/psp_sim.py --port 18091 --seed 1 --clock-start 2026-01-01T00:00:00Z`
- **Test:** `python -m unittest discover -s tests/sandbox/psp -p 'test_*.py'`
- **Verträge:** [`openapi/psp-sim.yaml`](openapi/psp-sim.yaml) (Port-Entwurf und Steuerschnittstelle), [`schemas/state-machine.json`](schemas/state-machine.json) (Zustandsautomat nach Kap. 03 §4.2),
  [`schemas/provider-event.schema.json`](schemas/provider-event.schema.json) (Benachrichtigung für `/v1/payments/provider-events/{provider}`).
- **Compose-Profil:** [`../compose.testnet.yaml`](../compose.testnet.yaml), nicht ausgeführt (kein Docker-Daemon).

## Operationen und Zustände

Authorize, Capture (Teilbetrag), Void, Refund (Teilbetrag), Statusabfrage nach `payment_id`, `provider_ref` oder ursprünglicher Händlerreferenz. Zustände:
`CREATED`, `REQUIRES_ACTION`, `AUTHORIZED`, `CAPTURE_PENDING`, `CAPTURED`, `AUTHORIZATION_FAILED`, `VOID_PENDING`, `VOIDED`, `REFUND_PENDING`, `PARTIALLY_REFUNDED`, `REFUNDED`;
`DISPUTED` ist das getrennte Feld `dispute_state`. `CAPTURE_UNKNOWN` und `RECONCILING` entstehen auf der Aufruferseite; der Simulator macht sie über die Fehlermodi provozierbar.

Geldinvarianten: Beträge sind Ganzzahlen (Gleitkomma, Text, Null und negative Werte werden mit 400 abgewiesen), erstattet ≤ erfasst ≤ autorisiert,
gleicher `Idempotency-Key` mit gleichem Request liefert die gespeicherte Antwort (Header `Idempotent-Replayed`), mit anderem Request 422. Eine zweite Autorisierung zur selben Händlerreferenz wird mit
409 abgewiesen (kein zweiter Charge auf Verdacht).

## Steuerung (nur Testnetz)

| Aufruf | Zweck |
|---|---|
| `POST /control/faults` `{operation, ref, mode, times, hold_seconds}` | `timeout` (Wirkung nicht angewandt, keine Antwort), `lost_response` (Wirkung vorhanden, Antwort verworfen), `duplicate` (Anfrage trifft zweimal ein), `decline`, `unavailable` (503) je Operation (`authorize`, `capture`, `void`, `refund`, `status`) und Referenz |
| `POST /control/clock` | Testzeit-Protokoll v1 ([Vereinbarung](../../lib/taktovia_testkit/testclock.py)): `set`, `advance_seconds`, `mode` |
| `POST /control/config` | `webhook_url`, `webhook_auto`, `settle_delay_s` (asynchroner Capture/Void/Refund über die Testuhr), `sca_action_ttl_s` |
| `POST /control/webhooks/deliver` | Benachrichtigung bis zu 100-mal `valid`, `late` oder `bad_signature` zustellen |
| `POST /control/sca/{id}/complete`, `POST /control/disputes` | SCA-Rückkehr (auch verzögert), Streitfall nach Capture |
| `GET /control/log`, `GET /control/events` | Protokoll aller Aufrufe (Referenzen, Fehlermodus, Wirkung, Wiederholung) und Zustellungen |

Zahlungsmittel für Tests: `pm_ok`, `pm_decline`, `pm_sca`. Die Webhook-Signatur `t=<epoch>,v1=<hex HMAC-SHA256>` verwendet einen aus dem Seed abgeleiteten Testschlüssel (`GET /control/config`, nur lokal); es liegt kein Schlüssel im Repository.
Der Simulator bindet nur Loopback oder private Adressen; `0.0.0.0` nur mit `TAKTOVIA_TESTNET=1`.

## Grenzen (bewusste Differenzen)

Keine Mehrfach-Captures, kein Void nach Teil-Capture, kein Transfer/Payout/Split (M6, Offene Punkte 30), keine Anbieterreferenzen realer PSP, Webhook-Wiederholung nur auf Abruf (kein Retry-Zeitplan).
