# Sandbox-Partner (WP-M0-24)

Deterministische Sandbox-Partner A und B sowie ein nur auskunftsfähiger Partner C (lokale HTTP-Server, nur Standardbibliothek). **Nur Testinfrastruktur**, ausschließlich
synthetische Daten; Ergebnisse sind Sandbox-Ergebnisse, kein Nachweis gegen reale Partner. Schlüssel werden zur Laufzeit aus dem Seed abgeleitet und liegen nicht im Repository.

- **Start:** `python tests/sandbox/partner/partner_sandbox.py --instances A,B,C --base-port 18100 --seed 1` (A=18100, B=18101, C=18102)
- **Test:** `python -m unittest discover -s tests/sandbox/partner -p 'test_*.py'`
- **Verträge:** [`openapi/partner-sandbox.yaml`](openapi/partner-sandbox.yaml) (Entwurf „Partner intern“, als Ergänzung nach `api/` zu übernehmen),
  [`schemas/capability-matrix.schema.json`](schemas/capability-matrix.schema.json), [`schemas/partner-event.schema.json`](schemas/partner-event.schema.json), [`schemas/hold-state-machine.json`](schemas/hold-state-machine.json).

## Instanzen

| Instanz | Zweck | Besonderheit |
|---|---|---|
| A | Vollständig (Hold, Verlängerung, Confirm, Dedup, Status, Storno, Refund, Ticket, Echtzeit) | Dienst `SVC-A-LAST` mit genau einem Sitz (Konkurrenztest), `SVC-A-NOEXT` ohne Holdverlängerung und Refund (Capability je Dienst) |
| B | Proprietäre API | keine Holdverlängerung, kein Refund, kein Ticket; `SVC-B-RAIL` ohne bindende Preise |
| C | Nur Auskunft (PH-AT-006) | kein Hold, kein Confirm, kein Status, kein Storno |

Jeder Aufruf trägt die Maschinenidentität in `X-Caller-Id` (Sandbox-Vereinfachung); fremde Aufrufer erhalten keine Existenzdetails (404). Hold-Zustände `HELD`, `CONSUMED`, `RELEASED`, `EXPIRED`
(Ablauf bei Sandboxuhr `now >= expires_at`); Planungsdefault Hold-TTL 180 s nur als Testwert.

## Steuerung (nur Testnetz)

| Aufruf | Zweck |
|---|---|
| `POST /control/faults` `{operation, ref, mode, times}` | `timeout`, `lost_response` (z. B. „Confirm persistiert, Antwort verworfen“), `reject` (definitive Ablehnung), `unavailable` je Operation und Referenz |
| `POST /control/config` | `latency_ms`, `dedup_enabled`, `status_enabled` (abgeschaltete Statusfähigkeit), `capabilities` (Merkmal je Dienst überschreiben) |
| `POST /control/clock` | Testzeit-Protokoll v1 |
| `POST /control/events/emit` | Partnerereignis erzeugen und senden (`target_url`) oder nur signieren (`dry_run`): Modi `valid`, `duplicate` (bis 100), `same_id_changed_payload`, `out_of_order`, `version_gap`, `rotation`, `bad_signature`, `unknown_key_id`, `stale_timestamp`, `future_timestamp`, `oversize`, `tenant_mismatch`, `disallowed_type` |
| `GET /control/keys`, `/control/log`, `/control/events` | abgeleitete Testschlüssel (nur lokal), Protokoll, erzeugte Ereignisse |

Signatur der Ereignisse: `X-Partner-Key-Id`, `X-Partner-Timestamp`, `X-Partner-Signature: v1=hex(HMAC-SHA256(secret, timestamp + "." + raw_body))` (Kap. 05 §5.6). Die Testbibliothek enthält einen
Referenzprüfer ([`../../lib/taktovia_testkit/partner_signature.py`](../../lib/taktovia_testkit/partner_signature.py)), mit dem die Tests belegen, dass jeder fehlerhafte Modus abgelehnt werden muss und die Rotation zwei gültige Signaturen erzeugt.
Als Planungswert gilt die maximale Körpergröße 64 KiB; die produktive Grenze legt der Detailvertrag fest.

## Offene Punkte

Inbox-Schlüssel `(partner_id, event_id)` plus Payloadhash weicht von der Tabelle `inbox` ab (Offene Punkte 12); der Referenzprüfer folgt Kap. 05 §5.6. TOMP-/OSDM-Abbildung folgt nach D-08 in M6.
