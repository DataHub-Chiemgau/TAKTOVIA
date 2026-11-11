# Zustandsautomaten Nachricht und Zustellversuch (`notification`, `notification_attempt`)

- **Arbeitspaket:** WP-M2-01 (Vorschlag), Umsetzung WP-M2-32 (`LH-6.12-COM-003`).
- **Status:** Vorschlag dieses Plans (M2 OP-28: Retry-Intervalle und Höchstzahl sind im Pflichtenheft für Nachrichten nicht festgelegt; Annahme: Backoff 1 s, 5 s, 30 s, 2 min, 10 min, höchstens 6 Versuche, in Anlehnung an Kap. 05 §5.6).
- **Regeln:** Eine fachliche Nachricht je (Ereignis, Empfänger, Kanal). Dieselbe Nachrichten-ID geht an alle Versuche und an den Provider. Ein Timeout ist `UNKNOWN`, nie `FAILED`; der Abgleich fragt den Providerstatus ab, bevor wiederholt wird. Retry und Zustellstatus ändern nie eine Buchungszusage. Inhalt und Kontaktdaten gelangen nicht in Logs.

## Nachricht (Auftrag)

Zustände: `QUEUED`, `SENDING`, `RETRY_WAIT`, `UNKNOWN`, `DELIVERED`, `FAILED`, `SUPPRESSED`
Anfangszustand: `QUEUED`
Endzustände: `DELIVERED`, `FAILED`, `SUPPRESSED`

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `QUEUED` | `SENDING` | Worker nimmt Auftrag an (Lease) | System | 409 `INVALID_STATE_TRANSITION` |
| `QUEUED` | `SUPPRESSED` | Kanal ohne verifizierten Kontakt oder Widerspruch | System | 409 `INVALID_STATE_TRANSITION` |
| `SENDING` | `DELIVERED` | Provider bestätigt Annahme eindeutig | System | 409 `INVALID_STATE_TRANSITION` |
| `SENDING` | `RETRY_WAIT` | Provider lehnt vorübergehend ab (nachweislich nicht zugestellt) | System | 409 `INVALID_STATE_TRANSITION` |
| `SENDING` | `SUPPRESSED` | beim Versand kein verifizierter Kontakt (Ergänzung WP-M2-32: der Kontakt wird erst nach dem Beanspruchen aufgelöst) | System | 409 `INVALID_STATE_TRANSITION` |
| `SENDING` | `UNKNOWN` | Timeout, Ergebnis unbestimmt | System | 409 `INVALID_STATE_TRANSITION` |
| `SENDING` | `FAILED` | dauerhafter Fehler oder ungültiger Kontakt | System | 409 `INVALID_STATE_TRANSITION` |
| `UNKNOWN` | `DELIVERED` | Abgleich: zugestellt | System | 409 `INVALID_STATE_TRANSITION` |
| `UNKNOWN` | `RETRY_WAIT` | Abgleich: nachweislich nicht zugestellt | System | 409 `INVALID_STATE_TRANSITION` |
| `UNKNOWN` | `FAILED` | Abgleich nicht möglich, Versuche erschöpft (Betreiberwarnung) | System | 409 `INVALID_STATE_TRANSITION` |
| `RETRY_WAIT` | `SENDING` | Backoff abgelaufen, Versuchszahl nicht erschöpft | System | 409 `INVALID_STATE_TRANSITION` |
| `RETRY_WAIT` | `FAILED` | Versuche erschöpft (Dead-Letter, Betreiberwarnung) | System | 409 `INVALID_STATE_TRANSITION` |

## Zustellversuch

Zustände: `STARTED`, `ACCEPTED`, `REJECTED`, `TIMED_OUT`
Anfangszustand: `STARTED`
Endzustände: `ACCEPTED`, `REJECTED`, `TIMED_OUT`

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `STARTED` | `ACCEPTED` | Provider nimmt an | System | 409 `INVALID_STATE_TRANSITION` |
| `STARTED` | `REJECTED` | Provider lehnt ab | System | 409 `INVALID_STATE_TRANSITION` |
| `STARTED` | `TIMED_OUT` | keine Antwort in der Frist | System | 409 `INVALID_STATE_TRANSITION` |
