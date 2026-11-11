# Zustandsautomat Mandant

- **Arbeitspaket:** WP-M1-17 (`LH-6.1-SYS-007`); Code: `platform.statemachine.StateMachines.TENANT`; Datenbankregeln: `state_transition_rule` (Automat `tenant`, Version 1) und `harden_state_column`.
- **Zustandsspalte (eigene, nur dieser Automat schreibt sie):** `tenant.status` (CHECK und Übergangsregeln seit `V1170`)
- **Quelle der Zustände:** Kern-DDL (`ACTIVE`, `SUSPENDED`, `CLOSED`)
- **Status der Übergangstabelle:** Entwurf der Umsetzung. Das Pflichtenheft nennt die Zustandsmenge, aber nur teilweise die Übergänge; die Kanten unten sind aus den dokumentierten Pfaden und Fehlerverträgen (Kap. 02 §7.4, Kap. 03 §4.2) abgeleitet und **vor Abnahme fachlich zu bestätigen** (Review Fachanalyse).
- **Regeln:** Übergang nur laut Tabelle, sonst 409 `INVALID_STATE_TRANSITION`; Revision/CAS mit `If-Match` (fehlend 428, veraltet 412 `REVISION_MISMATCH`); je Übergang ein Outbox-Ereignis `tenant.state_changed` ([Schema](../../../../api/events/platform.statechanged.v1.schema.json)); ältere Revision bewirkt keinen Rücksprung. Kein Gleichlauf mit anderen Automaten.
- **Hinweis:** Keine Revisionsspalte (OP-33): die Revision des Ereignisses ist die laufende Nummer der `tenant.state_changed`-Ereignisse. Geändert wird nur von der Plattformverwaltung (die Runtime-Rolle hat kein UPDATE auf `tenant`). `CLOSED` ist endgültig.

| von | erlaubte Folgezustände |
|---|---|
| `ACTIVE` | `SUSPENDED`, `CLOSED` |
| `SUSPENDED` | `ACTIVE`, `CLOSED` |
