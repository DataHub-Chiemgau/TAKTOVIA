# Zustandsautomat Erstattungsfall (`refund_case.state`)

- **Arbeitspaket:** WP-M2-31 (Enabler); Datenbank: Migration `V2310__refund_case.sql` (CHECK der Zustandswerte und Übergangstrigger über `harden_state_column`, Automat `refund_case` in `state_transition_rule`); Code: `backend/modules/fare-payment` (`PaymentCore.requestRefund`, `RefundHandler`).
- **Quelle:** Kap. 04 §4.4 (`refund_case`), Kap. 06 §6.4 („erstattet“ erst nach PSP-Bestätigung, davor „Erstattung beauftragt“), Kap. 02 §7.4 (Erstattung fehlgeschlagen: Buchung bleibt storniert, Erstattung offen). Alle Festlegungen sind **Planungsannahmen** (D-03, D-05 offen) und blockieren nur die Produktivschaltung.
- **Regeln:** Je Zahlung und Anlass (`case_key` = Buchungs-ID beim Storno) höchstens ein Fall; wiederholtes Storno findet den vorhandenen Fall (keine zweite Erstattung). Je Zahlung läuft höchstens **eine** Erstattung beim PSP (Reihenfolge `seq`), die nächste folgt nach Bestätigung oder Ablehnung der vorigen. Eindeutige PSP-Kommandoreferenz (`command_ref`, dieselbe wie in `payment_operation`): jeder Wiederholungsversuch und jeder Abgleich benutzt sie erneut. Die Summe der beauftragten Erstattungen einer Zahlung übersteigt nie `payment_intent.captured_minor` (Datenbankwächter unter der Zeilensperre der Zahlung, auch für parallele Aufträge und die Administration). `payment_intent.refunded_minor` führt nur den **bestätigten** Betrag. Erstattungshöhe: Minimalregel P1 (`RefundRule`, Testregel bis D-05): Kundenstorno 100 %, betreiberverursacht (`BETREIBER`, `FAHRZEUGAUSFALL`: die betreiberverursachte Regel, nie die Kundenstornoregel, BKG-014) 100 %, No-Show 0 %, abgerundet in kleinster Einheit.
- **Anlage:** ist der Einzug beim Storno bereits bestätigt, wird der Fall unmittelbar in `REQUESTED` (Anspruch) oder `NOT_DUE` (kein Anspruch) angelegt (Anlage, kein Übergang); nur beim Storno während eines laufenden Einzugs beginnt er in `PENDING_CAPTURE`.
- **Verhalten bei fehlgeschlagener Erstattung:** der PSP lehnt definitiv ab: Fall `FAILED` (Fehlercode `REFUND_DECLINED`), die Zahlung bleibt `REFUND_PENDING` (der Automat `payment` kennt keinen Rückweg), die Buchung bleibt storniert, der offene Betrag (`requested_minor - refunded_minor`) bleibt in `payment.refund_pending` sichtbar, Ereignis `payment.refund_failed` (Betreiberwarnung). Ein unbestimmtes Ergebnis (Timeout) bleibt `REQUESTED` und wird über die Kommandoreferenz abgeglichen.

Zustände: `PENDING_CAPTURE`, `REQUESTED`, `CONFIRMED`, `FAILED`, `NOT_DUE`, `NOT_REQUIRED`
Anfangszustand: `PENDING_CAPTURE`
Endzustände: `CONFIRMED`, `FAILED`, `NOT_DUE`, `NOT_REQUIRED`

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `REQUESTED` | `CONFIRMED` | PSP bestätigt die Erstattung (verifizierter Status, Statusabfrage), `refunded_minor` steigt | PSP-Ereignis, System | 409 `INVALID_STATE_TRANSITION` |
| `REQUESTED` | `FAILED` | PSP lehnt die Erstattung definitiv ab | System | 409 `INVALID_STATE_TRANSITION` |
| `PENDING_CAPTURE` | `REQUESTED` | Storno während eines laufenden Einzugs: der Einzug ist bestätigt, die Erstattung wird nach Regel beauftragt | System | 409 `INVALID_STATE_TRANSITION` |
| `PENDING_CAPTURE` | `NOT_DUE` | der Einzug ist bestätigt, die Regel ergibt keinen Anspruch | System | 409 `INVALID_STATE_TRANSITION` |
| `PENDING_CAPTURE` | `NOT_REQUIRED` | der Einzug kam nicht zustande (Abgleich: nicht eingezogen, Autorisierung wird aufgehoben, oder aufgehoben) | System | 409 `INVALID_STATE_TRANSITION` |
