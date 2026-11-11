# Zustandsautomat Operation (Saga-Schritt)

- **Arbeitspaket:** WP-M1-17 (`LH-6.1-SYS-007`); Code: `platform.statemachine.StateMachines.OPERATION`; Datenbankregeln: `state_transition_rule` (Automat `operation`, Version 1) und `harden_state_column`.
- **Zustandsspalte (eigene, nur dieser Automat schreibt sie):** `operation.status` (CHECK und Übergangsregeln seit `V1170`)
- **Quelle der Zustände:** Kap. 02 §7.3 (Operation)
- **Status der Übergangstabelle:** Entwurf der Umsetzung. Das Pflichtenheft nennt die Zustandsmenge, aber nur teilweise die Übergänge; die Kanten unten sind aus den dokumentierten Pfaden und Fehlerverträgen (Kap. 02 §7.4, Kap. 03 §4.2) abgeleitet und **vor Abnahme fachlich zu bestätigen** (Review Fachanalyse).
- **Regeln:** Übergang nur laut Tabelle, sonst 409 `INVALID_STATE_TRANSITION`; Revision/CAS mit `If-Match` (fehlend 428, veraltet 412 `REVISION_MISMATCH`); je Übergang ein Outbox-Ereignis `operation.state_changed` ([Schema](../../../../api/events/platform.statechanged.v1.schema.json)); ältere Revision bewirkt keinen Rücksprung. Kein Gleichlauf mit anderen Automaten.
- **Hinweis:** `RECONCILING` wird nie zu Erfolg oder Fehler verkürzt; es endet nur mit eindeutigem Ergebnis oder in `MANUAL_REVIEW`. `MANUAL_REVIEW -> QUEUED` verarbeitet mit dem Originalschlüssel erneut.

| von | erlaubte Folgezustände |
|---|---|
| `QUEUED` | `RUNNING` |
| `RUNNING` | `SUCCEEDED`, `FAILED`, `RECONCILING`, `MANUAL_REVIEW`, `QUEUED` |
| `RECONCILING` | `SUCCEEDED`, `FAILED`, `MANUAL_REVIEW` |
| `MANUAL_REVIEW` | `SUCCEEDED`, `FAILED`, `QUEUED`, `RECONCILING` |
