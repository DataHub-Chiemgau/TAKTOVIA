# Zustandsautomat Zahlung

- **Arbeitspaket:** WP-M1-17 (`LH-6.1-SYS-007`); Code: `platform.statemachine.StateMachines.PAYMENT`; Datenbankregeln: `state_transition_rule` (Automat `payment`, Version 1) und `harden_state_column`.
- **Zustandsspalte (eigene, nur dieser Automat schreibt sie):** `payment_intent.state` (Anwendung in M2)
- **Quelle der Zustände:** Kap. 03 §4.2
- **Status der Übergangstabelle:** Entwurf der Umsetzung. Das Pflichtenheft nennt die Zustandsmenge, aber nur teilweise die Übergänge; die Kanten unten sind aus den dokumentierten Pfaden und Fehlerverträgen (Kap. 02 §7.4, Kap. 03 §4.2) abgeleitet und **vor Abnahme fachlich zu bestätigen** (Review Fachanalyse).
- **Regeln:** Übergang nur laut Tabelle, sonst 409 `INVALID_STATE_TRANSITION`; Revision/CAS mit `If-Match` (fehlend 428, veraltet 412 `REVISION_MISMATCH`); je Übergang ein Outbox-Ereignis `payment.state_changed` ([Schema](../../../../api/events/platform.statechanged.v1.schema.json)); ältere Revision bewirkt keinen Rücksprung. Kein Gleichlauf mit anderen Automaten.
- **Hinweis:** `DISPUTED` ist ein eigener Streitstatus und nicht Teil dieses Automaten. `CAPTURE_UNKNOWN -> RECONCILING`: Timeout ist nie Ablehnung; erst die Statusabfrage entscheidet (`CAPTURED`, zurück nach `AUTHORIZED` oder `VOID_PENDING`). Kein zweiter Capture auf Verdacht.

| von | erlaubte Folgezustände |
|---|---|
| `CREATED` | `REQUIRES_ACTION`, `AUTHORIZED`, `AUTHORIZATION_FAILED` |
| `REQUIRES_ACTION` | `AUTHORIZED`, `AUTHORIZATION_FAILED` |
| `AUTHORIZED` | `CAPTURE_PENDING`, `VOID_PENDING` |
| `CAPTURE_PENDING` | `CAPTURED`, `CAPTURE_UNKNOWN` |
| `CAPTURE_UNKNOWN` | `RECONCILING` |
| `RECONCILING` | `CAPTURED`, `AUTHORIZED`, `VOID_PENDING` |
| `VOID_PENDING` | `VOIDED` |
| `CAPTURED` | `REFUND_PENDING` |
| `REFUND_PENDING` | `PARTIALLY_REFUNDED`, `REFUNDED` |
| `PARTIALLY_REFUNDED` | `REFUND_PENDING` |
