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

## Umsetzungsentscheidungen WP-M2-30 (Planungsannahmen, D-03/D-05 offen)

Alle Festlegungen sind Annahmen dieses Plans und blockieren nur die Produktivschaltung; sie bleiben bis zur Bestätigung durch Fachanalyse und Auftraggeber (D-12) offen. Code: `backend/modules/fare-payment` (`Payments`, `PaymentCore`, Handler), Migration `V2300__zahlungsautomat.sql`.

- **OP-3/OP-5 (Reihenfolge):** die Zahlung beginnt für eine Buchung im Zustand `CONFIRMING` (die Journey-Hülle entstand bei der Bestätigung des Holds, `payment_intent.journey_id` bleibt Pflicht). `AUTHORIZED` ist Voraussetzung für `CONFIRMED` (Kopplung durch die Anwendung, `PaymentBookingCoupling`: Fachoperation `Bookings.transition(PAYMENT_AUTHORIZED)`); erst danach wird der Einzug angestoßen (`AUTHORIZED` -> `CAPTURE_PENDING`, Operation `CAPTURE_PAYMENT`). Die Zahlungsfrist ist die Hold-Dauer (`taktovia.payments.authorization-window-seconds`, 180 s) ab Bestätigung des Holds (`payment_intent.authorize_by`).
- **Kein gemeinsames Zustandsfeld:** das Zahlungsmodul schreibt nie eine Buchung und liest ihren Zustand nicht; die Buchung kennt keinen Zahlstatus. `payment_intent.booking_id`/`service_id` sind Verweise. Die Kopplung läuft über die Aufgabenliste `Payments.couplingTasksIn` und die Ereignisse `payment.authorized`, `payment.authorization_failed`, `payment.captured`, `payment.outcome_unknown`.
- **Autorisierungs-Timeout (Lücke in Kap. 03 §4.2):** der Automat kennt keinen Zustand „Autorisierung unbestimmt“. Der Zustand bleibt `CREATED`; ein Marker (`failure_code = AUTHORIZE_OUTCOME_UNKNOWN`) und die Operation `AUTHORIZE_PAYMENT` im Zustand `RECONCILING` machen ihn unbestimmt; die UI-Projektion ist `UNKNOWN`. Abgleich über die Händlerreferenz (`merchant_ref`, die PSP-Referenz ist lokal noch unbekannt) mit **derselben** Kommandoreferenz; nie ein zweiter Charge.
- **Einzug abgelehnt (Capture fehlgeschlagen):** der Automat kennt keinen Fehlerzustand für den Einzug; eine definitive Ablehnung wird als Klärfall geführt (`CAPTURE_PENDING` -> `CAPTURE_UNKNOWN` -> `RECONCILING`, `failure_code = CAPTURE_DECLINED`, Operation `FAILED`); die Buchung bleibt unberührt (bestätigt), die Zahlung gilt weder als bezahlt noch als gescheitert. Ein Timeout bleibt `CAPTURE_UNKNOWN` und wird per Statusabfrage abgeglichen (`RECONCILING` -> `CAPTURED`, `AUTHORIZED` oder `VOID_PENDING`).
- **Autorisierung abgelehnt:** `AUTHORIZATION_FAILED`; die Anwendung storniert die Buchung mit Grund `PAYMENT_FAILED` (Hold und Kapazität werden frei, es wird nie eine Buchung bestätigt). Ein anderer Zahlungsweg läuft über einen neuen Checkout (neues Angebot, neuer Hold) oder, solange die Buchung `CONFIRMING` ist, über eine neue Zahlung (der Teilindex erlaubt eine neue Zahlung erst nach `AUTHORIZATION_FAILED`/`VOIDED`).
- **SCA länger als der Hold / aufzuhebende Autorisierung:** trifft die Autorisierung nach `authorize_by`, mit abweichendem Betrag oder für eine freigegebene Buchung ein, wird sie nie bestätigt: `AUTHORIZED` -> `VOID_PENDING` (Operation `VOID_PAYMENT`) -> `VOIDED`, Fehlercode `AUTHORIZATION_EXPIRED`, `AMOUNT_MISMATCH` bzw. `BOOKING_RELEASED`; die Anwendung gibt die Buchung frei. Die Browser-Rückkehr (`POST /v1/bookings/{id}/payment/return`) und PSP-Ereignisse stellen nur einen Abgleich (Statusabfrage) ein; der Übergang folgt allein dem verifizierten PSP-Stand.
- **Operationen:** `AUTHORIZE_PAYMENT`, `CAPTURE_PAYMENT`, `VOID_PAYMENT`, `REFUND_PAYMENT`, `RECONCILE_PAYMENT` (Tabelle `payment_operation` mit stabiler Kommandoreferenz je PSP-Aufruf). Der PSP wird nie in einer SQL-Transaktion aufgerufen; die Wirkung und der gefencte Abschluss der Operation sind eine Transaktion. Zwei Worker auf derselben Operation: der Fencing-Token lässt nur einen schreiben, der PSP erkennt den zweiten Aufruf an der Kommandoreferenz.
- **Lesen:** Zahlungsblock `payment` in `GET /v1/bookings/{id}` (UI-Projektion nach Kap. 05 §5.9); Fahrgäste lesen den Zahlstatus dort, `GET /v1/operations/{id}` ist Betriebsrollen mit `operations:read` vorbehalten (Rechtematrix, `passenger` hat kein `operations:read`).
