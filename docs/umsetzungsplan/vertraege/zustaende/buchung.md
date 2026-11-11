# Zustandsautomat Buchung (Journey-Buchung)

- **Arbeitspaket:** WP-M1-17 (`LH-6.1-SYS-007`); Code: `platform.statemachine.StateMachines.BOOKING`; Datenbankregeln: `state_transition_rule` (Automat `booking`, Version 1) und `harden_state_column`.
- **Zustandsspalte (eigene, nur dieser Automat schreibt sie):** `booking.state` (Anwendung in M2; Segment-Buchung `journey_leg.state` folgt mit M2/M6)
- **Quelle der Zustände:** Kap. 02 §7.3 (Journey-Buchung)
- **Status der Übergangstabelle:** Entwurf der Umsetzung. Das Pflichtenheft nennt die Zustandsmenge, aber nur teilweise die Übergänge; die Kanten unten sind aus den dokumentierten Pfaden und Fehlerverträgen (Kap. 02 §7.4, Kap. 03 §4.2) abgeleitet und **vor Abnahme fachlich zu bestätigen** (Review Fachanalyse).
- **Regeln:** Übergang nur laut Tabelle, sonst 409 `INVALID_STATE_TRANSITION`; Revision/CAS mit `If-Match` (fehlend 428, veraltet 412 `REVISION_MISMATCH`); je Übergang ein Outbox-Ereignis `booking.state_changed` ([Schema](../../../../api/events/platform.statechanged.v1.schema.json)); ältere Revision bewirkt keinen Rücksprung. Kein Gleichlauf mit anderen Automaten.
- **Hinweis:** `CONFIRMED` erst nach bestätigten buchbaren Segmenten und erfüllter Zahlungsvoraussetzung; diese Wächter sind M2. `RECONCILING` verlässt nur ein eindeutiger Partnerstatus. `CANCEL_PENDING -> RECONCILING` deckt „Storno B unklar“ ab.

| von | erlaubte Folgezustände |
|---|---|
| `HOLDING` | `HELD`, `COMPENSATING`, `FAILED` |
| `HELD` | `ACTION_REQUIRED`, `CONFIRMING`, `COMPENSATING`, `FAILED` |
| `ACTION_REQUIRED` | `CONFIRMING`, `COMPENSATING`, `FAILED` |
| `CONFIRMING` | `CONFIRMED`, `PARTIAL`, `RECONCILING`, `COMPENSATING`, `FAILED` |
| `RECONCILING` | `CONFIRMED`, `PARTIAL`, `COMPENSATING`, `FAILED` |
| `PARTIAL` | `COMPENSATING`, `CANCEL_PENDING`, `COMPLETED` |
| `COMPENSATING` | `FAILED`, `CANCELED` |
| `CONFIRMED` | `CANCEL_PENDING`, `COMPLETED` |
| `CANCEL_PENDING` | `CANCELED`, `RECONCILING` |
