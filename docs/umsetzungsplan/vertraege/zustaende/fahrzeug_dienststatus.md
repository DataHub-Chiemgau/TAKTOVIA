# Zustandsautomat Fahrzeug-/Dienststatus (`vehicle_service_status.status`)

- **Arbeitspaket:** WP-M3-01 (Vertrag), Umsetzung WP-M3-31 (Status), Anzeige WP-M3-36. Datenbank: Statusfeld an `staff_shift` bzw. Zusatztabelle `vehicle_status_history` (append-only) aus WP-M3-02.
- **Status:** Vorschlag dieses Plans (LH-6.6-DRV-007, M3 OP-03). Die sechs Zustände des Pflichtenhefts (Verfügbarkeit, Anfahrt, Wartezeit, Pause, Störung, Ende) heißen technisch `AVAILABLE`, `EN_ROUTE`, `WAITING`, `BREAK`, `DISRUPTED`, `ENDED`; `NOT_READY` (kein Bereitstatus ohne Einsatzfreigabe, CHK-001) ist der Anfangszustand.
- **Rollen:** Fahrer = `driver`; Disposition = `dispatcher`; System = Server (abgeleitet aus Fahrerereignissen, DRV-005).
- **Regeln:** `NOT_READY` -> `AVAILABLE` nur mit gültiger Einsatzfreigabe (`assertDispatchable`) und ohne aktive Sperre. Nur `AVAILABLE`, `EN_ROUTE` und `WAITING` sind für neue Zuweisung bei Planerhalt zulässig; `BREAK`, `DISRUPTED`, `ENDED`, `NOT_READY` werden nicht neu disponiert (Originalabnahme DRV-007). Die Wirkung von `BREAK` auf bereits zugesagte Stopps ist fail-closed: Zusagen werden nicht still entzogen, es entsteht ein Klärfall der Leitstelle (Annahme). Ein Statusereignis aus dem Offlinepuffer darf nie eine neuere Sperre oder Freigabe überschreiben.

Zustände: `NOT_READY`, `AVAILABLE`, `EN_ROUTE`, `WAITING`, `BREAK`, `DISRUPTED`, `ENDED`
Anfangszustand: `NOT_READY`
Endzustände: `ENDED`

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `NOT_READY` | `AVAILABLE` | Bereitmeldung nach serverbestätigter Einsatzfreigabe | Fahrer | 409 `INVALID_STATE_TRANSITION` |
| `AVAILABLE` | `EN_ROUTE` | Anfahrt zum ersten Stopp beginnt (Planstart) | Fahrer, System | 409 `INVALID_STATE_TRANSITION` |
| `EN_ROUTE` | `WAITING` | Ereignis `ARRIVAL` am Stopp | Fahrer | 409 `INVALID_STATE_TRANSITION` |
| `WAITING` | `EN_ROUTE` | Einstieg oder No-Show abgeschlossen, nächster Stopp | Fahrer, System | 409 `INVALID_STATE_TRANSITION` |
| `EN_ROUTE` | `AVAILABLE` | letzter Stopp abgearbeitet | System | 409 `INVALID_STATE_TRANSITION` |
| `AVAILABLE` | `BREAK` | Ereignis `BREAK_START` | Fahrer | 409 `INVALID_STATE_TRANSITION` |
| `WAITING` | `BREAK` | Ereignis `BREAK_START` (Klärfall bei zugesagten Stopps) | Fahrer | 409 `INVALID_STATE_TRANSITION` |
| `BREAK` | `AVAILABLE` | Ereignis `BREAK_END` | Fahrer | 409 `INVALID_STATE_TRANSITION` |
| `AVAILABLE` | `DISRUPTED` | Ereignis `DISRUPTION` oder operative Sperre | Fahrer, Disposition | 409 `INVALID_STATE_TRANSITION` |
| `EN_ROUTE` | `DISRUPTED` | Ereignis `DISRUPTION` oder Sicherheitssperre | Fahrer, Disposition, System | 409 `INVALID_STATE_TRANSITION` |
| `WAITING` | `DISRUPTED` | Ereignis `DISRUPTION` oder Sicherheitssperre | Fahrer, Disposition, System | 409 `INVALID_STATE_TRANSITION` |
| `BREAK` | `DISRUPTED` | Sicherheitssperre während der Pause | System, Disposition | 409 `INVALID_STATE_TRANSITION` |
| `DISRUPTED` | `NOT_READY` | Störung beendet, Einsatzfreigabe muss neu bestätigt werden | System, Disposition | 409 `INVALID_STATE_TRANSITION` |
| `AVAILABLE` | `ENDED` | Ereignis `SHIFT_END` | Fahrer, System | 409 `INVALID_STATE_TRANSITION` |
| `BREAK` | `ENDED` | Ereignis `SHIFT_END` | Fahrer, System | 409 `INVALID_STATE_TRANSITION` |
| `DISRUPTED` | `ENDED` | Ereignis `SHIFT_END` | Fahrer, Disposition | 409 `INVALID_STATE_TRANSITION` |
| `NOT_READY` | `ENDED` | Schicht ohne Bereitstatus beendet | Fahrer, System | 409 `INVALID_STATE_TRANSITION` |

## Negativbeispiele

- Bereitmeldung ohne Einsatzfreigabe (Vorbedingung des Übergangs von `NOT_READY` nach `AVAILABLE` fehlt): abgewiesen, 409 `INVALID_STATE_TRANSITION` mit fehlender Voraussetzung im Problem-Detail; kein neuer Hold auf dieses Fahrzeug (CHK-001, AT-26).
- `ENDED` -> `AVAILABLE`: abgewiesen (Endzustand), 409 `INVALID_STATE_TRANSITION`.
- `DISRUPTED` -> `AVAILABLE` ohne neue Freigabe (direkter Rücksprung): abgewiesen, 409 `INVALID_STATE_TRANSITION`.
- Verspätetes Ereignis `BREAK_END` für ein inzwischen gesperrtes Fahrzeug: `REVIEW_REQUIRED`, keine Statusänderung (Kap. 05 §5.5).
