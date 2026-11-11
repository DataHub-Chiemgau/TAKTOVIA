# Zustandsautomat lokale Buchung (`booking.state`, Einbetreiberbuchung M2)

- **Arbeitspaket:** WP-M2-01 (Vorschlag), Anwendung in WP-M2-23; Code: derselbe Automat `booking` (Version 1) wie in [buchung.md](buchung.md), `StateMachines.BOOKING`. M2 nutzt nur eine Teilmenge der Kanten; weitere Kanten (`HOLDING`, `HELD`, `ACTION_REQUIRED`, `PARTIAL`) gehören der Journey-Buchung (M6).
- **Status:** Vorschlag dieses Plans (M2 OP-4, OP-5): das Pflichtenheft definiert `booking.state` für die lokale Buchung nicht ausdrücklich; zu bestätigen durch Fachanalyse.
- **Entstehung:** Die Zeile entsteht beim Confirm in derselben Transaktion, die den Hold von `HELD` nach `CONSUMED` setzt (Kap. 01 §1.4 Schritt 6), mit Anfangszustand `CONFIRMING`. Wiederholte Bestätigung findet dieselbe Buchung (Idempotenz). Der Wartezustand für Kartenfreigabe (SCA) liegt im Zahlungsautomaten (`REQUIRES_ACTION`), nicht in `booking.state`.
- **Regeln:** Übergang nur laut Tabelle, sonst `409 INVALID_STATE_TRANSITION`; `If-Match` auf `booking.revision` (428/412); je Übergang ein Ereignis `booking.state_changed`. `CONFIRMED` erst mit erfüllter Zahlungsvoraussetzung (Zahlung `AUTHORIZED` bzw. tariflich kostenfrei). Unbestimmtes (PSP-Ergebnis unbekannt) ist `RECONCILING`, nie Erfolg oder Fehler (Leitlinie 6).

Zustände: `CONFIRMING`, `CONFIRMED`, `RECONCILING`, `COMPENSATING`, `CANCEL_PENDING`, `CANCELED`, `COMPLETED`, `FAILED`
Anfangszustand: `CONFIRMING`
Endzustände: `CANCELED`, `COMPLETED`, `FAILED`

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `CONFIRMING` | `CONFIRMED` | Zahlungsvoraussetzung erfüllt (verifizierter PSP-Status `AUTHORIZED` oder kostenfrei) | System (Confirm-Befehl, PSP-Ereignis) | 409 `INVALID_STATE_TRANSITION` |
| `CONFIRMING` | `RECONCILING` | Zahlungsergebnis unbestimmt (Timeout) | System | 409 `INVALID_STATE_TRANSITION` |
| `CONFIRMING` | `COMPENSATING` | Zahlung abgelehnt oder Hold nach Commit unbrauchbar | System | 409 `INVALID_STATE_TRANSITION` |
| `CONFIRMING` | `FAILED` | Bestätigung determiniert gescheitert, nichts zu kompensieren | System | 409 `INVALID_STATE_TRANSITION` |
| `RECONCILING` | `CONFIRMED` | Abgleich: Zahlung eindeutig autorisiert | System (Abgleichsjob) | 409 `INVALID_STATE_TRANSITION` |
| `RECONCILING` | `COMPENSATING` | Abgleich: Zahlung eindeutig gescheitert | System | 409 `INVALID_STATE_TRANSITION` |
| `RECONCILING` | `FAILED` | Abgleich: nichts zu kompensieren | System | 409 `INVALID_STATE_TRANSITION` |
| `COMPENSATING` | `CANCELED` | Kapazität freigegeben, Zahlung zurückgenommen | System | 409 `INVALID_STATE_TRANSITION` |
| `COMPENSATING` | `FAILED` | Kompensation endgültig gescheitert (sichtbar für Betreiber) | System | 409 `INVALID_STATE_TRANSITION` |
| `CONFIRMED` | `CANCEL_PENDING` | Storno bestätigt (Fahrgast, Betreiber) | Fahrgast, Disponent | 409 `INVALID_STATE_TRANSITION` |
| `CONFIRMED` | `COMPLETED` | Fahrt abgeschlossen (M3) | System | 409 `INVALID_STATE_TRANSITION` |
| `CANCEL_PENDING` | `CANCELED` | Kapazität freigegeben, Erstattung angestoßen | System | 409 `INVALID_STATE_TRANSITION` |
| `CANCEL_PENDING` | `RECONCILING` | Stornowirkung unbestimmt | System | 409 `INVALID_STATE_TRANSITION` |
