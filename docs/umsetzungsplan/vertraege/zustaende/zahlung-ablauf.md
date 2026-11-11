# Zahlungsautomat mit Auslöser und Akteur (Ergänzung zu [zahlung.md](zahlung.md))

- **Arbeitspaket:** WP-M2-01; Umsetzung WP-M2-29/-31. Die Zustandsmenge und die Kanten sind identisch mit `StateMachines.PAYMENT` (Kap. 03 §4.2, WP-M1-17); diese Datei ergänzt Auslöser, Akteur und Fehlercode je Kante.
- **Regeln:** Ein verifizierter PSP-Status, nicht die Browser-Rückkehr, löst Übergänge aus (Kap. 05 §5.3). `CAPTURE_UNKNOWN` verlässt nur der Statusabgleich (`RECONCILING`); kein zweiter Capture auf Verdacht (Leitlinie 6). PSP-Aufrufe laufen nie innerhalb einer offenen SQL-Transaktion (Leitlinie 3). Der Automat ist vom Buchungsstatus unabhängig.

Zustände: `CREATED`, `REQUIRES_ACTION`, `AUTHORIZED`, `AUTHORIZATION_FAILED`, `CAPTURE_PENDING`, `CAPTURE_UNKNOWN`, `RECONCILING`, `CAPTURED`, `VOID_PENDING`, `VOIDED`, `REFUND_PENDING`, `PARTIALLY_REFUNDED`, `REFUNDED`
Anfangszustand: `CREATED`
Endzustände: `AUTHORIZATION_FAILED`, `VOIDED`, `REFUNDED`

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `CREATED` | `REQUIRES_ACTION` | PSP verlangt SCA/3-D Secure | PSP-Ereignis | 409 `INVALID_STATE_TRANSITION` |
| `CREATED` | `AUTHORIZED` | Autorisierung ohne Aktion | PSP-Ereignis | 409 `INVALID_STATE_TRANSITION` |
| `CREATED` | `AUTHORIZATION_FAILED` | Ablehnung | PSP-Ereignis | 409 `INVALID_STATE_TRANSITION` |
| `REQUIRES_ACTION` | `AUTHORIZED` | SCA erfolgreich (verifizierter Status) | PSP-Ereignis | 409 `INVALID_STATE_TRANSITION` |
| `REQUIRES_ACTION` | `AUTHORIZATION_FAILED` | SCA gescheitert oder abgelaufen | PSP-Ereignis | 409 `INVALID_STATE_TRANSITION` |
| `AUTHORIZED` | `CAPTURE_PENDING` | Capture-Befehl (Zeitpunkt laut Zahlungsplan, M2 OP-27) | System | 409 `INVALID_STATE_TRANSITION` |
| `AUTHORIZED` | `VOID_PENDING` | Buchung scheitert oder Hold abgelaufen | System | 409 `INVALID_STATE_TRANSITION` |
| `CAPTURE_PENDING` | `CAPTURED` | PSP bestätigt Einzug | PSP-Ereignis | 409 `INVALID_STATE_TRANSITION` |
| `CAPTURE_PENDING` | `CAPTURE_UNKNOWN` | Timeout, Ergebnis unbestimmt | System | 409 `INVALID_STATE_TRANSITION` |
| `CAPTURE_UNKNOWN` | `RECONCILING` | Statusabfrage beim PSP gestartet | System | 409 `INVALID_STATE_TRANSITION` |
| `RECONCILING` | `CAPTURED` | Abgleich: eingezogen | System | 409 `INVALID_STATE_TRANSITION` |
| `RECONCILING` | `AUTHORIZED` | Abgleich: nicht eingezogen, Autorisierung gültig | System | 409 `INVALID_STATE_TRANSITION` |
| `RECONCILING` | `VOID_PENDING` | Abgleich: nicht eingezogen, Autorisierung aufzuheben | System | 409 `INVALID_STATE_TRANSITION` |
| `VOID_PENDING` | `VOIDED` | PSP bestätigt Aufhebung | PSP-Ereignis | 409 `INVALID_STATE_TRANSITION` |
| `CAPTURED` | `REFUND_PENDING` | Storno mit Erstattung | System | 409 `INVALID_STATE_TRANSITION` |
| `REFUND_PENDING` | `PARTIALLY_REFUNDED` | Teilerstattung bestätigt | PSP-Ereignis | 409 `INVALID_STATE_TRANSITION` |
| `REFUND_PENDING` | `REFUNDED` | Vollerstattung bestätigt | PSP-Ereignis | 409 `INVALID_STATE_TRANSITION` |
| `PARTIALLY_REFUNDED` | `REFUND_PENDING` | weitere Erstattung | System | 409 `INVALID_STATE_TRANSITION` |
